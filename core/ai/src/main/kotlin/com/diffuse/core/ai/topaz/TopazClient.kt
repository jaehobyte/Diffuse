package com.diffuse.core.ai.topaz

import com.diffuse.core.ai.gemini.await
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Logger
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * specs/topaz_enhancement.md §4. One method per route, each one request, no retry — retrying is
 * the provider's decision, made per route (a submit is never repeated).
 *
 * Every call takes the [TopazConfig] it runs under rather than reading a source, so a run's
 * polling, download and cancel all use the key that started it.
 *
 * The key goes in `X-API-Key` on requests to [TopazConfig.baseUrl] only. Redirects are not
 * followed on those — OkHttp strips `Authorization` across hosts, but not a custom header — and
 * the signed download URL is fetched with no key at all.
 */
internal class TopazClient(
    private val dispatchers: DispatcherProvider,
    okHttp: OkHttpClient,
    private val logger: Logger? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val apiHttp: OkHttpClient = okHttp.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(API_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /** Large files over a slow link: the read timeout is per read, not per download. */
    private val downloadHttp: OkHttpClient = okHttp.newBuilder()
        .readTimeout(DOWNLOAD_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    suspend fun submit(
        config: TopazConfig,
        endpoint: TopazEndpoint,
        fields: List<Pair<String, String>>,
        image: File,
        mimeType: String,
    ): TopazOutcome<String> = withContext(dispatchers.io) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("image", image.name, image.asRequestBody(mimeType.toMediaType()))
            fields.forEach { (name, value) -> addFormDataPart(name, value) }
        }.build()
        val request = authed(config, endpoint.submitPath).post(body).build()
        try {
            apiHttp.newCall(request).await().use { response ->
                parse(response, AsyncResponseDto.serializer())
            }.let { outcome ->
                when (outcome) {
                    is TopazOutcome.Success -> TopazOutcome.Success(outcome.value.processId)
                    is TopazOutcome.Failure -> outcome
                }
            }
        } catch (e: IOException) {
            // §4: a request that never reached the host is offline; one that may have reached it
            // is "unknown", because only Topaz knows whether a billable job now exists.
            logger?.warn(TAG, "POST ${endpoint.submitPath} failed", e)
            TopazOutcome.Failure(if (neverSent(e)) TopazFailure.Offline else TopazFailure.SubmitUnknown)
        }
    }

    suspend fun status(config: TopazConfig, processId: String): TopazOutcome<StatusDto> =
        get(config, "/status/$processId", StatusDto.serializer())

    suspend fun downloadUrl(config: TopazConfig, processId: String): TopazOutcome<String> =
        when (val outcome = get(config, "/download/$processId", DownloadDto.serializer())) {
            is TopazOutcome.Success -> TopazOutcome.Success(outcome.value.downloadUrl)
            is TopazOutcome.Failure -> outcome
        }

    suspend fun estimate(
        config: TopazConfig,
        endpoint: TopazEndpoint,
        fields: List<Pair<String, String>>,
    ): TopazOutcome<EstimateDto> = withContext(dispatchers.io) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (name, value) -> addFormDataPart(name, value) }
        }.build()
        val request = authed(config, endpoint.estimatePath).post(body).build()
        call(request) { parse(it, EstimateDto.serializer()) }
    }

    /** `DELETE /cancel/{id}`: 204 is cancelled; 409 is "already finished or not cancellable". */
    suspend fun cancel(config: TopazConfig, processId: String): TopazOutcome<Unit> =
        withContext(dispatchers.io) {
            val request = authed(config, "/cancel/$processId").delete().build()
            call(request) { response ->
                if (response.isSuccessful) TopazOutcome.Success(Unit) else failureOf(response)
            }
        }

    /**
     * Streams [url] into [target] — never into memory — stopping at [maxBytes] whatever the
     * `Content-Length` claims. No key header: the URL is signed and on another host.
     */
    suspend fun download(url: String, target: File, maxBytes: Long): TopazOutcome<Long> =
        withContext(dispatchers.io) {
            val request = try {
                Request.Builder().url(url).get().build()
            } catch (_: IllegalArgumentException) {
                return@withContext TopazOutcome.Failure(TopazFailure.BadResponse)
            }
            if (request.url.scheme != "https" && !allowsPlainHttp(request)) {
                return@withContext TopazOutcome.Failure(TopazFailure.BadResponse)
            }
            call(request, client = downloadHttp) { response -> stream(response, target, maxBytes) }
        }

    private fun stream(response: Response, target: File, maxBytes: Long): TopazOutcome<Long> {
        val body = response.body
        val declared = body?.contentLength() ?: -1
        val refusal = when {
            !response.isSuccessful -> failureOf(response)
            body == null -> TopazOutcome.Failure(TopazFailure.BadResponse)
            declared > maxBytes -> TopazOutcome.Failure(TopazFailure.TooLarge)
            declared > 0 && target.parentFile?.usableSpace?.let { it in 1 until declared } == true ->
                TopazOutcome.Failure(TopazFailure.DiskFull)
            else -> null
        }
        return refusal ?: copyCapped(body!!.byteStream(), target, maxBytes)
    }

    private fun copyCapped(input: java.io.InputStream, target: File, maxBytes: Long): TopazOutcome<Long> = try {
        val total = input.use { source -> target.outputStream().use { copyAtMost(source, it, maxBytes) } }
        if (total < 0) TopazOutcome.Failure(TopazFailure.TooLarge) else TopazOutcome.Success(total)
    } catch (e: IOException) {
        if (!isDiskFull(e)) throw e
        TopazOutcome.Failure(TopazFailure.DiskFull)
    }

    /** The bytes copied, or -1 once more than [maxBytes] arrived. */
    private fun copyAtMost(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        var read = input.read(buffer)
        while (read >= 0 && total + read <= maxBytes) {
            output.write(buffer, 0, read)
            total += read
            read = input.read(buffer)
        }
        return if (read >= 0) -1 else total
    }

    private suspend fun <T> get(
        config: TopazConfig,
        path: String,
        serializer: KSerializer<T>,
    ): TopazOutcome<T> = withContext(dispatchers.io) {
        call(authed(config, path).get().build()) { parse(it, serializer) }
    }

    /** IO failures of an idempotent call: these the provider may retry. */
    private suspend fun <T> call(
        request: Request,
        client: OkHttpClient = apiHttp,
        handle: (Response) -> TopazOutcome<T>,
    ): TopazOutcome<T> {
        coroutineContext.ensureActive()
        // The path only, in either log: a signed download URL carries its credential in the query.
        return try {
            client.newCall(request).await().use(handle)
        } catch (e: SocketTimeoutException) {
            logger?.warn(TAG, "${request.method} ${request.url.encodedPath} timed out", e)
            TopazOutcome.Failure(TopazFailure.Timeout)
        } catch (e: IOException) {
            logger?.warn(TAG, "${request.method} ${request.url.encodedPath} failed", e)
            TopazOutcome.Failure(if (isDiskFull(e)) TopazFailure.DiskFull else TopazFailure.Offline)
        }
    }

    private fun <T> parse(response: Response, serializer: KSerializer<T>): TopazOutcome<T> {
        if (!response.isSuccessful) return failureOf(response)
        val text = response.body?.string().orEmpty()
        return try {
            TopazOutcome.Success(json.decodeFromString(serializer, text))
        } catch (_: SerializationException) {
            TopazOutcome.Failure(TopazFailure.BadResponse)
        } catch (_: IllegalArgumentException) {
            TopazOutcome.Failure(TopazFailure.BadResponse)
        }
    }

    /** specs/topaz_enhancement.md §4's table. */
    private fun failureOf(response: Response): TopazOutcome.Failure {
        val code = response.code
        logger?.warn(TAG, "${response.request.method} ${response.request.url.encodedPath} -> $code")
        val failure = when (code) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> TopazFailure.Unauthorized
            HTTP_PAYMENT_REQUIRED -> TopazFailure.InsufficientCredits
            HTTP_PAYLOAD_TOO_LARGE -> TopazFailure.TooLarge
            HTTP_TOO_MANY_REQUESTS, HTTP_TOO_EARLY -> TopazFailure.RateLimited
            HTTP_BAD_REQUEST, HTTP_PRECONDITION_FAILED, HTTP_UNSUPPORTED_MEDIA,
            HTTP_UNPROCESSABLE, HTTP_NOT_FOUND, HTTP_CONFLICT,
            -> TopazFailure.Rejected
            in HTTP_SERVER_ERRORS -> TopazFailure.Server
            // A 3xx included: following it could hand the key to another host.
            else -> TopazFailure.BadResponse
        }
        val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?.let { TimeUnit.SECONDS.toMillis(it) }
        return TopazOutcome.Failure(failure, retryAfter)
    }

    private fun authed(config: TopazConfig, path: String): Request.Builder =
        Request.Builder().url(config.baseUrl.trimEnd('/') + path).header(API_KEY_HEADER, config.apiKey)

    /** MockWebServer is plain HTTP on loopback; nothing else may be. */
    private fun allowsPlainHttp(request: Request): Boolean =
        request.url.host == "localhost" || request.url.host == "127.0.0.1"

    private fun neverSent(e: IOException): Boolean =
        e is UnknownHostException || e is ConnectException || e is NoRouteToHostException

    private fun isDiskFull(e: IOException): Boolean =
        e.message?.let { it.contains("ENOSPC") || it.contains("No space left") } == true

    companion object {
        private const val TAG = "TopazClient"
        internal const val API_KEY_HEADER = "X-API-Key"

        private const val API_READ_TIMEOUT_S = 60L
        private const val DOWNLOAD_READ_TIMEOUT_S = 60L
        private const val BUFFER_BYTES = 64 * 1024

        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_PAYMENT_REQUIRED = 402
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409
        private const val HTTP_PRECONDITION_FAILED = 412
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_UNSUPPORTED_MEDIA = 415
        private const val HTTP_UNPROCESSABLE = 422
        private const val HTTP_TOO_EARLY = 425
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val HTTP_SERVER_ERRORS = 500..599
    }
}

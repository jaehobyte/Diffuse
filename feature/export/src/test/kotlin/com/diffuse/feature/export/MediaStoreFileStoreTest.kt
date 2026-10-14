package com.diffuse.feature.export

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * specs/export.md §고해상도 결과 저장. A result wider than the 4096px working size reaches the
 * gallery as the same bytes, at the same size; a failure leaves no pending row.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaStoreFileStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var media: FakeMediaProvider

    private val dispatchers = object : DispatcherProvider {
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
    }

    @Before
    fun setUp() {
        FakeMediaProvider.root = temp.newFolder("media")
        media = Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
    }

    @Test
    fun `a result past the working size is copied byte for byte and published`() = runTest {
        val file = jpeg(width = 6000, height = 40)

        val outcome = store().copy(file, "image/jpeg", 6000, 40)

        assertTrue("$outcome", outcome is Result.Success)
        val row = media.rows.values.single()
        assertArrayEquals(file.readBytes(), row.file.readBytes())
        assertEquals(0, row.values.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals("image/jpeg", row.values.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertTrue(row.values.getAsString(MediaStore.Images.Media.DISPLAY_NAME).endsWith(".jpg"))
    }

    @Test
    fun `a stored image of another size is removed and reported`() = runTest {
        val file = jpeg(width = 300, height = 40)

        val outcome = store().copy(file, "image/jpeg", 600, 80)

        assertTrue(outcome is Result.Failure)
        assertTrue(media.rows.isEmpty())
    }

    @Test
    fun `a missing file inserts nothing`() = runTest {
        val outcome = store().copy(File(temp.root, "gone.jpg"), "image/jpeg", 1, 1)

        assertTrue(outcome is Result.Failure)
        assertEquals(0, media.inserts)
    }

    private fun store() = MediaStoreFileStore(
        ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver,
        "Diffuse",
        dispatchers,
    )

    private fun jpeg(width: Int, height: Int): File {
        val file = temp.newFile()
        file.outputStream().use {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        return file
    }

    /** MediaStore, reduced to the four calls a pending insert makes. */
    class FakeMediaProvider : ContentProvider() {

        class Row(val values: ContentValues, val file: File)

        val rows = linkedMapOf<Uri, Row>()
        var inserts = 0

        override fun onCreate() = true

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserts++
            val inserted = Uri.withAppendedPath(uri, inserts.toString())
            rows[inserted] = Row(ContentValues(values), File(root, "$inserts.bin"))
            return inserted
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = rows.getValue(uri).file
            if (!file.exists()) file.createNewFile()
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            rows[uri]?.values?.putAll(values) ?: return 0
            return 1
        }

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
            if (rows.remove(uri) != null) 1 else 0

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null

        companion object {
            lateinit var root: File
        }
    }
}

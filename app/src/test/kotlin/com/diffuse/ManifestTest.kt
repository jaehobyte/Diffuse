package com.diffuse

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v2 tools are HTTP clients, and every one of their tests talks to MockWebServer inside the
 * JVM — where permissions are not enforced. Nothing else would notice a missing `INTERNET`
 * until the app was on a device, so this asserts it directly.
 */
@RunWith(AndroidJUnit4::class)
class ManifestTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val declared: List<String> = context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions
        .orEmpty()
        .toList()

    @Test
    fun `the app may reach the network`() {
        assertTrue(
            "INTERNET is not declared, so every HTTP call fails on a device",
            android.Manifest.permission.INTERNET in declared,
        )
    }

    @Test
    fun `the app declares the microphone permission it requests at runtime`() {
        assertTrue(android.Manifest.permission.RECORD_AUDIO in declared)
    }

    @Test
    fun `a network security config decides which hosts may be plain HTTP`() {
        // Read through the resource id the manifest attribute compiles to; without a config,
        // targetSdk 28+ blocks the cleartext dev server outright.
        val field = android.content.pm.ApplicationInfo::class.java
            .getDeclaredField("networkSecurityConfigRes")
            .apply { isAccessible = true }

        assertNotEquals(0, field.getInt(context.applicationInfo))
    }
}

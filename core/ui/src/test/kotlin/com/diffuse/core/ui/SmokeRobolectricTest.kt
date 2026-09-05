package com.diffuse.core.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

@RunWith(AndroidJUnit4::class)
class SmokeRobolectricTest {
    @Test
    fun robolectricBootsWithoutNetwork() {
        assertNotNull(RuntimeEnvironment.getApplication())
    }
}

package com.diffuse.feature.editor.tools.enhance

import androidx.test.core.app.ApplicationProvider
import com.diffuse.core.ai.topaz.TopazSettings
import com.diffuse.core.common.Result
import com.diffuse.core.data.TopazWorkspace
import com.diffuse.feature.editor.TestDispatchers
import java.io.File

/** For view-model fixtures that do not exercise Topaz: a real workspace and a saver that succeeds. */
fun testTopazStorage(): TopazStorage = TopazStorage(
    TopazWorkspace(
        File(ApplicationProvider.getApplicationContext<android.app.Application>().cacheDir, "topaz_test"),
        TestDispatchers,
    ),
) { _, _, _, _ -> Result.Success(Unit) }

fun testTopazSettings(): TopazSettings = TopazSettings(ApplicationProvider.getApplicationContext())

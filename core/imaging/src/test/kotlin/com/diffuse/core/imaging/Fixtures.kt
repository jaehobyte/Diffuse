package com.diffuse.core.imaging

import java.io.File

/**
 * specs/testing.md §7. The fixtures live at the repo root and are read-only, so tests
 * copy them out rather than opening them in place. The working directory of a Gradle
 * test task is the module directory, so walk up until `fixtures/` appears.
 */
internal object Fixtures {

    private val dir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures") }
        .firstOrNull { it.isDirectory }
        ?: error("fixtures/ not found above ${File("").absolutePath}")

    fun copyTo(name: String, destDir: File): File {
        val source = File(dir, name)
        require(source.isFile) { "missing fixture $name" }
        return File(destDir, name).also { source.copyTo(it, overwrite = true) }
    }
}

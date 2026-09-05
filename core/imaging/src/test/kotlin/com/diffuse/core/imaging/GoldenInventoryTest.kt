package com.diffuse.core.imaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * specs/testing.md §4: "Any golden not listed is a check failure." The manifest is the
 * list; this test is what makes it binding, so a stray or forgotten golden fails `check`
 * instead of drifting in unnoticed.
 */
class GoldenInventoryTest {

    @Test
    fun `the golden directory matches golden_manifest`() {
        val manifest = File(GoldenAssert.goldenDir().parentFile, "golden_manifest.txt")
        val declared = manifest.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .sorted()
        val present = GoldenAssert.goldenDir()
            .listFiles { file -> file.extension == "png" }
            .orEmpty()
            .map { it.name }
            .sorted()

        assertEquals(
            declared,
            present,
            "golden/ and golden_manifest.txt disagree; add the file to the manifest " +
                "or delete the golden",
        )
    }
}

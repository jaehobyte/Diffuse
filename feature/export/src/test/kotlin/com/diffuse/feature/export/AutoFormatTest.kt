package com.diffuse.feature.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** specs/export.md §Sheet: PNG is auto-selected when the document has alpha. */
class AutoFormatTest {

    @Test
    fun `alpha forces PNG`() {
        val settings = ExportSettings(format = ExportFormat.Jpeg)

        assertEquals(ExportFormat.Png, settings.autoFormatFor(hasAlpha = true).format)
    }

    @Test
    fun `no alpha leaves the user's choice alone`() {
        val settings = ExportSettings(format = ExportFormat.Jpeg)

        assertEquals(settings, settings.autoFormatFor(hasAlpha = false))
    }

    @Test
    fun `an explicit PNG stays PNG either way`() {
        val settings = ExportSettings(format = ExportFormat.Png)

        assertEquals(ExportFormat.Png, settings.autoFormatFor(hasAlpha = false).format)
        assertEquals(ExportFormat.Png, settings.autoFormatFor(hasAlpha = true).format)
    }
}

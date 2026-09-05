package com.diffuse.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdsTest {

    @Test
    fun `ids do not collide across many calls`() {
        val count = 10_000
        val ids = List(count) { newId() }
        assertEquals(count, ids.toSet().size, "newId produced a duplicate")
    }

    @Test
    fun `ids are never blank`() {
        assertTrue(newId().isNotBlank())
    }
}

package com.adbw.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordTest {

    @Test
    fun passwordIs32Base64UrlChars() {
        val p = Password.generate()
        assertEquals(32, p.length)
        assertTrue(p.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' })
    }

    @Test
    fun passwordsAreDistinct() {
        val a = Password.generate()
        val b = Password.generate()
        assertNotEquals(a, b)
    }

    @Test
    fun deviceIdIs32LowercaseHex() {
        val d = Password.deviceId()
        assertEquals(32, d.length)
        assertTrue(d.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(d, d.lowercase())
    }
}

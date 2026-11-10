package com.adbw.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {

    @Test
    fun parseWorkerForm() {
        val p = Pairing.parse("vibeadb://box.example.workers.dev/abcd1234ef567890#pw123")
        assertTrue(p.viaMailbox)
        assertEquals("box.example.workers.dev", p.mailboxHost)
        assertEquals("abcd1234ef567890", p.deviceId)
        assertEquals("pw123", p.password)
        assertNull(p.tunnelHost)
    }

    @Test
    fun parseDirectForm() {
        val p = Pairing.parse("vibeadb://aaa-bbb.trycloudflare.com#pw")
        assertFalse(p.viaMailbox)
        assertNull(p.mailboxHost)
        assertNull(p.deviceId)
        assertEquals("aaa-bbb.trycloudflare.com", p.tunnelHost)
        assertEquals("pw", p.password)
    }

    @Test
    fun buildAndParseRoundtrip() {
        val s = Pairing.worker("h.workers.dev", "d1", "p1")
        val p = Pairing.parse(s)
        assertEquals("h.workers.dev", p.mailboxHost)
        assertEquals("d1", p.deviceId)
        assertEquals("p1", p.password)

        val d = Pairing.direct("x.trycloudflare.com", "p2")
        val pd = Pairing.parse(d)
        assertEquals("x.trycloudflare.com", pd.tunnelHost)
        assertEquals("p2", pd.password)
    }

    @Test
    fun trimsWhitespace() {
        val p = Pairing.parse("  vibeadb://h/d#pw \n")
        assertEquals("h", p.mailboxHost)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingPrefix() {
        Pairing.parse("nonsense")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingPassword() {
        Pairing.parse("vibeadb://h/d#")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyBody() {
        Pairing.parse("vibeadb://#pw")
    }

    @Test
    fun easytierForm() {
        val s = Pairing.easytier("10.126.126.10", 27183, "pw")
        assertEquals("vibeadb://et/10.126.126.10:27183#pw", s)
        val p = Pairing.parse(s)
        assertTrue(p.viaEasyTier)
        assertTrue(p.viaMailbox)
        assertEquals("et", p.mailboxHost)
        assertEquals("10.126.126.10:27183", p.deviceId)
        assertEquals("pw", p.password)
    }
}

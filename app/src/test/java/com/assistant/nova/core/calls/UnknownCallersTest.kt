package com.assistant.nova.core.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnknownCallersTest {
    private val day = 86_400_000L
    private val shop = "+91 98400 12345"
    private val calls = listOf(
        CallRecord(shop, null, UnknownCallers.INCOMING, 10 * day, 120, "Tamil Nadu"),
        CallRecord("09840012345", "", UnknownCallers.OUTGOING, 12 * day, 60, "Tamil Nadu"), // same shop, other format
        CallRecord("98400 12345", null, UnknownCallers.MISSED, 11 * day, 0),
        CallRecord("+91 99999 88888", "Amma", UnknownCallers.INCOMING, 13 * day, 300), // a contact
        CallRecord("+91 77777 66666", null, UnknownCallers.INCOMING, 14 * day, 10),
        CallRecord("+91 12121 21212", null, UnknownCallers.OUTGOING, 9 * day, 900),
        CallRecord("", null, UnknownCallers.INCOMING, 15 * day, 5), // private number
    )
    private val contactKeys = setOf("7777766666") // saved, but the log has no cached name yet

    @Test fun `one row per unknown number`() {
        val list = UnknownCallers.group(calls, contactKeys)
        assertEquals(listOf("9840012345", "1212121212"), list.map { it.key })
        val s = list.first()
        assertEquals(3, s.calls)
        assertEquals(1, s.incoming); assertEquals(1, s.outgoing); assertEquals(1, s.missed)
        assertEquals(180L, s.talkSec)
        assertEquals("09840012345", s.number) // as shown on the latest call
        assertEquals("Tamil Nadu", s.location)
    }

    @Test fun `sorting`() {
        val list = UnknownCallers.group(calls, contactKeys)
        assertEquals("9840012345", UnknownCallers.sort(list, UnknownCallers.Sort.RECENT).first().key)
        assertEquals("9840012345", UnknownCallers.sort(list, UnknownCallers.Sort.MOST_CALLS).first().key)
        assertEquals("1212121212", UnknownCallers.sort(list, UnknownCallers.Sort.LONGEST_TALK).first().key)
    }

    @Test fun `search by digits, label or place`() {
        val list = UnknownCallers.group(calls, contactKeys, labels = mapOf("9840012345" to "Repair shop"))
        assertEquals("9840012345", UnknownCallers.search(list, "12345").single().key)
        assertEquals("9840012345", UnknownCallers.search(list, "repair").single().key)
        assertEquals("9840012345", UnknownCallers.search(list, "tamil").single().key)
        assertTrue(UnknownCallers.search(list, "nothing").isEmpty())
        assertEquals(2, UnknownCallers.search(list, "").size)
    }
}

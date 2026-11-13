package com.prefixblocker.app.core

import org.junit.Assert.*
import org.junit.Test

class PhoneNumbersTest {
    @Test fun frenchPrefixFormatsAreEquivalent() {
        listOf("0948*", "09 48*", "+33948*", "+33 9 48*", "0033948*", "09\u00a048*", "09-48*").forEach {
            assertEquals(it, "+33948*", PhoneNumbers.parseRule(it))
        }
    }

    @Test fun frenchFullNumbersAreEquivalent() {
        listOf("0948123456", "09 48 12 34 56", "+33948123456", "0033948123456", "+33 (9) 48.12.34.56").forEach {
            assertEquals(it, "+33948123456", PhoneNumbers.parseRule(it))
        }
    }

    @Test fun internationalPrefixesAndNumbersAreKept() {
        assertEquals("+4420*", PhoneNumbers.parseRule("0044 20*"))
        assertEquals("+442079460018", PhoneNumbers.parseRule("+44 20 7946 0018"))
        assertEquals("+1*", PhoneNumbers.parseRule("+1*"))
    }

    @Test fun malformedRulesAreRejected() {
        listOf("", "*", "09*48", "0948**", "abc", "+", "++33948*", "+330948*", "0948", "948*",
            "09481234567", "094812345", "+0123456789", "+1234567890123456", "09/48*", "09#48*", "09;48*", "09,48*").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { PhoneNumbers.parseRule(it) }
        }
    }

    @Test fun exceptionsMustBeExactNumbers() {
        assertThrows(IllegalArgumentException::class.java) { PhoneNumbers.parseRule("0948*", false) }
        assertEquals("+33948123456", PhoneNumbers.parseRule("09 48 12 34 56", false))
    }

    @Test fun unreadableCallNumbersNeverMatch() {
        listOf(null, "", "private", "-1", "anonymous", "112", "0948*", "+33", "09481234567").forEach {
            assertNull(it, PhoneNumbers.normalizeCall(it))
        }
    }

    @Test fun displayPreservesMeaningAndRoundTrips() {
        listOf("+33948*", "+33948123456", "+4420*", "+442079460018", "+33*", "+3*").forEach {
            assertEquals(it, PhoneNumbers.parseRule(PhoneNumbers.display(it)))
        }
        assertEquals("09 48*", PhoneNumbers.display("+33948*"))
        assertEquals("09 48 12 34 56", PhoneNumbers.display("+33948123456"))
    }

    @Test fun prefixAndExactMatchingAreDifferent() {
        assertTrue(PhoneNumbers.matches("+33948*", "+33948123456"))
        assertFalse(PhoneNumbers.matches("+33948*", "+33947123456"))
        assertTrue(PhoneNumbers.matches("+33948123456", "+33948123456"))
        assertFalse(PhoneNumbers.matches("+33948123456", "+33948123457"))
        assertFalse(PhoneNumbers.matches("+33948*", "+44948123456"))
    }
}

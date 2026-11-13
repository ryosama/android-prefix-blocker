package com.prefixblocker.app.core

import com.prefixblocker.app.data.AppStore
import org.junit.Assert.*
import org.junit.Test

class PolicyEngineTest {
    private val rules = AppStore.DEFAULT_RULES.map { PhoneNumbers.parseRule(it) }

    @Test fun allFiveDefaultsBlockTheirOwnPrefix() {
        AppStore.DEFAULT_RULES.forEach {
            val number = it.removeSuffix("*").padEnd(10, '1')
            assertTrue(number, PolicyEngine.decide(number, true, rules, emptyList()).blocked)
        }
    }

    @Test fun unrelatedAndForeignNumbersAreAllowed() {
        listOf("0648123456", "0947123456", "+44948123456", "+33648123456").forEach {
            assertEquals(Reason.NO_MATCH, PolicyEngine.decide(it, true, rules, emptyList()).reason)
        }
    }

    @Test fun pausedFilterAllowsMatchingNumberWithoutReadingContacts() {
        val result = PolicyEngine.decide("0948123456", false, rules, emptyList()) { error("Should not query contacts") }
        assertEquals(Reason.PAUSED, result.reason)
    }

    @Test fun savedContactsAlwaysWin() {
        assertEquals(Reason.CONTACT, PolicyEngine.decide("0948123456", true, rules, emptyList()) { true }.reason)
    }

    @Test fun explicitExceptionAlwaysWinsAndIsExact() {
        val exceptions = listOf("+33948123456")
        assertEquals(Reason.EXCEPTION, PolicyEngine.decide("09 48 12 34 56", true, rules, exceptions).reason)
        assertTrue(PolicyEngine.decide("0948123457", true, rules, exceptions).blocked)
    }

    @Test fun emptyListNeverBlocks() {
        assertFalse(PolicyEngine.decide("0948123456", true, emptyList(), emptyList()).blocked)
    }

    @Test fun exactRuleDoesNotAffectOtherNumbers() {
        val exact = listOf("+33948123456")
        assertTrue(PolicyEngine.decide("0948123456", true, exact, emptyList()).blocked)
        assertFalse(PolicyEngine.decide("0948123457", true, exact, emptyList()).blocked)
    }

    @Test fun mostSpecificRuleExplainsDecision() {
        val overlapping = listOf("+339*", "+33948*", "+339481*", "+33948123456")
        assertEquals("+33948123456", PolicyEngine.decide("0948123456", true, overlapping, emptyList()).rule)
        assertEquals("+339481*", PolicyEngine.decide("0948123457", true, overlapping, emptyList()).rule)
    }

    @Test fun noContactLookupForUnmatchedOrUnknownNumbers() {
        listOf("0648123456", null, "anonymous").forEach { number ->
            assertFalse(PolicyEngine.decide(number, true, rules, emptyList()) { error("Unnecessary contact lookup") }.blocked)
        }
    }
}

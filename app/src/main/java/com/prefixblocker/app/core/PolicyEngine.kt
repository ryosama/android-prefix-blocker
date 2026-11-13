package com.prefixblocker.app.core

enum class Reason { BLOCKED, NO_MATCH, CONTACT, EXCEPTION, PAUSED, UNKNOWN, ERROR, TIMEOUT }

data class Decision(val reason: Reason, val rule: String? = null) {
    val blocked: Boolean get() = reason == Reason.BLOCKED
}

object PolicyEngine {
    fun decide(
        rawNumber: String?,
        enabled: Boolean,
        blocks: List<String>,
        exceptions: List<String>,
        isContact: (String) -> Boolean = { false },
    ): Decision {
        if (!enabled) return Decision(Reason.PAUSED)
        val number = PhoneNumbers.normalizeCall(rawNumber) ?: return Decision(Reason.UNKNOWN)
        if (number in exceptions) return Decision(Reason.EXCEPTION)
        // An exact match takes priority, then the most specific prefix, for a useful explanation.
        val rule = blocks.filter { PhoneNumbers.matches(it, number) }
            .sortedWith(compareBy<String> { it.endsWith('*') }.thenByDescending { it.length })
            .firstOrNull() ?: return Decision(Reason.NO_MATCH)
        if (isContact(number)) return Decision(Reason.CONTACT)
        return Decision(Reason.BLOCKED, rule)
    }
}

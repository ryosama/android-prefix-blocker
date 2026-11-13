package com.prefixblocker.app.core

/** All matching uses an international representation, independently of spacing. */
object PhoneNumbers {
    private fun compact(input: String): String = buildString {
        input.forEach { char ->
            when {
                char.isWhitespace() || Character.isSpaceChar(char) || char in "().-" -> Unit
                char in '0'..'9' || char == '+' || char == '*' -> append(char)
                else -> throw IllegalArgumentException("invalid_character")
            }
        }
    }

    fun parseRule(input: String, allowPrefix: Boolean = true): String {
        // Le joker est retiré avant de normaliser le numéro, puis remis à la fin.
        // Cela rend aussi identiques les saisies 0948*, +33948* et 0033948*.
        val compact = compact(input)
        val prefix = compact.endsWith('*')
        require(allowPrefix || !prefix) { "exact_only" }
        val body = if (prefix) compact.dropLast(1) else compact
        require('*' !in body && body.isNotEmpty()) { "invalid_wildcard" }
        val normalized = international(body)
        val digits = normalized.drop(1)
        require(digits.length <= 15 && digits.isNotEmpty()) { "invalid_length" }
        if (!prefix) {
            // Une saisie courte sans joker ne doit pas devenir un préfixe par erreur.
            require(digits.length in 7..15) { "invalid_length" }
            if (digits.startsWith("33")) require(digits.length == 11) { "french_length" }
        }
        return normalized + if (prefix) "*" else ""
    }

    private fun international(value: String): String {
        val international = when {
            value.startsWith("00") -> "+" + value.drop(2)
            value.startsWith('+') -> value
            value.startsWith('0') -> "+33" + value.drop(1)
            else -> throw IllegalArgumentException("country_required")
        }
        require(international.length > 1 && international.drop(1).all { it in '0'..'9' }) { "invalid_number" }
        require(international[1] != '0') { "invalid_country" }
        // En France, l'indicatif +33 remplace le zéro national, il ne le précède pas.
        require(!international.startsWith("+330")) { "french_trunk" }
        return international
    }

    fun normalizeCall(input: String?): String? {
        if (input.isNullOrBlank()) return null
        return try { parseRule(input, allowPrefix = false) } catch (_: IllegalArgumentException) { null }
    }

    fun display(value: String): String {
        val prefix = value.endsWith('*')
        val body = if (prefix) value.dropLast(1) else value
        val text = if (body.startsWith("+33")) {
            ("0" + body.drop(3)).chunked(2).joinToString(" ")
        } else body
        return text + if (prefix) "*" else ""
    }

    fun matches(canonicalRule: String, canonicalNumber: String): Boolean =
        if (canonicalRule.endsWith('*')) canonicalNumber.startsWith(canonicalRule.dropLast(1))
        else canonicalNumber == canonicalRule
}

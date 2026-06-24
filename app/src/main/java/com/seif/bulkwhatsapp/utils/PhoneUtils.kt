package com.seif.bulkwhatsapp.utils

object PhoneUtils {
    
    /**
     * Normalize Egyptian phone number to international format +20XXXXXXXXXX
     * Handles:
     *   01012345678  → +201012345678
     *   1012345678   → +201012345678
     *   +201012345678 → +201012345678 (unchanged)
     *   00201012345678 → +201012345678
     */
    fun normalizeEgyptianPhone(raw: String): String {
        // Remove all non-digit except leading +
        var phone = raw.trim()
        val hasPlus = phone.startsWith("+")
        phone = phone.replace(Regex("[^0-9]"), "")

        return when {
            // Already has 20 country code
            phone.startsWith("20") && phone.length >= 12 -> "+$phone"
            // Starts with 002
            phone.startsWith("002") -> "+${phone.substring(2)}"
            // Egyptian local: starts with 0 (e.g. 01012345678)
            phone.startsWith("0") && phone.length == 11 -> "+20${phone.substring(1)}"
            // Egyptian local without 0 (e.g. 1012345678)
            phone.startsWith("1") && phone.length == 10 -> "+20$phone"
            // Already international with +
            hasPlus -> "+$phone"
            // Fallback - return as is with +
            else -> "+$phone"
        }
    }
}

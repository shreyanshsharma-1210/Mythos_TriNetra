package com.trustmesh.app.core.identity

object PhoneNumberNormalizer {
    /**
     * Normalizes an incoming phone number into a consistent format for lookup.
     * Currently handles basic stripping of whitespaces, hyphens, and brackets.
     * Leaves country codes intact if present, or prepends a default if necessary,
     * but strictly avoids complex country-specific assumptions.
     */
    fun normalize(phoneNumber: String): String {
        // Strip out non-digit and non-plus characters
        var normalized = phoneNumber.replace(Regex("[^0-9+]"), "")
        
        // Handle common local Indian number formatting (e.g., missing +91 but 10 digits)
        if (!normalized.startsWith("+") && normalized.length == 10) {
            normalized = "+91$normalized"
        } else if (normalized.startsWith("0") && normalized.length == 11) {
            normalized = "+91" + normalized.substring(1)
        } else if (!normalized.startsWith("+") && normalized.length == 12 && normalized.startsWith("91")) {
            normalized = "+$normalized"
        }
        
        return normalized
    }
}

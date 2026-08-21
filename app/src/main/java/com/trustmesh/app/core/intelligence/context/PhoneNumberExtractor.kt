package com.trustmesh.app.core.intelligence.context

object PhoneNumberExtractor {
    fun extract(text: String): List<String> {
        val candidates = mutableListOf<String>()
        val regex = Regex("\\b(?:\\+91[-.\\s]?)?(?:91[-.\\s]?)?[6-9]\\d{2}[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b")
        val matches = regex.findAll(text)
        
        for (match in matches) {
            val normalized = match.value.replace(Regex("[^0-9]"), "")
            
            val finalNumber = if (normalized.length == 10) {
                "+91$normalized"
            } else if (normalized.length == 12 && normalized.startsWith("91")) {
                "+$normalized"
            } else {
                continue
            }
            candidates.add(finalNumber)
        }
        return candidates.distinct()
    }
}

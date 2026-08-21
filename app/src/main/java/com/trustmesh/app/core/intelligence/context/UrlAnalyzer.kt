package com.trustmesh.app.core.intelligence.context

object UrlAnalyzer {

    fun analyze(text: String): List<RiskSignal> {
        val signals = mutableListOf<RiskSignal>()
        val urlRegex = Regex("(?i)\\b((?:https?://|www\\d{0,3}[.]|[a-z0-9.\\-]+[.][a-z]{2,4}/)(?:[^\\s()<>]+|\\((?:[^\\s()<>]+|(?:\\([^\\s()<>]+\\)))*\\))+(?:\\((?:[^\\s()<>]+|(?:\\([^\\s()<>]+\\)))*\\)|[^\\s`!()\\[\\]{};:'\".,<>?«»“”‘’]))")
        val matches = urlRegex.findAll(text)
        
        for (match in matches) {
            val url = match.value.lowercase()
            
            if (url.startsWith("http://")) {
                signals.add(RiskSignal(ScamSignalType.SUSPICIOUS_URL, Confidence.MEDIUM, 10, "UrlAnalyzer", "Insecure HTTP link detected"))
            }
            
            val ipRegex = Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b")
            if (ipRegex.containsMatchIn(url)) {
                signals.add(RiskSignal(ScamSignalType.IP_ADDRESS_URL, Confidence.HIGH, 20, "UrlAnalyzer", "IP address used in URL"))
            }
            
            val shorteners = listOf("bit.ly", "t.co", "tinyurl", "goo.gl", "ow.ly", "is.gd", "buff.ly", "tiny.cc", "shorte.st", "x.co", "v.gd")
            if (shorteners.any { url.contains(it) }) {
                signals.add(RiskSignal(ScamSignalType.SHORTENED_URL, Confidence.HIGH, 15, "UrlAnalyzer", "URL shortener detected"))
            }
            
            // Look for punycode or excessive subdomains? Too complex for this, just add a generic suspicious URL if it's not a common shortener but looks weird.
            // E.g. long URL with random chars.
            if (url.length > 50 && url.contains("-") && url.count { it == '.' } > 3) {
                signals.add(RiskSignal(ScamSignalType.SUSPICIOUS_URL, Confidence.MEDIUM, 10, "UrlAnalyzer", "Unusual URL pattern detected"))
            }
        }
        
        return signals
    }
}

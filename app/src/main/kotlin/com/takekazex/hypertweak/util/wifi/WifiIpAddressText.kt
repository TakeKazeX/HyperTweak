package com.takekazex.hypertweak.util.wifi

object WifiIpAddressText {
    private val IPV4_REGEX = Regex(
        "^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.){3}(25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)$"
    )

    fun isIpv4(address: String): Boolean = IPV4_REGEX.matches(address.trim())

    fun isIpv6(address: String): Boolean {
        val trimmed = address.trim()
        val withoutZone = if (trimmed.contains('%')) {
            trimmed.substringBefore('%')
        } else {
            trimmed
        }
        if (!withoutZone.contains(':')) return false
        val colonCount = withoutZone.count { it == ':' }
        if (colonCount < 2 || colonCount > 7) return false
        return withoutZone.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
    }

    fun isValidIp(address: String): Boolean = isIpv4(address) || isIpv6(address)

    fun parse(text: CharSequence?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isNotEmpty() && isValidIp(line) && seen.add(line)) {
                result.add(line)
            }
        }
        return result
    }

    fun isIpList(text: CharSequence?): Boolean {
        if (text.isNullOrBlank()) return false
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return lines.isNotEmpty() && lines.all { isValidIp(it) }
    }

    fun format(ipv4List: Collection<String>, ipv6List: Collection<String>): String {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (ip in ipv4List) {
            val trimmed = ip.trim()
            if (trimmed.isNotEmpty() && isIpv4(trimmed) && seen.add(trimmed)) {
                result.add(trimmed)
            }
        }
        for (ip in ipv6List) {
            val trimmed = ip.trim()
            if (trimmed.isNotEmpty() && isIpv6(trimmed) && seen.add(trimmed)) {
                result.add(trimmed)
            }
        }
        return result.joinToString("\n")
    }
}

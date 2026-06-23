package com.leeyf.acpcommit.prompt

object CommitResponseSanitizer {
    private val fencedBlock = Regex("""(?s)^```(?:text|gitcommit)?\s*\n?(.*?)\n?```$""", RegexOption.IGNORE_CASE)

    fun sanitize(response: String): String {
        val normalized = response.replace("\r\n", "\n").trim()
        val withoutFence = fencedBlock.matchEntire(normalized)?.groupValues?.get(1)?.trim() ?: normalized
        require(withoutFence.isNotBlank()) { "The ACP agent returned an empty commit message" }
        return withoutFence
    }
}

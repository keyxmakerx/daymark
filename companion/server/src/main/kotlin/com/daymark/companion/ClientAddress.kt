package com.daymark.companion

import java.net.InetAddress

/**
 * Works out **which address a per-client security control should key on**, given that the server
 * normally runs behind a reverse proxy.
 *
 * ## Why this exists
 *
 * Every per-source control in this server — sync auth lockout, TOTP lockout, the unauthenticated
 * recovery rate limit, audit `sourceIp` — keys on the peer address of the TCP connection. Behind a
 * reverse proxy (the deployment topology this project documents and recommends), that peer address
 * is **the proxy, identically, for every request on the internet**. Which means those controls
 * silently share one bucket:
 *
 * - 8 bad bearer tokens from one attacker locks out *every* client for the lockout window.
 * - 3 recovery requests exhaust the hourly budget for everybody.
 * - `sourceIp` in the audit log records the proxy, so it reads as working while being useless.
 *
 * The naive fix — trust `X-Forwarded-For` — is worse: any client could then send
 * `X-Forwarded-For: <random>` and evade every lockout entirely, turning a shared-bucket denial of
 * service into an unlimited-attempts bypass.
 *
 * So forwarded headers are honoured **only** from a proxy the operator has explicitly pinned, which
 * is the trusted-proxy contract described in `docs/COMPANION_DEPLOYMENT.md` §4.0.
 *
 * ## The rule
 *
 * 1. **No trusted proxies configured → always use the peer address.** This is the default, and it
 *    is byte-for-byte the behaviour this server had before, so enabling nothing changes nothing.
 * 2. **Peer is not a trusted proxy → use the peer address**, ignoring any forwarded headers it
 *    sent. Someone reaching the app directly does not get to describe themselves.
 * 3. **Peer is trusted →** walk `X-Forwarded-For` right-to-left, skipping entries that are
 *    themselves trusted proxies, and take the first address that is not. That is the closest hop
 *    the trusted chain actually vouches for. Left-to-right would take the leftmost entry, which is
 *    fully attacker-controlled — the classic spoofing mistake.
 * 4. Anything malformed, or a list that is entirely trusted proxies → fall back to the peer.
 *
 * Fail-safe throughout: every uncertain path returns the peer address, which is the conservative
 * choice because it over-groups (a shared bucket) rather than over-trusting (a bypass).
 */
object ClientAddress {

    /** One entry of the trusted-proxy allowlist: a single address, or a CIDR block. */
    class Range internal constructor(
        private val network: ByteArray,
        private val prefixLength: Int,
    ) {
        fun contains(address: ByteArray): Boolean {
            if (address.size != network.size) return false
            var bitsLeft = prefixLength
            var i = 0
            while (bitsLeft >= 8) {
                if (address[i] != network[i]) return false
                bitsLeft -= 8
                i++
            }
            if (bitsLeft == 0) return true
            val mask = (0xFF shl (8 - bitsLeft)) and 0xFF
            return (address[i].toInt() and mask) == (network[i].toInt() and mask)
        }
    }

    /**
     * Parses a comma-separated allowlist of IPs and CIDR blocks, e.g.
     * `"10.89.0.2/32, 192.168.1.5, fd00::/8"`. Unparseable entries are dropped rather than
     * throwing — a typo in one entry must not take the server down, and dropping an entry can only
     * make the result *more* conservative (that proxy stops being trusted).
     */
    fun parseTrusted(spec: String?): List<Range> {
        if (spec.isNullOrBlank()) return emptyList()
        return spec.split(',').mapNotNull { raw ->
            val entry = raw.trim()
            if (entry.isEmpty()) return@mapNotNull null
            runCatching {
                val slash = entry.indexOf('/')
                val host = if (slash >= 0) entry.substring(0, slash) else entry
                val bytes = InetAddress.getByName(stripBrackets(host)).address
                val prefix = if (slash >= 0) {
                    entry.substring(slash + 1).trim().toInt()
                } else {
                    bytes.size * 8
                }
                if (prefix < 0 || prefix > bytes.size * 8) return@runCatching null
                Range(bytes, prefix)
            }.getOrNull()
        }
    }

    /** True when [address] falls inside any configured trusted range. */
    fun isTrusted(address: String, trusted: List<Range>): Boolean {
        if (trusted.isEmpty()) return false
        val bytes = toBytes(address) ?: return false
        return trusted.any { it.contains(bytes) }
    }

    /**
     * The address a per-client control should key on.
     *
     * @param peer the direct TCP peer (`request.origin.remoteAddress`)
     * @param forwardedFor every `X-Forwarded-For` header value, in header order
     * @param trusted the configured allowlist; empty means "trust nothing", the default
     */
    fun resolve(peer: String, forwardedFor: List<String>, trusted: List<Range>): String {
        if (trusted.isEmpty()) return peer
        if (!isTrusted(peer, trusted)) return peer

        // Flatten "a, b" across possibly-repeated headers, preserving order.
        val hops = forwardedFor
            .flatMap { it.split(',') }
            .map { normalize(it) }
            .filter { it.isNotEmpty() }

        // Right-to-left: the last hop a trusted proxy appended is the one it actually observed.
        for (hop in hops.asReversed()) {
            if (toBytes(hop) == null) return peer // malformed chain — do not guess
            if (!isTrusted(hop, trusted)) return hop
        }
        return peer
    }

    private fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        // "[2001:db8::1]:443" -> "2001:db8::1"
        if (trimmed.startsWith("[")) {
            val close = trimmed.indexOf(']')
            if (close > 0) return trimmed.substring(1, close)
            return trimmed
        }
        // "1.2.3.4:5678" -> "1.2.3.4"; a bare IPv6 literal has several colons, so only strip when
        // there is exactly one.
        val colon = trimmed.indexOf(':')
        if (colon > 0 && trimmed.indexOf(':', colon + 1) < 0) return trimmed.substring(0, colon)
        return trimmed
    }

    private fun stripBrackets(host: String): String =
        if (host.startsWith("[") && host.endsWith("]")) host.substring(1, host.length - 1) else host

    /** Parses a literal address to bytes. Never resolves DNS: only numeric literals are accepted. */
    private fun toBytes(address: String): ByteArray? {
        val host = stripBrackets(address.trim())
        if (host.isEmpty()) return null
        // Reject anything that is not a numeric literal, so this can never trigger a DNS lookup on
        // an attacker-supplied string.
        if (!host.all { it.isDigit() || it == '.' || it == ':' || (it in 'a'..'f') || (it in 'A'..'F') || it == '%' }) {
            return null
        }
        return runCatching { InetAddress.getByName(host).address }.getOrNull()
    }
}

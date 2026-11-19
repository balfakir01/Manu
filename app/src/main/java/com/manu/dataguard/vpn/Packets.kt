package com.manu.dataguard.vpn

/** A DNS question pulled out of an IPv4/UDP packet that an app sent to our virtual DNS server. */
class DnsQuery(
    val srcAddr: ByteArray,
    val dstAddr: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    /** Raw DNS message (header + question + any extra records). */
    val dns: ByteArray,
    /** Lower-cased hostname being looked up, e.g. "graph.example.com". */
    val name: String,
    val qtype: Int,
    /** Offset in [dns] just past the question section. */
    val questionEnd: Int,
)

object Packets {

    /** Returns the DNS query in [buf], or null if the packet is anything else. */
    fun parseDnsQuery(buf: ByteArray, len: Int): DnsQuery? {
        if (len < 28) return null
        if ((buf[0].toInt() shr 4) and 0xF != 4) return null            // IPv4 only
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if ((buf[9].toInt() and 0xFF) != 17) return null                 // UDP only
        val totalLen = minOf(u16(buf, 2), len)
        val srcPort = u16(buf, ihl)
        val dstPort = u16(buf, ihl + 2)
        if (dstPort != 53) return null
        val dnsStart = ihl + 8
        if (totalLen < dnsStart + 12) return null
        val dns = buf.copyOfRange(dnsStart, totalLen)
        if (u16(dns, 4) < 1) return null                                 // no question
        val (name, end) = readName(dns, 12) ?: return null
        if (end + 4 > dns.size) return null
        return DnsQuery(
            srcAddr = buf.copyOfRange(12, 16),
            dstAddr = buf.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            dns = dns,
            name = name,
            qtype = u16(dns, end),
            questionEnd = end + 4,
        )
    }

    private fun readName(dns: ByteArray, start: Int): Pair<String, Int>? {
        var i = start
        val sb = StringBuilder()
        while (i < dns.size) {
            val l = dns[i].toInt() and 0xFF
            if (l == 0) return Pair(sb.toString().lowercase(), i + 1)
            if ((l and 0xC0) != 0) return null                           // compression never appears in queries
            if (i + 1 + l > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..l) sb.append((dns[i + k].toInt() and 0xFF).toChar())
            i += l + 1
        }
        return null
    }

    /** "This domain doesn't exist" answer — the app simply can't reach the host. */
    fun blockedAnswer(q: DnsQuery): ByteArray {
        val r = q.dns.copyOf(q.questionEnd)
        r[2] = (0x80 or (q.dns[2].toInt() and 0x79)).toByte()           // QR=1, keep opcode + RD
        r[3] = 0x83.toByte()                                             // RA=1, RCODE=3 (NXDOMAIN)
        r[4] = 0; r[5] = 1                                               // 1 question
        for (k in 6..11) r[k] = 0                                        // no answers/authority/extra
        return r
    }

    /** "Server failure" answer, used when no upstream DNS server replied. */
    fun failureAnswer(q: DnsQuery): ByteArray {
        val r = blockedAnswer(q)
        r[3] = 0x82.toByte()                                             // RCODE=2 (SERVFAIL)
        return r
    }

    /** The first IPv4 address (A record) in a DNS answer, or null if there isn't one. */
    fun firstIpv4(dns: ByteArray): ByteArray? {
        if (dns.size < 12) return null
        val questions = u16(dns, 4)
        val answers = u16(dns, 6)
        var i = 12
        repeat(questions) {
            i = skipName(dns, i) ?: return null
            i += 4
        }
        repeat(answers) {
            i = skipName(dns, i) ?: return null
            if (i + 10 > dns.size) return null
            val type = u16(dns, i)
            val len = u16(dns, i + 8)
            i += 10
            if (i + len > dns.size) return null
            if (type == 1 && len == 4) return dns.copyOfRange(i, i + 4)
            i += len                                                     // e.g. a CNAME; keep looking
        }
        return null
    }

    private fun skipName(dns: ByteArray, start: Int): Int? {
        var i = start
        while (i < dns.size) {
            val l = dns[i].toInt() and 0xFF
            if (l == 0) return i + 1
            if ((l and 0xC0) == 0xC0) return i + 2                       // compression pointer ends the name
            i += l + 1
        }
        return null
    }

    /** Wraps a DNS answer in IPv4+UDP headers addressed back to the app that asked. */
    fun wrapAnswer(q: DnsQuery, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        p[6] = 0x40                                                      // don't fragment
        p[8] = 64                                                        // TTL
        p[9] = 17                                                        // UDP
        System.arraycopy(q.dstAddr, 0, p, 12, 4)
        System.arraycopy(q.srcAddr, 0, p, 16, 4)
        put16(p, 10, ipChecksum(p))
        put16(p, 20, q.dstPort)
        put16(p, 22, q.srcPort)
        put16(p, 24, 8 + payload.size)
        // UDP checksum 0 = "not computed", which is valid for IPv4.
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    private fun ipChecksum(p: ByteArray): Int {
        var sum = 0
        for (i in 0 until 20 step 2) sum += u16(p, i)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte()
        b[o + 1] = v.toByte()
    }
}

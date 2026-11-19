package com.manu.dataguard.data

import android.content.Context
import android.telephony.TelephonyManager
import java.io.DataInputStream
import java.util.Locale

/**
 * Offline IP-to-country lookup. Uses the bundled DB-IP "IP to Country Lite" database
 * (CC BY 4.0, https://db-ip.com), so no address is ever sent anywhere to be located.
 * Asset format is documented in tools/build_geo_assets.py.
 */
class Geo private constructor(ctx: Context) {

    companion object {
        @Volatile private var instance: Geo? = null
        fun get(ctx: Context): Geo =
            instance ?: synchronized(this) { instance ?: Geo(ctx.applicationContext).also { instance = it } }

        /** Country the phone is in: mobile network, then SIM, then language settings. */
        fun homeCountry(ctx: Context): String? {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            val cc = listOfNotNull(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country)
                .firstOrNull { it.length == 2 }
            return cc?.uppercase(Locale.ROOT)
        }

        fun countryName(cc: String): String =
            Locale("", cc).getDisplayCountry(Locale.getDefault()).ifEmpty { cc }

        /** "US" -> 🇺🇸 */
        fun flag(cc: String): String =
            if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) "🌐"
            else String(Character.toChars(0x1F1E6 + (cc[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (cc[1] - 'A')))
    }

    private val starts: IntArray
    private val countryIdx: ByteArray
    private val codes: Array<String>

    init {
        DataInputStream(ctx.assets.open("geoip4.bin").buffered()).use { inp ->
            val magic = ByteArray(4).also { inp.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "GIP1") { "bad geoip4.bin" }
            val n = inp.readInt()
            starts = IntArray(n) { inp.readInt() }
            countryIdx = ByteArray(n).also { inp.readFully(it) }
            val c = inp.readUnsignedByte()
            codes = Array(c) {
                val b = ByteArray(2).also { inp.readFully(it) }
                String(b, Charsets.US_ASCII)
            }
        }
    }

    /** Two-letter country code for an IPv4 address, or null if unknown/private. */
    fun country(ipv4: ByteArray): String? {
        if (ipv4.size != 4) return null
        val ip = ((ipv4[0].toLong() and 0xFF) shl 24) or ((ipv4[1].toLong() and 0xFF) shl 16) or
            ((ipv4[2].toLong() and 0xFF) shl 8) or (ipv4[3].toLong() and 0xFF)
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {                       // last range whose start <= ip
            val mid = (lo + hi) ushr 1
            if ((starts[mid].toLong() and 0xFFFFFFFFL) <= ip) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (found < 0) return null
        val cc = codes[countryIdx[found].toInt() and 0xFF]
        return if (cc == "--" || cc == "ZZ") null else cc
    }
}

/** Country outlines (Natural Earth, public domain) and label points, for drawing the map. */
class World private constructor(ctx: Context) {

    class Country(val code: String, val lat: Float, val lon: Float, val rings: List<FloatArray>)

    companion object {
        @Volatile private var instance: World? = null
        fun get(ctx: Context): World =
            instance ?: synchronized(this) { instance ?: World(ctx.applicationContext).also { instance = it } }
    }

    val countries: List<Country>
    val byCode: Map<String, Country>

    init {
        val list = mutableListOf<Country>()
        DataInputStream(ctx.assets.open("world.bin").buffered()).use { inp ->
            val magic = ByteArray(4).also { inp.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "WLD1") { "bad world.bin" }
            repeat(inp.readUnsignedShort()) {
                val code = ByteArray(2).also { inp.readFully(it) }.toString(Charsets.US_ASCII)
                val lat = inp.readFloat()
                val lon = inp.readFloat()
                val rings = List(inp.readUnsignedShort()) {
                    val pts = inp.readUnsignedShort()
                    FloatArray(pts * 2) { inp.readShort() / 100f }   // lon, lat, lon, lat, ...
                }
                list += Country(code, lat, lon, rings)
            }
        }
        countries = list
        byCode = list.associateBy { it.code }
    }
}

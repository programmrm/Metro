package com.programmer

import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.security.MessageDigest

// DiziPal sınıfının dışına (altına) ekle
class DizipalPlayer : ExtractorApi() {
    override var name = "DizipalPlayer"
    override var mainUrl = "dplayer82.site"
    override val requiresReferer = true

    private val mapper by lazy { jacksonObjectMapper() }
    private val commonUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private fun originOf(url: String): String {
        val host = Regex("""^https?://([^/]+)""").find(url)?.groupValues?.getOrNull(1) ?: return ""
        return "https://$host/"
    }

    private companion object {
        const val MAX_GATE_ITERATIONS = 100_000_000
        val GATE_SALT_REGEX =
            Regex("""var\s+(\w+)\s*=\s*String\.fromCharCode\(([0-9,\s]+)\)""")
        val GATE_TOKEN_REGEX =
            Regex("""var\s+(\w+)\s*=\s*\w+\.toString\((\d+)\)""")
        val GATE_COND_REGEX =
            Regex("""\(\s*\w+\s*\(([^()]*)\)\s*\[\s*(\d+)\s*\]\s*(&|>>>)\s*(\d+)\s*\)\s*===\s*0""")
        val GATE_PARAM_REGEX =
            Regex("""NP_GATE\s*=\s*function\s*\(\s*(\w+)""")
    }

    // np.js ee(): SHA-256 çıktısını 8 büyük harfli 32-bit kelimeye böler
    private fun sha256Words(input: String): IntArray {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return IntArray(8) { i ->
            val o = i * 4
            ((digest[o].toInt() and 0xff) shl 24) or
                    ((digest[o + 1].toInt() and 0xff) shl 16) or
                    ((digest[o + 2].toInt() and 0xff) shl 8) or
                    (digest[o + 3].toInt() and 0xff)
        }
    }

    private fun splitConcat(expr: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        for (c in expr) {
            when {
                quote != null -> {
                    sb.append(c)
                    if (c == quote) quote = null
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    sb.append(c)
                }
                c == '+' -> {
                    parts.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
        }
        parts.add(sb.toString())
        return parts.map { it.trim() }
    }

    private fun buildGateInput(
        parts: List<String>,
        tokenVar: String,
        token: String,
        paramVar: String,
        paramValue: String,
        saltVar: String,
        salt: String
    ): String? {
        val out = StringBuilder()
        for (p in parts) {
            when {
                p.length >= 2 && ((p.startsWith("'") && p.endsWith("'")) ||
                        (p.startsWith("\"") && p.endsWith("\""))) ->
                    out.append(p.substring(1, p.length - 1))
                p == tokenVar -> out.append(token)
                p == paramVar -> out.append(paramValue)
                p == saltVar -> out.append(salt)
                else -> return null
            }
        }
        return out.toString()
    }

    private fun gateIterates(bits: Int): Int {
        return minOf(MAX_GATE_ITERATIONS, (1L shl minOf(bits + 8, 30)).toInt())
    }

    // window.NP_GATE(gateN, ee) fonksiyonunu iframe HTML'inden çözüp token üretir
    private fun npGateToken(pageSource: String, gateN: String): String? {
        val start = pageSource.indexOf("NP_GATE")
        if (start < 0) return null
        val body = pageSource.substring(start)

        val saltMatch = GATE_SALT_REGEX.find(body) ?: return null
        val saltVar = saltMatch.groupValues[1]
        val salt = saltMatch.groupValues[2]
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .map { it.toChar() }
            .joinToString("")

        val tokenMatch = GATE_TOKEN_REGEX.find(body) ?: return null
        val tokenVar = tokenMatch.groupValues[1]
        val base = tokenMatch.groupValues[2].toIntOrNull() ?: return null
        if (base !in 2..36) return null

        val condMatch = GATE_COND_REGEX.find(body) ?: return null
        val expr = condMatch.groupValues[1]
        val wordIndex = condMatch.groupValues[2].toIntOrNull() ?: return null
        val op = condMatch.groupValues[3]
        val opValue = condMatch.groupValues[4].toIntOrNull() ?: return null

        val paramMatch = GATE_PARAM_REGEX.find(body) ?: return null
        val paramVar = paramMatch.groupValues[1]

        val parts = splitConcat(expr)

        val isAnd = op == "&"
        val shift = opValue % 32
        val bits = if (isAnd) Integer.bitCount(opValue) else if (shift == 0) 32 else 32 - shift
        val cap = gateIterates(bits)

        for (n in 0 until cap) {
            val token = if (base == 10) n.toString() else Integer.toString(n, base)
            val input = buildGateInput(parts, tokenVar, token, paramVar, gateN, saltVar, salt)
                ?: return null
            val words = sha256Words(input)
            if (wordIndex !in words.indices) return null
            val unsigned = words[wordIndex].toLong() and 0xFFFFFFFFL
            val ok = if (isAnd) {
                (unsigned and opValue.toLong()) == 0L
            } else if (shift == 0) {
                unsigned == 0L
            } else {
                (unsigned shr shift) == 0L
            }
            if (ok) return token
        }
        return null
    }

    // np.js: NP_GATE yoksa fallback → ee(gateN + ":" + n)[0] >>> (32-b) === 0
    private fun fallbackGateToken(gateN: String, gateB: Int): String? {
        val b = gateB.coerceIn(0, 32)
        val shift = (32 - b) % 32
        val bits = if (shift == 0) 32 else 32 - shift
        val cap = gateIterates(bits)
        for (n in 0 until cap) {
            val unsigned = sha256Words("$gateN:$n")[0].toLong() and 0xFFFFFFFFL
            val ok = if (shift == 0) unsigned == 0L else (unsigned shr shift) == 0L
            if (ok) return n.toString()
        }
        return null
    }

    private fun computeGateToken(pageSource: String, gateN: String, gateB: Int): String? {
        val token = runCatching { npGateToken(pageSource, gateN) }.getOrNull()
        if (token != null) return token
        return runCatching { fallbackGateToken(gateN, gateB) }.getOrNull()
    }

    private suspend fun parseNpConfig(
        pageSource: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val marker = pageSource.indexOf("window.NP_CONFIG")
        if (marker < 0) return false
        val brace = pageSource.indexOf('{', marker)
        if (brace < 0) return false

        val config = runCatching { mapper.readTree(pageSource.substring(brace)) }.getOrNull() ?: return false
        var created = false

        // NP gate: her master/altyazı URL'sine "&p=<token>" eklenmeli, yoksa sunucu 404 döner
        val gateNode = config.get("gate")
        val gateN = gateNode?.get("n")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
        val gateB = gateNode?.get("b")?.asInt(12) ?: 12
        val gateToken = gateN?.let { computeGateToken(pageSource, it, gateB) }
        val gateSuffix = if (gateToken != null) "&p=$gateToken" else ""
        Log.d(
            "DiziPal",
            "NP gate: n=$gateN b=$gateB token=$gateToken"
        )

        val sources = config.get("sources")
        if (sources != null && sources.isArray) {
            for (node in sources) {
                val master = node.get("master")?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val playUrl = master + gateSuffix
                val title = node.get("title")?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: name
                val quality = Regex("""(\d{3,4})[pP]""").find(title)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Qualities.Unknown.value
                Log.d("DiziPal", "NP master: $title → $playUrl")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - $title",
                        url = playUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = originOf(playUrl)
                        this.quality = quality
                        this.headers = mapOf("User-Agent" to commonUserAgent)
                    }
                )
                created = true
            }
        }

        val subtitles = config.get("subtitles")
        if (subtitles != null && subtitles.isArray) {
            for (node in subtitles) {
                val subUrl = node.get("url")?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val playSubUrl = subUrl + gateSuffix
                val label = node.get("label")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: node.get("lang")?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: "tr"
                Log.d("DiziPal", "NP altyazı: $label → $playSubUrl")
                subtitleCallback.invoke(
                    newSubtitleFile(
                        lang = label,
                        url = playSubUrl
                    ) {
                        headers = mapOf(
                            "Referer" to originOf(playSubUrl),
                            "User-Agent" to commonUserAgent
                        )
                    }
                )
            }
        }

        Log.d("DiziPal", "NP oynatıcı: link=${if (created) "var" else "yok"} (referer $url)")
        return created
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer).text

        // Yeni "NP" oynatıcı: window.NP_CONFIG = {...} (nm.php master + nsub.php altyazı)
        if (parseNpConfig(response, url, subtitleCallback, callback)) return

        val openPlayerRegex = """window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""".toRegex()

        val subUrls = mutableSetOf<String>()
        Regex(""""file":"((?:\\\\\"|[^"])+)","label":"((?:\\\\\"|[^"])+)"""").findAll(response).forEach {
            val (subUrlExt, subLangExt) = it.destructured

            val subUrl = subUrlExt.replace("\\/", "/").replace("\\u0026", "&").replace("\\", "")
            val subLang = subLangExt.replace("\\u0131", "ı").replace("\\u0130", "İ").replace("\\u00fc", "ü").replace("\\u00e7", "ç").replace("\\u011f", "ğ").replace("\\u015f", "ş")

            if (subUrl in subUrls) return@forEach
            subUrls.add(subUrl)

            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = subLang,
                    url = fixUrl(subUrl)
                ) {
                    headers = mapOf(
                        "Referer" to url,
                        "User-Agent" to commonUserAgent
                    )
                }
            )
        }
        val playlistId = openPlayerRegex.find(response)?.groupValues?.get(1)
        Log.d("DiziPal", "--> playlistId: $playlistId")
        if (playlistId != null) {
            val domainRegex = """https?://[^/]+""".toRegex()
            val domain = domainRegex.find(url)?.value ?: "https://dplayer82.site"
            val apiUrl = "$domain/source2.php?v=$playlistId"
            Log.d("DiziPal", "--> apiUrl: $apiUrl")
            val apiResponse = app.get(apiUrl, referer = url).text

            try {
                val fileRegex = """"file"\s*:\s*"([^"]+)"""".toRegex()
                val fileMatches = fileRegex.findAll(apiResponse)

                fileMatches.forEach { matchResult ->
                    var fileUrl = matchResult.groupValues[1].replace("\\/", "/")

                    if (fileUrl.startsWith("//")) {
                        fileUrl = "https:$fileUrl"
                    } else if (!fileUrl.startsWith("http")) {
                        fileUrl = "https://$fileUrl"
                        Log.d("DiziPal", "--> fileUrl: $fileUrl")
                    }

                    if (fileUrl.contains("m.php")) {
                        fileUrl = fileUrl.replace("m.php", "master.m3u8")
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "DPlayer (Auto)",
                            url = fileUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            headers = mapOf("Origin" to domain, "Referer" to url)
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("DiziPal", "--> DPlayer Extractor Hata: ${e.message}")
            }
        }
    }
}

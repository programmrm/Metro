package com.programmer

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class CloseLoad : ExtractorApi() {
    override val name = "CloseLoad"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val filmRef = referer ?: "$mainUrl/"
        val headers2 = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
            "Referer" to filmRef,
            "Origin" to filmRef.trimEnd('/')
        )

        try {
            val response = app.get(url, referer = filmRef, headers = headers2)
            val html = response.text 

            // 1. JS Deşifre Algoritmasını Dene
            var realUrl = decryptNative(html)

            // 2. Fallback: Eğer JS çözümü http URL vermezse JSON-LD contentUrl'i dene
            if (realUrl.isNullOrBlank() || !realUrl.startsWith("http")) {
                Log.w("Kekik_${this.name}", "Native deşifre başarısız veya geçersiz, Fallback JSON-LD aranıyor...")
                val ldJsonMatch = """"contentUrl"\s*:\s*"([^"]+)"""".toRegex().find(html)
                realUrl = ldJsonMatch?.groupValues?.get(1)?.replace("\\/", "/")
            }

            if (!realUrl.isNullOrBlank() && realUrl.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = realUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.P1080.value
                        headers = mapOf(
                            "Referer" to filmRef,
                            "User-Agent" to headers2["User-Agent"]!!
                        )
                    }
                )
            } else {
                Log.e("Kekik_${this.name}", "Real URL bulunamadı veya deşifre edilemedi.")
            }

            processSubtitles(html, subtitleCallback)

        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Hata: ${e.message}")
        }
    }

    private fun decryptNative(html: String): String? {
        try {
            val scriptBlockMatch = """<script[^>]*>(.*?dc_[a-zA-Z0-9_]+\(.*?</script>)""".toRegex(RegexOption.DOT_MATCHES_ALL).find(html)
            val scriptContent = scriptBlockMatch?.groupValues?.get(1) ?: return null

            val arrayMatch = """\(\[((?:"[^"]+",?\s*)+)\]\)""".toRegex().find(scriptContent)
            val parts = arrayMatch?.groupValues?.get(1)?.split(",")?.map { 
                it.trim().trim('"').replace("\\/", "/") 
            } ?: return null

            val moduloMatch = """(\d+)\s*%\s*\(i\s*\+\s*(\d+)\)""".toRegex().find(scriptContent)
            val magicNum = moduloMatch?.groupValues?.get(1)?.toLongOrNull() ?: 399756995L
            val magicOffset = moduloMatch?.groupValues?.get(2)?.toIntOrNull() ?: 5

            val dcFuncStart = scriptContent.indexOf("function dc_")
            val dcFuncEnd = scriptContent.indexOf("function d1x", dcFuncStart).takeIf { it != -1 } ?: scriptContent.length
            val funcBody = if (dcFuncStart != -1) scriptContent.substring(dcFuncStart, dcFuncEnd) else scriptContent

            // Tüm ROT shift değerlerini replace içinden sırayla çıkar: (o - base + N) % 26
            val rotShifts = """\(o\s*-\s*base\s*\+\s*(\d+)\)""".toRegex().findAll(funcBody).map {
                it.groupValues[1].toIntOrNull() ?: 4
            }.toList()

            // Operasyon sırası: replace ve atob pozisyonlarını bul, sırala
            data class Op(val pos: Int, val isAtob: Boolean)
            val ops = mutableListOf<Op>()
            for (m in """\.replace\(""".toRegex().findAll(funcBody)) {
                ops.add(Op(m.range.first, false))
            }
            for (m in """atob\(""".toRegex().findAll(funcBody)) {
                ops.add(Op(m.range.first, true))
            }
            ops.sortBy { it.pos }

            var result = parts.joinToString("")
            var rotIdx = 0

            for (op in ops) {
                if (op.isAtob) {
                    var padded = result
                    while (padded.length % 4 != 0) padded += "="
                    result = String(Base64.decode(padded, Base64.NO_WRAP), Charsets.ISO_8859_1)
                } else {
                    val shift = if (rotIdx < rotShifts.size) rotShifts[rotIdx] else 4
                    rotIdx++
                    val sb = StringBuilder()
                    for (c in result) {
                        if (c in 'a'..'z') {
                            var shifted = c.code + shift
                            while (shifted > 'z'.code) shifted -= 26
                            while (shifted < 'a'.code) shifted += 26
                            sb.append(shifted.toChar())
                        } else if (c in 'A'..'Z') {
                            var shifted = c.code + shift
                            while (shifted > 'Z'.code) shifted -= 26
                            while (shifted < 'A'.code) shifted += 26
                            sb.append(shifted.toChar())
                        } else {
                            sb.append(c)
                        }
                    }
                    result = sb.toString()
                }
            }

            val unmix = StringBuilder()
            for (i in result.indices) {
                val charCode = result[i].code.toLong()
                val decryptedCode = (charCode - (magicNum % (i + magicOffset)) + 256) % 256
                unmix.append(decryptedCode.toInt().toChar())
            }

            return unmix.toString()

        } catch (e: Exception) {
            Log.e("Kekik_Extractor", "Native Çözümleme Hatası: ${e.message}")
            return null
        }
    }


    private fun processSubtitles(html: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            // JWPlayer setup içindeki tracks: [...] JSON bloğu
            val tracksMatch = """tracks\s*:\s*(\[.*?\])""".toRegex(RegexOption.DOT_MATCHES_ALL).find(html)
            tracksMatch?.groupValues?.get(1)?.let { tracksJson ->
                
                val trackPattern = """\{[^}]*\}""".toRegex()
                val fileRegex = """"file"\s*:\s*"([^"]+)"""".toRegex()
                val labelRegex = """"label"\s*:\s*"([^"]+)"""".toRegex()

                trackPattern.findAll(tracksJson).forEach { match ->
                    val block = match.value
                    val file = fileRegex.find(block)?.groupValues?.get(1)?.replace("\\/", "/")
                    val label = labelRegex.find(block)?.groupValues?.get(1) ?: "Altyazı"

                    // file null değilse ve http ile başlıyorsa fırlat
                    if (!file.isNullOrBlank() && file.startsWith("http")) {
                        subtitleCallback.invoke(SubtitleFile(label, file))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Altyazı Çözümleme Hatası: ${e.message}")
        }
    }
}

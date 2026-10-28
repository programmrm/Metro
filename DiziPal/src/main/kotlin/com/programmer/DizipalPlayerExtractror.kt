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

        val sources = config.get("sources")
        if (sources != null && sources.isArray) {
            for (node in sources) {
                val master = node.get("master")?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val title = node.get("title")?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: name
                val quality = Regex("""(\d{3,4})[pP]""").find(title)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Qualities.Unknown.value
                Log.d("DiziPal", "NP master: $title → $master")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - $title",
                        url = master,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = originOf(master)
                        this.quality = quality
                    }
                )
                created = true
            }
        }

        val subtitles = config.get("subtitles")
        if (subtitles != null && subtitles.isArray) {
            for (node in subtitles) {
                val subUrl = node.get("url")?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val label = node.get("label")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: node.get("lang")?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: "tr"
                Log.d("DiziPal", "NP altyazı: $label → $subUrl")
                subtitleCallback.invoke(
                    newSubtitleFile(
                        lang = label,
                        url = subUrl
                    ) {
                        headers = mapOf(
                            "Referer" to originOf(subUrl),
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
                            Qualities.Unknown.value
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("DiziPal", "--> DPlayer Extractor Hata: ${e.message}")
            }
        }
    }
}

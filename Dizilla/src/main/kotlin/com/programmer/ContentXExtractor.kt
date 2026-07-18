// ! Bu araç @programmer tarafından.

package com.programmer

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class ContentX : ExtractorApi() {
    override val name            = "ContentX"
    override val mainUrl         = "https://contentx.me"
    override val requiresReferer = true

    private val mapper by lazy {
        ObjectMapper().registerModule(KotlinModule.Builder().build()).apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef   = referer ?: ""
        Log.d("Kekik_${this.name}", "url » $url")

        val iSource  = app.get(url, referer=extRef).text
        val iExtract = Regex("""window\.openPlayer\('([^']+)'""").find(iSource)?.groups?.get(1)?.value ?: run {
            Log.e("Kekik_${this.name}", "openPlayer ID not found")
            return
        }

        val subUrls = mutableSetOf<String>()

        fun parseSubLang(s: String): String = s
            .replace("\\u0131", "ı").replace("\\u0130", "İ")
            .replace("\\u00fc", "ü").replace("\\u00e7", "ç")
            .replace("\\u011f", "ğ").replace("\\u015f", "ş")
            .replace("\\u00f6", "ö").replace("\\u00f6", "ö")

        fun addSubtitle(subUrl: String, subLang: String) {
            val cleanUrl = subUrl.replace("\\/", "/").replace("\\", "")
            if (cleanUrl in subUrls) return
            subUrls.add(cleanUrl)
            subtitleCallback.invoke(
                SubtitleFile(lang = parseSubLang(subLang), url = fixUrl(cleanUrl))
            )
        }

        fun makeLink(linkName: String, videoUrl: String, quality: Int = Qualities.Unknown.value) {
            val cleanUrl = videoUrl.replace("\\/", "/").replace("\\", "")
            val m3uUrl = cleanUrl.replace("m.php", "master.m3u8")
            Log.d("Kekik_${this.name}", "Link: $linkName → $m3uUrl")
            callback.invoke(
                newExtractorLink(
                    source  = this.name,
                    name    = linkName,
                    url     = m3uUrl,
                    type    = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = quality
                }
            )
        }

        // Subtitles from iframe page
        Regex(""""file"\s*:\s*"([^"]+)"[^}]*"label"\s*:\s*"([^"]+)"""").findAll(iSource).forEach {
            addSubtitle(it.groupValues[1], it.groupValues[2])
        }
        Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(iSource)?.groupValues?.getOrNull(1)?.let { tracksBlock ->
            Regex("""file\s*:\s*["']([^"']+)[^}]*?label\s*:\s*["']([^"']+)""").findAll(tracksBlock).forEach {
                addSubtitle(it.groupValues[1], it.groupValues[2])
            }
        }

        val vidSource  = app.get("${mainUrl}/source2.php?v=${iExtract}", referer=extRef).text
        Log.d("Kekik_${this.name}", "source2.php response length: ${vidSource.length}")

        var jsonParsed = false

        // Try JSON parsing first
        try {
            val json = mapper.readTree(vidSource)

            val playlist = json.get("playlist")
            if (playlist != null && playlist.isArray && playlist.size() > 0) {
                jsonParsed = true
                for (i in 0 until playlist.size()) {
                    val sources = playlist.get(i).get("sources")
                    if (sources != null && sources.isArray && sources.size() > 0) {
                        val firstSource = sources.get(0)
                        val file = firstSource.get("file")?.asText() ?: continue
                        val title = firstSource.get("title")?.asText() ?: this.name
                        val isDub = title.contains("dub", ignoreCase = true) || title.contains("dublaj", ignoreCase = true)
                        val isSub = title.contains("alt", ignoreCase = true) || title.contains("sub", ignoreCase = true) || title.contains("orijinal", ignoreCase = true) || title.contains("original", ignoreCase = true)
                        val sourceName = when {
                            isDub -> "${this.name} - Türkçe Dublaj"
                            isSub -> "${this.name} - Altyazılı"
                            else -> title
                        }
                        val quality = Regex("""(\d{3,4})[pP]""").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
                        makeLink(sourceName, file, quality)
                    }
                }
            }

            val tracks = json.get("tracks")
            if (tracks != null && tracks.isArray) {
                jsonParsed = true
                for (i in 0 until tracks.size()) {
                    val track = tracks.get(i)
                    val file = track.get("file")?.asText()
                    val label = track.get("label")?.asText()
                    if (file != null && label != null) {
                        addSubtitle(file, label)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "JSON parse error: ${e.message}")
        }

        // Always try regex fallback for both links and subtitles
        Regex(""""file"\s*:\s*"([^"]+)"[^}]*"label"\s*:\s*"([^"]+)"""").findAll(vidSource).forEach {
            addSubtitle(it.groupValues[1], it.groupValues[2])
        }

        if (!jsonParsed) {
            Log.d("Kekik_${this.name}", "JSON parse failed or no playlist, using regex fallback")
            Regex("""file":"([^"]+)""").findAll(vidSource).forEach { match ->
                val fileUrl = match.groupValues[1]
                if (fileUrl.contains(".m3u8") || fileUrl.contains("m3u8") || fileUrl.contains(".php")) {
                    makeLink(this.name, fileUrl)
                }
            }
            // If regex found nothing, at least try the first file
            if (subUrls.isEmpty()) {
                Regex("""file":"([^"]+)""").find(vidSource)?.groupValues?.getOrNull(1)?.let { makeLink(this.name, it) }
            }
        }
    }
}

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

        suspend fun addSubtitle(subUrl: String, subLang: String) {
            val cleanUrl = subUrl.replace("\\/", "/").replace("\\", "")
            if (cleanUrl in subUrls) return
            subUrls.add(cleanUrl)
            subtitleCallback.invoke(
                SubtitleFile(lang = parseSubLang(subLang), url = fixUrl(cleanUrl))
            )
        }

        suspend fun makeLink(linkName: String, videoUrl: String, quality: Int = Qualities.Unknown.value) {
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

        suspend fun extractSource(file: String, title: String) {
            val isDub = title.contains("dub", ignoreCase = true) || title.contains("dublaj", ignoreCase = true)
            val isSub = title.contains("alt", ignoreCase = true) || title.contains("sub", ignoreCase = true) || title.contains("orijinal", ignoreCase = true) || title.contains("original", ignoreCase = true)
            val sourceName = when {
                isDub -> "${this.name} - Türkçe Dublaj"
                isSub -> "${this.name} - Altyazılı"
                else -> title
            }
            val quality = Regex("""(\d{3,4})[pP]""").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
            Log.d("Kekik_${this.name}", "JSON source: $sourceName → $file")
            makeLink(sourceName, file, quality)
        }

        // Subtitles from iframe page
        for (match in Regex(""""file"\s*:\s*"([^"]+)"[^}]*"label"\s*:\s*"([^"]+)"""").findAll(iSource)) {
            addSubtitle(match.groupValues[1], match.groupValues[2])
        }
        val tracksMatch = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(iSource)
        if (tracksMatch != null) {
            val tracksBlock = tracksMatch.groupValues[1]
            for (match in Regex("""file\s*:\s*["']([^"']+)[^}]*?label\s*:\s*["']([^"']+)""").findAll(tracksBlock)) {
                addSubtitle(match.groupValues[1], match.groupValues[2])
            }
        }

        val vidSource  = app.get("${mainUrl}/source2.php?v=${iExtract}", referer=extRef).text
        Log.d("Kekik_${this.name}", "source2.php response length: ${vidSource.length}")

        var linksCreated = false

        // Try JSON parsing first
        try {
            val json = mapper.readTree(vidSource)
            Log.d("Kekik_${this.name}", "JSON keys: ${json.fieldNames().asSequence().joinToString()}")

            // Try playlist array (multiple sources)
            val playlist = json.get("playlist")
            if (playlist != null && playlist.isArray) {
                for (i in 0 until playlist.size()) {
                    val item = playlist.get(i)
                    // playlist[i].sources[j]
                    val sources = item.get("sources")
                    if (sources != null && sources.isArray) {
                        for (j in 0 until sources.size()) {
                            val s = sources.get(j)
                            val file = s.get("file")?.asText() ?: continue
                            val title = s.get("title")?.asText() ?: s.get("label")?.asText() ?: this.name
                            extractSource(file, title)
                            linksCreated = true
                        }
                    }
                    // playlist[i].file (flat)
                    val flatFile = item.get("file")?.asText()
                    if (flatFile != null) {
                        val title = item.get("title")?.asText() ?: item.get("label")?.asText() ?: this.name
                        extractSource(flatFile, title)
                        linksCreated = true
                    }
                }
            } else {
                Log.d("Kekik_${this.name}", "JSON has no playlist")
            }

            // Try top-level sources array
            if (!linksCreated) {
                val topSources = json.get("sources")
                if (topSources != null && topSources.isArray) {
                    for (i in 0 until topSources.size()) {
                        val s = topSources.get(i)
                        val file = s.get("file")?.asText() ?: continue
                        val title = s.get("title")?.asText() ?: s.get("label")?.asText() ?: this.name
                        extractSource(file, title)
                        linksCreated = true
                    }
                }
            }

            // Try top-level file (single source)
            if (!linksCreated) {
                val topFile = json.get("file")?.asText()
                if (topFile != null) {
                    val title = json.get("title")?.asText() ?: json.get("label")?.asText() ?: this.name
                    extractSource(topFile, title)
                    linksCreated = true
                }
            }

            // Try root-level array
            if (!linksCreated && json.isArray) {
                for (i in 0 until json.size()) {
                    val item = json.get(i)
                    val file = item.get("file")?.asText() ?: continue
                    val title = item.get("title")?.asText() ?: item.get("label")?.asText() ?: this.name
                    extractSource(file, title)
                    linksCreated = true
                }
            }

            // Tracks (subtitles)
            val tracks = json.get("tracks")
            if (tracks != null && tracks.isArray) {
                for (i in 0 until tracks.size()) {
                    val track = tracks.get(i)
                    val file = track.get("file")?.asText()
                    val label = track.get("label")?.asText()
                    if (file != null && label != null) {
                        Log.d("Kekik_${this.name}", "JSON track subtitle: $label → $file")
                        addSubtitle(file, label)
                    }
                }
            } else {
                Log.d("Kekik_${this.name}", "JSON has no tracks")
            }

            // Also check subtitle (top-level key)
            val subFile = json.get("subtitle")?.asText()
            val subLabel = json.get("sublang")?.asText() ?: json.get("language")?.asText()
            if (subFile != null && subLabel != null) {
                Log.d("Kekik_${this.name}", "JSON top-level subtitle: $subLabel → $subFile")
                addSubtitle(subFile, subLabel)
            }
        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "JSON parse error: ${e.message}")
        }

        // Always try regex subtitle extraction from source2.php
        for (match in Regex(""""file"\s*:\s*"([^"]+)"[^}]*"label"\s*:\s*"([^"]+)"""").findAll(vidSource)) {
            addSubtitle(match.groupValues[1], match.groupValues[2])
        }

        if (!linksCreated) {
            Log.d("Kekik_${this.name}", "JSON produced no links, using regex fallback")
            for (match in Regex("""file":"([^"]+)""").findAll(vidSource)) {
                val fileUrl = match.groupValues[1]
                if (fileUrl.contains(".m3u8") || fileUrl.contains("m3u8") || fileUrl.contains(".php")) {
                    makeLink(this.name, fileUrl)
                    linksCreated = true
                }
            }
            if (!linksCreated) {
                val fallbackMatch = Regex("""file":"([^"]+)""").find(vidSource)
                if (fallbackMatch != null) {
                    makeLink(this.name, fallbackMatch.groupValues[1])
                }
            }
        }

        // Try to extract subtitles from HLS master playlist
        if (subUrls.isEmpty()) {
            try {
                val m3uUrls = mutableSetOf<String>()
                // source2.php has m.php URLs, not .m3u8 — convert m.php to master.m3u8
                for (match in Regex("""https?://[^"'\s,}]+\.php[^"'\s,}]*""").findAll(vidSource)) {
                    m3uUrls.add(match.value.replace("\\/", "/").replace("\\", "").replace("m.php", "master.m3u8"))
                }
                for (match in Regex("""https?://[^"'\s,}]+\.m3u8[^"'\s,}]*""").findAll(vidSource)) {
                    m3uUrls.add(match.value.replace("\\/", "/").replace("\\", ""))
                }
                for (m3uUrl in m3uUrls) {
                    Log.d("Kekik_${this.name}", "Fetching M3U8 for subs: $m3uUrl")
                    val m3uBody = try { app.get(m3uUrl, referer=extRef).text } catch (e: Exception) { continue }
                    for (subMatch in Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES[^#]*?URI="([^"]+)"[^#]*?LANGUAGE="([^"]+)"""").findAll(m3uBody)) {
                        val subUri = subMatch.groupValues[1]
                        val subLang = subMatch.groupValues[2]
                        val subFullUrl = if (subUri.startsWith("http")) subUri else
                            "${m3uUrl.substringBeforeLast("/")}/$subUri"
                        Log.d("Kekik_${this.name}", "M3U8 subtitle: $subLang → $subFullUrl")
                        addSubtitle(subFullUrl, subLang)
                    }
                }
            } catch (e: Exception) {
                Log.d("Kekik_${this.name}", "M3U8 subtitle extraction error: ${e.message}")
            }
        }
    }
}

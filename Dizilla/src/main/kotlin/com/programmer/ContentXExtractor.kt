// ! Bu araç @programmer tarafından.

package com.programmer

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class ContentX : ExtractorApi() {
    override val name            = "ContentX"
    override val mainUrl         = "https://contentx.me"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef   = referer ?: ""
        Log.d("Kekik_${this.name}", "url » $url")

        val iSource  = app.get(url, referer=extRef).text
        val iExtract = Regex("""window\.openPlayer\('([^']+)'""").find(iSource)!!.groups[1]?.value ?: throw ErrorLoadingException("iExtract is null")

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

        // Pattern 1: JSON "file":"...","label":"..." (extra fields tolerated via [^}]*)
        Regex(""""file"\s*:\s*"([^"]+)"[^}]*"label"\s*:\s*"([^"]+)"""").findAll(iSource).forEach {
            addSubtitle(it.groupValues[1], it.groupValues[2])
        }

        // Pattern 2: JWPlayer tracks array
        Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(iSource)?.groupValues?.getOrNull(1)?.let { tracksBlock ->
            Regex("""file\s*:\s*["']([^"']+)[^}]*?label\s*:\s*["']([^"']+)""").findAll(tracksBlock).forEach {
                addSubtitle(it.groupValues[1], it.groupValues[2])
            }
        }

        val vidSource  = app.get("${mainUrl}/source2.php?v=${iExtract}", referer=extRef).text

        // Pattern 3: Subtitles in the video source JSON response
        Regex(""""file"\s*:\s*"([^"]+)"[^}]*"label"\s*:\s*"([^"]+)"""").findAll(vidSource).forEach {
            addSubtitle(it.groupValues[1], it.groupValues[2])
        }

        val vidExtract = Regex("""file":"([^"]+)""").find(vidSource)!!.groups[1]?.value ?: throw ErrorLoadingException("vidExtract is null")
        val m3uLink    = vidExtract.replace("\\", "")

        callback.invoke(
            newExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = m3uLink,
                type    = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )

        val iDublaj = Regex(""","([^']+)","Türkçe""").find(iSource)?.groups?.get(1)?.value
        if (iDublaj != null) {
            val dublajSource  = app.get("${mainUrl}/source2.php?v=${iDublaj}", referer=extRef).text
            val dublajExtract = Regex("""file":"([^"]+)""").find(dublajSource)!!.groups[1]?.value ?: throw ErrorLoadingException("dublajExtract is null")
            val dublajLink    = dublajExtract.replace("\\", "")

            callback.invoke(
                newExtractorLink(
                    source  = "${this.name} Türkçe Dublaj",
                    name    = "${this.name} Türkçe Dublaj",
                    url     = dublajLink,
                    type    = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

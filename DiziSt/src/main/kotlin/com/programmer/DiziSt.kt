// ! Bu araç @programmer tarafından.

package com.programmer

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.StringUtils.decodeUri
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.nio.charset.StandardCharsets

class DiziSt : MainAPI() {
    override var mainUrl              = "https://dizist.live"
    override var name                 = "DiziSt"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val mapper by lazy { jacksonObjectMapper() }
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(10 * 1024).string())

            if (response.code == 503 || doc.selectFirst("meta[name='cloudflare']") != null) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}"                     to "Yeni Eklenen Dizi Bölümleri",
        "${mainUrl}/yabanci-diziler"       to "Yabancı Diziler",
        "${mainUrl}/animeler"              to "Animeler",
        "${mainUrl}/asyadizileri"          to "Asya Dizileri",
        "${mainUrl}/arsiv"                 to "Arşiv"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            request.data,
            cookies     = mapOf("LockUser" to "true", "isTrustedUser" to "true"),
            interceptor = interceptor,
            cacheTime   = 60
        ).document

        val home = if (request.name == "Yeni Eklenen Dizi Bölümleri") {
            document.select("div.serie-box-mb").mapNotNull { it.toEpisodeResult() }
        } else {
            document.select("div.poster-mb-bx").mapNotNull { it.toMainPageResult() }
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toEpisodeResult(): SearchResponse? {
        val link = this.selectFirst("a[data-navigo]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = this.selectFirst("h2.truncate")?.text()?.trim() ?: return null

        val img = this.selectFirst(".poster-xs-image img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src").takeIf { it?.isNotBlank() == true } ?: img?.attr("src")
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val title = link.attr("title").ifEmpty { this.selectFirst(".poster-long-subject")?.text() } ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        val img = this.selectFirst(".poster-long-image img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src").takeIf { it?.isNotBlank() == true } ?: img?.attr("src")
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            "${mainUrl}/bg/searchcontent",
            cookies     = mapOf("LockUser" to "true", "isTrustedUser" to "true"),
            interceptor = interceptor,
            data        = mapOf("searchTerm" to query, "cKey" to "ca1d4a53d0f4761a949b85e51e18f096")
        ).document

        return document.select("div.poster-mb-bx").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            cookies     = mapOf("LockUser" to "true", "isTrustedUser" to "true"),
            interceptor = interceptor
        ).document

        val title       = document.selectFirst("h1.pull-left a")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst(".series-profile-image img")?.attr("src"))
        val description = document.selectFirst(".tv-story p")?.text()?.trim()
        val year        = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val tags        = document.select("a[href*='/tur/'], a[href*='/ulke/']").map { it.text() }
        val actors      = document.select("a[href*='/oyuncu/']").map { Actor(it.text()) }
        val trailer     = document.selectFirst("iframe[src*='youtube']")?.attr("src")

        val episodeList = mutableListOf<Episode>()

        document.select("#seasons-list a[href]").forEach { seasonLink ->
            val seasonUrl = fixUrlNull(seasonLink.attr("href")) ?: return@forEach
            val seasonDoc = app.get(
                seasonUrl,
                cookies     = mapOf("LockUser" to "true", "isTrustedUser" to "true"),
                interceptor = interceptor
            ).document

            seasonDoc.select("article.grid-box").forEach ep@ { epElem ->
                val epTitle   = epElem.selectFirst(".post-title a")?.text()?.trim() ?: return@ep
                val epHref    = fixUrlNull(epElem.selectFirst(".post-title a")?.attr("href")) ?: return@ep
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                episodeList.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.season = epSeason
                    this.episode = epEpisode
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun extractSubtitles(source: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val subUrls = mutableSetOf<String>()
        Regex(""""file"\s*:\s*"([^"]+)"[^}]*"label"\s*:\s*"([^"]+)"""").findAll(source).forEach {
            val file = it.groupValues[1].replace("\\/", "/").replace("\\", "")
            val label = it.groupValues[2]
                .replace("\\u0131", "ı").replace("\\u0130", "İ")
                .replace("\\u00fc", "ü").replace("\\u00e7", "ç")
            if (file !in subUrls) {
                subUrls.add(file)
                subtitleCallback.invoke(SubtitleFile(lang = label, url = fixUrl(file)))
            }
        }
        Regex("""tracks\s*:\s*\[(.*?)\]""", setOf(RegexOption.DOT_MATCHES_ALL)).find(source)?.groupValues?.getOrNull(1)?.let { tracks ->
            Regex("""file\s*:\s*["']([^"']+)[^}]*?label\s*:\s*["']([^"']+)""").findAll(tracks).forEach {
                val file = it.groupValues[1].replace("\\/", "/").replace("\\", "")
                val label = it.groupValues[2]
                if (file !in subUrls) {
                    subUrls.add(file)
                    subtitleCallback.invoke(SubtitleFile(lang = label, url = fixUrl(file)))
                }
            }
        }
    }

    private suspend fun iframeDecode(data: String, iframe: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var currentIframe = iframe

        if (currentIframe.contains("/player/king/king.php")) {
            currentIframe = currentIframe.replace("king.php?v=", "king.php?wmode=opaque&v=")
            val subDoc = app.get(
                currentIframe,
                referer     = data,
                cookies     = mapOf("LockUser" to "true", "isTrustedUser" to "true"),
                interceptor = interceptor
            ).document
            val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src") ?: return false

            val iDoc          = app.get(subFrame, referer = "${mainUrl}/").text
            extractSubtitles(iDoc, subtitleCallback)

            val cryptData     = Regex("CryptoJS\\.AES\\.decrypt\\(\"(.*)\",\"").find(iDoc)?.groupValues?.get(1) ?: return false
            val cryptPass     = Regex("\",\"(.*)\"\\);").find(iDoc)?.groupValues?.get(1) ?: return false
            val decryptedData = CryptoJS.decrypt(cryptPass, cryptData)
            val decryptedDoc  = Jsoup.parse(decryptedData)

            extractSubtitles(decryptedDoc.html(), subtitleCallback)

            val vidUrl        = Regex("""file: '(.*)',""").find(decryptedDoc.html())?.groupValues?.get(1) ?: return false

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = vidUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    headers = mapOf("Referer" to vidUrl)
                    quality = getQualityFromName("4k")
                }
            )

        } else if (currentIframe.contains("/player/moly/moly.php")) {
            currentIframe = currentIframe.replace("moly.php?h=", "moly.php?wmode=opaque&h=")
            var subDoc = app.get(
                currentIframe,
                referer     = data,
                cookies     = mapOf("LockUser" to "true", "isTrustedUser" to "true"),
                interceptor = interceptor
            ).document

            val atobData = Regex("""unescape\("(.*)"\)""").find(subDoc.html())?.groupValues?.get(1)
            if (atobData != null) {
                val decodedAtob = atobData.decodeUri()
                val strAtob     = String(Base64.decode(decodedAtob, Base64.DEFAULT), StandardCharsets.UTF_8)
                subDoc          = Jsoup.parse(strAtob)
            }

            extractSubtitles(subDoc.html(), subtitleCallback)

            val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src") ?: return false
            loadExtractor(subFrame, "${mainUrl}/", subtitleCallback, callback)

        } else if (currentIframe.contains("/player/haydi.php")) {
            currentIframe = currentIframe.replace("haydi.php?v=", "haydi.php?wmode=opaque&v=")
            var subDoc = app.get(
                currentIframe,
                referer     = data,
                cookies     = mapOf("LockUser" to "true", "isTrustedUser" to "true"),
                interceptor = interceptor
            ).document

            val atobData = Regex("""unescape\("(.*)"\)""").find(subDoc.html())?.groupValues?.get(1)
            if (atobData != null) {
                val decodedAtob = atobData.decodeUri()
                val strAtob     = String(Base64.decode(decodedAtob, Base64.DEFAULT), StandardCharsets.UTF_8)
                subDoc          = Jsoup.parse(strAtob)
            }

            extractSubtitles(subDoc.html(), subtitleCallback)

            val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src") ?: return false
            loadExtractor(subFrame, "${mainUrl}/", subtitleCallback, callback)
        } else if (currentIframe.contains("pichive.online")) {
            val videoId = Regex("""[?&]v=([^&]+)""").find(currentIframe)?.groupValues?.get(1) ?: return false
            val sourceUrl = "https://pichive.online/source2.php?v=$videoId"
            val sourceResp = app.get(sourceUrl, referer = data, interceptor = interceptor).text

            try {
                val json = mapper.readTree(sourceResp)
                val sources = mutableListOf<JsonNode>()

                if (json.has("playlist")) {
                    val playlist = json.get("playlist")
                    if (playlist.isArray) {
                        for (i in 0 until playlist.size()) {
                            val item = playlist.get(i)
                            if (item.has("sources")) {
                                val itemSources = item.get("sources")
                                if (itemSources.isArray) {
                                    for (j in 0 until itemSources.size()) {
                                        sources.add(itemSources.get(j))
                                    }
                                }
                            } else if (item.has("file")) {
                                sources.add(item)
                            }
                        }
                    }
                } else if (json.has("sources")) {
                    val jsonSources = json.get("sources")
                    if (jsonSources.isArray) {
                        for (i in 0 until jsonSources.size()) {
                            sources.add(jsonSources.get(i))
                        }
                    }
                } else if (json.has("file")) {
                    sources.add(json)
                }

                for (src in sources) {
                    val file = src.get("file")?.asText("")?.replace("\\/", "/") ?: continue
                    val title = src.get("title")?.asText(src.get("label")?.asText(this.name)) ?: this.name
                    if (file.contains(".m3u8") || file.contains(".mp4")) {
                        val quality = Regex("""(\d{3,4})[pP]""").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = title,
                                url = file.replace("m.php", "master.m3u8"),
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = currentIframe
                                this.quality = quality
                            }
                        )
                    }
                }

                if (json.has("tracks")) {
                    val tracks = json.get("tracks")
                    if (tracks.isArray) {
                        for (i in 0 until tracks.size()) {
                            val track = tracks.get(i)
                            val file = track.get("file")?.asText("") ?: continue
                            val label = track.get("label")?.asText("") ?: continue
                            if (file.isNotBlank() && label.isNotBlank()) {
                                subtitleCallback.invoke(newSubtitleFile(label, fixUrl(file)) {
                                    headers = mapOf("Referer" to currentIframe)
                                })
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DiziSt", "JSON parse error: ${e.message}")
            }

        } else {
            loadExtractor(currentIframe, "${mainUrl}/", subtitleCallback, callback)
        }

        return true
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZST", "data » $data")
        val document = app.get(
            data,
            cookies     = mapOf("LockUser" to "true", "isTrustedUser" to "true"),
            interceptor = interceptor
        ).document

        var iframe = document.selectFirst("#video-area iframe")?.attr("src") ?: return false
        Log.d("DZST", "iframe » $iframe")

        iframeDecode(data, iframe, subtitleCallback, callback)

        document.select("div.video-toolbar option[value]").forEach {
            val altLink = it.attr("value")
            val subDoc  = app.get(
                altLink,
                cookies     = mapOf("LockUser" to "true", "isTrustedUser" to "true"),
                interceptor = interceptor
            ).document
            iframe = subDoc.selectFirst("#video-area iframe")?.attr("src") ?: return false
            Log.d("DZST", "iframe » $iframe")
            iframeDecode(data, iframe, subtitleCallback, callback)
        }

        return true
    }
}

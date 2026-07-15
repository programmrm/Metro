package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

class Dizist : MainAPI() {
    override var mainUrl              = "https://dizist.live"
    override var name                 = "Dizist"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.AsianDrama, TvType.Anime)

    override var sequentialMainPage        = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

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
        mainUrl to "Yeni Eklenen Dizi Bölümleri",
        "${mainUrl}/yabanci-diziler" to "Yabancı Diziler",
        "${mainUrl}/asyadizileri" to "Asya Dizileri",
        "${mainUrl}/animeler" to "Animeler",
        "${mainUrl}/arsiv" to "Arşiv"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val document = app.get(url, interceptor = interceptor).document

        if (request.name == "Yeni Eklenen Dizi Bölümleri") {
            val sections = mutableListOf<HomePageList>()
            val sectionTitles = listOf(
                "lastSeriesEp" to "Yeni Eklenen Dizi Bölümleri",
                "lastAsiaEp" to "Yeni Eklenen Asya Bölümleri",
                "lastAsiaSeries" to "Yeni Eklenen Asya Dizileri",
                "lastAnimeEp" to "Yeni Eklenen Anime Bölümleri",
                "lastAnimeSeries" to "Yeni Eklenen Animeler",
                "lastEpisodes" to "Yazın En Fazla İzlenen Dizileri"
            )

            for ((id, title) in sectionTitles) {
                val items = document.select("div#result_$id > div.poster-mb-bx, div#result_$id > div.poster-mb-bx")
                    .ifEmpty { document.select("div.area:has(div#result_$id) div.poster-mb-bx") }
                    .ifEmpty { document.select("div.poster-mb-bx") }
                    .mapNotNull { it.toSearchResultFromHome() }

                if (items.isNotEmpty()) {
                    sections.add(HomePageList(title, items))
                }
            }

            if (sections.isEmpty()) {
                val home = document.select("div.poster-mb-bx").mapNotNull { it.toSearchResultFromHome() }
                return newHomePageResponse("Popüler", home)
            }

            return newHomePageResponse(sections)
        }

        val home = document.select("div.poster-mb-bx").mapNotNull { it.toSearchResultFromHome() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResultFromHome(): SearchResponse? {
        val linkEl = this.selectFirst("a[href*='/dizi/']") ?: return null
        val href = fixUrlNull(linkEl.attr("href")) ?: return null
        val title = linkEl.attr("title").ifEmpty {
            this.selectFirst("h2")?.text()?.trim() ?: return null
        }

        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { img ->
            img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        })

        val type = when {
            href.contains("/dizi/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        return newTvSeriesSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val homeDoc = app.get(mainUrl, interceptor = interceptor).document

        val cKey = homeDoc.selectFirst("input[name='cKey']")?.attr("value") ?: return searchFallback(query)
        val cValue = homeDoc.selectFirst("input[name='cValue']")?.attr("value") ?: return searchFallback(query)

        val response = app.post(
            "${mainUrl}/bg/searchcontent",
            data = mapOf(
                "cKey" to cKey,
                "cValue" to cValue,
                "searchTerm" to query
            ),
            referer = mainUrl,
            interceptor = interceptor
        ).text

        val json = try {
            mapper.readValue<SearchResponseData>(response)
        } catch (e: Exception) {
            return searchFallback(query)
        }

        if (json.data?.state != true || json.data?.html.isNullOrBlank()) {
            return searchFallback(query)
        }

        val doc = Jsoup.parse(json.data!!.html)
        return doc.select("a[href*='/dizi/']").mapNotNull { el ->
            val href = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val title = el.attr("title").ifEmpty { el.text().trim() }
            if (title.isBlank()) return@mapNotNull null

            val posterUrl = fixUrlNull(el.selectFirst("img")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
            })

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    private suspend fun searchFallback(query: String): List<SearchResponse> {
        val doc = app.get("${mainUrl}/yabanci-diziler", interceptor = interceptor).document
        return doc.select("div.poster-mb-bx").mapNotNull { it.toSearchResultFromHome() }
            .filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val jsonLd = document.selectFirst("script[type='application/ld+json']")?.data()
        val tvSeriesData = if (jsonLd != null) {
            try {
                mapper.readValue<JsonLdTVSeries>(jsonLd)
            } catch (e: Exception) { null }
        } else null

        val title = tvSeriesData?.name
            ?: document.selectFirst("h1")?.ownText()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val year = tvSeriesData?.let { data ->
            Regex("""\b(19|20)\d{2}\b""").find(data.name ?: "")?.value?.toIntOrNull()
                ?: data.datePublished?.substringBefore("-")?.toIntOrNull()
        } ?: document.selectFirst("h1 span")?.text()?.trim()?.removeSurrounding("(", ")")?.toIntOrNull()

        val poster = fixUrlNull(
            document.selectFirst("div.series-profile-image img")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            }
        )

        val description = tvSeriesData?.description
            ?: document.selectFirst("meta[name='description']")?.attr("content")

        val tags = tvSeriesData?.genre?.filter { it != "/" && it.isNotBlank() }
            ?: document.select("a[href*='/kategori/'], a[href*='/tur/']").mapNotNull { el ->
                el.text().trim().takeIf { it.isNotBlank() }
            }

        val actors = tvSeriesData?.actor?.mapNotNull { actorData ->
            val name = actorData.name?.trim() ?: return@mapNotNull null
            Actor(name, null)
        } ?: document.select("div.series-profile-cast li a").mapNotNull { el ->
            val name = el.selectFirst("h5")?.text()?.trim() ?: return@mapNotNull null
            Actor(name, null)
        }

        val episodes = mutableListOf<Episode>()
        val seasons = tvSeriesData?.containsSeason

        if (seasons != null) {
            for (season in seasons) {
                val seasonNum = season.seasonNumber ?: 1
                val episodeList = season.episode

                if (episodeList is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    for (ep in episodeList as List<JsonLdEpisode>) {
                        val epUrl = ep.url ?: continue
                        val epName = ep.name?.trim() ?: "${seasonNum}. Sezon ${ep.episodeNumber ?: ""}. Bölüm"
                        val epNum = ep.episodeNumber

                        episodes.add(newEpisode(fixUrlNull(epUrl) ?: continue) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                        })
                    }
                } else if (episodeList is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val ep = episodeList as Map<String, Any>
                    val epUrl = ep["url"] as? String ?: continue
                    val epName = ep["name"] as? String ?: "1. Bölüm"
                    val epNum = ep["episodeNumber"] as? Int ?: 1

                    episodes.add(newEpisode(fixUrlNull(epUrl) ?: continue) {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = epNum
                    })
                }
            }
        }

        if (episodes.isEmpty()) {
            document.select("div.swiper-slide a[href*='/izle/']").forEach { el ->
                val epUrl = fixUrlNull(el.attr("href")) ?: return@forEach
                val epTitle = el.selectFirst("h3")?.text()?.trim() ?: "Bölüm"
                val epSmall = el.selectFirst("small")?.text()?.trim() ?: ""

                val seasonMatch = Regex("""(\d+)\. Sezon""").find(epSmall)
                val episodeMatch = Regex("""(\d+)\. Bölüm""").find(epSmall)
                val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episodeNum = episodeMatch?.groupValues?.get(1)?.toIntOrNull()

                episodes.add(newEpisode(epUrl) {
                    this.name = epTitle
                    this.season = seasonNum
                    this.episode = episodeNum
                })
            }
        }

        if (episodes.isEmpty()) {
            val epFromJsonLd = try {
                val allScripts = document.select("script[type='application/ld+json']")
                for (script in allScripts) {
                    val data = script.data()
                    if (data.contains("containsSeason")) {
                        val parsed = mapper.readValue<JsonLdTVSeries>(data)
                        parsed.containsSeason
                    } else null
                }
            } catch (e: Exception) { null }

            val epFromPage = document.select("div.series-watch-season-episode a[href*='/izle/']").mapNotNull { el ->
                val epUrl = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                val epTitle = el.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                val epSmall = el.selectFirst("small")?.text()?.trim() ?: ""

                val seasonMatch = Regex("""(\d+)\. Sezon""").find(epTitle + " " + epSmall)
                val episodeMatch = Regex("""(\d+)\. Bölüm""").find(epTitle + " " + epSmall)
                val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episodeNum = episodeMatch?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epUrl) {
                    this.name = epTitle
                    this.season = seasonNum
                    this.episode = episodeNum
                }
            }
            episodes.addAll(epFromPage)
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = interceptor).document

        val iframeSrc = document.selectFirst("div#tv-spoox2 iframe")?.attr("src") ?: run {
            val altPlayer = document.selectFirst("div.card-video iframe")?.attr("src")
            if (altPlayer != null) fixUrlNull(altPlayer) else null
        } ?: return false

        Log.d("Dizist", "iframe: $iframeSrc")

        loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)

        document.select("div.series-watch-alternatives li a[href*='?player=']").forEach { playerLink ->
            val playerUrl = fixUrlNull(playerLink.attr("href")) ?: return@forEach
            val playerDoc = app.get(playerUrl, interceptor = interceptor).document
            val altIframe = playerDoc.selectFirst("div#tv-spoox2 iframe")?.attr("src")
                ?: playerDoc.selectFirst("div.card-video iframe")?.attr("src")
            if (altIframe != null) {
                loadExtractor(altIframe, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }

    private val mapper by lazy {
        com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}

data class SearchResponseData(
    @JsonProperty("data") val data: SearchResultData? = null
)

data class SearchResultData(
    @JsonProperty("state") val state: Boolean? = false,
    @JsonProperty("html") val html: String? = null
)

data class JsonLdTVSeries(
    @JsonProperty("@type") val type: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("datePublished") val datePublished: String? = null,
    @JsonProperty("genre") val genre: List<String>? = null,
    @JsonProperty("actor") val actor: List<JsonLdActor>? = null,
    @JsonProperty("containsSeason") val containsSeason: List<JsonLdSeason>? = null
)

data class JsonLdActor(
    @JsonProperty("@type") val type: String? = null,
    @JsonProperty("name") val name: String? = null
)

data class JsonLdSeason(
    @JsonProperty("@type") val type: String? = null,
    @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
    @JsonProperty("episode") val episode: Any? = null
)

data class JsonLdEpisode(
    @JsonProperty("@type") val type: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("episodeNumber") val episodeNumber: Int? = null
)

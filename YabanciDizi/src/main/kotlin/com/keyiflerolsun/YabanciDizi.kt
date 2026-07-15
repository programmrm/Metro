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

class YabanciDizi : MainAPI() {
    override var mainUrl              = "https://yabancidizi.life"
    override var name                 = "YabancıDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)

    override var sequentialMainPage        = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    private var cloudflareBypassed = false

    private suspend fun bypassCloudflare() {
        if (cloudflareBypassed) return
        try {
            app.get(mainUrl, interceptor = interceptor)
            cloudflareBypassed = true
        } catch (e: Exception) {
            println("Cloudflare bypass error: ${e.message}")
        }
    }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val body     = response.peekBody(10 * 1024).string()
            val doc      = Jsoup.parse(body)
            val title    = doc.title()

            if (response.code in listOf(403, 503) || title.contains("Cloudflare") || title.contains("Attention Required") || doc.selectFirst("meta[name='cloudflare']") != null) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        mainUrl to "Yeni Eklenenler",
        "${mainUrl}/dizi-izle-hd" to "Tüm Diziler",
        "${mainUrl}/film-izle-hd" to "Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        bypassCloudflare()
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val document = app.get(url, interceptor = interceptor).document

        when (request.data) {
            mainUrl -> {
                val sections = mutableListOf<HomePageList>()

                val homeSections = document.select("div.dark-segment")
                for (section in homeSections) {
                    val titleEl = section.selectFirst("div.segment-title, h2.segment-title")
                    val title = titleEl?.text()?.trim() ?: continue
                    if (title.isBlank()) continue

                    val items = parseHomeSection(section)
                    if (items.isNotEmpty()) {
                        sections.add(HomePageList(title, items))
                    }
                }

                if (sections.isEmpty()) {
                    val fallback = document.select("li.mofy-moviesli").mapNotNull { it.toSearchResult() }
                    if (fallback.isNotEmpty()) return newHomePageResponse("Son Eklenenler", fallback)

                    val fallback2 = document.select("li.segment-poster-sm a[href*='/dizi/'], li.segment-poster-sm a[href*='/film/']").mapNotNull { link ->
                        val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                        val title = link.attr("title").ifEmpty { link.text().trim() }
                        if (title.isBlank()) return@mapNotNull null
                        val posterUrl = fixUrlNull(link.selectFirst("img")?.let { img ->
                            img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                                ?: img.attr("data-src").takeIf { it.isNotBlank() }
                        })
                        val type = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie
                        newTvSeriesSearchResponse(title, href, type) { this.posterUrl = posterUrl }
                    }
                    if (fallback2.isNotEmpty()) return newHomePageResponse("Öne Çıkanlar", fallback2)
                }

                if (sections.isEmpty()) {
                    val allLinks = document.select("a[href*='/dizi/'], a[href*='/film/']").mapNotNull { link ->
                        if (link.selectFirst("img") == null) return@mapNotNull null
                        val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                        val title = link.attr("title").ifEmpty { link.text().trim() }
                        if (title.isBlank()) return@mapNotNull null
                        val posterUrl = fixUrlNull(link.selectFirst("img")?.let { img ->
                            img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                                ?: img.attr("data-src").takeIf { it.isNotBlank() }
                        })
                        val type = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie
                        newTvSeriesSearchResponse(title, href, type) { this.posterUrl = posterUrl }
                    }
                    return newHomePageResponse("Popüler", allLinks)
                }

                return newHomePageResponse(sections)
            }

            "${mainUrl}/film-izle-hd" -> {
                val items = mutableListOf<SearchResponse>()
                val sections = document.select("div.dark-segment")
                for (section in sections) {
                    val titleEl = section.selectFirst("div.segment-title, h2.segment-title")
                    val secTitle = titleEl?.text()?.trim() ?: continue
                    val secItems = parseHomeSection(section)
                    if (secItems.isNotEmpty()) {
                        items.addAll(secItems)
                    }
                }
                if (items.isEmpty()) {
                    val movieItems = document.select("li.mofy-moviesli").mapNotNull { it.toSearchResult() }
                    return newHomePageResponse(request.name, movieItems)
                }
                return newHomePageResponse(request.name, items)
            }

            else -> {
                val items = mutableListOf<SearchResponse>()

                val seriesItems = document.select("li.segment-poster-sm").mapNotNull { el ->
                    val link = el.selectFirst("a[href*='/dizi/']") ?: return@mapNotNull null
                    val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                    val title = link.attr("title").ifEmpty {
                        el.selectFirst("h2")?.text()?.trim() ?: return@mapNotNull null
                    }
                    val posterUrl = fixUrlNull(el.selectFirst("img")?.let { img ->
                        img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                            ?: img.attr("data-src").takeIf { it.isNotBlank() }
                    })
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
                }
                items.addAll(seriesItems)

                if (items.isEmpty()) {
                    val allLinks = document.select("a[href*='/dizi/']").mapNotNull { link ->
                        if (link.selectFirst("img") == null) return@mapNotNull null
                        val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                        val title = link.attr("title").ifEmpty { link.text().trim() }
                        if (title.isBlank()) return@mapNotNull null
                        val posterUrl = fixUrlNull(link.selectFirst("img")?.let { img ->
                            img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                                ?: img.attr("data-src").takeIf { it.isNotBlank() }
                        })
                        newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
                    }
                    return newHomePageResponse(request.name, allLinks)
                }

                return newHomePageResponse(request.name, items)
            }
        }
    }

    private fun parseHomeSection(section: Element): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()

        val movieItems = section.select("li.mofy-moviesli").mapNotNull { it.toSearchResult() }
        items.addAll(movieItems)

        val posterItems = section.select("li.segment-poster-sm").mapNotNull { el ->
            val link = el.selectFirst("a[href*='/dizi/'], a[href*='/film/']") ?: return@mapNotNull null
            val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val title = link.attr("title").ifEmpty {
                el.selectFirst("h2")?.text()?.trim() ?: return@mapNotNull null
            }
            val posterUrl = fixUrlNull(el.selectFirst("img")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
            })
            val type = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie
            newTvSeriesSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        }
        items.addAll(posterItems)

        val featuredItems = section.select("div.poster a[href*='/dizi/'], div.poster a[href*='/film/']").mapNotNull { link ->
            val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val title = link.attr("title").ifEmpty {
                link.selectFirst("h2")?.text()?.trim() ?: return@mapNotNull null
            }
            val posterUrl = fixUrlNull(link.selectFirst("img")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
            })
            val type = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie
            newTvSeriesSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        }
        items.addAll(featuredItems)

        val plainLinks = section.select("a[href*='/dizi/'], a[href*='/film/']").mapNotNull { link ->
            val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val title = link.attr("title").ifEmpty { link.text().trim() }
            if (title.isBlank() || href.contains("tur/") || href.contains("kategori/") || href.contains("koleksiyon/")) return@mapNotNull null
            val posterUrl = fixUrlNull(link.selectFirst("img")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
            })
            val type = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie
            newTvSeriesSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        }
        items.addAll(plainLinks)

        return items.distinctBy { it.url }.take(50)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        bypassCloudflare()
        val homeDoc = app.get(mainUrl, interceptor = interceptor).document

        val cKey = homeDoc.selectFirst("input[name='cKey']")?.attr("value")
        val cValue = homeDoc.selectFirst("input[name='cValue']")?.attr("value")

        if (cKey != null && cValue != null) {
            try {
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

                if (json.data?.state == true && !json.data?.html.isNullOrBlank()) {
                    val doc = Jsoup.parse(json.data!!.html)
                    return doc.select("a[href*='/dizi/'], a[href*='/film/']").mapNotNull { el ->
                        val itemHref = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                        val itemTitle = el.attr("title").ifEmpty { el.text().trim() }
                        if (itemTitle.isBlank()) return@mapNotNull null

                        val posterUrl = fixUrlNull(el.selectFirst("img")?.let { img ->
                            img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                                ?: img.attr("data-src").takeIf { it.isNotBlank() }
                        })

                        val type = if (itemHref.contains("/dizi/")) TvType.TvSeries else TvType.Movie
                        newTvSeriesSearchResponse(itemTitle, itemHref, type) {
                            this.posterUrl = posterUrl
                        }
                    }
                }
            } catch (e: Exception) {
                return searchFallback(query)
            }
        }

        return searchFallback(query)
    }

    private suspend fun searchFallback(query: String): List<SearchResponse> {
        val doc = app.get("${mainUrl}/dizi-izle-hd", interceptor = interceptor).document
        return doc.select("a[href*='/dizi/']").mapNotNull { link ->
            val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val title = link.attr("title").ifEmpty { link.text().trim() }
            if (title.isBlank()) return@mapNotNull null
            if (!title.contains(query, ignoreCase = true)) return@mapNotNull null
            val posterUrl = fixUrlNull(link.selectFirst("img")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
            })
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*='/dizi/'], a[href*='/film/']") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = link.attr("title").ifEmpty {
            this.selectFirst("h2, span.block a")?.text()?.trim() ?: return null
        }

        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { img ->
            img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img.attr("data-src").takeIf { it.isNotBlank() }
        })

        val type = when {
            href.contains("/dizi/") -> TvType.TvSeries
            href.contains("/film/") -> TvType.Movie
            else -> return null
        }

        return newTvSeriesSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        bypassCloudflare()
        val document = app.get(url, interceptor = interceptor).document

        val jsonLd = document.selectFirst("script[type='application/ld+json']")?.data()
        val tvSeriesData = if (jsonLd != null) {
            try {
                mapper.readValue<JsonLdTVSeries>(jsonLd)
            } catch (e: Exception) { null }
        } else null

        val tvType = if (tvSeriesData?.type?.contains("Movie", ignoreCase = true) == true) {
            TvType.Movie
        } else if (tvSeriesData?.type?.contains("TVSeries", ignoreCase = true) == true) {
            TvType.TvSeries
        } else if (url.contains("/film/")) {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

        val title = tvSeriesData?.name
            ?: document.selectFirst("h1.page-title")?.ownText()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val year = tvSeriesData?.let { data ->
            Regex("""\b(19|20)\d{2}\b""").find(data.name ?: "")?.value?.toIntOrNull()
                ?: data.datePublished?.substringBefore("-")?.toIntOrNull()
        } ?: document.selectFirst("h1.page-title span.light-title")?.text()?.trim()
            ?.removeSurrounding("(", ")")?.toIntOrNull()

        val poster = fixUrlNull(
            document.selectFirst("div.bg-cover-bg img")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            } ?: document.selectFirst("a#series-profile-image-wrapper img")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            } ?: tvSeriesData?.image
        )

        val description = tvSeriesData?.description
            ?: document.selectFirst("meta[name='description']")?.attr("content")

        val tags = tvSeriesData?.genre?.filter { it != "/" && it.isNotBlank() }
            ?: document.select("a[href*='/tur/']").mapNotNull { el ->
                el.text().trim().takeIf { it.isNotBlank() }
            }

        val actors = tvSeriesData?.actor?.mapNotNull { actorData ->
            val name = actorData.name?.trim() ?: return@mapNotNull null
            Actor(name, null)
        } ?: document.select("li.artist-photo a, div.series-profile-cast li a").mapNotNull { el ->
            val name = el.selectFirst("h5")?.text()?.trim() ?: return@mapNotNull null
            Actor(name, null)
        }

        val episodes = mutableListOf<Episode>()
        val seasons = tvSeriesData?.containsSeason

        if (tvType == TvType.Movie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                addActors(actors)
            }
        }

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
            document.select("div.episode-container a[href*='/izle/']").mapNotNull { el ->
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
            }.let { episodes.addAll(it) }
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
        bypassCloudflare()
        val document = app.get(data, interceptor = interceptor).document

        val downloadLinks = document.select("a.item[href*='vidmoly']")
        for (link in downloadLinks) {
            val url = link.attr("href")
            if (url.isNotBlank()) {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
        }

        val playerData = document.selectFirst("div#not-loaded")
        if (playerData != null) {
            val whatwehave = playerData.attr("data-whatwehave")
            val lang = playerData.attr("data-lang")
            if (whatwehave.isNotBlank()) {
                try {
                    val resolveUrl = "${mainUrl}/bg/player"
                    val response = app.post(
                        resolveUrl,
                        data = mapOf(
                            "whatwehave" to whatwehave,
                            "lang" to lang
                        ),
                        referer = data,
                        interceptor = interceptor
                    ).text

                    val json = try {
                        mapper.readValue<PlayerResponse>(response)
                    } catch (e: Exception) { null }

                    val link = json?.link
                    if (link != null && link.isNotBlank()) {
                        loadExtractor(link, mainUrl, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.d("YabanciDizi", "Player resolve error: ${e.message}")
                }
            }
        }

        for (alt in document.select("div.item[data-link]")) {
            val encodedLink = alt.attr("data-link")
            if (encodedLink.isNotBlank()) {
                val decoded = try {
                    val bytes = android.util.Base64.decode(encodedLink, android.util.Base64.DEFAULT)
                    if (bytes != null) String(bytes) else null
                } catch (e: Exception) { null }
                if (decoded != null) {
                    loadExtractor(decoded, mainUrl, subtitleCallback, callback)
                }
            }
        }

        val iframeSrc = document.selectFirst("iframe")?.attr("src")
        if (iframeSrc != null) {
            loadExtractor(iframeSrc, mainUrl, subtitleCallback, callback)
        }

        val vidSrc = document.selectFirst("video source")?.attr("src")
            ?: document.selectFirst("video")?.attr("src")
        if (vidSrc != null) {
            callback.invoke(
                newExtractorLink(
                    source = "YabanciDizi",
                    name = "Video",
                    url = vidSrc,
                    type = INFER_TYPE
                ) {
                    quality = getQualityFromName(vidSrc)
                    referer = mainUrl
                }
            )
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

data class PlayerResponse(
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("status") val status: String? = null
)

data class JsonLdTVSeries(
    @JsonProperty("@type") val type: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("datePublished") val datePublished: String? = null,
    @JsonProperty("genre") val genre: List<String>? = null,
    @JsonProperty("image") val image: String? = null,
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

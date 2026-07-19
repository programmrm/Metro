package com.programmer

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element

class FilmHdCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.nl"
    override var name                 = "FilmHdCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc = org.jsoup.Jsoup.parse(response.peekBody(10 * 1024).string())
            if (response.code == 503 || doc.selectFirst("meta[name='cloudflare']") != null) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/category/film-izle-2/" to "Filmler",
        "${mainUrl}/yabancidiziizle-5/"    to "Diziler",
        "${mainUrl}/film-robotu-1/"         to "Keşfet",
        "${mainUrl}/yil/2026/"              to "2026 Yapımları",
        "${mainUrl}/yil/2025-filmleri-izle-3/" to "2025 Yapımları"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && !request.data.contains("/yil/")) {
            "${request.data}?page=$page"
        } else {
            request.data
        }
        val document = app.get(url, interceptor = interceptor).document
        val items = document.select("div.poster[data-token]").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = link.attr("title").ifEmpty {
            this.selectFirst(".poster-info .poster-title, .poster-info .title")?.text()
        }?.trim() ?: return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src").takeIf { it?.isNotBlank() == true }
                ?: img?.attr("src")
        )

        val infoEl = this.selectFirst(".poster-info")
        val yearText = infoEl?.selectFirst(".year, [class*='year']")?.text()?.trim()
        val year = yearText?.toIntOrNull()
        val ratingText = infoEl?.selectFirst(".imdb, [class*='imdb'], .rating")?.text()?.trim()
        val rating = ratingText?.toFloatOrNull()

        val type = if (href.contains("dizi", ignoreCase = true)) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.year = year
            this.score = rating?.let { Score.from10(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "${mainUrl}/category/film-izle-2/",
            interceptor = interceptor
        ).document
        val allItems = document.select("div.poster[data-token]").mapNotNull { it.toSearchResponse() }
        return allItems.filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title = document.selectFirst("h3[class*='title'], h2[class*='title'], .film-title, [class*='title']")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.trim()
                ?.replace(" izle | Hdfilmcehennemi", "")?.replace(" | Hdfilmcehennemi", "")?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("img[data-found='1'][src*='/images/list/poster/']")?.attr("src")
        )

        val description = document.selectFirst("p:contains(Bir), p:contains(bir), .description, [class*='description']")?.text()?.trim()
            ?: document.selectFirst("p:matches(^.{50,})")?.text()?.trim()

        val yearEl = document.selectFirst("a[href*='/yil/']")
        val year = yearEl?.text()?.trim()?.toIntOrNull()

        val tags = document.select("a[href*='/tur/'], a[href*='turler'], [class*='genre'] a, [class*='tur'] a")
            .mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }

        val ratingText = document.selectFirst("[class*='imdb'] span, [class*='Imdb'], [class*='imdb']")?.text()?.trim()
            ?.replace("IMDb Puani", "")?.replace("IMDb", "")?.trim()
        val rating = ratingText?.split("/")?.firstOrNull()?.trim()?.toFloatOrNull()
            ?: ratingText?.split(" ")?.firstOrNull()?.toFloatOrNull()
            ?: ratingText?.toFloatOrNull()

        val durationText = document.selectFirst(":containsOwn(Süre)")?.nextElementSibling()?.text()
            ?: document.selectFirst("[class*='sure'], [class*='duration']")?.text()?.trim()
        val duration = durationText?.let { parseDuration(it) }

        val castNames = document.select("a[href*='/oyuncu/']").map { it.text().trim() }.filter { it.isNotBlank() }
        val actors = castNames.map { Actor(it) }

        val trailer = document.selectFirst("iframe[src*='youtube']")?.attr("src")
            ?: document.selectFirst("a[href*='youtube']")?.attr("href")

        val isSeries = url.contains("dizi", ignoreCase = true)

        val episodes = if (isSeries) {
            val seasonEpisodes = mutableListOf<Episode>()
            seasonEpisodes
        } else {
            null
        }

        val posterInfo = document.selectFirst(".poster-info, .film-info, .movie-info")

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes ?: listOf()) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.duration = duration
                this.score = rating?.let { Score.from10(it) }
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.duration = duration
                this.score = rating?.let { Score.from10(it) }
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = interceptor).document
        val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='rapidrame']")?.attr("src")
            ?: document.selectFirst("#player iframe, .player iframe, [class*='player'] iframe")?.attr("src")
            ?: return false

        loadExtractor(iframe, mainUrl, subtitleCallback, callback)
        return true
    }

    private fun parseDuration(text: String): Int? {
        val regex = Regex("""(\d+)\s*dakika""")
        val match = regex.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()?.let { it * 60 }
    }
}

package com.programmer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DiziYabancıDizi : MainAPI() {
    override var mainUrl              = "https://yabancidizi.life"
    override var name                 = "YabancıDizi"
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
        "${mainUrl}"              to "Yabancı Dizi Son Bölümler",
        "${mainUrl}/dizi-izle-hd" to "Tüm Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            request.data,
            cookies     = mapOf("LockUser" to "true", "isTrustedUser" to "true"),
            interceptor = interceptor,
            cacheTime   = 60
        ).document

        val home = if (request.name == "Yabancı Dizi Son Bölümler") {
            document.select("li.segment-poster-sm").mapNotNull { it.toEpisodeResult() }
        } else {
            document.select("li.segment-poster-sm").mapNotNull { it.toSeriesResult() }
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toEpisodeResult(): SearchResponse? {
        val link = this.selectFirst("a[data-navigo]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = this.selectFirst("h2.truncate")?.text()?.trim() ?: return null

        val img = this.selectFirst("img.lazy-wide")
        val posterUrl = fixUrlNull(
            img?.attr("data-src").takeIf { it?.isNotBlank() == true }
                ?: img?.attr("src")
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun Element.toSeriesResult(): SearchResponse? {
        val link = this.selectFirst("a[data-navigo], a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = link.attr("title").ifEmpty {
            this.selectFirst(".poster-subject h2.truncate")?.text()
                ?: link.attr("title")
        } ?: return null

        val img = this.selectFirst("img.lazy-wide")
        val posterUrl = fixUrlNull(
            img?.attr("data-src").takeIf { it?.isNotBlank() == true }
                ?: img?.attr("src")
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = app.post(
            "${mainUrl}/search?qr=$query",
            cookies     = mapOf("LockUser" to "true", "isTrustedUser" to "true"),
            interceptor = interceptor
        ).text

        val json = try {
            mapper.readTree(resp)
        } catch (e: Exception) {
            return emptyList()
        }

        val results = mutableListOf<SearchResponse>()

        if (json.has("success") && json.get("success").asBoolean()) {
            val data = json.get("data")
            if (data != null && data.has("result") && data.get("result").isArray) {
                data.get("result").forEach { item ->
                    val sType = item.get("s_type")?.asText() ?: return@forEach
                    if (sType == "0") {
                        val name = item.get("s_name")?.asText() ?: return@forEach
                        val link = item.get("s_link")?.asText() ?: return@forEach
                        val image = item.get("s_image")?.asText()

                        val posterUrl = if (image != null) {
                            fixUrlNull("/uploads/series/cover/$image")
                        } else null

                        results.add(newTvSeriesSearchResponse(name, "${mainUrl}/dizi/$link", TvType.TvSeries) {
                            this.posterUrl = posterUrl
                        })
                    }
                }
            }
        }

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            cookies     = mapOf("LockUser" to "true", "isTrustedUser" to "true"),
            interceptor = interceptor
        ).document

        val title = document.selectFirst("title")?.text()?.trim()
            ?.replace(" - yabancıdizi", "")?.trim()
            ?: return null

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf()) {
            this.plot = document.selectFirst("meta[name='description']")?.attr("content")
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return false
    }
}

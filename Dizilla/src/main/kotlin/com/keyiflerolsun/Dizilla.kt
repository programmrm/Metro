// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Dizilla : MainAPI() {
    override var mainUrl = "https://dizillahd.com"
    override var name = "Dizilla"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to mainUrl
    )

    private val apiCookies = mapOf("showAllDaFull" to "true")

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())
            if (doc.html().contains("verifying")) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/?page=1" to "Güncel Bölümler",
        "${mainUrl}/?page=2" to "Yeni Eklenenler",
        "${mainUrl}/?page=3" to "Popüler",
        "${mainUrl}/?page=4" to "Trend",
        "${mainUrl}/?page=5" to "En Son Sezon"
    )

    private val mapper by lazy {
        ObjectMapper().registerModule(KotlinModule.Builder().build()).apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    private suspend fun initializeCloudflare() {
        try {
            app.get(mainUrl, interceptor = interceptor, headers = commonHeaders)
        } catch (e: Exception) {
            println("Cloudflare init error: ${e.message}")
        }
    }

    private fun seriesFromJsonArray(array: JsonNode): List<SearchResponse> {
        val result = mutableListOf<SearchResponse>()
        for (i in 0 until array.size()) {
            val item = array.get(i)
            val title = item.get("title")?.asText()
                ?: item.get("name")?.asText()
                ?: item.get("object_name")?.asText()
                ?: continue
            val slug = item.get("slug")?.asText()
                ?: item.get("used_slug")?.asText()
                ?: continue
            val cleanSlug = slug.removePrefix("dizi/")
            val posterCandidates = listOfNotNull(
                item.get("face_url")?.asText(),
                item.get("poster_url")?.asText(),
                item.get("brand_url")?.asText(),
                item.get("square_url")?.asText(),
                item.get("poster")?.asText(),
                item.get("image")?.asText(),
                item.get("poster_path")?.asText(),
                item.get("object_poster_url")?.asText()
            )
            val rawPoster = posterCandidates.firstOrNull { !it.contains("cdn.ampproject.org") }
                ?: posterCandidates.firstOrNull()

            val poster = when {
                rawPoster.isNullOrEmpty() -> null
                rawPoster.startsWith("http") -> rawPoster
                rawPoster.startsWith("/") -> "$mainUrl$rawPoster"
                else -> "$mainUrl/$rawPoster"
            }

result.add(newTvSeriesSearchResponse(title, fixUrl("/dizi/$cleanSlug"), TvType.TvSeries) {
                this.posterUrl = poster
                this.posterHeaders = commonHeaders
            })
        }
        return result
    }

    private fun parseSeriesResponse(jsonText: String): List<SearchResponse>? {
        try {
            val rootNode = mapper.readTree(jsonText)

            val resultArray = rootNode.get("result")
            if (resultArray != null && resultArray.isArray && resultArray.size() > 0) {
                return seriesFromJsonArray(resultArray)
            }

            val dataNode = rootNode.get("data")
            if (dataNode != null && dataNode.isObject) {
                val innerResult = dataNode.get("result")
                if (innerResult != null && innerResult.isArray && innerResult.size() > 0) {
                    return seriesFromJsonArray(innerResult)
                }
            }
        } catch (e: Exception) {
            // not plain JSON, might be encrypted
        }

        try {
            val decrypted = decryptAES(jsonText)
            if (decrypted != null) {
                val rootNode = mapper.readTree(decrypted)

                val resultArray = rootNode.get("result")
                if (resultArray != null && resultArray.isArray && resultArray.size() > 0) {
                    return seriesFromJsonArray(resultArray)
                }

                val dataNode = rootNode.get("data")
                if (dataNode != null && dataNode.isObject) {
                    val innerResult = dataNode.get("result")
                    if (innerResult != null && innerResult.isArray && innerResult.size() > 0) {
                        return seriesFromJsonArray(innerResult)
                    }
                }
            }
        } catch (e: Exception) {
            // decryption failed
        }

        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            initializeCloudflare()
            if (page > 1) return newHomePageResponse(request.name, emptyList())

            val document = app.get(mainUrl, interceptor = interceptor, headers = commonHeaders, cookies = apiCookies).document
            val scriptData = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return newHomePageResponse(request.name, emptyList())
            val secureData = mapper.readTree(scriptData).get("props")?.get("pageProps")?.get("secureData")?.asText() ?: return newHomePageResponse(request.name, emptyList())
            val decrypted = decryptAES(secureData) ?: return newHomePageResponse(request.name, emptyList())
            val data = mapper.readTree(decrypted)

            val catKey = when (request.name) {
                "Güncel Bölümler" -> "getEpisodesOnBrandAll"
                "Yeni Eklenenler" -> "getLastSeriesAll"
                "Popüler" -> "allPopularSeries"
                "Trend" -> "getTrendSeries"
                "En Son Sezon" -> "getEpisodesOnNewSeason"
                else -> return newHomePageResponse(request.name, emptyList())
            }

            val rawNode = data.get(catKey) ?: return newHomePageResponse(request.name, emptyList())
            val arr = if (rawNode.isArray) rawNode else rawNode.get("items") ?: return newHomePageResponse(request.name, emptyList())

            val items = mutableListOf<SearchResponse>()
            for (i in 0 until arr.size()) {
                val item = arr.get(i) ?: continue
                val slug = item.get("used_slug")?.asText()
                    ?: item.get("episode_used_slug")?.asText()
                    ?: item.get("slug")?.asText()
                    ?: continue
                val url = if (slug.startsWith("dizi/") || slug.startsWith("/")) fixUrl(slug) else "$mainUrl/$slug"
                items.add(
                    newTvSeriesSearchResponse(
                        item.get("original_title")?.asText() ?: item.get("culture_title")?.asText() ?: continue,
                        url,
                        TvType.TvSeries
                    ) {
                        val posterCandidates = listOfNotNull(
                            item.get("face_url")?.asText(),
                            item.get("poster_url")?.asText(),
                            item.get("brand_url")?.asText(),
                            item.get("square_url")?.asText(),
                            item.get("poster")?.asText(),
                            item.get("image")?.asText(),
                            item.get("poster_path")?.asText(),
                            item.get("object_poster_url")?.asText(),
                            item.get("still_path")?.asText()
                        )
                        val rawPoster = posterCandidates.firstOrNull { 
                            !it.contains("cdn.ampproject.org")
                        } ?: posterCandidates.firstOrNull()
                        this.posterUrl = when {
                            rawPoster.isNullOrEmpty() -> null
                            rawPoster.startsWith("http") -> rawPoster
                            rawPoster.startsWith("/") -> "$mainUrl$rawPoster"
                            else -> "$mainUrl/$rawPoster"
                        }
                        this.posterHeaders = commonHeaders
                    }
                )
            }

            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            e.printStackTrace()
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            initializeCloudflare()
            val response = app.get(
                url = "${mainUrl}/api/bg/findSeries?queryStr=${query.trim()}&currentPageCount=50",
                interceptor = interceptor,
                headers = commonHeaders,
                cookies = apiCookies
            )
            if (!response.isSuccessful) return emptyList()

            parseSeriesResponse(response.text) ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return try {
            initializeCloudflare()
            val document = app.get(url, interceptor = interceptor, headers = commonHeaders, cookies = apiCookies).document

            val scriptData = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
            val pageProps = mapper.readTree(scriptData).get("props")?.get("pageProps") ?: return null
            val secureData = pageProps.get("secureData")?.asText() ?: return null
            val decrypted = decryptAES(secureData) ?: return null
            val data = mapper.readTree(decrypted)

            val contentItem = data.get("contentItem")

            val title = contentItem?.get("original_title")?.asText()
                ?: contentItem?.get("culture_title")?.asText()
                ?: data.get("name")?.asText()
                ?: data.get("title")?.asText()
                ?: document.selectFirst("h1")?.text()
                ?: return null

            val posterCandidates = listOfNotNull(
                contentItem?.get("face_url")?.asText(),
                contentItem?.get("poster_url")?.asText(),
                contentItem?.get("brand_url")?.asText(),
                contentItem?.get("square_url")?.asText(),
                data.get("poster_path")?.asText(),
                data.get("poster")?.asText()
            )
            val posterPath = posterCandidates.firstOrNull { !it.contains("cdn.ampproject.org") }
                ?: posterCandidates.firstOrNull()
            val poster = when {
                posterPath.isNullOrEmpty() -> fixUrlNull(document.selectFirst("img[alt*='$title']")?.attr("src"))
                posterPath.startsWith("http") -> posterPath
                posterPath.startsWith("/") -> "$mainUrl$posterPath"
                else -> "$mainUrl/$posterPath"
            }

            val description = contentItem?.get("description")?.asText()
                ?: data.get("description")?.asText()
                ?: data.get("overview")?.asText()
                ?: document.selectFirst("p, div.text-sm, div.mt-2")?.text()?.trim()

            val year = contentItem?.get("release_year")?.asInt()
                ?: data.get("year")?.asInt()
                ?: data.get("release_date")?.asText()?.takeLast(4)?.toIntOrNull()

            val tags = mutableListOf<String>()
            val categories = contentItem?.get("categories")?.asText()
            if (!categories.isNullOrEmpty()) {
                tags.addAll(categories.split(",").map { it.trim() })
            }
            if (tags.isEmpty()) {
                val genresNode = data.get("genres")
                if (genresNode != null && genresNode.isArray) {
                    for (i in 0 until genresNode.size()) tags.add(genresNode.get(i).asText())
                }
            }
            if (tags.isEmpty()) document.select("a[href*='dizi-turu']").forEach { tags.add(it.text()) }

            val scoreValue = contentItem?.get("imdb_point")?.asDouble()
                ?: contentItem?.get("vote_point")?.asDouble()
                ?: data.get("vote_average")?.asDouble()
                ?: data.get("score")?.asDouble()
            val score = scoreValue?.let { Score.from10(it) }

            val episodes = mutableListOf<Episode>()
            val seasonsResult = data.get("RelatedResults")
                ?.get("getSerieSeasonAndEpisodes")
                ?.get("result")

            if (seasonsResult != null && seasonsResult.isArray) {
                for (s in 0 until seasonsResult.size()) {
                    val seasonNode = seasonsResult.get(s)
                    val season = seasonNode.get("season_no")?.asInt() ?: seasonNode.get("season")?.asInt() ?: 1
                    val episodesNode = seasonNode.get("episodes") ?: seasonNode.get("bolumler")
                    if (episodesNode != null && episodesNode.isArray) {
                        for (e in 0 until episodesNode.size()) {
                            val epNode = episodesNode.get(e)
                            val epName = epNode.get("episode_text")?.asText() ?: epNode.get("name")?.asText() ?: epNode.get("title")?.asText() ?: ""
                            val epSlug = epNode.get("used_slug")?.asText() ?: epNode.get("slug")?.asText() ?: epNode.get("url")?.asText()
                            val epHref = when {
                                epSlug.isNullOrEmpty() -> null
                                epSlug.startsWith("http") -> epSlug
                                epSlug.startsWith("/") -> "$mainUrl$epSlug"
                                else -> "$mainUrl/$epSlug"
                            } ?: continue
                            val epEpisode = epNode.get("episode_no")?.asInt() ?: epNode.get("episode_number")?.asInt() ?: epNode.get("episode")?.asInt()
                            val epPoster = epNode.get("still_path")?.asText() ?: epNode.get("poster")?.asText()

                            episodes.add(newEpisode(epHref) {
                                this.name = epName
                                this.season = season
                                this.episode = epEpisode
                                this.posterUrl = epPoster
                            })
                        }
                    }
                }
            }

            if (episodes.isEmpty()) {
                document.selectXpath("//a[contains(@href, 'sezon') and contains(@href, 'bolum')]").forEach {
                    val href = fixUrlNull(it.attr("href")) ?: return@forEach
                    val text = it.text()
                    val season = Regex("""(\d+)\.?\s*Sezon""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
                    val episode = Regex("""(\d+)\.?\s*Bölüm""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
                    episodes.add(newEpisode(href) {
                        this.name = text
                        this.season = season ?: 1
                        this.episode = episode
                    })
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = commonHeaders
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            initializeCloudflare()
            Log.d("DZL", "data » $data")
            val document = app.get(data, interceptor = interceptor, headers = commonHeaders, cookies = apiCookies).document

            val script = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return false
            val pageProps = mapper.readTree(script).get("props")?.get("pageProps") ?: return false
            val secureData = pageProps.get("secureData")?.asText() ?: return false
            val decrypted = decryptAES(secureData) ?: return false
            val sourceData = mapper.readTree(decrypted)

            val sourceContent = sourceData.get("source_content")?.asText()
                ?: sourceData.get("RelatedResults")?.get("getEpisodeSources")?.get("result")?.get(0)?.get("source_content")?.asText()
                ?: return false

            val cleanedSource = sourceContent.replace("\\", "")
            val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(cleanedSource)?.groupValues?.get(1) ?: return false

            val fixedIframe = if (iframe.startsWith("//")) "https:$iframe" else iframe

            Log.d("DZL", "iframe » $fixedIframe")

            val videoId = Regex("""v=([^&]+)""").find(fixedIframe)?.groupValues?.get(1)
            if (videoId != null) {
                val baseUrl = Regex("""https?://[^/]+""").find(fixedIframe)?.value
                if (baseUrl != null) {
                    try {
                        val iframeHtml = app.get(fixedIframe, referer = mainUrl).text
                        Regex(""""file":"([^"]+)","label":"([^"]+)"""").findAll(iframeHtml).forEach {
                            val (subUrl, subLang) = it.destructured
                            val lang = subLang.replace("\\u0131", "ı").replace("\\u0130", "İ").replace("\\u00fc", "ü").replace("\\u00e7", "ç")
                            subtitleCallback.invoke(SubtitleFile(lang, subUrl.replace("\\", "")))
                        }
                    } catch (_: Exception) { }

                    val sourceUrl = "$baseUrl/source2.php?v=$videoId"
                    try {
                        val jsonText = app.get(sourceUrl, headers = commonHeaders, referer = mainUrl).text
                        val root = mapper.readTree(jsonText)
                        val playlist = root.get("playlist")
                        if (playlist != null && playlist.isArray) {
                            for (i in 0 until playlist.size()) {
                                val item = playlist.get(i)
                                val sources = item.get("sources")
                                if (sources != null && sources.isArray && sources.size() > 0) {
                                    val file = sources.get(0).get("file")?.asText() ?: continue
                                    val title = sources.get(0).get("title")?.asText() ?: "Kaynak ${i + 1}"
                                    val m3u8 = file.replace("m.php", "master.m3u8")
                                    callback.invoke(
                                        newExtractorLink(
                                            source = this.name,
                                            name = "${this.name} - $title",
                                            url = m3u8,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = "$baseUrl/"
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                }
                            }
                            return true
                        }
                    } catch (_: Exception) { }
                }
            }

            loadExtractor(fixedIframe, "${mainUrl}/", subtitleCallback, callback)
            true
        } catch (e: Exception) {
            Log.e("DZL", "loadLinks error: ${e.message}")
            false
        }
    }

    companion object {
        private val AES_KEY = "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI".toByteArray()

        private fun decryptAES(encryptedData: String): String? {
            return try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), IvParameterSpec(ByteArray(16)))
                String(cipher.doFinal(Base64.decode(encryptedData, Base64.DEFAULT)))
            } catch (e: Exception) {
                Log.e("Dizilla", "Decryption failed: ${e.message}")
                null
            }
        }
    }
}

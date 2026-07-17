package com.programmer

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 0L
    override var sequentialMainPageScrollDelay = 0L

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to mainUrl,
    )

    private val apiCookies = mapOf("showAllDaFull" to "true")

    private val mapper by lazy {
        ObjectMapper().registerModule(KotlinModule.Builder().build()).apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    private suspend fun getSecureData(): String? {
        return try {
            val doc = app.get(mainUrl, headers = commonHeaders, cookies = apiCookies).document
            val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
            mapper.readTree(scriptData).get("props")?.get("pageProps")?.get("secureData")?.asText()
        } catch (e: Exception) {
            Log.e("Dizilla", "getSecureData error: ${e.message}")
            null
        }
    }

    private fun decryptAndParse(secureData: String): JsonNode? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), IvParameterSpec(ByteArray(16)))
            val decrypted = String(cipher.doFinal(Base64.decode(secureData, Base64.DEFAULT)))
            mapper.readTree(decrypted)
        } catch (e: Exception) {
            Log.e("Dizilla", "Decrypt error: ${e.message}")
            null
        }
    }

    private fun pickPoster(item: JsonNode): String? {
        for (key in listOf("poster_url", "face_url", "back_url", "brand_url", "logo_url")) {
            val url = item.get(key)?.asText() ?: continue
            if (url.contains("file.macellan.online")) continue
            if (url.endsWith("/")) continue
            return url
        }
        return null
    }

    private fun extractItems(data: JsonNode, catKey: String): List<SearchResponse> {
        val rawNode = data.get(catKey) ?: return emptyList()
        val arr = if (rawNode.isArray) rawNode else rawNode.get("items") ?: return emptyList()

        val items = mutableListOf<SearchResponse>()
        for (i in 0 until arr.size()) {
            val item = arr.get(i) ?: continue
            val slug = item.get("used_slug")?.asText() ?: continue
            items.add(
                newTvSeriesSearchResponse(
                    item.get("original_title")?.asText() ?: item.get("culture_title")?.asText() ?: continue,
                    "$mainUrl/$slug",
                    TvType.TvSeries
                ) {
                    this.posterUrl = pickPoster(item)
                }
            )
        }
        return items
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/?page=1" to "Yeni Eklenenler",
        "${mainUrl}/?page=2" to "Popüler",
        "${mainUrl}/?page=3" to "Trend",
        "${mainUrl}/?page=4" to "Yeni Bölümler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        try {
            val secureData = getSecureData() ?: return newHomePageResponse(request.name, emptyList())
            val data = decryptAndParse(secureData) ?: return newHomePageResponse(request.name, emptyList())

            val catKey = when (request.name) {
                "Yeni Eklenenler" -> "getLastSeriesAll"
                "Popüler" -> "allPopularSeries"
                "Trend" -> "getSeriesByAdvancedSliderWithDetail"
                "Yeni Bölümler" -> "getEpisodesOnNewSeries"
                else -> return newHomePageResponse(request.name, emptyList())
            }

            val items = extractItems(data, catKey)
            Log.d("Dizilla", "${request.name}: ${items.size} items")
            return newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            Log.e("Dizilla", "getMainPage error: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val response = app.get(
                "${mainUrl}/api/bg/findSeries?queryStr=$query&currentPageCount=50",
                headers = commonHeaders,
                cookies = apiCookies
            )
            if (!response.isSuccessful) return emptyList()

            val rootNode = mapper.readTree(response.text)
            val resultArray = rootNode.get("result")
            if (resultArray != null && resultArray.isArray && resultArray.size() > 0) {
                return resultArray.mapNotNull { item ->
                    val title = item.get("title")?.asText() ?: item.get("name")?.asText() ?: return@mapNotNull null
                    val slug = item.get("slug")?.asText() ?: item.get("used_slug")?.asText() ?: return@mapNotNull null
                    val poster = item.get("poster")?.asText() ?: item.get("image")?.asText() ?: item.get("face_url")?.asText()
                    newTvSeriesSearchResponse(title, "$mainUrl/$slug", TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                }
            }

            val dataNode = rootNode.get("data")
            if (dataNode != null && dataNode.isObject) {
                val innerResult = dataNode.get("result")
                if (innerResult != null && innerResult.isArray && innerResult.size() > 0) {
                    return innerResult.mapNotNull { item ->
                        val title = item.get("title")?.asText() ?: item.get("name")?.asText() ?: return@mapNotNull null
                        val slug = item.get("slug")?.asText() ?: item.get("used_slug")?.asText() ?: return@mapNotNull null
                        val poster = item.get("poster")?.asText() ?: item.get("image")?.asText() ?: item.get("face_url")?.asText()
                        newTvSeriesSearchResponse(title, "$mainUrl/$slug", TvType.TvSeries) {
                            this.posterUrl = poster
                        }
                    }
                }
            }

            val decrypted = decryptAES(response.text)
            if (decrypted != null) {
                val decNode = mapper.readTree(decrypted)
                val r1 = decNode.get("result")
                if (r1 != null && r1.isArray) {
                    return r1.mapNotNull { item ->
                        val title = item.get("title")?.asText() ?: item.get("name")?.asText() ?: return@mapNotNull null
                        val slug = item.get("slug")?.asText() ?: item.get("used_slug")?.asText() ?: return@mapNotNull null
                        val poster = item.get("poster")?.asText() ?: item.get("image")?.asText() ?: item.get("face_url")?.asText()
                        newTvSeriesSearchResponse(title, "$mainUrl/$slug", TvType.TvSeries) {
                            this.posterUrl = poster
                        }
                    }
                }
                val d2 = decNode.get("data")
                if (d2 != null && d2.isObject) {
                    val r2 = d2.get("result")
                    if (r2 != null && r2.isArray) {
                        return r2.mapNotNull { item ->
                            val title = item.get("title")?.asText() ?: item.get("name")?.asText() ?: return@mapNotNull null
                            val slug = item.get("slug")?.asText() ?: item.get("used_slug")?.asText() ?: return@mapNotNull null
                            val poster = item.get("poster")?.asText() ?: item.get("image")?.asText() ?: item.get("face_url")?.asText()
                            newTvSeriesSearchResponse(title, "$mainUrl/$slug", TvType.TvSeries) {
                                this.posterUrl = poster
                            }
                        }
                    }
                }
            }

            emptyList()
        } catch (e: Exception) {
            Log.e("Dizilla", "search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, headers = commonHeaders, cookies = apiCookies).document
            val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
            val pageProps = mapper.readTree(scriptData).get("props")?.get("pageProps") ?: return null
            val secureData = pageProps.get("secureData")?.asText() ?: return null
            val decrypted = decryptAES(secureData) ?: return null
            val data = mapper.readTree(decrypted)

            val title = data.get("name")?.asText() ?: data.get("title")?.asText() ?: doc.selectFirst("h1")?.text() ?: return null

            val posterPath = data.get("poster_path")?.asText() ?: data.get("poster")?.asText()
            val poster = when {
                posterPath.isNullOrEmpty() -> fixUrlNull(doc.selectFirst("img[alt*='$title']")?.attr("src"))
                posterPath.startsWith("http") -> posterPath
                else -> "$mainUrl$posterPath"
            }

            val description = data.get("description")?.asText() ?: data.get("overview")?.asText() ?: doc.selectFirst("p, div.text-sm, div.mt-2")?.text()?.trim()

            val year = data.get("year")?.asInt() ?: data.get("release_date")?.asText()?.takeLast(4)?.toIntOrNull()

            val tags = mutableListOf<String>()
            val genresNode = data.get("genres")
            if (genresNode != null && genresNode.isArray) {
                for (i in 0 until genresNode.size()) tags.add(genresNode.get(i).asText())
            }

            val scoreValue = data.get("vote_average")?.asDouble() ?: data.get("score")?.asDouble()
            val score = scoreValue?.let { Score.from10(it) }

            val episodes = mutableListOf<Episode>()
            val seasonsNode = data.get("seasons") ?: data.get("sezonlar")
            if (seasonsNode != null && seasonsNode.isArray) {
                for (s in 0 until seasonsNode.size()) {
                    val seasonNode = seasonsNode.get(s)
                    val season = seasonNode.get("season_number")?.asInt() ?: seasonNode.get("season")?.asInt() ?: 1
                    val episodesNode = seasonNode.get("episodes") ?: seasonNode.get("bolumler")
                    if (episodesNode != null && episodesNode.isArray) {
                        for (e in 0 until episodesNode.size()) {
                            val epNode = episodesNode.get(e)
                            val epName = epNode.get("name")?.asText() ?: epNode.get("title")?.asText() ?: ""
                            val epSlug = epNode.get("slug")?.asText() ?: epNode.get("url")?.asText() ?: epNode.get("episode_used_slug")?.asText()
                            val epHref = when {
                                epSlug.isNullOrEmpty() -> null
                                epSlug.startsWith("http") -> epSlug
                                epSlug.startsWith("/") -> "$mainUrl$epSlug"
                                else -> "$mainUrl/$epSlug"
                            } ?: continue
                            val epEpisode = epNode.get("episode_number")?.asInt() ?: epNode.get("episode")?.asInt()
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

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
            }
        } catch (e: Exception) {
            Log.e("Dizilla", "load error: ${e.message}")
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
            Log.d("Dizilla", "loadLinks: $data")
            val doc = app.get(data, headers = commonHeaders, cookies = apiCookies).document
            val script = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return false
            val pageProps = mapper.readTree(script).get("props")?.get("pageProps") ?: return false
            val secureData = pageProps.get("secureData")?.asText() ?: return false
            val decrypted = decryptAES(secureData) ?: return false
            val sourceData = mapper.readTree(decrypted)

            val sourceContent = sourceData.get("source_content")?.asText()
                ?: sourceData.get("RelatedResults")?.get("getEpisodeSources")?.get("result")?.get(0)?.get("source_content")?.asText()
                ?: return false

            val cleanedSource = sourceContent.replace("\\", "")
            val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(cleanedSource)?.groupValues?.get(1) ?: return false

            Log.d("Dizilla", "iframe: $iframe")
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
            true
        } catch (e: Exception) {
            Log.e("Dizilla", "loadLinks error: ${e.message}")
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

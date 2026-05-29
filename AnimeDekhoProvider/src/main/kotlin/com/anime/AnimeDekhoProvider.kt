package com.anime

import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element

open class AnimeDekhoProvider : MainAPI() {
    override var mainUrl = "https://animedekho.app"
    override var name = "Anime Dekho"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    override val supportedTypes =
        setOf(
            TvType.Cartoon,
            TvType.Anime,
            TvType.AnimeMovie,
            TvType.Movie,
        )

    override val mainPage = mainPageOf(
        """{"taxonomy":"none","search":"none","term":"none","type":"series"}""" to "Series",
        """{"taxonomy":"none","search":"none","term":"none","type":"movie"}""" to "Movies",
        """{"taxonomy":"category","search":"none","term":"anime","type":"none"}""" to "Anime",
        """{"taxonomy":"category","search":"none","term":"cartoon","type":"none"}""" to "Cartoon",
        """{"taxonomy":"category","search":"none","term":"hindi-dub","type":"none"}""" to "Hindi Dub",
        """{"taxonomy":"category","search":"none","term":"tamil","type":"none"}""" to "Tamil",
        """{"taxonomy":"category","search":"none","term":"telugu","type":"none"}""" to "Telugu",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = when {
            request.data.contains("\"type\":\"series\"") -> "$mainUrl/serie/"
            request.data.contains("\"type\":\"movie\"") -> "$mainUrl/movie/"
            else -> {
                val term = Regex("\"term\":\"([^\"]+)\"").find(request.data)?.groupValues?.get(1) ?: ""
                "$mainUrl/category/$term/"
            }
        }

        val pageDoc = app.get(pageUrl).document

        val nonce = Regex("\"nonce\":\"([^\"]+)\"")
            .find(pageDoc.html())?.groupValues?.get(1) ?: ""

        val filterEl = pageDoc.selectFirst("[data-taxonomy]")
        val taxonomy = filterEl?.attr("data-taxonomy") ?: "none"
        val termVal = filterEl?.attr("data-term") ?: "none"
        val searchVal = filterEl?.attr("data-search") ?: "none"
        val typeVal = filterEl?.attr("data-type") ?: "none"

        val vars = """{"_wpsearch":"$nonce","taxonomy":"$taxonomy","search":"$searchVal","term":"$termVal","type":"$typeVal","genres":[],"years":[],"sort":1,"page":$page}"""

        val response = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf(
                "action" to "action_search",
                "vars" to vars
            ),
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "X-WP-Nonce" to nonce,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to pageUrl
            )
        )

        val document = response.document
        val home = document.select("article").mapNotNull { it.toSearchResult() }

        val finalHome = if (page == 1) {
            pageDoc.select("article").mapNotNull { it.toSearchResult() }
        } else {
            home
        }

        val hasNextPage = home.isNotEmpty()
        return newHomePageResponse(request.name, finalHome, hasNextPage)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = this.selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val title = this.selectFirst("header h2")?.text() ?: "null"
        var posterUrl = this.selectFirst("div figure img")?.attr("src")
        if (posterUrl!!.contains("data:image")) {
            posterUrl = this.selectFirst("div figure img")?.attr("data-lazy-src")
        }
        return newAnimeSearchResponse(title, Media(href, posterUrl).toJson(), TvType.Anime, false) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<AnimeSearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("ul[data-results] li article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val media = parseJson<Media>(url)
        val document = app.get(media.url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()?.substringAfter("Watch Online ")
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringAfter("Watch Online ")?.substringBefore(" Movie in Hindi Dubbed Free") ?: "No Title"
        val poster = fixUrlNull(document.selectFirst("div.post-thumbnail figure img")?.attr("src") ?: media.poster)
        val plot = document.selectFirst("div.entry-content p")?.text()?.trim()
            ?: document.selectFirst("meta[name=twitter:description]")?.attr("content")
        val year = (document.selectFirst("span.year")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:updated_time]")?.attr("content")
                ?.substringBefore("-"))?.toIntOrNull()
        val lst = document.select("ul.seasons-lst li")

        return if (lst.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, Media(
                media.url,
                mediaType = 1
            ).toJson()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            val episodes = document.select("ul.seasons-lst li").mapNotNull {
                val name = it.selectFirst("h3.title")?.ownText() ?: "null"
                val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val poster = it.selectFirst("div > div > figure > img")?.attr("src")
                val seasonnumber = it.selectFirst("h3.title > span")?.text().toString().substringAfter("S").substringBefore("-")
                val season = seasonnumber.toIntOrNull()
                newEpisode(Media(href, mediaType = 2).toJson()) {
                    this.name = name
                    this.posterUrl = poster
                    this.season = season
                }
            }
            val recommendations = document.select("div.swiper-wrapper article").map {
                val recName = it.selectFirst("h2")?.text() ?: "Unknown"
                val recHref = it.selectFirst("a")!!.attr("href")
                val recPosterUrl = it.selectFirst("figure img")?.attr("src")
                val mediadata = Media(
                    url = recHref,
                    poster = recPosterUrl,
                    mediaType = 0
                )
                val mediaJson = Gson().toJson(mediadata)
                newTvSeriesSearchResponse(recName, mediaJson, TvType.TvSeries) {
                    this.posterUrl = mediadata.poster
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val media = runCatching { parseJson<Media>(data) }.getOrElse {
            Log.e("Error:", "Failed to parse media JSON $it")
            return false
        }

        //VidStream
        val headers = mapOf("Cookie" to "toronites_server=vidstream")
        val doc = app.get(media.url, headers = headers).document
        doc.select("iframe.serversel[src]").forEach { iframe ->
            val serverUrl = iframe.attr("src")
            if (serverUrl.isBlank()) return@forEach

            val innerIframeUrl = runCatching {
                app.get(serverUrl).document
                    .selectFirst("iframe[src]")
                    ?.attr("src")
            }.getOrNull()

            if (!innerIframeUrl.isNullOrBlank()) {
                loadExtractor(innerIframeUrl, subtitleCallback, callback)
            }
        }

        val bodyClass = runCatching {
            app.get(media.url).document.selectFirst("body")?.attr("class")
        }.getOrNull()

        val term = Regex("(?:term|postid)-(\\d+)").find(bodyClass ?: "")
            ?.groupValues?.getOrNull(1)

        if (term.isNullOrEmpty()) {
            Log.e("Error:", "No postid/term ID found in body class: $bodyClass")
            return false
        }

        var success = false
        for (i in 0..10) {
            val iframeUrl = runCatching {
                app.get("$mainUrl/?trdekho=$i&trid=$term&trtype=${media.mediaType}")
                    .document.selectFirst("iframe")?.attr("src")
            }.getOrNull()
            if (!iframeUrl.isNullOrEmpty()) {
                Log.d("Error:", "Found iframe: $iframeUrl")
                runCatching {
                    loadExtractor(iframeUrl, subtitleCallback, callback)
                    success = true
                }.onFailure {
                    Log.e("Error:", "Failed to load extractor for $iframeUrl $it")
                }
            } else {
                Log.w("Error:", "No iframe found for iteration $i")
            }
        }

        return success
    }

    data class Media(val url: String, val poster: String? = null, val mediaType: Int? = null)

}

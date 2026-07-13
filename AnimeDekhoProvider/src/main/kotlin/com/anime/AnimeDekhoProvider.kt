package com.anime

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

open class AnimeDekhoProvider : MainAPI() {
    override var mainUrl             = "https://animedekho.app"
    override var name                = "Anime Dekho"
    override val hasMainPage         = true
    override var lang                = "hi"
    override val hasDownloadSupport  = true

    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
    )

    // ─── TMDB Logo ────────────────────────────────────────────────
    private val TMDB_API = "https://api.themoviedb.org/3"
    private val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private val TMDB_IMG = "https://image.tmdb.org/t/p/original"

    data class TmdbImages(
        @JsonProperty("logos") val logos: List<TmdbLogo>? = null
    )
    data class TmdbLogo(
        @JsonProperty("file_path") val filePath: String? = null,
        @JsonProperty("iso_639_1") val lang: String?     = null
    )
    data class TmdbFind(
        @JsonProperty("movie_results") val movies:  List<TmdbFindResult>? = null,
        @JsonProperty("tv_results")    val tvShows: List<TmdbFindResult>? = null
    )
    data class TmdbFindResult(
        @JsonProperty("id") val id: Int? = null
    )
    data class TmdbSearchResult(
        @JsonProperty("results") val results: List<TmdbFindResult>? = null
    )

    /**
     * Fetches the title logo URL from TMDB.
     * Method 1: Extracts IMDB ID from page links -> resolves TMDB ID via /find endpoint.
     * Method 2: Falls back to TMDB title search if no IMDB link is present.
     * Returns null silently if logo is unavailable.
     */
    private suspend fun fetchLogoUrl(
        document: org.jsoup.nodes.Document,
        title: String,
        isSeries: Boolean
    ): String? {
        return try {
            val mediaType = if (isSeries) "tv" else "movie"

            // Method 1: look for an IMDB link in the page
            val imdbId = document
                .select("a[href*='imdb.com/title']")
                .attr("href")
                .substringAfter("title/")
                .substringBefore("/")
                .takeIf { it.startsWith("tt") }

            val tmdbId: Int? = if (imdbId != null) {
                // Resolve TMDB ID from IMDB ID
                app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id")
                    .parsedSafe<TmdbFind>()
                    ?.let {
                        if (isSeries) it.tvShows?.firstOrNull()?.id
                        else          it.movies?.firstOrNull()?.id
                    }
            } else {
                // Method 2: search TMDB by title
                app.get("$TMDB_API/search/$mediaType?api_key=$TMDB_KEY&query=${title.trim().replace(" ", "+")}")
                    .parsedSafe<TmdbSearchResult>()
                    ?.results?.firstOrNull()?.id
            }

            if (tmdbId == null) return null

            // Fetch logo images from TMDB (prefer English, fall back to any)
            val images = app.get(
                "$TMDB_API/$mediaType/$tmdbId/images?api_key=$TMDB_KEY&include_image_language=en,null"
            ).parsedSafe<TmdbImages>()

            val logo = images?.logos?.firstOrNull { it.lang == "en" }
                ?: images?.logos?.firstOrNull()

            logo?.filePath?.let { "$TMDB_IMG$it" }

        } catch (e: Exception) {
            null
        }
    }
    // ─────────────────────────────────────────────────────────────

    private fun mainPageJson(taxonomy: String, search: String, term: String, type: String): String {
        return "{\"taxonomy\":\"$taxonomy\",\"search\":\"$search\",\"term\":\"$term\",\"type\":\"$type\"}"
    }

    override val mainPage = mainPageOf(
        mainPageJson("none", "none", "none", "series")          to "Series",
        mainPageJson("none", "none", "none", "movie")           to "Movies",
        mainPageJson("category", "none", "anime", "none")       to "Anime",
        mainPageJson("category", "none", "cartoon", "none")     to "Cartoon",
        mainPageJson("category", "none", "hindi-dub", "none")   to "Hindi Dub",
        mainPageJson("category", "none", "tamil", "none")       to "Tamil",
        mainPageJson("category", "none", "telugu", "none")      to "Telugu"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isSeries    = request.data.contains("type\":\"series")
        val isMovie     = request.data.contains("type\":\"movie")
        val isCategory  = !isSeries && !isMovie

        // Category pages use standard /page/N/ pagination
        if (isCategory) {
            val term      = Regex("\"term\":\"([^\"]+)\"").find(request.data)?.groupValues?.get(1) ?: ""
            val pageUrl   = "$mainUrl/category/$term/"
            val pagedUrl  = if (page == 1) pageUrl else "${pageUrl}page/$page/"
            val document  = app.get(pagedUrl).document
            val home      = document.select("article").mapNotNull { it.toSearchResult() }
            val hasNextPage = document.selectFirst("a.next.page-numbers") != null
            return newHomePageResponse(request.name, home, hasNextPage)
        }

        // Series & Movies use AJAX "Load more"
        val pageUrl = if (isSeries) "$mainUrl/series-hindi/" else "$mainUrl/movie-hindi/"

        // Page 1: normal HTML scrape
        if (page == 1) {
            val document = app.get(pageUrl).document
            val home     = document.select("article").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, home, true)
        }

        // Page 2+: AJAX POST
        val pageDoc  = app.get(pageUrl).document
        val nonce    = Regex("\"nonce\":\"([^\"]+)\"").find(pageDoc.html())?.groupValues?.get(1) ?: ""

        val filterEl  = pageDoc.selectFirst("[data-taxonomy]")
        val taxonomy  = filterEl?.attr("data-taxonomy") ?: "none"
        val termVal   = filterEl?.attr("data-term")     ?: "none"
        val searchVal = filterEl?.attr("data-search")   ?: "none"
        val typeVal   = filterEl?.attr("data-type")     ?: "none"

        val vars = """{"_wpsearch":"$nonce","taxonomy":"$taxonomy","search":"$searchVal","term":"$termVal","type":"$typeVal","genres":[],"years":[],"sort":1,"page":$page}"""

        val response = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf("action" to "action_search", "vars" to vars),
            headers = mapOf(
                "Content-Type"     to "application/x-www-form-urlencoded",
                "X-WP-Nonce"       to nonce,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer"          to pageUrl
            )
        ).text

        val json    = parseJson<AjaxResponse>(response)
        val htmlDoc = Jsoup.parse(json.html)
        val home    = htmlDoc.select("article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home, json.next)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href       = this.selectFirst("a.lnk-blk")?.attr("href") ?: return null
        var posterUrl  = this.selectFirst("div figure img")?.attr("src")
        if (posterUrl!!.contains("data:image")) {
            posterUrl  = this.selectFirst("div figure img")?.attr("data-lazy-src")
        }
        val imgAlt  = this.selectFirst("div figure img")?.attr("alt")?.trim()
        val h2Text  = this.selectFirst("header h2")?.text()?.trim()
        val title   = when {
            !imgAlt.isNullOrEmpty() && !imgAlt.contains("anime", ignoreCase = true) && imgAlt.length > 2 -> imgAlt
            !h2Text.isNullOrEmpty() && !h2Text.contains("AnimeDekho", ignoreCase = true) && h2Text.length > 2 -> h2Text
            else -> href.trimEnd('/').substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() }
        }
        return newAnimeSearchResponse(title, Gson().toJson(Media(href, posterUrl)), TvType.Anime, false) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<AnimeSearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("ul[data-results] li article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val media = Gson().fromJson(url, Media::class.java)

        val document = try {
            app.get(
                media.url,
                headers = mapOf(
                    "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Referer"         to mainUrl,
                    "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.5",
                ),
                timeout = 30
            ).document
        } catch (e: Exception) {
            Log.e("AnimeDekho", "Failed to load page: ${e.message}")
            return newMovieLoadResponse("Error", url, TvType.Movie, url) {
                this.posterUrl = media.poster
            }
        }

        fun String.cleanTitle(): String? {
            return this
                .substringAfter("Watch Online ").let { if (it != this) it else this }
                .substringBefore(" Movie in Hindi")
                .substringBefore(" Series in Hindi")
                .substringBefore(" in Hindi")
                .substringBefore(" in Tamil")
                .substringBefore(" in Telugu")
                .substringBefore(" | AnimeDekho")
                .substringBefore("| AnimeDekho")
                .substringAfter("AnimeDekho - ")
                .substringAfter("AnimeDekho \u2013 ")
                .trim()
                .takeIf {
                    it.isNotEmpty() &&
                    it.length > 2 &&
                    !it.equals("AnimeDekho", ignoreCase = true) &&
                    !it.startsWith("|")
                }
        }

        val title = listOfNotNull(
            document.selectFirst("h1.entry-title")?.text()?.trim(),
            document.selectFirst("h1")?.text()?.trim(),
            document.selectFirst("meta[property=og:title]")?.attr("content")?.trim(),
            document.selectFirst("meta[name=twitter:title]")?.attr("content")?.trim(),
            document.selectFirst("title")?.text()?.trim()
        ).firstNotNullOfOrNull { it.cleanTitle() }
            ?: media.url.trimEnd('/').substringAfterLast("/")
                .replace("-", " ")
                .replaceFirstChar { it.uppercase() }

        val poster = fixUrlNull(
            document.selectFirst("div.post-thumbnail figure img")?.attr("src") ?: media.poster
        )
        val plot = document.selectFirst("div.entry-content p")?.text()?.trim()
            ?: document.selectFirst("meta[name=twitter:description]")?.attr("content")
        val year = (document.selectFirst("span.year")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:updated_time]")
                ?.attr("content")?.substringBefore("-"))?.toIntOrNull()

        val lst      = document.select("ul.seasons-lst li")
        val isSeries = lst.isNotEmpty()

        // Fetch title logo from TMDB via IMDB ID (or title search as fallback)
        val logoUrl  = fetchLogoUrl(document, title, isSeries)

        return if (!isSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, Gson().toJson(Media(media.url, mediaType = 1))) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.logoUrl   = logoUrl
            }
        } else {
            val episodes = lst.mapNotNull {
                val name      = it.selectFirst("h3.title")?.ownText() ?: "null"
                val href      = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epPoster  = it.selectFirst("div > div > figure > img")?.attr("src")
                val season    = it.selectFirst("h3.title > span")?.text()
                    .toString().substringAfter("S").substringBefore("-").toIntOrNull()
                newEpisode(Gson().toJson(Media(href, mediaType = 2))) {
                    this.name      = name
                    this.posterUrl = epPoster
                    this.season    = season
                }
            }
            val recommendations = document.select("div.swiper-wrapper article").mapNotNull {
                val recName   = it.selectFirst("h2")?.text()             ?: return@mapNotNull null
                val recHref   = it.selectFirst("a")?.attr("href")        ?: return@mapNotNull null
                val recPoster = it.selectFirst("figure img")?.attr("src")
                newTvSeriesSearchResponse(recName, Gson().toJson(Media(recHref, recPoster, 0)), TvType.TvSeries) {
                    this.posterUrl = recPoster
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl      = poster
                this.plot           = plot
                this.year           = year
                this.logoUrl        = logoUrl
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
        val media = runCatching { Gson().fromJson(data, Media::class.java) }.getOrElse {
            Log.e("Error:", "Failed to parse media JSON $it")
            return false
        }

        val headers = mapOf("Cookie" to "toronites_server=vidstream")
        val doc = app.get(media.url, headers = headers).document
        doc.select("iframe.serversel[src]").forEach { iframe ->
            val serverUrl = iframe.attr("src")
            if (serverUrl.isBlank()) return@forEach
            val innerIframeUrl = runCatching {
                app.get(serverUrl).document.selectFirst("iframe[src]")?.attr("src")
            }.getOrNull()
            if (!innerIframeUrl.isNullOrBlank()) {
                loadExtractor(innerIframeUrl, subtitleCallback, callback)
            }
        }

        val bodyClass = runCatching {
            app.get(media.url).document.selectFirst("body")?.attr("class")
        }.getOrNull()

        val term = Regex("(?:term|postid)-(\\d+)").find(bodyClass ?: "")?.groupValues?.getOrNull(1)
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
            }
        }
        return success
    }

    data class Media(val url: String, val poster: String? = null, val mediaType: Int? = null)

    data class AjaxResponse(
        val next: Boolean,
        val html: String
    )
}

package com.anime

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

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

    // ─── TMDB API Features ───────────────────────────────────────
    private val TMDB_API = "https://api.themoviedb.org/3"
    private val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private val TMDB_IMG = "https://image.tmdb.org/t/p/original"

    data class TmdbImages(
        @JsonProperty("logos") val logos: List<TmdbImage>? = null,
        @JsonProperty("backdrops") val backdrops: List<TmdbImage>? = null
    )
    data class TmdbImage(
        @JsonProperty("file_path") val filePath: String? = null,
        @JsonProperty("iso_639_1") val lang: String?     = null
    )
    data class TmdbFind(
        @JsonProperty("movie_results") val movies: List<TmdbResult>? = null,
        @JsonProperty("tv_results")    val tvShows: List<TmdbResult>? = null
    )
    data class TmdbResult(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null
    )
    data class TmdbSearch(
        @JsonProperty("results") val results: List<TmdbResult>? = null
    )

    /**
     * A helper function to deeply clean the title for TMDB searching.
     */
    private fun cleanTitleText(title: String): String {
        var clean = title.replace(Regex("(?i)Watch Online"), "")
        
        // Remove episode patterns safely
        clean = clean.replace("(?i)\\s+\\d+[x×]\\d+.*".toRegex(), "")
        
        // Remove explicit "Episode 1" or "Season 1" texts
        clean = clean.replace("(?i)\\s+Episode\\s+\\d+.*".toRegex(), "")
        clean = clean.replace("(?i)\\s+Season\\s+\\d+.*".toRegex(), "")
        
        // Remove "fan dub" or "fandub"
        clean = clean.replace("(?i)\\s*fan\\s*dub.*".toRegex(), "")
        clean = clean.replace("(?i)\\s*fandub.*".toRegex(), "")
        
        // Remove everything from the first open bracket safely
        clean = clean.substringBefore("(")
        clean = clean.substringBefore("[")
        
        return clean.trim()
    }

    /**
     * Custom crash-proof URL encoder to safely handle all special characters
     */
    private fun encodeUri(text: String): String {
        return text.replace("%", "%25")
            .replace(" ", "%20")
            .replace("#", "%23")
            .replace("&", "%26")
            .replace("?", "%3F")
            .replace("=", "%3D")
            .replace(":", "%3A")
            .replace("/", "%2F")
            .replace("'", "%27")
            .replace("\"", "%22")
            .replace(",", "%2C")
    }

    /**
     * Normalizes title for exact matching comparison
     */
    private fun normalizeTitle(s: String?): String {
        return s?.replace("’", "'")?.replace(":", "")?.replace("-", "")?.replace(" ", "")?.lowercase() ?: ""
    }

    /**
     * Fetches Title Logo and Backdrop URL from TMDB.
     * Returns a List: [0] = logoUrl, [1] = backdropUrl
     */
    private suspend fun fetchTmdbAssets(document: Document, title: String, isSeries: Boolean): List<String?> {
        return try {
            var tmdbId: Int? = null
            var actualMediaType = if (isSeries) "tv" else "movie"

            // ── Method 1: TMDB Multi-Search with Smart Exact Matching ──
            val safeTitle = encodeUri(title)
            
            val searchRes = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle")
                .parsedSafe<TmdbSearch>()
            
            val validResults = searchRes?.results?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
            val normTitle = normalizeTitle(title)
            
            // Try to find an exact match first
            val exactMatch = validResults?.firstOrNull { 
                normalizeTitle(it.title).equals(normTitle, ignoreCase = true) || 
                normalizeTitle(it.name).equals(normTitle, ignoreCase = true) 
            }
            
            if (exactMatch != null) {
                tmdbId = exactMatch.id
                actualMediaType = exactMatch.mediaType ?: actualMediaType
            } else {
                // ── Method 2: Fallback to IMDB ID from the Page ──
                val imdbId = document.select("a[href*='imdb.com/title']").attr("href").substringAfter("title/").substringBefore("/").takeIf { it.startsWith("tt") }
                
                if (imdbId != null) {
                    app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id")
                        .parsedSafe<TmdbFind>()
                        ?.let { findRes ->
                            val tvId = findRes.tvShows?.firstOrNull()?.id
                            val movieId = findRes.movies?.firstOrNull()?.id
                            
                            if (isSeries) {
                                if (tvId != null) { tmdbId = tvId; actualMediaType = "tv" }
                                else if (movieId != null) { tmdbId = movieId; actualMediaType = "movie" }
                            } else {
                                if (movieId != null) { tmdbId = movieId; actualMediaType = "movie" }
                                else if (tvId != null) { tmdbId = tvId; actualMediaType = "tv" }
                            }
                        }
                }
                
                // ── Method 3: Ultimate Fallback ──
                if (tmdbId == null && !validResults.isNullOrEmpty()) {
                    val bestFuzzy = validResults.first()
                    tmdbId = bestFuzzy.id
                    actualMediaType = bestFuzzy.mediaType ?: actualMediaType
                }
            }

            if (tmdbId == null) return listOf(null, null)

            // ── Fetch Logo & Backdrop Images from TMDB ──
            val images = app.get(
                "$TMDB_API/$actualMediaType/$tmdbId/images?api_key=$TMDB_KEY"
            ).parsedSafe<TmdbImages>()

            // Priority for Logo
            val logo = images?.logos?.firstOrNull { it.lang == "en" }
                ?: images?.logos?.firstOrNull { it.lang == null }
                ?: images?.logos?.firstOrNull { it.lang == "ja" }
                ?: images?.logos?.firstOrNull()

            // Priority for Backdrop
            val backdrop = images?.backdrops?.firstOrNull { it.lang == null }
                ?: images?.backdrops?.firstOrNull { it.lang == "en" }
                ?: images?.backdrops?.firstOrNull()

            val logoUrl = logo?.filePath?.let { "$TMDB_IMG$it" }
            val backdropUrl = backdrop?.filePath?.let { "$TMDB_IMG$it" }

            listOf(logoUrl, backdropUrl)

        } catch (e: Exception) {
            listOf(null, null)
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

        if (isCategory) {
            val term      = Regex("\"term\":\"([^\"]+)\"").find(request.data)?.groupValues?.get(1) ?: ""
            val pageUrl   = "$mainUrl/category/$term/"
            val pagedUrl  = if (page == 1) pageUrl else "${pageUrl}page/$page/"
            val document  = app.get(pagedUrl).document
            val home      = document.select("article").mapNotNull { it.toSearchResult() }
            val hasNextPage = document.selectFirst("a.next.page-numbers") != null
            return newHomePageResponse(request.name, home, hasNextPage)
        }

        val pageUrl = if (isSeries) "$mainUrl/series-hindi/" else "$mainUrl/movie-hindi/"

        if (page == 1) {
            val document = app.get(pageUrl).document
            val home     = document.select("article").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, home, true)
        }

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

        fun String.extractRawTitle(): String? {
            return this
                .replace(Regex("(?i)Watch Online "), "")
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

        val rawTitle = listOfNotNull(
            document.selectFirst("h1.entry-title")?.text()?.trim(),
            document.selectFirst("h1")?.text()?.trim(),
            document.selectFirst("meta[property=og:title]")?.attr("content")?.trim(),
            document.selectFirst("meta[name=twitter:title]")?.attr("content")?.trim(),
            document.selectFirst("title")?.text()?.trim()
        ).firstNotNullOfOrNull { it.extractRawTitle() }
            ?: media.url.trimEnd('/').substringAfterLast("/")
                .replace("-", " ")
                .replaceFirstChar { it.uppercase() }

        val cleanTitle = cleanTitleText(rawTitle)

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

        // ── Fetch TMDB Logo & Backdrop using Clean Title ──
        val tmdbAssets  = fetchTmdbAssets(document, cleanTitle, isSeries)
        val logoUrl     = tmdbAssets[0]
        val backdropUrl = tmdbAssets[1]

        // ── Always use rawTitle so the video player shows the full original title ──
        val displayTitle = rawTitle

        return if (!isSeries) {
            newMovieLoadResponse(displayTitle, url, TvType.Movie, Gson().toJson(Media(media.url, mediaType = 1))) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdropUrl ?: poster
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
            newTvSeriesLoadResponse(displayTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl      = poster
                this.backgroundPosterUrl = backdropUrl ?: poster
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

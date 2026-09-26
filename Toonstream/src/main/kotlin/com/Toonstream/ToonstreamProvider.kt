package com.Toonstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.math.abs

// Data classes for TMDB API response mapping
data class TmdbImages(
    @JsonProperty("logos") val logos: List<TmdbImage>? = null,
    @JsonProperty("backdrops") val backdrops: List<TmdbImage>? = null
)

data class TmdbImage(
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("iso_639_1") val lang: String? = null
)

data class TmdbFind(
    @JsonProperty("movie_results") val movies: List<TmdbResult>? = null,
    @JsonProperty("tv_results") val tvShows: List<TmdbResult>? = null
)

data class TmdbResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("genre_ids") val genreIds: List<Int>? = null
)

data class TmdbSearch(
    @JsonProperty("results") val results: List<TmdbResult>? = null
)

data class TmdbDetails(
    val logoUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null
)

// Data class to store media information for serialization
data class ToonMedia(
    val url: String,
    val poster: String?
)

class ToonstreamProvider : MainAPI() {
    // Provider basic configuration
    override var mainUrl            = "https://toonstream.us"
    override var name               = "Toonstream"
    override val hasMainPage        = true
    override var lang               = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Movie, TvType.Anime, TvType.Cartoon)

    // TMDB API constants for fetching metadata (images, overview, etc.)
    private val TMDB_API = "https://api.themoviedb.org/3"
    private val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private val TMDB_IMG = "https://image.tmdb.org/t/p/original"

    // Initializing supported extractors
    private val zephyrflick  = Zephyrflick()
    private val awsStream    = AWSStream()
    private val abyss        = Abyss()
    private val streamRuby   = StreamRuby()
    private val cloudy       = Cloudy()
    private val upnsPlayer   = UpnsPlayer()
    private val gdMirrorbot  = GDMirrorbot()
    private val filesForever = FilesForever()
    private val emTurboVid   = EmTurboVid()
    private val vidMolyNet   = VidMolyNet()
    private val blakite      = Blakite()
    // Assuming this exists in your project based on original code
    private val gdMirrorbotFhd = GDMirrorbotFHD() 

    /**
     * Cleans up the title by removing unnecessary keywords, resolution, audio types, etc.
     */
    private fun cleanTitleText(title: String): String {
        return title.replace(Regex("[\u200B-\u200D\uFEFF\\p{Cf}]"), "")
            .replace("\u00A0", " ")
            .replace(Regex("(?i)Watch Online"), "")
            .replace("(?i)\\s+\\d+[x×]\\d+.*".toRegex(), "")
            .replace("×", "x")
            .replace("(?i)\\s+Episode\\s+\\d+.*".toRegex(), "")
            .replace("(?i)\\s+Season\\s+\\d+.*".toRegex(), "")
            .replace("(?i)\\s*(hindi|english)\\s*dub.*".toRegex(), "")
            .replace("(?i)\\s*(dual|multi)\\s*audio.*".toRegex(), "")
            .replace("(?i)\\s*fan\\s*dub.*".toRegex(), "")
            .replace("(?i)\\s*fandub.*".toRegex(), "")
            .substringBefore("(")
            .substringBefore("[")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    /**
     * Safely encodes the URI string for network requests.
     */
    private fun encodeUri(text: String): String {
        return try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            text.replace(" ", "+")
        }
    }

    /**
     * Normalizes the title string to alphanumeric lowercase for accurate comparison.
     */
    private fun normalizeTitle(s: String?): String {
        return s?.replace(Regex("[^a-zA-Z0-9]"), "")?.lowercase() ?: ""
    }

    /**
     * Extracts the release year from TMDB result object.
     */
    private fun getResultYear(result: TmdbResult): Int? {
        val dateString = result.releaseDate ?: result.firstAirDate
        return dateString?.substringBefore("-")?.toIntOrNull()
    }

    /**
     * Checks if TMDB year matches site year (with an allowed deviation of +/- 1 year).
     */
    private fun yearMatches(tmdbYear: Int?, siteYear: Int?): Boolean {
        if (siteYear == null || tmdbYear == null) return true
        return abs(tmdbYear - siteYear) <= 1
    }

    /**
     * Selects the best TMDB result from a list of candidates based on year and genre.
     */
    private fun pickBestResult(candidates: List<TmdbResult>, siteYear: Int?): TmdbResult? {
        if (candidates.isEmpty()) return null

        if (siteYear != null) {
            val yearMatched = candidates.filter { yearMatches(getResultYear(it), siteYear) }
            if (yearMatched.isNotEmpty()) {
                // If multiple results match the year, prefer the animation genre (ID 16)
                if (yearMatched.size == 1) return yearMatched.first()
                return yearMatched.firstOrNull { it.genreIds?.contains(16) == true } ?: yearMatched.first()
            }
        }
        // Fallback to the first candidate if no specific match is found
        return candidates.first()
    }

    /**
     * Fetches metadata (Logo, Backdrop, Overview) from TMDB API.
     */
    private suspend fun fetchTmdbAssets(document: Document?, title: String, isSeries: Boolean, year: Int?): TmdbDetails {
        return try {
            val safeTitle = encodeUri(title)
            val searchRes = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle").parsedSafe<TmdbSearch>()

            // Filter results to include only movies or tv shows
            val validResults = searchRes?.results?.filter { it.mediaType == "movie" || it.mediaType == "tv" } ?: emptyList()
            val normTitle = normalizeTitle(title)

            // Try Exact Match
            val exactCandidates = validResults.filter {
                normalizeTitle(it.title ?: it.name) == normTitle
            }
            var bestMatch = pickBestResult(exactCandidates, year)

            // Try StartsWith Match if exact match fails and title is reasonably long
            if (bestMatch == null && normTitle.length >= 6) {
                val startsWithCandidates = validResults.filter {
                    val tmdbNorm = normalizeTitle(it.title ?: it.name)
                    tmdbNorm.isNotEmpty() && tmdbNorm.startsWith(normTitle)
                }
                bestMatch = pickBestResult(startsWithCandidates, year)
            }

            // Try extracting IMDB ID from HTML document if standard search fails
            if (bestMatch == null && document != null) {
                val imdbId = document.select("a[href*='imdb.com/title']").mapNotNull {
                    val href = it.attr("href")
                    val possibleId = href.substringAfter("title/").substringBefore("/")
                    if (possibleId.startsWith("tt")) possibleId else null
                }.firstOrNull()

                if (imdbId != null) {
                    val findRes = app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id").parsedSafe<TmdbFind>()
                    val tvMatch = findRes?.tvShows?.firstOrNull()
                    val movieMatch = findRes?.movies?.firstOrNull()

                    bestMatch = if (isSeries) {
                        tvMatch ?: movieMatch
                    } else {
                        movieMatch ?: tvMatch
                    }
                }
            }

            if (bestMatch?.id == null) return TmdbDetails(null, null, null)

            val actualMediaType = bestMatch.mediaType ?: if (isSeries) "tv" else "movie"
            val images = app.get("$TMDB_API/$actualMediaType/${bestMatch.id}/images?api_key=$TMDB_KEY").parsedSafe<TmdbImages>()

            // Extract the most suitable Logo image
            var logoUrl: String? = null
            if (!images?.logos.isNullOrEmpty()) {
                val validLogos = images?.logos?.filter { it.filePath?.endsWith(".svg", ignoreCase = true) != true } ?: emptyList()
                val bestLogo = validLogos.firstOrNull { it.lang == "en" }
                    ?: validLogos.firstOrNull { it.lang == null }
                    ?: validLogos.firstOrNull { it.lang == "ja" }
                    ?: validLogos.firstOrNull()

                bestLogo?.filePath?.let { logoUrl = "$TMDB_IMG$it" }
            }

            // Extract the most suitable Backdrop image
            var backdropUrl: String? = null
            if (!images?.backdrops.isNullOrEmpty()) {
                val backs = images?.backdrops ?: emptyList()
                val bestBackdrop = backs.filter { it.lang == null }.randomOrNull()
                    ?: backs.filter { it.lang == "en" }.randomOrNull()
                    ?: backs.randomOrNull()

                bestBackdrop?.filePath?.let { backdropUrl = "$TMDB_IMG$it" }
            }

            TmdbDetails(logoUrl, backdropUrl, bestMatch.overview)

        } catch (e: Exception) {
            TmdbDetails(null, null, null) // Return empty details on failure
        }
    }

    // Homepage categories definition
    override val mainPage = mainPageOf(
        "fresh-drop"                          to "Fresh Drop",
        "category/anime-series"               to "Anime Series",
        "category/anime-movies"               to "Anime Movies",
        "category/language/hindi-language"    to "Hindi",
        "category/animation-&-cartoon-series" to "Animation & Cartoon Series",
        "category/animation-&-cartoon-movie"  to "Animation & Cartoon Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Handle "Fresh Drop" category uniquely
        if (request.data == "fresh-drop") {
            val items = fetchFreshDrop()
            return newHomePageResponse(
                list = HomePageList(name = request.name, list = items, isHorizontalImages = true),
                hasNext = false
            )
        }

        // Handle other standard categories with pagination support
        val path = request.data
        val url = when {
            path == "category/anime-series" && page == 1 -> "$mainUrl/category/anime%20series?type=series"
            path == "category/anime-series" -> "$mainUrl/category/anime-series?type=series&page=$page"
            path == "category/anime-movies" && page == 1 -> "$mainUrl/category/anime%20movies?type=movies"
            path == "category/anime-movies" -> "$mainUrl/category/anime-movies?type=movies&page=$page"
            page == 1 -> "$mainUrl/$path/"
            else -> "$mainUrl/$path/?page=$page"
        }

        val document = app.get(url).document
        val home = document.select("#movies-a ul > li").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

    /**
     * Parses the "Fresh Drop" section from the homepage.
     */
    private suspend fun fetchFreshDrop(): List<SearchResponse> {
        val document = app.get("$mainUrl/home/").document

        val header = document.select("h3.section-title").firstOrNull { it.text().contains("Fresh Drop", ignoreCase = true) }
            ?: return emptyList()

        val section = header.parents().firstOrNull { it.select("article.post.dfx").isNotEmpty() }
            ?: return emptyList()

        return section.select("article.post.dfx").mapNotNull { el ->
            val rawTitle = el.selectFirst("h2.entry-title")?.text()?.replace(Regex("(?i)Watch Online"), "")?.trim()
            if (rawTitle.isNullOrBlank()) return@mapNotNull null

            val cleanedTitle = cleanTitleText(rawTitle)
            if (cleanedTitle.isBlank()) return@mapNotNull null

            val hrefRaw = el.selectFirst("a.lnk-blk")?.attr("href") ?: return@mapNotNull null
            val href = fixUrl(hrefRaw)

            val posterRaw = el.selectFirst("img")?.attr("src")
            val fallbackPoster = if (posterRaw.isNullOrEmpty()) null else if (posterRaw.startsWith("http")) posterRaw else "https:$posterRaw"

            val rating = el.selectFirst("span.vote")?.text()?.replace("TMDB", "")?.trim()?.toDoubleOrNull()
            
            // Try fetching TMDB backdrop for horizontal image display
            val tmdbAssets = fetchTmdbAssets(null, cleanedTitle, true, null)
            val backdrop = tmdbAssets.backdropUrl ?: fallbackPoster
            val mediaJson = Gson().toJson(ToonMedia(href, fallbackPoster))

            newMovieSearchResponse(rawTitle, mediaJson, TvType.TvSeries) {
                this.posterUrl = backdrop
                this.score = Score.from10(rating)
            }
        }
    }

    /**
     * Extension function to convert a JSoup Element into a SearchResponse object.
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val rawTitle = this.selectFirst("article > header > h2, article h2.entry-title, h2")
            ?.text()?.replace(Regex("(?i)Watch Online"), "")?.trim() ?: return null

        val cleanedTitle = cleanTitleText(rawTitle)
        if (cleanedTitle.isBlank()) return null

        val href = fixUrl(this.selectFirst("article > a.lnk-blk, article a.lnk-blk, a")?.attr("href") ?: return null)
        
        val posterRaw = this.selectFirst("article img, img")?.attr("src") ?: ""
        val poster = when {
            posterRaw.startsWith("http") -> posterRaw
            posterRaw.startsWith("//") -> "https:$posterRaw"
            posterRaw.isNotEmpty() -> posterRaw
            else -> null
        }

        val tvType = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie
        val mediaJson = Gson().toJson(ToonMedia(href, poster))

        return newMovieSearchResponse(rawTitle, mediaJson, tvType) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchUrl = if (page == 1) "$mainUrl/s?q=$query&type=all" else "$mainUrl/s?q=$query&type=all&page=$page"
        var htmlText = app.get(searchUrl).text
        
        // Remove extraneous "Random" sections from search HTML to prevent parsing irrelevant items
        val regex = Regex("(?i)<[^>]+>\\s*(Random Series|Random Movies|Random)\\s*</[^>]+>")
        val match = regex.find(htmlText)
        if (match != null) {
            htmlText = htmlText.substring(0, match.range.first)
        }

        val doc = Jsoup.parse(htmlText)
        
        // Fallback selectors if the primary one fails
        var elements = doc.select("#movies-a ul > li")
        if (elements.isEmpty()) elements = doc.select("article, .result-item, .item")
        if (elements.isEmpty()) elements = doc.select("div:has(h2):has(a):has(img)")
        
        val pageResults = elements.mapNotNull { it.toSearchResult() }

        return newSearchResponseList(
            list = pageResults,
            hasNext = pageResults.isNotEmpty()
        )
    }

    /**
     * Parses the "Related Series/Movies" section on the detail page.
     */
    private fun parseRecommendations(document: Document): List<SearchResponse> {
        return try {
            val relatedHeader = document.select("h3").firstOrNull { h ->
                val t = h.text().trim()
                t.equals("Related Series", ignoreCase = true) || t.equals("Related Movies", ignoreCase = true)
            } ?: return emptyList()

            val relatedSection = relatedHeader.parents().firstOrNull { parent ->
                parent.select(".owl-carousel article.post.dfx").isNotEmpty()
            } ?: return emptyList()

            relatedSection.select(".owl-carousel article.post.dfx").mapNotNull { el ->
                val title = el.selectFirst("h2.entry-title")?.text()?.trim() ?: return@mapNotNull null
                val hrefRaw = el.selectFirst("a.lnk-blk")?.attr("href") ?: return@mapNotNull null
                val href = fixUrl(hrefRaw)

                val posterRaw = el.selectFirst("img")?.attr("src") ?: ""
                val poster = when {
                    posterRaw.isEmpty() -> null
                    posterRaw.startsWith("http") -> posterRaw
                    posterRaw.startsWith("//") -> "https:$posterRaw"
                    else -> posterRaw
                }

                val rating = el.selectFirst("span.vote")?.text()?.replace("TMDB", "")?.trim()?.toDoubleOrNull()
                val tvType = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie
                val mediaJson = Gson().toJson(ToonMedia(href, poster))

                newMovieSearchResponse(title, mediaJson, tvType) {
                    this.posterUrl = poster
                    this.score = Score.from10(rating)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val media = try {
            Gson().fromJson(url, ToonMedia::class.java)
        } catch (e: Exception) {
            ToonMedia(url, null) // Fallback for raw URLs passed directly
        }

        val actualUrl = media.url
        val document = app.get(actualUrl).document

        val rawTitle = document.selectFirst("header.entry-header > h1")?.text()?.replace(Regex("(?i)Watch Online"), "")?.trim() ?: ""
        val cleanTitle = cleanTitleText(rawTitle)

        val posterRaw = document.select("div.bghd > img").attr("src") ?: ""
        val fallbackPoster = if (posterRaw.startsWith("http")) posterRaw else "https:$posterRaw"
        val poster = media.poster ?: fallbackPoster

        val description = document.selectFirst("div.description > p")?.text()?.trim()
        val isSeries = actualUrl.contains("/series/")
        val year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()

        // Fetch enhanced metadata from TMDB
        val tmdbAssets = fetchTmdbAssets(document, cleanTitle, isSeries, year)
        // Prefer TMDB overview over site description if available
        val finalDescription = tmdbAssets.overview.takeIf { !it.isNullOrBlank() } ?: description
        val recommendations = parseRecommendations(document)

        return if (isSeries) {
            loadSeries(url, document, rawTitle, poster, finalDescription, tmdbAssets.logoUrl, tmdbAssets.backdropUrl, year, recommendations)
        } else {
            newMovieLoadResponse(rawTitle, url, TvType.Movie, actualUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = tmdbAssets.backdropUrl ?: poster
                this.plot = finalDescription
                this.year = year
                this.logoUrl = tmdbAssets.logoUrl
                this.recommendations = recommendations
            }
        }
    }

    /**
     * Parses episodes for TV series, including handling dynamic season loading via AJAX.
     */
    private suspend fun loadSeries(
        url: String,
        document: Document,
        title: String,
        poster: String,
        description: String?,
        logoUrl: String?,
        backdropUrl: String?,
        year: Int?,
        recommendations: List<SearchResponse> = emptyList()
    ): LoadResponse {
        val episodes = mutableListOf<Episode>()
        // Identify available seasons
        val seasonNumbers = document.select("a.season-btn").mapNotNull { it.attr("data-season").toIntOrNull() }.distinct().sorted()

        val media = try { Gson().fromJson(url, ToonMedia::class.java) } catch (e: Exception) { ToonMedia(url, null) }
        val actualUrl = media.url

        for (season in seasonNumbers) {
            // Attempt to load episodes for the season via WP admin-ajax
            val seasonDoc = try {
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "action_select_season",
                        "season" to season.toString(),
                        "post" to (document.selectFirst("a.season-btn[data-season='$season']")?.attr("data-post") ?: "")
                    ),
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).document
            } catch (e: Exception) {
                Jsoup.parse("")
            }

            // Fallback to direct URL if AJAX fails or returns empty
            val finalDoc = if (seasonDoc.select("article").isEmpty()) {
                try { app.get("$actualUrl/season/$season").document } catch (e: Exception) { seasonDoc }
            } else seasonDoc

            var epNum = 1
            finalDoc.select("article.post.episodes, article.post").forEach { ep ->
                val epHref = ep.selectFirst("a.lnk-blk, a")?.attr("href") ?: return@forEach
                val epPosterRaw = ep.selectFirst("img")?.attr("src")
                val epPoster = epPosterRaw?.let { if (it.startsWith("http")) it else "https:$it" }
                val epName = ep.selectFirst("h5.entry-title1, h2.entry-title, h3.entry-title")?.text()?.trim() ?: "Episode"

                episodes.add(newEpisode(fixUrl(epHref)) {
                    this.name = epName
                    this.posterUrl = epPoster
                    this.season = season
                    this.episode = epNum++
                })
            }
        }

        // Secondary fallback if primary parsing yields no episodes
        if (episodes.isEmpty()) {
            val seasonCounters = mutableMapOf<Int, Int>()
            document.select("#episode_by_temp article.post").forEach { ep ->
                val epHref = ep.selectFirst("a.lnk-blk, a")?.attr("href") ?: return@forEach
                val epPosterRaw = ep.selectFirst("img")?.attr("src")
                val epPoster = epPosterRaw?.let { if (it.startsWith("http")) it else "https:$it" }
                val epName = ep.selectFirst("h5.entry-title1")?.text()?.trim() ?: "Episode"
                
                val numEpi = ep.selectFirst("span.num-epi")?.text()?.trim()
                val epSeason = numEpi?.substringBefore("x")?.toIntOrNull() ?: 1

                val newCount = (seasonCounters[epSeason] ?: 0) + 1
                seasonCounters[epSeason] = newCount

                episodes.add(newEpisode(fixUrl(epHref)) {
                    this.name = epName
                    this.posterUrl = epPoster
                    this.season = epSeason
                    this.episode = newCount
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdropUrl ?: poster
            this.plot = description
            this.year = year
            this.logoUrl = logoUrl
            this.recommendations = recommendations
        }
    }

    /**
     * Matches the provider URL with the appropriate extractor instance.
     */
    private suspend fun invokeExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            url.contains("zephyrflick", true) || url.contains("as-cdn", true) -> zephyrflick.getUrl(url, referer, subtitleCallback, callback)
            url.contains("awstream", true) -> awsStream.getUrl(url, referer, subtitleCallback, callback)
            url.contains("abyssplayer", true) || url.contains("playhydrax", true) -> abyss.getUrl(url, referer, subtitleCallback, callback)
            url.contains("rubystm", true) || url.contains("streamruby", true) -> streamRuby.getUrl(url, referer, subtitleCallback, callback)
            url.contains("cloudy.upns", true) -> cloudy.getUrl(url, referer, subtitleCallback, callback)
            url.contains("upns", true) || url.contains("p2pplay", true) -> upnsPlayer.getUrl(url, referer, subtitleCallback, callback)
            url.contains("gdmirrorbot.nl", true) -> gdMirrorbot.getUrl(url, referer, subtitleCallback, callback)
            url.contains("filesforever", true) -> filesForever.getUrl(url, referer, subtitleCallback, callback)
            url.contains("emturbovid", true) || url.contains("turboviplay", true) -> emTurboVid.getUrl(url, referer, subtitleCallback, callback)
            url.contains("vidmoly", true) -> vidMolyNet.getUrl(url, referer, subtitleCallback, callback)
            url.contains("blakite", true) -> blakite.getUrl(url, referer, subtitleCallback, callback)
            // Fallback for generic extractors registered in Cloudstream
            else -> loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        // Collect all potential extractor links from iframes and anchors
        val allLinks = mutableSetOf<String>()

        // 1. Extract streaming links embedded in iframes
        document.select("iframe[data-src], iframe[src]").forEach { iframe ->
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }.trim()
            if (src.isNotBlank() && src != "about:blank" && !src.contains("youtube.com", true) && !src.contains("youtu.be", true)) {
                allLinks.add(if (src.startsWith("http")) src else fixUrl(src))
            }
        }

        // 2. Extract standard anchor links to known providers
        document.select(
            "a[href*=embed], a[href*=rubystm], a[href*=vidmoly], " +
            "a[href*=filesforever], a[href*=abyssplayer], " +
            "a[href*=emturbovid], a[href*=as-cdn]"
        ).forEach { a ->
            val link = a.attr("href").trim()
            if (link.isNotBlank()) {
                allLinks.add(if (link.startsWith("http")) link else fixUrl(link))
            }
        }

        // Categorize links based on priority
        val zephyrLinks = allLinks.filter { it.contains("zephyrflick", true) || it.contains("as-cdn", true) }
        val vidMolyLinks = allLinks.filter { it.contains("vidmoly", true) }
        val otherLinks = allLinks.filterNot { link ->
            link.contains("zephyrflick", true) || link.contains("as-cdn", true) || link.contains("vidmoly", true)
        }

        // Priority 1: Execute Zephyrflick links sequentially
        zephyrLinks.forEach { link ->
            try {
                invokeExtractor(link, data, subtitleCallback, callback)
                found = true
            } catch (e: Exception) {
                Log.e("ToonStream", "Zephyrflick extractor failed for $link: ${e.message}")
            }
        }

        // Priority 2: Execute VidMolyNet links sequentially
        vidMolyLinks.forEach { link ->
            try {
                invokeExtractor(link, data, subtitleCallback, callback)
                found = true
            } catch (e: Exception) {
                Log.e("ToonStream", "VidMolyNet extractor failed for $link: ${e.message}")
            }
        }

        // Priority 3: Execute all remaining links concurrently (Parallel Processing)
        if (otherLinks.isNotEmpty()) {
            coroutineScope {
                otherLinks.forEach { link ->
                    launch {
                        try {
                            invokeExtractor(link, data, subtitleCallback, callback)
                            found = true
                        } catch (e: Exception) {
                            Log.e("ToonStream", "Extractor failed for $link: ${e.message}")
                        }
                    }
                }
            }
        }

        return found
    }
}

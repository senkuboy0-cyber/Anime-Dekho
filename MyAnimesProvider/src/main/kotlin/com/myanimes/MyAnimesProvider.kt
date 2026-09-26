package com.myanimes

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.math.abs

// --- TMDB Data Classes ---
// Maps the JSON response from TMDB API to Kotlin data objects
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
    @JsonProperty("genre_ids") val genreIds: List<Int>? = null
)

data class TmdbSearch(
    @JsonProperty("results") val results: List<TmdbResult>? = null
)

// Holds the final extracted TMDB details for UI population
data class TmdbDetails(
    val id: Int?,
    val type: String?,
    val logo: String?,
    val backdrop: String?
)

class MyAnimesProvider : MainAPI() {
    override var mainUrl = "https://myanimes.in"
    override var name = "My Animes"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    // Supported media categories
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.TvSeries,
        TvType.Movie
    )

    // Homepage tabs configuration
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Fresh Drop",
        "$mainUrl/series/" to "Series",
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/category/crunchyroll/" to "Crunchyroll"
    )

    // --- Extractor Instances ---
    // Instantiated locally for direct usage during link extraction
    private val extAbyss = Abyss()
    private val extStreamP2P = StreamP2P()
    private val extCloudy = Cloudy()

    // --- TMDB API Constants ---
    private val TMDB_API = "https://api.themoviedb.org/3"
    private val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private val TMDB_IMG = "https://image.tmdb.org/t/p/original"
    private val normalizeRegex = Regex("[^a-zA-Z0-9]")

    /**
     * Extracts the release year from a TMDB result object.
     */
    private fun getResultYear(result: TmdbResult): Int? {
        val dateString = result.releaseDate ?: result.firstAirDate
        return dateString?.substringBefore("-")?.toIntOrNull()
    }

    /**
     * Checks if the TMDB year matches the site year with a +/- 1 year tolerance.
     */
    private fun yearMatches(tmdbYear: Int?, siteYear: Int?): Boolean {
        if (siteYear == null || tmdbYear == null) return true
        return abs(tmdbYear - siteYear) <= 1
    }

    /**
     * Selects the most accurate TMDB result from a list of candidates.
     */
    private fun pickBestResult(candidates: List<TmdbResult>, siteYear: Int?): TmdbResult? {
        if (candidates.isEmpty()) return null
        if (siteYear != null) {
            val matched = candidates.filter { yearMatches(getResultYear(it), siteYear) }
            if (matched.isNotEmpty()) {
                if (matched.size == 1) return matched.first()
                // Prefer animation genre (ID 16) if multiple results have the same year
                return matched.firstOrNull { it.genreIds?.contains(16) == true } ?: matched.first()
            }
        }
        return candidates.first()
    }

    /**
     * Safely encodes the URI string for network requests.
     */
    private fun encodeUri(text: String): String {
        return try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            text.replace(" ", "%20") // Basic fallback
        }
    }

    /**
     * Normalizes a title to alphanumeric lowercase for strict comparison.
     */
    private fun normalizeTitle(s: String?): String {
        return s?.replace(normalizeRegex, "")?.lowercase() ?: ""
    }

    /**
     * Cleans titles before TMDB search for better accuracy.
     */
    private fun cleanTitleForTmdb(title: String): String {
        return title.replace(Regex("(?i)\\s+Season\\s+\\d+.*"), "")
            .replace(Regex("(?i)\\s+Episode\\s+\\d+.*"), "")
            .substringBefore("(")
            .substringBefore("[")
            .trim()
    }

    /**
     * Fetches enriched metadata (Logo, Backdrop) from TMDB API.
     */
    private suspend fun fetchTmdbDetails(
        document: Document,
        title: String,
        isSeries: Boolean,
        year: Int?
    ): TmdbDetails {
        return try {
            val safeTitle = encodeUri(title)
            val searchRes = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle").parsedSafe<TmdbSearch>()
            
            val validResults = searchRes?.results?.filter { it.mediaType == "movie" || it.mediaType == "tv" } ?: emptyList()
            val normTitle = normalizeTitle(title)

            // Step 1: Exact Match
            val exactCandidates = validResults.filter { normalizeTitle(it.title ?: it.name) == normTitle }
            var bestMatch = pickBestResult(exactCandidates, year)

            // Step 2: Starts-with Match Fallback
            if (bestMatch == null && normTitle.length >= 6) {
                val startsWithCandidates = validResults.filter {
                    val tn = normalizeTitle(it.title ?: it.name)
                    tn.isNotEmpty() && tn.startsWith(normTitle)
                }
                bestMatch = pickBestResult(startsWithCandidates, year)
            }

            var tmdbId = bestMatch?.id
            var actualMediaType = bestMatch?.mediaType ?: if (isSeries) "tv" else "movie"

            // Step 3: Extract IMDB ID from HTML as a final fallback
            if (tmdbId == null) {
                val imdbId = document.select("a[href*=imdb.com/title]").mapNotNull { link ->
                    val possibleId = link.attr("href").substringAfter("title/").substringBefore("/")
                    if (possibleId.startsWith("tt")) possibleId else null
                }.firstOrNull()

                if (imdbId != null) {
                    val findRes = app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id").parsedSafe<TmdbFind>()
                    val match = if (isSeries) findRes?.tvShows?.firstOrNull() ?: findRes?.movies?.firstOrNull()
                                else findRes?.movies?.firstOrNull() ?: findRes?.tvShows?.firstOrNull()
                    
                    if (match != null) {
                        tmdbId = match.id
                        actualMediaType = match.mediaType ?: actualMediaType
                    }
                }
            }

            // Step 4: Final fallback to the very first valid result
            if (tmdbId == null && validResults.isNotEmpty()) {
                val fallback = pickBestResult(validResults, year)
                tmdbId = fallback?.id
                actualMediaType = fallback?.mediaType ?: actualMediaType
            }

            if (tmdbId == null) return TmdbDetails(null, null, null, null)

            // Fetch Images based on matched TMDB ID
            val images = app.get("$TMDB_API/$actualMediaType/$tmdbId/images?api_key=$TMDB_KEY").parsedSafe<TmdbImages>()
            var logoUrl: String? = null
            var backdropUrl: String? = null

            if (images != null) {
                // Determine Best Logo (Excluding SVGs)
                val validLogos = images.logos?.filter { it.filePath?.endsWith(".svg", ignoreCase = true) != true } ?: emptyList()
                val bestLogo = validLogos.firstOrNull { it.lang == "en" }
                    ?: validLogos.firstOrNull { it.lang == null }
                    ?: validLogos.firstOrNull { it.lang == "ja" }
                    ?: validLogos.firstOrNull()
                
                bestLogo?.filePath?.let { logoUrl = "$TMDB_IMG$it" }

                // Determine Best Backdrop
                val backdrops = images.backdrops ?: emptyList()
                val bestBackdrop = backdrops.firstOrNull { it.lang == null }
                    ?: backdrops.firstOrNull { it.lang == "en" }
                    ?: backdrops.firstOrNull()
                
                bestBackdrop?.filePath?.let { backdropUrl = "$TMDB_IMG$it" }
            }

            TmdbDetails(tmdbId, actualMediaType, logoUrl, backdropUrl)
        } catch (e: Exception) {
            Log.e("MyAnimes", "TMDB failed: ${e.message}")
            TmdbDetails(null, null, null, null)
        }
    }

    // --- Main Page ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHome = request.data == "$mainUrl/" || request.data == mainUrl

        if (isHome) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            
            val document = app.get(mainUrl).document
            val home = document.select("section.latest-drop article.post").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, home, false)
        }

        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val document = app.get(url).document
        val home = document.select("article.post").mapNotNull { it.toSearchResult() }

        val hasNext = document.selectFirst("link[rel=next], p[data-loadmore] button:not([disabled])") != null
        return newHomePageResponse(request.name, home, hasNext)
    }

    // --- Search ---

    override suspend fun search(query: String): List<SearchResponse> {
        return searchPage(query, 1)
    }

    private suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val safeQuery = encodeUri(query)
        val url = if (page <= 1) "$mainUrl/?s=$safeQuery" else "$mainUrl/page/$page/?s=$safeQuery"
        
        val document = app.get(url).document
        return document.select("article.post").mapNotNull { it.toSearchResult() }
    }

    // --- Details / Load ---

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isMovie = url.contains("/movies/")

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("img[src*=image.tmdb.org], .post-thumbnail img, figure img")?.attr("src")
        val plot = document.selectFirst(".entry-content p, .description p, section.single p")?.text()?.trim()

        val year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(document.selectFirst(".entry-meta")?.text().orEmpty())?.value?.toIntOrNull()

        val tags = document.select("li.rw span a[href*=/category/], .categories a, a[href*=/category/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Watch Now", true) }
            .distinct()

        val actors = document.select("li.rw")
            .firstOrNull { it.selectFirst("span")?.text()?.contains("Cast", true) == true }
            ?.select("a")?.map { it.text().trim() }.orEmpty()

        val recommendations = document.select("section.nt-related article.post, aside.right article.post")
            .mapNotNull { it.toSearchResult() }

        val tmdbTitle = cleanTitleForTmdb(title)
        val tmdbDetails = fetchTmdbDetails(document, tmdbTitle, !isMovie, year)

        // Return Movie Data
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = tmdbDetails.backdrop ?: poster
                this.logoUrl = tmdbDetails.logo
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
            }
        }

        // Return Series Data
        val episodes = mutableListOf<Episode>()

        // Primary episodes extraction (from seasons list)
        document.select("ul.seasons-lst > li").forEach { li ->
            val a = li.selectFirst("a[href*=/episode/]") ?: return@forEach
            val href = fixUrl(a.attr("href"))

            val titleEl = li.selectFirst("h3.title")
            val seText = titleEl?.selectFirst("span")?.text()?.trim().orEmpty()
            val seMatch = Regex("""S(\d+)\s*-?\s*E(\d+)""", RegexOption.IGNORE_CASE).find(seText)
                ?: Regex("""(\d+)x(\d+)""").find(href)

            val seasonNum = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val epNum = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0

            val epName = titleEl?.ownText()?.trim()?.ifBlank { null }
                ?: titleEl?.text()?.replace(Regex("""S\d+\s*-?\s*E\d+""", RegexOption.IGNORE_CASE), "")?.trim()?.ifBlank { null }
                ?: li.selectFirst("img[alt]")?.attr("alt")?.trim()?.ifBlank { null }
                ?: "Episode $epNum"

            val epPoster = li.selectFirst("figure img, img.brd1, img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }

            episodes.add(newEpisode(href) {
                this.name = epName
                this.season = seasonNum
                this.episode = epNum
                this.posterUrl = epPoster
            })
        }

        // Fallback episodes extraction
        if (episodes.isEmpty()) {
            document.select("a[href*=/episode/]").forEach { a ->
                val href = fixUrl(a.attr("href"))
                val seMatch = Regex("""(\d+)x(\d+)""").find(href)
                
                val seasonNum = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val epNum = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                val parent = a.parents().firstOrNull { it.tagName() == "li" } ?: a.parent()
                val epPoster = parent?.selectFirst("img")?.attr("src")
                val epName = parent?.selectFirst("h3.title, .title")?.text()
                    ?.replace(Regex("""S\d+\s*-?\s*E\d+""", RegexOption.IGNORE_CASE), "")
                    ?.trim()?.ifBlank { null } ?: "Episode $epNum"

                episodes.add(newEpisode(href) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = epPoster
                })
            }
        }

        val uniqueEpisodes = episodes.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = tmdbDetails.backdrop ?: poster
            this.logoUrl = tmdbDetails.logo
            this.year = year
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    // --- Load Links (Video Extraction) ---

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false
        val embedUrls = LinkedHashSet<String>()

        // Extract base64 embedded sources
        document.select("[data-src]").forEach { el ->
            decodeEmbed(el.attr("data-src"))?.let { embedUrls.add(it) }
        }

        // Extract iframes directly pointing to servers
        document.select("iframe.aa-embed-frame, iframe[src*=trembed], iframe[src*=trid]").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.contains("trembed") || src.contains("trid")) {
                embedUrls.add(fixUrl(src))
            }
        }

        // Check if Trembed is defined inside HTML scope
        val trid = Regex("""trid=(\d+)""").find(document.html())?.groupValues?.getOrNull(1)
        val trtype = Regex("""trtype=(\d+)""").find(document.html())?.groupValues?.getOrNull(1)
            ?: if (data.contains("/movies/")) "1" else "2"

        if (trid != null && embedUrls.isEmpty()) {
            embedUrls.add("$mainUrl/?trembed=0&trid=$trid&trtype=$trtype")
            embedUrls.add("$mainUrl/?trembed=1&trid=$trid&trtype=$trtype")
        }

        // Process extracted server URLs
        for (embedUrl in embedUrls) {
            try {
                val playerSrc = resolvePlayerSrc(embedUrl) ?: continue
                val lowerSrc = playerSrc.lowercase()
                
                when {
                    lowerSrc.contains("abyssplayer") || lowerSrc.contains("hydrax") -> {
                        extAbyss.getUrl(playerSrc, mainUrl, subtitleCallback, callback)
                        found = true
                    }
                    lowerSrc.contains("p2pplay") || (lowerSrc.contains("#") && lowerSrc.contains("play")) -> {
                        extStreamP2P.getUrl(playerSrc, mainUrl, subtitleCallback, callback)
                        found = true
                    }
                    lowerSrc.contains("upns") || lowerSrc.contains("cloudy") -> {
                        extCloudy.getUrl(playerSrc, mainUrl, subtitleCallback, callback)
                        found = true
                    }
                    else -> {
                        if (loadExtractor(playerSrc, mainUrl, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MyAnimes", "loadLinks error: ${e.message}")
            }
        }
        return found
    }

    /**
     * Resolves the actual internal player source URL if nested inside Trembed.
     */
    private suspend fun resolvePlayerSrc(embedUrl: String): String? {
        if (!embedUrl.contains("trembed")) return embedUrl

        val doc = app.get(embedUrl, referer = mainUrl).document
        val iframe = doc.selectFirst("iframe[src]")?.attr("src")?.trim().orEmpty()
        
        if (iframe.isNotBlank()) {
            // Unpack secondary layers like Hydrax or StreamP2P wrappers
            if (iframe.contains("hydrax.php") || iframe.contains("streamp2p.php")) {
                val inner = app.get(fixUrl(iframe), referer = mainUrl).document
                val innerSrc = inner.selectFirst("iframe[src]")?.attr("src")?.trim()
                return if (!innerSrc.isNullOrBlank()) fixUrl(innerSrc) else fixUrl(iframe)
            }
            return fixUrl(iframe)
        }
        return null
    }

    /**
     * Decodes Base64 encrypted embed links safely.
     */
    private fun decodeEmbed(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.startsWith("http")) return raw
        return try {
            val decoded = base64Decode(raw)
            if (decoded.contains("trembed") || decoded.startsWith("http")) decoded else null
        } catch (e: Exception) {
            null
        }
    }

    // --- Extension Helpers ---

    /**
     * Converts an HTML generic post element to a SearchResponse Object.
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a.lnk-blk[href], a[href*=/series/], a[href*=/movies/]") ?: return null
        val href = fixUrl(anchor.attr("href"))
        
        if (!href.contains("/series/") && !href.contains("/movies/")) return null

        val title = this.selectFirst("h2.entry-title, .entry-title")?.text()?.trim()
            ?: this.selectFirst("img[alt]")?.attr("alt")?.trim()
            ?: return null

        val poster = this.selectFirst(".post-thumbnail img, figure img, img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }

        return if (href.contains("/movies/")) {
            newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }
}

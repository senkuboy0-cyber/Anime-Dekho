package com.tooniboy

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.math.abs

// Data class to store media details for passing between pages
data class ToonMedia(
    val url: String,
    val poster: String? = null,
    val title: String? = null
)

/**
 * Watchable page URL + its type.
 * trtype: 1 = movie, 2 = episode (trembed system)
 */
data class EpisodeData(
    val url: String,
    val trtype: Int = 2
)

// --- TMDB Data Classes ---
// These classes map the JSON response from TMDB API to Kotlin objects

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

// Holds the final extracted TMDB details used in LoadResponse
data class TmdbDetails(
    val id: Int?,
    val type: String?,
    val logo: String?,
    val backdrop: String?
)

class Tooniboy : MainAPI() {
    override var mainUrl = "https://tooniboy.xyz"
    override var name = "Tooniboy"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    // Supported categories for this provider
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon
    )

    // --- Custom Extractor Instances ---
    // Instantiated here for direct calling in routeExtractor()
    private val extAbyss      = Abyss()
    private val extStreamRuby = StreamRuby()
    private val extCloudy     = Cloudy()
    private val extGDMirror   = GDMirrorbot()
    private val extFGDMirror  = GDMirrorbotFHD()
    private val extTurbo      = EmTurboVid()
    private val extVidMoly    = VidMolyNet()
    private val extBlakite    = Blakite()
    private val extZephyr     = Zephyrflick()

    // --- TMDB API Constants ---
    private val TMDB_API = "https://api.themoviedb.org/3"
    private val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private val TMDB_IMG = "https://image.tmdb.org/t/p/original"

    /**
     * Cleans up the raw title by removing unnecessary keywords, episode/season markers,
     * and dub/sub labels to increase TMDB search accuracy.
     */
    private fun cleanForTmdb(title: String): String {
        val t = title.replace(Regex("(?i)Watch Online"), "")
            .replace(Regex("\\s+\\d+[x×]\\d+.*"), "")
            .replace(Regex("(?i)\\s+Episode\\s+\\d+.*"), "")
            .replace(Regex("(?i)\\s+Season\\s+\\d+.*"), "")
            .replace(Regex("(?i)\\s+(?:in\\s+)?(?:hindi|tamil|telugu|english|japanese)\\s*(?:dub(?:bed)?)?\\s*$"), "")
            .replace(Regex("(?i)\\s+dub(?:bed)?\\s*$"), "")
            .replace(Regex("(?i)\\s*fan\\s*dub.*"), "")
            .replace(Regex("(?i)\\s*fandub.*"), "")
            .substringBefore("(")
            .substringBefore("[")
            .trim()
        
        return t.ifBlank { title }
    }

    /**
     * Normalizes a title for strict comparison.
     */
    private fun normalizeTitle(s: String?): String {
        return s?.replace(Regex("[^a-zA-Z0-9]"), "")?.lowercase() ?: ""
    }

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
            val yearMatched = candidates.filter { yearMatches(getResultYear(it), siteYear) }
            if (yearMatched.isNotEmpty()) {
                if (yearMatched.size == 1) return yearMatched.first()
                // Prefer animation genre (ID 16) if multiple results have the same year
                return yearMatched.firstOrNull { it.genreIds?.contains(16) == true } ?: yearMatched.first()
            }
        }
        return candidates.first()
    }

    /**
     * Fetches metadata (Logo, Backdrop) from TMDB API.
     */
    private suspend fun fetchTmdbAssets(document: Document?, rawTitle: String, isSeries: Boolean, year: Int?): TmdbDetails {
        return try {
            val title = cleanForTmdb(rawTitle)
            if (title.isBlank()) return TmdbDetails(null, null, null, null)

            var tmdbId: Int? = null
            var mediaType = if (isSeries) "tv" else "movie"

            val safeTitle = URLEncoder.encode(title, "UTF-8")
            val searchRes = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle").parsedSafe<TmdbSearch>()
            
            val validResults = searchRes?.results?.filter { it.mediaType == "movie" || it.mediaType == "tv" } ?: emptyList()
            val normTitle = normalizeTitle(title)

            // Step 1: Exact title match
            val exactCandidates = validResults.filter { normalizeTitle(it.title ?: it.name) == normTitle }
            var bestMatch = pickBestResult(exactCandidates, year)

            // Step 2: Starts-with match fallback
            if (bestMatch == null && normTitle.length >= 6) {
                val startsWithCandidates = validResults.filter {
                    val tn = normalizeTitle(it.title ?: it.name)
                    tn.isNotEmpty() && tn.startsWith(normTitle)
                }
                bestMatch = pickBestResult(startsWithCandidates, year)
            }

            if (bestMatch != null) {
                tmdbId = bestMatch.id
                bestMatch.mediaType?.let { mediaType = it }
            }

            // Step 3: Extract IMDB ID from the webpage HTML as a final fallback
            if (tmdbId == null && document != null) {
                val imdbId = document.select("a[href*='imdb.com/title']").mapNotNull { link ->
                    val href = link.attr("href")
                    val possibleId = href.substringAfter("title/").substringBefore("/")
                    if (possibleId.startsWith("tt")) possibleId else null
                }.firstOrNull()
                
                if (imdbId != null) {
                    val findRes = app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id").parsedSafe<TmdbFind>()
                    val tvMatch = findRes?.tvShows?.firstOrNull()
                    val movieMatch = findRes?.movies?.firstOrNull()

                    val chosen = if (isSeries) tvMatch ?: movieMatch else movieMatch ?: tvMatch
                    if (chosen != null) {
                        tmdbId = chosen.id
                        mediaType = chosen.mediaType ?: if (isSeries) "tv" else "movie"
                    }
                }
            }

            if (tmdbId == null) return TmdbDetails(null, null, null, null)

            // Step 4: Fetch images based on matched TMDB ID
            val images = app.get("$TMDB_API/$mediaType/$tmdbId/images?api_key=$TMDB_KEY").parsedSafe<TmdbImages>()
            var logoUrl: String? = null
            var backdropUrl: String? = null

            if (images != null) {
                // Parse Logo (Avoid SVGs)
                val validLogos = images.logos?.filter { it.filePath?.endsWith(".svg", ignoreCase = true) != true } ?: emptyList()
                val bestLogo = validLogos.firstOrNull { it.lang == "en" }
                    ?: validLogos.firstOrNull { it.lang == null }
                    ?: validLogos.firstOrNull { it.lang == "ja" }
                    ?: validLogos.firstOrNull()
                
                bestLogo?.filePath?.let { logoUrl = "$TMDB_IMG$it" }
                
                // Parse Backdrop (Randomize for fresh look)
                val backs = images.backdrops ?: emptyList()
                val bestBackdrop = backs.filter { it.lang == null }.randomOrNull()
                    ?: backs.filter { it.lang == "en" }.randomOrNull()
                    ?: backs.randomOrNull()
                
                bestBackdrop?.filePath?.let { backdropUrl = "$TMDB_IMG$it" }
            }

            TmdbDetails(tmdbId, mediaType, logoUrl, backdropUrl)

        } catch (e: Exception) {
            Log.e("Tooniboy", "TMDB failed: ${e.message}")
            TmdbDetails(null, null, null, null)
        }
    }

    /**
     * Helper extension to safely extract image source from an element.
     */
    private fun Element.getImageSrc(): String? {
        val img = this.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifEmpty { img.attr("src") }
        return if (src.isEmpty()) null else fixUrl(src)
    }

    /**
     * Cleans titles by removing excess whitespaces.
     */
    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Determines TvType based on URL path.
     */
    private fun detectType(href: String): TvType = when {
        href.contains("/movies/") || href.contains("/movie/") -> TvType.Movie
        else -> TvType.TvSeries
    }

    private fun isMovieUrl(url: String): Boolean = url.contains("/movies/") || url.contains("/movie/")

    /**
     * Extension to convert a generic card element into a SearchResponse.
     */
    private fun Element.toSearchResult(tvType: TvType): SearchResponse? {
        val anchor = this.selectFirst("a[href*='/series/'], a[href*='/movies/'], a[href*='/movie/']") ?: return null
        val href = fixUrl(anchor.attr("href"))

        val title = cleanTitle(
            this.selectFirst("h2.Title, div.Title, h2")?.text()
                ?: this.selectFirst("img")?.attr("alt")?.replace(Regex("^Image\\s*"), "")
                ?: return null
        )
        if (title.isBlank()) return null
        val poster = this.getImageSrc()

        return newMovieSearchResponse(title, Gson().toJson(ToonMedia(href, poster, title)), tvType) {
            this.posterUrl = poster
        }
    }

    /**
     * Parses a list of elements into a unique list of SearchResponses.
     */
    private fun parseCardList(document: Document): List<SearchResponse> {
        val elements = document.select("li.TPostMv, div.TPost.B, article.TPost.B")
        val seen = mutableSetOf<String>()

        return elements.mapNotNull { el ->
            val href = el.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            if (!seen.add(href)) return@mapNotNull null
            el.toSearchResult(detectType(href))
        }
    }

    /**
     * Extracts digits for video duration.
     */
    private fun parseDuration(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    // --- Main Page Configuration ---
    override val mainPage = mainPageOf(
        "series"                  to "Series",
        "movies"                  to "Movies",
        "category/language/hindi" to "Hindi",
        "category/animation"      to "Animation",
        "category/adventure"      to "Adventure"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val baseUrl = when (path) {
            "series" -> "$mainUrl/series/"
            "movies" -> "$mainUrl/movies/"
            else -> "$mainUrl/$path/"
        }
        val url = if (page > 1) "${baseUrl}page/$page/" else baseUrl

        val document = app.get(url).document
        val home = parseCardList(document)
        val hasNext = document.selectFirst("nav.wp-pagenavi a, a.next.page-numbers, link[rel=next], .pagination .next") != null
        
        return newHomePageResponse(request.name, home, hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page <= 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
        val document = app.get(url).document
        val results = parseCardList(document)
        val hasNext = document.selectFirst("nav.wp-pagenavi a, a.next.page-numbers") != null
        
        return newSearchResponseList(results, hasNext)
    }

    // --- Detail View / Load ---
    override suspend fun load(url: String): LoadResponse {
        val media = try {
            Gson().fromJson(url, ToonMedia::class.java)
        } catch (e: Exception) {
            ToonMedia(url)
        }

        val actualUrl = media.url
        val isMovie = isMovieUrl(actualUrl)
        val document = app.get(actualUrl).document

        val rawTitle = media.title ?: cleanTitle(
            document.selectFirst("h1.Title")?.text()
                ?: document.selectFirst("title")?.text()?.replace(" - Tooniboy", "")
                ?: "Unknown"
        )

        val background = fixUrlNull(document.selectFirst("figure.Objf img.TPostBg")?.attr("src"))
        val poster = media.poster ?: background
        val description = extractDescription(document)
        val year = document.selectFirst("span.Date")?.text()?.trim()?.toIntOrNull()
        val rating = document.selectFirst("div.post-ratings span")?.text()?.trim()?.toDoubleOrNull()
        val duration = document.selectFirst("span.Time")?.text()?.trim()
        val recommendations = parseRecommendations(document)

        val seasonLinks = document.select("section.SeasonBx .Title a[href*='/season/']")
            .map { fixUrl(it.attr("href")) }
            .filter { it.isNotBlank() }
            
        val isSeries = !isMovie && seasonLinks.isNotEmpty()
        val tmdb = fetchTmdbAssets(document, rawTitle, isSeries, year)

        return if (isSeries) {
            loadSeries(media, document, rawTitle, poster, background, description, year, rating, seasonLinks, recommendations, tmdb)
        } else {
            newMovieLoadResponse(rawTitle, url, TvType.Movie, Gson().toJson(EpisodeData(actualUrl, trtype = 1))) {
                this.posterUrl = poster
                this.backgroundPosterUrl = tmdb.backdrop ?: background ?: poster
                this.plot = description
                this.year = year
                this.score = Score.from10(rating)
                this.duration = parseDuration(duration)
                this.recommendations = recommendations
                this.logoUrl = tmdb.logo
            }
        }
    }

    /**
     * Scrapes the plot description cleanly.
     */
    private fun extractDescription(document: Document): String? {
        val descDiv = document.selectFirst("div.Description") ?: return null
        
        var html = descDiv.html()
        html = html.substringBefore("""<p class="Genre">""")
            .substringBefore("""<p class="Cast">""")
            .substringBefore("""<p class="Tags">""")

        val candidates = Jsoup.parse(html).select("p")
        for (p in candidates) {
            if (p.hasClass("Genre") || p.hasClass("Cast") || p.hasClass("Tags")) continue
            val clone = p.clone()
            clone.select("img, script, style").remove()
            val text = clone.text().trim()
            if (text.length > 20) return text
        }

        return document.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }?.trim()
    }

    /**
     * Scrapes "More titles like this" section for recommendations.
     */
    private fun parseRecommendations(document: Document): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        
        return try {
            val header = document.select("div.Top .Title").firstOrNull {
                it.text().contains("More titles like this", ignoreCase = true) ||
                it.text().contains("More like this", ignoreCase = true) ||
                it.text().contains("Related", ignoreCase = true)
            }

            val section = header?.parents()?.firstOrNull { parent ->
                parent.select("a[href*='/series/'], a[href*='/movies/']").isNotEmpty()
            }

            val cards = section?.select("div.TPost.B") ?: document.select("div.MovieListTop div.TPost.B")

            cards.mapNotNull { el ->
                val href = el.selectFirst("a[href*='/series/'], a[href*='/movies/'], a[href*='/movie/']")?.attr("href") ?: return@mapNotNull null
                if (!seen.add(href)) return@mapNotNull null
                el.toSearchResult(detectType(href))
            }
        } catch (e: Exception) {
            Log.e("Tooniboy", "Recommendations failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extracts episode lists from TV series seasons.
     */
    private suspend fun loadSeries(
        media: ToonMedia,
        document: Document,
        title: String,
        poster: String?,
        background: String?,
        description: String?,
        year: Int?,
        rating: Double?,
        seasonUrls: List<String>,
        recommendations: List<SearchResponse>,
        tmdb: TmdbDetails
    ): LoadResponse {
        val episodes = mutableListOf<Episode>()
        val seasonSlugRegex = Regex("/season/(.+)-(\\d+)/?$")

        for ((index, seasonUrl) in seasonUrls.withIndex()) {
            val match = seasonSlugRegex.find(seasonUrl)
            val seasonNum = match?.groupValues?.get(2)?.toIntOrNull() ?: (index + 1)

            val seasonDoc = try {
                app.get(seasonUrl).document
            } catch (e: Exception) {
                Log.e("Tooniboy", "Failed to load season $seasonNum: ${e.message}")
                continue
            }

            val rows = seasonDoc.select("div.TPTblCn table tbody tr")
            if (rows.isNotEmpty()) {
                rows.forEach { row ->
                    val epNum = row.selectFirst("td span.Num")?.text()?.trim()?.toIntOrNull() ?: return@forEach
                    val epLink = row.selectFirst("td.MvTbImg a[href], td.MvTbTtl a[href]")?.attr("href") ?: return@forEach
                    val epThumb = row.selectFirst("td.MvTbImg img")?.let { row.getImageSrc() }
                    val epName = row.selectFirst("td.MvTbTtl a")?.text()?.trim()?.ifBlank { "Episode $epNum" } ?: "Episode $epNum"

                    episodes.add(
                        newEpisode(Gson().toJson(EpisodeData(fixUrl(epLink), trtype = 2))) {
                            this.name = epName
                            this.posterUrl = epThumb
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            } else {
                // Fallback to standard list layout
                var fallbackEp = 1
                seasonDoc.select("article.TPost, li.TPostMv").forEach { el ->
                    val href = el.selectFirst("a[href*='/episode/']")?.attr("href") ?: return@forEach
                    val name = cleanTitle(el.selectFirst("h2.Title")?.text() ?: "Episode $fallbackEp")
                    
                    episodes.add(
                        newEpisode(Gson().toJson(EpisodeData(fixUrl(href), trtype = 2))) {
                            this.name = name
                            this.season = seasonNum
                            this.episode = fallbackEp++
                        }
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(title, Gson().toJson(media), TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = tmdb.backdrop ?: background ?: poster
            this.plot = description
            this.year = year
            this.score = Score.from10(rating)
            this.recommendations = recommendations
            this.logoUrl = tmdb.logo
        }
    }

    // --- Load Links (Video Extraction) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = try {
            Gson().fromJson(data, EpisodeData::class.java)
        } catch (e: Exception) {
            Log.e("Tooniboy", "Failed to parse episode data: ${e.message}")
            return false
        }

        val document = app.get(epData.url).document
        val serverButtons = document.select("button[data-key][data-id]")
        val firstButton = serverButtons.firstOrNull()

        val trtype = when {
            firstButton?.attr("data-typ") == "movie" -> 1
            isMovieUrl(epData.url) -> 1
            else -> if (epData.trtype in 1..2) epData.trtype else 2
        }

        val trid = firstButton?.attr("data-id")
            ?: document.selectFirst("[data-id]")?.attr("data-id")
            ?: Regex("""trid=(\d+)""").find(document.html())?.groupValues?.get(1)

        var success = false

        // 1. Process Default Iframe
        document.selectFirst("div.Video.on > iframe[src]")?.attr("src")?.takeIf { it.isNotBlank() }?.let { src ->
            try {
                val finalSrc = resolveDefaultPlayer(src) ?: src
                routeExtractor(finalSrc, epData.url, subtitleCallback, callback)
                success = true
            } catch (e: Exception) {
                Log.e("Tooniboy", "Default player failed: ${e.message}")
            }
        }

        // 2. Process all other Trembed Servers via API call
        if (trid != null) {
            serverButtons.forEach { btn ->
                val key = btn.attr("data-key").toIntOrNull() ?: return@forEach
                val label = btn.text().trim().ifBlank { "Server ${key + 1}" }
                try {
                    val embedDoc = app.get("$mainUrl/?trembed=$key&trid=$trid&trtype=$trtype").document
                    val iframeSrc = embedDoc.selectFirst("iframe[src]")?.attr("src")?.replace("&amp;", "&")
                    
                    if (!iframeSrc.isNullOrBlank()) {
                        routeExtractor(iframeSrc, epData.url, subtitleCallback, callback)
                        success = true
                        Log.d("Tooniboy", "[$label] $iframeSrc")
                    }
                } catch (e: Exception) {
                    Log.e("Tooniboy", "Server key=$key ($label) failed: ${e.message}")
                }
            }
        }

        return success
    }

    /**
     * Resolves internal player URLs to find the actual hosting source iframe.
     */
    private suspend fun resolveDefaultPlayer(src: String): String? {
        return try {
            if (src.contains("as-cdn")) {
                src
            } else {
                val innerDoc = app.get(src).document
                innerDoc.selectFirst("iframe[src]")?.attr("src")?.takeIf { 
                    it.contains("as-cdn") || it.contains("zephyrflick") || it.contains("awstream") 
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Intelligent Routing Function:
     * Routes the given iframe URL to the correct custom Extractor.
     * Falls back to CloudStream's standard built-in loadExtractor() if unknown.
     */
    private suspend fun routeExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val lowerUrl = url.lowercase()
        when {
            lowerUrl.contains("zephyrflick") || lowerUrl.contains("as-cdn") || lowerUrl.contains("awstream") -> {
                extZephyr.getUrl(url, referer, subtitleCallback, callback)
            }
            lowerUrl.contains("abyssplayer") || lowerUrl.contains("playhydrax") -> {
                extAbyss.getUrl(url, referer, subtitleCallback, callback)
            }
            lowerUrl.contains("rubystm") || lowerUrl.contains("streamruby") -> {
                extStreamRuby.getUrl(url, referer, subtitleCallback, callback)
            }
            lowerUrl.contains("cloudy") || lowerUrl.contains("upns") -> {
                extCloudy.getUrl(url, referer, subtitleCallback, callback)
            }
            lowerUrl.contains("gdmirrorbot") || lowerUrl.contains("fgdmirrorbot") -> {
                extGDMirror.getUrl(url, referer, subtitleCallback, callback)
            }
            lowerUrl.contains("emturbovid") -> {
                extTurbo.getUrl(url, referer, subtitleCallback, callback)
            }
            lowerUrl.contains("vidmoly") -> {
                extVidMoly.getUrl(url, referer, subtitleCallback, callback)
            }
            lowerUrl.contains("blakite") -> {
                extBlakite.getUrl(url, referer, subtitleCallback, callback)
            }
            else -> {
                Log.i("Tooniboy", "No custom extractor matched. Falling back to built-in for: $url")
                loadExtractor(url, referer, subtitleCallback, callback)
            }
        }
    }
}

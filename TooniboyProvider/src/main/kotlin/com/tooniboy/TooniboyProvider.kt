package com.tooniboy

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.select.Elements
import java.net.URLEncoder
import kotlin.math.abs

// Data class to store media details for passing between pages
data class ToonMedia(
    val url: String,
    val poster: String? = null,
    val title: String? = null
)

/**
 * Watchable page URL passed from load() to loadLinks().
 * The page (movie or episode) contains the server iframes.
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

// --- TMDB Episode-level Data Classes ---
// Used to fetch per-episode title and still thumbnail from TMDB.
// Season 0 / specials are intentionally excluded (see validSeasons filter).

data class TmdbSeasonBase(
    @JsonProperty("season_number") val seasonNumber: Int? = null
)

data class TmdbShowResponse(
    @JsonProperty("seasons") val seasons: List<TmdbSeasonBase>? = null
)

data class TmdbEpisode(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("still_path") val stillPath: String? = null
)

data class TmdbSeason(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null
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
    // Instantiated here for direct calling in routeExtractor().
    // Extractors built in this file family are handled locally and are
    // NEVER forwarded to CloudStream's built-in extractor system.
    // Only unknown iframe hosts fall back to loadExtractor().
    private val extRavok        = Ravok()
    private val extAbyss        = Abyss()
    private val extStreamRuby   = StreamRuby()
    private val extVidMoly      = VidMolyNet()
    private val extCloudy       = Cloudy()
    private val extUpns         = UpnsPlayer()
    private val extBlakite      = Blakite()
    private val extFilesForever = FilesForever()
    private val extStreamhg     = Streamhg()
    private val extEarnvids     = Earnvids()
    private val extByse         = Byse()
    private val extStreamTape   = StreamTape()
    private val extMixDrop      = MixDrop()
    private val extDoodStream   = DoodStream()
    private val extLuluvdo      = Luluvdo()
    private val extVidara       = Vidara()

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
    private suspend fun fetchTmdbAssets(document: org.jsoup.nodes.Document?, rawTitle: String, isSeries: Boolean, year: Int?): TmdbDetails {
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
     * Safely extracts the image source from a card element.
     */
    private fun org.jsoup.nodes.Element.getImageSrc(): String? {
        val img = this.selectFirst("img") ?: return null
        val src = img.attr("src").ifEmpty { img.attr("data-src") }
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
     * Extracts digits for video duration.
     */
    private fun parseDuration(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Converts a listing card into a SearchResponse.
     * Card structure (search / category / related):
     *   <article class="post dfx fcl movies">
     *     <header class="entry-header"><h2 class="entry-title">Title</h2>...</header>
     *     <div class="post-thumbnail or-1"><figure><img src="..."></figure></div>
     *     <a class="lnk-blk" href="/series/... or /movies/..."></a>
     *   </article>
     * Note: series cards also carry the "movies" class (site quirk),
     * while episode cards use "episodes" - selecting ".movies" excludes them.
     */
    private fun org.jsoup.nodes.Element.toSearchResult(tvType: TvType): SearchResponse? {
        val anchor = this.selectFirst("a.lnk-blk") ?: return null
        val href = fixUrl(anchor.attr("href"))
        if (href.isBlank()) return null

        val title = cleanTitle(
            this.selectFirst("h2.entry-title, h2")?.text()
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
     * Parses all listing cards on a search / category / related page.
     *
     * Scoped to <main class="main-site"> on purpose: the site renders two
     * sidebar widgets ("Random Series" and "Random Movies") inside
     * <aside class="sidebar">, and they reuse the SAME article.post.dfx.fcl.movies
     * markup. Without this scope, every search/category page would inject
     * ~10 random unrelated cards into the real results.
     */
    private fun parseCardList(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val elements = document.select("main.main-site article.post.dfx.fcl.movies")
        val seen = mutableSetOf<String>()

        return elements.mapNotNull { el ->
            val href = el.selectFirst("a.lnk-blk")?.attr("href") ?: return@mapNotNull null
            if (!seen.add(href)) return@mapNotNull null
            el.toSearchResult(detectType(href))
        }
    }

    /**
     * True when the page pagination has a NEXT link (more pages available).
     */
    private fun hasNextPage(document: org.jsoup.nodes.Document): Boolean {
        return document.select(".pagination a").any { it.text().equals("NEXT", ignoreCase = true) }
    }

    // --- Main Page Configuration ---
    override val mainPage = mainPageOf(
        "category/animation"              to "Latest Series",
        "category/movies"                 to "Latest Movies",
        "category/cartoon"                to "Cartoon",
        "category/language/hindi-language" to "Hindi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = "$mainUrl/${request.data}"
        // Category pagination: ?type=all&page=N (the old /page/N/ style is dead)
        val url = if (page > 1) "$baseUrl?type=all&page=$page" else baseUrl

        val document = app.get(url).document
        val home = parseCardList(document)
        val hasNext = hasNextPage(document)

        return newHomePageResponse(request.name, home, hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        // Search endpoint: /s?q=... (the old WordPress /?s=... style is dead)
        val url = if (page <= 1) "$mainUrl/s?q=$encodedQuery"
                  else "$mainUrl/s?q=$encodedQuery&type=all&page=$page"

        val document = app.get(url).document
        val results = parseCardList(document)
        val hasNext = hasNextPage(document)

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

        val rawTitle = cleanTitle(
            media.title
                ?: document.selectFirst("h1.entry-title")?.text()
                ?: document.selectFirst("title")?.text()?.replace(" - Tooniboy", "")
                ?: "Unknown"
        )

        // Backdrop comes directly from the site HTML (TMDB hotlink), no API call needed
        val background = fixUrlNull(document.selectFirst("img.TPostBg")?.attr("src"))
        val poster = media.poster
            ?: fixUrlNull(document.selectFirst("div.post-thumbnail img")?.attr("src"))
            ?: background
        val description = extractDescription(document)
        val year = document.selectFirst("div.entry-meta span.year")?.text()?.trim()?.toIntOrNull()
        val duration = parseDuration(document.selectFirst("div.entry-meta span.duration")?.text())
        val recommendations = parseRecommendations(document)

        // Seasons: <a class="season-btn" data-url="/series/{slug}/season/{n}">
        val seasonButtons = document.select("a.season-btn[data-url]")
        val isSeries = !isMovie && seasonButtons.isNotEmpty()
        val tmdb = fetchTmdbAssets(document, rawTitle, isSeries, year)

        return if (isSeries) {
            loadSeries(document, rawTitle, Gson().toJson(media), poster, background, description, year, seasonButtons, recommendations, tmdb)
        } else {
            newMovieLoadResponse(rawTitle, url, TvType.Movie, Gson().toJson(EpisodeData(actualUrl, trtype = 1))) {
                this.posterUrl = poster
                this.backgroundPosterUrl = tmdb.backdrop ?: background ?: poster
                this.plot = description
                this.year = year
                this.duration = duration
                this.recommendations = recommendations
                this.logoUrl = tmdb.logo
            }
        }
    }

    /**
     * Scrapes the plot description cleanly.
     * The div.description contains the story paragraph followed by
     * Language / Quality / Running time info rows - strip those.
     */
    private fun extractDescription(document: org.jsoup.nodes.Document): String? {
        val descDiv = document.selectFirst("div.description") ?: return null
        val fullText = descDiv.text().trim()
        if (fullText.isBlank()) {
            return document.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }?.trim()
        }
        val story = fullText.substringBefore("Language:").trim()
        return story.ifBlank { fullText }
    }

    /**
     * Scrapes the "Related Movies" / "Related Series" section.
     * Layout: <h3 class="section-title">Related ...</h3>
     *         followed by article.post.dfx.fcl.movies cards inside the same section.
     */
    private fun parseRecommendations(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val seen = mutableSetOf<String>()

        return try {
            val header = document.select("h3.section-title").firstOrNull {
                it.text().contains("Related", ignoreCase = true)
            }

            val section = header?.parents()?.firstOrNull { parent ->
                parent.select("article.post.dfx.fcl.movies").isNotEmpty()
            }

            val cards = section?.select("article.post.dfx.fcl.movies") ?: org.jsoup.select.Elements()

            cards.mapNotNull { el ->
                val href = el.selectFirst("a.lnk-blk")?.attr("href") ?: return@mapNotNull null
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
     *
     * Season buttons carry data-url like "/series/{slug}/season/{n}".
     * Requesting that URL returns a bare HTML fragment containing
     * <article class="post dfx fcl episodes"> items.
     * Each item:
     *   <span class="num-epi">1x5</span>
     *   <h5 class="entry-title1">S 1 | E 5</h5>
     *   <div class="post-thumbnail2"><figure><img src="thumb"></figure></div>
     *   <a class="lnk-blk" href="/episode/{slug-1x5}/">
     */
    private suspend fun loadSeries(
        document: org.jsoup.nodes.Document,
        title: String,
        mediaJson: String,
        poster: String?,
        background: String?,
        description: String?,
        year: Int?,
        seasonButtons: Elements,
        recommendations: List<SearchResponse>,
        tmdb: TmdbDetails
    ): LoadResponse {
        val episodes = mutableListOf<Episode>()

        // Build season list from season buttons; fallback = single season on main page
        data class SeasonRef(val num: Int, val url: String?)

        val seasons: List<SeasonRef> = if (seasonButtons.isNotEmpty()) {
            seasonButtons.mapIndexed { index, btn ->
                val dataUrl = btn.attr("data-url").trim()
                val fromUrl = dataUrl.trimEnd('/').substringAfterLast("/season/", "").toIntOrNull()
                val fromText = btn.text().trim().substringAfter("Season", "").trim().toIntOrNull()
                SeasonRef(fromUrl ?: fromText ?: (index + 1), dataUrl.ifBlank { null })
            }
        } else {
            listOf(SeasonRef(1, null))
        }

        for (season in seasons) {
            val articles: Elements = if (season.url != null) {
                try {
                    val target = if (season.url.startsWith("http")) season.url else fixUrl(season.url)
                    app.get(target).document.select("article.episodes")
                } catch (e: Exception) {
                    Log.e("Tooniboy", "Failed to load season ${season.num}: ${e.message}")
                    continue
                }
            } else {
                document.select("ul#episode_by_temp li article.episodes")
            }

            articles.forEachIndexed { position, art ->
                val href = art.selectFirst("a.lnk-blk")?.attr("href") ?: return@forEachIndexed
                if (href.isBlank()) return@forEachIndexed

                // num-epi format: "0x1" = season 0, episode 1
                val numText = art.selectFirst("span.num-epi")?.text()?.trim().orEmpty()
                val numParts = numText.split("x")
                val seasonNum = numParts.getOrNull(0)?.trim()?.toIntOrNull() ?: season.num
                val episodeNum = numParts.getOrNull(1)?.trim()?.toIntOrNull() ?: (position + 1)

                val rawName = art.selectFirst("h5.entry-title1")?.text()?.trim().orEmpty()
                val name = if (rawName.matches(Regex("S\\s*\\d+\\s*\\|\\s*E\\s*\\d+")) || rawName.isBlank()) {
                    "Episode $episodeNum"
                } else {
                    cleanTitle(rawName)
                }

                val thumb = art.selectFirst("div.post-thumbnail2 img")?.attr("src")?.takeIf { it.isNotBlank() }

                episodes.add(
                    newEpisode(Gson().toJson(EpisodeData(fixUrl(href)))) {
                        this.name = name
                        this.season = seasonNum
                        this.episode = episodeNum
                        this.posterUrl = thumb
                    }
                )
            }
        }

        // Override episode titles and thumbnails from TMDB for every season except Season 0 / specials.
        applyTmdbEpisodeMetadata(episodes, tmdb)

        return newTvSeriesLoadResponse(title, mediaJson, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = tmdb.backdrop ?: background ?: poster
            this.plot = description
            this.year = year
            this.recommendations = recommendations
            this.logoUrl = tmdb.logo
        }
    }

    /**
     * Overrides episode name and posterUrl from TMDB for every season except Season 0 / specials.
     *
     * - Skips entirely if no TMDB id or the matched TMDB entry is not a TV show.
     * - Fetches /tv/{id} once to enumerate valid seasons (season_number > 0),
     *   so Season 0 / specials from TMDB are never requested.
     * - Fetches each valid season in parallel, then for every site Episode with
     *   season > 0 looks up the matching TMDB episode by (seasonNumber, episodeNumber).
     * - Episodes in Season 0 (or with null season) are left untouched — they keep
     *   the site-provided name and thumbnail.
     * - If anything fails, the original site values are preserved.
     */
    private suspend fun applyTmdbEpisodeMetadata(episodes: MutableList<Episode>, tmdb: TmdbDetails) {
        val tmdbId = tmdb.id ?: return
        if (tmdb.type != "tv" || episodes.isEmpty()) return

        try {
            val showRes = app.get("$TMDB_API/tv/$tmdbId?api_key=$TMDB_KEY").parsedSafe<TmdbShowResponse>()
            // validSeasons excludes season_number == 0 (TMDB specials), null and negatives.
            val validSeasons = showRes?.seasons?.mapNotNull { it.seasonNumber }?.filter { it > 0 } ?: emptyList()
            if (validSeasons.isEmpty()) return

            val allTmdbEpisodes = mutableListOf<TmdbEpisode>()
            coroutineScope {
                validSeasons.map { sNum ->
                    async {
                        app.get("$TMDB_API/tv/$tmdbId/season/$sNum?api_key=$TMDB_KEY").parsedSafe<TmdbSeason>()
                    }
                }.awaitAll().forEach { season ->
                    if (season?.episodes != null) {
                        allTmdbEpisodes.addAll(season.episodes)
                    }
                }
            }
            if (allTmdbEpisodes.isEmpty()) return

            // Only consider episodes whose season is explicitly > 0.
            // Season 0 / specials and null-season episodes are never touched by TMDB.
            val chronoEpisodes = episodes.filter { (it.season ?: 0) > 0 }

            if (tmdbId == 0x10196) {
                val s2PlusSiteEpisodes = chronoEpisodes.filter { (it.season ?: 1) >= 2 }
                val s2PlusTmdbEpisodes = allTmdbEpisodes.drop(25)

                s2PlusSiteEpisodes.forEachIndexed { index, ep ->
                    val tmdbEp = s2PlusTmdbEpisodes.getOrNull(index) ?: return@forEachIndexed
                    tmdbEp.name?.takeIf { it.isNotBlank() }?.let { ep.name = it }
                    tmdbEp.stillPath?.takeIf { it.isNotBlank() }?.let { ep.posterUrl = "$TMDB_IMG$it" }
                }
            } else {
                chronoEpisodes.forEach { ep ->
                    val sNum = ep.season ?: return@forEach
                    val epNum = ep.episode ?: return@forEach

                    val tmdbEp = allTmdbEpisodes.find {
                        it.seasonNumber == sNum && it.episodeNumber == epNum
                    } ?: return@forEach

                    tmdbEp.name?.takeIf { it.isNotBlank() }?.let { ep.name = it }
                    tmdbEp.stillPath?.takeIf { it.isNotBlank() }?.let { ep.posterUrl = "$TMDB_IMG$it" }
                }
            }
        } catch (e: Exception) {
            Log.e("Tooniboy", "TMDB episode metadata mapping failed: ${e.message}")
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
            EpisodeData(data)
        }

        if (epData.url.isBlank()) return false

        val document = try {
            app.get(epData.url).document
        } catch (e: Exception) {
            Log.e("Tooniboy", "Failed to load page ${epData.url}: ${e.message}")
            return false
        }

        // New player layout:
        //   <aside class="video-player aa-cn" id="aa-options">
        //     <div class="video aa-tb hdd on" id="options-0"><iframe src="..."></div>
        //     <div class="video aa-tb hdd" id="options-1"><iframe data-src="..."></div>
        //     ...
        // First iframe has src, the rest are lazy data-src.
        // The YouTube trailer iframe lives OUTSIDE the aside, so selecting
        // within aside.aa-cn already excludes it; the youtube filter is a
        // second guard because trailers are not wanted.
        val iframeUrls = document.select("aside.aa-cn iframe")
            .map { el -> el.attr("src").ifBlank { el.attr("data-src") }.trim() }
            .filter { it.startsWith("http") }
            .filter { !it.contains("youtube.com", ignoreCase = true) && !it.contains("youtu.be", ignoreCase = true) }
            .distinct()
            .sortedBy { url ->
                // Playback order:
                //   1. Ravok (first)
                //   2. VidMoly (second)
                //   3. every host that has a custom extractor in Extractors.kt
                //   4. LAST - unknown hosts, which are handed over to
                //      CloudStream's built-in extractor system (loadExtractor)
                // (stable sort keeps DOM order inside each group)
                when {
                    url.contains("ravok", ignoreCase = true) -> 0
                    url.contains("vidmoly", ignoreCase = true) -> 1
                    isCustomExtractorHost(url) -> 2
                    else -> 3
                }
            }

        if (iframeUrls.isEmpty()) {
            Log.i("Tooniboy", "No server iframes found on ${epData.url}")
            return false
        }

        var success = false
        for (iframe in iframeUrls) {
            try {
                if (routeExtractor(iframe, epData.url, subtitleCallback, callback)) {
                    success = true
                }
            } catch (e: Exception) {
                Log.e("Tooniboy", "Server $iframe failed: ${e.message}")
            }
        }

        return success
    }

    /**
     * True when the iframe host belongs to one of the custom extractors in
     * Extractors.kt. Unknown hosts (voe, vidara, vidstreaming, ...) return
     * false and are handed to CloudStream's built-in loadExtractor() last.
     */
    private fun isCustomExtractorHost(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("abyssplayer") || u.contains("playhydrax") ||
            u.contains("rubystm") || u.contains("streamruby") ||
            u.contains("filesforever") || u.contains("cloudy.upns") ||
            u.contains("blakite") || u.contains("vidara") ||
            u.contains("p2pplay") || u.contains("upns.one") ||
            u.contains("hanerix") || u.contains("streamhg") ||
            u.contains("morencius") || u.contains("earnvids") ||
            u.contains("bysetayico") || u.contains("byse") ||
            u.contains("luluvdo") || u.contains("lulustream") ||
            u.contains("streamtape") || u.contains("mixdrop") ||
            u.contains("dood")
    }

    /**
     * Intelligent Routing Function:
     * Routes the given iframe URL to the correct custom Extractor.
     * Every extractor built in Extractors.kt is handled locally and never
     * reaches CloudStream's built-in extractor system.
     * Falls back to CloudStream's standard built-in loadExtractor() ONLY
     * for unknown hosts (voe, vidara, vidstreaming, turbonewvid, ...).
     */
    private suspend fun routeExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val lowerUrl = url.lowercase()
        return try {
            when {
                lowerUrl.contains("ravok") -> {
                    extRavok.getUrl(url, referer, subtitleCallback, callback); true
                }
                lowerUrl.contains("abyssplayer") || lowerUrl.contains("playhydrax") -> {
                    extAbyss.getUrl(url, referer, subtitleCallback, callback); true
                }
                lowerUrl.contains("rubystm") || lowerUrl.contains("streamruby") -> {
                    extStreamRuby.getUrl(url, referer, subtitleCallback, callback); true
                }
                lowerUrl.contains("vidmoly") -> {
                    extVidMoly.getUrl(url, referer, subtitleCallback, callback); true
                }
                lowerUrl.contains("filesforever") -> {
                    extFilesForever.getUrl(url, referer, subtitleCallback, callback); true
                }
                lowerUrl.contains("cloudy.upns") -> {
                    extCloudy.getUrl(url, referer, subtitleCallback, callback); true
                }
                lowerUrl.contains("blakite") -> {
                    extBlakite.getUrl(url, referer, subtitleCallback, callback); true
                }
                lowerUrl.contains("vidara") -> {
                    extVidara.getUrl(url, referer, subtitleCallback, callback); true
                }
                lowerUrl.contains("p2pplay") || lowerUrl.contains("upns.one") -> {
                    extUpns.getUrl(url, referer, subtitleCallback, callback); true
                }
                lowerUrl.contains("hanerix") || lowerUrl.contains("streamhg") -> {
                    extStreamhg.getUrl(url, referer, subtitleCallback, callback); true
                }
                lowerUrl.contains("morencius") || lowerUrl.contains("earnvids") -> {
                    extEarnvids.getUrl(url, referer, subtitleCallback, callback); true
                }
                lowerUrl.contains("bysetayico") || lowerUrl.contains("byse") -> {
                    extByse.getUrl(url, referer, subtitleCallback, callback); true
                }
                lowerUrl.contains("luluvdo") || lowerUrl.contains("lulustream") -> {
                    extLuluvdo.getUrl(url, referer, subtitleCallback, callback); true
                }
                lowerUrl.contains("streamtape") -> {
                    extStreamTape.getUrl(url, referer, subtitleCallback, callback); true
                }
                lowerUrl.contains("mixdrop") -> {
                    extMixDrop.getUrl(url, referer, subtitleCallback, callback); true
                }
                lowerUrl.contains("dood") -> {
                    extDoodStream.getUrl(url, referer, subtitleCallback, callback); true
                }
                else -> {
                    Log.i("Tooniboy", "No custom extractor matched. Falling back to built-in for: $url")
                    loadExtractor(url, referer, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e("Tooniboy", "Extractor failed for $url: ${e.message}")
            false
        }
    }
}

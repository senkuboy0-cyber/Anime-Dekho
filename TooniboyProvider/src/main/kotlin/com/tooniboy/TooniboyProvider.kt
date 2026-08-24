package com.tooniboy

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

data class ToonMedia(
    val url: String,
    val poster: String? = null,
    val title: String? = null,
)

/**
 * Watchable page URL + its type.
 * trtype: 1 = movie, 2 = episode (toronites embed system)
 */
data class EpisodeData(val url: String, val trtype: Int = 2)

// ─── TMDB Data Classes ───
data class TmdbImages(
    @JsonProperty("logos") val logos: ArrayList<TmdbImage>? = null,
    @JsonProperty("backdrops") val backdrops: ArrayList<TmdbImage>? = null
)
data class TmdbImage(
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("iso_639_1") val lang: String? = null
)
data class TmdbFind(
    @JsonProperty("movie_results") val movies: ArrayList<TmdbResult>? = null,
    @JsonProperty("tv_results") val tvShows: ArrayList<TmdbResult>? = null
)
data class TmdbResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("genre_ids") val genreIds: ArrayList<Int>? = null
)
data class TmdbSearch(
    @JsonProperty("results") val results: ArrayList<TmdbResult>? = null
)
data class TmdbDetails(
    val id: Int?,
    val type: String?,
    val logo: String?,
    val backdrop: String?
)

open class Tooniboy : MainAPI() {
    override var mainUrl = "https://tooniboy.co"
    override var name = "Tooniboy"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
    )

    // ─── TMDB (Logo + Backdrop only) ────────────────────────────
    private val TMDB_API = "https://api.themoviedb.org/3"
    private val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private val TMDB_IMG = "https://image.tmdb.org/t/p/original"

    /**
     * Full title cleaner (parity with AnimeDekho cleanTitleText + extractRawTitle):
     * strips "Watch Online", episode patterns (1x08 / Episode 5),
     * Season suffixes, dub/audio language suffixes (Hindi Dub / in Hindi etc.),
     * fandub markers, and anything inside (...)/[...].
     */
    private fun cleanForTmdb(title: String): String {
        var t = title.replace(Regex("Watch Online", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+\\d+[x×]\\d+.*"), "")                       // " 1x8 ..." suffixes
        t = t.replace(Regex("\\s+Episode\\s+\\d+.*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+Season\\s+\\d+.*", RegexOption.IGNORE_CASE), "")
        // trailing dub/language markers: "... Hindi Dub", "... in Hindi", "... Dubbed"
        t = t.replace(Regex("\\s+(?:in\\s+)?(?:hindi|tamil|telugu|english|japanese)\\s*(?:dub(?:bed)?)?\\s*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+dub(?:bed)?\\s*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s*fan\\s*dub.*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s*fandub.*", RegexOption.IGNORE_CASE), "")
        t = t.substringBefore("(").substringBefore("[")
        t = t.trim()
        return t.ifBlank { title }
    }

    private fun normalizeTitle(s: String?): String =
        (s ?: "").replace(Regex("[^a-zA-Z0-9]"), "").lowercase()

    private fun getResultYear(result: TmdbResult): Int? {
        val dateString = result.releaseDate ?: result.firstAirDate ?: return null
        if (dateString.contains("-")) {
            return dateString.substringBefore("-").toIntOrNull()
        }
        return null
    }

    private fun yearMatches(tmdbYear: Int?, siteYear: Int?): Boolean {
        if (siteYear == null || tmdbYear == null) return true
        val diff = tmdbYear - siteYear
        return diff == 0 || diff == 1 || diff == -1
    }

    /**
     * Year verification (±1) then animation-genre preference,
     * mirroring AnimeDekho pickBestResult.
     */
    private fun pickBestResult(candidates: List<TmdbResult>, siteYear: Int?): TmdbResult? {
        if (candidates.isEmpty()) return null

        if (siteYear != null) {
            val yearMatched = candidates.filter { yearMatches(getResultYear(it), siteYear) }
            if (yearMatched.isNotEmpty()) {
                if (yearMatched.size == 1) return yearMatched[0]
                // Prefer animation genre (16)
                return yearMatched.firstOrNull { it.genreIds?.contains(16) == true }
                    ?: yearMatched[0]
            }
        }
        return candidates[0]
    }

    /**
     * Fetches ONLY logo + backdrop from TMDB.
     * Matching chain: exact normalized title -> startsWith -> IMDB id.
     * Logo langs: en -> none -> ja -> first (SVG skipped).
     * Backdrop langs: none -> en -> first.
     */
    private suspend fun fetchTmdbAssets(document: Document?, rawTitle: String, isSeries: Boolean, year: Int?): TmdbDetails {
        return try {
            val title = cleanForTmdb(rawTitle)
            if (title.isBlank()) return TmdbDetails(null, null, null, null)

            var tmdbId: Int? = null
            var mediaType = if (isSeries) "tv" else "movie"

            val safeTitle = URLEncoder.encode(title, "UTF-8")
            val searchRes = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle")
                .parsedSafe<TmdbSearch>()

            val validResults = searchRes?.results
                ?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
                .orEmpty()

            val normTitle = normalizeTitle(title)

            // 1) Exact normalized-title match (title verification)
            val exactCandidates = validResults.filter {
                normalizeTitle(it.title) == normTitle || normalizeTitle(it.name) == normTitle
            }
            val exactMatch = pickBestResult(exactCandidates, year)
            if (exactMatch != null) {
                tmdbId = exactMatch.id
                exactMatch.mediaType?.let { mediaType = it }
            }

            // 2) startsWith match
            if (tmdbId == null && normTitle.length >= 6) {
                val startsWithCandidates = validResults.filter {
                    val tn = normalizeTitle(it.title).ifEmpty { normalizeTitle(it.name) }
                    tn.startsWith(normTitle)
                }
                val swMatch = pickBestResult(startsWithCandidates, year)
                if (swMatch != null) {
                    tmdbId = swMatch.id
                    swMatch.mediaType?.let { mediaType = it }
                }
            }

            // 3) IMDB id fallback from page links
            if (tmdbId == null && document != null) {
                var imdbId: String? = null
                for (link in document.select("a[href*='imdb.com/title']")) {
                    val href = link.attr("href")
                    if (href.contains("title/")) {
                        val possibleId = href.substringAfter("title/").substringBefore("/")
                        if (possibleId.startsWith("tt")) {
                            imdbId = possibleId
                            break
                        }
                    }
                }
                if (imdbId != null) {
                    val findRes = app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id")
                        .parsedSafe<TmdbFind>()
                    val tvMatch = findRes?.tvShows?.firstOrNull()
                    val movieMatch = findRes?.movies?.firstOrNull()

                    val chosen: TmdbResult? = if (isSeries) tvMatch ?: movieMatch else movieMatch ?: tvMatch
                    if (chosen != null) {
                        tmdbId = chosen.id
                        chosen.mediaType?.let { mediaType = it }
                            ?: run { mediaType = if (isSeries) "tv" else "movie" }
                    }
                }
            }

            if (tmdbId == null) return TmdbDetails(null, null, null, null)

            // ── Fetch images: logo + backdrop ──
            val images = app.get("$TMDB_API/$mediaType/$tmdbId/images?api_key=$TMDB_KEY")
                .parsedSafe<TmdbImages>()

            var logoUrl: String? = null
            var backdropUrl: String? = null

            if (images != null) {
                // Logo: skip SVGs; prefer en -> none -> ja -> first
                images.logos?.let { logos ->
                    val validLogos = logos.filter { img ->
                        val p = img.filePath ?: ""
                        p.isNotEmpty() && !p.endsWith(".svg") && !p.endsWith(".SVG")
                    }
                    val bestLogo = validLogos.firstOrNull { it.lang == "en" }
                        ?: validLogos.firstOrNull { it.lang == null }
                        ?: validLogos.firstOrNull { it.lang == "ja" }
                        ?: validLogos.firstOrNull()
                    bestLogo?.filePath?.let { logoUrl = "$TMDB_IMG$it" }
                }

                // Backdrop: prefer none -> en -> first
                images.backdrops?.let { backs ->
                    val bestBackdrop = backs.firstOrNull { it.lang == null }
                        ?: backs.firstOrNull { it.lang == "en" }
                        ?: backs.firstOrNull()
                    bestBackdrop?.filePath?.let { backdropUrl = "$TMDB_IMG$it" }
                }
            }

            TmdbDetails(tmdbId, mediaType, logoUrl, backdropUrl)
        } catch (e: Exception) {
            Log.e("Tooniboy", "TMDB failed: ${e.message}")
            TmdbDetails(null, null, null, null)
        }
    }

    // ─── Helpers ────────────────────────────────────────────────

    private fun Element.getImageSrc(): String? {
        val img = this.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifEmpty { img.attr("src") }
        if (src.isEmpty()) return null
        return fixUrl(src)
    }

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Detects type from URL. NOTE: movies use PLURAL "/movies/".
     */
    private fun detectType(href: String): TvType = when {
        href.contains("/movies/") -> TvType.Movie
        href.contains("/movie/") -> TvType.Movie   // safety for singular too
        else -> TvType.TvSeries
    }

    private fun isMovieUrl(url: String): Boolean =
        url.contains("/movies/") || url.contains("/movie/")

    /**
     * Universal card parser for toroflix theme.
     * Cards: <li class="TPostMv ..."><article class="TPost B"><a href>...
     */
    private fun Element.toSearchResult(tvType: TvType): SearchResponse? {
        val anchor = this.selectFirst("a[href*='/series/'], a[href*='/movies/'], a[href*='/movie/']")
            ?: return null
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

    private fun parseCardList(document: Document): MutableList<SearchResponse> {
        val home = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        val elements = document.select(
            "li.TPostMv, div.TPost.B, article.TPost.B"
        )
        for (el in elements) {
            val href = el.selectFirst("a[href]")?.attr("href") ?: continue
            if (!seen.add(href)) continue

            el.toSearchResult(detectType(href))?.let { home.add(it) }
        }
        return home
    }

    private fun parseDuration(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    // ─── Main Page ──────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "series" to "Series",
        "movies" to "Movies",
        "category/language/hindi" to "Hindi",
        "category/animation" to "Animation",
        "category/adventure" to "Adventure",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = when {
            path == "series" -> "$mainUrl/series/"
            path == "movies" -> "$mainUrl/movies/"
            else -> "$mainUrl/$path/"
        } + (if (page > 1) "page/$page/" else "")

        val document = app.get(url).document
        val home = parseCardList(document)

        val hasNext = document.selectFirst(
            "nav.wp-pagenavi a, a.next.page-numbers, link[rel=next], .pagination .next"
        ) != null

        return newHomePageResponse(request.name, home, hasNext)
    }

    // ─── Search ─────────────────────────────────────────────────

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page <= 1) {
            "$mainUrl/?s=$query"
        } else {
            "$mainUrl/page/$page/?s=$query"
        }
        val document = app.get(url).document
        val results = parseCardList(document)

        val hasNext = document.selectFirst("nav.wp-pagenavi a, a.next.page-numbers") != null
        return newSearchResponseList(results, hasNext)
    }

    // ─── Load (Detail) ──────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val media = try {
            Gson().fromJson(url, ToonMedia::class.java)
        } catch (e: Exception) {
            ToonMedia(url)
        }

        val actualUrl = media.url
        val movie = isMovieUrl(actualUrl)
        val document = app.get(actualUrl).document

        // ── Title ──
        val rawTitle = media.title
            ?: cleanTitle(
                document.selectFirst("h1.Title")?.text()
                    ?: document.selectFirst("title")?.text()?.replace(" - Tooniboy", "")
                    ?: "Unknown"
            )

        // ── Poster & Background ──
        val background = fixUrlNull(document.selectFirst("figure.Objf img.TPostBg")?.attr("src"))
        val poster = media.poster ?: background

        // ── Description ──
        val description = extractDescription(document)

        // ── Meta ──
        val year = document.selectFirst("span.Date")?.text()?.trim()?.toIntOrNull()
        val rating = document.selectFirst("div.post-ratings span")?.text()?.trim()?.toDoubleOrNull()
        val duration = document.selectFirst("span.Time")?.text()?.trim()

        // ── Recommendations ──
        val recommendations = parseRecommendations(document)

        // ── Series detection: real season links required ──
        val seasonLinks = document.select("section.SeasonBx .Title a[href*='/season/']")
            .map { fixUrl(it.attr("href")) }
            .filter { it.isNotBlank() }
        val isSeries = !movie && seasonLinks.isNotEmpty()

        // ── TMDB: logo + backdrop only ──
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
     * Robust description extraction. Some entries (e.g. Demon Slayer)
     * have an image-only first paragraph; real text lives in later ones.
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
            clone.select("img").remove()          // strip images (Demon Slayer case)
            clone.select("script,style").remove()
            val text = clone.text().trim()
            if (text.length > 20) return text
        }

        document.selectFirst("meta[name=description]")?.attr("content")?.let {
            if (it.isNotBlank()) return it.trim()
        }
        return null
    }

    /**
     * Parses ONLY the "More titles like this" carousel.
     */
    private fun parseRecommendations(document: Document): List<SearchResponse> {
        val recs = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        try {
            val header = document.select("div.Top .Title").firstOrNull {
                it.text().contains("More titles like this", ignoreCase = true)
                    || it.text().contains("More like this", ignoreCase = true)
                    || it.text().contains("Related", ignoreCase = true)
            }

            val section: Element? = header?.parents()?.firstOrNull { parent ->
                parent.select("a[href*='/series/'], a[href*='/movies/']").isNotEmpty()
            }

            val cards = section?.select("div.TPost.B")
                ?: document.select("div.MovieListTop div.TPost.B")

            for (el in cards) {
                val anchor = el.selectFirst("a[href*='/series/'], a[href*='/movies/'], a[href*='/movie/']")
                    ?: continue
                val href = anchor.attr("href")
                if (!seen.add(href)) continue

                el.toSearchResult(detectType(href))?.let { recs.add(it) }
            }
        } catch (e: Exception) {
            Log.e("Tooniboy", "recommendations failed: ${e.message}")
        }

        return recs
    }

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
                null
            } ?: continue

            val rows = seasonDoc.select("div.TPTblCn table tbody tr")
            if (rows.isNotEmpty()) {
                for (row in rows) {
                    val epNum = row.selectFirst("td span.Num")?.text()?.trim()?.toIntOrNull() ?: continue
                    val epLink = row.selectFirst("td.MvTbImg a[href], td.MvTbTtl a[href]")?.attr("href") ?: continue
                    val epThumb = row.selectFirst("td.MvTbImg img")?.let { row.getImageSrc() }
                    val epName = row.selectFirst("td.MvTbTtl a")?.text()?.trim().orEmpty()
                        .ifBlank { "Episode $epNum" }

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
                var fallbackEp = 1
                for (el in seasonDoc.select("article.TPost, li.TPostMv")) {
                    val href = el.selectFirst("a[href*='/episode/']")?.attr("href") ?: continue
                    val name = cleanTitle(el.selectFirst("h2.Title")?.text() ?: "Episode $fallbackEp")
                    episodes.add(
                        newEpisode(Gson().toJson(EpisodeData(fixUrl(href), trtype = 2))) {
                            this.name = name
                            this.season = seasonNum
                            this.episode = fallbackEp
                        }
                    )
                    fallbackEp++
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

    // ─── Load Links (Servers) ───────────────────────────────────

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

        // ── Server buttons carry data-typ (movie|episode), key and id ──
        val serverButtons = document.select("button[data-key][data-id]")
        val firstButton: Element? = serverButtons.firstOrNull()

        // trtype: movies use 1, episodes use 2. Detect from page, fallback to stored value.
        val trtype = when {
            firstButton != null && firstButton.attr("data-typ") == "movie" -> 1
            isMovieUrl(epData.url) -> 1
            else -> if (epData.trtype == 1 || epData.trtype == 2) epData.trtype else 2
        }

        val trid = firstButton?.attr("data-id")
            ?: document.selectFirst("[data-id]")?.attr("data-id")
            ?: Regex("""trid=(\d+)""").find(document.html())?.groupValues?.get(1)

        var success = false

        // ── Default player (VidStreamX → animedekho/as-cdn embed) ──
        val defaultIframe = document.selectFirst("div.Video.on > iframe[src]")
        defaultIframe?.attr("src")?.takeIf { it.isNotBlank() }?.let { src ->
            try {
                val resolved = resolveDefaultPlayer(src)
                if (!resolved.isNullOrEmpty()) {
                    loadExtractor(resolved, subtitleCallback, callback)
                    success = true
                } else {
                    loadExtractor(src, epData.url, subtitleCallback, callback)
                    success = true
                }
            } catch (e: Exception) {
                Log.e("Tooniboy", "Default player failed: ${e.message}")
            }
        }

        // ── trembed servers ──
        if (trid != null) {
            for (btn in serverButtons) {
                val key = btn.attr("data-key").toIntOrNull() ?: continue
                val label = btn.text().trim().ifBlank { "Server ${key + 1}" }
                try {
                    val embedDoc = app.get("$mainUrl/?trembed=$key&trid=$trid&trtype=$trtype").document
                    val iframeSrc = embedDoc.selectFirst("iframe[src]")?.attr("src")
                        ?.replace("&amp;", "&")
                    if (!iframeSrc.isNullOrBlank()) {
                        loadExtractor(iframeSrc, epData.url, subtitleCallback, callback)
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
     * Default VidStreamX player resolves through animedekho.app embed
     * to an as-cdn*.top video page handled by the Zephyrflick extractor.
     */
    private suspend fun resolveDefaultPlayer(src: String): String? {
        return try {
            if (src.contains("as-cdn")) {
                src
            } else {
                val innerDoc = app.get(src).document
                innerDoc.selectFirst("iframe[src]")?.attr("src")
                    ?.takeIf { it.contains("as-cdn") || it.contains("zephyrflick") || it.contains("awstream") }
            }
        } catch (e: Exception) {
            null
        }
    }
}

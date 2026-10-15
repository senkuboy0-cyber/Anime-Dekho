package com.anime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import java.net.URLEncoder
import kotlin.math.abs

open class AnimeDekhoProvider : MainAPI() {
    override var mainUrl = "https://animedekho.tv"
    override var name = "Anime Dekho"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.TvSeries
    )

    private val mainHost: String
        get() = mainUrl.substringAfter("://").substringBefore("/")

    private val TMDB_API = "https://api.themoviedb.org/3"
    private val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private val TMDB_IMG = "https://image.tmdb.org/t/p/original"

    private val normalizeRegex = Regex("[^a-zA-Z0-9]")

    // Custom Extractors
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
    private val neoCdn       = NeoCDN()

    // =========================================================================
    // Main CloudStream Overrides
    // =========================================================================

    override val mainPage = mainPageOf(
        "$mainUrl/browse/?type=series" to "Series",
        "$mainUrl/browse/?type=movies" to "Movies",
        "$mainUrl/category/anime/"     to "Anime",
        "$mainUrl/category/cartoon/"   to "Cartoon"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = buildSectionUrl(request.data, page)
        var doc = app.get(targetUrl, timeout = 30).document
        var items = parseBrowseCards(doc)

        if (items.isEmpty() && page > 1 && !request.data.contains("?")) {
            val altUrl = "${request.data.trimEnd('/')}/?pg=$page"
            doc = app.get(altUrl, timeout = 30).document
            items = parseBrowseCards(doc)
        }

        val hasNext = items.size >= 24 || doc.selectFirst("a.next.page-numbers, a.next") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = encodeUri(query)

        if (page == 1) {
            val suggestRes = try {
                app.get(
                    "$mainUrl/wp-json/dgt/v1/suggest?q=$encodedQuery",
                    headers = mapOf("Accept" to "application/json")
                ).parsedSafe<DgtSuggestResponse>()
            } catch (_: Exception) {
                null
            }

            val apiResults = suggestRes?.items.orEmpty().mapNotNull { item ->
                val title = item.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = item.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

                if (item.type == "movie") {
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = item.poster
                        this.year = item.year
                    }
                } else {
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = item.poster
                        this.year = item.year
                    }
                }
            }

            if (apiResults.isNotEmpty()) {
                return newSearchResponseList(apiResults, true)
            }
        }

        val browseSearchUrl = if (page <= 1) {
            "$mainUrl/browse/?q=$encodedQuery"
        } else {
            "$mainUrl/browse/?q=$encodedQuery&pg=$page"
        }

        val doc = app.get(browseSearchUrl, timeout = 30).document
        val htmlResults = parseBrowseCards(doc)
        return newSearchResponseList(htmlResults, htmlResults.size >= 24)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to mainUrl
                ),
                timeout = 30
            ).document
        } catch (e: Exception) {
            return newMovieLoadResponse("Error", url, TvType.Movie, url)
        }

        val snap = parseSnapshot(document)
        val isMovie = snap?.type == "movie" || url.contains("/movie-hindi/") || url.contains("/movie/")
        val isSeries = !isMovie

        val rawTitle = snap?.title?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1.dgt-h1, h1")?.text()?.trim()
            ?: extractRawTitle(document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())
            ?: url.trimEnd('/').substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() }

        val cleanTitle = cleanTitleText(extractRawTitle(rawTitle) ?: rawTitle)
        val year = snap?.year
        val plot = document.selectFirst("p.dgt-overview")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description], meta[name=twitter:description]")?.attr("content")

        val poster = snap?.poster
        val postId = snap?.id
            ?: document.selectFirst("[data-series]")?.attr("data-series")?.toIntOrNull()
            ?: document.selectFirst("[data-title]")?.attr("data-title")?.toIntOrNull()
            ?: 0

        val tmdbDetails = fetchTmdbDetails(cleanTitle, isSeries, year)
        val backdrop = tmdbDetails.backdrop ?: snap?.backdrop ?: poster
        val recommendations = parseBrowseCards(document)

        if (isMovie) {
            return newMovieLoadResponse(rawTitle, url, TvType.Movie, "$postId|0|$url") {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.logoUrl = tmdbDetails.logo
                this.recommendations = recommendations
            }
        }

        val epRes = if (postId > 0) {
            try {
                app.get(
                    "$mainUrl/wp-json/dgt/v1/episodes?series=$postId",
                    headers = mapOf("Accept" to "application/json")
                ).parsedSafe<DgtEpisodesResponse>()
            } catch (_: Exception) {
                null
            }
        } else null

        val seasonCounters = mutableMapOf<Int, Int>()
        val rawEpisodes = epRes?.episodes.orEmpty().map { ep ->
            val sNum = ep.season ?: 1
            val nextCount = (seasonCounters[sNum] ?: 0) + 1
            seasonCounters[sNum] = nextCount
            val epNum = ep.episode ?: nextCount

            val epTitle = ep.name?.takeIf { it.isNotBlank() }
                ?: ep.label?.takeIf { it.isNotBlank() }
                ?: "Episode $epNum"

            SiteEpisode(
                episodeId = ep.id ?: 0,
                href = ep.url ?: url,
                rawName = epTitle,
                poster = ep.still,
                season = sNum,
                calculatedEpNum = epNum
            )
        }

        applyTmdbEpisodeMetadata(rawEpisodes, tmdbDetails)

        val episodes = rawEpisodes.map { ep ->
            newEpisode("$postId|${ep.episodeId}|${ep.href}") {
                this.name = ep.finalName
                this.season = ep.season
                this.episode = ep.calculatedEpNum
                this.posterUrl = ep.finalPoster
            }
        }

        return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.logoUrl = tmdbDetails.logo
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var postId = 0
        var episodeId = 0
        var referer = mainUrl

        // Strip mainUrl prefix automatically added by CloudStream's fixUrl() inside newEpisode()
        val cleanData = data.removePrefix("$mainUrl/").removePrefix(mainUrl).trim()

        if (cleanData.contains("|")) {
            val parts = cleanData.split("|", limit = 3)
            postId = parts.getOrNull(0)?.substringAfterLast("/")?.toIntOrNull() ?: 0
            episodeId = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val rawRef = parts.getOrNull(2)?.trim().orEmpty()
            referer = when {
                rawRef.startsWith("http") -> rawRef
                rawRef.isNotBlank() -> "$mainUrl/${rawRef.trimStart('/')}"
                else -> mainUrl
            }
        } else if (data.startsWith("http")) {
            referer = data
        }

        val isMoviePage = referer.contains("/movie-hindi/") || referer.contains("/movie/")

        // Fallback: Fetch page HTML if postId is missing or if a series episode is missing episodeId
        if (postId <= 0 || (!isMoviePage && episodeId <= 0 && referer.startsWith("http"))) {
            try {
                val doc = app.get(referer).document
                if (postId <= 0) {
                    postId = doc.selectFirst("[data-title]")?.attr("data-title")?.toIntOrNull()
                        ?: doc.selectFirst("[data-series]")?.attr("data-series")?.toIntOrNull()
                        ?: parseSnapshot(doc)?.id
                        ?: 0
                }
                if (episodeId <= 0) {
                    episodeId = doc.selectFirst("[data-term]")?.attr("data-term")?.toIntOrNull()
                        ?: doc.selectFirst("[data-episode]")?.attr("data-episode")?.takeIf { it.isNotBlank() }?.let {
                            try { parseJson<DgtEpisode>(it).id } catch (_: Exception) { null }
                        }
                        ?: 0
                }
            } catch (e: Exception) {
                Log.e("AnimeDekho", "Fallback HTML parse failed for $referer: ${e.message}")
            }
        }

        if (postId <= 0) {
            Log.e("AnimeDekho", "loadLinks: postId missing, data=$data")
            return false
        }

        val watch = try {
            app.post(
                "$mainUrl/wp-json/dgt/v1/watch",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "Referer" to referer,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                ),
                json = mapOf("post_id" to postId, "episode_id" to episodeId)
            ).parsedSafe<DgtWatchResponse>()
        } catch (e: Exception) {
            Log.e("AnimeDekho", "Watch API request failed: ${e.message}")
            return false
        } ?: return false

        // Prioritize Vidstream first, then Vidmoly, followed by all other available servers
        val embeds = watch.sources.orEmpty()
            .filter { it.show_embed != false && !it.embed.isNullOrBlank() }
            .sortedBy { src ->
                val serverName = src.server.orEmpty()
                when {
                    serverName.contains("vidstream", ignoreCase = true) -> 0
                    serverName.contains("vidmoly", ignoreCase = true) -> 1
                    else -> 2
                }
            }

        var success = false
        for (src in embeds) {
            val wrapperUrl = src.embed ?: continue
            try {
                val realUrl = resolveRealIframe(wrapperUrl, referer) ?: continue
                if (invokeExtractor(realUrl, referer, subtitleCallback, callback)) {
                    success = true
                }
            } catch (e: Exception) {
                Log.e("AnimeDekho", "${src.server}: ${e.message}")
            }
        }

        return success
    }

    // =========================================================================
    // Site Parsing & Player Resolution Helpers
    // =========================================================================

    private fun buildSectionUrl(baseUrl: String, page: Int): String {
        if (page <= 1) return baseUrl
        return if (baseUrl.contains("?")) "$baseUrl&pg=$page" else "${baseUrl.trimEnd('/')}/page/$page/"
    }

    private fun parseBrowseCards(doc: Document): List<SearchResponse> {
        return doc.select("a.dgt-card").mapNotNull { card ->
            val href = card.attr("href").ifBlank { return@mapNotNull null }
            val url = if (href.startsWith("http")) href else "$mainUrl$href"
            val title = card.selectFirst(".dgt-card__title")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null

            val img = card.selectFirst("img")
            val poster = img?.attr("data-src")?.takeIf { it.startsWith("http") }
                ?: img?.attr("data-lazy-src")?.takeIf { it.startsWith("http") }
                ?: img?.attr("src")?.takeIf { it.startsWith("http") && !it.startsWith("data:") }

            val isMovie = url.contains("/movie-hindi/") || url.contains("/movie/")
            if (isMovie) {
                newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
            }
        }
    }

    private fun parseSnapshot(doc: Document): DgtSnapshot? {
        val raw = doc.selectFirst("[data-snapshot]")?.attr("data-snapshot") ?: return null
        return try {
            parseJson<DgtSnapshot>(raw)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun invokeExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            when {
                url.contains("zephyrflick", true) || url.contains("as-cdn", true) -> { zephyrflick.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("awstream", true) -> { awsStream.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("abyssplayer", true) || url.contains("playhydrax", true) -> { abyss.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("rubystm", true) || url.contains("streamruby", true) -> { streamRuby.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("cloudy.upns", true) -> { cloudy.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("upns", true) || url.contains("p2pplay", true) -> { upnsPlayer.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("gdmirrorbot.nl", true) -> { gdMirrorbot.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("filesforever", true) -> { filesForever.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("emturbovid", true) || url.contains("turboviplay", true) -> { emTurboVid.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("vidmoly", true) -> { vidMolyNet.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("blakite", true) -> { blakite.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("aaa/myth", true) -> { neoCdn.getUrl(url, referer, subtitleCallback, callback); true }
                else -> loadExtractor(url, referer, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("AnimeDekho", "Extractor failed for $url: ${e.message}")
            false
        }
    }

    private suspend fun resolveRealIframe(url: String, referer: String): String? {
        var current = url.trim()
        if (current.isEmpty()) return null

        if (current.contains("/aaa/myth/play2.php") || current.contains("/aaa/myth/play.php")) {
            val id = current.substringAfter("id=", "").substringBefore("&")
            if (id.isNotBlank()) {
                try {
                    val decoded = base64Decode(id)
                    if (decoded.startsWith("http")) {
                        if (!decoded.contains(mainHost, ignoreCase = true)) return decoded
                        current = decoded
                    }
                } catch (_: Exception) { }
            }
        }

        repeat(3) {
            if (!isAnimedekhoWrapper(current)) return current

            val doc = try {
                app.get(
                    current,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Referer" to referer
                    ),
                    referer = referer
                ).document
            } catch (_: Exception) {
                return current.takeIf { !isAnimedekhoWrapper(it) }
            }

            val iframe = doc.selectFirst("iframe[src]")?.attr("src")?.trim().orEmpty()
            if (iframe.isNotBlank() && !iframe.contains("challenge-platform")) {
                current = when {
                    iframe.startsWith("http") -> iframe
                    iframe.startsWith("//") -> "https:$iframe"
                    iframe.startsWith("/") -> mainUrl.trimEnd('/') + iframe
                    else -> iframe
                }
                if (!isAnimedekhoWrapper(current)) return current
                return@repeat
            }
            return current.takeIf { it.startsWith("http") }
        }
        return current.takeIf { it.startsWith("http") }
    }

    private fun isAnimedekhoWrapper(u: String): Boolean {
        if (!u.contains(mainHost, ignoreCase = true)) return false
        return u.contains("player.php") || u.contains("/embed/") ||
            u.contains("/aaa/myth/play") || u.contains("/aaa/get/play.php")
    }

    // =========================================================================
    // Title Normalization & TMDB Integration Helpers
    // =========================================================================

    private fun cleanTitleText(title: String): String {
        return title.replace(Regex("(?i)Watch Online"), "")
            .replace(Regex("(?i)\\s+\\d+[x×]\\d+.*"), "")
            .replace(Regex("(?i)\\s+Episode\\s+\\d+.*"), "")
            .replace(Regex("(?i)\\s+Season\\s+\\d+.*"), "")
            .replace(Regex("(?i)\\s*fan\\s*dub.*"), "")
            .replace(Regex("(?i)\\s*fandub.*"), "")
            .substringBefore("(")
            .substringBefore("[")
            .trim()
    }

    private fun extractRawTitle(title: String): String? {
        val processed = title.replace(Regex("(?i)Watch Online "), "")
            .replace(Regex("(?i)\\s+Anime\\s*$"), "")
            .replace(Regex("(?i)\\s*Movie\\s*\\(.*Dubbed.*\\).*$"), "")
            .substringBefore(" Movie in Hindi")
            .substringBefore(" Series in Hindi")
            .substringBefore(" in Hindi")
            .substringBefore(" in Tamil")
            .substringBefore(" in Telugu")
            .substringBefore(" | AnimeDekho")
            .substringBefore("| AnimeDekho")
            .substringAfter("AnimeDekho - ")
            .substringAfter("AnimeDekho – ")
            .trim()

        return processed.takeIf { it.length > 2 && !it.equals("AnimeDekho", ignoreCase = true) && !it.startsWith("|") }
    }

    private fun encodeUri(text: String): String {
        return try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            text.replace(" ", "%20")
        }
    }

    private fun normalizeTitle(s: String?): String {
        return s?.lowercase()?.replace("uu", "u")?.replace(normalizeRegex, "") ?: ""
    }

    private fun getResultYear(result: TmdbResult): Int? {
        val dateString = result.releaseDate ?: result.firstAirDate
        return dateString?.substringBefore("-")?.toIntOrNull()
    }

    private fun yearMatches(tmdbYear: Int?, siteYear: Int?): Boolean {
        if (siteYear == null || tmdbYear == null) return true
        return abs(tmdbYear - siteYear) <= 1
    }

    private fun pickBestResult(candidates: List<TmdbResult>, siteYear: Int?): TmdbResult? {
        if (candidates.isEmpty()) return null
        if (siteYear != null) {
            val matched = candidates.filter { yearMatches(getResultYear(it), siteYear) }
            if (matched.isNotEmpty()) {
                if (matched.size == 1) return matched.first()
                return matched.firstOrNull { it.genreIds?.contains(16) == true } ?: matched.first()
            }
        }
        return candidates.first()
    }

    private fun isTitleMatching(siteTitle: String, candidateTitles: List<String?>): Boolean {
        val normSiteFull = normalizeTitle(siteTitle)
        if (normSiteFull.isEmpty()) return false

        val hasColon = siteTitle.contains(":")
        val normSiteBase = if (hasColon) normalizeTitle(siteTitle.substringBefore(":")) else ""

        for (rawCandidate in candidateTitles) {
            if (rawCandidate.isNullOrBlank()) continue
            val normCandFull = normalizeTitle(rawCandidate)
            if (normCandFull.isEmpty()) continue

            if (normSiteFull == normCandFull) return true

            if (hasColon && normSiteBase.length >= 3) {
                val normCandBase = normalizeTitle(rawCandidate.substringBefore(":"))
                if (normSiteBase == normCandFull || normSiteBase == normCandBase) return true
            }
        }
        return false
    }

    private suspend fun fetchTmdbDetails(title: String, isSeries: Boolean, year: Int?): TmdbDetails {
        return try {
            val searchEndpoint = if (isSeries) "tv" else "movie"
            val safeTitle = encodeUri(title)
            val searchRes = app.get("$TMDB_API/search/$searchEndpoint?api_key=$TMDB_KEY&query=$safeTitle").parsedSafe<TmdbSearch>()

            var validResults = searchRes?.results ?: emptyList()

            if (validResults.isEmpty() && title.contains(":")) {
                val baseTitle = title.substringBefore(":").trim()
                if (baseTitle.length >= 3) {
                    val baseSearchRes = app.get("$TMDB_API/search/$searchEndpoint?api_key=$TMDB_KEY&query=${encodeUri(baseTitle)}").parsedSafe<TmdbSearch>()
                    validResults = baseSearchRes?.results ?: emptyList()
                }
            }

            val normTitle = normalizeTitle(title)

            var bestMatch = pickBestResult(validResults.filter {
                normalizeTitle(it.title ?: it.name) == normTitle ||
                normalizeTitle(it.originalTitle ?: it.originalName) == normTitle
            }, year)

            if (bestMatch == null && title.contains(":")) {
                bestMatch = pickBestResult(validResults.filter {
                    isTitleMatching(title, listOf(it.title, it.name, it.originalTitle, it.originalName))
                }, year)
            }

            if (bestMatch == null && normTitle.length >= 6 && !title.contains(":")) {
                bestMatch = pickBestResult(validResults.filter {
                    val tn = normalizeTitle(it.title ?: it.name)
                    tn.isNotEmpty() && tn.startsWith(normTitle)
                }, year)
            }

            val tmdbId = bestMatch?.id ?: return TmdbDetails(null, null, null, null)
            val images = app.get("$TMDB_API/$searchEndpoint/$tmdbId/images?api_key=$TMDB_KEY").parsedSafe<TmdbImages>()

            var logoUrl: String? = null
            var backdropUrl: String? = null

            if (images != null) {
                val validLogos = images.logos?.filter { it.filePath?.endsWith(".svg", ignoreCase = true) != true } ?: emptyList()
                val bestLogo = validLogos.firstOrNull { it.lang == "en" }
                    ?: validLogos.firstOrNull { it.lang == null }
                    ?: validLogos.firstOrNull { it.lang == "ja" }
                    ?: validLogos.firstOrNull()
                bestLogo?.filePath?.let { logoUrl = "$TMDB_IMG$it" }

                val backdrops = images.backdrops ?: emptyList()
                val bestBackdrop = backdrops.firstOrNull { it.lang == null }
                    ?: backdrops.firstOrNull { it.lang == "en" }
                    ?: backdrops.firstOrNull()
                bestBackdrop?.filePath?.let { backdropUrl = "$TMDB_IMG$it" }
            }

            TmdbDetails(tmdbId, searchEndpoint, logoUrl, backdropUrl)
        } catch (e: Exception) {
            TmdbDetails(null, null, null, null)
        }
    }

    private suspend fun applyTmdbEpisodeMetadata(rawEpisodes: List<SiteEpisode>, tmdbDetails: TmdbDetails) {
        val tmdbId = tmdbDetails.id ?: return
        if (tmdbDetails.type != "tv" || rawEpisodes.isEmpty()) return

        try {
            val showRes = app.get("$TMDB_API/tv/$tmdbId?api_key=$TMDB_KEY").parsedSafe<TmdbShowResponse>()
            val validSeasons = showRes?.seasons?.mapNotNull { it.seasonNumber }?.filter { it > 0 } ?: emptyList()

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

            allTmdbEpisodes.sortWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))

            val chronoEpisodes = rawEpisodes
                .filter { (it.season ?: 1) > 0 }
                .sortedWith(compareBy({ it.season ?: 1 }, { it.calculatedEpNum }))

            if (tmdbId == 0x10196) {
                // Leave Season 1 untouched and map Season 2+ sequentially starting from the 26th TMDB episode
                val s2PlusSiteEpisodes = chronoEpisodes.filter { (it.season ?: 1) >= 2 }
                val s2PlusTmdbEpisodes = allTmdbEpisodes.drop(25)

                s2PlusSiteEpisodes.forEachIndexed { index, ep ->
                    val tmdbEp = s2PlusTmdbEpisodes.getOrNull(index) ?: return@forEachIndexed
                    tmdbEp.name?.takeIf { it.isNotEmpty() }?.let { ep.finalName = it }
                    tmdbEp.stillPath?.takeIf { it.isNotEmpty() }?.let { ep.finalPoster = "$TMDB_IMG$it" }
                }
            } else {
                chronoEpisodes.forEachIndexed { index, ep ->
                    val tmdbEp = allTmdbEpisodes.find { it.seasonNumber == ep.season && it.episodeNumber == ep.calculatedEpNum }
                        ?: allTmdbEpisodes.getOrNull(index)
                        ?: return@forEachIndexed

                    tmdbEp.name?.takeIf { it.isNotEmpty() }?.let { ep.finalName = it }
                    tmdbEp.stillPath?.takeIf { it.isNotEmpty() }?.let { ep.finalPoster = "$TMDB_IMG$it" }
                }
            }
        } catch (e: Exception) {
            Log.e("AnimeDekho", "TMDB complete episode mapping failed: ${e.message}")
        }
    }
}

// =========================================================================
// Site API & Snapshot Data Classes
// =========================================================================

data class DgtSnapshot(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("backdrop") val backdrop: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("audio") val audio: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("total") val total: Int? = null
)

data class DgtEpisodesResponse(
    @JsonProperty("series_id") val series_id: Int? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episodes") val episodes: List<DgtEpisode>? = null
)

data class DgtEpisode(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("series_id") val series_id: Int? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("still") val still: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("special") val special: Boolean? = null
)

data class DgtSuggestResponse(
    @JsonProperty("items") val items: List<DgtSuggestItem>? = null
)

data class DgtSuggestItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("poster") val poster: String? = null
)

data class DgtWatchResponse(
    @JsonProperty("gated") val gated: Boolean? = null,
    @JsonProperty("sources") val sources: List<DgtSource>? = null
)

data class DgtSource(
    @JsonProperty("server") val server: String? = null,
    @JsonProperty("embed") val embed: String? = null,
    @JsonProperty("show_embed") val show_embed: Boolean? = null,
    @JsonProperty("kind") val kind: String? = null
)

data class SiteEpisode(
    val episodeId: Int,
    val href: String,
    val rawName: String,
    val poster: String?,
    val season: Int?,
    var calculatedEpNum: Int = 1,
    var finalName: String = rawName,
    var finalPoster: String? = poster
)

// =========================================================================
// TMDB Data Classes
// =========================================================================

data class TmdbImages(
    @JsonProperty("logos") val logos: List<TmdbImage>? = null,
    @JsonProperty("backdrops") val backdrops: List<TmdbImage>? = null
)

data class TmdbImage(
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("iso_639_1") val lang: String? = null
)

data class TmdbResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("genre_ids") val genreIds: List<Int>? = null
)

data class TmdbSeasonBase(
    @JsonProperty("season_number") val seasonNumber: Int? = null
)

data class TmdbShowResponse(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("seasons") val seasons: List<TmdbSeasonBase>? = null
)

data class TmdbSearch(
    @JsonProperty("results") val results: List<TmdbResult>? = null
)

data class TmdbSeason(
    @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null
)

data class TmdbEpisode(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null
)

data class TmdbDetails(
    val id: Int?,
    val type: String?,
    val logo: String?,
    val backdrop: String?
)

package com.myanimes

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MyAnimesProvider : MainAPI() {
    override var mainUrl = "https://myanimes.in"
    override var name = "My Animes"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.TvSeries,
        TvType.Movie,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Fresh Drop",
        "$mainUrl/series/" to "Series",
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/category/crunchyroll/" to "Crunchyroll",
    )

    // ─── Main page ───────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHome = request.data == "$mainUrl/" || request.data == mainUrl

        if (isHome) {
            // Fresh Drop only exists on homepage (no pagination)
            if (page > 1) {
                return newHomePageResponse(request.name, emptyList(), false)
            }
            val document = app.get(mainUrl).document
            val home = document.select("section.latest-drop article.post")
                .mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, home, false)
        }

        val url = if (page <= 1) {
            request.data
        } else {
            request.data.trimEnd('/') + "/page/$page/"
        }

        val document = app.get(url).document
        val home = document.select("article.post")
            .mapNotNull { it.toSearchResult() }

        val hasNext = document.selectFirst("link[rel=next]") != null ||
            document.selectFirst("p[data-loadmore] button:not([disabled])") != null

        return newHomePageResponse(request.name, home, hasNext)
    }

    // ─── Search ──────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        return searchPage(query, 1)
    }

    private suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val q = query.replace(" ", "+")
        val url = if (page <= 1) {
            "$mainUrl/?s=$q"
        } else {
            "$mainUrl/page/$page/?s=$q"
        }
        val document = app.get(url).document
        return document.select("article.post")
            .mapNotNull { it.toSearchResult() }
    }

    // ─── Load ────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isMovie = url.contains("/movies/")

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = document.selectFirst("img[src*=image.tmdb.org]")?.attr("src")
            ?: document.selectFirst(".post-thumbnail img, figure img")?.attr("src")

        val plot = document.selectFirst(".entry-content p, .description p, section.single p")
            ?.text()?.trim()

        val year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(
                document.selectFirst(".entry-meta")?.text().orEmpty()
            )?.value?.toIntOrNull()

        val rating = document.selectFirst("span.rating span")?.text()?.trim()?.toFloatOrNull()

        val tags = document.select("li.rw span a[href*=/category/], .categories a, a[href*=/category/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Watch Now", true) }
            .distinct()

        val actors = document.select("li.rw").firstOrNull {
            it.selectFirst("span")?.text()?.contains("Cast", true) == true
        }?.select("a")?.map { it.text().trim() }.orEmpty()

        val recommendations = document.select(
            "section.nt-related article.post, aside.right article.post"
        ).mapNotNull { it.toSearchResult() }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
            }
        }

        // Series – parse seasons / episodes
        val episodes = ArrayList<Episode>()
        val seasonBlocks = document.select("div.season, section.season, .seasons > div, [class*=season]")

        if (seasonBlocks.isNotEmpty()) {
            seasonBlocks.forEach { block ->
                val seasonText = block.selectFirst("h2, h3, .season-title, header")?.text().orEmpty()
                val seasonNum = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(seasonText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

                block.select("a[href*=/episode/]").forEach { a ->
                    episodes.add(parseEpisodeAnchor(a, seasonNum))
                }
            }
        }

        // Fallback: all episode links on page
        if (episodes.isEmpty()) {
            document.select("a[href*=/episode/]").forEach { a ->
                val href = a.attr("href")
                val se = Regex("""(\d+)x(\d+)""").find(href)
                val seasonNum = se?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                episodes.add(parseEpisodeAnchor(a, seasonNum))
            }
        }

        // Deduplicate by URL
        val unique = episodes.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, unique) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    private fun parseEpisodeAnchor(a: Element, defaultSeason: Int): Episode {
        val href = fixUrl(a.attr("href"))
        val parent = a.closest("div, li, article") ?: a.parent()

        val rawText = (parent?.text() ?: a.text()).replace(Regex("\\s+"), " ").trim()
        val epMatch = Regex("""S(\d+)-E(\d+)""", RegexOption.IGNORE_CASE).find(rawText)
            ?: Regex("""(\d+)x(\d+)""").find(href)

        val season = epMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: defaultSeason
        val epNum = epMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
            ?: Regex("""E(\d+)""", RegexOption.IGNORE_CASE).find(rawText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 0

        val name = rawText
            .replace(Regex("""S\d+-E\d+\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\d{2}\.\d{2}\.\d{4}"""), "")
            .replace("Go to Episode", "", ignoreCase = true)
            .trim()
            .ifBlank { "Episode $epNum" }

        val poster = parent?.selectFirst("img")?.attr("src")
            ?: parent?.selectFirst("img")?.attr("data-src")

        return newEpisode(href) {
            this.name = name
            this.season = season
            this.episode = epNum
            this.posterUrl = poster
        }
    }

    // ─── Load links ──────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        // Server tabs: base64 data-src or direct iframe src with trembed
        val embedUrls = LinkedHashSet<String>()

        document.select("[data-src]").forEach { el ->
            val raw = el.attr("data-src")
            decodeEmbed(raw)?.let { embedUrls.add(it) }
        }

        document.select("iframe.aa-embed-frame, iframe[src*=trembed], iframe[src*=trid]")
            .forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.contains("trembed") || src.contains("trid")) {
                    embedUrls.add(fixUrl(src))
                }
            }

        // Build from trid/trtype if present in page
        val trid = Regex("""trid=(\d+)""").find(document.html())?.groupValues?.getOrNull(1)
        val trtype = Regex("""trtype=(\d+)""").find(document.html())?.groupValues?.getOrNull(1)
            ?: if (data.contains("/movies/")) "1" else "2"

        if (trid != null && embedUrls.isEmpty()) {
            embedUrls.add("$mainUrl/?trembed=0&trid=$trid&trtype=$trtype")
            embedUrls.add("$mainUrl/?trembed=1&trid=$trid&trtype=$trtype")
        }

        for (embedUrl in embedUrls) {
            try {
                val playerSrc = resolvePlayerSrc(embedUrl) ?: continue
                when {
                    playerSrc.contains("abyssplayer.com", true) ||
                        playerSrc.contains("hydrax", true) -> {
                        Abyss().getUrl(playerSrc, mainUrl, subtitleCallback, callback)
                        found = true
                    }
                    playerSrc.contains("p2pplay", true) ||
                        playerSrc.contains("#") && playerSrc.contains("play", true) -> {
                        StreamP2P().getUrl(playerSrc, mainUrl, subtitleCallback, callback)
                        found = true
                    }
                    playerSrc.contains("upns", true) || playerSrc.contains("cloudy", true) -> {
                        Cloudy().getUrl(playerSrc, mainUrl, subtitleCallback, callback)
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
     * Fetch intermediate embed (?trembed=&trid=&trtype=) and extract iframe player URL.
     */
    private suspend fun resolvePlayerSrc(embedUrl: String): String? {
        val normalized = when {
            embedUrl.contains("trembed") -> embedUrl
            else -> return embedUrl
        }

        val doc = app.get(normalized, referer = mainUrl).document
        val iframe = doc.selectFirst("iframe[src]")?.attr("src")?.trim().orEmpty()
        if (iframe.isNotBlank()) {
            // hydrax.php / streamp2p.php may wrap another iframe
            if (iframe.contains("hydrax.php") || iframe.contains("streamp2p.php")) {
                val inner = app.get(fixUrl(iframe), referer = mainUrl).document
                val innerSrc = inner.selectFirst("iframe[src]")?.attr("src")?.trim()
                if (!innerSrc.isNullOrBlank()) return fixUrl(innerSrc)
                // streamp2p sometimes only has the hash iframe
                return fixUrl(iframe)
            }
            return fixUrl(iframe)
        }
        return null
    }

    private fun decodeEmbed(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.startsWith("http")) return raw
        return try {
            val decoded = base64Decode(raw)
            if (decoded.contains("trembed") || decoded.startsWith("http")) decoded else null
        } catch (_: Exception) {
            null
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a.lnk-blk[href]")
            ?: this.selectFirst("a[href*=/series/], a[href*=/movies/]")
            ?: return null

        val href = fixUrl(a.attr("href"))
        if (!href.contains("/series/") && !href.contains("/movies/")) return null

        val title = this.selectFirst("h2.entry-title, .entry-title")?.text()?.trim()
            ?: this.selectFirst("img[alt]")?.attr("alt")?.trim()
            ?: return null

        val poster = this.selectFirst(".post-thumbnail img, figure img, img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }

        val isMovie = href.contains("/movies/")

        return if (isMovie) {
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
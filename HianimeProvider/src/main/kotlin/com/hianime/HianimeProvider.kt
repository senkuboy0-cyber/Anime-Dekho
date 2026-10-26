package com.hianime

import com.google.gson.Gson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64

// ---------------------------------------------------------------------------
// HiAnime (hianime.at)
// ---------------------------------------------------------------------------
// Listing   /{category}?page=N               30 cards in .film_list-wrap .flw-item
// Search    /search?keyword=X&page=N         same cards
// Detail    /{slug}-{animeId}                 the poster link of a card goes to /watch/..., so the
//                                             detail url is always rebuilt without "/watch" and "?ep="
// Episodes  /api/theme/episode/list/{id}      a.ep-item[data-number, data-id, title]
// Servers   /api/theme/episode/servers?episodeId={id}
//           .server-item[data-type sub|dub, data-server-name, data-hash = base64 embed url]
//             ZokoAnime -> zokoanime.video/stream/mal/{malId}/{ep}/{sub|dub}
//             HD-1      -> megaplay.buzz/stream/s-2/{id}/{sub|dub}?s=tcdn
//
// Metadata
//   MAL id     read from the ZokoAnime embed url of the first episode (each season has its own)
//   ani.zip    by MAL id: background (Fanart), episode title, description and thumbnail, TMDB id
//   TMDB       title logo by id
//   No MAL id  TMDB title search with a cleaned title -> backdrop + title logo only
class HianimeProvider : MainAPI() {
    override var mainUrl = "https://hianime.at"
    override var name = "HiAnime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val aniZip = "https://api.ani.zip/mappings?mal_id="
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = "1865f43a0549ca50d341dd9ab8b29f49"
    private val tmdbImg = "https://image.tmdb.org/t/p/original"

    private val ajaxHeaders = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "recently-updated" to "Recently Updated",
        "dubbed-anime" to "Dubbed Anime",
        "tv" to "Tv Series",
        "movie" to "Movies",
        "genres/ecchi" to "Ecchi"
    )

    private fun encode(text: String): String =
        try { URLEncoder.encode(text, "UTF-8") } catch (_: Exception) { text }

    // ------------------------------------------------------------------ home
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/${request.data}?page=$page").document
        return newHomePageResponse(request.name, doc.cards(), doc.hasNextPage())
    }

    // ---------------------------------------------------------------- search
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val doc = app.get("$mainUrl/search?keyword=${encode(query)}&page=$page").document
        return newSearchResponseList(doc.cards(), doc.hasNextPage())
    }

    // Only the main grid. The sidebar ("Most Popular" etc.) uses the same card markup.
    private fun Document.cards(): List<SearchResponse> =
        select(".film_list-wrap .flw-item").mapNotNull { it.toSearch() }

    private fun Document.hasNextPage(): Boolean =
        selectFirst("a.page-link[title=Next]") != null

    private fun Element.toSearch(): SearchResponse? {
        val link = selectFirst("h3.film-name a") ?: return null
        val href = detailUrl(link.attr("href")) ?: return null
        val title = link.attr("title").ifBlank { link.text() }.ifBlank { return null }
        val poster = selectFirst("img.film-poster-img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val format = selectFirst(".fd-infor .fdi-item")?.text()
        val sub = selectFirst(".tick-sub")?.text()?.trim()?.toIntOrNull()
        val dub = selectFirst(".tick-dub")?.text()?.trim()?.toIntOrNull()
        return newAnimeSearchResponse(title, href, typeOf(format)) {
            this.posterUrl = poster
            addDubStatus(dub != null, sub != null, dub, sub)
        }
    }

    // Site type label -> CloudStream type. MOVIE must be a movie, otherwise the app shows episodes.
    private fun typeOf(format: String?): TvType =
        if (format?.trim().equals("MOVIE", true)) TvType.AnimeMovie else TvType.Anime

    // /watch/slug-123?ep=456  ->  https://hianime.at/slug-123
    private fun detailUrl(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val full = fixUrl(href).substringBefore("?").substringBefore("#")
        return full.replace("/watch/", "/").trimEnd('/')
    }

    // ------------------------------------------------------------------ load
    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = detailUrl(url) ?: return null
        val animeId = Regex("""-(\d+)$""").find(pageUrl)?.groupValues?.get(1) ?: return null
        val doc = app.get(pageUrl).document

        val title = doc.selectFirst(".anisc-detail h2.film-name")?.text()?.ifBlank { null }
            ?: doc.selectFirst("h2.film-name")?.text()
            ?: return null
        val jname = doc.selectFirst(".anisc-detail h2.film-name")?.attr("data-jname")?.ifBlank { null }
        val poster = doc.selectFirst(".anisc-poster img.film-poster-img")?.attr("src")
        val banner = doc.selectFirst(".anis-cover")?.attr("style")
            ?.let { Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1) }

        // "TV" / "MOVIE" / "ONA" / "OVA" / "SPECIAL" as written on the page.
        val format = doc.select(".film-stats span.item").map { it.text().trim() }
            .firstOrNull { it.isNotBlank() && !it.endsWith("m") }
        val type = typeOf(format)
        val isMovie = type == TvType.AnimeMovie

        val plot = doc.selectFirst(".anisc-info .item-title.w-hide .text")?.text()?.ifBlank { null }
            ?: doc.selectFirst(".film-description .text")?.text()
        val info = doc.select(".anisc-info .item").associate {
            it.selectFirst(".item-head")?.text()?.removeSuffix(":")?.trim().orEmpty() to
                (it.selectFirst(".name")?.text() ?: it.selectFirst(".text")?.text()).orEmpty().trim()
        }
        val year = info["Aired"]?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
        val duration = info["Duration"]?.let { Regex("""(\d+)""").find(it)?.value?.toIntOrNull() }
        val malScore = info["MAL Score"]?.toDoubleOrNull()

        val recommendations = doc
            .select("section.block_area:has(h2.cat-heading:containsOwn(Recommended)) .flw-item")
            .mapNotNull { it.toSearch() }

        // Episodes always come from the site.
        val epItems = runCatching {
            val html = app.get("$mainUrl/api/theme/episode/list/$animeId", headers = ajaxHeaders)
                .parsedSafe<HtmlResponse>()?.html.orEmpty()
            Jsoup.parse(html).select("a.ep-item").mapNotNull { a ->
                val epId = a.attr("data-id").ifBlank { return@mapNotNull null }
                val num = a.attr("data-number").toIntOrNull() ?: return@mapNotNull null
                Triple(num, epId, a.attr("title").ifBlank { null })
            }
        }.getOrNull().orEmpty().sortedBy { it.first }

        // MAL id from the first episode's ZokoAnime embed.
        val malId = epItems.firstOrNull()?.let { findMalId(it.second) }
        val zip = malId?.let { id -> runCatching { app.get("$aniZip$id").parsedSafe<AniZip>() }.getOrNull() }

        val kind = if (isMovie) "movie" else "tv"
        val background: String?
        val logo: String?
        if (zip != null) {
            // ani.zip -> background, TMDB id -> logo
            val tmdbId = resolveTmdb(zip, isMovie) ?: searchTmdb(title, jname, isMovie)
            val images = tmdbId?.let { tmdbImages(it.first, it.second) }
            background = zip.images?.firstOrNull { it.coverType.equals("Fanart", true) }?.url
                ?: images.bestBackdrop()
                ?: banner
            logo = images.bestLogo()
                ?: zip.images?.firstOrNull { it.coverType.equals("Clearlogo", true) }?.url
        } else {
            // No MAL id: TMDB title search -> backdrop + logo only
            val tmdbId = searchTmdb(title, jname, isMovie)
            val images = tmdbId?.let { tmdbImages(it.first, it.second) }
            background = images.bestBackdrop() ?: banner
            logo = images.bestLogo()
        }

        val episodes = epItems.map { (num, epId, siteTitle) ->
            val meta = zip?.episodes?.get(num.toString())
            newEpisode(Gson().toJson(EpisodeData(epId, num))) {
                this.name = meta?.title?.get("en")?.ifBlank { null }
                    ?: siteTitle?.takeUnless { it.equals("Full", true) }
                    ?: "Episode $num"
                this.episode = num
                this.description = meta?.overview?.ifBlank { null } ?: meta?.summary
                this.posterUrl = meta?.image ?: poster
                addDate(meta?.airDate ?: meta?.airdate)
            }
        }

        // Cast, tags, MAL / AniList ids are not attached on purpose.
        if (isMovie) {
            val movieData = epItems.firstOrNull()?.let { Gson().toJson(EpisodeData(it.second, it.first)) }
                ?: return null
            return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, movieData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.logoUrl = logo
                this.plot = plot
                this.year = year
                this.duration = duration
                this.score = malScore?.let { Score.from10(it) }
                this.recommendations = recommendations
            }
        }

        // TvSeries response (not AnimeLoadResponse), so the app does not pull cast / tags from AniList.
        return newTvSeriesLoadResponse(title, pageUrl, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.logoUrl = logo
            this.plot = plot
            this.year = year
            this.duration = duration
            this.score = malScore?.let { Score.from10(it) }
            this.recommendations = recommendations
        }
    }

    // ------------------------------------------------------------ servers
    private data class Server(val type: String, val name: String, val url: String)

    private suspend fun servers(episodeId: String): List<Server> {
        val html = app.get("$mainUrl/api/theme/episode/servers?episodeId=$episodeId", headers = ajaxHeaders)
            .parsedSafe<HtmlResponse>()?.html ?: return emptyList()
        return Jsoup.parse(html).select(".server-item").mapNotNull { el ->
            val hash = el.attr("data-hash").ifBlank { return@mapNotNull null }
            val embed = runCatching { String(Base64.getDecoder().decode(hash), Charsets.UTF_8) }.getOrNull()
                ?.trim()?.ifBlank { null } ?: return@mapNotNull null
            Server(el.attr("data-type").lowercase(), el.attr("data-server-name"), embed)
        }
    }

    private suspend fun findMalId(episodeId: String): Int? = runCatching {
        servers(episodeId).firstNotNullOfOrNull {
            Regex("""zokoanime\.[^/]+/stream/mal/(\d+)/""").find(it.url)?.groupValues?.get(1)?.toIntOrNull()
        }
    }.getOrNull()

    // ------------------------------------------------------------- TMDB
    // ani.zip themoviedb_id -> /find by tvdb id -> /find by imdb id.
    private suspend fun resolveTmdb(zip: AniZip, isMovie: Boolean): Pair<Int, String>? {
        val kind = if (isMovie) "movie" else "tv"
        zip.mappings?.themoviedbId?.toString()?.substringBefore(".")?.toIntOrNull()?.let { return it to kind }

        suspend fun find(externalId: String, source: String): Pair<Int, String>? {
            val res = runCatching {
                app.get("$tmdbApi/find/$externalId?api_key=$tmdbKey&external_source=$source").parsedSafe<TmdbFind>()
            }.getOrNull() ?: return null
            val movie = res.movieResults?.firstOrNull()?.id
            val tv = res.tvResults?.firstOrNull()?.id
            return when {
                isMovie && movie != null -> movie to "movie"
                tv != null -> tv to "tv"
                movie != null -> movie to "movie"
                else -> null
            }
        }

        zip.mappings?.thetvdbId?.let { find(it.toString(), "tvdb_id") }?.let { return it }
        zip.mappings?.imdbId?.ifBlank { null }?.let { find(it, "imdb_id") }?.let { return it }
        return null
    }

    // Title search with the cleaned English title, then the cleaned Japanese name.
    private suspend fun searchTmdb(title: String, jname: String?, isMovie: Boolean): Pair<Int, String>? {
        val kinds = if (isMovie) listOf("movie", "tv") else listOf("tv", "movie")
        val queries = listOfNotNull(cleanTitle(title), jname?.let { cleanTitle(it) })
            .filter { it.isNotBlank() }.distinct()
        for (q in queries) {
            for (kind in kinds) {
                val id = runCatching {
                    app.get("$tmdbApi/search/$kind?api_key=$tmdbKey&query=${encode(q)}")
                        .parsedSafe<TmdbSearch>()?.results?.firstOrNull()?.id
                }.getOrNull()
                if (id != null) return id to kind
            }
        }
        return null
    }

    private suspend fun tmdbImages(id: Int, kind: String): TmdbImages? = runCatching {
        app.get("$tmdbApi/$kind/$id/images?api_key=$tmdbKey&include_image_language=en,ja,null")
            .parsedSafe<TmdbImages>()
    }.getOrNull()

    private fun TmdbImages?.bestLogo(): String? = this?.logos?.let { logos ->
        (logos.firstOrNull { it.iso6391 == "en" } ?: logos.firstOrNull { it.iso6391 == null } ?: logos.firstOrNull())
            ?.filePath?.let { "$tmdbImg$it" }
    }

    // Textless backdrop first.
    private fun TmdbImages?.bestBackdrop(): String? = this?.backdrops?.let { b ->
        (b.firstOrNull { it.iso6391 == null } ?: b.firstOrNull())?.filePath?.let { "$tmdbImg$it" }
    }

    // Removes (...) / [...], season / part markers, Final, OVA, specials and roman numerals.
    private fun cleanTitle(raw: String): String {
        var clean = raw
        clean = clean.replace(Regex("""\s*[\(\[].*?[\)\]]"""), "")
        // 1st Season, 2nd Season, Season 2, Part 1, Part 2 ...
        clean = clean.replace(Regex("""(?i)(?:[:\-]\s*)?(?:\d+(?:st|nd|rd|th)\s+Season|Season\s+\d+|Part\s+\d+)(?:\s+Part\s+\d+)?\s*$"""), "")
        // The Final / Final Season
        clean = clean.replace(Regex("""(?i)(?:[:\-]\s*)?(?:The\s+Final|Final\s+Season)\s*$"""), "")
        // OVA / Omake
        clean = clean.replace(Regex("""(?i)(?:[:\-]\s*)?(?:OVA|Omake)\s*$"""), "")
        // One Special, Specials 2, Special, Two Specials ...
        val numWords = "(?:One|Two|Three|Four|Five|Six|Seven|Eight|Nine|Ten|\\d+)"
        val specials = "(?:Specials?)"
        clean = clean.replace(Regex("""(?i)(?:[:\-]\s+|\s+)?(?:$numWords\s+$specials|$specials\s+$numWords|$specials)\s*$"""), "")
        // I, II, III, IV, V, VI, VII, VIII, IX, X
        clean = clean.replace(Regex("""(?i)(?:[:\-]\s+|\s+)(?:I{1,3}|IV|V|VI{1,3}|IX|X)\s*$"""), "")
        return clean.trim().trimEnd(':', '-').trim()
    }

    // ------------------------------------------------------------- loadLinks
    // Order as on the site: SUB (ZokoAnime, HD-1), then DUB (ZokoAnime, HD-1).
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = runCatching { Gson().fromJson(data, EpisodeData::class.java) }.getOrNull() ?: return false
        val list = servers(ep.episodeId).sortedBy { if (it.type == "dub") 1 else 0 }

        val seen = HashSet<String>()
        var found = false
        val unique: (ExtractorLink) -> Unit = { link ->
            if (seen.add(link.name + "|" + link.url)) {
                found = true
                callback(link)
            }
        }

        for (server in list) {
            runCatching {
                when {
                    server.url.contains("zokoanime", true) ->
                        ZokoAnime().getUrl(server.url, "$mainUrl/", subtitleCallback, unique)
                    server.url.contains("megaplay", true) ->
                        MegaPlay().getUrl("${server.url}#${server.name}", "$mainUrl/", subtitleCallback, unique)
                    else ->
                        com.lagradost.cloudstream3.utils.loadExtractor(server.url, "$mainUrl/", subtitleCallback, unique)
                }
            }
        }
        return found
    }
}

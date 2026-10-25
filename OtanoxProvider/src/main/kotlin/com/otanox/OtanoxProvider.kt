package com.otanox

import com.google.gson.Gson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Base64

// ---------------------------------------------------------------------------
// Otanox (otanox.online)
// ---------------------------------------------------------------------------
// The site is a front end over AniList ids. Everything is read from JSON APIs:
//   api.otanox.online           catalog, trending, genre, search, anime info
//   otanox.online/api/provider  anikoto2 (Megaplay / Vidstream) + animesalt (Hindi)
//   api.ani.zip                 episode titles, overviews, thumbnails, fanart, clearlogo
//   api.themoviedb.org          logo / backdrop fallback (by id only, never by title search)
//   api.jikan.moe               MAL score
class OtanoxProvider : MainAPI() {
    override var mainUrl = "https://otanox.online"
    override var name = "Otanox"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val api = "https://api.otanox.online"
    private val aniZip = "https://api.ani.zip/mappings?anilist_id="
    private val animeSalt = "https://animesalt.cx"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = "1865f43a0549ca50d341dd9ab8b29f49"
    private val tmdbImg = "https://image.tmdb.org/t/p/original"
    private val perPage = 20

    // Same order the categories were requested in.
    override val mainPage = mainPageOf(
        "latest-episodes" to "Latest Episodes",
        "trending" to "Trending",
        "catalog?format=TV" to "TV Series",
        "catalog?format=MOVIE" to "Movies",
        "genre/Ecchi" to "Ecchi",
        "genre/Comedy" to "Comedy",
        "genre/Drama" to "Drama"
    )

    private fun encode(text: String): String =
        try { URLEncoder.encode(text, "UTF-8") } catch (_: Exception) { text }

    // Builds "<api>/<path>?page=N&perPage=20" whether or not the path already has a query.
    private fun pageUrl(path: String, page: Int): String {
        val sep = if (path.contains("?")) "&" else "?"
        return "$api/$path${sep}page=$page&perPage=$perPage"
    }

    // ------------------------------------------------------------------ home
    // Every list endpoint returns {page, perPage, total, data[]}.
    // There is no hasNextPage field, so next page exists while page * perPage < total.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get(pageUrl(request.data, page)).parsedSafe<ListResponse>()
        val items = res?.data.orEmpty().mapNotNull { it.toSearch() }
        val total = res?.total ?: 0
        val hasNext = items.isNotEmpty() && page * (res?.perPage ?: perPage) < total
        return newHomePageResponse(request.name, items, hasNext)
    }

    // ---------------------------------------------------------------- search
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val res = app.get(pageUrl("search?q=${encode(query)}", page)).parsedSafe<ListResponse>()
        val items = res?.data.orEmpty().mapNotNull { it.toSearch() }
        val hasNext = items.isNotEmpty() && page * (res?.perPage ?: perPage) < (res?.total ?: 0)
        return newSearchResponseList(items, hasNext)
    }

    // AniList format -> CloudStream type. MOVIE must be a movie, otherwise the app shows it as episodes.
    private fun typeOf(format: String?): TvType = when (format?.uppercase()) {
        "MOVIE" -> TvType.AnimeMovie
        else -> TvType.Anime
    }

    private fun OtanoxAnime.displayTitle(): String? =
        titleEnglish?.ifBlank { null } ?: titleRomaji?.ifBlank { null } ?: titleNative

    private fun OtanoxAnime.toSearch(): SearchResponse? {
        val id = anilistId ?: return null
        val title = displayTitle() ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id", typeOf(format)) {
            this.posterUrl = coverImageUrls?.firstOrNull()
        }
    }

    // ------------------------------------------------------------------ load
    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""/anime/(\d+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/watch/(\d+)""").find(url)?.groupValues?.get(1)
            ?: return null

        val info = app.get("$api/anime/$id").parsedSafe<InfoResponse>()?.data ?: return null
        val zip = runCatching { app.get("$aniZip$id").parsedSafe<AniZip>() }.getOrNull()

        val title = info.displayTitle() ?: "Anime $id"
        val type = typeOf(info.format)
        val isMovie = type == TvType.AnimeMovie
        val plot = info.description?.let { Jsoup.parse(it.replace("<br>", "\n")).text() }

        // TMDB id, tried in every way that needs no title search.
        val tmdb = resolveTmdb(info, zip, isMovie)
        val tmdbImages = tmdb?.let { (tmdbId, kind) ->
            runCatching {
                app.get("$tmdbApi/$kind/$tmdbId/images?api_key=$tmdbKey&include_image_language=en,ja,null")
                    .parsedSafe<TmdbImages>()
            }.getOrNull()
        }

        // Title logo: Otanox logoUrl (fanart) -> TMDB logo by id -> ani.zip Clearlogo.
        val logo = info.logoUrl?.ifBlank { null }
            ?: tmdbImages?.logos?.let { logos ->
                (logos.firstOrNull { it.iso6391 == "en" } ?: logos.firstOrNull { it.iso6391 == null } ?: logos.firstOrNull())
                    ?.filePath?.let { "$tmdbImg$it" }
            }
            ?: zip?.images?.firstOrNull { it.coverType.equals("Clearlogo", true) }?.url

        // Background: ani.zip Fanart -> TMDB backdrop (textless first). Banner is not used.
        val background = zip?.images?.firstOrNull { it.coverType.equals("Fanart", true) }?.url
            ?: tmdbImages?.backdrops?.let { b ->
                (b.firstOrNull { it.iso6391 == null } ?: b.firstOrNull())?.filePath?.let { "$tmdbImg$it" }
            }

        // MalId / AniListId are not attached on purpose: CloudStream would then pull
        // cast, genre chips and trailer from AniList, which are not wanted on this page.
        val malScore = info.idMal?.let { fetchMalScore(it) }

        // Recommendations from the AniList details proxy used by the site.
        val recommendations = fetchRecommendations(id)
        val score = malScore ?: info.averageScore?.let { it / 10.0 }

        // Episode list from anikoto2, enriched with ani.zip metadata.
        val anikoto = runCatching {
            app.get(
                "$mainUrl/api/provider/anikoto2/episodes?anilistId=$id",
                referer = "$mainUrl/"
            ).parsedSafe<AnikotoEpisodes>()
        }.getOrNull()

        val titles = listOfNotNull(info.titleEnglish, info.titleRomaji).filter { it.isNotBlank() }
        val anikotoByNum = anikoto?.episodes.orEmpty().filter { it.number != null }.associateBy { it.number!! }

        // If anikoto2 has nothing, still list episodes so the Hindi servers can be tried.
        val numbers: List<Int> = anikotoByNum.keys.sorted().ifEmpty {
            val count = info.episodes
                ?: zip?.episodeCount
                ?: info.nextAiringEpisode?.episode?.minus(1)
                ?: 1
            (1..maxOf(count, 1)).toList()
        }

        val episodes = numbers.map { num ->
            val ak = anikotoByNum[num]
            val meta = zip?.episodes?.get(num.toString())
            val data = EpisodeData(
                anilistId = id,
                episode = num,
                serverIds = ak?.serverIds,
                hasSub = ak?.hasSub ?: false,
                hasDub = ak?.hasDub ?: false,
                isMovie = isMovie,
                titles = titles
            )
            newEpisode(Gson().toJson(data)) {
                this.name = meta?.title?.get("en")?.ifBlank { null }
                    ?: ak?.title?.ifBlank { null }
                    ?: "Episode $num"
                this.episode = num
                this.description = meta?.overview
                this.posterUrl = meta?.image
                addDate(meta?.airDate ?: meta?.airdate)
            }
        }

        if (isMovie) {
            val movieData = episodes.firstOrNull()?.data
                ?: Gson().toJson(EpisodeData(id, 1, null, false, false, true, titles))
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, movieData) {
                this.posterUrl = info.coverImageUrls?.firstOrNull()
                this.backgroundPosterUrl = background
                this.logoUrl = logo
                this.plot = plot
                this.year = info.seasonYear ?: info.startDate?.year
                this.duration = info.duration
                this.score = score?.let { Score.from10(it) }
                this.recommendations = recommendations
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = info.coverImageUrls?.firstOrNull()
            this.backgroundPosterUrl = background
            this.logoUrl = logo
            this.plot = plot
            this.year = info.seasonYear ?: info.startDate?.year
            this.duration = info.duration
            this.score = score?.let { Score.from10(it) }
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // TMDB id order:
    //   1. Otanox tmdbId
    //   2. ani.zip mappings.themoviedb_id
    //   3. TMDB /find by tvdb id (Otanox tvdbId or ani.zip thetvdb_id)
    //   4. TMDB /find by imdb id (ani.zip imdb_id)
    // Returns (id, "tv" | "movie"). No title search, as requested.
    private suspend fun resolveTmdb(info: OtanoxAnime, zip: AniZip?, isMovie: Boolean): Pair<Int, String>? {
        val kind = if (isMovie) "movie" else "tv"
        info.tmdbId?.let { return it to kind }
        zip?.mappings?.themoviedbId?.toString()?.toIntOrNull()?.let { return it to kind }

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

        val tvdb = info.tvdbId ?: zip?.mappings?.thetvdbId
        tvdb?.let { find(it.toString(), "tvdb_id") }?.let { return it }
        zip?.mappings?.imdbId?.ifBlank { null }?.let { find(it, "imdb_id") }?.let { return it }
        return null
    }

    private suspend fun fetchRecommendations(id: String): List<SearchResponse> = runCatching {
        app.get("$mainUrl/api/anilist/details/$id", referer = "$mainUrl/")
            .parsedSafe<AniListDetails>()?.recommendations?.nodes.orEmpty()
            .mapNotNull { node ->
                val media = node.mediaRecommendation ?: return@mapNotNull null
                val recId = media.id ?: return@mapNotNull null
                val recTitle = media.title?.english?.ifBlank { null } ?: media.title?.romaji ?: return@mapNotNull null
                newAnimeSearchResponse(recTitle, "$mainUrl/anime/$recId", TvType.Anime) {
                    this.posterUrl = media.coverImage?.large
                }
            }
    }.getOrNull().orEmpty()

    private suspend fun fetchMalScore(malId: Int): Double? = runCatching {
        app.get("https://api.jikan.moe/v4/anime/$malId").parsedSafe<JikanResponse>()?.data?.score
    }.getOrNull()

    // ------------------------------------------------------------- loadLinks
    // Server order:
    //   Hindi available : Ravok -> Abyss [Hindi] -> Dub -> Sub -> other Abyss languages
    //   no Hindi        : Dub -> Sub (Dub skipped when the episode has none)
    // Dub / Sub servers: HD-1 / HD-2 -> "Megaplay-1" / "Megaplay-2",
    //                    Vidstream-1 / Vidstream-2 -> "Vidstream-1" / "Vidstream-2".
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = runCatching { Gson().fromJson(data, EpisodeData::class.java) }.getOrNull() ?: return false

        // Every server is shown, even when two of them share a playlist.
        // Only the exact same name + url pair is dropped.
        val seen = HashSet<String>()
        var found = false
        val unique: (ExtractorLink) -> Unit = { link ->
            if (seen.add(link.name + "|" + link.url)) {
                found = true
                callback(link)
            }
        }

        // Extractors are called directly (not loadExtractor) so the language label in the
        // url fragment reaches them and player.abyssplayer.com is matched correctly.

        // 1. Hindi from AnimeSalt
        val salt = runCatching { animeSaltEmbeds(ep) }.getOrNull()
        val otherLanguages = mutableListOf<Pair<String, String>>()
        if (salt != null) {
            if (salt.ravok != null) {
                runCatching { Ravok().getUrl(salt.ravok, "$animeSalt/", subtitleCallback, unique) }
            }
            salt.abyss.firstOrNull { it.first.equals("Hindi", true) }?.let { (lang, link) ->
                runCatching { Abyss().getUrl("$link#$lang", "$animeSalt/", subtitleCallback, unique) }
            }
            otherLanguages += salt.abyss.filterNot { it.first.equals("Hindi", true) }
        }

        // 2. Japanese dub, then sub, from anikoto2 / Megaplay
        if (!ep.serverIds.isNullOrBlank()) {
            val types = buildList {
                if (ep.hasDub) add("dub")
                if (ep.hasSub || !ep.hasDub) add("sub")
            }
            for (type in types) {
                for (embed in anikotoEmbeds(ep.serverIds, type)) {
                    runCatching { MegaPlay().getUrl(embed, "$mainUrl/", subtitleCallback, unique) }
                }
            }
        }

        // 3. Remaining AnimeSalt languages (Tamil, Telugu, English, Japanese ...)
        for ((lang, link) in otherLanguages) {
            runCatching { Abyss().getUrl("$link#$lang", "$animeSalt/", subtitleCallback, unique) }
        }

        return found
    }

    // anikoto2 sources -> megaplay.buzz embeds, each tagged with its server name.
    // The API often answers HD-2 with the HD-1 url, so all four embeds are built from the
    // real id it returns:
    //   HD-1        /stream/s-2/{id}/{type}?s=tcdn   -> Megaplay-1
    //   HD-2        /stream/s-2/{id}/{type}?s=bcdn   -> Megaplay-2
    //   Vidstream-1 /videojs/stream/s-2/{id}/{type}  -> Vidstream-1
    //   Vidstream-2 /stream/s-2/{id}/{type}          -> Vidstream-2
    private suspend fun anikotoEmbeds(serverIds: String, type: String): List<String> {
        val base = "$mainUrl/api/provider/anikoto2/sources?serverIds=${encode(serverIds)}&type=$type"
        val first = app.get(base, referer = "$mainUrl/").parsedSafe<AnikotoSource>()
        val apiUrl = first?.url?.ifBlank { null } ?: return emptyList()

        val match = Regex("""^(https?://[^/]+)/(?:videojs/)?stream/s-2/(\d+)/""").find(apiUrl)
            ?: return listOf("$apiUrl#Megaplay-1")
        val host = match.groupValues[1]
        val realId = match.groupValues[2]

        return listOf(
            "$host/stream/s-2/$realId/$type?s=tcdn#Megaplay-1",
            "$host/stream/s-2/$realId/$type?s=bcdn#Megaplay-2",
            "$host/videojs/stream/s-2/$realId/$type#Vidstream-1",
            "$host/stream/s-2/$realId/$type#Vidstream-2"
        )
    }

    // AnimeSalt (Hindi and other Indian languages).
    // The Otanox API usually says available:false but still gives slug / season / episode,
    // so the animesalt.cx page is read directly, same as the site front end does.
    private suspend fun animeSaltEmbeds(ep: EpisodeData): SaltEmbeds? {
        val info = app.get(
            "$mainUrl/api/provider/animesalt/servers?anilistId=${ep.anilistId}&ep=${ep.episode}",
            referer = "$mainUrl/"
        ).parsedSafe<SaltServers>()

        val candidates = mutableListOf<String>()
        if (!info?.slug.isNullOrBlank()) {
            val season = info?.season ?: 1
            val episode = info?.episode ?: ep.episode
            candidates += if (ep.isMovie || info?.kind.equals("movie", true)) {
                "$animeSalt/movies/${info!!.slug}/"
            } else {
                "$animeSalt/episode/${info!!.slug}-${season}x$episode/"
            }
        } else {
            // Not mapped by Otanox: try a slug made from the title (works for many movies, e.g. "your-name").
            ep.titles.map { slugify(it) }.filter { it.isNotBlank() }.distinct().forEach { slug ->
                candidates += if (ep.isMovie) "$animeSalt/movies/$slug/" else "$animeSalt/episode/$slug-1x${ep.episode}/"
            }
        }

        for (pageUrl in candidates) {
            val res = runCatching { app.get(pageUrl, referer = "$animeSalt/") }.getOrNull() ?: continue
            if (res.code != 200) continue
            val doc = res.document
            val frames = doc.select("iframe").map { it.attr("src").ifBlank { it.attr("data-src") } }

            // SERVER 1: ravok.buzz/video/<hash>, one player with every audio track.
            val ravok = frames.firstOrNull { it.contains("/video/") }

            // SERVER 2: player.php?data=<base64 json [{language, link}]>, one Abyss link per language.
            val abyss = frames.firstOrNull { it.contains("player.php?data=") }?.let { decodePlayerData(it) }.orEmpty()

            if (ravok == null && abyss.isEmpty()) continue
            return SaltEmbeds(ravok, abyss)
        }
        return null
    }

    private fun decodePlayerData(frameUrl: String): List<Pair<String, String>> {
        val raw = java.net.URLDecoder.decode(frameUrl.substringAfter("data=").substringBefore("&"), "UTF-8")
        val json = runCatching { String(Base64.getDecoder().decode(raw), Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val lang = o.optString("language").ifBlank { return@mapNotNull null }
            val link = o.optString("link").ifBlank { return@mapNotNull null }
            lang to link
        }
    }

    private fun slugify(title: String): String =
        title.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
}

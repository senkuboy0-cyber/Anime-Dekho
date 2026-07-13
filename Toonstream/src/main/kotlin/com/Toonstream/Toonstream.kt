package com.Toonstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class Toonstream : MainAPI() {
override var mainUrl: String = runBlocking {
ToonstreamProvider.getDomains()?.Toonstream ?: "https://toon-stream.site"
}
override var name                 = "Toonstream"
override val hasMainPage          = true
override var lang                 = "hi"
override val hasDownloadSupport   = true
override val supportedTypes       = setOf(TvType.Movie, TvType.Anime, TvType.Cartoon)

// ─── TMDB Logo Feature ───────────────────────────────────────  
private val TMDB_API = "https://api.themoviedb.org/3"  
private val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"  
private val TMDB_IMG = "https://image.tmdb.org/t/p/original"  

// TMDB API response data classes  
data class TmdbImages(  
    @JsonProperty("logos") val logos: List<TmdbLogo>? = null  
)  
data class TmdbLogo(  
    @JsonProperty("file_path") val filePath: String? = null,  
    @JsonProperty("iso_639_1") val lang: String?     = null  
)  
data class TmdbFind(  
    @JsonProperty("movie_results") val movies: List<TmdbResult>? = null,  
    @JsonProperty("tv_results")    val tvShows: List<TmdbResult>? = null  
)  
data class TmdbResult(  
    @JsonProperty("id") val id: Int? = null  
)  
data class TmdbSearch(  
    @JsonProperty("results") val results: List<TmdbResult>? = null  
)  

/**
 * A helper function to deeply clean the title for better UI display and TMDB searching.
 */
private fun cleanTitleText(title: String): String {
    var clean = title.replace("Watch Online", "", ignoreCase = true)
    
    // Rule 1: Remove episode patterns like " 2x11", " 1×54" and everything after it
    // Matches both English 'x' and multiplication sign '×' only if surrounded by numbers
    clean = clean.replace(Regex("\\s+\\d+[x×]\\d+.*", RegexOption.IGNORE_CASE), "")
    
    // Rule 2: Remove "fan dub" or "fandub" and everything after it
    clean = clean.replace(Regex("\\s*fan\\s*dub.*", RegexOption.IGNORE_CASE), "")
    clean = clean.replace(Regex("\\s*fandub.*", RegexOption.IGNORE_CASE), "")
    
    // Rule 3: Remove everything from the first open bracket '(' or '[' onwards safely
    clean = clean.substringBefore("(")
    clean = clean.substringBefore("[")
    
    // Final trim to ensure no trailing/leading whitespaces
    return clean.trim()
}

/**  
 * Fetches Title Logo URL from TMDB.  
 * First searches for IMDB ID on the page, if not found, searches TMDB using the cleaned title.
 * Automatically fallbacks to search the opposite media type (movie <-> tv) if not found.
 */  
private suspend fun fetchLogoUrl(document: Document, title: String, isSeries: Boolean): String? {  
    return try {  
        var actualMediaType = if (isSeries) "tv" else "movie"  

        // ── Method 1: Search for IMDB link on the Page ──  
        val imdbId = document  
            .select("a[href*='imdb.com/title']")  
            .attr("href")  
            .substringAfter("title/")  
            .substringBefore("/")  
            .takeIf { it.startsWith("tt") }  

        var tmdbId: Int? = null

        if (imdbId != null) {  
            // Get TMDB ID using IMDB ID  
            app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id")  
                .parsedSafe<TmdbFind>()  
                ?.let {  
                    if (isSeries) {
                        tmdbId = it.tvShows?.firstOrNull()?.id
                        if (tmdbId == null) {
                            tmdbId = it.movies?.firstOrNull()?.id
                            if (tmdbId != null) actualMediaType = "movie"
                        }
                    } else {
                        tmdbId = it.movies?.firstOrNull()?.id
                        if (tmdbId == null) {
                            tmdbId = it.tvShows?.firstOrNull()?.id
                            if (tmdbId != null) actualMediaType = "tv"
                        }
                    } 
                }  
        } 
        
        if (tmdbId == null) {  
            // ── Method 2: TMDB Search using Cleaned Title ──  
            val safeTitle = title.replace(Regex("\\s+"), "+")
            
            // Step A: Search in the expected category
            val searchRes = app.get("$TMDB_API/search/$actualMediaType?api_key=$TMDB_KEY&query=$safeTitle")  
                .parsedSafe<TmdbSearch>()  
            tmdbId = searchRes?.results?.firstOrNull()?.id  

            // Step B: If not found, search in the opposite category
            if (tmdbId == null) {
                actualMediaType = if (isSeries) "movie" else "tv"
                val fallbackSearchRes = app.get("$TMDB_API/search/$actualMediaType?api_key=$TMDB_KEY&query=$safeTitle")  
                    .parsedSafe<TmdbSearch>()  
                tmdbId = fallbackSearchRes?.results?.firstOrNull()?.id
            }
        }  

        if (tmdbId == null) return null  

        // ── Fetch Logo Image from TMDB ──  
        val images = app.get(  
            "$TMDB_API/$actualMediaType/$tmdbId/images?api_key=$TMDB_KEY"  
        ).parsedSafe<TmdbImages>()  

        // Priority: English -> No Language -> Japanese -> Any fallback
        val logo = images?.logos?.firstOrNull { it.lang == "en" }  
            ?: images?.logos?.firstOrNull { it.lang == null }
            ?: images?.logos?.firstOrNull { it.lang == "ja" }
            ?: images?.logos?.firstOrNull()

        logo?.filePath?.let { "$TMDB_IMG$it" }  

    } catch (e: Exception) {  
        null  // Return null quietly if no Logo is found  
    }  
}  
// ─────────────────────────────────────────────────────────────  

override val mainPage = mainPageOf(  
    "fresh-drop"                              to "Fresh Drop",  
    "category/anime-series"                   to "Anime Series",  
    "category/anime-movies"                   to "Anime Movies",  
    "category/language/hindi-language"        to "Hindi",  
    "category/animation-&-cartoon-series"     to "Animation & Cartoon Series",  
    "category/animation-&-cartoon-movie"      to "Animation & Cartoon Movie"  
)  

override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {  
    if (request.data == "fresh-drop") {  
        val items = fetchFreshDrop()  
        return newHomePageResponse(  
            list = HomePageList(name = request.name, list = items, isHorizontalImages = true),  
            hasNext = false  
        )  
    }  

    val path = request.data  
    val url = if (page == 1) "$mainUrl/$path/" else "$mainUrl/$path/?page=$page"  

    val document = app.get(url).document  
    val home = document.select("#movies-a ul > li").mapNotNull { it.toSearchResult() }  

    return newHomePageResponse(  
        list = HomePageList(name = request.name, list = home, isHorizontalImages = false),  
        hasNext = home.isNotEmpty()  
    )  
}  

private suspend fun fetchFreshDrop(): List<SearchResponse> {  
    val document = app.get("$mainUrl/home/").document  

    val header = document.select("h3.section-title")  
        .firstOrNull { it.text().contains("Fresh Drop", ignoreCase = true) }  
        ?: return emptyList()  

    val section = header.parents().firstOrNull {  
        it.select("article.post.dfx").isNotEmpty()  
    } ?: return emptyList()  

    return section.select("article.post.dfx").mapNotNull { el ->  
        val rawTitle = el.selectFirst("h2.entry-title")?.text() ?: return@mapNotNull null
        val title = cleanTitleText(rawTitle).ifBlank { return@mapNotNull null }
        
        val href  = el.selectFirst("a.lnk-blk")?.attr("href")?.let { fixUrl(it) }  
            ?: return@mapNotNull null  
        val posterRaw = el.selectFirst("img")?.attr("src")  
        val poster = if (posterRaw.isNullOrEmpty()) null  
            else if (posterRaw.startsWith("http")) posterRaw  
            else "https:$posterRaw"  
        val rating = el.selectFirst("span.vote")?.text()  
            ?.replace("TMDB", "")?.trim()?.toDoubleOrNull()  

        newMovieSearchResponse(title, href, TvType.TvSeries) {  
            this.posterUrl = poster  
            this.score = Score.from10(rating)  
        }  
    }  
}  

private fun Element.toSearchResult(): SearchResponse? {  
    val rawTitle = this.selectFirst("article > header > h2, article h2.entry-title")?.text() ?: return null
    val title = cleanTitleText(rawTitle).ifBlank { return null }
    
    val href  = fixUrl(  
        this.selectFirst("article > a.lnk-blk, article a.lnk-blk")  
            ?.attr("href") ?: return null  
    )  
    val posterRaw = this.selectFirst("article img")?.attr("src") ?: ""  
    val poster = when {  
        posterRaw.startsWith("http") -> posterRaw  
        posterRaw.startsWith("//")   -> "https:$posterRaw"  
        posterRaw.isNotEmpty()       -> posterRaw  
        else                         -> null  
    }  
    val tvType = when {  
        href.contains("/series/") -> TvType.TvSeries  
        href.contains("/movies/") -> TvType.Movie  
        else                      -> TvType.Movie  
    }  
    return newMovieSearchResponse(title, href, tvType) {  
        this.posterUrl = poster  
    }  
}  

override suspend fun search(query: String): List<SearchResponse> {  
    val results = mutableListOf<SearchResponse>()  
    for (i in 1..3) {  
        val searchUrl = if (i == 1) {
            "$mainUrl/s?q=$query"
        } else {
            "$mainUrl/page/$i/s?q=$query"
        }
        
        val doc = app.get(searchUrl).document  
        
        var page = doc.select("#movies-a ul > li").mapNotNull { it.toSearchResult() }  
        if (page.isEmpty()) {
            page = doc.select("article, .result-item, .item").mapNotNull { it.toSearchResult() }
        }
        
        if (page.isEmpty() || results.containsAll(page)) break  
        results.addAll(page)  
    }  
    return results  
}  

override suspend fun load(url: String): LoadResponse {  
    val document    = app.get(url).document  
    
    // Fetch and Clean the Title for UI & TMDB
    val rawTitle    = document.selectFirst("header.entry-header > h1")?.text() ?: ""
    val title       = cleanTitleText(rawTitle)
        
    val posterRaw   = document.select("div.bghd > img").attr("src")  
    val poster      = if (posterRaw.startsWith("http")) posterRaw else "https:$posterRaw"  
    val description = document.selectFirst("div.description > p")?.text()?.trim()  
    val isSeries    = url.contains("/series/")  

    // ── Fetch TMDB Logo ──  
    val logoUrl = fetchLogoUrl(document, title, isSeries)  

    return if (isSeries) {  
        loadSeries(url, document, title, poster, description, logoUrl)  
    } else {  
        newMovieLoadResponse(title, url, TvType.Movie, url) {  
            this.posterUrl = poster  
            this.plot      = description  
            this.logoUrl   = logoUrl   // ← Title Logo is set!  
        }  
    }  
}  

private suspend fun loadSeries(  
    url: String,  
    document: Document,  
    title: String,  
    poster: String,  
    description: String?,  
    logoUrl: String?          // ← New parameter  
): LoadResponse {  
    val episodes = mutableListOf<Episode>()  

    val seasonNumbers = document.select("a.season-btn").mapNotNull { el ->  
        el.attr("data-season").toIntOrNull()  
    }.distinct().sorted()  

    for (season in seasonNumbers) {  
        val seasonDoc = try {  
            app.post(  
                "$mainUrl/wp-admin/admin-ajax.php",  
                data = mapOf(  
                    "action" to "action_select_season",  
                    "season" to season.toString(),  
                    "post"   to (document.selectFirst("a.season-btn[data-season='$season']")  
                        ?.attr("data-post") ?: "")  
                ),  
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")  
            ).document  
        } catch (e: Exception) {  
            org.jsoup.Jsoup.parse("")  
        }  

        val finalDoc = if (seasonDoc.select("article").isEmpty()) {  
            try { app.get("$url/season/$season").document }  
            catch (e: Exception) { seasonDoc }  
        } else seasonDoc  

        finalDoc.select("article.post.episodes, article.post").forEach { ep ->  
            val epHref = ep.selectFirst("a.lnk-blk, a")?.attr("href") ?: return@forEach  
            val epPoster = ep.selectFirst("img")?.attr("src")  
                ?.let { if (it.startsWith("http")) it else "https:$it" }  
            val epName = ep.selectFirst("h5.entry-title1, h2.entry-title, h3.entry-title")  
                ?.text()?.trim() ?: "Episode"  
            episodes.add(newEpisode(fixUrl(epHref)) {  
                this.name      = epName  
                this.posterUrl = epPoster  
                this.season    = season  
            })  
        }  
    }  

    if (episodes.isEmpty()) {  
        document.select("#episode_by_temp article.post").forEach { ep ->  
            val epHref = ep.selectFirst("a.lnk-blk, a")?.attr("href") ?: return@forEach  
            val epPoster = ep.selectFirst("img")?.attr("src")  
                ?.let { if (it.startsWith("http")) it else "https:$it" }  
            val epName   = ep.selectFirst("h5.entry-title1")?.text()?.trim() ?: "Episode"  
            val numEpi   = ep.selectFirst("span.num-epi")?.text()?.trim()  
            val epSeason = numEpi?.substringBefore("x")?.toIntOrNull() ?: 1  
            episodes.add(newEpisode(fixUrl(epHref)) {  
                this.name      = epName  
                this.posterUrl = epPoster  
                this.season    = epSeason  
            })  
        }  
    }  

    return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {  
        this.posterUrl = poster  
        this.plot      = description  
        this.logoUrl   = logoUrl   // ← Title Logo is set!  
    }  
}  

override suspend fun loadLinks(  
    data: String,  
    isCasting: Boolean,  
    subtitleCallback: (SubtitleFile) -> Unit,  
    callback: (ExtractorLink) -> Unit  
): Boolean {  
    val document = app.get(data).document  

    data class ServerInfo(val truelink: String, val referer: String, val priority: Int)  

    val servers = document.select("#aa-options > div > iframe").mapNotNull { iframe ->  
        val rawSrc = iframe.attr("data-src").ifEmpty { iframe.attr("src") }  
        if (rawSrc.isEmpty()) return@mapNotNull null  

        val serverlink = if (rawSrc.startsWith("http")) rawSrc else "$mainUrl$rawSrc"  

        val truelink = try {  
            app.get(serverlink, referer = mainUrl)  
                .document  
                .selectFirst(".Video iframe, div.Video iframe, iframe[src]")  
                ?.attr("src") ?: ""  
        } catch (e: Exception) { "" }  

        if (truelink.isEmpty()) return@mapNotNull null  

        val priority = when {  
            truelink.contains("as-cdn21.top")    -> 0  
            truelink.contains("emturbovid.com")  -> 1  
            truelink.contains("gdmirrorbot.nl")  -> 2  
            truelink.contains("rubystm.com")     -> 3  
            truelink.contains("vidmoly.net")     -> 4  
            truelink.contains("abyssplayer.com") -> 5  
            truelink.contains("cloudy.upns.one") -> 6  
            else                                 -> 7  
        }  
        ServerInfo(truelink, serverlink, priority)  
    }  

    val fixedCallback: (ExtractorLink) -> Unit = { link ->  
        if (link.url.substringBefore("?").endsWith(".txt")) {  
            callback(  
                ExtractorLink(  
                    source        = link.source,  
                    name          = link.name,  
                    url           = link.url,  
                    referer       = link.referer,  
                    quality       = link.quality,  
                    type          = ExtractorLinkType.M3U8,  
                    headers       = link.headers,  
                    extractorData = link.extractorData  
                )  
            )  
        } else {  
            callback(link)  
        }  
    }  

    servers.sortedBy { it.priority }.forEach { server ->  
        loadExtractor(server.truelink, server.referer, subtitleCallback, fixedCallback)  
    }  
    return true  
}

}

// ─── AWSStream / Zephyrflick ──────────────────────────────────
class Zephyrflick : AWSStream() {
override val name    = "Zephyrflick"
override val mainUrl = "https://play.zephyrflick.top"
override val requiresReferer = true
}

open class AWSStream : ExtractorApi() {
override val name    = "AWSStream"
override val mainUrl = "https://z.awstream.net"
override val requiresReferer = true

override suspend fun getUrl(  
    url: String,  
    referer: String?,  
    subtitleCallback: (SubtitleFile) -> Unit,  
    callback: (ExtractorLink) -> Unit  
) {  
    val extractedHash = url.substringAfterLast("/")  
    val header   = mapOf("x-requested-with" to "XMLHttpRequest")  
    val formdata = mapOf("hash" to extractedHash, "r" to mainUrl)  
    val apiUrl   = "$mainUrl/player/index.php?data=$extractedHash&do=getVideo"  
    val response = app.post(apiUrl, headers = header, data = formdata)  
        .parsedSafe<Response>()  

    response?.videoSource?.let { m3u8 ->  
        callback(  
            newExtractorLink(name, name, url = m3u8, type = ExtractorLinkType.M3U8) {  
                this.referer = ""  
                this.quality = Qualities.P1080.value  
            }  
        )  
        val doc = app.get(url).document  
        val packed = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()  
            .orEmpty()  
        JsUnpacker(packed).unpack()?.let { unpacked ->  
            Regex("""kind":\s*"captions"\s*,\s*"file":\s*"(https.*?\.srt)"""")  
                .find(unpacked)?.groupValues?.get(1)?.let { srtUrl ->  
                    subtitleCallback(SubtitleFile("English", srtUrl))  
                }  
        }  
    }  
}  

data class Response(  
    val hls: Boolean,  
    val videoImage: String,  
    val videoSource: String,  
    val securedLink: String,  
    val downloadLinks: List<Any?>,  
    val attachmentLinks: List<Any?>,  
    val ck: String,  
)

}

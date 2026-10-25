package com.otanox

import com.fasterxml.jackson.annotation.JsonProperty

// ---------------------------------------------------------------------------
// api.otanox.online
// ---------------------------------------------------------------------------

// List endpoints: /latest-episodes, /trending, /catalog, /genre/{name}, /search
data class ListResponse(
    val success: Boolean? = null,
    val page: Int? = null,
    val perPage: Int? = null,
    val total: Int? = null,
    val data: List<OtanoxAnime>? = null,
)

// /anime/{anilistId}
data class InfoResponse(
    val success: Boolean? = null,
    val data: OtanoxAnime? = null,
)

data class OtanoxAnime(
    val anilistId: Int? = null,
    val idMal: Int? = null,
    val tmdbId: Int? = null,
    val tvdbId: Int? = null,
    val titleEnglish: String? = null,
    val titleRomaji: String? = null,
    val titleNative: String? = null,
    val description: String? = null,
    val format: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val seasonYear: Int? = null,
    val startDate: FuzzyDate? = null,
    val averageScore: Int? = null,
    val genres: List<String>? = null,
    val coverImageUrls: List<String>? = null,
    val bannerImageUrls: List<String>? = null,
    val logoUrl: String? = null,
    val logoSource: String? = null,
    val nextAiringEpisode: NextAiring? = null,
)

data class FuzzyDate(val year: Int? = null, val month: Int? = null, val day: Int? = null)

data class NextAiring(val airingAt: Long? = null, val episode: Int? = null)

// ---------------------------------------------------------------------------
// otanox.online/api/provider/anikoto2
// ---------------------------------------------------------------------------

data class AnikotoEpisodes(
    val animeId: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val episodes: List<AnikotoEpisode>? = null,
)

data class AnikotoEpisode(
    val number: Int? = null,
    val title: String? = null,
    val serverIds: String? = null,
    val hasSub: Boolean? = null,
    val hasDub: Boolean? = null,
)

// /sources?serverIds=&type=&server= -> megaplay.buzz embed
data class AnikotoSource(
    val type: String? = null,
    val server: String? = null,
    val url: String? = null,
    val servers: Map<String, List<String>>? = null,
)

// ---------------------------------------------------------------------------
// otanox.online/api/provider/animesalt
// ---------------------------------------------------------------------------

data class SaltServers(
    val available: Boolean? = null,
    val reason: String? = null,
    val slug: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val kind: String? = null,
    val languages: List<String>? = null,
)

// What loadLinks needs from an animesalt.cx page.
data class SaltEmbeds(
    val ravok: String?,
    val abyss: List<Pair<String, String>>, // (language, abyssplayer link)
)

// ---------------------------------------------------------------------------
// otanox.online/api/anilist/details/{id} (only recommendations are used)
// ---------------------------------------------------------------------------

data class AniListDetails(val recommendations: AniListRecs? = null)

data class AniListRecs(val nodes: List<AniListRecNode>? = null)

data class AniListRecNode(val mediaRecommendation: AniListMedia? = null)

data class AniListMedia(
    val id: Int? = null,
    val title: AniListTitle? = null,
    val coverImage: AniListCover? = null,
)

data class AniListTitle(val romaji: String? = null, val english: String? = null)

data class AniListCover(val large: String? = null)

// ---------------------------------------------------------------------------
// api.ani.zip
// ---------------------------------------------------------------------------

data class AniZip(
    val episodeCount: Int? = null,
    val episodes: Map<String, AniZipEpisode>? = null,
    val images: List<AniZipImage>? = null,
    val mappings: AniZipMappings? = null,
)

data class AniZipEpisode(
    val title: Map<String, String?>? = null,
    val overview: String? = null,
    val image: String? = null,
    val airDate: String? = null,
    val airdate: String? = null,
    val runtime: Int? = null,
)

data class AniZipImage(val coverType: String? = null, val url: String? = null)

data class AniZipMappings(
    @JsonProperty("themoviedb_id") val themoviedbId: Any? = null,
    @JsonProperty("thetvdb_id") val thetvdbId: Int? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("mal_id") val malId: Int? = null,
)

// ---------------------------------------------------------------------------
// TMDB / Jikan
// ---------------------------------------------------------------------------

data class TmdbImages(
    val logos: List<TmdbImage>? = null,
    val backdrops: List<TmdbImage>? = null,
    val posters: List<TmdbImage>? = null,
)

data class TmdbImage(
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("iso_639_1") val iso6391: String? = null,
)

data class TmdbFind(
    @JsonProperty("tv_results") val tvResults: List<TmdbId>? = null,
    @JsonProperty("movie_results") val movieResults: List<TmdbId>? = null,
)

data class TmdbId(val id: Int? = null)

data class JikanResponse(val data: JikanAnime? = null)

data class JikanAnime(val score: Double? = null)

// ---------------------------------------------------------------------------
// Data passed from load() to loadLinks() for one episode (or the movie).
// ---------------------------------------------------------------------------
data class EpisodeData(
    val anilistId: String,
    val episode: Int,
    val serverIds: String?,
    val hasSub: Boolean,
    val hasDub: Boolean,
    val isMovie: Boolean,
    val titles: List<String> = emptyList(),
)

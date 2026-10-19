version = 8

cloudstream {
    language = "en"
    authors = listOf("senkuboy0-cyber")
    description = "Hianimeq: Watch Sub & Dub anime in HD"
    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Anime",
        "AnimeMovie"
    )

    iconUrl = "https://raw.githubusercontent.com/senkuboy0-cyber/Anime-Dekho/refs/heads/main/assets/icons/Hianimeq.png"

    isCrossPlatform = true
}
version = 15

cloudstream {
    language = "hi"
    authors = listOf("senkuboy0-cyber")
    description = "Tooniboy: The best place for Hindi & Multi-language Anime, Cartoons and Movies."
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
        "AnimeMovie",
        "Cartoon",
        "TvSeries"
    )

    iconUrl = "https://raw.githubusercontent.com/senkuboy0-cyber/Anime-Dekho/refs/heads/main/assets/icons/tooniboy.png"

    isCrossPlatform = true
}

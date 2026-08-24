version = 1

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

    iconUrl = "https://tooniboy.co/wp-content/uploads/2024/03/cropped-tooniboy-high-resolution-logo-transparent-7.png"

    isCrossPlatform = true
}

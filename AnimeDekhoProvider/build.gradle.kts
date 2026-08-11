version = 3

cloudstream {
    language = "hi"
    authors = listOf("senkuboy0-cyber")
    description = "AnimeDekho: The best place for Hindi Dubbed Anime, Movies & Cartoons."
    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AnimeMovie",
        "Anime",
        "Cartoon"
    )

    iconUrl = "https://animedekho.app/wp-content/uploads/2023/07/AnimeDekho-Logo-300x-1.png"

    isCrossPlatform = true
}

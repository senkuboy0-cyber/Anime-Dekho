version = 35

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

    iconUrl = "https://raw.githubusercontent.com/senkuboy0-cyber/Anime-Dekho/refs/heads/main/assets/icons/AnimeDekho.png"

    isCrossPlatform = true
}

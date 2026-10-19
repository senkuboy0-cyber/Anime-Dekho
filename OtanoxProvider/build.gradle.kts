version = 2

cloudstream {
    language = "en"
    authors = listOf("senkuboy0-cyber")
    description = "OtaNox: Stream anime — fast, cinematic."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "AnimeMovie",
        "Anime"
    )

    iconUrl = "https://raw.githubusercontent.com/senkuboy0-cyber/Anime-Dekho/refs/heads/main/assets/icons/otanox.png"

    isCrossPlatform = true
}
import org.gradle.api.provider.Provider

include(
    ":AnimeDekhoProvider",
    ":MyAnimesProvider",
    ":TooniboyProvider",
    ":Toonstream",
    ":Mlsbd",
    ":MovieBoxProvider",
    ":Blakite",
    ":Zephyrflick",
    ":HianimeqProvider",
    ":HiAnime"
)

fun include(vararg modules: String) {
    modules.forEach {
        include(it)
    }
}

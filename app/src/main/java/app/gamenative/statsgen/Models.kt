package app.gamenative.statsgen

data class Achievement(
    val name: String,
    val displayName: Map<String, String>? = null,
    val description: Map<String, String>? = null,
    val hidden: Int = 0,
    val icon: String? = null,
    val iconGray: String? = null,
    val icongray: String? = null,
    val progress: Map<String, Any>? = null,
    val unlocked: Boolean? = null,
    val unlockTimestamp: Int? = null,
    val formattedUnlockTime: String? = null
)

data class Stat(
    val id: String,
    val name: String,
    val type: String,
    val default: String = "0",
    val global: String = "0",
    val min: String? = null
)

data class ProcessingResult(
    val achievements: List<Achievement>,
    val stats: List<Stat>,
    val copyDefaultUnlockedImg: Boolean,
    val copyDefaultLockedImg: Boolean,
    val nameToBlockBit: Map<String, Pair<Int, Int>> = emptyMap(),
)

// Describes one leaderboard as returned by the Steam Community web API or cached locally.
// sortMethod and displayType mirror the ELeaderboardSortMethod / ELeaderboardDisplayType enum
// integer values so they round-trip through JSON without needing enum conversions.
data class LeaderboardDefinition(
    val name: String,
    val id: Int,          // Steam numeric leaderboard ID (stable; cache and reuse)
    val sortMethod: Int,  // 1=Ascending, 2=Descending
    val displayType: Int  // 1=Numeric, 2=TimeSeconds, 3=TimeMilliSeconds
)

// One entry in a leaderboard's score table, matching GBE Fork's leaderboards.json format.
data class LeaderboardScoreEntry(
    val steamId: Long,    // SteamID.convertToUInt64()
    val score: Int,
    val details: List<Int>,
    val name: String = "" // display name; empty string is valid for GBE Fork
)

object StatType {
    const val STAT_TYPE_INT = "1"
    const val STAT_TYPE_FLOAT = "2"
    const val STAT_TYPE_AVGRATE = "3"
    const val STAT_TYPE_BITS = "4"

    const val ACHIEVEMENTS = "ACHIEVEMENTS"
    const val INT = "INT"
    const val FLOAT = "FLOAT"
    const val AVGRATE = "AVGRATE"
}

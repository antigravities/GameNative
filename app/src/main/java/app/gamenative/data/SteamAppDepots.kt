package app.gamenative.data

// Minimal two-column projection used only for background sizeBytes computation.
// Fetching just id + depots (rather than the full SteamApp entity) keeps row sizes smaller
// and limits JSON deserialization to the one field we actually need.
data class SteamAppDepots(
    val id: Int,
    val depots: Map<Int, DepotInfo>,
)

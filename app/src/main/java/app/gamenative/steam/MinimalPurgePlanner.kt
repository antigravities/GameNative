package app.gamenative.steam

import app.gamenative.db.dao.AppParent

/** modernMinimal: decides which steam_app rows fall outside the selected collection. */
object MinimalPurgePlanner {
    /**
     * Rows to delete: not [wanted], not in [keep] (installed / downloading), and not DLC of a
     * wanted or kept app (DLC of an installed orphan stays with it).
     */
    fun idsToDelete(rows: List<AppParent>, wanted: Set<Int>, keep: Set<Int>): List<Int> =
        rows.asSequence()
            .filter { row ->
                row.id !in wanted &&
                    row.id !in keep &&
                    row.dlcForAppId !in wanted &&
                    row.dlcForAppId !in keep
            }
            .map { it.id }
            .toList()
}

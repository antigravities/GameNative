package app.gamenative.data

// Minimal projection for the one-time install_dir repair backfill
// (SteamService.backfillInstallDirColumnOnce). The flat install_dir column mirrors
// config.installDir but was left blank for every row synced before the KeyValueUtils
// installDir path bug was fixed (it read a nonexistent "common.config.installdir" KeyValues
// path instead of the root-level "config.installdir"). config is deserialized via Room's
// TypeConverters, so it's the only place the correct value survives for those already-synced
// rows.
data class SteamAppInstallDirRepairRow(
    val id: Int,
    val config: ConfigInfo,
)

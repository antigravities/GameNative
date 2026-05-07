package app.gamenative.data

import kotlinx.serialization.Serializable

@Serializable
data class ConfigInfo(
    val installDir: String = "",
    val launch: List<LaunchInfo> = emptyList(),
    val steamControllerTemplateIndex: Int = 0,
    val steamControllerTouchTemplateIndex: Int = 0,
    val steamInputManifestPath: String = "",
    val steamControllerConfigDetails: List<SteamControllerConfigDetail> = emptyList(),
    // val steamControllerTouchConfigDetails: TouchConfigDetails,
    // True when Steam PICS reports community/workshop = 1 for this app.
    val hasWorkshop: Boolean = false,
)

@Serializable
data class SteamControllerConfigDetail(
    val publishedFileId: Long = 0L,
    val controllerType: String = "",
    val enabledBranches: List<String> = emptyList(),
)

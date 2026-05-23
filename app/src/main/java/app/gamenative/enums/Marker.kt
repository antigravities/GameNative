package app.gamenative.enums

enum class Marker(val fileName: String ) {
    DOWNLOAD_COMPLETE_MARKER(".download_complete"),
    DOWNLOAD_IN_PROGRESS_MARKER(".download_in_progress"),
    STEAM_DLL_REPLACED(".steam_dll_replaced"),
    STEAM_DLL_RESTORED(".steam_dll_restored"),
    STEAM_COLDCLIENT_USED(".steam_coldclient_used"),
    VCREDIST_INSTALLED(".vcredist_installed"),
    GOG_SCRIPT_INSTALLED(".gog_script_installed"),
    PHYSX_INSTALLED(".physx_installed"),
    OPENAL_INSTALLED(".openal_installed"),
    XNA_INSTALLED(".xna_installed"),
    UBISOFT_CONNECT_INSTALLED(".ubisoft_connect_installed"),
    ITCHIO_INSTALLER_RAN(".itchio_installer_ran"),
    ITCHIO_INSTALLER_CONSENT(".itchio_installer_consent"),
    ITCHIO_INSTALLER_SKIP(".itchio_installer_skip"),
}

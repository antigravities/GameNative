package app.gamenative.ui.screen.minimal

import org.junit.Assert.assertEquals
import org.junit.Test

class MinimalGameActionsTest {
    private val none = Int.MAX_VALUE // SteamService.INVALID_APP_ID

    private fun entry(
        installed: Boolean = false,
        orphan: Boolean = false,
        downloadPercent: Int? = null,
        paused: Boolean = false,
    ) = MinimalEntry(1, "Game", installed, orphan, downloadPercent, paused)

    @Test fun startingIgnoresA() {
        // Requested but no job yet: A must not start a second download.
        val starting = MinimalEntry(1, "Game", installed = false, orphan = false, downloadPercent = null, starting = true)
        assertEquals(PrimaryAction.Starting, primaryAction(starting))
        assertEquals(PrimaryAction.Starting, primaryAction(starting.copy(paused = true)))
    }

    @Test fun orphanIsAlwaysUninstall() =
        assertEquals(PrimaryAction.Uninstall, primaryAction(entry(installed = true, orphan = true, downloadPercent = 10)))

    @Test fun downloadingIsPause() =
        assertEquals(PrimaryAction.Pause, primaryAction(entry(downloadPercent = 42, paused = true)))

    @Test fun pausedIsResume() =
        assertEquals(PrimaryAction.Resume, primaryAction(entry(paused = true)))

    @Test fun installedIsLaunch() =
        assertEquals(PrimaryAction.Launch, primaryAction(entry(installed = true)))

    @Test fun otherwiseInstall() =
        assertEquals(PrimaryAction.Install, primaryAction(entry()))

    @Test fun defaultDlcSkipsMainIndirectAndOptional() {
        val depots = listOf(
            DepotDlc(dlcAppId = none, optionalDlcId = none), // base game depot
            DepotDlc(dlcAppId = 10, optionalDlcId = none), // regular DLC -> included
            DepotDlc(dlcAppId = 10, optionalDlcId = none), // second depot of the same DLC -> once
            DepotDlc(dlcAppId = 20, optionalDlcId = 20), // optional DLC -> excluded
            DepotDlc(dlcAppId = 30, optionalDlcId = none), // indirect DLC -> excluded
            DepotDlc(dlcAppId = 5, optionalDlcId = none), // included, sorted first
        )
        assertEquals(listOf(5, 10), defaultInstallDlcIds(depots, indirectDlcAppIds = setOf(30)))
    }

    @Test fun defaultDlcEmptyForBaseOnly() =
        assertEquals(emptyList<Int>(), defaultInstallDlcIds(listOf(DepotDlc(none, none)), emptySet()))
}

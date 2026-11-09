package app.gamenative.steam

import app.gamenative.data.SteamCollection
import org.junit.Assert.assertEquals
import org.junit.Test

class MinimalCollectionResolverTest {
    private val play = SteamCollection("uc-play", "To Play", setOf(440, 570))
    private val other = SteamCollection("uc-other", "Other", setOf(730))
    private val all = listOf(play, other)

    @Test fun emptyIdIsNotSelected() =
        assertEquals(MinimalSelection.NotSelected, MinimalCollectionResolver.resolve("", "", all))

    @Test fun emptyIdIsNotSelectedEvenWhenNotLoaded() =
        assertEquals(MinimalSelection.NotSelected, MinimalCollectionResolver.resolve("", "", null))

    @Test fun nullCollectionsIsNotLoadedAndKeepsLastName() =
        assertEquals(
            MinimalSelection.NotLoaded("uc-play", "To Play"),
            MinimalCollectionResolver.resolve("uc-play", "To Play", null),
        )

    @Test fun unknownIdIsMissing() =
        assertEquals(
            MinimalSelection.Missing("uc-gone", "Gone"),
            MinimalCollectionResolver.resolve("uc-gone", "Gone", all),
        )

    @Test fun loadedButEmptyIsMissing() =
        assertEquals(
            MinimalSelection.Missing("uc-play", "To Play"),
            MinimalCollectionResolver.resolve("uc-play", "To Play", emptyList()),
        )

    @Test fun matchResolvesWithAppIds() {
        val result = MinimalCollectionResolver.resolve("uc-play", "stale name", all)
        assertEquals(MinimalSelection.Resolved(play), result)
        assertEquals(setOf(440, 570), (result as MinimalSelection.Resolved).appIds)
    }
}

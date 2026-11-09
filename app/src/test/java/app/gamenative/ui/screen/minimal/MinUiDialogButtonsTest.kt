package app.gamenative.ui.screen.minimal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinUiDialogButtonsTest {
    private fun btn(tag: String) = MinUiDialogButton(tag.uppercase(), tag)

    @Test
    fun `confirm, dismiss and request map to A, X and B`() {
        // Sync conflict: Keep remote / Keep local / close.
        val b = minUiDialogBindings(btn("remote"), btn("local"), null, "close")
        assertEquals("remote", b.a?.onClick)
        assertEquals("local", b.x?.onClick)
        assertNull(b.y)
        assertEquals("close", b.back)
        assertFalse(b.showBackHint)
    }

    @Test
    fun `an OK-only dialog puts the dismiss button on A`() {
        val b = minUiDialogBindings(null, btn("ok"), null, "close")
        assertEquals("ok", b.a?.onClick)
        assertNull(b.x)
        assertEquals("close", b.back)
        assertTrue(b.showBackHint)
    }

    @Test
    fun `dismiss plus action puts the action on Y`() {
        // Executable not found: OK + Edit container.
        val b = minUiDialogBindings(null, btn("ok"), btn("edit"), "close")
        assertEquals("ok", b.a?.onClick)
        assertNull(b.x)
        assertEquals("edit", b.y?.onClick)
        assertTrue(b.showBackHint)
    }

    @Test
    fun `confirm without dismiss leaves X empty`() {
        // Crash notice: confirm + request only.
        val b = minUiDialogBindings(btn("ok"), null, null, "close")
        assertEquals("ok", b.a?.onClick)
        assertNull(b.x)
        assertTrue(b.showBackHint)
    }

    @Test
    fun `nothing set binds nothing`() {
        val b = minUiDialogBindings<String>(null, null, null, null)
        assertNull(b.a)
        assertNull(b.x)
        assertNull(b.y)
        assertNull(b.back)
        assertFalse(b.showBackHint)
    }

    @Test
    fun `no request means no B pill`() {
        val b = minUiDialogBindings(null, btn("ok"), null, null)
        assertNull(b.back)
        assertFalse(b.showBackHint)
    }
}

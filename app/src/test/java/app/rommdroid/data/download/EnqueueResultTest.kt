package app.rommdroid.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The long-press gesture is invisible: the snackbar is the only evidence the
 * user gets that anything happened, so what it says is worth pinning down.
 */
class EnqueueResultTest {

    @Test
    fun `queueing one file offers an undo`() {
        val message = EnqueueResult.Queued("Chrono Trigger", listOf("42_7")).asMessage()

        assertEquals("Queued Chrono Trigger", message.text)
        assertEquals(listOf("42_7"), message.undoIds)
    }

    @Test
    fun `a multi-file rom says how many files it queued`() {
        val message = EnqueueResult
            .Queued("Final Fantasy VII", listOf("1_1", "1_2", "1_3"))
            .asMessage()

        assertEquals("Queued Final Fantasy VII · 3 files", message.text)
    }

    @Test
    fun `the region of the copy that was picked is named`() {
        // Which variant a folded row stands for is otherwise invisible from
        // the list, and it is the whole reason to reach for undo.
        val message = EnqueueResult.Queued("Chrono Trigger", listOf("42_7")).asMessage("🇯🇵")

        assertTrue(message.text, message.text.contains("Chrono Trigger"))
        assertTrue(message.text, message.text.endsWith("🇯🇵"))
    }

    @Test
    fun `a second long-press on the same game does not offer an undo`() {
        val message = EnqueueResult.AlreadyQueued("Chrono Trigger").asMessage()

        assertEquals("Chrono Trigger is already in the queue", message.text)
        assertTrue(message.undoIds.isEmpty())
        assertTrue(!message.needsFolder)
    }

    @Test
    fun `no folder set sends the user to the folder picker`() {
        val message = EnqueueResult.NoFolder.asMessage()

        assertTrue(message.needsFolder)
        assertTrue(message.undoIds.isEmpty())
    }

    @Test
    fun `a failure is reported verbatim`() {
        val message = EnqueueResult.Failed("Unable to resolve host").asMessage()

        assertEquals("Unable to resolve host", message.text)
        assertTrue(!message.needsFolder)
    }
}

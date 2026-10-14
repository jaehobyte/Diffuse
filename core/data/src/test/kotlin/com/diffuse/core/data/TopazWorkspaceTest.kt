package com.diffuse.core.data

import com.diffuse.core.common.DispatcherProvider
import com.diffuse.core.common.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** specs/topaz_enhancement.md §5: who owns which Topaz file, and when it goes. */
class TopazWorkspaceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatchers = object : DispatcherProvider {
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
    }

    @Test
    fun `the first session of a process removes what an earlier process left, and only that`() = runTest {
        val root = temp.newFolder("topaz")
        val orphan = File(root, "old-session").apply { mkdirs() }
        File(orphan, "output_1.jpg").writeText("x")
        val project = temp.newFolder("projects", "p1")
        File(project, "source.jpg").writeText("keep")
        val workspace = TopazWorkspace(root, dispatchers)

        val first = (workspace.open() as Result.Success).value
        first.input.writeText("in")
        val second = (workspace.open() as Result.Success).value

        assertFalse(orphan.exists())
        assertTrue("a session this process opened survives the next open", first.input.isFile)
        assertTrue(second.dir.isDirectory)
        assertTrue(File(project, "source.jpg").isFile)
    }

    @Test
    fun `discard removes one session's files and no other's`() = runTest {
        val workspace = TopazWorkspace(temp.newFolder("topaz"), dispatchers)
        val mine = (workspace.open() as Result.Success).value
        val other = (workspace.open() as Result.Success).value
        mine.output(1).writeText("a")
        other.output(1).writeText("b")

        workspace.discard(mine)

        assertFalse(mine.dir.exists())
        assertEquals("b", other.output(1).readText())
    }

    @Test
    fun `each run writes its own result file`() = runTest {
        val session = (TopazWorkspace(temp.newFolder("topaz"), dispatchers).open() as Result.Success).value

        assertFalse(session.output(1) == session.output(2))
    }
}

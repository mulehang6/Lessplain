package com.lessplain.experiment.ide

import com.lessplain.experiment.model.BrowserHostKind
import com.lessplain.experiment.model.JsonPayload
import com.lessplain.experiment.model.ViewDocument
import com.lessplain.experiment.model.ViewRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BrowserViewSelectionTest {
    private val views = listOf(
        view("root"),
        view("entry", parentRef = "root"),
        view("sibling", parentRef = "root"),
        view("child", parentRef = "entry"),
        view("other-root"),
    )

    @Test
    fun `editor selection can move among entry siblings and descendants`() {
        val fixedTreeRoot = viewTreeRootRef("entry", views)

        assertEquals("root", fixedTreeRoot)
        assertEquals("root", resolveBrowserViewSelection(BrowserHostKind.EDITOR, fixedTreeRoot, "root", views))
        assertEquals("entry", resolveBrowserViewSelection(BrowserHostKind.EDITOR, fixedTreeRoot, "entry", views))
        assertEquals("sibling", resolveBrowserViewSelection(BrowserHostKind.EDITOR, fixedTreeRoot, "sibling", views))
        assertEquals("child", resolveBrowserViewSelection(BrowserHostKind.EDITOR, fixedTreeRoot, "child", views))
    }

    @Test
    fun `editor selection cannot leave its tree or show history`() {
        val fixedTreeRoot = viewTreeRootRef("entry", views)

        assertFailsWith<IllegalStateException> {
            resolveBrowserViewSelection(BrowserHostKind.EDITOR, fixedTreeRoot, "other-root", views)
        }
        assertFailsWith<IllegalStateException> {
            resolveBrowserViewSelection(BrowserHostKind.EDITOR, fixedTreeRoot, null, views)
        }
    }

    @Test
    fun `editor tree stays anchored when its root is reparented`() {
        val movedViews = views + view("new-parent")
        val reparentedViews = movedViews.map { record ->
            if (record.ref == "root") record.copy(parentRef = "new-parent") else record
        }

        assertEquals("sibling", resolveBrowserViewSelection(BrowserHostKind.EDITOR, "root", "sibling", reparentedViews))
        assertFailsWith<IllegalStateException> {
            resolveBrowserViewSelection(BrowserHostKind.EDITOR, "root", "new-parent", reparentedViews)
        }
    }

    @Test
    fun `tool window selection remains global and can show history`() {
        assertEquals("other-root", resolveBrowserViewSelection(BrowserHostKind.TOOL_WINDOW, null, "other-root", views))
        assertNull(resolveBrowserViewSelection(BrowserHostKind.TOOL_WINDOW, null, null, views))
    }

    private fun view(ref: String, parentRef: String? = null) = ViewRecord(
        ref = ref,
        revision = 1,
        document = ViewDocument("问题", JsonPayload.empty()),
        sources = emptyMap(),
        createdAt = 0,
        updatedAt = 0,
        parentRef = parentRef,
    )
}

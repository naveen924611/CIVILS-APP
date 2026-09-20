package com.naveen.civilscompanion.syllabus

import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.ui.syllabus.TopicTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicTreeTest {
    private fun t(id: String, parent: String?, title: String, status: String = "not_started", pos: Int = 0, tags: List<String> = emptyList(), approved: Boolean = true, deleted: Boolean = false) =
        Topic(id = id, parentId = parent, title = title, status = status, position = pos, examTags = tags, approved = approved, deleted = deleted, level = if (parent == null) 0 else 1)

    private val topics = listOf(
        t("p", null, "GS Paper 2", tags = listOf("UPSC", "APPSC")),
        t("a", "p", "Preamble", "studied", 1),
        t("b", "p", "Federalism", "revised", 0),
        t("c", "p", "Parliament", "in_progress", 2),
        t("q", null, "Essay", tags = listOf("UPSC")),
        t("e", "q", "Essay themes", "strong"),
        t("x", "p", "Not approved", approved = false),
        t("y", "p", "Deleted", deleted = true),
        t("z", "gone", "Orphan", "strong"),
    )

    @Test
    fun buildSortsByPositionAndCountsCoverageOverLeaves() {
        val tree = TopicTree.build(topics)
        assertEquals(listOf("Essay", "GS Paper 2", "Orphan"), tree.map { it.topic.title }.sorted())
        val paper = tree.first { it.topic.id == "p" }
        assertEquals(listOf("Federalism", "Preamble", "Parliament"), paper.children.map { it.topic.title })
        assertEquals(3, paper.leaves)
        assertEquals(2, paper.covered)
        assertEquals(67, paper.coverage)
        assertEquals(100, tree.first { it.topic.id == "q" }.coverage)
        assertEquals(75, TopicTree.coverage(tree.filter { it.topic.id != "z" }))
    }

    @Test
    fun examFilterKeepsTaggedTopicsAndTheirParents() {
        val appsc = TopicTree.build(topics, "APPSC")
        assertEquals(listOf("GS Paper 2"), appsc.map { it.topic.title }) // untagged children follow the paper
        assertEquals(3, appsc[0].children.size)
        val upsc = TopicTree.build(topics, "UPSC")
        assertTrue(upsc.any { it.topic.title == "Essay" })
    }

    @Test
    fun rowsHideCollapsedChildren() {
        val tree = TopicTree.build(topics).filter { it.topic.id == "p" }
        assertEquals(4, TopicTree.rows(tree, emptySet()).size)
        val rows = TopicTree.rows(tree, setOf("p"))
        assertEquals(1, rows.size)
        assertEquals(0, rows[0].depth)
    }

    @Test
    fun searchFindsAtAnyDepthAndIgnoresCase() {
        val tree = TopicTree.build(topics)
        assertEquals(listOf("Federalism"), TopicTree.search(tree, "  FEDERAL").map { it.topic.title })
        assertEquals(2, TopicTree.search(tree, "essay").size)
        assertTrue(TopicTree.search(tree, "   ").isEmpty())
    }

    @Test
    fun pathFindAndIdsBelow() {
        assertEquals("GS Paper 2 / Federalism", TopicTree.path(topics, "b"))
        val tree = TopicTree.build(topics)
        assertNotNull(TopicTree.find(tree, "e"))
        assertNull(TopicTree.find(tree, "nope"))
        assertEquals(setOf("p", "a", "b", "c"), TopicTree.idsBelow(tree.first { it.topic.id == "p" }).toSet())
    }

    @Test
    fun labels() {
        assertEquals("Not started", TopicTree.statusLabel("weird"))
        assertEquals("In progress", TopicTree.statusLabel("in_progress"))
        assertEquals("High", TopicTree.importanceLabel(8.0))
        assertEquals("Medium", TopicTree.importanceLabel(4.0))
        assertEquals("Low", TopicTree.importanceLabel(1.0))
        assertTrue(TopicTree.isCovered("revised"))
        assertEquals(5, TopicTree.STATUSES.size)
    }

    @Test
    fun ancestorsListParentsFirst() {
        assertEquals(listOf("p"), TopicTree.ancestors(topics, "b"))
        assertEquals(emptyList<String>(), TopicTree.ancestors(topics, "p"))
        assertEquals(emptyList<String>(), TopicTree.ancestors(topics, "unknown"))
        assertEquals(emptyList<String>(), TopicTree.ancestors(topics, "z")) // its parent is not in the list
    }
}

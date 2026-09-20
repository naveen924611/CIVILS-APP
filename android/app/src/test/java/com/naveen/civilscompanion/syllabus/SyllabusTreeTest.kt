package com.naveen.civilscompanion.syllabus

import com.naveen.civilscompanion.ui.syllabus.SNode
import com.naveen.civilscompanion.ui.syllabus.SyllabusTree
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyllabusTreeTest {
    private val source = Json.parseToJsonElement(
        """[
          {"title":"GS Paper 1","exam_tags":["upsc"],"children":[
            {"title":"Polity","est_hours":3,"children":[{"title":"Preamble"},{"title":"Federalism","importance":8.5}]},
            {"title":"History","children":[]}
          ]},
          {"title":"  ","children":[]},
          "junk",
          {"title":"Essay","exam_tags":["APPSC","x"]}
        ]""",
    )

    private fun titles(nodes: List<SNode>): List<String> = nodes.map { it.title }

    @Test
    fun parseReadsTitlesTagsNumbersAndSkipsJunk() {
        val tree = SyllabusTree.parse(source)
        assertEquals(listOf("GS Paper 1", "Essay"), titles(tree))
        assertEquals(listOf("UPSC"), tree[0].examTags)
        assertEquals(listOf("APPSC"), tree[1].examTags)
        assertEquals(3.0, tree[0].children[0].estHours!!, 0.0)
        assertEquals(8.5, tree[0].children[0].children[1].importance!!, 0.0)
        assertEquals(6, SyllabusTree.count(tree))
        val keys = SyllabusTree.rows(tree, emptySet()).map { it.node.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(SyllabusTree.parse(null).isEmpty())
        assertTrue(SyllabusTree.parse(JsonPrimitive("x")).isEmpty())
    }

    @Test
    fun toJsonRoundTrips() {
        val tree = SyllabusTree.parse(source)
        val json = SyllabusTree.toJson(tree)
        assertEquals(2, json.size)
        val first = json[0] as JsonObject
        assertEquals(JsonPrimitive("GS Paper 1"), first["title"])
        assertTrue((first["children"] as JsonArray).size == 2)
        assertEquals(titles(tree), titles(SyllabusTree.parse(json)))
        assertEquals(SyllabusTree.count(tree), SyllabusTree.count(SyllabusTree.parse(json)))
    }

    @Test
    fun renameAddRemove() {
        var tree = SyllabusTree.parse(source)
        val polity = tree[0].children[0]
        tree = SyllabusTree.rename(tree, polity.key, "  Indian Polity ")
        assertEquals("Indian Polity", tree[0].children[0].title)
        assertEquals(tree, SyllabusTree.rename(tree, polity.key, "   "))
        tree = SyllabusTree.addChild(tree, polity.key, "Judiciary")
        assertEquals(listOf("Preamble", "Federalism", "Judiciary"), titles(tree[0].children[0].children))
        tree = SyllabusTree.addChild(tree, null, "Ethics")
        assertEquals("Ethics", tree.last().title)
        val keys = SyllabusTree.rows(tree, emptySet()).map { it.node.key }
        assertEquals(keys.size, keys.toSet().size)
        tree = SyllabusTree.remove(tree, polity.key)
        assertNull(SyllabusTree.find(tree, polity.key))
        assertEquals(listOf("History"), titles(tree[0].children))
        assertEquals(tree, SyllabusTree.remove(tree, 9999))
    }

    @Test
    fun moveUpAndDownStayInsideTheirParent() {
        var tree = SyllabusTree.parse(source)
        val history = tree[0].children[1]
        assertTrue(SyllabusTree.canMoveUp(tree, history.key))
        assertFalse(SyllabusTree.canMoveDown(tree, history.key))
        tree = SyllabusTree.moveUp(tree, history.key)
        assertEquals(listOf("History", "Polity"), titles(tree[0].children))
        tree = SyllabusTree.moveUp(tree, history.key)
        assertEquals(listOf("History", "Polity"), titles(tree[0].children))
        tree = SyllabusTree.moveDown(tree, history.key)
        assertEquals(listOf("Polity", "History"), titles(tree[0].children))
        assertFalse(SyllabusTree.canMoveUp(tree, tree[0].key))
        assertTrue(SyllabusTree.canMoveDown(tree, tree[0].key))
    }

    @Test
    fun mergeMovesChildrenAndTagsIntoThePreviousSibling() {
        var tree = SyllabusTree.parse(source)
        val polity = tree[0].children[0]
        val history = tree[0].children[1]
        tree = SyllabusTree.addChild(tree, history.key, "Modern India")
        tree = SyllabusTree.mergeWithPrevious(tree, history.key)
        assertEquals(listOf("Polity"), titles(tree[0].children))
        assertEquals(listOf("Preamble", "Federalism", "Modern India"), titles(tree[0].children[0].children))
        assertEquals(polity.key, tree[0].children[0].key)
        assertEquals(tree, SyllabusTree.mergeWithPrevious(tree, polity.key)) // first child: nothing above it
    }

    @Test
    fun splitKeepsChildrenOnTheFirstPart() {
        var tree = SyllabusTree.parse(source)
        val polity = tree[0].children[0]
        tree = SyllabusTree.split(tree, polity.key, SyllabusTree.splitTitles("Constitution; Parliament\n Judiciary ;"))
        assertEquals(listOf("Constitution", "Parliament", "Judiciary", "History"), titles(tree[0].children))
        assertEquals(2, tree[0].children[0].children.size)
        assertTrue(tree[0].children[1].children.isEmpty())
        assertEquals(tree, SyllabusTree.split(tree, tree[0].children[0].key, listOf("Only one")))
        val keys = SyllabusTree.rows(tree, emptySet()).map { it.node.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun tagsRowsAndExamFilter() {
        var tree = SyllabusTree.parse(source)
        tree = SyllabusTree.setTags(tree, tree[1].key, listOf("UPSC", "APPSC", "UPSC"))
        assertEquals(listOf("UPSC", "APPSC"), tree[1].examTags)
        val collapsed = setOf(tree[0].children[0].key)
        assertEquals(
            listOf("GS Paper 1", "Polity", "History", "Essay"),
            SyllabusTree.rows(tree, collapsed).map { it.node.title },
        )
        assertEquals(listOf(0, 1, 1, 0), SyllabusTree.rows(tree, collapsed).map { it.depth })
        assertEquals(listOf("GS Paper 1", "Essay"), titles(SyllabusTree.keepExam(tree, "UPSC")))
        val appsc = SyllabusTree.keepExam(tree, "APPSC")
        assertEquals(listOf("Essay"), titles(appsc)) // the UPSC-only paper and its untagged children are left out
        assertEquals(tree, SyllabusTree.keepExam(tree, null))
    }
}

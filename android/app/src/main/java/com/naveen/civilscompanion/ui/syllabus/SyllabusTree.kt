package com.naveen.civilscompanion.ui.syllabus

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** One line of a syllabus that is being checked (import review). `key` is a number that only identifies it on this screen. */
data class SNode(
    val key: Int,
    val title: String,
    val examTags: List<String> = emptyList(),
    val estHours: Double? = null,
    val importance: Double? = null,
    val children: List<SNode> = emptyList(),
)

/** A line of the tree as it is drawn: the node and how deep it is. */
data class SRow(val node: SNode, val depth: Int)

/**
 * Pure editing of a syllabus tree (no Android): read it from the server's JSON, rename, add, delete, move, merge and split
 * topics, and write it back as JSON. Every function returns a new tree; nothing is changed in place.
 */
object SyllabusTree {

    private class Keys {
        var last = 0
        fun next(): Int {
            last += 1
            return last
        }
    }

    /** Reads the server's `tree_json` (a list of {title, exam_tags, est_hours, importance, children}). Unreadable parts are skipped. */
    fun parse(root: JsonElement?): List<SNode> {
        val keys = Keys()
        return readList(root, keys)
    }

    private fun readList(element: JsonElement?, keys: Keys): List<SNode> {
        val array = element as? JsonArray ?: return emptyList()
        val out = ArrayList<SNode>()
        for (item in array) {
            val obj = item as? JsonObject ?: continue
            val title = text(obj["title"]).trim()
            if (title.isEmpty()) continue
            val tags = (obj["exam_tags"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase() }
                ?.filter { it == "UPSC" || it == "APPSC" || it == "SI" }
                ?.distinct()
                .orEmpty()
            val key = keys.next()
            out.add(
                SNode(
                    key = key,
                    title = title,
                    examTags = tags,
                    estHours = number(obj["est_hours"]),
                    importance = number(obj["importance"]),
                    children = readList(obj["children"], keys),
                ),
            )
        }
        return out
    }

    private fun text(e: JsonElement?): String = (e as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun number(e: JsonElement?): Double? =
        if (e == null || e is JsonNull) null else (e as? JsonPrimitive)?.doubleOrNull

    /** Writes the tree in the shape the server expects (`PUT /syllabus/{id}/tree`, `POST /syllabus/{id}/approve`). */
    fun toJson(nodes: List<SNode>): JsonArray = JsonArray(nodes.map { node ->
        val fields = LinkedHashMap<String, JsonElement>()
        fields["title"] = JsonPrimitive(node.title)
        fields["exam_tags"] = JsonArray(node.examTags.map { JsonPrimitive(it) })
        node.estHours?.let { fields["est_hours"] = JsonPrimitive(it) }
        node.importance?.let { fields["importance"] = JsonPrimitive(it) }
        fields["children"] = toJson(node.children)
        JsonObject(fields)
    })

    fun count(nodes: List<SNode>): Int = nodes.sumOf { 1 + count(it.children) }

    fun find(nodes: List<SNode>, key: Int): SNode? {
        for (n in nodes) {
            if (n.key == key) return n
            val inner = find(n.children, key)
            if (inner != null) return inner
        }
        return null
    }

    private fun maxKey(nodes: List<SNode>): Int = nodes.maxOfOrNull { maxOf(it.key, maxKey(it.children)) } ?: 0

    /** Runs `edit(siblings, index)` on the list that holds the node with this key. Unknown key: nothing changes. */
    private fun editSiblings(nodes: List<SNode>, key: Int, edit: (List<SNode>, Int) -> List<SNode>): List<SNode> {
        val index = nodes.indexOfFirst { it.key == key }
        if (index >= 0) return edit(nodes, index)
        return nodes.map { n -> if (n.children.isEmpty()) n else n.copy(children = editSiblings(n.children, key, edit)) }
    }

    fun rename(nodes: List<SNode>, key: Int, title: String): List<SNode> {
        val clean = title.trim()
        if (clean.isEmpty()) return nodes
        return editSiblings(nodes, key) { list, i -> list.toMutableList().also { it[i] = it[i].copy(title = clean) } }
    }

    fun remove(nodes: List<SNode>, key: Int): List<SNode> =
        editSiblings(nodes, key) { list, i -> list.toMutableList().also { it.removeAt(i) } }

    /** Adds a new node as the last child of `parentKey` (null = at the top). Returns the new tree. */
    fun addChild(nodes: List<SNode>, parentKey: Int?, title: String): List<SNode> {
        val clean = title.trim()
        if (clean.isEmpty()) return nodes
        val fresh = SNode(key = maxKey(nodes) + 1, title = clean)
        if (parentKey == null) return nodes + fresh
        return editSiblings(nodes, parentKey) { list, i ->
            list.toMutableList().also { it[i] = it[i].copy(children = it[i].children + fresh) }
        }
    }

    fun moveUp(nodes: List<SNode>, key: Int): List<SNode> = editSiblings(nodes, key) { list, i ->
        if (i == 0) list else list.toMutableList().also {
            val above = it[i - 1]
            it[i - 1] = it[i]
            it[i] = above
        }
    }

    fun moveDown(nodes: List<SNode>, key: Int): List<SNode> = editSiblings(nodes, key) { list, i ->
        if (i >= list.lastIndex) list else list.toMutableList().also {
            val below = it[i + 1]
            it[i + 1] = it[i]
            it[i] = below
        }
    }

    fun canMoveUp(nodes: List<SNode>, key: Int): Boolean = siblingIndex(nodes, key).let { it != null && it.first > 0 }

    fun canMoveDown(nodes: List<SNode>, key: Int): Boolean = siblingIndex(nodes, key).let { it != null && it.first < it.second - 1 }

    /** (index, sibling count) of a node, or null when it is not in the tree. */
    private fun siblingIndex(nodes: List<SNode>, key: Int): Pair<Int, Int>? {
        val i = nodes.indexOfFirst { it.key == key }
        if (i >= 0) return i to nodes.size
        for (n in nodes) {
            val inner = siblingIndex(n.children, key)
            if (inner != null) return inner
        }
        return null
    }

    /** Merge: the node's sub-topics and exam tags move into the sibling above it, and the node itself goes away. */
    fun mergeWithPrevious(nodes: List<SNode>, key: Int): List<SNode> = editSiblings(nodes, key) { list, i ->
        if (i == 0) list else list.toMutableList().also {
            val target = it[i - 1]
            val gone = it[i]
            it[i - 1] = target.copy(
                examTags = (target.examTags + gone.examTags).distinct(),
                estHours = if (target.estHours == null && gone.estHours == null) null else (target.estHours ?: 0.0) + (gone.estHours ?: 0.0),
                importance = target.importance ?: gone.importance,
                children = target.children + gone.children,
            )
            it.removeAt(i)
        }
    }

    /** Split: the node keeps the first title (and its sub-topics); the other titles become new nodes right after it. */
    fun split(nodes: List<SNode>, key: Int, titles: List<String>): List<SNode> {
        val clean = titles.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.size < 2) return nodes
        var next = maxKey(nodes)
        return editSiblings(nodes, key) { list, i ->
            list.toMutableList().also {
                val original = it[i]
                it[i] = original.copy(title = clean.first())
                val extras = clean.drop(1).map { title ->
                    next += 1
                    SNode(key = next, title = title, examTags = original.examTags)
                }
                it.addAll(i + 1, extras)
            }
        }
    }

    /** "Tags": the owner chooses UPSC, APPSC, SI or a mix for a node. */
    fun setTags(nodes: List<SNode>, key: Int, tags: List<String>): List<SNode> = editSiblings(nodes, key) { list, i ->
        list.toMutableList().also { it[i] = it[i].copy(examTags = tags.distinct()) }
    }

    /** Splits what the owner typed ("A; B; C" or one per line) into titles. */
    fun splitTitles(input: String): List<String> =
        input.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** The lines to draw, hiding everything under a collapsed node. */
    fun rows(nodes: List<SNode>, collapsed: Set<Int>): List<SRow> {
        val out = ArrayList<SRow>()
        fun walk(list: List<SNode>, depth: Int) {
            for (n in list) {
                out.add(SRow(n, depth))
                if (n.key !in collapsed) walk(n.children, depth + 1)
            }
        }
        walk(nodes, 0)
        return out
    }

    /** Keeps only the nodes for one exam, plus the parents of kept nodes. A node with no tags follows its parent; with no tags at all it is kept. */
    fun keepExam(nodes: List<SNode>, exam: String?, inherited: List<String> = emptyList()): List<SNode> {
        if (exam == null) return nodes
        return nodes.mapNotNull { n ->
            val tags = n.examTags.ifEmpty { inherited }
            val kids = keepExam(n.children, exam, tags)
            if (tags.isEmpty() || exam in tags || kids.isNotEmpty()) n.copy(children = kids) else null
        }
    }
}

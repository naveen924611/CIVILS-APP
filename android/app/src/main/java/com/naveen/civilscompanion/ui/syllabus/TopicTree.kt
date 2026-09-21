package com.naveen.civilscompanion.ui.syllabus

import com.naveen.civilscompanion.data.model.Topic

/** A topic with its sub-topics and how much of it is covered (share of the leaf topics that are studied or better). */
data class TNode(
    val topic: Topic,
    val children: List<TNode>,
    val leaves: Int,
    val covered: Int,
) {
    val coverage: Int get() = if (leaves == 0) 0 else Math.round(100.0 * covered / leaves).toInt()
}

/** A line of the map as it is drawn. */
data class TRow(val node: TNode, val depth: Int)

/** Pure logic of the syllabus map (spec 6.20): building the tree from topic rows, exam filter, coverage, search. */
object TopicTree {
    val STATUSES = listOf("not_started", "in_progress", "studied", "revised", "strong")

    fun statusLabel(status: String): String = when (status) {
        "in_progress" -> "In progress"
        "studied" -> "Studied"
        "revised" -> "Revised"
        "strong" -> "Strong"
        else -> "Not started"
    }

    fun isCovered(status: String): Boolean = status == "studied" || status == "revised" || status == "strong"

    /** "High", "Medium" or "Low" for a 0-10 importance. */
    fun importanceLabel(value: Double): String = when {
        value >= 7.0 -> "High"
        value >= 4.0 -> "Medium"
        else -> "Low"
    }

    /** Approved, not deleted topics as a tree. `exam` = "UPSC", "APPSC" or "SI" keeps that exam's topics (untagged ones follow their parent). */
    fun build(topics: List<Topic>, exam: String? = null): List<TNode> {
        val live = topics.filter { !it.deleted && it.approved }
        val ids = live.map { it.id }.toSet()
        val byParent = live.groupBy { if (it.parentId != null && it.parentId in ids) it.parentId else null }
        fun make(parent: String?, seen: Set<String>, inherited: List<String>): List<TNode> {
            val kids = byParent[parent].orEmpty()
                .filter { it.id !in seen }
                .sortedWith(compareBy<Topic> { it.position }.thenBy { it.title })
            val out = ArrayList<TNode>()
            for (t in kids) {
                val tags = t.examTags.ifEmpty { inherited }
                val children = make(t.id, seen + t.id, tags)
                if (exam != null && exam !in tags && children.isEmpty()) continue
                val leaves = if (children.isEmpty()) 1 else children.sumOf { it.leaves }
                val covered = if (children.isEmpty()) (if (isCovered(t.status)) 1 else 0) else children.sumOf { it.covered }
                out.add(TNode(t, children, leaves, covered))
            }
            return out
        }
        return make(null, emptySet(), emptyList())
    }

    /** Coverage of a whole list of nodes (for example one exam), 0 to 100. */
    fun coverage(nodes: List<TNode>): Int {
        val leaves = nodes.sumOf { it.leaves }
        return if (leaves == 0) 0 else Math.round(100.0 * nodes.sumOf { it.covered } / leaves).toInt()
    }

    /** The lines to draw. Children of a collapsed node are hidden. */
    fun rows(nodes: List<TNode>, collapsed: Set<String>): List<TRow> {
        val out = ArrayList<TRow>()
        fun walk(list: List<TNode>, depth: Int) {
            for (n in list) {
                out.add(TRow(n, depth))
                if (n.topic.id !in collapsed) walk(n.children, depth + 1)
            }
        }
        walk(nodes, 0)
        return out
    }

    /** Every node (any depth) whose title contains the words typed, ignoring case. Empty search = no result. */
    fun search(nodes: List<TNode>, query: String): List<TNode> {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<TNode>()
        fun walk(list: List<TNode>) {
            for (n in list) {
                val title = n.topic.title.lowercase()
                if (words.all { it in title }) out.add(n)
                walk(n.children)
            }
        }
        walk(nodes)
        return out
    }

    /** "Polity / Federalism": the titles above a topic, papers first, for the detail panel. */
    fun path(topics: List<Topic>, id: String): String {
        val byId = topics.associateBy { it.id }
        val names = ArrayList<String>()
        var cur = byId[id]
        var guard = 0
        while (cur != null && guard++ < 8) {
            names.add(cur.title)
            cur = cur.parentId?.let { byId[it] }
        }
        return names.reversed().joinToString(" / ")
    }

    /** The ids of the topics above one (parent first, up to the paper), so the tree can open itself down to a topic. */
    fun ancestors(topics: List<Topic>, id: String): List<String> {
        val byId = topics.associateBy { it.id }
        val out = ArrayList<String>()
        var cur = byId[id]?.parentId
        var guard = 0
        while (cur != null && guard++ < 8) {
            val parent = byId[cur] ?: break
            out.add(parent.id)
            cur = parent.parentId
        }
        return out
    }

    fun find(nodes: List<TNode>, id: String): TNode? {
        for (n in nodes) {
            if (n.topic.id == id) return n
            val inner = find(n.children, id)
            if (inner != null) return inner
        }
        return null
    }

    /** All topic ids at or below a node. */
    fun idsBelow(node: TNode): List<String> = listOf(node.topic.id) + node.children.flatMap { idsBelow(it) }
}

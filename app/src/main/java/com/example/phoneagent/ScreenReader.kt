package com.example.phoneagent

import android.view.accessibility.AccessibilityNodeInfo

/** Turns the accessibility tree into a compact numbered list the LLM can refer to. */
object ScreenReader {

    class Snapshot(val text: String, val nodes: List<AccessibilityNodeInfo>, val pkg: String)

    fun read(root: AccessibilityNodeInfo?): Snapshot {
        if (root == null) return Snapshot("(screen not readable)", emptyList(), "")
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val sb = StringBuilder()

        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || !n.isVisibleToUser) return
            val label = listOfNotNull(n.text?.toString(), n.contentDescription?.toString(), n.hintText?.toString())
                .filter { it.isNotBlank() }
                .joinToString(" | ")
            val interactive = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
            if (label.isNotEmpty() || interactive) {
                val flags = buildList {
                    if (n.isClickable) add("clickable")
                    if (n.isEditable) add("editable")
                    if (n.isScrollable) add("scrollable")
                    if (n.isChecked) add("checked")
                }.joinToString(",")
                sb.append("[${nodes.size}] ${n.className?.toString()?.substringAfterLast('.')} ")
                sb.append("\"${label.take(100)}\"")
                if (flags.isNotEmpty()) sb.append(" ($flags)")
                sb.append('\n')
                nodes.add(n)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return Snapshot(sb.toString().take(12000), nodes, root.packageName?.toString() ?: "")
    }
}

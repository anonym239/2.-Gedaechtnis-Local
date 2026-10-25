package de.gedaechtnis.local.capture

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Sammelt sichtbaren Text aus dem Accessibility-Baum – deutlich billiger als Screenshot + OCR.
 * Passwortfelder werden grundsätzlich übersprungen.
 */
object NodeTextCollector {

    data class Collected(val text: String, val nodeCount: Int)

    private const val MAX_NODES = 800
    private const val MAX_CHARS = 8_000

    fun collect(root: AccessibilityNodeInfo): Collected {
        val sb = StringBuilder()
        val seen = HashSet<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_NODES && sb.length < MAX_CHARS) {
            val node = stack.removeLast()
            visited++
            if (node.isPassword || !node.isVisibleToUser) continue

            val value = (node.text ?: node.contentDescription)?.toString()?.trim()
            if (!value.isNullOrEmpty() && value.length > 1 && seen.add(value)) {
                sb.append(value).append('\n')
            }
            // Kinder in umgekehrter Reihenfolge auf den Stack, damit die Lesereihenfolge erhalten bleibt.
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return Collected(sb.toString().take(MAX_CHARS), visited)
    }
}

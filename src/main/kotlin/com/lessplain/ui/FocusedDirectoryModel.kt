package com.lessplain.ui

/** 目录数据只表示真实文件位置，不包含 Agent 推断的架构职责。 */
internal data class DirectoryEntry(val path: String, val name: String, val directory: Boolean)
internal data class FocusedDirectoryNode(val entry: DirectoryEntry, val children: List<FocusedDirectoryNode> = emptyList())
internal data class DirectoryViewport(val x: Int = 0, val y: Int = 0)

/** 只展开当前路径，其他同级项保留在稳定排序的位置。 */
internal fun focusedDirectory(root: DirectoryEntry, current: String, children: (String) -> List<DirectoryEntry>): FocusedDirectoryNode {
    fun contains(path: String) = current == path || current.startsWith("${path.trimEnd('/')}/")
    fun build(entry: DirectoryEntry): FocusedDirectoryNode {
        if (!entry.directory || !contains(entry.path)) return FocusedDirectoryNode(entry)
        return FocusedDirectoryNode(entry, children(entry.path).sortedWith(
            compareByDescending<DirectoryEntry> { it.directory }.thenBy { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.name },
        ).map(::build))
    }
    return build(root)
}

/** 返回与前进只恢复浏览历史，不能据此推断用户是否理解某个目录。 */
internal class DirectoryHistory(initial: String) {
    private var entries = listOf(initial)
    private var index = 0
    private val positions = mutableMapOf<String, DirectoryViewport>()
    val current get() = entries[index]
    val canBack get() = index > 0
    val canForward get() = index < entries.lastIndex
    fun remember(position: DirectoryViewport) { positions[current] = position }
    fun viewport() = positions[current]
    fun visit(path: String) {
        if (path == current) return
        entries = entries.take(index + 1) + path
        index++
    }
    fun travel(delta: Int) { index = (index + delta).coerceIn(0, entries.lastIndex) }
}

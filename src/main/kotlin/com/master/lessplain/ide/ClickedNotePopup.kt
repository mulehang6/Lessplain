package com.master.lessplain.ide

/** 仅通过点击选择，并用代次防止旧弹层关闭其替代弹层。 */
internal class ClickedNotePopup {
    var target: Pair<String, String>? = null
        private set
    var generation = 0
        private set

    fun click(recordId: String, nodeId: String): Boolean {
        val requested = recordId to nodeId
        val open = target != requested
        generation++
        target = requested.takeIf { open }
        return open
    }

    fun close(expectedGeneration: Int = generation): Boolean {
        if (expectedGeneration != generation) return false
        target = null
        generation++
        return true
    }
}

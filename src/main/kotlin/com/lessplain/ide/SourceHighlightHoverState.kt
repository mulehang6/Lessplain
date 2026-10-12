package com.lessplain.ide

/** 仅悬停提示可以开始渐显；取消后需重新进入提示区域才能触发。 */
internal class SourceHighlightHoverState {
    private enum class State { IDLE, ACTIVE, SUPPRESSED, DISPOSED }

    private var state = State.IDLE
    val active: Boolean get() = state == State.ACTIVE

    /** 保持区域包含完整代码和标签下方短间隙，不按动画当前覆盖范围分类。 */
    fun move(inHint: Boolean, inBlock: Boolean): Boolean {
        state = when (state) {
            State.IDLE -> if (inHint) State.ACTIVE else State.IDLE
            State.ACTIVE -> if (inHint || inBlock) State.ACTIVE else State.IDLE
            State.SUPPRESSED -> if (inHint) State.SUPPRESSED else State.IDLE
            State.DISPOSED -> State.DISPOSED
        }
        return active
    }

    /** 任何鼠标按键，包括开始选择拖动，都会关闭渐显。 */
    fun press(inHint: Boolean, inBlock: Boolean): Boolean {
        if (state != State.DISPOSED && (inHint || inBlock)) {
            state = if (inHint) State.SUPPRESSED else State.IDLE
        }
        return active
    }

    /** 拖动可维持已有渐显，但文字选择期间不能触发新的渐显。 */
    fun drag(inHint: Boolean, inBlock: Boolean): Boolean {
        if (active) return move(inHint, inBlock)
        if (state == State.SUPPRESSED && !inHint) state = State.IDLE
        return false
    }

    /** 离开编辑器后允许下次进入提示时触发，不立即开始渐显。 */
    fun exit() {
        if (state != State.DISPOSED) state = State.IDLE
    }

    /** 延迟的队列事件不能重新激活已释放的提示。 */
    fun dispose() {
        state = State.DISPOSED
    }
}

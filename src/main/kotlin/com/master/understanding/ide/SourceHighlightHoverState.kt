package com.master.understanding.ide

/** Only a hint can start a reveal; a cancelled reveal must wait for a fresh hint entry. */
internal class SourceHighlightHoverState {
    private enum class State { IDLE, ACTIVE, SUPPRESSED, DISPOSED }

    private var state = State.IDLE
    val active: Boolean get() = state == State.ACTIVE

    /** Coordinates are classified against the full painted code area, not the animated sweep. */
    fun move(inHint: Boolean, inBlock: Boolean): Boolean {
        state = when (state) {
            State.IDLE -> if (inHint) State.ACTIVE else State.IDLE
            State.ACTIVE -> if (inHint || inBlock) State.ACTIVE else State.IDLE
            State.SUPPRESSED -> if (inHint) State.SUPPRESSED else State.IDLE
            State.DISPOSED -> State.DISPOSED
        }
        return active
    }

    /** Any mouse button, including the start of a selection drag, dismisses the reveal. */
    fun press(inHint: Boolean, inBlock: Boolean): Boolean {
        if (state != State.DISPOSED && (inHint || inBlock)) {
            state = if (inHint) State.SUPPRESSED else State.IDLE
        }
        return active
    }

    /** Dragging may keep an existing reveal, but cannot start one during a text selection. */
    fun drag(inHint: Boolean, inBlock: Boolean): Boolean {
        if (active) return move(inHint, inBlock)
        if (state == State.SUPPRESSED && !inHint) state = State.IDLE
        return false
    }

    /** Leaving the editor arms the next hint entry without starting a reveal. */
    fun exit() {
        if (state != State.DISPOSED) state = State.IDLE
    }

    /** Late queued events cannot revive a released marker. */
    fun dispose() {
        state = State.DISPOSED
    }
}

package com.master.understanding.ide

/** Transient canvas owners for source-inlay stepping; never serialized with graph preferences. */
internal class CanvasNavigationSessions {
    private val registrations = mutableListOf<Registration>()
    private var active: Registration? = null

    fun register(matches: (String, String) -> Boolean, busy: () -> Boolean,
        previous: () -> Unit, next: () -> Unit): Registration =
        Registration(matches, busy, previous, next).also { registrations += it }

    fun step(recordId: String, nodeId: String, backward: Boolean): Boolean {
        val matching = registrations.filter { it.matches(recordId, nodeId) }
        if (matching.isEmpty()) return false
        if (matching.any { it.busy() }) return true
        val owner = active?.takeIf { it in matching } ?: matching.last()
        if (backward) owner.previous() else owner.next()
        return true
    }

    inner class Registration internal constructor(
        internal val matches: (String, String) -> Boolean,
        internal val busy: () -> Boolean,
        internal val previous: () -> Unit,
        internal val next: () -> Unit,
    ) : AutoCloseable {
        fun activate() { if (this in registrations) active = this }
        override fun close() {
            registrations -= this
            if (active === this) active = null
        }
    }
}

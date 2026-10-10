package com.master.understanding.ide

/** Transient canvas owners for source-inlay stepping; never serialized with graph preferences. */
internal class CanvasNavigationSessions {
    private val registrations = mutableListOf<Registration>()
    private var active: Registration? = null
    private val waiting = mutableListOf<Waiting>()

    private data class Waiting(val recordId: String, val nodeId: String, val ready: () -> Unit)

    /** Compose can attach the visible canvas after the tool-window show callback has completed. */
    fun whenAvailable(recordId: String, nodeId: String, ready: () -> Unit): AutoCloseable {
        val request = Waiting(recordId, nodeId, ready)
        waiting += request
        notifyAvailable()
        return AutoCloseable { waiting -= request }
    }

    private fun notifyAvailable() {
        waiting.toList().forEach { request ->
            if (request in waiting && registrations.any { it.matches(request.recordId, request.nodeId) }) {
                waiting -= request
                request.ready()
            }
        }
    }

    fun register(
        matches: (String, String) -> Boolean, busy: () -> Boolean,
        previous: () -> Unit, next: () -> Unit,
        sourceStep: ((Boolean) -> Unit)? = null,
        cancel: () -> Unit = {},
    ): Registration = Registration(matches, busy, previous, next, sourceStep, cancel).also {
        registrations += it
        notifyAvailable()
    }

    fun step(
        recordId: String, nodeId: String, backward: Boolean,
    ): Boolean {
        val matching = registrations.filter { it.matches(recordId, nodeId) }
        if (matching.isEmpty()) return false
        val busy = matching.filter { it.busy() }
        val owners = busy.ifEmpty { matching }
        val owner = active?.takeIf { it in owners } ?: owners.last()
        if (owner.sourceStep != null) {
            owner.sourceStep.invoke(backward)
            return true
        }
        if (busy.isNotEmpty()) return true
        if (backward) owner.previous() else owner.next()
        return true
    }

    fun cancelChoices(recordId: String, nodeId: String) {
        registrations.filter { it.matches(recordId, nodeId) && it.busy() }.forEach { it.cancel() }
    }

    inner class Registration internal constructor(
        internal val matches: (String, String) -> Boolean,
        internal val busy: () -> Boolean,
        internal val previous: () -> Unit,
        internal val next: () -> Unit,
        internal val sourceStep: ((Boolean) -> Unit)?,
        internal val cancel: () -> Unit,
    ) : AutoCloseable {
        fun activate() {
            if (this in registrations) {
                active = this
                notifyAvailable()
            }
        }

        override fun close() {
            registrations -= this
            if (active === this) active = null
        }
    }
}

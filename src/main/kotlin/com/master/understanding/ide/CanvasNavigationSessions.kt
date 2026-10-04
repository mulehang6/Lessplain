package com.master.understanding.ide

import java.awt.Component
import java.awt.Point

/** Transient canvas owners for source-inlay stepping; never serialized with graph preferences. */
internal class CanvasNavigationSessions {
    private val registrations = mutableListOf<Registration>()
    private var active: Registration? = null

    fun register(
        matches: (String, String) -> Boolean, busy: () -> Boolean,
        previous: () -> Unit, next: () -> Unit,
        sourceStep: ((Boolean, Component, Point) -> Unit)? = null,
        cancel: () -> Unit = {},
    ): Registration = Registration(matches, busy, previous, next, sourceStep, cancel).also { registrations += it }

    fun step(
        recordId: String, nodeId: String, backward: Boolean,
        component: Component? = null, point: Point = Point(),
    ): Boolean {
        val matching = registrations.filter { it.matches(recordId, nodeId) }
        if (matching.isEmpty()) return false
        val busy = matching.filter { it.busy() }
        val owners = busy.ifEmpty { matching }
        val owner = active?.takeIf { it in owners } ?: owners.last()
        if (component != null && owner.sourceStep != null) {
            owner.sourceStep.invoke(backward, component, Point(point))
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
        internal val sourceStep: ((Boolean, Component, Point) -> Unit)?,
        internal val cancel: () -> Unit,
    ) : AutoCloseable {
        fun activate() {
            if (this in registrations) active = this
        }

        override fun close() {
            registrations -= this
            if (active === this) active = null
        }
    }
}

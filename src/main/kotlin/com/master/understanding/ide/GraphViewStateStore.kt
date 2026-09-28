package com.master.understanding.ide

import com.google.gson.Gson
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.master.understanding.model.BrowseState
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Settings belong to a particular root graph or a particular node's question canvas. */
internal data class GraphLayerKey(val recordId: String, val questionNodeId: String? = null) {
    val canvasId: String get() = questionNodeId?.let { "questions:$recordId:$it" } ?: recordId

    internal fun propertyName(): String {
        val identity = Gson().toJson(listOf(recordId, questionNodeId))
        return "master.understanding.graph." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(identity.toByteArray(StandardCharsets.UTF_8))
    }
}

internal data class GraphViewState(
    val navigationPinned: Boolean = false,
    val autoFollowNode: Boolean = true,
    val flowEnabled: Boolean = true,
    val browse: BrowseState? = null,
    val nodePositions: Map<String, GraphNodePosition> = emptyMap(),
)

internal data class GraphNodePosition(val x: Double, val y: Double)

/** Project-local persistence, shared by the tool window and editor tab. */
internal class GraphViewStateStore(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    constructor(project: Project) : this(
        read = { name -> PropertiesComponent.getInstance(project).getValue(name) },
        write = { name, value -> PropertiesComponent.getInstance(project).setValue(name, value) },
    )

    private val gson = Gson()
    private val listeners = ConcurrentHashMap<GraphLayerKey, CopyOnWriteArrayList<(GraphViewState) -> Unit>>()

    fun get(key: GraphLayerKey): GraphViewState = read(key.propertyName())
        ?.let { json -> runCatching { gson.fromJson(json, GraphViewState::class.java) }.getOrNull() }
        ?: GraphViewState()

    @Synchronized
    fun update(key: GraphLayerKey, transform: (GraphViewState) -> GraphViewState) {
        val state = transform(get(key))
        write(key.propertyName(), gson.toJson(state))
        listeners[key]?.forEach { it(state) }
    }

    fun listen(key: GraphLayerKey, callback: (GraphViewState) -> Unit): AutoCloseable {
        listeners.computeIfAbsent(key) { CopyOnWriteArrayList() }.add(callback)
        return AutoCloseable { listeners[key]?.remove(callback) }
    }
}

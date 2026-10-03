package com.master.understanding.ide

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.master.understanding.model.BrowseState
import com.master.understanding.storage.decodeBrowseState
import com.master.understanding.diagram.LayoutDirection
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
    val layoutPreference: GraphLayoutPreference = GraphLayoutPreference.AUTO,
    val verticalViewport: BrowseState? = null,
    val horizontalViewport: BrowseState? = null,
    val verticalPositions: Map<String, GraphNodePosition> = emptyMap(),
    val horizontalPositions: Map<String, GraphNodePosition> = emptyMap(),
    val stateVersion: Int = 2,
) {
    fun viewport(direction: LayoutDirection): BrowseState? =
        if (direction == LayoutDirection.VERTICAL) verticalViewport else horizontalViewport

    fun positions(direction: LayoutDirection): Map<String, GraphNodePosition> =
        if (direction == LayoutDirection.VERTICAL) verticalPositions else horizontalPositions

    fun withViewport(direction: LayoutDirection, value: BrowseState): GraphViewState =
        if (direction == LayoutDirection.VERTICAL) copy(verticalViewport = value) else copy(horizontalViewport = value)

    fun withPositions(direction: LayoutDirection, value: Map<String, GraphNodePosition>): GraphViewState =
        if (direction == LayoutDirection.VERTICAL) copy(verticalPositions = value) else copy(horizontalPositions = value)
}

internal enum class GraphLayoutPreference { AUTO, VERTICAL, HORIZONTAL }

internal data class GraphNodePosition(val x: Double, val y: Double)

/** Project-local persistence, shared by the tool window and editor tab. */
internal class GraphViewStateStore(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    val navigationSessions = CanvasNavigationSessions()

    constructor(project: Project) : this(
        read = { name -> PropertiesComponent.getInstance(project).getValue(name) },
        write = { name, value -> PropertiesComponent.getInstance(project).setValue(name, value) },
    )

    private val gson = Gson()
    private val listeners = ConcurrentHashMap<GraphLayerKey, CopyOnWriteArrayList<(GraphViewState) -> Unit>>()

    fun get(key: GraphLayerKey): GraphViewState = read(key.propertyName())?.let { json ->
        runCatching {
            val source = JsonParser.parseString(json).asJsonObject
            val browse = decodeBrowseState(source.remove("browse"), key.canvasId, gson)
            val vertical = decodeBrowseState(source.remove("verticalViewport"), key.canvasId, gson)
            val horizontal = decodeBrowseState(source.remove("horizontalViewport"), key.canvasId, gson)
            val state = gson.fromJson(source, GraphViewState::class.java)
            val legacy = !source.has("stateVersion")
            GraphViewState(
                navigationPinned = state.navigationPinned,
                autoFollowNode = source.get("autoFollowNode")?.asBoolean ?: true,
                flowEnabled = source.get("flowEnabled")?.asBoolean ?: true,
                browse = browse,
                nodePositions = orDefault(state.nodePositions, emptyMap()),
                layoutPreference = if (legacy) GraphLayoutPreference.AUTO
                    else orDefault(state.layoutPreference, GraphLayoutPreference.AUTO),
                verticalViewport = if (legacy) browse else vertical,
                horizontalViewport = if (legacy) null else horizontal,
                verticalPositions = if (legacy) orDefault(state.nodePositions, emptyMap())
                    else orDefault(state.verticalPositions, emptyMap()),
                horizontalPositions = if (legacy) emptyMap()
                    else orDefault(state.horizontalPositions, emptyMap()),
            )
        }.getOrNull()
    } ?: GraphViewState()

    private fun <T : Any> orDefault(value: T?, fallback: T): T = value ?: fallback

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

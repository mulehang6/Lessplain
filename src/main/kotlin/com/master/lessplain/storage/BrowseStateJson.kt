package com.master.lessplain.storage

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.master.lessplain.model.BrowseState
import com.master.lessplain.model.RouteNavigationState

/** Reads optional route fields independently so a corrupt route cannot discard a valid selected node. */
internal fun decodeBrowseState(source: JsonElement?, recordId: String, gson: Gson): BrowseState? {
    if (source == null || source.isJsonNull) return null
    val json = runCatching { source.asJsonObject.deepCopy() }.getOrNull() ?: return null
    val navigationJson = json.remove("navigationState")
    val pathJson = json.remove("staticPath")
    val decoded = runCatching { gson.fromJson(json, BrowseState::class.java) }.getOrNull() ?: return null
    val path = runCatching {
        if (pathJson == null || pathJson.isJsonNull) emptyList() else pathJson.asJsonArray.map { it.asString }
    }.getOrDefault(emptyList())
    val navigation = if (navigationJson == null || navigationJson.isJsonNull) null else {
        runCatching { gson.fromJson(navigationJson, RouteNavigationState::class.java) }
            .getOrNull()
            ?.let { RouteNavigationState(decodedOr(it.prefixNodeId, ""), decodedOr(it.selectedEdgeIds, emptyList())) }
            ?: RouteNavigationState("")
    }
    return decoded.copy(
        recordId = recordId,
        staticPath = path,
        navigationState = navigation,
        zoom = if (json.has("zoom") && decoded.zoom.isFinite() && decoded.zoom > 0.0) decoded.zoom else 1.0,
    )
}

private fun <T : Any> decodedOr(value: T?, fallback: T): T = value ?: fallback

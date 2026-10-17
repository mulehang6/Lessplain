package com.lessplain.experiment.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.lessplain.experiment.model.EditorSourceBinding
import com.lessplain.experiment.model.SourceStepDirection
import com.lessplain.experiment.model.ResolvedSource
import com.lessplain.ide.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class SourceStatus(val location: String, val evidence: String)

/** 当前定位只维护一套原生 inline 与高亮；清除后不留下访问历史。 */
internal class ViewSourceNavigation(private val project: Project, private val service: ViewService) : Disposable {
    private val generation = AtomicLong()
    private val marker = SourceInsightMarker(project, this)

    suspend fun open(
        source: ResolvedSource,
        label: String,
        binding: EditorSourceBinding? = null,
    ): SourceStatus {
        val request = generation.incrementAndGet()
        val resolved = withContext(Dispatchers.IO) {
            commitSourceDocuments(project) { resolveSourceInReadAction(project, source.anchor()) }
        }
        return withContext(Dispatchers.EDT) {
            if (request != generation.get()) return@withContext SourceStatus("UNRESOLVED", "UNKNOWN")
            marker.clear()
            if (!resolved.isCurrent()) return@withContext SourceStatus("UNRESOLVED", "UNKNOWN")

            val offset = resolved.located.offset
            if (offset != null && resolved.file != null) {
                OpenFileDescriptor(project, resolved.file, offset).navigate(true)
            }
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor != null && editor.document === resolved.document && offset != null &&
                resolved.located.evidence != EvidenceStatus.CHANGED
            ) {
                val anchor = source.anchor()
                marker.show(
                    anchor = anchor,
                    resolution = resolved,
                    label = label,
                    navigation = SourceInlayNavigation(
                        binding?.canPrevious == true,
                        binding?.canNext == true,
                        previous = { binding?.let { service.requestSourceStep(it, SourceStepDirection.PREVIOUS) } },
                        next = { binding?.let { service.requestSourceStep(it, SourceStepDirection.NEXT) } },
                        evidenceChanged = ::clear,
                    ),
                    binding = binding,
                )
            }
            SourceStatus(resolved.located.location.name, resolved.located.evidence.name)
        }
    }

    fun bindingFor(editor: Editor): EditorSourceBinding? = marker.bindingFor(editor)

    fun updateNavigationState(
        hostId: String,
        viewRef: String,
        revision: Int,
        elementId: String,
        nodeId: String,
        canPrevious: Boolean,
        canNext: Boolean,
    ) = marker.updateNavigationState(hostId, viewRef, revision, elementId, nodeId, canPrevious, canNext)

    fun bindingForNavigation(
        hostId: String,
        viewRef: String,
        revision: Int,
        elementId: String,
        nodeId: String,
    ) = marker.bindingForNavigation(hostId, viewRef, revision, elementId, nodeId)

    fun invalidateHost(hostId: String) = marker.invalidateHost(hostId)

    fun invalidateRevisions(revisions: Map<String, Int>) = marker.invalidateRevisions(revisions)

    fun invalidateBinding(binding: EditorSourceBinding) = marker.invalidateBinding(binding)

    override fun dispose() {
        marker.dispose()
    }

    fun clear() {
        val request = generation.incrementAndGet()
        ApplicationManager.getApplication().invokeLater {
            if (request == generation.get()) marker.clear()
        }
    }
}

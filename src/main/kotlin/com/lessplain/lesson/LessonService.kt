package com.lessplain.lesson

import com.google.gson.Gson
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.lessplain.core.BackgroundTasks
import com.lessplain.ide.UnderstandingService
import com.lessplain.model.SourceAnchor
import com.lessplain.storage.sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/** 教材与旧图服务分别存储；只在外置项目目录写学习材料，不初始化业务图数据库。 */
class LessonService(private val project: Project, scope: CoroutineScope) : Disposable {
    val workspace = LessonWorkspace(PathManager.getConfigDir().resolve("lessplain/projects")
        .resolve(sha256(project.locationHash)))
    val engine = LessonEngine(workspace, readExample = ::exampleText)
    private val tasks = BackgroundTasks(scope) { SwingUtilities.invokeLater(it) }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val files = mutableMapOf<String, LessonVirtualFile>()
    private val sourceMarks = LessonSourceHighlights(project, this)
    private val destinations = mutableMapOf<String, LessonDestination>()
    @Volatile private var disposed = false
    private val stagedNotes = ConcurrentHashMap<String, String>()
    private val stagedBrowse = ConcurrentHashMap<String, LessonBrowse>()
    private val initialized = CompletableFuture<Unit>()
    @Volatile var loadError: String? = null
        private set

    init {
        tasks.dispatch({ workspace.load() }) { result ->
            result.onSuccess { initialized.complete(Unit); changed() }
                .onFailure { loadError = it.message; initialized.completeExceptionally(it); changed() }
        }
    }

    val snapshot: LessonState get() = workspace.snapshot

    internal fun <T> background(action: () -> T, failed: () -> Unit = {}, completed: (T) -> Unit = {}) {
        tasks.dispatch({ initialized.join(); action() }) { result ->
            if (!disposed) lessonUiRead { result.onSuccess(completed).onFailure { failed(); showFailure(it) } }
        }
    }

    fun startLearning(anchor: SourceAnchor, focus: LessonSourceFocus? = null) {
        val graph = project.getServiceIfCreated(UnderstandingService::class.java)
        val origin = LessonOrigin(anchor, graph?.activeRecordId, graph?.activeNodeId, focus)
        background({
            engine.start(origin, anchor.text.replace(Regex("\\s+"), " ").take(80),
                "讲解所选代码中的实体或概念、作用与使用方式，提供易读的例子和必要图示。默认不展开语法；基础知识由用户按需追问。")
        }) { context ->
            changed()
            copyQuestion(context.token)
            openLesson(context.lessonId)
        }
    }

    fun openLesson(id: String) = openLesson(id, null)

    internal fun openLesson(id: String, destination: LessonDestination?) {
        if (disposed || project.isDisposed) return
        background({ workspace.lesson(id) }) { record ->
            val file = files.getOrPut(id) { LessonVirtualFile(record.id, record.title) }
            if (destination != null) destinations[id] = destination
            FileEditorManager.getInstance(project).openFile(file, true)
            changed()
        }
    }

    internal fun takeDestination(id: String): LessonDestination? = destinations.remove(id)

    fun copyQuestion(token: String) {
        val context = engine.context(token)
        val text = "${context.question}\n\n" +
            "请先调用 Lessplain 的 get_understanding_context(contextToken=\"$token\") 读取冻结的教材来源，" +
            "再按返回的 lessonProtocol 调用 present_understanding 保存结果。请把解释呈现在教材里。\n" +
            Gson().toJson(mapOf("schemaVersion" to "1.4", "contextToken" to token, "lessonId" to context.lessonId))
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    fun annotate(id: String, target: LessonTarget, question: String, note: String, failed: () -> Unit = {}, completed: (LessonAnnotation) -> Unit) {
        background({ engine.addAnnotation(id, target, question, note) }, failed) { annotation ->
            if (annotation.contextToken != null) copyQuestion(annotation.contextToken)
            completed(annotation)
            changed()
        }
    }

    fun stageNote(annotationId: String, text: String) { stagedNotes[annotationId] = text }

    fun saveNote(annotationId: String, text: String) {
        background({ engine.updateNote(annotationId, text) { !disposed && stagedNotes[annotationId] == text } }) { changed() }
    }

    fun saveBrowse(id: String, browse: LessonBrowse) {
        stagedBrowse[id] = browse
        background({ engine.saveBrowse(id, browse) { !disposed && stagedBrowse[id] == browse } })
    }

    /** MCP 的冻结 token 和结果类型互相校验；不按当前选择推断归属。 */
    suspend fun present(
        requestId: String, token: String, title: String, blocks: List<LessonBlock>, examples: List<LessonExample>, reply: Boolean,
        question: String, sourceOrigin: LessonOrigin? = null,
    ): LessonRecord = withContext(Dispatchers.IO) {
        initialized.join()
        // 已保存请求先由 engine 核对指纹；源码随后变动不能破坏相同请求的幂等重试。
        if (snapshot.receipts.none { it.requestId == requestId }) validateBindingSources(blocks)
        if (sourceOrigin != null) validateLesson(LessonRecord("validation", 1, title, sourceOrigin, blocks, examples, 0))
        val frozenToken = if (sourceOrigin != null) {
            engine.start(sourceOrigin, title, question, "lesson:source:${sha256("$requestId|$token")}").token
        } else token
        val record = engine.present(requestId, frozenToken, title, blocks, examples, reply, question)
        SwingUtilities.invokeLater {
            if (disposed) return@invokeLater
            lessonUiRead {
                changed()
                files[record.id]?.let { FileEditorManager.getInstance(project).updateFilePresentation(it) }
                val selected = FileEditorManager.getInstance(project).selectedFiles.filterIsInstance<LessonVirtualFile>()
                if (selected.none { it.lessonId == record.id }) {
                    NotificationGroupManager.getInstance().getNotificationGroup("code.comprehension.results")
                        .createNotification("教程已更新", record.title, NotificationType.INFORMATION)
                        .addAction(NotificationAction.createSimple("打开教程") { openLesson(record.id) }).notify(project)
                }
            }
        }
        record
    }

    suspend fun contextPayload(token: String = "", lessonId: String = ""): Map<String, Any?> = withContext(Dispatchers.IO) {
        initialized.join()
        val context = token.takeIf(String::isNotBlank)?.let(engine::context)
        require(context == null || lessonId.isBlank() || context.lessonId == lessonId) { "教材标识与固定来源不一致" }
        val record = workspace.lesson(context?.lessonId ?: lessonId)
        val annotations = snapshot.annotations.filter { it.lessonId == record.id }
        mapOf(
            "schemaVersion" to "1.4", "presentation" to "lesson", "lessonProtocol" to lessonProtocol,
            "context" to context, "lesson" to record, "annotations" to annotations,
            "targetCurrent" to (context?.target?.let { engine.targetIsCurrent(record, it) } ?: true),
            "pendingQuestions" to snapshot.contexts.filter { frozen -> frozen.lessonId == record.id &&
                (frozen.annotationId?.let { id -> annotations.any { it.id == id && it.status == "pending" } }
                    ?: record.blocks.isEmpty()) },
            "exampleFiles" to record.examples.map { example ->
                val code = exampleText(record, example)
                mapOf("id" to example.id, "fileName" to example.fileName, "code" to code, "version" to sha256(code))
            },
        )
    }

    internal fun exampleText(record: LessonRecord, example: LessonExample): String {
        val path = workspace.examplePath(record, example)
        return ApplicationManager.getApplication().runReadAction<String> {
            val file = LocalFileSystem.getInstance().findFileByNioFile(path)
            file?.let { FileDocumentManager.getInstance().getCachedDocument(it)?.text }
                ?: workspace.exampleText(record, example)
        }
    }

    internal fun sourceStatus(record: LessonRecord): String? = ApplicationManager.getApplication().runReadAction<String?> {
        val anchor = record.origin.anchor
        val file = sourceFile(anchor) ?: return@runReadAction "来源文件已不存在"
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return@runReadAction "来源文件无法读取"
        val located = record.origin.focus?.let { (sourceMarks.currentRange(document, it) ?: resolveLessonFocus(document.text, it))?.first }
            ?: if (record.origin.focus == null) uniqueLessonSourceOffset(document.text, anchor.text) else null
        if (located == null) "来源片段已变化或不再唯一，笔记仍保留"
        else if (anchor.version.isNotBlank() && sha256(document.text) != anchor.version) "源码文件已变化，仍可定位原片段；请核对解释是否适用"
        else null
    }

    fun navigateSource(anchor: SourceAnchor, focus: LessonSourceFocus? = null) {
        val (file, offset) = ApplicationManager.getApplication().runReadAction<Pair<com.intellij.openapi.vfs.VirtualFile?, Int?>> {
            val file = sourceFile(anchor)
            file to file?.let { FileDocumentManager.getInstance().getDocument(it) }
                ?.let { document -> focus?.let { (sourceMarks.currentRange(document, it) ?: resolveLessonFocus(document.text, it))?.first }
                    ?: if (focus == null) uniqueLessonSourceOffset(document.text, anchor.text) else null }
        }
        if (file == null || offset == null) {
            Messages.showInfoMessage(project, "来源片段已变化或不再唯一；保留的笔记不会挂到其他代码上。", "无法定位来源")
        } else lessonUiRead { OpenFileDescriptor(project, file, offset).navigate(true) }
    }

    internal fun sourceFile(anchor: SourceAnchor) = project.basePath?.let { base ->
        val root = Path.of(base).toAbsolutePath().normalize()
        val path = root.resolve(anchor.path).normalize()
        if (!path.startsWith(root)) null else LocalFileSystem.getInstance().findFileByNioFile(path)
    }

    /** 在写教材之前核实真实文档，不能只接受一个看似合法的 JSON 范围。 */
    private fun validateBindingSources(blocks: List<LessonBlock>) = ApplicationManager.getApplication().runReadAction {
        blocks.flatMap { it.sourceBindings.orEmpty() }.forEach { binding ->
            validateLessonFocus(binding.focus)
            val file = sourceFile(binding.focus.anchor) ?: error("概念来源文件不存在：${binding.focus.anchor.path}")
            val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: error("概念来源无法读取")
            require(resolveLessonFocus(text, binding.focus) != null) { "概念 ${binding.label} 的上下文已变化或不唯一" }
            require(binding.focus.anchor.version.isBlank() || binding.focus.anchor.version == sha256(text)) { "概念 ${binding.label} 的源码版本已变化" }
        }
    }

    fun onChanged(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun changed() {
        if (disposed || project.isDisposed) return
        listeners.forEach { it() }
        sourceMarks.refresh()
    }

    private fun showFailure(failure: Throwable) {
        val message = failure.cause?.message ?: failure.message ?: "未知错误"
        NotificationGroupManager.getInstance().getNotificationGroup("code.comprehension.results")
            .createNotification("教材操作失败", message, NotificationType.ERROR).notify(project)
    }

    override fun dispose() {
        disposed = true
        listeners.clear()
        sourceMarks.close()
        tasks.close()
        if (initialized.isDone && !initialized.isCompletedExceptionally && (stagedNotes.isNotEmpty() || stagedBrowse.isNotEmpty())) {
            // 只串行化文件写入，不等待可能正在读 IDE 文档的 engine，避免关闭项目时互相等待。
            workspace.update { state -> state.copy(
                annotations = state.annotations.map { annotation ->
                    stagedNotes[annotation.id]?.let { annotation.copy(note = it) } ?: annotation
                },
                browse = state.browse + stagedBrowse,
            ) }
        }
    }
}

internal val lessonProtocol: Map<String, Any> = mapOf(
    "version" to 2,
    "submit" to "present_understanding(schemaVersion=1.4, requestId=唯一ID, question=问题原文, placement=root, presentation=lesson或annotationReply, contextToken=冻结token, lesson=教材JSON字符串)。graph 可省略。",
    "payload" to "{title,blocks:[{id,kind,text?,richText?,level?,language?,exampleId?,columns?,rows?,graph?,source?,sourceBindings?}],examples:[{id,fileName,code,dependencies:[]}]}",
    "richText" to "正文可用 richText:[{text,style:plain/strong/emphasis/code/link,href?}] 替代 text。按顺序拼接得到规范文本；样式不计字符。链接只接受 http/https。",
    "sourceBindings" to "教材块可含 sourceBindings:[{id,label,focus:{anchor:{path,text,symbol?,version?},start,end},start?,end?}]。focus.anchor.text 是项目文件中唯一的定位上下文，focus.start/end 指向上下文内的精确概念。外层 start/end 可限定正文规范文本的对应范围。所有偏移使用 UTF-16，end 不包含。先核对源码，不凭同名词绑定。",
    "blocks" to "kind 为 text/code/table/diagram。text 使用正常讲解文字，level=1..3 表示标题。table 含 columns 与 rows。diagram.graph 沿用 nodes/edges/clusters 模型，不提交坐标。",
    "examples" to "示例使用独立、自包含的 .main.kts，dependencies 是与项目一致版本的 Maven 坐标。code 不重复依赖头；代码块用 exampleId 引用实际示例，不重复 text。伪代码和源码引用只用 code.text，不标可运行。",
    "source" to "源码引用代码块可用 source={path:项目相对路径,text:唯一源码片段,symbol?:符号,version?:完整文件哈希} 提供查看源码入口。教学简化模型明确标注，并通过源码引用块对照实际定义。",
    "reply" to "context.annotationId 非空时使用 presentation=annotationReply；回复只添加 blocks 和新的 examples，不能覆盖已有教材或个人笔记。",
    "teaching" to "先讲实体/概念的作用与用法。文字、例子、图按教学需要组织，不强制交互、测验或运行；语法基础按用户追问补充。",
)

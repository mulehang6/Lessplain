package com.lessplain.tutorial

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class TutorialStoreTest {
    @TempDir lateinit var path: Path
    private fun TutorialStore.context() = saveContext(TutorialContext("ctx_test", "学习 Flow", emptyList()))

    @Test fun `creation validates the complete bundle and retry returns the same material`() {
        TutorialStore(path).use { store ->
            store.context()
            assertFailsWith<TutorialProblem> { store.create("ctx_test", "Flow", "![missing](lessplain://diagram/missing)", TutorialResourceState()) }
            assertTrue(store.list().isEmpty())
            val first = store.create("ctx_test", "Flow", "正文", TutorialResourceState())
            assertEquals(first, store.create("ctx_test", "Flow", "正文", TutorialResourceState()))
        }
        TutorialStore(path).use { store -> assertEquals("正文", store.list().single().markdown) }
    }

    @Test fun `resource replacement uses separate revisions and retains positions and annotations`() {
        TutorialStore(path).use { store ->
            store.context()
            val record = store.create("ctx_test", "Flow", "正文", TutorialResourceState())
            val graph = TutorialGraph(listOf(TutorialNode("flow", "Flow")), emptyList())
            val first = store.saveDiagram(record.id, TutorialDiagram("delivery", graph), 0)
            store.savePositions(record.id, "delivery", mapOf("flow" to TutorialPosition(30, 45)))
            val second = store.saveDiagram(record.id, first.copy(graph = graph.copy(nodes = listOf(TutorialNode("flow", "新标题")))), 1)
            assertEquals(2, second.revision)
            assertEquals(TutorialPosition(30, 45), store.resource(record.id).positions["delivery"]?.get("flow"))
            val error = assertFailsWith<TutorialProblem> { store.saveDiagram(record.id, first, 1) }
            assertEquals("REVISION_CONFLICT", error.code)
            assertEquals(2, error.currentRevision)
            assertEquals(1, store.record(record.id).revision)
        }
    }

    @Test fun `UTF16 annotations follow insertions move with unique text and survive deletion`() {
        TutorialStore(path).use { store ->
            store.context()
            val initial = "前😀 中文 Flow 尾"
            val record = store.create("ctx_test", "Flow", initial, TutorialResourceState())
            val start = initial.indexOf("中文")
            store.createAnnotation(TutorialAnnotation("note_test", record.id, "note", start, start + 7, "中文 Flow", "保留我的笔记"))
            store.change(record.id, 1, "插入$initial", 0, 0, 2)
            assertEquals(start + 2, store.annotation("note_test").start)
            val afterInsert = store.record(record.id)
            val moved = afterInsert.markdown.replace("中文 Flow", "") + "中文 Flow"
            store.change(record.id, afterInsert.revision, moved, start + 2, 7, 0)
            assertEquals(moved.lastIndexOf("中文 Flow"), store.annotation("note_test").start)
            val beforeDelete = store.record(record.id)
            store.change(record.id, beforeDelete.revision, moved.replace("中文 Flow", ""), moved.lastIndexOf("中文 Flow"), 7, 0)
            assertTrue(store.annotation("note_test").orphaned)
            assertEquals("保留我的笔记", store.annotation("note_test").text)
            assertEquals("中文 Flow", store.annotation("note_test").quote)
        }
        TutorialStore(path).use { assertTrue(it.annotations().single().orphaned) }
    }

    @Test fun `partial edits retain the range and original question`() {
        val old = TutorialAnnotation("q", "t", "question", 3, 9, "abcdef", "问题")
        val next = relocateAnnotation(old, 5, 1, 3)
        assertEquals(11, next.end)
        assertFalse(next.orphaned)
        assertEquals("问题", next.text)
    }

    @Test fun `deleting one duplicate never rebinds its note to the remaining same named text`() {
        TutorialStore(path).use { store ->
            store.context()
            val record = store.create("ctx_test", "重复", "Flow 和 Flow", TutorialResourceState())
            store.createAnnotation(TutorialAnnotation("note_duplicate", record.id, "note", 0, 4, "Flow", "只属于第一处"))
            store.change(record.id, 1, " 和 Flow", 0, 4, 0)
            assertTrue(store.annotation("note_duplicate").orphaned)
            assertFalse(store.annotation("note_duplicate").relocatable)
            assertEquals("只属于第一处", store.annotation("note_duplicate").text)
        }
    }

    @Test fun `a unique quote can be cut and pasted while its original snapshot remains`() {
        TutorialStore(path).use { store ->
            store.context()
            val record = store.create("ctx_test", "移动", "Flow 的笔记", TutorialResourceState())
            store.createAnnotation(TutorialAnnotation("note_move", record.id, "note", 0, 4, "Flow", "保留"))
            store.change(record.id, 1, " 的笔记", 0, 4, 0)
            assertTrue(store.annotation("note_move").orphaned)
            store.change(record.id, 2, " 的笔记Flow", 4, 0, 4)
            assertFalse(store.annotation("note_move").orphaned)
            assertEquals(4, store.annotation("note_move").start)
            assertEquals("Flow", store.annotation("note_move").quote)
        }
    }

    @Test fun `unique replacements are sequential but validation failure leaves original intact`() {
        assertEquals("新 结尾", editTutorialText("旧 尾", listOf(TutorialEdit("旧", "新"), TutorialEdit("尾", "结尾"))))
        assertEquals("AMBIGUOUS_TEXT", assertFailsWith<TutorialProblem> { editTutorialText("中文 中文", listOf(TutorialEdit("中文", "新"))) }.code)
        assertEquals("TEXT_NOT_FOUND", assertFailsWith<TutorialProblem> { editTutorialText("原文", listOf(TutorialEdit("不存在", ""))) }.code)
    }

    @Test fun `explicit deletion removes the question branch and replay data without deleting independent materials`() {
        lateinit var materialId: String
        TutorialStore(path).use { store ->
            store.context()
            val record = store.create("ctx_test", "Flow", "中文 Flow", TutorialResourceState())
            materialId = record.id
            store.saveContext(TutorialContext("ctx_question", "为什么", emptyList()))
            val question = store.createAnnotation(TutorialAnnotation("q_root", record.id, "question", 0, 2, "中文", "为什么", "answered", "答复", "ctx_question"))
            store.createAnnotation(question.copy(id = "q_child", parentId = question.id, text = "继续问"))
            store.createAnnotation(question.copy(id = "q_grandchild", parentId = "q_child"))
            store.createAnnotation(TutorialAnnotation("note_keep", record.id, "note", 0, 2, "中文", "保留笔记"))
            store.createAnnotation(question.copy(id = "q_other", contextId = null))
            val reply = """{"ok":true,"data":{"id":"q_root","text":"为什么","answer":"答复"}}"""
            store.saveToolReceipt("answer", reply)
            assertNotNull(store.toolReceipt("answer"))
            assertEquals(setOf("q_root", "q_child", "q_grandchild"), store.deleteAnnotation(question.id).map { it.id }.toSet())
            assertEquals(setOf("note_keep", "q_other"), store.annotations(record.id).map { it.id }.toSet())
            assertNull(store.toolReceipt("answer"))
            assertEquals("NOT_FOUND", assertFailsWith<TutorialProblem> { store.context("ctx_question") }.code)
            assertEquals("NOT_FOUND", assertFailsWith<TutorialProblem> { store.updateAnnotation(question.id) { it.copy(answer = "迟到的回答") } }.code)
            assertEquals("NOT_FOUND", assertFailsWith<TutorialProblem> { store.createAnnotation(question.copy(id = "q_late", parentId = question.id)) }.code)
            store.saveToolReceipt("late_answer", reply)
            assertNull(store.toolReceipt("late_answer"))
            assertEquals(record, store.record(record.id))
        }
        TutorialStore(path).use { store ->
            assertEquals(setOf("note_keep", "q_other"), store.annotations(materialId).map { it.id }.toSet())
            assertNull(store.toolReceipt("answer"))
        }
    }

    @Test fun `old empty drafts are filtered but retained on disk and late saves never recreate deletion`() {
        lateinit var material: String
        TutorialStore(path).use { store ->
            store.context()
            material = store.create("ctx_test", "空草稿", "正文", TutorialResourceState()).id
            val old = TutorialAnnotation("note_empty", material, "note", 0, 2, "正文", "  ")
            store.createAnnotation(old)
            assertTrue(old.isEmptyDraft)
            assertTrue(annotationInkRuns(store.annotations(material)).isEmpty())
            assertTrue(annotationsAt(store.annotations(material), 0).isEmpty())
            val saved = assertNotNull(store.saveAnnotationDraft(old.copy(id = "note_new"), "新笔记", true))
            assertFalse(saved.isEmptyDraft)
            store.deleteAnnotation(saved.id)
            assertEquals("NOT_FOUND", assertFailsWith<TutorialProblem> { store.saveAnnotationDraft(saved, "迟到保存", false) }.code)
        }
        TutorialStore(path).use { store ->
            assertEquals("note_empty", store.annotations(material).single().id)
            assertTrue(store.annotations(material).filterNot { it.isEmptyDraft }.isEmpty())
        }
    }

    @Test fun `deleting a question keeps contexts used by independent tutorials`() {
        TutorialStore(path).use { store ->
            store.context()
            val material = store.create("ctx_test", "引用产物", "正文", TutorialResourceState())
            val question = store.createAnnotation(TutorialAnnotation("q_shared", material.id, "question", 0, 2, "正文", contextId = "ctx_test", answerMaterialId = material.id))
            store.deleteAnnotation(question.id)
            assertEquals("ctx_test", store.context("ctx_test").id)
            assertEquals(material, store.record(material.id))
        }
    }
}

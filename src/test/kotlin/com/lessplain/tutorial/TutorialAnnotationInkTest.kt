package com.lessplain.tutorial

import org.junit.jupiter.api.Test
import kotlin.test.*

class TutorialAnnotationInkTest {
    private fun item(id: String, kind: String, start: Int, end: Int) = TutorialAnnotation(id, "t", kind, start, end, "$start:$end", text = "已保存")

    @Test fun `only the true intersection is purple and repeated notes never darken it`() {
        val note = item("n", "note", 0, 10)
        val question = item("q", "question", 5, 15)
        val expected = listOf(AnnotationInkRun(0, 5, "note"), AnnotationInkRun(5, 10, "both"), AnnotationInkRun(10, 15, "question"))
        assertEquals(expected, annotationInkRuns(listOf(note, question)))
        assertEquals(expected, annotationInkRuns(listOf(note, note.copy(id = "n2"), question, question.copy(id = "q2"))))
        assertEquals(listOf(AnnotationInkRun(0, 10, "both")), annotationInkRuns(listOf(note, question.copy(start = 0, end = 10))))
        assertEquals(listOf(AnnotationInkRun(0, 10, "note")), annotationInkRuns(listOf(note, question.copy(orphaned = true))))
    }

    @Test fun `touching ranges keep their original category and separate gaps stay unpainted`() {
        assertEquals(listOf(AnnotationInkRun(0, 5, "note"), AnnotationInkRun(5, 8, "question"), AnnotationInkRun(10, 12, "note")),
            annotationInkRuns(listOf(item("a", "note", 0, 5), item("b", "question", 5, 8), item("c", "note", 10, 12))))
    }

    @Test fun `mixed hover independently finds real note and question ranges`() {
        val note = item("n", "note", 0, 10)
        val question = item("q", "question", 5, 15)
        val nested = item("q_small", "question", 6, 8)
        val groups = annotationsAt(listOf(note, question, nested, nested.copy(id = "q_small_2")), 7)
        assertEquals(listOf(note), groups["note"])
        assertEquals(setOf("q_small", "q_small_2"), groups["question"]?.map { it.id }?.toSet())
        assertEquals(setOf("question"), annotationsAt(listOf(note, question), 12).keys)
    }
}

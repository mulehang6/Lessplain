package com.lessplain.ide

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class ProjectSourcePathsTest {
    @TempDir lateinit var directory: Path

    @Test fun rejectsTraversalSiblingPrefixesAndMissingFiles() {
        val root = Files.createDirectory(directory.resolve("project"))
        val inside = Files.writeString(root.resolve("source.kt"), "inside")
        val outside = Files.createDirectories(directory.resolve("project-other")).resolve("source.kt")
        Files.writeString(outside, "outside")
        requireProjectSourcePath(root, inside)
        assertFailsWith<IllegalArgumentException> { requireProjectSourcePath(root, outside) }
        assertFailsWith<IllegalArgumentException> { requireProjectSourcePath(root, root.resolve("../project-other/source.kt")) }
        assertFails { requireProjectSourcePath(root, root.resolve("missing.kt")) }
    }

    @Test fun rejectsExternalDirectoryAliasButAllowsInternalAlias() {
        val root = Files.createDirectory(directory.resolve("project"))
        val outside = Files.createDirectory(directory.resolve("outside"))
        val inside = Files.createDirectory(root.resolve("inside"))
        Files.writeString(outside.resolve("source.kt"), "outside")
        Files.writeString(inside.resolve("source.kt"), "inside")
        for ((name, target) in listOf("external" to outside, "internal" to inside)) {
            val alias = root.resolve(name)
            if (System.getProperty("os.name").startsWith("Windows")) {
                val process = ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", alias.toString(), target.toString())
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), output)
            } else Files.createSymbolicLink(alias, target)
            try {
                if (name == "external") assertFailsWith<IllegalArgumentException> { requireProjectSourcePath(root, alias.resolve("source.kt")) }
                else requireProjectSourcePath(root, alias.resolve("source.kt"))
            } finally { Files.delete(alias) }
        }
    }
}

package com.lessplain.ide

import java.nio.file.Path

/** 同时检查逻辑路径和真实路径，统一拒绝目录穿越以及指向项目外的符号链接或 junction。 */
internal fun requireProjectSourcePath(root: Path, candidate: Path) {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val normalized = candidate.toAbsolutePath().normalize()
    require(normalized.startsWith(normalizedRoot)) { "源码路径必须在项目内" }
    require(normalized.toRealPath().startsWith(normalizedRoot.toRealPath())) { "源码链接超出项目" }
}

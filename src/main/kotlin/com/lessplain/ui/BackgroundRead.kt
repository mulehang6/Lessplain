package com.lessplain.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lessplain.core.LatestBackgroundRead
import java.util.concurrent.CompletableFuture
import javax.swing.SwingUtilities

/** 后台读库只携带固定输入；结果经 EDT 发布，不读取变化中的全局界面选择。 */
internal fun <T> dispatchBackgroundRead(read: () -> T, completed: (Result<T>) -> Unit) {
    CompletableFuture.supplyAsync { runCatching(read) }.whenComplete { result, failure ->
        SwingUtilities.invokeLater {
            completed(if (failure == null) result else Result.failure(failure.cause ?: failure))
        }
    }
}

/** 同一图保留已有快照，换图则重新加载；连续变更只执行最新待执行读取。 */
@Composable
internal fun <T> rememberBackgroundRead(key: Any, revision: Int, read: () -> T): Result<T>? {
    var result by remember(key) { mutableStateOf<Result<T>?>(null) }
    val loader = remember(key) { LatestBackgroundRead<T>(::dispatchBackgroundRead) { result = it } }
    DisposableEffect(loader) {
        onDispose { loader.close() }
    }
    DisposableEffect(loader, revision) {
        loader.request(read)
        onDispose { }
    }
    return result
}

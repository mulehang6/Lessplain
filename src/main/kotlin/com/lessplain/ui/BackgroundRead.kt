package com.lessplain.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lessplain.core.LatestBackgroundRead
import com.lessplain.ide.UnderstandingService

/** 同一图保留已有快照，换图则重新加载；连续变更只执行最新待执行读取。 */
@Composable
internal fun <T> rememberBackgroundRead(service: UnderstandingService, key: Any, revision: Int, read: () -> T): Result<T>? {
    var result by remember(key) { mutableStateOf<Result<T>?>(null) }
    val loader = remember(service, key) { LatestBackgroundRead<T>(service::readBackground) { result = it } }
    DisposableEffect(loader) {
        onDispose { loader.close() }
    }
    DisposableEffect(loader, revision) {
        loader.request(read)
        onDispose { }
    }
    return result
}

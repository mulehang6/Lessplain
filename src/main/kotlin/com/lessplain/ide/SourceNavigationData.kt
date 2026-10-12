package com.lessplain.ide

import com.lessplain.model.BrowseState
import com.lessplain.model.UnderstandingRecord
import com.lessplain.storage.UnderstandingRepository

/** 导航算法只依赖快照和保存入口，生产界面提供内存快照及合并后台写入。 */
internal class SourceNavigationData(
    val getRecord: (String) -> UnderstandingRecord,
    val getBrowseState: (String) -> BrowseState?,
    val saveBrowseState: (BrowseState) -> Unit,
    val readOnly: () -> Boolean,
) {
    constructor(repository: UnderstandingRepository) : this(
        repository::getRecord, repository::getBrowseState, repository::saveBrowseState, { repository.readOnly },
    )
}

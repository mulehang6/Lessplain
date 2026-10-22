package com.lessplain.model

/** 与参数格式错误分开报告并发修订，调用者可返回当前版本供重试。 */
class UnderstandingRevisionConflict(val currentRevision: Int) : IllegalArgumentException("理解记录版本已变化，请重新读取")

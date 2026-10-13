package com.niuma.core

/** Status copy shared by the run screen and execution service. */
object RunPresentation {
    fun completionMessage(status: RunStatus, reason: String = ""): String? = when (status) {
        RunStatus.SUCCESS -> Regex("\\b(?:[01]\\d|2[0-3]):[0-5]\\d\\b").find(reason)?.value?.let { "打卡成功 · $it" }
            ?: "打卡成功"
        RunStatus.FAILED, RunStatus.MISSED -> "打卡失败"
        RunStatus.UNKNOWN -> "结果待核查"
        else -> null
    }

    fun finalResultLabel(status: RunStatus): String? = when (status) {
        RunStatus.SUCCESS -> "成功"
        RunStatus.FAILED, RunStatus.MISSED -> "失败"
        RunStatus.UNKNOWN -> "待核查"
        else -> null
    }
}

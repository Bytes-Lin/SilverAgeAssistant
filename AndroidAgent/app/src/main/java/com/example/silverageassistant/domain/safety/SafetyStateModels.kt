package com.example.silverageassistant.domain.safety

enum class ElderObservedState { NORMAL, ABNORMAL }

data class SafetyAnalysisResult(
    val state: ElderObservedState,
    val detail: String?,
)

data class TimedSafetyAnalysis(
    val observedAt: String,
    val result: SafetyAnalysisResult,
)

interface SafetyVisionAnalyzer {
    suspend fun analyze(
        image: SafetyImage,
        previousState: TimedSafetyAnalysis?,
        observedAt: String,
    ): SafetyAnalysisResult
}

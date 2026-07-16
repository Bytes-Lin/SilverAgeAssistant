package com.example.silverageassistant.ui.conversation

enum class ConversationPhase(
    val statusText: String,
    val guidanceText: String,
) {
    Idle("可以开始说话", "点下面的按钮，我会认真听。"),
    Listening("正在听", "请慢慢说，说完后点“我说完了”。"),
    Transcribing("正在识别", "正在把您的话变成文字，请稍候。"),
    Thinking("正在思考", "正在整理回答，请稍候。"),
    Speaking("正在播放", "正在为您读出回答，随时可以停止。"),
}

data class ConversationUiState(
    val phase: ConversationPhase = ConversationPhase.Idle,
    val transcript: String = "",
    val reply: String = "",
) {
    val isProcessing: Boolean
        get() = phase == ConversationPhase.Transcribing || phase == ConversationPhase.Thinking
}

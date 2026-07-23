package com.example.silverageassistant.domain.model

import kotlinx.coroutines.flow.Flow

interface ChatModelProvider {
    fun stream(request: ChatRequest): Flow<ChatStreamEvent>
}

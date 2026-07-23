package com.example.silverageassistant.domain.safety

data class SafetyImage(
    val bytes: ByteArray,
    val mimeType: String,
    val sourceLabel: String,
)

interface SafetyImageSource {
    suspend fun acquireLatestImage(): SafetyImage
}

/** Future LAN camera implementations must provide only the latest still image. */
interface NetworkCameraImageSource : SafetyImageSource

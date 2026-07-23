package com.example.silverageassistant.data.safety

import android.content.Context
import com.example.silverageassistant.domain.safety.SafetyImage
import com.example.silverageassistant.domain.safety.SafetyImageSource
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MockSafetyImageSource(
    context: Context,
    private val assetDirectory: String = "mock/fall_detect",
) : SafetyImageSource {
    private val assets = context.applicationContext.assets
    private val nextIndex = AtomicInteger(0)

    override suspend fun acquireLatestImage(): SafetyImage = withContext(Dispatchers.IO) {
        val names = assets.list(assetDirectory)
            .orEmpty()
            .filter { it.endsWith(".png", true) || it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) }
            .sorted()
        check(names.isNotEmpty()) { "没有可用的状态检测测试图像" }
        val name = names[Math.floorMod(nextIndex.getAndIncrement(), names.size)]
        val bytes = assets.open("$assetDirectory/$name").use { it.readBytes() }
        SafetyImage(
            bytes = bytes,
            mimeType = if (name.endsWith(".png", true)) "image/png" else "image/jpeg",
            sourceLabel = "mock/$name",
        )
    }
}

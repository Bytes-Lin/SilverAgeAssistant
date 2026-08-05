package com.example.silverageassistant.domain.gui.vision

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 不绑定具体模型厂商的像素预算。具体数值必须由 GUI 模型配置提供，不能根据手机分辨率
 * 直接把原图上传。
 */
data class ScreenshotPixelBudget(
    val maxLongEdgePx: Int,
    val maxPixelCount: Long,
) {
    init {
        require(maxLongEdgePx > 0)
        require(maxPixelCount > 0)
    }
}

/**
 * 从截图裁剪区域到实际上传给 MLLM 的图像之间的确定性几何关系。
 *
 * 当前核心只做等比 resize，不做 letterbox。若具体 Provider 要求补边，平台适配层必须把
 * padding 也显式加入 AffineTransform2D，禁止用隐含比例猜测。
 */
data class ModelImageGeometry(
    val captureSize: PixelSize,
    val cropInCapture: PixelRect,
    val uploadSize: PixelSize,
    val captureToUpload: AffineTransform2D,
) {
    val uploadToCapture: AffineTransform2D = captureToUpload.inverse()
}

object ScreenshotResizePlanner {
    fun plan(
        captureSize: PixelSize,
        cropInCapture: PixelRect = PixelRect.full(captureSize),
        budget: ScreenshotPixelBudget,
    ): ModelImageGeometry {
        require(cropInCapture.isInside(captureSize)) {
            "Screenshot crop must stay inside the captured bitmap"
        }
        val cropWidth = cropInCapture.width
        val cropHeight = cropInCapture.height
        val longEdgeScale = budget.maxLongEdgePx.toDouble() / maxOf(cropWidth, cropHeight)
        val pixelScale = sqrt(
            budget.maxPixelCount.toDouble() / (cropWidth * cropHeight),
        )
        val scale = min(1.0, min(longEdgeScale, pixelScale))
        val uploadSize = PixelSize(
            width = (cropWidth * scale).roundToInt().coerceAtLeast(1),
            height = (cropHeight * scale).roundToInt().coerceAtLeast(1),
        )
        val exactScaleX = uploadSize.width.toDouble() / cropWidth
        val exactScaleY = uploadSize.height.toDouble() / cropHeight
        val captureToUpload = AffineTransform2D
            .translation(-cropInCapture.left, -cropInCapture.top)
            .then(AffineTransform2D.scale(exactScaleX, exactScaleY))
        return ModelImageGeometry(
            captureSize = captureSize,
            cropInCapture = cropInCapture,
            uploadSize = uploadSize,
            captureToUpload = captureToUpload,
        )
    }
}

/**
 * 一次 MLLM 观察所绑定的不可变屏幕帧。任何旋转、窗口切换、人工触摸或新截图都会让
 * 基于旧 frameId 的动作失效。
 */
data class ScreenFrameGeometry(
    val frameId: String,
    val capturedAtEpochMillis: Long,
    val displayId: Int,
    val rotationDegrees: Int,
    val screenSize: PixelSize,
    val targetWindowInScreen: PixelRect,
    val modelImage: ModelImageGeometry,
    /**
     * 由 Android 截图实现按每一帧提供。它负责表达 MediaProjection 缩放/居中、显示旋转、
     * 厂商截图尺寸差异等，不能默认永远为 Identity。
     */
    val captureToScreen: AffineTransform2D,
) {
    init {
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(targetWindowInScreen.isInside(screenSize))
    }
}

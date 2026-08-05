package com.example.silverageassistant.domain.gui.vision

enum class ModelCoordinateSpace {
    /**
     * 推荐协议：0..1000 相对于实际上传图像。这样模型内部缩放不需要由客户端猜测。
     */
    NORMALIZED_0_1000,

    NORMALIZED_0_1,
    UPLOAD_IMAGE_PIXELS,
}

data class ModelPointPrediction(
    val frameId: String,
    val x: Double,
    val y: Double,
    val coordinateSpace: ModelCoordinateSpace,
    /**
     * 当模型返回像素坐标时要求同时回显它认为的输入尺寸，用于发现模型按其他尺寸输出。
     */
    val reportedImageSize: PixelSize? = null,
)

data class MappedScreenPoint(
    val frameId: String,
    val uploadPoint: PointD,
    val capturePoint: PointD,
    val screenPoint: PointD,
)

object ModelCoordinateMapper {
    fun toScreen(
        prediction: ModelPointPrediction,
        frame: ScreenFrameGeometry,
    ): MappedScreenPoint {
        require(prediction.frameId == frame.frameId) {
            "Model prediction belongs to a stale screenshot"
        }
        val uploadPoint = prediction.toUploadPoint(frame.modelImage.uploadSize)
        val uploadBounds = PixelRect.full(frame.modelImage.uploadSize)
        require(uploadBounds.contains(uploadPoint)) {
            "Model point is outside the uploaded image"
        }
        val capturePoint = frame.modelImage.uploadToCapture.map(uploadPoint)
        require(frame.modelImage.cropInCapture.contains(capturePoint)) {
            "Mapped point is outside the captured target crop"
        }
        val screenPoint = frame.captureToScreen.map(capturePoint)
        require(PixelRect.full(frame.screenSize).contains(screenPoint)) {
            "Mapped point is outside the display"
        }
        require(frame.targetWindowInScreen.contains(screenPoint)) {
            "Mapped point is outside the current target window"
        }
        return MappedScreenPoint(
            frameId = frame.frameId,
            uploadPoint = uploadPoint,
            capturePoint = capturePoint,
            screenPoint = screenPoint,
        )
    }

    private fun ModelPointPrediction.toUploadPoint(uploadSize: PixelSize): PointD {
        val maxX = (uploadSize.width - 1).toDouble()
        val maxY = (uploadSize.height - 1).toDouble()
        return when (coordinateSpace) {
            ModelCoordinateSpace.NORMALIZED_0_1000 -> {
                require(x in 0.0..1000.0 && y in 0.0..1000.0)
                PointD(x = x / 1000.0 * maxX, y = y / 1000.0 * maxY)
            }

            ModelCoordinateSpace.NORMALIZED_0_1 -> {
                require(x in 0.0..1.0 && y in 0.0..1.0)
                PointD(x = x * maxX, y = y * maxY)
            }

            ModelCoordinateSpace.UPLOAD_IMAGE_PIXELS -> {
                require(reportedImageSize == uploadSize) {
                    "Model pixel coordinates use an unexpected image size"
                }
                PointD(x = x, y = y)
            }
        }
    }
}

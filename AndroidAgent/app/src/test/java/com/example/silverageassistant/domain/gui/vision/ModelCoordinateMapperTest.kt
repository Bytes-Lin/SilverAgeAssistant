package com.example.silverageassistant.domain.gui.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCoordinateMapperTest {
    @Test
    fun largePortraitScreenshot_isResizedWithinBothBudgets() {
        val geometry = ScreenshotResizePlanner.plan(
            captureSize = PixelSize(1440, 3200),
            budget = ScreenshotPixelBudget(
                maxLongEdgePx = 1600,
                maxPixelCount = 1_500_000,
            ),
        )

        assertTrue(maxOf(geometry.uploadSize.width, geometry.uploadSize.height) <= 1600)
        assertTrue(geometry.uploadSize.pixelCount <= 1_500_000)
        assertEquals(720, geometry.uploadSize.width)
        assertEquals(1600, geometry.uploadSize.height)
    }

    @Test
    fun normalizedModelPoint_isMappedBackThroughCropResizeAndOsScale() {
        val modelImage = ScreenshotResizePlanner.plan(
            captureSize = PixelSize(1080, 2400),
            cropInCapture = PixelRect(
                left = 0.0,
                top = 100.0,
                right = 1080.0,
                bottom = 2300.0,
            ),
            budget = ScreenshotPixelBudget(
                maxLongEdgePx = 1100,
                maxPixelCount = 1_000_000,
            ),
        )
        val frame = ScreenFrameGeometry(
            frameId = "frame-1",
            capturedAtEpochMillis = 1,
            displayId = 0,
            rotationDegrees = 0,
            screenSize = PixelSize(1440, 3200),
            targetWindowInScreen = PixelRect(
                left = 0.0,
                top = 0.0,
                right = 1440.0,
                bottom = 3200.0,
            ),
            modelImage = modelImage,
            captureToScreen = AffineTransform2D.scale(
                sx = 1440.0 / 1080.0,
                sy = 3200.0 / 2400.0,
            ),
        )

        val mapped = ModelCoordinateMapper.toScreen(
            prediction = ModelPointPrediction(
                frameId = "frame-1",
                x = 500.0,
                y = 500.0,
                coordinateSpace = ModelCoordinateSpace.NORMALIZED_0_1000,
            ),
            frame = frame,
        )

        assertEquals(720.0, mapped.screenPoint.x, 2.0)
        assertEquals(1600.0, mapped.screenPoint.y, 2.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun staleFramePrediction_isRejected() {
        val size = PixelSize(100, 200)
        val frame = ScreenFrameGeometry(
            frameId = "new-frame",
            capturedAtEpochMillis = 2,
            displayId = 0,
            rotationDegrees = 0,
            screenSize = size,
            targetWindowInScreen = PixelRect.full(size),
            modelImage = ScreenshotResizePlanner.plan(
                captureSize = size,
                budget = ScreenshotPixelBudget(200, 20_000),
            ),
            captureToScreen = AffineTransform2D.Identity,
        )

        ModelCoordinateMapper.toScreen(
            prediction = ModelPointPrediction(
                frameId = "old-frame",
                x = 500.0,
                y = 500.0,
                coordinateSpace = ModelCoordinateSpace.NORMALIZED_0_1000,
            ),
            frame = frame,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun pixelCoordinatesWithUnexpectedModelImageSize_areRejected() {
        val size = PixelSize(100, 200)
        val frame = ScreenFrameGeometry(
            frameId = "frame-1",
            capturedAtEpochMillis = 1,
            displayId = 0,
            rotationDegrees = 0,
            screenSize = size,
            targetWindowInScreen = PixelRect.full(size),
            modelImage = ScreenshotResizePlanner.plan(
                captureSize = size,
                budget = ScreenshotPixelBudget(200, 20_000),
            ),
            captureToScreen = AffineTransform2D.Identity,
        )

        ModelCoordinateMapper.toScreen(
            prediction = ModelPointPrediction(
                frameId = "frame-1",
                x = 50.0,
                y = 100.0,
                coordinateSpace = ModelCoordinateSpace.UPLOAD_IMAGE_PIXELS,
                reportedImageSize = PixelSize(200, 400),
            ),
            frame = frame,
        )
    }
}

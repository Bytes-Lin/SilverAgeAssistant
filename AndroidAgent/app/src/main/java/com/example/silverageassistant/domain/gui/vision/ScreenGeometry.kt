package com.example.silverageassistant.domain.gui.vision

import kotlin.math.abs

data class PixelSize(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "Pixel dimensions must be positive" }
    }

    val pixelCount: Long
        get() = width.toLong() * height.toLong()
}

data class PointD(
    val x: Double,
    val y: Double,
)

data class PixelRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(right > left && bottom > top) { "Rectangle must have a positive area" }
    }

    val width: Double
        get() = right - left

    val height: Double
        get() = bottom - top

    fun contains(point: PointD): Boolean =
        point.x >= left && point.x < right && point.y >= top && point.y < bottom

    fun isInside(size: PixelSize): Boolean =
        left >= 0.0 &&
            top >= 0.0 &&
            right <= size.width.toDouble() &&
            bottom <= size.height.toDouble()

    companion object {
        fun full(size: PixelSize) = PixelRect(
            left = 0.0,
            top = 0.0,
            right = size.width.toDouble(),
            bottom = size.height.toDouble(),
        )
    }
}

/**
 * 二维仿射变换，使用：
 *
 * x' = m00*x + m01*y + m02
 * y' = m10*x + m11*y + m12
 */
data class AffineTransform2D(
    val m00: Double,
    val m01: Double,
    val m02: Double,
    val m10: Double,
    val m11: Double,
    val m12: Double,
) {
    fun map(point: PointD): PointD = PointD(
        x = m00 * point.x + m01 * point.y + m02,
        y = m10 * point.x + m11 * point.y + m12,
    )

    /**
     * 先执行当前变换，再执行 next。
     */
    fun then(next: AffineTransform2D): AffineTransform2D = AffineTransform2D(
        m00 = next.m00 * m00 + next.m01 * m10,
        m01 = next.m00 * m01 + next.m01 * m11,
        m02 = next.m00 * m02 + next.m01 * m12 + next.m02,
        m10 = next.m10 * m00 + next.m11 * m10,
        m11 = next.m10 * m01 + next.m11 * m11,
        m12 = next.m10 * m02 + next.m11 * m12 + next.m12,
    )

    fun inverse(): AffineTransform2D {
        val determinant = m00 * m11 - m01 * m10
        require(abs(determinant) > MIN_DETERMINANT) { "Transform is not invertible" }
        return AffineTransform2D(
            m00 = m11 / determinant,
            m01 = -m01 / determinant,
            m02 = (m01 * m12 - m11 * m02) / determinant,
            m10 = -m10 / determinant,
            m11 = m00 / determinant,
            m12 = (m10 * m02 - m00 * m12) / determinant,
        )
    }

    companion object {
        val Identity = AffineTransform2D(
            m00 = 1.0,
            m01 = 0.0,
            m02 = 0.0,
            m10 = 0.0,
            m11 = 1.0,
            m12 = 0.0,
        )

        fun translation(dx: Double, dy: Double) = AffineTransform2D(
            m00 = 1.0,
            m01 = 0.0,
            m02 = dx,
            m10 = 0.0,
            m11 = 1.0,
            m12 = dy,
        )

        fun scale(sx: Double, sy: Double) = AffineTransform2D(
            m00 = sx,
            m01 = 0.0,
            m02 = 0.0,
            m10 = 0.0,
            m11 = sy,
            m12 = 0.0,
        )

        private const val MIN_DETERMINANT = 1e-12
    }
}

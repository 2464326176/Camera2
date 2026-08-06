/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.RenderEffect
import android.media.Image
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

/**
 * Helper class used to convert a [Image] object from
 * [ImageFormat.YUV_420_888] format to an RGB [Bitmap] object.
 *
 * This is a pure-Kotlin reimplementation that does not depend on the deprecated
 * RenderScript API (removed in newer Android Gradle Plugin versions). It reuses
 * [YuvByteBuffer] to obtain a compact planar YUV_420_888 / NV21 buffer, then applies
 * the standard JFIF YUV->RGB transform.
 *
 * NOTE: This has been tested in a limited number of devices and is not
 * considered production-ready code. It was created for illustration purposes,
 * since this is not an efficient camera pipeline due to the multiple copies
 * required to convert each frame. For example, the Renderscript/RenderEffect
 * implementation might have better performance.
 */
class YuvToRgbConverter(context: Context) {

    // Scratch buffer reused across conversions to avoid per-frame allocations.
    /** Reusable YUV byte buffer kept between conversions to avoid per-frame allocations. */
    private var yuvBits: ByteBuffer? = null

    /** Converts a YUV [image] into [output] as an RGB bitmap (thread-safe). */
    @Synchronized
    fun yuvToRgb(image: Image, output: Bitmap) {
        val yuvBuffer = YuvByteBuffer(image, yuvBits)
        yuvBits = yuvBuffer.buffer

        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)

        when (yuvBuffer.type) {
            ImageFormat.YUV_420_888 -> yuv420ToRgb(yuvBuffer.buffer, width, height, pixels)
            ImageFormat.NV21 -> nv21ToRgb(yuvBuffer.buffer, width, height, pixels)
            else -> throw IllegalArgumentException("Unsupported yuv type: ${yuvBuffer.type}")
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    /** Converts a planar YUV_420_888 [buffer] into the [pixels] RGB array, one pixel at a time. */
    private fun yuv420ToRgb(buffer: ByteBuffer, width: Int, height: Int, pixels: IntArray) {
        val data = buffer.array()
        val ySize = width * height
        val uSize = ySize / 4
        var p = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val yIdx = y * width + x
                val uvIdx = (y shr 1) * (width shr 1) + (x shr 1)
                val yv = data[yIdx].toInt() and 0xFF
                val u = data[ySize + uvIdx].toInt() and 0xFF
                val v = data[ySize + uSize + uvIdx].toInt() and 0xFF
                pixels[p++] = yuvToRgbInt(yv, u, v)
            }
        }
    }

    /** Converts an interleaved NV21 [buffer] into the [pixels] RGB array, one pixel at a time. */
    private fun nv21ToRgb(buffer: ByteBuffer, width: Int, height: Int, pixels: IntArray) {
        val data = buffer.array()
        val ySize = width * height
        var p = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val yIdx = y * width + x
                val uvIdx = (y shr 1) * width + (x and 1.inv())
                val yv = data[yIdx].toInt() and 0xFF
                val v = data[ySize + uvIdx].toInt() and 0xFF
                val u = data[ySize + uvIdx + 1].toInt() and 0xFF
                pixels[p++] = yuvToRgbInt(yv, u, v)
            }
        }
    }

    /** Computes a packed ARGB [Int] for a single pixel using the JFIF YUV->RGB formula. */
    private fun yuvToRgbInt(y: Int, u: Int, v: Int): Int {
        var r = (y + (v - 128) + ((v - 128) * 103) / 256)
        var g = (y - ((u - 128) * 88) / 256 - ((v - 128) * 183) / 256)
        var b = (y + (u - 128) + ((u - 128) * 198) / 256)
        r = r.coerceIn(0, 255)
        g = g.coerceIn(0, 255)
        b = b.coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

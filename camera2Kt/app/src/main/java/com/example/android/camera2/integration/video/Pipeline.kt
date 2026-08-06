/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package com.example.android.camera2.integration.video.fragments

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Size
import android.view.Surface
import com.example.android.camera.utils.AutoFitSurfaceView

import com.example.android.camera2.integration.video.EncoderWrapper

abstract class Pipeline(width: Int, height: Int, fps: Int, filterOn: Boolean,
        dynamicRange: Long, characteristics: CameraCharacteristics, encoder: EncoderWrapper,
        viewFinder: AutoFitSurfaceView) {
    /** Width of the video to encode, in pixels. */
    protected val width = width
    /** Height of the video to encode, in pixels. */
    protected val height = height
    /** Target frame rate, in frames per second. */
    protected val fps = fps
    /** Whether the portrait/beauty filter is enabled. */
    protected val filterOn = filterOn
    /** Dynamic range profile requested from the camera (e.g. SDR, HLG10). */
    protected val dynamicRange = dynamicRange
    /** Camera characteristics used to query capabilities and orientation. */
    protected val characteristics = characteristics
    /** Encoder wrapper that owns the MediaCodec/MediaRecorder and output surface. */
    protected val encoder = encoder
    /** SurfaceView used to display the camera preview. */
    protected val viewFinder = viewFinder

    /** Builds a capture request for preview; returns null if not supported. */
    open public fun createPreviewRequest(session: CameraCaptureSession,
            previewStabilization: Boolean): CaptureRequest? {
        return null
    }

    /** Builds a capture request used while recording video. */
    public abstract fun createRecordRequest(session: CameraCaptureSession,
            previewStabilization: Boolean): CaptureRequest

    /** Releases the EGL window surface used for preview rendering. */
    open public fun destroyWindowSurface() { }

    /** Updates the preview size after the view is laid out. */
    open public fun setPreviewSize(previewSize: Size) { }

    /** Creates GL/EGL resources needed for rendering. */
    open public fun createResources(surface: Surface) { }

    /** Returns the list of surfaces used for preview. */
    public abstract fun getPreviewTargets(): List<Surface>

    /** Returns the list of surfaces used for recording. */
    public abstract fun getRecordTargets(): List<Surface>

    /** Called on a tap event to (re)trigger recording on the encoder surface. */
    open public fun actionDown(encoderSurface: Surface) { }

    /** Removes the on-frame-available listener. */
    open public fun clearFrameListener() { }

    /** Releases all GL/EGL and encoder resources. */
    open public fun cleanup() { }

    /** Starts the recording pipeline. */
    open public fun startRecording() { }

    /** Stops the recording pipeline. */
    open public fun stopRecording() { }
}
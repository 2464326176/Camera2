/*
 * Copyright 2024 The Android Open Source Project
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

package com.example.android.camera2.integration

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.android.camera2.integration.advanced.CameraActivity as AdvancedCameraActivity
import com.example.android.camera2.integration.basic.CameraActivity as BasicCameraActivity
import com.example.android.camera2.integration.cameraxbasic.MainActivity as CameraxBasicMainActivity
import com.example.android.camera2.integration.cameraxext.MainActivity as CameraxExtMainActivity
import com.example.android.camera2.integration.cameraxvideo.MainActivity as CameraxVideoMainActivity
import com.example.android.camera2.integration.extensions.CameraActivity as ExtensionsCameraActivity
import com.example.android.camera2.integration.hdr.HdrViewfinderActivity
import com.example.android.camera2.integration.mlkit.MainActivity as MlkitMainActivity
import com.example.android.camera2.integration.slowmo.CameraActivity as SlowmoCameraActivity
import com.example.android.camera2.integration.video.CameraActivity as VideoCameraActivity

/**
 * Unified launcher / main menu that lists every integrated camera sample and starts the
 * corresponding Activity when its card is tapped.
 */
data class SampleItem(
    val title: String,
    val description: String,
    val activityClass: Class<*>
)

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val samples = listOf(
            SampleItem(
                "Camera2Basic",
                "Capture JPEG, RAW and DEPTH images via the Camera2 API.",
                BasicCameraActivity::class.java
            ),
            SampleItem(
                "Camera2Extensions",
                "Camera2 extension live preview and still capture.",
                ExtensionsCameraActivity::class.java
            ),
            SampleItem(
                "Camera2SlowMotion",
                "High-speed video in a constrained capture session.",
                SlowmoCameraActivity::class.java
            ),
            SampleItem(
                "Camera2Video",
                "Recording video using the Camera2 API and MediaRecorder.",
                VideoCameraActivity::class.java
            ),
            SampleItem(
                "CameraXBasic",
                "Getting started with the CameraX API.",
                CameraxBasicMainActivity::class.java
            ),
            SampleItem(
                "CameraXAdvanced (TFLite)",
                "Object detection with CameraX and TensorFlow Lite.",
                AdvancedCameraActivity::class.java
            ),
            SampleItem(
                "CameraXVideo",
                "Video capture with the CameraX VideoCapture API.",
                CameraxVideoMainActivity::class.java
            ),
            SampleItem(
                "CameraX-MLKit",
                "QR-code scanner using CameraX MlKitAnalyzer.",
                MlkitMainActivity::class.java
            ),
            SampleItem(
                "CameraXExtensions",
                "CameraX extension live preview and still capture (Compose).",
                CameraxExtMainActivity::class.java
            ),
            SampleItem(
                "HdrViewfinder",
                "Real-time HDR viewfinder using the Camera2 API.",
                HdrViewfinderActivity::class.java
            )
        )

        val recycler = findViewById<RecyclerView>(R.id.sample_list)
        recycler.layoutManager = GridLayoutManager(this, 2)
        recycler.adapter = SampleAdapter(samples) { item ->
            startActivity(Intent(this, item.activityClass))
        }
    }
}

package com.opencv.camera;

/**
 * Frame metadata extracted from Camera2 CaptureResult, passed to the C++ layer
 * in sync with the frame data. Field names correspond one-to-one with the
 * C++ FrameMetadata struct.
 */
public class FrameMetadata {
    public long timestampNs;
    public int iso = 100;
    public long exposureTimeNs;
    public int flashState;
    public float lensAperture;
    public int aeState;
    public int afState;
    public int awbState;

    public FrameMetadata() {}

    public FrameMetadata(long timestampNs, int iso, long exposureTimeNs,
                         int flashState, float lensAperture,
                         int aeState, int afState, int awbState) {
        this.timestampNs = timestampNs;
        this.iso = iso;
        this.exposureTimeNs = exposureTimeNs;
        this.flashState = flashState;
        this.lensAperture = lensAperture;
        this.aeState = aeState;
        this.afState = afState;
        this.awbState = awbState;
    }
}
package com.opencv.camera;

/** Per-frame camera state paired with the image buffer sent to C++. */
public class FrameMetadata {
    public long timestampNs;
    public int iso = 100;
    public long exposureTimeNs;
    public int flashState;
    public float lensAperture;
    public int aeState;
    public int afState;
    public int awbState;
    public float focalLength;
    public float focusDistance;
    public int rotation;
    public int lensFacing;
    public int frameNumber;
    public boolean approximate;

    public FrameMetadata() {}

    public FrameMetadata(
            long timestampNs, int iso, long exposureTimeNs,
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

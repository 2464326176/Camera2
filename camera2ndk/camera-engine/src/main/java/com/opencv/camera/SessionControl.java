package com.opencv.camera;

/**
 * UI/camera state passed to C++. Fields express intent only; native Decision
 * owns the final algorithm selection, ordering and parameters.
 */
public final class SessionControl {
    public static final int MODE_PHOTO = 0;
    public static final int MODE_VIDEO = 1;

    public int mode = MODE_PHOTO;
    public int lensFacing;
    public int flashMode;
    public boolean preferFaceDetect = true;
    public boolean preferDenoise = true;
    public boolean preferSharpen = true;
    public boolean preferHdr;
    public boolean preferClahe;
    public boolean preferSaturation;
    public boolean preferBokeh;
    public int jpegQuality = 95;
    public int analysisMaxSide = 320;

    public SessionControl copy() {
        SessionControl copy = new SessionControl();
        copy.mode = mode;
        copy.lensFacing = lensFacing;
        copy.flashMode = flashMode;
        copy.preferFaceDetect = preferFaceDetect;
        copy.preferDenoise = preferDenoise;
        copy.preferSharpen = preferSharpen;
        copy.preferHdr = preferHdr;
        copy.preferClahe = preferClahe;
        copy.preferSaturation = preferSaturation;
        copy.preferBokeh = preferBokeh;
        copy.jpegQuality = jpegQuality;
        copy.analysisMaxSide = analysisMaxSide;
        return copy;
    }
}

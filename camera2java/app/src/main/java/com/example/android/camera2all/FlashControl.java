package com.example.android.camera2all;

/**
 * Implemented by the camera fragments so the shared top-bar flash button (in
 * {@link MainActivity}) can drive the flash mode of whichever fragment is currently active.
 */
public interface FlashControl {

    /** Flash disabled. */
    int FLASH_OFF = 0;
    /** Flash fires automatically when the scene is dark. */
    int FLASH_AUTO = 1;
    /** Flash always fires. */
    int FLASH_ON = 2;

    /** Apply the given flash mode to the live preview (and subsequent captures). */
    void setFlashMode(int mode);

    /** @return the current flash mode (one of {@link #FLASH_OFF}, {@link #FLASH_AUTO}, {@link #FLASH_ON}). */
    int getFlashMode();

    /** @return true if the active camera supports a flash at all. */
    boolean isFlashSupported();
}

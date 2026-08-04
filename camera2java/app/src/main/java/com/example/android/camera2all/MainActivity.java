/*
 * Integrated Camera2 app: Basic (photo) + Video + Raw.
 */

package com.example.android.camera2all;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    // Shared flash mode driven by the top-bar flash button; routed to the active fragment.
    private int mFlashMode = FlashControl.FLASH_AUTO;
    // Top-bar flash button (far left).
    private ImageButton mFlashButton;

    // Currently selected bottom-navigation tab, so it can be re-opened after a settings change.
    private int mCurrentNavId = R.id.nav_photo;

    // Request code used when launching the settings page for a result.
    private static final int REQUEST_SETTINGS = 1001;

    private final BottomNavigationView.OnNavigationItemSelectedListener mOnNavigationItemSelectedListener
            = new BottomNavigationView.OnNavigationItemSelectedListener() {

        @Override
        public boolean onNavigationItemSelected(@NonNull android.view.MenuItem item) {
            int id = item.getItemId();
            if (id == R.id.nav_photo) {
                mCurrentNavId = id;
                switchFragment(Camera2BasicFragment.newInstance());
                return true;
            } else if (id == R.id.nav_video) {
                mCurrentNavId = id;
                switchFragment(Camera2VideoFragment.newInstance());
                return true;
            } else if (id == R.id.nav_raw) {
                mCurrentNavId = id;
                switchFragment(Camera2RawFragment.newInstance());
                return true;
            }
            return false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BottomNavigationView nav = findViewById(R.id.navigation);
        nav.setOnNavigationItemSelectedListener(mOnNavigationItemSelectedListener);

        // Top-bar flash (left): cycle Off -> Auto -> On and route to the active fragment.
        mFlashButton = findViewById(R.id.flash);
        updateFlashIcon();
        mFlashButton.setOnClickListener(v -> {
            mFlashMode = (mFlashMode + 1) % 3;
            updateFlashIcon();
            applyFlashToActiveFragment(mFlashMode);
        });

        // Top-bar settings (right): open the standalone settings page.
        findViewById(R.id.settings).setOnClickListener(v -> openSettings());

        if (savedInstanceState == null) {
            switchFragment(Camera2BasicFragment.newInstance());
        }
    }

    // Updates the flash button tint to reflect the current mode (grey / white / yellow).
    private void updateFlashIcon() {
        int color = (mFlashMode == FlashControl.FLASH_ON) ? 0xFFFFFF00
                : (mFlashMode == FlashControl.FLASH_OFF) ? 0xFF808080 : 0xFFFFFFFF;
        mFlashButton.setColorFilter(color);
    }

    // The flash mode currently selected on the top bar; camera fragments read this when (re)building
    // their preview session so the active mode is always reflected after a tab switch.
    public int getFlashMode() {
        return mFlashMode;
    }

    // Applies the flash mode to whichever camera fragment is currently shown.
    private void applyFlashToActiveFragment(int mode) {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.container);
        if (f instanceof FlashControl) {
            FlashControl fc = (FlashControl) f;
            if (fc.isFlashSupported()) {
                fc.setFlashMode(mode);
            } else {
                Toast.makeText(this, R.string.flash_not_supported, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // Launches the standalone settings page and waits for it to finish so we can re-apply config.
    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivityForResult(intent, REQUEST_SETTINGS);
    }

    // When the user saves settings, re-open the active fragment so the new configuration takes
    // effect on the next camera (re)build.
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK) {
            switch (mCurrentNavId) {
                case R.id.nav_video:
                    switchFragment(Camera2VideoFragment.newInstance());
                    break;
                case R.id.nav_raw:
                    switchFragment(Camera2RawFragment.newInstance());
                    break;
                case R.id.nav_photo:
                default:
                    switchFragment(Camera2BasicFragment.newInstance());
                    break;
            }
        }
    }

    private void switchFragment(Fragment fragment) {
        FragmentManager fm = getSupportFragmentManager();
        Fragment current = fm.findFragmentById(R.id.container);
        // Use replace (not hide/show) so the outgoing Fragment goes through onPause
        // (closing its camera) and the incoming Fragment goes through onResume (opening its
        // camera). This avoids two Fragment instances holding the same CameraDevice at once.
        if (current != null && current.getClass().equals(fragment.getClass())) {
            return;
        }
        fm.beginTransaction()
                .replace(R.id.container, fragment)
                .commitNow();
    }
}

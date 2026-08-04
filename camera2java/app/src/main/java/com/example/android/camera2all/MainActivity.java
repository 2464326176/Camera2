/*
 * Integrated Camera2 app: Basic (photo) + Video + Raw.
 */

package com.example.android.camera2all;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private final BottomNavigationView.OnNavigationItemSelectedListener mOnNavigationItemSelectedListener
            = new BottomNavigationView.OnNavigationItemSelectedListener() {

        @Override
        public boolean onNavigationItemSelected(@NonNull android.view.MenuItem item) {
            int id = item.getItemId();
            if (id == R.id.nav_photo) {
                switchFragment(Camera2BasicFragment.newInstance());
                return true;
            } else if (id == R.id.nav_video) {
                switchFragment(Camera2VideoFragment.newInstance());
                return true;
            } else if (id == R.id.nav_raw) {
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

        if (savedInstanceState == null) {
            switchFragment(Camera2BasicFragment.newInstance());
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

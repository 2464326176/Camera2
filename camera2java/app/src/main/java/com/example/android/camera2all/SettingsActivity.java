/*
 * Standalone settings page (replaces the old settings dialog).
 *
 * Three clearly separated sections - 预览 / 拍照 / 视频 - each expose a size and a
 * format selector. All selections are persisted via SettingsManager on "保存" and
 * reloaded on every entry ("读取"). "恢复默认" resets every preference to its default.
 */

package com.example.android.camera2all;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * Persistent, non-dialog capture-configuration screen.
 */
public class SettingsActivity extends AppCompatActivity {

    private Spinner mSpPreviewSize;
    private Spinner mSpPreviewFormat;
    private Spinner mSpPhotoSize;
    private Spinner mSpPhotoFormat;
    private Spinner mSpVideoSize;
    private Spinner mSpVideoFormat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.settings_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.settings_page_title);
        }
        // Up / back simply discards any unsaved edits and returns.
        toolbar.setNavigationOnClickListener(v -> finish());

        mSpPreviewSize = findViewById(R.id.sp_preview_size);
        mSpPreviewFormat = findViewById(R.id.sp_preview_format);
        mSpPhotoSize = findViewById(R.id.sp_photo_size);
        mSpPhotoFormat = findViewById(R.id.sp_photo_format);
        mSpVideoSize = findViewById(R.id.sp_video_size);
        mSpVideoFormat = findViewById(R.id.sp_video_format);

        // Populate every control from the persisted configuration (read).
        initSpinners();

        Button save = findViewById(R.id.btn_save);
        save.setOnClickListener(v -> {
            SettingsManager.put(this, SettingsManager.KEY_PREVIEW_SIZE,
                    valueOf(mSpPreviewSize, R.array.preview_size_values));
            SettingsManager.put(this, SettingsManager.KEY_PREVIEW_FORMAT,
                    valueOf(mSpPreviewFormat, R.array.preview_format_values));
            SettingsManager.put(this, SettingsManager.KEY_PHOTO_SIZE,
                    valueOf(mSpPhotoSize, R.array.photo_size_values));
            SettingsManager.put(this, SettingsManager.KEY_PHOTO_FORMAT,
                    valueOf(mSpPhotoFormat, R.array.photo_format_values));
            SettingsManager.put(this, SettingsManager.KEY_VIDEO_SIZE,
                    valueOf(mSpVideoSize, R.array.video_size_values));
            SettingsManager.put(this, SettingsManager.KEY_VIDEO_FORMAT,
                    valueOf(mSpVideoFormat, R.array.video_format_values));
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });

        Button reset = findViewById(R.id.btn_reset);
        reset.setOnClickListener(v -> {
            SettingsManager.resetAll(this);
            initSpinners();
            Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
        });
    }

    // Reads the stored configuration and selects the matching spinner entry for each control.
    private void initSpinners() {
        setupSpinner(mSpPreviewSize, R.array.preview_size_entries, R.array.preview_size_values,
                SettingsManager.getPreviewSize(this));
        setupSpinner(mSpPreviewFormat, R.array.preview_format_entries, R.array.preview_format_values,
                SettingsManager.getPreviewFormat(this));
        setupSpinner(mSpPhotoSize, R.array.photo_size_entries, R.array.photo_size_values,
                SettingsManager.getPhotoSize(this));
        setupSpinner(mSpPhotoFormat, R.array.photo_format_entries, R.array.photo_format_values,
                SettingsManager.getPhotoFormat(this));
        setupSpinner(mSpVideoSize, R.array.video_size_entries, R.array.video_size_values,
                SettingsManager.getVideoSize(this));
        setupSpinner(mSpVideoFormat, R.array.video_format_entries, R.array.video_format_values,
                SettingsManager.getVideoFormat(this));
    }

    private void setupSpinner(@NonNull Spinner spinner, int entriesRes, int valuesRes,
                              @NonNull String currentValue) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, entriesRes,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        String[] values = getResources().getStringArray(valuesRes);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(currentValue)) {
                spinner.setSelection(i);
                break;
            }
        }
    }

    // Maps the spinner's current position back to its stored value string.
    @NonNull
    private String valueOf(@NonNull Spinner spinner, int valuesRes) {
        String[] values = getResources().getStringArray(valuesRes);
        int pos = spinner.getSelectedItemPosition();
        return (pos >= 0 && pos < values.length) ? values[pos] : values[0];
    }
}

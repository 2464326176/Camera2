package com.opencv.camera;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

/**
 * 精简相机设置：仅接线生效项（人脸 / 网格 / 快门声）。
 */
public class SettingsFragment extends Fragment {

    public static final String KEY_FACE_DETECT = "face_detect_enabled";
    public static final String KEY_GRID_DEFAULT = "grid_default";
    public static final String KEY_SOUND = "shutter_sound";

    private SharedPreferences prefs;

    public static SettingsFragment newInstance() {
        return new SettingsFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return createSettingsView();
    }

    private View createSettingsView() {
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setClickable(true);
        root.setFocusable(true);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars()
                            | WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.navigationBars());
            v.setPadding(0, bars.top + dp(12), 0, bars.bottom + dp(20));
            return insets;
        });

        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(16), dp(8), dp(16), dp(16));
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView back = new TextView(requireContext());
        back.setText("← 返回");
        back.setTextSize(16);
        back.setTextColor(0xFFFFFFFF);
        back.setPadding(dp(8), dp(8), dp(16), dp(8));
        back.setOnClickListener(v -> getParentFragmentManager().popBackStack());
        header.addView(back);

        TextView title = new TextView(requireContext());
        title.setText("设置");
        title.setTextSize(22);
        title.setTextColor(0xFFFFFFFF);
        header.addView(title);
        root.addView(header);

        root.addView(createSwitchItem("人脸检测", "照片预览时检测并框选人脸", KEY_FACE_DETECT, true));
        root.addView(createSwitchItem("默认网格线", "打开相机时显示构图辅助线", KEY_GRID_DEFAULT, false));
        root.addView(createSwitchItem("快门声音", "拍照时播放快门声", KEY_SOUND, true));

        TextView note = new TextView(requireContext());
        note.setText("闪光灯请在预览顶栏切换：关 / 开 / 自动");
        note.setTextSize(13);
        note.setTextColor(0x80FFFFFF);
        note.setPadding(dp(24), dp(24), dp(24), dp(8));
        root.addView(note);

        TextView version = new TextView(requireContext());
        version.setText("OpenCV Camera v1.0.0");
        version.setTextSize(12);
        version.setTextColor(0x60FFFFFF);
        version.setPadding(dp(24), dp(16), dp(24), dp(8));
        root.addView(version);

        return root;
    }

    private View createSwitchItem(String title, String subtitle, String key, boolean defaultValue) {
        LinearLayout item = new LinearLayout(requireContext());
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setPadding(dp(24), dp(16), dp(24), dp(16));
        item.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout textContainer = new LinearLayout(requireContext());
        textContainer.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(requireContext());
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTextColor(0xFFFFFFFF);
        textContainer.addView(titleView);

        TextView subtitleView = new TextView(requireContext());
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(13);
        subtitleView.setTextColor(0x80FFFFFF);
        subtitleView.setPadding(0, dp(4), 0, 0);
        textContainer.addView(subtitleView);

        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        item.addView(textContainer, textParams);

        SwitchCompat switchView = new SwitchCompat(requireContext());
        switchView.setChecked(prefs.getBoolean(key, defaultValue));
        switchView.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(key, isChecked).apply());
        item.addView(switchView);

        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(item);

        View divider = new View(requireContext());
        divider.setBackgroundColor(0x1AFFFFFF);
        container.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        return container;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}

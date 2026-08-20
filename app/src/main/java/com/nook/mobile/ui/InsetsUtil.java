package com.nook.mobile.ui;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 系统栏 inset 工具：Android 15 强制 edge-to-edge 下，
 * 为根布局补齐状态栏/导航栏内边距，避免内容被系统栏遮挡。
 */
public final class InsetsUtil {

    private InsetsUtil() {
    }

    /** 让 root 布局绘制在状态栏与导航栏下方。 */
    public static void applySystemBars(final View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // 只补上下内边距；返回 insets（不消费），避免影响 Snackbar 等在导航栏上的定位
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), bars.bottom);
            return insets;
        });
        // 确保立即触发一次 inset 分发
        root.requestApplyInsets();
    }
}

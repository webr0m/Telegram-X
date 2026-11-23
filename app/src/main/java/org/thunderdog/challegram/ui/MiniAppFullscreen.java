/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.app.Dialog;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.tool.Screen;

/** Reparents the existing page into a full-screen window without reloading its document. */
final class MiniAppFullscreen {
  private final BaseActivity activity;
  private final FrameLayout host;
  private final View content;
  private final Runnable onChanged, onClose;
  private Dialog dialog;
  private FrameLayout frame;
  private LinearLayout controls;
  private Insets safeInsets = Insets.NONE;
  private int previousOrientation;
  private boolean requested, active, destroyed;
  private boolean orientationLocked, orientationOwned;

  void setOrientationLocked (boolean locked) {
    orientationLocked = locked;
    updateOrientation();
  }

  private void updateOrientation () {
    boolean needed = !destroyed && active && (isFullscreen() || orientationLocked);
    if (needed) {
      if (!orientationOwned) {
        previousOrientation = activity.getRequestedOrientation();
        orientationOwned = true;
      }
      activity.setOrientation(orientationLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 ?
        ActivityInfo.SCREEN_ORIENTATION_LOCKED : ActivityInfo.SCREEN_ORIENTATION_USER);
    } else if (orientationOwned) {
      orientationOwned = false;
      activity.setOrientation(previousOrientation);
    }
  }

  MiniAppFullscreen (BaseActivity activity, FrameLayout host, View content, Runnable onChanged, Runnable onClose) {
    this.activity = activity;
    this.host = host;
    this.content = content;
    this.onChanged = onChanged;
    this.onClose = onClose;
  }

  boolean isFullscreen () {
    return dialog != null;
  }

  boolean isSupported () {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
  }

  boolean setRequested (boolean requested) {
    this.requested = requested;
    update();
    return !requested || !active || dialog != null;
  }

  void setActive (boolean active) {
    this.active = active;
    update();
  }

  private void update () {
    if (!destroyed && requested && active && dialog == null && isSupported()) {
      show();
    } else if ((!requested || !active || destroyed) && dialog != null) {
      dismiss();
    }
    updateOrientation();
  }

  private void show () {
    Dialog windowDialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
    windowDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    frame = new FrameLayout(activity);
    ((ViewGroup) content.getParent()).removeView(content);
    frame.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    controls = new LinearLayout(activity);
    Button exit = new Button(activity);
    exit.setAllCaps(false);
    exit.setText(Lang.getString(R.string.MiniAppExitFullscreen));
    exit.setOnClickListener(v -> setRequested(false));
    Button close = new Button(activity);
    close.setAllCaps(false);
    close.setText(Lang.getString(R.string.MiniAppClose));
    close.setOnClickListener(v -> onClose.run());
    controls.addView(exit);
    controls.addView(close);
    frame.addView(controls, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Screen.dp(48), Gravity.TOP | Gravity.END));
    windowDialog.setContentView(frame);
    windowDialog.setCancelable(false);
    windowDialog.setOnKeyListener((d, keyCode, event) -> {
      if (keyCode == KeyEvent.KEYCODE_BACK) {
        if (event.getAction() == KeyEvent.ACTION_UP) setRequested(false);
        return true;
      }
      return false;
    });
    windowDialog.setOnDismissListener(d -> {
      if (dialog == windowDialog) {
        dialog = null;
        restoreContent();
      }
    });
    Window window = windowDialog.getWindow();
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    WindowCompat.setDecorFitsSystemWindows(window, false);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      WindowManager.LayoutParams params = window.getAttributes();
      params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
      window.setAttributes(params);
    }
    ViewCompat.setOnApplyWindowInsetsListener(frame, (view, insets) -> {
      if (view != frame || dialog == null) return insets;
      safeInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
      Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
      frame.setPadding(0, 0, 0, ime.bottom);
      FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) controls.getLayoutParams();
      params.topMargin = safeInsets.top;
      params.rightMargin = safeInsets.right;
      params.leftMargin = safeInsets.left;
      controls.setLayoutParams(params);
      view.post(onChanged);
      return insets;
    });
    dialog = windowDialog;
    try {
      windowDialog.show();
      window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
      WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window, frame);
      bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      bars.hide(WindowInsetsCompat.Type.systemBars());
      updateOrientation();
      ViewCompat.requestApplyInsets(frame);
      frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> onChanged.run());
      onChanged.run();
    } catch (RuntimeException e) {
      requested = false;
      dismiss();
    }
  }

  private void dismiss () {
    Dialog old = dialog;
    dialog = null;
    restoreContent();
    old.dismiss();
  }

  private void restoreContent () {
    if (content.getParent() instanceof ViewGroup) {
      ((ViewGroup) content.getParent()).removeView(content);
    }
    host.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    updateOrientation();
    safeInsets = Insets.NONE;
    controls = null;
    frame = null;
    onChanged.run();
  }

  Insets safeArea (View viewport) {
    if (frame == null) return Insets.NONE;
    int[] page = new int[2], window = new int[2];
    viewport.getLocationInWindow(page);
    frame.getLocationInWindow(window);
    return Insets.of(Math.max(0, safeInsets.left - page[0] + window[0]),
      Math.max(0, safeInsets.top - page[1] + window[1]),
      Math.max(0, page[0] + viewport.getWidth() - window[0] - frame.getWidth() + safeInsets.right),
      Math.max(0, page[1] + viewport.getHeight() - window[1] - frame.getHeight() + safeInsets.bottom));
  }

  int contentTop (View viewport) {
    if (controls == null) return 0;
    int[] page = new int[2], button = new int[2];
    viewport.getLocationInWindow(page);
    controls.getLocationInWindow(button);
    return Math.max(0, button[1] + controls.getHeight() - page[1]);
  }

  void destroy () {
    destroyed = true;
    update();
  }
}

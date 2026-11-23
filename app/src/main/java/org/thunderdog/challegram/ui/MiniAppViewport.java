/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Compact mode measures the actual document, including after fullscreen reparenting. */
final class MiniAppViewport extends FrameLayout {
  private final View content;
  private boolean compact;

  MiniAppViewport (Context context, View content) {
    super(context);
    this.content = content;
    addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM));
  }

  void setCompact (boolean compact) {
    if (this.compact != compact) {
      this.compact = compact;
      requestLayout();
    }
  }

  boolean isCompact () { return compact; }

  @Override
  protected void onMeasure (int widthSpec, int heightSpec) {
    if (content.getParent() == this) {
      LayoutParams params = (LayoutParams) content.getLayoutParams();
      // Keep the controls and document usable when the keyboard reduces available height.
      params.height = compact ? Math.max(1, Math.round(MeasureSpec.getSize(heightSpec) * .62f)) : ViewGroup.LayoutParams.MATCH_PARENT;
      params.gravity = Gravity.BOTTOM;
    }
    super.onMeasure(widthSpec, heightSpec);
  }
}

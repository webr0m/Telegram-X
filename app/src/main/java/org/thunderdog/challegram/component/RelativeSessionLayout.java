/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 08/12/2016
 */
package org.thunderdog.challegram.component;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.RelativeLayout;

import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.user.RemoveHelper;
import org.thunderdog.challegram.core.Lang;

public class RelativeSessionLayout extends RelativeLayout implements RemoveHelper.RemoveDelegate {
  private RemoveHelper helper;
  private Runnable accessibilityRemoveAction;

  public RelativeSessionLayout (Context context) {
    super(context);
    helper = new RemoveHelper(this, R.drawable.baseline_remove_circle_24);
  }

  public void setAccessibilityRemoveAction (@Nullable Runnable action) {
    if (accessibilityRemoveAction != action) {
      accessibilityRemoveAction = action;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
      }
    }
  }

  @Override
  public void onInitializeAccessibilityNodeInfo (AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && accessibilityRemoveAction != null && isEnabled() && getVisibility() == View.VISIBLE && getAlpha() > 0f) {
      AccessibilityNodeInfoCompat.wrap(info).addAction(
        new AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.btn_terminateSession, Lang.getString(R.string.TerminateSession))
      );
    }
  }

  @Override
  public boolean performAccessibilityAction (int action, Bundle arguments) {
    if (action == R.id.btn_terminateSession && accessibilityRemoveAction != null && isEnabled() && getVisibility() == View.VISIBLE && getAlpha() > 0f) {
      accessibilityRemoveAction.run();
      return true;
    }
    return super.performAccessibilityAction(action, arguments);
  }

  public void draw (Canvas c) {
    helper.save(c);
    super.draw(c);
    helper.restore(c);
    helper.draw(c);
  }

  @Override
  public void setRemoveDx (float dx) {
    helper.setDx(dx);
  }

  @Override
  public void onRemoveSwipe () {
    helper.onSwipe();
  }
}

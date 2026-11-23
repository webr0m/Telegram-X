/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.widget.TextView;
import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Collections;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.NavigationStack;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;

/** Activity-owned live tabs. No WebView, authentication URL or account data is persisted. */
public final class MiniAppTabs implements NavigationStack.ChangeListener, BaseActivity.PasscodeListener, BaseActivity.SimpleStateListener {
  private static final int MAX_TABS = 4;
  private final BaseActivity activity;
  private final ArrayList<MiniAppController> tabs = new ArrayList<>();
  private final LinearLayout bar, tabButtons;
  private final Button manage;
  private final IdentityHashMap<MiniAppController, Bitmap> previews = new IdentityHashMap<>();
  private MiniAppController parking;
  private AlertDialog picker;
  private boolean destroyed;
  private int generation;
  private int reservedHeight;

  public MiniAppTabs (BaseActivity activity) {
    this.activity = activity;
    bar = new LinearLayout(activity);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setVisibility(View.GONE);
    manage = new Button(activity);
    manage.setAllCaps(false);
    manage.setOnClickListener(v -> show());
    bar.addView(manage, new LinearLayout.LayoutParams(Screen.dp(140), android.view.ViewGroup.LayoutParams.MATCH_PARENT));
    HorizontalScrollView strip = new HorizontalScrollView(activity);
    tabButtons = new LinearLayout(activity);
    tabButtons.setOrientation(LinearLayout.HORIZONTAL);
    strip.addView(tabButtons);
    bar.addView(strip, new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1));
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(48), Gravity.BOTTOM);
    params.setMargins(Screen.dp(8), 0, Screen.dp(8), Screen.dp(8));
    ((FrameLayout) activity.getContentView()).addView(bar, params);
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bar, (view, insets) -> {
      android.view.ViewGroup.MarginLayoutParams margins = (android.view.ViewGroup.MarginLayoutParams) bar.getLayoutParams();
      int bottom = Screen.dp(8) + insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom;
      if (margins.bottomMargin != bottom) {
        margins.bottomMargin = bottom;
        bar.setLayoutParams(margins);
        refresh();
      }
      return insets;
    });
    activity.navigation().getStack().addChangeListener(this);
    activity.addPasscodeListener(this);
    activity.addSimpleStateListener(this);
  }

  boolean minimize (MiniAppController app, Runnable afterParking) {
    if (destroyed || parking != null || app.isDestroyed() || !app.isFocused()) return false;
    prune();
    if (tabs.size() >= MAX_TABS) {
      UI.showToast(R.string.MiniAppTabsFull, android.widget.Toast.LENGTH_SHORT);
      return false;
    }
    parking = app;
    Bitmap preview = app.captureTabPreview();
    final int requestGeneration = generation;
    if (!activity.navigation().navigateBackRetainingCurrent(app, () -> {
      parking = null;
      if (destroyed || requestGeneration != generation || !app.canRestoreTab() || !activity.hasTdlib() || activity.currentTdlib() != app.tdlib()) {
        app.destroy();
      } else {
        tabs.add(app);
        if (preview != null) previews.put(app, preview);
        app.onMinimized();
      }
      refresh();
      if (afterParking != null && !destroyed && requestGeneration == generation) UI.post(afterParking);
    })) {
      parking = null;
      return false;
    }
    return true;
  }

  private void prune () {
    for (int i = tabs.size() - 1; i >= 0; i--) {
      MiniAppController app = tabs.get(i);
      if (!app.canRestoreTab() || !activity.hasTdlib() || app.tdlib() != activity.currentTdlib()) {
        tabs.remove(i);
        previews.remove(app);
        if (!app.isDestroyed()) app.destroy();
      }
    }
  }

  private void refresh () {
    if (destroyed) return;
    prune();
    ViewController<?> current = activity.navigation().getStack().getCurrent();
    manage.setText(Lang.getString(R.string.MiniAppTabsCount, Integer.toString(tabs.size())));
    rebuildStrip();
    boolean visible = !tabs.isEmpty() && !activity.isPasscodeShowing() && activity.getActivityState() == UI.State.RESUMED &&
      !(current instanceof MiniAppController) && !(current instanceof MiniAppPaymentController) &&
      !(current instanceof MiniAppPaymentProvider) && !(current instanceof MiniAppCardController);
    setBarVisible(visible);
  }

  private void setBarVisible (boolean visible) {
    bar.setVisibility(visible ? View.VISIBLE : View.GONE);
    int next = visible ? Screen.dp(48) + ((android.view.ViewGroup.MarginLayoutParams) bar.getLayoutParams()).bottomMargin : 0;
    if (next != reservedHeight) {
      reservedHeight = next;
      activity.navigation().setPersistentBottomInset(next);
    }
  }

  public void show () {
    refresh();
    if (destroyed || tabs.isEmpty() || activity.isPasscodeShowing() || activity.getActivityState() != UI.State.RESUMED) return;
    ArrayList<MiniAppController> snapshot = new ArrayList<>(tabs);
    dismissPicker();
    ScrollView scroll = new ScrollView(activity);
    LinearLayout cards = new LinearLayout(activity);
    cards.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(cards);
    for (MiniAppController app : snapshot) {
      LinearLayout card = new LinearLayout(activity);
      card.setOrientation(LinearLayout.VERTICAL);
      card.setPadding(Screen.dp(12), Screen.dp(8), Screen.dp(12), Screen.dp(8));
      Bitmap preview = previews.get(app);
      if (preview != null) {
        ImageView image = new ImageView(activity);
        image.setImageBitmap(preview);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription(Lang.getString(R.string.MiniAppTabPreview, app.tabTitle()));
        card.addView(image, new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(120)));
      }
      Button title = new Button(activity);
      title.setAllCaps(false);
      title.setText(app.tabTitle());
      title.setOnClickListener(v -> { dismissPicker(); restore(app); });
      title.setOnLongClickListener(v -> { showActions(app); return true; });
      card.addView(title);
      Button actions = new Button(activity);
      actions.setAllCaps(false);
      actions.setText(Lang.getString(R.string.MiniAppTabActions));
      actions.setOnClickListener(v -> showActions(app));
      card.addView(actions);
      cards.addView(card);
    }
    picker = new AlertDialog.Builder(activity).setTitle(Lang.getString(R.string.MiniAppTabs))
      .setView(scroll).setNeutralButton(Lang.getString(R.string.MiniAppTabsCloseAll), (d, which) -> closeGroup(null))
      .setNegativeButton(Lang.getString(R.string.Cancel), null).show();
  }

  private void rebuildStrip () {
    tabButtons.removeAllViews();
    for (MiniAppController app : tabs) {
      Button title = new Button(activity);
      title.setAllCaps(false);
      title.setText(app.tabTitle());
      title.setSingleLine(true);
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      title.setContentDescription(Lang.getString(R.string.MiniAppTabResume, app.tabTitle()));
      title.setOnClickListener(v -> restore(app));
      title.setOnLongClickListener(v -> { showActions(app); return true; });
      tabButtons.addView(title, new LinearLayout.LayoutParams(Screen.dp(160), Screen.dp(48)));
      Button close = new Button(activity);
      close.setText("×");
      close.setContentDescription(Lang.getString(R.string.MiniAppTabClose, app.tabTitle()));
      close.setOnClickListener(v -> close(app));
      tabButtons.addView(close, new LinearLayout.LayoutParams(Screen.dp(48), Screen.dp(48)));
    }
  }

  private void showActions (MiniAppController app) {
    dismissPicker();
    if (!tabs.contains(app) || activity.isPasscodeShowing()) return;
    ArrayList<String> labels = new ArrayList<>();
    ArrayList<Runnable> actions = new ArrayList<>();
    labels.add(Lang.getString(R.string.MiniAppResume)); actions.add(() -> restore(app));
    int index = tabs.indexOf(app);
    if (index > 0) { labels.add(Lang.getString(R.string.MiniAppTabMoveLeft)); actions.add(() -> move(app, -1)); }
    if (index + 1 < tabs.size()) { labels.add(Lang.getString(R.string.MiniAppTabMoveRight)); actions.add(() -> move(app, 1)); }
    labels.add(Lang.getString(R.string.MiniAppClose)); actions.add(() -> close(app));
    if (tabs.size() > 1) { labels.add(Lang.getString(R.string.MiniAppTabsCloseOthers)); actions.add(() -> closeGroup(app)); }
    picker = new AlertDialog.Builder(activity).setTitle(app.tabTitle()).setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
      .setNegativeButton(Lang.getString(R.string.Cancel), null).show();
  }

  private void move (MiniAppController app, int direction) {
    int index = tabs.indexOf(app), target = index + direction;
    if (index < 0 || target < 0 || target >= tabs.size()) return;
    Collections.swap(tabs, index, target);
    refresh();
    show();
  }

  private void closeGroup (MiniAppController keep) {
    ArrayList<MiniAppController> snapshot = new ArrayList<>(tabs);
    snapshot.remove(keep);
    if (snapshot.isEmpty() || activity.isPasscodeShowing()) return;
    dismissPicker();
    boolean unsaved = false;
    for (MiniAppController app : snapshot) unsaved |= app.needsCloseConfirmation();
    String message = Lang.getString(R.string.MiniAppTabsCloseConfirm, Integer.toString(snapshot.size()));
    if (unsaved) message += "\n\n" + Lang.getString(R.string.MiniAppCloseConfirm);
    picker = new AlertDialog.Builder(activity).setMessage(message)
      .setPositiveButton(Lang.getString(R.string.MiniAppClose), (d, which) -> {
        for (MiniAppController app : snapshot) if (tabs.remove(app)) { previews.remove(app); app.destroy(); }
        refresh();
      }).setNegativeButton(Lang.getString(R.string.Cancel), null).show();
  }

  private void restore (MiniAppController app) {
    prune();
    if (!tabs.contains(app) || !app.canRestoreTab() || activity.isPasscodeShowing()) { refresh(); return; }
    if (activity.navigation().getStack().getCurrent() instanceof MiniAppController current) {
      current.minimizeForTabSwitch(() -> { if (tabs.contains(app)) restore(app); });
      return;
    }
    app.onRestored();
    if (activity.navigation().navigateTo(app)) {
      tabs.remove(app);
      previews.remove(app);
    } else app.onMinimized();
    refresh();
  }

  private void close (MiniAppController app) {
    if (!tabs.contains(app)) return;
    Runnable close = () -> { tabs.remove(app); previews.remove(app); app.destroy(); refresh(); };
    if (app.needsCloseConfirmation()) {
      picker = new AlertDialog.Builder(activity).setMessage(Lang.getString(R.string.MiniAppCloseConfirm))
        .setPositiveButton(Lang.getString(R.string.MiniAppClose), (d, which) -> close.run())
        .setNegativeButton(Lang.getString(R.string.Cancel), null).show();
    } else close.run();
  }

  void discard (MiniAppController app) {
    if (tabs.remove(app)) { previews.remove(app); app.destroy(); refresh(); }
  }

  private void dismissPicker () {
    if (picker != null) { picker.dismiss(); picker = null; }
  }

  public void clear () {
    generation++;
    dismissPicker();
    ArrayList<MiniAppController> old = new ArrayList<>(tabs);
    tabs.clear();
    previews.clear();
    for (MiniAppController app : old) app.destroy();
    setBarVisible(false);
  }

  public void destroy () {
    destroyed = true;
    clear();
    activity.navigation().getStack().removeChangeListener(this);
    activity.removePasscodeListener(this);
    activity.removeSimpleStateListener(this);
    ((FrameLayout) activity.getContentView()).removeView(bar);
  }

  @Override
  public void onStackChanged (NavigationStack stack) {
    refresh();
  }

  @Override
  public void onPasscodeShowing (BaseActivity context, boolean isShowing) {
    if (isShowing) dismissPicker();
    refresh();
  }

  @Override
  public void onActivityStateChanged (BaseActivity context, int newState, int oldState) {
    if (newState != UI.State.RESUMED) dismissPicker();
    refresh();
  }
}

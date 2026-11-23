/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.thunderdog.challegram.ui;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.Collections;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.navigation.NavigationStack;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.AuthorizationListener;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;

/** Live pages belong to this activity; persisted entries only describe how to request a fresh session. */
public final class MiniAppTabs implements NavigationStack.ChangeListener, BaseActivity.PasscodeListener, BaseActivity.SimpleStateListener, AuthorizationListener {
  private static final int MAX_TABS = 4;
  private static final class Tab {
    MiniAppController live;
    MiniAppTabSnapshot saved;
    Bitmap preview;
    Tab(MiniAppController live) { this.live = live; }
    Tab(MiniAppTabSnapshot saved) { this.saved = saved; }
    String title() { return live != null ? live.tabTitle() : saved.title; }
    boolean unsaved() { return live != null && live.needsCloseConfirmation(); }
    MiniAppTabSnapshot descriptor() {
      if (live == null) return saved;
      try { return live.snapshot(); } catch (IllegalArgumentException ignored) { return null; }
    }
    void destroy() { preview = null; if (live != null) { live.destroy(); live = null; } }
  }
  private final BaseActivity activity;
  private final ArrayList<Tab> tabs = new ArrayList<>();
  private final LinearLayout bar, tabButtons;
  private final Button manage;
  private MiniAppController parking;
  private AlertDialog picker;
  private Tdlib observed;
  private long userId;
  private int generation, reservedHeight;
  private boolean destroyed;
  private Tab opening;

  public MiniAppTabs(BaseActivity activity) {
    this.activity = activity;
    bar = new LinearLayout(activity); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setVisibility(View.GONE);
    manage = new Button(activity); manage.setAllCaps(false); manage.setOnClickListener(v -> show());
    bar.addView(manage, new LinearLayout.LayoutParams(Screen.dp(140), -1));
    HorizontalScrollView strip = new HorizontalScrollView(activity);
    tabButtons = new LinearLayout(activity); tabButtons.setOrientation(LinearLayout.HORIZONTAL);
    strip.addView(tabButtons); bar.addView(strip, new LinearLayout.LayoutParams(0, -1, 1));
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, Screen.dp(48), Gravity.BOTTOM);
    params.setMargins(Screen.dp(8), 0, Screen.dp(8), Screen.dp(8));
    ((FrameLayout) activity.getContentView()).addView(bar, params);
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bar, (view, insets) -> {
      android.view.ViewGroup.MarginLayoutParams margins = (android.view.ViewGroup.MarginLayoutParams) bar.getLayoutParams();
      int bottom = Screen.dp(8) + insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom;
      if (margins.bottomMargin != bottom) { margins.bottomMargin = bottom; bar.setLayoutParams(margins); refresh(); }
      return insets;
    });
    activity.navigation().getStack().addChangeListener(this);
    activity.addPasscodeListener(this); activity.addSimpleStateListener(this);
    bindAccount();
  }
  private String storageKey() { return "miniapp_tabs_" + observed.accountId() + "_" + userId; }
  boolean hasRoomForNewLaunch () { bindAccount(); prune(); return tabs.size() < 5; }
  public void bindAccount() {
    if (destroyed || !activity.hasTdlib()) return;
    Tdlib next = activity.currentTdlib();
    if (observed != next) {
      if (observed != null) observed.listeners().removeAuthorizationChangeListener(this);
      observed = next; observed.listeners().addAuthorizationChangeListener(this);
    }
    if (!next.isAuthorized() || next.myUserId() == 0 || userId == next.myUserId()) return;
    userId = next.myUserId();
    for (MiniAppTabSnapshot saved : MiniAppTabSnapshot.decode(activity.getSharedPreferences(storageKey(), 0).getString("tabs", "[]"))) tabs.add(new Tab(saved));
    refresh();
  }
  private void persist() {
    if (observed == null || userId == 0 || !observed.isAuthorized() || observed.myUserId() != userId) return;
    ArrayList<MiniAppTabSnapshot> descriptors = new ArrayList<>();
    for (Tab tab : tabs) {
      MiniAppTabSnapshot saved = tab.descriptor();
      if (saved != null && descriptors.stream().noneMatch(previous -> previous.sameLaunch(saved))) descriptors.add(saved);
    }
    // Include the last active page as well as four minimized pages; only metadata is restored.
    ArrayList<ViewController<?>> stack = activity.navigation().getStack().getAll();
    for (int i = stack.size() - 1; i >= 0; i--) {
      if (stack.get(i) instanceof MiniAppController app && app.tdlib() == observed && app.canRestoreTab()) {
        try {
          MiniAppTabSnapshot active = app.snapshot();
          descriptors.removeIf(saved -> saved.sameLaunch(active));
          if (descriptors.size() < 5) descriptors.add(0, active);
        } catch (IllegalArgumentException ignored) { }
        break;
      }
    }
    String key = storageKey(), data = MiniAppTabSnapshot.encode(descriptors);
    Background.instance().post(() -> activity.getSharedPreferences(key, 0).edit().putString("tabs", data).commit());
  }
  boolean minimize(MiniAppController app, Runnable afterParking) {
    bindAccount();
    if (destroyed || parking != null || app.isDestroyed() || !app.isFocused()) return false;
    prune();
    if (tabs.size() >= MAX_TABS) { UI.showToast(R.string.MiniAppTabsFull, android.widget.Toast.LENGTH_SHORT); return false; }
    parking = app;
    Bitmap preview = app.captureTabPreview(); final int expected = generation;
    if (!activity.navigation().navigateBackRetainingCurrent(app, () -> {
      if (parking == app) parking = null;
      if (destroyed || expected != generation || !app.canRestoreTab() || !activity.hasTdlib() || activity.currentTdlib() != app.tdlib()) app.destroy();
      else { Tab tab = new Tab(app); tab.preview = preview; tabs.add(tab); app.onMinimized(); persist(); }
      refresh();
      if (afterParking != null && !destroyed && expected == generation) UI.post(afterParking);
    })) { parking = null; return false; }
    return true;
  }
  private void prune() {
    for (int i = tabs.size() - 1; i >= 0; i--) {
      Tab tab = tabs.get(i);
      if (tab.live != null && (!tab.live.canRestoreTab() || !activity.hasTdlib() || tab.live.tdlib() != activity.currentTdlib())) { tabs.remove(i); tab.destroy(); }
    }
  }
  private void refresh() {
    if (destroyed) return;
    prune();
    ViewController<?> current = activity.navigation().getStack().getCurrent();
    manage.setText(Lang.getString(R.string.MiniAppTabsCount, Integer.toString(tabs.size())));
    tabButtons.removeAllViews();
    for (Tab tab : tabs) {
      Button title = button(tab.title(), () -> restore(tab)); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      title.setContentDescription(Lang.getString(R.string.MiniAppTabResume, tab.title()));
      title.setOnLongClickListener(v -> { actions(tab); return true; });
      tabButtons.addView(title, new LinearLayout.LayoutParams(Screen.dp(160), Screen.dp(48)));
      Button close = button("×", () -> close(tab)); close.setContentDescription(Lang.getString(R.string.MiniAppTabClose, tab.title()));
      tabButtons.addView(close, new LinearLayout.LayoutParams(Screen.dp(48), Screen.dp(48)));
    }
    setBarVisible(!tabs.isEmpty() && !activity.isPasscodeShowing() && activity.getActivityState() == UI.State.RESUMED &&
      !(current instanceof MiniAppController) && !(current instanceof MiniAppPaymentController) && !(current instanceof MiniAppPaymentProvider) &&
      !(current instanceof MiniAppCardController) && !(current instanceof MiniAppReceiptController) && !(current instanceof MiniAppPaymentHistoryController));
  }
  private Button button(String title, Runnable action) {
    Button button = new Button(activity); button.setAllCaps(false); button.setText(title); button.setOnClickListener(v -> action.run()); return button;
  }
  void onFreshSessionOpened(MiniAppController app) {
    if (destroyed || !activity.hasTdlib() || app.tdlib() != activity.currentTdlib()) return;
    try {
      MiniAppTabSnapshot now = app.snapshot();
      boolean changed = tabs.removeIf(tab -> tab.live == null && tab.saved.sameLaunch(now));
      if (opening != null && opening.saved != null && opening.saved.botId == now.botId && opening.saved.kind == 3) changed |= tabs.remove(opening);
      if (opening != null && !tabs.contains(opening)) opening = null;
      if (changed) { persist(); refresh(); }
    } catch (IllegalArgumentException ignored) { }
  }
  private void setBarVisible(boolean visible) {
    bar.setVisibility(visible ? View.VISIBLE : View.GONE);
    int next = visible ? Screen.dp(48) + ((android.view.ViewGroup.MarginLayoutParams) bar.getLayoutParams()).bottomMargin : 0;
    if (next != reservedHeight) { reservedHeight = next; activity.navigation().setPersistentBottomInset(next); }
  }
  public void show() {
    bindAccount(); refresh();
    if (destroyed || tabs.isEmpty() || activity.isPasscodeShowing() || activity.getActivityState() != UI.State.RESUMED) return;
    dismissPicker();
    ScrollView scroll = new ScrollView(activity); LinearLayout cards = new LinearLayout(activity); cards.setOrientation(LinearLayout.VERTICAL); scroll.addView(cards);
    for (Tab tab : new ArrayList<>(tabs)) {
      if (tab.preview != null) {
        ImageView image = new ImageView(activity); image.setImageBitmap(tab.preview); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription(Lang.getString(R.string.MiniAppTabPreview, tab.title())); cards.addView(image, new LinearLayout.LayoutParams(-1, Screen.dp(120)));
      }
      if (tab.live == null) { TextView hint = new TextView(activity); hint.setText(Lang.getString(R.string.MiniAppTabNewSession)); cards.addView(hint); }
      cards.addView(button(tab.title(), () -> { dismissPicker(); restore(tab); }));
      cards.addView(button(Lang.getString(R.string.MiniAppTabActions), () -> actions(tab)));
    }
    picker = new AlertDialog.Builder(activity).setTitle(Lang.getString(R.string.MiniAppTabs)).setView(scroll)
      .setNeutralButton(Lang.getString(R.string.MiniAppTabsCloseAll), (d, which) -> closeGroup(null)).setNegativeButton(Lang.getString(R.string.Cancel), null).show();
  }
  private void actions(Tab tab) {
    dismissPicker(); if (!tabs.contains(tab) || activity.isPasscodeShowing()) return;
    ArrayList<String> labels = new ArrayList<>(); ArrayList<Runnable> actions = new ArrayList<>();
    labels.add(Lang.getString(R.string.MiniAppResume)); actions.add(() -> restore(tab));
    int index = tabs.indexOf(tab);
    if (index > 0) { labels.add(Lang.getString(R.string.MiniAppTabMoveLeft)); actions.add(() -> move(tab, -1)); }
    if (index + 1 < tabs.size()) { labels.add(Lang.getString(R.string.MiniAppTabMoveRight)); actions.add(() -> move(tab, 1)); }
    labels.add(Lang.getString(R.string.MiniAppClose)); actions.add(() -> close(tab));
    if (tabs.size() > 1) { labels.add(Lang.getString(R.string.MiniAppTabsCloseOthers)); actions.add(() -> closeGroup(tab)); }
    picker = new AlertDialog.Builder(activity).setTitle(tab.title()).setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
      .setNegativeButton(Lang.getString(R.string.Cancel), null).show();
  }
  private void move(Tab tab, int direction) {
    int index = tabs.indexOf(tab), target = index + direction;
    if (index < 0 || target < 0 || target >= tabs.size()) return;
    Collections.swap(tabs, index, target); persist(); refresh(); show();
  }
  private void closeGroup(Tab keep) {
    ArrayList<Tab> selected = new ArrayList<>(tabs); selected.remove(keep);
    if (selected.isEmpty() || activity.isPasscodeShowing()) return;
    dismissPicker();
    picker = new AlertDialog.Builder(activity).setMessage(Lang.getString(R.string.MiniAppTabsCloseConfirm, Integer.toString(selected.size())))
      .setPositiveButton(Lang.getString(R.string.MiniAppClose), (d, which) -> {
        for (Tab tab : selected) if (tabs.remove(tab)) tab.destroy(); persist(); refresh();
      }).setNegativeButton(Lang.getString(R.string.Cancel), null).show();
  }
  private void restore(Tab tab) {
    prune(); if (!tabs.contains(tab) || activity.isPasscodeShowing() || activity.getActivityState() != UI.State.RESUMED) return;
    if (parking != null) return;
    if (activity.navigation().getStack().getCurrent() instanceof MiniAppController current && tab.live != null) {
      exchange(tab, current, tab.live); return;
    }
    if (tab.live != null) {
      tab.live.onRestored();
      if (activity.navigation().navigateTo(tab.live)) { tabs.remove(tab); tab.preview = null; persist(); }
      else tab.live.onMinimized();
      refresh(); return;
    }
    ViewController<?> owner = activity.navigation().getStack().getCurrent();
    if (owner == null || !owner.isFocused() || observed == null || observed.myUserId() != userId) return;
    final int expected = generation;
    picker = new AlertDialog.Builder(activity).setMessage(Lang.getString(R.string.MiniAppTabRestoreConfirm))
      .setPositiveButton(Lang.getString(R.string.MiniAppResume), (d, which) -> {
        if (expected != generation || !tabs.contains(tab) || !owner.isFocused()) return;
        Runnable loadBot = () -> observed.send(new TdApi.GetUser(tab.saved.botId), (bot, error) -> UI.post(() -> {
          if (expected != generation || !tabs.contains(tab) || !owner.isFocused() || activity.currentTdlib() != observed) return;
          if (error != null) { UI.showError(error); return; }
          opening = tab;
          MiniAppController.Navigator navigator = incoming -> {
            if (expected != generation || !tabs.contains(tab) || !owner.isFocused() || activity.isPasscodeShowing() || activity.getActivityState() != UI.State.RESUMED) return false;
            return owner instanceof MiniAppController current ? exchange(tab, current, incoming) : owner.navigateTo(incoming);
          };
          if (!MiniAppController.reopenTab(owner, tab.saved, navigator)) { opening = null; UI.showToast(R.string.MiniAppUnavailable, android.widget.Toast.LENGTH_SHORT); }
        }));
        if (tab.saved.chatId == 0) loadBot.run();
        else observed.send(new TdApi.GetChat(tab.saved.chatId), (chat, error) -> UI.post(() -> {
          if (expected != generation || !tabs.contains(tab) || !owner.isFocused() || observed != activity.currentTdlib()) return;
          if (error != null) UI.showError(error); else loadBot.run();
        }));
      }).setNegativeButton(Lang.getString(R.string.Cancel), null).show();
  }
  private boolean exchange(Tab selected, MiniAppController outgoing, MiniAppController incoming) {
    if (destroyed || parking != null || activity.getActivityState() != UI.State.RESUMED || !outgoing.canParkTab() || !tabs.contains(selected) || outgoing == incoming ||
        incoming.isDestroyed() || !outgoing.canRestoreTab() || activity.currentTdlib() != outgoing.tdlib() || incoming.tdlib() != observed) return false;
    Tab replacement = new Tab(outgoing); replacement.preview = outgoing.captureTabPreview();
    MiniAppTabSwap<Tab> swap = MiniAppTabSwap.begin(tabs, selected, replacement);
    if (swap == null) return false;
    int expected = generation;
    parking = outgoing;
    outgoing.prepareTabExchange(); incoming.onRestored();
    if (!activity.navigation().navigateToRetainingCurrent(outgoing, incoming, () -> {
      if (parking == outgoing) parking = null;
      if (destroyed || expected != generation || !outgoing.canRestoreTab()) outgoing.destroy();
      else outgoing.onMinimized();
      selected.live = null; selected.preview = null;
      if (opening == selected) opening = null;
      persist(); refresh();
    })) {
      swap.rollback(); parking = null; outgoing.onRestored(); incoming.onMinimized(); refresh(); return false;
    }
    swap.commit();
    persist(); refresh(); return true;
  }
  private void close(Tab tab) {
    if (!tabs.contains(tab)) return;
    Runnable close = () -> { tabs.remove(tab); tab.destroy(); persist(); refresh(); };
    if (tab.unsaved()) picker = new AlertDialog.Builder(activity).setMessage(Lang.getString(R.string.MiniAppCloseConfirm))
      .setPositiveButton(Lang.getString(R.string.MiniAppClose), (d, which) -> close.run()).setNegativeButton(Lang.getString(R.string.Cancel), null).show();
    else close.run();
  }
  void discard(MiniAppController app) {
    if (parking == app) return; // The transition callback releases its page after detaching the view.
    for (Tab tab : new ArrayList<>(tabs)) if (tab.live == app) { tabs.remove(tab); tab.destroy(); persist(); refresh(); }
  }
  private void dismissPicker() { if (picker != null) { picker.dismiss(); picker = null; } }
  public void clear() {
    persist(); generation++; dismissPicker(); opening = null; parking = null;
    ArrayList<Tab> old = new ArrayList<>(tabs); tabs.clear();
    for (Tab tab : old) tab.destroy();
    if (observed != null) observed.listeners().removeAuthorizationChangeListener(this);
    observed = null; userId = 0; setBarVisible(false);
  }
  public void destroy() {
    if (destroyed) return;
    destroyed = true; clear();
    activity.navigation().getStack().removeChangeListener(this); activity.removePasscodeListener(this); activity.removeSimpleStateListener(this);
    ((FrameLayout) activity.getContentView()).removeView(bar);
  }
  @Override public void onStackChanged(NavigationStack stack) { persist(); refresh(); }
  @Override public void onPasscodeShowing(BaseActivity context, boolean showing) { if (showing) dismissPicker(); refresh(); }
  @Override public void onActivityStateChanged(BaseActivity context, int state, int old) { if (state != UI.State.RESUMED) { dismissPicker(); persist(); } else bindAccount(); refresh(); }
  @Override public void onAuthorizationStateChanged(TdApi.AuthorizationState state) {
    Tdlib expected = observed;
    UI.post(() -> {
      if (observed != expected || destroyed) return;
      if (state instanceof TdApi.AuthorizationStateReady) bindAccount();
      else if (state instanceof TdApi.AuthorizationStateLoggingOut) {
        String key = userId == 0 ? null : storageKey(); clear();
        if (key != null) Background.instance().post(() -> activity.getSharedPreferences(key, 0).edit().clear().commit());
      }
    });
  }
}

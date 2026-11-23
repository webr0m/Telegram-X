/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.app.AlertDialog;
import android.view.View;
import android.widget.*;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.BackHeaderButton;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.support.ViewSupport;
import java.util.ArrayList;
import java.util.Collections;

public final class MiniAppPaymentHistoryController extends ViewController<Void> implements org.thunderdog.challegram.BaseActivity.PasscodeListener {
  private LinearLayout content;
  private final Runnable changed = this::render;
  private final long userId;
  private AlertDialog menu;
  public MiniAppPaymentHistoryController (Context context, Tdlib tdlib) { super(context, tdlib); userId = tdlib.myUserId(); }
  @Override public int getId () { return R.id.controller_miniAppPaymentHistory; }
  @Override public CharSequence getName () { return Lang.getString(R.string.MiniAppPaymentHistory); }
  @Override protected int getBackButton () { return BackHeaderButton.TYPE_BACK; }
  @Override public boolean shouldDisallowScreenshots () { return true; }
  @Override protected View onCreateView (Context context) {
    ScrollView scroll = new ScrollView(context); content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
    ViewSupport.setThemedBackground(content, ColorId.filling, this); scroll.addView(content);
    tdlib.ui().payments().addListener(changed); context().addPasscodeListener(this); render(); tdlib.ui().payments().checkPending(); return scroll;
  }
  private void render () {
    if (content == null || isDestroyed()) return;
    content.removeAllViews();
    if (!tdlib.isAuthorized() || tdlib.myUserId() != userId || !context().hasTdlib() || context().currentTdlib() != tdlib) return;
    Button check = new Button(context()); check.setText(Lang.getString(R.string.MiniAppPaymentCheck));
    check.setOnClickListener(v -> tdlib.ui().payments().checkPending()); content.addView(check);
    ArrayList<MiniAppPaymentRecord> records = tdlib.ui().payments().list(); Collections.reverse(records);
    if (records.isEmpty()) { TextView empty = new TextView(context()); empty.setText(Lang.getString(R.string.MiniAppPaymentsEmpty)); empty.setTextColor(Theme.textAccentColor()); content.addView(empty); }
    for (MiniAppPaymentRecord record : records) {
      Button row = new Button(context()); row.setAllCaps(false);
      int state = record.status.equals("paid") ? R.string.MiniAppPaymentPaid : record.status.equals("failed") ? R.string.MiniAppPaymentFailed : R.string.MiniAppPaymentPending;
      row.setText(record.title + "\n" + me.vkryl.core.CurrencyUtils.buildAmount(record.currency, record.amount) + "\n" + Lang.getString(state));
      MiniAppPaymentScan scan = tdlib.ui().payments().scan(record);
      if (scan != null && record.receiptMessageId == 0 && !record.status.equals("failed")) {
        int label = tdlib.ui().payments().searching(record) ? R.string.MiniAppHistorySearching : scan.retry ? R.string.MiniAppHistoryRetry :
          scan.complete ? R.string.MiniAppHistoryComplete : R.string.MiniAppHistoryPaused;
        row.append("\n" + Lang.getString(label, Lang.formatNumber(scan.messages)));
      }
      row.setOnClickListener(v -> {
        if (record.receiptMessageId != 0) MiniAppReceiptController.open(this, record.receiptChatId, record.receiptMessageId);
        else actions(record);
      }); content.addView(row);
    }
  }
  private void actions (MiniAppPaymentRecord record) {
    if (!isFocused() || context().isPasscodeShowing() || tdlib.myUserId() != userId) return;
    ArrayList<String> titles = new ArrayList<>(); ArrayList<Runnable> actions = new ArrayList<>();
    if (!record.status.equals("failed")) {
      if (tdlib.ui().payments().searching(record)) {
        titles.add(Lang.getString(R.string.MiniAppHistoryPause)); actions.add(() -> tdlib.ui().payments().cancelHistory(record));
      } else {
        titles.add(Lang.getString(R.string.MiniAppHistoryContinue)); actions.add(() -> {
          if (!tdlib.ui().payments().searchHistory(record, false)) org.thunderdog.challegram.tool.UI.showToast(R.string.MiniAppHistoryBusy, android.widget.Toast.LENGTH_SHORT);
        });
        titles.add(Lang.getString(R.string.MiniAppHistoryRestart)); actions.add(() -> tdlib.ui().payments().searchHistory(record, true));
      }
    }
    titles.add(Lang.getString(R.string.MiniAppHistoryOpenSeller));
    actions.add(() -> tdlib.ui().openChat(this, tgx.td.ChatId.fromUserId(record.sellerId), new org.thunderdog.challegram.telegram.TdlibUi.ChatOpenParameters().keepStack()));
    menu = new AlertDialog.Builder(context()).setTitle(record.title).setItems(titles.toArray(new String[0]), (d, which) -> {
      if (!isDestroyed() && tdlib.isAuthorized() && tdlib.myUserId() == userId && context().currentTdlib() == tdlib) actions.get(which).run();
    }).setNegativeButton(Lang.getString(R.string.Cancel), null).show();
  }
  @Override public void onActivityPause () { super.onActivityPause(); if (menu != null) menu.dismiss(); }
  @Override public void onPasscodeShowing (org.thunderdog.challegram.BaseActivity activity, boolean showing) { if (showing && menu != null) menu.dismiss(); }
  @Override public void destroy () { if (isDestroyed()) return; if (menu != null) menu.dismiss(); context().removePasscodeListener(this); tdlib.ui().payments().removeListener(changed); super.destroy(); }
}

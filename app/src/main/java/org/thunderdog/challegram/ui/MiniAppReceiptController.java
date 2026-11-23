/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.BackHeaderButton;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Screen;
import java.util.concurrent.TimeUnit;

public final class MiniAppReceiptController extends ViewController<Void> {
  private final long chatId, messageId, userId;
  private LinearLayout content;
  private boolean loading;
  private MiniAppReceiptController (Context context, Tdlib tdlib, long chatId, long messageId) {
    super(context, tdlib); this.chatId = chatId; this.messageId = messageId; userId = tdlib.myUserId();
  }
  public static void open (ViewController<?> owner, long chat, long message) {
    if (owner == null || owner.isDestroyed() || !owner.tdlib().isAuthorized() || chat == 0 || message <= 0) return;
    MiniAppReceiptController receipt = new MiniAppReceiptController(owner.context(), owner.tdlib(), chat, message);
    if (!owner.navigateTo(receipt)) receipt.destroy();
  }
  @Override public int getId () { return R.id.controller_miniAppReceipt; }
  @Override public CharSequence getName () { return Lang.getString(R.string.MiniAppReceipt); }
  @Override protected int getBackButton () { return BackHeaderButton.TYPE_BACK; }
  @Override public boolean shouldDisallowScreenshots () { return true; }
  @Override protected View onCreateView (Context context) {
    ScrollView scroll = new ScrollView(context);
    content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
    int padding = Screen.dp(20); content.setPadding(padding, padding, padding, padding);
    ViewSupport.setThemedBackground(content, ColorId.filling, this); scroll.addView(content);
    load(); return scroll;
  }
  private void text (String value) {
    if (value == null || value.isEmpty()) return;
    TextView view = new TextView(context()); view.setText(value); view.setTextIsSelectable(true);
    view.setTextColor(Theme.textAccentColor()); addThemeTextAccentColorListener(view);
    view.setPadding(0, Screen.dp(6), 0, Screen.dp(6));
    content.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
  }
  private void load () {
    if (loading) return;
    loading = true; content.removeAllViews(); text(Lang.getString(R.string.LoadingInformation));
    tdlib.send(new TdApi.GetPaymentReceipt(chatId, messageId), (receipt, error) -> runOnUiThreadOptional(() -> {
      loading = false;
      if (!tdlib.isAuthorized() || tdlib.myUserId() != userId || !context().hasTdlib() || context().currentTdlib() != tdlib) return;
      content.removeAllViews();
      if (error != null) {
        text(Lang.getString(R.string.MiniAppReceiptUnavailable));
        Button retry = new Button(context()); retry.setText(Lang.getString(R.string.RetryChatList)); retry.setOnClickListener(v -> load()); content.addView(retry);
        return;
      }
      text(receipt.productInfo.title); text(receipt.productInfo.description.text);
      text(Lang.getString(R.string.MiniAppPaymentSeller, tdlib.cache().userName(receipt.sellerBotUserId)));
      text(Lang.getDatestamp(receipt.date, TimeUnit.SECONDS));
      if (receipt.type instanceof TdApi.PaymentReceiptTypeStars stars) {
        text(Lang.getString(R.string.MiniAppPaymentStars, Lang.formatNumber(stars.starCount)));
        text(Lang.getString(R.string.MiniAppReceiptTransaction, stars.transactionId));
      } else if (receipt.type instanceof TdApi.PaymentReceiptTypeRegular regular) {
        if (regular.invoice.isTest) text(Lang.getString(R.string.MiniAppPaymentTest));
        String currency = regular.invoice.currency;
        try {
          MiniAppInvoiceState.add(MiniAppInvoiceState.add(MiniAppInvoiceState.total(regular.invoice.priceParts),
            regular.shippingOption == null ? 0 : MiniAppInvoiceState.total(regular.shippingOption.priceParts)), regular.tipAmount);
        } catch (IllegalArgumentException e) { text(Lang.getString(R.string.MiniAppReceiptUnavailable)); return; }
        for (TdApi.LabeledPricePart part : regular.invoice.priceParts) text(part.label + ": " + money(currency, part.amount));
        long total = MiniAppInvoiceState.total(regular.invoice.priceParts);
        if (regular.shippingOption != null) {
          long shipping = MiniAppInvoiceState.total(regular.shippingOption.priceParts);
          text(regular.shippingOption.title + ": " + money(currency, shipping)); total = MiniAppInvoiceState.add(total, shipping);
        }
        if (regular.tipAmount != 0) text(Lang.getString(R.string.MiniAppPaymentTip) + ": " + money(currency, regular.tipAmount));
        text(Lang.getString(R.string.MiniAppReceiptTotal, money(currency, MiniAppInvoiceState.add(total, regular.tipAmount))));
        if (regular.invoice.subscriptionPeriod > 0) text(Lang.getString(R.string.MiniAppReceiptPeriod, Lang.formatNumber(regular.invoice.subscriptionPeriod)));
        text(regular.credentialsTitle);
        if (regular.orderInfo != null) {
          text(regular.orderInfo.name); text(regular.orderInfo.phoneNumber); text(regular.orderInfo.emailAddress);
          TdApi.Address address = regular.orderInfo.shippingAddress;
          if (address != null) { text(address.streetLine1); text(address.streetLine2); text(address.city); text(address.state); text(address.postalCode); text(address.countryCode); }
        }
      }
    }));
  }
  private static String money (String currency, long amount) {
    if (PremiumCatalog.currencyExponent(currency) < 0) return Lang.getString(R.string.MiniAppReceiptRawAmount, Long.toString(amount), currency);
    return PremiumCatalog.formatAmount(new TdApi.PremiumPaymentOption(currency, amount, 0, 1, "", null), Lang.dateFormatLocale());
  }
}

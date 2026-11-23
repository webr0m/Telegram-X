/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.BackHeaderButton;
import org.thunderdog.challegram.navigation.NavigationController;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Keyboard;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import me.vkryl.core.lambda.RunnableData;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Collects card details locally and sends them directly to the selected payment provider. */
final class MiniAppCardController extends ViewController<Void> {
  private final TdApi.PaymentProvider provider;
  private final boolean test, save;
  private final RunnableData<TdApi.InputCredentialsNew> callback;
  private final long userId;
  private final ArrayList<EditText> fields = new ArrayList<>();
  private LinearLayout content;
  private EditText number, expiry, cvc, name, country, postal;
  private TextView error;
  private Button next;
  private Call call;
  private boolean tokenizing, submitted;

  MiniAppCardController (Context context, Tdlib tdlib, TdApi.PaymentProvider provider, boolean test, boolean save,
                         RunnableData<TdApi.InputCredentialsNew> callback) {
    super(context, tdlib);
    this.provider = provider; this.test = test; this.save = save; this.callback = callback; this.userId = tdlib.myUserId();
  }
  @Override public int getId () { return R.id.controller_miniAppCard; }
  @Override public CharSequence getName () { return provider instanceof TdApi.PaymentProviderStripe ? "Stripe" : "Smart Glocal"; }
  @Override protected int getBackButton () { return BackHeaderButton.TYPE_BACK; }
  @Override public boolean shouldDisallowScreenshots () { return true; }
  @Override public boolean canSlideBackFrom (NavigationController navigation, float x, float y) { return false; }

  private boolean validAccount () { return !isDestroyed() && tdlib.isAuthorized() && tdlib.myUserId() == userId && context().hasTdlib() && context().currentTdlib() == tdlib; }

  @Override protected View onCreateView (Context context) {
    ScrollView scroll = new ScrollView(context);
    content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    int margin = org.thunderdog.challegram.tool.Screen.dp(20);
    content.setPadding(margin, margin, margin, margin);
    ViewSupport.setThemedBackground(content, ColorId.filling, this);
    if (Build.VERSION.SDK_INT >= 26) content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    scroll.addView(content);
    text(Lang.getString(R.string.MiniAppCardProvider, getName().toString()));
    if (test) text(Lang.getString(R.string.MiniAppPaymentTest));
    number = field(R.string.MiniAppCardNumber, InputType.TYPE_CLASS_PHONE, 26);
    expiry = field(R.string.MiniAppCardExpiry, InputType.TYPE_CLASS_DATETIME, 7);
    cvc = field(R.string.MiniAppCardCvc, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, 4);
    if (provider instanceof TdApi.PaymentProviderStripe stripe) {
      if (stripe.needCardholderName) name = field(R.string.MiniAppCardName, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME, 256);
      if (stripe.needCountry) country = field(R.string.MiniAppPaymentCountry, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 2);
      if (stripe.needPostalCode) postal = field(R.string.MiniAppPaymentPostal, InputType.TYPE_CLASS_TEXT, 32);
    }
    error = text("");
    next = new Button(context);
    next.setAllCaps(false);
    next.setText(Lang.getString(R.string.MiniAppPaymentReview));
    next.setOnClickListener(v -> tokenize());
    content.addView(next);
    return scroll;
  }

  private TextView text (CharSequence value) {
    TextView view = new TextView(context()); view.setText(value); view.setTextColor(Theme.textAccentColor());
    addThemeTextAccentColorListener(view); content.addView(view); return view;
  }
  private EditText field (int resource, int type, int max) {
    text(Lang.getString(resource));
    EditText view = new EditText(context()); view.setHint(Lang.getString(resource)); view.setInputType(type); view.setSingleLine(true);
    view.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)}); view.setSaveEnabled(false);
    view.setTextColor(Theme.textAccentColor()); view.setHintTextColor(Theme.getColor(ColorId.textPlaceholder));
    addThemeTextAccentColorListener(view); content.addView(view); fields.add(view); return view;
  }
  private static String value (EditText view) { return view == null ? "" : view.getText().toString(); }
  private void setBusy (boolean busy) { tokenizing = busy; next.setEnabled(!busy); for (EditText field : fields) field.setEnabled(!busy); }

  private void tokenize () {
    if (tokenizing || submitted || !isFocused() || !validAccount()) return;
    try {
      Calendar now = Calendar.getInstance();
      TdApi.PaymentProviderStripe stripe = provider instanceof TdApi.PaymentProviderStripe ? (TdApi.PaymentProviderStripe) provider : null;
      MiniAppCardTokens.Card card = new MiniAppCardTokens.Card(value(number), value(expiry), value(cvc), value(name), value(country), value(postal),
        stripe != null && stripe.needCardholderName, stripe != null && stripe.needCountry, stripe != null && stripe.needPostalCode,
        now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1);
      Call request = MiniAppCardTokens.client().newCall(MiniAppCardTokens.request(provider, card, test));
      Keyboard.hide(getValue());
      setBusy(true);
      error.setText("");
      call = request;
      request.enqueue(new Callback() {
        @Override public void onFailure (Call failed, IOException failure) { complete(failed, null); }
        @Override public void onResponse (Call completed, Response response) {
          String token = null;
          try (Response result = response) {
            ResponseBody body = result.body();
            if (result.isSuccessful() && body != null && body.contentLength() <= 65536) {
              okio.BufferedSource input = body.source();
              if (!input.request(65537)) token = MiniAppCardTokens.credentials(provider, input.readUtf8(), test);
            }
          } catch (Exception ignored) { }
          complete(completed, token);
        }
      });
    } catch (IllegalArgumentException e) {
      EditText invalid = switch (String.valueOf(e.getMessage())) { case "number" -> number; case "expiry" -> expiry; case "cvc" -> cvc;
        case "name" -> name; case "country" -> country; case "postal" -> postal; default -> null; };
      error.setText(Lang.getString(R.string.MiniAppCardInvalid));
      if (invalid != null) { invalid.setError(Lang.getString(R.string.MiniAppCardInvalid)); invalid.requestFocus(); }
    } catch (Exception e) { error.setText(Lang.getString(R.string.MiniAppCardTokenFailed)); }
  }

  private void complete (Call completed, String token) {
    runOnUiThreadOptional(() -> {
      if (call != completed || !validAccount()) return;
      call = null;
      setBusy(false);
      cvc.setText("");
      if (token == null) { error.setText(Lang.getString(R.string.MiniAppCardTokenFailed)); return; }
      submitted = true;
      for (EditText field : fields) field.setText("");
      callback.runWithData(new TdApi.InputCredentialsNew(token, save));
      if (isFocused()) navigateBack();
    });
  }
  @Override protected void onFocusStateChanged () { super.onFocusStateChanged(); if (isFocused() && submitted) org.thunderdog.challegram.tool.UI.post(this::navigateBack); }
  @Override public void destroy () {
    if (isDestroyed()) return;
    if (call != null) { call.cancel(); call = null; }
    for (EditText field : fields) field.setText("");
    super.destroy();
  }
}

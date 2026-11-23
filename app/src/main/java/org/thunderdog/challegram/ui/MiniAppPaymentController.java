/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.app.AlertDialog;
import android.os.Build;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
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
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Keyboard;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import java.util.ArrayList;
import java.util.Locale;
import com.google.android.gms.wallet.IsReadyToPayRequest;
import com.google.android.gms.wallet.PaymentDataRequest;
import com.google.android.gms.wallet.PaymentsClient;
import com.google.android.gms.wallet.Wallet;
import com.google.android.gms.wallet.WalletConstants;
import me.vkryl.core.lambda.RunnableData;

/** Native review and explicit authorization for invoice links and Mini App openInvoice. */
public final class MiniAppPaymentController extends ViewController<Void> {
  private final TdApi.InputInvoice invoice;
  private final long accountUserId;
  private final RunnableData<String> callback;
  private final MiniAppInvoiceState state = new MiniAppInvoiceState();
  private TdApi.PaymentForm form;
  private LinearLayout content;
  private TextView status;
  private Button pay;
  private AlertDialog dialog;
  private boolean busy;
  private EditText name, phone, email, country, region, city, street1, street2, postal, tip;
  private CheckBox acceptTerms, saveInfo, saveCredentials;
  private TdApi.ValidatedOrderInfo order;
  private TdApi.ShippingOption shipping;
  private TdApi.InputCredentials credentials;
  private long tipAmount;
  private PaymentsClient googlePay;
  private MiniAppGooglePayLauncher googlePayLauncher;
  private boolean googlePayReady, googlePayPending;

  public MiniAppPaymentController (Context context, Tdlib tdlib, String slug, RunnableData<String> callback) {
    this(context, tdlib, new TdApi.InputInvoiceName(slug), callback);
  }

  private MiniAppPaymentController (Context context, Tdlib tdlib, TdApi.InputInvoice invoice, RunnableData<String> callback) {
    super(context, tdlib);
    this.invoice = invoice;
    this.accountUserId = tdlib.myUserId();
    this.callback = callback;
  }

  public static void open (ViewController<?> owner, String slug) {
    if (!MiniAppInvoiceState.validSlug(slug) || !owner.tdlib().isAuthorized() || owner.isDestroyed()) return;
    MiniAppPaymentController checkout = new MiniAppPaymentController(owner.context(), owner.tdlib(), slug, status -> { });
    if (owner.navigationController() == null || !owner.navigationController().navigateTo(checkout)) checkout.destroy();
  }

  public static void openMessage (ViewController<?> owner, TdApi.Message message) {
    if (owner.isDestroyed() || !owner.tdlib().isAuthorized() || tgx.td.ChatId.isSecret(message.chatId)) return;
    MiniAppPaymentController checkout = new MiniAppPaymentController(owner.context(), owner.tdlib(),
      new TdApi.InputInvoiceMessage(message.chatId, message.id), status -> { });
    if (owner.navigationController() == null || !owner.navigationController().navigateTo(checkout)) checkout.destroy();
  }

  @Override public int getId () { return R.id.controller_miniAppPayment; }
  @Override public CharSequence getName () { return Lang.getString(R.string.MiniAppPayment); }
  @Override protected int getBackButton () { return BackHeaderButton.TYPE_BACK; }
  @Override public boolean shouldDisallowScreenshots () { return true; }
  @Override public boolean canSlideBackFrom (NavigationController navigation, float x, float y) { return false; }

  @Override protected View onCreateView (Context context) {
    ScrollView scroll = new ScrollView(context);
    content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(Screen.dp(20), Screen.dp(12), Screen.dp(20), Screen.dp(24));
    ViewSupport.setThemedBackground(content, ColorId.filling, this);
    scroll.addView(content);
    status = text(Lang.getString(R.string.LoadingInformation));
    if (invoice instanceof TdApi.InputInvoiceName named && !MiniAppInvoiceState.validSlug(named.name)) {
      state.failed();
      status.setText(R.string.MiniAppPaymentFailed);
    } else {
      tdlib.send(new TdApi.GetPaymentForm(invoice, null), (result, error) -> runOnUiThreadOptional(() -> {
        if (!validAccount()) return;
        if (error != null) { state.failed(); status.setText(R.string.MiniAppPaymentFailed); UI.showError(error); }
        else { form = result; render(); }
      }));
    }
    return scroll;
  }

  private boolean validAccount () {
    return !isDestroyed() && tdlib.isAuthorized() && tdlib.myUserId() == accountUserId &&
      context().hasTdlib() && context().currentTdlib() == tdlib;
  }

  private TextView text (CharSequence value) {
    TextView view = new TextView(context());
    view.setText(value);
    view.setTextColor(Theme.textAccentColor());
    addThemeTextAccentColorListener(view);
    view.setPadding(0, Screen.dp(8), 0, Screen.dp(8));
    content.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return view;
  }

  private Button button (CharSequence title, Runnable action) {
    Button button = new Button(context());
    button.setAllCaps(false);
    button.setText(title);
    button.setOnClickListener(v -> { if (!busy && validAccount() && isFocused()) action.run(); });
    content.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return button;
  }

  private EditText field (int label, int inputType) {
    text(Lang.getString(label));
    EditText input = new EditText(context());
    input.setHint(Lang.getString(label));
    input.setInputType(inputType);
    input.setSingleLine(true);
    input.setTextColor(Theme.textAccentColor());
    input.setHintTextColor(Theme.getColor(ColorId.textPlaceholder));
    addThemeTextAccentColorListener(input);
    content.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return input;
  }

  private CheckBox check (int label) {
    CheckBox view = new CheckBox(context());
    view.setText(Lang.getString(label));
    view.setTextColor(Theme.textAccentColor());
    addThemeTextAccentColorListener(view);
    content.addView(view);
    return view;
  }

  private void render () {
    googlePayReady = false;
    googlePay = null;
    name = phone = email = country = region = city = street1 = street2 = postal = tip = null;
    acceptTerms = saveInfo = saveCredentials = null;
    order = null;
    shipping = null;
    credentials = null;
    tipAmount = 0;
    content.removeAllViews();
    text(form.productInfo.title);
    text(form.productInfo.description.text);
    text(Lang.getString(R.string.MiniAppPaymentSeller, tdlib.cache().userName(form.sellerBotUserId)));
    if (form.type instanceof TdApi.PaymentFormTypeStars stars) {
      if (stars.starCount <= 0) { text(Lang.getString(R.string.MiniAppPaymentFailed)); return; }
      text(starPrice(stars.starCount));
      button(Lang.getString(R.string.MiniAppPaymentBalance), () -> navigateTo(new StarsController(context(), tdlib, true, stars.starCount, form.productInfo.title)));
    } else if (form.type instanceof TdApi.PaymentFormTypeStarSubscription subscription) {
      if (subscription.pricing.starCount <= 0 || subscription.pricing.period <= 0) { text(Lang.getString(R.string.MiniAppPaymentFailed)); return; }
      text(Lang.getString(R.string.MiniAppPaymentSubscription, starPrice(subscription.pricing.starCount), Lang.formatNumber(subscription.pricing.period)));
      acceptTerms = check(R.string.MiniAppPaymentAcceptSubscription);
      button(Lang.getString(R.string.MiniAppPaymentBalance), () -> navigateTo(new StarsController(context(), tdlib, true, subscription.pricing.starCount, form.productInfo.title)));
    } else if (form.type instanceof TdApi.PaymentFormTypeRegular regular) {
      if (PremiumCatalog.currencyExponent(regular.invoice.currency) < 0) { text(Lang.getString(R.string.MiniAppPaymentUnsupported)); return; }
      renderRegular(regular);
      initializeGooglePay(regular);
    } else { text(Lang.getString(R.string.MiniAppPaymentUnsupported)); return; }
    status = text("");
    pay = button(Lang.getString(R.string.MiniAppPaymentReview), this::prepare);
  }

  private void renderRegular (TdApi.PaymentFormTypeRegular regular) {
    TdApi.Invoice bill = regular.invoice;
    if (bill.isTest) text(Lang.getString(R.string.MiniAppPaymentTest));
    for (TdApi.LabeledPricePart part : bill.priceParts) text(part.label + ": " + money(part.amount));
    if (bill.needName) name = field(R.string.MiniAppPaymentName, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
    if (bill.needPhoneNumber) phone = field(R.string.MiniAppPaymentPhone, InputType.TYPE_CLASS_PHONE);
    if (bill.needEmailAddress) email = field(R.string.MiniAppPaymentEmail, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
    if (bill.needShippingAddress) {
      country = field(R.string.MiniAppPaymentCountry, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
      region = field(R.string.MiniAppPaymentRegion, InputType.TYPE_CLASS_TEXT);
      city = field(R.string.MiniAppPaymentCity, InputType.TYPE_CLASS_TEXT);
      street1 = field(R.string.MiniAppPaymentStreet1, InputType.TYPE_CLASS_TEXT);
      street2 = field(R.string.MiniAppPaymentStreet2, InputType.TYPE_CLASS_TEXT);
      postal = field(R.string.MiniAppPaymentPostal, InputType.TYPE_CLASS_TEXT);
    }
    if (bill.sendPhoneNumberToProvider || bill.sendEmailAddressToProvider) text(Lang.getString(R.string.MiniAppPaymentProviderData));
    if (hasOrderFields()) {
      saveInfo = check(R.string.MiniAppPaymentSaveInfo);
      if (regular.savedOrderInfo != null) button(Lang.getString(R.string.MiniAppPaymentUseSavedInfo), () -> fill(regular.savedOrderInfo));
    }
    if (bill.maxTipAmount > 0) {
      tip = field(R.string.MiniAppPaymentTip, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
      text(Lang.getString(R.string.MiniAppPaymentMaxTip, money(bill.maxTipAmount)));
      if (bill.suggestedTipAmounts != null) for (long amount : bill.suggestedTipAmounts) {
        if (amount > 0 && amount <= bill.maxTipAmount) button(money(amount), () -> tip.setText(java.math.BigDecimal.valueOf(amount, PremiumCatalog.currencyExponent(bill.currency)).toPlainString()));
      }
    }
    if (!bill.termsOfServiceUrl.isEmpty() || !bill.recurringPaymentTermsOfServiceUrl.isEmpty()) {
      if (!bill.termsOfServiceUrl.isEmpty()) button(Lang.getString(R.string.MiniAppPaymentReadTerms), () -> openUrl(bill.termsOfServiceUrl));
      if (!bill.recurringPaymentTermsOfServiceUrl.isEmpty()) button(Lang.getString(R.string.MiniAppPaymentRecurringTerms), () -> openUrl(bill.recurringPaymentTermsOfServiceUrl));
      acceptTerms = check(R.string.MiniAppPaymentAcceptTerms);
    }
    if (bill.subscriptionPeriod > 0) text(Lang.getString(R.string.MiniAppPaymentSubscription, money(MiniAppInvoiceState.total(bill.priceParts)), Lang.formatNumber(bill.subscriptionPeriod)));
    if (bill.subscriptionPeriod > 0 && acceptTerms == null) acceptTerms = check(R.string.MiniAppPaymentAcceptSubscription);
    if (regular.canSaveCredentials && !regular.needPassword) saveCredentials = check(R.string.MiniAppPaymentSaveMethod);
  }

  private boolean hasOrderFields () { return name != null || phone != null || email != null || country != null; }
  private static String value (EditText view) { return view == null ? "" : view.getText().toString().trim(); }
  private static void fill (EditText field, String value) { if (field != null) field.setText(value); }
  private void fill (TdApi.OrderInfo info) {
    fill(name, info.name); fill(phone, info.phoneNumber); fill(email, info.emailAddress);
    if (info.shippingAddress != null) {
      TdApi.Address address = info.shippingAddress;
      fill(country, address.countryCode); fill(region, address.state); fill(city, address.city);
      fill(street1, address.streetLine1); fill(street2, address.streetLine2); fill(postal, address.postalCode);
    }
  }

  private String money (long amount) {
    String currency = ((TdApi.PaymentFormTypeRegular) form.type).invoice.currency;
    return PremiumCatalog.formatAmount(new TdApi.PremiumPaymentOption(currency, amount, 0, 1, "", null), Lang.dateFormatLocale());
  }
  private String starPrice (long amount) { return Lang.getString(R.string.MiniAppPaymentStars, Lang.formatNumber(amount)); }
  private long total () {
    if (form.type instanceof TdApi.PaymentFormTypeStars stars) return stars.starCount;
    if (form.type instanceof TdApi.PaymentFormTypeStarSubscription subscription) return subscription.pricing.starCount;
    return MiniAppInvoiceState.add(MiniAppInvoiceState.add(MiniAppInvoiceState.total(((TdApi.PaymentFormTypeRegular) form.type).invoice.priceParts),
      shipping != null ? MiniAppInvoiceState.total(shipping.priceParts) : 0), tipAmount);
  }
  private String price () { return form.type instanceof TdApi.PaymentFormTypeRegular ? money(total()) : starPrice(total()); }

  private void setBusy (boolean busy) {
    this.busy = busy;
    if (pay != null) pay.setEnabled(!busy);
    if (content != null) setInputsEnabled(content, !busy);
  }

  private static void setInputsEnabled (ViewGroup group, boolean enabled) {
    for (int i = 0; i < group.getChildCount(); i++) {
      View view = group.getChildAt(i);
      if (view instanceof EditText || view instanceof CheckBox) view.setEnabled(enabled);
      else if (view instanceof ViewGroup child) setInputsEnabled(child, enabled);
    }
  }

  private void prepare () {
    if (acceptTerms != null && !acceptTerms.isChecked()) { status.setText(R.string.MiniAppPaymentTermsRequired); return; }
    Keyboard.hide(getValue());
    if (!(form.type instanceof TdApi.PaymentFormTypeRegular regular)) { confirm(); return; }
    try { tipAmount = MiniAppInvoiceState.parseTip(value(tip), PremiumCatalog.currencyExponent(regular.invoice.currency), regular.invoice.maxTipAmount); }
    catch (IllegalArgumentException e) { status.setText(R.string.MiniAppPaymentInvalidTip); return; }
    credentials = null;
    order = null;
    shipping = null;
    TdApi.Address address = country == null ? null : new TdApi.Address(value(country).toUpperCase(Locale.US), value(region), value(city), value(street1), value(street2), value(postal));
    TdApi.OrderInfo info = hasOrderFields() ? new TdApi.OrderInfo(value(name), value(phone), value(email), address) : null;
    setBusy(true);
    tdlib.send(new TdApi.ValidateOrderInfo(invoice, info, saveInfo != null && saveInfo.isChecked()), (result, error) -> runOnUiThreadOptional(() -> {
      setBusy(false);
      if (!validAccount() || !isFocused()) return;
      if (error != null) { UI.showError(error); return; }
      order = result;
      if (result.shippingOptions != null && result.shippingOptions.length > 0) {
        String[] choices = new String[result.shippingOptions.length];
        for (int i = 0; i < choices.length; i++) choices[i] = result.shippingOptions[i].title + " · " + money(MiniAppInvoiceState.total(result.shippingOptions[i].priceParts));
        dialog = new AlertDialog.Builder(context()).setTitle(Lang.getString(R.string.MiniAppPaymentShipping))
          .setItems(choices, (d, which) -> { shipping = result.shippingOptions[which]; chooseMethod(regular); }).show();
      } else if (regular.invoice.isFlexible) status.setText(R.string.MiniAppPaymentNoShipping);
      else chooseMethod(regular);
    }));
  }

  private void chooseMethod (TdApi.PaymentFormTypeRegular regular) {
    if (!validAccount() || !isFocused()) return;
    ArrayList<String> labels = new ArrayList<>();
    ArrayList<Runnable> actions = new ArrayList<>();
    if (regular.paymentProvider instanceof TdApi.PaymentProviderStripe || regular.paymentProvider instanceof TdApi.PaymentProviderSmartGlocal) {
      labels.add(regular.paymentProvider instanceof TdApi.PaymentProviderStripe ? "Stripe" : "Smart Glocal");
      actions.add(() -> openCard(regular));
    }
    if (googlePayReady && googlePay != null && regular.paymentProvider instanceof TdApi.PaymentProviderStripe) {
      labels.add("Google Pay");
      actions.add(() -> openGooglePay(regular));
    }
    if (regular.paymentProvider instanceof TdApi.PaymentProviderOther provider && MiniAppController.httpsOrigin(provider.url) != null) {
      labels.add(tdlib.cache().userName(regular.paymentProviderUserId));
      actions.add(() -> openProvider(provider.url));
    }
    if (regular.additionalPaymentOptions != null) for (TdApi.PaymentOption option : regular.additionalPaymentOptions) {
      if (MiniAppController.httpsOrigin(option.url) != null) { labels.add(option.title); actions.add(() -> openProvider(option.url)); }
    }
    if (regular.savedCredentials != null) for (TdApi.SavedCredentials saved : regular.savedCredentials) {
      labels.add(saved.title); actions.add(() -> useSaved(saved));
    }
    if (labels.isEmpty()) { status.setText(R.string.MiniAppPaymentUnsupported); return; }
    dialog = new AlertDialog.Builder(context()).setTitle(Lang.getString(R.string.MiniAppPaymentMethod))
      .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
      .setNegativeButton(Lang.getString(R.string.Cancel), null).show();
  }

  private void openProvider (String url) {
    MiniAppPaymentProvider provider = new MiniAppPaymentProvider(context(), tdlib, url, saveCredentials != null && saveCredentials.isChecked(), result -> {
      if (!validAccount()) return;
      credentials = result;
      addOneShotFocusListener(this::confirm);
    });
    if (!navigationController().navigateTo(provider)) provider.destroy();
  }

  private void openCard (TdApi.PaymentFormTypeRegular regular) {
    MiniAppCardController card = new MiniAppCardController(context(), tdlib, regular.paymentProvider, regular.invoice.isTest,
      saveCredentials != null && saveCredentials.isChecked(), result -> {
        if (!validAccount()) return;
        credentials = result;
        addOneShotFocusListener(this::confirm);
      });
    if (!navigationController().navigateTo(card)) card.destroy();
  }

  private void initializeGooglePay (TdApi.PaymentFormTypeRegular regular) {
    if (Build.VERSION.SDK_INT < 19 || !(regular.paymentProvider instanceof TdApi.PaymentProviderStripe stripe) ||
        !MiniAppCardTokens.stripeKey(stripe.publishableKey, regular.invoice.isTest)) return;
    try {
      TdApi.PaymentForm expected = form;
      googlePay = Wallet.getPaymentsClient(context(), new Wallet.WalletOptions.Builder()
        .setEnvironment(regular.invoice.isTest ? WalletConstants.ENVIRONMENT_TEST : WalletConstants.ENVIRONMENT_PRODUCTION).build());
      IsReadyToPayRequest readiness = IsReadyToPayRequest.fromJson(MiniAppGooglePay.readiness(stripe).toString());
      googlePay.isReadyToPay(readiness).addOnCompleteListener(context(), task -> {
        if (validAccount() && form == expected) googlePayReady = task.isSuccessful() && Boolean.TRUE.equals(task.getResult());
      });
    } catch (Exception | LinkageError ignored) { googlePay = null; googlePayReady = false; }
  }

  private void openGooglePay (TdApi.PaymentFormTypeRegular regular) {
    if (!googlePayReady || googlePay == null || busy || !validAccount() || !isFocused() ||
        !(regular.paymentProvider instanceof TdApi.PaymentProviderStripe stripe)) return;
    try {
      TdApi.PaymentForm expected = form;
      final boolean save = saveCredentials != null && saveCredentials.isChecked();
      PaymentDataRequest request = PaymentDataRequest.fromJson(MiniAppGooglePay.payment(stripe, regular.invoice.isTest, regular.invoice.currency,
        PremiumCatalog.currencyExponent(regular.invoice.currency), total(), tdlib.cache().userName(form.sellerBotUserId)).toString());
      if (request == null) throw new IllegalArgumentException("Invalid Wallet request");
      googlePayPending = true;
      setBusy(true);
      if (googlePayLauncher != null) googlePayLauncher.destroy();
      googlePayLauncher = new MiniAppGooglePayLauncher(context(), (resultCode, resultJson) -> {
        googlePayPending = false;
        setBusy(false);
        if (!validAccount() || form != expected) return;
        if (resultCode == MiniAppGooglePay.CANCELLED) { status.setText(R.string.MiniAppGooglePayCancelled); return; }
        if (resultCode != MiniAppGooglePay.SUCCESS || resultJson == null) { status.setText(R.string.MiniAppGooglePayFailed); return; }
        try {
          credentials = new TdApi.InputCredentialsNew(MiniAppGooglePay.credentials(stripe, resultJson, regular.invoice.isTest), save);
          status.setText("");
          addOneShotFocusListener(this::confirm);
        } catch (Exception ignored) { status.setText(R.string.MiniAppGooglePayFailed); }
      });
      googlePayLauncher.launch(googlePay.loadPaymentData(request));
    } catch (Exception | LinkageError ignored) {
      if (googlePayLauncher != null) { googlePayLauncher.destroy(); googlePayLauncher = null; }
      googlePayPending = false;
      setBusy(false);
      status.setText(R.string.MiniAppGooglePayFailed);
    }
  }

  private void useSaved (TdApi.SavedCredentials saved) {
    setBusy(true);
    tdlib.send(new TdApi.GetTemporaryPasswordState(), (result, error) -> runOnUiThreadOptional(() -> {
      setBusy(false);
      if (!validAccount() || !isFocused()) return;
      if (error != null) { UI.showError(error); return; }
      if (result.hasPassword && result.validFor > 0) { credentials = new TdApi.InputCredentialsSaved(saved.id); confirm(); return; }
      EditText password = new EditText(context());
      password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
      password.setHint(Lang.getString(R.string.MiniAppPaymentPassword));
      dialog = new AlertDialog.Builder(context()).setTitle(Lang.getString(R.string.MiniAppPaymentPassword)).setView(password)
        .setPositiveButton(Lang.getString(R.string.Continue), (d, which) -> {
          String secret = password.getText().toString();
          password.setText("");
          if (!validAccount() || !isFocused()) return;
          setBusy(true);
          tdlib.send(new TdApi.CreateTemporaryPassword(secret, 60), (state, failure) -> runOnUiThreadOptional(() -> {
            setBusy(false);
            if (!validAccount() || !isFocused()) return;
            if (failure != null) UI.showError(failure);
            else if (state.hasPassword && state.validFor > 0) { credentials = new TdApi.InputCredentialsSaved(saved.id); confirm(); }
          }));
        }).setNegativeButton(Lang.getString(R.string.Cancel), (d, which) -> password.setText("")).show();
      dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
      dialog.setOnDismissListener(d -> password.setText(""));
    }));
  }

  private void confirm () {
    if (!validAccount() || !isFocused() || busy || state.isFinished()) return;
    if (acceptTerms != null && !acceptTerms.isChecked()) { status.setText(R.string.MiniAppPaymentTermsRequired); return; }
    String amount;
    try {
      if (total() <= 0) throw new IllegalArgumentException("Invalid total");
      amount = price();
    } catch (IllegalArgumentException e) { status.setText(R.string.MiniAppPaymentFailed); return; }
    String review = Lang.getString(R.string.MiniAppPaymentConfirm, amount, form.productInfo.title, tdlib.cache().userName(form.sellerBotUserId));
    if (shipping != null) review += "\n\n" + shipping.title + " · " + money(MiniAppInvoiceState.total(shipping.priceParts));
    if (form.type instanceof TdApi.PaymentFormTypeStarSubscription subscription) review += "\n\n" + Lang.getString(R.string.MiniAppPaymentSubscription, amount, Lang.formatNumber(subscription.pricing.period));
    if (form.type instanceof TdApi.PaymentFormTypeRegular regular && regular.invoice.subscriptionPeriod > 0) review += "\n\n" + Lang.getString(R.string.MiniAppPaymentSubscription, amount, Lang.formatNumber(regular.invoice.subscriptionPeriod));
    dialog = new AlertDialog.Builder(context()).setTitle(Lang.getString(R.string.MiniAppPayment)).setMessage(review)
      .setPositiveButton(Lang.getString(R.string.MiniAppPaymentPay, amount), (d, which) -> submit())
      .setNegativeButton(Lang.getString(R.string.Cancel), null).show();
  }

  private void submit () {
    if (!validAccount() || !isFocused() || busy || acceptTerms != null && !acceptTerms.isChecked() ||
        form.type instanceof TdApi.PaymentFormTypeRegular && credentials == null || !state.begin()) return;
    setBusy(true);
    status.setText(R.string.MiniAppPaymentProcessing);
    TdApi.InputCredentials submittedCredentials = credentials;
    credentials = null;
    tdlib.send(new TdApi.SendPaymentForm(invoice, form.id, order != null ? order.orderInfoId : "", shipping != null ? shipping.id : "", submittedCredentials, tipAmount),
      (result, error) -> runOnUiThreadOptional(() -> {
        if (!validAccount()) return;
        setBusy(false);
        if (error != null) {
          state.failed();
          status.setText(R.string.MiniAppPaymentFailed);
          UI.showError(error);
          if (error.message.contains("FORM_EXPIRED")) {
            setBusy(true);
            tdlib.send(new TdApi.GetPaymentForm(invoice, null), (fresh, failure) -> runOnUiThreadOptional(() -> {
              setBusy(false);
              if (!validAccount()) return;
              if (failure != null) UI.showError(failure);
              else { form = fresh; render(); }
            }));
          }
        } else if (result.success) finish("paid");
        else {
          state.pending();
          status.setText(R.string.MiniAppPaymentPending);
          pay.setEnabled(false);
          if (MiniAppController.httpsOrigin(result.verificationUrl) != null) button(Lang.getString(R.string.MiniAppPaymentVerify), () -> openUrl(result.verificationUrl));
          button(Lang.getString(R.string.MiniAppPaymentDone), () -> finish("pending"));
        }
      }));
  }

  private void openUrl (String url) {
    if (MiniAppController.httpsOrigin(url) != null) tdlib.ui().openUrl(this, url, new TdlibUi.UrlOpenParameters().controller(this).requireOpenPrompt());
  }

  private void finish (String status) {
    if (state.complete(status)) callback.runWithData(status);
    navigateBack();
  }

  @Override public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (commit && !busy) finish(state.closeStatus());
    return true;
  }

  @Override public void destroy () {
    if (isDestroyed()) return;
    if (googlePayLauncher != null) googlePayLauncher.destroy();
    if (dialog != null) dialog.dismiss();
    if (state.complete(state.closeStatus())) callback.runWithData(state.result());
    credentials = null;
    form = null;
    super.destroy();
  }
}

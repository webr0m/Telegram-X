/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewCompat;
import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.BackHeaderButton;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import java.util.Collections;
import me.vkryl.core.lambda.RunnableData;

/** A separate origin-scoped provider page. It has no Mini App bridge or init data. */
final class MiniAppPaymentProvider extends ViewController<Void> {
  private final String url;
  private final RunnableData<TdApi.InputCredentialsNew> onCredentials;
  private final boolean save;
  private WebView page;
  private boolean submitted;
  private final long accountUserId;

  MiniAppPaymentProvider (Context context, Tdlib tdlib, String url, boolean save, RunnableData<TdApi.InputCredentialsNew> callback) {
    super(context, tdlib);
    this.url = url;
    this.save = save;
    this.onCredentials = callback;
    this.accountUserId = tdlib.myUserId();
  }

  @Override public int getId () { return R.id.controller_miniAppPaymentProvider; }
  @Override public CharSequence getName () { return Lang.getString(R.string.MiniAppPaymentMethod); }
  @Override protected int getBackButton () { return BackHeaderButton.TYPE_BACK; }
  @Override public boolean shouldDisallowScreenshots () { return true; }

  @SuppressLint({"SetJavaScriptEnabled", "RestrictedApi"})
  @Override protected View onCreateView (Context context) {
    String origin = MiniAppController.httpsOrigin(url);
    if (origin == null || !androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER) ||
        !androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
      android.widget.TextView unavailable = new android.widget.TextView(context);
      unavailable.setText(Lang.getString(R.string.MiniAppWebViewRequired));
      return unavailable;
    }
    page = new WebView(context);
    page.getSettings().setJavaScriptEnabled(true);
    page.getSettings().setDomStorageEnabled(true);
    page.getSettings().setAllowFileAccess(false);
    page.getSettings().setAllowContentAccess(false);
    if (Build.VERSION.SDK_INT >= 16) {
      page.getSettings().setAllowFileAccessFromFileURLs(false);
      page.getSettings().setAllowUniversalAccessFromFileURLs(false);
    }
    if (Build.VERSION.SDK_INT >= 21) page.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    page.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading (WebView view, String next) {
        return !"https".equalsIgnoreCase(android.net.Uri.parse(next).getScheme());
      }
      @Override public boolean shouldOverrideUrlLoading (WebView view, android.webkit.WebResourceRequest request) {
        return !"https".equalsIgnoreCase(request.getUrl().getScheme());
      }
    });
    WebViewCompat.addWebMessageListener(page, "TgxPaymentProvider", Collections.singleton(origin), (view, message, source, main, proxy) -> {
      if (submitted || isDestroyed() || !isFocused() || !tdlib.isAuthorized() || tdlib.myUserId() != accountUserId ||
          !context().hasTdlib() || context().currentTdlib() != tdlib || !main || !origin.equals(MiniAppController.httpsOrigin(source.toString())) ||
          !origin.equals(MiniAppController.httpsOrigin(view.getUrl())) || message.getData() == null || message.getData().length() > 65536) return;
      try {
        JSONObject event = new JSONObject(message.getData());
        if (!"payment_form_submit".equals(event.optString("eventType"))) return;
        JSONObject data = new JSONObject(event.getString("eventData"));
        Object credentials = data.get("credentials");
        JSONObject token = credentials instanceof JSONObject ? (JSONObject) credentials : new JSONObject((String) credentials);
        if (token.length() == 0) return;
        submitted = true;
        onCredentials.runWithData(new TdApi.InputCredentialsNew(token.toString(), save));
        navigateBack();
      } catch (Exception ignored) {
        // A malformed provider event must not submit or dismiss the payment form.
      }
    });
    WebViewCompat.addDocumentStartJavaScript(page,
      "if(window.top===window&&window.TgxPaymentProvider){window.TelegramWebviewProxy={postEvent:function(type,data){" +
        "window.TgxPaymentProvider.postMessage(JSON.stringify({eventType:type,eventData:typeof data==='string'?data:JSON.stringify(data)}));}};}",
      Collections.singleton(origin));
    page.loadUrl(url);
    return page;
  }

  @Override protected void onFocusStateChanged () {
    super.onFocusStateChanged();
    if (page != null) { if (isFocused()) page.onResume(); else page.onPause(); }
  }
  @Override public void onActivityPause () { super.onActivityPause(); if (page != null) page.onPause(); }
  @Override public void onActivityResume () { super.onActivityResume(); if (page != null && isFocused()) page.onResume(); }
  @Override public void destroy () {
    if (isDestroyed()) return;
    if (page != null) { page.stopLoading(); page.destroy(); page = null; }
    super.destroy();
  }
}

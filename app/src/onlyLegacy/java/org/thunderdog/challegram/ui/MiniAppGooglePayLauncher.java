/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.app.Activity;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wallet.AutoResolveHelper;
import com.google.android.gms.wallet.PaymentData;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.tool.Intents;

/** Wallet 18 retains the legacy resolver for the legacy Android flavor. */
final class MiniAppGooglePayLauncher {
  private final BaseActivity activity;
  private final MiniAppGooglePay.Callback callback;
  private boolean destroyed, pending;
  MiniAppGooglePayLauncher (BaseActivity activity, MiniAppGooglePay.Callback callback) { this.activity = activity; this.callback = callback; }
  void launch (Task<PaymentData> task) {
    pending = true;
    activity.putActivityResultHandler(Intents.ACTIVITY_RESULT_MINI_APP_GOOGLE_PAY, (code, result, intent) -> {
      pending = false;
      if (destroyed) return;
      if (result == Activity.RESULT_CANCELED) { callback.onResult(MiniAppGooglePay.CANCELLED, null); return; }
      PaymentData data = result == Activity.RESULT_OK && intent != null ? PaymentData.getFromIntent(intent) : null;
      if (data != null && data.toJson() != null) callback.onResult(MiniAppGooglePay.SUCCESS, data.toJson());
      else callback.onResult(MiniAppGooglePay.FAILED, null);
    });
    AutoResolveHelper.resolveTask(task, activity, Intents.ACTIVITY_RESULT_MINI_APP_GOOGLE_PAY);
  }
  void destroy () {
    destroyed = true;
    if (pending) activity.putActivityResultHandler(Intents.ACTIVITY_RESULT_MINI_APP_GOOGLE_PAY, null);
    pending = false;
  }
}

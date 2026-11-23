/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import androidx.activity.result.ActivityResultLauncher;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wallet.PaymentData;
import com.google.android.gms.wallet.contract.TaskResultContracts;
import java.util.concurrent.atomic.AtomicInteger;
import org.thunderdog.challegram.BaseActivity;

/** Manual registry ownership permits a checkout created after the activity has resumed. */
final class MiniAppGooglePayLauncher {
  private static final AtomicInteger IDS = new AtomicInteger();
  private final ActivityResultLauncher<Task<PaymentData>> launcher;
  private boolean destroyed;

  MiniAppGooglePayLauncher (BaseActivity activity, MiniAppGooglePay.Callback callback) {
    launcher = activity.getActivityResultRegistry().register("mini-app-wallet-" + IDS.incrementAndGet(),
      new TaskResultContracts.GetPaymentDataResult(), result -> {
        if (destroyed) return;
        PaymentData data = result.getResult();
        if (result.getStatus().isCanceled()) callback.onResult(MiniAppGooglePay.CANCELLED, null);
        else if (result.getStatus().isSuccess() && data != null && data.toJson() != null) callback.onResult(MiniAppGooglePay.SUCCESS, data.toJson());
        else callback.onResult(MiniAppGooglePay.FAILED, null);
      });
  }
  void launch (Task<PaymentData> task) { launcher.launch(task); }
  void destroy () { destroyed = true; launcher.unregister(); }
}

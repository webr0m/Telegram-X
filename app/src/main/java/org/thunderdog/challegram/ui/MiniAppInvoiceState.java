/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import java.math.BigDecimal;
import org.drinkless.tdlib.TdApi;

/** Payment status is determined by TDLib, never by the merchant or provider page. */
final class MiniAppInvoiceState {
  private boolean sending, attempted, finished, pending;
  private String result;

  static boolean validSlug (String slug) { return slug != null && slug.matches("[A-Za-z0-9_=-]{1,256}"); }
  boolean begin () {
    if (sending || finished || pending) return false;
    attempted = sending = true;
    return true;
  }
  boolean isSending () { return sending; }
  boolean isFinished () { return finished; }
  String result () { return result; }

  void failed () { sending = false; attempted = true; }
  void pending () { sending = false; pending = true; }

  boolean complete (String status) {
    if (finished) return false;
    if (!"paid".equals(status) && !"pending".equals(status) && !"failed".equals(status) && !"cancelled".equals(status)) {
      throw new IllegalArgumentException("Unknown invoice status");
    }
    sending = false;
    finished = true;
    result = status;
    return true;
  }

  String closeStatus () { return sending || pending ? "pending" : attempted ? "failed" : "cancelled"; }

  static long add (long left, long right) {
    if (right > 0 && left > Long.MAX_VALUE - right || right < 0 && left < Long.MIN_VALUE - right) throw new IllegalArgumentException("Price overflow");
    return left + right;
  }

  static long total (TdApi.LabeledPricePart[] parts) {
    long sum = 0;
    if (parts != null) for (TdApi.LabeledPricePart part : parts) sum = add(sum, part.amount);
    return sum;
  }

  static long parseTip (String text, int exponent, long maximum) {
    if (exponent < 0 || exponent > 9 || maximum < 0) throw new IllegalArgumentException("Invalid tip");
    if (text.trim().isEmpty()) return 0;
    try {
      long amount = new BigDecimal(text.trim().replace(',', '.')).movePointRight(exponent).longValueExact();
      if (amount < 0 || amount > maximum) throw new IllegalArgumentException("Invalid tip");
      return amount;
    } catch (ArithmeticException | NumberFormatException e) {
      throw new IllegalArgumentException("Invalid tip", e);
    }
  }
}

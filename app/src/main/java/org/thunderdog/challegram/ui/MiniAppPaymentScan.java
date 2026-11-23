/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.thunderdog.challegram.ui;

import org.drinkless.tdlib.TdApi;
import org.json.JSONException;
import org.json.JSONObject;

/** A resumable history frontier. Reaching the time boundary never means a payment failed. */
final class MiniAppPaymentScan {
  final long seller, chat;
  long from, pages, messages;
  int cutoff;
  boolean complete, retry;

  MiniAppPaymentScan (long seller, long chat, int cutoff) {
    if (seller <= 0 || chat <= 0 || cutoff <= 0) throw new IllegalArgumentException("Invalid history identity");
    this.seller = seller; this.chat = chat; this.cutoff = cutoff;
  }
  void boundary (int date) {
    if (date <= 0) throw new IllegalArgumentException("Invalid history boundary");
    if (date < cutoff) complete = false;
    cutoff = date;
  }
  void restart () { from = pages = messages = 0; complete = retry = false; }
  boolean accept (TdApi.Message[] page) {
    retry = false;
    if (page.length == 0) { complete = true; return false; }
    long next = from == 0 ? Long.MAX_VALUE : from;
    int oldest = Integer.MAX_VALUE;
    long added = 0;
    java.util.HashSet<Long> seen = new java.util.HashSet<>();
    for (TdApi.Message message : page) {
      if (message == null || message.chatId != chat || message.id <= 0 || message.date <= 0 || message.sendingState != null) continue;
      if (from != 0 && message.id > from) continue;
      if (!seen.add(message.id)) continue;
      next = Math.min(next, message.id); oldest = Math.min(oldest, message.date);
      if (from == 0 || message.id < from) added++;
    }
    pages++;
    if (added == 0) { retry = true; return false; }
    from = next; messages += added;
    complete = oldest < cutoff;
    return !complete;
  }
  JSONObject json () throws JSONException {
    return new JSONObject().put("seller", seller).put("chat", chat).put("cutoff", cutoff).put("from", from)
      .put("pages", pages).put("messages", messages).put("complete", complete).put("retry", retry);
  }
  static MiniAppPaymentScan parse (JSONObject data) throws JSONException {
    MiniAppPaymentScan scan = new MiniAppPaymentScan(data.getLong("seller"), data.getLong("chat"), data.getInt("cutoff"));
    scan.from = data.getLong("from"); scan.pages = data.getLong("pages"); scan.messages = data.getLong("messages");
    scan.complete = data.getBoolean("complete"); scan.retry = data.getBoolean("retry");
    if (scan.from < 0 || scan.pages < 0 || scan.messages < 0 || scan.pages > 10000000 || scan.messages > 1000000000 ||
        scan.from == 0 && scan.messages != 0 || scan.from > 0 && (scan.pages == 0 || scan.messages == 0)) throw new IllegalArgumentException("Invalid history frontier");
    return scan;
  }
}

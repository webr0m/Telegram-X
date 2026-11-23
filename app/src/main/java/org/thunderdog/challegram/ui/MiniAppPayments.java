/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.thunderdog.challegram.ui;

import android.content.SharedPreferences;
import android.os.SystemClock;
import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.telegram.AuthorizationListener;
import org.thunderdog.challegram.telegram.MessageListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import me.vkryl.core.lambda.RunnableData;
import tgx.td.ChatId;

/** Account-scoped observation survives the checkout and process restarts. All model access is on UI. */
public final class MiniAppPayments implements MessageListener, AuthorizationListener {
  private final Tdlib tdlib;
  private final LinkedHashMap<String, MiniAppPaymentRecord> records = new LinkedHashMap<>();
  private final ArrayList<Runnable> listeners = new ArrayList<>();
  private final HashSet<String> checking = new HashSet<>();
  private final LinkedHashMap<Long, MiniAppPaymentScan> scans = new LinkedHashMap<>();
  private final LinkedHashMap<Long, Integer> historicalRuns = new LinkedHashMap<>();
  private int runSequence;
  private long userId, lastCheck;
  private int generation;
  private int scanCursor;

  public MiniAppPayments (Tdlib tdlib) {
    this.tdlib = tdlib;
    tdlib.listeners().addAuthorizationChangeListener(this);
    load();
  }
  private SharedPreferences storage (long id) { return org.thunderdog.challegram.unsorted.AppContext.get().getSharedPreferences("miniapp_payments_" + tdlib.accountId() + "_" + id, 0); }
  private void load () {
    if (!tdlib.isAuthorized() || tdlib.myUserId() == 0 || userId == tdlib.myUserId()) return;
    userId = tdlib.myUserId(); generation++; records.clear(); checking.clear(); scans.clear(); historicalRuns.clear();
    tdlib.listeners().unsubscribeFromGlobalUpdates(this);
    tdlib.listeners().subscribeForGlobalUpdates(this);
    try {
      String data = storage(userId).getString("ledger", "[]");
      if (data.length() <= 256 * 1024) {
        JSONArray array = new JSONArray(data);
        for (int i = 0; i < Math.min(100, array.length()); i++) {
          try { MiniAppPaymentRecord record = MiniAppPaymentRecord.parse(array.getJSONObject(i)); records.put(record.key, record); }
          catch (Exception ignored) { }
        }
      }
    } catch (Exception ignored) { }
    try {
      String data = storage(userId).getString("history", "[]");
      if (data.length() <= 65536) {
        JSONArray array = new JSONArray(data);
        for (int i = 0; i < Math.min(100, array.length()); i++) try {
          MiniAppPaymentScan scan = MiniAppPaymentScan.parse(array.getJSONObject(i));
          if (scan.chat == ChatId.fromUserId(scan.seller)) scans.put(scan.seller, scan);
        } catch (Exception ignored) { }
      }
    } catch (Exception ignored) { }
  }
  private boolean active () { return userId != 0 && tdlib.isAuthorized() && tdlib.myUserId() == userId; }
  ArrayList<MiniAppPaymentRecord> list () { load(); return new ArrayList<>(records.values()); }
  MiniAppPaymentRecord find (String key) { return records.get(key); }
  MiniAppPaymentScan scan (MiniAppPaymentRecord record) { return scans.get(record.sellerId); }
  boolean searching (MiniAppPaymentRecord record) { return historicalRuns.containsKey(record.sellerId); }
  public void addListener (Runnable listener) { listeners.add(listener); }
  public void removeListener (Runnable listener) { listeners.remove(listener); }
  private void notifyChanged () { for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }
  private String encode () {
    JSONArray array = new JSONArray();
    for (MiniAppPaymentRecord record : records.values()) try { array.put(record.json()); } catch (Exception ignored) { }
    return array.toString();
  }
  private void persist () {
    if (!active()) return;
    long id = userId; String data = encode();
    Background.instance().post(() -> storage(id).edit().putString("ledger", data).commit());
    notifyChanged();
  }

  boolean reserve (TdApi.InputInvoice invoice, TdApi.PaymentForm form, String currency, long amount, RunnableData<Boolean> ready) {
    load();
    String key = MiniAppPaymentRecord.key(invoice);
    MiniAppPaymentRecord previous = records.get(key);
    if (!active() || previous != null && !previous.status.equals("failed")) return false;
    if (records.size() >= 100 && previous == null) {
      String victim = null;
      for (MiniAppPaymentRecord record : records.values()) if (!record.status.equals("pending")) { victim = record.key; break; }
      if (victim == null) return false;
      records.remove(victim);
    }
    MiniAppPaymentRecord record = new MiniAppPaymentRecord(key, invoice instanceof TdApi.InputInvoiceName name ? name.name : "",
      invoice instanceof TdApi.InputInvoiceMessage message ? message.chatId : 0,
      invoice instanceof TdApi.InputInvoiceMessage message ? message.messageId : 0, form.sellerBotUserId, currency, amount,
      form.productInfo.title.length() > 1024 ? form.productInfo.title.substring(0, 1024) : form.productInfo.title, (int) (tdlib.currentTimeMillis() / 1000), "pending");
    records.put(key, record);
    long id = userId; int expected = generation; String data = encode();
    Background.instance().post(() -> {
      boolean saved = storage(id).edit().putString("ledger", data).commit();
      UI.post(() -> {
        if (generation != expected || !active()) return;
        if (!saved) { records.remove(key); notifyChanged(); }
        ready.runWithData(saved);
      });
    });
    notifyChanged();
    return true;
  }
  void fail (String key) { MiniAppPaymentRecord record = records.get(key); if (record != null && !record.status.equals("paid")) { record.status = "failed"; persist(); } }
  void accepted (String key) { MiniAppPaymentRecord record = records.get(key); if (record != null) { record.status = "paid"; persist(); } }

  @Override public void onNewMessage (TdApi.Message message) {
    if (message.content instanceof TdApi.MessagePaymentSuccessful) UI.post(() -> consider(message));
  }
  private void consider (TdApi.Message message) {
    if (!active()) return;
    for (MiniAppPaymentRecord record : new ArrayList<>(records.values())) {
      if (record.receiptMessageId != 0 || record.status.equals("failed") || !record.matches(message, ChatId.fromUserId(record.sellerId))) continue;
      String request = record.key + ":receipt";
      if (!checking.add(request)) continue;
      int expected = generation;
      tdlib.send(new TdApi.GetPaymentReceipt(message.chatId, message.id), (receipt, error) -> UI.post(() -> {
        if (expected != generation || !active()) return;
        checking.remove(request);
        if (records.get(record.key) != record || error != null || !record.matchesReceipt(receipt)) return;
        record.status = "paid"; record.receiptChatId = message.chatId; record.receiptMessageId = message.id;
        persist();
      }));
    }
  }

  public void checkPending () {
    load();
    if (!active() || lastCheck != 0 && SystemClock.elapsedRealtime() - lastCheck < 15000) return;
    lastCheck = SystemClock.elapsedRealtime();
    HashSet<Long> sellers = new HashSet<>();
    ArrayList<MiniAppPaymentRecord> candidates = new ArrayList<>(records.values());
    int start = candidates.isEmpty() ? 0 : scanCursor % candidates.size();
    for (int i = 0; i < candidates.size(); i++) {
      MiniAppPaymentRecord record = candidates.get((start + i) % candidates.size());
      if (record.status.equals("failed") || record.receiptMessageId != 0 || !sellers.add(record.sellerId)) continue;
      long seller = record.sellerId; int expected = generation;
      tdlib.send(new TdApi.CreatePrivateChat(seller, false), (chat, error) -> UI.post(() -> {
        if (expected != generation || !active() || error != null) return;
        history(chat.id, 0, 1, expected);
        startHistory(seller, chat.id, 3, false);
      }));
      if (sellers.size() >= 4) { scanCursor = start + i + 1; break; }
    }
  }
  private void history (long chat, long from, int pages, int expected) {
    String request = "history:" + chat;
    if (!checking.add(request)) return;
    tdlib.send(new TdApi.GetChatHistory(chat, from, 0, 100, false), (messages, error) -> UI.post(() -> {
      if (expected != generation || !active()) return;
      checking.remove(request);
      if (error != null || messages.messages.length == 0) return;
      long next = 0;
      for (TdApi.Message message : messages.messages) { consider(message); next = message.id; }
      if (pages > 1 && next != 0 && next != from) history(chat, next, pages - 1, expected);
    }));
  }

  private int cutoff (long seller) {
    int earliest = Integer.MAX_VALUE;
    for (MiniAppPaymentRecord record : records.values()) if (record.sellerId == seller && !record.status.equals("failed") && record.receiptMessageId == 0) earliest = Math.min(earliest, record.created);
    return earliest;
  }

  private void persistScans () {
    if (!active()) return;
    JSONArray array = new JSONArray();
    for (MiniAppPaymentScan scan : scans.values()) try { array.put(scan.json()); } catch (Exception ignored) { }
    long id = userId; String data = array.toString();
    Background.instance().post(() -> storage(id).edit().putString("history", data).commit());
    notifyChanged();
  }

  boolean searchHistory (MiniAppPaymentRecord record, boolean restart) {
    load();
    if (!active() || records.get(record.key) != record || record.status.equals("failed") || record.receiptMessageId != 0 ||
        historicalRuns.containsKey(record.sellerId) || historicalRuns.size() >= 4) return false;
    startHistory(record.sellerId, ChatId.fromUserId(record.sellerId), 20, restart);
    return historicalRuns.containsKey(record.sellerId);
  }

  void cancelHistory (MiniAppPaymentRecord record) {
    historicalRuns.remove(record.sellerId);
    persistScans();
  }

  private void startHistory (long seller, long chat, int budget, boolean restart) {
    int date = cutoff(seller);
    if (!active() || date == Integer.MAX_VALUE || historicalRuns.containsKey(seller) || historicalRuns.size() >= 4) return;
    if (scans.size() >= 100 && !scans.containsKey(seller)) {
      Long victim = null;
      for (Long id : scans.keySet()) if (!historicalRuns.containsKey(id) && cutoff(id) == Integer.MAX_VALUE) { victim = id; break; }
      if (victim == null) return;
      scans.remove(victim);
    }
    MiniAppPaymentScan scan = scans.get(seller);
    if (scan == null || scan.chat != chat) { scan = new MiniAppPaymentScan(seller, chat, date); scans.put(seller, scan); }
    scan.boundary(date);
    if (restart || scan.complete) scan.restart();
    int run = ++runSequence, expected = generation;
    historicalRuns.put(seller, run); persistScans();
    MiniAppPaymentScan selected = scan;
    tdlib.send(new TdApi.CreatePrivateChat(seller, false), (privateChat, error) -> UI.post(() -> {
      if (!currentRun(seller, run, expected)) return;
      if (error != null || privateChat.id != chat) { selected.retry = true; finishHistory(seller, run); return; }
      historicalPage(selected, budget, run, expected);
    }));
  }

  private boolean currentRun (long seller, int run, int expected) {
    return active() && expected == generation && Integer.valueOf(run).equals(historicalRuns.get(seller));
  }

  private void finishHistory (long seller, int run) {
    if (Integer.valueOf(run).equals(historicalRuns.get(seller))) { historicalRuns.remove(seller); persistScans(); }
  }

  private void historicalPage (MiniAppPaymentScan scan, int budget, int run, int expected) {
    if (!currentRun(scan.seller, run, expected)) return;
    if (cutoff(scan.seller) == Integer.MAX_VALUE) { scan.complete = true; finishHistory(scan.seller, run); return; }
    long from = scan.from;
    tdlib.send(new TdApi.GetChatHistory(scan.chat, from, 0, 100, false), (page, error) -> UI.post(() -> {
      if (!currentRun(scan.seller, run, expected)) return;
      if (error != null) { scan.retry = true; finishHistory(scan.seller, run); return; }
      for (TdApi.Message message : page.messages) consider(message);
      boolean more = scan.accept(page.messages);
      persistScans();
      if (more && budget > 1) historicalPage(scan, budget - 1, run, expected);
      else finishHistory(scan.seller, run);
    }));
  }
  @Override public void onAuthorizationStateChanged (TdApi.AuthorizationState state) {
    UI.post(() -> {
      if (state instanceof TdApi.AuthorizationStateLoggingOut) {
        long old = userId; generation++; userId = 0; records.clear(); checking.clear(); scans.clear(); historicalRuns.clear();
        if (old != 0) Background.instance().post(() -> storage(old).edit().clear().commit());
        notifyChanged();
      } else if (state instanceof TdApi.AuthorizationStateReady) { load(); notifyChanged(); checkPending(); }
    });
  }
}

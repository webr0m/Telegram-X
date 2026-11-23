/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.thunderdog.challegram.ui;

import org.drinkless.tdlib.TdApi;
import org.json.JSONException;
import org.json.JSONObject;

/** The persisted ledger contains invoice identities, never payment credentials or verification URLs. */
final class MiniAppPaymentRecord {
  final String key, slug, currency, title;
  final long invoiceChatId, invoiceMessageId, sellerId, amount;
  final int created;
  String status;
  long receiptChatId, receiptMessageId;

  MiniAppPaymentRecord (String key, String slug, long chatId, long messageId, long sellerId, String currency,
                        long amount, String title, int created, String status) {
    if (sellerId <= 0 || amount <= 0 || !currency.matches("[A-Z]{3}") || created <= 0 || title.length() > 1024 ||
        !(status.equals("pending") || status.equals("paid") || status.equals("failed"))) throw new IllegalArgumentException("Invalid payment record");
    if (!slug.isEmpty() ? !MiniAppInvoiceState.validSlug(slug) || !key.equals("name:" + slug) : chatId == 0 || messageId <= 0 || !key.equals("message:" + chatId + ":" + messageId)) throw new IllegalArgumentException("Invalid invoice identity");
    this.key = key; this.slug = slug; this.invoiceChatId = chatId; this.invoiceMessageId = messageId;
    this.sellerId = sellerId; this.currency = currency; this.amount = amount; this.title = title; this.created = created; this.status = status;
  }

  static String key (TdApi.InputInvoice invoice) {
    if (invoice instanceof TdApi.InputInvoiceName named) return "name:" + named.name;
    if (invoice instanceof TdApi.InputInvoiceMessage message) return "message:" + message.chatId + ":" + message.messageId;
    throw new IllegalArgumentException("Unsupported invoice");
  }

  boolean matches (TdApi.Message message, long sellerChatId) {
    if (message == null || message.chatId != sellerChatId || message.id <= 0 || message.date < created ||
        !(message.content instanceof TdApi.MessagePaymentSuccessful paid) || !currency.equals(paid.currency) || amount != paid.totalAmount) return false;
    return !slug.isEmpty() ? slug.equals(paid.invoiceName) : paid.invoiceChatId == invoiceChatId && paid.invoiceMessageId == invoiceMessageId;
  }

  boolean matchesReceipt (TdApi.PaymentReceipt receipt) {
    if (receipt == null || receipt.sellerBotUserId != sellerId || receipt.date < created) return false;
    if (receipt.type instanceof TdApi.PaymentReceiptTypeStars stars) return "XTR".equals(currency) && stars.starCount == amount;
    if (!(receipt.type instanceof TdApi.PaymentReceiptTypeRegular regular) || !currency.equals(regular.invoice.currency)) return false;
    try { return amount == MiniAppInvoiceState.add(MiniAppInvoiceState.add(MiniAppInvoiceState.total(regular.invoice.priceParts),
      regular.shippingOption == null ? 0 : MiniAppInvoiceState.total(regular.shippingOption.priceParts)), regular.tipAmount); }
    catch (IllegalArgumentException e) { return false; }
  }

  static boolean rejected (TdApi.Error error) {
    if (error == null || error.code != 400) return false;
    return switch (error.message) {
      case "FORM_EXPIRED", "BALANCE_TOO_LOW", "TMP_PASSWORD_INVALID", "ORDER_INFO_INVALID", "SHIPPING_OPTION_INVALID",
        "PAYMENT_CREDENTIALS_INVALID", "NEW_CREDENTIALS_INVALID", "CREDENTIALS_INVALID", "CURRENCY_TOTAL_AMOUNT_INVALID" -> true;
      default -> false;
    };
  }

  JSONObject json () throws JSONException {
    return new JSONObject().put("key", key).put("slug", slug).put("chat", invoiceChatId).put("message", invoiceMessageId)
      .put("seller", sellerId).put("currency", currency).put("amount", amount).put("title", title).put("created", created)
      .put("status", status).put("receipt_chat", receiptChatId).put("receipt_message", receiptMessageId);
  }
  static MiniAppPaymentRecord parse (JSONObject data) throws JSONException {
    MiniAppPaymentRecord record = new MiniAppPaymentRecord(data.getString("key"), data.getString("slug"), data.getLong("chat"), data.getLong("message"),
      data.getLong("seller"), data.getString("currency"), data.getLong("amount"), data.getString("title"), data.getInt("created"), data.getString("status"));
    record.receiptChatId = data.optLong("receipt_chat"); record.receiptMessageId = data.optLong("receipt_message");
    if (record.receiptMessageId < 0 || (record.receiptChatId == 0) != (record.receiptMessageId == 0) ||
        record.receiptMessageId != 0 && !record.status.equals("paid")) throw new IllegalArgumentException("Invalid receipt reference");
    return record;
  }
}

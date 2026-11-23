/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.math.BigDecimal;

/** TDLib exposes a Stripe gateway key, but no generic Google Pay merchant configuration. */
final class MiniAppGooglePay {
  static final int CANCELLED = 0, SUCCESS = 1, FAILED = 2;
  interface Callback { void onResult (int status, String json); }
  private static JSONObject base () throws JSONException { return new JSONObject().put("apiVersion", 2).put("apiVersionMinor", 0); }
  private static JSONObject card (TdApi.PaymentProviderStripe stripe, boolean tokenize) throws JSONException {
    JSONObject parameters = new JSONObject().put("allowedAuthMethods", new JSONArray().put("PAN_ONLY").put("CRYPTOGRAM_3DS"))
      .put("allowedCardNetworks", new JSONArray().put("AMEX").put("DISCOVER").put("JCB").put("MASTERCARD").put("VISA"));
    if (stripe.needCountry || stripe.needPostalCode || stripe.needCardholderName) {
      parameters.put("billingAddressRequired", true).put("billingAddressParameters", new JSONObject().put("format", "FULL"));
    }
    JSONObject method = new JSONObject().put("type", "CARD").put("parameters", parameters);
    if (tokenize) method.put("tokenizationSpecification", new JSONObject().put("type", "PAYMENT_GATEWAY")
      .put("parameters", new JSONObject().put("gateway", "stripe").put("stripe:publishableKey", stripe.publishableKey)
        .put("stripe:version", MiniAppCardTokens.STRIPE_VERSION)));
    return method;
  }
  static JSONObject readiness (TdApi.PaymentProviderStripe stripe) throws JSONException {
    return base().put("allowedPaymentMethods", new JSONArray().put(card(stripe, false)));
  }
  static JSONObject payment (TdApi.PaymentProviderStripe stripe, boolean test, String currency, int exponent, long amount, String merchant) throws JSONException {
    if (!MiniAppCardTokens.stripeKey(stripe.publishableKey, test) || currency == null || !currency.matches("[A-Z]{3}") ||
        exponent < 0 || exponent > 9 || amount <= 0) throw new IllegalArgumentException("Invalid Google Pay transaction");
    return base().put("allowedPaymentMethods", new JSONArray().put(card(stripe, true)))
      .put("transactionInfo", new JSONObject().put("totalPriceStatus", "FINAL").put("currencyCode", currency)
        .put("totalPrice", BigDecimal.valueOf(amount, exponent).toPlainString()).put("checkoutOption", "DEFAULT"))
      .put("merchantInfo", new JSONObject().put("merchantName", merchant));
  }
  static String credentials (TdApi.PaymentProviderStripe stripe, String response, boolean test) throws JSONException {
    JSONObject method = new JSONObject(response).getJSONObject("paymentMethodData");
    JSONObject data = method.getJSONObject("tokenizationData");
    if (!"CARD".equals(method.getString("type")) || !"PAYMENT_GATEWAY".equals(data.getString("type"))) throw new IllegalArgumentException("Unexpected Google Pay method");
    return MiniAppCardTokens.credentials(stripe, data.getString("token"), test);
  }
}

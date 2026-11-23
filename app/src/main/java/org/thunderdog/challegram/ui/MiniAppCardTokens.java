/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import org.drinkless.tdlib.TdApi;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/** Provider-specific tokenization only. Requests never authorize a Telegram payment. */
final class MiniAppCardTokens {
  static final String STRIPE_VERSION = "2017-06-05";

  private static final OkHttpClient CLIENT = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
      .retryOnConnectionFailure(false).connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).build();
  static OkHttpClient client () { return CLIENT; }

  static boolean stripeKey (String key, boolean test) {
    return key != null && key.startsWith(test ? "pk_test_" : "pk_live_") && key.matches("pk_(test|live)_[A-Za-z0-9]{4,512}");
  }

  static String smartEndpoint (String url, boolean test) {
    if (url == null || url.isEmpty()) return "https://" + (test ? "tgb-playground" : "tgb") + ".smart-glocal.com/cds/v1/tokenize/card";
    try {
      URI uri = new URI(url);
      String host = uri.getHost();
      if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null ||
          !host.toLowerCase(Locale.US).endsWith(".smart-glocal.com") || uri.getUserInfo() != null ||
          uri.getPort() != -1 && uri.getPort() != 443 || uri.getRawQuery() != null || uri.getRawFragment() != null ||
          !"/cds/v1/tokenize/card".equals(uri.getRawPath())) throw new IllegalArgumentException("Invalid provider endpoint");
      return uri.toASCIIString();
    } catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("Invalid provider endpoint"); }
  }

  static final class Card {
    final String number, cvc, name, country, postal;
    final int month, year;
    Card (String number, String expiry, String cvc, String name, String country, String postal,
          boolean needName, boolean needCountry, boolean needPostal, int currentYear, int currentMonth) {
      this.number = number.replace(" ", "").replace("-", "");
      this.cvc = cvc.trim();
      this.name = name.trim();
      this.country = country.trim().toUpperCase(Locale.US);
      this.postal = postal.trim();
      String[] parts = expiry.trim().split("/", -1);
      try {
        if (parts.length != 2 || !parts[0].trim().matches("[0-9]{1,2}") || !parts[1].trim().matches("([0-9]{2}|[0-9]{4})")) throw new IllegalArgumentException("expiry");
        month = Integer.parseInt(parts[0].trim());
        int parsed = Integer.parseInt(parts[1].trim());
        year = parts[1].trim().length() == 2 ? 2000 + parsed : parsed;
      } catch (NumberFormatException e) { throw new IllegalArgumentException("expiry"); }
      if (!validNumber(this.number)) throw new IllegalArgumentException("number");
      if (month < 1 || month > 12 || year < currentYear || year == currentYear && month < currentMonth || year > currentYear + 30) throw new IllegalArgumentException("expiry");
      boolean amex = this.number.startsWith("34") || this.number.startsWith("37");
      if (!this.cvc.matches(amex ? "[0-9]{4}" : "[0-9]{3}")) throw new IllegalArgumentException("cvc");
      if (needName && (this.name.isEmpty() || this.name.length() > 256)) throw new IllegalArgumentException("name");
      if (needCountry && !this.country.matches("[A-Z]{2}")) throw new IllegalArgumentException("country");
      if (needPostal && (this.postal.isEmpty() || this.postal.length() > 32)) throw new IllegalArgumentException("postal");
    }
  }

  static boolean validNumber (String number) {
    if (!number.matches("[0-9]{12,19}") || number.matches("0+")) return false;
    int sum = 0;
    boolean doubleDigit = false;
    for (int i = number.length() - 1; i >= 0; i--) {
      int value = number.charAt(i) - '0';
      if (doubleDigit) { value *= 2; if (value > 9) value -= 9; }
      sum += value;
      doubleDigit = !doubleDigit;
    }
    return sum % 10 == 0;
  }

  static Request request (TdApi.PaymentProvider provider, Card card, boolean test) throws JSONException {
    if (provider instanceof TdApi.PaymentProviderStripe stripe) {
      if (!stripeKey(stripe.publishableKey, test)) throw new IllegalArgumentException("Invalid publishable key");
      FormBody.Builder body = new FormBody.Builder().add("card[number]", card.number).add("card[exp_month]", Integer.toString(card.month))
        .add("card[exp_year]", Integer.toString(card.year)).add("card[cvc]", card.cvc);
      if (!card.name.isEmpty()) body.add("card[name]", card.name);
      if (!card.country.isEmpty()) body.add("card[address_country]", card.country);
      if (!card.postal.isEmpty()) body.add("card[address_zip]", card.postal);
      return new Request.Builder().url("https://api.stripe.com/v1/tokens")
        .header("Authorization", "Bearer " + stripe.publishableKey).header("Stripe-Version", STRIPE_VERSION)
        .header("Cache-Control", "no-store").post(body.build()).build();
    }
    if (provider instanceof TdApi.PaymentProviderSmartGlocal smart) {
      if (smart.publicToken == null || !smart.publicToken.matches("[!-~]{1,1024}")) throw new IllegalArgumentException("Invalid public token");
      JSONObject details = new JSONObject().put("number", card.number).put("expiration_month", String.format(Locale.US, "%02d", card.month))
        .put("expiration_year", Integer.toString(card.year)).put("security_code", card.cvc);
      RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), new JSONObject().put("card", details).toString());
      return new Request.Builder().url(smartEndpoint(smart.tokenizeUrl, test)).header("X-PUBLIC-TOKEN", smart.publicToken)
        .header("Cache-Control", "no-store").post(body).build();
    }
    throw new IllegalArgumentException("Unsupported provider");
  }

  static String credentials (TdApi.PaymentProvider provider, String response, boolean test) throws JSONException {
    JSONObject result = new JSONObject(response);
    if (provider instanceof TdApi.PaymentProviderStripe) {
      String id = result.getString("id");
      if (!id.matches("tok_[A-Za-z0-9_]{1,256}") || !"card".equals(result.getString("type")) || result.optBoolean("used") ||
          !result.has("livemode") || result.getBoolean("livemode") == test) throw new IllegalArgumentException("Invalid token");
      return new JSONObject().put("type", "card").put("id", id).toString();
    }
    if (provider instanceof TdApi.PaymentProviderSmartGlocal) {
      String token = result.getJSONObject("data").getString("token");
      if (token.isEmpty() || token.length() > 8192) throw new IllegalArgumentException("Invalid token");
      return new JSONObject().put("type", "card").put("token", token).toString();
    }
    throw new IllegalArgumentException("Unsupported provider");
  }
}

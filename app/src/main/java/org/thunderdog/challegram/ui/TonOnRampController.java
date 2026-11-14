/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.net.Uri;
import android.text.InputType;
import android.view.View;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Locale;

// @MadeByLLM("gpt-6", id = "stars-development")
public final class TonOnRampController extends RecyclerViewController<Void> implements View.OnClickListener {
  private record Asset(TdApi.OnRampProvider provider, String code) { }

  private final long account;
  private final String address;
  private final ArrayList<Asset> assets = new ArrayList<>();
  private SettingsAdapter adapter;
  private boolean busy, failed;

  public TonOnRampController (Context context, Tdlib tdlib, long account, String address) {
    super(context, tdlib);
    this.account = account;
    this.address = address;
  }

  @Override public int getId () { return R.id.controller_tonOnRamp; }
  @Override public CharSequence getName () { return Lang.getString(R.string.TgxTonBuyOnChain); }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recycler) {
    adapter = new SettingsAdapter(this);
    recycler.setAdapter(adapter);
    refresh();
  }

  private boolean sameWallet () {
    TdApi.TonWalletState state = tdlib.tonWalletState();
    return account != 0 && account == tdlib.myUserId() && tdlib.isAuthorized() &&
      tdlib.options().canUseTonWallet && state != null && address.equals(state.address);
  }

  static boolean isTonAsset (String currency) {
    return currency != null && ("TON".equalsIgnoreCase(currency) || "GRAM".equalsIgnoreCase(currency));
  }

  static String amountInput (String value) {
    String input = value.trim().replace(',', '.');
    if (!input.matches("[0-9]{1,12}(?:\\.[0-9]{1,9})?")) {
      return null;
    }
    BigDecimal amount = new BigDecimal(input);
    return amount.signum() > 0 ? amount.stripTrailingZeros().toPlainString() : null;
  }

  static boolean withinLimits (String amount, TdApi.OnRampPaymentLimits limits) {
    if (limits == null) {
      return true;
    }
    try {
      BigDecimal requested = new BigDecimal(amount);
      String minimum = limits.cryptocurrencyMinAmount;
      String maximum = limits.cryptocurrencyMaxAmount;
      return (minimum == null || minimum.isEmpty() || requested.compareTo(new BigDecimal(minimum)) >= 0) &&
        (maximum == null || maximum.isEmpty() || requested.compareTo(new BigDecimal(maximum)) <= 0);
    } catch (NumberFormatException error) {
      return false;
    }
  }

  static boolean isFresh (int expiration, long now) {
    return expiration > now;
  }

  private long now () {
    return tdlib.options().unixTime.currentTimeMillis() / 1000L;
  }

  private void refresh () {
    if (!sameWallet() || busy) {
      return;
    }
    busy = true;
    failed = false;
    assets.clear();
    render();
    tdlib.send(new TdApi.GetOnRampProviders(""), (result, error) -> runOnUiThreadOptional(() -> {
      if (sameWallet() && result != null) {
        for (TdApi.OnRampProvider provider : result.providers) {
          if (provider.id == null || provider.id.isEmpty() || provider.cryptocurrencies == null) {
            continue;
          }
          for (String code : provider.cryptocurrencies) {
            if (isTonAsset(code)) {
              assets.add(new Asset(provider, code));
              break;
            }
          }
        }
      }
      failed = result == null;
      complete(error);
    }));
  }

  private void render () {
    ArrayList<ListItem> rows = new ArrayList<>();
    if (sameWallet()) {
      rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0,
        Lang.getString(R.string.TgxTonOnRampWallet, address)));
      if (busy) {
        rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.LoadingInformation));
      } else {
        if (assets.isEmpty()) {
          rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0,
            failed ? R.string.TgxTonOnRampError : R.string.TgxTonOnRampUnavailable));
        }
        for (Asset asset : assets) {
          String name = asset.provider.name;
          rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonOnRampProvider, 0,
            name == null || name.isEmpty() ? asset.provider.id : name).setData(asset));
        }
        rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonRefresh, 0,
          failed ? R.string.TgxStarsRetry : R.string.TgxStarsRefresh));
      }
    }
    adapter.setItems(rows, false);
  }

  private boolean complete (TdApi.Error error) {
    busy = false;
    render();
    if (!isFocused() || !sameWallet()) {
      return false;
    }
    if (error != null) {
      UI.showError(error);
      return false;
    }
    return true;
  }

  @Override
  public void onClick (View view) {
    if (!isFocused() || !sameWallet() || busy) {
      return;
    }
    if (view.getId() == R.id.btn_tonRefresh) {
      refresh();
    } else if (view.getId() == R.id.btn_tonOnRampProvider && view.getTag() instanceof ListItem row &&
        row.getData() instanceof Asset asset && assets.contains(asset)) {
      chooseCurrency(asset);
    }
  }

  private void chooseCurrency (Asset asset) {
    if (!asset.provider.supportsBaseCurrencies) {
      availability(asset, "");
      return;
    }
    busy = true;
    render();
    tdlib.send(new TdApi.GetOnRampProviderBaseCurrencies(asset.provider.id, asset.code),
      (result, error) -> runOnUiThreadOptional(() -> {
        if (!complete(error) || result == null) {
          return;
        }
        ArrayList<String> currencies = new ArrayList<>();
        for (String currency : result.currencies) {
          if (currency != null && currency.matches("[A-Za-z]{3}")) {
            currencies.add(currency.toUpperCase(Locale.ROOT));
          }
        }
        if (currencies.isEmpty()) {
          unavailable();
          return;
        }
        Options.Builder options = new Options.Builder().info(Lang.getString(R.string.TgxTonOnRampCurrency));
        for (int index = 0; index < currencies.size(); index++) {
          options.item(new OptionItem(index + 1, currencies.get(index), OptionColor.NORMAL, 0));
        }
        showOptions(options.cancelItem().build(), (view, id) -> {
          if (id > 0 && id <= currencies.size() && sameWallet() && !busy && isFocused()) {
            availability(asset, currencies.get(id - 1));
          }
          return true;
        });
      }));
  }

  private void availability (Asset asset, String currency) {
    busy = true;
    render();
    tdlib.send(new TdApi.GetOnRampPaymentAvailability(asset.provider.id, asset.code, currency),
      (result, error) -> runOnUiThreadOptional(() -> {
        if (!complete(error) || result == null) {
          return;
        }
        if (!result.isAllowed || !result.isPurchaseAllowed) {
          unavailable();
          return;
        }
        ArrayList<String> methods = new ArrayList<>();
        for (TdApi.OnRampPaymentMethod method : result.methods) {
          if (method.isAvailable && method.name != null && !method.name.isEmpty()) {
            methods.add(method.name);
          }
        }
        if (methods.isEmpty()) {
          if (result.methods.length == 0) {
            limits(asset, currency, "");
          } else {
            unavailable();
          }
          return;
        }
        Options.Builder options = new Options.Builder().info(Lang.getString(R.string.TgxTonOnRampMethod));
        for (int index = 0; index < methods.size(); index++) {
          options.item(new OptionItem(index + 1, methods.get(index), OptionColor.NORMAL, 0));
        }
        showOptions(options.cancelItem().build(), (view, id) -> {
          if (id > 0 && id <= methods.size() && sameWallet() && !busy && isFocused()) {
            limits(asset, currency, methods.get(id - 1));
          }
          return true;
        });
      }));
  }

  private void limits (Asset asset, String currency, String method) {
    if (!asset.provider.supportsLimits || currency.isEmpty()) {
      enterAmount(asset, currency, method, null);
      return;
    }
    busy = true;
    render();
    tdlib.send(new TdApi.GetOnRampPaymentLimits(asset.provider.id, asset.code, currency, method),
      (result, error) -> runOnUiThreadOptional(() -> {
        if (complete(error) && result != null) {
          enterAmount(asset, currency, method, result);
        }
      }));
  }

  private void enterAmount (Asset asset, String currency, String method, TdApi.OnRampPaymentLimits limits) {
    CharSequence caption = Lang.getString(R.string.TgxTonOnRampAmount);
    if (limits != null && limits.cryptocurrencyMinAmount != null && !limits.cryptocurrencyMinAmount.isEmpty() &&
        limits.cryptocurrencyMaxAmount != null && !limits.cryptocurrencyMaxAmount.isEmpty()) {
      caption = Lang.getString(R.string.TgxTonOnRampRange, limits.cryptocurrencyMinAmount, limits.cryptocurrencyMaxAmount);
    }
    var input = openInputAlert(getName(), caption, R.string.Continue, R.string.Cancel, null,
      (edit, value) -> {
        String amount = amountInput(value);
        if (amount == null || !withinLimits(amount, limits)) {
          UI.showToast(R.string.TgxTonOnRampInvalidAmount, android.widget.Toast.LENGTH_SHORT);
          return false;
        }
        tdlib.ui().post(() -> {
          if (!isDestroyed() && isFocused() && sameWallet() && !busy) {
            quote(asset, currency, method, amount);
          }
        });
        return true;
      }, true);
    input.getEditText().setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
  }

  private void quote (Asset asset, String currency, String method, String amount) {
    if (!asset.provider.supportsQuote || currency.isEmpty()) {
      confirmCheckout(asset, currency, method, amount, null);
      return;
    }
    busy = true;
    render();
    tdlib.send(new TdApi.GetOnRampPaymentQuote(asset.provider.id, asset.code, currency, amount, "", method),
      (result, error) -> runOnUiThreadOptional(() -> {
        if (complete(error) && result != null) {
          if (isFresh(result.expirationDate, now())) {
            confirmCheckout(asset, currency, method, amount, result);
          } else {
            expired();
          }
        }
      }));
  }

  private void confirmCheckout (Asset asset, String currency, String method, String amount, TdApi.OnRampPaymentQuote quote) {
    String details = Lang.getString(R.string.TgxTonOnRampConfirm, amount, address);
    if (quote != null) {
      details += "\n\n" + Lang.getString(R.string.TgxTonOnRampQuote, quote.cryptocurrencyAmount, quote.totalAmount, currency);
      if (quote.paymentMethod != null && !quote.paymentMethod.isEmpty()) {
        details += "\n" + quote.paymentMethod;
      }
    }
    showConfirm(details, Lang.getString(R.string.Continue), () -> {
      if (!sameWallet() || busy || !isFocused()) {
        return;
      }
      if (quote != null && !isFresh(quote.expirationDate, now())) {
        expired();
        return;
      }
      String paymentMethod = quote != null && quote.paymentMethod != null && !quote.paymentMethod.isEmpty() ?
        quote.paymentMethod : method;
      createCheckout(asset, currency, paymentMethod, amount);
    });
  }

  private void createCheckout (Asset asset, String currency, String method, String amount) {
    busy = true;
    render();
    tdlib.send(new TdApi.CreateOnRampPaymentSession(asset.provider.id, asset.code, address, method, currency, amount,
      "", "", Theme.isDark() ? "dark" : "light", "", ""), (result, error) -> runOnUiThreadOptional(() -> {
      if (!complete(error) || result == null) {
        return;
      }
      URI url = TonWalletController.httpsUrl(result.url);
      if (url == null) {
        unavailable();
      } else if (!isFresh(result.expirationDate, now())) {
        expired();
      } else {
        showConfirm(Lang.getString(R.string.TgxTonOnRampOpen, url.getHost()), Lang.getString(R.string.Continue), () -> {
          if (sameWallet() && isFocused()) {
            if (isFresh(result.expirationDate, now())) {
              Intents.openUriInBrowser(Uri.parse(url.toASCIIString()));
            } else {
              expired();
            }
          }
        });
      }
    }));
  }

  private void unavailable () {
    UI.showToast(R.string.TgxTonOnRampUnavailable, android.widget.Toast.LENGTH_LONG);
  }

  private void expired () {
    UI.showToast(R.string.TgxTonOnRampExpired, android.widget.Toast.LENGTH_LONG);
  }
}

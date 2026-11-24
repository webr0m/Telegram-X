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
import android.view.View;
import android.widget.Toast;

import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibOptionListener;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

// @MadeByLLM("gpt-6", id = "stars-development")
public final class TonRevenueController extends RecyclerViewController<Void> implements View.OnClickListener, TdlibOptionListener {
  private static final long MIN_WITHDRAWAL = 10_000_000_000L;
  private final long userId;
  private SettingsAdapter adapter;
  private TdApi.GramRevenueStatus status;
  private record RevenuePoint(long dayMillis, long amount) { }
  private List<RevenuePoint> dailyRevenue = Collections.emptyList();
  private boolean allDays;
  private static final int RECENT_DAYS = 7;
  private long revision;
  private boolean busy, failed;

  public TonRevenueController (Context context, Tdlib tdlib) {
    super(context, tdlib);
    userId = tdlib.myUserId();
  }

  @Override public int getId () { return R.id.controller_tonRevenue; }
  @Override public CharSequence getName () { return Lang.getString(R.string.TgxTonRevenue); }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recycler) {
    adapter = new SettingsAdapter(this);
    recycler.setAdapter(adapter);
    tdlib.listeners().addOptionsListener(this);
    refresh(false);
  }

  @Override
  public void destroy () {
    tdlib.listeners().removeOptionListener(this);
    super.destroy();
  }

  private boolean sameAccount () {
    return userId != 0 && userId == tdlib.myUserId() && tdlib.isAuthorized();
  }

  @Override
  public void onGramRevenueStatusUpdated (TdApi.GramRevenueStatus updated) {
    runOnUiThreadOptional(() -> {
      if (sameAccount()) {
        revision++;
        status = updated;
        render();
      }
    });
  }

  static boolean canWithdraw (TdApi.GramRevenueStatus status) {
    return status != null && status.withdrawalEnabled && status.availableAmount >= MIN_WITHDRAWAL;
  }

  static boolean validWithdrawalUrl (String url) {
    if (url == null) {
      return false;
    }
    try {
      URI parsed = new URI(url);
      return "https".equalsIgnoreCase(parsed.getScheme()) && "fragment.com".equalsIgnoreCase(parsed.getRawAuthority());
    } catch (URISyntaxException error) {
      return false;
    }
  }

  private void refresh (boolean withdraw) {
    if (!sameAccount() || busy) {
      return;
    }
    busy = true;
    failed = false;
    long expected = revision;
    render();
    tdlib.send(new TdApi.GetGramRevenueStatistics(Theme.isDark()), (stats, error) -> runOnUiThreadOptional(() -> {
      if (!sameAccount()) {
        return;
      }
      busy = false;
      failed = stats == null;
      if (stats != null) {
        if (revision == expected) {
          status = stats.status;
        }
        dailyRevenue = readDailyRevenue(stats.revenueByDayGraph);
      }
      render();
      if (withdraw && isFocused()) {
        if (error != null) {
          UI.showError(error);
        } else if (canWithdraw(status)) {
          promptPassword();
        } else {
          UI.showToast(R.string.TgxTonWithdrawalUnavailable, Toast.LENGTH_LONG);
        }
      }
    }));
  }

  static List<RevenuePoint> readDailyRevenue (TdApi.StatisticalGraph graph) {
    if (!(graph instanceof TdApi.StatisticalGraphData data) || data.jsonData == null) {
      return Collections.emptyList();
    }
    try {
      JSONObject json = new JSONObject(data.jsonData);
      JSONArray columns = json.getJSONArray("columns");
      JSONObject types = json.getJSONObject("types");
      JSONArray dates = null;
      JSONArray values = null;
      for (int index = 0; index < columns.length(); index++) {
        JSONArray column = columns.getJSONArray(index);
        String type = types.optString(column.getString(0));
        if ("x".equals(type) && dates == null) {
          dates = column;
        } else if ("line".equals(type) || "bar".equals(type) || "area".equals(type)) {
          if (values != null) {
            return Collections.emptyList();
          }
          values = column;
        }
      }
      if (dates == null || values == null || dates.length() != values.length()) {
        return Collections.emptyList();
      }
      ArrayList<RevenuePoint> points = new ArrayList<>();
      for (int index = 1; index < dates.length(); index++) {
        long day = Long.parseLong(dates.get(index).toString());
        long amount = Long.parseLong(values.get(index).toString());
        if (day <= 0 || (index > 1 && day <= points.get(index - 2).dayMillis)) {
          return Collections.emptyList();
        }
        points.add(new RevenuePoint(day, amount));
      }
      return points;
    } catch (JSONException | NumberFormatException error) {
      return Collections.emptyList();
    }
  }

  private void addDailyRevenue (ArrayList<ListItem> rows) {
    if (dailyRevenue.isEmpty()) {
      return;
    }
    rows.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.TgxTonDailyRevenue));
    DateFormat format = DateFormat.getDateInstance(DateFormat.MEDIUM, Lang.dateFormatLocale());
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    int limit = allDays ? dailyRevenue.size() : Math.min(dailyRevenue.size(), RECENT_DAYS);
    for (int index = dailyRevenue.size() - 1; index >= dailyRevenue.size() - limit; index--) {
      RevenuePoint point = dailyRevenue.get(index);
      addAmount(rows, format.format(new Date(point.dayMillis)), point.amount);
    }
    if (dailyRevenue.size() > RECENT_DAYS) {
      rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonRevenueDays, 0,
        allDays ? R.string.TgxTonRevenueRecentDays : R.string.TgxTonRevenueAllDays));
    }
  }

  private void promptPassword () {
    busy = true;
    render();
    tdlib.send(new TdApi.GetPasswordState(), (passwordState, error) -> runOnUiThreadOptional(() -> {
      busy = false;
      if (!sameAccount()) {
        return;
      }
      render();
      if (!isFocused()) {
        return;
      }
      if (error != null) {
        UI.showError(error);
      } else if (!canWithdraw(status)) {
        UI.showToast(R.string.TgxTonWithdrawalUnavailable, Toast.LENGTH_LONG);
      } else if (!passwordState.hasPassword) {
        UI.showToast(R.string.TgxStarsWithdrawalNeedsPassword, Toast.LENGTH_LONG);
      } else {
        PasswordController controller = new PasswordController(context(), tdlib);
        controller.setArguments(new PasswordController.Args(PasswordController.MODE_CUSTOM_CONFIRM, passwordState)
          .setConfirmDelegate(new PasswordController.CustomConfirmDelegate() {
            @Override public boolean needNext () { return true; }
            @Override public void onPasswordConfirmed (ViewController<?> origin, String password) {
              openWithdrawal(origin, password);
            }
          }));
        navigateTo(controller);
      }
    }));
  }

  private void openWithdrawal (ViewController<?> origin, String password) {
    if (busy || !sameAccount() || !origin.isFocused() || origin.tdlib() != tdlib) {
      return;
    }
    if (!canWithdraw(status)) {
      UI.showToast(R.string.TgxTonWithdrawalUnavailable, Toast.LENGTH_LONG);
      return;
    }
    busy = true;
    tdlib.send(new TdApi.GetGramWithdrawalUrl(password), (result, error) -> runOnUiThreadOptional(() -> {
      busy = false;
      if (!sameAccount() || !origin.isFocused()) {
        return;
      }
      if (error != null) {
        UI.showError(error);
      } else if (result != null && validWithdrawalUrl(result.url)) {
        origin.navigateBack();
        Intents.openUriInBrowser(Uri.parse(result.url));
      } else {
        UI.showToast(R.string.TgxStarsUnexpectedWithdrawalUrl, Toast.LENGTH_LONG);
      }
    }));
  }

  private void render () {
    ArrayList<ListItem> rows = new ArrayList<>();
    if (sameAccount()) {
      if (status != null) {
        addAmount(rows, R.string.TgxTonRevenueTotal, status.totalAmount);
        addAmount(rows, R.string.TgxTonRevenueBalance, status.balanceAmount);
        addAmount(rows, R.string.TgxTonRevenueAvailable, status.availableAmount);
        if (!failed && !busy && canWithdraw(status)) {
          rows.add(new ListItem(ListItem.TYPE_BUTTON, R.id.btn_tonWithdraw, 0, R.string.TgxTonWithdraw));
        } else if (!failed && !busy) {
          rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.TgxTonWithdrawalUnavailable));
        }
      }
      addDailyRevenue(rows);
      if (failed) {
        rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.TgxTonRevenueError));
      }
      if (busy) {
        rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.LoadingInformation));
      } else {
        rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonRefresh, 0,
          failed ? R.string.TgxStarsRetry : R.string.TgxStarsRefresh));
      }
    }
    adapter.setItems(rows, false);
  }

  private static void addAmount (ArrayList<ListItem> rows, int label, long amount) {
    String formatted = Lang.getString(R.string.TgxTonAmount, TonWalletController.amount(amount));
    rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, Lang.getString(label, formatted)));
  }

  private static void addAmount (ArrayList<ListItem> rows, String caption, long amount) {
    String formatted = Lang.getString(R.string.TgxTonAmount, TonWalletController.amount(amount));
    rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, Lang.getString(R.string.TgxTonEntry, caption, formatted)));
  }

  @Override
  public void onClick (View view) {
    if (!isFocused() || !sameAccount() || busy) {
      return;
    }
    if (view.getId() == R.id.btn_tonRefresh) {
      refresh(false);
    } else if (view.getId() == R.id.btn_tonRevenueDays) {
      allDays = !allDays;
      render();
    } else if (view.getId() == R.id.btn_tonWithdraw && !failed && canWithdraw(status)) {
      showConfirm(Lang.getString(R.string.TgxTonWithdrawalConfirm), Lang.getString(R.string.Continue), () -> refresh(true));
    }
  }
}

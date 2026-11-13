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
import android.view.View;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

import me.vkryl.core.StringUtils;

public final class TonWalletController extends RecyclerViewController<Void> implements View.OnClickListener {
  private enum Page { OVERVIEW, TELEGRAM, WALLET, NFT, CONNECTIONS }
  private enum Direction { ALL, IN, OUT }
  private final long account;
  private final LinkedHashMap<String, TdApi.Object> entries = new LinkedHashMap<>();
  private final HashSet<String> cursors = new HashSet<>();
  private SettingsAdapter adapter;
  private final Runnable listener = () -> runOnUiThreadOptional(() -> {
    if (sameAccount() && adapter != null) {
      render();
    }
  });
  private Page page = Page.OVERVIEW;
  private Direction direction = Direction.ALL;
  private String offset = "";
  private Long balance;
  private int generation;
  private boolean loading, failed;

  public TonWalletController (Context context, Tdlib tdlib) {
    super(context, tdlib);
    account = tdlib.myUserId();
  }

  @Override public int getId () { return R.id.controller_tonWallet; }
  @Override public CharSequence getName () { return Lang.getString(R.string.TgxTonWallet); }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recycler) {
    adapter = new SettingsAdapter(this);
    recycler.setAdapter(adapter);
    tdlib.addTonWalletListener(listener);
    show(Page.OVERVIEW);
  }

  @Override
  public void destroy () {
    tdlib.removeTonWalletListener(listener);
    super.destroy();
  }

  private boolean sameAccount () {
    return account != 0 && account == tdlib.myUserId() && tdlib.isAuthorized();
  }

  private void show (Page selection) {
    page = selection;
    generation++;
    entries.clear();
    cursors.clear();
    offset = "";
    balance = null;
    loading = failed = false;
    render();
    if (sameAccount() && selection == Page.OVERVIEW) {
      int expected = generation;
      tdlib.send(new TdApi.GetTonTransactions(null, "", 1), (result, error) -> runOnUiThreadOptional(() -> {
        if (sameAccount() && expected == generation && result != null) {
          balance = result.gramAmount;
          render();
        }
      }));
      if (tdlib.options().canUseTonWallet) {
        tdlib.send(new TdApi.LoadTonWalletState(), (ok, error) -> runOnUiThreadOptional(() -> {
          if (sameAccount() && expected == generation && error != null && isFocused()) {
            UI.showError(error);
          }
        }));
        tdlib.send(new TdApi.LoadTonWalletGaslessTransfersInfo(), (ok, error) -> {});
      }
    } else if (selection != Page.OVERVIEW) {
      load();
    }
  }

  static String amount (long smallestUnits) {
    return BigDecimal.valueOf(smallestUnits, 9).stripTrailingZeros().toPlainString();
  }

  private static String ton (long amount) {
    return Lang.getString(R.string.TgxTonAmount, amount(amount));
  }

  private void load () {
    if (!sameAccount() || loading || offset == null || page == Page.OVERVIEW ||
        (page != Page.TELEGRAM && !tdlib.options().canUseTonWallet)) {
      return;
    }
    loading = true;
    failed = false;
    render();
    int expected = generation;
    String cursor = offset;
    TdApi.TransactionDirection filter = direction == Direction.IN ? new TdApi.TransactionDirectionIncoming() :
      direction == Direction.OUT ? new TdApi.TransactionDirectionOutgoing() : null;
    if (page == Page.TELEGRAM) {
      tdlib.send(new TdApi.GetTonTransactions(filter, cursor, 30), (result, error) -> runOnUiThreadOptional(() -> {
        if (!sameAccount() || expected != generation) {
          return;
        }
        if (result != null) {
          balance = result.gramAmount;
          for (TdApi.TonTransaction entry : result.transactions) {
            entries.put((entry.isRefund ? "refund:" : "transaction:") + entry.id, entry);
          }
        }
        finish(cursor, result != null ? result.nextOffset : null, error);
      }));
    } else if (page == Page.WALLET) {
      tdlib.send(new TdApi.GetTonWalletTransactions(filter, cursor, 30), (result, error) -> runOnUiThreadOptional(() -> {
        if (!sameAccount() || expected != generation) {
          return;
        }
        if (result != null) {
          balance = result.gramAmount;
          for (TdApi.TonWalletTransaction entry : result.transactions) {
            entries.put(entry.id, entry);
          }
        }
        finish(cursor, result != null ? result.nextOffset : null, error);
      }));
    } else if (page == Page.CONNECTIONS) {
      tdlib.send(new TdApi.GetTonConnectSessions(), (result, error) -> runOnUiThreadOptional(() -> {
        if (!sameAccount() || expected != generation) {
          return;
        }
        if (result != null) {
          for (TdApi.TonConnectSession session : result.sessions) {
            if (!(session.state instanceof TdApi.TonConnectSessionStateClosed)) {
              entries.put(Long.toString(session.id), session);
            }
          }
        }
        finish(cursor, null, error);
      }));
    } else {
      tdlib.send(new TdApi.GetTonWalletNfts(cursor, 30), (result, error) -> runOnUiThreadOptional(() -> {
        if (!sameAccount() || expected != generation) {
          return;
        }
        if (result != null) {
          for (TdApi.TonNft entry : result.nfts) {
            entries.put(entry.address, entry);
          }
        }
        finish(cursor, result != null ? result.nextOffset : null, error);
      }));
    }
  }

  private void finish (String cursor, String next, TdApi.Error error) {
    loading = false;
    failed = error != null;
    if (failed) {
      if (isFocused()) {
        UI.showError(error);
      }
    } else {
      cursors.add(cursor);
      offset = !StringUtils.isEmpty(next) && !cursors.contains(next) ? next : null;
    }
    render();
  }

  private void render () {
    ArrayList<ListItem> rows = new ArrayList<>();
    if (!sameAccount()) {
      adapter.setItems(rows, false);
      return;
    }
    if (page == Page.OVERVIEW) {
      Long current = tdlib.ownedGramAmount();
      if (current == null) {
        current = balance;
      }
      rows.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.TgxTonTelegramBalance));
      if (current != null) {
        rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, ton(current)));
      }
      rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonLedger, 0, R.string.TgxTonLedger));
      if (tdlib.options().canUseTonWallet) {
        TdApi.TonWalletState state = tdlib.tonWalletState();
        rows.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.TgxTonBlockchainWallet));
        if (state != null && !StringUtils.isEmpty(state.address)) {
          rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, ton(state.gramAmount)));
          rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonCopyAddress, 0, R.string.TgxTonCopyAddress));
          if (state.isBackupEnabled) {
            rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.TgxTonBackupEnabled));
          }
          TdApi.TonWalletGaslessTransfersInfo gasless = tdlib.tonGaslessTransfers();
          if (gasless != null && gasless.leftCount >= 0) {
            rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0,
              Lang.getString(R.string.TgxTonGasless, Lang.formatNumber(gasless.leftCount))));
          }
          rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonWalletHistory, 0, R.string.TgxTonWalletHistory));
          rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonNfts, 0, R.string.TgxTonNfts));
          rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonConnections, 0, R.string.TgxTonConnections));
        } else if (state == null || state.isBeingCreated) {
          rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.LoadingInformation));
        } else {
          rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.TgxTonWalletMissing));
        }
      }
    } else {
      rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonOverview, 0, R.string.TgxTonOverview));
      if (balance != null) {
        rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, ton(balance)));
      }
      if (page == Page.TELEGRAM || page == Page.WALLET) {
        rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonDirection, 0, directionText(direction)));
      }
      for (TdApi.Object entry : entries.values()) {
        CharSequence label;
        if (entry instanceof TdApi.TonTransaction ledger) {
          label = Lang.getString(R.string.TgxTonEntry, date(ledger.date), ton(ledger.gramAmount));
        } else if (entry instanceof TdApi.TonWalletTransaction wallet) {
          label = Lang.getString(R.string.TgxTonEntry, date(wallet.date), walletLabel(wallet));
        } else if (entry instanceof TdApi.TonConnectSession session) {
          label = Lang.getString(R.string.TgxTonEntry, sessionName(session), sessionState(session.state));
        } else {
          TdApi.TonNft nft = (TdApi.TonNft) entry;
          label = StringUtils.isEmpty(nft.name) ? nft.address : nft.name;
        }
        rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonEntry, 0, label).setData(entry));
      }
      if (loading) {
        rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.LoadingInformation));
      } else if (offset != null || failed) {
        rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonMore, 0, failed ? R.string.RetryChatList : R.string.TgxStarsMore));
      } else if (entries.isEmpty()) {
        rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.TgxTonEmpty));
      }
    }
    rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonRefresh, 0, R.string.TgxPremiumRefreshStars));
    adapter.setItems(rows, false);
  }

  private static String date (int date) {
    return Lang.dateYearShortTime(date, TimeUnit.SECONDS);
  }

  private String walletLabel (TdApi.TonWalletTransaction transaction) {
    if (transaction.type instanceof TdApi.TonWalletTransactionTypeTransfer transfer) {
      return ton(transfer.amount);
    }
    if (transaction.type instanceof TdApi.TonWalletTransactionTypeOnRampDeposit deposit) {
      return ton(deposit.amount);
    }
    return Lang.getString(transaction.type instanceof TdApi.TonWalletTransactionTypeKeyChange ?
      R.string.TgxTonKeyChange : R.string.TgxTonNftTransfer);
  }

  private static String directionText (Direction direction) {
    return Lang.getString(direction == Direction.IN ? R.string.TgxTonIncoming :
      direction == Direction.OUT ? R.string.TgxTonOutgoing : R.string.TgxTonAll);
  }

  private static String sessionName (TdApi.TonConnectSession session) {
    if (session.manifest instanceof TdApi.TonConnectManifestInfo app && !StringUtils.isEmpty(app.name)) {
      return app.name;
    }
    return Lang.getString(R.string.TgxTonUnknownApp);
  }

  private static String sessionState (TdApi.TonConnectSessionState state) {
    int text = switch (state) {
      case TdApi.TonConnectSessionStateReady ignored -> R.string.TgxTonConnectionReady;
      case TdApi.TonConnectSessionStateClosing ignored -> R.string.TgxTonConnectionClosing;
      case TdApi.TonConnectSessionStateClosed ignored -> R.string.TgxTonConnectionClosed;
      default -> R.string.TgxTonConnectionPending;
    };
    return Lang.getString(text);
  }

  @Override
  public void onClick (View view) {
    if (!isFocused() || !sameAccount()) {
      return;
    }
    int id = view.getId();
    if (id == R.id.btn_tonRefresh) {
      show(page);
    } else if (id == R.id.btn_tonOverview) {
      show(Page.OVERVIEW);
    } else if (id == R.id.btn_tonLedger || id == R.id.btn_tonWalletHistory || id == R.id.btn_tonNfts) {
      direction = Direction.ALL;
      show(id == R.id.btn_tonLedger ? Page.TELEGRAM : id == R.id.btn_tonNfts ? Page.NFT : Page.WALLET);
    } else if (id == R.id.btn_tonConnections && tdlib.options().canUseTonWallet) {
      show(Page.CONNECTIONS);
    } else if (id == R.id.btn_tonMore) {
      load();
    } else if (id == R.id.btn_tonCopyAddress) {
      TdApi.TonWalletState state = tdlib.tonWalletState();
      if (tdlib.options().canUseTonWallet && state != null && !StringUtils.isEmpty(state.address)) {
        showConfirm(state.address, Lang.getString(R.string.Copy), () -> {
          TdApi.TonWalletState fresh = tdlib.tonWalletState();
          if (sameAccount() && tdlib.options().canUseTonWallet && fresh != null && state.address.equals(fresh.address)) {
            UI.copyText(fresh.address, R.string.CopiedText);
          }
        });
      }
    } else if (id == R.id.btn_tonDirection) {
      Options.Builder options = new Options.Builder();
      Direction[] values = Direction.values();
      for (int i = 0; i < values.length; i++) {
        options.item(new OptionItem(i + 1, directionText(values[i]), OptionColor.NORMAL, 0));
      }
      options.cancelItem();
      showOptions(options.build(), (v, choice) -> {
        if (choice > 0 && choice <= values.length && sameAccount()) {
          direction = values[choice - 1];
          show(page);
        }
        return true;
      });
    } else if (id == R.id.btn_tonEntry && view.getTag() instanceof ListItem row) {
      showDetails(row.getData());
    }
  }

  private void showDetails (Object data) {
    if (data instanceof TdApi.TonTransaction telegram) {
      showCopyDetails(Lang.getString(R.string.TgxTonEntry, date(telegram.date), ton(telegram.gramAmount)) +
        (telegram.isRefund ? "\n" + Lang.getString(R.string.TgxTonRefund) : ""), telegram.id);
    } else if (data instanceof TdApi.TonWalletTransaction wallet) {
      String info = Lang.getString(R.string.TgxTonEntry, date(wallet.date), walletLabel(wallet)) + "\n" +
        Lang.getString(R.string.TgxTonFee, ton(wallet.feeAmount)) + "\n" +
        Lang.getString(wallet.state instanceof TdApi.TonWalletTransactionStateSucceeded ?
          R.string.TgxTonCompleted : R.string.TgxTonFailed);
      if (!StringUtils.isEmpty(wallet.peerAddress)) {
        info += "\n" + wallet.peerAddress;
      }
      showCopyDetails(info, wallet.id);
    } else if (data instanceof TdApi.TonConnectSession session && tdlib.options().canUseTonWallet) {
      String info = sessionName(session) + "\n" + sessionState(session.state);
      if (session.manifest instanceof TdApi.TonConnectManifestInfo app && !StringUtils.isEmpty(app.url)) {
        info += "\n" + app.url;
      }
      if (session.creationDate > 0) {
        info += "\n" + Lang.getString(R.string.TgxTonConnectionCreated, date(session.creationDate));
      }
      showOptions(new Options.Builder().info(info).cancelItem().build(), (view, id) -> true);
    } else if (data instanceof TdApi.TonNft nft) {
      Options.Builder options = new Options.Builder().info(nft.name + "\n" + nft.address);
      if (!StringUtils.isEmpty(nft.address)) {
        options.item(new OptionItem(1, Lang.getString(R.string.Copy), OptionColor.NORMAL, 0));
      }
      if (nft.gift != null && !StringUtils.isEmpty(nft.gift.name)) {
        options.item(new OptionItem(2, Lang.getString(R.string.TgxStarsViewCollectible), OptionColor.NORMAL, 0));
      }
      options.cancelItem();
      showOptions(options.build(), (view, id) -> {
        if (sameAccount() && id == 1) {
          UI.copyText(nft.address, R.string.CopiedText);
        } else if (sameAccount() && id == 2) {
          navigateTo(new StarsCollectibleController(context(), tdlib, nft.gift.name, account));
        }
        return true;
      });
    }
  }

  private void showCopyDetails (String caption, String id) {
    showConfirm(caption + "\n" + id, Lang.getString(R.string.Copy), () -> {
      if (sameAccount()) {
        UI.copyText(id, R.string.CopiedText);
      }
    });
  }
}

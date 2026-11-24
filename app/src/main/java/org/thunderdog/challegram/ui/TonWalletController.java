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

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

import me.vkryl.core.StringUtils;

// @MadeByLLM("gpt-6", id = "stars-development")
public final class TonWalletController extends RecyclerViewController<Void> implements View.OnClickListener, Tdlib.TonConnectSessionListener {
  private enum Page { OVERVIEW, TELEGRAM, WALLET, NFT, CONNECTIONS }
  private enum Direction { ALL, IN, OUT }
  private final long account;
  private final String initialTransactionId;
  private final LinkedHashMap<String, TdApi.Object> entries = new LinkedHashMap<>();
  private final HashSet<Long> liveSessionIds = new HashSet<>();
  private final HashSet<String> cursors = new HashSet<>();
  private SettingsAdapter adapter;
  private final Runnable listener = () -> runOnUiThreadOptional(() -> {
    if (sameAccount() && adapter != null) {
      refreshWalletOptions();
    }
  });
  private Page page = Page.OVERVIEW;
  private boolean walletAvailable;
  private Direction direction = Direction.ALL;
  private String offset = "";
  private Long balance;
  private int generation;
  private boolean loading, failed, openedInitialTransaction;

  public TonWalletController (Context context, Tdlib tdlib) {
    this(context, tdlib, null);
  }

  public TonWalletController (Context context, Tdlib tdlib, String initialTransactionId) {
    super(context, tdlib);
    account = tdlib.myUserId();
    this.initialTransactionId = initialTransactionId;
  }

  @Override public int getId () { return R.id.controller_tonWallet; }
  @Override public CharSequence getName () { return Lang.getString(R.string.TgxTonWallet); }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recycler) {
    adapter = new SettingsAdapter(this);
    recycler.setAdapter(adapter);
    tdlib.addTonWalletListener(listener);
    tdlib.addTonConnectSessionListener(this);
    show(Page.OVERVIEW);
  }

  @Override
  public void onFocus () {
    super.onFocus();
    if (!openedInitialTransaction && !StringUtils.isEmpty(initialTransactionId) &&
        sameAccount() && tdlib.options().canUseTonWallet) {
      openedInitialTransaction = true;
      loadTransaction(initialTransactionId);
    }
  }

  private void loadTransaction (String id) {
    int expected = generation;
    tdlib.send(new TdApi.GetTonWalletTransaction(id), (transaction, error) -> runOnUiThreadOptional(() -> {
      if (!isFocused() || !sameAccount() || !tdlib.options().canUseTonWallet || expected != generation) {
        return;
      }
      if (transaction != null && id.equals(transaction.id)) {
        showDetails(transaction);
      } else if (error != null) {
        UI.showError(error);
      }
    }));
  }

  @Override
  public void destroy () {
    tdlib.removeTonWalletListener(listener);
    tdlib.removeTonConnectSessionListener(this);
    super.destroy();
  }

  private void refreshWalletOptions () {
    if (page == Page.OVERVIEW && walletAvailable != tdlib.options().canUseTonWallet) {
      show(Page.OVERVIEW);
    } else {
      render();
    }
  }

  private boolean sameAccount () {
    return account != 0 && account == tdlib.myUserId() && tdlib.isAuthorized();
  }

  private void show (Page selection) {
    page = selection;
    walletAvailable = tdlib.options().canUseTonWallet;
    generation++;
    entries.clear();
    liveSessionIds.clear();
    cursors.clear();
    offset = "";
    balance = null;
    loading = failed = false;
    render();
    if (sameAccount() && selection == Page.OVERVIEW) {
      int expected = generation;
      tdlib.send(new TdApi.GetTonTransactions(null, "", 1), (result, error) -> runOnUiThreadOptional(() -> {
        if (sameAccount() && expected == generation) {
          if (result != null) {
            balance = result.gramAmount;
            render();
          } else if (error != null && isFocused() && tdlib.ownedGramAmount() == null) {
            UI.showError(error);
          }
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

  public static String unsignedAmount (long amount) {
    return BigDecimal.valueOf(amount, 9).abs().stripTrailingZeros().toPlainString();
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
            if (!liveSessionIds.contains(session.id)) {
              applySession(session);
            }
          }
        }
        finish(cursor, null, error);
      }));
    } else {
      tdlib.send(new TdApi.GetTonWalletNfts(cursor, 20), (result, error) -> runOnUiThreadOptional(() -> {
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

  @Override
  public void onTonConnectSessionChanged (TdApi.TonConnectSession session) {
    runOnUiThreadOptional(() -> {
      if (page == Page.CONNECTIONS && sameAccount() && tdlib.options().canUseTonWallet) {
        if (loading) {
          liveSessionIds.add(session.id);
        }
        applySession(session);
        render();
      }
    });
  }

  private void applySession (TdApi.TonConnectSession session) {
    String key = Long.toString(session.id);
    if (session.state instanceof TdApi.TonConnectSessionStateClosed) {
      entries.remove(key);
    } else {
      entries.put(key, session);
    }
  }

  static boolean pendingRequest (TdApi.TonConnectRequest request, long sessionId, long now) {
    return request != null && request.sessionId == sessionId &&
      (request.expirationDate == 0 || request.expirationDate > now);
  }

  private void showPendingRequests (TdApi.TonConnectSession session) {
    int expected = generation;
    tdlib.send(new TdApi.GetTonConnectSessionPendingRequests(session.id), (result, error) -> runOnUiThreadOptional(() -> {
      if (!isFocused() || !sameAccount() || page != Page.CONNECTIONS || expected != generation ||
          !tdlib.options().canUseTonWallet ||
          !(entries.get(Long.toString(session.id)) instanceof TdApi.TonConnectSession current) ||
          !(current.state instanceof TdApi.TonConnectSessionStateReady)) {
        return;
      }
      if (error != null) {
        UI.showError(error);
        return;
      }
      if (result == null || result.session == null || result.session.id != session.id ||
          !(result.session.state instanceof TdApi.TonConnectSessionStateReady)) {
        return;
      }
      long now = tdlib.options().unixTime.currentTimeMillis() / 1000L;
      StringBuilder info = new StringBuilder(sessionName(result.session));
      int count = 0;
      for (TdApi.TonConnectRequest request : result.requests) {
        if (pendingRequest(request, session.id, now)) {
          count++;
          info.append("\n\n").append(request.expirationDate == 0 ?
            Lang.getString(R.string.TgxTonConnectNoExpiry) :
            Lang.getString(R.string.TgxTonConnectExpires, date(request.expirationDate)));
        }
      }
      info.insert(0, Lang.getString(R.string.TgxTonConnectRequests, Lang.formatNumber(count)) + "\n");
      showOptions(new Options.Builder().info(info).cancelItem().build(), (view, id) -> true);
    }));
  }

  private void finish (String cursor, String next, TdApi.Error error) {
    liveSessionIds.clear();
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
      if (httpsUrl(tdlib.options().gramTopUpUrl) != null) {
        rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonBalanceTopUp, 0, R.string.TgxTonBalanceTopUp));
      }
      rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonLedger, 0, R.string.TgxTonLedger));
      rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonRevenue, 0, R.string.TgxTonRevenue));
      if (tdlib.options().canUseTonWallet) {
        TdApi.TonWalletState state = tdlib.tonWalletState();
        rows.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.TgxTonBlockchainWallet));
        if (state != null && !StringUtils.isEmpty(state.address)) {
          rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, ton(state.gramAmount)));
          rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonCopyAddress, 0, R.string.TgxTonReceive));
          if (state.isBackupEnabled) {
            rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.TgxTonBackupEnabled));
          }
          TdApi.TonWalletGaslessTransfersInfo gasless = tdlib.tonGaslessTransfers();
          if (gasless != null && gasless.leftCount >= 0) {
            rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0,
              Lang.getString(R.string.TgxTonGasless, Lang.formatNumber(gasless.leftCount))));
          }
          rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonBuyOnChain, 0, R.string.TgxTonBuyOnChain));
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
          label = Lang.getString(R.string.TgxTonEntry, Lang.getString(ledgerType(ledger.type)), ton(ledger.gramAmount));
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
        rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonMore, 0, failed ? R.string.TgxStarsRetry : R.string.TgxStarsMore));
      } else if (entries.isEmpty()) {
        rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.TgxTonEmpty));
      }
    }
    rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_tonRefresh, 0, R.string.TgxStarsRefresh));
    adapter.setItems(rows, false);
  }

  private static String date (int date) {
    return Lang.dateYearShortTime(date, TimeUnit.SECONDS);
  }

  static int ledgerType (TdApi.TonTransactionType type) {
    return switch (type) {
      case TdApi.TonTransactionTypeFragmentDeposit deposit -> deposit.isGift ? R.string.TgxTonGiftDeposit : R.string.TgxTonFragmentDeposit;
      case TdApi.TonTransactionTypeFragmentWithdrawal ignored -> R.string.TgxTonFragmentWithdrawal;
      case TdApi.TonTransactionTypeSuggestedPostPayment ignored -> R.string.TgxTonSuggestedPostPayment;
      case TdApi.TonTransactionTypeGiftPurchaseOffer ignored -> R.string.TgxTonGiftOffer;
      case TdApi.TonTransactionTypeUpgradedGiftPurchase ignored -> R.string.TgxTonGiftPurchase;
      case TdApi.TonTransactionTypeUpgradedGiftSale ignored -> R.string.TgxTonGiftSale;
      case TdApi.TonTransactionTypeStakeDiceStake ignored -> R.string.TgxTonDiceStake;
      case TdApi.TonTransactionTypeStakeDicePayout ignored -> R.string.TgxTonDicePayout;
      case null, default -> R.string.TgxTonTransaction;
    };
  }

  static TdApi.UpgradedGift transactionGift (TdApi.TonTransactionType type) {
    return switch (type) {
      case TdApi.TonTransactionTypeGiftPurchaseOffer offer -> offer.gift;
      case TdApi.TonTransactionTypeUpgradedGiftPurchase purchase -> purchase.gift;
      case TdApi.TonTransactionTypeUpgradedGiftSale sale -> sale.gift;
      case null, default -> null;
    };
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
    } else if (id == R.id.btn_tonBalanceTopUp) {
      String url = tdlib.options().gramTopUpUrl;
      URI parsed = httpsUrl(url);
      if (parsed != null) {
        confirmExternal(R.string.TgxTonTopUpOpen, parsed, () -> url.equals(tdlib.options().gramTopUpUrl));
      }
    } else if (id == R.id.btn_tonRevenue) {
      navigateTo(new TonRevenueController(context(), tdlib));
    } else if (id == R.id.btn_tonConnections && tdlib.options().canUseTonWallet) {
      show(Page.CONNECTIONS);
    } else if (id == R.id.btn_tonBuyOnChain && tdlib.options().canUseTonWallet) {
      TdApi.TonWalletState state = tdlib.tonWalletState();
      if (state != null && !StringUtils.isEmpty(state.address)) {
        navigateTo(new TonOnRampController(context(), tdlib, account, state.address));
      }
    } else if (id == R.id.btn_tonMore) {
      load();
    } else if (id == R.id.btn_tonCopyAddress) {
      TdApi.TonWalletState state = tdlib.tonWalletState();
      if (tdlib.options().canUseTonWallet && state != null && !StringUtils.isEmpty(state.address)) {
        showReceive(state.address);
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

  static URI httpsUrl (String value) {
    if (value == null) {
      return null;
    }
    try {
      URI url = new URI(value);
      if ("https".equalsIgnoreCase(url.getScheme()) && url.getHost() != null &&
          url.getRawUserInfo() == null && (url.getPort() == -1 || url.getPort() == 443)) {
        return url;
      }
    } catch (URISyntaxException ignored) { }
    return null;
  }

  static URI explorerAddress (String prefix, String address) {
    URI base = httpsUrl(prefix);
    if (base == null || base.getRawFragment() != null || address == null ||
        !address.matches("(?:[A-Za-z0-9_-]{48}|-?[0-9]{1,3}:[a-fA-F0-9]{64})")) {
      return null;
    }
    URI result = httpsUrl(prefix + address.replace(":", "%3A"));
    return result != null && base.getRawAuthority().equals(result.getRawAuthority()) ? result : null;
  }

  private void confirmExternal (int message, URI url, java.util.function.BooleanSupplier stillAllowed) {
    showConfirm(Lang.getString(message, url.getHost()), Lang.getString(R.string.Continue), () -> {
      if (sameAccount() && isFocused() && stillAllowed.getAsBoolean()) {
        Intents.openUriInBrowser(Uri.parse(url.toASCIIString()));
      }
    });
  }

  private boolean hasWalletAddress (String expected) {
    TdApi.TonWalletState state = tdlib.tonWalletState();
    return sameAccount() && tdlib.options().canUseTonWallet && state != null && expected.equals(state.address);
  }

  private void showReceive (String address) {
    String prefix = tdlib.options().tonBlockchainExplorerUrl;
    URI explorer = explorerAddress(prefix, address);
    Options.Builder options = new Options.Builder().info(Lang.getString(R.string.TgxTonReceiveInfo, address))
      .item(new OptionItem(1, Lang.getString(R.string.Copy), OptionColor.NORMAL, 0))
      .item(new OptionItem(2, Lang.getString(R.string.Share), OptionColor.NORMAL, 0));
    if (explorer != null) {
      options.item(new OptionItem(3, Lang.getString(R.string.TgxTonExplorer), OptionColor.NORMAL, 0));
    }
    showOptions(options.cancelItem().build(), (view, id) -> {
      if (hasWalletAddress(address)) {
        if (id == 1) {
          UI.copyText(address, R.string.CopiedText);
        } else if (id == 2) {
          Intents.shareText(address);
        } else if (id == 3 && explorer != null && prefix.equals(tdlib.options().tonBlockchainExplorerUrl)) {
          confirmExternal(R.string.TgxTonExplorerOpen, explorer,
            () -> hasWalletAddress(address) && prefix.equals(tdlib.options().tonBlockchainExplorerUrl));
        }
      }
      return true;
    });
  }

  private void showDetails (Object data) {
    if (data instanceof TdApi.TonTransaction telegram) {
      showLedgerDetails(telegram);
    } else if (data instanceof TdApi.TonWalletTransaction wallet) {
      String info = Lang.getString(R.string.TgxTonEntry, date(wallet.date), walletLabel(wallet)) + "\n" +
        Lang.getString(R.string.TgxTonFee, ton(wallet.feeAmount)) + "\n" +
        Lang.getString(wallet.state instanceof TdApi.TonWalletTransactionStateSucceeded ?
          R.string.TgxTonCompleted : R.string.TgxTonFailed);
      if (!StringUtils.isEmpty(wallet.peerAddress)) {
        info += "\n" + wallet.peerAddress;
      }
      String copyId = wallet.id;
      if (wallet.state instanceof TdApi.TonWalletTransactionStateSucceeded succeeded && !StringUtils.isEmpty(succeeded.txHash)) {
        copyId = succeeded.txHash;
      }
      if (!StringUtils.isEmpty(wallet.peerDomain)) {
        info += "\n" + wallet.peerDomain;
      }
      if (wallet.type instanceof TdApi.TonWalletTransactionTypeTransfer transfer) {
        info = addComment(info, transfer.comment, transfer.isCommentEncrypted);
        if (transfer.isGasless) {
          info += "\n" + Lang.getString(R.string.TgxTonGaslessTransaction);
        }
      } else if (wallet.type instanceof TdApi.TonWalletTransactionTypeNftTransfer transfer) {
        info = addComment(info, transfer.comment, transfer.isCommentEncrypted);
      }
      showCopyDetails(info, copyId);
    } else if (data instanceof TdApi.TonConnectSession session && tdlib.options().canUseTonWallet) {
      String info = sessionName(session) + "\n" + sessionState(session.state);
      if (session.manifest instanceof TdApi.TonConnectManifestInfo app && !StringUtils.isEmpty(app.url)) {
        info += "\n" + app.url;
      }
      if (session.creationDate > 0) {
        info += "\n" + Lang.getString(R.string.TgxTonConnectionCreated, date(session.creationDate));
      }
      Options.Builder options = new Options.Builder().info(info);
      if (session.state instanceof TdApi.TonConnectSessionStateReady) {
        options.item(new OptionItem(1, Lang.getString(R.string.TgxTonConnectViewRequests), OptionColor.NORMAL, 0));
      }
      showOptions(options.cancelItem().build(), (view, id) -> {
        if (id == 1 && isFocused() && sameAccount() && page == Page.CONNECTIONS &&
            tdlib.options().canUseTonWallet &&
            entries.get(Long.toString(session.id)) instanceof TdApi.TonConnectSession current &&
            current.state instanceof TdApi.TonConnectSessionStateReady) {
          showPendingRequests(current);
        }
        return true;
      });
    } else if (data instanceof TdApi.TonNft nft) {
      Options.Builder options = new Options.Builder().info(nft.name + "\n" + nft.address);
      if (!StringUtils.isEmpty(nft.address)) {
        options.item(new OptionItem(1, Lang.getString(R.string.Copy), OptionColor.NORMAL, 0));
      }
      if (nft.gift != null && !StringUtils.isEmpty(nft.gift.name)) {
        options.item(new OptionItem(2, Lang.getString(R.string.TgxStarsViewCollectible), OptionColor.NORMAL, 0));
      }
      String prefix = tdlib.options().tonBlockchainExplorerUrl;
      URI explorer = explorerAddress(prefix, nft.address);
      if (explorer != null) {
        options.item(new OptionItem(3, Lang.getString(R.string.TgxTonExplorer), OptionColor.NORMAL, 0));
      }
      options.cancelItem();
      showOptions(options.build(), (view, id) -> {
        if (sameAccount() && id == 1) {
          UI.copyText(nft.address, R.string.CopiedText);
        } else if (sameAccount() && id == 2) {
          navigateTo(new StarsCollectibleController(context(), tdlib, nft.gift.name, account));
        } else if (sameAccount() && id == 3 && explorer != null && prefix.equals(tdlib.options().tonBlockchainExplorerUrl)) {
          confirmExternal(R.string.TgxTonExplorerOpen, explorer,
            () -> tdlib.options().canUseTonWallet && prefix.equals(tdlib.options().tonBlockchainExplorerUrl));
        }
        return true;
      });
    }
  }

  private static String addComment (String info, String comment, boolean encrypted) {
    if (encrypted) {
      return info + "\n" + Lang.getString(R.string.TgxTonEncryptedComment);
    }
    return StringUtils.isEmpty(comment) ? info : info + "\n" + comment;
  }

  private void showLedgerDetails (TdApi.TonTransaction transaction) {
    StringBuilder info = new StringBuilder(Lang.getString(ledgerType(transaction.type)))
      .append('\n').append(Lang.getString(R.string.TgxTonEntry, date(transaction.date), ton(transaction.gramAmount)));
    if (transaction.isRefund) {
      info.append('\n').append(Lang.getString(R.string.TgxTonRefund));
    }
    if (transaction.type instanceof TdApi.TonTransactionTypeFragmentWithdrawal withdrawal && withdrawal.withdrawalState != null) {
      int state = switch (withdrawal.withdrawalState) {
        case TdApi.RevenueWithdrawalStatePending ignored -> R.string.TgxTonPending;
        case TdApi.RevenueWithdrawalStateSucceeded ignored -> R.string.TgxTonCompleted;
        default -> R.string.TgxTonFailed;
      };
      info.append('\n').append(Lang.getString(state));
    } else if (transaction.type instanceof TdApi.TonTransactionTypeUpgradedGiftSale sale && sale.commissionGramAmount > 0) {
      info.append('\n').append(Lang.getString(R.string.TgxTonCommission, ton(sale.commissionGramAmount)));
    }
    if (transaction.type instanceof TdApi.TonTransactionTypeSuggestedPostPayment payment) {
      TdApi.Chat chat = tdlib.chat(payment.chatId);
      if (chat != null && !StringUtils.isEmpty(chat.title)) {
        info.append('\n').append(chat.title);
      }
    }
    TdApi.UpgradedGift gift = transactionGift(transaction.type);
    if (gift == null || StringUtils.isEmpty(gift.name)) {
      showCopyDetails(info.toString(), transaction.id);
      return;
    }
    if (!StringUtils.isEmpty(gift.title)) {
      info.append('\n').append(gift.title);
    }
    Options.Builder options = new Options.Builder().info(info)
      .item(new OptionItem(1, Lang.getString(R.string.TgxStarsViewCollectible), OptionColor.NORMAL, 0));
    if (!StringUtils.isEmpty(transaction.id)) {
      options.item(new OptionItem(2, Lang.getString(R.string.Copy), OptionColor.NORMAL, 0));
    }
    showOptions(options.cancelItem().build(), (view, id) -> {
      if (sameAccount() && id == 1) {
        navigateTo(new StarsCollectibleController(context(), tdlib, gift.name, account));
      } else if (sameAccount() && id == 2) {
        UI.copyText(transaction.id, R.string.CopiedText);
      }
      return true;
    });
  }

  private void showCopyDetails (String caption, String id) {
    if (StringUtils.isEmpty(id)) {
      showOptions(new Options.Builder().info(caption).cancelItem().build(), (view, choice) -> true);
      return;
    }
    showConfirm(caption + "\n" + id, Lang.getString(R.string.Copy), () -> {
      if (sameAccount()) {
        UI.copyText(id, R.string.CopiedText);
      }
    });
  }
}

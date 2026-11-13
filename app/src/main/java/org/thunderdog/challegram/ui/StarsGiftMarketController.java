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
import android.text.InputType;
import android.view.View;
import android.widget.Toast;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.util.SenderPickerDelegate;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Objects;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

import me.vkryl.core.StringUtils;

final class StarsGiftMarketController extends RecyclerViewController<Void> implements View.OnClickListener {
  private final long giftId, recipientId, account;
  private final TdApi.MessageSender profile;
  private final LinkedHashMap<String, TdApi.Object> entries = new LinkedHashMap<>();
  private final HashSet<String> cursors = new HashSet<>();
  private String offset = "";
  private SettingsAdapter adapter;
  private boolean loading, failed, saving;

  StarsGiftMarketController (Context context, Tdlib tdlib, long giftId, long recipientId) {
    this(context, tdlib, giftId, recipientId, null);
  }

  StarsGiftMarketController (Context context, Tdlib tdlib, TdApi.MessageSender profile) {
    this(context, tdlib, 0, tdlib.myUserId(), profile);
  }

  private StarsGiftMarketController (Context context, Tdlib tdlib, long giftId, long recipientId, TdApi.MessageSender profile) {
    super(context, tdlib);
    this.profile = profile;
    this.giftId = giftId;
    this.recipientId = recipientId;
    this.account = tdlib.myUserId();
  }

  private boolean owned () {
    return giftId == 0 && profile == null;
  }

  @Override public int getId () { return R.id.controller_giftMarket; }
  @Override public CharSequence getName () {
    return Lang.getString(profile != null ? R.string.TgxStarsPublicCollectibles :
      owned() ? R.string.TgxStarsReceivedGifts : R.string.TgxStarsResale);
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recycler) {
    adapter = new SettingsAdapter(this);
    recycler.setAdapter(adapter);
    load();
  }

  private void load () {
    if (loading || saving || offset == null || account == 0 || account != tdlib.myUserId()) {
      return;
    }
    final String cursor = offset;
    loading = true;
    failed = false;
    render();
    if (owned() || profile != null) {
      tdlib.send(new TdApi.GetReceivedGifts("", owned() ? new TdApi.MessageSenderUser(account) : profile, 0,
        profile != null, false, profile != null, profile != null, profile != null, false, false, true, false, cursor, 30),
        (page, error) -> runOnUiThreadOptional(() -> {
          if (account != tdlib.myUserId()) {
            return;
          }
          if (page != null) {
            for (TdApi.ReceivedGift item : page.gifts) {
              if (item.wasRefunded) {
                continue;
              }
              if (profile != null) {
                if (item.gift instanceof TdApi.SentGiftUpgraded upgraded) {
                  entries.put(upgraded.gift.name, upgraded.gift);
                }
              } else if (!StringUtils.isEmpty(item.receivedGiftId) &&
                  (item.gift instanceof TdApi.SentGiftUpgraded || item.gift instanceof TdApi.SentGiftRegular)) {
                entries.put(item.receivedGiftId, item);
              }
            }
          }
          finishPage(cursor, page != null ? page.nextOffset : null, error);
        }));
    } else {
      tdlib.send(new TdApi.SearchGiftsForResale(giftId, new TdApi.GiftForResaleOrderPrice(), false, false,
        new TdApi.UpgradedGiftAttributeId[0], cursor, 30), (page, error) -> runOnUiThreadOptional(() -> {
          if (account != tdlib.myUserId()) {
            return;
          }
          if (page != null) {
            for (TdApi.GiftForResale item : page.gifts) {
              if (starPrice(item.gift) > 0 || StarsCollectibleController.tonPrice(item.gift) > 0) {
                entries.put(item.gift.name, item.gift);
              }
            }
          }
          finishPage(cursor, page != null ? page.nextOffset : null, error);
        }));
    }
  }

  private void finishPage (String cursor, String next, TdApi.Error error) {
    loading = false;
    failed = error != null;
    if (!failed) {
      cursors.add(cursor);
      offset = !StringUtils.isEmpty(next) && !cursors.contains(next) ? next : null;
    }
    render();
  }

  private void refresh () {
    if (loading || saving) {
      return;
    }
    entries.clear();
    cursors.clear();
    offset = "";
    load();
  }

  private void render () {
    ArrayList<ListItem> items = new ArrayList<>();
    for (TdApi.Object entry : entries.values()) {
      if (entry instanceof TdApi.ReceivedGift received && received.gift instanceof TdApi.SentGiftRegular regular) {
        String emoji = regular.gift.sticker != null ? regular.gift.sticker.emoji : "";
        String title = Lang.getString(R.string.TgxStarsRegularGift, emoji,
          Lang.dateYearShortTime(received.date, TimeUnit.SECONDS)).trim();
        items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_giftMarketItem, R.drawable.baseline_stars_24, title).setData(received));
        continue;
      }
      TdApi.UpgradedGift gift = entry instanceof TdApi.ReceivedGift received ?
        ((TdApi.SentGiftUpgraded) received.gift).gift : (TdApi.UpgradedGift) entry;
      String title = Lang.getString(R.string.TgxStarsCollectibleNumber, gift.title, Lang.formatNumber(gift.number));
      long price = starPrice(gift);
      if (price > 0) {
        title = Lang.getString(R.string.TgxStarsGiftItem, title, Lang.formatNumber(price));
      } else if (StarsCollectibleController.tonPrice(gift) > 0) {
        title = Lang.getString(R.string.TgxStarsCollectiblePriced, title,
          StarsCollectibleController.formatPrice(new TdApi.GiftResalePriceGram(StarsCollectibleController.tonPrice(gift))));
      }
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_giftMarketItem, R.drawable.baseline_stars_24, title).setData(entry));
    }
    if (loading || saving) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.LoadingInformation));
    } else if (failed || offset != null) {
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_giftMarketMore, 0,
        failed ? R.string.RetryChatList : R.string.TgxStarsMore));
    } else if (entries.isEmpty()) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.TgxStarsGiftEmpty));
    }
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_giftMarketRefresh, 0, R.string.TgxPremiumRefreshStars));
    adapter.setItems(items, false);
  }

  static long starPrice (TdApi.UpgradedGift gift) {
    return gift != null && gift.resaleParameters != null && !gift.resaleParameters.gramOnly
      ? gift.resaleParameters.starCount : 0;
  }

  static boolean sameOffer (TdApi.UpgradedGift expected, TdApi.UpgradedGift fresh) {
    return fresh != null && expected.id == fresh.id && expected.name != null && expected.name.equals(fresh.name) && starPrice(expected) > 0 &&
      starPrice(expected) == starPrice(fresh);
  }

  @Override
  public void onClick (View view) {
    if (loading || saving) {
      return;
    }
    if (view.getId() == R.id.btn_giftMarketMore) {
      load();
    } else if (view.getId() == R.id.btn_giftMarketRefresh) {
      refresh();
    } else if (view.getId() == R.id.btn_giftMarketItem && view.getTag() instanceof ListItem row) {
      if (row.getData() instanceof TdApi.UpgradedGift gift) {
        navigateTo(new StarsCollectibleController(context, tdlib, gift.name, recipientId));
      } else if (row.getData() instanceof TdApi.ReceivedGift received) {
        if (received.gift instanceof TdApi.SentGiftUpgraded) {
          showListingActions(received);
        } else if (received.gift instanceof TdApi.SentGiftRegular regular) {
          showRegularGiftActions(received, regular.gift);
        }
      }
    }
  }

  private void showRegularGiftActions (TdApi.ReceivedGift received, TdApi.Gift gift) {
    Options.Builder options = new Options.Builder().info(Lang.getString(R.string.TgxStarsRegularGift,
      gift.sticker != null ? gift.sticker.emoji : "", Lang.dateYearShortTime(received.date, TimeUnit.SECONDS)).trim());
    options.item(new OptionItem(1, Lang.getString(received.isSaved ? R.string.TgxStarsHideGift : R.string.TgxStarsShowGift),
      OptionColor.NORMAL, 0));
    if (received.sellStarCount > 0) {
      options.item(new OptionItem(2, Lang.getString(R.string.TgxStarsConvertGift), OptionColor.RED, 0));
    }
    long upgradePrice = upgradePrice(received);
    if (upgradePrice >= 0) {
      options.item(new OptionItem(3, Lang.getString(R.string.TgxStarsUpgradeGift), OptionColor.NORMAL, 0));
    }
    options.cancelItem();
    showOptions(options.build(), (view, id) -> {
      if (id == 1) {
        showConfirm(Lang.getString(received.isSaved ? R.string.TgxStarsHideGiftConfirm : R.string.TgxStarsShowGiftConfirm),
          Lang.getString(R.string.Save), () -> toggleVisibility(received));
      } else if (id == 3) {
        chooseUpgradePrivacy(received, upgradePrice);
      } else if (id == 2) {
        showConfirm(Lang.getString(R.string.TgxStarsConvertGiftConfirm, Lang.formatNumber(received.sellStarCount)),
          Lang.getString(R.string.TgxStarsConvertGift), () -> convertGift(received));
      }
      return true;
    });
  }

  private void chooseUpgradePrivacy (TdApi.ReceivedGift expected, long price) {
    showOptions(Lang.getString(R.string.TgxStarsUpgradeGiftConfirm, Lang.formatNumber(price)),
      new int[] {1, 2, R.id.btn_cancel},
      new String[] {Lang.getString(R.string.TgxStarsUpgradeKeepDetails),
        Lang.getString(R.string.TgxStarsUpgradeOmitDetails), Lang.getString(R.string.Cancel)}, (view, id) -> {
        if (id == 1 || id == 2) {
          boolean keep = id == 1;
          tdlib.ui().post(() -> {
            if (current()) {
              showConfirm(Lang.getString(R.string.TgxStarsUpgradeGiftConfirm, Lang.formatNumber(price)),
                Lang.getString(keep ? R.string.TgxStarsUpgradeKeepDetails : R.string.TgxStarsUpgradeOmitDetails),
                () -> upgradeGift(expected, price, keep));
            }
          });
        }
        return true;
      });
  }

  static long upgradePrice (TdApi.ReceivedGift gift) {
    if (!gift.canBeUpgraded || gift.wasRefunded || !(gift.gift instanceof TdApi.SentGiftRegular regular)) {
      return -1;
    }
    return gift.prepaidUpgradeStarCount > 0 ? 0 : regular.gift.upgradeStarCount > 0 ? regular.gift.upgradeStarCount : -1;
  }

  private void upgradeGift (TdApi.ReceivedGift expected, long price, boolean keepDetails) {
    if (!begin()) {
      return;
    }
    tdlib.send(new TdApi.GetReceivedGift(expected.receivedGiftId), (fresh, error) -> runOnUiThreadOptional(() -> {
      if (!current() || error != null || !sameOwnedGift(expected, fresh) || upgradePrice(fresh) != price) {
        afterMutation(error, false);
        return;
      }
      tdlib.send(new TdApi.UpgradeGift("", fresh.receivedGiftId, keepDetails, price), (result, upgradeError) ->
        runOnUiThreadOptional(() -> {
          boolean open = result != null && current();
          afterMutation(upgradeError, result != null);
          if (open && result.gift != null && !StringUtils.isEmpty(result.gift.name)) {
            navigateTo(new StarsCollectibleController(context(), tdlib, result.gift.name, account));
          }
        }));
    }));
  }

  private void convertGift (TdApi.ReceivedGift expected) {
    if (!begin()) {
      return;
    }
    tdlib.send(new TdApi.GetReceivedGift(expected.receivedGiftId), (fresh, error) -> runOnUiThreadOptional(() -> {
      if (!current() || error != null || !canConvert(expected, fresh)) {
        afterMutation(error, false);
        return;
      }
      tdlib.send(new TdApi.SellGift("", fresh.receivedGiftId),
        (ok, sellError) -> runOnUiThreadOptional(() -> afterMutation(sellError, sellError == null)));
    }));
  }

  static boolean canConvert (TdApi.ReceivedGift expected, TdApi.ReceivedGift fresh) {
    return sameOwnedGift(expected, fresh) && fresh.gift instanceof TdApi.SentGiftRegular &&
      fresh.sellStarCount > 0 && expected.sellStarCount == fresh.sellStarCount;
  }

  private void showListingActions (TdApi.ReceivedGift received) {
    TdApi.UpgradedGift gift = ((TdApi.SentGiftUpgraded) received.gift).gift;
    long stars = starPrice(gift), ton = StarsCollectibleController.tonPrice(gift);
    boolean listed = stars > 0 || ton > 0;
    Options.Builder options = new Options.Builder().info(Lang.getString(R.string.TgxStarsCollectibleNumber,
      gift.title, Lang.formatNumber(gift.number)));
    options.item(new OptionItem(1, Lang.getString(stars > 0 ? R.string.TgxStarsEditInStars : R.string.TgxStarsListInStars), OptionColor.NORMAL, 0));
    options.item(new OptionItem(2, Lang.getString(ton > 0 && gift.resaleParameters.gramOnly ? R.string.TgxStarsEditInTon : R.string.TgxStarsListInTon), OptionColor.NORMAL, 0));
    if (listed) {
      options.item(new OptionItem(3, Lang.getString(R.string.TgxStarsUnlist), OptionColor.RED, 0));
    }
    options.item(new OptionItem(4, Lang.getString(received.isSaved ? R.string.TgxStarsHideGift : R.string.TgxStarsShowGift),
      OptionColor.NORMAL, 0));
    if (received.canBeTransferred && received.transferStarCount >= 0 &&
        received.nextTransferDate <= tdlib.currentTime(TimeUnit.SECONDS)) {
      options.item(new OptionItem(5, Lang.getString(R.string.TgxStarsTransferGift), OptionColor.NORMAL, 0));
    }
    if (canDropOriginalDetails(received, received)) {
      options.item(new OptionItem(6, Lang.getString(R.string.TgxStarsDropGiftDetails), OptionColor.RED, 0));
    }
    options.cancelItem();
    showOptions(options.build(), (view, id) -> {
      if (id == 1 || id == 2) {
        enterListingPrice(received, gift, id == 2);
      } else if (id == 3) {
        showConfirm(Lang.getString(R.string.TgxStarsUnlistConfirm), Lang.getString(R.string.TgxStarsUnlist),
          () -> setListing(received, gift, null));
      } else if (id == 6) {
        showConfirm(Lang.getString(R.string.TgxStarsDropGiftDetailsConfirm, Lang.formatNumber(received.dropOriginalDetailsStarCount)),
          Lang.getString(R.string.TgxStarsDropGiftDetails), () -> dropOriginalDetails(received));
      } else if (id == 5) {
        pickTransferRecipient(received);
      } else if (id == 4) {
        showConfirm(Lang.getString(received.isSaved ? R.string.TgxStarsHideGiftConfirm : R.string.TgxStarsShowGiftConfirm),
          Lang.getString(R.string.Save), () -> toggleVisibility(received));
      }
      return true;
    });
  }

  static boolean canDropOriginalDetails (TdApi.ReceivedGift expected, TdApi.ReceivedGift fresh) {
    return sameOwnedGift(expected, fresh) && fresh.gift instanceof TdApi.SentGiftUpgraded upgraded &&
      upgraded.gift.originalDetails != null && fresh.dropOriginalDetailsStarCount > 0 &&
      expected.dropOriginalDetailsStarCount == fresh.dropOriginalDetailsStarCount;
  }

  private void dropOriginalDetails (TdApi.ReceivedGift expected) {
    if (!begin()) {
      return;
    }
    tdlib.send(new TdApi.GetReceivedGift(expected.receivedGiftId), (fresh, error) -> runOnUiThreadOptional(() -> {
      if (!current() || error != null || !canDropOriginalDetails(expected, fresh)) {
        afterMutation(error, false);
        return;
      }
      tdlib.send(new TdApi.DropGiftOriginalDetails(fresh.receivedGiftId, fresh.dropOriginalDetailsStarCount),
        (ok, dropError) -> runOnUiThreadOptional(() -> afterMutation(dropError, dropError == null)));
    }));
  }

  private void pickTransferRecipient (TdApi.ReceivedGift expected) {
    ContactsController picker = new ContactsController(context(), tdlib);
    picker.setArguments(new ContactsController.Args(new SenderPickerDelegate() {
      private boolean sending;

      @Override
      public boolean allowGlobalSearch () { return true; }

      @Override
      public String getUserPickTitle () { return Lang.getString(R.string.TgxStarsGiftRecipient); }

      @Override
      public boolean onSenderPick (ContactsController origin, View view, TdApi.MessageSender sender) {
        if (!sending && origin.isFocused() && tdlib.myUserId() == account && sender instanceof TdApi.MessageSenderUser user) {
          TdApi.User recipient = tdlib.cache().user(user.userId);
          if (recipient != null && recipient.id != account && recipient.type instanceof TdApi.UserTypeRegular) {
            origin.showConfirm(Lang.getString(R.string.TgxStarsTransferGiftConfirm, tdlib.senderName(sender),
              Lang.formatNumber(expected.transferStarCount)), Lang.getString(R.string.TgxStarsTransferGift), () -> {
              if (sending || !origin.isFocused() || account != tdlib.myUserId()) {
                return;
              }
              sending = true;
              tdlib.send(new TdApi.GetReceivedGift(expected.receivedGiftId), (fresh, error) -> origin.runOnUiThreadOptional(() -> {
                if (!origin.isFocused() || account != tdlib.myUserId() || error != null ||
                    !canTransfer(expected, fresh, tdlib.currentTime(TimeUnit.SECONDS))) {
                  sending = false;
                  if (origin.isFocused() && account == tdlib.myUserId()) {
                    if (error != null) {
                      UI.showError(error);
                    } else {
                      UI.showToast(R.string.TgxStarsGiftChanged, Toast.LENGTH_SHORT);
                    }
                  }
                  return;
                }
                tdlib.send(new TdApi.TransferGift("", fresh.receivedGiftId, sender, fresh.transferStarCount),
                  (ok, sendError) -> origin.runOnUiThreadOptional(() -> {
                    sending = false;
                    if (account == tdlib.myUserId()) {
                      if (sendError != null) {
                        UI.showError(sendError);
                      } else {
                        entries.remove(expected.receivedGiftId);
                        render();
                        UI.showToast(R.string.TgxStarsMarketSaved, Toast.LENGTH_SHORT);
                        if (origin.isFocused()) {
                          origin.navigateBack();
                        }
                      }
                    }
                  }));
              }));
            });
          } else {
            UI.showToast(R.string.TgxStarsGiftRecipientError, Toast.LENGTH_SHORT);
          }
        }
        return false;
      }
    }));
    navigateTo(picker);
  }

  static boolean canTransfer (TdApi.ReceivedGift expected, TdApi.ReceivedGift fresh, long now) {
    return sameOwnedGift(expected, fresh) && expected.gift instanceof TdApi.SentGiftUpgraded &&
      fresh.canBeTransferred && fresh.transferStarCount >= 0 && expected.transferStarCount == fresh.transferStarCount &&
      fresh.nextTransferDate <= now && expected.nextTransferDate == fresh.nextTransferDate;
  }

  private void toggleVisibility (TdApi.ReceivedGift expected) {
    if (!begin()) {
      return;
    }
    tdlib.send(new TdApi.GetReceivedGift(expected.receivedGiftId), (fresh, error) -> runOnUiThreadOptional(() -> {
      if (!current() || error != null || !sameOwnedGift(expected, fresh) || expected.isSaved != fresh.isSaved) {
        afterMutation(error, false);
        return;
      }
      tdlib.send(new TdApi.ToggleGiftIsSaved(fresh.receivedGiftId, !expected.isSaved),
        (ok, saveError) -> runOnUiThreadOptional(() -> afterMutation(saveError, saveError == null)));
    }));
  }

  static boolean sameOwnedGift (TdApi.ReceivedGift expected, TdApi.ReceivedGift fresh) {
    if (fresh == null || fresh.wasRefunded || expected.receivedGiftId == null ||
        !expected.receivedGiftId.equals(fresh.receivedGiftId)) {
      return false;
    }
    if (expected.gift instanceof TdApi.SentGiftUpgraded a && fresh.gift instanceof TdApi.SentGiftUpgraded b) {
      return a.gift.id == b.gift.id;
    }
    return expected.gift instanceof TdApi.SentGiftRegular a && fresh.gift instanceof TdApi.SentGiftRegular b &&
      a.gift.id == b.gift.id;
  }

  private long minimum (boolean ton) {
    return ton ? tdlib.options().giftResaleGramCentCountMin : tdlib.options().giftResaleStarCountMin;
  }

  private long maximum (boolean ton) {
    return ton ? tdlib.options().giftResaleGramCentCountMax : tdlib.options().giftResaleStarCountMax;
  }

  private void enterListingPrice (TdApi.ReceivedGift received, TdApi.UpgradedGift gift, boolean ton) {
    long minimum = minimum(ton), maximum = maximum(ton);
    CharSequence caption = Lang.getString(R.string.TgxStarsWithdrawalRange,
      StarsCollectibleController.formatPrice(StarsCollectibleController.price(ton, minimum)),
      StarsCollectibleController.formatPrice(StarsCollectibleController.price(ton, maximum)));
    long existing = ton ? StarsCollectibleController.tonPrice(gift) : starPrice(gift);
    String initial = existing <= 0 ? null : ton ? BigDecimal.valueOf(existing, 2).stripTrailingZeros().toPlainString() :
      Long.toString(existing);
    var input = openInputAlert(Lang.getString(ton ? R.string.TgxStarsListInTon : R.string.TgxStarsListInStars),
      caption, R.string.Continue, R.string.Cancel, initial, (edit, value) -> {
        long amount = StarsCollectibleController.parseAmount(value, ton);
        if (!validListingAmount(amount, minimum, maximum)) {
          return false;
        }
        TdApi.GiftResalePrice price = StarsCollectibleController.price(ton, amount);
        tdlib.ui().post(() -> {
          if (current()) {
            showConfirm(Lang.getString(R.string.TgxStarsListingPriceConfirm, gift.title, Lang.formatNumber(gift.number),
              StarsCollectibleController.formatPrice(price)), Lang.getString(R.string.Save),
              () -> setListing(received, gift, price));
          }
        });
        return true;
      }, true);
    input.getEditText().setInputType(InputType.TYPE_CLASS_NUMBER | (ton ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
  }

  static boolean validListingAmount (long amount, long minimum, long maximum) {
    return amount > 0 && amount >= minimum && amount <= maximum;
  }

  private void setListing (TdApi.ReceivedGift received, TdApi.UpgradedGift previous, TdApi.GiftResalePrice price) {
    if (!begin()) {
      return;
    }
    tdlib.send(new TdApi.GetReceivedGift(received.receivedGiftId), (fresh, error) -> runOnUiThreadOptional(() -> {
      if (!current() || error != null || fresh == null || fresh.wasRefunded ||
          !(fresh.gift instanceof TdApi.SentGiftUpgraded upgraded) || !sameListing(previous, upgraded.gift) ||
          (price != null && fresh.nextResaleDate > tdlib.currentTime(TimeUnit.SECONDS)) ||
          (price instanceof TdApi.GiftResalePriceStar stars && !validListingAmount(stars.starCount, minimum(false), maximum(false))) ||
          (price instanceof TdApi.GiftResalePriceGram ton && !validListingAmount(ton.gramCentCount, minimum(true), maximum(true)))) {
        afterMutation(error, false);
        return;
      }
      tdlib.send(new TdApi.SetGiftResalePrice(received.receivedGiftId, price), (ok, saveError) ->
        runOnUiThreadOptional(() -> afterMutation(saveError, saveError == null)));
    }));
  }

  static boolean sameListing (TdApi.UpgradedGift first, TdApi.UpgradedGift second) {
    if (first.id != second.id || !Objects.equals(first.name, second.name)) {
      return false;
    }
    TdApi.GiftResaleParameters a = first.resaleParameters, b = second.resaleParameters;
    return a == b || (a != null && b != null && a.starCount == b.starCount && a.gramCentCount == b.gramCentCount &&
      a.gramOnly == b.gramOnly);
  }

  private boolean current () {
    return isFocused() && account != 0 && account == tdlib.myUserId();
  }

  private boolean begin () {
    if (!current() || saving || loading) {
      return false;
    }
    saving = true;
    render();
    return true;
  }

  private void afterMutation (TdApi.Error error, boolean success) {
    saving = false;
    if (current()) {
      if (error != null) {
        UI.showError(error);
      } else {
        UI.showToast(success ? R.string.TgxStarsMarketSaved : R.string.TgxStarsGiftChanged, Toast.LENGTH_SHORT);
      }
      refresh();
    } else {
      render();
    }
  }
}

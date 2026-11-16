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

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

import org.drinkless.tdlib.TdApi;

final class StarsHistory {
  enum SubscriptionAction { CHANGE_RENEWAL, REJOIN, OPEN_RENEWAL }

  private StarsHistory () { }

  static String amount (TdApi.StarAmount amount, Locale locale, boolean signed) {
    BigDecimal total = BigDecimal.valueOf(amount.starCount).add(BigDecimal.valueOf(amount.nanostarCount, 9));
    NumberFormat format = NumberFormat.getNumberInstance(locale);
    format.setMaximumFractionDigits(9);
    return (signed && total.signum() > 0 ? "+" : "") + format.format(total);
  }

  static String transactionKey (TdApi.StarTransaction transaction) {
    return (transaction.isRefund ? "refund:" : "original:") + transaction.id;
  }

  static boolean canEdit (TdApi.StarSubscription shown, TdApi.StarSubscription fresh, long now) {
    return shown.id.equals(fresh.id) && shown.isCanceled == fresh.isCanceled &&
      shown.chatId == fresh.chatId && shown.expirationDate == fresh.expirationDate &&
      fresh.expirationDate > now && shown.pricing != null && fresh.pricing != null &&
      shown.pricing.starCount == fresh.pricing.starCount && shown.pricing.period == fresh.pricing.period;
  }
  static boolean canApplySubscriptionAction (SubscriptionAction action, TdApi.StarSubscription shown,
                                            TdApi.StarSubscription fresh, long now) {
    if (action == SubscriptionAction.CHANGE_RENEWAL) {
      return canEdit(shown, fresh, now);
    }
    if (!sameChannelSubscription(shown, fresh)) {
      return false;
    }
    TdApi.StarSubscriptionTypeChannel channel = (TdApi.StarSubscriptionTypeChannel) fresh.type;
    return action == SubscriptionAction.REJOIN ? channel.canReuse && fresh.expirationDate > now :
      fresh.expirationDate <= now && channel.inviteLink != null && !channel.inviteLink.isEmpty();
  }

  static boolean sameChannelSubscription (TdApi.StarSubscription shown, TdApi.StarSubscription fresh) {
    return shown.id.equals(fresh.id) && shown.chatId == fresh.chatId && shown.expirationDate == fresh.expirationDate &&
      shown.pricing != null && fresh.pricing != null && shown.pricing.starCount == fresh.pricing.starCount &&
      shown.pricing.period == fresh.pricing.period &&
      shown.type instanceof TdApi.StarSubscriptionTypeChannel a && fresh.type instanceof TdApi.StarSubscriptionTypeChannel b &&
      a.canReuse == b.canReuse && java.util.Objects.equals(a.inviteLink, b.inviteLink);
  }

}

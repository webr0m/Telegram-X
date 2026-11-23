/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.util;

import androidx.annotation.Nullable;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.Tdlib;

public final class MiniAppChatPolicy {
  private MiniAppChatPolicy () { }

  public static boolean allows (Tdlib tdlib, @Nullable TdApi.Chat chat, @Nullable TdApi.TargetChatTypes types) {
    if (chat == null || types == null) return false;
    switch (chat.type.getConstructor()) {
      case TdApi.ChatTypePrivate.CONSTRUCTOR:
        return tdlib.isBotChat(chat) ? types.allowBotChats : types.allowUserChats;
      case TdApi.ChatTypeBasicGroup.CONSTRUCTOR:
        return types.allowGroupChats;
      case TdApi.ChatTypeSupergroup.CONSTRUCTOR:
        return tdlib.isChannelChat(chat) ? types.allowChannelChats : types.allowGroupChats;
      default:
        return false;
    }
  }

  public static boolean supports (Tdlib tdlib, TdApi.AttachmentMenuBot bot, @Nullable TdApi.Chat chat) {
    if (chat != null && chat.type instanceof TdApi.ChatTypePrivate && tdlib.chatUserId(chat) == bot.botUserId) {
      return bot.supportsSelfChat;
    }
    return allows(tdlib, chat, new TdApi.TargetChatTypes(
      bot.supportsUserChats, bot.supportsBotChats, bot.supportsGroupChats, bot.supportsChannelChats));
  }
}

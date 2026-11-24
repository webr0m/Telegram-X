/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import org.drinkless.tdlib.TdApi;

/**
 * What to do with an attachment whose target chat is an invite link. TDLib documents the contract on
 * {@code internalLinkTypeChatInvite}: check the link, let the user agree, then join it. The two decisions that
 * carry weight are whether a check means the chat is already usable, and which of the four join results leaves
 * a chat the Mini App can actually be opened in.
 */
public final class MiniAppInviteJoin {
  /** The checked chat is ours to use, so the Mini App may be attached to it directly. */
  public static final int OPEN = 0;
  /** Nothing to open yet: the user has to be asked whether they want to join this chat. */
  public static final int ASK = 1;
  /** The link could not be checked, so there is nothing to ask about either. */
  public static final int FAIL = 2;

  /** The join worked and TDLib hands back a usable chat. */
  public static final int JOINED = 0;
  /** The chat exists but somebody has to approve the user first, so there is no chat to attach to. */
  public static final int PENDING_APPROVAL = 1;
  /** The chat refused the user. */
  public static final int DECLINED = 2;
  /** Anything else, including an {@code Error} answer. */
  public static final int FAILED = 3;

  /** {@code Tdlib.CHAT_ACCESS_PRIVATE}, spelled out to keep this class free of the app layer. */
  public static final int ACCESS_PRIVATE = -3;

  private MiniAppInviteJoin () { }

  /**
   * A chat the account can already reach is opened without a prompt; a link pointing at a chat the user is not
   * in — including one they left or were banned from, which TDLib reports as private — goes through the dialog.
   */
  public static int decide (TdApi.ChatInviteLinkInfo info, int accessState) {
    if (info == null) {
      return FAIL;
    }
    if (info.chatId == 0) {
      return ASK;
    }
    return accessState == ACCESS_PRIVATE ? ASK : OPEN;
  }

  public static int afterJoin (TdApi.Object result) {
    if (result instanceof TdApi.ChatJoinResultSuccess) {
      return JOINED;
    } else if (result instanceof TdApi.ChatJoinResultRequestSent ||
        result instanceof TdApi.ChatJoinResultGuardBotApprovalRequired) {
      return PENDING_APPROVAL;
    } else if (result instanceof TdApi.ChatJoinResultDeclined) {
      return DECLINED;
    }
    return FAILED;
  }

  /** The chat only exists for the client once the join actually succeeded. */
  public static long joinedChatId (TdApi.Object result) {
    return result instanceof TdApi.ChatJoinResultSuccess ? ((TdApi.ChatJoinResultSuccess) result).chatId : 0;
  }
}

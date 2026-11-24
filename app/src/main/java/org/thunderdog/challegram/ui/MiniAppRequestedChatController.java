/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import androidx.annotation.Nullable;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import me.vkryl.core.lambda.RunnableData;
import tgx.td.Td;

/** Lists the chats a Mini App may receive and shares the one the user taps. */
final class MiniAppRequestedChatController extends ChatsController {
  private final long accountUserId;
  private final long botUserId;
  private final String preparedButtonId;
  private final MiniAppRequestedChat spec;
  private final RunnableData<String> onResult;
  private boolean sending, completed;

  MiniAppRequestedChatController (Context context, Tdlib tdlib, long botUserId, String preparedButtonId,
      MiniAppRequestedChat spec, RunnableData<String> onResult) {
    super(context, tdlib);
    this.botUserId = botUserId;
    this.preparedButtonId = preparedButtonId;
    this.spec = spec;
    this.onResult = onResult;
    this.accountUserId = tdlib.myUserId();
    setArguments(new Arguments(chat -> spec.allows(target(tdlib, chat)), new PickerDelegate() {
      @Override
      public boolean onChatPicked (TdApi.Chat chat, Runnable onDone) {
        share(chat.id);
        return false;
      }

      @Override
      public int getTitleStringRes () {
        return R.string.MiniAppShareChat;
      }
    }));
  }

  private void share (long chatId) {
    if (completed || sending || tdlib.myUserId() != accountUserId) return;
    sending = true;
    // TDLib re-runs the same restrictions and owns the ones this client cannot read synchronously, so its
    // answer is the verdict; a refusal leaves the picker open for another chat.
    tdlib.send(new TdApi.ShareChatWithBot(new TdApi.KeyboardButtonSourceWebApp(botUserId, preparedButtonId),
      spec.buttonId, chatId, false), (ok, error) -> UI.post(() -> {
        sending = false;
        if (error == null) finish(null);
        else if (isDestroyed() || tdlib.myUserId() != accountUserId) finish("USER_DECLINED");
        else UI.showError(error);
      }));
  }

  private void finish (String error) {
    if (completed) return;
    completed = true;
    onResult.runWithData(error);
    if (!isDestroyed() && isFocused()) navigateBack();
  }

  /** Everything TDLib compares against, read from the chats this client already has cached. */
  static MiniAppRequestedChat.Target target (Tdlib tdlib, @Nullable TdApi.Chat chat) {
    MiniAppRequestedChat.Target result = new MiniAppRequestedChat.Target();
    if (chat == null) {
      return result;
    }
    switch (chat.type.getConstructor()) {
      case TdApi.ChatTypePrivate.CONSTRUCTOR:
        result.kind = MiniAppRequestedChat.CHAT_USER;
        return result;
      case TdApi.ChatTypeSecret.CONSTRUCTOR:
        result.kind = MiniAppRequestedChat.CHAT_SECRET;
        return result;
      case TdApi.ChatTypeBasicGroup.CONSTRUCTOR: {
        result.kind = MiniAppRequestedChat.CHAT_BASIC;
        TdApi.BasicGroup group = tdlib.chatToBasicGroup(chat.id);
        result.isActive = group != null && group.isActive;
        break;
      }
      case TdApi.ChatTypeSupergroup.CONSTRUCTOR: {
        boolean channel = tdlib.isChannelChat(chat);
        TdApi.Supergroup supergroup = tdlib.chatToSupergroup(chat.id);
        result.kind = channel ? MiniAppRequestedChat.CHAT_CHANNEL : MiniAppRequestedChat.CHAT_MEGAGROUP;
        result.isDirectMessagesGroup = supergroup != null && supergroup.isDirectMessagesGroup;
        result.isForum = !channel && supergroup != null && supergroup.isForum;
        result.hasUsername = supergroup != null && Td.hasUsername(supergroup);
        break;
      }
      default:
        return result;
    }
    TdApi.ChatMemberStatus status = tdlib.chatStatus(chat.id);
    if (TD.isCreator(status)) {
      result.memberStatus = MiniAppRequestedChat.Target.STATUS_CREATOR;
    } else if (status instanceof TdApi.ChatMemberStatusAdministrator admin) {
      result.memberStatus = MiniAppRequestedChat.Target.STATUS_ADMINISTRATOR;
      result.rights = admin.rights;
    }
    result.isAnonymousAdmin = tdlib.isAnonymousAdmin(chat.id);
    result.canInviteUsers = tdlib.canInviteUsers(chat);
    result.canPromoteMembers = status != null && TD.canPromoteMembers(status);
    return result;
  }

  @Override
  public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (sending) return true;
    return super.performOnBackPressed(fromTop, commit);
  }

  @Override
  public void destroy () {
    if (!sending && !completed) {
      completed = true;
      onResult.runWithData("USER_DECLINED");
    }
    super.destroy();
  }
}

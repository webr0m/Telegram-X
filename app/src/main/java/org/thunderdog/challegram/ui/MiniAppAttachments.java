/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.app.AlertDialog;
import android.widget.CheckBox;
import androidx.annotation.Nullable;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.popups.ModernActionedLayout;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.util.MiniAppChatPolicy;
import org.thunderdog.challegram.unsorted.Settings;
import tgx.td.Td;

/** Handles the TDLib attachment-menu consent and target-chat contracts. */
public final class MiniAppAttachments {
  private MiniAppAttachments () { }

  public static void open (ViewController<?> owner, long botId, long chatId, String url,
                            @Nullable TdApi.MessageTopic topic, @Nullable TdApi.InputMessageReplyTo reply) {
    loadBot(owner, botId, bot -> openInChat(owner, bot, chatId, url, topic, reply));
  }

  static void open (ViewController<?> owner, long botId, long chatId, String url,
                    @Nullable TdApi.MessageTopic topic, @Nullable TdApi.InputMessageReplyTo reply, @Nullable MiniAppController.Navigator navigator) {
    loadBot(owner, botId, bot -> openInChat(owner, bot, chatId, url, topic, reply, navigator));
  }

  public static void openLink (ViewController<?> owner, TdApi.InternalLinkTypeAttachmentMenuBot link) {
    owner.tdlib().send(new TdApi.SearchPublicChat(link.botUsername), (chat, error) -> owner.runOnUiThreadOptional(() -> {
      if (!owner.isFocused()) return;
      if (error != null) {
        UI.showError(error);
      } else {
        loadBot(owner, owner.tdlib().chatUserId(chat), bot -> chooseTarget(owner, bot, link));
      }
    }));
  }

  private static void loadBot (ViewController<?> owner, long botId, me.vkryl.core.lambda.RunnableData<TdApi.AttachmentMenuBot> done) {
    Tdlib tdlib = owner.tdlib();
    TdApi.User user = tdlib.cache().user(botId);
    if (!tdlib.isAuthorized() || user == null || !(user.type instanceof TdApi.UserTypeBot bot) ||
        !bot.canBeAddedToAttachmentMenu || Td.hasRestriction(user.restrictionInfo, Settings.instance().needRestrictContent())) {
      unavailable();
      return;
    }
    long sourceChat = owner.getChatId();
    tdlib.send(new TdApi.GetAttachmentMenuBot(botId), (info, error) -> owner.runOnUiThreadOptional(() -> {
      if (!owner.isFocused() || owner.getChatId() != sourceChat) return;
      if (error != null) UI.showError(error);
      else done.runWithData(info);
    }));
  }

  private static void chooseTarget (ViewController<?> owner, TdApi.AttachmentMenuBot bot, TdApi.InternalLinkTypeAttachmentMenuBot link) {
    if (link.targetChat instanceof TdApi.TargetChatCurrent) {
      if (owner instanceof MessagesController messages) {
        MessagesController.ReplyInfo reply = messages.getCurrentReplyId();
        openInChat(owner, bot, messages.getChatId(), link.url, messages.getMessageTopicId(reply),
          reply != null ? reply.toInputMessageReply() : null);
      } else {
        unavailable();
      }
    } else if (link.targetChat instanceof TdApi.TargetChatChosen chosen) {
      ChatsController picker = new ChatsController(owner.context(), owner.tdlib());
      picker.setArguments(new ChatsController.Arguments(
        chat -> MiniAppChatPolicy.allows(owner.tdlib(), chat, chosen.types) && MiniAppChatPolicy.supports(owner.tdlib(), bot, chat),
        new ChatsController.PickerDelegate() {
          @Override
          public boolean onChatPicked (TdApi.Chat chat, Runnable onDone) {
            openInChat(picker, bot, chat.id, link.url, null, null);
            return false;
          }
        }));
      owner.navigateTo(picker);
    } else if (link.targetChat instanceof TdApi.TargetChatInternalLink target) {
      resolveTarget(owner, bot, link.url, target.link);
    }
  }

  private static void resolveTarget (ViewController<?> owner, TdApi.AttachmentMenuBot bot, String url, TdApi.InternalLinkType target) {
    Tdlib tdlib = owner.tdlib();
    if (target instanceof TdApi.InternalLinkTypePublicChat link) {
      tdlib.send(new TdApi.SearchPublicChat(link.chatUsername), (chat, error) -> owner.runOnUiThreadOptional(() -> {
        if (!owner.isFocused()) return;
        if (error != null) UI.showError(error);
        else openInChat(owner, bot, chat.id, url, null, null);
      }));
    } else if (target instanceof TdApi.InternalLinkTypeMessage link) {
      tdlib.send(new TdApi.GetMessageLinkInfo(link.url), (info, error) -> owner.runOnUiThreadOptional(() -> {
        if (!owner.isFocused()) return;
        if (error != null) UI.showError(error);
        else if (info.chatId != 0) openInChat(owner, bot, info.chatId, url, info.topicId, null);
        else unavailable();
      }));
    } else if (target instanceof TdApi.InternalLinkTypeChatInvite invite) {
      resolveInvite(owner, bot, url, invite.inviteLink);
    } else {
      unavailable();
    }
  }

  /**
   * An attachment can point at a chat the account is not in. TDLib asks the client to check the link, let the
   * user agree, and join it; only then is there a chat the Mini App can be attached to.
   */
  private static void resolveInvite (ViewController<?> owner, TdApi.AttachmentMenuBot bot, String url, String inviteLink) {
    Tdlib tdlib = owner.tdlib();
    tdlib.send(new TdApi.CheckChatInviteLink(inviteLink), (info, error) -> owner.runOnUiThreadOptional(() -> {
      if (!owner.isFocused()) return;
      int decision = error != null ? MiniAppInviteJoin.FAIL :
        MiniAppInviteJoin.decide(info, tdlib.chatAccessState(info.chatId));
      if (error != null) {
        UI.showError(error);
      } else if (decision == MiniAppInviteJoin.OPEN) {
        openInChat(owner, bot, info.chatId, url, null, null);
      } else if (decision == MiniAppInviteJoin.ASK) {
        askToJoin(owner, bot, url, inviteLink, info);
      } else {
        unavailable();
      }
    }));
  }

  private static void askToJoin (ViewController<?> owner, TdApi.AttachmentMenuBot bot, String url, String inviteLink,
                                 TdApi.ChatInviteLinkInfo info) {
    ViewController<?> current = owner.context().navigation().getCurrentStackItem();
    if (current == null) {
      unavailable();
      return;
    }
    ModernActionedLayout.showJoinDialog(current, info, () -> joinInvite(owner, bot, url, inviteLink, info));
  }

  private static void joinInvite (ViewController<?> owner, TdApi.AttachmentMenuBot bot, String url, String inviteLink,
                                  TdApi.ChatInviteLinkInfo info) {
    long sourceChat = owner.getChatId();
    owner.tdlib().send(new TdApi.JoinChatByInviteLink(inviteLink), (result, error) -> owner.runOnUiThreadOptional(() -> {
      if (owner.isDestroyed() || !owner.isFocused() || owner.getChatId() != sourceChat) return;
      if (error != null) {
        UI.showError(error);
        return;
      }
      switch (MiniAppInviteJoin.afterJoin(result)) {
        case MiniAppInviteJoin.JOINED:
          openInChat(owner, bot, MiniAppInviteJoin.joinedChatId(result), url, null, null);
          break;
        case MiniAppInviteJoin.PENDING_APPROVAL:
          UI.showToast(Lang.getString(R.string.RequestJoinGroupSent, info.title), android.widget.Toast.LENGTH_LONG);
          break;
        default:
          unavailable();
          break;
      }
    }));
  }

  private static void openInChat (ViewController<?> owner, TdApi.AttachmentMenuBot bot, long chatId, String url,
                                  @Nullable TdApi.MessageTopic topic, @Nullable TdApi.InputMessageReplyTo reply) {
    openInChat(owner, bot, chatId, url, topic, reply, null);
  }

  private static void openInChat (ViewController<?> owner, TdApi.AttachmentMenuBot bot, long chatId, String url,
                                  @Nullable TdApi.MessageTopic topic, @Nullable TdApi.InputMessageReplyTo reply, @Nullable MiniAppController.Navigator navigator) {
    if (!MiniAppChatPolicy.supports(owner.tdlib(), bot, owner.tdlib().chat(chatId))) {
      unavailable();
      return;
    }
    Runnable open = () -> MiniAppController.openAttachment(owner, bot.botUserId, chatId, url, topic, reply, navigator);
    if (bot.isAdded) {
      open.run();
      return;
    }
    CheckBox write = new CheckBox(owner.context());
    write.setText(Lang.getString(R.string.MiniAppWriteAccess, bot.name));
    write.setChecked(false);
    AlertDialog.Builder builder = new AlertDialog.Builder(owner.context(), Theme.dialogTheme())
      .setTitle(bot.name)
      .setMessage(Lang.getString(R.string.MiniAppAttachmentTerms))
      .setNeutralButton(Lang.getString(R.string.MiniAppReadTerms),
        (dialog, which) -> org.thunderdog.challegram.tool.Intents.openUri("https://telegram.org/tos/mini-apps"))
      .setPositiveButton(Lang.getString(R.string.MiniAppAddAndOpen), null)
      .setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> {});
    if (bot.requestWriteAccess) builder.setView(write);
    AlertDialog dialog = owner.showAlert(builder);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
      if (!owner.isFocused() || owner.isDestroyed()) return;
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
      owner.tdlib().send(new TdApi.ToggleBotIsAddedToAttachmentMenu(bot.botUserId, true,
        bot.requestWriteAccess && write.isChecked()), (ok, error) -> owner.runOnUiThreadOptional(() -> {
          if (error != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            UI.showError(error);
          } else {
            boolean openAfterAdding = dialog.isShowing() && owner.isFocused();
            dialog.dismiss();
            if (openAfterAdding) open.run();
          }
        }));
    });
  }

  public static void remove (ViewController<?> owner, long botId) {
    owner.showConfirm(Lang.getString(R.string.MiniAppRemoveAttachmentConfirm), Lang.getString(R.string.MiniAppRemoveAttachment),
      () -> owner.tdlib().send(new TdApi.ToggleBotIsAddedToAttachmentMenu(botId, false, false),
        (ok, error) -> owner.runOnUiThreadOptional(() -> { if (error != null) UI.showError(error); })));
  }

  private static void unavailable () {
    UI.showToast(R.string.MiniAppUnavailable, android.widget.Toast.LENGTH_SHORT);
  }
}

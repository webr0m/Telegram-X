/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.app.AlertDialog;
import android.content.Context;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.inline.CustomResultView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.InlineResult;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.util.MiniAppChatPolicy;
import me.vkryl.core.lambda.RunnableData;
import tgx.td.Td;

/** Chooses a permitted recipient and previews the inline result before submitting it. */
final class MiniAppPreparedMessageController extends ChatsController {
  private final TdApi.PreparedInlineMessage prepared;
  private final RunnableData<String> onResult;
  private final long accountUserId;
  private InlineResult<?> preview;
  private AlertDialog confirmation;
  private boolean sending, completed;

  MiniAppPreparedMessageController (Context context, Tdlib tdlib, TdApi.PreparedInlineMessage prepared, RunnableData<String> onResult) {
    super(context, tdlib);
    this.prepared = prepared;
    this.onResult = onResult;
    this.accountUserId = tdlib.myUserId();
    preview = InlineResult.valueOf(context(), tdlib, "", prepared.result, null);
    setArguments(new Arguments(chat -> MiniAppChatPolicy.allows(tdlib, chat, prepared.chatTypes), new PickerDelegate() {
      @Override
      public boolean onChatPicked (TdApi.Chat chat, Runnable onDone) {
        confirm(chat);
        return false;
      }

      @Override
      public int getTitleStringRes () {
        return R.string.MiniAppShareMessage;
      }
    }));
  }

  private void confirm (TdApi.Chat chat) {
    if (completed || sending || confirmation != null || tdlib.myUserId() != accountUserId) return;
    if (preview == null || preview.getId() == null || !MiniAppChatPolicy.allows(tdlib, chat, prepared.chatTypes)) {
      finish("MESSAGE_EXPIRED");
      return;
    }
    if (!tdlib.canSendBasicMessage(chat)) {
      UI.showToast(R.string.YouCantSendMessages, android.widget.Toast.LENGTH_SHORT);
      return;
    }
    CustomResultView view = new CustomResultView(context());
    view.setInlineResult(preview);
    // This dialog owns only the preview receiver. The picker owns the send operation.
    confirmation = showAlert(new AlertDialog.Builder(context(), org.thunderdog.challegram.theme.Theme.dialogTheme())
      .setTitle(Lang.getString(R.string.MiniAppShareMessage))
      .setMessage(Lang.getString(R.string.MiniAppShareMessageConfirm, chat.title))
      .setView(view)
      .setPositiveButton(Lang.getString(R.string.Send), (dialog, which) -> send(chat))
      .setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> {}));
    confirmation.setOnDismissListener(dialog -> {
      if (confirmation == dialog) confirmation = null;
      view.performDestroy();
    });
  }

  private void send (TdApi.Chat chat) {
    if (completed || sending || tdlib.myUserId() != accountUserId ||
        !MiniAppChatPolicy.allows(tdlib, tdlib.chat(chat.id), prepared.chatTypes)) return;
    sending = true;
    tdlib.send(new TdApi.SendInlineQueryResultMessage(chat.id, null, null, Td.newSendOptions(),
      prepared.inlineQueryId, preview.getId(), false), (message, error) -> UI.post(() -> {
        sending = false;
        finish(error == null ? null : "MESSAGE_SEND_FAILED");
        if (error != null && !isDestroyed()) UI.showError(error);
      }));
  }

  private void finish (String error) {
    if (completed) return;
    completed = true;
    onResult.runWithData(error);
    if (!isDestroyed() && isFocused()) navigateBack();
  }

  @Override
  public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (sending) return true;
    return super.performOnBackPressed(fromTop, commit);
  }

  @Override
  public void destroy () {
    if (confirmation != null) confirmation.dismiss();
    if (!sending && !completed) {
      completed = true;
      onResult.runWithData("USER_DECLINED");
    }
    preview = null;
    super.destroy();
  }
}

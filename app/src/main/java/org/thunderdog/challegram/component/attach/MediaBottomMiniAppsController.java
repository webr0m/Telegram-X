/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.component.attach;

import android.content.Context;
import android.view.View;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.user.SimpleUsersAdapter;
import org.thunderdog.challegram.data.TGUser;
import org.thunderdog.challegram.ui.MiniAppAttachments;
import org.thunderdog.challegram.ui.MessagesController;
import org.thunderdog.challegram.util.MiniAppChatPolicy;
import java.util.ArrayList;
import java.util.List;

public final class MediaBottomMiniAppsController extends MediaBottomBaseController<Void> implements SimpleUsersAdapter.Callback {
  private SimpleUsersAdapter adapter;
  private final Runnable onBotsChanged = this::displayBots;

  public MediaBottomMiniAppsController (MediaLayout layout) {
    super(layout, R.string.MiniApp);
  }

  @Override
  public int getId () {
    return R.id.controller_media_miniApps;
  }

  @Override
  protected View onCreateView (Context context) {
    buildContentView(true);
    setLayoutManager(new LinearLayoutManager(context, RecyclerView.VERTICAL, false));
    adapter = new SimpleUsersAdapter(this, this, SimpleUsersAdapter.OPTION_CLICKABLE, this);
    setAdapter(adapter);
    tdlib.ui().addAttachmentMenuBotListener(onBotsChanged);
    displayBots();
    return contentView;
  }

  private void displayBots () {
    if (isDestroyed() || adapter == null) return;
    List<TGUser> users = new ArrayList<>();
    for (TdApi.AttachmentMenuBot bot : tdlib.attachmentMenuBots()) {
      TdApi.User user = tdlib.cache().user(bot.botUserId);
      if (bot.isAdded && bot.showInAttachmentMenu && user != null &&
          MiniAppChatPolicy.supports(tdlib, bot, tdlib.chat(mediaLayout.getTargetChatId()))) {
        users.add(TGUser.createWithUsername(tdlib, user));
      }
    }
    if (users.isEmpty()) {
      adapter.setUsers(users);
      showError(R.string.MiniAppNoAttachmentBots, 0, null, true);
    } else {
      hideProgress(() -> {
        if (!isDestroyed()) {
          adapter.setUsers(users);
          expandStartHeight(adapter);
        }
      });
    }
  }

  @Override
  public void onUserPicked (TGUser user) {
    MessagesController owner = mediaLayout.getTarget();
    if (owner == null || user.getUser() == null) return;
    MessagesController.ReplyInfo reply = owner.getCurrentReplyId();
    long botId = user.getUser().id;
    mediaLayout.forceHide();
    MiniAppAttachments.open(owner, botId, owner.getChatId(), "", owner.getMessageTopicId(reply),
      reply != null ? reply.toInputMessageReply() : null);
  }

  @Override
  public void onUserSelected (int count, TGUser user, boolean selected) { }

  @Override
  public void destroy () {
    tdlib.ui().removeAttachmentMenuBotListener(onBotsChanged);
    super.destroy();
  }
}

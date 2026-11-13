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
import android.widget.Toast;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.emoji.MediaStickersAdapter;
import org.thunderdog.challegram.component.sticker.StickerSmallView;
import org.thunderdog.challegram.component.sticker.TGStickerObj;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.v.RtlGridLayoutManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.function.LongConsumer;

final class SavedTagEmojiPicker extends RecyclerViewController<Void> implements View.OnClickListener,
    StickerSmallView.StickerMovementCallback {
  private final long account;
  private long packId;
  private final String title;
  private final LongConsumer selected;
  private CustomRecyclerView recycler;
  private androidx.recyclerview.widget.RecyclerView.LayoutManager listLayout;
  private SettingsAdapter list;
  private boolean loading, picking;

  SavedTagEmojiPicker (Context context, Tdlib tdlib, long account, String title, LongConsumer selected) {
    super(context, tdlib);
    this.account = account;
    this.title = title;
    this.selected = selected;
  }

  @Override public int getId () { return R.id.controller_savedTagEmojiPicker; }
  @Override public CharSequence getName () { return title; }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recycler) {
    this.recycler = recycler;
    listLayout = recycler.getLayoutManager();
    list = new SettingsAdapter(this);
    recycler.setAdapter(list);
    load();
  }

  private boolean canChoose () {
    return !isDestroyed() && account != 0 && account == tdlib.myUserId() && tdlib.hasPremium();
  }

  private void status (int resource, boolean retry) {
    ArrayList<ListItem> rows = new ArrayList<>();
    rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, resource));
    if (retry) {
      rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_savedTagRetry, 0, R.string.RetryChatList));
    }
    list.setItems(rows, false);
  }

  private void load () {
    if (!canChoose() || loading) {
      return;
    }
    loading = true;
    status(R.string.LoadingInformation, false);
    if (packId == 0) {
      tdlib.send(new TdApi.GetInstalledStickerSets(new TdApi.StickerTypeCustomEmoji()),
        (packs, error) -> runOnUiThreadOptional(() -> {
          if (!finishLoad(error)) {
            return;
          }
          ArrayList<ListItem> rows = new ArrayList<>();
          for (TdApi.StickerSetInfo pack : packs.sets) {
            if (pack != null && pack.id != 0 && pack.isInstalled && !pack.isArchived) {
              rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_savedTagEmojiPack, 0, pack.title).setData(pack));
            }
          }
          if (rows.isEmpty()) {
            status(R.string.SavedMessagesNoEmojiPacks, false);
          } else {
            list.setItems(rows, false);
          }
        }));
    } else {
      tdlib.send(new TdApi.GetStickerSet(packId), (pack, error) -> runOnUiThreadOptional(() -> {
        if (!finishLoad(error)) {
          return;
        }
        if (!pack.isInstalled || pack.isArchived || !(pack.stickerType instanceof TdApi.StickerTypeCustomEmoji)) {
          status(R.string.SavedMessagesNoEmojiPacks, true);
          return;
        }
        ArrayList<MediaStickersAdapter.StickerItem> items = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();
        for (TdApi.Sticker sticker : pack.stickers) {
          long id = customEmojiId(sticker);
          if (id != 0 && seen.add(id)) {
            items.add(new MediaStickersAdapter.StickerItem(MediaStickersAdapter.StickerHolder.TYPE_STICKER,
              new TGStickerObj(tdlib, sticker, (String) null, sticker.fullType)));
          }
        }
        if (items.isEmpty()) {
          status(R.string.SavedMessagesNoEmojiPacks, true);
        } else {
          MediaStickersAdapter grid = new MediaStickersAdapter(this, this, false, this);
          recycler.setLayoutManager(new RtlGridLayoutManager(context(), 6));
          recycler.setAdapter(grid);
          grid.setItems(items);
        }
      }));
    }
  }

  static long customEmojiId (TdApi.Sticker sticker) {
    return sticker != null && sticker.fullType instanceof TdApi.StickerFullTypeCustomEmoji emoji ? emoji.customEmojiId : 0;
  }

  private boolean finishLoad (TdApi.Error error) {
    loading = false;
    if (!canChoose()) {
      return false;
    }
    if (error != null) {
      UI.showError(error);
      status(R.string.SavedMessagesEmojiLoadFailed, true);
      return false;
    }
    return true;
  }

  @Override
  public void onClick (View view) {
    if (!isFocused() || !canChoose() || loading) {
      return;
    }
    if (view.getId() == R.id.btn_savedTagRetry) {
      load();
    } else if (view.getId() == R.id.btn_savedTagEmojiPack && view.getTag() instanceof ListItem row &&
        row.getData() instanceof TdApi.StickerSetInfo pack) {
      packId = pack.id;
      load();
    }
  }

  @Override
  public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (packId == 0 || loading || picking) {
      return super.performOnBackPressed(fromTop, commit);
    }
    if (commit) {
      packId = 0;
      recycler.setLayoutManager(listLayout);
      recycler.setAdapter(list);
      load();
    }
    return true;
  }

  @Override
  public boolean onStickerClick (StickerSmallView view, View clickView, TGStickerObj sticker, boolean isMenuClick,
                                TdApi.MessageSendOptions sendOptions) {
    if (picking || !isFocused() || !canChoose() || !sticker.isCustomEmoji() || sticker.getCustomEmojiId() == 0) {
      return false;
    }
    picking = true;
    showConfirm(Lang.getString(R.string.SavedMessagesConfirmCustomTag),
      Lang.getString(R.string.Done), () -> {
        if (isFocused() && canChoose()) {
          selected.accept(sticker.getCustomEmojiId());
          navigateBack();
        } else {
          UI.showToast(R.string.SavedMessagesTagsUnavailable, Toast.LENGTH_SHORT);
        }
      }).setDismissListener(popup -> picking = false);
    return true;
  }

  @Override public long getStickerOutputChatId () { return 0; }
  @Override public void setStickerPressed (StickerSmallView view, TGStickerObj sticker, boolean pressed) { }
  @Override public boolean canFindChildViewUnder (StickerSmallView view, int x, int y) { return false; }
  @Override public boolean needsLongDelay (StickerSmallView view) { return false; }
  @Override public int getStickersListTop () { return 0; }
  @Override public int getViewportHeight () { return recycler.getHeight(); }
  @Override public boolean needShowButtons () { return false; }
}

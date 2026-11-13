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
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.ContentPreview;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

import tgx.td.Td;

import tgx.td.MessageId;

final class SavedMessagesTopicsController extends RecyclerViewController<Void> implements View.OnClickListener {
  private final long account, chatId, topicId;
  private final LinkedHashMap<Long, TdApi.Message> messages = new LinkedHashMap<>();
  private final HashSet<Long> cursors = new HashSet<>();
  private final Runnable topicListener = () -> runOnUiThreadOptional(this::onTopicsChanged);
  private SettingsAdapter adapter;
  private TdApi.SavedMessagesTopic openedTopic;
  private long cursor;
  private int generation;
  private String query = "";
  private boolean loading, end, failed, saving;

  SavedMessagesTopicsController (Context context, Tdlib tdlib, long chatId, long topicId) {
    super(context, tdlib);
    this.account = tdlib.myUserId();
    this.chatId = chatId;
    this.topicId = topicId;
    this.openedTopic = topicId != 0 ? tdlib.savedMessagesTopic(topicId) : null;
  }

  @Override
  public int getId () {
    return topicId == 0 ? R.id.controller_savedMessagesTopics : R.id.controller_savedMessagesTopic;
  }

  @Override
  public CharSequence getName () {
    return openedTopic != null ? title(openedTopic) : Lang.getString(R.string.SavedMessagesTopics);
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recycler) {
    adapter = new SettingsAdapter(this);
    recycler.setAdapter(adapter);
    tdlib.addSavedMessagesTopicListener(topicListener);
    load();
  }

  @Override
  public void destroy () {
    tdlib.removeSavedMessagesTopicListener(topicListener);
    super.destroy();
  }

  private boolean currentAccount () {
    return account != 0 && account == tdlib.myUserId() && tdlib.isSelfChat(chatId) && !isDestroyed();
  }

  private void onTopicsChanged () {
    if (!currentAccount() || adapter == null || !isFocused()) {
      return;
    }
    if (topicId == 0) {
      render();
    } else {
      TdApi.SavedMessagesTopic fresh = tdlib.savedMessagesTopic(topicId);
      if (fresh != null && openedTopic != null && lastId(fresh) != lastId(openedTopic) && query.isEmpty()) {
        openedTopic = fresh;
        restart("");
        return;
      }
      openedTopic = fresh;
      if (fresh == null) {
        generation++;
        loading = false;
        end = true;
        messages.clear();
      }
      render();
    }
  }

  @Override
  public void onFocus () {
    super.onFocus();
    if (adapter != null) {
      onTopicsChanged();
    }
  }

  private static long lastId (TdApi.SavedMessagesTopic topic) {
    return topic.lastMessage != null ? topic.lastMessage.id : 0;
  }

  private String title (TdApi.SavedMessagesTopic topic) {
    if (topic.type instanceof TdApi.SavedMessagesTopicTypeSavedFromChat source) {
      return tdlib.chatTitle(source.chatId);
    }
    return Lang.getString(topic.type instanceof TdApi.SavedMessagesTopicTypeAuthorHidden ?
      R.string.SavedMessagesHiddenAuthors : R.string.SavedMessagesMyNotes);
  }

  private void load () {
    if (!currentAccount() || loading || end || saving) {
      return;
    }
    loading = true;
    failed = false;
    render();
    if (topicId == 0) {
      tdlib.send(new TdApi.LoadSavedMessagesTopics(30), (ok, error) -> runOnUiThreadOptional(() -> {
        if (currentAccount()) {
          loading = false;
          end = error != null && error.code == 404;
          failed = error != null && !end;
          render();
        }
      }));
      return;
    }
    int request = generation;
    long from = cursor;
    if (query.isEmpty()) {
      tdlib.send(new TdApi.GetSavedMessagesTopicHistory(topicId, from, 0, 40), (result, error) -> runOnUiThreadOptional(() -> {
        long next = 0;
        if (result != null) {
          for (TdApi.Message message : result.messages) {
            if (belongsToTopic(message, chatId, topicId) && (from == 0 || message.id < from) &&
                (next == 0 || message.id < next)) {
              next = message.id;
            }
          }
        }
        finishPage(request, result != null ? result.messages : null, next, error);
      }));
    } else {
      tdlib.send(new TdApi.SearchSavedMessages(topicId, null, query, from, 0, 40),
        (result, error) -> runOnUiThreadOptional(() -> finishPage(request,
          result != null ? result.messages : null, result != null ? result.nextFromMessageId : 0, error)));
    }
  }

  static boolean belongsToTopic (TdApi.Message message, long chatId, long topicId) {
    return message != null && message.chatId == chatId && message.id > 0 &&
      message.topicId instanceof TdApi.MessageTopicSavedMessages saved && saved.savedMessagesTopicId == topicId;
  }

  private void finishPage (int request, TdApi.Message[] page, long next, TdApi.Error error) {
    if (request != generation || !currentAccount()) {
      return;
    }
    loading = false;
    failed = error != null;
    if (!failed && page != null) {
      for (TdApi.Message message : page) {
        if (belongsToTopic(message, chatId, topicId)) {
          messages.put(message.id, message);
        }
      }
      end = next <= 0 || !cursors.add(next);
      cursor = next;
    }
    render();
  }

  private void restart (String nextQuery) {
    generation++;
    loading = failed = end = false;
    messages.clear();
    cursors.clear();
    cursor = 0;
    query = nextQuery;
    load();
  }

  private void render () {
    ArrayList<ListItem> rows = new ArrayList<>();
    if (topicId == 0) {
      ArrayList<TdApi.SavedMessagesTopic> sorted = new ArrayList<>(tdlib.savedMessagesTopics());
      sorted.sort(Comparator.comparingLong((TdApi.SavedMessagesTopic topic) -> topic.order).reversed()
        .thenComparingLong(topic -> topic.id));
      for (TdApi.SavedMessagesTopic topic : sorted) {
        String label = topic.isPinned ? Lang.getString(R.string.SavedMessagesPinnedTopic, title(topic)) : title(topic);
        rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_savedMessagesTopic, 0, label).setData(topic));
      }
    } else if (openedTopic != null) {
      if (openedTopic.type instanceof TdApi.SavedMessagesTopicTypeMyNotes) {
        rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_savedMessagesNewNote, 0, R.string.SavedMessagesNewNote));
      }
      rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_savedMessagesSearch, 0, R.string.SavedMessagesSearchTopic));
      if (!query.isEmpty()) {
        rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_savedMessagesResetSearch, 0, R.string.SavedMessagesClearSearch));
      }
      rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_savedMessagesTopicActions, 0, R.string.SavedMessagesTopicActions));
      for (TdApi.Message message : messages.values()) {
        ContentPreview preview = ContentPreview.getChatListPreview(tdlib, chatId, message, false);
        String date = Lang.dateYearShortTime(message.date, TimeUnit.SECONDS);
        rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_savedMessagesItem, 0,
          preview != null ? Lang.getString(R.string.SavedMessagesPreviewLine, date, preview.toString()) : date).setData(message));
      }
    }
    if (loading || saving) {
      rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.LoadingInformation));
    } else if (!end) {
      rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_savedMessagesMore, 0,
        failed ? R.string.RetryChatList : R.string.SavedMessagesLoadMore));
    } else if (topicId == 0 ? tdlib.savedMessagesTopics().isEmpty() : messages.isEmpty()) {
      rows.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.SavedMessagesTopicEmpty));
    }
    adapter.setItems(rows, false);
  }

  @Override
  public void onClick (View view) {
    if (!isFocused() || !currentAccount() || saving) {
      return;
    }
    int id = view.getId();
    if (id == R.id.btn_savedMessagesMore) {
      load();
    } else if (id == R.id.btn_savedMessagesTopic && view.getTag() instanceof ListItem row &&
        row.getData() instanceof TdApi.SavedMessagesTopic topic && tdlib.savedMessagesTopic(topic.id) != null) {
      navigateTo(new SavedMessagesTopicsController(context(), tdlib, chatId, topic.id));
    } else if (id == R.id.btn_savedMessagesItem && view.getTag() instanceof ListItem row &&
        row.getData() instanceof TdApi.Message message && belongsToTopic(message, chatId, topicId)) {
      tdlib.ui().openChat(this, chatId, new TdlibUi.ChatOpenParameters().keepStack()
        .highlightMessage(new MessageId(chatId, message.id)).ensureHighlightAvailable());
    } else if (id == R.id.btn_savedMessagesNewNote) {
      composeNote();
    } else if (id == R.id.btn_savedMessagesSearch && openedTopic != null) {
      openInputAlert(Lang.getString(R.string.SavedMessagesSearchTopic), null, R.string.Search, R.string.Cancel,
        query, (field, value) -> {
          tdlib.ui().post(() -> {
            if (currentAccount() && isFocused() && tdlib.savedMessagesTopic(topicId) != null) {
              restart(value.trim());
            }
          });
          return true;
        }, true);
    } else if (id == R.id.btn_savedMessagesResetSearch) {
      restart("");
    } else if (id == R.id.btn_savedMessagesTopicActions) {
      showTopicActions();
    }
  }

  private void composeNote () {
    TdApi.SavedMessagesTopic topic = tdlib.savedMessagesTopic(topicId);
    if (topic == null || !(topic.type instanceof TdApi.SavedMessagesTopicTypeMyNotes)) {
      return;
    }
    openInputAlert(Lang.getString(R.string.SavedMessagesNewNote), null, R.string.Save, R.string.Cancel, null,
      (field, value) -> {
        String text = value.trim();
        if (text.isEmpty()) {
          return false;
        }
        tdlib.ui().post(() -> sendNote(text));
        return true;
      }, true);
  }

  private void sendNote (String text) {
    TdApi.SavedMessagesTopic topic = tdlib.savedMessagesTopic(topicId);
    if (!currentAccount() || !isFocused() || saving || topic == null ||
        !(topic.type instanceof TdApi.SavedMessagesTopicTypeMyNotes)) {
      return;
    }
    saving = true;
    render();
    TdApi.InputMessageText input = new TdApi.InputMessageText(new TdApi.FormattedText(text, new TdApi.TextEntity[0]), null, false);
    tdlib.send(new TdApi.SendMessage(chatId, null, null, Td.newSendOptions(), null, input),
      (sent, error) -> runOnUiThreadOptional(() -> {
        saving = false;
        if (currentAccount()) {
          if (error != null) {
            UI.showError(error);
            render();
          } else {
            restart(query);
          }
        }
      }));
  }

  private void showTopicActions () {
    TdApi.SavedMessagesTopic expected = tdlib.savedMessagesTopic(topicId);
    if (expected == null) {
      return;
    }
    int label = expected.isPinned ? R.string.SavedMessagesUnpinTopic : R.string.SavedMessagesPinTopic;
    showOptions(title(expected), new int[] {1, 2, R.id.btn_cancel},
      new String[] {Lang.getString(label), Lang.getString(R.string.SavedMessagesDeleteTopic), Lang.getString(R.string.Cancel)},
      (view, id) -> {
        if (id == 1) {
          mutateTopic(expected, false);
        } else if (id == 2) {
          tdlib.ui().post(() -> {
            if (isFocused() && currentAccount()) {
              showConfirm(Lang.getString(R.string.SavedMessagesDeleteTopicConfirm, title(expected)), Lang.getString(R.string.Delete),
                () -> mutateTopic(expected, true));
            }
          });
        }
        return true;
      });
  }

  static boolean sameTopic (TdApi.SavedMessagesTopic expected, TdApi.SavedMessagesTopic current) {
    return current != null && expected.id == current.id && current.order != 0 &&
      expected.isPinned == current.isPinned && lastId(expected) == lastId(current);
  }

  private void mutateTopic (TdApi.SavedMessagesTopic expected, boolean deleting) {
    if (saving || !isFocused() || !currentAccount()) {
      return;
    }
    TdApi.SavedMessagesTopic fresh = tdlib.savedMessagesTopic(topicId);
    if (!sameTopic(expected, fresh)) {
      UI.showToast(R.string.SavedMessagesTopicChanged, Toast.LENGTH_SHORT);
      return;
    }
    saving = true;
    render();
    TdApi.Function<TdApi.Ok> request = deleting ? new TdApi.DeleteSavedMessagesTopicHistory(topicId) :
      new TdApi.ToggleSavedMessagesTopicIsPinned(topicId, !fresh.isPinned);
    tdlib.send(request, (ok, error) -> runOnUiThreadOptional(() -> {
      saving = false;
      if (currentAccount() && isFocused()) {
        if (error != null) {
          UI.showError(error);
        } else if (deleting) {
          navigateBack();
          return;
        }
        render();
      }
    }));
  }
}

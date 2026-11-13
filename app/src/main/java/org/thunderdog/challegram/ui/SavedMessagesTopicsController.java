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

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.Toast;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.ContentPreview;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

import tgx.td.MessageId;
import tgx.td.Td;

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
      ArrayList<TdApi.SavedMessagesTopic> sorted = sortedTopics();
      if (!loading && pinnedIds(sorted).length > 1) {
        rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_savedMessagesReorder, 0, R.string.SavedMessagesReorder));
      }
      for (TdApi.SavedMessagesTopic topic : sorted) {
        String label = topic.isPinned ? Lang.getString(R.string.SavedMessagesPinnedTopic, title(topic)) : title(topic);
        rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_savedMessagesTopic, 0, label).setData(topic));
      }
    } else if (openedTopic != null) {
      if (isNotes() || !draftText().isEmpty()) {
        rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_savedMessagesNewNote, 0,
          draftText().isEmpty() ? R.string.SavedMessagesNewNote : R.string.SavedMessagesResumeDraft));
      }
      rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_savedMessagesSearch, 0, R.string.SavedMessagesSearchTopic));
      rows.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_savedMessagesDate, 0, R.string.SavedMessagesJumpToDate));
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
    } else if (id == R.id.btn_savedMessagesReorder) {
      choosePinnedTopic();
    } else if (id == R.id.btn_savedMessagesTopic && view.getTag() instanceof ListItem row &&
        row.getData() instanceof TdApi.SavedMessagesTopic topic && tdlib.savedMessagesTopic(topic.id) != null) {
      navigateTo(new SavedMessagesTopicsController(context(), tdlib, chatId, topic.id));
    } else if (id == R.id.btn_savedMessagesItem && view.getTag() instanceof ListItem row &&
        row.getData() instanceof TdApi.Message message && belongsToTopic(message, chatId, topicId)) {
      showMessageActions(message);
    } else if (id == R.id.btn_savedMessagesDate) {
      chooseDate();
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

  private void openMessage (TdApi.Message message) {
    tdlib.ui().openChat(this, chatId, new TdlibUi.ChatOpenParameters().keepStack()
      .highlightMessage(new MessageId(chatId, message.id)).ensureHighlightAvailable());
  }

  private void chooseDate () {
    Calendar now = Calendar.getInstance();
    DatePickerDialog dates = new DatePickerDialog(context(), Theme.dialogTheme(), (view, year, month, day) -> {
      if (!isFocused() || !currentAccount() || tdlib.savedMessagesTopic(topicId) == null) {
        return;
      }
      Calendar endOfDay = Calendar.getInstance();
      endOfDay.clear();
      endOfDay.set(year, month, day, 23, 59, 59);
      int date = (int) Math.min(Integer.MAX_VALUE, endOfDay.getTimeInMillis() / 1000L);
      tdlib.send(new TdApi.GetSavedMessagesTopicMessageByDate(topicId, date), (message, error) -> runOnUiThreadOptional(() -> {
        if (isFocused() && currentAccount() && tdlib.savedMessagesTopic(topicId) != null) {
          if (belongsToTopic(message, chatId, topicId)) {
            openMessage(message);
          } else if (error != null && error.code != 404) {
            UI.showError(error);
          } else {
            UI.showToast(R.string.SavedMessagesNoMessageByDate, Toast.LENGTH_SHORT);
          }
        }
      }));
    }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
    dates.getDatePicker().setMaxDate(now.getTimeInMillis());
    ViewSupport.showDatePicker(dates);
  }

  private boolean isNotes () {
    return openedTopic != null && openedTopic.type instanceof TdApi.SavedMessagesTopicTypeMyNotes;
  }

  private SharedPreferences drafts () {
    return context().getSharedPreferences("saved_messages_topic_drafts", Context.MODE_PRIVATE);
  }

  static String draftKey (long account, long topicId) {
    return account + ":" + topicId;
  }

  private String draftKey () {
    return draftKey(account, topicId);
  }

  private String draftText () {
    return drafts().getString(draftKey(), "");
  }

  private long draftReplyId () {
    return drafts().getLong(draftKey() + ":reply", 0);
  }

  private void saveDraft (String text, long replyId) {
    SharedPreferences.Editor editor = drafts().edit();
    if (text.isEmpty()) {
      editor.remove(draftKey()).remove(draftKey() + ":reply");
    } else {
      editor.putString(draftKey(), text).putLong(draftKey() + ":reply", replyId);
    }
    editor.apply();
  }

  private void showMessageActions (TdApi.Message message) {
    Options.Builder options = new Options.Builder();
    options.item(new OptionItem(1, Lang.getString(R.string.SavedMessagesOpenMessage), OptionColor.NORMAL, 0));
    options.item(new OptionItem(2, Lang.getString(R.string.Reply), OptionColor.NORMAL, 0));
    options.cancelItem();
    showOptions(options.build(), (view, id) -> {
      if (id == 1) {
        openMessage(message);
      } else if (id == 2) {
        tdlib.ui().post(() -> {
          if (isFocused() && currentAccount()) {
            if (!draftText().isEmpty() && draftReplyId() != message.id) {
              showConfirm(Lang.getString(R.string.SavedMessagesChangeDraftReply), Lang.getString(R.string.Continue),
                () -> editDraft(message.id));
            } else {
              editDraft(message.id);
            }
          }
        });
      }
      return true;
    });
  }

  private void composeNote () {
    editDraft(draftReplyId());
  }

  private void editDraft (long replyId) {
    if (!isFocused() || !currentAccount() || saving || tdlib.savedMessagesTopic(topicId) == null ||
        (!isNotes() && replyId == 0)) {
      return;
    }
    var field = openInputAlert(Lang.getString(replyId != 0 ? R.string.Reply : R.string.SavedMessagesNewNote),
      Lang.getString(R.string.SavedMessagesLocalDraft), R.string.Save, R.string.Cancel, draftText(), (edit, value) -> {
        if (!currentAccount() || tdlib.savedMessagesTopic(topicId) == null) {
          return false;
        }
        saveDraft(value, replyId);
        render();
        if (!value.trim().isEmpty()) {
          tdlib.ui().post(() -> offerDraftActions(value, replyId));
        }
        return true;
      }, true);
    field.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
      InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
  }

  private void offerDraftActions (String expected, long replyId) {
    if (!isFocused() || !currentAccount()) {
      return;
    }
    showOptions(Lang.getString(replyId != 0 ? R.string.SavedMessagesReplyDestination : R.string.SavedMessagesLocalDraft),
      new int[] {1, 2, R.id.btn_cancel},
      new String[] {Lang.getString(R.string.SavedMessagesSendDraft), Lang.getString(R.string.SavedMessagesDiscardDraft),
        Lang.getString(R.string.Cancel)}, (view, id) -> {
        if (id == 1) {
          sendDraft(expected, replyId);
        } else if (id == 2 && matchesDraft(expected, replyId)) {
          saveDraft("", 0);
          render();
        }
        return true;
      });
  }

  private boolean matchesDraft (String text, long replyId) {
    return currentAccount() && text.equals(draftText()) && replyId == draftReplyId();
  }

  private void sendDraft (String text, long replyId) {
    if (!matchesDraft(text, replyId) || !isFocused() || saving || tdlib.savedMessagesTopic(topicId) == null ||
        (!isNotes() && replyId == 0)) {
      return;
    }
    saving = true;
    render();
    if (replyId == 0) {
      sendSavedText(text, 0);
    } else {
      tdlib.send(new TdApi.GetMessage(chatId, replyId), (message, error) -> runOnUiThreadOptional(() -> {
        if (!isFocused() || !matchesDraft(text, replyId) || tdlib.savedMessagesTopic(topicId) == null ||
            !belongsToTopic(message, chatId, topicId)) {
          saving = false;
          if (currentAccount()) {
            render();
            if (isFocused()) {
              UI.showToast(R.string.SavedMessagesReplyUnavailable, Toast.LENGTH_SHORT);
            }
          }
        } else {
          sendSavedText(text, replyId);
        }
      }));
    }
  }

  private void sendSavedText (String text, long replyId) {
    TdApi.InputMessageReplyTo reply = replyId == 0 ? null : new TdApi.InputMessageReplyToMessage(replyId, null, 0, "");
    TdApi.InputMessageText input = new TdApi.InputMessageText(
      new TdApi.FormattedText(text, new TdApi.TextEntity[0]), null, false);
    tdlib.send(new TdApi.SendMessage(chatId, null, reply, Td.newSendOptions(), null, input),
      (sent, error) -> runOnUiThreadOptional(() -> {
        saving = false;
        if (currentAccount()) {
          if (error != null) {
            UI.showError(error);
            render();
          } else {
            if (matchesDraft(text, replyId)) {
              saveDraft("", 0);
            }
            render();
            if (isFocused() && sent != null && replyId != 0 && !isNotes()) {
              openMessage(sent);
            }
          }
        }
      }));
  }

  private ArrayList<TdApi.SavedMessagesTopic> sortedTopics () {
    ArrayList<TdApi.SavedMessagesTopic> topics = new ArrayList<>(tdlib.savedMessagesTopics());
    topics.sort(Comparator.comparingLong((TdApi.SavedMessagesTopic topic) -> topic.order).reversed()
      .thenComparingLong(topic -> topic.id));
    return topics;
  }

  private static long[] pinnedIds (ArrayList<TdApi.SavedMessagesTopic> topics) {
    return topics.stream().filter(topic -> topic.isPinned && topic.order != 0).mapToLong(topic -> topic.id).toArray();
  }

  private void choosePinnedTopic () {
    long[] expected = pinnedIds(sortedTopics());
    if (loading || expected.length < 2) {
      return;
    }
    Options.Builder options = new Options.Builder().info(Lang.getString(R.string.SavedMessagesReorder));
    for (int i = 0; i < expected.length; i++) {
      TdApi.SavedMessagesTopic topic = tdlib.savedMessagesTopic(expected[i]);
      if (topic == null || !topic.isPinned) {
        return;
      }
      options.item(new OptionItem(i + 1, title(topic), OptionColor.NORMAL, 0));
    }
    options.cancelItem();
    showOptions(options.build(), (view, id) -> {
      if (id > 0 && id <= expected.length) {
        tdlib.ui().post(() -> choosePinnedPosition(expected, id - 1));
      }
      return true;
    });
  }

  private void choosePinnedPosition (long[] expected, int from) {
    if (!isFocused() || !currentAccount() || !Arrays.equals(expected, pinnedIds(sortedTopics()))) {
      return;
    }
    Options.Builder options = new Options.Builder().info(Lang.getString(R.string.SavedMessagesReorderPosition));
    for (int i = 0; i < expected.length; i++) {
      if (i != from) {
        options.item(new OptionItem(i + 1, Lang.getString(R.string.SavedMessagesPosition, Lang.formatNumber(i + 1)),
          OptionColor.NORMAL, 0));
      }
    }
    options.cancelItem();
    showOptions(options.build(), (view, id) -> {
      if (id > 0 && id <= expected.length && id - 1 != from) {
        savePinnedOrder(expected, movePinnedTopic(expected, from, id - 1));
      }
      return true;
    });
  }

  static long[] movePinnedTopic (long[] expected, int from, int to) {
    long[] order = expected.clone();
    long moved = order[from];
    if (from < to) {
      System.arraycopy(order, from + 1, order, from, to - from);
    } else if (from > to) {
      System.arraycopy(order, to, order, to + 1, from - to);
    }
    order[to] = moved;
    return order;
  }

  private void savePinnedOrder (long[] expected, long[] order) {
    if (!isFocused() || !currentAccount() || saving || loading) {
      return;
    }
    if (!Arrays.equals(expected, pinnedIds(sortedTopics()))) {
      UI.showToast(R.string.SavedMessagesTopicChanged, Toast.LENGTH_SHORT);
      return;
    }
    saving = true;
    render();
    tdlib.send(new TdApi.SetPinnedSavedMessagesTopics(order), (ok, error) -> runOnUiThreadOptional(() -> {
      saving = false;
      if (currentAccount()) {
        if (isFocused() && error != null) {
          UI.showError(error);
        }
        render();
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

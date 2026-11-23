/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.thunderdog.challegram.ui;

import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;

/** A relaunch description, not a WebView session. Authenticated URLs and page contents are excluded. */
final class MiniAppTabSnapshot {
  final long botId, chatId, topic;
  final int kind, topicKind;
  final String name, parameter, title;
  final boolean compact;
  MiniAppTabSnapshot (long bot, long chat, int kind, String name, String parameter, String title, int topicKind, long topic, boolean compact) {
    if (bot <= 0 || chat < -10000000000000L || kind < 0 || kind > 3 || !name.matches("[A-Za-z0-9_]{0,64}") ||
        kind == 1 && name.isEmpty() || !parameter.matches("[A-Za-z0-9_-]{0,512}") || title.length() > 256 ||
        topicKind < 0 || topicKind > 4 || topicKind != 0 && topicKind != 4 && topic <= 0 || topicKind == 4 && topic == 0 ||
        topicKind == 2 && topic > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid tab descriptor");
    botId = bot; chatId = chat; this.kind = kind; this.name = name; this.parameter = parameter; this.title = title;
    this.topicKind = topicKind; this.topic = topic; this.compact = compact;
  }
  TdApi.MessageTopic topic () {
    return switch (topicKind) { case 1 -> new TdApi.MessageTopicThread(topic); case 2 -> new TdApi.MessageTopicForum((int) topic);
      case 3 -> new TdApi.MessageTopicDirectMessages(topic); case 4 -> new TdApi.MessageTopicSavedMessages(topic); default -> null; };
  }
  boolean sameLaunch (MiniAppTabSnapshot other) {
    return botId == other.botId && chatId == other.chatId && kind == other.kind && name.equals(other.name) &&
      parameter.equals(other.parameter) && topicKind == other.topicKind && topic == other.topic;
  }
  JSONObject json () throws JSONException {
    return new JSONObject().put("bot", botId).put("chat", chatId).put("kind", kind).put("name", name).put("parameter", parameter)
      .put("title", title).put("topic_kind", topicKind).put("topic", topic).put("compact", compact);
  }
  static String encode (Iterable<MiniAppTabSnapshot> tabs) {
    JSONArray array = new JSONArray();
    for (MiniAppTabSnapshot tab : tabs) { if (array.length() >= 5) break; try { array.put(tab.json()); } catch (JSONException ignored) { } }
    return array.toString();
  }
  static ArrayList<MiniAppTabSnapshot> decode (String data) {
    ArrayList<MiniAppTabSnapshot> result = new ArrayList<>();
    if (data == null || data.length() > 32768) return result;
    try {
      JSONArray array = new JSONArray(data);
      for (int i = 0; i < Math.min(5, array.length()); i++) {
        try {
          JSONObject tab = array.getJSONObject(i);
          result.add(new MiniAppTabSnapshot(tab.getLong("bot"), tab.getLong("chat"), tab.getInt("kind"), tab.getString("name"),
            tab.getString("parameter"), tab.getString("title"), tab.getInt("topic_kind"), tab.getLong("topic"), tab.getBoolean("compact")));
        } catch (Exception ignored) { }
      }
    } catch (JSONException ignored) { }
    return result;
  }
}

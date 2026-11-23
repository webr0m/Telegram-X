/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

import me.vkryl.core.StringUtils;

/** DeviceStorage belongs to an authenticated Telegram user and bot, independently of web origins. */
public final class MiniAppStorage {
  private static final int MAX_BYTES = 5 * 1024 * 1024;

  private MiniAppStorage () { }

  public static final class Result {
    public final String event;
    public final JSONObject data;

    private Result (String event, JSONObject data) {
      this.event = event;
      this.data = data;
    }
  }

  // Called on the background queue so disk writes cannot block the WebView bridge.
  public static Result handle (Context context, long userId, long botId, String event, JSONObject request) {
    JSONObject response = new JSONObject();
    try {
      String requestId = request.getString("req_id");
      response.put("req_id", requestId);
      if (requestId.isEmpty() || requestId.length() > 64 || userId == 0 || botId == 0) {
        return failure(response, "INVALID_REQUEST");
      }
      SharedPreferences storage = context.getSharedPreferences("miniapp_device_" + userId + "_" + botId, Context.MODE_PRIVATE);
      if ("web_app_device_storage_clear".equals(event)) {
        return storage.edit().clear().commit() ?
          new Result("device_storage_cleared", response) : failure(response, "STORAGE_FAILED");
      }
      String key = request.getString("key");
      if (key.getBytes(StringUtils.UTF_8).length > MAX_BYTES) {
        return failure(response, "KEY_INVALID");
      }
      if ("web_app_device_storage_get_key".equals(event)) {
        response.put("value", storage.contains(key) ? storage.getString(key, null) : JSONObject.NULL);
        return new Result("device_storage_key_received", response);
      }
      SharedPreferences.Editor editor = storage.edit();
      if (request.isNull("value")) {
        editor.remove(key);
      } else {
        String value = request.getString("value");
        long size = key.getBytes(StringUtils.UTF_8).length + value.getBytes(StringUtils.UTF_8).length;
        for (Map.Entry<String, ?> entry : storage.getAll().entrySet()) {
          if (!entry.getKey().equals(key)) {
            size += entry.getKey().getBytes(StringUtils.UTF_8).length + entry.getValue().toString().getBytes(StringUtils.UTF_8).length;
          }
        }
        if (size > MAX_BYTES) return failure(response, "QUOTA_EXCEEDED");
        editor.putString(key, value);
      }
      return editor.commit() ? new Result("device_storage_key_saved", response) : failure(response, "STORAGE_FAILED");
    } catch (JSONException | RuntimeException e) {
      return failure(response, "STORAGE_FAILED");
    }
  }

  private static Result failure (JSONObject response, String error) {
    try {
      response.put("error", error);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
    return new Result("device_storage_failed", response);
  }
}

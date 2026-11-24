/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

/** Turns the {@code setEmojiStatus} arguments of a Mini App into the exact TDLib request, or into a failure. */
public final class MiniAppEmojiStatus {
  public static final int MAX_DURATION_DAYS = 365;
  public static final int SECONDS_PER_DAY = 86400;
  /** The only two documented failures a page can be told about before anything reaches the server. */
  public static final String ERROR_SUGGESTED_EMOJI_INVALID = "SUGGESTED_EMOJI_INVALID";
  public static final String ERROR_DURATION_INVALID = "DURATION_INVALID";

  private MiniAppEmojiStatus () { }

  public static final class Prepared {
    public final TdApi.SetEmojiStatus request;
    public final String error;

    private Prepared (TdApi.SetEmojiStatus request, String error) {
      this.request = request;
      this.error = error;
    }

    public boolean isValid () {
      return error == null && request != null;
    }
  }

  /**
   * The page always addresses a status by its custom-emoji identifier: TDLib cannot express a plain unicode
   * emoji, an upgraded-gift badge or a default badge through this API.
   */
  public static Prepared prepare (JSONObject data, long nowSeconds) {
    if (data == null) return failed(ERROR_SUGGESTED_EMOJI_INVALID);
    String id = data.optString("custom_emoji_id", "");
    if (id.length() > 19 || !id.matches("[1-9][0-9]{0,18}")) return failed(ERROR_SUGGESTED_EMOJI_INVALID);
    long customEmojiId;
    try {
      customEmojiId = Long.parseLong(id);
    } catch (NumberFormatException e) {
      return failed(ERROR_SUGGESTED_EMOJI_INVALID);
    }
    int days;
    if (data.isNull("duration") || !data.has("duration")) {
      days = 0;
    } else {
      days = data.optInt("duration", -1);
      if (days < 1 || days > MAX_DURATION_DAYS) return failed(ERROR_DURATION_INVALID);
    }
    int expirationDate = days == 0 ? 0 : (int) Math.min((long) Integer.MAX_VALUE - 1L, nowSeconds + (long) days * SECONDS_PER_DAY);
    TdApi.EmojiStatus status = new TdApi.EmojiStatus(new TdApi.EmojiStatusTypeCustomEmoji(customEmojiId), expirationDate);
    return new Prepared(new TdApi.SetEmojiStatus(status), null);
  }

  /** Access is only about Premium: without it the page has to be sent to the premium screen instead. */
  public static String accessStatus (boolean hasPremium) {
    return hasPremium ? "allowed" : "cancelled";
  }

  private static Prepared failed (String error) {
    return new Prepared(null, error);
  }
}

/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import org.json.JSONObject;

/**
 * Everything a page asks the bottom button to look like, read out of {@code web_app_setup_main_button}
 * and {@code web_app_setup_secondary_button}. The SDK sends its whole state on every event, so parsing a
 * single payload is enough to know how the button has to look; nothing here accumulates.
 */
public final class MiniAppButtonParams {
  private MiniAppButtonParams () { }

  public static final class Value {
    public final String text;
    public final long emojiId;
    public final boolean visible, active, progress, shine;
    /** 0 when the button has to keep the theme colors, otherwise an opaque color. */
    public final int textColor, backgroundColor;

    private Value (String text, long emojiId, boolean active, boolean progress, boolean shine, int textColor, int backgroundColor) {
      this.text = text;
      this.emojiId = emojiId;
      this.active = active;
      this.progress = progress;
      this.shine = shine;
      this.textColor = textColor;
      this.backgroundColor = backgroundColor;
      // A button without a label and without an icon has nothing to show, and a page cannot make one
      // appear: the SDK refuses that combination before it ever reaches the client.
      this.visible = !text.isEmpty() || emojiId != 0;
    }
  }

  private static final Value HIDDEN = new Value("", 0, false, false, false, 0, 0);

  public static Value parse (JSONObject data) {
    if (data == null || !data.optBoolean("is_visible")) {
      return HIDDEN;
    }
    String text = trim(data.optString("text"));
    long emojiId = emojiId(data.opt("icon_custom_emoji_id"));
    boolean progress = data.optBoolean("is_progress_visible");
    boolean active = data.optBoolean("is_active", true);
    return new Value(text, emojiId, active, progress,
      data.optBoolean("has_shine_effect") && active && !progress,
      MiniAppHeaderColor.parse(data.optString("text_color")),
      MiniAppHeaderColor.parse(data.optString("color")));
  }

  /**
   * The SDK hands over a decimal string of ten to twenty digits, or something that cannot be an id at all.
   * Twenty digits already overflow a long, so that range only has meaning as a shape: what is kept is a
   * number a view can actually use, and everything else simply means the button has no icon.
   */
  public static long emojiId (Object value) {
    String string = String.valueOf(value);
    if (string.length() < 10) {
      return 0;
    }
    for (int i = 0; i < string.length(); i++) {
      if (string.charAt(i) < '0' || string.charAt(i) > '9') {
        return 0;
      }
    }
    try {
      return Long.parseLong(string);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** A button that has nothing to show, or that is busy, does not answer taps. */
  public static boolean isClickable (Value value) {
    return value.visible && value.active && !value.progress;
  }

  /** A page can ask for a button that does nothing, and the only honest way to say so is to dim it. */
  public static float contentAlpha (boolean active) {
    return active ? 1f : 0.8f;
  }

  /** The sweep only occupies a fraction of its cycle; the rest of it is the pause between two shines. */
  private static final float SHINE_SWEEP_PART = 0.12f;

  /**
   * Where the sweep sits during one animation cycle: 0 at its beginning, 1 at its end, or a negative value
   * while the button waits for the next one, which is also the signal to stop redrawing.
   */
  public static float shinePosition (float cycle) {
    return cycle <= SHINE_SWEEP_PART ? cycle / SHINE_SWEEP_PART : -1f;
  }

  /** Blank runs are folded so that a label of nothing but spaces does not reserve a row. */
  private static String trim (String text) {
    if (text == null) {
      return "";
    }
    String result = text.trim();
    return result.length() > 64 ? result.substring(0, 64) : result;
  }
}

/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import org.json.JSONObject;

/**
 * The vibration a page asked for, and the effect an Android device can actually deliver for it.
 *
 * {@code web_app_trigger_haptic_feedback} carries nine distinct sensations, but it names them in two
 * different fields depending on {@code type}, and Android only gained its effect constants in five
 * separate releases. Both of those belong in one table, so the answer this class gives is a symbolic
 * {@code EFFECT_*} id resolved against the API level of the device rather than a constant picked at the
 * call site.
 */
public final class MiniAppHaptic {
  public static final int NONE = 0;
  public static final int IMPACT_LIGHT = 1;
  public static final int IMPACT_SOFT = 2;
  public static final int IMPACT_MEDIUM = 3;
  public static final int IMPACT_RIGID = 4;
  public static final int IMPACT_HEAVY = 5;
  public static final int NOTIFY_SUCCESS = 6;
  public static final int NOTIFY_WARNING = 7;
  public static final int NOTIFY_ERROR = 8;
  public static final int SELECTION = 9;

  /** Effect ids the UI layer maps onto {@code HapticFeedbackConstants}; {@link #EFFECT_NONE} means no call at all. */
  public static final int EFFECT_NONE = 0;
  public static final int EFFECT_LONG_PRESS = 1;
  public static final int EFFECT_KEYBOARD_TAP = 2;
  public static final int EFFECT_CLOCK_TICK = 3;
  public static final int EFFECT_CONTEXT_CLICK = 4;
  public static final int EFFECT_TEXT_HANDLE_MOVE = 5;
  public static final int EFFECT_CONFIRM = 6;
  public static final int EFFECT_REJECT = 7;
  public static final int EFFECT_SEGMENT_FREQUENT_TICK = 8;

  /** API levels the individual effect constants were added in. */
  static final int SDK_CLOCK_TICK = 21;
  static final int SDK_CONTEXT_CLICK = 23;
  static final int SDK_TEXT_HANDLE_MOVE = 27;
  static final int SDK_CONFIRM = 30;
  static final int SDK_SEGMENT_FREQUENT_TICK = 34;

  private MiniAppHaptic () {}

  /**
   * Reads {@code type} and whichever sub-field that type owns. The SDK throws before it ever sends an
   * unlisted style, so one arriving here means a patched page: {@link #NONE} keeps the client from
   * inventing a vibration nobody asked for.
   */
  public static int parse (JSONObject data) {
    if (data == null) {
      return NONE;
    }
    String type = data.optString("type", "");
    if (type.equals("selection_change")) {
      return SELECTION;
    } else if (type.equals("impact")) {
      return impact(data.optString("impact_style", ""));
    } else if (type.equals("notification")) {
      return notification(data.optString("notification_type", ""));
    }
    return NONE;
  }

  private static int impact (String style) {
    if (style.equals("light")) return IMPACT_LIGHT;
    if (style.equals("soft")) return IMPACT_SOFT;
    if (style.equals("medium")) return IMPACT_MEDIUM;
    if (style.equals("rigid")) return IMPACT_RIGID;
    if (style.equals("heavy")) return IMPACT_HEAVY;
    return NONE;
  }

  private static int notification (String type) {
    if (type.equals("success")) return NOTIFY_SUCCESS;
    if (type.equals("warning")) return NOTIFY_WARNING;
    if (type.equals("error")) return NOTIFY_ERROR;
    return NONE;
  }

  /**
   * Picks the oldest-proof effect that still reads as what the page meant. {@code KEYBOARD_TAP} and
   * {@code LONG_PRESS} exist since API 8 and 3, so they are the floor everything else falls back to;
   * {@code soft} and {@code light} only differ where Android has a subtler constant than a clock tick.
   */
  public static int effectFor (int kind, int sdkInt) {
    switch (kind) {
      case IMPACT_LIGHT:
        return sdkInt >= SDK_CLOCK_TICK ? EFFECT_CLOCK_TICK : EFFECT_KEYBOARD_TAP;
      case IMPACT_SOFT:
        return sdkInt >= SDK_TEXT_HANDLE_MOVE ? EFFECT_TEXT_HANDLE_MOVE :
          sdkInt >= SDK_CLOCK_TICK ? EFFECT_CLOCK_TICK : EFFECT_KEYBOARD_TAP;
      case IMPACT_MEDIUM:
        return sdkInt >= SDK_CONTEXT_CLICK ? EFFECT_CONTEXT_CLICK : EFFECT_KEYBOARD_TAP;
      case IMPACT_RIGID:
        return EFFECT_KEYBOARD_TAP;
      case IMPACT_HEAVY:
        return EFFECT_LONG_PRESS;
      case NOTIFY_SUCCESS:
        return sdkInt >= SDK_CONFIRM ? EFFECT_CONFIRM :
          sdkInt >= SDK_CONTEXT_CLICK ? EFFECT_CONTEXT_CLICK : EFFECT_KEYBOARD_TAP;
      case NOTIFY_WARNING:
        return EFFECT_LONG_PRESS;
      case NOTIFY_ERROR:
        return sdkInt >= SDK_CONFIRM ? EFFECT_REJECT : EFFECT_LONG_PRESS;
      case SELECTION:
        return sdkInt >= SDK_SEGMENT_FREQUENT_TICK ? EFFECT_SEGMENT_FREQUENT_TICK :
          sdkInt >= SDK_CLOCK_TICK ? EFFECT_CLOCK_TICK : EFFECT_KEYBOARD_TAP;
      default:
        return EFFECT_NONE;
    }
  }
}

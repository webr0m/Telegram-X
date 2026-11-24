/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import org.json.JSONObject;

/** Header color requested through {@code web_app_set_header_color}, which is either a theme key or a literal color. */
public final class MiniAppHeaderColor {
  public static final int THEME = 0;
  public static final int BACKGROUND = 1;
  public static final int SECONDARY = 2;
  public static final int CUSTOM = 3;

  private MiniAppHeaderColor () { }

  public static final class Value {
    public final int kind;
    public final int argb;

    private Value (int kind, int argb) {
      this.kind = kind;
      this.argb = argb;
    }
  }

  private static final Value THEME_VALUE = new Value(THEME, 0);

  public static Value parse (JSONObject data) {
    if (data == null) return THEME_VALUE;
    if (data.has("color")) return new Value(CUSTOM, parse(data.optString("color")));
    String key = data.optString("color_key");
    if ("bg_color".equals(key)) return new Value(BACKGROUND, 0);
    if ("secondary_bg_color".equals(key)) return new Value(SECONDARY, 0);
    return THEME_VALUE;
  }

  /** @return 0 when the page sent something that is not a six-digit color, which resets the header. */
  public static int parse (String color) {
    if (color == null || !color.matches("#[0-9a-fA-F]{6}")) return 0;
    return (int) (0xff000000L | Long.parseLong(color.substring(1), 16));
  }

  /**
   * The color the header ends up showing, always opaque so it can be painted over the theme, or 0 when the
   * header has to follow the theme again. Theme parameters are reported without an alpha channel, which is
   * why the keywords cannot be handed to a view as they come.
   */
  public static int resolve (int kind, int argb, int backgroundArgb, int secondaryArgb) {
    switch (kind) {
      case BACKGROUND: return backgroundArgb | 0xff000000;
      case SECONDARY: return secondaryArgb | 0xff000000;
      case CUSTOM: return argb == 0 ? 0 : argb | 0xff000000;
      default:
      case THEME: return 0;
    }
  }

  /**
   * Whether the controls sitting on top of that color read better in white. The weights are the same luma
   * coefficients the app already uses for luminance, and 128 is the midpoint of the eight-bit range: a pure
   * red or a pure blue keeps white controls, a pure yellow does not.
   */
  public static boolean usesLightContent (int argb) {
    int luma = (((argb >> 16) & 0xff) * 299 + ((argb >> 8) & 0xff) * 587 + (argb & 0xff) * 114) / 1000;
    return luma < 128;
  }
}

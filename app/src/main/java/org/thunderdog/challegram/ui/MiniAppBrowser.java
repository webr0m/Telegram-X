/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.navigation.ViewController;

/**
 * `try_browser` names a browser, it never carries a package: this table is the only authority on what a page asks for.
 */
final class MiniAppBrowser {
  private MiniAppBrowser () { }

  static String packageName (String name) {
    if (name == null) {
      return null;
    }
    switch (name) {
      case "google-chrome":
      case "chrome": return "com.android.chrome";
      case "mozilla-firefox":
      case "firefox": return "org.mozilla.firefox";
      case "microsoft-edge":
      case "edge": return "com.microsoft.emmx";
      case "opera": return "com.opera.browser";
      case "opera-mini": return "com.opera.mini.native";
      case "brave":
      case "brave-browser": return "com.brave.browser";
      case "duckduckgo":
      case "duckduckgo-browser": return "com.duckduckgo.mobile.android";
      case "samsung":
      case "samsung-browser": return "com.sec.android.app.sbrowser";
      case "vivaldi":
      case "vivaldi-browser": return "com.vivaldi.browser";
      case "kiwi":
      case "kiwi-browser": return "com.kiwibrowser.browser";
      case "uc":
      case "uc-browser": return "com.UCMobile.intl";
      case "tor":
      case "tor-browser": return "org.torproject.torbrowser";
    }
    return null;
  }

  static boolean open (ViewController<?> owner, String url, String packageName) {
    BaseActivity context = owner != null ? owner.context() : null;
    if (context == null || packageName == null) {
      return false;
    }
    try {
      context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(packageName));
      return true;
    } catch (ActivityNotFoundException | SecurityException ignored) {
      return false;
    }
  }
}

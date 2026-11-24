/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The layout of the preference that holds one bot's sealed values: {@code {"key": {"v": iv+ciphertext, "b": 1}}}
 * with at most the documented number of keys. Only the bookkeeping lives here; sealing and unsealing stay in the
 * Android host, which lets the capacity and damage rules be checked without a framework.
 */
public final class MiniAppSecureEntries {
  /** Anything longer is treated as damage rather than as data: no bot may grow the preference without bound. */
  public static final int MAX_JSON_CHARS = 128 * 1024;

  public static final int OK = 0;
  public static final int FULL = 1;
  /** The stored document cannot be trusted, so nothing may be overwritten onto it. */
  public static final int CORRUPT = 2;

  /** The state of a single stored entry. {@link #status} uses the vocabulary of {@link MiniAppSecureStorage.Value}. */
  public static final class Stored {
    public final int status;
    public final String blob;

    private Stored (int status, String blob) {
      this.status = status;
      this.blob = blob;
    }
  }

  /** The outcome of a write: {@link #document} being {@code null} means the preference should be removed. */
  public static final class Result {
    public final int status;
    public final String document;

    private Result (int status, String document) {
      this.status = status;
      this.document = document;
    }
  }

  private MiniAppSecureEntries () { }

  public static Stored inspect (String raw, String key) {
    if (key == null || key.isEmpty()) return new Stored(MiniAppSecureStorage.Value.MISSING, null);
    if (raw == null || raw.length() == 0) return new Stored(MiniAppSecureStorage.Value.MISSING, null);
    JSONObject entries = parse(raw);
    if (entries == null) return new Stored(MiniAppSecureStorage.Value.FAILED, null);
    Object entry = entries.opt(key);
    if (entry == null) return new Stored(MiniAppSecureStorage.Value.MISSING, null);
    if (!(entry instanceof JSONObject)) return new Stored(MiniAppSecureStorage.Value.FAILED, null);
    Object blob = ((JSONObject) entry).opt("v");
    if (!(blob instanceof String) || ((String) blob).length() == 0) {
      return new Stored(MiniAppSecureStorage.Value.FAILED, null);
    }
    boolean bound = ((JSONObject) entry).optInt("b") != 0;
    return new Stored(bound ? MiniAppSecureStorage.Value.NEEDS_BIOMETRIC : MiniAppSecureStorage.Value.PRESENT, (String) blob);
  }

  /** @param value {@code null} erases the key; otherwise {@code blob} is the sealed bytes of the new value. */
  public static Result write (String raw, String key, String value, String blob, boolean bound) {
    if (key == null || key.isEmpty()) return new Result(CORRUPT, null);
    JSONObject entries = raw == null || raw.length() == 0 ? new JSONObject() : parse(raw);
    if (entries == null) return new Result(CORRUPT, null);
    if (value == null) {
      entries.remove(key);
      return new Result(OK, entries.length() == 0 ? null : entries.toString());
    }
    if (blob == null) return new Result(CORRUPT, null);
    if (entries.opt(key) == null && entries.length() >= MiniAppSecureStorage.MAX_KEYS_PER_BOT) {
      return new Result(FULL, null);
    }
    try {
      JSONObject entry = new JSONObject();
      entry.put("v", blob);
      if (bound) entry.put("b", 1);
      entries.put(key, entry);
    } catch (JSONException e) {
      return new Result(CORRUPT, null);
    }
    return new Result(OK, entries.toString());
  }

  /** Keeps every entry except one, which is how a clear and an erase are expressed. */
  public static Result erase (String raw, String key) {
    return write(raw, key, null, null, false);
  }

  public static int count (String raw) {
    JSONObject entries = parse(raw == null || raw.length() == 0 ? "" : raw);
    return entries == null ? 0 : entries.length();
  }

  private static JSONObject parse (String raw) {
    if (raw == null || raw.length() == 0 || raw.length() > MAX_JSON_CHARS) return null;
    try {
      return new JSONObject(raw);
    } catch (JSONException e) {
      return null;
    }
  }
}

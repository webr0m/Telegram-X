/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The {@code SecureStorage} protocol of a Mini App. Values themselves are sealed and persisted by the host;
 * this class decides which key may be written, what the page is allowed to read without authenticating, and
 * which error string a failure is reported as.
 */
public final class MiniAppSecureStorage {
  /** Documented by the Bot API: each bot may keep up to 10 items per user. */
  public static final int MAX_KEYS_PER_BOT = 10;
  public static final int MAX_KEY_CHARS = 64;
  public static final int MAX_VALUE_CHARS = 4096;

  public static final String ERROR_DATA_INVALID = "DATA_INVALID";
  public static final String ERROR_DATA_NOT_FOUND = "DATA_NOT_FOUND";
  public static final String ERROR_BIOMETRY_NOT_AVAILABLE = "BIOMETRY_NOT_AVAILABLE";
  public static final String ERROR_BIOMETRY_NOT_CONSENT = "BIOMETRY_NOT_CONSENT";
  public static final String ERROR_BIOMETRY_FAILED = "BIOMETRY_FAILED";
  public static final String ERROR_STATE_FORBIDDEN = "STATE_FORBIDDEN";
  public static final String ERROR_UNKNOWN = "UNKNOWN_ERROR";

  public static final class Value {
    public static final int PRESENT = 0;
    public static final int MISSING = 1;
    public static final int NEEDS_BIOMETRIC = 2;
    public static final int FAILED = 3;

    public final int status;
    public final String text;

    private Value (int status, String text) {
      this.status = status;
      this.text = text;
    }

    public static Value of (String text) {
      return new Value(PRESENT, text);
    }

    public static Value missing () {
      return new Value(MISSING, null);
    }

    /** The entry exists but only a biometric authentication may reveal it. */
    public static Value restricted () {
      return new Value(NEEDS_BIOMETRIC, null);
    }

    public static Value failed () {
      return new Value(FAILED, null);
    }
  }

  public interface Host {
    int WRITE_OK = 0;
    int WRITE_FULL = 1;
    int WRITE_FAILED = 2;

    void publish (String event, JSONObject data);

    /** Reads an entry without authenticating; {@link Value#NEEDS_BIOMETRIC} for a bound entry. */
    Value read (String key);

    /** A {@code null} value erases the key. Binding requires {@link #canUseBiometrics()}. */
    int write (String key, String value, boolean bindToBiometric);

    boolean eraseAll ();

    /** Biometrics are usable for storage only while the page could restore an entry later. */
    boolean canUseBiometrics ();

    /** Whether the bot was granted biometric access; decides between the two consent-related errors. */
    boolean isBiometryConsented ();

    /** Asks the user; the outcome is delivered through {@link #onRestoreResult}. */
    void authenticate (int run, String key);

    int nextRunToken ();
  }

  private final Host host;
  private int restoreRun;
  private String restoreReqId;

  public MiniAppSecureStorage (Host host) {
    if (host == null) throw new IllegalArgumentException("host == null");
    this.host = host;
  }

  public void saveKey (JSONObject request) {
    String reqId = reqId(request);
    String key = key(request);
    if (key == null) {
      failed(reqId, ERROR_DATA_INVALID);
      return;
    }
    boolean erase = !request.has("value") || request.isNull("value");
    String value = erase ? null : request.optString("value", "");
    if (value != null && value.length() > MAX_VALUE_CHARS) {
      failed(reqId, ERROR_DATA_INVALID);
      return;
    }
    switch (host.write(key, value, value != null && host.canUseBiometrics())) {
      default: case Host.WRITE_OK:
        host.publish("secure_storage_key_saved", json("req_id", reqId));
        break;
      case Host.WRITE_FULL:
        failed(reqId, ERROR_DATA_INVALID);
        break;
      case Host.WRITE_FAILED:
        failed(reqId, ERROR_UNKNOWN);
        break;
    }
  }

  public void getKey (JSONObject request) {
    String reqId = reqId(request);
    String key = key(request);
    if (key == null) {
      failed(reqId, ERROR_DATA_INVALID);
      return;
    }
    Value entry = host.read(key);
    if (entry.status == Value.FAILED) {
      failed(reqId, ERROR_UNKNOWN);
    } else if (entry.status == Value.NEEDS_BIOMETRIC) {
      host.publish("secure_storage_key_received", json("req_id", reqId, "can_restore", true));
    } else if (entry.status == Value.MISSING) {
      host.publish("secure_storage_key_received", json("req_id", reqId));
    } else {
      host.publish("secure_storage_key_received", json("req_id", reqId, "value", entry.text));
    }
  }

  public void restoreKey (JSONObject request) {
    String reqId = reqId(request);
    String key = key(request);
    if (key == null) {
      failed(reqId, ERROR_DATA_INVALID);
      return;
    }
    Value entry = host.read(key);
    if (entry.status == Value.FAILED) {
      failed(reqId, ERROR_UNKNOWN);
    } else if (entry.status == Value.MISSING) {
      failed(reqId, ERROR_DATA_NOT_FOUND);
    } else if (entry.status == Value.PRESENT) {
      host.publish("secure_storage_key_restored", json("req_id", reqId, "value", entry.text));
    } else if (restoreRun != 0) {
      failed(reqId, ERROR_STATE_FORBIDDEN);
    } else if (!host.canUseBiometrics()) {
      failed(reqId, host.isBiometryConsented() ? ERROR_BIOMETRY_NOT_AVAILABLE : ERROR_BIOMETRY_NOT_CONSENT);
    } else {
      restoreReqId = reqId;
      restoreRun = host.nextRunToken();
      host.authenticate(restoreRun, key);
    }
  }

  public void onRestoreResult (int run, boolean authenticated, String value) {
    if (run != restoreRun) return;
    String pending = restoreReqId;
    restoreRun = 0;
    restoreReqId = null;
    if (!authenticated) {
      failed(pending, ERROR_BIOMETRY_FAILED);
    } else if (value == null) {
      failed(pending, ERROR_DATA_NOT_FOUND);
    } else {
      host.publish("secure_storage_key_restored", json("req_id", pending, "value", value));
    }
  }

  public void clear (JSONObject request) {
    String reqId = reqId(request);
    if (host.eraseAll()) {
      host.publish("secure_storage_cleared", json("req_id", reqId));
    } else {
      failed(reqId, ERROR_UNKNOWN);
    }
  }

  /** The page is gone or reloaded: an answer to an older prompt may not be delivered to it. */
  public void invalidate () {
    restoreRun = 0;
    restoreReqId = null;
  }

  private void failed (String reqId, String error) {
    host.publish("secure_storage_failed", json("req_id", reqId, "error", error));
  }

  private static String reqId (JSONObject request) {
    String value = request == null ? null : request.optString("req_id", null);
    return value == null || value.length() > 64 ? "" : value;
  }

  /** {@code null} marks a key the page was not allowed to use. */
  private static String key (JSONObject request) {
    if (request == null) return null;
    String value = request.optString("key", "");
    return value.isEmpty() || value.length() > MAX_KEY_CHARS ? null : value;
  }

  private static JSONObject json (Object... entries) {
    JSONObject object = new JSONObject();
    try {
      for (int i = 0; i < entries.length; i += 2) {
        if (entries[i + 1] != null) {
          object.put((String) entries[i], entries[i + 1]);
        }
      }
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
    return object;
  }
}

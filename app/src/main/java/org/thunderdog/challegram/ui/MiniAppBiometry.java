/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The {@code BiometricManager} protocol of a Mini App: which answers the page may receive and when a system
 * prompt may be shown at all. The Android side reports hardware state and runs prompts; every decision about
 * what the page learns lives here.
 */
public final class MiniAppBiometry {
  public static final int MAX_REASON_CHARS = 128;
  public static final int MAX_TOKEN_CHARS = 1024;

  public static final String AUTH_AUTHORIZED = "authorized";
  public static final String AUTH_CANCELLED = "cancelled";
  public static final String AUTH_FAILED = "failed";
  public static final String AUTH_NOT_AVAILABLE = "not_available";

  public static final String TOKEN_UPDATED = "updated";
  public static final String TOKEN_REMOVED = "removed";
  public static final String TOKEN_FAILED = "failed";
  public static final String TOKEN_NOT_AVAILABLE = "not_available";

  public interface Host {
    void publish (String event, JSONObject data);
    /** The device can authenticate with biometrics and the platform API can show a prompt. */
    boolean isAvailable ();
    /** {@code finger}, {@code face} or {@code unknown}; only meaningful while {@link #isAvailable()} holds. */
    String biometricType ();
    /** Stable per-install identifier, never derived from real hardware data. */
    String deviceId ();
    boolean isAccessRequested ();
    boolean isAccessGranted ();
    boolean isTokenSaved ();
    /** The outcome arrives through {@link #onAccessResult}. */
    void promptAccess (int run, String reason);
    /** The outcome arrives through {@link #onAuthResult}. */
    void promptAuth (int run, String reason);
    /** An empty token clears it; the outcome arrives through {@link #onTokenResult}. */
    void storeToken (int run, String token);
    void openSettings ();
    int nextRunToken ();
  }

  private final Host host;
  private int accessRun;
  private int authRun;
  private int tokenRun;

  public MiniAppBiometry (Host host) {
    if (host == null) throw new IllegalArgumentException("host == null");
    this.host = host;
  }

  public void getInfo () {
    publishInfo();
  }

  /**
   * {@code web_app_biometry_request_access}. The SDK resolves its callback from a {@code biometry_info_received}
   * event, so every outcome — including the ones that never show a prompt — answers with the current state.
   */
  public void requestAccess (JSONObject request) {
    String reason = reason(request);
    if (accessRun != 0 || !host.isAvailable() || reason == null) {
      publishInfo();
      return;
    }
    accessRun = host.nextRunToken();
    host.promptAccess(accessRun, reason);
  }

  /** The persisted consent state must already reflect the user's answer. */
  public void onAccessResult (int run) {
    if (run != accessRun) return;
    accessRun = 0;
    publishInfo();
  }

  public void requestAuth (JSONObject request) {
    String reason = reason(request);
    if (!host.isAvailable()) {
      host.publish("biometry_auth_requested", json("status", AUTH_NOT_AVAILABLE));
      return;
    }
    if (authRun != 0 || reason == null || !host.isAccessGranted()) {
      host.publish("biometry_auth_requested", json("status", AUTH_FAILED));
      return;
    }
    authRun = host.nextRunToken();
    host.promptAuth(authRun, reason);
  }

  public void onAuthResult (int run, boolean authorized, boolean cancelled, String token) {
    if (run != authRun) return;
    authRun = 0;
    if (!authorized) {
      host.publish("biometry_auth_requested", json("status", cancelled ? AUTH_CANCELLED : AUTH_FAILED));
      return;
    }
    host.publish("biometry_auth_requested", json("status", AUTH_AUTHORIZED, "token", token == null ? "" : token));
  }

  public void updateToken (JSONObject request) {
    if (!host.isAvailable()) {
      host.publish("biometry_token_updated", json("status", TOKEN_NOT_AVAILABLE));
      return;
    }
    if (tokenRun != 0 || !host.isAccessGranted() || request == null || !request.has("token")) {
      host.publish("biometry_token_updated", json("status", TOKEN_FAILED));
      return;
    }
    String token = request.optString("token", "");
    if (token.length() > MAX_TOKEN_CHARS) {
      host.publish("biometry_token_updated", json("status", TOKEN_FAILED));
      return;
    }
    tokenRun = host.nextRunToken();
    host.storeToken(tokenRun, token);
  }

  public void onTokenResult (int run, boolean saved, boolean failed) {
    if (run != tokenRun) return;
    tokenRun = 0;
    host.publish("biometry_token_updated", json("status",
      failed ? TOKEN_FAILED : saved ? TOKEN_UPDATED : TOKEN_REMOVED));
  }

  /** Settings are only worth opening while the bot is blocked on a denial it cannot resolve itself. */
  public void openSettings () {
    if (host.isAvailable() && host.isAccessRequested() && !host.isAccessGranted()) {
      host.openSettings();
    }
  }

  /** The page is gone or reloaded: a prompt that is still on screen may not answer it. */
  public void invalidate () {
    accessRun = 0;
    authRun = 0;
    tokenRun = 0;
  }

  private void publishInfo () {
    boolean available = host.isAvailable();
    host.publish("biometry_info_received", json(
      "available", available,
      "type", available ? host.biometricType() : "unknown",
      "access_requested", available && host.isAccessRequested(),
      "access_granted", available && host.isAccessGranted(),
      "token_saved", available && host.isTokenSaved(),
      "device_id", host.deviceId()));
  }

  /** {@code null} marks a reason the page was not allowed to send. */
  private static String reason (JSONObject request) {
    String value = request == null ? "" : request.optString("reason", "").trim();
    return value.length() > MAX_REASON_CHARS ? null : value;
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

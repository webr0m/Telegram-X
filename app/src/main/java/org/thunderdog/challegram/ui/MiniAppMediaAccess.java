/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * The camera and microphone states a Mini App is told about, plus the answers to the two events the media shim
 * of the SDK sends. That shim replaces {@code getUserMedia}, {@code enumerateDevices} and
 * {@code permissions.query} with this table: it only trusts the client once {@link #CHECKED} arrives with
 * {@code supported}, and every status it reads has to be one of the four strings below.
 */
public final class MiniAppMediaAccess {
  public static final String GRANTED = "granted";
  public static final String DENIED = "denied";
  public static final String NOT_REQUESTED = "not_requested";
  /** The hardware is missing, which the page turns into a {@code NotFoundError} rather than a refusal. */
  public static final String UNAVAILABLE = "unavailable";

  public static final String CHECKED = "media_access_checked";
  public static final String REQUESTED = "media_access_requested";
  public static final String CHANGED = "media_access_changed";

  /** {@code android.webkit.PermissionRequest} resource names, spelled out to keep this class Android-free. */
  public static final String RESOURCE_VIDEO = "android.webkit.resource.VIDEO_CAPTURE";
  public static final String RESOURCE_AUDIO = "android.webkit.resource.AUDIO_CAPTURE";
  public static final String RESOURCE_VIDEO_AUDIO = "android.webkit.resource.VIDEO_AUDIO_CAPTURE";

  public static final int CAMERA = 0;
  public static final int MICROPHONE = 1;

  public interface Host {
    void publish (String event, JSONObject data);
    /** The page is on screen, resumed, and this request follows a real touch on it. */
    boolean isUserInitiated ();
    boolean hasHardware (int kind);
    boolean isGranted (int kind);
    /**
     * Shows the system dialog for the listed kinds. {@code false} means no dialog can appear, so the outcome is
     * already final and no {@link #onPromptFinished} call follows.
     */
    boolean promptPermissions (boolean camera, boolean microphone);
  }

  /** What a {@code PermissionRequest} may be handed back, and whether that covers everything it asked for. */
  public static final class Grant {
    public final String[] resources;
    public final boolean complete;

    Grant (String[] resources, boolean complete) {
      this.resources = resources;
      this.complete = complete;
    }
  }

  private final Host host;
  private boolean askedCamera, askedMicrophone, promptPending;
  private boolean cameraGrantedAtLoad, microphoneGrantedAtLoad;
  private String lastPublished;

  public MiniAppMediaAccess (Host host) {
    if (host == null) throw new IllegalArgumentException("host == null");
    this.host = host;
  }

  /** A new document re-reads the sensors, so grants made during the previous one are no longer pending. */
  public void documentStarted () {
    cameraGrantedAtLoad = host.isGranted(CAMERA);
    microphoneGrantedAtLoad = host.isGranted(MICROPHONE);
    askedCamera = askedMicrophone = promptPending = false;
    lastPublished = null;
  }

  public String status (int kind) {
    if (!host.hasHardware(kind)) {
      return UNAVAILABLE;
    }
    if (host.isGranted(kind)) {
      return GRANTED;
    }
    return (kind == CAMERA ? askedCamera : askedMicrophone) ? DENIED : NOT_REQUESTED;
  }

  /** True, if the WebView cannot see a grant this client only just obtained, so the page has to reload. */
  public boolean isReloadRequired () {
    return (!cameraGrantedAtLoad && host.isGranted(CAMERA)) ||
      (!microphoneGrantedAtLoad && host.isGranted(MICROPHONE));
  }

  public void check (JSONObject data) {
    if (data == null || !data.optBoolean("shim_installed")) {
      host.publish(CHECKED, json("supported", false));
      return;
    }
    publish(CHECKED, true);
  }

  /**
   * Answers with the current table whenever there is nothing to ask, which lets the page keep a permission it
   * already has and stop waiting on one it may never be shown.
   */
  public void request (JSONObject data) {
    boolean camera = data != null && data.optBoolean("camera");
    boolean microphone = data != null && data.optBoolean("microphone");
    boolean needsCamera = camera && status(CAMERA).equals(NOT_REQUESTED);
    boolean needsMicrophone = microphone && status(MICROPHONE).equals(NOT_REQUESTED);
    if (promptPending || !host.isUserInitiated()) {
      publish(REQUESTED, false);
      return;
    }
    if (!needsCamera && !needsMicrophone) {
      publish(REQUESTED, false);
      return;
    }
    // Whatever follows, the user has been shown this kind: a refusal must read as denied rather than invite
    // the page to ask again, and that also covers a device where no dialog can be raised at all.
    promptPending = true;
    if (needsCamera) {
      askedCamera = true;
    }
    if (needsMicrophone) {
      askedMicrophone = true;
    }
    if (!host.promptPermissions(needsCamera, needsMicrophone)) {
      promptPending = false;
      publish(REQUESTED, false);
    }
  }

  /** The current document is gone: a dialog that is still open may not answer anything. */
  public void invalidate () {
    promptPending = false;
  }

  public void onPromptFinished () {
    if (!promptPending) return;
    promptPending = false;
    publish(REQUESTED, false);
  }

  /** Pushed when the OS state changes behind the page's back; an unchanged table would only be noise. */
  public void onStateChanged () {
    if (signature(state()).equals(lastPublished)) return;
    publish(CHANGED, false);
  }

  /**
   * Decides what the WebView itself may use. The page is answered either way, so both kinds in the request stop
   * being {@link #NOT_REQUESTED} and a refusal reads as {@link #DENIED} instead of asking again.
   */
  public Grant onWebViewRequest (String[] requested) {
    boolean camera = false, microphone = false;
    if (requested != null) {
      for (String resource : requested) {
        if (RESOURCE_VIDEO.equals(resource)) {
          camera = true;
        } else if (RESOURCE_AUDIO.equals(resource)) {
          microphone = true;
        } else if (RESOURCE_VIDEO_AUDIO.equals(resource)) {
          camera = microphone = true;
        }
      }
    }
    if (camera) {
      askedCamera = true;
    }
    if (microphone) {
      askedMicrophone = true;
    }
    return resolve(requested, host.isGranted(CAMERA), host.isGranted(MICROPHONE));
  }

  /** Maps requested resources onto what the app holds; an unrecognised resource is never granted. */
  public static Grant resolve (String[] requested, boolean cameraGranted, boolean microphoneGranted) {
    if (requested == null || requested.length == 0) {
      return new Grant(new String[0], true);
    }
    List<String> allowed = new ArrayList<>(requested.length);
    boolean complete = true;
    for (String resource : requested) {
      boolean ok;
      if (RESOURCE_VIDEO.equals(resource)) {
        ok = cameraGranted;
      } else if (RESOURCE_AUDIO.equals(resource)) {
        ok = microphoneGranted;
      } else if (RESOURCE_VIDEO_AUDIO.equals(resource)) {
        ok = cameraGranted && microphoneGranted;
      } else {
        ok = false;
      }
      if (ok) {
        allowed.add(resource);
      } else {
        complete = false;
      }
    }
    return new Grant(allowed.toArray(new String[0]), complete);
  }

  private void publish (String event, boolean forCheck) {
    JSONObject state = state();
    lastPublished = signature(state);
    try {
      if (forCheck) {
        state.put("supported", true);
      }
    } catch (JSONException e) {
      throw new RuntimeException(e);
    }
    host.publish(event, state);
  }

  private JSONObject state () {
    return json(
      "camera", status(CAMERA),
      "microphone", status(MICROPHONE),
      "reload_required", isReloadRequired());
  }

  /** Sorted by key: {@code JSONObject} keeps insertion order, and equal states must compare equal. */
  private static String signature (JSONObject json) {
    List<String> keys = new ArrayList<>();
    for (Iterator<String> it = json.keys(); it.hasNext(); ) {
      keys.add(it.next());
    }
    Collections.sort(keys);
    StringBuilder builder = new StringBuilder();
    for (String key : keys) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(key).append('=').append(json.opt(key));
    }
    return builder.toString();
  }

  private static JSONObject json (Object... entries) {
    JSONObject object = new JSONObject();
    try {
      for (int i = 0; i < entries.length; i += 2) {
        object.put((String) entries[i], entries[i + 1]);
      }
    } catch (JSONException e) {
      throw new RuntimeException(e);
    }
    return object;
  }
}

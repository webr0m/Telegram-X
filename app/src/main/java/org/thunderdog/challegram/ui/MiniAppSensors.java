/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Accelerometer, gyroscope and device-orientation streams for Mini Apps. The only failure the WebApp
 * specification knows about is a missing sensor, so anything the page asks for wrongly is either ignored
 * or answered as the success it still is.
 */
public final class MiniAppSensors {
  public static final int ACCELEROMETER = 0;
  public static final int GYROSCOPE = 1;
  public static final int ORIENTATION = 2;

  /** The two orientation sensors a page can be pointed at, plus the case where the device owns neither. */
  public static final int SOURCE_NONE = 0;
  public static final int SOURCE_GAME = 1;
  public static final int SOURCE_MAGNETIC = 2;

  private static final String[] PREFIX = {"accelerometer", "gyroscope", "device_orientation"};
  private static final int MIN_RATE = 20;
  private static final int MAX_RATE = 1000;
  private static final int DEFAULT_RATE = 1000;

  public interface Host {
    /** @return true when the device owns a sensor usable at the requested interval. */
    boolean acquire (int kind, int refreshRateMs);
    void release (int kind);
    void publish (String event, JSONObject data);
    /** Asked only for {@link #ORIENTATION}: whether the device owns the requested orientation sensor. */
    boolean hasOrientationSource (int source);
  }

  private final Host host;
  private final boolean[] started = new boolean[PREFIX.length];
  private final int[] rate = new int[PREFIX.length];
  private final long[] lastSampleAt = new long[PREFIX.length];
  private int orientationSource;
  private boolean active;

  public MiniAppSensors (Host host) {
    if (host == null) throw new IllegalArgumentException("host == null");
    this.host = host;
  }

  public boolean isStarted (int kind) {
    return valid(kind) && started[kind];
  }

  public int refreshRate (int kind) {
    return valid(kind) ? rate[kind] : 0;
  }

  /** Which sensor the running orientation stream is reading from, or {@link #SOURCE_NONE} when stopped. */
  public int orientationSource () {
    return orientationSource;
  }

  /**
   * `need_absolute` asks for angles referenced to magnetic north, which only the fused rotation vector can
   * produce; the game rotation vector is the cheaper and steadier choice when the page does not care.
   */
  public static int resolveOrientationSource (boolean needAbsolute, boolean hasMagnetic, boolean hasGame) {
    if (needAbsolute) {
      return hasMagnetic ? SOURCE_MAGNETIC : hasGame ? SOURCE_GAME : SOURCE_NONE;
    }
    return hasGame ? SOURCE_GAME : hasMagnetic ? SOURCE_MAGNETIC : SOURCE_NONE;
  }

  public void start (int kind, JSONObject request) {
    if (!valid(kind)) return;
    // The official shim drops an out-of-range refresh_rate and sends the request without the field at all,
    // so the same fallback keeps the outgoing events inside the documented vocabulary.
    int refreshRate = request == null ? DEFAULT_RATE : request.optInt("refresh_rate", DEFAULT_RATE);
    if (refreshRate < MIN_RATE || refreshRate > MAX_RATE) refreshRate = DEFAULT_RATE;
    if (started[kind]) {
      // Re-arming a running stream is not a failure: the page gets the interval that is actually running.
      host.publish(PREFIX[kind] + "_started", json("refresh_rate", rate[kind]));
      return;
    }
    if (kind == ORIENTATION) {
      orientationSource = resolveOrientationSource(request != null && request.optBoolean("need_absolute", false),
        host.hasOrientationSource(SOURCE_MAGNETIC), host.hasOrientationSource(SOURCE_GAME));
      if (orientationSource == SOURCE_NONE) {
        host.publish(PREFIX[kind] + "_failed", json("error", "UNSUPPORTED"));
        return;
      }
    }
    if (!active || !host.acquire(kind, refreshRate)) {
      host.publish(PREFIX[kind] + "_failed", json("error", "UNSUPPORTED"));
      return;
    }
    started[kind] = true;
    rate[kind] = refreshRate;
    lastSampleAt[kind] = 0L;
    host.publish(PREFIX[kind] + "_started", json("refresh_rate", refreshRate));
  }

  public void stop (int kind) {
    if (!valid(kind)) return;
    if (started[kind]) {
      started[kind] = false;
      rate[kind] = 0;
      lastSampleAt[kind] = 0L;
      host.release(kind);
      if (kind == ORIENTATION) orientationSource = SOURCE_NONE;
    }
    host.publish(PREFIX[kind] + "_stopped", json());
  }

  /** Linear acceleration or rotation rate, in the m/s² and rad/s units the WebApp specification reports. */
  public void onAxesSample (int kind, long uptimeMs, float x, float y, float z) {
    if (!valid(kind) || !started[kind] || !throttled(kind, uptimeMs)) return;
    host.publish(PREFIX[kind] + "_changed", json("x", x, "y", y, "z", z));
  }

  /** Device orientation in degrees; a sample may only claim magnetic north when that is the sensor in use. */
  public void onOrientationSample (long uptimeMs, int source, float alpha, float beta, float gamma) {
    if (source != orientationSource || !throttled(ORIENTATION, uptimeMs)) return;
    host.publish("device_orientation_changed", json("absolute", source == SOURCE_MAGNETIC, "alpha", alpha, "beta", beta, "gamma", gamma));
  }

  /** Suspends hardware use without forgetting which streams the page asked for. */
  public void setActive (boolean active) {
    if (this.active == active) return;
    this.active = active;
    for (int kind = 0; kind < PREFIX.length; kind++) {
      if (started[kind]) {
        if (active) host.acquire(kind, rate[kind]);
        else host.release(kind);
      }
    }
  }

  /** A new document took over: nothing the previous page asked for may keep running. */
  public void reset () {
    for (int kind = 0; kind < PREFIX.length; kind++) {
      if (started[kind]) {
        started[kind] = false;
        rate[kind] = 0;
        host.release(kind);
      }
    }
    orientationSource = SOURCE_NONE;
  }

  public void destroy () {
    for (int kind = 0; kind < PREFIX.length; kind++) {
      if (started[kind]) {
        started[kind] = false;
        host.release(kind);
      }
    }
    orientationSource = SOURCE_NONE;
    active = false;
  }

  private boolean throttled (int kind, long uptimeMs) {
    if (!started[kind]) return false;
    long previous = lastSampleAt[kind];
    if (previous != 0L && uptimeMs - previous < rate[kind]) return false;
    lastSampleAt[kind] = uptimeMs;
    return true;
  }

  private static boolean valid (int kind) {
    return kind >= 0 && kind < PREFIX.length;
  }

  private static JSONObject json (Object... entries) {
    JSONObject object = new JSONObject();
    try {
      for (int i = 0; i < entries.length; i += 2) {
        object.put((String) entries[i], entries[i + 1]);
      }
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
    return object;
  }
}

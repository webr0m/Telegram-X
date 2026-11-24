/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Clipboard, geolocation, QR scanner and home-screen shortcut requests coming from a Mini App. Every
 * answer to the page is produced here so that the hardware and UI glue in the controller stays thin and
 * the whole decision table is verifiable without an Android framework.
 */
public final class MiniAppDeviceAccess {
  public static final String HOME_UNSUPPORTED = "unsupported";
  public static final String HOME_ADDED = "added";
  public static final String HOME_MISSED = "missed";
  public static final String HOME_UNKNOWN = "unknown";

  public interface Host {
    void publish (String event, JSONObject data);
    /** The page must be on screen, resumed and reacting to a real user interaction. */
    boolean isUserInitiated ();
    /** Reads the primary clip; the answer is delivered through {@link #onClipboardText}. */
    void readClipboard (String requestId);
    /** Starts a single-shot position fix; the answer is delivered through {@link #onLocationFix}. */
    void requestLocationFix (int run);
    void openScanner (int run, String label);
    void closeScanner ();
    void openSystemLocationSettings ();
    /** Requests a launcher shortcut; {@code false} means the launcher cannot pin shortcuts here. */
    boolean requestHomeScreenShortcut ();
    boolean isLocationSupported ();
    boolean isLocationGranted ();
    String homeScreenStatus ();
    int nextRunToken ();
  }

  /** A position fix, holding only the values the current device actually measured. */
  public static final class Fix {
    public final double latitude;
    public final double longitude;
    public final Double altitude;
    public final Double course;
    public final Double speed;
    public final Double horizontalAccuracy;
    public final Double verticalAccuracy;
    public final Double courseAccuracy;
    public final Double speedAccuracy;

    public Fix (double latitude, double longitude, Double altitude, Double course, Double speed,
                Double horizontalAccuracy, Double verticalAccuracy) {
      this(latitude, longitude, altitude, course, speed, horizontalAccuracy, verticalAccuracy, null, null);
    }

    public Fix (double latitude, double longitude, Double altitude, Double course, Double speed,
                Double horizontalAccuracy, Double verticalAccuracy, Double courseAccuracy, Double speedAccuracy) {
      this.latitude = latitude;
      this.longitude = longitude;
      this.altitude = measured(altitude, Double.NaN);
      this.course = measured(course, 360.0);
      this.speed = measured(speed, Double.NaN);
      this.horizontalAccuracy = measured(horizontalAccuracy, Double.NaN);
      this.verticalAccuracy = measured(verticalAccuracy, Double.NaN);
      this.courseAccuracy = measured(courseAccuracy, 360.0);
      this.speedAccuracy = measured(speedAccuracy, Double.NaN);
    }

    /** Android reports unusable optional readings as {@code NaN} or as negative accuracy. */
    private static Double measured (Double value, double upperBound) {
      if (value == null || value.isNaN() || value.isInfinite()) return null;
      if (value < 0.0) return null;
      if (!Double.isNaN(upperBound) && value > upperBound) return null;
      return value;
    }
  }

  private static final int MAX_QR_LABEL = 64;
  private static final int MAX_CLIPBOARD_REQUESTS = 8;
  private static final double MAX_COORDINATE = 180.0;

  private final Host host;
  private int qrRunToken;
  private int locationRunToken;
  private int pendingClipboardRequests;
  private boolean qrPopupOpened;
  private boolean locationAccessRequested;

  public MiniAppDeviceAccess (Host host) {
    if (host == null) throw new IllegalArgumentException("host == null");
    this.host = host;
  }

  // clipboard

  public void readTextFromClipboard (JSONObject request) {
    String requestId = request == null ? "" : request.optString("req_id");
    if (requestId.length() > 64 || pendingClipboardRequests >= MAX_CLIPBOARD_REQUESTS) {
      host.publish("clipboard_text_received", json("req_id", requestId));
      return;
    }
    if (!host.isUserInitiated()) {
      host.publish("clipboard_text_received", json("req_id", requestId));
      return;
    }
    pendingClipboardRequests++;
    host.readClipboard(requestId);
  }

  public void onClipboardText (String requestId, String text) {
    pendingClipboardRequests = Math.max(0, pendingClipboardRequests - 1);
    if (requestId == null || requestId.length() > 64) return;
    JSONObject payload = json("req_id", requestId);
    try {
      if (text != null && text.length() <= 512 * 1024) {
        payload.put("data", text);
      }
    } catch (JSONException ignored) {
      // A clip too large for JSON is reported as no data, which is what the page sees on an empty clipboard.
    }
    host.publish("clipboard_text_received", payload);
  }

  // location

  public void checkLocation () {
    boolean granted = host.isLocationGranted();
    host.publish("location_checked", json(
      "available", host.isLocationSupported(),
      "access_requested", locationAccessRequested || granted,
      "access_granted", granted));
  }

  public void requestLocation (JSONObject request) {
    if (!host.isLocationSupported() || !host.isUserInitiated()) {
      host.publish("location_requested", json("available", false));
      return;
    }
    int run = host.nextRunToken();
    locationRunToken = run;
    locationAccessRequested = true;
    host.requestLocationFix(run);
  }

  public void onLocationFix (int run, Fix fix) {
    if (run != locationRunToken) return;
    if (fix == null || !host.isLocationGranted()) {
      host.publish("location_requested", json("available", false));
      return;
    }
    boolean validCoordinates = Double.isFinite(fix.latitude) && Double.isFinite(fix.longitude) &&
      Math.abs(fix.latitude) <= 90.0 && Math.abs(fix.longitude) <= MAX_COORDINATE;
    if (!validCoordinates) {
      host.publish("location_requested", json("available", false));
      return;
    }
    host.publish("location_requested", json("available", true,
      "latitude", fix.latitude, "longitude", fix.longitude,
      "altitude", fix.altitude, "course", fix.course, "speed", fix.speed,
      "horizontal_accuracy", fix.horizontalAccuracy, "vertical_accuracy", fix.verticalAccuracy,
      "course_accuracy", fix.courseAccuracy, "speed_accuracy", fix.speedAccuracy));
  }

  public void openLocationSettings () {
    host.openSystemLocationSettings();
  }

  // qr scanner

  public void openScanQrPopup (JSONObject request) {
    if (qrPopupOpened) {
      // The SDK refuses a second open call, so reaching this line means the page is out of sync: keep the
      // scanner that is already running rather than answering the new request twice.
      return;
    }
    if (!host.isUserInitiated()) {
      host.publish("scan_qr_popup_closed", json());
      return;
    }
    String label = singleLine(request == null ? "" : request.optString("text", ""));
    if (label.length() > MAX_QR_LABEL) label = label.substring(0, MAX_QR_LABEL);
    qrPopupOpened = true;
    qrRunToken = host.nextRunToken();
    host.openScanner(qrRunToken, label);
  }

  private boolean isScanQrPopupOpened (int run) {
    return qrPopupOpened && run == qrRunToken;
  }

  public void onScanResult (int run, String data) {
    if (!isScanQrPopupOpened(run) || data == null) return;
    // The native scanner stops on its own once it has read something, so the page gets the text and the
    // closing event in the order a client that keeps scanning would not produce.
    qrPopupOpened = false;
    qrRunToken = 0;
    host.publish("qr_text_received", json("data", data));
    host.publish("scan_qr_popup_closed", json());
  }

  public void closeScanQrPopup () {
    if (!qrPopupOpened) return;
    qrPopupOpened = false;
    qrRunToken = 0;
    host.closeScanner();
    host.publish("scan_qr_popup_closed", json());
  }

  /** The scanner went away on its own (back press, camera error); the page still has to be told. */
  public void onScannerClosedExternally () {
    closeScanQrPopup();
  }

  // home screen

  public void checkHomeScreen () {
    String status = host.homeScreenStatus();
    if (!HOME_ADDED.equals(status) && !HOME_MISSED.equals(status) && !HOME_UNSUPPORTED.equals(status) &&
        !HOME_UNKNOWN.equals(status)) {
      // A launcher answer this class does not know about is reported as unknown rather than unsupported: the
      // first lets the page ask again and be corrected by addToHomeScreen, the second hides the feature for good.
      status = HOME_UNKNOWN;
    }
    host.publish("home_screen_checked", json("status", status));
  }

  public void addToHomeScreen () {
    if (!host.isUserInitiated() || !host.requestHomeScreenShortcut()) {
      host.publish("home_screen_checked", json("status", HOME_UNSUPPORTED));
      return;
    }
    host.publish("home_screen_added", json());
  }

  /** Account or document changed: nothing that is already on its way may reach the old page. */
  public void invalidate () {
    qrPopupOpened = false;
    qrRunToken = 0;
    locationRunToken = 0;
    pendingClipboardRequests = 0;
  }

  /** The label is painted onto the camera overlay, where a blank run or a newline would break its two lines. */
  private static String singleLine (String value) {
    StringBuilder builder = new StringBuilder(value.length());
    boolean pendingSpace = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0b) {
        pendingSpace = builder.length() > 0;
      } else {
        if (pendingSpace) {
          builder.append(' ');
          pendingSpace = false;
        }
        builder.append(c);
      }
    }
    return builder.toString();
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

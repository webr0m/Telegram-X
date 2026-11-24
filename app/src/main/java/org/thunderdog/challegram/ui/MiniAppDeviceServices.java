/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;

import org.json.JSONObject;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.MainActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.helper.LocationHelper;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.ui.camera.CameraController;

import java.util.ArrayList;
import java.util.List;

/**
 * Android side of the Mini App device APIs. It owns the hardware, the system dialogs and the launcher; every
 * decision about what the page is told lives in {@link MiniAppDeviceAccess} and {@link MiniAppSensors}.
 */
final class MiniAppDeviceServices implements MiniAppDeviceAccess.Host, MiniAppMediaAccess.Host, MiniAppSensors.Host,
  SensorEventListener, CameraController.QrCodeListener {

  interface Gateway {
    void publish (String event, JSONObject data);
    /** The page is on screen, resumed and the request follows a real touch on it. */
    boolean isReadyForDeviceAccess ();
    int nextRunToken ();
    /** A public {@code https://t.me} link reopening this Mini App, or null when it cannot be pinned. */
    @Nullable String homeScreenLink ();
    CharSequence homeScreenTitle ();
    String homeScreenId ();
  }

  private static final int LOCATION_TIMEOUT_MS = 10000;
  private static final int MAX_CLIPBOARD_CHARS = 512 * 1024;

  private final BaseActivity context;
  private final Gateway gateway;
  private final MiniAppDeviceAccess access = new MiniAppDeviceAccess(this);
  private final MiniAppMediaAccess media = new MiniAppMediaAccess(this);
  private final MiniAppSensors sensors = new MiniAppSensors(this);

  private final @Nullable SensorManager sensorManager;
  private final @Nullable Sensor[] sensorsFound = new Sensor[3];
  private final @Nullable Sensor[] orientationSensors = new Sensor[3];
  private final @Nullable Sensor[] registeredSensors = new Sensor[3];
  private final boolean[] sensorsRegistered = new boolean[3];
  private final float[] rotationMatrix = new float[9];
  private final float[] orientationAngles = new float[3];

  private int scannerRun;
  private boolean scannerVisible;
  private int locationRun;

  MiniAppDeviceServices (BaseActivity context, Gateway gateway) {
    if (context == null || gateway == null) throw new IllegalArgumentException("context == null || gateway == null");
    this.context = context;
    this.gateway = gateway;
    this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
  }

  // requests coming from the page

  void readTextFromClipboard (@Nullable JSONObject data) {
    access.readTextFromClipboard(data);
  }

  void checkLocation () {
    access.checkLocation();
  }

  void requestLocation (@Nullable JSONObject data) {
    access.requestLocation(data);
  }

  void openLocationSettings () {
    access.openLocationSettings();
  }

  void openScanQrPopup (@Nullable JSONObject data) {
    access.openScanQrPopup(data);
  }

  void closeScanQrPopup () {
    access.closeScanQrPopup();
  }

  void checkHomeScreen () {
    access.checkHomeScreen();
  }

  void addToHomeScreen () {
    access.addToHomeScreen();
  }

  void mediaDocumentStarted () {
    media.documentStarted();
  }

  void checkMediaAccess (@Nullable JSONObject data) {
    media.check(data);
  }

  void requestMediaAccess (@Nullable JSONObject data) {
    media.request(data);
  }

  /** Called whenever the page becomes visible again: a grant made in system settings has to reach it. */
  void onMediaStateChanged () {
    media.onStateChanged();
  }

  MiniAppMediaAccess.Grant onWebViewPermissionRequest (String[] resources) {
    return media.onWebViewRequest(resources);
  }

  void startSensor (int kind, @Nullable JSONObject data) {
    sensors.start(kind, data);
  }

  void stopSensor (int kind) {
    sensors.stop(kind);
  }

  /** Called whenever the page leaves the screen: streams stop feeding it and the scanner must go away. */
  void setActive (boolean active) {
    sensors.setActive(active);
    if (!active && scannerVisible) {
      scannerVisible = false;
      context.forceCloseCamera();
      access.onScannerClosedExternally();
    }
  }

  /** The current document is gone: results already on their way may not be delivered to it. */
  void invalidate () {
    access.invalidate();
    media.invalidate();
    sensors.reset();
    scannerRun = 0;
    locationRun = 0;
  }

  void destroy () {
    sensors.destroy();
    if (scannerVisible) {
      scannerVisible = false;
      context.forceCloseCamera();
    }
  }

  // MiniAppDeviceAccess.Host

  @Override
  public void publish (String event, JSONObject data) {
    gateway.publish(event, data);
  }

  @Override
  public boolean isUserInitiated () {
    return gateway.isReadyForDeviceAccess();
  }

  @Override
  public void readClipboard (String requestId) {
    String text = null;
    try {
      ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
      ClipData clip = clipboard != null ? clipboard.getPrimaryClip() : null;
      if (clip != null && clip.getItemCount() > 0) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < clip.getItemCount(); i++) {
          CharSequence value = clip.getItemAt(i).coerceToText(context);
          if (TextUtils.isEmpty(value)) continue;
          if (result.length() > 0) result.append('\n');
          result.append(value);
          if (result.length() > MAX_CLIPBOARD_CHARS) break;
        }
        if (result.length() > 0 && result.length() <= MAX_CLIPBOARD_CHARS) {
          text = result.toString();
        }
      }
    } catch (RuntimeException e) {
      text = null;
    }
    access.onClipboardText(requestId, text);
  }

  @Override
  public void requestLocationFix (int run) {
    locationRun = run;
    LocationHelper.requestLocation(context, LOCATION_TIMEOUT_MS, true, false, (errorCode, location) -> {
      if (run != locationRun) return;
      locationRun = 0;
      access.onLocationFix(run, errorCode == LocationHelper.ERROR_CODE_NONE ? toFix(location) : null);
    });
  }

  @Override
  public void openScanner (int run, String label) {
    // The camera asks for its own permissions through the single callback slot the activity has, which would
    // swallow a media prompt still open. Its kinds stay denied, so the page is left with an answer it can re-ask.
    media.invalidate();
    scannerRun = run;
    scannerVisible = true;
    context.openCameraByTap(new ViewController.CameraOpenOptions().ignoreAnchor(true).noTrace(true).allowSystem(false)
      .optionalMicrophone(true).mode(CameraController.MODE_QR).qrAcceptAnyPayload(true)
      .qrModeSubtitle(R.string.MiniAppScanQrSubtitle).qrModeSubtitleText(label == null || label.isEmpty() ? null : label)
      .qrCodeListener(this));
  }

  @Override
  public void closeScanner () {
    if (scannerVisible) {
      scannerVisible = false;
      context.forceCloseCamera();
    }
  }

  @Override
  public void openSystemLocationSettings () {
    Intents.openPermissionSettings();
  }

  @Override
  public boolean requestHomeScreenShortcut () {
    String link = gateway.homeScreenLink();
    if (link == null || !ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false;
    ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(context, gateway.homeScreenId())
      .setShortLabel(gateway.homeScreenTitle())
      .setIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(link))
        .setClass(context, MainActivity.class)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP))
      .build();
    return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null);
  }

  @Override
  public boolean isLocationSupported () {
    return context.permissions().canAccessLocation() || hasEnabledProvider();
  }

  @Override
  public boolean isLocationGranted () {
    return context.permissions().canAccessLocation();
  }

  @Override
  public String homeScreenStatus () {
    if (gateway.homeScreenLink() == null) return MiniAppDeviceAccess.HOME_UNSUPPORTED;
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return MiniAppDeviceAccess.HOME_UNSUPPORTED;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
      ShortcutManager manager = (ShortcutManager) context.getSystemService(Context.SHORTCUT_SERVICE);
      String id = gateway.homeScreenId();
      try {
        List<ShortcutInfo> pinned = manager != null ? manager.getPinnedShortcuts() : null;
        if (pinned != null) {
          boolean added = false;
          for (int i = 0; i < pinned.size(); i++) {
            if (id.equals(pinned.get(i).getId())) {
              added = true;
              break;
            }
          }
          return added ? MiniAppDeviceAccess.HOME_ADDED : MiniAppDeviceAccess.HOME_MISSED;
        }
      } catch (RuntimeException e) {
        // The launcher accepted the pin request but its own list cannot be read: the docs reserve unknown
        // exactly for that, and a page told unsupported would stop offering the button for good.
        return MiniAppDeviceAccess.HOME_UNKNOWN;
      }
    }
    return MiniAppDeviceAccess.HOME_UNKNOWN;
  }

  @Override
  public int nextRunToken () {
    return gateway.nextRunToken();
  }

  // MiniAppMediaAccess.Host

  @Override
  public boolean hasHardware (int kind) {
    PackageManager manager = context.getPackageManager();
    return manager != null && manager.hasSystemFeature(kind == MiniAppMediaAccess.CAMERA ?
      PackageManager.FEATURE_CAMERA_ANY : PackageManager.FEATURE_MICROPHONE);
  }

  @Override
  public boolean isGranted (int kind) {
    return context.permissions().checkPermission(kind == MiniAppMediaAccess.CAMERA ?
      Manifest.permission.CAMERA : Manifest.permission.RECORD_AUDIO);
  }

  @Override
  public boolean promptPermissions (boolean camera, boolean microphone) {
    // Below M there is no runtime dialog at all, so the state the page is told about is already final
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
    List<String> missing = new ArrayList<>(2);
    if (camera && !isGranted(MiniAppMediaAccess.CAMERA)) {
      missing.add(Manifest.permission.CAMERA);
    }
    if (microphone && !isGranted(MiniAppMediaAccess.MICROPHONE)) {
      missing.add(Manifest.permission.RECORD_AUDIO);
    }
    if (missing.isEmpty()) return false;
    context.requestCustomPermissions(missing.toArray(new String[0]), (code, permissions, results, count) ->
      media.onPromptFinished());
    return true;
  }

  // MiniAppSensors.Host

  @Override
  public boolean acquire (int kind, int refreshRateMs) {
    Sensor sensor = sensorFor(kind);
    if (sensorManager == null || sensor == null) return false;
    boolean registered = sensorManager.registerListener(this, sensor, refreshRateMs * 1000);
    sensorsRegistered[kind] = registered;
    registeredSensors[kind] = registered ? sensor : null;
    return registered;
  }

  @Override
  public void release (int kind) {
    if (kind >= 0 && kind < registeredSensors.length) {
      Sensor registered = registeredSensors[kind];
      if (sensorManager != null && registered != null) {
        sensorManager.unregisterListener(this, registered);
      }
      registeredSensors[kind] = null;
      sensorsRegistered[kind] = false;
    }
  }

  @Override
  public void onSensorChanged (SensorEvent event) {
    if (event == null || event.sensor == null || event.values == null || event.values.length < 3) return;
    long uptime = SystemClock.elapsedRealtime();
    switch (event.sensor.getType()) {
      case Sensor.TYPE_ACCELEROMETER:
        sensors.onAxesSample(MiniAppSensors.ACCELEROMETER, uptime, event.values[0], event.values[1], event.values[2]);
        break;
      case Sensor.TYPE_GYROSCOPE:
        sensors.onAxesSample(MiniAppSensors.GYROSCOPE, uptime, event.values[0], event.values[1], event.values[2]);
        break;
      case Sensor.TYPE_ROTATION_VECTOR:
      case Sensor.TYPE_GAME_ROTATION_VECTOR:
        int source = event.sensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR ?
          MiniAppSensors.SOURCE_GAME : MiniAppSensors.SOURCE_MAGNETIC;
        if (SensorManager.getRotationMatrix(rotationMatrix, null, event.values, null) &&
            SensorManager.getOrientation(rotationMatrix, orientationAngles) != null) {
          sensors.onOrientationSample(uptime, source,
            degrees(orientationAngles[0]), degrees(orientationAngles[1]), degrees(orientationAngles[2]));
        }
        break;
    }
  }

  @Override
  public void onAccuracyChanged (Sensor sensor, int accuracy) { }

  // CameraController.QrCodeListener

  @Override
  public void onQrCodeScanned (String qrCode) {
    scannerVisible = false;
    int run = scannerRun;
    scannerRun = 0;
    access.onScanResult(run, qrCode);
  }

  @Override
  public void onQrScanCancelled () {
    if (scannerVisible) {
      scannerVisible = false;
      access.onScannerClosedExternally();
    }
  }

  // internals

  private @Nullable Sensor sensorFor (int kind) {
    if (kind == MiniAppSensors.ORIENTATION) return orientationSensor(sensors.orientationSource());
    if (kind < 0 || kind >= sensorsFound.length || sensorManager == null) return null;
    if (sensorsFound[kind] == null) {
      int type = kind == MiniAppSensors.GYROSCOPE ? Sensor.TYPE_GYROSCOPE : Sensor.TYPE_ACCELEROMETER;
      sensorsFound[kind] = sensorManager.getDefaultSensor(type);
    }
    return sensorsFound[kind];
  }

  private @Nullable Sensor orientationSensor (int source) {
    if (source == MiniAppSensors.SOURCE_NONE || sensorManager == null) return null;
    if (source < 0 || source >= orientationSensors.length) return null;
    if (orientationSensors[source] == null) {
      orientationSensors[source] = sensorManager.getDefaultSensor(source == MiniAppSensors.SOURCE_MAGNETIC ?
        Sensor.TYPE_ROTATION_VECTOR : Sensor.TYPE_GAME_ROTATION_VECTOR);
    }
    return orientationSensors[source];
  }

  @Override
  public boolean hasOrientationSource (int source) {
    return orientationSensor(source) != null;
  }

  private boolean hasEnabledProvider () {
    if (!(context.getSystemService(Context.LOCATION_SERVICE) instanceof LocationManager manager)) return false;
    try {
      List<String> providers = manager.getProviders(true);
      return providers != null && !providers.isEmpty();
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static @Nullable MiniAppDeviceAccess.Fix toFix (@Nullable Location location) {
    if (location == null) return null;
    return new MiniAppDeviceAccess.Fix(location.getLatitude(), location.getLongitude(),
      Double.valueOf(location.getAltitude()), Double.valueOf(location.getBearing()), Double.valueOf(location.getSpeed()),
      Double.valueOf(location.getAccuracy()), null);
  }

  private static float degrees (float radians) {
    if (Float.isNaN(radians)) return 0f;
    float value = (float) Math.toDegrees(radians);
    return value < 0f ? value + 360f : value;
  }
}

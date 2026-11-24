/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.biometric.BiometricPrompt;
import androidx.core.os.CancellationSignal;

import org.json.JSONObject;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.BiometricAuthentication;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.unsorted.AppContext;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Android side of {@code SecureStorage} and {@code BiometricManager}.
 *
 * <p>Every bot gets its own AES key that never leaves the preferences in clear: it is sealed by a non-exportable
 * Android Keystore key, and a second Keystore key that can only be used right after a strong biometric
 * authentication seals the same value again. Reading a protected entry therefore always goes through a real
 * prompt, while saving one stays instant.</p>
 */
final class MiniAppSecureServices implements MiniAppBiometry.Host, MiniAppSecureStorage.Host {

  interface Gateway {
    void publish (String event, JSONObject data);
    /** The page is on screen, resumed, and the request follows an actual touch on it. */
    boolean isReadyForDeviceAccess ();
    int nextRunToken ();
    /** Shown through the controller so that closing the page also closes the dialog. */
    AlertDialog showSecureDialog (AlertDialog.Builder builder);
  }

  private static final String FILE_PREFIX = "miniapp_secure_";
  private static final String SUFFIX_VALUES = "_values";
  private static final String SUFFIX_TOKEN = "_token";
  private static final String SUFFIX_CONSENT = "_consent";
  private static final String SUFFIX_SEALED = "_sealed";
  private static final String SUFFIX_SEALED_AUTH = "_sealed_auth";
  private static final String KEY_DEVICE_ID = "device_id";

  private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
  private static final String ALIAS_PREFIX = "tgx_miniapp_";
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";

  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final int DATA_KEY_BYTES = 32;

  private static final int NO_PROMPT = 0;
  private static final int PROMPT_RESTORE = 1;
  private static final int PROMPT_AUTHENTICATE = 2;

  private final BaseActivity context;
  private final Gateway gateway;
  private final SharedPreferences storage;
  private final String valuesKey;
  private final String tokenKey;
  private final String consentKey;
  private final String sealedKey;
  private final String sealedAuthKey;
  private final String plainAlias;
  private final String authAlias;

  private final MiniAppBiometry biometry = new MiniAppBiometry(this);
  private final MiniAppSecureStorage secure = new MiniAppSecureStorage(this);
  private final SecureRandom random = new SecureRandom();

  private @Nullable AlertDialog consentDialog;
  private @Nullable CancellationSignal promptSignal;
  private int promptRun;
  private int promptKind;
  private @Nullable String promptValueKey;
  private boolean destroyed;

  MiniAppSecureServices (BaseActivity context, Gateway gateway, long accountUserId, long botUserId) {
    if (context == null || gateway == null || botUserId == 0) throw new IllegalArgumentException();
    this.context = context;
    this.gateway = gateway;
    this.storage = context.getSharedPreferences(FILE_PREFIX + accountUserId, Context.MODE_PRIVATE);
    String bot = String.valueOf(botUserId);
    this.valuesKey = bot + SUFFIX_VALUES;
    this.tokenKey = bot + SUFFIX_TOKEN;
    this.consentKey = bot + SUFFIX_CONSENT;
    this.sealedKey = bot + SUFFIX_SEALED;
    this.sealedAuthKey = bot + SUFFIX_SEALED_AUTH;
    this.plainAlias = ALIAS_PREFIX + accountUserId + "_" + bot;
    this.authAlias = plainAlias + "_auth";
  }

  // requests coming from the page

  void getBiometryInfo () {
    biometry.getInfo();
  }

  void requestBiometryAccess (@Nullable JSONObject data) {
    biometry.requestAccess(data);
  }

  void requestBiometryAuth (@Nullable JSONObject data) {
    biometry.requestAuth(data);
  }

  void updateBiometryToken (@Nullable JSONObject data) {
    biometry.updateToken(data);
  }

  void openBiometrySettings () {
    biometry.openSettings();
  }

  void saveSecureKey (@Nullable JSONObject data) {
    secure.saveKey(data);
  }

  void getSecureKey (@Nullable JSONObject data) {
    secure.getKey(data);
  }

  void restoreSecureKey (@Nullable JSONObject data) {
    secure.restoreKey(data);
  }

  void clearSecureStorage (@Nullable JSONObject data) {
    secure.clear(data);
  }

  /** The document is gone or reloaded: nothing that is on its way may answer the old page. */
  void invalidate () {
    secure.invalidate();
    biometry.invalidate();
    dismissConsentDialog();
    cancelPrompt();
  }

  void destroy () {
    destroyed = true;
    invalidate();
  }

  /** Signing out drops every sealed value, consent and token this account had. */
  static void wipeAccount (long accountUserId) {
    Context base = AppContext.get();
    if (base == null) return;
    base.getSharedPreferences(FILE_PREFIX + accountUserId, Context.MODE_PRIVATE).edit().clear().apply();
    deleteKeystoreEntries(ALIAS_PREFIX + accountUserId + "_");
  }

  // MiniAppBiometry.Host

  @Override
  public void publish (String event, JSONObject data) {
    gateway.publish(event, data);
  }

  @Override
  public int nextRunToken () {
    return gateway.nextRunToken();
  }

  @Override
  public boolean isAvailable () {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && BiometricAuthentication.isStrongAvailable(true);
  }

  @Override
  public String biometricType () {
    if (context.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT)) {
      return "finger";
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        context.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_FACE)) {
      return "face";
    }
    return "unknown";
  }

  @Override
  public String deviceId () {
    String id = storage.getString(KEY_DEVICE_ID, "");
    if (id == null || id.length() != 36) {
      id = java.util.UUID.randomUUID().toString();
      storage.edit().putString(KEY_DEVICE_ID, id).apply();
    }
    return id;
  }

  @Override
  public boolean isAccessRequested () {
    return storage.getString(consentKey, null) != null;
  }

  @Override
  public boolean isAccessGranted () {
    return "1".equals(storage.getString(consentKey, null));
  }

  @Override
  public boolean isTokenSaved () {
    return !TextUtils.isEmpty(storage.getString(tokenKey, ""));
  }

  @Override
  public void promptAccess (int run, String reason) {
    if (destroyed || !gateway.isReadyForDeviceAccess()) {
      biometry.onAccessResult(run);
      return;
    }
    consentDialog = gateway.showSecureDialog(new AlertDialog.Builder(context, Theme.dialogTheme())
      .setTitle(Lang.getString(R.string.MiniAppBiometryAccess))
      .setMessage(TextUtils.isEmpty(reason) ? Lang.getString(R.string.MiniAppBiometryAccessConfirm) : reason)
      .setPositiveButton(Lang.getString(R.string.Allow), (dialog, which) -> {
        setConsent(true);
        biometry.onAccessResult(run);
      })
      .setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> {
        setConsent(false);
        biometry.onAccessResult(run);
      })
      .setOnCancelListener(dialog -> {
        setConsent(false);
        biometry.onAccessResult(run);
      }));
  }

  @Override
  public void promptAuth (int run, String reason) {
    if (!gateway.isReadyForDeviceAccess()) {
      // Without a touch on the page nothing may be shown at all, which the page reads as a cancellation.
      biometry.onAuthResult(run, false, true, null);
      return;
    }
    Cipher cipher = promptRun == 0 ? authCipher() : null;
    if (cipher == null) {
      biometry.onAuthResult(run, false, false, null);
      return;
    }
    promptRun = run;
    promptKind = PROMPT_AUTHENTICATE;
    promptValueKey = null;
    if (!runPrompt(cipher, reason)) {
      clearPrompt();
      biometry.onAuthResult(run, false, false, null);
    }
  }

  @Override
  public void storeToken (int run, String token) {
    if (token.isEmpty()) {
      boolean removed = storage.edit().remove(tokenKey).commit();
      biometry.onTokenResult(run, false, !removed);
      return;
    }
    if (!canUseBiometrics()) {
      // A token that no authentication could ever release must not be kept.
      biometry.onTokenResult(run, false, true);
      return;
    }
    SecretKey dataKey = dataKey(true);
    byte[] sealed = dataKey == null ? null : encrypt(dataKey, token.getBytes(StandardCharsets.UTF_8));
    if (sealed == null) {
      biometry.onTokenResult(run, false, true);
      return;
    }
    boolean saved = storage.edit().putString(tokenKey, encode(sealed)).commit();
    biometry.onTokenResult(run, saved, !saved);
  }

  @Override
  public void openSettings () {
    try {
      Intent intent = new Intent(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ?
        Settings.ACTION_BIOMETRIC_ENROLL : Settings.ACTION_SECURITY_SETTINGS);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      context.startActivity(intent);
    } catch (RuntimeException e) {
      // No system screen to show: the page already received its answer.
    }
  }

  // MiniAppSecureStorage.Host

  @Override
  public MiniAppSecureStorage.Value read (String key) {
    MiniAppSecureEntries.Stored stored = MiniAppSecureEntries.inspect(storedValues(), key);
    if (stored.status == MiniAppSecureStorage.Value.MISSING) return MiniAppSecureStorage.Value.missing();
    if (stored.status == MiniAppSecureStorage.Value.NEEDS_BIOMETRIC) {
      // A protected entry is only ever released through an authenticated restore.
      return MiniAppSecureStorage.Value.restricted();
    }
    byte[] blob = stored.blob == null ? null : decode(stored.blob);
    if (stored.status == MiniAppSecureStorage.Value.FAILED || blob == null) {
      return MiniAppSecureStorage.Value.failed();
    }
    SecretKey dataKey = dataKey(false);
    if (dataKey == null) return MiniAppSecureStorage.Value.failed();
    String text = decryptText(dataKey, blob);
    return text == null ? MiniAppSecureStorage.Value.failed() : MiniAppSecureStorage.Value.of(text);
  }

  @Override
  public int write (String key, @Nullable String value, boolean bindToBiometric) {
    String blob = null;
    if (value != null) {
      SecretKey dataKey = dataKey(true);
      byte[] sealed = dataKey == null ? null : encrypt(dataKey, value.getBytes(StandardCharsets.UTF_8));
      if (sealed == null) return MiniAppSecureStorage.Host.WRITE_FAILED;
      blob = encode(sealed);
    }
    MiniAppSecureEntries.Result result = MiniAppSecureEntries.write(storedValues(), key, value, blob,
      value != null && bindToBiometric && canUseBiometrics());
    if (result.status == MiniAppSecureEntries.FULL) return MiniAppSecureStorage.Host.WRITE_FULL;
    if (result.status == MiniAppSecureEntries.CORRUPT) return MiniAppSecureStorage.Host.WRITE_FAILED;
    SharedPreferences.Editor editor = storage.edit();
    if (result.document == null) editor.remove(valuesKey);
    else editor.putString(valuesKey, result.document);
    return editor.commit() ? MiniAppSecureStorage.Host.WRITE_OK : MiniAppSecureStorage.Host.WRITE_FAILED;
  }

  @Override
  public boolean eraseAll () {
    return storage.edit()
      .remove(valuesKey).remove(tokenKey).remove(sealedKey).remove(sealedAuthKey).commit();
  }

  @Override
  public boolean canUseBiometrics () {
    return isAvailable() && isAccessGranted() && keystoreKey(authAlias, true, true) != null;
  }

  @Override
  public boolean isBiometryConsented () {
    return isAccessGranted();
  }

  @Override
  public void authenticate (int run, String key) {
    MiniAppSecureEntries.Stored stored = gateway.isReadyForDeviceAccess() && promptRun == 0 ?
      MiniAppSecureEntries.inspect(storedValues(), key) : null;
    Cipher cipher = stored != null && stored.status == MiniAppSecureStorage.Value.NEEDS_BIOMETRIC ? authCipher() : null;
    if (cipher == null) {
      secure.onRestoreResult(run, false, null);
      return;
    }
    promptRun = run;
    promptKind = PROMPT_RESTORE;
    promptValueKey = key;
    if (!runPrompt(cipher, null)) {
      clearPrompt();
      secure.onRestoreResult(run, false, null);
    }
  }

  // prompts

  /** @param reason what the page asked the user to confirm; an empty one falls back to the generic title. */
  private boolean runPrompt (@Nullable Cipher cipher, @Nullable String reason) {
    if (cipher == null) return false;
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || destroyed || context.isFinishing()) return false;
    CharSequence title = TextUtils.isEmpty(reason) ? Lang.getString(R.string.ConfirmYourBiometrics) : reason;
    try {
      promptSignal = BiometricAuthentication.authenticate(context,
        title, Lang.getString(R.string.CancelBiometrics), true,
        new BiometricPrompt.CryptoObject(cipher), new BiometricAuthentication.Callback() {
          @Override
          public void onAuthenticated (BiometricPrompt.AuthenticationResult result, boolean strong) {
            onPromptSucceeded(result);
          }

          @Override
          public void onAuthenticationError (CharSequence message, boolean isFatal) {
            onPromptFailed(false);
          }

          @Override
          public void onAuthenticationError (CharSequence message, boolean isFatal, int errorCode) {
            onPromptFailed(errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
              errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON);
          }
        });
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private void onPromptSucceeded (@Nullable BiometricPrompt.AuthenticationResult result) {
    int run = promptRun;
    int kind = promptKind;
    String key = promptValueKey;
    clearPrompt();
    BiometricPrompt.CryptoObject crypto = result == null ? null : result.getCryptoObject();
    Cipher cipher = crypto == null ? null : crypto.getCipher();
    if (kind == PROMPT_RESTORE) {
      SecretKey dataKey = cipher == null ? null : unwrapAuthenticated(cipher);
      secure.onRestoreResult(run, dataKey != null, dataKey == null ? null : decryptEntry(dataKey, key));
    } else if (kind == PROMPT_AUTHENTICATE) {
      byte[] blob = decode(storage.getString(tokenKey, ""));
      if (blob == null) {
        // Reaching this line means the platform verified the user; there is simply no token to hand out.
        biometry.onAuthResult(run, cipher != null, false, "");
      } else {
        SecretKey dataKey = cipher == null ? null : unwrapAuthenticated(cipher);
        String token = dataKey == null ? null : decryptText(dataKey, blob);
        biometry.onAuthResult(run, token != null, false, token == null ? "" : token);
      }
    }
  }

  private void onPromptFailed (boolean cancelled) {
    int run = promptRun;
    int kind = promptKind;
    clearPrompt();
    if (kind == PROMPT_RESTORE) {
      secure.onRestoreResult(run, false, null);
    } else if (kind == PROMPT_AUTHENTICATE) {
      biometry.onAuthResult(run, false, cancelled, null);
    }
  }

  private @Nullable String decryptEntry (SecretKey dataKey, @Nullable String key) {
    if (key == null) return null;
    String blob = MiniAppSecureEntries.inspect(storedValues(), key).blob;
    byte[] sealed = blob == null ? null : decode(blob);
    return sealed == null ? null : decryptText(dataKey, sealed);
  }

  private void clearPrompt () {
    promptRun = 0;
    promptKind = NO_PROMPT;
    promptValueKey = null;
  }

  private void cancelPrompt () {
    CancellationSignal signal = promptSignal;
    promptSignal = null;
    clearPrompt();
    if (signal != null) {
      try {
        signal.cancel();
      } catch (IllegalStateException ignored) {
        // The prompt had already finished.
      }
    }
  }

  private void dismissConsentDialog () {
    AlertDialog dialog = consentDialog;
    consentDialog = null;
    if (dialog != null) {
      try {
        dialog.dismiss();
      } catch (IllegalArgumentException ignored) {
        // The window was already torn down with the activity.
      }
    }
  }

  private void setConsent (boolean granted) {
    storage.edit().putString(consentKey, granted ? "1" : "0").commit();
  }

  // sealed data key

  private @Nullable SecretKey dataKey (boolean create) {
    byte[] sealed = decode(storage.getString(sealedKey, ""));
    if (sealed == null || sealed.length <= IV_BYTES) {
      return create ? generateDataKey() : null;
    }
    Cipher cipher = cipher(plainAlias, false, Cipher.DECRYPT_MODE, Arrays.copyOfRange(sealed, 0, IV_BYTES));
    byte[] raw = cipher == null ? null : doFinal(cipher, Arrays.copyOfRange(sealed, IV_BYTES, sealed.length));
    return raw == null ? null : new SecretKeySpec(raw, "AES");
  }

  private @Nullable SecretKey generateDataKey () {
    byte[] raw = new byte[DATA_KEY_BYTES];
    random.nextBytes(raw);
    byte[] sealed = seal(plainAlias, false, raw);
    if (sealed == null) return null;
    SharedPreferences.Editor editor = storage.edit().putString(sealedKey, encode(sealed));
    byte[] authSealed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? seal(authAlias, true, raw) : null;
    if (authSealed != null) editor.putString(sealedAuthKey, encode(authSealed));
    else editor.remove(sealedAuthKey);
    return editor.commit() ? new SecretKeySpec(raw, "AES") : null;
  }

  /**
   * A cipher ready for the authenticated use of the biometric key. Without a sealed copy there is nothing to
   * unwrap, and encrypting with the same key is the cheapest way to prove a real authentication happened.
   */
  private @Nullable Cipher authCipher () {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null;
    byte[] sealed = decode(storage.getString(sealedAuthKey, ""));
    if (sealed != null && sealed.length > IV_BYTES) {
      return cipher(authAlias, true, Cipher.DECRYPT_MODE, Arrays.copyOfRange(sealed, 0, IV_BYTES));
    }
    return cipher(authAlias, true, Cipher.ENCRYPT_MODE, null);
  }

  private @Nullable SecretKey unwrapAuthenticated (Cipher authenticated) {
    byte[] sealed = decode(storage.getString(sealedAuthKey, ""));
    if (sealed == null || sealed.length <= IV_BYTES) return null;
    byte[] raw = doFinal(authenticated, Arrays.copyOfRange(sealed, IV_BYTES, sealed.length));
    return raw == null ? null : new SecretKeySpec(raw, "AES");
  }

  private @Nullable byte[] seal (String alias, boolean bound, byte[] data) {
    Cipher cipher = cipher(alias, bound, Cipher.ENCRYPT_MODE, null);
    if (cipher == null) return null;
    byte[] body = doFinal(cipher, data);
    byte[] iv = cipher.getIV();
    return body == null || iv == null ? null : combined(iv, body);
  }

  private @Nullable Cipher cipher (String alias, boolean bound, int mode, @Nullable byte[] iv) {
    SecretKey key = keystoreKey(alias, mode == Cipher.ENCRYPT_MODE, bound);
    if (key == null) return null;
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      if (mode == Cipher.ENCRYPT_MODE) {
        cipher.init(mode, key);
      } else {
        if (iv == null || iv.length != IV_BYTES) return null;
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
      }
      return cipher;
    } catch (Exception e) {
      return null;
    }
  }

  private static @Nullable byte[] doFinal (Cipher cipher, byte[] data) {
    try {
      return cipher.doFinal(data);
    } catch (Exception e) {
      return null;
    }
  }

  private @Nullable byte[] encrypt (SecretKey key, byte[] data) {
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      return combined(iv, cipher.doFinal(data));
    } catch (Exception e) {
      return null;
    }
  }

  private @Nullable String decryptText (SecretKey key, byte[] blob) {
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(blob, 0, IV_BYTES)));
      byte[] plain = cipher.doFinal(Arrays.copyOfRange(blob, IV_BYTES, blob.length));
      return new String(plain, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return null;
    }
  }

  // keystore

  private @Nullable SecretKey keystoreKey (String alias, boolean create, boolean bound) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
    if (bound && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null;
    try {
      KeyStore keystore = KeyStore.getInstance(ANDROID_KEYSTORE);
      keystore.load(null);
      if (keystore.isKeyEntry(alias)) {
        Key key = keystore.getKey(alias, null);
        return key instanceof SecretKey ? (SecretKey) key : null;
      }
      if (!create) return null;
      KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
      generator.init(keySpec(alias, bound));
      return generator.generateKey();
    } catch (Exception e) {
      return null;
    }
  }

  @RequiresApi(Build.VERSION_CODES.M)
  private static KeyGenParameterSpec keySpec (String alias, boolean bound) throws Exception {
    KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(alias,
      KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(DATA_KEY_BYTES * 8)
      .setUserAuthenticationRequired(bound);
    if (bound) {
      // Every single use of the key has to be authorised by the user; an idle window would defeat the point.
      builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
    }
    return builder.build();
  }

  private static void deleteKeystoreEntries (String prefix) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
    try {
      KeyStore keystore = KeyStore.getInstance(ANDROID_KEYSTORE);
      keystore.load(null);
      List<String> names = new ArrayList<>();
      Enumeration<String> aliases = keystore.aliases();
      while (aliases != null && aliases.hasMoreElements()) {
        String name = aliases.nextElement();
        if (name != null && name.startsWith(prefix)) names.add(name);
      }
      for (int i = 0, length = names.size(); i < length; i++) {
        keystore.deleteEntry(names.get(i));
      }
    } catch (Exception e) {
      // A keystore that cannot be inspected leaves the sealed values unreadable, which is the safe outcome.
    }
  }

  // persisted state

  private String storedValues () {
    String raw = storage.getString(valuesKey, "");
    return raw == null ? "" : raw;
  }

  // encoding

  private static String encode (byte[] data) {
    return Base64.encodeToString(data, Base64.NO_WRAP);
  }

  private static @Nullable byte[] decode (@Nullable String value) {
    if (value == null || value.length() == 0) return null;
    try {
      return Base64.decode(value, Base64.NO_WRAP);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static byte[] combined (byte[] first, byte[] second) {
    byte[] result = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }
}

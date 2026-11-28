/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.thunderdog.challegram.util;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

final class GitHubUpdateClient {
  interface Listener {
    void onRelease (@Nullable Release release, @Nullable Throwable error);
    default void onProgress (long bytesDownloaded, long totalBytes) { }
    default void onDownloaded (File file, Release release) { }
    default void onDownloadFailed (Throwable error) { }
  }

  static final class Asset {
    final String name;
    final String versionName;
    final String downloadUrl;
    final long size;
    final String sha256;

    Asset (String name, String versionName, String downloadUrl, long size, String sha256) {
      this.name = name;
      this.versionName = versionName;
      this.downloadUrl = downloadUrl;
      this.size = size;
      this.sha256 = sha256;
    }
  }

  static final class Release {
    final String tagName;
    final String htmlUrl;
    @Nullable
    final String commit;
    final Asset asset;

    Release (String tagName, String htmlUrl, @Nullable String commit, Asset asset) {
      this.tagName = tagName;
      this.htmlUrl = htmlUrl;
      this.commit = commit;
      this.asset = asset;
    }
  }

  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private static final long MAX_APK_BYTES = 512L * 1024 * 1024;
  private static final int BUFFER_SIZE = 64 * 1024;
  private static final Pattern VERSION_PATTERN = Pattern.compile(
    "(?:^|[-_])(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)(?:-beta\\.(\\d+))?(?:-|$)"
  );
  private static final String[] SDK_TOKENS = {
    "legacy", "lollipop", "marshmallow", "nougatOreoPie", "QRS"
  };

  private final BaseActivity context;
  private final OkHttpClient client;
  private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "TGX-UpdateDownloader");
    thread.setDaemon(true);
    return thread;
  });
  private volatile Call checkCall;
  private volatile Call downloadCall;
  private volatile int generation;

  GitHubUpdateClient (BaseActivity context) {
    this.context = context;
    this.client = new OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .build();
  }

  void findRelease (String currentVersionName, String preferredAbi, Listener listener) {
    if (isBlank(BuildConfig.GITHUB_API_URL)) {
      post(() -> listener.onRelease(null, new IOException("GitHub update API is not configured")));
      return;
    }
    HttpUrl apiUrl = HttpUrl.parse(BuildConfig.GITHUB_API_URL);
    if (apiUrl == null || !apiUrl.scheme().equals("https")) {
      post(() -> listener.onRelease(null, new IOException("GitHub update API URL is invalid")));
      return;
    }
    final int requestGeneration = ++generation;
    final Call call = client.newCall(new Request.Builder()
      .url(apiUrl)
      .header("Accept", "application/vnd.github+json")
      .header("User-Agent", BuildConfig.PROJECT_NAME + "/" + BuildConfig.VERSION_NAME)
      .build());
    checkCall = call;
    call.enqueue(new Callback() {
      @Override
      public void onFailure (Call call, IOException e) {
        finishCheck(requestGeneration, listener, null, call.isCanceled() ? null : e);
      }

      @Override
      public void onResponse (Call call, Response response) throws IOException {
        Release release = null;
        Throwable error = null;
        try (ResponseBody body = response.body()) {
          if (!response.isSuccessful() || body == null) {
            throw new IOException("GitHub update check failed: " + response.code());
          }
          BufferedSource source = body.source();
          source.request(MAX_RESPONSE_BYTES + 1L);
          if (source.buffer().size() > MAX_RESPONSE_BYTES) {
            throw new IOException("GitHub update response is too large");
          }
          release = findRelease(new JSONArray(source.readUtf8()), currentVersionName, preferredAbi);
        } catch (Throwable t) {
          error = t;
        }
        finishCheck(requestGeneration, listener, release, error);
      }
    });
  }

  private void finishCheck (int requestGeneration, Listener listener, @Nullable Release release, @Nullable Throwable error) {
    if (!isCurrent(requestGeneration)) {
      return;
    }
    checkCall = null;
    post(() -> {
      if (isCurrent(requestGeneration)) {
        listener.onRelease(release, error);
      }
    });
  }

  @Nullable
  private Release findRelease (JSONArray releases, String currentVersionName, String preferredAbi) throws Exception {
    final Version currentVersion = Version.parse(currentVersionName);
    final String abiToken = abiToken(preferredAbi);
    if (currentVersion == null || abiToken == null) {
      return null;
    }
    Release best = null;
    Version bestVersion = null;
    for (int releaseIndex = 0; releaseIndex < releases.length(); releaseIndex++) {
      JSONObject releaseObject = releases.getJSONObject(releaseIndex);
      if (releaseObject.optBoolean("draft")) {
        continue;
      }
      String tagName = releaseObject.getString("tag_name");
      String htmlUrl = releaseObject.optString("html_url", "");
      if (!isGitHubUrl(htmlUrl)) {
        continue;
      }
      String commit = releaseObject.optString("target_commitish", "");
      JSONArray assets = releaseObject.optJSONArray("assets");
      if (assets == null) {
        continue;
      }
      for (int assetIndex = 0; assetIndex < assets.length(); assetIndex++) {
        JSONObject assetObject = assets.getJSONObject(assetIndex);
        Asset asset = parseAsset(assetObject, abiToken);
        if (asset == null) {
          continue;
        }
        Version version = Version.parse(asset.versionName);
        if (version == null || !version.isNewerThan(currentVersion)) {
          continue;
        }
        if (bestVersion == null || version.isNewerThan(bestVersion)) {
          bestVersion = version;
          best = new Release(tagName, htmlUrl, commit.isEmpty() ? null : commit, asset);
        }
      }
    }
    return best;
  }

  @Nullable
  private Asset parseAsset (JSONObject assetObject, String abiToken) {
    String name = assetObject.optString("name", "");
    String lowerName = name.toLowerCase(Locale.ROOT);
    if (!lowerName.endsWith(".apk") || !lowerName.contains("-" + abiToken.toLowerCase(Locale.ROOT) + "-")) {
      return null;
    }
    String sdkToken = sdkToken();
    if (sdkToken == null) {
      for (String token : SDK_TOKENS) {
        if (lowerName.contains("-" + token.toLowerCase(Locale.ROOT) + "-")) {
          return null;
        }
      }
    } else if (!lowerName.contains("-" + sdkToken.toLowerCase(Locale.ROOT) + "-")) {
      return null;
    }
    Matcher matcher = VERSION_PATTERN.matcher(name);
    if (!matcher.find()) {
      return null;
    }
    int versionStart = matcher.start();
    if (name.charAt(versionStart) == '-' || name.charAt(versionStart) == '_') {
      versionStart++;
    }
    String versionName = name.substring(versionStart, name.length() - ".apk".length());
    String downloadUrl = assetObject.optString("browser_download_url", "");
    long size = assetObject.optLong("size", 0L);
    String digest = assetObject.optString("digest", "");
    if (!isGitHubDownloadUrl(downloadUrl) || size <= 0 || size > MAX_APK_BYTES || !digest.startsWith("sha256:")) {
      return null;
    }
    String sha256 = digest.substring("sha256:".length()).toLowerCase(Locale.ROOT);
    if (!sha256.matches("[0-9a-f]{64}")) {
      return null;
    }
    return new Asset(name, versionName, downloadUrl, size, sha256);
  }

  void download (Release release, Listener listener) {
    final int requestGeneration = ++generation;
    final File directory = context.getExternalFilesDir("updates");
    if (directory == null) {
      post(() -> listener.onDownloadFailed(new IOException("Update directory is unavailable")));
      return;
    }
    String safeTag = release.tagName.replaceAll("[^a-zA-Z0-9._-]", "_");
    final File destination = new File(directory, "TGX-" + safeTag + ".apk");
    final File temporary = new File(directory, destination.getName() + ".part");
    if (!isCurrent(requestGeneration) || downloadExecutor.isShutdown()) {
      return;
    }
    downloadExecutor.execute(() -> {
      if (!isCurrent(requestGeneration)) {
        return;
      }
      try {
        if (!directory.exists() && !directory.mkdirs()) {
          throw new IOException("Unable to create update directory");
        }
        if (!destination.exists() || !isUsableFile(destination, release.asset)) {
          deleteQuietly(destination);
          deleteQuietly(temporary);
          final Call call = client.newCall(new Request.Builder()
            .url(release.asset.downloadUrl)
            .header("User-Agent", BuildConfig.PROJECT_NAME + "/" + BuildConfig.VERSION_NAME)
            .build());
          downloadCall = call;
          if (!isCurrent(requestGeneration)) {
            call.cancel();
            downloadCall = null;
            throw new IOException("APK download canceled");
          }
          try (Response response = call.execute();
               ResponseBody body = response.body();
               BufferedSource source = body != null ? body.source() : null;
               BufferedSink sink = Okio.buffer(Okio.sink(temporary))) {
            if (!response.isSuccessful() || source == null) {
              throw new IOException("APK download failed: " + response.code());
            }
            MessageDigest digest = sha256();
            byte[] buffer = new byte[BUFFER_SIZE];
            long downloaded = 0;
            long lastProgress = 0;
            for (int read; (read = source.read(buffer)) != -1; ) {
              if (!isCurrent(requestGeneration)) {
                throw new IOException("APK download canceled");
              }
              if (downloaded + read > release.asset.size) {
                throw new IOException("APK download size mismatch");
              }
              sink.write(buffer, 0, read);
              digest.update(buffer, 0, read);
              downloaded += read;
              long now = System.currentTimeMillis();
              if (now - lastProgress >= 500 || downloaded == release.asset.size) {
                lastProgress = now;
                final long progress = downloaded;
                post(() -> {
                  if (isCurrent(requestGeneration)) {
                    listener.onProgress(progress, release.asset.size);
                  }
                });
              }
            }
            sink.flush();
            if (downloaded != release.asset.size) {
              throw new IOException("APK download size mismatch");
            }
            if (!hex(digest.digest()).equals(release.asset.sha256)) {
              throw new IOException("APK download checksum mismatch");
            }
          } finally {
            downloadCall = null;
          }
          deleteQuietly(destination);
          if (!temporary.renameTo(destination)) {
            throw new IOException("Unable to finish APK download");
          }
        }
        if (!isUsableFile(destination, release.asset)) {
          throw new IOException("Downloaded APK is invalid");
        }
        validatePackage(destination, release.asset.versionName);
        if (!isCurrent(requestGeneration)) {
          deleteQuietly(destination);
          return;
        }
        post(() -> {
          if (isCurrent(requestGeneration)) {
            listener.onDownloaded(destination, release);
          }
        });
      } catch (Throwable t) {
        deleteQuietly(temporary);
        deleteQuietly(destination);
        if (!isCurrent(requestGeneration)) {
          return;
        }
        post(() -> {
          if (isCurrent(requestGeneration)) {
            listener.onDownloadFailed(t);
          }
        });
      }
    });
  }

  private boolean isUsableFile (File file, Asset asset) {
    if (!file.isFile() || file.length() != asset.size) {
      return false;
    }
    try (FileInputStream input = new FileInputStream(file)) {
      MessageDigest digest = sha256();
      byte[] buffer = new byte[BUFFER_SIZE];
      for (int read; (read = input.read(buffer)) != -1; ) {
        digest.update(buffer, 0, read);
      }
      return hex(digest.digest()).equals(asset.sha256);
    } catch (Throwable t) {
      return false;
    }
  }

  private void validatePackage (File file, String expectedVersionName) throws Exception {
    PackageManager packageManager = context.getPackageManager();
    PackageInfo archiveInfo = packageManager.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
    if (archiveInfo == null) {
      throw new IOException("Downloaded file is not an APK");
    }
    if (!context.getPackageName().equals(archiveInfo.packageName)) {
      throw new IOException("APK package does not match this app");
    }
    if (!expectedVersionName.equals(archiveInfo.versionName)) {
      throw new IOException("APK version does not match its release asset");
    }
    PackageInfo installedInfo = packageManager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
    Set<String> installedSignatures = signatures(installedInfo);
    Set<String> archiveSignatures = signatures(archiveInfo);
    if (installedSignatures.isEmpty() || !intersects(installedSignatures, archiveSignatures)) {
      throw new IOException("APK signature does not match this app");
    }
  }

  private Set<String> signatures (PackageInfo packageInfo) throws NoSuchAlgorithmException {
    Set<String> result = new HashSet<>();
    if (packageInfo.signatures != null) {
      for (Signature signature : packageInfo.signatures) {
        result.add(hex(sha256().digest(signature.toByteArray())));
      }
    }
    return result;
  }

  private boolean intersects (Set<String> first, Set<String> second) {
    for (String value : first) {
      if (second.contains(value)) {
        return true;
      }
    }
    return false;
  }

  void cancel () {
    generation++;
    if (checkCall != null) {
      checkCall.cancel();
      checkCall = null;
    }
    if (downloadCall != null) {
      downloadCall.cancel();
      downloadCall = null;
    }
    downloadExecutor.shutdownNow();
  }

  private boolean isCurrent (int requestGeneration) {
    return requestGeneration == generation;
  }

  private void post (Runnable runnable) {
    context.runOnUiThread(runnable);
  }

  @Nullable
  private String sdkToken () {
    if (BuildConfig.LATEST_FLAVOR) {
      return null;
    } else if (BuildConfig.LEGACY_FLAVOR) {
      return "legacy";
    } else if (BuildConfig.LOLLIPOP_FLAVOR) {
      return "lollipop";
    } else if (BuildConfig.MARSHMALLOW_FLAVOR) {
      return "marshmallow";
    } else if (BuildConfig.NOP_FLAVOR) {
      return "nougatOreoPie";
    } else if (BuildConfig.QRS_FLAVOR) {
      return "QRS";
    }
    return "";
  }

  @Nullable
  private static String abiToken (String preferredAbi) {
    switch (preferredAbi) {
      case "arm64": return "arm64-v8a";
      case "arm32": return "armeabi-v7a";
      case "x64": return "x64";
      case "x86": return "x86";
      default: return null;
    }
  }

  private static boolean isGitHubUrl (String url) {
    HttpUrl parsed = HttpUrl.parse(url);
    return parsed != null && parsed.scheme().equals("https") && parsed.host().equals("github.com");
  }

  private static boolean isGitHubDownloadUrl (String url) {
    HttpUrl parsed = HttpUrl.parse(url);
    return parsed != null && parsed.scheme().equals("https") &&
      (parsed.host().equals("github.com") || parsed.host().equals("objects.githubusercontent.com"));
  }

  private static MessageDigest sha256 () throws NoSuchAlgorithmException {
    return MessageDigest.getInstance("SHA-256");
  }

  private static String hex (byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result.append(Character.forDigit((value >>> 4) & 0xf, 16));
      result.append(Character.forDigit(value & 0xf, 16));
    }
    return result.toString();
  }

  private static void deleteQuietly (File file) {
    if (file.exists() && !file.delete()) {
      file.deleteOnExit();
    }
  }

  private static boolean isBlank (@Nullable String value) {
    return value == null || value.trim().isEmpty();
  }

  private static final class Version {
    final int major;
    final int minor;
    final int patch;
    final int build;
    final int beta;

    private Version (int major, int minor, int patch, int build, int beta) {
      this.major = major;
      this.minor = minor;
      this.patch = patch;
      this.build = build;
      this.beta = beta;
    }

    @Nullable
    static Version parse (String versionName) {
      Matcher matcher = VERSION_PATTERN.matcher(versionName);
      if (!matcher.find()) {
        return null;
      }
      try {
        String betaValue = matcher.group(5);
        return new Version(
          Integer.parseInt(matcher.group(1)),
          Integer.parseInt(matcher.group(2)),
          Integer.parseInt(matcher.group(3)),
          Integer.parseInt(matcher.group(4)),
          betaValue == null ? Integer.MAX_VALUE : Integer.parseInt(betaValue)
        );
      } catch (NumberFormatException e) {
        return null;
      }
    }

    boolean isNewerThan (Version other) {
      int[] current = {major, minor, patch, build};
      int[] candidate = {other.major, other.minor, other.patch, other.build};
      for (int i = 0; i < current.length; i++) {
        if (candidate[i] != current[i]) {
          return candidate[i] > current[i];
        }
      }
      return other.beta > beta;
    }
  }
}

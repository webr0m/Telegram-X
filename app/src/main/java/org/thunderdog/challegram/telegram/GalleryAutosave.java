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
 *
 * File created on 14/02/2018
 */
package org.thunderdog.challegram.telegram;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.data.MediaWrapper;
import org.thunderdog.challegram.unsorted.AppContext;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import tgx.td.Td;

public final class GalleryAutosave {
  private static final int MAX_WAITING = 256;
  private static final String DIRECTORY = "Telegram X";
  private final Tdlib tdlib;
  private final Map<String, Candidate> pending = new LinkedHashMap<>();
  private final HashSet<String> exporting = new HashSet<>();
  private final java.util.LinkedHashSet<String> recentlyExported = new java.util.LinkedHashSet<>();
  private TdApi.AutosaveSettings rules;
  private long requestedFor;
  private int ruleGeneration;
  private boolean loading;

  GalleryAutosave (Tdlib tdlib) {
    this.tdlib = tdlib;
  }

  private static SharedPreferences preferences () {
    return AppContext.get().getSharedPreferences("gallery_autosave", Context.MODE_PRIVATE);
  }

  public static boolean supported () {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
  }

  public boolean enabled () {
    long user = tdlib.myUserId();
    return supported() && user != 0 && preferences().getBoolean("account:" + user, false);
  }

  public void setEnabled (boolean enabled) {
    long user = tdlib.myUserId();
    if (!supported() || user == 0 || !tdlib.isAuthorized()) {
      return;
    }
    preferences().edit().putBoolean("account:" + user, enabled).apply();
    reset();
  }

  synchronized void reset () {
    pending.clear();
    recentlyExported.clear();
    rules = null;
    ruleGeneration++;
    requestedFor = 0;
    loading = false;
  }

  synchronized void onRulesChanged () {
    rules = null;
    ruleGeneration++;
    loading = false;
    if (!pending.isEmpty()) {
      loadRules();
    }
  }

  synchronized void onNewMessage (TdApi.Message message) {
    if (!enabled() || !tdlib.isAuthorized() || !eligible(message)) {
      return;
    }
    TdApi.Chat chat = tdlib.chat(message.chatId);
    int category = category(chat);
    if (category == 0) {
      return;
    }
    TdApi.File file = mediaFile(message.content);
    if (file == null || file.id == 0) {
      return;
    }
    long user = tdlib.myUserId();
    if (requestedFor != user) {
      reset();
      requestedFor = user;
    }
    Candidate candidate = new Candidate(user, message.chatId, message.id, file,
      message.content instanceof TdApi.MessageVideo, category);
    String key = candidate.key();
    if (exporting.contains(key) || recentlyExported.contains(key)) {
      return;
    }
    if (rules != null && !canQueue(candidate)) {
      return;
    }
    pending.put(key, candidate);
    if (pending.size() > MAX_WAITING) {
      pending.remove(pending.keySet().iterator().next());
    }
    if (rules == null) {
      loadRules();
    } else {
      finishReady();
    }
  }

  synchronized void onFile (TdApi.File file) {
    if (pending.isEmpty() || file.local == null || !file.local.isDownloadingCompleted) {
      return;
    }
    for (Candidate candidate : pending.values()) {
      if (candidate.file.id == file.id) {
        candidate.file = file;
      }
    }
    if (rules == null) {
      loadRules();
    } else {
      finishReady();
    }
  }

  private void loadRules () {
    if (loading || requestedFor == 0 || requestedFor != tdlib.myUserId() || !enabled()) {
      return;
    }
    loading = true;
    int generation = ruleGeneration;
    long account = requestedFor;
    tdlib.send(new TdApi.GetAutosaveSettings(), (result, error) -> {
      synchronized (this) {
        if (account != requestedFor || account != tdlib.myUserId() || generation != ruleGeneration || !loading) {
          return;
        }
        loading = false;
        if (result != null) {
          rules = result;
          finishReady();
        }
      }
    });
  }

  private void finishReady () {
    ArrayList<Candidate> ready = new ArrayList<>();
    Iterator<Candidate> entries = pending.values().iterator();
    while (entries.hasNext()) {
      Candidate candidate = entries.next();
      if (!canQueue(candidate)) {
        entries.remove();
      } else if (isComplete(candidate.file)) {
        entries.remove();
        if (exporting.add(candidate.key())) {
          ready.add(candidate);
        }
      }
    }
    for (Candidate candidate : ready) {
      tdlib.send(new TdApi.GetMessage(candidate.chat, candidate.message),
        (latest, error) -> {
          if (validLatest(candidate, latest)) {
            Background.instance().post(() -> export(candidate));
          } else {
            finish(candidate);
          }
        });
    }
  }

  private boolean validLatest (Candidate candidate, TdApi.Message message) {
    if (!eligible(message) || message.chatId != candidate.chat || message.id != candidate.message) {
      return false;
    }
    return containsMediaFile(message.content, candidate.file.id) && canPublish(candidate);
  }

  private synchronized void finish (Candidate candidate) {
    exporting.remove(candidate.key());
  }

  private synchronized void markExported (String key) {
    recentlyExported.add(key);
    if (recentlyExported.size() > MAX_WAITING) {
      recentlyExported.remove(recentlyExported.iterator().next());
    }
  }

  private synchronized boolean canPublish (Candidate candidate) {
    return enabled() && tdlib.isAuthorized() && candidate.account == tdlib.myUserId() &&
      category(tdlib.chat(candidate.chat)) == candidate.category && canQueue(candidate);
  }

  private boolean canQueue (Candidate candidate) {
    TdApi.ScopeAutosaveSettings rule = effectiveRules(rules, candidate.chat, candidate.category);
    return canQueue(rule, candidate.video, candidate.file.size);
  }

  static boolean canQueue (TdApi.ScopeAutosaveSettings rule, boolean video, long size) {
    // TDLib may not know a video's exact size until it downloads. Verify the disk length before saving.
    return video && size == 0 ? rule != null && rule.autosaveVideos && rule.maxVideoFileSize > 0 :
      isAllowed(rule, video, size);
  }

  private static boolean isComplete (TdApi.File file) {
    return file.local != null && file.local.isDownloadingCompleted;
  }

  private void export (Candidate candidate) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canPublish(candidate)) {
        File source = new File(candidate.file.local.path);
        long length = source.length();
        boolean allowed;
        synchronized (this) {
          allowed = isAllowed(effectiveRules(rules, candidate.chat, candidate.category), candidate.video, length);
        }
        if (source.isFile() && length > 0 && allowed) {
          if (publish(source, candidate, () -> canPublish(candidate))) {
            markExported(candidate.key());
          }
        }
      }
    } catch (IOException | RuntimeException error) {
      Log.w("Could not autosave media", error);
    } finally {
      finish(candidate);
    }
  }

  static boolean eligible (TdApi.Message message) {
    if (message == null || message.id <= 0 || !message.canBeSaved || message.isOutgoing ||
        message.sendingState != null || message.schedulingState != null || message.selfDestructType != null ||
        message.selfDestructIn > 0 || message.autoDeleteIn > 0 || message.ephemeralContent != null ||
        message.ephemeralMessageId != 0 || message.isPaidStarSuggestedPost || message.isPaidGramSuggestedPost) {
      return false;
    }
    return (message.content instanceof TdApi.MessagePhoto photo && !photo.isSecret && !photo.hasSpoiler) ||
      (message.content instanceof TdApi.MessageVideo video && !video.isSecret && !video.hasSpoiler);
  }

  static int category (TdApi.Chat chat) {
    if (chat == null || chat.hasProtectedContent) {
      return 0;
    }
    if (chat.type instanceof TdApi.ChatTypePrivate) {
      return 1;
    }
    if (chat.type instanceof TdApi.ChatTypeBasicGroup) {
      return 2;
    }
    if (chat.type instanceof TdApi.ChatTypeSupergroup group) {
      return group.isChannel ? 3 : 2;
    }
    return 0;
  }

  static TdApi.ScopeAutosaveSettings effectiveRules (TdApi.AutosaveSettings rules, long chat, int category) {
    if (rules == null || category == 0) {
      return null;
    }
    for (TdApi.AutosaveSettingsException exception : rules.exceptions) {
      if (exception.chatId == chat && exception.settings != null) {
        return exception.settings;
      }
    }
    return switch (category) {
      case 1 -> rules.privateChatSettings;
      case 2 -> rules.groupSettings;
      case 3 -> rules.channelSettings;
      default -> null;
    };
  }

  static boolean isAllowed (TdApi.ScopeAutosaveSettings rule, boolean video, long size) {
    return rule != null && (video ? rule.autosaveVideos && size > 0 &&
      size <= rule.maxVideoFileSize : rule.autosavePhotos);
  }

  private static TdApi.File mediaFile (TdApi.MessageContent content) {
    if (content instanceof TdApi.MessagePhoto photo) {
      TdApi.PhotoSize target = MediaWrapper.buildTargetFile(photo.photo);
      if (target == null && photo.photo.sizes.length == 1) {
        target = Td.findBiggest(photo.photo);
      }
      return target != null ? target.photo : null;
    }
    return content instanceof TdApi.MessageVideo video ? video.video.video : null;
  }

  static boolean containsMediaFile (TdApi.MessageContent content, int id) {
    if (content instanceof TdApi.MessagePhoto photo) {
      for (TdApi.PhotoSize size : photo.photo.sizes) {
        if (size.photo.id == id) {
          return true;
        }
      }
      return false;
    }
    return content instanceof TdApi.MessageVideo video && video.video.video.id == id;
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  private static boolean publish (File source, Candidate candidate, BooleanSupplier stillAllowed) throws IOException {
    ContentResolver resolver = AppContext.get().getContentResolver();
    Uri collection = candidate.video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
      android.webkit.MimeTypeMap.getFileExtensionFromUrl(source.getName()).toLowerCase(java.util.Locale.ROOT));
    if (mime == null || !mime.startsWith(candidate.video ? "video/" : "image/")) {
      mime = candidate.video ? "video/mp4" : "image/jpeg";
    }
    String extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
    String path = (candidate.video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/" + DIRECTORY + "/";
    String name = "TGX_" + candidate.account + "_" + candidate.chat + "_" + candidate.message + "." +
      (extension != null ? extension : candidate.video ? "mp4" : "jpg");
    // Keep Telegram identifiers out of the public filename while retaining a stable retry key.
    name = java.util.UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." +
      (extension != null ? extension : candidate.video ? "mp4" : "jpg");
    try (android.database.Cursor matches = resolver.query(collection, new String[] {MediaStore.MediaColumns._ID},
        MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
        new String[] {name, path}, null)) {
      if (matches != null && matches.moveToFirst()) {
        return true;
      }
    }
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
    values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
    values.put(MediaStore.MediaColumns.RELATIVE_PATH, path);
    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
    Uri destination = resolver.insert(collection, values);
    if (destination == null) {
      return false;
    }
    boolean published = false;
    try {
      try (FileInputStream input = new FileInputStream(source); OutputStream output = resolver.openOutputStream(destination)) {
        if (output == null) {
          return false;
        }
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
          output.write(buffer, 0, count);
        }
      }
      if (stillAllowed.getAsBoolean()) {
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        published = resolver.update(destination, values, null, null) == 1;
      }
      return published;
    } finally {
      if (!published) {
        resolver.delete(destination, null, null);
      }
    }
  }

  private static final class Candidate {
    final long account, chat, message;
    final boolean video;
    final int category;
    TdApi.File file;

    Candidate (long account, long chat, long message, TdApi.File file, boolean video, int category) {
      this.account = account;
      this.chat = chat;
      this.message = message;
      this.file = file;
      this.video = video;
      this.category = category;
    }

    String key () {
      return "media:" + account + ":" + chat + ":" + message;
    }
  }
}

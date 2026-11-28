/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.navigation.BackHeaderButton;
import org.thunderdog.challegram.navigation.DoubleHeaderView;
import org.thunderdog.challegram.navigation.HeaderButton;
import org.thunderdog.challegram.navigation.NavigationController;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.Menu;
import org.thunderdog.challegram.navigation.MoreDelegate;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ThemeDelegate;
import org.thunderdog.challegram.tool.Keyboard;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.Permissions;
import org.thunderdog.challegram.util.MiniAppStorage;
import org.thunderdog.challegram.util.StringList;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;

import me.vkryl.core.StringUtils;

import tgx.td.ChatId;
import tgx.td.Td;

/** A TDLib-authenticated Mini App with an origin-scoped, main-frame-only bridge. */
public final class MiniAppController extends ViewController<Void> implements Menu, MoreDelegate, org.thunderdog.challegram.telegram.AuthorizationListener, MiniAppDeviceServices.Gateway, MiniAppSecureServices.Gateway {
  interface Navigator { boolean navigate (MiniAppController app); }
  private final long botUserId;
  private final long accountUserId;
  private final long chatId;
  private final String sourceUrl;
  private final @Nullable String shortName;
  private final String startParameter;
  private final @Nullable String keyboardButtonText;
  private final @Nullable TdApi.MessageTopic topicId;
  private final @Nullable TdApi.InputMessageReplyTo replyTo;
  private final @Nullable TdApi.WebAppOpenMode mode;
  private DoubleHeaderView header;
  private WebView webView;
  private ProgressBar progress;
  private TextView errorText;
  private LinearLayout buttons;
  private MiniAppButtonView mainButton, secondaryButton;
  private @Nullable JavaScriptReplyProxy reply;
  private @Nullable AlertDialog popup;
  private boolean popupIsPage;
  private String origin;
  private long launchId;
  private int documentGeneration;
  private int pendingCustomRequests;
  private int pendingStorageRequests;
  private boolean appBackButton, confirmClosing, dataSending, messageSent;
  private @Nullable ValueCallback<Uri[]> fileChooser;
  private long lastInteractionTime;
  private boolean requestingDownload;
  private boolean failed;
  private MiniAppFullscreen fullscreen;
  private boolean attachmentLaunch;
  private boolean preparingMessage, preparedResultPending, requestingChat;
  private int preparedGeneration;
  private String preparedError;
  private boolean initialFullscreen;
  private boolean settingsButtonVisible;
  private MiniAppViewport viewport;
  private boolean minimized;
  private boolean openingInvoice, invoiceResultPending;
  private int invoiceGeneration;
  private String invoiceSlug, invoiceStatus;
  private boolean allowVerticalSwipes = true;
  private float handleDownY;
  private MiniAppDeviceServices deviceServices;
  private MiniAppSecureServices secureServices;
  private int deviceRunToken;
  private int headerColorKind = MiniAppHeaderColor.THEME;
  private int headerColorValue;
  private boolean headerLightContent;
  private int bottomBarColor;
  private boolean navigationColorOwned;
  private @Nullable HeaderButton headerMoreButton;
  private boolean pendingEmojiAccess;

  private MiniAppController (Context context, Tdlib tdlib, long botUserId, long chatId,
                            String sourceUrl, @Nullable String shortName, String startParameter,
                            @Nullable String keyboardButtonText, @Nullable TdApi.MessageTopic topicId,
                            @Nullable TdApi.InputMessageReplyTo replyTo, @Nullable TdApi.WebAppOpenMode mode) {
    super(context, tdlib);
    this.botUserId = botUserId;
    this.accountUserId = tdlib.myUserId();
    this.chatId = chatId;
    this.sourceUrl = sourceUrl;
    this.shortName = shortName;
    this.startParameter = startParameter;
    this.keyboardButtonText = keyboardButtonText;
    this.topicId = topicId;
    this.replyTo = replyTo;
    this.mode = mode;
  }

  public static void open (ViewController<?> owner, long botUserId, String url,
                           @Nullable String keyboardButtonText, @Nullable TdApi.MessageTopic topicId,
                           @Nullable TdApi.InputMessageReplyTo replyTo) {
    confirmOpen(owner, botUserId, owner.getChatId(), url, null, "", keyboardButtonText, topicId, replyTo, null);
  }

  static void openAttachment (ViewController<?> owner, long botUserId, long chatId, String url,
                              @Nullable TdApi.MessageTopic topic, @Nullable TdApi.InputMessageReplyTo reply) {
    openAttachment(owner, botUserId, chatId, url, topic, reply, null);
  }

  static void openAttachment (ViewController<?> owner, long botUserId, long chatId, String url,
                              @Nullable TdApi.MessageTopic topic, @Nullable TdApi.InputMessageReplyTo reply, @Nullable Navigator navigator) {
    confirmOpen(owner, botUserId, chatId, url, null, "", null, topic, reply, null, true, navigator);
  }

  public static void openLink (ViewController<?> owner, long chatId, String username,
                               @Nullable String shortName, String startParameter, @Nullable TdApi.WebAppOpenMode mode) {
    long sourceChatId = owner.getChatId();
    owner.tdlib().send(new TdApi.SearchPublicChat(username), (chat, error) -> owner.runOnUiThreadOptional(() -> {
      if (!owner.isFocused() || owner.getChatId() != sourceChatId) {
        return;
      }
      if (error != null) {
        UI.showError(error);
      } else {
        confirmOpen(owner, owner.tdlib().chatUserId(chat), chatId, "", shortName, startParameter, null, null, null, mode);
      }
    }));
  }

  private static void confirmOpen (ViewController<?> owner, long botUserId, long chatId, String url,
                                  @Nullable String shortName, String startParameter, @Nullable String keyboardButtonText,
                                  @Nullable TdApi.MessageTopic topicId, @Nullable TdApi.InputMessageReplyTo replyTo,
                                  @Nullable TdApi.WebAppOpenMode mode) {
    confirmOpen(owner, botUserId, chatId, url, shortName, startParameter, keyboardButtonText, topicId, replyTo, mode, false);
  }

  private static void confirmOpen (ViewController<?> owner, long botUserId, long chatId, String url,
                                  @Nullable String shortName, String startParameter, @Nullable String keyboardButtonText,
                                  @Nullable TdApi.MessageTopic topicId, @Nullable TdApi.InputMessageReplyTo replyTo,
                                  @Nullable TdApi.WebAppOpenMode mode, boolean attachmentLaunch) {
    confirmOpen(owner, botUserId, chatId, url, shortName, startParameter, keyboardButtonText, topicId, replyTo, mode, attachmentLaunch, null);
  }

  private static void confirmOpen (ViewController<?> owner, long botUserId, long chatId, String url,
                                  @Nullable String shortName, String startParameter, @Nullable String keyboardButtonText,
                                  @Nullable TdApi.MessageTopic topicId, @Nullable TdApi.InputMessageReplyTo replyTo,
                                  @Nullable TdApi.WebAppOpenMode mode, boolean attachmentLaunch, @Nullable Navigator navigator) {
    TdApi.User user = owner.tdlib().cache().user(botUserId);
    if (owner.isDestroyed() || !owner.tdlib().isAuthorized() || user == null || !(user.type instanceof TdApi.UserTypeBot) ||
        ChatId.isSecret(chatId) || Td.hasRestriction(user.restrictionInfo, Settings.instance().needRestrictContent())) {
      UI.showToast(R.string.MiniAppUnavailable, android.widget.Toast.LENGTH_SHORT);
      return;
    }
    owner.showConfirm(Lang.getString(R.string.MiniAppOpenConfirm, owner.tdlib().cache().userName(botUserId)),
      Lang.getString(R.string.MiniAppOpen), () -> {
        if (owner.isFocused() && !owner.isDestroyed()) {
          if (navigator == null && !owner.context().miniAppTabs().hasRoomForNewLaunch()) {
            UI.showToast(R.string.MiniAppTabsFull, android.widget.Toast.LENGTH_SHORT);
            return;
          }
          MiniAppController app = new MiniAppController(owner.context(), owner.tdlib(), botUserId, chatId,
            url, shortName, startParameter, keyboardButtonText, topicId, replyTo, mode);
          app.attachmentLaunch = attachmentLaunch;
          if (!(navigator != null ? navigator.navigate(app) : owner.navigateTo(app))) app.destroy();
        }
      });
  }

  @Override
  public int getId () {
    return R.id.controller_miniApp;
  }

  @Override
  public long getChatId () {
    return chatId;
  }

  @Override
  protected int getBackButton () {
    return BackHeaderButton.TYPE_BACK;
  }

  @Override
  public View getCustomHeaderCell () {
    return header;
  }

  @Override
  protected int getMenuId () {
    return R.id.menu_miniApp;
  }

  @Override
  public void fillMenuItems (int id, HeaderView header, LinearLayout menu) {
    headerMoreButton = header.addMoreButton(menu, this);
  }

  @Override
  public void onMenuItemPressed (int id, View view) {
    if (id != R.id.menu_btn_more) return;
    me.vkryl.core.collection.IntList ids = new me.vkryl.core.collection.IntList(3);
    StringList labels = new StringList(3);
    ids.append(R.id.btn_miniAppFullscreen);
    labels.append(Lang.getString(R.string.MiniAppFullscreen));
    ids.append(R.id.btn_miniAppCompact);
    labels.append(Lang.getString(viewport.isCompact() ? R.string.MiniAppExpand : R.string.MiniAppCompact));
    ids.append(R.id.btn_miniAppMinimize);
    labels.append(Lang.getString(R.string.MiniAppMinimize));
    if (webView != null && !failed && webView.getUrl() != null) {
      ids.append(R.id.btn_miniAppReload);
      labels.append(Lang.getString(R.string.MiniAppReload));
    }
    ids.append(R.id.btn_miniAppTabs);
    labels.append(Lang.getString(R.string.MiniAppTabs));
    ids.append(R.id.btn_miniAppPayments);
    labels.append(Lang.getString(R.string.MiniAppPaymentHistory));
    if (settingsButtonVisible) {
      ids.append(R.id.btn_miniAppSettings);
      labels.append(Lang.getString(R.string.Settings));
    }
    if (attachmentLaunch) {
      ids.append(R.id.btn_miniAppRemove);
      labels.append(Lang.getString(R.string.MiniAppRemoveAttachment));
    }
    showMore(ids.get(), labels.get(), 0);
  }

  @Override
  public void onMoreItemPressed (int id) {
    if (id == R.id.btn_miniAppCompact) {
      fullscreen.setRequested(false);
      viewport.setCompact(!viewport.isCompact());
    } else if (id == R.id.btn_miniAppMinimize) {
      minimize();
    } else if (id == R.id.btn_miniAppReload) {
      webView.reload();
    } else if (id == R.id.btn_miniAppTabs) {
      context().miniAppTabs().show();
    } else if (id == R.id.btn_miniAppPayments) {
      navigateTo(new MiniAppPaymentHistoryController(context(), tdlib));
    } else if (id == R.id.btn_miniAppFullscreen) {
      if (!fullscreen.isSupported() || !fullscreen.setRequested(true)) {
        sendEvent("fullscreen_failed", json("error", "UNSUPPORTED"));
      }
    } else if (id == R.id.btn_miniAppSettings && settingsButtonVisible) {
      sendEvent("settings_button_pressed", json());
    } else if (id == R.id.btn_miniAppRemove && attachmentLaunch) {
      MiniAppAttachments.remove(this, botUserId);
    }
  }

  @Override
  public boolean canSlideBackFrom (NavigationController navigation, float x, float y) {
    return false;
  }

  @SuppressLint({"SetJavaScriptEnabled", "RestrictedApi"})
  @Override
  protected View onCreateView (Context context) {
    tdlib.listeners().addAuthorizationChangeListener(this);
    header = new DoubleHeaderView(context);
    header.setThemedTextColor(this);
    header.initWithMargin(Screen.dp(49), true);
    header.setTitle(tdlib.cache().userName(botUserId));
    header.setSubtitle(Lang.getString(R.string.MiniApp));
    LinearLayout root = new LinearLayout(context);
    root.setOrientation(LinearLayout.VERTICAL);
    MiniAppViewport host = viewport = new MiniAppViewport(context, root);
    viewport.setCompact(mode instanceof TdApi.WebAppOpenModeCompact);
    host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    host.setOnClickListener(v -> viewport.setCompact(false));
    fullscreen = new MiniAppFullscreen(context(), host, root, this::sendFullscreenState, this::requestClose);
    initialFullscreen = mode instanceof TdApi.WebAppOpenModeFullScreen;
    fullscreen.setRequested(initialFullscreen);
    deviceServices = new MiniAppDeviceServices(context(), this);
    secureServices = new MiniAppSecureServices(context(), this, accountUserId, botUserId);
    ViewSupport.setThemedBackground(root, ColorId.filling, this);
    TextView handle = new TextView(context);
    handle.setText("―");
    handle.setTextColor(Theme.textAccentColor());
    addThemeTextAccentColorListener(handle);
    handle.setGravity(Gravity.CENTER);
    handle.setContentDescription(Lang.getString(R.string.MiniAppExpand));
    handle.setOnClickListener(v -> viewport.setCompact(false));
    handle.setOnTouchListener((v, event) -> {
      if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { handleDownY = event.getRawY(); return true; }
      if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
        float distance = event.getRawY() - handleDownY;
        if (allowVerticalSwipes && distance > Screen.dp(80)) minimize();
        else if (allowVerticalSwipes && distance < -Screen.dp(40)) viewport.setCompact(false);
        else if (Math.abs(distance) < Screen.dp(12)) v.performClick();
        return true;
      }
      return true;
    });
    root.addView(handle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(32)));
    progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
    root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(3)));
    errorText = new TextView(context);
    errorText.setTextColor(Theme.textAccentColor());
    addThemeTextAccentColorListener(errorText);
    errorText.setGravity(Gravity.CENTER);
    errorText.setPadding(Screen.dp(24), Screen.dp(24), Screen.dp(24), Screen.dp(24));
    errorText.setVisibility(View.GONE);
    root.addView(errorText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    try {
      if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
          !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        showError(R.string.MiniAppWebViewRequired);
        return host;
      }
      webView = new WebView(context);
      WebSettings settings = webView.getSettings();
      settings.setJavaScriptEnabled(true);
      settings.setDomStorageEnabled(true);
      settings.setAllowFileAccess(false);
      settings.setAllowContentAccess(false);
      settings.setJavaScriptCanOpenWindowsAutomatically(false);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
      }
      webView.setWebChromeClient(new WebChromeClient() {
        @Override
        public void onProgressChanged (WebView view, int value) {
          progress.setProgress(value);
        }

        @Override
        public void onPermissionRequest (PermissionRequest request) {
          if (request == null) return;
          if (isDestroyed() || deviceServices == null || !isFocused() || minimized ||
              context().getActivityState() != UI.State.RESUMED) {
            request.deny();
            return;
          }
          MiniAppMediaAccess.Grant grant = deviceServices.onWebViewPermissionRequest(request.getResources());
          if (grant.resources.length == 0) {
            request.deny();
          } else {
            request.grant(grant.resources);
          }
        }

        @Override
        public boolean onShowFileChooser (WebView view, ValueCallback<Uri[]> callback, FileChooserParams parameters) {
          if (!isFocused()) {
            callback.onReceiveValue(null);
            return true;
          }
          cancelFileChooser();
          fileChooser = callback;
          int generation = documentGeneration;
          context().putActivityResultHandler(Intents.ACTIVITY_RESULT_MINI_APP_FILE, (requestCode, resultCode, data) -> {
            ValueCallback<Uri[]> pending = fileChooser;
            fileChooser = null;
            if (pending == null) return;
            Uri[] selected = FileChooserParams.parseResult(resultCode, data);
            if (generation != documentGeneration || isDestroyed()) selected = null;
            if (selected != null) {
              for (Uri uri : selected) {
                if (uri == null || !"content".equals(uri.getScheme())) {
                  selected = null;
                  break;
                }
              }
            }
            pending.onReceiveValue(selected);
          });
          try {
            Intent intent = parameters.createIntent();
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            context().startActivityForResult(intent, Intents.ACTIVITY_RESULT_MINI_APP_FILE);
          } catch (RuntimeException e) {
            cancelFileChooser();
          }
          return true;
        }
      });
      webView.setOnTouchListener((v, event) -> {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
          lastInteractionTime = SystemClock.elapsedRealtime();
        }
        return false;
      });
      webView.setWebViewClient(new WebViewClient() {
        @Override
        public void onPageStarted (WebView view, String url, Bitmap favicon) {
          documentGeneration++;
          cancelFileChooser();
          reply = null;
          pendingCustomRequests = 0;
          lastInteractionTime = 0;
          appBackButton = confirmClosing = false;
          settingsButtonVisible = false;
          allowVerticalSwipes = true;
          fullscreen.setOrientationLocked(false);
          preparingMessage = preparedResultPending = requestingChat = false;
          openingInvoice = invoiceResultPending = false;
          pendingEmojiAccess = false;
          headerColorKind = MiniAppHeaderColor.THEME;
          headerColorValue = 0;
          bottomBarColor = 0;
          buttons.setBackgroundColor(0);
          applyHeaderColor();
          applyNavigationColor();
          deviceServices.invalidate();
          deviceServices.mediaDocumentStarted();
          if (secureServices != null) secureServices.invalidate();
          mainButton.setVisibility(View.GONE);
          secondaryButton.setVisibility(View.GONE);
          if (popup != null) {
            popup.dismiss();
          }
          progress.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished (WebView view, String url) {
          progress.setVisibility(View.GONE);
          sendTheme();
          sendViewport();
          if (!failed) context().miniAppTabs().onFreshSessionOpened(MiniAppController.this);
        }

        @Override
        public void onReceivedError (WebView view, int code, String description, String failingUrl) {
          if (failingUrl.equals(view.getUrl())) {
            showError(R.string.MiniAppLoadFailed);
          }
        }

        @Override
        public boolean shouldOverrideUrlLoading (WebView view, String url) {
          return navigateUrl(url);
        }

        @Override
        public boolean shouldOverrideUrlLoading (WebView view, WebResourceRequest request) {
          return request.isForMainFrame() && navigateUrl(request.getUrl().toString());
        }
      });
      webView.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> sendViewport());
      root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
      buttons = new LinearLayout(context);
      buttons.setOrientation(LinearLayout.VERTICAL);
      mainButton = newButton(context, "main_button_pressed");
      secondaryButton = newButton(context, "secondary_button_pressed");
      buttons.addView(mainButton);
      buttons.addView(secondaryButton);
      root.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      openAuthenticatedUrl();
    } catch (RuntimeException e) {
      showError(R.string.MiniAppWebViewRequired);
    }
    return host;
  }

  private MiniAppButtonView newButton (Context context, String event) {
    MiniAppButtonView button = new MiniAppButtonView(context, tdlib);
    button.setVisibility(View.GONE);
    button.setOnClickListener(v -> {
      lastInteractionTime = SystemClock.elapsedRealtime();
      sendEvent(event, json());
    });
    button.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(52)));
    return button;
  }

  private void showError (int resource) {
    failed = true;
    documentGeneration++;
    reply = null;
    bottomBarColor = 0;
    if (buttons != null) {
      buttons.setBackgroundColor(0);
    }
    applyNavigationColor();
    if (deviceServices != null) deviceServices.invalidate();
    if (secureServices != null) secureServices.invalidate();
    progress.setVisibility(View.GONE);
    errorText.setText(resource);
    errorText.setVisibility(View.VISIBLE);
    if (webView != null) {
      webView.setVisibility(View.GONE);
    }
  }

  private void openAuthenticatedUrl () {
    TdApi.WebAppOpenParameters parameters = new TdApi.WebAppOpenParameters(themeParameters(), "android", mode);
    if (shortName != null) {
      tdlib.send(new TdApi.GetWebAppLinkUrl(chatId, botUserId, shortName, startParameter, false, parameters),
        (url, error) -> runOnUiThreadOptional(() -> onUrl(url, error)));
    } else if (sourceUrl.isEmpty() && !attachmentLaunch) {
      tdlib.send(new TdApi.GetMainWebApp(chatId, botUserId, startParameter, parameters),
        (app, error) -> runOnUiThreadOptional(() -> {
          if (app != null) {
            initialFullscreen = app.mode instanceof TdApi.WebAppOpenModeFullScreen;
            fullscreen.setRequested(initialFullscreen);
            viewport.setCompact(app.mode instanceof TdApi.WebAppOpenModeCompact);
          }
          onUrl(app != null ? app.url : null, error);
        }));
    } else if (keyboardButtonText != null) {
      tdlib.send(new TdApi.GetWebAppUrl(botUserId, sourceUrl, parameters),
        (url, error) -> runOnUiThreadOptional(() -> onUrl(url, error)));
    } else {
      tdlib.send(new TdApi.OpenWebApp(chatId, botUserId, sourceUrl, topicId, replyTo, parameters), (app, error) ->
        UI.post(() -> {
          if (isDestroyed()) {
            if (app != null) {
              tdlib.send(new TdApi.CloseWebApp(app.launchId), tdlib.typedOkHandler());
            }
            return;
          }
          if (app != null) {
            launchId = app.launchId;
            tdlib.ui().registerWebApp(launchId, this::onMessageSent);
          }
          onUrl(app != null ? app.url : null, error);
        }));
    }
  }

  @SuppressLint("RestrictedApi")
  private void onUrl (@Nullable TdApi.WebAppUrl url, @Nullable TdApi.Error error) {
    if (tdlib.myUserId() != accountUserId || error != null || url == null || httpsOrigin(url.url) == null) {
      if (error != null) {
        UI.showError(error);
      }
      showError(R.string.MiniAppLoadFailed);
      return;
    }
    origin = httpsOrigin(url.url);
    Set<String> origins = Collections.singleton(origin);
    try {
      String bootstrap;
      try (InputStream input = context().getResources().openRawResource(R.raw.mini_app_bridge);
           ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
          output.write(buffer, 0, count);
        }
        bootstrap = new String(output.toByteArray(), StringUtils.UTF_8);
      }
      WebViewCompat.addWebMessageListener(webView, "TgxMiniAppBridge", origins,
        (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
          String data = message.getData();
          UI.post(() -> {
            if (!failed && !isDestroyed() && isMainFrame && origin.equals(httpsOrigin(sourceOrigin.toString())) &&
                origin.equals(httpsOrigin(view.getUrl()))) {
              reply = replyProxy;
              receiveEvent(data);
            }
          });
        });
      // This WebKit version keeps document-start injection available on the legacy flavor.
      WebViewCompat.addDocumentStartJavaScript(webView, bootstrap, origins);
      updateDeviceActivity();
      webView.loadUrl(url.url);
    } catch (Exception e) {
      showError(R.string.MiniAppLoadFailed);
    }
  }

  static @Nullable String httpsOrigin (@Nullable String url) {
    if (url == null) {
      return null;
    }
    Uri uri = Uri.parse(url);
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
      return null;
    }
    return "https://" + uri.getHost().toLowerCase(java.util.Locale.US) +
      (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
  }

  private boolean navigateUrl (String url) {
    if (origin != null && origin.equals(httpsOrigin(url))) {
      return false;
    }
    Uri uri = Uri.parse(url);
    if ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()) ||
        "tg".equalsIgnoreCase(uri.getScheme())) {
      tdlib.ui().openUrl(this, url, new TdlibUi.UrlOpenParameters().controller(this).requireOpenPrompt());
    }
    return true;
  }

  private void receiveEvent (@Nullable String message) {
    if (!tdlib.isAuthorized() || minimized || tdlib.myUserId() != accountUserId || message == null || message.length() > 6 * 1024 * 1024) {
      return;
    }
    try {
      JSONObject envelope = new JSONObject(message);
      String event = envelope.getString("eventType");
      Object payload = new JSONTokener(envelope.optString("eventData", "{}")).nextValue();
      JSONObject data = payload instanceof JSONObject ? (JSONObject) payload : json();
      switch (event) {
        case "web_app_ready":
          progress.setVisibility(View.GONE);
          sendTheme();
          sendViewport();
          sendFullscreenState();
          sendVisibility();
          break;
        case "web_app_request_theme":
          sendTheme();
          break;
        case "web_app_request_viewport":
          sendViewport();
          break;
        case "web_app_expand":
          viewport.setCompact(false);
          sendViewport();
          break;
        case "web_app_toggle_orientation_lock":
          fullscreen.setOrientationLocked(data.optBoolean("locked"));
          break;
        case "web_app_setup_swipe_behavior":
          allowVerticalSwipes = data.optBoolean("allow_vertical_swipe", true);
          break;
        case "web_app_request_safe_area":
          sendSafeAreas();
          break;
        case "web_app_request_content_safe_area":
          sendSafeAreas();
          break;
        case "web_app_setup_back_button":
          appBackButton = data.optBoolean("is_visible");
          break;
        case "web_app_setup_settings_button":
          settingsButtonVisible = data.optBoolean("is_visible");
          break;
        case "web_app_setup_closing_behavior":
          confirmClosing = data.optBoolean("need_confirmation");
          break;
        case "web_app_close":
          requestClose();
          break;
        case "web_app_hide_keyboard":
          Keyboard.hide(webView);
          break;
        case "web_app_setup_main_button":
          setupButton(mainButton, data);
          break;
        case "web_app_setup_secondary_button":
          setupButton(secondaryButton, data);
          arrangeButtons(data.optString("position", "bottom"));
          break;
        case "web_app_set_background_color":
          if (validColor(data.optString("color"))) {
            webView.setBackgroundColor(Color.parseColor(data.getString("color")));
          }
          break;
        case "web_app_set_bottom_bar_color":
          if (validColor(data.optString("color"))) {
            bottomBarColor = Color.parseColor(data.getString("color"));
            buttons.setBackgroundColor(bottomBarColor);
            applyNavigationColor();
          }
          break;
        case "web_app_open_link":
          openExternalLink(data.getString("url"), data.optBoolean("try_instant_view", false), data.optString("try_browser"));
          break;
        case "web_app_open_tg_link": {
          String path = data.getString("path_full");
          if (path.startsWith("/") && !path.startsWith("//")) {
            fullscreen.setRequested(false);
            tdlib.ui().openUrl(this, "https://t.me" + path, new TdlibUi.UrlOpenParameters().controller(this));
          }
          break;
        }
        case "web_app_open_popup":
          showPopup(data);
          break;
        case "web_app_invoke_custom_method":
          invokeCustomMethod(data);
          break;
        case "web_app_device_storage_save_key":
        case "web_app_device_storage_get_key":
        case "web_app_device_storage_clear": {
          if (pendingStorageRequests >= 16) {
            sendEvent("device_storage_failed", json("req_id", data.optString("req_id"), "error", "REQUEST_LIMIT_EXCEEDED"));
            break;
          }
          pendingStorageRequests++;
          int generation = documentGeneration;
          long userId = tdlib.myUserId();
          Background.instance().post(() -> {
            MiniAppStorage.Result result = MiniAppStorage.handle(context(), userId, botUserId, event, data);
            runOnUiThreadOptional(() -> {
              pendingStorageRequests--;
              if (documentGeneration == generation) sendEvent(result.event, result.data);
            });
          });
          break;
        }
        case "web_app_request_write_access":
          requestWriteAccess();
          break;
        case "web_app_request_phone":
          requestPhone();
          break;
        case "web_app_request_file_download":
          requestDownload(data);
          break;
        case "web_app_data_send":
          sendData(data.getString("data"));
          break;
        case "web_app_trigger_haptic_feedback":
          if (isFocused() && context.getActivityState() == UI.State.RESUMED) {
            int constant = hapticConstant(MiniAppHaptic.effectFor(MiniAppHaptic.parse(data), Build.VERSION.SDK_INT));
            if (constant >= 0) {
              webView.performHapticFeedback(constant);
            }
          }
          break;
        case "web_app_request_fullscreen":
          if (!fullscreen.isSupported() || (!fullscreen.isFullscreen() && !hasRecentInteraction())) {
            sendEvent("fullscreen_failed", json("error", "UNSUPPORTED"));
          } else if (fullscreen.isFullscreen()) {
            sendEvent("fullscreen_failed", json("error", "ALREADY_FULLSCREEN"));
          } else if (!fullscreen.setRequested(true)) {
            sendEvent("fullscreen_failed", json("error", "UNSUPPORTED"));
          }
          break;
        case "web_app_exit_fullscreen":
          fullscreen.setRequested(false);
          sendFullscreenState();
          break;
        case "web_app_check_home_screen":
          deviceServices.checkHomeScreen();
          break;
        case "web_app_add_to_home_screen":
          deviceServices.addToHomeScreen();
          break;
        case "web_app_secure_storage_save_key":
          secureServices.saveSecureKey(data);
          break;
        case "web_app_secure_storage_get_key":
          secureServices.getSecureKey(data);
          break;
        case "web_app_secure_storage_restore_key":
          secureServices.restoreSecureKey(data);
          break;
        case "web_app_secure_storage_clear":
          secureServices.clearSecureStorage(data);
          break;
        case "web_app_check_location":
          deviceServices.checkLocation();
          break;
        case "web_app_request_location":
          deviceServices.requestLocation(data);
          break;
        case "web_app_open_location_settings":
          deviceServices.openLocationSettings();
          break;
        case "web_app_check_media_access":
          deviceServices.checkMediaAccess(data);
          break;
        case "web_app_request_media_access":
          deviceServices.requestMediaAccess(data);
          break;
        case "web_app_biometry_get_info":
          secureServices.getBiometryInfo();
          break;
        case "web_app_biometry_request_access":
          secureServices.requestBiometryAccess(data);
          break;
        case "web_app_biometry_request_auth":
          secureServices.requestBiometryAuth(data);
          break;
        case "web_app_biometry_update_token":
          secureServices.updateBiometryToken(data);
          break;
        case "web_app_biometry_open_settings":
          secureServices.openBiometrySettings();
          break;
        case "web_app_read_text_from_clipboard":
          deviceServices.readTextFromClipboard(data);
          break;
        case "web_app_send_prepared_message":
          sharePreparedMessage(data);
          break;
        case "web_app_request_chat":
          requestChat(data);
          break;
        case "web_app_switch_inline_query":
          switchInlineQuery(data);
          break;
        case "web_app_open_scan_qr_popup":
          deviceServices.openScanQrPopup(data);
          break;
        case "web_app_close_scan_qr_popup":
          deviceServices.closeScanQrPopup();
          break;
        case "web_app_set_header_color": {
          MiniAppHeaderColor.Value color = MiniAppHeaderColor.parse(data);
          headerColorKind = color.kind;
          headerColorValue = color.argb;
          applyHeaderColor();
          break;
        }
        case "web_app_set_emoji_status":
          setEmojiStatus(data);
          break;
        case "web_app_request_emoji_status_access":
          requestEmojiStatusAccess();
          break;
        case "web_app_open_invoice":
          openInvoice(data.optString("slug"));
          break;
        case "web_app_start_accelerometer":
          deviceServices.startSensor(MiniAppSensors.ACCELEROMETER, data);
          break;
        case "web_app_start_device_orientation":
          deviceServices.startSensor(MiniAppSensors.ORIENTATION, data);
          break;
        case "web_app_start_gyroscope":
          deviceServices.startSensor(MiniAppSensors.GYROSCOPE, data);
          break;
        case "web_app_stop_accelerometer":
          deviceServices.stopSensor(MiniAppSensors.ACCELEROMETER);
          break;
        case "web_app_stop_device_orientation":
          deviceServices.stopSensor(MiniAppSensors.ORIENTATION);
          break;
        case "web_app_stop_gyroscope":
          deviceServices.stopSensor(MiniAppSensors.GYROSCOPE);
          break;
        case "web_app_share_to_story":
          // Stories sharing is not available in this build yet.
          UI.showToast("Stories sharing is not supported yet", Toast.LENGTH_SHORT);
          break;
        case "web_app_verify_age":
          // Age verification flow is not available in this build yet.
          UI.showToast("Age verification is not supported yet", Toast.LENGTH_SHORT);
          break;
        case "resize_frame":
          // Instant View embed resize; no-op in this client.
          break;
        default:
          break;
      }
    } catch (JSONException | IllegalArgumentException e) {
      // Malformed events from the page cannot interrupt the native controller.
    }
  }

  private static boolean validColor (String color) {
    return color.matches("#[0-9a-fA-F]{6}");
  }

  /** {@code HapticFeedbackConstants} value of a {@link MiniAppHaptic} effect, or a negative value for none. */
  private static int hapticConstant (int effect) {
    switch (effect) {
      case MiniAppHaptic.EFFECT_LONG_PRESS:
        return HapticFeedbackConstants.LONG_PRESS;
      case MiniAppHaptic.EFFECT_KEYBOARD_TAP:
        return HapticFeedbackConstants.KEYBOARD_TAP;
      case MiniAppHaptic.EFFECT_CLOCK_TICK:
        return HapticFeedbackConstants.CLOCK_TICK;
      case MiniAppHaptic.EFFECT_CONTEXT_CLICK:
        return HapticFeedbackConstants.CONTEXT_CLICK;
      case MiniAppHaptic.EFFECT_TEXT_HANDLE_MOVE:
        return HapticFeedbackConstants.TEXT_HANDLE_MOVE;
      case MiniAppHaptic.EFFECT_CONFIRM:
        return HapticFeedbackConstants.CONFIRM;
      case MiniAppHaptic.EFFECT_REJECT:
        return HapticFeedbackConstants.REJECT;
      case MiniAppHaptic.EFFECT_SEGMENT_FREQUENT_TICK:
        return HapticFeedbackConstants.SEGMENT_FREQUENT_TICK;
      default:
        return -1;
    }
  }

  private void setupButton (MiniAppButtonView button, JSONObject data) {
    MiniAppButtonParams.Value params = MiniAppButtonParams.parse(data);
    button.setParams(params);
    button.setEnabled(MiniAppButtonParams.isClickable(params));
    button.setVisibility(params.visible ? View.VISIBLE : View.GONE);
    button.setBackgroundColor(params.backgroundColor != 0 ? params.backgroundColor : Theme.getColor(ColorId.fillingPositive));
  }

  private void arrangeButtons (String position) {
    boolean horizontal = "left".equals(position) || "right".equals(position);
    buttons.removeAllViews();
    buttons.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
    mainButton.setLayoutParams(new LinearLayout.LayoutParams(horizontal ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(52), horizontal ? 1 : 0));
    secondaryButton.setLayoutParams(new LinearLayout.LayoutParams(horizontal ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(52), horizontal ? 1 : 0));
    boolean secondaryFirst = "left".equals(position) || "top".equals(position);
    buttons.addView(secondaryFirst ? secondaryButton : mainButton);
    buttons.addView(secondaryFirst ? mainButton : secondaryButton);
  }

  private void openExternalLink (String url, boolean tryInstantView, String tryBrowser) {
    Uri uri = Uri.parse(url);
    if (!hasRecentInteraction() || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
      return;
    }
    fullscreen.setRequested(false);
    String browser = MiniAppBrowser.packageName(tryBrowser);
    if (browser != null) {
      if (tdlib.isTrustedHost(url, true)) {
        openInBrowser(url, browser);
      } else {
        confirmOpen(url, () -> openInBrowser(url, browser));
      }
    } else if (!tryInstantView || Settings.instance().getInstantViewMode() == Settings.INSTANT_VIEW_MODE_NONE ||
        tdlib.isTrustedHost(url, true)) {
      openInDefaultBrowser(url);
    } else {
      // Keeping the reader inside the app is a bigger concession than handing the link over, so a host the
      // user has not already trusted is asked about first, with the wording the link prompt uses.
      confirmOpen(url, () -> tdlib.ui().openUrl(this, url,
        new TdlibUi.UrlOpenParameters().controller(this).forceInstantView()));
    }
  }

  private void confirmOpen (String url, Runnable onOpen) {
    showAlert(new AlertDialog.Builder(context(), Theme.dialogTheme())
      .setMessage(Lang.getString(R.string.OpenThisLink, url))
      .setPositiveButton(Lang.getString(R.string.Open), (dialog, which) -> onOpen.run())
      .setNegativeButton(Lang.getString(R.string.Cancel), null));
  }

  private void openInBrowser (String url, String packageName) {
    if (!MiniAppBrowser.open(this, url, packageName)) {
      openInDefaultBrowser(url);
    }
  }

  private void openInDefaultBrowser (String url) {
    tdlib.ui().openUrl(this, url, new TdlibUi.UrlOpenParameters().controller(this).requireOpenPrompt());
  }

  private boolean hasRecentInteraction () {
    return isFocused() && context.getActivityState() == UI.State.RESUMED && lastInteractionTime != 0 &&
      SystemClock.elapsedRealtime() - lastInteractionTime < 10000;
  }

  /** A header color picked by the page also picks the color of everything drawn on top of it. */
  @Override
  protected int getHeaderTextColorId () {
    return headerLightContent ? ColorId.white : super.getHeaderTextColorId();
  }

  @Override
  protected int getHeaderIconColorId () {
    return headerLightContent ? ColorId.white : super.getHeaderIconColorId();
  }

  private void applyHeaderColor () {
    TdApi.ThemeParameters params = themeParameters();
    int color = MiniAppHeaderColor.resolve(headerColorKind, headerColorValue, params.backgroundColor, params.secondaryBackgroundColor);
    headerLightContent = color != 0 && MiniAppHeaderColor.usesLightContent(color);
    // The header belongs to whoever is on screen: a page repainting it from the background would color the
    // controls of another controller, so the state is kept and applied when this one comes back.
    if (!isFocused() || headerView == null) return;
    headerView.setCustomBackgroundColor(color);
    headerView.resetColors(this, null);
    if (header != null) {
      header.setTextColor(Theme.getColor(getHeaderTextColorId()));
    }
    if (headerMoreButton != null) {
      headerMoreButton.setThemeColorId(getHeaderIconColorId());
    }
  }

  /**
   * The system navigation bar continues the bottom bar the page painted, so it takes the same color and its
   * icons switch to whatever that color needs. Leaving the screen drops it: the bar belongs to whoever is on
   * top, and a page setting a color from the background would tint the controller that follows it.
   * <p>
   * The activity holds a single slot for it, shared by every Mini App, and the lifecycle callbacks reach
   * controllers that are not on screen, so only the controller that painted the bar may hand it back.
   */
  private void applyNavigationColor () {
    boolean onScreen = isFocused() && !minimized && context.getActivityState() == UI.State.RESUMED;
    int color = onScreen ? bottomBarColor : 0;
    if (color == 0 && !navigationColorOwned) return;
    navigationColorOwned = color != 0;
    context().setMiniAppNavigationColor(color, color != 0 && MiniAppHeaderColor.usesLightContent(color));
  }

  private void setEmojiStatus (JSONObject data) {
    MiniAppEmojiStatus.Prepared prepared = MiniAppEmojiStatus.prepare(data, System.currentTimeMillis() / 1000L);
    if (!prepared.isValid()) {
      sendEvent("emoji_status_failed", json("error", prepared.error));
      return;
    }
    if (!tdlib.hasPremium()) {
      sendEvent("emoji_status_failed", json("error", "UNSUPPORTED"));
      return;
    }
    int generation = documentGeneration;
    tdlib.send(prepared.request, (result, error) -> runOnUiThreadOptional(() -> {
      if (isDestroyed() || documentGeneration != generation) return;
      sendEvent(error != null ? "emoji_status_failed" : "emoji_status_set", error != null ? json("error", "SERVER_ERROR") : json());
    }));
  }

  private void requestEmojiStatusAccess () {
    if (tdlib.hasPremium()) {
      sendEvent("emoji_status_access_requested", json("status", "allowed"));
    } else {
      pendingEmojiAccess = true;
      tdlib.ui().openPremiumSettings(this, new TdApi.PremiumSourceFeature(new TdApi.PremiumFeatureEmojiStatus()));
    }
  }

  /** The answer goes out when the user comes back, because subscribing to Premium may have just happened. */
  private void reportEmojiAccess () {
    if (pendingEmojiAccess && isFocused() && !minimized) {
      pendingEmojiAccess = false;
      sendEvent("emoji_status_access_requested", json("status", MiniAppEmojiStatus.accessStatus(tdlib.hasPremium())));
    }
  }

  private void updateDeviceActivity () {
    if (deviceServices == null) return;
    boolean active = isFocused() && !minimized && context.getActivityState() == UI.State.RESUMED;
    deviceServices.setActive(active);
    // Coming back from the system settings may have changed a permission without the page asking again
    if (active) {
      deviceServices.onMediaStateChanged();
    }
  }

  // MiniAppDeviceServices.Gateway

  @Override
  public void publish (String event, JSONObject data) {
    sendEvent(event, data);
  }

  @Override
  public boolean isReadyForDeviceAccess () {
    return isFocused() && !minimized && context.getActivityState() == UI.State.RESUMED && hasRecentInteraction();
  }

  @Override
  public int nextRunToken () {
    return ++deviceRunToken;
  }

  @Override
  public AlertDialog showSecureDialog (AlertDialog.Builder builder) {
    return showAlert(builder);
  }

  @Override
  public @Nullable String homeScreenLink () {
    if (!sourceUrl.isEmpty()) {
      return httpsOrigin(sourceUrl) != null ? sourceUrl : null;
    }
    String username = shortName != null ? Td.primaryUsername(tdlib.cache().user(botUserId)) : null;
    if (StringUtils.isEmpty(username)) return null;
    String parameter = startParameter.matches("[A-Za-z0-9_-]{0,512}") ? startParameter : "";
    return "https://" + tdlib.tMeHost() + "/" + username + "/" + shortName +
      (parameter.isEmpty() ? "" : "?startapp=" + parameter);
  }

  @Override
  public CharSequence homeScreenTitle () {
    return tabTitle();
  }

  @Override
  public String homeScreenId () {
    return "miniapp_" + accountUserId + "_" + botUserId + (shortName != null ? "_" + shortName : "");
  }

  private void switchInlineQuery (JSONObject data) throws JSONException {
    TdApi.User user = tdlib.cache().user(botUserId);
    if (!hasRecentInteraction() || user == null || !(user.type instanceof TdApi.UserTypeBot bot) ||
        !bot.isInline || !Td.hasUsername(user)) return;
    String query = data.optString("query");
    if (query.length() > 256) return;
    JSONArray chatTypes = data.optJSONArray("chat_types");
    java.util.Set<String> types = new java.util.HashSet<>();
    if (chatTypes != null) {
      for (int i = 0; i < chatTypes.length(); i++) types.add(chatTypes.getString(i));
    }
    org.thunderdog.challegram.data.TGSwitchInline item =
      new org.thunderdog.challegram.data.TGSwitchInline(Td.primaryUsername(user), query);
    fullscreen.setRequested(false);
    if (types.isEmpty() && chatId != 0 && tdlib.canSendBasicMessage(chatId)) {
      tdlib.ui().openChat(this, chatId, new TdlibUi.ChatOpenParameters().keepStack().messageTopic(topicId).shareItem(item));
      return;
    }
    TdApi.TargetChatTypes allowed = new TdApi.TargetChatTypes(types.isEmpty() || types.contains("users"),
      types.isEmpty() || types.contains("bots"), types.isEmpty() || types.contains("groups"), types.isEmpty() || types.contains("channels"));
    ChatsController picker = new ChatsController(context(), tdlib);
    picker.setArguments(new ChatsController.Arguments(
      chat -> org.thunderdog.challegram.util.MiniAppChatPolicy.allows(tdlib, chat, allowed) && tdlib.canSendBasicMessage(chat),
      new ChatsController.PickerDelegate() {
        @Override
        public boolean onChatPicked (TdApi.Chat chat, Runnable onDone) { return tdlib.canSendBasicMessage(chat); }
        @Override
        public Object getShareItem () { return item; }
        @Override
        public void modifyChatOpenParams (TdlibUi.ChatOpenParameters params) { params.keepStack(); }
      }));
    navigateTo(picker);
  }

  private void sharePreparedMessage (JSONObject data) throws JSONException {
    if (preparingMessage || preparedResultPending || !hasRecentInteraction()) {
      sendEvent("prepared_message_failed", json("error", "USER_DECLINED"));
      return;
    }
    String id = data.getString("id");
    if (id.isEmpty() || id.length() > 512) {
      sendEvent("prepared_message_failed", json("error", "MESSAGE_EXPIRED"));
      return;
    }
    preparingMessage = true;
    int generation = documentGeneration;
    tdlib.send(new TdApi.GetPreparedInlineMessage(botUserId, id), (message, error) -> runOnUiThreadOptional(() -> {
      if (generation != documentGeneration) return;
      if (error != null || message == null || message.result == null || message.chatTypes == null) {
        preparingMessage = false;
        sendEvent("prepared_message_failed", json("error", "MESSAGE_EXPIRED"));
        return;
      }
      if (!isFocused()) {
        preparingMessage = false;
        sendEvent("prepared_message_failed", json("error", "USER_DECLINED"));
        return;
      }
      MiniAppPreparedMessageController picker = new MiniAppPreparedMessageController(context(), tdlib, message, failure -> {
        if (isDestroyed() || generation != documentGeneration) return;
        preparingMessage = false;
        preparedResultPending = true;
        preparedGeneration = generation;
        preparedError = failure;
        reportPreparedResult();
      });
      fullscreen.setActive(false);
      if (!navigateTo(picker)) {
        picker.destroy();
        updateFullscreenActivity();
      }
    }));
  }

  private void requestChat (JSONObject data) {
    String id = data.optString("req_id");
    if (requestingChat || !hasRecentInteraction()) {
      sendEvent("requested_chat_failed", json("error", "USER_DECLINED"));
      return;
    }
    if (id.isEmpty() || id.length() > 512) {
      sendEvent("requested_chat_failed", json("error", "BUTTON_EXPIRED"));
      return;
    }
    requestingChat = true;
    int generation = documentGeneration;
    // TDLib remembers the restrictions of the last button it fetched in a single slot, so the fetched ids and
    // the ids handed to shareChatWithBot have to be the same ones and nothing else may interleave.
    tdlib.send(new TdApi.GetPreparedKeyboardButton(botUserId, id), (button, error) -> runOnUiThreadOptional(() -> {
      if (generation != documentGeneration) return;
      MiniAppRequestedChat spec = error == null ? MiniAppRequestedChat.from(button) : null;
      if (spec == null) {
        requestingChat = false;
        sendEvent("requested_chat_failed", json("error", "BUTTON_EXPIRED"));
        return;
      }
      if (minimized || !isFocused()) {
        requestingChat = false;
        sendEvent("requested_chat_failed", json("error", "USER_DECLINED"));
        return;
      }
      MiniAppRequestedChatController picker = new MiniAppRequestedChatController(context(), tdlib, botUserId, id, spec,
        failure -> {
          if (isDestroyed() || generation != documentGeneration) return;
          requestingChat = false;
          if (failure == null) sendEvent("requested_chat_sent", json());
          else sendEvent("requested_chat_failed", json("error", failure));
        });
      fullscreen.setActive(false);
      if (!navigateTo(picker)) {
        picker.destroy();
        requestingChat = false;
        sendEvent("requested_chat_failed", json("error", "UNSUPPORTED"));
        updateFullscreenActivity();
      }
    }));
  }

  private void reportPreparedResult () {
    if (preparedResultPending && preparedGeneration == documentGeneration && isFocused()) {
      preparedResultPending = false;
      if (preparedError == null) sendEvent("prepared_message_sent", json());
      else sendEvent("prepared_message_failed", json("error", preparedError));
    }
  }

  private void requestDownload (JSONObject data) throws JSONException {
    String url = data.getString("url");
    String name = data.getString("file_name");
    if (requestingDownload || popup != null || !hasRecentInteraction() || httpsOrigin(url) == null ||
        name.isEmpty() || name.length() > 240 || name.contains("/") || name.contains("\\") ||
        name.matches(".*[\\x00-\\x1f].*") || ".".equals(name) || "..".equals(name)) {
      sendEvent("file_download_requested", json("status", "cancelled"));
      return;
    }
    requestingDownload = true;
    int generation = documentGeneration;
    tdlib.send(new TdApi.CheckWebAppFileDownload(botUserId, name, url), (ok, error) -> runOnUiThreadOptional(() -> {
      if (generation != documentGeneration || !isFocused() || popup != null) {
        requestingDownload = false;
        if (generation == documentGeneration) sendEvent("file_download_requested", json("status", "cancelled"));
        return;
      }
      if (error != null) {
        requestingDownload = false;
        sendEvent("file_download_requested", json("status", "cancelled"));
        return;
      }
      boolean[] confirmed = {false};
      popup = showAlert(new AlertDialog.Builder(context(), Theme.dialogTheme())
        .setMessage(Lang.getString(R.string.MiniAppDownloadConfirm, name))
        .setPositiveButton(Lang.getString(R.string.MiniAppDownload), (dialog, which) -> {
          confirmed[0] = true;
          Runnable download = () -> enqueueDownload(generation, url, name);
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
              context().permissions().requestWriteExternalStorage(Permissions.WriteType.DOWNLOADS, granted -> {
                if (granted) {
                  download.run();
                } else {
                  requestingDownload = false;
                  if (generation == documentGeneration) sendEvent("file_download_requested", json("status", "cancelled"));
                }
              })) {
            return;
          }
          download.run();
        })
        .setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> {}));
      popup.setOnDismissListener(dialog -> {
        if (popup == dialog) popup = null;
        if (!confirmed[0]) {
          requestingDownload = false;
          if (generation == documentGeneration) sendEvent("file_download_requested", json("status", "cancelled"));
        }
      });
    }));
  }

  private void enqueueDownload (int generation, String url, String name) {
    requestingDownload = false;
    if (isDestroyed() || generation != documentGeneration) {
      return;
    }
    try {
      DownloadManager manager = (DownloadManager) context().getSystemService(Context.DOWNLOAD_SERVICE);
      if (manager == null) throw new IllegalStateException("Download service unavailable");
      DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
        .setTitle(name)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
      manager.enqueue(request);
      sendEvent("file_download_requested", json("status", "downloading"));
    } catch (RuntimeException e) {
      sendEvent("file_download_requested", json("status", "cancelled"));
    }
  }

  private void showPopup (JSONObject data) throws JSONException {
    if (popup != null) {
      // Only the page's own popup may stay unanswered: its reply is already queued on the dismiss listener.
      // Any other dialog holds the single slot without ever answering for a popup, so the page would be left
      // waiting on one that is never shown.
      if (!popupIsPage) sendEvent("popup_closed", json());
      return;
    }
    if (!isFocused()) {
      sendEvent("popup_closed", json());
      return;
    }
    JSONArray options = data.getJSONArray("buttons");
    String title = data.optString("title");
    String message = data.getString("message");
    if (options.length() < 1 || options.length() > 3 || title.length() > 64 || message.length() > 256) {
      sendEvent("popup_closed", json());
      return;
    }
    AlertDialog.Builder builder = new AlertDialog.Builder(context(), Theme.dialogTheme()).setTitle(title).setMessage(message);
    String[] chosen = {null};
    for (int i = 0; i < options.length(); i++) {
      JSONObject option = options.getJSONObject(i);
      String id = option.optString("id");
      String type = option.optString("type", "default");
      String label = "ok".equals(type) ? Lang.getString(R.string.OK) :
        "cancel".equals(type) ? Lang.getString(R.string.Cancel) :
        "close".equals(type) ? Lang.getString(R.string.MiniAppClose) : option.optString("text");
      if (id.length() > 64 || label.isEmpty() || label.length() > 64) {
        sendEvent("popup_closed", json());
        return;
      }
      android.content.DialogInterface.OnClickListener listener = (dialog, which) -> chosen[0] = id;
      if (i == 0) builder.setPositiveButton(label, listener);
      else if (i == 1) builder.setNegativeButton(label, listener);
      else builder.setNeutralButton(label, listener);
    }
    int generation = documentGeneration;
    popup = showAlert(builder);
    popupIsPage = true;
    popup.setOnDismissListener(dialog -> {
      if (popup == dialog) {
        popup = null;
        popupIsPage = false;
      }
      if (documentGeneration == generation) {
        sendEvent("popup_closed", chosen[0] != null ? json("button_id", chosen[0]) : json());
      }
    });
  }

  private void invokeCustomMethod (JSONObject data) throws JSONException {
    String requestId = data.getString("req_id");
    String method = data.getString("method");
    JSONObject parameters = data.getJSONObject("params");
    // The SDK polls this one on its own for about three seconds after a shared phone number, ten requests at a
    // time. It still goes to the bot, but the page must not lose its own budget because of a loop it did not start.
    boolean isSdkContactPoll = "getRequestedContact".equals(method);
    if (requestId.length() > 64 || method.length() > 128 || (!isSdkContactPoll && pendingCustomRequests >= 8)) {
      sendEvent("custom_method_invoked", json("req_id", requestId, "error", "REQUEST_LIMIT_EXCEEDED"));
      return;
    }
    int generation = documentGeneration;
    if (!isSdkContactPoll) {
      pendingCustomRequests++;
    }
    tdlib.send(new TdApi.SendWebAppCustomRequest(botUserId, method, parameters.toString()), (result, error) -> runOnUiThreadOptional(() -> {
      if (generation != documentGeneration) {
        return;
      }
      if (!isSdkContactPoll) {
        pendingCustomRequests--;
      }
      if (error != null) {
        sendEvent("custom_method_invoked", json("req_id", requestId, "error", error.message));
      } else {
        try {
          sendEvent("custom_method_invoked", json("req_id", requestId, "result", new JSONTokener(result.result).nextValue()));
        } catch (JSONException e) {
          sendEvent("custom_method_invoked", json("req_id", requestId, "error", "INVALID_RESPONSE"));
        }
      }
    }));
  }

  private boolean requestingWriteAccess;
  private boolean requestingPhone;

  private void requestPhone () {
    if (requestingPhone) return;
    if (popup != null || !isFocused()) {
      sendEvent("phone_requested", json("status", "cancelled"));
      return;
    }
    TdApi.User self = tdlib.cache().user(tdlib.myUserId());
    if (self == null || self.phoneNumber == null || self.phoneNumber.isEmpty()) {
      sendEvent("phone_requested", json("status", "cancelled"));
      return;
    }
    requestingPhone = true;
    int generation = documentGeneration;
    boolean[] confirmed = {false};
    popup = showAlert(new AlertDialog.Builder(context(), Theme.dialogTheme())
      .setTitle(Lang.getString(R.string.ShareYourPhoneNumberTitle))
      .setMessage(Lang.getString(R.string.ShareYourPhoneNumberDesc, tdlib.cache().userName(botUserId)))
      .setPositiveButton(Lang.getString(R.string.OK), (dialog, which) -> {
        confirmed[0] = true;
        TdApi.InputMessageContact contact = new TdApi.InputMessageContact(
          new TdApi.Contact(self.phoneNumber, self.firstName, self.lastName, "", self.id));
        tdlib.send(new TdApi.CreatePrivateChat(botUserId, false), (chat, error) -> runOnUiThreadOptional(() -> {
          if (generation != documentGeneration) return;
          if (tdlib.myUserId() != self.id) {
            requestingPhone = false;
            sendEvent("phone_requested", json("status", "cancelled"));
            return;
          }
          if (error != null) {
            onPhoneSent(generation, error);
          } else {
            tdlib.send(new TdApi.SendMessage(chat.id, null, null, Td.newSendOptions(), null, contact),
              (message, sendError) -> runOnUiThreadOptional(() -> onPhoneSent(generation, sendError)));
          }
        }));
      })
      .setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> {}));
    popup.setOnDismissListener(dialog -> {
      if (popup == dialog) popup = null;
      if (!confirmed[0]) {
        requestingPhone = false;
        if (generation == documentGeneration) sendEvent("phone_requested", json("status", "cancelled"));
      }
    });
  }

  private void onPhoneSent (int generation, @Nullable TdApi.Error error) {
    requestingPhone = false;
    if (generation == documentGeneration) {
      sendEvent("phone_requested", json("status", error == null ? "sent" : "cancelled"));
      if (error != null) UI.showError(error);
    }
  }

  private void requestWriteAccess () {
    if (requestingWriteAccess) return;
    if (!isFocused() || popup != null) {
      sendEvent("write_access_requested", json("status", "cancelled"));
      return;
    }
    requestingWriteAccess = true;
    int generation = documentGeneration;
    tdlib.send(new TdApi.CanBotSendMessages(botUserId), (ok, error) -> runOnUiThreadOptional(() -> {
      if (generation != documentGeneration || !isFocused() || popup != null) {
        requestingWriteAccess = false;
        if (generation == documentGeneration) sendEvent("write_access_requested", json("status", "cancelled"));
        return;
      }
      if (error == null) {
        requestingWriteAccess = false;
        sendEvent("write_access_requested", json("status", "allowed"));
        return;
      }
      boolean[] allowed = {false};
      popup = showAlert(new AlertDialog.Builder(context(), Theme.dialogTheme())
        .setMessage(Lang.getString(R.string.MiniAppWriteAccess, tdlib.cache().userName(botUserId)))
        .setPositiveButton(Lang.getString(R.string.Allow), (dialog, which) -> {
          allowed[0] = true;
          tdlib.send(new TdApi.AllowBotToSendMessages(botUserId), (result, failure) -> runOnUiThreadOptional(() -> {
            requestingWriteAccess = false;
            if (generation == documentGeneration) {
              sendEvent("write_access_requested", json("status", failure == null ? "allowed" : "cancelled"));
            }
          }));
        })
        .setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> {}));
      popup.setOnDismissListener(dialog -> {
        if (popup == dialog) popup = null;
        if (!allowed[0]) {
          requestingWriteAccess = false;
          if (generation == documentGeneration) {
            sendEvent("write_access_requested", json("status", "cancelled"));
          }
        }
      });
    }));
  }

  private void sendData (String data) {
    if (data.isEmpty() || data.getBytes(StringUtils.UTF_8).length > 4096) {
      return;
    }
    if (dataSending) {
      return;
    }
    // `web_app_data_send` is one-way: the shim throws on a bad payload and posts the event otherwise, so it
    // never waits for a reply and a rejection the user cannot see is a button that does nothing.
    if (keyboardButtonText == null) {
      UI.showToast(R.string.MiniAppDataSendUnavailable, android.widget.Toast.LENGTH_SHORT);
      return;
    }
    if (messageSent) {
      UI.showToast(R.string.MiniAppDataSendAlready, android.widget.Toast.LENGTH_SHORT);
      return;
    }
    dataSending = true;
    tdlib.send(new TdApi.SendWebAppData(botUserId, keyboardButtonText, data), (ok, error) -> runOnUiThreadOptional(() -> {
      dataSending = false;
      if (error != null) {
        UI.showError(error);
      } else {
        onMessageSent();
      }
    }));
  }

  private void onMessageSent () {
    messageSent = true;
    if (minimized) {
      context().miniAppTabs().discard(this);
      return;
    }
    if (isFocused()) {
      navigateBack();
    }
  }

  private static TdApi.ThemeParameters themeParameters () {
    return new TdApi.ThemeParameters(Theme.fillingColor() & 0xffffff, Theme.getColor(ColorId.background) & 0xffffff,
      Theme.getColor(ColorId.headerBackground) & 0xffffff, Theme.fillingColor() & 0xffffff, Theme.fillingColor() & 0xffffff,
      Theme.getColor(ColorId.separator) & 0xffffff, Theme.textAccentColor() & 0xffffff, Theme.getColor(ColorId.textNeutral) & 0xffffff,
      Theme.getColor(ColorId.textNeutral) & 0xffffff, Theme.getColor(ColorId.textLight) & 0xffffff, Theme.getColor(ColorId.textNegative) & 0xffffff,
      Theme.getColor(ColorId.textPlaceholder) & 0xffffff, Theme.getColor(ColorId.textLink) & 0xffffff,
      Theme.getColor(ColorId.fillingPositive) & 0xffffff, Theme.getColor(ColorId.fillingPositiveContent) & 0xffffff);
  }

  private void sendTheme () {
    TdApi.ThemeParameters t = themeParameters();
    sendEvent("theme_changed", json("theme_params", json(
      "bg_color", color(t.backgroundColor), "secondary_bg_color", color(t.secondaryBackgroundColor),
      "header_bg_color", color(t.headerBackgroundColor), "bottom_bar_bg_color", color(t.bottomBarBackgroundColor),
      "section_bg_color", color(t.sectionBackgroundColor), "section_separator_color", color(t.sectionSeparatorColor),
      "text_color", color(t.textColor), "accent_text_color", color(t.accentTextColor),
      "section_header_text_color", color(t.sectionHeaderTextColor), "subtitle_text_color", color(t.subtitleTextColor),
      "destructive_text_color", color(t.destructiveTextColor), "hint_color", color(t.hintColor),
      "link_color", color(t.linkColor), "button_color", color(t.buttonColor), "button_text_color", color(t.buttonTextColor))));
  }

  private static String color (int value) {
    return String.format(java.util.Locale.US, "#%06x", value & 0xffffff);
  }

  private void sendViewport () {
    if (webView != null) {
      sendEvent("viewport_changed", json("height", webView.getHeight() / Screen.density(), "is_state_stable", true,
        "is_expanded", fullscreen.isFullscreen() || !viewport.isCompact()));
      sendSafeAreas();
    }
  }

  private void sendSafeAreas () {
    if (webView == null || fullscreen == null) return;
    Insets area = fullscreen.safeArea(webView);
    float scale = Screen.density();
    sendEvent("safe_area_changed", json("top", area.top / scale, "bottom", area.bottom / scale,
      "left", area.left / scale, "right", area.right / scale));
    sendEvent("content_safe_area_changed", json("top", Math.max(area.top, fullscreen.contentTop(webView)) / scale,
      "bottom", area.bottom / scale, "left", area.left / scale, "right", area.right / scale));
  }

  private void sendFullscreenState () {
    if (fullscreen != null) {
      sendEvent("fullscreen_changed", json("is_fullscreen", fullscreen.isFullscreen()));
      sendViewport();
    }
  }

  private void updateFullscreenActivity () {
    if (fullscreen != null) fullscreen.setActive(isFocused() && context.getActivityState() == UI.State.RESUMED && !isDestroyed());
  }

  private void sendEvent (String event, JSONObject data) {
    if (!isDestroyed() && tdlib.myUserId() == accountUserId && reply != null) {
      reply.postMessage(json("eventType", event, "eventData", data).toString());
    }
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

  private void requestClose () {
    if (openingInvoice) return;
    if (fullscreen != null) fullscreen.setRequested(false);
    if (confirmClosing && !messageSent) {
      showConfirm(Lang.getString(R.string.MiniAppCloseConfirm), Lang.getString(R.string.MiniAppClose), this::navigateBack);
    } else {
      navigateBack();
    }
  }

  @Override
  public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (commit) {
      if (appBackButton) {
        sendEvent("back_button_pressed", json());
      } else {
        requestClose();
      }
    }
    return true;
  }

  @Override
  public void onThemeChanged (ThemeDelegate from, ThemeDelegate to) {
    super.onThemeChanged(from, to);
    sendTheme();
    applyHeaderColor();
  }

  @Override
  protected void onFocusStateChanged () {
    super.onFocusStateChanged();
    if (isFocused()) {
      applyHeaderColor();
    } else if (headerView != null) {
      // The header is shared by the whole stack: leaving this color behind would repaint whatever comes next.
      headerView.setCustomBackgroundColor(0);
    }
    applyNavigationColor();
    updateFullscreenActivity();
    updateDeviceActivity();
    reportPreparedResult();
    reportInvoiceResult();
    reportEmojiAccess();
    if (webView != null) {
      if (isFocused() && context.getActivityState() == UI.State.RESUMED) webView.onResume();
      else webView.onPause();
    }
    if (messageSent && isFocused()) {
      UI.post(this::navigateBack);
    } else {
      sendVisibility();
    }
  }

  @Override
  public void onActivityPause () {
    super.onActivityPause();
    if (fullscreen != null) fullscreen.setActive(false);
    if (deviceServices != null) deviceServices.setActive(false);
    applyNavigationColor();
    if (webView != null) {
      webView.onPause();
      sendEvent("visibility_changed", json("is_visible", false));
    }
  }

  @Override
  public void onActivityResume () {
    super.onActivityResume();
    updateFullscreenActivity();
    updateDeviceActivity();
    applyNavigationColor();
    if (webView != null) {
      if (isFocused()) webView.onResume();
      sendVisibility();
    }
  }

  @Override
  public void destroy () {
    if (isDestroyed()) return;
    tdlib.listeners().removeAuthorizationChangeListener(this);
    documentGeneration++;
    reply = null;
    if (navigationColorOwned) {
      navigationColorOwned = false;
      context().setMiniAppNavigationColor(0, false);
    }
    if (fullscreen != null) fullscreen.destroy();
    if (deviceServices != null) {
      deviceServices.destroy();
      deviceServices = null;
    }
    if (secureServices != null) {
      secureServices.destroy();
      secureServices = null;
    }
    cancelFileChooser();
    if (headerView != null) headerView.setCustomBackgroundColor(0);
    if (mainButton != null) mainButton.performDestroy();
    if (secondaryButton != null) secondaryButton.performDestroy();
    if (popup != null) popup.dismiss();
    if (launchId != 0) {
      tdlib.ui().unregisterWebApp(launchId);
      tdlib.send(new TdApi.CloseWebApp(launchId), tdlib.typedOkHandler());
      launchId = 0;
    }
    if (webView != null) {
      webView.stopLoading();
      if (webView.getParent() instanceof ViewGroup parent) parent.removeView(webView);
      webView.destroy();
      webView = null;
    }
    super.destroy();
  }

  private void minimize () {
    minimizeForTabSwitch(null);
  }

  boolean minimizeForTabSwitch (@Nullable Runnable afterParking) {
    if (!canParkTab()) return false;
    prepareTabExchange();
    if (!context().miniAppTabs().minimize(this, afterParking)) { minimized = false; updateDeviceActivity(); applyNavigationColor(); return false; }
    return true;
  }

  boolean canParkTab () {
    return isFocused() && !messageSent && !dataSending && !openingInvoice && !preparingMessage && fileChooser == null && (popup == null || !popup.isShowing());
  }

  void prepareTabExchange () {
    Keyboard.hide(webView);
    if (fullscreen != null) fullscreen.setRequested(false);
    minimized = true;
    updateDeviceActivity();
    applyNavigationColor();
  }

  @Nullable Bitmap captureTabPreview () {
    if (webView == null || webView.getWidth() <= 0 || webView.getHeight() <= 0 ||
        (context().getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0) return null;
    try {
      int width = Math.min(320, webView.getWidth());
      int height = Math.max(1, Math.min(180, Math.round(webView.getHeight() * (float) width / webView.getWidth())));
      Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
      android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
      canvas.scale((float) width / webView.getWidth(), (float) width / webView.getWidth());
      webView.draw(canvas);
      return bitmap;
    } catch (RuntimeException | OutOfMemoryError ignored) { return null; }
  }

  private void sendVisibility () {
    sendEvent("visibility_changed", json("is_visible", isFocused() && !minimized && context.getActivityState() == UI.State.RESUMED));
  }

  void onMinimized () {
    minimized = true;
    updateDeviceActivity();
    applyNavigationColor();
    if (webView != null) webView.onPause();
  }

  void onRestored () {
    minimized = false;
    lastInteractionTime = 0;
    updateDeviceActivity();
    applyNavigationColor();
  }

  boolean canRestoreTab () {
    return !isDestroyed() && !messageSent && tdlib.isAuthorized() && tdlib.myUserId() == accountUserId;
  }

  @Override
  public void onAuthorizationStateChanged (TdApi.AuthorizationState state) {
    if (state instanceof TdApi.AuthorizationStateLoggingOut) {
      MiniAppSecureServices.wipeAccount(accountUserId);
    }
    if (!(state instanceof TdApi.AuthorizationStateReady)) UI.post(() -> {
      if (!isDestroyed()) {
        if (minimized) context().miniAppTabs().discard(this);
        else destroy();
      }
    });
  }

  boolean needsCloseConfirmation () { return confirmClosing && !messageSent; }

  MiniAppTabSnapshot snapshot () {
    int topicKind = topicId instanceof TdApi.MessageTopicThread ? 1 : topicId instanceof TdApi.MessageTopicForum ? 2 :
      topicId instanceof TdApi.MessageTopicDirectMessages ? 3 : topicId instanceof TdApi.MessageTopicSavedMessages ? 4 : 0;
    long topic = topicId instanceof TdApi.MessageTopicThread thread ? thread.messageThreadId : topicId instanceof TdApi.MessageTopicForum forum ? forum.forumTopicId :
      topicId instanceof TdApi.MessageTopicDirectMessages direct ? direct.directMessagesChatTopicId : topicId instanceof TdApi.MessageTopicSavedMessages saved ? saved.savedMessagesTopicId : 0;
    int kind = shortName != null ? 1 : sourceUrl.isEmpty() ? attachmentLaunch ? 2 : 0 : 3;
    String title = tabTitle();
    return new MiniAppTabSnapshot(botUserId, chatId, kind, shortName == null ? "" : shortName,
      startParameter.matches("[A-Za-z0-9_-]{0,512}") ? startParameter : "", title.substring(0, Math.min(256, title.length())), topicKind, topic, viewport.isCompact());
  }

  static boolean reopenTab (ViewController<?> owner, MiniAppTabSnapshot tab) {
    return reopenTab(owner, tab, null);
  }

  static boolean reopenTab (ViewController<?> owner, MiniAppTabSnapshot tab, @Nullable Navigator navigator) {
    TdApi.User bot = owner.tdlib().cache().user(tab.botId);
    if (!owner.isFocused() || owner.isDestroyed() || !owner.tdlib().isAuthorized() || bot == null || !(bot.type instanceof TdApi.UserTypeBot) ||
        ChatId.isSecret(tab.chatId) || Td.hasRestriction(bot.restrictionInfo, Settings.instance().needRestrictContent())) return false;
    if (tab.kind == 3) { owner.tdlib().ui().openPrivateProfile(owner, tab.botId, null); return true; }
    if (tab.kind == 2) { MiniAppAttachments.open(owner, tab.botId, tab.chatId, "", tab.topic(), null, navigator); return true; }
    MiniAppController app = new MiniAppController(owner.context(), owner.tdlib(), tab.botId, tab.chatId, "", tab.kind == 1 ? tab.name : null,
      tab.parameter, null, tab.topic(), null, tab.compact ? new TdApi.WebAppOpenModeCompact() : new TdApi.WebAppOpenModeFullSize());
    if (!(navigator != null ? navigator.navigate(app) : owner.navigateTo(app))) { app.destroy(); return false; }
    return true;
  }

  String tabTitle () { return tdlib.cache().userName(botUserId) + (shortName != null ? " / " + shortName : ""); }

  private void openInvoice (String slug) {
    if (openingInvoice || !isFocused() || !MiniAppInvoiceState.validSlug(slug)) {
      sendEvent("invoice_closed", json("slug", slug, "status", "failed"));
      return;
    }
    openingInvoice = true;
    invoiceGeneration = documentGeneration;
    final int generation = documentGeneration;
    invoiceSlug = slug;
    fullscreen.setRequested(false);
    MiniAppPaymentController checkout = new MiniAppPaymentController(context(), tdlib, slug, status -> {
      if (!isDestroyed() && generation == documentGeneration && invoiceGeneration == generation && slug.equals(invoiceSlug)) {
        invoiceStatus = status;
        invoiceResultPending = true;
        reportInvoiceResult();
      }
    });
    if (!navigationController().navigateTo(checkout)) {
      checkout.destroy();
      openingInvoice = false;
      sendEvent("invoice_closed", json("slug", slug, "status", "failed"));
    }
  }

  private void reportInvoiceResult () {
    if (invoiceResultPending && isFocused()) {
      invoiceResultPending = openingInvoice = false;
      sendEvent("invoice_closed", json("slug", invoiceSlug, "status", invoiceStatus));
    }
  }

  private void cancelFileChooser () {
    if (fileChooser != null) {
      ValueCallback<Uri[]> pending = fileChooser;
      fileChooser = null;
      context().putActivityResultHandler(Intents.ACTIVITY_RESULT_MINI_APP_FILE, null);
      pending.onReceiveValue(null);
    }
  }
}

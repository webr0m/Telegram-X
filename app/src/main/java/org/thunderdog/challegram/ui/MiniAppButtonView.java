/*
 * This file is a part of Telegram X.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.thunderdog.challegram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.emoji.CustomEmojiId;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.widget.CustomEmojiTextView;

import me.vkryl.android.AnimatorUtils;

/**
 * The bottom button of a Mini App: the label the page wrote, optionally led by a custom emoji it picked, a
 * highlight sweeping across it while the page asks for one, and a spinning arc in place of the label while
 * the page is working.
 */
public class MiniAppButtonView extends CustomEmojiTextView {
  /** The peak of the sweep, which is the same twenty percent of white the SDK gradient ends at. */
  private static final int SHINE_PEAK = 0x33FFFFFF;

  /** A custom emoji is only an id, so the label carries the placeholder the rest of the app uses for one. */
  private static final String EMOJI_PLACEHOLDER = "*";

  private boolean shineRequested, progressRequested;
  private @ColorInt int contentColor;

  private float shinePosition = -1f;
  private float progressAngle;

  private @Nullable ValueAnimator shineAnimator;
  private @Nullable ValueAnimator progressAnimator;

  private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Matrix shineMatrix = new Matrix();
  private @Nullable LinearGradient shineShader;
  private int shineShaderWidth;

  public MiniAppButtonView (Context context, Tdlib tdlib) {
    super(context, tdlib);
    setGravity(Gravity.CENTER);
    setSingleLine(true);
    setEllipsize(TextUtils.TruncateAt.END);
    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
    setTypeface(Fonts.getRobotoMedium());
    setPadding(Screen.dp(12), 0, Screen.dp(12), 0);
  }

  public void setParams (MiniAppButtonParams.Value params) {
    // Opaque on purpose: a custom emoji the server cannot resolve falls back to the built-in drawing path,
    // which reads its metrics off the emoji it was given whenever the text paint is partly transparent.
    contentColor = (params.textColor != 0 ? params.textColor : Theme.getColor(ColorId.fillingPositiveContent)) | 0xff000000;
    setTextColor(contentColor);
    setAlpha(MiniAppButtonParams.contentAlpha(params.active));
    setText(params.progress ? "" : buildLabel(params.text, params.emojiId));
    if (params.progress != progressRequested) {
      progressRequested = params.progress;
      updateProgressAnimator();
    }
    if (params.shine != shineRequested) {
      shineRequested = params.shine;
      updateShineAnimator();
    }
  }

  private CharSequence buildLabel (String text, long emojiId) {
    if (emojiId == 0) {
      return text;
    }
    SpannableStringBuilder builder = new SpannableStringBuilder();
    builder.append(EMOJI_PLACEHOLDER);
    builder.setSpan(new CustomEmojiId(emojiId, false), 0, EMOJI_PLACEHOLDER.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    if (!text.isEmpty()) {
      builder.append(' ').append(text);
    }
    return builder;
  }

  private void updateProgressAnimator () {
    boolean running = progressRequested && isReadyToAnimate();
    if (running && progressAnimator == null) {
      progressAnimator = AnimatorUtils.simpleValueAnimator();
      progressAnimator.setRepeatCount(ValueAnimator.INFINITE);
      progressAnimator.setDuration(1000L);
      progressAnimator.setInterpolator(AnimatorUtils.LINEAR_INTERPOLATOR);
      progressAnimator.addUpdateListener(animation -> {
        progressAngle = AnimatorUtils.getFraction(animation) * 360f;
        invalidate();
      });
      progressAnimator.start();
    } else if (!running && progressAnimator != null) {
      progressAnimator.cancel();
      progressAnimator = null;
    }
  }

  private void updateShineAnimator () {
    boolean running = shineRequested && isReadyToAnimate();
    if (running && shineAnimator == null) {
      shineAnimator = AnimatorUtils.simpleValueAnimator();
      shineAnimator.setRepeatCount(ValueAnimator.INFINITE);
      shineAnimator.setDuration(5000L);
      shineAnimator.setInterpolator(AnimatorUtils.LINEAR_INTERPOLATOR);
      shineAnimator.addUpdateListener(animation -> {
        float position = MiniAppButtonParams.shinePosition(AnimatorUtils.getFraction(animation));
        boolean wasDrawn = shinePosition >= 0f;
        shinePosition = position;
        if (wasDrawn || position >= 0f) {
          invalidate();
        }
      });
      shineAnimator.start();
    } else if (!running && shineAnimator != null) {
      shineAnimator.cancel();
      shineAnimator = null;
      shinePosition = -1f;
    }
  }

  private boolean isReadyToAnimate () {
    return getVisibility() == VISIBLE && isAttachedToWindow();
  }

  @Override
  public void setVisibility (int visibility) {
    super.setVisibility(visibility);
    updateProgressAnimator();
    updateShineAnimator();
  }

  @Override
  protected void onAttachedToWindow () {
    super.onAttachedToWindow();
    updateProgressAnimator();
    updateShineAnimator();
  }

  @Override
  protected void onDetachedFromWindow () {
    cancelAnimations();
    super.onDetachedFromWindow();
  }

  @Override
  public void performDestroy () {
    cancelAnimations();
    super.performDestroy();
  }

  private void cancelAnimations () {
    if (progressAnimator != null) {
      progressAnimator.cancel();
      progressAnimator = null;
    }
    if (shineAnimator != null) {
      shineAnimator.cancel();
      shineAnimator = null;
      shinePosition = -1f;
    }
  }

  @Override
  protected void onDraw (Canvas canvas) {
    super.onDraw(canvas);
    if (progressRequested) {
      drawProgress(canvas);
    }
    drawShine(canvas);
  }

  private void drawProgress (Canvas c) {
    float radius = Screen.dp(9f);
    float centerX = getWidth() / 2f;
    float centerY = getHeight() / 2f;
    fillPaint.reset();
    fillPaint.setAntiAlias(true);
    fillPaint.setStyle(Paint.Style.STROKE);
    fillPaint.setStrokeWidth(Screen.dp(2.25f));
    fillPaint.setColor(contentColor);
    Paints.getRectF().set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
    c.drawArc(Paints.getRectF(), progressAngle, 270f, false, fillPaint);
  }

  private void drawShine (Canvas c) {
    int width = getWidth(), height = getHeight();
    if (shinePosition < 0f || width == 0 || height == 0) {
      return;
    }
    if (shineShader == null || shineShaderWidth != width) {
      shineShaderWidth = width;
      shineShader = new LinearGradient(0, 0, width, height,
        new int[] {0x00FFFFFF, SHINE_PEAK, 0x00FFFFFF}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
    }
    shineMatrix.setTranslate(-width + shinePosition * 2f * width, 0);
    shineShader.setLocalMatrix(shineMatrix);
    fillPaint.reset();
    fillPaint.setShader(shineShader);
    int saveTo = c.save();
    c.clipRect(0, 0, width, height);
    c.drawRect(0, 0, width, height, fillPaint);
    c.restoreToCount(saveTo);
  }
}

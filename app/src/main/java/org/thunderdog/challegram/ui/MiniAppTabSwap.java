/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.thunderdog.challegram.ui;

import java.util.List;

/** Reserves an existing slot without increasing capacity. Rollback cannot resurrect a cleared slot. */
final class MiniAppTabSwap<T> {
  private final List<T> slots;
  private final T selected, replacement;
  private boolean finished;
  private MiniAppTabSwap (List<T> slots, T selected, T replacement) { this.slots = slots; this.selected = selected; this.replacement = replacement; }
  static <T> MiniAppTabSwap<T> begin (List<T> slots, T selected, T replacement) {
    int index = identityIndex(slots, selected);
    if (index < 0 || identityIndex(slots, replacement) >= 0 || selected == replacement) return null;
    MiniAppTabSwap<T> swap = new MiniAppTabSwap<>(slots, selected, replacement);
    slots.set(index, replacement);
    return swap;
  }
  void commit () { finished = true; }
  boolean rollback () {
    if (finished) return false;
    finished = true;
    int index = identityIndex(slots, replacement);
    if (index < 0) return false;
    slots.set(index, selected); return true;
  }
  private static <T> int identityIndex (List<T> slots, T target) {
    for (int i = 0; i < slots.size(); i++) if (slots.get(i) == target) return i;
    return -1;
  }
}

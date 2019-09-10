// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.impl.source.codeStyle;

import com.intellij.formatting.FormatTextRanges;
import com.intellij.formatting.FormattingRangesExtender;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.Segment;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.ChangedRangesInfo;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

class ChangedRangesUtil {

  private ChangedRangesUtil() {
  }

  static List<TextRange> processChangedRanges(@NotNull PsiFile file,
                                              @NotNull ChangedRangesInfo changedRangesInfo) {
    Document document = file.getViewProvider().getDocument();
    List<TextRange> changedRanges = optimizedChangedRanges(changedRangesInfo.allChangedRanges);
    if (document != null) {
      FormattingRangesExtender extender = new FormattingRangesExtenderImpl(document, file);
      changedRanges = extender.getExtendedRanges(changedRanges);
    }
    return changedRanges;
  }

  private static List<TextRange> optimizedChangedRanges(@NotNull List<TextRange> allChangedRanges) {
    if (allChangedRanges.isEmpty()) return allChangedRanges;
    List<TextRange> sorted = ContainerUtil.sorted(allChangedRanges, Segment.BY_START_OFFSET_THEN_END_OFFSET);

    List<TextRange> result = ContainerUtil.newSmartList();

    TextRange prev = sorted.get(0);
    for (TextRange next : sorted) {
      if (next.getStartOffset() <= prev.getEndOffset() + 5) {
        int newEndOffset = Math.max(prev.getEndOffset(), next.getEndOffset());
        prev = new TextRange(prev.getStartOffset(), newEndOffset);
      }
      else {
        result.add(prev);
        prev = next;
      }
    }
    result.add(prev);

    return result;
  }
}

/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.text;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.text.Layout.Directions;
import android.text.Layout.TabStops;
import android.text.style.CharacterStyle;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;

/**
 * Represents a line of styled text, for measuring in visual order and
 * for rendering.
 *
 * <p>Get a new instance using obtain(), and when finished with it, return it
 * to the pool using recycle().
 *
 * <p>Call set to prepare the instance for use, then either draw, measure,
 * metrics, or caretToLeftRightOf.
 *
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class TextLine {
    private static final boolean DEBUG = false;

    private TextPaint mPaint;
    private CharSequence mText;
    private int mStart;
    private int mLen;
    private int mDir;
    private Directions mDirections;
    private boolean mHasTabs;
    private TabStops mTabs;
    private char[] mChars;
    private boolean mCharsValid;
    private Spanned mSpanned;
    private PrecomputedText mComputed;

    // Additional width of whitespace for justification. This value is per whitespace, thus
    // the line width will increase by mAddedWidth x (number of stretchable whitespaces).
    private float mAddedWidth;

    private final TextPaint mWorkPaint = new TextPaint();
    private final TextPaint mActivePaint = new TextPaint();
    private final SpanSet<MetricAffectingSpan> mMetricAffectingSpanSpanSet =
            new SpanSet<MetricAffectingSpan>(MetricAffectingSpan.class);
    private final SpanSet<CharacterStyle> mCharacterStyleSpanSet =
            new SpanSet<CharacterStyle>(CharacterStyle.class);
    private final SpanSet<ReplacementSpan> mReplacementSpanSpanSet =
            new SpanSet<ReplacementSpan>(ReplacementSpan.class);

    private final DecorationInfo mDecorationInfo = new DecorationInfo();
    private final ArrayList<DecorationInfo> mDecorations = new ArrayList<>();

    private static final TextLine[] sCached = new TextLine[3];

    /**
     * Returns a new TextLine from the shared pool.
     *
     * @return an uninitialized TextLine
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public static TextLine obtain() {
        TextLine tl;
        synchronized (sCached) {
            for (int i = sCached.length; --i >= 0;) {
                if (sCached[i] != null) {
                    tl = sCached[i];
                    sCached[i] = null;
                    return tl;
                }
            }
        }
        tl = new TextLine();
        if (DEBUG) {
            Log.v("TLINE", "new: " + tl);
        }
        return tl;
    }

    /**
     * Puts a TextLine back into the shared pool. Do not use this TextLine once
     * it has been returned.
     * @param tl the textLine
     * @return null, as a convenience from clearing references to the provided
     * TextLine
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public static TextLine recycle(TextLine tl) {
        tl.mText = null;
        tl.mPaint = null;
        tl.mDirections = null;
        tl.mSpanned = null;
        tl.mTabs = null;
        tl.mChars = null;
        tl.mComputed = null;

        tl.mMetricAffectingSpanSpanSet.recycle();
        tl.mCharacterStyleSpanSet.recycle();
        tl.mReplacementSpanSpanSet.recycle();

        synchronized(sCached) {
            for (int i = 0; i < sCached.length; ++i) {
                if (sCached[i] == null) {
                    sCached[i] = tl;
                    break;
                }
            }
        }
        return null;
    }

    /**
     * Initializes a TextLine and prepares it for use.
     *
     * @param paint the base paint for the line
     * @param text the text, can be Styled
     * @param start the start of the line relative to the text
     * @param limit the limit of the line relative to the text
     * @param dir the paragraph direction of this line
     * @param directions the directions information of this line
     * @param hasTabs true if the line might contain tabs
     * @param tabStops the tabStops. Can be null.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void set(TextPaint paint, CharSequence text, int start, int limit, int dir,
            Directions directions, boolean hasTabs, TabStops tabStops) {
        mPaint = paint;
        mText = text;
        mStart = start;
        mLen = limit - start;
        mDir = dir;
        mDirections = directions;
        if (mDirections == null) {
            throw new IllegalArgumentException("Directions cannot be null");
        }
        mHasTabs = hasTabs;
        mSpanned = null;

        boolean hasReplacement = false;
        if (text instanceof Spanned) {
            mSpanned = (Spanned) text;
            mReplacementSpanSpanSet.init(mSpanned, start, limit);
            hasReplacement = mReplacementSpanSpanSet.numberOfSpans > 0;
        }

        mComputed = null;
        if (text instanceof PrecomputedText) {
            // Here, no need to check line break strategy or hyphenation frequency since there is no
            // line break concept here.
            mComputed = (PrecomputedText) text;
            if (!mComputed.getParams().getTextPaint().equalsForTextMeasurement(paint)) {
                mComputed = null;
            }
        }

        mCharsValid = hasReplacement || hasTabs || directions != Layout.DIRS_ALL_LEFT_TO_RIGHT;

        if (mCharsValid) {
            if (mChars == null || mChars.length < mLen) {
                mChars = ArrayUtils.newUnpaddedCharArray(mLen);
            }
            TextUtils.getChars(text, start, limit, mChars, 0);
            if (hasReplacement) {
                // Handle these all at once so we don't have to do it as we go.
                // Replace the first character of each replacement run with the
                // object-replacement character and the remainder with zero width
                // non-break space aka BOM.  Cursor movement code skips these
                // zero-width characters.
                char[] chars = mChars;
                for (int i = start, inext; i < limit; i = inext) {
                    inext = mReplacementSpanSpanSet.getNextTransition(i, limit);
                    if (mReplacementSpanSpanSet.hasSpansIntersecting(i, inext)) {
                        // transition into a span
                        chars[i - start] = '\ufffc';
                        for (int j = i - start + 1, e = inext - start; j < e; ++j) {
                            chars[j] = '\ufeff'; // used as ZWNBS, marks positions to skip
                        }
                    }
                }
            }
        }
        mTabs = tabStops;
        mAddedWidth = 0;
    }

    /**
     * Justify the line to the given width.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void justify(float justifyWidth) {
        int end = mLen;
        while (end > 0 && isLineEndSpace(mText.charAt(mStart + end - 1))) {
            end--;
        }
        final int spaces = countStretchableSpaces(0, end);
        if (spaces == 0) {
            // There are no stretchable spaces, so we can't help the justification by adding any
            // width.
            return;
        }
        final float width = Math.abs(measure(end, false, null));
        mAddedWidth = (justifyWidth - width) / spaces;
    }

    /**
     * Renders the TextLine.
     *
     * @param c the canvas to render on
     * @param x the leading margin position
     * @param top the top of the line
     * @param y the baseline
     * @param bottom the bottom of the line
     */
    void draw(Canvas c, float x, int top, int y, int bottom) {
        if (!mHasTabs) {
            if (mDirections == Layout.DIRS_ALL_LEFT_TO_RIGHT) {
                drawRun(c, 0, mLen, false, x, top, y, bottom, false);
                return;
            }
            if (mDirections == Layout.DIRS_ALL_RIGHT_TO_LEFT) {
                drawRun(c, 0, mLen, true, x, top, y, bottom, false);
                return;
            }
        }

        float h = 0;
        int[] runs = mDirections.mDirections;

        int lastRunIndex = runs.length - 2;
        for (int i = 0; i < runs.length; i += 2) {
            int runStart = runs[i];
            int runLimit = runStart + (runs[i+1] & Layout.RUN_LENGTH_MASK);
            if (runStart > mLen) break;
            boolean runIsRtl = (runs[i+1] & Layout.RUN_RTL_FLAG) != 0;

            int segstart = runStart;
            for (int j = mHasTabs ? runStart : runLimit; j <= runLimit; j++) {
                int codept = 0;
                if (mHasTabs && j < runLimit) {
                    codept = mChars[j];
                    if (codept >= 0xD800 && codept < 0xDC00 && j + 1 < runLimit) {
                        codept = Character.codePointAt(mChars, j);
                        if (codept > 0xFFFF) {
                            ++j;
                            continue;
                        }
                    }
                }

                if (j == runLimit || codept == '\t') {
                    h += drawRun(c, segstart, j, runIsRtl, x+h, top, y, bottom,
                            i != lastRunIndex || j != mLen);

                    if (codept == '\t') {
                        h = mDir * nextTab(h * mDir);
                    }
                    segstart = j + 1;
                }
            }
        }
    }

    /**
     * Returns metrics information for the entire line.
     *
     * @param fmi receives font metrics information, can be null
     * @return the signed width of the line
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public float metrics(FontMetricsInt fmi) {
        return measure(mLen, false, fmi);
    }

    /**
     * Returns information about a position on the line.
     *
     * @param offset the line-relative character offset, between 0 and the
     * line length, inclusive
     * @param trailing true to measure the trailing edge of the character
     * before offset, false to measure the leading edge of the character
     * at offset.
     * @param fmi receives metrics information about the requested
     * character, can be null.
     * @return the signed offset from the leading margin to the requested
     * character edge.
     */
    float measure(int offset, boolean trailing, FontMetricsInt fmi) {
        int target = trailing ? offset - 1 : offset;
        if (target < 0) {
            return 0;
        }

        float h = 0;

        if (!mHasTabs) {
            if (mDirections == Layout.DIRS_ALL_LEFT_TO_RIGHT) {
                return measureRun(0, offset, mLen, false, fmi);
            }
            if (mDirections == Layout.DIRS_ALL_RIGHT_TO_LEFT) {
                return measureRun(0, offset, mLen, true, fmi);
            }
        }

        char[] chars = mChars;
        int[] runs = mDirections.mDirections;
        for (int i = 0; i < runs.length; i += 2) {
            int runStart = runs[i];
            int runLimit = runStart + (runs[i+1] & Layout.RUN_LENGTH_MASK);
            if (runStart > mLen) break;
            boolean runIsRtl = (runs[i+1] & Layout.RUN_RTL_FLAG) != 0;

            int segstart = runStart;
            for (int j = mHasTabs ? runStart : runLimit; j <= runLimit; j++) {
                int codept = 0;
                if (mHasTabs && j < runLimit) {
                    codept = chars[j];
                    if (codept >= 0xD800 && codept < 0xDC00 && j + 1 < runLimit) {
                        codept = Character.codePointAt(chars, j);
                        if (codept > 0xFFFF) {
                            ++j;
                            continue;
                        }
                    }
                }

                if (j == runLimit || codept == '\t') {
                    boolean inSegment = target >= segstart && target < j;

                    boolean advance = (mDir == Layout.DIR_RIGHT_TO_LEFT) == runIsRtl;
                    if (inSegment && advance) {
                        return h + measureRun(segstart, offset, j, runIsRtl, fmi);
                    }

                    float w = measureRun(segstart, j, j, runIsRtl, fmi);
                    h += advance ? w : -w;

                    if (inSegment) {
                        return h + measureRun(segstart, offset, j, runIsRtl, null);
                    }

                    if (codept == '\t') {
                        if (offset == j) {
                            return h;
                        }
                        h = mDir * nextTab(h * mDir);
                        if (target == j) {
                            return h;
                        }
                    }

                    segstart = j + 1;
                }
            }
        }

        return h;
    }

    /**
     * @see #measure(int, boolean, FontMetricsInt)
     * @return The measure results for all possible offsets
     */
    float[] measureAllOffsets(boolean[] trailing, FontMetricsInt fmi) {
        float[] measurement = new float[mLen + 1];

        int[] target = new int[mLen + 1];
        for (int offset = 0; offset < target.length; ++offset) {
            target[offset] = trailing[offset] ? offset - 1 : offset;
        }
        if (target[0] < 0) {
            measurement[0] = 0;
        }

        float h = 0;

        if (!mHasTabs) {
            if (mDirections == Layout.DIRS_ALL_LEFT_TO_RIGHT) {
                for (int offset = 0; offset <= mLen; ++offset) {
                    measurement[offset] = measureRun(0, offset, mLen, false, fmi);
                }
                return measurement;
            }
            if (mDirections == Layout.DIRS_ALL_RIGHT_TO_LEFT) {
                for (int offset = 0; offset <= mLen; ++offset) {
                    measurement[offset] = measureRun(0, offset, mLen, true, fmi);
                }
                return measurement;
            }
        }

        char[] chars = mChars;
        int[] runs = mDirections.mDirections;
        for (int i = 0; i < runs.length; i += 2) {
            int runStart = runs[i];
            int runLimit = runStart + (runs[i + 1] & Layout.RUN_LENGTH_MASK);
            if (runStart > mLen) break;
            boolean runIsRtl = (runs[i + 1] & Layout.RUN_RTL_FLAG) != 0;

            int segstart = runStart;
            for (int j = mHasTabs ? runStart : runLimit; j <= runLimit; ++j) {
                int codept = 0;
                if (mHasTabs && j < runLimit) {
                    codept = chars[j];
                    if (codept >= 0xD800 && codept < 0xDC00 && j + 1 < runLimit) {
                        codept = Character.codePointAt(chars, j);
                        if (codept > 0xFFFF) {
                            ++j;
                            continue;
                        }
                    }
                }

                if (j == runLimit || codept == '\t') {
                    float oldh = h;
                    boolean advance = (mDir == Layout.DIR_RIGHT_TO_LEFT) == runIsRtl;
                    float w = measureRun(segstart, j, j, runIsRtl, fmi);
                    h += advance ? w : -w;

                    float baseh = advance ? oldh : h;
                    FontMetricsInt crtfmi = advance ? fmi : null;
                    for (int offset = segstart; offset <= j && offset <= mLen; ++offset) {
                        if (target[offset] >= segstart && target[offset] < j) {
                            measurement[offset] =
                                    baseh + measureRun(segstart, offset, j, runIsRtl, crtfmi);
                        }
                    }

                    if (codept == '\t') {
                        if (target[j] == j) {
                            measurement[j] = h;
                        }
                        h = mDir * nextTab(h * mDir);
                        if (target[j + 1] == j) {
                            measurement[j + 1] =  h;
                        }
                    }

                    segstart = j + 1;
                }
            }
        }
        if (target[mLen] == mLen) {
            measurement[mLen] = h;
        }

        return measurement;
    }

    /**
     * Draws a unidirectional (but possibly multi-styled) run of text.
     *
     *
     * @param c the canvas to draw on
     * @param start the line-relative start
     * @param limit the line-relative limit
     * @param runIsRtl true if the run is right-to-left
     * @param x the position of the run that is closest to the leading margin
     * @param top the top of the line
     * @param y the baseline
     * @param bottom the bottom of the line
     * @param needWidth true if the width value is required.
     * @return the signed width of the run, based on the paragraph direction.
     * Only valid if needWidth is true.
     */
    private float drawRun(Canvas c, int start,
            int limit, boolean runIsRtl, float x, int top, int y, int bottom,
            boolean needWidth) {

        if ((mDir == Layout.DIR_LEFT_TO_RIGHT) == runIsRtl) {
            float w = -measureRun(start, limit, limit, runIsRtl, null);
            handleRun(start, limit, limit, runIsRtl, c, x + w, top,
                    y, bottom, null, false);
            return w;
        }

        return handleRun(start, limit, limit, runIsRtl, c, x, top,
                y, bottom, null, needWidth);
    }

    /**
     * Measures a unidirectional (but possibly multi-styled) run of text.
     *
     *
     * @param start the line-relative start of the run
     * @param offset the offset to measure to, between start and limit inclusive
     * @param limit the line-relative limit of the run
     * @param runIsRtl true if the run is right-to-left
     * @param fmi receives metrics information about the requested
     * run, can be null.
     * @return the signed width from the start of the run to the leading edge
     * of the character at offset, based on the run (not paragraph) direction
     */
    private float measureRun(int start, int offset, int limit, boolean runIsRtl,
            FontMetricsInt fmi) {
        return handleRun(start, offset, limit, runIsRtl, null, 0, 0, 0, 0, fmi, true);
    }

    /**
     * Walk the cursor through this line, skipping conjuncts and
     * zero-width characters.
     *
     * <p>This function cannot properly walk the cursor off the ends of the line
     * since it does not know about any shaping on the previous/following line
     * that might affect the cursor position. Callers must either avoid these
     * situations or handle the result specially.
     *
     * @param cursor the starting position of the cursor, between 0 and the
     * length of the line, inclusive
     * @param toLeft true if the caret is moving to the left.
     * @return the new offset.  If it is less than 0 or greater than the length
     * of the line, the previous/following line should be examined to get the
     * actual offset.
     */
    int getOffsetToLeftRightOf(int cursor, boolean toLeft) {
        // 1) The caret marks the leading edge of a character. The character
        // logically before it might be on a different level, and the active caret
        // position is on the character at the lower level. If that character
        // was the previous character, the caret is on its trailing edge.
        // 2) Take this character/edge and move it in the indicated direction.
        // This gives you a new character and a new edge.
        // 3) This position is between two visually adjacent characters.  One of
        // these might be at a lower level.  The active position is on the
        // character at the lower level.
        // 4) If the active position is on the trailing edge of the character,
        // the new caret position is the following logical character, else it
        // is the character.

        int lineStart = 0;
        int lineEnd = mLen;
        boolean paraIsRtl = mDir == -1;
        int[] runs = mDirections.mDirections;

        int runIndex, runLevel = 0, runStart = lineStart, runLimit = lineEnd, newCaret = -1;
        boolean trailing = false;

        if (cursor == lineStart) {
            runIndex = -2;
        } else if (cursor == lineEnd) {
            runIndex = runs.length;
        } else {
          // First, get information about the run containing the character with
          // the active caret.
          for (runIndex = 0; runIndex < runs.length; runIndex += 2) {
            runStart = lineStart + runs[runIndex];
            if (cursor >= runStart) {
              runLimit = runStart + (runs[runIndex+1] & Layout.RUN_LENGTH_MASK);
              if (runLimit > lineEnd) {
                  runLimit = lineEnd;
              }
              if (cursor < runLimit) {
                runLevel = (runs[runIndex+1] >>> Layout.RUN_LEVEL_SHIFT) &
                    Layout.RUN_LEVEL_MASK;
                if (cursor == runStart) {
                  // The caret is on a run boundary, see if we should
                  // use the position on the trailing edge of the previous
                  // logical character instead.
                  int prevRunIndex, prevRunLevel, prevRunStart, prevRunLimit;
                  int pos = cursor - 1;
                  for (prevRunIndex = 0; prevRunIndex < runs.length; prevRunIndex += 2) {
                    prevRunStart = lineStart + runs[prevRunIndex];
                    if (pos >= prevRunStart) {
                      prevRunLimit = prevRunStart +
                          (runs[prevRunIndex+1] & Layout.RUN_LENGTH_MASK);
                      if (prevRunLimit > lineEnd) {
                          prevRunLimit = lineEnd;
                      }
                      if (pos < prevRunLimit) {
                        prevRunLevel = (runs[prevRunIndex+1] >>> Layout.RUN_LEVEL_SHIFT)
                            & Layout.RUN_LEVEL_MASK;
                        if (prevRunLevel < runLevel) {
                          // Start from logically previous character.
                          runIndex = prevRunIndex;
                          runLevel = prevRunLevel;
                          runStart = prevRunStart;
                          runLimit = prevRunLimit;
                          trailing = true;
                          break;
                        }
                      }
                    }
                  }
                }
                break;
              }
            }
          }

          // caret might be == lineEnd.  This is generally a space or paragraph
          // separator and has an associated run, but might be the end of
          // text, in which case it doesn't.  If that happens, we ran off the
          // end of the run list, and runIndex == runs.length.  In this case,
          // we are at a run boundary so we skip the below test.
          if (runIndex != runs.length) {
              boolean runIsRtl = (runLevel & 0x1) != 0;
              boolean advance = toLeft == runIsRtl;
              if (cursor != (advance ? runLimit : runStart) || advance != trailing) {
                  // Moving within or into the run, so we can move logically.
                  newCaret = getOffsetBeforeAfter(runIndex, runStart, runLimit,
                          runIsRtl, cursor, advance);
                  // If the new position is internal to the run, we're at the strong
                  // position already so we're finished.
                  if (newCaret != (advance ? runLimit : runStart)) {
                      return newCaret;
                  }
              }
          }
        }

        // If newCaret is -1, we're starting at a run boundary and crossing
        // into another run. Otherwise we've arrived at a run boundary, and
        // need to figure out which character to attach to.  Note we might
        // need to run this twice, if we cross a run boundary and end up at
        // another run boundary.
        while (true) {
          boolean advance = toLeft == paraIsRtl;
          int otherRunIndex = runIndex + (advance ? 2 : -2);
          if (otherRunIndex >= 0 && otherRunIndex < runs.length) {
            int otherRunStart = lineStart + runs[otherRunIndex];
            int otherRunLimit = otherRunStart +
            (runs[otherRunIndex+1] & Layout.RUN_LENGTH_MASK);
            if (otherRunLimit > lineEnd) {
                otherRunLimit = lineEnd;
            }
            int otherRunLevel = (runs[otherRunIndex+1] >>> Layout.RUN_LEVEL_SHIFT) &
                Layout.RUN_LEVEL_MASK;
            boolean otherRunIsRtl = (otherRunLevel & 1) != 0;

            advance = toLeft == otherRunIsRtl;
            if (newCaret == -1) {
                newCaret = getOffsetBeforeAfter(otherRunIndex, otherRunStart,
                        otherRunLimit, otherRunIsRtl,
                        advance ? otherRunStart : otherRunLimit, advance);
                if (newCaret == (advance ? otherRunLimit : otherRunStart)) {
                    // Crossed and ended up at a new boundary,
                    // repeat a second and final time.
                    runIndex = otherRunIndex;
                    runLevel = otherRunLevel;
                    continue;
                }
                break;
            }

            // The new caret is at a boundary.
            if (otherRunLevel < runLevel) {
              // The strong character is in the other run.
              newCaret = advance ? otherRunStart : otherRunLimit;
            }
            break;
          }

          if (newCaret == -1) {
              // We're walking off the end of the line.  The paragraph
              // level is always equal to or lower than any internal level, so
              // the boundaries get the strong caret.
              newCaret = advance ? mLen + 1 : -1;
              break;
          }

          // Else we've arrived at the end of the line.  That's a strong position.
          // We might have arrived here by crossing over a run with no internal
          // breaks and dropping out of the above loop before advancing one final
          // time, so reset the caret.
          // Note, we use '<=' below to handle a situation where the only run
          // on the line is a counter-directional run.  If we're not advancing,
          // we can end up at the 'lineEnd' position but the caret we want is at
          // the lineStart.
          if (newCaret <= lineEnd) {
              newCaret = advance ? lineEnd : lineStart;
          }
          break;
        }

        return newCaret;
    }

    /**
     * Returns the next valid offset within this directional run, skipping
     * conjuncts and zero-width characters.  This should not be called to walk
     * off the end of the line, since the returned values might not be valid
     * on neighboring lines.  If the returned offset is less than zero or
     * greater than the line length, the offset should be recomputed on the
     * preceding or following line, respectively.
     *
     * @param runIndex the run index
     * @param runStart the start of the run
     * @param runLimit the limit of the run
     * @param runIsRtl true if the run is right-to-left
     * @param offset the offset
     * @param after true if the new offset should logically follow the provided
     * offset
     * @return the new offset
     */
    private int getOffsetBeforeAfter(int runIndex, int runStart, int runLimit,
            boolean runIsRtl, int offset, boolean after) {

        if (runIndex < 0 || offset == (after ? mLen : 0)) {
            // Walking off end of line.  Since we don't know
            // what cursor positions are available on other lines, we can't
            // return accurate values.  These are a guess.
            if (after) {
                return TextUtils.getOffsetAfter(mText, offset + mStart) - mStart;
            }
            return TextUtils.getOffsetBefore(mText, offset + mStart) - mStart;
        }

        TextPaint wp = mWorkPaint;
        wp.set(mPaint);
        wp.setWordSpacing(mAddedWidth);

        int spanStart = runStart;
        int spanLimit;
        if (mSpanned == null) {
            spanLimit = runLimit;
        } else {
            int target = after ? offset + 1 : offset;
            int limit = mStart + runLimit;
            while (true) {
                spanLimit = mSpanned.nextSpanTransition(mStart + spanStart, limit,
                        MetricAffectingSpan.class) - mStart;
                if (spanLimit >= target) {
                    break;
                }
                spanStart = spanLimit;
            }

            MetricAffectingSpan[] spans = mSpanned.getSpans(mStart + spanStart,
                    mStart + spanLimit, MetricAffectingSpan.class);
            spans = TextUtils.removeEmptySpans(spans, mSpanned, MetricAffectingSpan.class);

            if (spans.length > 0) {
                ReplacementSpan replacement = null;
                for (int j = 0; j < spans.length; j++) {
                    MetricAffectingSpan span = spans[j];
                    if (span instanceof ReplacementSpan) {
                        replacement = (ReplacementSpan)span;
                    } else {
                        span.updateMeasureState(wp);
                    }
                }

                if (replacement != null) {
                    // If we have a replacement span, we're moving either to
                    // the start or end of this span.
                    return after ? spanLimit : spanStart;
                }
            }
        }

        int dir = runIsRtl ? Paint.DIRECTION_RTL : Paint.DIRECTION_LTR;
        int cursorOpt = after ? Paint.CURSOR_AFTER : Paint.CURSOR_BEFORE;
        if (mCharsValid) {
            return wp.getTextRunCursor(mChars, spanStart, spanLimit - spanStart,
                    dir, offset, cursorOpt);
        } else {
            return wp.getTextRunCursor(mText, mStart + spanStart,
                    mStart + spanLimit, dir, mStart + offset, cursorOpt) - mStart;
        }
    }

    /**
     * @param wp
     */
    private static void expandMetricsFromPaint(FontMetricsInt fmi, TextPaint wp) {
        final int previousTop     = fmi.top;
        final int previousAscent  = fmi.ascent;
        final int previousDescent = fmi.descent;
        final int previousBottom  = fmi.bottom;
        final int previousLeading = fmi.leading;

        wp.getFontMetricsInt(fmi);

        updateMetrics(fmi, previousTop, previousAscent, previousDescent, previousBottom,
                previousLeading);
    }

    static void updateMetrics(FontMetricsInt fmi, int previousTop, int previousAscent,
            int previousDescent, int previousBottom, int previousLeading) {
        fmi.top     = Math.min(fmi.top,     previousTop);
        fmi.ascent  = Math.min(fmi.ascent,  previousAscent);
        fmi.descent = Math.max(fmi.descent, previousDescent);
        fmi.bottom  = Math.max(fmi.bottom,  previousBottom);
        fmi.leading = Math.max(fmi.leading, previousLeading);
    }

    private static void drawStroke(TextPaint wp, Canvas c, int color, float position,
            float thickness, float xleft, float xright, float baseline) {
        final float strokeTop = baseline + wp.baselineShift + position;

        final int previousColor = wp.getColor();
        final Paint.Style previousStyle = wp.getStyle();
        final boolean previousAntiAlias = wp.isAntiAlias();

        wp.setStyle(Paint.Style.FILL);
        wp.setAntiAlias(true);

        wp.setColor(color);
        c.drawRect(xleft, strokeTop, xright, strokeTop + thickness, wp);

        wp.setStyle(previousStyle);
        wp.setColor(previousColor);
        wp.setAntiAlias(previousAntiAlias);
    }

    private float getRunAdvance(TextPaint wp, int start, int end, int contextStart, int contextEnd,
            boolean runIsRtl, int offset) {
        if (mCharsValid) {
            return wp.getRunAdvance(mChars, start, end, contextStart, contextEnd, runIsRtl, offset);
        } else {
            final int delta = mStart;
            if (mComputed == null) {
                // TODO: Enable measured getRunAdvance for ReplacementSpan and RTL text.
                return wp.getRunAdvance(mText, delta + start, delta + end,
                        delta + contextStart, delta + contextEnd, runIsRtl, delta + offset);
            } else {
                return mComputed.getWidth(start + delta, end + delta);
            }
        }
    }

    /**
     * Utility function for measuring and rendering text.  The text must
     * not include a tab.
     *
     * @param wp the working paint
     * @param start the start of the text
     * @param end the end of the text
     * @param runIsRtl true if the run is right-to-left
     * @param c the canvas, can be null if rendering is not needed
     * @param x the edge of the run closest to the leading margin
     * @param top the top of the line
     * @param y the baseline
     * @param bottom the bottom of the line
     * @param fmi receives metrics information, can be null
     * @param needWidth true if the width of the run is needed
     * @param offset the offset for the purpose of measuring
     * @param decorations the list of locations and paremeters for drawing decorations
     * @return the signed width of the run based on the run direction; only
     * valid if needWidth is true
     */
    private float handleText(TextPaint wp, int start, int end,
            int contextStart, int contextEnd, boolean runIsRtl,
            Canvas c, float x, int top, int y, int bottom,
            FontMetricsInt fmi, boolean needWidth, int offset,
            @Nullable ArrayList<DecorationInfo> decorations) {

        wp.setWordSpacing(mAddedWidth);
        // Get metrics first (even for empty strings or "0" width runs)
        if (fmi != null) {
            expandMetricsFromPaint(fmi, wp);
        }

        // No need to do anything if the run width is "0"
        if (end == start) {
            return 0f;
        }

        float totalWidth = 0;

        final int numDecorations = decorations == null ? 0 : decorations.size();
        if (needWidth || (c != null && (wp.bgColor != 0 || numDecorations != 0 || runIsRtl))) {
            totalWidth = getRunAdvance(wp, start, end, contextStart, contextEnd, runIsRtl, offset);
        }

        if (c != null) {
            final float leftX, rightX;
            if (runIsRtl) {
                leftX = x - totalWidth;
                rightX = x;
            } else {
                leftX = x;
                rightX = x + totalWidth;
            }

            if (wp.bgColor != 0) {
                int previousColor = wp.getColor();
                Paint.Style previousStyle = wp.getStyle();

                wp.setColor(wp.bgColor);
                wp.setStyle(Paint.Style.FILL);
                c.drawRect(leftX, top, rightX, bottom, wp);

                wp.setStyle(previousStyle);
                wp.setColor(previousColor);
            }

            if (numDecorations != 0) {
                for (int i = 0; i < numDecorations; i++) {
                    final DecorationInfo info = decorations.get(i);

                    final int decorationStart = Math.max(info.start, start);
                    final int decorationEnd = Math.min(info.end, offset);
                    float decorationStartAdvance = getRunAdvance(
                            wp, start, end, contextStart, contextEnd, runIsRtl, decorationStart);
                    float decorationEndAdvance = getRunAdvance(
                            wp, start, end, contextStart, contextEnd, runIsRtl, decorationEnd);
                    final float decorationXLeft, decorationXRight;
                    if (runIsRtl) {
                        decorationXLeft = rightX - decorationEndAdvance;
                        decorationXRight = rightX - decorationStartAdvance;
                    } else {
                        decorationXLeft = leftX + decorationStartAdvance;
                        decorationXRight = leftX + decorationEndAdvance;
                    }

                    // Theoretically, there could be cases where both Paint's and TextPaint's
                    // setUnderLineText() are called. For backward compatibility, we need to draw
                    // both underlines, the one with custom color first.
                    if (info.underlineColor != 0) {
                        drawStroke(wp, c, info.underlineColor, wp.getUnderlinePosition(),
                                info.underlineThickness, decorationXLeft, decorationXRight, y);
                    }
                    if (info.isUnderlineText) {
                        final float thickness =
                                Math.max(wp.getUnderlineThickness(), 1.0f);
                        drawStroke(wp, c, wp.getColor(), wp.getUnderlinePosition(), thickness,
                                decorationXLeft, decorationXRight, y);
                    }

                    if (info.isStrikeThruText) {
                        final float thickness =
                                Math.max(wp.getStrikeThruThickness(), 1.0f);
                        drawStroke(wp, c, wp.getColor(), wp.getStrikeThruPosition(), thickness,
                                decorationXLeft, decorationXRight, y);
                    }
                }
            }

            drawTextRun(c, wp, start, end, contextStart, contextEnd, runIsRtl,
                    leftX, y + wp.baselineShift);
        }

        return runIsRtl ? -totalWidth : totalWidth;
    }

    /**
     * Utility function for measuring and rendering a replacement.
     *
     *
     * @param replacement the replacement
     * @param wp the work paint
     * @param start the start of the run
     * @param limit the limit of the run
     * @param runIsRtl true if the run is right-to-left
     * @param c the canvas, can be null if not rendering
     * @param x the edge of the replacement closest to the leading margin
     * @param top the top of the line
     * @param y the baseline
     * @param bottom the bottom of the line
     * @param fmi receives metrics information, can be null
     * @param needWidth true if the width of the replacement is needed
     * @return the signed width of the run based on the run direction; only
     * valid if needWidth is true
     */
    private float handleReplacement(ReplacementSpan replacement, TextPaint wp,
            int start, int limit, boolean runIsRtl, Canvas c,
            float x, int top, int y, int bottom, FontMetricsInt fmi,
            boolean needWidth) {

        float ret = 0;

        int textStart = mStart + start;
        int textLimit = mStart + limit;

        if (needWidth || (c != null && runIsRtl)) {
            int previousTop = 0;
            int previousAscent = 0;
            int previousDescent = 0;
            int previousBottom = 0;
            int previousLeading = 0;

            boolean needUpdateMetrics = (fmi != null);

            if (needUpdateMetrics) {
                previousTop     = fmi.top;
                previousAscent  = fmi.ascent;
                previousDescent = fmi.descent;
                previousBottom  = fmi.bottom;
                previousLeading = fmi.leading;
            }

            ret = replacement.getSize(wp, mText, textStart, textLimit, fmi);

            if (needUpdateMetrics) {
                updateMetrics(fmi, previousTop, previousAscent, previousDescent, previousBottom,
                        previousLeading);
            }
        }

        if (c != null) {
            if (runIsRtl) {
                x -= ret;
            }
            replacement.draw(c, mText, textStart, textLimit,
                    x, top, y, bottom, wp);
        }

        return runIsRtl ? -ret : ret;
    }

    private int adjustHyphenEdit(int start, int limit, int hyphenEdit) {
        int result = hyphenEdit;
        // Only draw hyphens on first or last run in line. Disable them otherwise.
        if (start > 0) { // not the first run
            result &= ~Paint.HYPHENEDIT_MASK_START_OF_LINE;
        }
        if (limit < mLen) { // not the last run
            result &= ~Paint.HYPHENEDIT_MASK_END_OF_LINE;
        }
        return result;
    }

    private static final class DecorationInfo {
        public boolean isStrikeThruText;
        public boolean isUnderlineText;
        public int underlineColor;
        public float underlineThickness;
        public int start = -1;
        public int end = -1;

        public boolean hasDecoration() {
            return isStrikeThruText || isUnderlineText || underlineColor != 0;
        }

        // Copies the info, but not the start and end range.
        public DecorationInfo copyInfo() {
            final DecorationInfo copy = new DecorationInfo();
            copy.isStrikeThruText = isStrikeThruText;
            copy.isUnderlineText = isUnderlineText;
            copy.underlineColor = underlineColor;
            copy.underlineThickness = underlineThickness;
            return copy;
        }
    }

    private void extractDecorationInfo(@NonNull TextPaint paint, @NonNull DecorationInfo info) {
        info.isStrikeThruText = paint.isStrikeThruText();
        if (info.isStrikeThruText) {
            paint.setStrikeThruText(false);
        }
        info.isUnderlineText = paint.isUnderlineText();
        if (info.isUnderlineText) {
            paint.setUnderlineText(false);
        }
        info.underlineColor = paint.underlineColor;
        info.underlineThickness = paint.underlineThickness;
        paint.setUnderlineText(0, 0.0f);
    }

    /**
     * Utility function for handling a unidirectional run.  The run must not
     * contain tabs but can contain styles.
     *
     *
     * @param start the line-relative start of the run
     * @param measureLimit the offset to measure to, between start and limit inclusive
     * @param limit the limit of the run
     * @param runIsRtl true if the run is right-to-left
     * @param c the canvas, can be null
     * @param x the end of the run closest to the leading margin
     * @param top the top of the line
     * @param y the baseline
     * @param bottom the bottom of the line
     * @param fmi receives metrics information, can be null
     * @param needWidth true if the width is required
     * @return the signed width of the run based on the run direction; only
     * valid if needWidth is true
     */
    private float handleRun(int start, int measureLimit,
            int limit, boolean runIsRtl, Canvas c, float x, int top, int y,
            int bottom, FontMetricsInt fmi, boolean needWidth) {

        if (measureLimit < start || measureLimit > limit) {
            throw new IndexOutOfBoundsException("measureLimit (" + measureLimit + ") is out of "
                    + "start (" + start + ") and limit (" + limit + ") bounds");
        }

        // Case of an empty line, make sure we update fmi according to mPaint
        if (start == measureLimit) {
            final TextPaint wp = mWorkPaint;
            wp.set(mPaint);
            if (fmi != null) {
                expandMetricsFromPaint(fmi, wp);
            }
            return 0f;
        }

        final boolean needsSpanMeasurement;
        if (mSpanned == null) {
            needsSpanMeasurement = false;
        } else {
            mMetricAffectingSpanSpanSet.init(mSpanned, mStart + start, mStart + limit);
            mCharacterStyleSpanSet.init(mSpanned, mStart + start, mStart + limit);
            needsSpanMeasurement = mMetricAffectingSpanSpanSet.numberOfSpans != 0
                    || mCharacterStyleSpanSet.numberOfSpans != 0;
        }

        if (!needsSpanMeasurement) {
            final TextPaint wp = mWorkPaint;
            wp.set(mPaint);
            wp.setHyphenEdit(adjustHyphenEdit(start, limit, wp.getHyphenEdit()));
            return handleText(wp, start, limit, start, limit, runIsRtl, c, x, top,
                    y, bottom, fmi, needWidth, measureLimit, null);
        }

        // Shaping needs to take into account context up to metric boundaries,
        // but rendering needs to take into account character style boundaries.
        // So we iterate through metric runs to get metric bounds,
        // then within each metric run iterate through character style runs
        // for the run bounds.
        final float originalX = x;
        for (int i = start, inext; i < measureLimit; i = inext) {
            final TextPaint wp = mWorkPaint;
            wp.set(mPaint);

            inext = mMetricAffectingSpanSpanSet.getNextTransition(mStart + i, mStart + limit) -
                    mStart;
            int mlimit = Math.min(inext, measureLimit);

            ReplacementSpan replacement = null;

            for (int j = 0; j < mMetricAffectingSpanSpanSet.numberOfSpans; j++) {
                // Both intervals [spanStarts..spanEnds] and [mStart + i..mStart + mlimit] are NOT
                // empty by construction. This special case in getSpans() explains the >= & <= tests
                if ((mMetricAffectingSpanSpanSet.spanStarts[j] >= mStart + mlimit) ||
                        (mMetricAffectingSpanSpanSet.spanEnds[j] <= mStart + i)) continue;
                final MetricAffectingSpan span = mMetricAffectingSpanSpanSet.spans[j];
                if (span instanceof ReplacementSpan) {
                    replacement = (ReplacementSpan)span;
                } else {
                    // We might have a replacement that uses the draw
                    // state, otherwise measure state would suffice.
                    span.updateDrawState(wp);
                }
            }

            if (replacement != null) {
                x += handleReplacement(replacement, wp, i, mlimit, runIsRtl, c, x, top, y,
                        bottom, fmi, needWidth || mlimit < measureLimit);
                continue;
            }

            final TextPaint activePaint = mActivePaint;
            activePaint.set(mPaint);
            int activeStart = i;
            int activeEnd = mlimit;
            final DecorationInfo decorationInfo = mDecorationInfo;
            mDecorations.clear();
            for (int j = i, jnext; j < mlimit; j = jnext) {
                jnext = mCharacterStyleSpanSet.getNextTransition(mStart + j, mStart + inext) -
                        mStart;

                final int offset = Math.min(jnext, mlimit);
                wp.set(mPaint);
                for (int k = 0; k < mCharacterStyleSpanSet.numberOfSpans; k++) {
                    // Intentionally using >= and <= as explained above
                    if ((mCharacterStyleSpanSet.spanStarts[k] >= mStart + offset) ||
                            (mCharacterStyleSpanSet.spanEnds[k] <= mStart + j)) continue;

                    final CharacterStyle span = mCharacterStyleSpanSet.spans[k];
                    span.updateDrawState(wp);
                }

                extractDecorationInfo(wp, decorationInfo);

                if (j == i) {
                    // First chunk of text. We can't handle it yet, since we may need to merge it
                    // with the next chunk. So we just save the TextPaint for future comparisons
                    // and use.
                    activePaint.set(wp);
                } else if (!wp.hasEqualAttributes(activePaint)) {
                    // The style of the present chunk of text is substantially different from the
                    // style of the previous chunk. We need to handle the active piece of text
                    // and restart with the present chunk.
                    activePaint.setHyphenEdit(adjustHyphenEdit(
                            activeStart, activeEnd, mPaint.getHyphenEdit()));
                    x += handleText(activePaint, activeStart, activeEnd, i, inext, runIsRtl, c, x,
                            top, y, bottom, fmi, needWidth || activeEnd < measureLimit,
                            Math.min(activeEnd, mlimit), mDecorations);

                    activeStart = j;
                    activePaint.set(wp);
                    mDecorations.clear();
                } else {
                    // The present TextPaint is substantially equal to the last TextPaint except
                    // perhaps for decorations. We just need to expand the active piece of text to
                    // include the present chunk, which we always do anyway. We don't need to save
                    // wp to activePaint, since they are already equal.
                }

                activeEnd = jnext;
                if (decorationInfo.hasDecoration()) {
                    final DecorationInfo copy = decorationInfo.copyInfo();
                    copy.start = j;
                    copy.end = jnext;
                    mDecorations.add(copy);
                }
            }
            // Handle the final piece of text.
            activePaint.setHyphenEdit(adjustHyphenEdit(
                    activeStart, activeEnd, mPaint.getHyphenEdit()));
            x += handleText(activePaint, activeStart, activeEnd, i, inext, runIsRtl, c, x,
                    top, y, bottom, fmi, needWidth || activeEnd < measureLimit,
                    Math.min(activeEnd, mlimit), mDecorations);
        }

        return x - originalX;
    }

    /**
     * Render a text run with the set-up paint.
     *
     * @param c the canvas
     * @param wp the paint used to render the text
     * @param start the start of the run
     * @param end the end of the run
     * @param contextStart the start of context for the run
     * @param contextEnd the end of the context for the run
     * @param runIsRtl true if the run is right-to-left
     * @param x the x position of the left edge of the run
     * @param y the baseline of the run
     */
    private void drawTextRun(Canvas c, TextPaint wp, int start, int end,
            int contextStart, int contextEnd, boolean runIsRtl, float x, int y) {

        if (mCharsValid) {
            int count = end - start;
            int contextCount = contextEnd - contextStart;
            c.drawTextRun(mChars, start, count, contextStart, contextCount,
                    x, y, runIsRtl, wp);
        } else {
            int delta = mStart;
            c.drawTextRun(mText, delta + start, delta + end,
                    delta + contextStart, delta + contextEnd, x, y, runIsRtl, wp);
        }
    }

    /**
     * Returns the next tab position.
     *
     * @param h the (unsigned) offset from the leading margin
     * @return the (unsigned) tab position after this offset
     */
    float nextTab(float h) {
        if (mTabs != null) {
            return mTabs.nextTab(h);
        }
        return TabStops.nextDefaultStop(h, TAB_INCREMENT);
    }

    private boolean isStretchableWhitespace(int ch) {
        // TODO: Support NBSP and other stretchable whitespace (b/34013491 and b/68204709).
        return ch == 0x0020;
    }

    /* Return the number of spaces in the text line, for the purpose of justification */
    private int countStretchableSpaces(int start, int end) {
        int count = 0;
        for (int i = start; i < end; i++) {
            final char c = mCharsValid ? mChars[i] : mText.charAt(i + mStart);
            if (isStretchableWhitespace(c)) {
                count++;
            }
        }
        return count;
    }

    // Note: keep this in sync with Minikin LineBreaker::isLineEndSpace()
    public static boolean isLineEndSpace(char ch) {
        return ch == ' ' || ch == '\t' || ch == 0x1680
                || (0x2000 <= ch && ch <= 0x200A && ch != 0x2007)
                || ch == 0x205F || ch == 0x3000;
    }

    private static final int TAB_INCREMENT = 20;
}

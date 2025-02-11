/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.datasketches.quantiles;

import static org.apache.datasketches.common.Util.LS;

import java.util.Arrays;
import java.util.Comparator;

import org.apache.datasketches.common.SketchesArgumentException;

/**
 * Utility class for generic quantiles sketch.
 *
 * <p>This class contains a highly specialized sort called blockyTandemMergeSort().
 * It also contains methods that are used while building histograms and other common
 * functions.</p>
 *
 * @author Kevin Lang
 * @author Alexander Saydakov
 */
final class ItemsUtil {

  private ItemsUtil() {}

  static final int ITEMS_SER_VER = 3;
  static final int PRIOR_ITEMS_SER_VER = 2;

  /**
   * Check the validity of the given serialization version
   * @param serVer the given serialization version
   */
  static void checkItemsSerVer(final int serVer) {
    if ((serVer == ITEMS_SER_VER) || (serVer == PRIOR_ITEMS_SER_VER)) { return; }
    throw new SketchesArgumentException(
        "Possible corruption: Invalid Serialization Version: " + serVer);
  }

  /**
   * Checks the sequential validity of the given array of generic items.
   * They must be unique, monotonically increasing and not null.
   * @param <T> the data type
   * @param items given array of generic items
   * @param comparator the comparator for generic item data type T
   */
  static final <T> void validateItems(final T[] items, final Comparator<? super T> comparator) {
    final int lenM1 = items.length - 1;
    for (int j = 0; j < lenM1; j++) {
      if ((items[j] != null) && (items[j + 1] != null)
          && (comparator.compare(items[j], items[j + 1]) < 0)) {
        continue;
      }
      throw new SketchesArgumentException(
          "Items must be unique, monotonically increasing and not null.");
    }
  }

  /**
   * Called when the base buffer has just acquired 2*k elements.
   * @param <T> the data type
   * @param sketch the given quantiles sketch
   */
  @SuppressWarnings("unchecked")
  static <T> void processFullBaseBuffer(final ItemsSketch<T> sketch) {
    final int bbCount = sketch.getBaseBufferCount();
    final long n = sketch.getN();
    assert bbCount == (2 * sketch.getK()); // internal consistency check

    // make sure there will be enough levels for the propagation
    ItemsUpdateImpl.maybeGrowLevels(sketch, n); // important: n_ was incremented by update before we got here

    // this aliasing is a bit dangerous; notice that we did it after the possible resizing
    final Object[] baseBuffer = sketch.getCombinedBuffer();

    Arrays.sort((T[]) baseBuffer, 0, bbCount, sketch.getComparator());
    ItemsUpdateImpl.inPlacePropagateCarry(
        0,
        null, 0,  // this null is okay
        (T[]) baseBuffer, 0,
        true, sketch);
    sketch.baseBufferCount_ = 0;
    Arrays.fill(baseBuffer, 0, 2 * sketch.getK(), null); // to release the discarded objects
    assert (n / (2L * sketch.getK())) == sketch.getBitPattern();  // internal consistency check
  }

  static <T> String toString(final boolean sketchSummary, final boolean dataDetail,
      final ItemsSketch<T> sketch) {
    final StringBuilder sb = new StringBuilder();
    final String thisSimpleName = sketch.getClass().getSimpleName();
    final int bbCount = sketch.getBaseBufferCount();
    final int combAllocCount = sketch.getCombinedBufferAllocatedCount();
    final int k = sketch.getK();
    final long bitPattern = sketch.getBitPattern();

    if (dataDetail) {
      sb.append(ClassicUtil.LS).append("### ").append(thisSimpleName).append(" DATA DETAIL: ").append(ClassicUtil.LS);
      final Object[] items  = sketch.getCombinedBuffer();

      //output the base buffer
      sb.append("   BaseBuffer   :");
      if (bbCount > 0) {
        for (int i = 0; i < bbCount; i++) {
          sb.append(' ').append(items[i]);
        }
      }
      sb.append(ClassicUtil.LS);
      //output all the levels
      final int numItems = combAllocCount;
      if (numItems > (2 * k)) {
        sb.append("   Valid | Level");
        for (int j = 2 * k; j < numItems; j++) { //output level data starting at 2K
          if ((j % k) == 0) { //start output of new level
            final int levelNum = j > (2 * k) ? (j - (2 * k)) / k : 0;
            final String validLvl = ((1L << levelNum) & bitPattern) > 0 ? "    T  " : "    F  ";
            final String lvl = String.format("%5d", levelNum);
            sb.append(ClassicUtil.LS).append("   ").append(validLvl).append(" ").append(lvl).append(":");
          }
          sb.append(' ').append(items[j]);
        }
        sb.append(ClassicUtil.LS);
      }
      sb.append("### END DATA DETAIL").append(ClassicUtil.LS);
    }

    if (sketchSummary) {
      final long n = sketch.getN();
      final String nStr = String.format("%,d", n);
      final int numLevels = ClassicUtil.computeNumLevelsNeeded(k, n);
      final String bufCntStr = String.format("%,d", combAllocCount);
      final int preBytes = sketch.isEmpty() ? Long.BYTES : 2 * Long.BYTES;
      final double epsPmf = ClassicUtil.getNormalizedRankError(k, true);
      final String epsPmfPctStr = String.format("%.3f%%", epsPmf * 100.0);
      final double eps =  ClassicUtil.getNormalizedRankError(k, false);
      final String epsPctStr = String.format("%.3f%%", eps * 100.0);
      final int numSamples = sketch.getNumRetained();
      final String numSampStr = String.format("%,d", numSamples);
      final T minItem = sketch.isEmpty() ? null : sketch.getMinItem();
      final T maxItem = sketch.isEmpty() ? null : sketch.getMaxItem();
      sb.append(ClassicUtil.LS).append("### ").append(thisSimpleName).append(" SUMMARY: ").append(ClassicUtil.LS);
      sb.append("   K                            : ").append(k).append(ClassicUtil.LS);
      sb.append("   N                            : ").append(nStr).append(ClassicUtil.LS);
      sb.append("   BaseBufferCount              : ").append(bbCount).append(ClassicUtil.LS);
      sb.append("   CombinedBufferAllocatedCount : ").append(bufCntStr).append(ClassicUtil.LS);
      sb.append("   Total Levels                 : ").append(numLevels).append(ClassicUtil.LS);
      sb.append("   Valid Levels                 : ").append(ClassicUtil.computeValidLevels(bitPattern))
        .append(ClassicUtil.LS);
      sb.append("   Level Bit Pattern            : ").append(Long.toBinaryString(bitPattern))
        .append(ClassicUtil.LS);
      sb.append("   Valid Samples                : ").append(numSampStr).append(ClassicUtil.LS);
      sb.append("   Preamble Bytes               : ").append(preBytes).append(ClassicUtil.LS);
      sb.append("   Normalized Rank Error        : ").append(epsPctStr).append(LS);
      sb.append("   Normalized Rank Error (PMF)  : ").append(epsPmfPctStr).append(LS);
      sb.append("   Min Quantile                 : ").append(minItem).append(ClassicUtil.LS);
      sb.append("   Max Quantile                 : ").append(maxItem).append(ClassicUtil.LS);
      sb.append("### END SKETCH SUMMARY").append(ClassicUtil.LS);
    }
    return sb.toString();
  }

}

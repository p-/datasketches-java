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

package org.apache.datasketches.kll;

import static org.apache.datasketches.kll.KllPreambleUtil.DATA_START_ADR;
import static org.apache.datasketches.kll.KllPreambleUtil.DATA_START_ADR_SINGLE_VALUE;
import static org.apache.datasketches.kll.KllPreambleUtil.N_LONG_ADR;
import static org.apache.datasketches.kll.KllSketch.Error.SRC_MUST_BE_DOUBLE;
import static org.apache.datasketches.kll.KllSketch.Error.SRC_MUST_BE_FLOAT;
import static org.apache.datasketches.kll.KllSketch.Error.TGT_IS_READ_ONLY;
import static org.apache.datasketches.kll.KllSketch.Error.kllSketchThrow;
import static org.apache.datasketches.kll.KllSketch.SketchType.DOUBLES_SKETCH;
import static org.apache.datasketches.kll.KllSketch.SketchType.FLOATS_SKETCH;

import java.util.Random;

import org.apache.datasketches.QuantilesAPI;
import org.apache.datasketches.SketchesArgumentException;
import org.apache.datasketches.memory.Memory;
import org.apache.datasketches.memory.MemoryRequestServer;
import org.apache.datasketches.memory.WritableMemory;

/*
 * Sampled stream data (floats or doubles) is stored as an array or as part of a Memory object.
 * This array is partitioned into sections called levels and the indices into the array of values
 * are tracked by a small integer array called levels or levels array.
 * The data for level i lies in positions levelsArray[i] through levelsArray[i + 1] - 1 inclusive.
 * Hence, the levelsArray must contain (numLevels + 1) indices.
 * The valid portion of values array is completely packed and sorted, except for level 0,
 * which is filled from the top down. Any values below the index levelsArray[0] is garbage and will be
 * overwritten by subsequent updates.
 *
 * Invariants:
 * 1) After a compaction, or an update, or a merge, every level is sorted except for level zero.
 * 2) After a compaction, (sum of capacities) - (sum of values) >= 1,
 *  so there is room for least 1 more value in level zero.
 * 3) There are no gaps except at the bottom, so if levels_[0] = 0,
 *  the sketch is exactly filled to capacity and must be compacted or the valuesArray and levelsArray
 *  must be expanded to include more levels.
 * 4) Sum of weights of all retained values == N.
 * 5) Current total value capacity = valuesArray.length = levelsArray[numLevels].
 */

/**
 * This class is the root of the KLL sketch class hierarchy. It includes the public API that is independent
 * of either sketch type (float or double) and independent of whether the sketch is targeted for use on the
 * heap or Direct (off-heap).
 *
 * <p>KLL is an implementation of a very compact quantiles sketch with lazy compaction scheme
 * and nearly optimal accuracy per retained value.
 * See <a href="https://arxiv.org/abs/1603.05346v2">Optimal Quantile Approximation in Streams</a>.</p>
 *
 * <p>This is a stochastic streaming sketch that enables near-real time analysis of the
 * approximate distribution of values from a very large stream in a single pass, requiring only
 * that the values are comparable.
 * The analysis is obtained using <i>getQuantile()</i> or <i>getQuantiles()</i> functions or the
 * inverse functions getRank(), getPMF() (the Probability Mass Function), and getCDF()
 * (the Cumulative Distribution Function).</p>
 *
 * <p>Given an input stream of <i>N</i> numeric values, the <i>natural rank</i> of any specific
 * value is defined as its index <i>(1 to N)</i> in the hypothetical sorted stream of all
 * <i>N</i> input values.</p>
 *
 * <p>The <i>normalized rank</i> (<i>rank</i>) of any specific value is defined as its
 * <i>natural rank</i> divided by <i>N</i>.
 * Thus, the <i>normalized rank</i> is a value in the interval (0.0, 1.0].
 * In the Javadocs for all the quantile sketches <i>natural rank</i> is never used
 * so any reference to just <i>rank</i> should be interpreted to mean <i>normalized rank</i>.</p>
 *
 * <p>All quantile sketches are configured with a parameter <i>k</i>, which affects the size of
 * the sketch and its estimation error.</p>
 *
 * <p>In the research literature, the estimation error is commonly called <i>epsilon</i>
 * (or <i>eps</i>) and is a fraction between zero and one.
 * Larger values of <i>k</i> result in smaller values of epsilon.
 * The epsilon error is always with respect to the rank domain. Estimating the error in the
 * quantile domain must be done by first computing the error in the rank domain and then
 * translating that to the quantile domain.</p>
 *
 * <p>The relationship between the normalized rank and the corresponding quantiles can be viewed
 * as a two dimensional monotonic plot with the normalized rank on one axis and the
 * corresponding values on the other axis. Let <i>q := quantile</i> and <i>r := rank</i> then both
 * <i>q = getQuantile(r)</i> and <i>r = getRank(q)</i> are monotonically increasing functions.
 * If the y-axis is used for the rank domain and the x-axis for the quantile domain,
 * then <i>y = getRank(x)</i> is also the single point Cumulative Distribution Function (CDF).</p>
 *
 * <p>The functions <i>getQuantile(...)</i> translate ranks into corresponding quantiles.
 * The functions <i>getRank(...), getCDF(...), and getPMF(...) (Probability Mass Function)</i>
 * perform the opposite operation and translate values into ranks.</p>
 *
 * <p>The <i>getPMF(...)</i> function has about 13 to 47% worse rank error (depending
 * on <i>k</i>) than the other queries because the mass of each "bin" of the PMF has
 * "double-sided" error from the upper and lower edges of the bin as a result of a subtraction,
 * as the errors from the two edges can sometimes add.</p>
 *
 * <p>The default <i>k</i> of 200 yields a "single-sided" epsilon of about 1.33% and a
 * "double-sided" (PMF) epsilon of about 1.65%.</p>
 *
 * <p>A <i>getQuantile(rank)</i> query has the following guarantees:</p>
 * <ul>
 * <li>Let <i>v = getQuantile(r)</i> where <i>r</i> is the rank between zero and one.</li>
 * <li>The value <i>v</i> will be a value from the input stream.</li>
 * <li>Let <i>trueRank</i> be the true rank of <i>v</i> derived from the hypothetical sorted
 * stream of all <i>N</i> values.</li>
 * <li>Let <i>eps = getNormalizedRankError(false)</i>.</li>
 * <li>Then <i>r - eps &le; trueRank &le; r + eps</i> with a confidence of 99%. Note that the
 * error is on the rank, not the value.</li>
 * </ul>
 *
 * <p>A <i>getRank(value)</i> query has the following guarantees:</p>
 * <ul>
 * <li>Let <i>r = getRank(v)</i> where <i>v</i> is a value between the min and max values of
 * the input stream.</li>
 * <li>Let <i>trueRank</i> be the true rank of <i>v</i> derived from the hypothetical sorted
 * stream of all <i>N</i> values.</li>
 * <li>Let <i>eps = getNormalizedRankError(false)</i>.</li>
 * <li>Then <i>r - eps &le; trueRank &le; r + eps</i> with a confidence of 99%.</li>
 * </ul>
 *
 * <p>A <i>getPMF(...)</i> query has the following guarantees:</p>
 * <ul>
 * <li>Let <i>{r<sub>1</sub>, r<sub>2</sub>, ..., r<sub>m+1</sub>}
 * = getPMF(v<sub>1</sub>, v<sub>2</sub>, ..., v<sub>m</sub>)</i> where
 * <i>v<sub>1</sub>, v<sub>2</sub>, ..., v<sub>m</sub></i> are monotonically increasing values
 * supplied by the user that are part of the monotonic sequence
 * <i>v<sub>0</sub> = min, v<sub>1</sub>, v<sub>2</sub>, ..., v<sub>m</sub>, v<sub>m+1</sub> = max</i>,
 * and where <i>min</i> and <i>max</i> are the actual minimum and maximum values of the input
 * stream automatically included in the sequence by the <i>getPMF(...)</i> function.
 *
 * <li>Let <i>r<sub>i</sub> = mass<sub>i</sub></i> = estimated mass between
 * <i>v<sub>i-1</sub></i> and <i>v<sub>i</sub></i> where <i>v<sub>0</sub> = min</i>
 * and <i>v<sub>m+1</sub> = max</i>.</li>
 *
 * <li>Let <i>trueMass</i> be the true mass between the values of <i>v<sub>i</sub>,
 * v<sub>i+1</sub></i> derived from the hypothetical sorted stream of all <i>N</i> values.</li>
 * <li>Let <i>eps = getNormalizedRankError(true)</i>.</li>
 * <li>Then <i>mass - eps &le; trueMass &le; mass + eps</i> with a confidence of 99%.</li>
 * <li><i>r<sub>1</sub></i> includes the mass of all points between <i>min = v<sub>0</sub></i> and
 * <i>v<sub>1</sub></i>.</li>
 * <li><i>r<sub>m+1</sub></i> includes the mass of all points between <i>v<sub>m</sub></i> and
 * <i>max = v<sub>m+1</sub></i>.</li>
 * </ul>
 *
 * <p>A <i>getCDF(...)</i> query has the following guarantees:</p>
 * <ul>
 * <li>Let <i>{r<sub>1</sub>, r<sub>2</sub>, ..., r<sub>m+1</sub>}
 * = getCDF(v<sub>1</sub>, v<sub>2</sub>, ..., v<sub>m</sub>)</i> where
 * <i>v<sub>1</sub>, v<sub>2</sub>, ..., v<sub>m</sub>)</i> are monotonically increasing values
 * supplied by the user that are part of the monotonic sequence
 * <i>{v<sub>0</sub> = min, v<sub>1</sub>, v<sub>2</sub>, ..., v<sub>m</sub>, v<sub>m+1</sub> = max}</i>,
 * and where <i>min</i> and <i>max</i> are the actual minimum and maximum values of the input
 * stream automatically included in the sequence by the <i>getCDF(...)</i> function.
 *
 * <li>Let <i>r<sub>i</sub> = mass<sub>i</sub></i> = estimated mass between
 * <i>v<sub>0</sub> = min</i> and <i>v<sub>i</sub></i>.</li>
 *
 * <li>Let <i>trueMass</i> be the true mass between the true ranks of <i>v<sub>i</sub>,
 * v<sub>i+1</sub></i> derived from the hypothetical sorted stream of all <i>N</i> values.</li>
 * <li>Let <i>eps = getNormalizedRankError(true)</i>.</li>
 * <li>then <i>mass - eps &le; trueMass &le; mass + eps</i> with a confidence of 99%.</li>
 * <li><i>r<sub>1</sub></i> includes the mass of all points between <i>min = v<sub>0</sub></i> and
 * <i>v<sub>1</sub></i>.</li>
 * <li><i>r<sub>m+1</sub></i> includes the mass of all points between <i>min = v<sub>0</sub></i> and
 * <i>max = v<sub>m+1</sub></i>.</li>
 * </ul>
 *
 * <p>Because errors are independent, we can make some estimates of the size of the confidence bounds
 * for the <em>quantile</em> returned from a call to <em>getQuantile()</em>, but not error bounds.
 * These confidence bounds may be quite large for certain distributions.</p>
 * <ul>
 * <li>Let <i>q = getQuantile(r)</i>, the estimated quantile of rank <i>r</i>.</li>
 * <li>Let <i>eps = getNormalizedRankError(false)</i>.</li>
 * <li>Let <i>q<sub>lo</sub></i> = estimated quantile of rank <i>(r - eps)</i>.</li>
 * <li>Let <i>q<sub>hi</sub></i> = estimated quantile of rank <i>(r + eps)</i>.</li>
 * <li>Then <i>q<sub>lo</sub> &le; q &le; q<sub>hi</sub></i>, with 99% confidence.</li>
 * </ul>
 *
 * <p>Please visit our website: <a href="https://datasketches.apache.org">DataSketches Home Page</a> and
 * the Javadocs for more information.</p>
 *
 * @see <a href="https://datasketches.apache.org/docs/KLL/KLLSketch.html">KLL Sketch</a>
 * @see <a href="https://datasketches.apache.org/docs/Quantiles/SketchingQuantilesAndRanksTutorial.html">
 * Sketching Quantiles and Ranks, Tutorial</a>
 * @see org.apache.datasketches.QuantileSearchCriteria
 *
 * @author Lee Rhodes
 * @author Kevin Lang
 * @author Alexander Saydakov
 */
public abstract class KllSketch implements QuantilesAPI {

  /**
   * Used to define the variable type of the current instance of this class.
   */
  public enum SketchType { FLOATS_SKETCH, DOUBLES_SKETCH }

  enum Error {
    TGT_IS_READ_ONLY("Given sketch Memory is immutable, cannot write."),
    SRC_MUST_BE_DOUBLE("Given sketch must be of type Double."),
    SRC_MUST_BE_FLOAT("Given sketch must be of type Float."),
    MUST_NOT_CALL("This is an artifact of inheritance and should never be called."),
    SINGLE_VALUE_IMPROPER_CALL("Improper method use for single-value sketch"),
    MRS_MUST_NOT_BE_NULL("MemoryRequestServer cannot be null."),
    NOT_SINGLE_VALUE("Sketch is not single value."),
    MUST_NOT_BE_UPDATABLE_FORMAT("Given Memory object must not be in updatableFormat.");

    private String msg;

    private Error(final String msg) {
      this.msg = msg;
    }

    final static void kllSketchThrow(final Error errType) {
      throw new SketchesArgumentException(errType.getMessage());
    }

    private String getMessage() {
      return msg;
    }
  }

  /**
   * The default value of K
   */
  public static final int DEFAULT_K = 200;

  /**
   * The maximum value of K
   */
  public static final int MAX_K = (1 << 16) - 1; // serialized as an unsigned short

  /**
   * The default value of M. The parameter <i>m</i> is the minimum level size in number of values.
   * Currently, the public default is 8, but this can be overridden using Package Private methods to
   * 2, 4, 6 or 8, and the sketch works just fine.  The value 8 was chosen as a compromise between speed and size.
   * Choosing smaller values of <i>m</i> less than 8 will make the sketch slower.
   */
  static final int DEFAULT_M = 8;
  static final int MAX_M = 8; //The maximum value of M
  static final int MIN_M = 2; //The minimum value of M
  static final Random random = new Random();
  final SketchType sketchType;
  final boolean updatableMemFormat;
  final MemoryRequestServer memReqSvr;
  final boolean readOnly;
  int[] levelsArr;
  WritableMemory wmem;

  /**
   * Constructor for on-heap and off-heap.
   * If both wmem and memReqSvr are null, this is a heap constructor.
   * If wmem != null and wmem is not readOnly, then memReqSvr must not be null.
   * If wmem was derived from an original Memory instance via a cast, it will be readOnly.
   * @param sketchType either DOUBLE_SKETCH or FLOAT_SKETCH
   * @param wmem  the current WritableMemory or null
   * @param memReqSvr the given MemoryRequestServer or null
   */
  KllSketch(final SketchType sketchType, final WritableMemory wmem, final MemoryRequestServer memReqSvr) {
   this.sketchType = sketchType;
   this.wmem = wmem;
   if (wmem != null) {
     this.updatableMemFormat = KllPreambleUtil.getMemoryUpdatableFormatFlag(wmem);
     this.readOnly = wmem.isReadOnly() || !updatableMemFormat;
     if (readOnly) {
       this.memReqSvr = null;
     } else {
       if (memReqSvr == null) { kllSketchThrow(Error.MRS_MUST_NOT_BE_NULL); }
       this.memReqSvr = memReqSvr;
     }
   } else { //wmem is null, heap case
     this.updatableMemFormat = false;
     this.memReqSvr = null;
     this.readOnly = false;
   }
  }

  /**
   * Gets the approximate value of <em>k</em> to use given epsilon, the normalized rank error.
   * @param epsilon the normalized rank error between zero and one.
   * @param pmf if true, this function returns the value of <em>k</em> assuming the input epsilon
   * is the desired "double-sided" epsilon for the getPMF() function. Otherwise, this function
   * returns the value of <em>k</em> assuming the input epsilon is the desired "single-sided"
   * epsilon for all the other queries.
   * @return the value of <i>k</i> given a value of epsilon.
   */
  public static int getKFromEpsilon(final double epsilon, final boolean pmf) {
    return KllHelper.getKFromEpsilon(epsilon, pmf);
  }

  /**
   * Returns upper bound on the serialized size of a KllSketch given the following parameters.
   * @param k parameter that controls size of the sketch and accuracy of estimates
   * @param n stream length
   * @param sketchType either DOUBLES_SKETCH or FLOATS_SKETCH
   * @param updatableMemFormat true if updatable Memory format, otherwise the standard compact format.
   * @return upper bound on the serialized size of a KllSketch.
   */
  public static int getMaxSerializedSizeBytes(final int k, final long n,
      final SketchType sketchType, final boolean updatableMemFormat) {
    final KllHelper.GrowthStats gStats =
        KllHelper.getGrowthSchemeForGivenN(k, DEFAULT_M, n, sketchType, false);
    return updatableMemFormat ? gStats.updatableBytes : gStats.compactBytes;
  }

  /**
   * Gets the normalized rank error given k and pmf.
   * Static method version of the <i>getNormalizedRankError(boolean)</i>.
   * The epsilon value returned is a best fit to 99 percent confidence empirically measured max error
   * in thousands of trials.
   * @param k the configuration parameter
   * @param pmf if true, returns the "double-sided" normalized rank error for the getPMF() function.
   * Otherwise, it is the "single-sided" normalized rank error for all the other queries.
   * @return if pmf is true, the normalized rank error for the getPMF() function.
   * Otherwise, it is the "single-sided" normalized rank error for all the other queries.
   */
  public static double getNormalizedRankError(final int k, final boolean pmf) {
    return KllHelper.getNormalizedRankError(k, pmf);
  }

  //numValues can be either numRetained, or current max capacity at given K and numLevels.
  static int getCurrentSerializedSizeBytes(final int numLevels, final int numValues,
      final SketchType sketchType, final boolean updatableMemFormat) {
    final int typeBytes = (sketchType == DOUBLES_SKETCH) ? Double.BYTES : Float.BYTES;
    int levelsBytes = 0;
    if (updatableMemFormat) {
      levelsBytes = (numLevels + 1) * Integer.BYTES;
    } else {
      if (numValues == 0) { return N_LONG_ADR; }
      if (numValues == 1) { return DATA_START_ADR_SINGLE_VALUE + typeBytes; }
      levelsBytes = numLevels * Integer.BYTES;
    }
    return DATA_START_ADR + levelsBytes + (numValues + 2) * typeBytes; //+2 is for min & max
  }

  /**
   * Returns the current number of bytes this sketch would require to store in the compact Memory Format.
   * @return the current number of bytes this sketch would require to store in the compact Memory Format.
   * @deprecated version 4.0.0 use {@link #getSerializedSizeBytes}.
   */
  @Deprecated
  public final int getCurrentCompactSerializedSizeBytes() {
    return getCurrentSerializedSizeBytes(getNumLevels(), getNumRetained(), sketchType, false);
  }

  /**
   * Returns the current number of bytes this sketch would require to store in the updatable Memory Format.
   * @return the current number of bytes this sketch would require to store in the updatable Memory Format.
   * @deprecated version 4.0.0 use {@link #getSerializedSizeBytes}.
   */
  @Deprecated
  public final int getCurrentUpdatableSerializedSizeBytes() {
    final int valuesCap = KllHelper.computeTotalValueCapacity(getK(), getM(), getNumLevels());
    return getCurrentSerializedSizeBytes(getNumLevels(), valuesCap, sketchType, true);
  }

  @Override
  public abstract int getK();

  @Override
  public abstract long getN();

  /**
   * Gets the approximate rank error of this sketch normalized as a fraction between zero and one.
   * The epsilon value returned is a best fit to 99 percent confidence empirically measured max error
   * in thousands of trials.
   * @param pmf if true, returns the "double-sided" normalized rank error for the getPMF() function.
   * Otherwise, it is the "single-sided" normalized rank error for all the other queries.
   * @return if pmf is true, returns the "double-sided" normalized rank error for the getPMF() function.
   * Otherwise, it is the "single-sided" normalized rank error for all the other queries.
   */
  public final double getNormalizedRankError(final boolean pmf) {
    return getNormalizedRankError(getMinK(), pmf);
  }

  @Override
  public final int getNumRetained() {
    return levelsArr[getNumLevels()] - levelsArr[0];
  }

  /**
   * Returns the current number of bytes this Sketch would require if serialized.
   * @return the number of bytes this sketch would require if serialized.
   */
  public int getSerializedSizeBytes() {
    return (updatableMemFormat)
        ? getCurrentUpdatableSerializedSizeBytes()
        : getCurrentCompactSerializedSizeBytes();
  }

  /**
   * This returns the WritableMemory for Direct type sketches,
   * otherwise returns null.
   * @return the WritableMemory for Direct type sketches, otherwise null.
   */
  WritableMemory getWritableMemory() {
    return wmem;
  }

  @Override
  public boolean hasMemory() {
    return (wmem != null);
  }

  @Override
  public boolean isDirect() {
    return (wmem != null) ? wmem.isDirect() : false;
  }

  @Override
  public final boolean isEmpty() {
    return getN() == 0;
  }

  @Override
  public final boolean isEstimationMode() {
    return getNumLevels() > 1;
  }

  /**
   * Returns true if the backing WritableMemory is in updatable format.
   * @return true if the backing WritableMemory is in updatable format.
   */
  public final boolean isMemoryUpdatableFormat() {
    return hasMemory() && updatableMemFormat;
  }

  @Override
  public final boolean isReadOnly() {
    return readOnly;
  }

  /**
   * Returns true if the backing resource of <i>this</i> is identical with the backing resource
   * of <i>that</i>. The capacities must be the same.  If <i>this</i> is a region,
   * the region offset must also be the same.
   * @param that A different non-null object
   * @return true if the backing resource of <i>this</i> is the same as the backing resource
   * of <i>that</i>.
   */
  public final boolean isSameResource(final Memory that) {
    return (wmem != null) && wmem.isSameResource(that);
  }

  /**
   * Merges another sketch into this one.
   * Attempting to merge a KllDoublesSketch with a KllFloatsSketch will
   * throw an exception.
   * @param other sketch to merge into this one
   */
  public final void merge(final KllSketch other) {
    if (readOnly) { kllSketchThrow(TGT_IS_READ_ONLY); }
    if (sketchType == DOUBLES_SKETCH) {
      if (!other.isDoublesSketch()) { kllSketchThrow(SRC_MUST_BE_DOUBLE); }
      KllDoublesHelper.mergeDoubleImpl((KllDoublesSketch)this, other);
    } else {
      if (!other.isFloatsSketch()) { kllSketchThrow(SRC_MUST_BE_FLOAT); }
      KllFloatsHelper.mergeFloatImpl((KllFloatsSketch)this, other);
    }
  }

  /**
   * {@inheritDoc}
   * <p>The parameter <i>k</i> will not change.</p>
   */
  @Override
  public final void reset() {
    if (readOnly) { kllSketchThrow(TGT_IS_READ_ONLY); }
    final int k = getK();
    setN(0);
    setMinK(k);
    setNumLevels(1);
    setLevelZeroSorted(false);
    setLevelsArray(new int[] {k, k});
    if (sketchType == DOUBLES_SKETCH) {
      setMinDoubleValue(Double.NaN);
      setMaxDoubleValue(Double.NaN);
      setDoubleValuesArray(new double[k]);
    } else {
      setMinFloatValue(Float.NaN);
      setMaxFloatValue(Float.NaN);
      setFloatValuesArray(new float[k]);
    }
  }

  @Override
  public final String toString() {
    return toString(false, false);
  }

  /**
   * Returns a summary of the sketch as a string.
   * @param withLevels if true include information about levels
   * @param withData if true include sketch data
   * @return string representation of sketch summary
   */
  public String toString(final boolean withLevels, final boolean withData) {
    return KllHelper.toStringImpl(this, withLevels, withData);
  }

  /**
   * @return full size of internal values array including garbage.
   */
  abstract double[] getDoubleValuesArray();

  abstract double getDoubleSingleValue();

  /**
   * @return full size of internal values array including garbage.
   */
  abstract float[] getFloatValuesArray();

  abstract float getFloatSingleValue();

  final int[] getLevelsArray() {
    return levelsArr;
  }

  /**
   * Returns the configured parameter <i>m</i>, which is the minimum level size in number of values.
   * Currently, the public default is 8, but this can be overridden using Package Private methods to
   * 2, 4, 6 or 8, and the sketch works just fine.  The value 8 was chosen as a compromise between speed and size.
   * Choosing smaller values of <i>m</i> will make the sketch much slower.
   * @return the configured parameter m
   */
  abstract int getM();

  abstract double getMaxDoubleValue();

  abstract float getMaxFloatValue();

  abstract double getMinDoubleValue();

  abstract float getMinFloatValue();

  /**
   * MinK is the value of K that results from a merge with a sketch configured with a value of K lower than
   * the k of this sketch. This value is then used in computing the estimated upper and lower bounds of error.
   * @return The minimum K as a result of merging with lower values of k.
   */
  abstract int getMinK();

  final int getNumLevels() {
    return levelsArr.length - 1;
  }

  abstract void incN();

  abstract void incNumLevels();

  final boolean isCompactSingleValue() {
    return hasMemory() && !updatableMemFormat && (getN() == 1);
  }

  boolean isDoublesSketch() { return sketchType == DOUBLES_SKETCH; }

  boolean isFloatsSketch() { return sketchType == FLOATS_SKETCH; }

  abstract boolean isLevelZeroSorted();

  /**
   * First determine that this is a singleValue sketch before calling this.
   * @return the value of the single value
   */
  boolean isSingleValue() { return getN() == 1; }

  abstract void setDoubleValuesArray(double[] floatValues);

  abstract void setDoubleValuesArrayAt(int index, double value);

  abstract void setFloatValuesArray(float[] floatValues);

  abstract void setFloatValuesArrayAt(int index, float value);

  final void setLevelsArray(final int[] levelsArr) {
    if (readOnly) { kllSketchThrow(TGT_IS_READ_ONLY); }
    this.levelsArr = levelsArr;
    if (wmem != null) {
      wmem.putIntArray(DATA_START_ADR, this.levelsArr, 0, levelsArr.length);
    }
  }

  final void setLevelsArrayAt(final int index, final int value) {
    if (readOnly) { kllSketchThrow(TGT_IS_READ_ONLY); }
    this.levelsArr[index] = value;
    if (wmem != null) {
      final int offset = DATA_START_ADR + index * Integer.BYTES;
      wmem.putInt(offset, value);
    }
  }

  abstract void setLevelZeroSorted(boolean sorted);

  abstract void setMaxDoubleValue(double value);

  abstract void setMaxFloatValue(float value);

  abstract void setMinDoubleValue(double value);

  abstract void setMinFloatValue(float value);

  abstract void setMinK(int minK);

  abstract void setN(long n);

  abstract void setNumLevels(int numLevels);

}

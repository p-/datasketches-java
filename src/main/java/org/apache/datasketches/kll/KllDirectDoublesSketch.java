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
import static org.apache.datasketches.kll.KllPreambleUtil.DOUBLES_SKETCH_BIT_MASK;
import static org.apache.datasketches.kll.KllPreambleUtil.PREAMBLE_INTS_FULL;
import static org.apache.datasketches.kll.KllPreambleUtil.SERIAL_VERSION_UPDATABLE;
import static org.apache.datasketches.kll.KllPreambleUtil.UPDATABLE_BIT_MASK;
import static org.apache.datasketches.kll.KllPreambleUtil.getMemoryK;
import static org.apache.datasketches.kll.KllPreambleUtil.getMemoryLevelZeroSortedFlag;
import static org.apache.datasketches.kll.KllPreambleUtil.getMemoryM;
import static org.apache.datasketches.kll.KllPreambleUtil.getMemoryMinK;
import static org.apache.datasketches.kll.KllPreambleUtil.getMemoryN;
import static org.apache.datasketches.kll.KllPreambleUtil.getMemoryNumLevels;
import static org.apache.datasketches.kll.KllPreambleUtil.setMemoryFamilyID;
import static org.apache.datasketches.kll.KllPreambleUtil.setMemoryFlags;
import static org.apache.datasketches.kll.KllPreambleUtil.setMemoryK;
import static org.apache.datasketches.kll.KllPreambleUtil.setMemoryLevelZeroSortedFlag;
import static org.apache.datasketches.kll.KllPreambleUtil.setMemoryM;
import static org.apache.datasketches.kll.KllPreambleUtil.setMemoryMinK;
import static org.apache.datasketches.kll.KllPreambleUtil.setMemoryN;
import static org.apache.datasketches.kll.KllPreambleUtil.setMemoryNumLevels;
import static org.apache.datasketches.kll.KllPreambleUtil.setMemoryPreInts;
import static org.apache.datasketches.kll.KllPreambleUtil.setMemorySerVer;
import static org.apache.datasketches.kll.KllSketch.Error.MUST_NOT_CALL;
import static org.apache.datasketches.kll.KllSketch.Error.NOT_SINGLE_ITEM;
import static org.apache.datasketches.kll.KllSketch.Error.TGT_IS_READ_ONLY;
import static org.apache.datasketches.kll.KllSketch.Error.kllSketchThrow;

import org.apache.datasketches.common.Family;
import org.apache.datasketches.memory.MemoryRequestServer;
import org.apache.datasketches.memory.WritableMemory;

/**
 * This class implements an off-heap doubles KllSketch via a WritableMemory instance of the sketch.
 *
 * <p>Please refer to the documentation in the package-info:<br>
 * {@link org.apache.datasketches.kll}</p>
 *
 * @author Lee Rhodes, Kevin Lang
 */
class KllDirectDoublesSketch extends KllDoublesSketch {

  /**
   * The constructor with Memory that can be off-heap.
   * @param wmem the current WritableMemory
   * @param memReqSvr the given MemoryRequestServer to request a larger WritableMemory
   * @param memVal the MemoryValadate object
   */
  KllDirectDoublesSketch(final WritableMemory wmem, final MemoryRequestServer memReqSvr,
      final KllMemoryValidate memVal) {
    super(wmem, memReqSvr);
    levelsArr = memVal.levelsArr;
  }

  /**
   * Create a new instance of this sketch.
   * @param k parameter that controls size of the sketch and accuracy of estimates
   * @param m parameter that controls the minimum level width in items.
   * @param dstMem the given destination WritableMemory object for use by the sketch
   * @param memReqSvr the given MemoryRequestServer to request a larger WritableMemory
   * @return a new instance of this sketch
   */
  static KllDirectDoublesSketch newDirectInstance(final int k, final int m, final WritableMemory dstMem,
      final MemoryRequestServer memReqSvr) {
    setMemoryPreInts(dstMem, PREAMBLE_INTS_FULL);
    setMemorySerVer(dstMem, SERIAL_VERSION_UPDATABLE);
    setMemoryFamilyID(dstMem, Family.KLL.getID());
    setMemoryFlags(dstMem, DOUBLES_SKETCH_BIT_MASK | UPDATABLE_BIT_MASK);
    setMemoryK(dstMem, k);
    setMemoryM(dstMem, m);
    setMemoryN(dstMem, 0);
    setMemoryMinK(dstMem, k);
    setMemoryNumLevels(dstMem, 1);
    int offset = DATA_START_ADR;
    dstMem.putIntArray(offset, new int[] {k, k}, 0, 2);
    offset += 2 * Integer.BYTES;
    dstMem.putDoubleArray(offset, new double[] {Double.NaN, Double.NaN}, 0, 2);
    offset += 2 * Double.BYTES;
    dstMem.putDoubleArray(offset, new double[k], 0, k);
    final KllMemoryValidate memVal = new KllMemoryValidate(dstMem);
    return new KllDirectDoublesSketch(dstMem, memReqSvr, memVal);
  }

  @Override
  public int getK() {
    return getMemoryK(wmem);
  }

  @Override
  public long getN() {
    return getMemoryN(wmem);
  }

  @Override //returns entire array including empty space at bottom
  double[] getDoubleItemsArray() {
    final int capacityItems = levelsArr[getNumLevels()];
    final double[] quantilesArr = new double[capacityItems];
    final int levelsBytes = levelsArr.length * Integer.BYTES; //updatable format
    final int offset = DATA_START_ADR + levelsBytes + 2 * Double.BYTES;
    wmem.getDoubleArray(offset, quantilesArr, 0, capacityItems);
    return quantilesArr;
  }

  @Override
  double getDoubleSingleItem() {
    if (!isSingleItem()) { kllSketchThrow(NOT_SINGLE_ITEM); return Double.NaN; }
    final int k = getK();
    final int offset = DATA_START_ADR + 2 * Integer.BYTES + (2 + k - 1) * Double.BYTES;
    return wmem.getDouble(offset);
  }

  @Override
  float getFloatSingleItem() { kllSketchThrow(MUST_NOT_CALL); return Float.NaN; }

  @Override
  int getM() {
    return getMemoryM(wmem);
  }

  @Override
  double getMaxDoubleItem() {
    final int offset = DATA_START_ADR + getLevelsArray().length * Integer.BYTES + Double.BYTES;
    return wmem.getDouble(offset);
  }

  @Override
  double getMinDoubleItem() {
    final int offset = DATA_START_ADR + getLevelsArray().length * Integer.BYTES;
    return wmem.getDouble(offset);
  }

  @Override
  int getMinK() {
    return getMemoryMinK(wmem);
  }

  @Override
  void incN() {
    if (readOnly) { kllSketchThrow(TGT_IS_READ_ONLY); }
    long n = getMemoryN(wmem);
    setMemoryN(wmem, ++n);
  }

  @Override
  void incNumLevels() {
    if (readOnly) { kllSketchThrow(TGT_IS_READ_ONLY); }
    int numLevels = getMemoryNumLevels(wmem);
    setMemoryNumLevels(wmem, ++numLevels);
  }

  @Override
  boolean isLevelZeroSorted() {
    return getMemoryLevelZeroSortedFlag(wmem);
  }

  @Override
  void setDoubleItemsArray(final double[] doubleItems) {
    if (readOnly) { kllSketchThrow(TGT_IS_READ_ONLY); }
    final int offset = DATA_START_ADR + getLevelsArray().length * Integer.BYTES + 2 * Double.BYTES;
    wmem.putDoubleArray(offset, doubleItems, 0, doubleItems.length);
  }

  @Override
  void setDoubleItemsArrayAt(final int index, final double item) {
    if (readOnly) { kllSketchThrow(TGT_IS_READ_ONLY); }
    final int offset =
        DATA_START_ADR + getLevelsArray().length * Integer.BYTES + 2 * Double.BYTES + index * Double.BYTES;
    wmem.putDouble(offset, item);
  }

  @Override
  void setLevelZeroSorted(final boolean sorted) {
    if (readOnly) { kllSketchThrow(TGT_IS_READ_ONLY); }
    setMemoryLevelZeroSortedFlag(wmem, sorted);
  }

  @Override
  void setMaxDoubleItem(final double item) {
    if (readOnly) { kllSketchThrow(TGT_IS_READ_ONLY); }
    final int offset = DATA_START_ADR + getLevelsArray().length * Integer.BYTES + Double.BYTES;
    wmem.putDouble(offset, item);
  }

  @Override
  void setMinDoubleItem(final double item) {
    if (readOnly) { kllSketchThrow(TGT_IS_READ_ONLY); }
    final int offset = DATA_START_ADR + getLevelsArray().length * Integer.BYTES;
    wmem.putDouble(offset, item);
  }

  @Override
  void setMinK(final int minK) {
    if (readOnly) { kllSketchThrow(TGT_IS_READ_ONLY); }
    setMemoryMinK(wmem, minK);
  }

  @Override
  void setN(final long n) {
    if (readOnly) { kllSketchThrow(TGT_IS_READ_ONLY); }
    setMemoryN(wmem, n);
  }

  @Override
  void setNumLevels(final int numLevels) {
    if (readOnly) { kllSketchThrow(TGT_IS_READ_ONLY); }
    setMemoryNumLevels(wmem, numLevels);
  }

}

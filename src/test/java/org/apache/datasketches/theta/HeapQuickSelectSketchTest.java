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

package org.apache.datasketches.theta;

import static org.apache.datasketches.common.Family.QUICKSELECT;
import static org.apache.datasketches.common.ResizeFactor.X1;
import static org.apache.datasketches.common.ResizeFactor.X2;
import static org.apache.datasketches.common.ResizeFactor.X8;
import static org.apache.datasketches.theta.PreambleUtil.FAMILY_BYTE;
import static org.apache.datasketches.theta.PreambleUtil.FLAGS_BYTE;
import static org.apache.datasketches.theta.PreambleUtil.LG_NOM_LONGS_BYTE;
import static org.apache.datasketches.theta.PreambleUtil.PREAMBLE_LONGS_BYTE;
import static org.apache.datasketches.theta.PreambleUtil.SER_VER_BYTE;
import static org.apache.datasketches.theta.PreambleUtil.THETA_LONG;
import static org.apache.datasketches.theta.PreambleUtil.insertLgResizeFactor;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.util.Arrays;

import org.apache.datasketches.common.Family;
import org.apache.datasketches.common.ResizeFactor;
import org.apache.datasketches.common.SketchesArgumentException;
import org.apache.datasketches.memory.Memory;
import org.apache.datasketches.memory.WritableMemory;
import org.apache.datasketches.thetacommon.ThetaUtil;
import org.testng.annotations.Test;

/**
 * @author Lee Rhodes
 */
public class HeapQuickSelectSketchTest {
  private Family fam_ = QUICKSELECT;

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkBadSerVer() {
    int k = 512;
    int u = k;
    long seed = ThetaUtil.DEFAULT_UPDATE_SEED;
    UpdateSketch usk = UpdateSketch.builder().setFamily(fam_).setSeed(seed).setNominalEntries(k).build();
    HeapQuickSelectSketch sk1 = (HeapQuickSelectSketch)usk; //for internal checks

    assertTrue(usk.isEmpty());

    for (int i = 0; i< u; i++) {
      sk1.update(i);
    }

    assertFalse(usk.isEmpty());
    assertEquals(usk.getEstimate(), u, 0.0);
    assertEquals(sk1.getRetainedEntries(false), u);

    byte[] byteArray = usk.toByteArray();
    WritableMemory mem = WritableMemory.writableWrap(byteArray);
    mem.putByte(SER_VER_BYTE, (byte) 0); //corrupt the SerVer byte

    Sketch.heapify(mem, seed);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkIllegalSketchID_UpdateSketch() {
    int k = 512;
    int u = k;
    long seed = ThetaUtil.DEFAULT_UPDATE_SEED;
    UpdateSketch usk = UpdateSketch.builder().setFamily(fam_).setSeed(seed).setNominalEntries(k).build();
    HeapQuickSelectSketch sk1 = (HeapQuickSelectSketch)usk; //for internal checks
    assertTrue(usk.isEmpty());

    for (int i = 0; i< u; i++) {
      usk.update(i);
    }

    assertFalse(usk.isEmpty());
    assertEquals(usk.getEstimate(), u, 0.0);
    assertEquals(sk1.getRetainedEntries(false), u);
    byte[] byteArray = usk.toByteArray();
    WritableMemory mem = WritableMemory.writableWrap(byteArray);
    mem.putByte(FAMILY_BYTE, (byte) 0); //corrupt the Sketch ID byte

    //try to heapify the corruped mem
    Sketch.heapify(mem, seed);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkHeapifySeedConflict() {
    int k = 512;
    long seed1 = 1021;
    long seed2 = ThetaUtil.DEFAULT_UPDATE_SEED;
    UpdateSketch usk = UpdateSketch.builder().setFamily(fam_).setSeed(seed1).setNominalEntries(k).build();
    byte[] byteArray = usk.toByteArray();
    Memory srcMem = Memory.wrap(byteArray);
    Sketch.heapify(srcMem, seed2);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkHeapifyCorruptLgNomLongs() {
    UpdateSketch usk = UpdateSketch.builder().setNominalEntries(16).build();
    WritableMemory srcMem = WritableMemory.writableWrap(usk.toByteArray());
    srcMem.putByte(LG_NOM_LONGS_BYTE, (byte)2); //corrupt
    Sketch.heapify(srcMem, ThetaUtil.DEFAULT_UPDATE_SEED);
  }

  @Test
  public void checkHeapifyByteArrayExact() {
    int k = 512;
    int u = k;
    long seed = ThetaUtil.DEFAULT_UPDATE_SEED;
    UpdateSketch usk = UpdateSketch.builder().setFamily(fam_).setSeed(seed).setNominalEntries(k).build();

    for (int i=0; i<u; i++) {
      usk.update(i);
    }

    int bytes = usk.getCurrentBytes();
    byte[] byteArray = usk.toByteArray();
    assertEquals(bytes, byteArray.length);

    Memory srcMem = Memory.wrap(byteArray);
    UpdateSketch usk2 = Sketches.heapifyUpdateSketch(srcMem, seed);
    assertEquals(usk2.getEstimate(), u, 0.0);
    assertEquals(usk2.getLowerBound(2), u, 0.0);
    assertEquals(usk2.getUpperBound(2), u, 0.0);
    assertEquals(usk2.isEmpty(), false);
    assertEquals(usk2.isEstimationMode(), false);
    assertEquals(usk2.getClass().getSimpleName(), usk.getClass().getSimpleName());
    assertEquals(usk2.getResizeFactor(), usk.getResizeFactor());
    usk2.toString(true, true, 8, true);
  }

  @Test
  public void checkHeapifyByteArrayEstimating() {
    int k = 4096;
    int u = 2*k;
    long seed = ThetaUtil.DEFAULT_UPDATE_SEED;

    UpdateSketch usk = UpdateSketch.builder().setFamily(fam_).setSeed(seed).setNominalEntries(k).build();

    for (int i=0; i<u; i++) {
      usk.update(i);
    }

    double uskEst = usk.getEstimate();
    double uskLB  = usk.getLowerBound(2);
    double uskUB  = usk.getUpperBound(2);
    assertEquals(usk.isEstimationMode(), true);
    byte[] byteArray = usk.toByteArray();

    Memory srcMem = Memory.wrap(byteArray);
    UpdateSketch usk2 = UpdateSketch.heapify(srcMem, seed);
    assertEquals(usk2.getEstimate(), uskEst);
    assertEquals(usk2.getLowerBound(2), uskLB);
    assertEquals(usk2.getUpperBound(2), uskUB);
    assertEquals(usk2.isEmpty(), false);
    assertEquals(usk2.isEstimationMode(), true);
    assertEquals(usk2.getClass().getSimpleName(), usk.getClass().getSimpleName());
    assertEquals(usk2.getResizeFactor(), usk.getResizeFactor());
  }

  @Test
  public void checkHeapifyMemoryEstimating() {
    int k = 512;
    int u = 2*k;
    long seed = ThetaUtil.DEFAULT_UPDATE_SEED;
    boolean estimating = (u > k);
    UpdateSketch sk1 = UpdateSketch.builder().setFamily(fam_).setSeed(seed).setNominalEntries(k).build();

    for (int i=0; i<u; i++) {
      sk1.update(i);
    }

    double sk1est = sk1.getEstimate();
    double sk1lb  = sk1.getLowerBound(2);
    double sk1ub  = sk1.getUpperBound(2);
    assertEquals(sk1.isEstimationMode(), estimating);

    byte[] byteArray = sk1.toByteArray();
    Memory mem = Memory.wrap(byteArray);

    UpdateSketch sk2 = UpdateSketch.heapify(mem, ThetaUtil.DEFAULT_UPDATE_SEED);

    assertEquals(sk2.getEstimate(), sk1est);
    assertEquals(sk2.getLowerBound(2), sk1lb);
    assertEquals(sk2.getUpperBound(2), sk1ub);
    assertEquals(sk2.isEmpty(), false);
    assertEquals(sk2.isEstimationMode(), estimating);
    assertEquals(sk2.getClass().getSimpleName(), sk1.getClass().getSimpleName());
  }

  @Test
  public void checkHQStoCompactForms() {
    int k = 512;
    int u = 4*k;
    boolean estimating = (u > k);

    //boolean compact = false;
    int maxBytes = (k << 4) + (Family.QUICKSELECT.getMinPreLongs() << 3);

    UpdateSketch usk = UpdateSketch.builder().setFamily(fam_).setNominalEntries(k).build();
    HeapQuickSelectSketch sk1 = (HeapQuickSelectSketch)usk; //for internal checks

    assertEquals(usk.getClass().getSimpleName(), "HeapQuickSelectSketch");
    assertFalse(usk.isDirect());
    assertFalse(usk.hasMemory());
    assertFalse(usk.isCompact());
    assertFalse(usk.isOrdered());

    for (int i=0; i<u; i++) {
      usk.update(i);
    }

    sk1.rebuild(); //forces size back to k

    //get baseline values
    double uskEst = usk.getEstimate();
    double uskLB  = usk.getLowerBound(2);
    double uskUB  = usk.getUpperBound(2);
    int uskBytes = usk.getCurrentBytes();    //size stored as UpdateSketch
    int uskCompBytes = usk.getCompactBytes(); //size stored as CompactSketch
    assertEquals(uskBytes, maxBytes);
    assertEquals(usk.isEstimationMode(), estimating);

    CompactSketch comp1, comp2, comp3, comp4;

    comp1 = usk.compact(false,  null);

    assertEquals(comp1.getEstimate(), uskEst);
    assertEquals(comp1.getLowerBound(2), uskLB);
    assertEquals(comp1.getUpperBound(2), uskUB);
    assertEquals(comp1.isEmpty(), false);
    assertEquals(comp1.isEstimationMode(), estimating);
    assertEquals(comp1.getCompactBytes(), uskCompBytes);
    assertEquals(comp1.getClass().getSimpleName(), "HeapCompactSketch");

    comp2 = usk.compact(true, null);

    assertEquals(comp2.getEstimate(), uskEst);
    assertEquals(comp2.getLowerBound(2), uskLB);
    assertEquals(comp2.getUpperBound(2), uskUB);
    assertEquals(comp2.isEmpty(), false);
    assertEquals(comp2.isEstimationMode(), estimating);
    assertEquals(comp2.getCompactBytes(), uskCompBytes);
    assertEquals(comp2.getClass().getSimpleName(), "HeapCompactSketch");

    byte[] memArr2 = new byte[uskCompBytes];
    WritableMemory mem2 = WritableMemory.writableWrap(memArr2);  //allocate mem for compact form

    comp3 = usk.compact(false,  mem2);  //load the mem2

    assertEquals(comp3.getEstimate(), uskEst);
    assertEquals(comp3.getLowerBound(2), uskLB);
    assertEquals(comp3.getUpperBound(2), uskUB);
    assertEquals(comp3.isEmpty(), false);
    assertEquals(comp3.isEstimationMode(), estimating);
    assertEquals(comp3.getCompactBytes(), uskCompBytes);
    assertEquals(comp3.getClass().getSimpleName(), "DirectCompactSketch");

    mem2.clear();
    comp4 = usk.compact(true, mem2);

    assertEquals(comp4.getEstimate(), uskEst);
    assertEquals(comp4.getLowerBound(2), uskLB);
    assertEquals(comp4.getUpperBound(2), uskUB);
    assertEquals(comp4.isEmpty(), false);
    assertEquals(comp4.isEstimationMode(), estimating);
    assertEquals(comp4.getCompactBytes(), uskCompBytes);
    assertEquals(comp4.getClass().getSimpleName(), "DirectCompactSketch");
    comp4.toString(false, true, 0, false);
  }

  @Test
  public void checkHQStoCompactEmptyForms() {
    int k = 512;

    UpdateSketch usk = UpdateSketch.builder().setFamily(fam_).setResizeFactor(X2).setNominalEntries(k).build();
    println("lgArr: "+ usk.getLgArrLongs());

    //empty
    println(usk.toString(false, true, 0, false));
    boolean estimating = false;
    assertEquals(usk.getClass().getSimpleName(), "HeapQuickSelectSketch");
    double uskEst = usk.getEstimate();
    double uskLB  = usk.getLowerBound(2);
    double uskUB  = usk.getUpperBound(2);
    int currentUSBytes = usk.getCurrentBytes();
    assertEquals(currentUSBytes, (32*8) + 24);  // clumsy, but a function of RF and TCF
    int compBytes = usk.getCompactBytes(); //compact form
    assertEquals(compBytes, 8);
    assertEquals(usk.isEstimationMode(), estimating);

    byte[] arr2 = new byte[compBytes];
    WritableMemory mem2 = WritableMemory.writableWrap(arr2);

    CompactSketch csk2 = usk.compact(false,  mem2);
    assertEquals(csk2.getEstimate(), uskEst);
    assertEquals(csk2.getLowerBound(2), uskLB);
    assertEquals(csk2.getUpperBound(2), uskUB);
    assertEquals(csk2.isEmpty(), true);
    assertEquals(csk2.isEstimationMode(), estimating);
    assertEquals(csk2.getClass().getSimpleName(), "DirectCompactSketch");

    CompactSketch csk3 = usk.compact(true, mem2);
    println(csk3.toString(false, true, 0, false));
    println(csk3.toString());
    assertEquals(csk3.getEstimate(), uskEst);
    assertEquals(csk3.getLowerBound(2), uskLB);
    assertEquals(csk3.getUpperBound(2), uskUB);
    assertEquals(csk3.isEmpty(), true);
    assertEquals(csk3.isEstimationMode(), estimating);
    assertEquals(csk3.getClass().getSimpleName(), "DirectCompactSketch");
  }

  @Test
  public void checkExactMode() {
    int k = 4096;
    int u = 4096;

    UpdateSketch usk = UpdateSketch.builder().setFamily(fam_).setNominalEntries(k).build();
    HeapQuickSelectSketch sk1 = (HeapQuickSelectSketch)usk; //for internal checks

    assertTrue(usk.isEmpty());

    for (int i = 0; i< u; i++) {
      usk.update(i);
    }

    assertEquals(usk.getEstimate(), u, 0.0);
    assertEquals(sk1.getRetainedEntries(false), u);
  }

  @Test
  public void checkEstMode() {
    int k = 4096;
    int u = 2*k;
    UpdateSketch usk = UpdateSketch.builder().setFamily(fam_).setResizeFactor(ResizeFactor.X4).setNominalEntries(k).build();
    HeapQuickSelectSketch sk1 = (HeapQuickSelectSketch)usk; //for internal checks

    assertTrue(usk.isEmpty());

    for (int i = 0; i< u; i++) {
      usk.update(i);
    }

    assertTrue(sk1.getRetainedEntries(false) > k); // in general it might be exactly k, but in this case must be greater
  }

  @Test
  public void checkSamplingMode() {
    int k = 4096;
    int u = k;
    float p = (float)0.5;

    UpdateSketch usk = UpdateSketch.builder().setFamily(fam_).setP(p).setNominalEntries(k).build();
    HeapQuickSelectSketch sk1 = (HeapQuickSelectSketch)usk; //for internal checks

    for (int i = 0; i < u; i++ ) {
      usk.update(i);
    }

    double p2 = sk1.getP();
    double theta = sk1.getTheta();
    assertTrue(theta <= p2);

    double est = usk.getEstimate();
    double kdbl = k;
    assertEquals(kdbl, est, kdbl*.05);
    double ub = usk.getUpperBound(1);
    assertTrue(ub > est);
    double lb = usk.getLowerBound(1);
    assertTrue(lb < est);
  }

  @Test
  public void checkErrorBounds() {
    int k = 512;

    UpdateSketch usk = UpdateSketch.builder().setFamily(fam_).setResizeFactor(X1).setNominalEntries(k).build();

    //Exact mode
    for (int i = 0; i < k; i++ ) {
      usk.update(i);
    }

    double est = usk.getEstimate();
    double lb = usk.getLowerBound(2);
    double ub = usk.getUpperBound(2);
    assertEquals(est, ub, 0.0);
    assertEquals(est, lb, 0.0);

    //Est mode
    int u = 10*k;
    for (int i = k; i < u; i++ ) {
      usk.update(i);
      usk.update(i); //test duplicate rejection
    }
    est = usk.getEstimate();
    lb = usk.getLowerBound(2);
    ub = usk.getUpperBound(2);
    assertTrue(est <= ub);
    assertTrue(est >= lb);
  }

  //Empty Tests
  @Test
  public void checkEmptyAndP() {
    //virgin, p = 1.0
    int k = 1024;

    UpdateSketch usk = UpdateSketch.builder().setFamily(fam_).setNominalEntries(k).build();
    HeapQuickSelectSketch sk1 = (HeapQuickSelectSketch)usk; //for internal checks

    assertTrue(usk.isEmpty());
    usk.update(1);
    assertEquals(sk1.getRetainedEntries(true), 1);
    assertFalse(usk.isEmpty());

    //virgin, p = .001
    UpdateSketch usk2 = UpdateSketch.builder().setFamily(fam_).setP((float)0.001).setNominalEntries(k).build();
    sk1 = (HeapQuickSelectSketch)usk2;
    assertTrue(usk2.isEmpty());
    usk2.update(1); //will be rejected
    assertEquals(sk1.getRetainedEntries(true), 0);
    assertFalse(usk2.isEmpty());
    double est = usk2.getEstimate();
    //println("Est: "+est);
    assertEquals(est, 0.0, 0.0); //because curCount = 0
    double ub = usk2.getUpperBound(2); //huge because theta is tiny!
    //println("UB: "+ub);
    assertTrue(ub > 0.0);
    double lb = usk2.getLowerBound(2);
    assertTrue(lb <= est);
    //println("LB: "+lb);
  }

  @Test
  public void checkUpperAndLowerBounds() {
    int k = 512;
    int u = 2*k;

    UpdateSketch usk = UpdateSketch.builder().setFamily(fam_).setResizeFactor(X2).setNominalEntries(k).build();

    for (int i = 0; i < u; i++ ) {
      usk.update(i);
    }

    double est = usk.getEstimate();
    double ub = usk.getUpperBound(1);
    double lb = usk.getLowerBound(1);
    assertTrue(ub > est);
    assertTrue(lb < est);
  }

  @Test
  public void checkRebuild() {
    int k = 16;
    int u = 4*k;

    UpdateSketch usk = UpdateSketch.builder().setFamily(fam_).setNominalEntries(k).build();
    HeapQuickSelectSketch sk1 = (HeapQuickSelectSketch)usk; //for internal checks

    assertTrue(usk.isEmpty());

    for (int i = 0; i< u; i++) {
      usk.update(i);
    }

    assertFalse(usk.isEmpty());
    assertTrue(usk.getEstimate() > 0.0);
    assertTrue(sk1.getRetainedEntries(false) > k);

    sk1.rebuild();
    assertEquals(sk1.getRetainedEntries(false), k);
    assertEquals(sk1.getRetainedEntries(true), k);
    sk1.rebuild();
    assertEquals(sk1.getRetainedEntries(false), k);
    assertEquals(sk1.getRetainedEntries(true), k);
  }

  @Test
  public void checkResetAndStartingSubMultiple() {
    int k = 1024;
    int u = 4*k;

    UpdateSketch usk = UpdateSketch.builder().setFamily(fam_).setResizeFactor(X8).setNominalEntries(k).build();
    HeapQuickSelectSketch sk1 = (HeapQuickSelectSketch)usk; //for internal checks

    assertTrue(usk.isEmpty());

    for (int i=0; i<u; i++) {
      usk.update(i);
    }

    assertEquals(1 << sk1.getLgArrLongs(), 2*k);
    sk1.reset();
    ResizeFactor rf = sk1.getResizeFactor();
    int subMul = ThetaUtil.startingSubMultiple(11, rf.lg(), 5); //messy
    assertEquals(sk1.getLgArrLongs(), subMul);

    UpdateSketch usk2 = UpdateSketch.builder().setFamily(fam_).setResizeFactor(ResizeFactor.X1).setNominalEntries(k).build();
    sk1 = (HeapQuickSelectSketch)usk2;

    for (int i=0; i<u; i++) {
      usk2.update(i);
    }

    assertEquals(1 << sk1.getLgArrLongs(), 2*k);
    sk1.reset();
    rf = sk1.getResizeFactor();
    subMul = ThetaUtil.startingSubMultiple(11, rf.lg(), 5); //messy
    assertEquals(sk1.getLgArrLongs(), subMul);

    assertNull(sk1.getMemory());
    assertFalse(sk1.isOrdered());
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkNegativeHashes() {
    int k = 512;
    UpdateSketch qs = UpdateSketch.builder().setFamily(QUICKSELECT).setNominalEntries(k).build();
    qs.hashUpdate(-1L);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkMinReqBytes() {
    int k = 16;
    UpdateSketch s1 = Sketches.updateSketchBuilder().setNominalEntries(k).build();
    for (int i = 0; i < (4 * k); i++) { s1.update(i); }
    byte[] byteArray = s1.toByteArray();
    byte[] badBytes = Arrays.copyOfRange(byteArray, 0, 24);
    Memory mem = Memory.wrap(badBytes);
    Sketch.heapify(mem);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkThetaAndLgArrLongs() {
    int k = 16;
    UpdateSketch s1 = Sketches.updateSketchBuilder().setNominalEntries(k).build();
    for (int i = 0; i < k; i++) { s1.update(i); }
    byte[] badArray = s1.toByteArray();
    WritableMemory mem = WritableMemory.writableWrap(badArray);
    PreambleUtil.insertLgArrLongs(mem, 4);
    PreambleUtil.insertThetaLong(mem, Long.MAX_VALUE / 2);
    Sketch.heapify(mem);
  }

  @Test
  public void checkFamily() {
    UpdateSketch sketch = Sketches.updateSketchBuilder().build();
    assertEquals(sketch.getFamily(), Family.QUICKSELECT);
  }

  @Test
  public void checkMemSerDeExceptions() {
    int k = 1024;
    UpdateSketch sk1 = UpdateSketch.builder().setFamily(QUICKSELECT).setNominalEntries(k).build();
    sk1.update(1L); //forces preLongs to 3
    byte[] bytearray1 = sk1.toByteArray();
    WritableMemory mem = WritableMemory.writableWrap(bytearray1);
    long pre0 = mem.getLong(0);

    tryBadMem(mem, PREAMBLE_LONGS_BYTE, 2); //Corrupt PreLongs
    mem.putLong(0, pre0); //restore

    tryBadMem(mem, SER_VER_BYTE, 2); //Corrupt SerVer
    mem.putLong(0, pre0); //restore

    tryBadMem(mem, FAMILY_BYTE, 1); //Corrupt Family
    mem.putLong(0, pre0); //restore

    tryBadMem(mem, FLAGS_BYTE, 2); //Corrupt READ_ONLY to true
    mem.putLong(0, pre0); //restore

    tryBadMem(mem, FAMILY_BYTE, 4); //Corrupt, Family to Union
    mem.putLong(0, pre0); //restore

    final long origThetaLong = mem.getLong(THETA_LONG);
    try {
      mem.putLong(THETA_LONG, Long.MAX_VALUE / 2); //Corrupt the theta value
      HeapQuickSelectSketch.heapifyInstance(mem, ThetaUtil.DEFAULT_UPDATE_SEED);
      fail();
    } catch (SketchesArgumentException e) {
      //expected
    }
    mem.putLong(THETA_LONG, origThetaLong); //restore theta
    byte[] byteArray2 = new byte[bytearray1.length -1];
    WritableMemory mem2 = WritableMemory.writableWrap(byteArray2);
    mem.copyTo(0, mem2, 0, mem2.getCapacity());
    try {
      HeapQuickSelectSketch.heapifyInstance(mem2, ThetaUtil.DEFAULT_UPDATE_SEED);
      fail();
    } catch (SketchesArgumentException e) {
      //expected
    }

    // force ResizeFactor.X1, but allocated capacity too small
    insertLgResizeFactor(mem, ResizeFactor.X1.lg());
    UpdateSketch hqss = HeapQuickSelectSketch.heapifyInstance(mem, ThetaUtil.DEFAULT_UPDATE_SEED);
    assertEquals(hqss.getResizeFactor(), ResizeFactor.X2); // force-promote to X2
  }

  private static void tryBadMem(WritableMemory mem, int byteOffset, int byteValue) {
    try {
      mem.putByte(byteOffset, (byte) byteValue); //Corrupt
      HeapQuickSelectSketch.heapifyInstance(mem, ThetaUtil.DEFAULT_UPDATE_SEED);
      fail();
    } catch (SketchesArgumentException e) {
      //expected
    }
  }

  @Test
  public void printlnTest() {
    println("PRINTING: "+this.getClass().getName());
  }

  /**
   * @param s value to print
   */
  static void println(String s) {
    //System.out.println(s); //disable here
  }

}

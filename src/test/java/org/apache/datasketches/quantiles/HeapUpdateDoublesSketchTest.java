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

import static java.lang.Math.floor;
import static org.apache.datasketches.common.Util.log2;
import static org.apache.datasketches.quantiles.ClassicUtil.LS;
import static org.apache.datasketches.quantiles.ClassicUtil.computeCombinedBufferItemCapacity;
import static org.apache.datasketches.quantiles.ClassicUtil.computeNumLevelsNeeded;
import static org.apache.datasketches.quantiles.HeapUpdateDoublesSketch.checkPreLongsFlagsSerVer;
import static org.apache.datasketches.quantiles.PreambleUtil.COMPACT_FLAG_MASK;
import static org.apache.datasketches.quantiles.PreambleUtil.EMPTY_FLAG_MASK;
import static org.apache.datasketches.quantilescommon.QuantileSearchCriteria.EXCLUSIVE;
import static org.apache.datasketches.quantilescommon.QuantileSearchCriteria.INCLUSIVE;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.nio.ByteOrder;

import org.apache.datasketches.common.SketchesArgumentException;
import org.apache.datasketches.memory.Memory;
import org.apache.datasketches.memory.WritableMemory;
import org.apache.datasketches.quantilescommon.QuantilesUtil;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@SuppressWarnings("deprecation")
public class HeapUpdateDoublesSketchTest {

  @BeforeMethod
  public void setUp() {
    DoublesSketch.rand.setSeed(32749); // make sketches deterministic for testing
  }

  // Please note that this is a randomized test that could probabilistically fail
  // if we didn't set the seed. (The probability of failure could be reduced by increasing k.)
  // Setting the seed has now made it deterministic.
  @Test
  public void checkEndToEnd() {
    int k = 256;
    UpdateDoublesSketch qs = DoublesSketch.builder().setK(k).build();
    UpdateDoublesSketch qs2 = DoublesSketch.builder().setK(k).build();
    int n = 1000000;
    for (int item = n; item >= 1; item--) {
      if ((item % 4) == 0) {
        qs.update(item);
      }
      else {
        qs2.update(item);
      }
    }
    assertEquals(qs.getN() + qs2.getN(), n);
    DoublesUnion union = DoublesUnion.heapify(qs);
    union.union(qs2);
    DoublesSketch result = union.getResult();

    int numPhiValues = 99;
    double[] phiArr = new double[numPhiValues];
    for (int q = 1; q <= 99; q++) {
      phiArr[q-1] = q / 100.0;
    }
    double[] splitPoints = result.getQuantiles(phiArr);

//    for (int i = 0; i < 99; i++) {
//      String s = String.format("%d\t%.6f\t%.6f", i, phiArr[i], splitPoints[i]);
//      println(s);
//    }

    for (int q = 1; q <= 99; q++) {
      double nominal = (1e6 * q) / 100.0;
      double reported = splitPoints[q-1];
      assertTrue(reported >= (nominal - 10000.0));
      assertTrue(reported <= (nominal + 10000.0));
    }

    double[] pmfResult = result.getPMF(splitPoints);
    double subtotal = 0.0;
    for (int q = 1; q <= 100; q++) {
      double phi = q / 100.0;
      subtotal += pmfResult[q-1];
      assertTrue(subtotal >= (phi - 0.01));
      assertTrue(subtotal <= (phi + 0.01));
    }

    double[] cdfResult = result.getCDF(splitPoints);
    for (int q = 1; q <= 100; q++) {
      double phi = q / 100.0;
      subtotal = cdfResult[q-1];
      assertTrue(subtotal >= (phi - 0.01));
      assertTrue(subtotal <= (phi + 0.01));
    }

    assertEquals(result.getRank(500000), 0.5, 0.01);
  }

  @Test
  public void checkSmallMinMax () {
    int k = 32;
    int n = 8;
    UpdateDoublesSketch qs1 = DoublesSketch.builder().setK(k).build();
    UpdateDoublesSketch qs2 = DoublesSketch.builder().setK(k).build();
    UpdateDoublesSketch qs3 = DoublesSketch.builder().setK(k).build();

    for (int i = n; i >= 1; i--) {
      qs1.update(i);
      qs2.update(10+i);
      qs3.update(i);
    }
    assertEquals(qs1.getQuantile (0.0, EXCLUSIVE), 1.0);
    assertEquals(qs1.getQuantile (0.5, EXCLUSIVE), 5.0);
    assertEquals(qs1.getQuantile (1.0, EXCLUSIVE), 8.0);

    assertEquals(qs2.getQuantile (0.0, EXCLUSIVE), 11.0);
    assertEquals(qs2.getQuantile (0.5, EXCLUSIVE), 15.0);
    assertEquals(qs2.getQuantile (1.0, EXCLUSIVE), 18.0);

    assertEquals(qs3.getQuantile (0.0, EXCLUSIVE), 1.0);
    assertEquals(qs3.getQuantile (0.5, EXCLUSIVE), 5.0);
    assertEquals(qs3.getQuantile (1.0, EXCLUSIVE), 8.0);

    double[] queries = {0.0, 0.5, 1.0};

    double[] resultsA = qs1.getQuantiles(queries, EXCLUSIVE);
    assertEquals(resultsA[0], 1.0);
    assertEquals(resultsA[1], 5.0);
    assertEquals(resultsA[2], 8.0);

    DoublesUnion union1 = DoublesUnion.heapify(qs1);
    union1.union(qs2);
    DoublesSketch result1 = union1.getResult();

    DoublesUnion union2 = DoublesUnion.heapify(qs2);
    union2.union(qs3);
    DoublesSketch result2 = union2.getResult();

    double[] resultsB = result1.getQuantiles(queries, EXCLUSIVE);
    assertEquals(resultsB[0], 1.0);
    assertEquals(resultsB[1], 11.0);
    assertEquals(resultsB[2], 18.0);

    double[] resultsC = result2.getQuantiles(queries, EXCLUSIVE);
    assertEquals(resultsC[0], 1.0);
    assertEquals(resultsC[1], 11.0);
    assertEquals(resultsC[2], 18.0);
  }

  @Test
  public void checkMisc() {
    int k = PreambleUtil.DEFAULT_K;
    int n = 10000;
    UpdateDoublesSketch qs = buildAndLoadQS(k, n);
    qs.update(Double.NaN); //ignore
    int n2 = (int)qs.getN();
    assertEquals(n2, n);

    qs.reset();
    assertEquals(qs.getN(), 0);
  }

  @SuppressWarnings("unused")
  @Test
  public void checkToStringDetail() {
    int k = PreambleUtil.DEFAULT_K;
    int n = 1000000;
    UpdateDoublesSketch qs = buildAndLoadQS(k, 0);
    String s = qs.toString();
    s = qs.toString(false, true);
    //println(s);
    qs = buildAndLoadQS(k, n);
    s = qs.toString();
    //println(s);
    s = qs.toString(false, true);
    //println(qs.toString(false, true));

    int n2 = (int)qs.getN();
    assertEquals(n2, n);
    qs.update(Double.NaN); //ignore
    qs.reset();
    assertEquals(qs.getN(), 0);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkConstructorException() {
    DoublesSketch.builder().setK(0).build();
  }

  @Test
  public void checkPreLongsFlagsAndSize() {
    byte[] byteArr;
    UpdateDoublesSketch ds = DoublesSketch.builder().build(); //k = 128
    //empty
    byteArr = ds.toByteArray(true); // compact
    assertEquals(byteArr.length, 8);

    byteArr = ds.toByteArray(false); // not compact
    assertEquals(byteArr.length, 8);
    assertEquals(byteArr[3], EMPTY_FLAG_MASK);

    //not empty
    ds.update(1);
    byteArr = ds.toByteArray(true); // compact
    assertEquals(byteArr.length, 40); //compact, 1 value

    byteArr = ds.toByteArray(false); // not compact
    assertEquals(byteArr.length, 64); // 32 + MIN_K(=2) * 2 * 8 = 64
  }

  @Test
  public void checkPreLongsFlagsSerVerB() {
    checkPreLongsFlagsSerVer(EMPTY_FLAG_MASK, 1, 1); //38
    checkPreLongsFlagsSerVer(0, 1, 5);               //164
    checkPreLongsFlagsSerVer(EMPTY_FLAG_MASK, 2, 1); //42
    checkPreLongsFlagsSerVer(0, 2, 2);               //72
    checkPreLongsFlagsSerVer(EMPTY_FLAG_MASK | COMPACT_FLAG_MASK, 3, 1); //47
    checkPreLongsFlagsSerVer(EMPTY_FLAG_MASK | COMPACT_FLAG_MASK, 3, 2); //79
    checkPreLongsFlagsSerVer(EMPTY_FLAG_MASK, 3, 2);  //78
    checkPreLongsFlagsSerVer(COMPACT_FLAG_MASK, 3, 2);//77
    checkPreLongsFlagsSerVer(0, 3, 2);                //76
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkPreLongsFlagsSerVer3() {
    checkPreLongsFlagsSerVer(EMPTY_FLAG_MASK, 1, 2);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkGetQuantiles() {
    int k = PreambleUtil.DEFAULT_K;
    int n = 1000000;
    DoublesSketch qs = buildAndLoadQS(k, n);
    double[] frac = {-0.5};
    qs.getQuantiles(frac);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkGetQuantile() {
    int k = PreambleUtil.DEFAULT_K;
    int n = 1000000;
    DoublesSketch qs = buildAndLoadQS(k, n);
    double frac = -0.5; //negative not allowed
    qs.getQuantile(frac);
  }

  //@Test  //visual only
  public void summaryCheckViaMemory() {
    DoublesSketch qs = buildAndLoadQS(256, 1000000);
    String s = qs.toString();
    println(s);
    println("");

    Memory srcMem = WritableMemory.writableWrap(qs.toByteArray());

    HeapUpdateDoublesSketch qs2 = HeapUpdateDoublesSketch.heapifyInstance(srcMem);
    s = qs2.toString();
    println(s);
  }

  @Test
  public void checkComputeNumLevelsNeeded() {
    int n = 1 << 20;
    int k = PreambleUtil.DEFAULT_K;
    int lvls1 = computeNumLevelsNeeded(k, n);
    int lvls2 = (int)Math.max(floor(log2((double)n/k)),0);
    assertEquals(lvls1, lvls2);
  }

  @Test
  public void checkComputeBitPattern() {
    int n = 1 << 20;
    int k = PreambleUtil.DEFAULT_K;
    long bitP = ClassicUtil.computeBitPattern(k, n);
    assertEquals(bitP, n/(2L*k));
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkValidateSplitPointsOrder() {
    double[] arr = {2, 1};
    QuantilesUtil.checkDoublesSplitPointsOrder(arr);
  }

  @Test
  public void checkGetStorageBytes() {
    int k = PreambleUtil.DEFAULT_K; //128
    DoublesSketch qs = buildAndLoadQS(k, 0); //k, n
    int stor = qs.getCurrentCompactSerializedSizeBytes();
    assertEquals(stor, 8);

    qs = buildAndLoadQS(k, 2*k); //forces one level
    stor = qs.getCurrentCompactSerializedSizeBytes();

    int retItems = ClassicUtil.computeRetainedItems(k, 2*k);
    assertEquals(stor, 32 + (retItems << 3));

    qs = buildAndLoadQS(k, (2*k)-1); //just Base Buffer
    stor = qs.getCurrentCompactSerializedSizeBytes();
    retItems = ClassicUtil.computeRetainedItems(k, (2*k)-1);
    assertEquals(stor, 32 + (retItems << 3));
  }

  @Test
  public void checkGetStorageBytes2() {
    int k = PreambleUtil.DEFAULT_K;
    long v = 1;
    UpdateDoublesSketch qs = DoublesSketch.builder().setK(k).build();
    for (int i = 0; i< 1000; i++) {
      qs.update(v++);
//      for (int j = 0; j < 1000; j++) {
//        qs.update(v++);
//      }
      byte[] byteArr = qs.toByteArray(false);
      assertEquals(byteArr.length, qs.getCurrentUpdatableSerializedSizeBytes());
    }
  }

  @Test
  public void checkMerge() {
    int k = PreambleUtil.DEFAULT_K;
    int n = 1000000;
    DoublesSketch qs1 = buildAndLoadQS(k,n,0);
    DoublesSketch qs2 = buildAndLoadQS(k,0,0); //empty
    DoublesUnion union = DoublesUnion.heapify(qs2);
    union.union(qs1);
    DoublesSketch result = union.getResult();
    double med1 = qs1.getQuantile(0.5);
    double med2 = result.getQuantile(0.5);
    assertEquals(med1, med2, 0.0);
    //println(med1+","+med2);
  }

  @Test
  public void checkReverseMerge() {
    int k = PreambleUtil.DEFAULT_K;
    DoublesSketch qs1 = buildAndLoadQS(k,  1000, 0);
    DoublesSketch qs2 = buildAndLoadQS(2*k,1000, 1000);
    DoublesUnion union = DoublesUnion.heapify(qs2);
    union.union(qs1); //attempt merge into larger k
    DoublesSketch result = union.getResult();
    assertEquals(result.getK(), k);
  }

  @Test
  public void checkInternalBuildHistogram() {
    int k = PreambleUtil.DEFAULT_K;
    int n = 1000000;
    DoublesSketch qs = buildAndLoadQS(k,n,0);
    double eps = qs.getNormalizedRankError(true);
    //println("EPS:"+eps);
    double[] spts = {100000, 500000, 900000};
    double[] fracArr = qs.getPMF(spts);
//    println(fracArr[0]+", "+ (fracArr[0]-0.1));
//    println(fracArr[1]+", "+ (fracArr[1]-0.4));
//    println(fracArr[2]+", "+ (fracArr[2]-0.4));
//    println(fracArr[3]+", "+ (fracArr[3]-0.1));
    assertEquals(fracArr[0], .1, eps);
    assertEquals(fracArr[1], .4, eps);
    assertEquals(fracArr[2], .4, eps);
    assertEquals(fracArr[3], .1, eps);
  }

  @Test
  public void checkComputeBaseBufferCount() {
    int n = 1 << 20;
    int k = PreambleUtil.DEFAULT_K;
    long bbCnt = ClassicUtil.computeBaseBufferItems(k, n);
    assertEquals(bbCnt, n % (2L*k));
  }

  @Test
  public void checkToFromByteArray() {
    checkToFromByteArray2(128, 1300); //generates a pattern of 5 -> 101
    checkToFromByteArray2(4, 7);
    checkToFromByteArray2(4, 8);
    checkToFromByteArray2(4, 9);
  }

  private static void checkToFromByteArray2(int k, int n) {
    DoublesSketch qs = buildAndLoadQS(k, n);
    byte[] byteArr;
    Memory mem;
    DoublesSketch qs2;

    // from compact
    byteArr = qs.toByteArray(true);
    mem = Memory.wrap(byteArr);
    qs2 = UpdateDoublesSketch.heapify(mem);
    for (double f = 0.1; f < 0.95; f += 0.1) {
      assertEquals(qs.getQuantile(f), qs2.getQuantile(f), 0.0);
    }

    // ordered, non-compact
    byteArr = qs.toByteArray(false);
    mem = Memory.wrap(byteArr);
    qs2 = DoublesSketch.heapify(mem);
    final DoublesSketchAccessor dsa = DoublesSketchAccessor.wrap(qs2);
    dsa.sort();
    for (double f = 0.1; f < 0.95; f += 0.1) {
      assertEquals(qs.getQuantile(f), qs2.getQuantile(f), 0.0);
    }

    // not ordered, not compact
    byteArr = qs.toByteArray(false);
    mem = Memory.wrap(byteArr);
    qs2 = DoublesSketch.heapify(mem);
    for (double f = 0.1; f < 0.95; f += 0.1) {
      assertEquals(qs.getQuantile(f), qs2.getQuantile(f), 0.0);
    }
  }

  @Test
  public void checkEmpty() {
    int k = PreambleUtil.DEFAULT_K;
    DoublesSketch qs1 = buildAndLoadQS(k, 0);
    byte[] byteArr = qs1.toByteArray();
    Memory mem = Memory.wrap(byteArr);
    DoublesSketch qs2 = DoublesSketch.heapify(mem);
    assertTrue(qs2.isEmpty());
    final int expectedSizeBytes = 8; //COMBINED_BUFFER + ((2 * MIN_K) << 3);
    assertEquals(byteArr.length, expectedSizeBytes);
    try { qs2.getQuantile(0.5); fail(); } catch (IllegalArgumentException e) { }
    try { qs2.getQuantiles(new double[] {0.0, 0.5, 1.0}); fail(); } catch (IllegalArgumentException e) { }
    try { qs2.getRank(0); fail(); } catch (IllegalArgumentException e) { }
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkMemTooSmall1() {
    Memory mem = Memory.wrap(new byte[7]);
    HeapUpdateDoublesSketch.heapifyInstance(mem);
    fail();
    //qs2.getQuantile(0.5);
  }

  //Corruption tests
  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkSerVer() {
    DoublesUtil.checkDoublesSerVer(0, HeapUpdateDoublesSketch.MIN_HEAP_DOUBLES_SER_VER);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkFamilyID() {
    ClassicUtil.checkFamilyID(3);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkMemCapacityException() {
    int k = PreambleUtil.DEFAULT_K;
    long n = 1000;
    int serVer = 3;
    int combBufItemCap = computeCombinedBufferItemCapacity(k, n);
    int memCapBytes = (combBufItemCap + 4) << 3;
    int badCapBytes = memCapBytes - 1; //corrupt
    HeapUpdateDoublesSketch.checkHeapMemCapacity(k, n, false, serVer, badCapBytes);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkBufAllocAndCap() {
    int k = PreambleUtil.DEFAULT_K;
    long n = 1000;
    int serVer = 3;
    int combBufItemCap = computeCombinedBufferItemCapacity(k, n); //non-compact cap
    int memCapBytes = (combBufItemCap + 4) << 3;
    int memCapBytesV1 = (combBufItemCap + 5) << 3;
    HeapUpdateDoublesSketch.checkHeapMemCapacity(k, n, false, 1, memCapBytesV1);
    HeapUpdateDoublesSketch.checkHeapMemCapacity(k, n, false, serVer, memCapBytes - 1); //corrupt
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkPreLongsFlagsCap() {
    int preLongs = 5;
    int flags = EMPTY_FLAG_MASK;
    int memCap = 8;
    ClassicUtil.checkPreLongsFlagsCap(preLongs, flags,  memCap); //corrupt
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkPreLongsFlagsCap2() {
    int preLongs = 5;
    int flags = 0;
    int memCap = 8;
    ClassicUtil.checkPreLongsFlagsCap(preLongs, flags,  memCap); //corrupt
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkFlags() {
    int flags = 1;
    ClassicUtil.checkHeapFlags(flags);
  }

  @Test
  public void checkZeroPatternReturn() {
    int k = PreambleUtil.DEFAULT_K;
    DoublesSketch qs1 = buildAndLoadQS(k, 64);
    byte[] byteArr = qs1.toByteArray();
    Memory mem = Memory.wrap(byteArr);
    HeapUpdateDoublesSketch.heapifyInstance(mem);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkBadDownSamplingRatio() {
    int k1 = 64;
    DoublesSketch qs1 = buildAndLoadQS(k1, k1);
    qs1.downSample(qs1, 2*k1, null);//should be smaller
  }

  @Test
  public void checkImproperKvalues() {
    checksForImproperK(0);
    checksForImproperK(1<<16);
  }

  //Primarily visual only tests
  static void testDownSampling(int bigK, int smallK) {
    HeapUpdateDoublesSketch sketch1 = HeapUpdateDoublesSketch.newInstance(bigK);
    HeapUpdateDoublesSketch sketch2 = HeapUpdateDoublesSketch.newInstance(smallK);
    for (int i = 127; i >= 1; i--) {
      sketch1.update (i);
      sketch2.update (i);
    }
    HeapUpdateDoublesSketch downSketch =
        (HeapUpdateDoublesSketch)sketch1.downSample(sketch1, smallK, null);
    println (LS+"Sk1"+LS);
    String s1, s2, down;
    s1 = sketch1.toString(true, true);
    println(s1);
    println (LS+"Down"+LS);
    down = downSketch.toString(true, true);
    println(down);
    println(LS+"Sk2"+LS);
    s2 = sketch2.toString(true, true);
    println(s2);
    assertEquals(downSketch.getNumRetained(), sketch2.getNumRetained());
  }

  @Test
  public void checkDownSampling() {
    testDownSampling(4,4); //no down sampling
    testDownSampling(16,4);
    //testDownSampling(12,3);
  }

  @Test
  public void testDownSampling2() {
    HeapUpdateDoublesSketch sketch1 = HeapUpdateDoublesSketch.newInstance(8);
    HeapUpdateDoublesSketch sketch2 = HeapUpdateDoublesSketch.newInstance(2);
    DoublesSketch downSketch;
    downSketch = sketch1.downSample(sketch1, 2, null);
    assertTrue(sameStructurePredicate(sketch2, downSketch));
    for (int i = 0; i < 50; i++) {
      sketch1.update (i);
      sketch2.update (i);
      downSketch = sketch1.downSample(sketch1, 2, null);
      assertTrue (sameStructurePredicate(sketch2, downSketch));
    }
  }

  @Test
  public void testDownSampling3() {
    int k1 = 8;
    int k2 = 2;
    int n = 50;
    UpdateDoublesSketch sketch1 = DoublesSketch.builder().setK(k1).build();
    UpdateDoublesSketch sketch2 = DoublesSketch.builder().setK(k2).build();
    DoublesSketch downSketch;
    for (int i = 0; i < n; i++) {
      sketch1.update (i);
      sketch2.update (i);
      downSketch = sketch1.downSample(sketch1, k2, null);
      assertTrue (sameStructurePredicate(sketch2, downSketch));
    }
  }

  @Test //
  public void testDownSampling3withMem() {
    int k1 = 8;
    int k2 = 2;
    int n = 50;
    UpdateDoublesSketch sketch1 = DoublesSketch.builder().setK(k1).build();
    UpdateDoublesSketch sketch2 = DoublesSketch.builder().setK(k2).build();
    DoublesSketch downSketch;
    int bytes = DoublesSketch.getUpdatableStorageBytes(k2, n);
    WritableMemory mem = WritableMemory.writableWrap(new byte[bytes]);
    for (int i = 0; i < n; i++) {
      sketch1.update (i);
      sketch2.update (i);

      downSketch = sketch1.downSample(sketch1, k2, mem);
      assertTrue (sameStructurePredicate(sketch2, downSketch));
    }

  }


  @Test
  public void testDownSampling4() {
    for (int n1 = 0; n1 < 50; n1++ ) {
      HeapUpdateDoublesSketch bigSketch = HeapUpdateDoublesSketch.newInstance(8);
      for (int i1 = 1; i1 <= n1; i1++ ) {
        bigSketch.update(i1);
      }
      for (int n2 = 0; n2 < 50; n2++ ) {
        HeapUpdateDoublesSketch directSketch = HeapUpdateDoublesSketch.newInstance(2);
        for (int i1 = 1; i1 <= n1; i1++ ) {
          directSketch.update(i1);
        }
        for (int i2 = 1; i2 <= n2; i2++ ) {
          directSketch.update(i2);
        }
        HeapUpdateDoublesSketch smlSketch = HeapUpdateDoublesSketch.newInstance(2);
        for (int i2 = 1; i2 <= n2; i2++ ) {
          smlSketch.update(i2);
        }
        DoublesMergeImpl.downSamplingMergeInto(bigSketch, smlSketch);
        assertTrue (sameStructurePredicate(directSketch, smlSketch));
      }
    }
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void testDownSamplingExceptions1() {
    UpdateDoublesSketch qs1 = DoublesSketch.builder().setK(4).build(); // not smaller
    DoublesSketch qs2 = DoublesSketch.builder().setK(3).build();
    DoublesMergeImpl.mergeInto(qs2, qs1);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void testDownSamplingExceptions2() {
    UpdateDoublesSketch qs1 = DoublesSketch.builder().setK(4).build();
    DoublesSketch qs2 = DoublesSketch.builder().setK(7).build(); // 7/4 not pwr of 2
    DoublesMergeImpl.mergeInto(qs2, qs1);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void testDownSamplingExceptions3() {
    UpdateDoublesSketch qs1 = DoublesSketch.builder().setK(4).build();
    DoublesSketch qs2 = DoublesSketch.builder().setK(12).build(); // 12/4 not pwr of 2
    DoublesMergeImpl.mergeInto(qs2, qs1);
  }

  //@Test  //visual only
  public void quantilesCheckViaMemory() {
    int k = 256;
    int n = 1000000;
    DoublesSketch qs = buildAndLoadQS(k, n);
    double[] ranks = {0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};
    String s = getRanksTable(qs, ranks);
    println(s);
    println("");

    Memory srcMem = Memory.wrap(qs.toByteArray());

    HeapUpdateDoublesSketch qs2 = HeapUpdateDoublesSketch.heapifyInstance(srcMem);
    println(getRanksTable(qs2, ranks));
  }

  static String getRanksTable(DoublesSketch qs, double[] ranks) {
    double rankError = qs.getNormalizedRankError(false);
    double[] values = qs.getQuantiles(ranks);
    double maxV = qs.getMaxItem();
    double minV = qs.getMinItem();
    double delta = maxV - minV;
    println("Note: This prints the relative value errors for illustration.");
    println("The quantiles sketch does not and can not guarantee relative value errors");

    StringBuilder sb = new StringBuilder();
    sb.append(LS);
    sb.append("N = ").append(qs.getN()).append(LS);
    sb.append("K = ").append(qs.getK()).append(LS);
    String formatStr1 = "%10s%15s%10s%15s%10s%10s";
    String formatStr2 = "%10.1f%15.5f%10.0f%15.5f%10.5f%10.5f";
    String hdr = String.format(
        formatStr1, "Rank", "ValueLB", "<= Value", "<= ValueUB", "RelErrLB", "RelErrUB");
    sb.append(hdr).append(LS);
    for (int i=0; i<ranks.length; i++) {
      double rank = ranks[i];
      double value = values[i];
      if (rank == 0.0) { assertEquals(value, minV, 0.0); }
      else if (rank == 1.0) { assertEquals(value, maxV, 0.0); }
      else {
        double rankUB = rank + rankError;
        double valueUB = minV + (delta*rankUB);
        double rankLB = Math.max(rank - rankError, 0.0);
        double valueLB = minV + (delta*rankLB);
        assertTrue(value < valueUB);
        assertTrue(value > valueLB);

        double valRelPctErrUB = (valueUB/ value) -1.0;
        double valRelPctErrLB = (valueLB/ value) -1.0;
        String row = String.format(
            formatStr2,rank, valueLB, value, valueUB, valRelPctErrLB, valRelPctErrUB);
        sb.append(row).append(LS);
      }
    }
    return sb.toString();
  }

  @Test
  public void checkKisTwo() {
    int k = 2;
    UpdateDoublesSketch qs1 = DoublesSketch.builder().setK(k).build();
    double err = qs1.getNormalizedRankError(false);
    assertTrue(err < 1.0);
    byte[] arr = qs1.toByteArray(true); //8
    assertEquals(arr.length, DoublesSketch.getCompactSerialiedSizeBytes(k, 0));
    qs1.update(1.0);
    arr = qs1.toByteArray(true); //40
    assertEquals(arr.length, DoublesSketch.getCompactSerialiedSizeBytes(k, 1));
  }

  @Test
  public void checkKisTwoDeprecated() {
    int k = 2;
    UpdateDoublesSketch qs1 = DoublesSketch.builder().setK(k).build();
    double err = qs1.getNormalizedRankError(false);
    assertTrue(err < 1.0);
    byte[] arr = qs1.toByteArray(true); //8
    assertEquals(arr.length, DoublesSketch.getCompactSerialiedSizeBytes(k, 0));
    assertEquals(arr.length, qs1.getCurrentCompactSerializedSizeBytes());
    qs1.update(1.0);
    arr = qs1.toByteArray(true); //40
    assertEquals(arr.length, DoublesSketch.getCompactSerialiedSizeBytes(k, 1));
    assertEquals(arr.length, qs1.getCurrentCompactSerializedSizeBytes());
  }

  @Test
  public void checkPutMemory() {
    UpdateDoublesSketch qs1 = DoublesSketch.builder().build(); //k = 128
    for (int i=0; i<1000; i++) {
      qs1.update(i);
    }
    int bytes = qs1.getCurrentUpdatableSerializedSizeBytes();
    WritableMemory dstMem = WritableMemory.writableWrap(new byte[bytes]);
    qs1.putMemory(dstMem, false);
    Memory srcMem = dstMem;
    DoublesSketch qs2 = DoublesSketch.heapify(srcMem);
    assertEquals(qs1.getMinItem(), qs2.getMinItem(), 0.0);
    assertEquals(qs1.getMaxItem(), qs2.getMaxItem(), 0.0);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkPutMemoryTooSmall() {
    UpdateDoublesSketch qs1 = DoublesSketch.builder().build(); //k = 128
    for (int i=0; i<1000; i++) {
      qs1.update(i);
    }
    int bytes = qs1.getCurrentCompactSerializedSizeBytes();
    WritableMemory dstMem = WritableMemory.writableWrap(new byte[bytes-1]); //too small
    qs1.putMemory(dstMem);
  }

  //Himanshu's case
  @Test
  public void testIt() {
    java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(1<<20).order(ByteOrder.nativeOrder());
    WritableMemory mem = WritableMemory.writableWrap(bb);

    int k = 1024;
    DoublesSketch qsk = new DoublesSketchBuilder().setK(k).build();
    DoublesUnion u1 = DoublesUnion.heapify(qsk);
    u1.getResult().putMemory(mem);
    DoublesUnion u2 = DoublesUnion.heapify(mem);
    DoublesSketch qsk2 = u2.getResult();
    assertTrue(qsk2.isEmpty());
  }

  @Test
  public void checkEvenlySpacedQuantiles() {
    DoublesSketch qsk = buildAndLoadQS(32, 1001);
    double[] values = qsk.getQuantiles(11);
    for (int i = 0; i<values.length; i++) {
      println(""+values[i]);
    }
    assertEquals(values.length, 11);
  }

  @Test(expectedExceptions = SketchesArgumentException.class)
  public void checkEvenlySpacedQuantilesException() {
    DoublesSketch qsk = buildAndLoadQS(32, 1001);
    qsk.getQuantiles(1);
    qsk.getQuantiles(0);
  }

  @Test
  public void checkEvenlySpaced() {
    int n = 11;
    double[] es = org.apache.datasketches.quantilescommon.QuantilesUtil.evenlySpaced(0.0, 1.0, n);
    int len = es.length;
    for (int j=0; j<len; j++) {
      double f = es[j];
      assertEquals(f, j/10.0, (j/10.0) * 0.001);
      print(es[j]+", ");
    }
    println("");
  }

  @Test
  public void checkPMFonEmpty() {
    DoublesSketch qsk = buildAndLoadQS(32, 1001);
    double[] array = new double[0];
    double[] qOut = qsk.getQuantiles(array);
    assertEquals(qOut.length, 0);
    println("qOut: "+qOut.length);
    double[] cdfOut = qsk.getCDF(array);
    println("cdfOut: "+cdfOut.length);
    assertEquals(cdfOut[0], 1.0, 0.0);
  }

  @Test
  public void checkPuts() {
    long n1 = 1001;
    UpdateDoublesSketch qsk = buildAndLoadQS(32, (int)n1);
    long n2 = qsk.getN();
    assertEquals(n2, n1);

    int bbCnt1 = qsk.getBaseBufferCount();
    long pat1 = qsk.getBitPattern();

    qsk.putBitPattern(pat1 + 1); //corrupt the pattern
    long pat2 = qsk.getBitPattern();
    assertEquals(pat1 + 1, pat2);

    qsk.putBaseBufferCount(bbCnt1 + 1); //corrupt the bbCount
    int bbCnt2 = qsk.getBaseBufferCount();
    assertEquals(bbCnt1 + 1, bbCnt2);

    qsk.putN(n1 + 1); //corrupt N
    long n3 = qsk.getN();
    assertEquals(n1 + 1, n3);

    assertNull(qsk.getMemory());
  }

  @Test
  public void serializeDeserializeCompact() {
    UpdateDoublesSketch sketch1 = DoublesSketch.builder().build();
    for (int i = 0; i < 1000; i++) {
      sketch1.update(i);
    }
    UpdateDoublesSketch sketch2;
    sketch2 = (UpdateDoublesSketch) DoublesSketch.heapify(Memory.wrap(sketch1.toByteArray()));
    for (int i = 0; i < 1000; i++) {
      sketch2.update(i + 1000);
    }
    assertEquals(sketch2.getMinItem(), 0.0);
    assertEquals(sketch2.getMaxItem(), 1999.0);
    assertEquals(sketch2.getQuantile(0.5), 1000.0, 10.0);
  }

  @Test
  public void serializeDeserializeEmptyNonCompact() {
    UpdateDoublesSketch sketch1 = DoublesSketch.builder().build();
    byte[] byteArr = sketch1.toByteArray(false); //Ordered, Not Compact, Empty
    assertEquals(byteArr.length, sketch1.getSerializedSizeBytes());
    Memory mem = Memory.wrap(byteArr);
    UpdateDoublesSketch sketch2 = (UpdateDoublesSketch) DoublesSketch.heapify(mem);
    for (int i = 0; i < 1000; i++) {
      sketch2.update(i);
    }
    assertEquals(sketch2.getMinItem(), 0.0);
    assertEquals(sketch2.getMaxItem(), 999.0);
    assertEquals(sketch2.getQuantile(0.5), 500.0, 4.0);
  }

  @Test
  public void getRankAndGetCdfConsistency() {
    final UpdateDoublesSketch sketch = DoublesSketch.builder().build();
    final int n = 1_000_000;
    final double[] values = new double[n];
    for (int i = 0; i < n; i++) {
      sketch.update(i);
      values[i] = i;
    }
    { // inclusive = false (default)
      final double[] ranks = sketch.getCDF(values);
      for (int i = 0; i < n; i++) {
        assertEquals(ranks[i], sketch.getRank(values[i]), 0.00001, "CDF vs rank for value " + i);
      }
    }
    { // inclusive = true
      final double[] ranks = sketch.getCDF(values, INCLUSIVE);
      for (int i = 0; i < n; i++) {
        assertEquals(ranks[i], sketch.getRank(values[i], INCLUSIVE), 0.00001, "CDF vs rank for value " + i);
      }
    }
  }

  @Test
  public void maxK() {
    final UpdateDoublesSketch sketch = DoublesSketch.builder().setK(32768).build();
    Assert.assertEquals(sketch.getK(), 32768);
  }

  @Test
  public void checkBounds() {
    final UpdateDoublesSketch sketch = DoublesSketch.builder().build();
    for (int i = 0; i < 1000; i++) {
      sketch.update(i);
    }
    double eps = sketch.getNormalizedRankError(false);
    double est = sketch.getQuantile(0.5);
    double ub = sketch.getQuantileUpperBound(0.5);
    double lb = sketch.getQuantileLowerBound(0.5);
    assertEquals(ub, sketch.getQuantile(.5 + eps));
    assertEquals(lb, sketch.getQuantile(0.5 - eps));
    println("Ext     : " + est);
    println("UB      : " + ub);
    println("LB      : " + lb);
  }

  @Test
  public void checkGetKFromEqs() {
    final UpdateDoublesSketch sketch = DoublesSketch.builder().build();
    int k = sketch.getK();
    double eps = DoublesSketch.getNormalizedRankError(k, false);
    double epsPmf = DoublesSketch.getNormalizedRankError(k, true);
    int kEps = DoublesSketch.getKFromEpsilon(eps, false);
    int kEpsPmf = DoublesSketch.getKFromEpsilon(epsPmf, true);
    assertEquals(kEps, k);
    assertEquals(kEpsPmf, k);
  }

  @Test
  public void tenItems() {
    final UpdateDoublesSketch sketch = DoublesSketch.builder().build();
    for (int i = 1; i <= 10; i++) { sketch.update(i); }
    assertFalse(sketch.isEmpty());
    assertEquals(sketch.getN(), 10);
    assertEquals(sketch.getNumRetained(), 10);
    for (int i = 1; i <= 10; i++) {
      assertEquals(sketch.getRank(i, EXCLUSIVE), (i - 1) / 10.0);
      assertEquals(sketch.getRank(i, EXCLUSIVE), (i - 1) / 10.0);
      assertEquals(sketch.getRank(i, INCLUSIVE), i / 10.0);
    }
    // inclusive = false (default)
    assertEquals(sketch.getQuantile(0, EXCLUSIVE), 1);
    assertEquals(sketch.getQuantile(0.1, EXCLUSIVE), 2);
    assertEquals(sketch.getQuantile(0.2, EXCLUSIVE), 3);
    assertEquals(sketch.getQuantile(0.3, EXCLUSIVE), 4);
    assertEquals(sketch.getQuantile(0.4, EXCLUSIVE), 5);
    assertEquals(sketch.getQuantile(0.5, EXCLUSIVE), 6);
    assertEquals(sketch.getQuantile(0.6, EXCLUSIVE), 7);
    assertEquals(sketch.getQuantile(0.7, EXCLUSIVE), 8);
    assertEquals(sketch.getQuantile(0.8, EXCLUSIVE), 9);
    assertEquals(sketch.getQuantile(0.9, EXCLUSIVE), 10);
    assertEquals(sketch.getQuantile(1, EXCLUSIVE), 10);
    // inclusive = true
    assertEquals(sketch.getQuantile(0, INCLUSIVE), 1);
    assertEquals(sketch.getQuantile(0.1, INCLUSIVE), 1);
    assertEquals(sketch.getQuantile(0.2, INCLUSIVE), 2);
    assertEquals(sketch.getQuantile(0.3, INCLUSIVE), 3);
    assertEquals(sketch.getQuantile(0.4, INCLUSIVE), 4);
    assertEquals(sketch.getQuantile(0.5, INCLUSIVE), 5);
    assertEquals(sketch.getQuantile(0.6, INCLUSIVE), 6);
    assertEquals(sketch.getQuantile(0.7, INCLUSIVE), 7);
    assertEquals(sketch.getQuantile(0.8, INCLUSIVE), 8);
    assertEquals(sketch.getQuantile(0.9, INCLUSIVE), 9);
    assertEquals(sketch.getQuantile(1, INCLUSIVE), 10);

    // getQuantile() and getQuantiles() equivalence
    {
      // inclusive = false (default)
      final double[] quantiles =
          sketch.getQuantiles(new double[] {0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1});
      for (int i = 0; i <= 10; i++) {
        assertEquals(sketch.getQuantile(i / 10.0), quantiles[i]);
      }
    }
    {
      // inclusive = true
      final double[] quantiles =
          sketch.getQuantiles(new double[] {0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1}, INCLUSIVE);
      for (int i = 0; i <= 10; i++) {
        assertEquals(sketch.getQuantile(i / 10.0, INCLUSIVE), quantiles[i]);
      }
    }
  }

  //private methods

  private static void checksForImproperK(final int k) {
    final String s = "Did not catch improper k: " + k;
    try {
      DoublesSketch.builder().setK(k);
      fail(s);
    } catch (SketchesArgumentException e) {
      //pass
    }
    try {
      DoublesSketch.builder().setK(k).build();
      fail(s);
    } catch (SketchesArgumentException e) {
      //pass
    }
    try {
      HeapUpdateDoublesSketch.newInstance(k);
      fail(s);
    } catch (SketchesArgumentException e) {
      //pass
    }
  }

  private static boolean sameStructurePredicate(final DoublesSketch mq1, final DoublesSketch mq2) {
    final boolean b1 =
      ( (mq1.getK() == mq2.getK())
        && (mq1.getN() == mq2.getN())
        && (mq1.getCombinedBufferItemCapacity()
            >= ClassicUtil.computeCombinedBufferItemCapacity(mq1.getK(), mq1.getN()))
        && (mq2.getCombinedBufferItemCapacity()
            >= ClassicUtil.computeCombinedBufferItemCapacity(mq2.getK(), mq2.getN()))
        && (mq1.getBaseBufferCount() == mq2.getBaseBufferCount())
        && (mq1.getBitPattern() == mq2.getBitPattern()) );

    final boolean b2;
    if (mq1.isEmpty()) {
      b2 = mq2.isEmpty();
    } else {
      b2 =  (mq1.getMinItem() == mq2.getMinItem()) && (mq1.getMaxItem() == mq2.getMaxItem());
    }
    return b1 && b2;
  }

  static UpdateDoublesSketch buildAndLoadQS(int k, int n) {
    return buildAndLoadQS(k, n, 0);
  }

  static UpdateDoublesSketch buildAndLoadQS(int k, int n, int startV) {
    UpdateDoublesSketch qs = DoublesSketch.builder().setK(k).build();
    for (int i=1; i<=n; i++) {
      qs.update(startV + i);
    }
    return qs;
  }

  @Test
  public void printlnTest() {
    println("PRINTING: "+this.getClass().getName());
    print("PRINTING: "+this.getClass().getName() + LS);
  }

  /**
   * @param s value to print
   */
  static void println(String s) {
    print(s+LS);
  }

  /**
   * @param s value to print
   */
  static void print(String s) {
    //System.err.print(s); //disable here
  }

}

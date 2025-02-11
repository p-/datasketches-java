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

import org.apache.datasketches.quantilescommon.QuantilesDoublesSketchIterator;
import org.testng.Assert;
import org.testng.annotations.Test;

public class KllDoublesSketchIteratorTest {

  @Test
  public void emptySketch() {
    KllDoublesSketch sketch = KllDoublesSketch.newHeapInstance();
    QuantilesDoublesSketchIterator it = sketch.iterator();
    Assert.assertFalse(it.next());
  }

  @Test
  public void oneItemSketch() {
    KllDoublesSketch sketch = KllDoublesSketch.newHeapInstance();
    sketch.update(0);
    QuantilesDoublesSketchIterator it = sketch.iterator();
    Assert.assertTrue(it.next());
    Assert.assertEquals(it.getQuantile(), 0f);
    Assert.assertEquals(it.getWeight(), 1);
    Assert.assertFalse(it.next());
  }

  @Test
  public void bigSketches() {
    for (int n = 1000; n < 100000; n += 2000) {
      KllDoublesSketch sketch = KllDoublesSketch.newHeapInstance();
      for (int i = 0; i < n; i++) {
        sketch.update(i);
      }
      QuantilesDoublesSketchIterator it = sketch.iterator();
      int count = 0;
      int weight = 0;
      while (it.next()) {
        count++;
        weight += (int)it.getWeight();
      }
      Assert.assertEquals(count, sketch.getNumRetained());
      Assert.assertEquals(weight, n);
    }
  }

}

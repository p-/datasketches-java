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

package org.apache.datasketches.common;

import org.apache.datasketches.memory.Memory;
import org.apache.datasketches.memory.WritableMemory;

/**
 * Methods of serializing and deserializing arrays of the object version of primitive types of
 * Number. The array can be a mix of primitive object types.
 *
 * <p>This class serializes numbers with a leading byte (ASCII character) indicating the type.
 * The class keeps the values byte aligned, even though only 3 bits are strictly necessary to
 * encode one of the 6 different primitives with object types that extend Number.</p>
 *
 * <p>Classes handled are: <code>Long</code>, <code>Integer</code>, <code>Short</code>,
 * <code>Byte</code>, <code>Double</code>, and <code>Float</code>.</p>
 *
 * @author Jon Malkin
 */
public class ArrayOfNumbersSerDe extends ArrayOfItemsSerDe<Number> {

  // values selected to enable backwards compatibility
  private static final byte LONG_INDICATOR    = 12;
  private static final byte INTEGER_INDICATOR = 9;
  private static final byte SHORT_INDICATOR   = 3;
  private static final byte BYTE_INDICATOR    = 2;
  private static final byte DOUBLE_INDICATOR  = 4;
  private static final byte FLOAT_INDICATOR   = 6;

  @Override
  public byte[] serializeToByteArray(final Number[] items) {
    int length = 0;
    for (final Number item: items) {
      if (item instanceof Long) {
        length += Byte.BYTES + Long.BYTES;
      } else if (item instanceof Integer) {
        length += Byte.BYTES + Integer.BYTES;
      } else if (item instanceof Short) {
        length += Byte.BYTES + Short.BYTES;
      } else if (item instanceof Byte) {
        length += Byte.BYTES << 1;
      } else if (item instanceof Double) {
        length += Byte.BYTES + Double.BYTES;
      } else if (item instanceof Float) {
        length += Byte.BYTES + Float.BYTES;
      } else {
        throw new SketchesArgumentException(
            "Item must be one of: Long, Integer, Short, Byte, Double, Float");
      }
    }
    final byte[] bytes = new byte[length];
    final WritableMemory mem = WritableMemory.writableWrap(bytes);
    long offsetBytes = 0;
    for (final Number item: items) {
      if (item instanceof Long) {
        mem.putByte(offsetBytes, LONG_INDICATOR);
        mem.putLong(offsetBytes + 1, item.longValue());
        offsetBytes += Byte.BYTES + Long.BYTES;
      } else if (item instanceof Integer) {
        mem.putByte(offsetBytes, INTEGER_INDICATOR);
        mem.putInt(offsetBytes + 1, item.intValue());
        offsetBytes += Byte.BYTES + Integer.BYTES;
      } else if (item instanceof Short) {
        mem.putByte(offsetBytes, SHORT_INDICATOR);
        mem.putShort(offsetBytes + 1, item.shortValue());
        offsetBytes += Byte.BYTES + Short.BYTES;
      } else if (item instanceof Byte) {
        mem.putByte(offsetBytes, BYTE_INDICATOR);
        mem.putByte(offsetBytes + 1, item.byteValue());
        offsetBytes += Byte.BYTES << 1;
      } else if (item instanceof Double) {
        mem.putByte(offsetBytes, DOUBLE_INDICATOR);
        mem.putDouble(offsetBytes + 1, item.doubleValue());
        offsetBytes += Byte.BYTES + Double.BYTES;
      } else { // (item instanceof Float) 0- already checked possibilities above
        mem.putByte(offsetBytes, FLOAT_INDICATOR);
        mem.putFloat(offsetBytes + 1, item.floatValue());
        offsetBytes += Byte.BYTES + Float.BYTES;
      }
    }
    return bytes;
  }

  @Override
  public Number[] deserializeFromMemory(final Memory mem, final int length) {
    final Number[] array = new Number[length];
    long offsetBytes = 0;
    for (int i = 0; i < length; i++) {
      Util.checkBounds(offsetBytes, Byte.BYTES, mem.getCapacity());
      final byte numType = mem.getByte(offsetBytes);
      offsetBytes += Byte.BYTES;

      switch (numType) {
        case LONG_INDICATOR:
          Util.checkBounds(offsetBytes, Long.BYTES, mem.getCapacity());
          array[i] = mem.getLong(offsetBytes);
          offsetBytes += Long.BYTES;
          break;
        case INTEGER_INDICATOR:
          Util.checkBounds(offsetBytes, Integer.BYTES, mem.getCapacity());
          array[i] = mem.getInt(offsetBytes);
          offsetBytes += Integer.BYTES;
          break;
        case SHORT_INDICATOR:
          Util.checkBounds(offsetBytes, Short.BYTES, mem.getCapacity());
          array[i] = mem.getShort(offsetBytes);
          offsetBytes += Short.BYTES;
          break;
        case BYTE_INDICATOR:
          Util.checkBounds(offsetBytes, Byte.BYTES, mem.getCapacity());
          array[i] = mem.getByte(offsetBytes);
          offsetBytes += Byte.BYTES;
          break;
        case DOUBLE_INDICATOR:
          Util.checkBounds(offsetBytes, Double.BYTES, mem.getCapacity());
          array[i] = mem.getDouble(offsetBytes);
          offsetBytes += Double.BYTES;
          break;
        case FLOAT_INDICATOR:
          Util.checkBounds(offsetBytes, Float.BYTES, mem.getCapacity());
          array[i] = mem.getFloat(offsetBytes);
          offsetBytes += Float.BYTES;
          break;
        default:
          throw new SketchesArgumentException("Unrecognized entry type reading Number array entry "
              + i + ": " + numType);
      }
    }

    return array;
  }
}

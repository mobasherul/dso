/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.io;

import com.tc.bytes.ITCByteBuffer;
import com.tc.bytes.TCByteBufferFactory;
import com.tc.test.TCTestCase;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Date;
import java.util.Random;

public class TCByteBufferInputStreamTest extends TCTestCase {

  private Random random = new SecureRandom();

  public void testExceptions() {
    try {
      new TCByteBufferInputStream((ITCByteBuffer) null);
      fail();
    } catch (NullPointerException npe) {
      // expected
    }

    try {
      new TCByteBufferInputStream((ITCByteBuffer[]) null);
      fail();
    } catch (NullPointerException npe) {
      // expected
    }

    try {
      new TCByteBufferInputStream(new ITCByteBuffer[] { TCByteBufferFactory.getInstance(false, 5), null });
      fail();
    } catch (NullPointerException npe) {
      // expected
    }

    try {
      TCByteBufferInputStream bbis = new TCByteBufferInputStream(TCByteBufferFactory.getInstance(false, 10));
      bbis.read(null);
      fail();
    } catch (NullPointerException npe) {
      // expected
    }

    try {
      TCByteBufferInputStream bbis = new TCByteBufferInputStream(TCByteBufferFactory.getInstance(false, 10));
      bbis.read(null, 1, 10);
      fail();
    } catch (NullPointerException npe) {
      // expected
    }

    try {
      TCByteBufferInputStream bbis = new TCByteBufferInputStream(TCByteBufferFactory.getInstance(false, 10));
      bbis.read(new byte[10], 10, 1);
      fail();
    } catch (IndexOutOfBoundsException ioobe) {
      // expected
    }

    try {
      TCByteBufferInputStream bbis = new TCByteBufferInputStream(TCByteBufferFactory.getInstance(false, 10));
      bbis.read(new byte[10], -1, 1);
      fail();
    } catch (IndexOutOfBoundsException ioobe) {
      // expected
    }

    try {
      TCByteBufferInputStream bbis = new TCByteBufferInputStream(TCByteBufferFactory.getInstance(false, 10));
      bbis.read(new byte[10], 1, -1);
      fail();
    } catch (IndexOutOfBoundsException ioobe) {
      // expected
    }

    TCByteBufferInputStream bbis = new TCByteBufferInputStream(TCByteBufferFactory.getInstance(false, 10));
    for (int i = 0; i < 10; i++) {
      bbis.close();
    }

    try {
      bbis.read();
      fail();
    } catch (IllegalStateException ise) {
      // expected
    }

    try {
      bbis.read(new byte[1]);
      fail();
    } catch (IllegalStateException ise) {
      // expected
    }

    try {
      bbis.read(new byte[1], 0, 1);
      fail();
    } catch (IllegalStateException ise) {
      // expected
    }
  }

  public void testToArray() {
    for (int i = 0; i < 250; i++) {
      log("testToArray(" + i + ")");
      ITCByteBuffer[] data = getRandomDataNonZeroLength();
      TCByteBufferInputStream bbis = new TCByteBufferInputStream(data);

      int read = random.nextInt(bbis.available());
      for (int r = 0; r < read; r++) {
        bbis.read();
      }

      TCByteBufferInputStream compare = new TCByteBufferInputStream(bbis.toArray());
      while (compare.available() > 0) {
        int orig = bbis.read();
        int comp = compare.read();
        assertEquals(orig, comp);
      }

      assertEquals(0, compare.available());
      assertEquals(0, bbis.available());
    }
  }

  public void testLimit() {
    for (int i = 0; i < 250; i++) {
      log("testLimit(" + i + ")");
      ITCByteBuffer[] data = getRandomDataNonZeroLength();
      TCByteBufferInputStream bbis = new TCByteBufferInputStream(data);

      int read = random.nextInt(bbis.available());
      for (int r = 0; r < read; r++) {
        bbis.read();
      }

      int which = random.nextInt(3);
      switch (which) {
        case 0: {
          bbis.limit(0);
          assertEquals(0, bbis.available());
          break;
        }
        case 1: {
          int before = bbis.available();
          bbis.limit(bbis.available());
          assertEquals(before, bbis.available());
          break;
        }
        case 2: {
          bbis.limit(random.nextInt(bbis.available()));
          break;
        }
        default: {
          throw new RuntimeException("" + which);
        }
      }

      TCByteBufferInputStream compare = new TCByteBufferInputStream(bbis.toArray());
      while (compare.available() > 0) {
        int orig = bbis.read();
        int comp = compare.read();
        assertEquals(orig, comp);
      }

      assertEquals(0, compare.available());
      assertEquals(0, bbis.available());
    }
  }

  public void testDuplicateAndLimitZeroLen() {
    TCByteBufferInputStream bbis = new TCByteBufferInputStream(new ITCByteBuffer[] {});

    assertEquals(0, bbis.available());
    assertEquals(0, bbis.duplicateAndLimit(0).available());
  }

  public void testDuplicateAndLimit() {
    for (int i = 0; i < 50; i++) {
      log("testDuplicateAndLimit(" + i + ")");
      ITCByteBuffer[] data = getRandomDataNonZeroLength();
      TCByteBufferInputStream bbis = new TCByteBufferInputStream(data);

      int length = bbis.available();
      assertTrue(length > 0);
      int start = random.nextInt(length);
      bbis.skip(start);
      int limit = random.nextInt(bbis.available());

      TCByteBufferInput dupe = bbis.duplicateAndLimit(limit);
      for (int n = 0; n < limit; n++) {
        int dupeByte = dupe.read();
        int origByte = bbis.read();

        assertTrue(dupeByte != -1);
        assertTrue(origByte != -1);

        assertEquals(origByte, dupeByte);
      }

      assertEquals(0, dupe.available());
    }
  }

  public void testDuplicate() {
    for (int i = 0; i < 250; i++) {
      log("testDuplicate(" + i + ")");
      ITCByteBuffer[] data = getRandomData();
      TCByteBufferInputStream bbis = new TCByteBufferInputStream(data);
      bbis.read();
      TCByteBufferInput dupe = bbis.duplicate();
      assertEquals(bbis.available(), dupe.available());
      int read = bbis.read();
      if (read != -1) {
        // reading from one stream doesn't affect the other
        assertEquals(dupe.available() - 1, bbis.available());
        int dupeRead = dupe.read();
        assertEquals(read, dupeRead);
      }

      bbis = new TCByteBufferInputStream(getRandomDataNonZeroLength());
      int dupeStart = random.nextInt(bbis.getTotalLength());
      for (int n = 0; n < dupeStart; n++) {
        int b = bbis.read();
        assertTrue(b >= 0);
      }
      dupe = bbis.duplicate();
      while (bbis.available() > 0) {
        int n1 = bbis.read();
        int n2 = dupe.read();
        assertEquals(n1, n2);
      }
      assertEquals(0, dupe.available());
      assertEquals(0, bbis.available());
    }
  }

  public void testOffsetReadArray() {
    for (int i = 0; i < 25; i++) {
      log("testOffsetReadArray(" + i + ")");
      testOffsetReadArray(getRandomData());
    }
  }

  private void testOffsetReadArray(ITCByteBuffer[] data) {
    final int numBufs = data.length == 0 ? 0 : data.length - 1;

    reportLengths(data);
    final long totalLength = length(data);
    TCByteBufferInputStream bbis = new TCByteBufferInputStream(data);
    assertEquals(totalLength, bbis.available());
    assertEquals(totalLength, bbis.getTotalLength());

    int index = 0;
    int bytesRead = 0;
    while (bbis.available() > 0) {
      byte[] buffer = new byte[random.nextInt(50) + 1];
      byte fill = (byte) random.nextInt();
      Arrays.fill(buffer, fill);

      final int offset = random.nextInt(buffer.length);
      final int length = random.nextInt(buffer.length - offset);
      final int read = bbis.read(buffer, offset, length);
      if (read == -1) {
        break;
      }
      bytesRead += read;

      for (int i = 0; i < offset; i++) {
        assertEquals(fill, buffer[i]);
      }

      for (int i = offset + length + 1; i < buffer.length; i++) {
        assertEquals(fill, buffer[i]);
      }

      for (int i = 0; i < read; i++) {
        for (; !data[index].hasRemaining() && index < numBufs; index++) {
          //
        }

        assertEquals(data[index].get(), buffer[offset + i]);
      }
    }

    if (index < numBufs) {
      for (; index < numBufs; index++) {
        assertEquals(0, data[index + 1].limit());
      }
    }

    assertEquals(index, numBufs);
    if (numBufs > 0) {
      assertEquals(0, data[numBufs].remaining());
    }

    assertEquals(bytesRead, totalLength);
    assertEquals(0, bbis.available());
    assertEquals(-1, bbis.read());
    assertEquals(-1, bbis.read(new byte[10]));
    assertEquals(-1, bbis.read(new byte[10], 0, 3));
  }

  public void testReadBasics() {
    for (int i = 0; i < 250; i++) {
      log("testReadBasics(" + i + ")");
      testReadBasics(getRandomData());
    }
  }

  public void testBasic() {
    for (int i = 0; i < 250; i++) {
      log("testBasic(" + i + ")");
      ITCByteBuffer[] data = getRandomDataNonZeroLength();
      TCByteBufferInputStream bbis = new TCByteBufferInputStream(data);

      assertTrue(bbis.available() > 0);

      final byte b = data[0].get(0);
      int read = bbis.read();
      assertEquals(b, (byte) read);

      byte[] readArray = new byte[1];
      bbis = new TCByteBufferInputStream(data);
      bbis.read(readArray);
      assertEquals(b, readArray[0]);

      bbis = new TCByteBufferInputStream(data);
      bbis.read(readArray, 0, 1);
      assertEquals(b, readArray[0]);

      bbis = new TCByteBufferInputStream(data);
      int avail = bbis.available();
      bbis.read(new byte[0]);
      assertEquals(avail, bbis.available());
      bbis.read(new byte[0], 0, 0);
      assertEquals(avail, bbis.available());
      bbis.read(new byte[10], 0, 0);
      assertEquals(avail, bbis.available());
    }
  }

  public void testMarkReset() throws IOException {
    ITCByteBuffer[] data = createBuffersWithRandomData(4, 10);
    TCByteBufferInputStream bbis = new TCByteBufferInputStream(data);

    bbis.readInt();
    bbis.readInt();
    bbis.readInt(); // should be 2 bytes into 2nd buffer by now

    try {
      bbis.tcReset();
      fail();
    } catch (IllegalStateException ise) {
      // expected
    }

    int avail = bbis.available();
    bbis.mark();

    int i1 = bbis.readInt();
    int i2 = bbis.readInt();
    int i3 = bbis.readInt(); // should be 4 bytes into 3rd buffer now

    bbis.tcReset();

    assertEquals(avail, bbis.available());
    assertEquals(i1, bbis.readInt());
    assertEquals(i2, bbis.readInt());
    assertEquals(i3, bbis.readInt());

    try {
      bbis.tcReset();
      fail();
    } catch (IllegalStateException ise) {
      // expected
    }
  }

  public void testMarkReset2() throws IOException {
    // This one stays on the same buffer between mark and reset
    ITCByteBuffer[] data = createBuffersWithRandomData(1, 10);
    TCByteBufferInputStream bbis = new TCByteBufferInputStream(data);

    bbis.readInt();
    int avail = bbis.available();
    bbis.mark();

    int i1 = bbis.readInt();

    bbis.tcReset();

    assertEquals(avail, bbis.available());
    assertEquals(i1, bbis.readInt());
  }

  private void testReadBasics(ITCByteBuffer[] data) {
    final int numBufs = data.length == 0 ? 0 : data.length - 1;

    reportLengths(data);
    final long len = length(data);
    TCByteBufferInputStream bbis = new TCByteBufferInputStream(data);
    assertEquals(len, bbis.available());
    assertEquals(len, bbis.getTotalLength());

    int index = 0;
    for (int i = 0; i < len; i++) {
      for (; !data[index].hasRemaining() && index < numBufs; index++) {
        //
      }

      assertEquals(len - i, bbis.available());
      int fromStream = bbis.read();
      assertFalse(fromStream < 0);

      byte original = data[index].get();

      assertEquals(original, (byte) fromStream);
    }

    if (index < numBufs) {
      for (; index < numBufs; index++) {
        assertEquals(0, data[index + 1].limit());
      }
    }

    assertEquals(index, numBufs);
    if (numBufs > 0) {
      assertEquals(0, data[numBufs].remaining());
    }

    assertEquals(0, bbis.available());
    assertEquals(-1, bbis.read());
    assertEquals(-1, bbis.read(new byte[10]));
    assertEquals(-1, bbis.read(new byte[10], 0, 3));
  }

  private void reportLengths(ITCByteBuffer[] data) {
    // comment this if tests are failing
    if (true) return;

    System.err.print(data.length + " buffers with lengths: ");
    for (int i = 0; i < data.length; i++) {
      System.err.print(data[i].limit() + " ");
    }
    System.err.println();
  }

  public void testTrailingZeroLength() {
    ITCByteBuffer[] data = getRandomData();
    ITCByteBuffer zeroLen = TCByteBufferFactory.getInstance(false, 0);

    for (int i = 0; i < Math.min(10, data.length); i++) {
      data[data.length - i - 1] = zeroLen;
      rewindBuffers(data);
      testReadArrayBasics(data);

      rewindBuffers(data);
      testOffsetReadArray(data);
    }
  }

  public void testRandomZeroLength() {
    ITCByteBuffer[] data = getRandomDataNonZeroLength();

    ITCByteBuffer zeroLen = TCByteBufferFactory.getInstance(false, 0);

    int num = Math.min(25, data.length);

    for (int i = 0; i < num; i++) {
      data[random.nextInt(data.length)] = zeroLen;
      rewindBuffers(data);
      testReadArrayBasics(data);

      rewindBuffers(data);
      testOffsetReadArray(data);
    }
  }

  private ITCByteBuffer[] getRandomDataNonZeroLength() {
    ITCByteBuffer[] data;
    do {
      data = getRandomData();
    } while ((data.length == 0) || (data[0].limit() == 0));

    return data;
  }

  public void testReadArrayBasics() {
    for (int i = 0; i < 50; i++) {
      log("testReadArrayBasics(" + i + ")");
      testReadArrayBasics(getRandomData());
    }
  }

  private void rewindBuffers(ITCByteBuffer[] data) {
    for (int i = 0; i < data.length; i++) {
      data[i].rewind();
    }
  }

  private void testReadArrayBasics(ITCByteBuffer[] data) {
    final int numBufs = data.length == 0 ? 0 : data.length - 1;

    reportLengths(data);
    final long len = length(data);
    TCByteBufferInputStream bbis = new TCByteBufferInputStream(data);
    assertEquals(len, bbis.available());
    assertEquals(len, bbis.getTotalLength());

    int counter = 0;
    int index = 0;
    while (bbis.available() > 0) {
      byte[] buffer = new byte[random.nextInt(10) + 1];
      int read = bbis.read(buffer);

      if (read <= 0) {
        assertEquals(0, bbis.available());
        break;
      }

      counter += read;
      assertEquals(len - counter, bbis.available());

      for (int i = 0; i < read; i++) {
        for (; !data[index].hasRemaining() && index < numBufs; index++) {
          //
        }
        assertEquals(data[index].get(), buffer[i]);
      }
    }

    if (index < numBufs) {
      for (; index < numBufs; index++) {
        assertEquals(0, data[index + 1].limit());
      }
    }

    assertEquals(numBufs, index);
    if (numBufs > 0) {
      assertEquals(0, data[numBufs].remaining());
    }

    assertEquals(0, bbis.available());
    assertEquals(-1, bbis.read());
    assertEquals(-1, bbis.read(new byte[10]));
    assertEquals(-1, bbis.read(new byte[10], 0, 3));
  }

  public void testSkip() {
    for (int i = 0; i < 250; i++) {
      log("testSkip(" + i + ")");
      ITCByteBuffer[] data = getRandomDataNonZeroLength();
      TCByteBufferInputStream is = new TCByteBufferInputStream(data);

      assertEquals(0, is.skip(0));
      assertEquals(0, is.skip(-1));

      int len = is.getTotalLength();
      assertEquals(len, is.available());
      long skipped = is.skip(len - 1);
      assertEquals(len - 1, skipped);
      assertEquals(1, is.available());
      is.read();
      assertEquals(0, is.available());
      int read = is.read();
      assertEquals(-1, read);

      try {
        is.skip(Integer.MAX_VALUE + 1L);
        fail();
      } catch (IllegalArgumentException iae) {
        // expected
      }
    }
  }

  private void log(String msg) {
    System.err.println(new Date() + " - " + msg);
  }

  public void testZeroLength() {
    ITCByteBuffer[] data = new ITCByteBuffer[] {};
    long len = length(data);
    assertEquals(0, len);
    TCByteBufferInputStream bbis = new TCByteBufferInputStream(data);
    assertEquals(0, bbis.available());
    assertEquals(0, bbis.getTotalLength());
    assertEquals(-1, bbis.read());
    assertEquals(-1, bbis.read(new byte[10]));
    assertEquals(-1, bbis.read(new byte[10], 1, 3));
    assertEquals(0, bbis.read(new byte[10], 1, 0));

    testReadArrayBasics(data);
    testReadBasics(data);
    testOffsetReadArray(data);

    ITCByteBuffer[] toArray = bbis.toArray();
    assertEquals(0, toArray.length);
  }

  private long length(ITCByteBuffer[] data) {
    long rv = 0;
    for (int i = 0; i < data.length; i++) {
      rv += data[i].limit();
    }
    return rv;
  }

  private ITCByteBuffer[] createBuffersWithRandomData(int number, int size) {
    ITCByteBuffer[] rv = new ITCByteBuffer[number];
    for (int i = 0; i < rv.length; i++) {
      rv[i] = TCByteBufferFactory.getInstance(false, size);
      byte[] bites = new byte[size];
      random.nextBytes(bites);
      rv[i].put(bites);
      rv[i].flip();
    }

    return rv;
  }

  private ITCByteBuffer[] getRandomData() {
    final int num = random.nextInt(20);
    final int maxSize = random.nextInt(200);

    ITCByteBuffer[] rv = new ITCByteBuffer[num];

    for (int i = 0; i < num; i++) {
      rv[i] = TCByteBufferFactory.getInstance(false, maxSize > 0 ? random.nextInt(maxSize) : 0);
      random.nextBytes(rv[i].array());
    }

    return rv;
  }

}

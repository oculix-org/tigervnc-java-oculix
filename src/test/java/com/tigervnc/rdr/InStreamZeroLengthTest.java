package com.tigervnc.rdr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract: zero bytes are always available. CMsgReader asks for a field's length with
 * hasDataOrRestore(len), and a server may send a length of 0 (an empty desktop name, an
 * empty clipboard, a fence with no payload). Before the fix, check() divided by the
 * item size, so these threw ArithmeticException "/ by zero".
 */
public class InStreamZeroLengthTest {

  @Test
  void checkNoWaitZero_onAnEmptyStream_isTrue() {
    InStream in = new MemInStream(new byte[0], 0, 0);
    assertTrue(in.checkNoWait(0));
  }

  @Test
  void checkNoWaitZero_withDataBuffered_isTrue() {
    InStream in = new MemInStream(new byte[] {1, 2, 3}, 0, 3);
    assertTrue(in.checkNoWait(0));
  }

  @Test
  void zeroLengthField_readsAsCMsgReaderDoes_andLeavesTheNextByte() {
    // A U32 length of 0, then the first byte of the next message (3, ServerCutText).
    InStream in = new MemInStream(new byte[] {0, 0, 0, 0, 3}, 0, 5);
    in.setRestorePoint();
    int len = in.readU32();
    assertEquals(0, len);
    assertTrue(in.hasDataOrRestore(len));
    in.clearRestorePoint();
    assertEquals(3, in.readU8());
  }
}

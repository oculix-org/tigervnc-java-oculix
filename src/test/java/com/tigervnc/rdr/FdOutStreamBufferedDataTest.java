package com.tigervnc.rdr;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import com.tigervnc.network.FileDescriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract: hasBufferedData() is true only while bytes are waiting to be sent. CConn asks it
 * whether to wait for the socket to become writable; before the fix it counted every byte ever
 * sent, so after the first message the viewer waited for writability forever, and an idle
 * viewer spun a full core (#25).
 */
public class FdOutStreamBufferedDataTest {

  /** Takes at most {@code budget} bytes in all, then reports "would block" (0). */
  static class SinkFd implements FileDescriptor {
    int budget;
    int written;
    SinkFd(int budget) { this.budget = budget; }
    public int read(ByteBuffer buf, int length) { return 0; }
    public int write(ByteBuffer buf, int length) {
      int n = Math.min(length, budget);
      budget -= n;
      buf.position(buf.position() + n);
      written += n;
      return n;
    }
    public int select(int interestOps, Integer timeout) { return 1; }
    public void close() throws IOException {}
  }

  @Test
  void aNewStream_hasNoBufferedData() {
    assertFalse(new FdOutStream(new SinkFd(1024), false, 0, 0).hasBufferedData());
  }

  @Test
  void unflushedBytes_areBufferedData() {
    FdOutStream out = new FdOutStream(new SinkFd(1024), false, 0, 0);
    out.writeU32(0x01020304);
    assertTrue(out.hasBufferedData());
  }

  @Test
  void afterAFullFlush_thereIsNoBufferedData_thoughBytesWereSent() {
    SinkFd fd = new SinkFd(1024);
    FdOutStream out = new FdOutStream(fd, false, 0, 0);
    out.writeU32(0x01020304);
    out.flush();
    assertEquals(4, fd.written);
    assertEquals(4, out.length());   // length() is the stream position, kept as it was
    assertFalse(out.hasBufferedData());
  }

  @Test
  void afterAPartialFlush_theRestIsStillBufferedData() {
    SinkFd fd = new SinkFd(3);
    FdOutStream out = new FdOutStream(fd, false, 0, 0);
    out.writeU32(0x01020304);
    out.flush();                     // non-blocking: 3 bytes go, the 4th would block
    assertEquals(3, fd.written);
    assertTrue(out.hasBufferedData());
  }
}

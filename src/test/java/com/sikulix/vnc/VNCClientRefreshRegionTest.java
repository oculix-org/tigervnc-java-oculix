package com.sikulix.vnc;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * #18: refreshFramebuffer(x, y, w, h, incremental) asks the server for the w x h region at
 * (x, y). It used to pass w and h as the bottom-right corner, so the request covered
 * (w - x) x (h - y), wrapping to about 65000 when w < x or h < y.
 *
 * A minimal RFB 3.8 server (security None, 1920x1080) runs on a loopback socket and reads the
 * FramebufferUpdateRequest that follows the call.
 */
class VNCClientRefreshRegionTest {

  private static final int WIDTH = 1920;
  private static final int HEIGHT = 1080;

  @Test
  void aRegionRightOfAndBelowItsSizeIsRequestedAsGiven() throws Exception {
    assertRequest(1000, 600, 400, 300);
  }

  @Test
  void aOneByOneRegionIsRequestedAsOneByOne() throws Exception {
    assertRequest(10, 10, 1, 1);
  }

  private void assertRequest(int x, int y, int w, int h) throws Exception {
    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      listener.setSoTimeout(10000);
      // The fake server blocks on accept and reads, so it gets its own thread, not the common pool.
      CompletableFuture<int[]> seen = new CompletableFuture<>();
      Thread server = new Thread(() -> {
        try {
          seen.complete(serve(listener, x, y));
        } catch (Throwable failure) {
          seen.completeExceptionally(failure);
        }
      }, "fake-rfb-server");
      server.setDaemon(true);
      server.start();
      VNCClient client = assertTimeoutPreemptively(Duration.ofSeconds(10),
          () -> VNCClient.connect("127.0.0.1", listener.getLocalPort(), "", true));
      try {
        client.refreshFramebuffer(x, y, w, h, false);
        int[] request = seen.get(10, TimeUnit.SECONDS);
        assertArrayEquals(new int[] {0, x, y, w, h}, request,
            "FramebufferUpdateRequest {incremental, x, y, w, h}");
      } finally {
        client.close();
      }
    }
  }

  /**
   * Handshakes, then returns the first non-incremental update request for (x, y). x and y must
   * not both be 0, or the client's own full-screen request would match.
   */
  private static int[] serve(ServerSocket listener, int x, int y) {
    try (Socket socket = listener.accept()) {
      socket.setSoTimeout(10000);
      DataInputStream in = new DataInputStream(socket.getInputStream());
      DataOutputStream out = new DataOutputStream(socket.getOutputStream());
      out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      in.readFully(new byte[12]);
      out.write(new byte[] {1, 1});          // one security type: None
      out.flush();
      in.readUnsignedByte();                 // the client's choice
      out.writeInt(0);                       // SecurityResult OK
      out.flush();
      in.readUnsignedByte();                 // ClientInit
      out.writeShort(WIDTH);
      out.writeShort(HEIGHT);
      // 32bpp, depth 24, little-endian, true colour, rgb888
      out.write(new byte[] {32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 16, 8, 0, 0, 0, 0});
      byte[] name = "refresh".getBytes(StandardCharsets.US_ASCII);
      out.writeInt(name.length);
      out.write(name);
      out.flush();
      while (true) {
        int type = in.readUnsignedByte();
        switch (type) {
          case 0:  // SetPixelFormat
            in.readFully(new byte[19]);
            break;
          case 2:  // SetEncodings
            in.readUnsignedByte();
            int n = in.readUnsignedShort();
            in.readFully(new byte[4 * n]);
            break;
          case 3:  // FramebufferUpdateRequest
            int incremental = in.readUnsignedByte();
            int rx = in.readUnsignedShort();
            int ry = in.readUnsignedShort();
            int rw = in.readUnsignedShort();
            int rh = in.readUnsignedShort();
            if (incremental == 0 && rx == x && ry == y) {
              return new int[] {incremental, rx, ry, rw, rh};
            }
            break;
          case 4:  // KeyEvent
            in.readFully(new byte[7]);
            break;
          case 5:  // PointerEvent
            in.readFully(new byte[5]);
            break;
          case 6:  // ClientCutText
            in.readFully(new byte[3]);
            in.readFully(new byte[in.readInt()]);
            break;
          default:
            throw new IllegalStateException("unexpected client message type " + type);
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}

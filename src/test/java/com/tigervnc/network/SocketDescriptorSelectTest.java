package com.tigervnc.network;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract: SocketDescriptor.select() leaves the channel registered with no selector of its own,
 * so another selector (Fl's, in the viewer) still gets OP_READ for the socket. On Windows, while
 * the socket stayed registered with SocketDescriptor's selector, Fl's selector missed the server's
 * reply almost every time (#25); the viewer then never read SecurityResult and stalled at login.
 * The second test is that scenario; it can only fail on Windows, the first one fails anywhere.
 */
public class SocketDescriptorSelectTest {

  ServerSocket server;
  Socket peer;
  SocketDescriptor sd;
  CountDownLatch requestSeen = new CountDownLatch(1);
  CountDownLatch sendReply = new CountDownLatch(1);

  @BeforeEach
  void connect() throws Exception {
    server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    Thread t = new Thread(() -> {
      try {
        peer = server.accept();
        peer.setSoTimeout(5000);
        InputStream in = peer.getInputStream();
        OutputStream out = peer.getOutputStream();
        if (in.readNBytes(12).length == 12) requestSeen.countDown();
        if (sendReply.await(5, TimeUnit.SECONDS)) { out.write(new byte[]{0, 0, 0, 0}); out.flush(); }
      } catch (Exception e) { }
    }, "SocketDescriptorSelectTest peer");
    t.setDaemon(true);
    t.start();
    sd = new SocketDescriptor();
    sd.setChannel(SocketChannel.open(new InetSocketAddress(server.getInetAddress(), server.getLocalPort())));
  }

  @AfterEach
  void close() throws Exception {
    sd.close();
    if (peer != null) peer.close();
    server.close();
  }

  void sendRequest() throws Exception {
    ByteBuffer req = ByteBuffer.wrap(new byte[12]);
    while (req.hasRemaining()) {
      assertTrue(sd.select(SelectionKey.OP_WRITE, null) > 0);
      sd.write(req, req.remaining());
    }
  }

  @Test
  void afterSelect_theChannelIsNotRegisteredWithItsSelector() throws Exception {
    assertNull(sd.channel.keyFor(sd.selector), "after connect");
    sendRequest();
    assertNull(sd.channel.keyFor(sd.selector), "after a blocking OP_WRITE select");
    assertTrue(requestSeen.await(5, TimeUnit.SECONDS));
    assertEquals(0, sd.select(SelectionKey.OP_READ, 0));
    assertNull(sd.channel.keyFor(sd.selector), "after a selectNow that found nothing");
    sendReply.countDown();
    assertTrue(sd.select(SelectionKey.OP_READ, 5000) > 0);
    assertNull(sd.channel.keyFor(sd.selector), "after a select that found data");
  }

  @Test
  void anotherSelector_isToldWhenTheReplyArrives() throws Exception {
    try (Selector other = Selector.open()) {
      SelectionKey key = sd.channel.register(other, SelectionKey.OP_READ);   // Fl.add_fd
      sendRequest();
      assertTrue(requestSeen.await(5, TimeUnit.SECONDS));
      assertEquals(0, sd.select(SelectionKey.OP_READ, 0));                  // checkNoWait: nothing yet
      sendReply.countDown();
      assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
        while (true) {                                                       // Fl.wait: select(100)
          if (other.select(100) > 0 && key.isReadable()) return;
          other.selectedKeys().clear();
        }
      }, "the other selector never reported OP_READ for the reply");
      ByteBuffer reply = ByteBuffer.allocate(4);
      assertEquals(4, sd.read(reply, 4));
    }
  }
}

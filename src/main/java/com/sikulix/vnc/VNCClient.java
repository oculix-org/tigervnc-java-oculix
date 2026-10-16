/*
 * Copyright (c) 2017 - sikulix.com - MIT license
 */
package com.sikulix.vnc;

import com.tigervnc.network.TcpSocket;
import com.tigervnc.rdr.FdInStreamBlockCallback;
import com.tigervnc.rfb.*;
import com.tigervnc.rfb.Exception;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;

import static com.sikulix.vnc.VNCClipboard.*;

/**
 * A headless VNC client: one thread reads and decodes the server messages
 * ({@link #processMessages()}), any other thread may send input, request updates and copy
 * the frame buffer.
 *
 * Outgoing messages are serialised by TigerVNC's writer, which locks its output stream per
 * message. The frame buffer is swapped through a volatile field and copied under its image
 * lock. The decoder threads belong to the reading thread: they are stopped by that thread on
 * its way out, or by the connecting thread when the handshake fails.
 */
public class VNCClient extends CConnection implements FdInStreamBlockCallback, Closeable {

  private final TcpSocket sock;
  private volatile boolean shuttingDown = false;
  private volatile boolean reading = false;
  private volatile VNCFrameBuffer buffer;
  static LogWriter vlog = new LogWriter("VNCClient");

  static {
    CSecurity.msg = new RefusingMsgBox();
  }

  public static void onDebugging() {
    LogWriter.setLogParams("300");
  }

  public static void offDebugging() {
    LogWriter.setLogParams("30");
  }

  /**
   * Opens the connection and runs the RFB handshake until the session is usable.
   * Interrupting the calling thread abandons the handshake; on abandon or failure the
   * socket is closed before the exception reaches the caller.
   */
  public static VNCClient connect(String address, int port, String password, boolean shareConnection) {
    VNCClient client = new VNCClient(address, port, password, shareConnection);
    try {
      while (client.state() != stateEnum.RFBSTATE_NORMAL) {
        if (Thread.currentThread().isInterrupted()) {
          throw new Exception("VNCClient: handshake with " + address + ":" + port + " interrupted");
        }
        client.processMsg();
      }
    } catch (RuntimeException | Error failure) {
      client.abandon(failure);
      throw failure;
    }
    vlog.debug("running");
    return client;
  }

  /**
   * Closes the socket of a handshake that will not complete and stops the decoder threads;
   * close errors stay attached to the cause.
   */
  private void abandon(Throwable cause) {
    shuttingDown = true;
    try {
      sock.shutdown();
    } catch (RuntimeException e) {
      cause.addSuppressed(e);
    }
    try {
      sock.close();
    } catch (IOException | RuntimeException e) {
      cause.addSuppressed(e);
    }
    stopDecoder();
  }

  private VNCClient(String address, int port, final String password, boolean shareConnection) {
    this.security = new ThreadLocalSecurityClient(new BasicUserPasswdGetter(password));
    this.setPreferredEncoding(Encodings.encodingZRLE);
    this.setShared(shareConnection);

    setServerName(address);
    setServerPort(port);
    this.sock = new TcpSocket(this.getServerName(), this.getServerPort());
    this.sock.inStream().setBlockCallback(this);
    this.setStreams(this.sock.inStream(), this.sock.outStream());
    this.initialiseProtocol();
  }

  /**
   * Called by TigerVNC once the server is initialised, before the first update request:
   * installs a frame buffer of the server's size and asks for {@link VNCFrameBuffer#PIXEL_FORMAT}.
   */
  @Override
  public void initDone() {
    installFrameBuffer();
    setPF(VNCFrameBuffer.PIXEL_FORMAT);
  }

  /** Called by TigerVNC when the server's desktop size changes: replaces the frame buffer. */
  @Override
  public void resizeFramebuffer() {
    installFrameBuffer();
  }

  /** Hands TigerVNC a new frame buffer of the server's size, then publishes it to readers. */
  private void installFrameBuffer() {
    VNCFrameBuffer fb = new VNCFrameBuffer(server.width(), server.height());
    setFramebuffer(fb);
    buffer = fb;
  }

  public void serverCutText(String content, int length) {
    if (length > 0) {
      VNCClipboard.putTextToClipboard(PLAIN, UTF8, CHAR_BUFFER, content);
    }
  }

  @Override
  public void clientRedirect(int i, String s, String s1) {

  }

  public void setColourMapEntries(int offset, int nbColors, int[] rgb) {
//    buffer.setColourMapEntries(offset, nbColors, rgb);
  }

  @Override
  public void bell() {

  }

  /** Ignores the server's cursor shape: a headless client draws no cursor. */
  @Override
  public void setCursor(int width, int height, Point hotspot, byte[] data) {
  }

  /** Requests a full, non-incremental update of the whole frame buffer. */
  @Override
  public void refreshFramebuffer() {
    Rectangle bounds = getBounds();
    refreshFramebuffer(0, 0, bounds.width, bounds.height, false);
  }

  /**
   * Sends FramebufferUpdateRequest message to server.
   *
   * @param x           X coordinate of desired region
   * @param y           Y coordinate of desired region
   * @param w           Width of desired region
   * @param h           Height of desired region
   * @param incremental Zero sends entire desktop, One sends changes only.
   */
  public void refreshFramebuffer(int x, int y, int w, int h, boolean incremental) {
    writer().writeFramebufferUpdateRequest(new Rect(x, y, w, h), incremental);
  }

  /**
   * Tells VNC server to depress key.
   *
   * @param key X Window System Keysym for key.
   * @throws IOException If there is a socket error.
   */
  public void keyDown(int key) throws IOException {
    writer().writeKeyEvent(key, true);
  }

  /**
   * Tells VNC server to release key.
   *
   * @param key X Window System Keysym for key.
   * @throws IOException If there is a socket error.
   */
  public void keyUp(int key) throws IOException {
    writer().writeKeyEvent(key, false);
  }

  /**
   * Tells VNC server to perform a mouse event. bOne through bEight are mouse
   * buttons one through eight respectively.  A zero means release that
   * button, and a one means depress that button.
   *
   * @param buttonState logical or of BUTTON_N_DOWN
   * @param x           X coordinate of action
   * @param y           Y coordinate of action
   * @throws IOException If there is a socket error.
   */
  public void mouseEvent(int buttonState, int x, int y) throws IOException {
    writer().writePointerEvent(new Point(x, y), buttonState);
  }

  /**
   * Closes the connection. The reading thread stops the decoder threads when its read fails;
   * if no thread is reading, they are stopped here.
   */
  @Override
  public void close() {
    this.shuttingDown = true;
    if (this.sock != null) {
      this.sock.shutdown();
    }
    if (!reading) {
      stopDecoder();
    }
    vlog.debug("shutting down");
  }

  /** Stops TigerVNC's decoder threads; safe to call more than once and from several threads. */
  private synchronized void stopDecoder() {
    super.close();
  }

  public boolean isShuttingDown() {
    return  shuttingDown;
  }

  /**
   * Returns the VNCClient Object as a string
   */
  public String toString() {
    return "VNCClient: " + getServerName() + ":" + getServerPort();
  }

  /** The size of the current frame buffer, which is what {@link #getFrameBuffer} can copy from. */
  public Rectangle getBounds() {
    VNCFrameBuffer fb = buffer;
    return new Rectangle(0, 0, fb.width(), fb.height());
  }

  /** Copies a region of the current frame buffer; parts outside it stay black. */
  public BufferedImage getFrameBuffer(int x, int y, int w, int h) {
    return buffer.getImage(x, y, w, h);
  }

  @Override
  public void blockCallback() {
    try {
      synchronized (this) {
        this.wait(1L);
      }
    } catch (InterruptedException var4) {
      throw new Exception(var4.getMessage());
    }
  }

  /**
   * Reads and decodes server messages until {@link #close()} is called or the connection fails,
   * then stops the decoder threads. Only one thread may run this.
   */
  public void processMessages() {
    reading = true;
    try {
      while (!shuttingDown) {
        processMsg();
      }
    } finally {
      stopDecoder();
      reading = false;
    }
  }

}

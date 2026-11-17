package com.sikulix.vnc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract: the client chooses a security type even when the server's RFB 3.8 security-types
 * message arrives in two TCP segments, the count byte first and the list later (#16).
 *
 * Before the fix, CConnection consumed the count byte, found the list not yet there, and
 * returned without restoring it; on re-entry it read each type byte as a new count, so the
 * handshake never finished and connect() spun. The scenario runs in a separate JVM so that
 * such a spinning client cannot outlive the test in Surefire.
 */
public class VNCClientSplitSecurityTypesTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void securityTypesInOneWrite_clientChoosesVncAuth() throws Exception {
    runProbe("whole");
  }

  @Test
  void securityTypesSplitAfterTheCount_clientChoosesVncAuth() throws Exception {
    runProbe("split");
  }

  private void runProbe(String mode) throws Exception {
    Path output = temporaryDirectory.resolve("security-types-" + mode + ".log");
    String executable = System.getProperty("os.name").startsWith("Windows")
        ? "java.exe" : "java";
    String java = Paths.get(System.getProperty("java.home"), "bin", executable).toString();
    String classpath = System.getProperty("surefire.test.class.path",
        System.getProperty("java.class.path"));

    Process child = new ProcessBuilder(java, "-cp", classpath, Probe.class.getName(), mode)
        .redirectErrorStream(true)
        .redirectOutput(output.toFile())
        .start();
    try {
      boolean finished = child.waitFor(20, TimeUnit.SECONDS);
      assertTrue(finished, () -> "Isolated scenario exceeded 20 s.\n" + readLog(output));
      assertEquals(0, child.exitValue(), () -> "Isolated scenario failed.\n" + readLog(output));
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        if (!child.waitFor(5, TimeUnit.SECONDS)) {
          throw new AssertionError("Could not terminate isolated test JVM");
        }
      }
    }
  }

  private static String readLog(Path output) {
    try {
      return new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
    } catch (IOException error) {
      return "Cannot read child output: " + error;
    }
  }

  /** Public entry point used only by the child JVM; not a second JUnit test. */
  public static final class Probe {
    private static final long CHOICE_SECONDS = 5;
    private static final long SPLIT_PAUSE_MILLIS = 200;
    private static final byte[] VERSION = "RFB 003.008\n".getBytes(StandardCharsets.US_ASCII);
    // RFB 3.8 security-types message: a count of 2, then VNC Authentication (2) and Tight (16).
    private static final byte[] COUNT = {2};
    private static final byte[] TYPES = {2, 16};

    public static void main(String[] args) {
      boolean split = "split".equals(args[0]);
      System.out.println("Mode: " + (split ? "count and list in two writes" : "one write"));
      System.out.println("VNCClient loaded from: "
          + VNCClient.class.getProtectionDomain().getCodeSource().getLocation());
      System.out.println("Java: " + System.getProperty("java.version"));
      int exitCode = 1;
      try {
        int choice = run(split);
        if (choice == 2) {
          exitCode = 0;
          System.out.println("PASS: the client chose VNC Authentication (2).");
        } else {
          System.out.println("FAIL: the client chose " + choice + ", not 2.");
        }
      } catch (Throwable failure) {
        failure.printStackTrace(System.out);
        System.out.println("FAIL: " + failure);
      }
      // Ends the JVM even if the client thread is still spinning in the handshake.
      System.exit(exitCode);
    }

    private static int run(boolean split) throws Exception {
      CompletableFuture<Integer> choice = new CompletableFuture<>();
      try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
        listener.setSoTimeout((int) TimeUnit.SECONDS.toMillis(CHOICE_SECONDS));
        int port = listener.getLocalPort();

        Thread client = new Thread(() -> {
          try {
            VNCClient.connect("127.0.0.1", port, "secret", true);
          } catch (Throwable expected) {
            // The server closes after the choice, so the handshake ends in an exception.
          }
        }, "split-sectypes-connect");
        client.setDaemon(true);
        client.start();

        try (Socket socket = listener.accept()) {
          socket.setTcpNoDelay(true);
          socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(CHOICE_SECONDS));
          DataInputStream input = new DataInputStream(socket.getInputStream());
          OutputStream output = socket.getOutputStream();

          output.write(VERSION);
          output.flush();
          byte[] version = new byte[VERSION.length];
          input.readFully(version);
          if (!Arrays.equals(VERSION, version)) {
            throw new AssertionError("Unexpected client RFB version");
          }

          if (split) {
            output.write(COUNT);
            output.flush();
            Thread.sleep(SPLIT_PAUSE_MILLIS);
            output.write(TYPES);
          } else {
            byte[] message = new byte[COUNT.length + TYPES.length];
            System.arraycopy(COUNT, 0, message, 0, COUNT.length);
            System.arraycopy(TYPES, 0, message, COUNT.length, TYPES.length);
            output.write(message);
          }
          output.flush();

          // The protocol event under test: the client's one-byte choice. A hung client sends
          // nothing, and the read times out.
          choice.complete(input.readUnsignedByte());
        }
      }
      return choice.get(1, TimeUnit.SECONDS);
    }
  }
}

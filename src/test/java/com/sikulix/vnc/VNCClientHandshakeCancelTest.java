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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract: interrupting connect() while the peer withholds SecurityResult
 * terminates the client thread and closes its connection.
 *
 * The scenario runs in a separate JVM. A broken client cannot leave a spinning
 * thread in Surefire. A successful child exit requires both observations BEFORE
 * fixture cleanup or process exit; killing the child never counts as success.
 * No production source changes and no additional test dependency beyond JUnit 5.
 */
public class VNCClientHandshakeCancelTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void interruptDuringSecurityResultEndsTheThreadAndClosesTheSocket() throws Exception {
    Path output = temporaryDirectory.resolve("handshake-cancel.log");
    String executable = System.getProperty("os.name").startsWith("Windows")
        ? "java.exe" : "java";
    String java = Paths.get(System.getProperty("java.home"), "bin", executable).toString();
    String classpath = System.getProperty("surefire.test.class.path",
        System.getProperty("java.class.path"));

    Process child = new ProcessBuilder(java, "-cp", classpath, Probe.class.getName())
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
    private static final long SETUP_SECONDS = 5;
    private static final long STOP_MILLIS = 2000;
    private static final byte[] VERSION = "RFB 003.008\n".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] args) {
      int exitCode = 1;
      try (Scenario scenario = new Scenario()) {
        scenario.verify();
        // close() below must also succeed before exitCode can become zero.
      } catch (Throwable failure) {
        failure.printStackTrace(System.err);
        System.err.println("FAIL: isolated JVM will exit; its exit is NOT proof of socket cleanup.");
        System.exit(exitCode);
        return;
      }
      exitCode = 0;
      System.out.println("PASS: client termination and peer EOF observed before fixture cleanup.");
      System.exit(exitCode);
    }

    private static final class Scenario implements AutoCloseable {
      private final ServerSocket listener;
      private final AtomicReference<Socket> accepted = new AtomicReference<>();
      private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
      private final AtomicReference<Throwable> clientFailure = new AtomicReference<>();
      private final AtomicReference<VNCClient> returnedClient = new AtomicReference<>();
      private final CompletableFuture<Void> challengeAnswered = new CompletableFuture<>();
      private final CompletableFuture<Integer> peerRead = new CompletableFuture<>();
      private final Thread serverThread;
      private final Thread clientThread;
      private volatile boolean connectReturned;

      Scenario() throws IOException {
        // Explicit loopback binding: no listener on the machine's other interfaces.
        listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        listener.setSoTimeout((int) TimeUnit.SECONDS.toMillis(SETUP_SECONDS));
        int port = listener.getLocalPort();
        serverThread = new Thread(this::serve, "t1-fake-vnc-server");
        clientThread = new Thread(() -> {
          try {
            returnedClient.set(VNCClient.connect("127.0.0.1", port, "secret", true));
            connectReturned = true;
          } catch (Throwable error) {
            // Retained and checked by verify(); Errors are never accepted as cancellation.
            clientFailure.set(error);
          }
        }, "t1-vnc-connect");
      }

      private void serve() {
        try (Socket socket = listener.accept()) {
          accepted.set(socket);
          socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(SETUP_SECONDS));
          DataInputStream input = new DataInputStream(socket.getInputStream());
          OutputStream output = socket.getOutputStream();

          output.write(VERSION);
          output.flush();
          byte[] version = new byte[VERSION.length];
          input.readFully(version);
          require(Arrays.equals(VERSION, version), "Unexpected client RFB version");

          output.write(new byte[] {1, 2}); // One security type: VNC Authentication.
          output.flush();
          require(input.readUnsignedByte() == 2, "Client did not select VNC Authentication");
          output.write(new byte[16]); // Deterministic challenge; authentication is never completed.
          output.flush();
          input.readFully(new byte[16]);

          // Synchronization on a protocol event, not on a sampled stack frame or sleep.
          challengeAnswered.complete(null);
          // Withhold SecurityResult. Only EOF is accepted; bytes and exceptions fail.
          peerRead.complete(input.read());
        } catch (Throwable error) {
          serverFailure.set(error);
          challengeAnswered.completeExceptionally(error);
          peerRead.completeExceptionally(error);
        }
      }

      void verify() throws Exception {
        System.out.println("VNCClient loaded from: "
            + VNCClient.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("Java: " + System.getProperty("java.version"));
        serverThread.start();
        clientThread.start();
        challengeAnswered.get(SETUP_SECONDS, TimeUnit.SECONDS);
        require(clientThread.isAlive() && !connectReturned && clientFailure.get() == null,
            "Client ended before interruption: " + clientFailure.get());
        require(!peerRead.isDone(), "Connection ended or sent extra data before interruption");

        clientThread.interrupt();
        clientThread.join(STOP_MILLIS);
        boolean terminated = !clientThread.isAlive();
        String stack = stackOf(clientThread);

        // Observe the peer even if the client is still alive, for a complete failure report.
        Integer read = null;
        Throwable readFailure = null;
        try {
          read = peerRead.get(STOP_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
          readFailure = error;
        }
        System.out.println("Before cleanup: clientTerminated=" + terminated
            + ", peerRead=" + read + ", peerFailure=" + readFailure
            + ", clientFailure=" + clientFailure.get());

        require(terminated, "connect() still running " + STOP_MILLIS
            + " ms after interrupt.\n" + stack);
        require(!connectReturned, "connect() returned normally without receiving SecurityResult");
        require(clientFailure.get() instanceof Exception,
            "Expected an exception from cancelled connect(), got: " + clientFailure.get());
        require(readFailure == null, "Peer did not observe EOF: " + readFailure);
        require(read != null && read == -1, "Expected peer EOF (-1), got: " + read);
        require(serverFailure.get() == null, "Fake server failed: " + serverFailure.get());
      }

      @Override
      public void close() throws Exception {
        // These actions are fixture cleanup, NEVER evidence that the client closed itself.
        // Close the listener and accepted socket: closing just the listener is insufficient.
        try {
          listener.close();
        } finally {
          Socket socket = accepted.get();
          if (socket != null) {
            socket.close();
          }
          clientThread.interrupt();
          serverThread.join(1000);
          clientThread.join(1000);
          // Handles unexpected normal return without leaking that connection.
          VNCClient connection = returnedClient.get();
          if (connection != null) {
            connection.close();
          }
        }
        require(!serverThread.isAlive(), "Fake server did not terminate during cleanup");
        require(!clientThread.isAlive(), "Client survived fixture cleanup; child JVM must exit.\n"
            + stackOf(clientThread));
      }
    }

    private static void require(boolean condition, String message) {
      if (!condition) {
        throw new AssertionError(message);
      }
    }

    private static String stackOf(Thread thread) {
      StringBuilder result = new StringBuilder(thread.getName()).append('\n');
      for (StackTraceElement frame : thread.getStackTrace()) {
        result.append("  at ").append(frame).append('\n');
      }
      return result.toString();
    }
  }
}

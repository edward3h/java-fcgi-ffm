/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.posix;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;

class PosixTest {
    @Test
    void writeThenReadRoundTripsBytesOverASocketPair() throws IOException {
        int[] fds = PosixTestSupport.socketPair();
        try {
            byte[] message = "hello fastcgi".getBytes();
            Posix.write(fds[0], message, 0, message.length);
            byte[] received = new byte[message.length];
            int n = Posix.read(fds[1], received, 0, received.length);
            assertThat(n).isEqualTo(message.length);
            assertThat(received).isEqualTo(message);
        } finally {
            Posix.close(fds[0]);
            Posix.close(fds[1]);
        }
    }

    @Test
    void readReturnsZeroAtEndOfStreamAfterPeerCloses() throws IOException {
        int[] fds = PosixTestSupport.socketPair();
        Posix.close(fds[0]);
        try {
            byte[] buf = new byte[16];
            assertThat(Posix.read(fds[1], buf, 0, buf.length)).isEqualTo(0);
        } finally {
            Posix.close(fds[1]);
        }
    }

    @Test
    void acceptReturnsAConnectedClientFdWhenAPeerConnects() throws Exception {
        int port = findFreePort();
        int listenFd = PosixTestSupport.listenOn(port);
        try {
            try (Socket client = new Socket("127.0.0.1", port)) {
                int clientFd = Posix.accept(listenFd);
                try {
                    assertThat(clientFd).isAtLeast(0);
                } finally {
                    Posix.close(clientFd);
                }
            }
        } finally {
            Posix.close(listenFd);
        }
    }

    @Test
    void isSocketIsTrueForARealSocketAndFalseForARegularPipe() throws Exception {
        int[] fds = PosixTestSupport.socketPair();
        try {
            assertThat(Posix.isSocket(fds[0])).isTrue();
        } finally {
            Posix.close(fds[0]);
            Posix.close(fds[1]);
        }
        // fd 1 (stdout) under the Gradle test JVM is a pipe/file, never a socket.
        assertThat(Posix.isSocket(1)).isFalse();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}

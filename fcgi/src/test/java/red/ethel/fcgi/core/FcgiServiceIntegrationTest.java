/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import red.ethel.fcgi.core.protocol.NameValuePairs;
import red.ethel.fcgi.core.protocol.RecordHeader;
import red.ethel.fcgi.core.protocol.RecordType;

/// Drives a real FcgiService instance over an actual loopback TCP socket,
/// acting as the FastCGI client (the role Apache mod_fcgid normally plays)
/// to prove the full accept -> protocol -> handler -> response path works
/// without libfcgi.
class FcgiServiceIntegrationTest {
    @Test
    @Timeout(10)
    void servesARequestEndToEndOverARealSocket() throws Exception {
        int port = findFreePort();
        int listenFd = red.ethel.fcgi.core.posix.PosixTestSupportBridge.listenOn(port);

        var service = FcgiServiceTestFactory.createOnFd(listenFd);
        var serverThread = new Thread(() -> service.serve(exchange -> {
            try {
                exchange.out().write("hello from handler".getBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));
        serverThread.setDaemon(true);
        serverThread.start();

        try (Socket client = new Socket("127.0.0.1", port)) {
            OutputStream clientOut = client.getOutputStream();
            InputStream clientIn = client.getInputStream();

            writeBeginRequest(clientOut);
            var params = new LinkedHashMap<String, String>();
            params.put("REQUEST_METHOD", "GET");
            writeParamsRecord(clientOut, NameValuePairs.encode(params));
            writeParamsRecord(clientOut, new byte[0]);
            writeStdinRecord(clientOut, new byte[0]);

            var stdout = RecordHeader.readFrom(clientIn);
            assertThat(stdout.type()).isEqualTo(RecordType.STDOUT);
            byte[] body = clientIn.readNBytes(stdout.contentLength());
            assertThat(new String(body)).isEqualTo("hello from handler");

            var stdoutTerminator = RecordHeader.readFrom(clientIn);
            assertThat(stdoutTerminator.type()).isEqualTo(RecordType.STDOUT);
            assertThat(stdoutTerminator.contentLength()).isEqualTo(0);

            var endRequest = RecordHeader.readFrom(clientIn);
            assertThat(endRequest.type()).isEqualTo(RecordType.END_REQUEST);
            byte[] endBody = clientIn.readNBytes(endRequest.contentLength());
            assertThat(endBody[0] | endBody[1] | endBody[2] | endBody[3]).isEqualTo(0);
        }
    }

    private static int findFreePort() throws IOException {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void writeBeginRequest(OutputStream out) throws IOException {
        byte[] body = {0, 1, 0, 0, 0, 0, 0, 0}; // role = FCGI_RESPONDER
        new RecordHeader(1, RecordType.BEGIN_REQUEST, 1, body.length, 0).writeTo(out);
        out.write(body);
    }

    private static void writeParamsRecord(OutputStream out, byte[] content) throws IOException {
        new RecordHeader(1, RecordType.PARAMS, 1, content.length, 0).writeTo(out);
        out.write(content);
    }

    private static void writeStdinRecord(OutputStream out, byte[] content) throws IOException {
        new RecordHeader(1, RecordType.STDIN, 1, content.length, 0).writeTo(out);
        out.write(content);
    }
}

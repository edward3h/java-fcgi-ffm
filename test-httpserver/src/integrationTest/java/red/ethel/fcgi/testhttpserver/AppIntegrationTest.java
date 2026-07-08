/* (C) Edward Harman 2026 */
package red.ethel.fcgi.testhttpserver;

import static com.google.common.truth.Truth.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class AppIntegrationTest {

    @Container
    static final GenericContainer<?> container = new GenericContainer<>(
                    new ImageFromDockerfile().withDockerfile(Path.of(System.getProperty("docker.dir"), "Dockerfile")))
            .withCopyFileToContainer(
                    MountableFile.forHostPath(System.getProperty("fcgi.binary.path"), 0755), "/var/www/html/app.fcgi")
            .withExposedPorts(80);

    static HttpClient client;
    static String baseUrl;

    @BeforeAll
    static void setUp() {
        client = HttpClient.newHttpClient();
        baseUrl = "http://" + container.getHost() + ":" + container.getMappedPort(80);
    }

    @Test
    void rootHandlerReturns200() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/")).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Handler root");
    }

    @Test
    void fooHandlerReturns200() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/foo")).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Handler foo");
    }

    @Test
    void postBodyIsEchoed() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                .POST(HttpRequest.BodyPublishers.ofString("hello world"))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("hello world");
    }

    @Test
    void filterAddsResponseHeader() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/foo")).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Filter")).hasValue("applied");
    }

    @Test
    void filterCanShortCircuitHandler() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/blocked")).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).doesNotContain("Handler blocked");
    }
}

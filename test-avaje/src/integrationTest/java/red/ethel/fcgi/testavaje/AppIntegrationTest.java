/* (C) Edward Harman 2026 */
package red.ethel.fcgi.testavaje;

import static com.google.common.truth.Truth.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class AppIntegrationTest {

    static final Network network = Network.newNetwork();

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("fcgi_test")
            .withUsername("fcgi_test")
            .withPassword("fcgi_test_password")
            .withInitScript("schema.sql")
            .withNetwork(network)
            .withNetworkAliases("mysql");

    @Container
    static final GenericContainer<?> container = new GenericContainer<>(new ImageFromDockerfile()
                    .withDockerfile(Path.of(System.getProperty("docker.dir"), "mysql", "Dockerfile")))
            .withCopyFileToContainer(
                    MountableFile.forHostPath(System.getProperty("fcgi.binary.path"), 0755), "/var/www/html/app.fcgi")
            .withNetwork(network)
            .withEnv("MYSQL_HOST", "mysql")
            .withEnv("MYSQL_DATABASE", "fcgi_test")
            .withEnv("MYSQL_USERNAME", "fcgi_test")
            .withEnv("MYSQL_PASSWORD", "fcgi_test_password")
            .withExposedPorts(80)
            .dependsOn(mysql);

    static HttpClient client;
    static String baseUrl;

    @BeforeAll
    static void setUp() {
        client = HttpClient.newHttpClient();
        baseUrl = "http://" + container.getHost() + ":" + container.getMappedPort(80);
    }

    @Test
    void getCountersReturns200WithData() throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(baseUrl + "/db/counters")).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        // anchored as one substring so the assertion proves page_views specifically
        // has value 0, not just that some counter somewhere does
        assertThat(response.body()).contains("{\"name\":\"page_views\",\"value\":0}");
    }

    @Test
    void incrementCounterReturnsUpdatedValue() throws Exception {
        var post = HttpRequest.newBuilder(URI.create(baseUrl + "/db/counters/api_calls/increment"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        var postResponse = client.send(post, HttpResponse.BodyHandlers.ofString());
        assertThat(postResponse.statusCode()).isEqualTo(200);
        assertThat(postResponse.body()).contains("\"value\":1");
    }

    @Test
    void incrementUnknownCounterReturns404() throws Exception {
        var post = HttpRequest.newBuilder(URI.create(baseUrl + "/db/counters/nonexistent/increment"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        var postResponse = client.send(post, HttpResponse.BodyHandlers.ofString());
        assertThat(postResponse.statusCode()).isEqualTo(404);
    }
}

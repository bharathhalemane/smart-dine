package com.example.qrorder.media.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Opt-in: talks to a real MinIO-compatible server. Run with -Ds3.test.image=<image name you have available>.
 * Skipped by default, so the normal build never depends on a third-party image being downloadable.
 */
@EnabledIfSystemProperty(named = "s3.test.image", matches = ".+")
class S3ObjectStorageMinioIT {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F',
            0, 1, 1, 0, 0, 1, 0, 1, 0, 0};

    static GenericContainer<?> minio;
    static S3ObjectStorage storage;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        minio = new GenericContainer<>(System.getProperty("s3.test.image"))
                .withEnv("MINIO_ROOT_USER", "minioadmin")
                .withEnv("MINIO_ROOT_PASSWORD", "minioadmin123")
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
        minio.start();
        String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        try (S3Client admin = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("minioadmin", "minioadmin123")))
                .forcePathStyle(true).build()) {
            admin.createBucket(b -> b.bucket("media-test"));
        }
        storage = new S3ObjectStorage(new StorageProperties(endpoint, "us-east-1", "media-test", "minioadmin",
                "minioadmin123", "https://cdn.test", true, 5_242_880, 300, 60));
    }

    @AfterAll
    static void stop() {
        if (storage != null) {
            storage.destroy();
        }
        if (minio != null) {
            minio.stop();
        }
    }

    private static HttpResponse<String> put(ObjectStorage.PresignedUpload ticket, String contentType, byte[] body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(ticket.url()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        request.header("Content-Type", contentType);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aSignedUploadIsAcceptedAndCanBeInspectedReadAndDeleted() throws Exception {
        String key = "hotels/h1/menu/a.jpg";
        var ticket = storage.presignUpload(key, "image/jpeg", JPEG.length, Duration.ofMinutes(5));

        assertThat(storage.head(key)).isEmpty();
        HttpResponse<String> response = put(ticket, ticket.headers().get("Content-Type"), JPEG);
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);

        var info = storage.head(key).orElseThrow();
        assertThat(info.size()).isEqualTo(JPEG.length);
        assertThat(info.contentType()).isEqualTo("image/jpeg");
        assertThat(storage.readFirstBytes(key, 3)).containsExactly((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);

        storage.delete(key);
        assertThat(storage.head(key)).isEmpty();
    }

    @Test
    void theSignatureOnlyAcceptsTheSignedContentType() throws Exception {
        String key = "hotels/h1/menu/b.jpg";
        var ticket = storage.presignUpload(key, "image/jpeg", JPEG.length, Duration.ofMinutes(5));

        HttpResponse<String> response = put(ticket, "text/html", JPEG);

        assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(storage.head(key)).isEmpty();
    }

    @Test
    void anExpiredSignedUrlIsRefused() throws Exception {
        String key = "hotels/h1/menu/c.jpg";
        var ticket = storage.presignUpload(key, "image/jpeg", JPEG.length, Duration.ofSeconds(1));
        Thread.sleep(2500);

        HttpResponse<String> response = put(ticket, "image/jpeg", JPEG);

        assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(storage.head(key)).isEmpty();
    }
}
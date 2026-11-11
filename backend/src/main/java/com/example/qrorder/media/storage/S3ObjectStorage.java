package com.example.qrorder.media.storage;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** Works with AWS S3, Cloudflare R2, MinIO and other S3-compatible stores. */
@Component
@ConditionalOnProperty(prefix = "app.media", name = "bucket")
public class S3ObjectStorage implements ObjectStorage, DisposableBean {

    static {
        // Recent SDKs add checksum parameters to signed URLs that many S3-compatible stores reject.
        // "when_required" keeps URLs plain and is fine for AWS S3 too. An explicit setting by the operator wins.
        if (System.getProperty("aws.requestChecksumCalculation") == null) {
            System.setProperty("aws.requestChecksumCalculation", "when_required");
        }
        if (System.getProperty("aws.responseChecksumValidation") == null) {
            System.setProperty("aws.responseChecksumValidation", "when_required");
        }
    }

    private final String bucket;
    private final S3Client client;
    private final S3Presigner presigner;

    public S3ObjectStorage(StorageProperties props) {
        require(props.bucket(), "app.media.bucket");
        require(props.accessKey(), "app.media.access-key");
        require(props.secretKey(), "app.media.secret-key");
        require(props.publicBaseUrl(), "app.media.public-base-url");
        this.bucket = props.bucket();

        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        Region region = Region.of(props.region());
        boolean customEndpoint = props.endpoint() != null && !props.endpoint().isBlank();

        var clientBuilder = S3Client.builder().region(region).credentialsProvider(credentials)
                .forcePathStyle(props.pathStyle());
        var presignerBuilder = S3Presigner.builder().region(region).credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build());
        if (customEndpoint) {
            clientBuilder.endpointOverride(URI.create(props.endpoint()));
            presignerBuilder.endpointOverride(URI.create(props.endpoint()));
        }
        this.client = clientBuilder.build();
        this.presigner = presignerBuilder.build();
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when image storage is enabled (app.media.bucket is set)");
        }
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long sizeBytes, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder().bucket(bucket).key(key)
                .contentType(contentType).contentLength(sizeBytes).build();
        PresignedPutObjectRequest signed = presigner.presignPutObject(r -> r.signatureDuration(ttl).putObjectRequest(put));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        return new PresignedUpload(signed.url().toString(), headers, signed.expiration());
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        try {
            HeadObjectResponse response = client.headObject(r -> r.bucket(bucket).key(key));
            long size = response.contentLength() == null ? -1 : response.contentLength();
            return Optional.of(new ObjectInfo(size, response.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public byte[] readFirstBytes(String key, int count) {
        return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key)
                .range("bytes=0-" + (count - 1)).build()).asByteArray();
    }

    @Override
    public void delete(String key) {
        client.deleteObject(r -> r.bucket(bucket).key(key));
    }

    @Override
    public void destroy() {
        presigner.close();
        client.close();
    }
}
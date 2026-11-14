package com.textbin.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Value("${app.upload.dir:./uploads}")
    private String uploadBaseDir;

    @Value("${app.storage.type:auto}")
    private String storageType;

    @Value("${app.s3.bucket:}")
    private String s3Bucket;

    @Value("${app.s3.endpoint:}")
    private String s3Endpoint;

    @Value("${app.s3.access-key:}")
    private String s3AccessKey;

    @Value("${app.s3.secret-key:}")
    private String s3SecretKey;

    @Value("${app.s3.region:auto}")
    private String s3Region;

    @Value("${app.storage.max-capacity-gb:8}")
    private int maxCapacityGb;

    @Bean
    @Primary
    public StorageService storageService() {
        boolean useS3 = !"local".equalsIgnoreCase(storageType)
                && (s3Bucket != null && !s3Bucket.isBlank());

        if (useS3) {
            log.info("Storage configuration: S3/Cloudflare R2 mode detected for bucket: {}", s3Bucket);
            try {
                Region region = "auto".equalsIgnoreCase(s3Region) || s3Region.isBlank()
                        ? Region.US_EAST_1
                        : Region.of(s3Region);

                S3ClientBuilder builder = S3Client.builder()
                        .httpClientBuilder(UrlConnectionHttpClient.builder())
                        .region(region);

                if (s3Endpoint != null && !s3Endpoint.isBlank()) {
                    builder.endpointOverride(URI.create(s3Endpoint.trim()));
                }

                if (s3AccessKey != null && !s3AccessKey.isBlank() && s3SecretKey != null && !s3SecretKey.isBlank()) {
                    builder.credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(s3AccessKey.trim(), s3SecretKey.trim())
                    ));
                }

                S3Client s3Client = builder.build();
                return new S3StorageService(s3Client, s3Bucket.trim(), maxCapacityGb);
            } catch (Exception ex) {
                log.error("Failed to initialize S3Client ({}). Falling back to Local Disk Storage.", ex.getMessage(), ex);
            }
        }

        log.info("Storage configuration: Using Local Disk Storage ({})", uploadBaseDir);
        return new LocalStorageService(uploadBaseDir);
    }
}

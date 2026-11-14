package com.textbin.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Storage implementation for Cloudflare R2, AWS S3, MinIO, and other S3-compatible object stores.
 */
public class S3StorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

    private final S3Client s3Client;
    private final String bucketName;
    private final long maxCapacityBytes;

    public S3StorageService(S3Client s3Client, String bucketName, int maxCapacityGb) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.maxCapacityBytes = (long) maxCapacityGb * 1024L * 1024L * 1024L;
        log.info("Initialized S3StorageService for bucket: {} (Safety Cap: {} GB)", bucketName, maxCapacityGb);
    }

    @Override
    public String store(String pasteId, String fileId, String filename, InputStream inputStream, long contentLength, String contentType) throws IOException {
        String s3Key = pasteId + "/" + fileId + "/" + filename;

        try {
            PutObjectRequest putReq = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType(contentType)
                    .contentLength(contentLength)
                    .build();

            s3Client.putObject(putReq, RequestBody.fromInputStream(inputStream, contentLength));
            return s3Key;
        } catch (Exception ex) {
            log.error("Failed to upload file to S3/R2 [key={}]: {}", s3Key, ex.getMessage(), ex);
            throw new IOException("Failed to upload file to cloud storage: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Resource loadAsResource(String storagePath) throws IOException {
        String s3Key = normalizeKey(storagePath);

        try {
            GetObjectRequest getReq = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();

            ResponseInputStream<GetObjectResponse> s3Stream = s3Client.getObject(getReq);
            long contentLength = s3Stream.response().contentLength();
            String filename = s3Key.contains("/") ? s3Key.substring(s3Key.lastIndexOf('/') + 1) : s3Key;

            return new InputStreamResource(s3Stream) {
                @Override
                public long contentLength() {
                    return contentLength;
                }

                @Override
                public String getFilename() {
                    return filename;
                }

                @Override
                public boolean exists() {
                    return true;
                }

                @Override
                public boolean isReadable() {
                    return true;
                }
            };
        } catch (NoSuchKeyException ex) {
            log.debug("Object not found in S3 bucket [key={}]: {}", s3Key, ex.getMessage());
            return null;
        } catch (Exception ex) {
            log.error("Error retrieving object from S3 [key={}]: {}", s3Key, ex.getMessage());
            return null;
        }
    }

    @Override
    public void delete(String storagePath) {
        String s3Key = normalizeKey(storagePath);
        try {
            DeleteObjectRequest deleteReq = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();
            s3Client.deleteObject(deleteReq);
        } catch (Exception ex) {
            log.warn("Error deleting S3 object [key={}]: {}", s3Key, ex.getMessage());
        }
    }

    @Override
    public void deleteFolder(String pasteId) {
        String prefix = pasteId + "/";
        try {
            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .build();

            ListObjectsV2Response listRes = s3Client.listObjectsV2(listReq);
            List<ObjectIdentifier> toDelete = listRes.contents().stream()
                    .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                    .collect(Collectors.toList());

            if (!toDelete.isEmpty()) {
                DeleteObjectsRequest delReq = DeleteObjectsRequest.builder()
                        .bucket(bucketName)
                        .delete(Delete.builder().objects(toDelete).build())
                        .build();
                s3Client.deleteObjects(delReq);
                log.info("Purged {} files from S3 for prefix {}", toDelete.size(), prefix);
            }
        } catch (Exception ex) {
            log.warn("Error deleting S3 folder for prefix [{}]: {}", prefix, ex.getMessage());
        }
    }

    @Override
    public String getStorageType() {
        return "S3";
    }

    private static String normalizeKey(String storagePath) {
        if (storagePath == null) return "";
        return storagePath.replace('\\', '/').replaceAll("^/+", "");
    }
}

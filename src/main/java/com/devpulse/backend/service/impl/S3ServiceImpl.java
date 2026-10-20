package com.devpulse.backend.service.impl;

import com.devpulse.backend.config.S3Properties;
import com.devpulse.backend.service.interfaces.S3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class S3ServiceImpl implements S3Service {

    private final S3Client s3Client;
    private final S3Properties s3Properties;

    @Override
    public String uploadFile(MultipartFile file) throws IOException {

        String key = file.getOriginalFilename();

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(s3Properties.getBucketName())
                .key(key)
                .contentType(file.getContentType())
                .build();

        s3Client.putObject(
                request,
                RequestBody.fromBytes(file.getBytes())
        );

        return key;
    }

    @Override
    public byte[] downloadFile(String key) {

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(s3Properties.getBucketName())
                .key(key)
                .build();

        return s3Client.getObjectAsBytes(request)
                .asByteArray();
    }

    @Override
    public void deleteFile(String key) {

        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(s3Properties.getBucketName())
                .key(key)
                .build();

        s3Client.deleteObject(request);
    }

    @Override
    public boolean fileExists(String key) {

        try {

            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(s3Properties.getBucketName())
                    .key(key)
                    .build();

            s3Client.headObject(request);

            return true;

        } catch (Exception ex) {

            return false;
        }
    }
}
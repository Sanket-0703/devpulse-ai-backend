package com.devpulse.backend.controller;

import com.devpulse.backend.service.interfaces.S3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/test/s3")
@RequiredArgsConstructor
public class S3TestController {

    private final S3Service s3Service;

    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<String> upload(
            @RequestParam("file") MultipartFile file)
            throws Exception {

        String key = s3Service.uploadFile(file);

        return ResponseEntity.ok(key);
    }

    @GetMapping("/download/{key}")
    public ResponseEntity<byte[]> download(
            @PathVariable String key) {

        byte[] file = s3Service.downloadFile(key);

        return ResponseEntity.ok(file);
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(
            @PathVariable String key) {

        s3Service.deleteFile(key);

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/exists/{key}")
    public ResponseEntity<Boolean> exists(
            @PathVariable String key) {

        return ResponseEntity.ok(
                s3Service.fileExists(key)
        );
    }
}
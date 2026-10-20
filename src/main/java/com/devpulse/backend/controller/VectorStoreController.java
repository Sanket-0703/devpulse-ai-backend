package com.devpulse.backend.controller;

import com.devpulse.backend.service.VectorStoreService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/vector")
public class VectorStoreController {

    private final VectorStoreService vectorStoreService;

    public VectorStoreController(VectorStoreService vectorStoreService) {
        this.vectorStoreService = vectorStoreService;
    }

    @PostMapping("/test")
    public ResponseEntity<String> addTestDocument() {

        vectorStoreService.addTestDocument();

        return ResponseEntity.ok("Test document added to vector store");
    }

    @GetMapping("/search")
    public ResponseEntity<String> searchDocument(
            @RequestParam String query) {

        return ResponseEntity.ok(
                vectorStoreService.searchTestDocument(query)
        );
    }
}
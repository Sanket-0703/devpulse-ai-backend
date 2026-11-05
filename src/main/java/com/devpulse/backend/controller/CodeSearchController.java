package com.devpulse.backend.controller;

import com.devpulse.backend.dto.search.CodeSearchResult;
import com.devpulse.backend.security.CustomUserPrincipal;
import com.devpulse.backend.service.CodeSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
@Tag(name = "Code search", description = "Search your indexed repositories by meaning")
public class CodeSearchController {

    private final CodeSearchService searchService;

    public CodeSearchController(CodeSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    @Operation(summary = "Semantic code search",
            description = "Finds code by what it does, e.g. \"where are JWT tokens validated\". "
                    + "Only indexed repositories are searched.")
    public List<CodeSearchResult> search(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam String q,
            @RequestParam(required = false) Long repositoryId,
            @RequestParam(defaultValue = "10") int limit) {

        return searchService.search(principal.getId(), q, repositoryId, limit);
    }
}

package com.devpulse.backend.dto.github;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GitHubRepositoryCacheDto {

    private Long id;

    private Long githubId;

    private String name;

    private String fullName;

    private String description;

    private boolean isPrivate;

    private boolean fork;

    private boolean archived;

    private String htmlUrl;

    private String defaultBranch;

    private String language;
}
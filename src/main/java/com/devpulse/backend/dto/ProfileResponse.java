package com.devpulse.backend.dto;

import com.devpulse.backend.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ProfileResponse {

    private Long id;

    private String name;

    private String email;

    private Role role;
}
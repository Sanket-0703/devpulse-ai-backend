package com.devpulse.backend.entity;

/** Who reported a finding: the AI model, or one of DevPulse's own security rules. */
public enum FindingSource {

    AI,
    RULE
}

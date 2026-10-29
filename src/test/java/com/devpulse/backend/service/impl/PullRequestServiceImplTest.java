package com.devpulse.backend.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Search parsing; list/detail/files end to end are in PullRequestIntegrationTest. */
class PullRequestServiceImplTest {

    @Test
    void aNumberSearchAcceptsAnOptionalHash() {
        assertThat(PullRequestServiceImpl.parseNumber("42")).isEqualTo(42);
        assertThat(PullRequestServiceImpl.parseNumber(" #42 ")).isEqualTo(42);
        assertThat(PullRequestServiceImpl.parseNumber("#")).isNull();
        assertThat(PullRequestServiceImpl.parseNumber("fix 42")).isNull();
        assertThat(PullRequestServiceImpl.parseNumber("12345678901")).isNull(); // not an int
    }
}

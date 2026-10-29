package com.devpulse.backend.service;

/** The demo account (see DemoProperties). */
public interface DemoService {

    boolean enabled();

    /**
     * The demo user's id.
     *
     * @throws com.devpulse.backend.exception.ResourceNotFoundException when demo mode is off
     */
    Long demoUserId();
}

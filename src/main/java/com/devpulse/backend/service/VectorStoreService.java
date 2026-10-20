package com.devpulse.backend.service;

public interface VectorStoreService {

    void addTestDocument();

    String searchTestDocument(String query);
}
package com.devpulse.backend.service.impl;

import com.devpulse.backend.service.VectorStoreService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VectorStoreServiceImpl implements VectorStoreService {

    private final VectorStore vectorStore;

    public VectorStoreServiceImpl(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void addTestDocument() {

        Document document = new Document(
                """
                public class UserService {

                    public User getUser(Long id) {
                        return userRepository.findById(id)
                                .orElseThrow();
                    }
                }
                """
        );

        vectorStore.add(List.of(document));
    }

    @Override
    public String searchTestDocument(String query) {

        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(3)
                        .build()
        );

        if (documents.isEmpty()) {
            return "No relevant documents found";
        }

        return documents.get(0).getText();
    }
}
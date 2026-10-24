package com.devpulse.backend.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * JPA converter: an entity field marked
 * {@code @Convert(converter = EncryptedStringConverter.class)} is encrypted
 * on the way into the database and decrypted on the way out. Code using the
 * entity only ever sees plaintext; the column only ever holds ciphertext.
 *
 * It's a Spring bean (Spring Boot lets Hibernate create converters through
 * Spring), so the key comes from configuration via {@link TokenEncryptor}.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final TokenEncryptor tokenEncryptor;

    public EncryptedStringConverter(TokenEncryptor tokenEncryptor) {
        this.tokenEncryptor = tokenEncryptor;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return tokenEncryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return tokenEncryptor.decrypt(dbData);
    }
}

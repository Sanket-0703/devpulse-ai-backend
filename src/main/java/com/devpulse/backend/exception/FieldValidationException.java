package com.devpulse.backend.exception;

/**
 * A request that passed bean validation but is still wrong for one specific
 * field, e.g. an incorrect current password. Maps to 400 with the message
 * attached to that field, so the frontend shows it next to the input.
 *
 * Deliberately not a 401: the user is signed in; only a form value is wrong.
 * (A 401 would also make the frontend try to refresh the session.)
 */
public class FieldValidationException extends RuntimeException {

    private final String field;

    public FieldValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}

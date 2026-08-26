package in.suhansingh.ghbliapi.exception;

/**
 * Signup hit an address that is already registered. Mapped to <strong>409 Conflict</strong>
 * by {@code GlobalExceptionHandler} — not 400, because the request is well-formed, and not
 * 422, because the conflict is with server state rather than with the payload.
 *
 * <p>Thrown from the pre-check, which loses a race by definition; the unique index is what
 * actually guarantees the invariant, and its {@code DuplicateKeyException} maps to the same
 * 409 so both paths look identical from outside.
 */
public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String message) {
        super(message);
    }
}

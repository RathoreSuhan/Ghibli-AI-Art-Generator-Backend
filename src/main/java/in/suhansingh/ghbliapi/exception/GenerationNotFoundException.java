package in.suhansingh.ghbliapi.exception;

/**
 * A generation the caller cannot have — either because no such id exists, or because it exists
 * and belongs to somebody else.
 *
 * <p>Those two are deliberately the same exception, and
 * {@code GlobalExceptionHandler} maps them both to <strong>404</strong> rather than 403. A 403
 * would confirm that the id is real, turning the endpoint into an oracle that anyone can walk to
 * enumerate other users' generations without ever reading one. The API's answer to "is this
 * yours?" and "does this exist?" has to be the same answer.
 *
 * <p>Same reasoning the login path already uses, where an unknown e-mail and a wrong password
 * are indistinguishable.
 */
public class GenerationNotFoundException extends RuntimeException {

    public GenerationNotFoundException(String message) {
        super(message);
    }
}

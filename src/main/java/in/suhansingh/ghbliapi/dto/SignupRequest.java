package in.suhansingh.ghbliapi.dto;

import in.suhansingh.ghbliapi.model.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Signup payload. Every constraint here is live only because
 * spring-boot-starter-validation landed in Phase 0 — {@code spring-boot-starter-web}
 * brings no JSR-380 provider, so on the classpath as it stood before Phase 0 all of
 * these would be inert and any password would be accepted.
 */
@Data
public class SignupRequest {

    /**
     * BCrypt silently truncates the input at 72 bytes, so an unbounded password is a
     * subtle correctness bug rather than a memory one: two different long passwords that
     * share a 72-byte prefix would both authenticate. Capping below that keeps the hash
     * a function of the whole password.
     */
    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int MAX_PASSWORD_LENGTH = 72;

    @NotBlank(message = "must not be blank")
    @Size(max = 100, message = "must be at most 100 characters")
    private String name;

    /**
     * {@code @Email} accepts a blank string by design (an absent value is not a format
     * error), so {@code @NotBlank} is not redundant here.
     */
    @NotBlank(message = "must not be blank")
    @Email(message = "must be a well-formed email address")
    @Size(max = 254, message = "must be at most 254 characters")
    private String email;

    @NotBlank(message = "must not be blank")
    @Size(min = MIN_PASSWORD_LENGTH, max = MAX_PASSWORD_LENGTH,
            message = "must be between " + MIN_PASSWORD_LENGTH + " and " + MAX_PASSWORD_LENGTH + " characters")
    private String password;

    /**
     * Normalises on the way in, replacing the setter Lombok would have generated.
     *
     * <p>This has to happen before validation, not after. {@code @Email} does not trim, so a
     * copy-pasted {@code "suhan@example.com "} is a format error to it — the caller would get
     * a 400 saying the address is malformed because of a trailing space they cannot see. And
     * because {@code @RequestBody} validation runs on the object Jackson has already
     * populated, the only place early enough to fix that is the setter Jackson calls.
     *
     * <p>Lower-casing here too keeps one definition of "the same address" shared with
     * {@link User#setEmail}: the uniqueness pre-check, the unique index and the login lookup
     * all have to agree, and Mongo string equality is case-sensitive.
     */
    public void setEmail(String email) {
        this.email = User.normalizeEmail(email);
    }
}

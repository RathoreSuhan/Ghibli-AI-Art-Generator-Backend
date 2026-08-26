package in.suhansingh.ghbliapi.dto;

import in.suhansingh.ghbliapi.model.User;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Login payload.
 *
 * <p>Deliberately weaker validation than {@link SignupRequest}: only "present at all".
 * Applying the signup rules here would leak policy — a 400 "password must be between 8
 * and 72 characters" tells an attacker the minimum length, and worse, changing the
 * policy later would lock out existing accounts at the DTO layer before their (valid)
 * hash was ever checked. A wrong password is a 401, not a 400.
 *
 * <p>Note there is no {@code @Email} here either, for the same reason: whether the address
 * looks well-formed is irrelevant to whether it matches a stored one, and rejecting the
 * format would answer "is this a real account?" with a different status code than a wrong
 * password does.
 */
@Data
public class LoginRequest {

    @NotBlank(message = "must not be blank")
    private String email;

    @NotBlank(message = "must not be blank")
    private String password;

    /**
     * Normalised the same way as {@link SignupRequest#setEmail} and {@link User#setEmail}, so
     * that a padded or differently-cased address logs into the account it was signed up with.
     * Without this, {@code Suhan@Example.com } misses the stored {@code suhan@example.com} and
     * a correct password comes back as a 401.
     */
    public void setEmail(String email) {
        this.email = User.normalizeEmail(email);
    }
}

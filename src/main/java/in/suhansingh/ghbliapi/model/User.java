package in.suhansingh.ghbliapi.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * An application user.
 *
 * <p>Created only through {@code AuthService#signup}, which is also the only place that
 * populates {@link #password} — always with a BCrypt hash from the configured
 * {@code PasswordEncoder}, never with a plaintext value.
 */
@Document(collection = "users")
@Getter
@Setter
// password is a BCrypt hash, but it must not reach a log line either.
@ToString(exclude = "password")
@NoArgsConstructor
public class User {

    public static final String ROLE_USER = "ROLE_USER";

    @Id
    private String id;

    /**
     * Always stored lower-cased and trimmed — see {@link #setEmail}. Mongo string
     * comparison is case-sensitive by default, so without normalising on the way in,
     * {@code Bob@x.com} and {@code bob@x.com} are two distinct users and the unique
     * index below happily accepts both.
     *
     * <p>The index is only actually created because
     * {@code spring.data.mongodb.auto-index-creation=true} is set. Spring Data MongoDB
     * disabled auto-index creation in 3.0; with it off this annotation is inert and
     * enforces nothing. {@code UserIndexTest} asserts the index exists at runtime rather
     * than trusting the annotation.
     */
    @Indexed(unique = true)
    private String email;

    /**
     * BCrypt hash, written by {@code AuthService#signup} and read only by
     * {@code DaoAuthenticationProvider} during login.
     *
     * <p>{@code @JsonIgnore} rather than {@code @JsonProperty(access = WRITE_ONLY)}:
     * there is no scenario where a raw hash should be bound *from* JSON either, so the
     * absolute form is the safer default. Serialisation is pinned by {@code UserJsonTest}.
     * Auth responses never carry this field at all — {@code AuthResponse} is a record with
     * no password component, so the entity is never serialised to a client.
     */
    @JsonIgnore
    private String password;

    private String name;

    private Set<String> roles = new LinkedHashSet<>(Set.of(ROLE_USER));

    /**
     * Set at construction rather than with {@code @CreatedDate}: auditing needs
     * {@code @EnableMongoAuditing}, which belongs with the Generation entity in Phase 3.
     */
    private Instant createdAt = Instant.now();

    public User(String name, String email, String password) {
        this.name = name;
        setEmail(email);
        this.password = password;
    }

    /** Lombok skips generating a setter when one is declared, so this is the only path in. */
    public void setEmail(String email) {
        this.email = normalizeEmail(email);
    }

    /**
     * Callers that look a user up by e-mail must normalize the same way, or a lookup for
     * {@code Bob@x.com} misses the stored {@code bob@x.com}.
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}

package in.suhansingh.ghbliapi.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one property of {@link User} that is a security bug rather than a test failure
 * when it regresses: the BCrypt hash must never leave the process as JSON.
 *
 * <p>Uses the application's own auto-configured {@code ObjectMapper} rather than a bare
 * {@code new ObjectMapper()}. That is the mapper an accidental
 * {@code ResponseEntity.ok(user)} would actually go through, and a bare instance is not
 * equivalent — it has no JSR-310 module, so it throws on {@code createdAt} before it ever
 * reaches the password field, which would make this test pass for the wrong reason.
 */
@SpringBootTest
class UserJsonTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void passwordIsNeverSerializedToJson() throws Exception {
        User user = new User("Suhan", "suhan@example.com", "$2a$10$aBcDeFgHiJkLmNoPqRsTuV");
        user.setId("507f1f77bcf86cd799439011");

        String json = objectMapper.writeValueAsString(user);

        assertThat(json).doesNotContain("password");
        assertThat(json).doesNotContain("$2a$10$");
        // The rest of the document is still there — this is not an accidental blanket ignore.
        assertThat(json).contains("suhan@example.com").contains("Suhan").contains(User.ROLE_USER);
        assertThat(json).contains("createdAt");
    }

    @Test
    void toStringDoesNotLeakTheHashIntoLogs() {
        User user = new User("Suhan", "suhan@example.com", "$2a$10$aBcDeFgHiJkLmNoPqRsTuV");

        assertThat(user.toString()).doesNotContain("$2a$10$").contains("suhan@example.com");
    }

    @Test
    void emailNormalizationHandlesNull() {
        // findByEmail(null) must not blow up before it can miss.
        assertThat(User.normalizeEmail(null)).isNull();
        assertThat(new User("x", null, "h").getEmail()).isNull();
    }
}

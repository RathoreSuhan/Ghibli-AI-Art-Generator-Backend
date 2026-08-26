package in.suhansingh.ghbliapi.repository;

import in.suhansingh.ghbliapi.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against embedded MongoDB. {@code @SpringBootTest} rather than
 * {@code @DataMongoTest} on purpose — see {@code EmbeddedMongoCanaryTest} for why the
 * slice annotation would quietly use the live local server instead.
 */
@SpringBootTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clearUsers() {
        // deleteAll(), NOT dropCollection(): Spring Data creates the unique index once at
        // context startup, so dropping the collection would take the index with it and
        // duplicateEmailIsRejected() below would then pass a document it should reject.
        userRepository.deleteAll();
    }

    @Test
    void savesAndFindsByEmail() {
        User saved = userRepository.save(new User("Suhan", "suhan@example.com", "not-a-real-hash"));

        assertThat(saved.getId()).as("Mongo assigns the id on insert").isNotBlank();

        Optional<User> found = userRepository.findByEmail("suhan@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getName()).isEqualTo("Suhan");
        assertThat(found.get().getRoles()).containsExactly(User.ROLE_USER);
        assertThat(found.get().getCreatedAt()).isNotNull().isBefore(Instant.now().plusSeconds(1));
    }

    @Test
    void emailIsStoredLowercasedAndTrimmed() {
        userRepository.save(new User("Mixed Case", "  SUHAN@Example.COM  ", "hash"));

        // Mongo string equality is case-sensitive, so normalising on the way in is what
        // makes the unique index meaningful.
        assertThat(userRepository.findByEmail("suhan@example.com")).isPresent();
        assertThat(userRepository.findByEmail("SUHAN@Example.COM")).isEmpty();
    }

    @Test
    void existsByEmailReflectsWhatWasSaved() {
        userRepository.save(new User("Suhan", "suhan@example.com", "hash"));

        assertThat(userRepository.existsByEmail("suhan@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("nobody@example.com")).isFalse();
    }

    @Test
    void duplicateEmailIsRejected() {
        userRepository.save(new User("First", "dupe@example.com", "hash-one"));

        // This is the assertion that fails the moment auto-index-creation is switched off:
        // with no unique index the second insert succeeds and the collection holds two.
        assertThatThrownBy(() -> userRepository.save(new User("Second", "dupe@example.com", "hash-two")))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void duplicateEmailIsRejectedRegardlessOfCasing() {
        userRepository.save(new User("First", "casing@example.com", "hash-one"));

        assertThatThrownBy(() -> userRepository.save(new User("Second", "CASING@EXAMPLE.COM", "hash-two")))
                .isInstanceOf(DuplicateKeyException.class);
    }
}

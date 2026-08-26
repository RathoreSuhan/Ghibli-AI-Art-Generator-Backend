package in.suhansingh.ghbliapi.repository;

import in.suhansingh.ghbliapi.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Both query methods match on the raw stored value, so callers must pass an e-mail that
 * has been through {@link User#normalizeEmail(String)} — otherwise a mixed-case address
 * silently misses the lower-cased document. Phase 2's signup/login path is the first
 * caller that has to honour this.
 */
@Repository
public interface UserRepository extends MongoRepository<User, String> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}

package in.suhansingh.ghbliapi.repository;

import in.suhansingh.ghbliapi.model.GenerationImage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * The only repository that ever loads image bytes, and it is called from exactly one place:
 * {@code GenerationHistoryService#imageFor}, behind the metadata ownership check. Keeping it
 * out of the list path is what makes "the history list never returns bytes" a structural
 * property rather than a promise.
 */
@Repository
public interface GenerationImageRepository extends MongoRepository<GenerationImage, String> {

    /**
     * Owner-scoped even though the caller has already proved ownership of the parent
     * {@code Generation}. Two independent checks cost one extra predicate on a single-document
     * lookup by {@code _id}; one check costs nothing until the day the parent query is edited.
     */
    Optional<GenerationImage> findByIdAndUserId(String id, String userId);
}

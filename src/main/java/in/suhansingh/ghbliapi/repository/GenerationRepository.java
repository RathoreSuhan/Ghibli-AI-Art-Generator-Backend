package in.suhansingh.ghbliapi.repository;

import in.suhansingh.ghbliapi.model.Generation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Every method here takes a {@code userId}, and that is the design rather than a convention.
 * There is no {@code findById(String)} in use anywhere in the application: the plain inherited
 * one exists on {@link MongoRepository} but calling it would return another user's document,
 * so the owner-scoped variants below are the only ones the service layer touches.
 */
@Repository
public interface GenerationRepository extends MongoRepository<Generation, String> {

    /**
     * One page of a user's history, newest first.
     *
     * <p><strong>Callers must pass an unsorted {@link Pageable}.</strong> Sorting lives in this
     * method name and nowhere else. Spring Data does not treat a sorted {@code Pageable} as an
     * override of {@code OrderBy…} — it merges the two, and on a field they both mention the
     * {@code Pageable} wins. So a request carrying {@code ?sort=createdAt,asc} would flip the
     * order of an endpoint whose contract says "newest first", and one carrying
     * {@code ?sort=prompt} would produce {@code {prompt: 1, createdAt: -1}}, which the
     * {@code {userId: 1, createdAt: -1}} index cannot serve — an in-memory sort that fails
     * outright past Mongo's 32 MB sort limit.
     *
     * <p>{@code GenerationHistoryController} therefore builds its {@code PageRequest} from
     * {@code page} and {@code size} only and never binds a {@code sort} parameter, and
     * {@code GenerationHistoryIntegrationTest} asserts that passing one changes nothing.
     *
     * <p>{@link Page} rather than {@code Slice} because the history UI shows numbered pages,
     * which needs {@code totalElements}. That costs a second {@code count} query per request;
     * a {@code Slice} would fetch {@code size + 1} documents instead and only be able to say
     * whether more exist.
     */
    Page<Generation> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /**
     * The ownership check for reads. Both conditions go into one query so a document belonging
     * to someone else is simply not found — the alternative, loading by id and comparing the
     * owner in Java, is the same logic with a branch that can be forgotten.
     */
    Optional<Generation> findByIdAndUserId(String id, String userId);

    /**
     * The ownership check for deletes, expressed the same way: the {@code userId} predicate is
     * part of the delete filter, so a cross-user id matches nothing and removes nothing.
     *
     * <p>@return documents actually removed — 0 or 1, since {@code id} is unique. The count is
     * what distinguishes "deleted" from "was never yours", which is the difference between 204
     * and 404. A {@code void} return would throw that away.
     */
    long deleteByIdAndUserId(String id, String userId);
}

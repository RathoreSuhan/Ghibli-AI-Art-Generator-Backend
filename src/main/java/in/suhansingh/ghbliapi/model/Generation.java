package in.suhansingh.ghbliapi.model;

import in.suhansingh.ghbliapi.enums.GenerationType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Metadata for one generated image. <strong>Never holds the image bytes.</strong>
 *
 * <p>The PNG lives in {@link GenerationImage}, in its own {@code generation_images}
 * collection, and is reachable only through {@link #imageId}. That split is the entire point
 * of the storage decision in PLAN.md §2.1: the history list is the dominant read, it needs
 * none of the binary, and a 1–2 MB {@code byte[]} inline here would put every megabyte into
 * the working set of a query that only ever renders prompts and dates. Because
 * {@link #imageId} is a plain {@code String} and not a {@code @DBRef}, there is no mapping
 * hook that could fetch the other collection behind the reader's back — loading a
 * {@code Generation} is exactly one query, always.
 *
 * <p>GridFS was considered and rejected. MongoDB's own manual recommends it only for files
 * that exceed the 16 MB BSON document limit; SDXL output is 1–2 MB, so it would add chunked
 * two-collection indirection to solve a problem this data does not have.
 *
 * <p>{@code GenerationRepositoryTest} pins the no-bytes property by reflection, so a
 * future field of an array type fails the build rather than quietly fattening the list
 * response.
 */
@Document(collection = "generations")
/*
 * The only query this collection serves: "this user's history, newest first, paginated".
 * {userId: 1, createdAt: -1} answers the equality match and the sort from one B-tree, so
 * Mongo never has to sort in memory. Two single-field indexes could not do that — it would
 * use one of them, then sort the matched set. There is deliberately no separate @Indexed on
 * userId either: userId is the index PREFIX, so a userId-only query already uses this index,
 * and a second index on the same field would cost a write on every insert and buy no read.
 *
 * A named index is a one-way door. Mongo does NOT alter an existing index when the
 * definition changes — it raises IndexKeySpecsConflict on startup and the application fails
 * until someone drops the old one by hand. With auto-index-creation enabled (Phase 1) that
 * happens on the first context refresh after a deploy, so the def below has to be right the
 * first time rather than iterated on.
 */
@CompoundIndex(name = "generations_userId_createdAt_desc", def = "{'userId': 1, 'createdAt': -1}")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class Generation {

    @Id
    private String id;

    /**
     * Owner. Always the Mongo {@code _id} of a {@code User}, taken from the JWT subject via
     * the {@code SecurityContext} — never from a request body, query parameter or header.
     * Accepting it from the client would make every history endpoint a one-parameter IDOR.
     *
     * <p>Indexed as the prefix of the compound index declared on this class, not by a
     * separate {@code @Indexed} annotation.
     */
    private String userId;

    private GenerationType type;

    /**
     * The prompt as the user typed it — not the {@code ", in the beautiful, detailed anime
     * style of studio ghibli."} suffixed form that actually goes to Stability. The suffix is
     * an implementation detail of {@code GhibliArtService}; storing it would put the same
     * boilerplate on every row and show it back to the user in their own history.
     */
    private String prompt;

    /**
     * For {@link GenerationType#TEXT_TO_IMAGE}, the style the user selected (e.g.
     * {@code general}, {@code fantasy_art}) before {@code GhibliArtService} maps it to a
     * Stability {@code style_preset}. For {@link GenerationType#IMAGE_TO_IMAGE} the user
     * chooses nothing, so this records the fixed preset that endpoint applies.
     */
    private String style;

    /** Stability engine that produced it, so old rows stay explainable after an engine swap. */
    private String engineId;

    /** {@code _id} of the {@link GenerationImage} holding the bytes. */
    private String imageId;

    /**
     * Pixel dimensions read back out of the returned PNG header, so they describe what
     * Stability actually produced rather than what was requested. Boxed and nullable: an
     * undecodable or truncated response still deserves a history row, and a wrong {@code 0}
     * would read as a real measurement.
     */
    private Integer width;

    private Integer height;

    /**
     * Size of the stored PNG. Kept here, on the light document, precisely so a UI can show it
     * without touching {@code generation_images}.
     */
    private Long imageSizeBytes;

    /**
     * Stamped by Spring Data auditing, which requires {@code @EnableMongoAuditing} — see
     * {@code MongoAuditingConfig}. Without that annotation this field stays null, no warning
     * is logged, and the sort in {@code findByUserIdOrderByCreatedAtDesc} silently degenerates.
     *
     * <p>Note that auditing overwrites this on <em>insert</em> only, i.e. when the id is null.
     * A document saved with an id already set counts as an update, and its {@code createdAt}
     * is left exactly as given — which is how the tests build fixtures with controlled
     * timestamps.
     *
     * <p>{@code userId} is set explicitly rather than with {@code @CreatedBy}: that annotation
     * needs an {@code AuditorAware} bean and stays null forever without one, and an owner id
     * arriving invisibly is the last thing an authorization check should depend on.
     */
    @CreatedDate
    private Instant createdAt;
}

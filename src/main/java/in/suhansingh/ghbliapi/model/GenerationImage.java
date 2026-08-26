package in.suhansingh.ghbliapi.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The PNG bytes for one generation, stored as BSON BinData in its own collection.
 *
 * <p>Separate from {@link Generation} on purpose (PLAN.md §2.1). Mongo has no column
 * projection cost model to lean on — a {@code find} returns whole documents unless a
 * projection is spelled out — so the reliable way to keep 1–2 MB of binary out of a list
 * query is for it not to be in the document at all.
 *
 * <p>Not GridFS: MongoDB recommends GridFS only above the 16 MB BSON limit, and these
 * documents are 1–2 MB. GridFS would mean two collections, chunk assembly on every read, and
 * ObjectId/hex-string plumbing, in exchange for a limit this data never approaches.
 *
 * <p>There is no {@code @Indexed} field here at all. Every read arrives as
 * {@code findByIdAndUserId}, which is served by the mandatory {@code _id} index with
 * {@code userId} applied as a filter on the single matched document. An index on
 * {@code userId} would be paid for on every insert and never used.
 */
@Document(collection = "generation_images")
@Getter
@Setter
@NoArgsConstructor
public class GenerationImage {

    @Id
    private String id;

    /**
     * Owner, duplicated from the owning {@link Generation}.
     *
     * <p>Redundant in the happy path — the only way to learn an image id is to first load a
     * {@code Generation} that is already scoped to the caller. It is here so the ownership
     * check does not rest on a single query being written correctly: the image fetch re-asserts
     * it, and a future caller that somehow holds a raw image id still cannot read another
     * user's bytes.
     */
    private String userId;

    /**
     * Raw PNG. {@code byte[]} maps to BSON BinData subtype 0.
     *
     * <p>{@code @JsonIgnore} is belt and braces: this entity is never returned from a
     * controller — the image endpoint writes {@code getData()} straight to the response as
     * {@code image/png}, and the list endpoint returns a DTO. If a future handler ever did
     * return the entity, this stops the bytes being base64'd into a JSON body.
     */
    @JsonIgnore
    private byte[] data;

    /** Always {@code image/png} today; stored so the image endpoint never has to assume. */
    private String contentType;

    public GenerationImage(String userId, byte[] data, String contentType) {
        this.userId = userId;
        this.data = data;
        this.contentType = contentType;
    }

    /** @return byte count, or 0 when the document carries no payload */
    public long size() {
        return data == null ? 0L : data.length;
    }
}

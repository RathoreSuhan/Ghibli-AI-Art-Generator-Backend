package in.suhansingh.ghbliapi.repository;

import in.suhansingh.ghbliapi.model.Generation;
import in.suhansingh.ghbliapi.model.GenerationImage;
import in.suhansingh.ghbliapi.enums.GenerationType;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The persistence layer on real embedded MongoDB — index definitions, derived queries and
 * pagination behaviour, none of which can be proved against a mock.
 */
@SpringBootTest
class GenerationRepositoryTest {

    private static final String OWNER = "owner-user-id";
    private static final String STRANGER = "stranger-user-id";

    @Autowired
    private GenerationRepository generationRepository;

    @Autowired
    private GenerationImageRepository generationImageRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void clear() {
        // Flapdoodle runs one mongod for the whole suite, so every class has to start from empty
        // or the paging assertions below pass or fail depending on execution order.
        generationRepository.deleteAll();
        generationImageRepository.deleteAll();
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Builds a row with an id already assigned, which matters more than it looks.
     * {@code @CreatedDate} only fires on an insert — Spring Data decides that by the id being
     * null — so pre-assigning one makes the save an upsert and the explicit {@code createdAt}
     * below survives. Letting auditing stamp these instead would put every fixture within the
     * same millisecond, and BSON dates have millisecond precision, so the ordering test would
     * be asserting on a coin flip.
     */
    private Generation generation(String userId, String prompt, Instant createdAt) {
        Generation generation = new Generation();
        generation.setId(new ObjectId().toHexString());
        generation.setUserId(userId);
        generation.setType(GenerationType.TEXT_TO_IMAGE);
        generation.setPrompt(prompt);
        generation.setStyle("general");
        generation.setEngineId("stable-diffusion-xl-1024-v1-0");
        generation.setImageId(new ObjectId().toHexString());
        generation.setWidth(1024);
        generation.setHeight(1024);
        generation.setImageSizeBytes(1_234L);
        generation.setCreatedAt(createdAt);
        return generationRepository.save(generation);
    }

    private static Instant minutesAgo(long minutes) {
        return Instant.parse("2026-08-26T12:00:00Z").minus(minutes, ChronoUnit.MINUTES);
    }

    // --- the compound index physically exists -------------------------------

    /**
     * {@code @CompoundIndex} is a no-op unless {@code spring.data.mongodb.auto-index-creation}
     * is true — it has defaulted to false since Spring Data MongoDB 3.0, and when it is off no
     * index is created, nothing is logged, and every query below still passes because a
     * collection scan returns the same rows. This asserts the index is really in the database,
     * with the key order and direction the queries depend on.
     */
    @Test
    void theUserIdCreatedAtCompoundIndexExistsWithDescendingCreatedAt() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(Generation.class).getIndexInfo();

        assertThat(indexes)
                .as("compound index on generations: %s", indexes)
                .anySatisfy(index -> {
                    assertThat(index.getIndexFields())
                            .extracting(IndexField::getKey)
                            .containsExactly("userId", "createdAt");
                    // userId ascending, createdAt descending — that order and those directions
                    // are what let one B-tree serve the equality match and the sort together.
                    assertThat(index.getIndexFields())
                            .extracting(IndexField::getDirection)
                            .containsExactly(Sort.Direction.ASC, Sort.Direction.DESC);
                });
    }

    /**
     * The deliberate absence from requirement 1's "userId (indexed)": there is no standalone
     * index on {@code userId}, because it is the prefix of the compound index and a second one
     * would be written on every insert and read by nothing.
     */
    @Test
    void thereIsNoRedundantStandaloneIndexOnUserId() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(Generation.class).getIndexInfo();

        assertThat(indexes)
                .as("a single-field userId index would be redundant with the compound one")
                .noneSatisfy(index -> assertThat(index.getIndexFields())
                        .extracting(IndexField::getKey)
                        .containsExactly("userId"));
    }

    // --- no bytes on the metadata document ----------------------------------

    /**
     * Structural, not behavioural: the reason the list endpoint is light is that there is
     * nowhere on this document for a megabyte to hide. A future {@code byte[] thumbnail} field
     * would be an easy, plausible addition that quietly puts binary back into every history
     * query — this fails the build instead.
     */
    @Test
    void theGenerationDocumentDeclaresNoBinaryField() {
        for (Field field : Generation.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            assertThat(field.getType().isArray())
                    .as("Generation.%s is an array — image bytes must stay in generation_images",
                            field.getName())
                    .isFalse();
        }
    }

    // --- findByUserIdOrderByCreatedAtDesc -----------------------------------

    @Test
    void historyIsReturnedNewestFirst() {
        generation(OWNER, "oldest", minutesAgo(30));
        generation(OWNER, "newest", minutesAgo(1));
        generation(OWNER, "middle", minutesAgo(15));

        Page<Generation> page = generationRepository
                .findByUserIdOrderByCreatedAtDesc(OWNER, PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(Generation::getPrompt)
                .containsExactly("newest", "middle", "oldest");
    }

    @Test
    void pagingReportsTheTotalAndSlicesInOrder() {
        for (int i = 0; i < 5; i++) {
            generation(OWNER, "prompt-" + i, minutesAgo(i));
        }

        Page<Generation> first = generationRepository
                .findByUserIdOrderByCreatedAtDesc(OWNER, PageRequest.of(0, 2));

        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(first.isFirst()).isTrue();
        assertThat(first.isLast()).isFalse();
        // prompt-0 is the most recent, since minutesAgo counts backwards.
        assertThat(first.getContent()).extracting(Generation::getPrompt)
                .containsExactly("prompt-0", "prompt-1");

        Page<Generation> last = generationRepository
                .findByUserIdOrderByCreatedAtDesc(OWNER, PageRequest.of(2, 2));

        assertThat(last.isLast()).isTrue();
        assertThat(last.getNumberOfElements()).isEqualTo(1);
        assertThat(last.getContent()).extracting(Generation::getPrompt).containsExactly("prompt-4");
    }

    @Test
    void aPagePastTheEndIsEmptyRatherThanAnError() {
        generation(OWNER, "only", minutesAgo(1));

        Page<Generation> page = generationRepository
                .findByUserIdOrderByCreatedAtDesc(OWNER, PageRequest.of(9, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    /**
     * Documents the trap the service is written around. A {@code Sort} on a {@code Pageable}
     * does not override {@code OrderBy…} in the method name — Spring Data merges them and the
     * {@code Pageable} wins on the same property, so passing
     * {@code Sort.by("createdAt").ascending()} here silently reverses a method whose name still
     * says {@code Desc}. That is why {@code GenerationHistoryService} builds an unsorted
     * {@code PageRequest} and the controller exposes no {@code sort} parameter.
     */
    @Test
    void aSortedPageableOverridesTheMethodNameOrdering() {
        generation(OWNER, "oldest", minutesAgo(30));
        generation(OWNER, "newest", minutesAgo(1));

        Page<Generation> ascending = generationRepository.findByUserIdOrderByCreatedAtDesc(
                OWNER, PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "createdAt")));

        assertThat(ascending.getContent())
                .as("a sorted Pageable wins over OrderBy on the same field")
                .extracting(Generation::getPrompt)
                .containsExactly("oldest", "newest");
    }

    @Test
    void historyIsScopedToTheOwner() {
        generation(OWNER, "mine", minutesAgo(5));
        generation(STRANGER, "theirs", minutesAgo(5));

        Page<Generation> page = generationRepository
                .findByUserIdOrderByCreatedAtDesc(OWNER, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(Generation::getPrompt).containsExactly("mine");
    }

    // --- findByIdAndUserId --------------------------------------------------

    @Test
    void findByIdAndUserIdFindsTheOwnersOwnRow() {
        Generation mine = generation(OWNER, "mine", minutesAgo(5));

        assertThat(generationRepository.findByIdAndUserId(mine.getId(), OWNER))
                .isPresent()
                .get()
                .extracting(Generation::getPrompt)
                .isEqualTo("mine");
    }

    /**
     * The core IDOR guard, at the layer where it is enforced: a real, existing id plus the
     * wrong owner is empty, not the document. The endpoint turns that into a 404.
     */
    @Test
    void findByIdAndUserIdIsEmptyForSomeoneElsesRow() {
        Generation theirs = generation(STRANGER, "theirs", minutesAgo(5));

        assertThat(generationRepository.findById(theirs.getId())).isPresent();
        assertThat(generationRepository.findByIdAndUserId(theirs.getId(), OWNER)).isEmpty();
    }

    // --- deleteByIdAndUserId ------------------------------------------------

    @Test
    void deleteByIdAndUserIdRemovesTheRowAndReportsOne() {
        Generation mine = generation(OWNER, "mine", minutesAgo(5));

        assertThat(generationRepository.deleteByIdAndUserId(mine.getId(), OWNER)).isEqualTo(1);
        assertThat(generationRepository.findById(mine.getId())).isEmpty();
    }

    /**
     * The reason the method returns a count rather than {@code void}: zero is how the service
     * distinguishes "deleted" from "there was nothing of yours to delete", and a cross-user
     * delete must land in the second case with the document still there afterwards.
     */
    @Test
    void deleteByIdAndUserIdDeletesNothingForTheWrongOwner() {
        Generation theirs = generation(STRANGER, "theirs", minutesAgo(5));

        assertThat(generationRepository.deleteByIdAndUserId(theirs.getId(), OWNER)).isZero();
        assertThat(generationRepository.findById(theirs.getId())).isPresent();
    }

    // --- the image collection ----------------------------------------------

    @Test
    void imageBytesRoundTripThroughBinDataUnchanged() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, (byte) 0xFF};

        GenerationImage saved = generationImageRepository.save(
                new GenerationImage(OWNER, png, "image/png"));

        GenerationImage loaded = generationImageRepository.findByIdAndUserId(saved.getId(), OWNER)
                .orElseThrow();

        assertThat(loaded.getData()).isEqualTo(png);
        assertThat(loaded.getContentType()).isEqualTo("image/png");
        assertThat(loaded.size()).isEqualTo(png.length);
    }

    /**
     * Defence in depth from {@code GenerationImage#userId}: even holding a raw image id — which
     * the API never publishes — the wrong owner reads nothing.
     */
    @Test
    void imagesAreNotReadableWithTheWrongOwnerEvenGivenTheRawId() {
        GenerationImage theirs = generationImageRepository.save(
                new GenerationImage(STRANGER, new byte[]{1, 2, 3}, "image/png"));

        assertThat(generationImageRepository.findById(theirs.getId())).isPresent();
        assertThat(generationImageRepository.findByIdAndUserId(theirs.getId(), OWNER)).isEmpty();
    }

    /**
     * Auditing is switched on and reaches this collection. If {@code @EnableMongoAuditing} were
     * missing, {@code createdAt} would stay null forever with nothing logged and the sort in
     * {@code findByUserIdOrderByCreatedAtDesc} would compare nulls.
     */
    @Test
    void createdDateIsStampedOnInsertWhenNoIdIsSet() {
        Generation generation = new Generation();
        generation.setUserId(OWNER);
        generation.setType(GenerationType.IMAGE_TO_IMAGE);
        generation.setPrompt("make it dreamy");

        Instant before = Instant.now().minusSeconds(1);
        Generation saved = generationRepository.save(generation);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt())
                .as("@CreatedDate needs @EnableMongoAuditing or it stays null silently")
                .isNotNull()
                .isAfter(before);
    }
}

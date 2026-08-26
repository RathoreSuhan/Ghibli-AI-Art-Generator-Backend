package in.suhansingh.ghbliapi.service;

import in.suhansingh.ghbliapi.dto.GenerationSummaryResponse;
import in.suhansingh.ghbliapi.dto.PageResponse;
import in.suhansingh.ghbliapi.exception.GenerationNotFoundException;
import in.suhansingh.ghbliapi.model.Generation;
import in.suhansingh.ghbliapi.model.GenerationImage;
import in.suhansingh.ghbliapi.enums.GenerationType;
import in.suhansingh.ghbliapi.repository.GenerationImageRepository;
import in.suhansingh.ghbliapi.repository.GenerationRepository;
import in.suhansingh.ghbliapi.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.Optional;

/**
 * Persistence and retrieval of generation history. Knows nothing about calling Stability —
 * {@code GhibliArtService} owns that, and its prompt construction, style mapping and resize
 * algorithm are untouched by this class.
 *
 * <p>Two responsibilities that look similar and are not:
 *
 * <ul>
 *   <li>{@link #recordQuietly} runs <em>after</em> a successful generation and must never
 *       change the outcome of the request. It swallows everything.</li>
 *   <li>{@link #list}, {@link #imageFor} and {@link #delete} serve the history endpoints and
 *       must fail loudly, because a swallowed error there would mean handing a caller an empty
 *       page or somebody else's bytes.</li>
 * </ul>
 */
@Service
public class GenerationHistoryService {

    private static final Logger log = LoggerFactory.getLogger(GenerationHistoryService.class);

    /**
     * Recorded as the style for {@link GenerationType#IMAGE_TO_IMAGE}.
     *
     * <p>Photo-to-art takes no style from the caller; {@code GhibliArtService#createGhibliArt}
     * hardcodes {@code style_preset = "anime"} for every request. This constant mirrors that
     * literal. It is duplicated rather than shared because reading the applied preset back out
     * would mean changing that method's return type from {@code byte[]} to a result object —
     * a change to the generation path, which this phase is not allowed to make. If the preset
     * there ever changes, this constant has to change with it;
     * {@code GhibliArtServiceStyleTest} is the place that would catch the divergence.
     */
    static final String IMAGE_TO_IMAGE_STYLE = "anime";

    /** Mirrors the fallback in {@code TextGenerationRequestDTO} for an absent style. */
    static final String DEFAULT_TEXT_STYLE = "general";

    private final GenerationRepository generationRepository;
    private final GenerationImageRepository generationImageRepository;

    /**
     * Read with {@code @Value} using the same keys and the same defaults as
     * {@code GhibliArtService}, so both classes resolve the same engine id from one
     * configuration source. Injecting {@code GhibliArtService} to ask it instead would create a
     * dependency purely to read a constant — and it is mocked in several tests, where the answer
     * would come back null.
     */
    private final String textEngineId;
    private final String imageEngineId;

    public GenerationHistoryService(
            GenerationRepository generationRepository,
            GenerationImageRepository generationImageRepository,
            @Value("${stability.api.text-engine:stable-diffusion-xl-1024-v1-0}") String textEngineId,
            @Value("${stability.api.image-engine:stable-diffusion-xl-1024-v1-0}") String imageEngineId) {
        this.generationRepository = generationRepository;
        this.generationImageRepository = generationImageRepository;
        this.textEngineId = textEngineId;
        this.imageEngineId = imageEngineId;
    }

    // --- write path ---------------------------------------------------------

    /**
     * Stores the image and its metadata for whoever is authenticated, and <strong>never throws
     * under any circumstance</strong>.
     *
     * <p>That is the requirement, not defensive habit. By the time this runs, the user has
     * already waited on a paid Stability call that produced an image they can see. Letting a
     * Mongo timeout, a disconnected Atlas, an oversized document or a bug in this method turn
     * that into a 500 would destroy the artifact to protect the bookkeeping. So the failure mode
     * is deliberately asymmetric: the PNG always goes back on the wire, and a failure here costs
     * a history row and produces a log line at ERROR with the owner and prompt so it is
     * diagnosable after the fact.
     *
     * <p>Synchronous rather than {@code @Async}, and that is the right call twice over. It adds a
     * few milliseconds of Mongo write to a request that just spent seconds at Stability, and it
     * means the row is committed before the response is sent — so Phase 5's requirement that a
     * new generation appear in history without a manual refresh holds without a race. An async
     * version would also lose the {@code SecurityContext}, since it does not propagate to another
     * thread by default, and would silently record nothing.
     *
     * @param requestedStyle the style the caller asked for; ignored for
     *                       {@link GenerationType#IMAGE_TO_IMAGE}, which has none
     * @param png            bytes exactly as returned to the client
     */
    public void recordQuietly(GenerationType type, String prompt, String requestedStyle, byte[] png) {
        try {
            if (png == null || png.length == 0) {
                log.warn("Not recording a {} generation: the response carried no image bytes", type);
                return;
            }

            // Empty, not an exception. Every caller is behind an authenticated route, so this is
            // unreachable in production — but a slice test with a @WithMockUser principal has no
            // Mongo id, and that must not fail a request whose subject is image generation.
            Optional<String> userId = CurrentUser.id();
            if (userId.isEmpty()) {
                log.warn("Not recording a {} generation: no authenticated user id in the security context", type);
                return;
            }

            save(userId.get(), type, prompt, requestedStyle, png);

        } catch (Exception ex) {
            // Exception, not RuntimeException: an Error is still allowed to propagate, but every
            // checked or unchecked failure below this line is a bookkeeping failure and the user
            // keeps their image. Logged at ERROR with the prompt so the row can be reconstructed.
            log.error("Failed to persist {} generation for prompt \"{}\" — the image was still returned to the client",
                    type, prompt, ex);
        }
    }

    /**
     * Image document first, metadata second.
     *
     * <p>There is no transaction here and no need for one: this is two documents with no
     * invariant that must hold atomically for the rest of the system to be correct (PLAN.md §3).
     * The ordering is still not arbitrary. Metadata-first would leave, on a failure, a history row
     * whose image endpoint 404s — a visibly broken entry the user has to look at. Image-first
     * leaves unreferenced bytes, which nothing can reach, so the compensating delete below is an
     * optimisation for storage rather than a correctness fix. If it also fails, the log line names
     * the id.
     */
    private void save(String userId, GenerationType type, String prompt, String requestedStyle, byte[] png) {
        GenerationImage image = generationImageRepository.save(
                new GenerationImage(userId, png, MediaType.IMAGE_PNG_VALUE));

        try {
            Generation generation = new Generation();
            generation.setUserId(userId);
            generation.setType(type);
            generation.setPrompt(prompt);
            generation.setStyle(styleFor(type, requestedStyle));
            generation.setEngineId(engineFor(type));
            generation.setImageId(image.getId());
            generation.setImageSizeBytes(image.size());
            // createdAt is left null on purpose: @CreatedDate stamps it during the save below,
            // which only happens because id is still null and Spring Data therefore treats this
            // as an insert.
            applyDimensions(generation, png);

            Generation saved = generationRepository.save(generation);
            log.debug("Recorded {} generation {} ({} bytes) for user {}", type, saved.getId(), image.size(), userId);

        } catch (RuntimeException ex) {
            try {
                generationImageRepository.deleteById(image.getId());
            } catch (RuntimeException cleanupFailure) {
                log.error("Orphaned image document {} — metadata save failed and so did the cleanup",
                        image.getId(), cleanupFailure);
            }
            throw ex;
        }
    }

    private String styleFor(GenerationType type, String requestedStyle) {
        if (type == GenerationType.IMAGE_TO_IMAGE) {
            return IMAGE_TO_IMAGE_STYLE;
        }
        return requestedStyle == null || requestedStyle.isBlank() ? DEFAULT_TEXT_STYLE : requestedStyle;
    }

    private String engineFor(GenerationType type) {
        return type == GenerationType.IMAGE_TO_IMAGE ? imageEngineId : textEngineId;
    }

    /**
     * Reads width and height out of the PNG header without decoding the pixels.
     *
     * <p>{@code ImageIO.read} would allocate a full {@code BufferedImage} — roughly 4 MB of heap
     * for a 1024×1024 image — to learn two integers. An {@code ImageReader} parses the header
     * only. Failure is not an error condition: a response Java cannot decode still deserves a
     * history row, so the fields stay null and the reason is logged at debug.
     */
    private void applyDimensions(Generation generation, byte[] png) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
            if (input == null) {
                return;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                log.debug("No ImageIO reader for the returned bytes — storing the generation without dimensions");
                return;
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                generation.setWidth(reader.getWidth(0));
                generation.setHeight(reader.getHeight(0));
            } finally {
                // Releases the reader's native resources. ImageIO pools readers, so skipping this
                // leaks one per generation for the lifetime of the JVM.
                reader.dispose();
            }

        } catch (Exception ex) {
            log.debug("Could not read image dimensions — storing the generation without them", ex);
        }
    }

    // --- read path ---------------------------------------------------------

    /**
     * One page of the caller's own history, newest first, with no image bytes anywhere in it.
     *
     * <p>The {@link PageRequest} is built without a {@code Sort} on purpose. Order comes from
     * {@code findByUserIdOrderByCreatedAtDesc}, and a sorted {@code Pageable} would merge with
     * that rather than replace it — see the note on the repository method.
     *
     * @param page zero-based; already range-checked by the controller
     */
    public PageResponse<GenerationSummaryResponse> list(int page, int size) {
        String userId = CurrentUser.requireId();

        Pageable pageable = PageRequest.of(page, size);
        Page<Generation> generations = generationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);

        // Entities are converted here and the Page itself is never serialised. Generation holds
        // no byte[] field and imageId is a plain String, not a @DBRef, so nothing in this call
        // touches generation_images.
        return PageResponse.from(generations, GenerationSummaryResponse::from);
    }

    /**
     * The bytes for one of the caller's generations.
     *
     * <p>Two owner-scoped lookups rather than one join, which is what the storage split costs and
     * it is cheap: both are single-document reads by {@code _id}. The first is the authorization
     * decision — a generation belonging to someone else is not found, and the caller cannot tell
     * that from a nonexistent id.
     *
     * @throws GenerationNotFoundException when the generation is not the caller's, does not
     *         exist, or its image document is missing
     */
    public GenerationImage imageFor(String generationId) {
        String userId = CurrentUser.requireId();

        Generation generation = generationRepository.findByIdAndUserId(generationId, userId)
                .orElseThrow(() -> new GenerationNotFoundException(
                        "No generation " + generationId + " for the current user"));

        if (generation.getImageId() == null) {
            // Only reachable if a metadata document was written without its image, which the save
            // ordering prevents. Same 404 rather than a 500: from the caller's side there is no
            // image to fetch, and the cause is ours to find in the log.
            log.error("Generation {} has no imageId — metadata exists with no bytes", generationId);
            throw new GenerationNotFoundException("No image stored for generation " + generationId);
        }

        return generationImageRepository.findByIdAndUserId(generation.getImageId(), userId)
                .orElseThrow(() -> {
                    log.error("Generation {} references missing image document {}",
                            generationId, generation.getImageId());
                    return new GenerationNotFoundException("No image stored for generation " + generationId);
                });
    }

    /**
     * Removes both documents.
     *
     * <p>Metadata first. On a failure between the two, the leftover is unreferenced bytes that no
     * query can reach — invisible, and reclaimable later. The other order would leave a live
     * history row pointing at nothing, which is the failure the user actually sees.
     *
     * @throws GenerationNotFoundException when the generation is not the caller's or does not
     *         exist, so a cross-user delete is a 404 and removes nothing
     */
    public void delete(String generationId) {
        String userId = CurrentUser.requireId();

        Generation generation = generationRepository.findByIdAndUserId(generationId, userId)
                .orElseThrow(() -> new GenerationNotFoundException(
                        "No generation " + generationId + " for the current user"));

        // The userId predicate is in the delete filter too, not just in the lookup above. Belt
        // and braces against a future edit that drops the find and deletes by id alone.
        long removed = generationRepository.deleteByIdAndUserId(generationId, userId);
        if (removed == 0) {
            // Lost a race with a concurrent delete of the same row. The caller's intent is
            // satisfied either way, so this is not an error — but the image below still needs
            // clearing in case the other request stopped short.
            log.debug("Generation {} was already gone by the time the delete ran", generationId);
        }

        if (generation.getImageId() != null) {
            generationImageRepository.deleteById(generation.getImageId());
        }

        log.debug("Deleted generation {} and image {} for user {}",
                generationId, generation.getImageId(), userId);
    }
}

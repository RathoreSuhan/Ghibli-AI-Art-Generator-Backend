package in.suhansingh.ghbliapi.controller;

import in.suhansingh.ghbliapi.dto.GenerationSummaryResponse;
import in.suhansingh.ghbliapi.dto.PageResponse;
import in.suhansingh.ghbliapi.model.GenerationImage;
import in.suhansingh.ghbliapi.service.GenerationHistoryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * User-scoped generation history.
 *
 * <p><strong>No handler here takes a user id.</strong> Not as a path variable, not as a query
 * parameter, not from a header. Every method resolves the owner from the {@code SecurityContext}
 * inside {@link GenerationHistoryService}, because an endpoint shaped
 * {@code /api/v1/generations?userId=…} is an IDOR that no amount of later validation makes safe —
 * the fix is for the id not to be an input at all.
 *
 * <p>Separate from {@code GenerationController} rather than bolted onto it: that class is a
 * write-only façade over Stability with a mocked service in its slice test, and this one is a
 * read/delete façade over Mongo. Splitting them also keeps
 * {@code @WebMvcTest(GenerationController.class)} loading one small controller.
 *
 * <p>No {@code @CrossOrigin}. CORS comes from the {@code corsConfigurationSource} bean, which is
 * where {@code DELETE} had to be added to the allowed methods for the third endpoint below to be
 * reachable from a browser at all.
 *
 * <p>Deliberately no {@code @Validated} on the class. Spring Framework 6.1+ applies the
 * {@code @Min}/{@code @Max} constraints on the parameters below by itself and raises
 * {@code HandlerMethodValidationException}, which {@code GlobalExceptionHandler} inherits a
 * ProblemDetail 400 for. Adding {@code @Validated} would switch that to the older AOP path — a
 * CGLIB proxy and a {@code ConstraintViolationException} — for no gain.
 */
@RestController
@RequestMapping("/api/v1/generations")
@RequiredArgsConstructor
public class GenerationHistoryController {

    /**
     * Twelve divides by 2, 3 and 4, so the last row of the history grid is full at every
     * breakpoint the frontend uses.
     */
    static final String DEFAULT_PAGE_SIZE = "12";

    /**
     * A ceiling, not a suggestion. Each row is metadata only, but an unbounded {@code size}
     * would let one request ask Mongo for every document a user owns, and the cost of that grows
     * with the account rather than with the page.
     */
    static final int MAX_PAGE_SIZE = 100;

    private final GenerationHistoryService generationHistoryService;

    /**
     * Newest first, metadata only.
     *
     * <p>There is no {@code sort} parameter, and that omission is load-bearing. Order is fixed by
     * the repository method name; binding a Spring Data {@code Pageable} argument here would let
     * {@code ?sort=…} merge into that and either reverse the contract or force an in-memory sort
     * the compound index cannot serve.
     *
     * @return 200 with a {@link PageResponse} of {@link GenerationSummaryResponse}; an empty
     *         {@code content} array rather than a 404 when the user has no history, because "no
     *         generations yet" is a successful answer to the question asked
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PageResponse<GenerationSummaryResponse>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        return ResponseEntity.ok(generationHistoryService.list(page, size));
    }

    /**
     * The PNG for one of the caller's generations.
     *
     * <p>This is the endpoint that a browser cannot reach with {@code <img src>}, because it
     * requires an {@code Authorization} header and the image loader sends none. Phase 5 fetches it
     * and wraps the blob in {@code URL.createObjectURL}.
     *
     * @return 200 with raw {@code image/png} bytes, or a 404 ProblemDetail when the generation is
     *         not the caller's — the same answer as for an id that never existed
     */
    @GetMapping(value = "/{id}/image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> image(@PathVariable String id) {
        GenerationImage image = generationHistoryService.imageFor(id);
        byte[] data = image.getData();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        image.getContentType() != null ? image.getContentType() : MediaType.IMAGE_PNG_VALUE))
                .contentLength(data.length)
                // The bytes behind a generation id never change, so a day of caching removes a
                // megabyte of transfer per revisit. private, because the response is
                // user-specific and must not land in a shared proxy.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                // inline so a preview renders in place; the filename is only a hint for a
                // browser-initiated save, and Phase 5 names its own download. Note the build() —
                // without it the header carries the builder's toString(), which is a valid header
                // value as far as the servlet API is concerned and total nonsense to a client.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("ghibli-" + id + ".png").build().toString())
                .body(data);
    }

    /**
     * Removes the metadata document and its image document.
     *
     * @return 204, or a 404 ProblemDetail for an id the caller does not own. Not idempotent-by-404:
     *         deleting twice gives 204 then 404, which is the honest report of what happened
     *         rather than pretending the second call did something
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        generationHistoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

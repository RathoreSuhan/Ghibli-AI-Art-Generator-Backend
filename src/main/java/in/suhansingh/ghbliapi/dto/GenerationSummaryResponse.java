package in.suhansingh.ghbliapi.dto;

import in.suhansingh.ghbliapi.model.Generation;
import in.suhansingh.ghbliapi.enums.GenerationType;

import java.time.Instant;

/**
 * One row of history. <strong>Carries no image bytes and no image id.</strong>
 *
 * <p>A record, so "there is no byte array here" is enforced by the type rather than by
 * remembering not to add one — the component list is the entire wire contract and cannot be
 * extended by a setter or an inherited field.
 *
 * <p>{@code imageId} is omitted on purpose even though it is harmless to expose. The bytes are
 * addressed as {@code /api/v1/generations/{id}/image}, keyed on the <em>generation</em> id, so
 * publishing the internal image id would only invite a client to build URLs from it — a second
 * way to name the same resource, and one that would have to grow its own ownership check.
 *
 * <p>{@link #width}, {@link #height} and {@link #imageSizeBytes} are what let a grid reserve
 * layout space and show a file size without fetching a single megabyte. They are nullable:
 * older rows, or a response Java could not decode, have no measurement, and 0 would read as
 * one.
 *
 * @param id             generation id; the path segment for the image and delete endpoints
 * @param type           which endpoint produced it
 * @param prompt         the prompt as typed, without the Ghibli suffix the service appends
 * @param style          user-selected style for text-to-image, applied preset for image-to-image
 * @param engineId       Stability engine used
 * @param width          pixel width of the stored PNG, or null if it could not be read
 * @param height         pixel height of the stored PNG, or null if it could not be read
 * @param imageSizeBytes stored PNG size, or null if unknown
 * @param createdAt      ISO-8601 UTC instant, e.g. {@code 2026-08-26T10:15:30.123Z}
 */
public record GenerationSummaryResponse(
        String id,
        GenerationType type,
        String prompt,
        String style,
        String engineId,
        Integer width,
        Integer height,
        Long imageSizeBytes,
        Instant createdAt) {

    public static GenerationSummaryResponse from(Generation generation) {
        return new GenerationSummaryResponse(
                generation.getId(),
                generation.getType(),
                generation.getPrompt(),
                generation.getStyle(),
                generation.getEngineId(),
                generation.getWidth(),
                generation.getHeight(),
                generation.getImageSizeBytes(),
                generation.getCreatedAt());
    }
}

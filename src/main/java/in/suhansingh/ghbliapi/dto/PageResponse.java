package in.suhansingh.ghbliapi.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * The pagination envelope. Phase 5 codes against exactly these eight fields.
 *
 * <p>Written by hand instead of returning Spring Data's {@link Page}, because serialising a
 * {@code PageImpl} straight out of a controller is not a stable contract. Spring Data 3.3+
 * warns about it at runtime ("Serializing PageImpl instances as-is is not supported") for a
 * concrete reason: the JSON is a reflection of internal structure, so it ships {@code pageable}
 * and {@code sort} sub-objects, spells the current page {@code number} and the page size
 * {@code size}, and has changed shape across versions. A frontend written against that couples
 * itself to a Spring Data release.
 *
 * <p>Names here are chosen to be unambiguous rather than familiar — {@code page} rather than
 * {@code number}, because {@code number} beside {@code numberOfElements} is a field pair nobody
 * can keep straight.
 *
 * @param content          this page's items, already mapped to DTOs
 * @param page             zero-based index of this page, echoing the request
 * @param size             requested page size, not the number returned
 * @param totalElements    matching documents across all pages; requires a count query
 * @param totalPages       {@code ceil(totalElements / size)}; 0 when there are no results
 * @param first            true when {@code page == 0}
 * @param last             true when this is the final page, and also when there are no results
 * @param numberOfElements items actually in {@link #content}, which is smaller than
 *                         {@link #size} on the last page and 0 past the end
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        int numberOfElements) {

    /**
     * @param mapper entity to DTO. Taken as a function rather than mapping in the caller so the
     *               conversion happens inside the page walk and no intermediate
     *               {@code Page<Generation>} of entities is ever handed to a serialiser.
     */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                page.getNumberOfElements());
    }
}

package in.suhansingh.ghbliapi.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * The single place the application learns who is calling.
 *
 * <p><strong>Nothing here reads the request.</strong> Not the body, not a query parameter, not
 * a header other than the one the JWT filter already verified. That is the whole guarantee: an
 * owner id that can only come from a signature-checked token cannot be swapped for someone
 * else's, so {@code ?userId=…} is not an attack because it is not an input. Routing every
 * caller through one accessor is what makes that auditable — there is exactly one method to
 * read before believing the claim.
 *
 * <p>Reads {@code SecurityContextHolder} rather than taking an {@code @AuthenticationPrincipal}
 * parameter so that the same code answers on both paths that need it: the history endpoints,
 * where a principal argument would work, and the fire-and-forget persistence hook inside the
 * generation flow, which is called from a service and has no handler signature to hang an
 * annotation on.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * @return the authenticated user's Mongo id, or empty when there is no usable principal
     *
     * <p>Empty covers three distinct situations, all of which mean the same thing to a caller:
     * no authentication at all, an {@code AnonymousAuthenticationToken} (whose
     * {@code isAuthenticated()} returns <em>true</em> while its principal is the string
     * {@code "anonymousUser"}, which is why the type check below matters), and a principal of
     * some other {@link org.springframework.security.core.userdetails.UserDetails} type such as
     * the one {@code @WithMockUser} installs — that one carries a username but no Mongo id, and
     * there is no id to invent.
     */
    public static Optional<String> id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        // Not authentication.getName(): that delegates to UserPrincipal#getUsername(), which is
        // the e-mail. Scoping a query by e-mail would work today and break the moment an
        // address is editable.
        return authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.getId() != null
                ? Optional.of(principal.getId())
                : Optional.empty();
    }

    /**
     * @return the authenticated user's Mongo id
     * @throws AccessDeniedException when there is none — 403 through
     *         {@code GlobalExceptionHandler}, never a null owner id reaching a query. An
     *         unreachable state in practice, since {@code SecurityConfig} requires
     *         authentication on every path that calls this; it is a hard failure rather than a
     *         fallback because the alternative — a query filtered by {@code userId: null} —
     *         would return whichever documents happened to have no owner.
     */
    public static String requireId() {
        return id().orElseThrow(() -> new AccessDeniedException(
                "No authenticated user in the security context"));
    }
}

package in.suhansingh.ghbliapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TextGenerationRequestDTO {

    /** Falls back to the general Ghibli look when the request omits or nulls out "style". */
    public static final String DEFAULT_STYLE = "general";

    // Enforced only because spring-boot-starter-validation is now on the classpath —
    // spring-boot-starter-web alone leaves @NotBlank silently inert.
    @NotBlank(message = "must not be blank")
    @Size(max = 2000, message = "must be at most 2000 characters")
    private String prompt;

    // Safe default rather than @NotBlank: an absent style is a valid request, not a client error.
    // GhibliArtService also guards null/blank, because an explicit "style": null overwrites this.
    private String style = DEFAULT_STYLE;
}

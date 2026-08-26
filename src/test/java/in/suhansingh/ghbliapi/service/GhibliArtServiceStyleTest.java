package in.suhansingh.ghbliapi.service;

import in.suhansingh.ghbliapi.client.StabilityAIClient;
import in.suhansingh.ghbliapi.dto.TextToImageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the style handling in {@code createGhibliArtFromText}. The old code called
 * {@code style.equals("general")} directly, so a request without a style threw a raw
 * NullPointerException (surfacing as an empty 500). The mapping itself is unchanged and
 * asserted here so it stays that way.
 */
class GhibliArtServiceStyleTest {

    private static final byte[] FAKE_PNG = {(byte) 0x89, 'P', 'N', 'G'};

    private StabilityAIClient stabilityAIClient;
    private GhibliArtService service;

    @BeforeEach
    void setUp() {
        stabilityAIClient = mock(StabilityAIClient.class);
        when(stabilityAIClient.generateImageFromText(anyString(), anyString(), any())).thenReturn(FAKE_PNG);

        service = new GhibliArtService(
                stabilityAIClient,
                mock(RestTemplate.class),
                "test-key",
                "stable-diffusion-xl-1024-v1-0",
                "stable-diffusion-xl-1024-v1-0",
                "https://api.stability.test");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "general"})
    void absentOrGeneralStyleMapsToAnimeWithoutNpe(String style) {
        assertThat(service.createGhibliArtFromText("a quiet hillside", style)).isEqualTo(FAKE_PNG);
        assertThat(capturedPayload().getStyle_preset()).isEqualTo("anime");
    }

    @Test
    void underscoredStyleIsStillHyphenatedForStability() {
        service.createGhibliArtFromText("a quiet hillside", "analog_film");
        assertThat(capturedPayload().getStyle_preset()).isEqualTo("analog-film");
    }

    @Test
    void promptSuffixIsUnchanged() {
        service.createGhibliArtFromText("a quiet hillside", "general");
        assertThat(capturedPayload().getText_prompts().get(0).getText())
                .isEqualTo("a quiet hillside, in the beautiful, detailed anime style of studio ghibli.");
    }

    private TextToImageRequest capturedPayload() {
        ArgumentCaptor<TextToImageRequest> payload = ArgumentCaptor.forClass(TextToImageRequest.class);
        verify(stabilityAIClient).generateImageFromText(anyString(), anyString(), payload.capture());
        return payload.getValue();
    }
}

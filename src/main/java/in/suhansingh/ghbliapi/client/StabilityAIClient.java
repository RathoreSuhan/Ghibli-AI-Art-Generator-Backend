package in.suhansingh.ghbliapi.client;

import in.suhansingh.ghbliapi.dto.TextToImageRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;


/**
 * Feign client for Stability AI. Declares the <strong>text-to-image</strong> call only.
 *
 * <p>Image-to-image is deliberately absent. It is posted by {@code GhibliArtService} through an
 * injected {@code RestTemplate}, because Feign cannot carry its {@code init_image} part: the
 * resize step produces {@code byte[]}, which has to be wrapped as a Spring {@code Resource}, and
 * feign-form drops {@code Resource} parts without raising anything. That is measured, not assumed
 * — see {@code FeignMultipartEncodingTest}, which encodes the real request and asserts on the
 * bytes. Text-to-image sends a JSON body, so it never touches that code path.
 *
 * <p>The Feign variant of image-to-image that used to live here was called by nothing; it was
 * removed rather than left as dead code.
 */
@FeignClient(
        name = "stabilityAiClient",
        url = "${stability.api.base-url}",
        configuration = in.suhansingh.ghbliapi.config.FeignConfig.class
)

public interface StabilityAIClient {

    @PostMapping(
            value = "/v1/generation/{engine_id}/text-to-image",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            // Stability returns a binary PNG stream when this accepts header is present.
            headers = {"Accept=image/png"}
    )
    byte[] generateImageFromText(
            @RequestHeader("Authorization") String authorizationHeader,
            @PathVariable("engine_id") String engineId,
            @RequestBody TextToImageRequest requestBody
    );

}

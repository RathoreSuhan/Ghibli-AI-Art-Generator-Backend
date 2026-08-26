package in.suhansingh.ghbliapi.client;

import in.suhansingh.ghbliapi.dto.TextToImageRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;


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

    // Note: image-to-image is not declared here. GhibliArtService posts that multipart body
    // directly with an injected RestTemplate. The Feign variant that used to live here was
    // never called by anything — removed rather than left as dead code.

}

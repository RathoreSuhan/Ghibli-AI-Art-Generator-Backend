package in.suhansingh.ghbliapi.controller;

import in.suhansingh.ghbliapi.dto.TextGenerationRequestDTO;
import in.suhansingh.ghbliapi.enums.GenerationType;
import in.suhansingh.ghbliapi.service.GenerationHistoryService;
import in.suhansingh.ghbliapi.service.GhibliArtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
// CORS is not configured here. The @CrossOrigin annotation that used to sit on this class
// moved to the corsConfigurationSource bean in SecurityConfig, which applies to every path
// including /api/v1/auth/** — two independent CORS declarations would have drifted apart.
@RequiredArgsConstructor
public class GenerationController {
    private final GhibliArtService ghibliArtService;

    // History is recorded from here rather than from inside GhibliArtService, for two reasons:
    // that service's signatures and its prompt/style/resize logic are off-limits this phase, and
    // it is mocked in the slice tests — persistence wired inside a mock records nothing. The
    // recording call sits after the generate call, so nothing is written for a failed generation.
    private final GenerationHistoryService generationHistoryService;

    // Failures are translated by GlobalExceptionHandler into an RFC 9457 ProblemDetail.
    // Success stays raw image/png bytes.
    @PostMapping(value = "/generate", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> generateGhibliArt(@RequestParam("image") MultipartFile image, @RequestParam("prompt") String prompt) {
        byte[] imageBytes = ghibliArtService.createGhibliArt(image, prompt);

        // Style is null: this endpoint accepts none, and the service applies a fixed preset that
        // GenerationHistoryService substitutes. recordQuietly cannot throw, so the return below
        // is reached whatever Mongo does — the user never loses an image to a bookkeeping failure.
        generationHistoryService.recordQuietly(GenerationType.IMAGE_TO_IMAGE, prompt, null, imageBytes);

        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(imageBytes);
    }

    @PostMapping(value = "/generate-from-text", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> generateGhibliArtFromText(@Valid @RequestBody TextGenerationRequestDTO requestDTO) {
        byte[] imageBytes = ghibliArtService.createGhibliArtFromText(requestDTO.getPrompt(), requestDTO.getStyle());

        // The prompt as the user typed it, not the Ghibli-suffixed string the service builds, so
        // history shows back what was asked for and prompt construction stays untouched.
        generationHistoryService.recordQuietly(
                GenerationType.TEXT_TO_IMAGE, requestDTO.getPrompt(), requestDTO.getStyle(), imageBytes);

        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(imageBytes);
    }
}

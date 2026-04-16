package in.suhansingh.ghbliapi.service;

import in.suhansingh.ghbliapi.client.StabilityAIClient;
import in.suhansingh.ghbliapi.dto.TextToImageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Service
public class GhibliArtService {

    private final StabilityAIClient stabilityAIClient;
    private final String apiKey;
    // Using SDXL as the default because the old v1-6 engine id is no longer available.
    private final String textEngineId;
    // Keep image and text engines configurable for quick provider-side changes.
    private final String imageEngineId;
    // Added base URL to build direct image-to-image API request endpoint.
    private final String stabilityBaseUrl;

    public GhibliArtService(
            StabilityAIClient stabilityAIClient,
            @Value("${stability.api.key}") String apiKey,
            @Value("${stability.api.text-engine:stable-diffusion-xl-1024-v1-0}") String textEngineId,
            @Value("${stability.api.image-engine:stable-diffusion-xl-1024-v1-0}") String imageEngineId,
            @Value("${stability.api.base-url}") String stabilityBaseUrl
    ) {
        this.stabilityAIClient = stabilityAIClient;
        this.apiKey = apiKey;
        this.textEngineId = textEngineId;
        this.imageEngineId = imageEngineId;
        this.stabilityBaseUrl = stabilityBaseUrl;
    }

    public byte[] createGhibliArt(MultipartFile image, String prompt) {
        String finalPrompt = prompt+ ", in the beautiful, detailed anime style of studio ghibli.";
        String stylePreset = "anime";

        try {
            // Stability image-to-image endpoint accepts maximum 5MB init_image; validate early with readable error.
            if (image.getSize() > 5 * 1024 * 1024) {
                throw new IllegalArgumentException("Image is too large. Stability supports up to 5MB for photo-to-art.");
            }

            // CRITICAL FIX: Stability API only accepts specific dimension pairs for SDXL.
            // Resize user-uploaded image to the nearest allowed dimension before sending to API.
            // This prevents "invalid_sdxl_v1_dimensions" 400 errors from Stability.
            byte[] resizedImageBytes = resizeImageToStabilityDimensions(image.getBytes());

            // Switched PhotoToArt to direct multipart HTTP call because multipart Feign encoding was causing generation failures.
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setAccept(java.util.List.of(MediaType.IMAGE_PNG));

            // Wrap resized image as resource so multipart body includes a real filename.
            ByteArrayResource imageResource = new ByteArrayResource(resizedImageBytes) {
                @Override
                public String getFilename() {
                    return image.getOriginalFilename() != null ? image.getOriginalFilename() : "upload.png";
                }
            };

            MultiValueMap<String, Object> formData = new LinkedMultiValueMap<>();
            formData.add("init_image", imageResource);
            formData.add("text_prompts[0][text]", finalPrompt);
            formData.add("style_preset", stylePreset);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(formData, headers);
            String endpoint = stabilityBaseUrl + "/v1/generation/" + imageEngineId + "/image-to-image";

            ResponseEntity<byte[]> response = new RestTemplate().exchange(
                    endpoint,
                    HttpMethod.POST,
                    requestEntity,
                    byte[].class
            );

            return response.getBody();
        } catch (Exception ex) {
            // Added explicit runtime exception message so controller/frontend can show the real backend failure reason.
            throw new RuntimeException("Photo to art generation failed: " + ex.getMessage(), ex);
        }
    }

    public byte[] createGhibliArtFromText(String prompt, String style){
        String finalPrompt = prompt+ ", in the beautiful, detailed anime style of studio ghibli.";
        String stylePreset = style.equals("general") ? "anime" : style.replace("_", "-");

        TextToImageRequest requestPayLoad = new TextToImageRequest(finalPrompt, stylePreset);

        return stabilityAIClient.generateImageFromText(
                "Bearer " + apiKey,
                textEngineId,
                requestPayLoad
        );
    }

    /**
     * Resizes uploaded image to one of Stability's allowed SDXL dimensions.
     * Supported dimensions: 1024x1024, 1152x896, 1216x832, 1344x768, 1536x640,
     * 640x1536, 768x1344, 832x1216, 896x1152.
     * Selects the dimension with the closest aspect ratio to preserve image content.
     */
    private byte[] resizeImageToStabilityDimensions(byte[] imageBytes) throws IOException {
        // Decode uploaded image bytes into a BufferedImage for manipulation.
        ByteArrayInputStream input = new ByteArrayInputStream(imageBytes);
        BufferedImage originalImage = ImageIO.read(input);

        // Validate that the image was successfully decoded from user-provided bytes.
        if (originalImage == null) {
            throw new IOException("Could not read uploaded image. Ensure it is a valid image format (JPEG, PNG, etc).");
        }

        // Calculate aspect ratio of original image to preserve composition during resize.
        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();
        double originalAspectRatio = (double) originalWidth / originalHeight;

        // Stability allowed dimensions for SDXL (width x height) from API documentation.
        int[][] allowedDimensions = {
            {1024, 1024},
            {1152, 896},
            {1216, 832},
            {1344, 768},
            {1536, 640},
            {640, 1536},
            {768, 1344},
            {832, 1216},
            {896, 1152}
        };

        // Find the dimension pair with the closest aspect ratio to original image.
        // This minimizes distortion by selecting dimensions that match the original composition.
        int[] bestDimension = allowedDimensions[0];
        double minAspectRatioDiff = Double.MAX_VALUE;

        for (int[] dim : allowedDimensions) {
            double dimAspectRatio = (double) dim[0] / dim[1];
            double diff = Math.abs(dimAspectRatio - originalAspectRatio);
            if (diff < minAspectRatioDiff) {
                minAspectRatioDiff = diff;
                bestDimension = dim;
            }
        }

        int targetWidth = bestDimension[0];
        int targetHeight = bestDimension[1];

        // Create a new BufferedImage with target dimensions (TYPE_INT_RGB handles most image formats cleanly).
        BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        
        // Use Graphics2D to scale and draw the original image onto the resized canvas.
        // Wrap in try-finally to ensure graphics resources are always disposed.
        Graphics2D graphics2D = resizedImage.createGraphics();
        try {
            graphics2D.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
        } finally {
            // Always dispose Graphics2D to free native resources and prevent memory leaks.
            graphics2D.dispose();
        }

        // Encode resized image back to PNG bytes for transmission to Stability API.
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(resizedImage, "png", output);
        return output.toByteArray();
    }
} // End of GhibliArtService class
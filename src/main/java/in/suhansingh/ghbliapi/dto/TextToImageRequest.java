package in.suhansingh.ghbliapi.dto;

import lombok.Data;

import java.util.List;

@Data
public class TextToImageRequest {
    private List<TextPrompt> text_prompts;
    private double cfg_scale = 7;
    // Changed to an SDXL v1 allowed size to avoid 400 invalid_sdxl_v1_dimensions errors.
    private int height = 1024;
    // Changed to an SDXL v1 allowed size to avoid 400 invalid_sdxl_v1_dimensions errors.
    private int width = 1024;
    private int samples = 1;
    private int steps = 30;
    // Stability API expects this exact JSON field name.
    private String style_preset;

    //Inner class for the text_prompts
    public static class TextPrompt {
        private String text;
        public TextPrompt(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
        public void setText(String text) {
            this.text = text;
        }
    }

    //constructor
    public TextToImageRequest(String text, String style) {
        this.text_prompts = List.of(new TextPrompt(text));
        this.style_preset = style;
    }

}

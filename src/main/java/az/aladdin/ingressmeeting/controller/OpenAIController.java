package az.aladdin.ingressmeeting.controller;

import az.aladdin.ingressmeeting.service.OpenAIService;
import az.aladdin.ingressmeeting.service.SummaryPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/openai")
public class OpenAIController {

    private final OpenAIService openAIService;

    public OpenAIController(OpenAIService openAIService) {
        this.openAIService = openAIService;
    }

    @PostMapping("/process")
    public ResponseEntity<String> processText(@RequestBody Map<String, String> request) {
        String text = request.get("text");
        String prompt = request.getOrDefault("prompt", SummaryPrompt.DEFAULT);

        if (text == null || text.trim().isEmpty()) {
            log.warn("API /openai/process rejected | reason=empty_text");
            return ResponseEntity.badRequest().body("Text is required");
        }

        log.info("API /openai/process | textChars={}", text.length());
        try {
            String result = openAIService.sendToOpenAI(text, prompt);
            log.info("API /openai/process done | responseChars={}", result != null ? result.length() : 0);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("API /openai/process failed | error={}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("OpenAI failed: " + e.getMessage());
        }
    }
}

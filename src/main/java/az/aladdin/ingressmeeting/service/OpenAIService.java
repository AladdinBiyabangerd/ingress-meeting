package az.aladdin.ingressmeeting.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class OpenAIService {

    private final WebClient webClient;
    private final boolean apiKeyPresent;

    public OpenAIService(@Value("${openai.api-key:}") String apiKey,
                         @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl) {
        this.apiKeyPresent = apiKey != null && !apiKey.isBlank();
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (apiKey != null ? apiKey : ""))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("OpenAI client configured | baseUrl={} apiKeyPresent={}", baseUrl, apiKeyPresent);
    }

    public String sendToOpenAI(String text, String prompt) {
        long start = System.currentTimeMillis();
        log.info("OpenAI request started | textChars={} promptChars={} apiKeyPresent={}",
                text != null ? text.length() : 0,
                prompt != null ? prompt.length() : 0,
                apiKeyPresent);

        if (!apiKeyPresent) {
            throw new IllegalStateException("OPENAI_API_KEY is not set");
        }

        String fullPrompt = prompt + "\n\n" + text;

        Map<String, Object> requestBody = Map.of(
                "model", "gpt-4o-mini",
                "messages", List.of(
                        Map.of("role", "user", "content", fullPrompt)
                ),
                "temperature", 0.7
        );

        try {
            Map<String, Object> response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.containsKey("choices")) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
                if (!choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    String content = (String) message.get("content");
                    log.info("OpenAI request completed | elapsedMs={} responseChars={}",
                            System.currentTimeMillis() - start,
                            content != null ? content.length() : 0);
                    return content;
                }
            }
            log.warn("OpenAI returned empty choices | elapsedMs={}", System.currentTimeMillis() - start);
            return "No response from OpenAI";
        } catch (Exception e) {
            log.error("OpenAI request failed | elapsedMs={} error={}",
                    System.currentTimeMillis() - start, e.getMessage(), e);
            throw e;
        }
    }

    @Async
    public CompletableFuture<String> sendToOpenAIAsync(String text, String prompt) {
        try {
            String result = sendToOpenAI(text, prompt);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}

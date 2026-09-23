package az.aladdin.ingressmeeting.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pushes terminal job results to Academy so Academy never has to poll
 * {@code GET /api/whisper/status/{jobId}} while Whisper is saturating RAM.
 *
 * Env: {@code ACADEMY_CALLBACK_BASE_URL} = e.g.
 * {@code https://ingress.academy/portal/api/meeting/jobs}
 * → POST {@code {base}/{jobId}/complete} with {@code X-Api-Key}.
 */
@Slf4j
@Service
public class AcademyCallbackService {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 3;

    private final WebClient webClient;
    private final String callbackBaseUrl;
    private final String apiSecret;
    private final boolean enabled;

    public AcademyCallbackService(
            @Value("${academy.callback-base-url:}") String callbackBaseUrl,
            @Value("${app.api-secret:}") String apiSecret) {
        this.callbackBaseUrl = callbackBaseUrl == null ? "" : callbackBaseUrl.trim().replaceAll("/+$", "");
        this.apiSecret = apiSecret == null ? "" : apiSecret.trim();
        this.enabled = !this.callbackBaseUrl.isEmpty() && !this.apiSecret.isEmpty();
        this.webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("Academy callback configured | enabled={} baseUrl={}", enabled, this.callbackBaseUrl);
    }

    public void notifyJobResult(String jobId, MeetingProcessingService.ProcessingResult result) {
        if (!enabled) {
            log.debug("Academy callback skipped (not configured) | jobId={}", jobId);
            return;
        }
        if (jobId == null || jobId.isBlank() || result == null || result.status == null) {
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("status", result.status.name());
        if (result.transcription != null) {
            body.put("transcription", result.transcription);
        }
        if (result.summary != null) {
            body.put("summary", result.summary);
        }
        if (result.summaryError != null) {
            body.put("summaryError", result.summaryError);
        }
        if (result.error != null) {
            body.put("error", result.error);
        }

        String url = callbackBaseUrl + "/" + jobId + "/complete";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long start = System.currentTimeMillis();
            try {
                webClient.post()
                        .uri(url)
                        .header("X-Api-Key", apiSecret)
                        .bodyValue(body)
                        .retrieve()
                        .toBodilessEntity()
                        .block(TIMEOUT);
                log.info("Academy callback OK | jobId={} status={} attempt={} elapsedMs={}",
                        jobId, result.status, attempt, System.currentTimeMillis() - start);
                return;
            } catch (Exception e) {
                log.warn("Academy callback failed | jobId={} status={} attempt={}/{} elapsedMs={} error={}",
                        jobId, result.status, attempt, MAX_ATTEMPTS,
                        System.currentTimeMillis() - start, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        log.error("Academy callback exhausted retries | jobId={} status={} url={}",
                jobId, result.status, url);
    }
}

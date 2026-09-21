package az.aladdin.ingressmeeting.controller;

import az.aladdin.ingressmeeting.model.JobRecord;
import az.aladdin.ingressmeeting.service.MeetingProcessingService;
import az.aladdin.ingressmeeting.service.TranscriptStoreService;
import az.aladdin.ingressmeeting.service.UploadStorageService;
import az.aladdin.ingressmeeting.service.WhisperService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/whisper")
public class WhisperController {

    private final WhisperService whisperService;
    private final MeetingProcessingService processingService;
    private final UploadStorageService uploadStorageService;
    private final TranscriptStoreService transcriptStoreService;
    private final Map<String, CompletableFuture<MeetingProcessingService.ProcessingResult>> jobs = new ConcurrentHashMap<>();

    public WhisperController(WhisperService whisperService,
                             MeetingProcessingService processingService,
                             UploadStorageService uploadStorageService,
                             TranscriptStoreService transcriptStoreService) {
        this.whisperService = whisperService;
        this.processingService = processingService;
        this.uploadStorageService = uploadStorageService;
        this.transcriptStoreService = transcriptStoreService;
    }

    @PostMapping("/transcribe")
    public ResponseEntity<Map<String, String>> transcribe(@RequestParam("file") MultipartFile file) {
        String jobId = UUID.randomUUID().toString();
        log.info("API /transcribe accepted | jobId={} file={} sizeBytes={}",
                jobId, file.getOriginalFilename(), file.getSize());

        try {
            UploadStorageService.StoredUpload stored = uploadStorageService.store(file);
            CompletableFuture<MeetingProcessingService.ProcessingResult> future =
                    processingService.processStoredUpload(jobId, stored);
            jobs.put(jobId, future);

            future.whenComplete((result, error) -> {
                if (error != null) {
                    log.error("Job finished with exception | jobId={} error={}", jobId, error.getMessage(), error);
                } else if (result != null && result.isSuccess()) {
                    log.info("Job completed | jobId={} status=COMPLETED", jobId);
                } else if (result != null && result.isPartial()) {
                    log.warn("Job partial | jobId={} summaryError={}", jobId, result.summaryError);
                } else {
                    log.warn("Job failed | jobId={} error={}",
                            jobId, result != null ? result.error : "unknown");
                }
            });

            return ResponseEntity.ok(Map.of(
                "jobId", jobId,
                "status", "PROCESSING",
                "message", "Transcription and summarization started. Poll /api/whisper/status/{jobId} for results."
            ));
        } catch (Exception e) {
            log.error("API /transcribe rejected | jobId={} error={}", jobId, e.getMessage(), e);
            Map<String, String> body = new LinkedHashMap<>();
            body.put("jobId", jobId);
            body.put("status", "FAILED");
            body.put("error", e.getMessage() != null ? e.getMessage() : "upload failed");
            return ResponseEntity.internalServerError().body(body);
        }
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String jobId) {
        CompletableFuture<MeetingProcessingService.ProcessingResult> future = jobs.get(jobId);

        if (future != null && !future.isDone()) {
            log.debug("Status poll | jobId={} status=PROCESSING", jobId);
            return ResponseEntity.ok(Map.of(
                "jobId", jobId,
                "status", "PROCESSING"
            ));
        }

        if (future != null && future.isDone()) {
            try {
                MeetingProcessingService.ProcessingResult result = future.get();
                jobs.remove(jobId);
                return ResponseEntity.ok(toResponse(jobId, result));
            } catch (Exception e) {
                jobs.remove(jobId);
                log.error("Status poll failed | jobId={} error={}", jobId, e.getMessage(), e);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("jobId", jobId);
                body.put("status", "FAILED");
                body.put("error", e.getMessage());
                return ResponseEntity.ok(body);
            }
        }

        Optional<JobRecord> stored = transcriptStoreService.find(jobId);
        if (stored.isPresent()) {
            log.info("Status poll from disk | jobId={} status={}", jobId, stored.get().getStatus());
            return ResponseEntity.ok(toResponse(stored.get()));
        }

        log.debug("Status poll | jobId={} notFound=true", jobId);
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/transcribe-sync")
    public ResponseEntity<String> transcribeSync(@RequestParam("file") MultipartFile file) {
        log.info("API /transcribe-sync | file={} sizeBytes={}", file.getOriginalFilename(), file.getSize());
        UploadStorageService.StoredUpload stored = null;
        try {
            stored = uploadStorageService.store(file);
            String transcription = whisperService.transcribe(stored.path(), stored.originalFilename());
            log.info("API /transcribe-sync done | file={} chars={}", file.getOriginalFilename(), transcription.length());
            return ResponseEntity.ok(transcription);
        } catch (Exception e) {
            log.error("API /transcribe-sync failed | file={} error={}", file.getOriginalFilename(), e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Transcription failed: " + e.getMessage());
        } finally {
            uploadStorageService.deleteQuietly(stored);
        }
    }

    private Map<String, Object> toResponse(String jobId, MeetingProcessingService.ProcessingResult result) {
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
        return body;
    }

    private Map<String, Object> toResponse(JobRecord record) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", record.getJobId());
        body.put("status", record.getStatus() != null ? record.getStatus().name() : "UNKNOWN");
        if (record.getOriginalFilename() != null) {
            body.put("originalFilename", record.getOriginalFilename());
        }
        if (record.getTranscription() != null) {
            body.put("transcription", record.getTranscription());
        }
        if (record.getSummary() != null) {
            body.put("summary", record.getSummary());
        }
        if (record.getSummaryError() != null) {
            body.put("summaryError", record.getSummaryError());
        }
        if (record.getError() != null) {
            body.put("error", record.getError());
        }
        body.put("summaryRetryCount", record.getSummaryRetryCount());
        if (record.getCreatedAt() != null) {
            body.put("createdAt", record.getCreatedAt());
        }
        if (record.getUpdatedAt() != null) {
            body.put("updatedAt", record.getUpdatedAt());
        }
        return body;
    }
}

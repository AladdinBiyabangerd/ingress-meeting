package az.aladdin.ingressmeeting.service;

import az.aladdin.ingressmeeting.config.AsyncConfig;
import az.aladdin.ingressmeeting.model.JobRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class MeetingProcessingService {

    private final WhisperService whisperService;
    private final OpenAIService openAIService;
    private final UploadStorageService uploadStorageService;
    private final TranscriptStoreService transcriptStoreService;
    private final SummaryPrompt summaryPrompt;
    private final AcademyCallbackService academyCallbackService;

    public MeetingProcessingService(WhisperService whisperService,
                                    OpenAIService openAIService,
                                    UploadStorageService uploadStorageService,
                                    TranscriptStoreService transcriptStoreService,
                                    SummaryPrompt summaryPrompt,
                                    AcademyCallbackService academyCallbackService) {
        this.whisperService = whisperService;
        this.openAIService = openAIService;
        this.uploadStorageService = uploadStorageService;
        this.transcriptStoreService = transcriptStoreService;
        this.summaryPrompt = summaryPrompt;
        this.academyCallbackService = academyCallbackService;
    }

    @Async(AsyncConfig.MEETING_EXECUTOR)
    public CompletableFuture<ProcessingResult> processStoredUpload(String jobId,
                                                                   UploadStorageService.StoredUpload upload) {
        String filename = upload.originalFilename();
        long start = System.currentTimeMillis();
        log.info("Meeting processing started | jobId={} file={} path={} sizeBytes={}",
                jobId, filename, upload.path(), upload.sizeBytes());

        String transcription = null;
        try {
            log.info("Step 1/2: transcription | jobId={} file={}", jobId, filename);
            transcription = whisperService.transcribe(upload.path(), filename);
            log.info("Step 1/2 done | jobId={} transcriptionChars={}", jobId, transcription.length());

            ProcessingResult done = summarizeAndPersist(jobId, filename, transcription, start);
            academyCallbackService.notifyJobResult(jobId, done);
            return CompletableFuture.completedFuture(done);
        } catch (Exception e) {
            log.error("Meeting processing failed before/during transcription | jobId={} elapsedMs={} error={}",
                    jobId, System.currentTimeMillis() - start, e.getMessage(), e);
            JobRecord failed = baseRecord(jobId, filename);
            failed.setError(e.getMessage());
            try {
                transcriptStoreService.saveFailed(failed);
            } catch (Exception storeError) {
                log.error("Failed to persist FAILED job | jobId={} error={}", jobId, storeError.getMessage());
            }
            ProcessingResult failedResult =
                    new ProcessingResult(JobRecord.Status.FAILED, null, null, e.getMessage(), null);
            academyCallbackService.notifyJobResult(jobId, failedResult);
            return CompletableFuture.completedFuture(failedResult);
        } finally {
            uploadStorageService.deleteQuietly(upload);
        }
    }

    public ProcessingResult summarizeAndPersist(String jobId,
                                                String filename,
                                                String transcription,
                                                long startMs) {
        try {
            log.info("Step 2/2: OpenAI summary | jobId={} file={}", jobId, filename);
            String summary = openAIService.sendToOpenAI(transcription, summaryPrompt.get());

            JobRecord completed = baseRecord(jobId, filename);
            completed.setTranscription(transcription);
            completed.setSummary(summary);
            transcriptStoreService.saveFinal(completed);

            log.info("Meeting processing completed | jobId={} elapsedMs={} summaryChars={}",
                    jobId, System.currentTimeMillis() - startMs, summary != null ? summary.length() : 0);
            return new ProcessingResult(JobRecord.Status.COMPLETED, transcription, summary, null, null);
        } catch (Exception e) {
            log.error("Summary failed — saving PARTIAL | jobId={} error={}", jobId, e.getMessage(), e);
            JobRecord partial = baseRecord(jobId, filename);
            partial.setTranscription(transcription);
            partial.setSummaryError(e.getMessage());
            try {
                transcriptStoreService.savePartial(partial);
            } catch (Exception storeError) {
                log.error("Failed to persist PARTIAL job | jobId={} error={}", jobId, storeError.getMessage());
            }
            return new ProcessingResult(JobRecord.Status.PARTIAL, transcription, null, null, e.getMessage());
        }
    }

    public ProcessingResult retrySummary(JobRecord partial) {
        long start = System.currentTimeMillis();
        String jobId = partial.getJobId();
        try {
            log.info("Retrying summary | jobId={} retryCount={}", jobId, partial.getSummaryRetryCount());
            String summary = openAIService.sendToOpenAI(partial.getTranscription(), summaryPrompt.get());

            JobRecord completed = baseRecord(jobId, partial.getOriginalFilename());
            completed.setCreatedAt(partial.getCreatedAt());
            completed.setTranscription(partial.getTranscription());
            completed.setSummary(summary);
            completed.setSummaryRetryCount(partial.getSummaryRetryCount() + 1);
            transcriptStoreService.saveFinal(completed);

            log.info("Summary retry succeeded | jobId={} elapsedMs={}", jobId, System.currentTimeMillis() - start);
            ProcessingResult done = new ProcessingResult(
                    JobRecord.Status.COMPLETED, partial.getTranscription(), summary, null, null);
            academyCallbackService.notifyJobResult(jobId, done);
            return done;
        } catch (Exception e) {
            log.warn("Summary retry failed | jobId={} error={}", jobId, e.getMessage());
            partial.setSummaryError(e.getMessage());
            partial.setSummaryRetryCount(partial.getSummaryRetryCount() + 1);
            partial.setUpdatedAt(Instant.now().toString());
            try {
                transcriptStoreService.savePartial(partial);
            } catch (Exception storeError) {
                log.error("Failed updating PARTIAL after retry | jobId={} error={}", jobId, storeError.getMessage());
            }
            ProcessingResult stillPartial = new ProcessingResult(
                    JobRecord.Status.PARTIAL,
                    partial.getTranscription(),
                    null,
                    null,
                    e.getMessage()
            );
            // Academy already has transcription from the first PARTIAL callback; push again for freshness.
            academyCallbackService.notifyJobResult(jobId, stillPartial);
            return stillPartial;
        }
    }

    private static JobRecord baseRecord(String jobId, String filename) {
        JobRecord record = new JobRecord();
        record.setJobId(jobId);
        record.setOriginalFilename(filename);
        record.setCreatedAt(Instant.now().toString());
        record.setUpdatedAt(record.getCreatedAt());
        return record;
    }

    public static class ProcessingResult {
        public final JobRecord.Status status;
        public final String transcription;
        public final String summary;
        public final String error;
        public final String summaryError;

        public ProcessingResult(JobRecord.Status status,
                                String transcription,
                                String summary,
                                String error,
                                String summaryError) {
            this.status = status;
            this.transcription = transcription;
            this.summary = summary;
            this.error = error;
            this.summaryError = summaryError;
        }

        public boolean isSuccess() {
            return status == JobRecord.Status.COMPLETED;
        }

        public boolean isPartial() {
            return status == JobRecord.Status.PARTIAL;
        }
    }
}

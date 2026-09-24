package az.aladdin.ingressmeeting.service;

import az.aladdin.ingressmeeting.config.AsyncConfig;
import az.aladdin.ingressmeeting.model.JobRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Whisper transcription only. Lesson summaries are produced by Academy
 * after all video-part transcripts are collected.
 */
@Slf4j
@Service
public class MeetingProcessingService {

    private final WhisperService whisperService;
    private final UploadStorageService uploadStorageService;
    private final TranscriptStoreService transcriptStoreService;
    private final AcademyCallbackService academyCallbackService;

    public MeetingProcessingService(WhisperService whisperService,
                                    UploadStorageService uploadStorageService,
                                    TranscriptStoreService transcriptStoreService,
                                    AcademyCallbackService academyCallbackService) {
        this.whisperService = whisperService;
        this.uploadStorageService = uploadStorageService;
        this.transcriptStoreService = transcriptStoreService;
        this.academyCallbackService = academyCallbackService;
    }

    @Async(AsyncConfig.MEETING_EXECUTOR)
    public CompletableFuture<ProcessingResult> processStoredUpload(String jobId,
                                                                   UploadStorageService.StoredUpload upload) {
        String filename = upload.originalFilename();
        long start = System.currentTimeMillis();
        log.info("Meeting processing started | jobId={} file={} path={} sizeBytes={}",
                jobId, filename, upload.path(), upload.sizeBytes());

        try {
            log.info("Transcription started | jobId={} file={}", jobId, filename);
            String transcription = whisperService.transcribe(upload.path(), filename);
            log.info("Transcription done | jobId={} transcriptionChars={}", jobId, transcription.length());

            JobRecord completed = baseRecord(jobId, filename);
            completed.setTranscription(transcription);
            transcriptStoreService.saveFinal(completed);

            ProcessingResult done = new ProcessingResult(
                    JobRecord.Status.COMPLETED, transcription, null, null, null);
            log.info("Meeting processing completed | jobId={} elapsedMs={} transcriptionChars={}",
                    jobId, System.currentTimeMillis() - start, transcription.length());
            academyCallbackService.notifyJobResult(jobId, done);
            return CompletableFuture.completedFuture(done);
        } catch (Exception e) {
            log.error("Meeting processing failed | jobId={} elapsedMs={} error={}",
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

package az.aladdin.ingressmeeting.scheduler;

import az.aladdin.ingressmeeting.model.JobRecord;
import az.aladdin.ingressmeeting.service.MeetingProcessingService;
import az.aladdin.ingressmeeting.service.TranscriptStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class SummaryRetryScheduler {

    private static final long ONE_HOUR_MS = 3_600_000L;

    private final TranscriptStoreService transcriptStoreService;
    private final MeetingProcessingService meetingProcessingService;

    public SummaryRetryScheduler(TranscriptStoreService transcriptStoreService,
                                 MeetingProcessingService meetingProcessingService) {
        this.transcriptStoreService = transcriptStoreService;
        this.meetingProcessingService = meetingProcessingService;
    }

    @Scheduled(fixedRate = ONE_HOUR_MS, initialDelay = ONE_HOUR_MS)
    public void retryFailedSummaries() {
        List<JobRecord> partials = transcriptStoreService.listPartialsNeedingSummary();
        if (partials.isEmpty()) {
            log.info("Summary retry scheduler | no PARTIAL jobs");
            return;
        }

        log.info("Summary retry scheduler started | partialCount={}", partials.size());
        int ok = 0;
        int fail = 0;
        for (JobRecord partial : partials) {
            MeetingProcessingService.ProcessingResult result = meetingProcessingService.retrySummary(partial);
            if (result.isSuccess()) {
                ok++;
            } else {
                fail++;
            }
        }
        log.info("Summary retry scheduler finished | succeeded={} stillPartial={}", ok, fail);
    }
}

package az.aladdin.ingressmeeting.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Disabled: Meeting no longer runs OpenAI summaries. Academy combines part
 * transcripts and summarizes. Kept as an empty bean so older docs/refs stay harmless.
 */
@Slf4j
@Component
public class SummaryRetryScheduler {
    // Intentionally empty — OpenAI summary retries removed from Meeting.
}

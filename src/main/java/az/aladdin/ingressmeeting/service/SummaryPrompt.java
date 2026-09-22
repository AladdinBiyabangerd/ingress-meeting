package az.aladdin.ingressmeeting.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Same instructions as Ingress Academy {@code portal.lesson_summary.summarize_transcript}
 * so Meeting and Academy produce consistent IT-training summaries.
 */
@Slf4j
@Component
public class SummaryPrompt {

    /**
     * Academy Claude system + user intro, combined for OpenAI single-string prompts.
     * Transcript text is appended by {@link OpenAIService} after this prompt.
     */
    public static final String DEFAULT =
            "You summarize IT training class recordings for Ingress Academy (Baku). "
            + "Reply with ONLY a JSON object (no markdown) with keys:\n"
            + "- \"title\": short lesson title in Azerbaijani (max ~80 chars), concrete "
            + "topic name (e.g. \"Java dəyişənlər və tiplər\"), not a date\n"
            + "- \"summary\": 5-10 sentences in Azerbaijani summarizing what was taught\n"
            + "- \"topics\": array of short Azerbaijani topic strings (max 12)\n"
            + "Do not invent content that is not supported by the transcript.\n\n"
            + "Summarize this class transcript and propose a clear lesson title.";

    public String get() {
        return DEFAULT;
    }
}

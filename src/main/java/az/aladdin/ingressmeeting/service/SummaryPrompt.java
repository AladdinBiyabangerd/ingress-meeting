package az.aladdin.ingressmeeting.service;

import az.aladdin.ingressmeeting.model.JobRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SummaryPrompt {

    public static final String DEFAULT =
            "Summarize the following text in plain, simple language. " +
            "Keep all technical terms, proper names, abbreviations, and specialized vocabulary EXACTLY as they appear. " +
            "Do not explain or simplify terminology. Only simplify the surrounding language and structure. " +
            "Provide a concise summary.";

    public String get() {
        return DEFAULT;
    }
}

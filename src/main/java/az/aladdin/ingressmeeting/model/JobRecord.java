package az.aladdin.ingressmeeting.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobRecord {

    public enum Status {
        PROCESSING,
        PARTIAL,
        COMPLETED,
        FAILED
    }

    private String jobId;
    private Status status;
    private String originalFilename;
    private String transcription;
    private String summary;
    private String summaryError;
    private String error;
    private String createdAt;
    private String updatedAt;
    private int summaryRetryCount;

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getTranscription() {
        return transcription;
    }

    public void setTranscription(String transcription) {
        this.transcription = transcription;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSummaryError() {
        return summaryError;
    }

    public void setSummaryError(String summaryError) {
        this.summaryError = summaryError;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getSummaryRetryCount() {
        return summaryRetryCount;
    }

    public void setSummaryRetryCount(int summaryRetryCount) {
        this.summaryRetryCount = summaryRetryCount;
    }
}

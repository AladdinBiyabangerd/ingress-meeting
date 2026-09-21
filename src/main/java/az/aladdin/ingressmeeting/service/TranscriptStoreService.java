package az.aladdin.ingressmeeting.service;

import az.aladdin.ingressmeeting.model.JobRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
@Service
public class TranscriptStoreService {

    private final Path root;
    private final Path partialDir;
    private final Path finalDir;
    private final ObjectMapper objectMapper;

    public TranscriptStoreService(
            @Value("${RESULTS_DIR:${java.io.tmpdir}/ingress-meeting-results}") String resultsDir) {
        this.root = Path.of(resultsDir).toAbsolutePath().normalize();
        this.partialDir = root.resolve("partial");
        this.finalDir = root.resolve("final");
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }


    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(partialDir);
        Files.createDirectories(finalDir);
        log.info("Transcript store ready | root={} partial={} final={}", root, partialDir, finalDir);
    }

    public void savePartial(JobRecord record) throws IOException {
        record.setStatus(JobRecord.Status.PARTIAL);
        record.setUpdatedAt(Instant.now().toString());
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(record.getUpdatedAt());
        }
        writeAtomic(partialPath(record.getJobId()), record);
        log.info("Saved PARTIAL | jobId={} path={}", record.getJobId(), partialPath(record.getJobId()));
    }

    public void saveFinal(JobRecord record) throws IOException {
        record.setStatus(JobRecord.Status.COMPLETED);
        record.setSummaryError(null);
        record.setError(null);
        record.setUpdatedAt(Instant.now().toString());
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(record.getUpdatedAt());
        }
        writeAtomic(finalPath(record.getJobId()), record);
        deletePartial(record.getJobId());
        log.info("Saved FINAL and deleted PARTIAL | jobId={} path={}",
                record.getJobId(), finalPath(record.getJobId()));
    }

    public void saveFailed(JobRecord record) throws IOException {
        record.setStatus(JobRecord.Status.FAILED);
        record.setUpdatedAt(Instant.now().toString());
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(record.getUpdatedAt());
        }
        // Keep failed jobs under partial so ops can inspect; no transcription to promote
        writeAtomic(partialPath(record.getJobId()), record);
        log.warn("Saved FAILED | jobId={} error={}", record.getJobId(), record.getError());
    }

    public void deletePartial(String jobId) {
        try {
            boolean deleted = Files.deleteIfExists(partialPath(jobId));
            if (deleted) {
                log.info("Deleted PARTIAL | jobId={}", jobId);
            }
        } catch (IOException e) {
            log.warn("Failed to delete PARTIAL | jobId={} error={}", jobId, e.getMessage());
        }
    }

    public Optional<JobRecord> find(String jobId) {
        Optional<JobRecord> completed = read(finalPath(jobId));
        if (completed.isPresent()) {
            return completed;
        }
        return read(partialPath(jobId));
    }

    public List<JobRecord> listPartialsNeedingSummary() {
        List<JobRecord> result = new ArrayList<>();
        if (!Files.isDirectory(partialDir)) {
            return result;
        }
        try (Stream<Path> files = Files.list(partialDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(path -> read(path).ifPresent(record -> {
                        if (record.getStatus() == JobRecord.Status.PARTIAL
                                && record.getTranscription() != null
                                && !record.getTranscription().isBlank()) {
                            result.add(record);
                        }
                    }));
        } catch (IOException e) {
            log.error("Failed listing PARTIAL jobs | error={}", e.getMessage(), e);
        }
        return result;
    }

    private Path partialPath(String jobId) {
        return partialDir.resolve(safeId(jobId) + ".json");
    }

    private Path finalPath(String jobId) {
        return finalDir.resolve(safeId(jobId) + ".json");
    }

    private static String safeId(String jobId) {
        return jobId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void writeAtomic(Path target, JobRecord record) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        objectMapper.writeValue(tmp.toFile(), record);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Optional<JobRecord> read(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(path.toFile(), JobRecord.class));
        } catch (IOException e) {
            log.warn("Failed reading job file | path={} error={}", path, e.getMessage());
            return Optional.empty();
        }
    }
}

package az.aladdin.ingressmeeting.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Service
public class UploadStorageService {

    private final Path uploadRoot;

    public UploadStorageService(
            @Value("${UPLOAD_DIR:${java.io.tmpdir}/ingress-meeting-uploads}") String uploadDir) {
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(uploadRoot);
        log.info("Upload storage ready | dir={}", uploadRoot.toAbsolutePath());
    }

    /**
     * Persist multipart to durable disk before the HTTP request ends.
     * Required for @Async processing (Tomcat deletes request temp files after response).
     */
    public StoredUpload store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = extensionOf(originalFilename);
        Path dir = Files.createDirectories(uploadRoot.resolve(UUID.randomUUID().toString()));
        Path target = dir.resolve("upload" + extension);

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }

        long size = Files.size(target);
        log.info("Upload persisted | original={} path={} sizeBytes={}",
                originalFilename, target.toAbsolutePath(), size);
        return new StoredUpload(target, originalFilename, size);
    }

    public void deleteQuietly(StoredUpload upload) {
        if (upload == null || upload.path() == null) {
            return;
        }
        Path parent = upload.path().getParent();
        try {
            if (parent != null && parent.startsWith(uploadRoot) && Files.exists(parent)) {
                try (Stream<Path> walk = Files.walk(parent)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete upload path | path={} error={}", p, e.getMessage());
                        }
                    });
                }
                log.info("Upload cleaned up | path={}", parent);
            } else {
                Files.deleteIfExists(upload.path());
            }
        } catch (IOException e) {
            log.warn("Upload cleanup failed | path={} error={}", upload.path(), e.getMessage());
        }
    }

    private static String extensionOf(String originalFilename) {
        if (originalFilename != null && originalFilename.contains(".")) {
            String ext = originalFilename.substring(originalFilename.lastIndexOf('.'));
            if (ext.length() <= 16 && ext.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '.')) {
                return ext;
            }
        }
        return ".bin";
    }

    public record StoredUpload(Path path, String originalFilename, long sizeBytes) {}
}

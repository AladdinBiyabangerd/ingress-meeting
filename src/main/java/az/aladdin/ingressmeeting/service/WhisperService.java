package az.aladdin.ingressmeeting.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Local transcription via whisper.cpp ({@code whisper-cli}), not Python/PyTorch.
 *
 * Flow: ffmpeg → 16 kHz mono WAV → {@code whisper-cli -m ggml-*.bin -otxt}.
 */
@Slf4j
@Service
public class WhisperService {

    @Value("${whisper.model:large-v3-q5_0}")
    private String modelName;

    @Value("${whisper.model-dir:${user.home}/.cache/whisper}")
    private String modelDir;

    @Value("${whisper.cli-path:whisper-cli}")
    private String cliPath;

    @Value("${whisper.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${whisper.language:az}")
    private String language;

    @Value("${whisper.threads:0}")
    private int threads;

    private volatile boolean modelReady = false;
    private Path resolvedModelFile;

    @PostConstruct
    public void init() {
        new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                resolvedModelFile = resolveModelFile();
                ensureBinary(cliPath, "whisper-cli");
                ensureBinary(ffmpegPath, "ffmpeg");
                if (!Files.isRegularFile(resolvedModelFile)) {
                    throw new IllegalStateException("Whisper ggml model missing: " + resolvedModelFile
                            + " (entrypoint should download it into WHISPER_MODEL_DIR)");
                }
                modelReady = true;
                log.info("Whisper.cpp ready | cli={} model={} sizeBytes={} language={} threads={} elapsedMs={}",
                        cliPath,
                        resolvedModelFile,
                        Files.size(resolvedModelFile),
                        language,
                        effectiveThreads(),
                        System.currentTimeMillis() - start);
            } catch (Exception e) {
                modelReady = false;
                log.error("Whisper.cpp init failed | elapsedMs={} error={}",
                        System.currentTimeMillis() - start, e.getMessage(), e);
            }
        }, "whisper-model-loader").start();
    }

    public String transcribe(MultipartFile audioFile) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("whisper-sync-");
        String originalFilename = audioFile.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : ".bin";
        Path tempAudioFile = tempDir.resolve(UUID.randomUUID() + extension);
        try (var in = audioFile.getInputStream()) {
            Files.copy(in, tempAudioFile);
        }
        try {
            return transcribe(tempAudioFile, originalFilename);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    public String transcribe(Path audioFile, String originalFilename) throws IOException, InterruptedException {
        long size = Files.size(audioFile);
        log.info("Transcription requested | file={} path={} sizeBytes={} modelReady={}",
                originalFilename, audioFile.toAbsolutePath(), size, modelReady);

        waitUntilReady();

        if (!Files.isRegularFile(audioFile)) {
            throw new IOException("Audio file missing before transcription: " + audioFile);
        }

        Path workDir = Files.createTempDirectory("whisper-cpp-");
        long start = System.currentTimeMillis();
        try {
            Path wav = workDir.resolve("audio.wav");
            log.info("Starting whisper.cpp | path={} language={} threads={} model={}",
                    audioFile.toAbsolutePath(), language, effectiveThreads(), resolvedModelFile.getFileName());
            convertToWav(audioFile, wav);
            String text = runWhisperCli(wav, workDir);
            log.info("Transcription completed | file={} elapsedMs={} chars={}",
                    originalFilename, System.currentTimeMillis() - start, text.length());
            log.debug("Transcription text preview | {}", preview(text, 200));
            return text;
        } catch (Exception e) {
            log.error("Transcription failed | file={} elapsedMs={} error={}",
                    originalFilename, System.currentTimeMillis() - start, e.getMessage(), e);
            throw e;
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Async
    public CompletableFuture<String> transcribeAsync(MultipartFile audioFile) {
        try {
            return CompletableFuture.completedFuture(transcribe(audioFile));
        } catch (Exception e) {
            log.error("Async transcription failed | error={}", e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private void waitUntilReady() throws InterruptedException {
        long waitStart = System.currentTimeMillis();
        while (!modelReady) {
            if (System.currentTimeMillis() - waitStart > TimeUnit.MINUTES.toMillis(30)) {
                throw new IllegalStateException("Timed out waiting for whisper.cpp model to become ready");
            }
            Thread.sleep(500);
        }
        if (System.currentTimeMillis() - waitStart > 1000) {
            log.info("Waited for model | waitMs={}", System.currentTimeMillis() - waitStart);
        }
    }

    private Path resolveModelFile() {
        String raw = (modelName == null ? "" : modelName.trim());
        if (raw.isEmpty()) {
            raw = "large-v3-q5_0";
        }
        String fileName;
        if (raw.endsWith(".bin")) {
            fileName = raw;
        } else if (raw.startsWith("ggml-")) {
            fileName = raw + ".bin";
        } else {
            fileName = "ggml-" + raw + ".bin";
        }
        return Path.of(modelDir).resolve(fileName).toAbsolutePath().normalize();
    }

    private int effectiveThreads() {
        if (threads > 0) {
            return threads;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, cores);
    }

    private void convertToWav(Path input, Path wavOut) throws IOException, InterruptedException {
        List<String> cmd = List.of(
                ffmpegPath,
                "-y",
                "-i", input.toAbsolutePath().toString(),
                "-ar", "16000",
                "-ac", "1",
                "-c:a", "pcm_s16le",
                wavOut.toAbsolutePath().toString()
        );
        log.info("ffmpeg convert | {}", String.join(" ", cmd));
        String output = runCommand(cmd, "ffmpeg");
        if (!Files.isRegularFile(wavOut) || Files.size(wavOut) == 0) {
            throw new IOException("ffmpeg did not produce wav: " + wavOut + " output=" + preview(output, 400));
        }
        log.info("ffmpeg done | wavBytes={}", Files.size(wavOut));
    }

    private String runWhisperCli(Path wav, Path workDir) throws IOException, InterruptedException {
        Path outPrefix = workDir.resolve("transcript");
        List<String> cmd = new ArrayList<>();
        cmd.add(cliPath);
        cmd.add("-m");
        cmd.add(resolvedModelFile.toString());
        cmd.add("-f");
        cmd.add(wav.toAbsolutePath().toString());
        cmd.add("-l");
        cmd.add(language == null || language.isBlank() ? "az" : language.trim());
        cmd.add("-t");
        cmd.add(String.valueOf(effectiveThreads()));
        cmd.add("-nt");
        cmd.add("-np");
        cmd.add("-ng"); // CPU-only image
        cmd.add("-of");
        cmd.add(outPrefix.toAbsolutePath().toString());
        cmd.add("-otxt");

        log.info("whisper-cli start | {}", String.join(" ", cmd));
        String combined = runCommand(cmd, "whisper-cli");

        Path txt = Path.of(outPrefix + ".txt");
        if (Files.isRegularFile(txt)) {
            String text = Files.readString(txt, StandardCharsets.UTF_8).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        // Fallback: some builds print plain text when -np is set
        String fallback = combined.lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty())
                .filter(l -> !l.startsWith("whisper_"))
                .filter(l -> !l.startsWith("ggml_"))
                .filter(l -> !l.startsWith("system_info"))
                .filter(l -> !l.startsWith("main:"))
                .filter(l -> !l.contains("processing"))
                .collect(Collectors.joining("\n"))
                .trim();
        if (!fallback.isEmpty()) {
            return fallback;
        }
        throw new IOException("whisper-cli produced empty transcript; log=" + preview(combined, 800));
    }

    private String runCommand(List<String> cmd, String label) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder all = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                all.append(line).append('\n');
                if (line.length() < 500) {
                    log.info("[{}] {}", label, line);
                }
            }
        }
        int exitCode = process.waitFor();
        log.info("{} finished | exitCode={}", label, exitCode);
        if (exitCode != 0) {
            throw new RuntimeException(label + " failed exitCode=" + exitCode + " output=" + preview(all.toString(), 800));
        }
        return all.toString();
    }

    private static void ensureBinary(String pathOrName, String label) throws IOException {
        Path asPath = Path.of(pathOrName);
        if (asPath.isAbsolute()) {
            if (!Files.isRegularFile(asPath)) {
                throw new IOException(label + " binary missing: " + pathOrName);
            }
            log.info("Binary ok | label={} path={}", label, pathOrName);
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "command -v " + pathOrName);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int code = p.waitFor();
            if (code != 0 || out.isEmpty()) {
                throw new IOException(label + " not found on PATH: " + pathOrName);
            }
            log.info("Binary ok | label={} path={}", label, out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while locating " + label, e);
        }
    }

    private void deleteRecursively(Path root) {
        try {
            if (root == null || !Files.exists(root)) {
                return;
            }
            Files.walk(root)
                    .sorted((a, b) -> b.compareTo(a))
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            log.warn("Temp cleanup failed | path={} error={}", root, e.getMessage());
        }
    }

    private static String preview(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}

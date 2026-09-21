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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class WhisperService {

    @Value("${whisper.model:base}")
    private String modelName;

    @Value("${whisper.python-path:python3}")
    private String pythonPath;

    @Value("${whisper.model-dir:${user.home}/.cache/whisper}")
    private String modelDir;

    @Value("${whisper.device:cpu}")
    private String device;

    @Value("${whisper.language:az}")
    private String language;

    private final Map<String, Object> modelCache = new ConcurrentHashMap<>();
    private volatile boolean modelLoaded = false;

    @PostConstruct
    public void init() {
        log.info("Whisper init started | model={} device={} language={} modelDir={} python={}",
                modelName, device, language, modelDir, pythonPath);
        new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                loadModel(modelName);
                modelLoaded = true;
                log.info("Whisper model ready | model={} elapsedMs={}",
                        modelName, System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.error("Whisper model load failed | model={} elapsedMs={} error={}",
                        modelName, System.currentTimeMillis() - start, e.getMessage(), e);
            }
        }, "whisper-model-loader").start();
    }

    private void loadModel(String model) throws IOException, InterruptedException {
        String cacheKey = model + "_" + device;
        if (modelCache.containsKey(cacheKey)) {
            log.info("Whisper model already in cache | key={}", cacheKey);
            return;
        }

        log.info("Loading Whisper model into memory | model={} device={} dir={}", model, device, modelDir);

        String pythonScript = "import os; os.environ['PYTHONHTTPSVERIFY']='0'; " +
                "import ssl; ssl._create_default_https_context = ssl._create_unverified_context; " +
                "import whisper; " +
                "print('PYTHON: loading model...', flush=True); " +
                "model = whisper.load_model('" + model + "', download_root='" + modelDir + "', device='" + device + "'); " +
                "import pickle; " +
                "f = open('/tmp/whisper_model_" + model + "_" + device + ".pkl', 'wb'); " +
                "pickle.dump(model, f); f.close(); " +
                "print('MODEL_LOADED', flush=True)";

        int exitCode = runPython(pythonScript, "model-load");
        if (exitCode != 0) {
            throw new RuntimeException("Model load failed with exit code " + exitCode);
        }
        modelCache.put(cacheKey, new Object());
        log.info("Whisper model pickled | /tmp/whisper_model_{}_{}.pkl", model, device);
    }

    public String transcribe(MultipartFile audioFile) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("whisper-sync-");
        String originalFilename = audioFile.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : ".wav";
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
                originalFilename, audioFile.toAbsolutePath(), size, modelLoaded);

        long waitStart = System.currentTimeMillis();
        while (!modelLoaded) {
            if (System.currentTimeMillis() - waitStart > TimeUnit.MINUTES.toMillis(30)) {
                throw new IllegalStateException("Timed out waiting for Whisper model to load");
            }
            Thread.sleep(500);
        }
        if (System.currentTimeMillis() - waitStart > 1000) {
            log.info("Waited for model | waitMs={}", System.currentTimeMillis() - waitStart);
        }

        if (!Files.isRegularFile(audioFile)) {
            throw new IOException("Audio file missing before transcription: " + audioFile);
        }

        log.info("Starting transcription | path={} language={}", audioFile.toAbsolutePath(), language);

        long start = System.currentTimeMillis();
        try {
            String pythonScript = "import os; os.environ['PYTHONHTTPSVERIFY']='0'; " +
                    "import ssl; ssl._create_default_https_context = ssl._create_unverified_context; " +
                    "import whisper; import pickle; " +
                    "print('PYTHON: starting transcription...', flush=True); " +
                    "f = open('/tmp/whisper_model_" + modelName + "_" + device + ".pkl', 'rb'); " +
                    "model = pickle.load(f); f.close(); " +
                    "result = model.transcribe('" + audioFile.toAbsolutePath().toString().replace("'", "\\'") +
                    "', fp16=False, language='" + language.replace("'", "") + "'); " +
                    "print('TRANSCRIPT_START', flush=True); " +
                    "print(result['text'].strip()); " +
                    "print('TRANSCRIPT_END', flush=True)";

            String output = runPythonCapture(pythonScript, "transcribe");
            String text = extractTranscript(output);
            log.info("Transcription completed | file={} elapsedMs={} chars={}",
                    originalFilename, System.currentTimeMillis() - start, text.length());
            log.debug("Transcription text preview | {}", preview(text, 200));
            return text;
        } catch (Exception e) {
            log.error("Transcription failed | file={} elapsedMs={} error={}",
                    originalFilename, System.currentTimeMillis() - start, e.getMessage(), e);
            throw e;
        }
    }

    @Async
    public CompletableFuture<String> transcribeAsync(MultipartFile audioFile) {
        try {
            String result = transcribe(audioFile);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("Async transcription failed | error={}", e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
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

    private int runPython(String script, String label) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(pythonPath, "-c", script);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PYTHONHTTPSVERIFY", "0");
        env.put("CURL_CA_BUNDLE", "");
        env.put("REQUESTS_CA_BUNDLE", "");
        env.put("SSL_CERT_FILE", "");

        log.debug("Starting python process | label={}", label);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[python:{}] {}", label, line);
            }
        }
        int exitCode = process.waitFor();
        log.info("Python process finished | label={} exitCode={}", label, exitCode);
        return exitCode;
    }

    private String runPythonCapture(String script, String label) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(pythonPath, "-c", script);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PYTHONHTTPSVERIFY", "0");
        env.put("CURL_CA_BUNDLE", "");
        env.put("REQUESTS_CA_BUNDLE", "");
        env.put("SSL_CERT_FILE", "");

        log.debug("Starting python process | label={}", label);
        Process process = pb.start();
        StringBuilder all = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                all.append(line).append('\n');
                if (!line.startsWith("TRANSCRIPT_") && !isLikelyTranscriptBody(all, line)) {
                    log.info("[python:{}] {}", label, line);
                }
            }
        }
        int exitCode = process.waitFor();
        log.info("Python process finished | label={} exitCode={}", label, exitCode);
        if (exitCode != 0) {
            throw new RuntimeException("Transcription failed: " + all);
        }
        return all.toString();
    }

    private boolean isLikelyTranscriptBody(StringBuilder all, String line) {
        return all.indexOf("TRANSCRIPT_START") >= 0 && all.indexOf("TRANSCRIPT_END") < 0
                && !"TRANSCRIPT_START".equals(line);
    }

    private String extractTranscript(String output) {
        int start = output.indexOf("TRANSCRIPT_START");
        int end = output.indexOf("TRANSCRIPT_END");
        if (start >= 0 && end > start) {
            return output.substring(start + "TRANSCRIPT_START".length(), end).trim();
        }
        // Fallback: last non-empty line that is not a status marker
        String[] lines = output.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()
                    && !line.startsWith("PYTHON:")
                    && !line.equals("TRANSCRIPT_START")
                    && !line.equals("TRANSCRIPT_END")
                    && !line.equals("MODEL_LOADED")) {
                return line;
            }
        }
        return output.trim();
    }

    private static String preview(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}

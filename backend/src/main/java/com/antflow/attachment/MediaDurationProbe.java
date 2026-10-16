package com.antflow.attachment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MediaDurationProbe {
    private final MobileMediaProperties properties;

    public Double seconds(Path input) {
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("antflow-duration-", ".txt");
            process = new ProcessBuilder(properties.getFfprobeBin(), "-v", "error",
                "-protocol_whitelist", "file,pipe", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", input.toAbsolutePath().toString())
                .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0
                || Files.size(output) > 128) return null;
            double value = Double.parseDouble(Files.readString(output).trim());
            return Double.isFinite(value) && value >= 0 ? value : null;
        } catch (IOException | NumberFormatException exception) {
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (output != null) {
                try { Files.deleteIfExists(output); } catch (IOException ignored) { }
            }
        }
    }
}

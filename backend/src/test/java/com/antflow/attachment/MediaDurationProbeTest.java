package com.antflow.attachment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MediaDurationProbeTest {
    @TempDir Path directory;

    @Test
    void measuresActualBytesAndRejectsUnreadableMedia() throws Exception {
        byte[] samples = new byte[16000];
        ByteBuffer wav = ByteBuffer.allocate(44 + samples.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + samples.length);
        wav.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
            .putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000)
            .putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(samples.length).put(samples);
        Path audio = directory.resolve("one-second.wav");
        Files.write(audio, wav.array());
        MediaDurationProbe probe = new MediaDurationProbe(new MobileMediaProperties());
        assertThat(probe.seconds(audio)).isCloseTo(1d, within(0.01));
        Files.writeString(audio, "not audio");
        assertThat(probe.seconds(audio)).isNull();
    }

    @Test
    void unavailableBinaryDoesNotInventADuration() {
        MobileMediaProperties properties = new MobileMediaProperties();
        properties.setFfprobeBin(directory.resolve("missing-ffprobe").toString());
        assertThat(new MediaDurationProbe(properties).seconds(directory.resolve("clip"))).isNull();
    }
}

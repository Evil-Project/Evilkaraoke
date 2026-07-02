package org.evilproject.evilkaraoke.client.audio;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import javax.sound.sampled.spi.AudioFileReader;
import javax.sound.sampled.spi.FormatConversionProvider;

import org.junit.jupiter.api.Test;

class JavaSoundCodecProviderTest {
    @Test
    void runtimeIncludesSongAndStreamCodecProviders() {
        Set<String> readers = ServiceLoader.load(AudioFileReader.class).stream()
                .map(provider -> provider.type().getName())
                .collect(Collectors.toSet());
        Set<String> converters = ServiceLoader.load(FormatConversionProvider.class).stream()
                .map(provider -> provider.type().getName())
                .collect(Collectors.toSet());

        assertTrue(readers.contains("io.github.jseproject.OpusAudioFileReader"), "missing OPUS reader");
        assertTrue(readers.contains("io.github.jseproject.VorbisAudioFileReader"), "missing Vorbis reader");
        assertTrue(readers.contains("javazoom.spi.mpeg.sampled.file.MpegAudioFileReader"), "missing MP3 reader");

        assertTrue(converters.contains("io.github.jseproject.OpusFormatConversionProvider"), "missing OPUS converter");
        assertTrue(converters.contains("io.github.jseproject.VorbisFormatConversionProvider"), "missing Vorbis converter");
        assertTrue(converters.contains("javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider"), "missing MP3 converter");
    }
}

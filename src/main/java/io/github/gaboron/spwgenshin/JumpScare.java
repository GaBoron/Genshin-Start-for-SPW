package io.github.gaboron.spwgenshin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;

/** Opens the selected destination silently, then plays one bundled sound on success. */
final class JumpScare implements AutoCloseable {
    @FunctionalInterface interface Opener { void open(WindowsLaunch.Target target) throws IOException; }
    interface Sound {
        void play() throws LineUnavailableException;
        void stop();
    }
    record Outcome(boolean opened, Throwable soundError) { }

    private final Opener opener;
    private final Sound sound;
    private boolean enabled = true;
    private boolean closed;
    private long generation;

    JumpScare() throws IOException, UnsupportedAudioFileException {
        this(new WindowsLaunch()::open, PcmSound.load());
    }

    JumpScare(Opener opener, Sound sound) {
        this.opener = opener;
        this.sound = sound;
    }

    synchronized void configure(boolean enabled) {
        if (this.enabled != enabled) generation++;
        this.enabled = enabled;
        if (!enabled) sound.stop();
    }

    Outcome launch(WindowsLaunch.Target target) throws IOException {
        long requestGeneration;
        synchronized (this) {
            if (closed || !enabled) return new Outcome(false, null);
            requestGeneration = generation;
        }

        // No audio device, toast or confirmation is opened before this returns.
        // A failed launch, including cancelled Windows elevation, never reaches play().
        opener.open(target);
        synchronized (this) {
            if (closed || !enabled || generation != requestGeneration) return new Outcome(true, null);
            try { sound.play(); }
            catch (Exception | LinkageError error) { return new Outcome(true, error); }
        }
        return new Outcome(true, null);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        generation++;
        sound.stop();
    }

    /** Decodes in memory; the default audio device is opened only after launch success. */
    private static final class PcmSound implements Sound {
        private final AudioFormat format;
        private final byte[] pcm;
        private Clip active;

        private PcmSound(AudioFormat format, byte[] pcm) {
            this.format = format;
            this.pcm = pcm;
        }

        static PcmSound load() throws IOException, UnsupportedAudioFileException {
            try (var input = JumpScare.class.getResourceAsStream("/audio/genshin-start.wav")) {
                if (input == null) throw new IOException("内置启动音效缺失");
                try (var audio = AudioSystem.getAudioInputStream(new ByteArrayInputStream(input.readAllBytes()))) {
                    return new PcmSound(audio.getFormat(), audio.readAllBytes());
                }
            }
        }

        @Override public synchronized void play() throws LineUnavailableException {
            stop();
            Clip clip = AudioSystem.getClip();
            try {
                clip.addLineListener(event -> {
                    if (event.getType() == LineEvent.Type.STOP) finished(clip);
                });
                clip.open(format, pcm, 0, pcm.length);
                active = clip;
                clip.start();
            } catch (LineUnavailableException | RuntimeException | LinkageError error) {
                if (active == clip) active = null;
                clip.close();
                throw error;
            }
        }

        private synchronized void finished(Clip clip) {
            if (active == clip) active = null;
            clip.close();
        }

        @Override public synchronized void stop() {
            Clip clip = active;
            active = null;
            if (clip != null) {
                try { clip.stop(); }
                finally { clip.close(); }
            }
        }
    }
}

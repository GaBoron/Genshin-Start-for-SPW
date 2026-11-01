package io.github.gaboron.spwgenshin;

import java.text.Normalizer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Serializes host callbacks and permits one automatic launch per selected track. */
final class PlaybackTrigger {
    private static final Pattern HOYO_MIX = Pattern.compile(
            "(?<![\\p{L}\\p{N}])hoyo[\\s\\p{Pd}_]*mix(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    record Track(String key, String artist, String albumArtist) {
        boolean matches() { return matchesArtist(artist) || matchesArtist(albumArtist); }
    }
    record Ticket(long revision, String key) { }

    private final LongSupplier clock;
    private Track track;
    private Boolean playing;
    private boolean enabled = true;
    private boolean closed;
    private boolean attempted;
    private boolean pending;
    private long revision;
    private long lastPosition = -1;
    private long lastSample;
    private int advances;

    PlaybackTrigger() { this(System::nanoTime); }
    PlaybackTrigger(LongSupplier clock) { this.clock = clock; }

    static boolean matchesArtist(String value) {
        return value != null && HOYO_MIX.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC)).find();
    }

    synchronized void select(Track selected) {
        if (closed) return;
        if (selected == null || track == null || !track.key().equals(selected.key())) {
            revision++;
            attempted = false;
            pending = false;
            playing = null;
            resetPosition();
        }
        track = selected;
    }

    synchronized Ticket playing(boolean value) {
        playing = value;
        if (!value) {
            cancelPending();
            resetPosition();
        }
        return claim();
    }

    synchronized Ticket position(long position) {
        // The API has no current isPlaying getter. On hot enable or a track change
        // without a playing callback, two ordinary clock advances establish playback.
        long now = clock.getAsLong();
        if (playing == null && lastPosition >= 0) {
            long elapsed = (now - lastSample) / 1_000_000;
            long delta = position - lastPosition;
            if (elapsed >= 50) {
                if (elapsed <= 2_000 && delta > 0 && delta <= elapsed * 4 + 100) advances++;
                else advances = 0;
                if (advances >= 2) playing = true;
            } else return null;
        }
        lastPosition = position;
        lastSample = now;
        return claim();
    }

    synchronized void seek() { resetPosition(); }

    synchronized void configure(boolean value) {
        if (enabled != value) {
            revision++;
            pending = false;
        }
        enabled = value;
    }

    synchronized boolean isCurrent(Ticket ticket) {
        return !closed && enabled && pending && Boolean.TRUE.equals(playing) && track != null
                && revision == ticket.revision() && track.key().equals(ticket.key()) && track.matches();
    }

    synchronized boolean beginLaunch(Ticket ticket) {
        if (!isCurrent(ticket)) return false;
        pending = false;
        attempted = true;
        return true;
    }

    synchronized void close() {
        closed = true;
        revision++;
        track = null;
        pending = false;
        resetPosition();
    }

    private Ticket claim() {
        if (closed || !enabled || attempted || pending || !Boolean.TRUE.equals(playing) || track == null || !track.matches()) return null;
        pending = true;
        return new Ticket(revision, track.key());
    }

    private void cancelPending() {
        if (pending) revision++;
        pending = false;
    }

    private void resetPosition() {
        lastPosition = -1;
        advances = 0;
    }
}

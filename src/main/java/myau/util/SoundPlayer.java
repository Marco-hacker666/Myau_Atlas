package myau.util;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.BufferedInputStream;
import java.io.InputStream;

/**
 * Plays short WAV effects bundled in the jar, outside Minecraft's own sound
 * engine. Ported from OpenSkid (GPL-3.0) so Myau Atlas can greet the player with
 * a startup sound; see {@code myau.ui.impl.mainmenu.MyauMainMenu#playStartupOnce()}.
 *
 * <p>The clip is opened and closed on a daemon thread, so a missing or malformed
 * resource can never stall or crash the client. That guarantee is kept, but the
 * failures are no longer silent (2026-10-10): a sound that plays nothing must be
 * diagnosable, so the reason is reported once on {@code System.err} the way the
 * font loader reports its own misses. Every step still never throws.
 */
public final class SoundPlayer {
    /** How long a clip of unknown length is left open before being closed. */
    private static final long UNKNOWN_LENGTH_MS = 10000L;

    private SoundPlayer() {
    }

    /**
     * Whether a classpath resource is present and decodable by {@code javax.sound}.
     *
     * <p>Opening a {@link Clip} needs an audio device, which build servers do not
     * have, so the unit test checks this instead of actually playing the sound.
     *
     * @param resourcePath absolute classpath path, e.g. {@code /assets/myau/sounds/startup.wav}
     * @return true when the resource exists and can be turned into an audio stream
     */
    public static boolean isPlayable(String resourcePath) {
        if (resourcePath == null || resourcePath.isEmpty()) {
            return false;
        }
        InputStream raw = SoundPlayer.class.getResourceAsStream(resourcePath);
        if (raw == null) {
            return false;
        }
        AudioInputStream stream = null;
        try {
            stream = AudioSystem.getAudioInputStream(new BufferedInputStream(raw));
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            close(stream);
            close(raw);
        }
    }

    /**
     * Plays {@code resourcePath} asynchronously at {@code volumePct} (0-100).
     *
     * <p>Failures never propagate, exactly like OpenSkid's player: a sound must
     * never be able to break the client. They are logged instead, so a reported
     * "the sound does not play" is answerable from the game log.
     */
    public static void play(String resourcePath, int volumePct) {
        if (resourcePath == null || resourcePath.isEmpty()) {
            return;
        }
        final String path = resourcePath;
        final int volume = Math.max(0, Math.min(100, volumePct));
        if (volume <= 0) {
            return;
        }
        System.out.println("[Myau] Playing " + path + " at " + volume + "%");
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                InputStream raw = null;
                InputStream buffered = null;
                AudioInputStream stream = null;
                Clip clip = null;
                try {
                    raw = SoundPlayer.class.getResourceAsStream(path);
                    if (raw == null) {
                        fail(path, "not found on the classpath");
                        return;
                    }
                    buffered = new BufferedInputStream(raw);
                    stream = AudioSystem.getAudioInputStream(buffered);
                    clip = AudioSystem.getClip();
                    clip.open(stream);
                    applyVolume(clip, volume);
                    clip.start();
                    sleep(playLengthMs(clip, stream));
                } catch (Exception e) {
                    fail(path, e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    close(clip);
                    close(stream);
                    close(buffered);
                    close(raw);
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * How long to keep the clip open before closing it.
     *
     * <p>{@code Clip#getMicrosecondLength()} returns {@link AudioSystem#NOT_SPECIFIED}
     * for some formats, which used to close the clip a fifth of a second into
     * playback and cut the sound off, so the stream is used as the fallback.
     */
    private static long playLengthMs(Clip clip, AudioInputStream stream) {
        long micros = clip.getMicrosecondLength();
        if (micros <= 0) {
            AudioFormat format = stream.getFormat();
            long frames = stream.getFrameLength();
            if (frames > 0 && format != null && format.getFrameRate() > 0.0F) {
                micros = (long) (frames / format.getFrameRate() * 1000000.0);
            }
        }
        if (micros <= 0) {
            return UNKNOWN_LENGTH_MS;
        }
        return micros / 1000L + 250L;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void fail(String path, String reason) {
        System.err.println("[Myau] Could not play " + path + ": " + reason);
    }

    private static void applyVolume(Clip clip, int volumePct) {
        try {
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                float db = (float) (20.0 * Math.log10(volumePct / 100.0));
                if (db < gain.getMinimum()) {
                    db = gain.getMinimum();
                }
                if (db > gain.getMaximum()) {
                    db = gain.getMaximum();
                }
                gain.setValue(db);
            }
        } catch (Exception ignored) {
        }
    }

    private static void close(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}

package myau.util;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.InputStream;

/**
 * Plays short WAV effects bundled in the jar, outside Minecraft's own sound
 * engine. Ported from OpenSkid (GPL-3.0) so Myau Atlas can greet the player with
 * a startup sound; see {@code myau.ui.impl.mainmenu.MyauMainMenu#playStartupOnce()}.
 *
 * <p>The clip is opened and closed on a daemon thread, so a missing or malformed
 * resource can never stall or crash the client.
 */
public final class SoundPlayer {
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
     * Failures are swallowed, exactly like OpenSkid's player: a sound must never
     * be able to break the client.
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
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream raw = SoundPlayer.class.getResourceAsStream(path);
                    if (raw == null) {
                        return;
                    }
                    BufferedInputStream buffered = new BufferedInputStream(raw);
                    AudioInputStream stream = null;
                    Clip clip = null;
                    try {
                        stream = AudioSystem.getAudioInputStream(buffered);
                        clip = AudioSystem.getClip();
                        clip.open(stream);
                        applyVolume(clip, volume);
                        clip.start();
                        long waitMs = clip.getMicrosecondLength() / 1000L + 200L;
                        if (waitMs > 0) {
                            try {
                                Thread.sleep(waitMs);
                            } catch (InterruptedException ignored) {
                            }
                        }
                    } finally {
                        if (clip != null) {
                            try {
                                clip.close();
                            } catch (Exception ignored) {
                            }
                        }
                        close(stream);
                    }
                } catch (Exception ignored) {
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
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

    private static void close(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}

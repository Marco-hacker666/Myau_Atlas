package myau.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the startup sound the main menu plays: the WAV must ship in the jar and
 * be decodable by {@code javax.sound}.
 *
 * <p>A {@code Clip} cannot be opened without an audio device, so this checks the
 * resource rather than actually playing it. {@code MyauMainMenu} calls
 * {@code SoundPlayer.play} with exactly this path.
 */
public class SoundPlayerTest {
    private static final String STARTUP_SOUND = "/assets/myau/sounds/startup.wav";

    @Test
    public void startupSoundIsBundledAndDecodable() {
        assertTrue("startup.wav must be present and decodable", SoundPlayer.isPlayable(STARTUP_SOUND));
    }

    @Test
    public void missingResourceIsNotPlayable() {
        assertFalse(SoundPlayer.isPlayable("/assets/myau/sounds/does-not-exist.wav"));
    }

    @Test
    public void emptyResourceIsNotPlayable() {
        assertFalse(SoundPlayer.isPlayable(""));
        assertFalse(SoundPlayer.isPlayable(null));
    }
}

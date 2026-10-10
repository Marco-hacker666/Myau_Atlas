package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): plays a chosen sound on every kill.
//
// The pack lives at /assets/myau/sounds and is played from the classpath with
// javax.sound.sampled on a daemon thread, as upstream does, so the module keeps
// its own sounds rather than borrowing vanilla ones.
import java.io.BufferedInputStream;
import java.io.InputStream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

public class KillSounds extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final long TARGET_TIMEOUT_MS = 10000L;
    private static final String[] FILES = {
        "kill-dark-souls-kill-sound-effect.wav",
        "kill-fortnite-kill-sound.wav",
        "kill-imposter-kill-among-us.wav",
        "kill-kill-overwatch.wav",
        "kill-kill-sound-for-tsb.wav",
        "kill-quake-killsound.wav",
        "kill-valorant-kill-sound.wav"
    };

    public final ModeProperty sound = new ModeProperty("sound", 0,
            new String[]{"DarkSouls", "Fortnite", "AmongUs", "Overwatch", "TSB", "Quake", "Valorant"});
    public final PercentProperty volume = new PercentProperty("volume", 50);
    public final BooleanProperty onlyPlayers = new BooleanProperty("only-players", true);

    private EntityLivingBase target;
    private long lastAttackAt;

    public KillSounds() {
        super("KillSounds", false, false, "Plays a custom sound whenever you get a kill.");
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.sound.getModeString()};
    }

    @Override
    public void onEnabled() {
        clearTarget();
    }

    @Override
    public void onDisabled() {
        clearTarget();
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || !(event.getTarget() instanceof EntityLivingBase)) {
            return;
        }
        if (event.getTarget() == mc.thePlayer) {
            return;
        }
        if (this.onlyPlayers.getValue() && !(event.getTarget() instanceof EntityPlayer)) {
            return;
        }
        this.target = (EntityLivingBase) event.getTarget();
        this.lastAttackAt = System.currentTimeMillis();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || this.target == null) {
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null) {
            this.target = null;
            return;
        }
        if (this.target.isDead || this.target.getHealth() <= 0.0F) {
            playKillSound();
            this.target = null;
            return;
        }
        if (System.currentTimeMillis() - this.lastAttackAt > TARGET_TIMEOUT_MS) {
            this.target = null;
        }
    }

    private void playKillSound() {
        int index = this.sound.getValue();
        if (index < 0 || index >= FILES.length) {
            index = 0;
        }
        final String path = "/assets/myau/sounds/" + FILES[index];
        final int vol = Math.max(0, Math.min(100, this.volume.getValue()));
        if (vol <= 0) {
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                AudioInputStream stream = null;
                Clip clip = null;
                BufferedInputStream buffered = null;
                try {
                    InputStream raw = KillSounds.class.getResourceAsStream(path);
                    if (raw == null) {
                        return;
                    }
                    buffered = new BufferedInputStream(raw);
                    stream = AudioSystem.getAudioInputStream(buffered);
                    clip = AudioSystem.getClip();
                    clip.open(stream);
                    try {
                        if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                            float db = (float) (20.0 * Math.log10(vol / 100.0));
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
                    clip.start();
                    long waitMs = clip.getMicrosecondLength() / 1000L + 200L;
                    if (waitMs > 0) {
                        try {
                            Thread.sleep(waitMs);
                        } catch (InterruptedException ignored) {
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    try {
                        if (clip != null) {
                            clip.close();
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        if (stream != null) {
                            stream.close();
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        /* The buffered stream is not owned by the clip, so it
                           is closed here too: one kill must not leak a line. */
                        if (buffered != null) {
                            buffered.close();
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void clearTarget() {
        this.target = null;
        this.lastAttackAt = 0L;
    }
}

package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): lightning, sound and particles at the spot
// where a player you attacked dies.
import java.util.Random;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.AttackEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.util.SoundUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.util.EnumParticleTypes;

public class KillEffect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final long TARGET_TIMEOUT_MS = 10000L;
    private static final int PARTICLE_COUNT = 16;
    private final Random random = new Random();

    public final BooleanProperty bolt = new BooleanProperty("bolt", true);
    public final BooleanProperty sound = new BooleanProperty("sound", true);
    public final BooleanProperty particles = new BooleanProperty("particles", true);
    public final FloatProperty volume = new FloatProperty("volume", 1.0f, 0.1f, 1.0f, () -> this.sound.getValue());

    private EntityLivingBase target;
    private long lastAttackAt;
    private int kills;

    public KillEffect() {
        super("KillEffect", false, false, "Plays lightning and effects when you get a kill.");
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(this.kills)};
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
            playEffect(this.target);
            this.target = null;
            return;
        }
        if (System.currentTimeMillis() - this.lastAttackAt > TARGET_TIMEOUT_MS) {
            this.target = null;
        }
    }

    private void playEffect(EntityLivingBase dead) {
        double x = dead.posX;
        double y = dead.posY;
        double z = dead.posZ;
        double centerY = dead.posY + dead.getEyeHeight() / 2.0;
        if (this.bolt.getValue()) {
            try {
                EntityLightningBolt lightning = new EntityLightningBolt(mc.theWorld, x, y, z);
                mc.theWorld.addWeatherEffect(lightning);
            } catch (Exception ignored) {
            }
        }
        if (this.sound.getValue()) {
            try {
                SoundUtil.playSound("ambient.weather.thunder");
                mc.thePlayer.playSound("ambient.weather.thunder", this.volume.getValue(), 1.0F);
            } catch (Exception ignored) {
            }
        }
        if (this.particles.getValue()) {
            try {
                for (int i = 0; i < PARTICLE_COUNT; i++) {
                    double ox = this.random.nextGaussian() * 0.25;
                    double oy = this.random.nextGaussian() * 0.25;
                    double oz = this.random.nextGaussian() * 0.25;
                    if ((i & 1) == 0) {
                        mc.theWorld.spawnParticle(EnumParticleTypes.CRIT_MAGIC, x + ox, centerY + oy, z + oz, 0.0, 0.0, 0.0);
                    } else {
                        mc.theWorld.spawnParticle(EnumParticleTypes.FIREWORKS_SPARK, x + ox, centerY + oy, z + oz, 0.0, 0.08, 0.0);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        this.kills++;
    }

    private void clearTarget() {
        this.target = null;
        this.lastAttackAt = 0L;
    }
}

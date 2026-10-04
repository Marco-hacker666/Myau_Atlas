package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import myau.util.SoundUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.StringUtils;

import java.awt.Color;
import java.util.Locale;
import java.util.Random;

/** Client-side hit particles and an optional block-break sound on credited kills. */
public class HitParticleEffects extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final Random random = new Random();

    public final ModeProperty mode = new ModeProperty("mode", 0,
            new String[]{"CRIT", "MAGIC", "REDSTONE", "HEART", "SMOKE", "FLAME"});
    public final IntProperty amount = new IntProperty("amount", 5, 1, 20);
    public final ColorProperty customColor = new ColorProperty("custom-color", new Color(255, 0, 0).getRGB(),
            () -> this.mode.getValue() == 2);
    public final BooleanProperty onlyCrits = new BooleanProperty("only-crits", false);
    public final BooleanProperty killSound = new BooleanProperty("kill-sound", true);
    /** Phrase used by servers that announce a credited kill as "You killed ...". */
    public final TextProperty killMessage = new TextProperty("kill-message", "You killed",
            this.killSound::getValue);

    public HitParticleEffects() {
        super("HitParticleEffects", false);
    }

    @EventTarget
    public void onHit(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE
                || !(event.getPacket() instanceof S19PacketEntityStatus)
                || mc.thePlayer == null || mc.theWorld == null) return;

        final S19PacketEntityStatus packet = (S19PacketEntityStatus) event.getPacket();
        if (packet.getOpCode() != 2) return; // Server-confirmed hurt animation; ignores misses.
        // Incoming packets are intercepted on Netty's thread. Resolve the entity and
        // add particles on Minecraft's client thread, where the world is safe to use.
        mc.addScheduledTask(() -> {
            if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
            Entity entity = packet.getEntity(mc.theWorld);
            if (!(entity instanceof EntityLivingBase)) return;
            EntityLivingBase target = (EntityLivingBase) entity;
            if (this.onlyCrits.getValue()
                    && !(mc.thePlayer.fallDistance > 0.0F && !mc.thePlayer.onGround
                    && !mc.thePlayer.isInWater() && !mc.thePlayer.isRiding())) return;
            this.spawnParticles(target);
        });
    }

    private void spawnParticles(EntityLivingBase target) {
        if (mc.theWorld == null) return;

        double x = target.posX;
        double y = target.posY + target.getEyeHeight() / 2.0;
        double z = target.posZ;
        ThemeStyle themed = ThemeStyle.active(EffectColors.class);
        boolean themedDust = themed != null && themed.recolours() && this.mode.getValue() == 2;
        for (int i = 0; i < this.amount.getValue(); i++) {
            double px = x + this.random.nextGaussian() * 0.2;
            double py = y + this.random.nextGaussian() * 0.2;
            double pz = z + this.random.nextGaussian() * 0.2;
            switch (this.mode.getValue()) {
                case 0:
                    myau.util.ParticleFix.spawn(mc.theWorld, EnumParticleTypes.CRIT, px, py, pz, 0.0, 0.0, 0.0);
                    break;
                case 1:
                    myau.util.ParticleFix.spawn(mc.theWorld, EnumParticleTypes.CRIT_MAGIC, px, py, pz, 0.0, 0.0, 0.0);
                    break;
                case 2:
                    Color color = themedDust
                            ? themed.color(new Color(this.customColor.getValue()), target, i)
                            : new Color(this.customColor.getValue());
                    double red = Math.max(color.getRed() / 255.0, 0.001);
                    myau.util.ParticleFix.spawn(mc.theWorld, EnumParticleTypes.REDSTONE, px, py, pz,
                            red, color.getGreen() / 255.0, color.getBlue() / 255.0);
                    break;
                case 3:
                    myau.util.ParticleFix.spawn(mc.theWorld, EnumParticleTypes.HEART, px, py, pz, 0.0, 0.0, 0.0);
                    break;
                case 4:
                    myau.util.ParticleFix.spawn(mc.theWorld, EnumParticleTypes.SMOKE_NORMAL, px, py, pz, 0.0, 0.02, 0.0);
                    break;
                case 5:
                    myau.util.ParticleFix.spawn(mc.theWorld, EnumParticleTypes.FLAME, px, py, pz, 0.0, 0.02, 0.0);
                    break;
                default:
                    break;
            }
        }
    }

    @EventTarget
    public void onKillMessage(PacketEvent event) {
        if (!this.isEnabled() || !this.killSound.getValue()
                || event.getType() != EventType.RECEIVE
                || !(event.getPacket() instanceof S02PacketChat)
                || mc.thePlayer == null) return;

        S02PacketChat packet = (S02PacketChat) event.getPacket();
        if (packet.getType() != 0) return;
        final String message = StringUtils.stripControlCodes(packet.getChatComponent().getUnformattedText());
        final String playerName = mc.thePlayer.getName();
        mc.addScheduledTask(() -> {
            if (this.isEnabled() && this.killSound.getValue()
                    && isCreditedKill(message, playerName, this.killMessage.getValue())) {
                // Minecraft's stone breaking sound is the familiar block-break cue.
                SoundUtil.playSound("dig.stone");
            }
        });
    }

    static boolean isCreditedKill(String message, String playerName, String customPhrase) {
        if (message == null || playerName == null || playerName.trim().isEmpty()) return false;
        String text = message.toLowerCase(Locale.ROOT).trim();
        String name = playerName.toLowerCase(Locale.ROOT).trim();
        String phrase = customPhrase == null ? "" : customPhrase.toLowerCase(Locale.ROOT).trim();
        return (!phrase.isEmpty() && text.contains(phrase))
                || text.endsWith("by " + name)
                || text.endsWith("a manos de " + name);
    }
}

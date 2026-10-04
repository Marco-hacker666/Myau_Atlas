package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.BlinkModules;
import myau.event.EventTarget;
import myau.event.types.Priority;
import myau.events.KeyEvent;
import myau.events.PlayerUpdateEvent;
import myau.module.Module;
import myau.util.PlayerUtil;
import myau.util.RandomUtil;
import myau.property.properties.FloatProperty;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition;
import net.minecraft.util.AxisAlignedBB;

public class AntiVoid extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private boolean isInVoid = false;
    private boolean wasInVoid = false;
    private double[] lastSafePosition = null;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"BLINK"});
    public final FloatProperty distance = new FloatProperty("distance", 5.0F, 0.0F, 16.0F);
    /** Refuse to engage while fighting, where the delayed world costs more than the fall. */
    public final BooleanProperty combatOnly = new BooleanProperty("not-in-combat", true);
    public final FloatProperty combatRange = new FloatProperty("combat-range", 8.0F, 2.0F, 30.0F,
            this.combatOnly::getValue);
    /** Ticks after taking damage that still count as a fight. */
    public final IntProperty combatTicks = new IntProperty("combat-ticks", 60, 10, 400,
            this.combatOnly::getValue);

    private int ticksSinceHurt = 999;

    private void resetBlink() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
        this.lastSafePosition = null;
    }

    /**
     * Whether it is safe to start holding packets right now.
     *
     * Not during a fight. AntiVoid works by freezing what the server knows
     * about this player and rewriting it afterwards, and everything that makes
     * that survivable when falling alone -- nobody watching, no hits landing,
     * a path that is a straight vertical line -- stops being true the moment
     * someone is swinging. The exchange needs the server's idea of where both
     * players are to be current, and this makes it deliberately stale; hits
     * land on a position that is about to be rewritten, knockback applies to a
     * journey that will be replaced, and the correction arrives mid-combo.
     *
     * Falling into the void during a fight is also the case where it is most
     * tempting, which is why it needs saying rather than assuming.
     */
    private boolean canUseAntiVoid() {
        LongJump longJump = (LongJump) Myau.moduleManager.modules.get(LongJump.class);
        if (longJump != null && longJump.isJumping()) {
            return false;
        }
        if (placing()) {
            return false;
        }
        return !this.combatOnly.getValue() || !inCombat();
    }

    /**
     * In a fight, by the two signs that do not depend on any module being on:
     * something hit this player recently, or an opponent is close enough to.
     */
    private boolean inCombat() {
        if (mc.thePlayer.hurtTime > 0 || this.ticksSinceHurt < this.combatTicks.getValue()) {
            return true;
        }
        double range = this.combatRange.getValue();
        for (Object object : mc.theWorld.playerEntities) {
            if (!(object instanceof net.minecraft.entity.player.EntityPlayer)) {
                continue;
            }
            net.minecraft.entity.player.EntityPlayer player =
                    (net.minecraft.entity.player.EntityPlayer) object;
            if (player == mc.thePlayer || player.deathTime > 0
                    || myau.util.TeamUtil.isFriend(player)
                    || myau.util.TeamUtil.isSameTeam(player)
                    || myau.util.TeamUtil.isBot(player)) {
                continue;
            }
            if (mc.thePlayer.getDistanceToEntity(player) <= range) {
                return true;
            }
        }
        return false;
    }

    /* Right click held with blocks in hand: bridging or saving themselves.
       Holding packets then holds the clicks too, and they reach the server
       late, judged from a position it is about to be told to forget --
       2026-09-25 14:31:56, three blocks refused under a 30-packet blink,
       then two lagbacks. The player is doing AntiVoid's job; let them. */
    private boolean placing() {
        return mc.gameSettings.keyBindUseItem.isKeyDown() && myau.util.ItemUtil.isHoldingBlock();
    }

    public AntiVoid() {
        super("AntiVoid", false);
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(PlayerUpdateEvent event) {
        if (this.isEnabled()) {
            this.ticksSinceHurt = mc.thePlayer.hurtTime > 0 ? 0
                    : Math.min(999, this.ticksSinceHurt + 1);
            this.isInVoid = !mc.thePlayer.capabilities.allowFlying && PlayerUtil.isInWater();
            if (this.mode.getValue() == 0) {
                if (!this.isInVoid || placing() && Myau.blinkManager.getBlinkingModule() == BlinkModules.ANTI_VOID) {
                    this.resetBlink();
                }
                if (this.lastSafePosition != null) {
                    float subWidth = mc.thePlayer.width / 2.0F;
                    float height = mc.thePlayer.height;
                    if (PlayerUtil.checkInWater(
                            new AxisAlignedBB(
                                    this.lastSafePosition[0] - (double) subWidth,
                                    this.lastSafePosition[1],
                                    this.lastSafePosition[2] - (double) subWidth,
                                    this.lastSafePosition[0] + (double) subWidth,
                                    this.lastSafePosition[1] + (double) height,
                                    this.lastSafePosition[2] + (double) subWidth
                            )
                    )) {
                        this.resetBlink();
                    }
                }
                if (!this.wasInVoid && this.isInVoid && this.canUseAntiVoid()) {
                    Myau.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK);
                    if (Myau.blinkManager.setBlinkState(true, BlinkModules.ANTI_VOID)) {
                        this.lastSafePosition = new double[]{mc.thePlayer.prevPosX, mc.thePlayer.prevPosY, mc.thePlayer.prevPosZ};
                    }
                }
                if (Myau.blinkManager.getBlinkingModule() == BlinkModules.ANTI_VOID
                        && this.lastSafePosition != null
                        && this.lastSafePosition[1] - (double) this.distance.getValue().floatValue() > mc.thePlayer.posY) {
                    Myau.blinkManager
                            .blinkedPackets
                            .offerFirst(
                                    new C04PacketPlayerPosition(
                                            this.lastSafePosition[0], this.lastSafePosition[1] - RandomUtil.nextDouble(10.0, 20.0), this.lastSafePosition[2], false
                                    )
                            );
                    this.resetBlink();
                }
            }
            this.wasInVoid = this.isInVoid;
        }
    }

    @EventTarget
    public void onKey(KeyEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (event.getKey() == mc.gameSettings.keyBindUseItem.getKeyCode()) {
            ItemStack currentItem = mc.thePlayer.inventory.getCurrentItem();
            if (currentItem != null && (currentItem.getItem() instanceof ItemEnderPearl
                    || currentItem.getItem() instanceof net.minecraft.item.ItemBlock)) {
                this.resetBlink();
            }
        }
    }

    @Override
    public void onEnabled() {
        this.isInVoid = false;
        this.wasInVoid = false;
        this.resetBlink();
    }

    @Override
    public void onDisabled() {
        Myau.blinkManager.setBlinkState(false, BlinkModules.ANTI_VOID);
    }

    @Override
    public void verifyValue(String mode) {
        if (this.isEnabled()) {
            this.onDisabled();
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}

package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): throws the fishing rod at nearby enemies,
// with silent aim, movement prediction and a packet-only slot mode.
import com.google.common.base.CaseFormat;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import myau.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.AxisAlignedBB;

public class AutoRod extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"LEGIT", "PACKET", "NEWPACKET"});
    public final FloatProperty range = new FloatProperty("range", 4.5F, 2.0F, 8.0F);
    public final IntProperty throwDelay = new IntProperty("throw-delay", 400, 50, 1000);
    public final IntProperty pullDelay = new IntProperty("pull-delay", 500, 50, 1000, () -> mode.getValue() == 0);
    public final BooleanProperty switchBack = new BooleanProperty("switch-back", true);
    public final BooleanProperty silentAim = new BooleanProperty("silent-aim", true);
    public final IntProperty fov = new IntProperty("fov", 180, 30, 360, () -> silentAim.getValue());
    public final BooleanProperty prediction = new BooleanProperty("prediction", true, () -> silentAim.getValue());
    public final IntProperty predictedTicks = new IntProperty("predicted-ticks", 5, 0, 20, () -> silentAim.getValue() && prediction.getValue());
    public final BooleanProperty ignoreTeammates = new BooleanProperty("ignore-teammates", true);
    public final BooleanProperty aimInvis = new BooleanProperty("aim-invis", false);
    public final BooleanProperty rodKeyOnly = new BooleanProperty("rod-key-only", false);
    private final TimerUtil throwTimer = new TimerUtil();
    private final TimerUtil pullTimer = new TimerUtil();
    private boolean rodOut = false;
    private int savedSlot = -1;

    public AutoRod() {
        super("AutoRod", false, false, "Automatically throws your fishing rod at nearby enemies.");
    }

    @Override
    public void onEnabled() {
        this.throwTimer.reset();
        this.pullTimer.reset();
        this.rodOut = false;
        this.savedSlot = -1;
    }

    @Override
    public void onDisabled() {
        /* NewPacket slot spoof restore (MiauMinus AutoRod2 NewPacket branch). */
        if (this.mode.getValue() == 2 && this.savedSlot != -1 && mc.thePlayer != null) {
            try {
                mc.getNetHandler().addToSendQueue(new C09PacketHeldItemChange(this.savedSlot));
            } catch (Exception ignored) {
            }
        }
        this.restoreSlot();
        this.rodOut = false;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (!this.silentAim.getValue()) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        EntityPlayer target = this.findAimTarget();
        if (target == null) {
            return;
        }
        AxisAlignedBB box = target.getEntityBoundingBox();
        if (this.prediction.getValue() && this.predictedTicks.getValue() > 0) {
            int ticks = this.predictedTicks.getValue();
            box = box.offset(target.motionX * ticks, target.motionY * ticks * 0.5, target.motionZ * ticks);
        }
        float[] rotations = RotationUtil.getRotationsToBox(box, event.getYaw(), event.getPitch(), 180.0F, 0.0F);
        event.setRotation(rotations[0], rotations[1], 1);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.playerController == null) {
            return;
        }
        if (mc.currentScreen != null) {
            return;
        }
        if (this.rodKeyOnly.getValue() && !mc.gameSettings.keyBindUseItem.isKeyDown()) {
            return;
        }
        if (this.rodOut) {
            if (this.pullTimer.hasTimeElapsed(this.pullDelay.getValue().longValue())) {
                if (this.mode.getValue() == 2) {
                    /* NewPacket pull: slot restore only, no second rod use. */
                    if (this.savedSlot != -1) {
                        try {
                            mc.getNetHandler().addToSendQueue(new C09PacketHeldItemChange(this.savedSlot));
                        } catch (Exception ignored) {
                        }
                    }
                    this.savedSlot = -1;
                    this.rodOut = false;
                    this.throwTimer.reset();
                } else {
                    this.useRod();
                    this.rodOut = false;
                    this.restoreSlot();
                    this.throwTimer.reset();
                }
            }
            return;
        }
        if (!this.throwTimer.hasTimeElapsed(this.throwDelay.getValue().longValue())) {
            return;
        }
        if (!this.hasTarget()) {
            return;
        }
        int slot = this.findRodSlot();
        if (slot == -1) {
            return;
        }
        if (this.mode.getValue() == 0) {
            this.savedSlot = mc.thePlayer.inventory.currentItem;
            mc.thePlayer.inventory.currentItem = slot;
            mc.playerController.updateController();
        } else if (this.mode.getValue() == 2) {
            /* NewPacket throw: spoof the held slot via packets, client slot untouched. */
            this.savedSlot = mc.thePlayer.inventory.currentItem;
            try {
                mc.getNetHandler().addToSendQueue(new C09PacketHeldItemChange(slot));
            } catch (Exception ignored) {
            }
        }
        this.useRod();
        this.rodOut = true;
        this.pullTimer.reset();
    }

    private void useRod() {
        ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
        if (this.mode.getValue() != 0 && (stack == null || stack.getItem() != Items.fishing_rod)) {
            int slot = this.findRodSlot();
            if (slot != -1) {
                stack = mc.thePlayer.inventory.getStackInSlot(slot);
            }
        }
        if (stack == null) {
            return;
        }
        mc.getNetHandler().addToSendQueue(new C08PacketPlayerBlockPlacement(stack));
        if (this.mode.getValue() == 0) {
            mc.thePlayer.swingItem();
        } else {
            try {
                mc.getNetHandler().addToSendQueue(new C0APacketAnimation());
            } catch (Exception ignored) {
            }
        }
    }

    private boolean hasTarget() {
        return this.findAimTarget() != null;
    }

    private EntityPlayer findAimTarget() {
        float maxRange = this.range.getValue();
        EntityPlayer best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Object o : mc.theWorld.playerEntities) {
            if (!(o instanceof EntityPlayer) || o == mc.thePlayer) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) o;
            if (player.isDead || player.getHealth() <= 0.0F) {
                continue;
            }
            if (player.isInvisible() && !this.aimInvis.getValue()) {
                continue;
            }
            if (this.ignoreTeammates.getValue() && TeamUtil.isSameTeam(player)) {
                continue;
            }
            if (TeamUtil.isFriend(player) || TeamUtil.isBot(player)) {
                continue;
            }
            if (mc.thePlayer.getDistanceToEntity((Entity) o) > maxRange) {
                continue;
            }
            if (this.silentAim.getValue() && RotationUtil.angleToEntity(player) > this.fov.getValue()) {
                continue;
            }
            double distSq = mc.thePlayer.getDistanceSqToEntity((Entity) o);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = player;
            }
        }
        return best;
    }

    private int findRodSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() == Items.fishing_rod) {
                return i;
            }
        }
        return -1;
    }

    private void restoreSlot() {
        if (this.switchBack.getValue() && this.savedSlot != -1 && mc.thePlayer != null
                && mc.thePlayer.inventory.currentItem != this.savedSlot) {
            mc.thePlayer.inventory.currentItem = this.savedSlot;
            if (mc.playerController != null) {
                mc.playerController.updateController();
            }
        }
        this.savedSlot = -1;
    }
}

package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): passive opponent spotter. Watches nearby
// players each tick and posts one chat line per check once its violation
// level crosses the threshold.
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ChatUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

public class CheatDetector extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double TRACK_RANGE = 40.0;
    private static final long ALERT_GAP_MS = 5000L;

    public final ModeProperty alertMode = new ModeProperty("alert-mode", 0, new String[]{"Chat", "Chat+WDR"});
    public final IntProperty violationThreshold = new IntProperty("violation-threshold", 8, 2, 20);
    public final IntProperty maxTracked = new IntProperty("max-tracked-players", 10, 2, 30);
    public final IntProperty reportCooldown = new IntProperty("report-cooldown", 180, 10, 600, () -> this.alertMode.getValue() == 1);
    public final BooleanProperty checkAutoBlock = new BooleanProperty("check-autoblock", true);
    public final BooleanProperty checkSneakScaffold = new BooleanProperty("check-sneak-scaffold", true);
    public final BooleanProperty checkNoSlow = new BooleanProperty("check-noslow", true);
    public final BooleanProperty checkScaffold = new BooleanProperty("check-scaffold", true);
    public final BooleanProperty checkVoidBridge = new BooleanProperty("check-void-bridge", true);
    public final BooleanProperty checkNoFall = new BooleanProperty("check-nofall", true);
    public final BooleanProperty checkReach = new BooleanProperty("check-reach", true);
    public final BooleanProperty checkAimSnap = new BooleanProperty("check-aimsnap", true);
    public final FloatProperty reachLimit = new FloatProperty("reach-limit", 4.3F, 3.0F, 6.0F, this.checkReach::getValue);

    private final Map<UUID, Tracked> tracked = new LinkedHashMap<UUID, Tracked>();
    private final Set<String> flagged = new HashSet<String>();
    private final Map<UUID, Long> lastAlertAt = new HashMap<UUID, Long>();
    private final Map<UUID, Long> lastReportAt = new HashMap<UUID, Long>();

    private static final class Tracked {
        final Map<String, Integer> vl = new HashMap<String, Integer>();
        final Set<String> alerted = new HashSet<String>();
        int autoBlockTicks;
        int sneakTicks;
        int noSlowTicks;
        int scaffoldTicks;
        int voidTicks;
        int slowFallTicks;
        double falling;
        double lastX;
        double lastY;
        double lastZ;
        float lastYaw;
        float lastPitch;
        boolean hasPrev;
        int lastSnapTick = -100;
    }

    public CheatDetector() {
        super("CheatDetector", false, false, "Watches nearby players and reports suspected cheats in chat.");
    }

    @Override
    public void onEnabled() {
        this.reset();
    }

    @Override
    public void onDisabled() {
        this.reset();
    }

    private void reset() {
        this.tracked.clear();
        this.flagged.clear();
        this.lastAlertAt.clear();
        this.lastReportAt.clear();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        List<EntityPlayer> nearby = new ArrayList<EntityPlayer>();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (!this.skip(player)) {
                nearby.add(player);
            }
        }
        final EntityPlayer self = mc.thePlayer;
        nearby.sort((a, b) -> Double.compare(self.getDistanceToEntity(a), self.getDistanceToEntity(b)));

        int cap = Math.min(this.maxTracked.getValue(), nearby.size());
        Set<UUID> seen = new HashSet<UUID>();
        for (int i = 0; i < cap; i++) {
            EntityPlayer player = nearby.get(i);
            seen.add(player.getUniqueID());
            Tracked state = this.tracked.get(player.getUniqueID());
            if (state == null) {
                state = new Tracked();
                this.tracked.put(player.getUniqueID(), state);
            }
            this.checkPlayer(player, state);
        }
        this.tracked.keySet().retainAll(seen);
        this.lastAlertAt.keySet().retainAll(seen);

        if (mc.thePlayer.ticksExisted % 20 == 0) {
            this.decay();
        }
    }

    private boolean skip(EntityPlayer player) {
        if (player == null || player == mc.thePlayer || player.isDead || player.getHealth() <= 0.0F) {
            return true;
        }
        if (player.ridingEntity != null) {
            return true;
        }
        if (mc.thePlayer.getDistanceToEntity(player) > TRACK_RANGE) {
            return true;
        }
        try {
            if (TeamUtil.isSameTeam(player)) {
                return true;
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private void checkPlayer(EntityPlayer player, Tracked state) {
        boolean holdingBlocks = player.getHeldItem() != null && player.getHeldItem().getItem() instanceof ItemBlock;
        double speed = 0.0;
        if (state.hasPrev) {
            double dx = player.posX - state.lastX;
            double dz = player.posZ - state.lastZ;
            speed = Math.sqrt(dx * dx + dz * dz);
        }

        if (this.checkAutoBlock.getValue()) {
            if (player.isBlocking() && player.isUsingItem()) {
                state.autoBlockTicks++;
                if (state.autoBlockTicks > 12) {
                    this.addVl(player, state, "autoBlock", "AutoBlock", "blocking+using " + state.autoBlockTicks + "t");
                    state.autoBlockTicks = 0;
                }
            } else {
                state.autoBlockTicks = 0;
            }
        }

        if (this.checkSneakScaffold.getValue()) {
            if (player.isSneaking() && holdingBlocks && player.rotationPitch >= 60.0F && player.onGround) {
                state.sneakTicks++;
                if (state.sneakTicks > 15) {
                    this.addVl(player, state, "sneakScaffold", "SneakScaffold", "sneak-bridging " + state.sneakTicks + "t");
                    state.sneakTicks = 0;
                }
            } else {
                state.sneakTicks = 0;
            }
        }

        if (this.checkNoSlow.getValue()) {
            if (state.hasPrev && player.isUsingItem() && player.isSprinting() && speed > 0.2) {
                state.noSlowTicks++;
                if (state.noSlowTicks > 20) {
                    this.addVl(player, state, "noSlow", "NoSlow", "using+sprint " + String.format("%.2f", speed) + " b/t");
                    state.noSlowTicks = 0;
                }
            } else {
                state.noSlowTicks = 0;
            }
        }

        if (this.checkScaffold.getValue()) {
            if (state.hasPrev && holdingBlocks && player.rotationPitch >= 70.0F && speed > 0.12) {
                state.scaffoldTicks++;
                if (state.scaffoldTicks > 25) {
                    this.addVl(player, state, "scaffold", "Scaffold", "flat pitch bridging");
                    state.scaffoldTicks = 0;
                }
            } else {
                state.scaffoldTicks = 0;
            }
        }

        if (this.checkVoidBridge.getValue()) {
            if (state.hasPrev && holdingBlocks && speed > 0.1 && this.airBelow(player)) {
                state.voidTicks++;
                if (state.voidTicks > 40) {
                    this.addVl(player, state, "voidBridge", "VoidBridge", "bridging over air");
                    state.voidTicks = 0;
                }
            } else {
                state.voidTicks = 0;
            }
        }

        if (this.checkNoFall.getValue()) {
            if (!player.onGround && state.hasPrev && !player.isInWater() && !player.isInLava()) {
                double drop = state.lastY - player.posY;
                if (drop > 0.0) {
                    state.falling += drop;
                }
                if (state.falling > 3.0 && player.motionY < 0.0 && player.motionY > -0.6
                        && !player.isSneaking() && !player.isOnLadder()
                        && !((myau.mixin.IAccessorEntity) player).getIsInWeb()
                        && !player.capabilities.isFlying) {
                    state.slowFallTicks++;
                    if (state.slowFallTicks > 20) {
                        this.addVl(player, state, "noFall", "NoFall", "slow fall " + String.format("%.1f", state.falling) + "b");
                        state.slowFallTicks = 0;
                    }
                }
            } else {
                state.slowFallTicks = 0;
            }
            if (player.onGround || player.isInWater() || player.isInLava() || player.hurtTime > 0) {
                state.falling = 0.0;
            }
        }

        if (this.checkReach.getValue()) {
            this.checkReachHits(player, state);
        }

        if (this.checkAimSnap.getValue() && state.hasPrev) {
            float yawSnap = Math.abs(MathHelper.wrapAngleTo180_float(player.rotationYaw - state.lastYaw));
            float pitchSnap = Math.abs(MathHelper.wrapAngleTo180_float(player.rotationPitch - state.lastPitch));
            int tick = mc.thePlayer.ticksExisted;
            if ((yawSnap > 45.0F || pitchSnap > 30.0F) && tick - state.lastSnapTick > 10
                    && (player.isSwingInProgress || this.nearTarget(player, 5.0))) {
                state.lastSnapTick = tick;
                this.addVl(player, state, "aimSnap", "AimSnap", ((int) yawSnap) + " deg snap");
            }
        }

        state.lastX = player.posX;
        state.lastY = player.posY;
        state.lastZ = player.posZ;
        state.lastYaw = player.rotationYaw;
        state.lastPitch = player.rotationPitch;
        state.hasPrev = true;
    }

    private void checkReachHits(EntityPlayer attacker, Tracked state) {
        float limit = this.reachLimit.getValue();
        for (EntityPlayer victim : mc.theWorld.playerEntities) {
            if (victim == attacker || victim.isDead || victim.hurtTime != 10) {
                continue;
            }
            double dist = attacker.getDistanceToEntity(victim);
            if (dist <= limit || dist >= 7.0 || !attacker.isSwingInProgress) {
                continue;
            }
            if (this.nearestSwinger(victim, attacker) < dist) {
                continue;
            }
            this.addVl(attacker, state, "reach", "Reach", String.format("%.1f", dist) + "b hit");
        }
    }

    private double nearestSwinger(EntityPlayer victim, EntityPlayer exclude) {
        double best = Double.MAX_VALUE;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == exclude || player == victim || player.isDead || !player.isSwingInProgress) {
                continue;
            }
            double dist = player.getDistanceToEntity(victim);
            if (dist < best) {
                best = dist;
            }
        }
        return best;
    }

    private boolean nearTarget(EntityPlayer player, double range) {
        for (EntityPlayer other : mc.theWorld.playerEntities) {
            if (other == player || other.isDead) {
                continue;
            }
            if (player.getDistanceToEntity(other) <= range) {
                return true;
            }
        }
        return false;
    }

    private boolean airBelow(EntityPlayer player) {
        try {
            return mc.theWorld.isAirBlock(new BlockPos(player.posX, player.posY - 1.0, player.posZ))
                    && mc.theWorld.isAirBlock(new BlockPos(player.posX, player.posY - 2.0, player.posZ));
        } catch (Exception e) {
            return false;
        }
    }

    private void addVl(EntityPlayer player, Tracked state, String key, String label, String detail) {
        Integer current = state.vl.get(key);
        int vl = (current == null ? 0 : current) + 1;
        state.vl.put(key, vl);
        if (vl >= this.effectiveThreshold() && !state.alerted.contains(key)) {
            state.alerted.add(key);
            this.alert(player, label, vl, detail);
        }
    }

    private void decay() {
        int rearm = Math.max(1, this.effectiveThreshold() / 2);
        for (Tracked state : this.tracked.values()) {
            for (Map.Entry<String, Integer> entry : state.vl.entrySet()) {
                int vl = entry.getValue();
                if (vl > 0) {
                    entry.setValue(vl - 1);
                    if (vl - 1 < rearm) {
                        state.alerted.remove(entry.getKey());
                    }
                }
            }
        }
    }

    private void alert(EntityPlayer player, String check, int vl, String detail) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueID();
        Long last = this.lastAlertAt.get(id);
        if (last != null && now - last < ALERT_GAP_MS) {
            return;
        }
        this.lastAlertAt.put(id, now);
        this.flagged.add(player.getName());
        ChatUtil.sendFormatted("&7[&cCheatDetector&7] &f" + player.getName() + " &7failed &c" + check + " &7(VL " + vl + ") &8[" + detail + "]");
        if (this.alertMode.getValue() == 1) {
            Long lastReport = this.lastReportAt.get(id);
            if (lastReport == null || now - lastReport >= this.effectiveCooldownMs()) {
                this.lastReportAt.put(id, now);
                ChatUtil.sendMessage("/wdr " + player.getName() + " " + this.wdrReason(check));
            }
        }
    }

    private int effectiveThreshold() {
        return Math.max(this.violationThreshold.getValue(), 8);
    }

    private long effectiveCooldownMs() {
        return Math.max(this.reportCooldown.getValue(), 180) * 1000L;
    }

    private String wdrReason(String check) {
        if ("AimSnap".equals(check)) {
            return "KillAura";
        } else if ("Reach".equals(check)) {
            return "Reach";
        } else if ("NoSlow".equals(check)) {
            return "Noslow";
        } else if ("AutoBlock".equals(check)) {
            return "Autoblock";
        } else if ("NoFall".equals(check)) {
            return "Nofall";
        } else {
            return "Scaffold";
        }
    }

    @Override
    public String[] getSuffix() {
        if (this.flagged.isEmpty()) {
            return new String[]{this.alertMode.getModeString()};
        }
        return new String[]{this.flagged.size() + " flagged"};
    }
}

package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): a passive distance watch around your own bed
// (or spawn), with per-player re-alert cooldowns. No auto actions.
import java.util.LinkedHashMap;
import java.util.Map;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ChatUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

public class BedProximityAlert extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final long RESCAN_DELAY_MS = 5000L;

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"BED", "SPAWN"});
    public final IntProperty radius = new IntProperty("radius", 24, 8, 64);
    public final IntProperty cooldown = new IntProperty("cooldown", 10, 1, 60);
    public final BooleanProperty ignoreTeammates = new BooleanProperty("ignore-teammates", true);
    public final BooleanProperty alertChat = new BooleanProperty("alert-chat", true);
    public final IntProperty bedScanRange = new IntProperty("bed-scan-range", 25, 8, 48, () -> mode.getValue() == 0);

    private final Map<String, Long> alertCooldowns = new LinkedHashMap<String, Long>();
    private BlockPos anchor;
    private long nextRescanAt = -1L;

    public BedProximityAlert() {
        super("BedProximityAlert", false, false, "Warns in chat when enemies approach your bed.");
    }

    @Override
    public void onEnabled() {
        this.alertCooldowns.clear();
        this.anchor = null;
        this.nextRescanAt = -1L;
    }

    @Override
    public void onDisabled() {
        this.alertCooldowns.clear();
        this.anchor = null;
        this.nextRescanAt = -1L;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(this.radius.getValue())};
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST
                || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (this.anchor == null || (this.mode.getValue() == 0 && !this.isBed(this.anchor))) {
            long now = System.currentTimeMillis();
            if (this.nextRescanAt == -1L) {
                this.nextRescanAt = now + RESCAN_DELAY_MS;
                this.findAnchor();
            } else if (now >= this.nextRescanAt) {
                this.nextRescanAt = now + RESCAN_DELAY_MS;
                this.findAnchor();
            }
            if (this.anchor == null) {
                return;
            }
        }
        long millis = System.currentTimeMillis();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null || player == mc.thePlayer || TeamUtil.isBot(player)) {
                continue;
            }
            if (this.ignoreTeammates.getValue() && TeamUtil.isSameTeam(player)) {
                continue;
            }
            double distance = player.getDistance(
                    (double) this.anchor.getX() + 0.5,
                    (double) this.anchor.getY() + 0.5,
                    (double) this.anchor.getZ() + 0.5);
            if (distance > (double) this.radius.getValue()) {
                continue;
            }
            Long last = this.alertCooldowns.get(player.getName());
            if (last != null && last + (long) this.cooldown.getValue() * 1000L > millis) {
                continue;
            }
            this.alertCooldowns.put(player.getName(), millis);
            if (this.alertChat.getValue()) {
                ChatUtil.sendFormatted(String.format(
                        "%s%s: %s&r &fis %d blocks from your bed &e&l!&r",
                        Myau.clientName,
                        this.getName(),
                        player.getDisplayName().getFormattedText(),
                        (int) distance + 1));
            }
        }
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        this.alertCooldowns.clear();
        this.anchor = null;
        this.nextRescanAt = -1L;
    }

    private void findAnchor() {
        if (this.mode.getValue() == 1) {
            if (mc.theWorld.getSpawnPoint() != null) {
                this.anchor = mc.theWorld.getSpawnPoint();
            }
            return;
        }
        int x = MathHelper.floor_double(mc.thePlayer.posX);
        int y = MathHelper.floor_double(mc.thePlayer.posY);
        int z = MathHelper.floor_double(mc.thePlayer.posZ);
        int range = this.bedScanRange.getValue();
        int yWindow = 8;
        for (int r = 0; r <= range; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    for (int dy = -yWindow; dy <= yWindow; dy++) {
                        BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
                        if (this.isBed(pos)) {
                            this.anchor = pos;
                            return;
                        }
                    }
                }
            }
        }
    }

    private boolean isBed(BlockPos pos) {
        return pos != null && mc.theWorld != null && mc.theWorld.getBlockState(pos).getBlock() == Blocks.bed;
    }
}

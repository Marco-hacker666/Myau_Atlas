/*
 * Ported from OpenSkid (https://github.com/loloshelly102-a11y/OpenSkid, GPL-3.0),
 * src/main/java/openskid/module/modules/BedPlates.java, 2026-10-05: package openskid -> myau,
 * OpenSkid -> Myau. Changes after that are marked in comments.
 */
package myau.module.modules;

// Adapted from Expo BedPlates (connected bed-defense scan plus per-bed overlay).
// Rebuilt on openskid helpers (RenderUtil boxes, TickEvent throttle). No pasted code.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.PercentProperty;
import myau.util.RenderUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class BedPlates extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final long SCAN_INTERVAL_MS = 1000L;
    private static final int SCAN_RADIUS = 20;
    private static final int SCAN_Y_RADIUS = 8;
    private static final int MAX_BEDS = 8;
    private static final int MAX_DEFENSE_PER_BED = 40;
    private static final int MAX_BOXES_PER_FRAME = 192;

    public final IntProperty surroundingRange = new IntProperty("surrounding-range", 3, 1, 6);
    public final IntProperty maxDistance = new IntProperty("max-distance", 32, 8, 64);
    public final BooleanProperty outline = new BooleanProperty("outline", true);
    public final BooleanProperty fill = new BooleanProperty("fill", true);
    public final PercentProperty opacity = new PercentProperty("opacity", 40);

    private final Map<BlockPos, Set<BlockPos>> defenses = new LinkedHashMap<>();
    private long nextScanAt = 0L;

    public BedPlates() {
        super("BedPlates", false, false, "Shows colored overlays on beds and their defenses.");
    }

    @Override
    public void onEnabled() {
        this.defenses.clear();
        this.nextScanAt = 0L;
    }

    @Override
    public void onDisabled() {
        this.defenses.clear();
        this.nextScanAt = 0L;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(this.defenses.size())};
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < this.nextScanAt) {
            return;
        }
        this.nextScanAt = now + SCAN_INTERVAL_MS;
        this.scanBeds();
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.defenses.clear();
        this.nextScanAt = 0L;
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (this.defenses.isEmpty()) {
            return;
        }
        int outlineAlpha = Math.max(0, Math.min(255, this.opacity.getValue() * 255 / 100));
        RenderUtil.enableRenderState();
        try {
            int boxes = 0;
            for (Map.Entry<BlockPos, Set<BlockPos>> entry : this.defenses.entrySet()) {
                if (boxes >= MAX_BOXES_PER_FRAME) {
                    break;
                }
                BlockPos bed = entry.getKey();
                if (!this.isBedHead(bed)) {
                    continue;
                }
                if (mc.thePlayer.getDistance(bed.getX() + 0.5, bed.getY() + 0.5, bed.getZ() + 0.5) > (double) this.maxDistance.getValue()) {
                    continue;
                }
                boxes = this.drawBed(bed, boxes);
                if (boxes >= MAX_BOXES_PER_FRAME) {
                    break;
                }
                for (BlockPos pos : entry.getValue()) {
                    if (boxes >= MAX_BOXES_PER_FRAME) {
                        break;
                    }
                    int[] rgb = colorFor(mc.theWorld.getBlockState(pos).getBlock());
                    if (this.fill.getValue()) {
                        RenderUtil.drawBlockBox(pos, 1.0, rgb[0], rgb[1], rgb[2]);
                    }
                    if (this.outline.getValue()) {
                        RenderUtil.drawBlockBoundingBox(pos, 1.0, rgb[0], rgb[1], rgb[2], outlineAlpha, 1.5F);
                    }
                    boxes++;
                }
            }
        } finally {
            RenderUtil.disableRenderState();
        }
    }

    private int drawBed(BlockPos bed, int boxes) {
        if (this.fill.getValue()) {
            RenderUtil.drawBlockBox(bed, 1.0, 255, 90, 90);
        }
        if (this.outline.getValue()) {
            RenderUtil.drawBlockBoundingBox(bed, 1.0, 255, 90, 90, 255, 1.5F);
        }
        return boxes + 1;
    }

    private void scanBeds() {
        for (BlockPos known : new LinkedHashSet<>(this.defenses.keySet())) {
            if (!this.isBedHead(known)) {
                this.defenses.remove(known);
            }
        }
        if (this.defenses.size() >= MAX_BEDS) {
            return;
        }
        int px = (int) Math.floor(mc.thePlayer.posX);
        int py = (int) Math.floor(mc.thePlayer.posY);
        int pz = (int) Math.floor(mc.thePlayer.posZ);
        for (int x = px - SCAN_RADIUS; x <= px + SCAN_RADIUS; x++) {
            for (int y = Math.max(0, py - SCAN_Y_RADIUS); y <= Math.min(255, py + SCAN_Y_RADIUS); y++) {
                for (int z = pz - SCAN_RADIUS; z <= pz + SCAN_RADIUS; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!this.isBedHead(pos) || this.defenses.containsKey(pos)) {
                        continue;
                    }
                    this.defenses.put(pos, this.flood(pos, this.surroundingRange.getValue()));
                    BlockPos other = this.otherHalf(pos);
                    if (other != null && !other.equals(pos)) {
                        for (BlockPos extra : this.flood(other, this.surroundingRange.getValue())) {
                            if (this.defenses.get(pos).size() >= MAX_DEFENSE_PER_BED) {
                                break;
                            }
                            this.defenses.get(pos).add(extra);
                        }
                    }
                    if (this.defenses.size() >= MAX_BEDS) {
                        return;
                    }
                }
            }
        }
    }

    private boolean isBedHead(BlockPos pos) {
        if (pos == null || mc.theWorld == null) {
            return false;
        }
        try {
            IBlockState state = mc.theWorld.getBlockState(pos);
            return state.getBlock() instanceof BlockBed
                    && state.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD;
        } catch (Exception ignored) {
            return false;
        }
    }

    private BlockPos otherHalf(BlockPos head) {
        try {
            IBlockState state = mc.theWorld.getBlockState(head);
            if (!(state.getBlock() instanceof BlockBed)) {
                return null;
            }
            return head.offset(state.getValue(BlockBed.FACING).getOpposite());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Set<BlockPos> flood(BlockPos center, int range) {
        Set<BlockPos> result = new LinkedHashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(center);
        visited.add(center);
        int rangeSq = range * range;
        while (!queue.isEmpty() && visited.size() < 256 && result.size() < MAX_DEFENSE_PER_BED) {
            BlockPos current = queue.poll();
            for (EnumFacing facing : EnumFacing.values()) {
                BlockPos next = current.offset(facing);
                if (!visited.add(next)) {
                    continue;
                }
                if (visited.size() >= 256 || result.size() >= MAX_DEFENSE_PER_BED) {
                    break;
                }
                if (next.getY() < 0 || next.getY() > 255 || next.getY() < center.getY()) {
                    continue;
                }
                double dx = (double) next.getX() - center.getX();
                double dy = (double) next.getY() - center.getY();
                double dz = (double) next.getZ() - center.getZ();
                if (dx * dx + dy * dy + dz * dz > (double) rangeSq) {
                    continue;
                }
                Block block;
                try {
                    block = mc.theWorld.getBlockState(next).getBlock();
                } catch (Exception ignored) {
                    continue;
                }
                if (block instanceof BlockBed || mc.theWorld.isAirBlock(next)) {
                    continue;
                }
                if (!isDefense(block)) {
                    continue;
                }
                result.add(next);
                queue.add(next);
            }
        }
        return result;
    }

    private static boolean isDefense(Block block) {
        return block == Blocks.end_stone
                || block == Blocks.wool
                || block == Blocks.glass
                || block == Blocks.stained_glass
                || block == Blocks.stained_glass_pane
                || block == Blocks.planks
                || block == Blocks.log
                || block == Blocks.log2
                || block == Blocks.obsidian
                || block == Blocks.clay
                || block == Blocks.hardened_clay
                || block == Blocks.stained_hardened_clay
                || block == Blocks.sandstone
                || block == Blocks.ice
                || block == Blocks.packed_ice;
    }

    private static int[] colorFor(Block block) {
        if (block == Blocks.obsidian) {
            return new int[]{170, 0, 170};
        }
        if (block == Blocks.end_stone) {
            return new int[]{225, 225, 140};
        }
        if (block == Blocks.glass || block == Blocks.stained_glass || block == Blocks.stained_glass_pane) {
            return new int[]{150, 220, 255};
        }
        if (block == Blocks.planks || block == Blocks.log || block == Blocks.log2) {
            return new int[]{160, 110, 60};
        }
        if (block == Blocks.sandstone) {
            return new int[]{220, 200, 150};
        }
        if (block == Blocks.ice || block == Blocks.packed_ice) {
            return new int[]{180, 240, 255};
        }
        return new int[]{235, 235, 235};
    }
}

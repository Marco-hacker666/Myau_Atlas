package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): automatically places blocks around your bed
// for defense, in a single or double ring layout.
import com.google.common.base.CaseFormat;

import java.util.ArrayList;
import java.util.List;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.TimerUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

public class AutoBed extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final ModeProperty layout = new ModeProperty("layout", 0, new String[]{"SINGLE", "DOUBLE"});
    public final FloatProperty bedRadius = new FloatProperty("bed-radius", 6.0F, 3.0F, 10.0F);
    public final FloatProperty range = new FloatProperty("range", 4.5F, 3.0F, 6.0F);
    public final IntProperty placeDelay = new IntProperty("place-delay", 80, 0, 300);
    public final BooleanProperty swapBack = new BooleanProperty("swap-back", true);
    private final TimerUtil placeTimer = new TimerUtil();
    private BlockPos bedFoot = null;
    private int placedCount = 0;

    public AutoBed() {
        super("AutoBed", false, false, "Automatically places blocks around your bed for defense.");
    }

    @Override
    public void onEnabled() {
        this.bedFoot = null;
        this.placedCount = 0;
        this.placeTimer.reset();
    }

    @Override
    public void onDisabled() {
        this.bedFoot = null;
        this.placedCount = 0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.layout.getModeString())};
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
        if (!this.isBedFoot(this.bedFoot)) {
            this.bedFoot = this.findBed();
            if (this.bedFoot == null) {
                return;
            }
        }
        if (!this.placeTimer.hasTimeElapsed(this.placeDelay.getValue().longValue())) {
            return;
        }
        int blockSlot = this.findBlockSlot();
        if (blockSlot == -1) {
            return;
        }
        Placement placement = this.nextPlacement();
        if (placement == null) {
            return;
        }
        int prevSlot = mc.thePlayer.inventory.currentItem;
        mc.thePlayer.inventory.currentItem = blockSlot;
        mc.playerController.updateController();
        ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
        if (stack != null && mc.playerController.onPlayerRightClick(
                mc.thePlayer, mc.theWorld, stack, placement.support, placement.face, placement.hitVec)) {
            mc.thePlayer.swingItem();
            this.placedCount++;
            this.placeTimer.reset();
        }
        if (this.swapBack.getValue()) {
            mc.thePlayer.inventory.currentItem = prevSlot;
            mc.playerController.updateController();
        }
    }

    private Placement nextPlacement() {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        double maxReach = this.range.getValue();
        for (BlockPos target : this.getDefensePositions()) {
            if (!this.isReplaceable(target)) {
                continue;
            }
            for (EnumFacing face : EnumFacing.values()) {
                BlockPos support = target.offset(face);
                if (this.isReplaceable(support) || this.isBed(support)) {
                    continue;
                }
                EnumFacing clickFace = face.getOpposite();
                Vec3 hitVec = new Vec3(support.getX() + 0.5 + clickFace.getFrontOffsetX() * 0.5,
                        support.getY() + 0.5 + clickFace.getFrontOffsetY() * 0.5,
                        support.getZ() + 0.5 + clickFace.getFrontOffsetZ() * 0.5);
                if (eyes.squareDistanceTo(hitVec) > maxReach * maxReach) {
                    continue;
                }
                return new Placement(support, clickFace, hitVec);
            }
        }
        return null;
    }

    private List<BlockPos> getDefensePositions() {
        List<BlockPos> list = new ArrayList<BlockPos>();
        if (this.bedFoot == null) {
            return list;
        }
        EnumFacing facing = this.getBedFacing(this.bedFoot);
        if (facing == null) {
            return list;
        }
        BlockPos foot = this.bedFoot;
        BlockPos head = foot.offset(facing);
        EnumFacing back = facing.getOpposite();
        EnumFacing left = this.leftOf(facing);
        EnumFacing right = left.getOpposite();
        list.add(foot.offset(left));
        list.add(foot.offset(right));
        list.add(head.offset(left));
        list.add(head.offset(right));
        list.add(foot.offset(back));
        list.add(head.offset(facing));
        if (this.layout.getValue() == 1) {
            list.add(foot.up(1));
            list.add(head.up(1));
            list.add(foot.offset(left).up(1));
            list.add(foot.offset(right).up(1));
            list.add(head.offset(left).up(1));
            list.add(head.offset(right).up(1));
            list.add(foot.offset(back).up(1));
            list.add(head.offset(facing).up(1));
        }
        return list;
    }

    private BlockPos findBed() {
        BlockPos origin = new BlockPos(MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY), MathHelper.floor_double(mc.thePlayer.posZ));
        int search = Math.max(1, Math.round(this.bedRadius.getValue()));
        double maxDistSq = (double) this.bedRadius.getValue() * (double) this.bedRadius.getValue();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -search; dx <= search; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -search; dz <= search; dz++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (!this.isBedFoot(pos) || this.getBedFacing(pos) == null) {
                        continue;
                    }
                    double dist = mc.thePlayer.getDistanceSq(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (dist <= maxDistSq && dist < bestDist) {
                        bestDist = dist;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private boolean isBed(BlockPos pos) {
        return pos != null && mc.theWorld.getBlockState(pos).getBlock() instanceof BlockBed;
    }

    private boolean isBedFoot(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        if (!(block instanceof BlockBed)) {
            return false;
        }
        int meta = block.getMetaFromState(mc.theWorld.getBlockState(pos));
        return (meta & 8) == 0;
    }

    private EnumFacing getBedFacing(BlockPos pos) {
        int meta = mc.theWorld.getBlockState(pos).getBlock().getMetaFromState(mc.theWorld.getBlockState(pos));
        switch (meta & 3) {
            case 0:
                return EnumFacing.SOUTH;
            case 1:
                return EnumFacing.WEST;
            case 2:
                return EnumFacing.NORTH;
            case 3:
                return EnumFacing.EAST;
            default:
                return null;
        }
    }

    private EnumFacing leftOf(EnumFacing facing) {
        switch (facing) {
            case NORTH:
                return EnumFacing.WEST;
            case WEST:
                return EnumFacing.SOUTH;
            case SOUTH:
                return EnumFacing.EAST;
            case EAST:
                return EnumFacing.NORTH;
            default:
                return EnumFacing.NORTH;
        }
    }

    private int findBlockSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.stackSize > 0 && stack.getItem() instanceof ItemBlock) {
                return i;
            }
        }
        return -1;
    }

    private boolean isReplaceable(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        return block == Blocks.air || block == Blocks.water || block == Blocks.flowing_water
                || block == Blocks.lava || block == Blocks.flowing_lava || block == Blocks.fire;
    }

    private static class Placement {
        final BlockPos support;
        final EnumFacing face;
        final Vec3 hitVec;

        Placement(BlockPos support, EnumFacing face, Vec3 hitVec) {
            this.support = support;
            this.face = face;
            this.hitVec = hitVec;
        }
    }
}

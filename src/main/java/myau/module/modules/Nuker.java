package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): automatically breaks blocks around you.
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import myau.util.TimerUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;

public class Nuker extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final FloatProperty radius = new FloatProperty("radius", 4.0F, 1.0F, 6.0F);
    public final IntProperty delay = new IntProperty("delay", 120, 0, 1000);
    public final ModeProperty filter = new ModeProperty("filter", 0, new String[]{"ALL", "WHITELIST"});
    public final TextProperty whitelist = new TextProperty("whitelist", "wool,planks,log", () -> filter.getValue() == 1);
    public final BooleanProperty swing = new BooleanProperty("swing", true);
    private final TimerUtil mineTimer = new TimerUtil();
    private BlockPos current = null;

    public Nuker() {
        super("Nuker", false, false, "Automatically breaks blocks around you.");
    }

    @Override
    public void onEnabled() {
        this.current = null;
        this.mineTimer.reset();
    }

    @Override
    public void onDisabled() {
        this.current = null;
        if (mc.playerController != null) {
            try {
                mc.playerController.resetBlockRemoving();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(this.radius.getValue())};
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
        if (this.current != null && !this.isMineable(this.current)) {
            this.current = null;
        }
        if (this.current == null) {
            this.current = this.scan();
            if (this.current == null) {
                return;
            }
        }
        if (!this.mineTimer.hasTimeElapsed(this.delay.getValue().longValue())) {
            return;
        }
        EnumFacing face = this.firstExposedFace(this.current);
        if (face == null) {
            this.current = null;
            return;
        }
        mc.playerController.onPlayerDamageBlock(this.current, face);
        if (this.swing.getValue()) {
            mc.thePlayer.swingItem();
        } else {
            try {
                mc.getNetHandler().addToSendQueue(new C0APacketAnimation());
            } catch (Exception ignored) {
            }
        }
        this.mineTimer.reset();
    }

    private BlockPos scan() {
        int r = MathHelper.ceiling_float_int(this.radius.getValue());
        double rangeSq = (double) this.radius.getValue() * (double) this.radius.getValue();
        int baseX = MathHelper.floor_double(mc.thePlayer.posX);
        int baseY = MathHelper.floor_double(mc.thePlayer.posY);
        int baseZ = MathHelper.floor_double(mc.thePlayer.posZ);
        for (int dy = 1; dy >= -r; dy--) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = new BlockPos(baseX + dx, baseY + dy, baseZ + dz);
                    double distSq = mc.thePlayer.getDistanceSq(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (distSq > rangeSq) {
                        continue;
                    }
                    if (this.isMineable(pos) && this.firstExposedFace(pos) != null) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private boolean isMineable(BlockPos pos) {
        if (!mc.theWorld.isBlockLoaded(pos)) {
            return false;
        }
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        if (block == Blocks.air || block == Blocks.water || block == Blocks.flowing_water
                || block == Blocks.lava || block == Blocks.flowing_lava || block == Blocks.fire
                || block == Blocks.bedrock || block == Blocks.barrier) {
            return false;
        }
        if (this.filter.getValue() == 1) {
            String name = block.getUnlocalizedName().toLowerCase();
            for (String entry : this.whitelist.getValue().split(",")) {
                String key = entry.trim().toLowerCase();
                if (!key.isEmpty() && name.contains(key)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private EnumFacing firstExposedFace(BlockPos pos) {
        for (EnumFacing face : EnumFacing.values()) {
            if (mc.theWorld.isAirBlock(pos.offset(face))) {
                return face;
            }
        }
        return null;
    }
}

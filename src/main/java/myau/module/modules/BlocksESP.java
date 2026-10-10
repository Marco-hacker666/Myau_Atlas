package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): highlights nearby ores through walls.
// RenderUtil has no 3D line helper, so the tracer segment is drawn here.
import java.util.concurrent.CopyOnWriteArraySet;

import myau.event.EventTarget;
import myau.events.LoadWorldEvent;
import myau.events.Render3DEvent;
import myau.mixin.IAccessorMinecraft;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.RenderUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

public class BlocksESP extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int SCAN_BUDGET = 4096;
    private static final int MAX_TRACKED = 1024;

    public final IntProperty range = new IntProperty("range", 32, 8, 64);
    public final BooleanProperty diamonds = new BooleanProperty("diamonds", true);
    public final BooleanProperty gold = new BooleanProperty("gold", true);
    public final BooleanProperty iron = new BooleanProperty("iron", true);
    public final BooleanProperty emeralds = new BooleanProperty("emeralds", true);
    public final BooleanProperty coal = new BooleanProperty("coal", false);
    public final BooleanProperty redstone = new BooleanProperty("redstone", false);
    public final BooleanProperty lapis = new BooleanProperty("lapis", false);
    public final BooleanProperty outline = new BooleanProperty("outline", true);
    public final BooleanProperty tracers = new BooleanProperty("tracers", false);

    private final CopyOnWriteArraySet<BlockPos> tracked = new CopyOnWriteArraySet<BlockPos>();
    private int scanCursor = 0;

    public BlocksESP() {
        super("BlocksESP", false, false, "Highlights nearby ores through walls with tracers.");
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(this.tracked.size())};
    }

    @Override
    public void onEnabled() {
        this.tracked.clear();
        this.scanCursor = 0;
    }

    @Override
    public void onDisabled() {
        this.tracked.clear();
        this.scanCursor = 0;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.tracked.clear();
        this.scanCursor = 0;
    }

    private boolean isTrackedType(int id) {
        switch (id) {
            case 56:
                return this.diamonds.getValue();
            case 14:
                return this.gold.getValue();
            case 15:
                return this.iron.getValue();
            case 129:
                return this.emeralds.getValue();
            case 16:
                return this.coal.getValue();
            case 73:
            case 74:
                return this.redstone.getValue();
            case 21:
                return this.lapis.getValue();
            default:
                return false;
        }
    }

    private int paletteFor(int id) {
        switch (id) {
            case 56:
                return 0x55FFFF;
            case 14:
                return 0xFFAA00;
            case 15:
                return 0xFFFFFF;
            case 129:
                return 0x55FF55;
            case 16:
                return 0x555555;
            case 73:
            case 74:
                return 0xFF5555;
            case 21:
                return 0x5555FF;
            default:
                return 0xFFFFFF;
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null || mc.getRenderViewEntity() == null) {
            return;
        }
        this.scanSlice();
        if (this.tracked.isEmpty()) {
            return;
        }
        double maxDist = this.range.getValue().doubleValue();
        boolean drawOutline = this.outline.getValue();
        boolean drawTracers = this.tracers.getValue();
        Vec3 start = drawTracers ? this.tracerStart() : null;
        RenderUtil.enableRenderState();
        for (BlockPos pos : this.tracked) {
            int id = Block.getIdFromBlock(mc.theWorld.getBlockState(pos).getBlock());
            if (!this.isTrackedType(id)) {
                this.tracked.remove(pos);
                continue;
            }
            if (mc.thePlayer.getDistance(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > maxDist) {
                continue;
            }
            int rgb = this.paletteFor(id);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            RenderUtil.drawBlockBox(pos, 1.0, r, g, b);
            if (drawOutline) {
                RenderUtil.drawBlockBoundingBox(pos, 1.0, r, g, b, 255, 1.5F);
            }
            if (drawTracers && start != null) {
                this.drawTracer(start, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        r / 255.0F, g / 255.0F, b / 255.0F, 1.0F, 1.5F);
            }
        }
        RenderUtil.disableRenderState();
    }

    /** Myau's RenderUtil has no line helper: the segment is drawn relative to the camera. */
    private void drawTracer(Vec3 start, double targetX, double targetY, double targetZ,
                            float red, float green, float blue, float alpha, float width) {
        double cameraX = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double cameraY = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double cameraZ = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();
        GL11.glPushMatrix();
        GL11.glTranslated(-cameraX, -cameraY, -cameraZ);
        GL11.glLineWidth(width);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glColor4f(red, green, blue, alpha);
        GL11.glVertex3d(start.xCoord, start.yCoord, start.zCoord);
        GL11.glVertex3d(targetX, targetY, targetZ);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(2.0F);
        GL11.glPopMatrix();
        GlStateManager.resetColor();
    }

    private void scanSlice() {
        int r = this.range.getValue();
        int size = r * 2 + 1;
        int plane = size * size;
        int total = plane * size;
        if (total <= 0) {
            return;
        }
        if (this.scanCursor < 0 || this.scanCursor >= total) {
            this.scanCursor = 0;
        }
        int baseX = (int) Math.floor(mc.thePlayer.posX);
        int baseY = (int) Math.floor(mc.thePlayer.posY);
        int baseZ = (int) Math.floor(mc.thePlayer.posZ);
        int end = Math.min(total, this.scanCursor + SCAN_BUDGET);
        for (int i = this.scanCursor; i < end; i++) {
            int dx = (i % size) - r;
            int dy = ((i / size) % size) - r;
            int dz = (i / plane) - r;
            BlockPos pos = new BlockPos(baseX + dx, baseY + dy, baseZ + dz);
            if (!mc.theWorld.isBlockLoaded(pos, false)) {
                continue;
            }
            int id = Block.getIdFromBlock(mc.theWorld.getBlockState(pos).getBlock());
            if (this.isTrackedType(id) && this.tracked.size() < MAX_TRACKED) {
                this.tracked.add(pos);
            }
        }
        this.scanCursor = end >= total ? 0 : end;
    }

    private Vec3 tracerStart() {
        Vec3 vec;
        if (mc.gameSettings.thirdPersonView == 0) {
            vec = new Vec3(0.0, 0.0, 1.0)
                    .rotatePitch((float) -Math.toRadians(RenderUtil.lerpFloat(
                            mc.getRenderViewEntity().rotationPitch,
                            mc.getRenderViewEntity().prevRotationPitch,
                            ((IAccessorMinecraft) mc).getTimer().renderPartialTicks)))
                    .rotateYaw((float) -Math.toRadians(RenderUtil.lerpFloat(
                            mc.getRenderViewEntity().rotationYaw,
                            mc.getRenderViewEntity().prevRotationYaw,
                            ((IAccessorMinecraft) mc).getTimer().renderPartialTicks)));
        } else {
            vec = new Vec3(0.0, 0.0, 0.0)
                    .rotatePitch((float) -Math.toRadians(RenderUtil.lerpFloat(
                            mc.thePlayer.cameraPitch, mc.thePlayer.prevCameraPitch,
                            ((IAccessorMinecraft) mc).getTimer().renderPartialTicks)))
                    .rotateYaw((float) -Math.toRadians(RenderUtil.lerpFloat(
                            mc.thePlayer.cameraYaw, mc.thePlayer.prevCameraYaw,
                            ((IAccessorMinecraft) mc).getTimer().renderPartialTicks)));
        }
        return new Vec3(vec.xCoord, vec.yCoord + mc.getRenderViewEntity().getEyeHeight(), vec.zCoord);
    }
}

package myau.module.modules;

// Ported from OpenSkid (GPL-3.0): off-screen directional arrows to nearby
// players with distance labels.
import java.util.ArrayList;
import java.util.List;

import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.util.RenderUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

public class Arrows extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int WHITE = 0xFFFFFFFF;

    public final IntProperty distance = new IntProperty("distance", 48, 8, 64);
    public final IntProperty radius = new IntProperty("radius", 55, 30, 200);
    public final BooleanProperty teamColor = new BooleanProperty("team-color", true);
    public final BooleanProperty showDistance = new BooleanProperty("show-distance", true);
    public final BooleanProperty onlyOffscreen = new BooleanProperty("only-offscreen", true);
    public final BooleanProperty hideTeammates = new BooleanProperty("hide-teammates", false);
    public final BooleanProperty bots = new BooleanProperty("bots", false);

    private final List<EntityPlayer> arrowPlayers = new ArrayList<EntityPlayer>(64);

    public Arrows() {
        super("Arrows", false, false, "Shows offscreen arrows pointing to nearby players.");
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(arrowPlayers.size())};
    }

    @Override
    public void onEnabled() {
        arrowPlayers.clear();
    }

    @Override
    public void onDisabled() {
        arrowPlayers.clear();
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null || mc.getRenderViewEntity() == null) {
            return;
        }
        if (mc.currentScreen != null) {
            return;
        }
        arrowPlayers.clear();
        float maxDist = distance.getValue().floatValue();
        boolean hideTeams = hideTeammates.getValue();
        boolean allowBots = bots.getValue();
        boolean offscreenOnly = onlyOffscreen.getValue();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null || player == mc.thePlayer || player == mc.getRenderViewEntity() || player.deathTime > 0) {
                continue;
            }
            if (!allowBots && TeamUtil.isBot(player)) {
                continue;
            }
            if (hideTeams && TeamUtil.isSameTeam(player)) {
                continue;
            }
            if (mc.getRenderViewEntity().getDistanceToEntity(player) > maxDist) {
                continue;
            }
            if (offscreenOnly && RenderUtil.isInViewFrustum(player.getEntityBoundingBox(), 0.1)) {
                continue;
            }
            arrowPlayers.add(player);
        }
        if (arrowPlayers.isEmpty()) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        float centerX = sr.getScaledWidth() / 2.0F;
        float centerY = sr.getScaledHeight() / 2.0F;
        float ring = radius.getValue().floatValue();
        boolean useTeam = teamColor.getValue();
        boolean labels = showDistance.getValue();
        double selfX = RenderUtil.lerpDouble(mc.thePlayer.posX, mc.thePlayer.prevPosX, event.getPartialTicks());
        double selfZ = RenderUtil.lerpDouble(mc.thePlayer.posZ, mc.thePlayer.prevPosZ, event.getPartialTicks());
        GlStateManager.pushMatrix();
        RenderUtil.enableRenderState();
        for (EntityPlayer player : arrowPlayers) {
            double px = RenderUtil.lerpDouble(player.posX, player.prevPosX, event.getPartialTicks());
            double pz = RenderUtil.lerpDouble(player.posZ, player.prevPosZ, event.getPartialTicks());
            float yawBetween = RotationUtil.getYawBetween(selfX, selfZ, px, pz);
            if (mc.gameSettings.thirdPersonView == 2) {
                yawBetween += 180.0F;
            }
            if (Math.abs(MathHelper.wrapAngleTo180_float(yawBetween)) < 25.0F) {
                continue;
            }
            float dirX = (float) Math.sin(Math.toRadians(yawBetween));
            float dirY = (float) Math.cos(Math.toRadians(yawBetween)) * -1.0F;
            float renderX = centerX + ring * dirX;
            float renderY = centerY + ring * dirY;
            int color = useTeam ? TeamUtil.getTeamColor(player, 1.0F).getRGB() : WHITE;
            GlStateManager.pushMatrix();
            GlStateManager.translate(renderX, renderY, 0.0F);
            RenderUtil.drawTriangle(0.0F, 0.0F, (float) (Math.atan2(dirY, dirX) + Math.PI), 10.0F, color);
            GlStateManager.popMatrix();
            if (labels) {
                String text = (int) mc.thePlayer.getDistanceToEntity(player) + "m";
                mc.fontRendererObj.drawStringWithShadow(text, renderX - mc.fontRendererObj.getStringWidth(text) / 2.0F, renderY + 9.0F, WHITE);
            }
        }
        RenderUtil.disableRenderState();
        GlStateManager.popMatrix();
    }
}

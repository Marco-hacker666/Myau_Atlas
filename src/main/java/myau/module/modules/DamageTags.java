/*
 * Ported from OpenSkid (https://github.com/loloshelly102-a11y/OpenSkid, GPL-3.0),
 * src/main/java/openskid/module/modules/DamageTags.java, 2026-10-05: package openskid -> myau,
 * OpenSkid -> Myau. Changes after that are marked in comments.
 */
package myau.module.modules;

import myau.event.EventTarget;
import myau.events.AttackEvent;
import myau.events.Render3DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Floating combat numbers. Concept adapted from the raven-bS DamageTags module.
public class DamageTags extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MAX_TAGS = 32;
    private static final long WATCH_MS = 2000L;

    public final FloatProperty lifetime = new FloatProperty("Lifetime", 1.2f, 0.2f, 3.0f);
    public final FloatProperty mergeDistance = new FloatProperty("Merge-Distance", 1.0f, 0.0f, 3.0f);
    public final FloatProperty scale = new FloatProperty("Scale", 1.0f, 0.5f, 3.0f);
    public final BooleanProperty shadow = new BooleanProperty("Shadow", true);
    public final BooleanProperty background = new BooleanProperty("Background", true);

    private final List<FloatTag> tags = new ArrayList<>();
    private final Map<Integer, Float> baselines = new HashMap<>();
    private int watchId = -1;
    private long watchUntil = 0L;

    public DamageTags() {
        super("DamageTags", false, false, "Shows floating damage numbers above entities you hit.");
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(tags.size())};
    }

    @Override
    public void onEnabled() {
        resetState();
    }

    @Override
    public void onDisabled() {
        resetState();
    }

    private void resetState() {
        tags.clear();
        baselines.clear();
        watchId = -1;
        watchUntil = 0L;
    }

    private float total(EntityLivingBase living) {
        return living.getHealth() + living.getAbsorptionAmount();
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!isEnabled() || mc.thePlayer == null || !(event.getTarget() instanceof EntityLivingBase)) {
            return;
        }
        EntityLivingBase entity = (EntityLivingBase) event.getTarget();
        if (entity instanceof EntityArmorStand || entity == mc.thePlayer) {
            return;
        }
        watchId = entity.getEntityId();
        watchUntil = System.currentTimeMillis() + WATCH_MS;
        baselines.put(watchId, total(entity));
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (watchId != -1) {
            if (now > watchUntil) {
                baselines.remove(watchId);
                watchId = -1;
            } else {
                Entity watched = mc.theWorld.getEntityByID(watchId);
                if (watched instanceof EntityLivingBase && TeamUtil.isEntityLoaded(watched)) {
                    EntityLivingBase living = (EntityLivingBase) watched;
                    Float base = baselines.get(watchId);
                    if (base != null) {
                        float delta = total(living) - base;
                        if (Math.abs(delta) > 0.01f) {
                            spawnTag(living, delta, now);
                            baselines.put(watchId, total(living));
                        }
                    }
                } else {
                    baselines.remove(watchId);
                    watchId = -1;
                }
            }
        }

        pruneExpired(now);
        if (tags.isEmpty()) {
            return;
        }

        RenderManager rm = mc.getRenderManager();
        for (FloatTag tag : tags) {
            float progress = (now - tag.createdAt) / (float) tag.durationMs;
            if (progress >= 1.0f) {
                continue;
            }
            float rise = (1.0f - (1.0f - progress) * (1.0f - progress)) * 1.0f;
            float alpha = progress <= 0.7f ? 1.0f : 1.0f - (progress - 0.7f) / 0.3f;
            double x = tag.x - rm.viewerPosX;
            double y = tag.y + rise - rm.viewerPosY;
            double z = tag.z - rm.viewerPosZ;

            GlStateManager.pushMatrix();
            GlStateManager.translate((float) x, (float) y, (float) z);
            GlStateManager.rotate(-rm.playerViewY, 0.0f, 1.0f, 0.0f);
            GlStateManager.rotate(rm.playerViewX, 1.0f, 0.0f, 0.0f);
            float s = 0.02666667f * scale.getValue();
            GlStateManager.scale(-s, -s, s);
            GlStateManager.disableLighting();
            GlStateManager.depthMask(false);
            GlStateManager.disableDepth();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);

            int halfW = mc.fontRendererObj.getStringWidth(tag.text) / 2;
            if (background.getValue()) {
                drawTagBackground(halfW, alpha);
            }
            int alpha255 = Math.max(4, Math.min(255, Math.round(alpha * 255.0f)));
            int color = (alpha255 << 24) | (tag.color & 0xFFFFFF);
            mc.fontRendererObj.drawString(tag.text, -halfW, 0, color, shadow.getValue());

            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.enableLighting();
            GlStateManager.disableBlend();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            GlStateManager.popMatrix();
        }
    }

    private void pruneExpired(long now) {
        Iterator<FloatTag> it = tags.iterator();
        while (it.hasNext()) {
            FloatTag tag = it.next();
            if (now - tag.createdAt >= tag.durationMs) {
                it.remove();
            }
        }
    }

    private long lifetimeMs() {
        return Math.max(1L, Math.round(lifetime.getValue() * 1000.0f));
    }

    private void spawnTag(EntityLivingBase living, float delta, long now) {
        double x = living.posX;
        double y = living.posY + living.height + 0.5;
        double z = living.posZ;
        float mergeSq = mergeDistance.getValue() * mergeDistance.getValue();
        for (FloatTag tag : tags) {
            if (now - tag.createdAt >= tag.durationMs) {
                continue;
            }
            double dx = tag.x - x;
            double dy = tag.y - y;
            double dz = tag.z - z;
            if (dx * dx + dy * dy + dz * dz <= mergeSq) {
                tag.amount += delta;
                tag.text = formatAmount(tag.amount);
                tag.color = colorFor(tag.amount);
                tag.createdAt = now;
                tag.durationMs = lifetimeMs();
                return;
            }
        }
        tags.add(new FloatTag(formatAmount(delta), colorFor(delta), delta, x, y, z, now, lifetimeMs()));
        if (tags.size() > MAX_TAGS) {
            tags.remove(0);
        }
    }

    private void drawTagBackground(int halfW, float alpha) {
        float bgAlpha = 0.25f * alpha;
        if (bgAlpha <= 0.0f) {
            return;
        }
        GlStateManager.disableTexture2D();
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(-halfW - 1, -1, 0).color(0.0f, 0.0f, 0.0f, bgAlpha).endVertex();
        wr.pos(-halfW - 1, 8, 0).color(0.0f, 0.0f, 0.0f, bgAlpha).endVertex();
        wr.pos(halfW + 1, 8, 0).color(0.0f, 0.0f, 0.0f, bgAlpha).endVertex();
        wr.pos(halfW + 1, -1, 0).color(0.0f, 0.0f, 0.0f, bgAlpha).endVertex();
        tess.draw();
        GlStateManager.enableTexture2D();
    }

    private String formatAmount(float value) {
        float abs = Math.abs(value);
        String sign = value >= 0.0f ? "+" : "-";
        if (Math.abs(abs - Math.round(abs)) < 0.05f) {
            return sign + Math.round(abs);
        }
        return sign + String.format(Locale.US, "%.1f", abs);
    }

    private int colorFor(float value) {
        return value >= 0.0f ? 0x55FF55 : 0xFF5555;
    }

    private static class FloatTag {
        private String text;
        private int color;
        private float amount;
        private final double x;
        private final double y;
        private final double z;
        private long createdAt;
        private long durationMs;

        private FloatTag(String text, int color, float amount, double x, double y, double z, long createdAt, long durationMs) {
            this.text = text;
            this.color = color;
            this.amount = amount;
            this.x = x;
            this.y = y;
            this.z = z;
            this.createdAt = createdAt;
            this.durationMs = durationMs;
        }
    }
}

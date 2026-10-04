package myau.module.modules;

import net.minecraft.client.renderer.GlStateManager;
import myau.Myau;
import myau.event.EventTarget;
import myau.events.RenderLivingEvent;
import myau.module.Module;
import myau.util.TeamUtil;
import myau.property.properties.BooleanProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.*;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.GL11;

public class Chams extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final BooleanProperty players = new BooleanProperty("players", true);
    public final BooleanProperty friends = new BooleanProperty("friends", true);
    public final BooleanProperty enemiess = new BooleanProperty("enemies", true);
    public final BooleanProperty bosses = new BooleanProperty("bosses", false);
    public final BooleanProperty mobs = new BooleanProperty("mobs", false);
    public final BooleanProperty creepers = new BooleanProperty("creepers", false);
    public final BooleanProperty enderman = new BooleanProperty("endermen", false);
    public final BooleanProperty blaze = new BooleanProperty("blazes", false);
    public final BooleanProperty animals = new BooleanProperty("animals", false);
    public final BooleanProperty self = new BooleanProperty("self", false);
    public final BooleanProperty bots = new BooleanProperty("bots", false);

    private boolean shouldRenderChams(EntityLivingBase entityLivingBase) {
        if (entityLivingBase.deathTime > 0) {
            return false;
        } else if (mc.getRenderViewEntity().getDistanceToEntity(entityLivingBase) > 512.0F) {
            return false;
        } else if (entityLivingBase instanceof EntityPlayer) {
            if (entityLivingBase != mc.thePlayer && entityLivingBase != mc.getRenderViewEntity()) {
                if (TeamUtil.isBot((EntityPlayer) entityLivingBase)) {
                    return this.bots.getValue();
                } else if (TeamUtil.isFriend((EntityPlayer) entityLivingBase)) {
                    return this.friends.getValue();
                } else {
                    return TeamUtil.isTarget((EntityPlayer) entityLivingBase) ? this.enemiess.getValue() : this.players.getValue();
                }
            } else {
                return this.self.getValue() && mc.gameSettings.thirdPersonView != 0;
            }
        } else if (entityLivingBase instanceof EntityDragon || entityLivingBase instanceof EntityWither) {
            return !entityLivingBase.isInvisible() && this.bosses.getValue();
        } else if (!(entityLivingBase instanceof EntityMob) && !(entityLivingBase instanceof EntitySlime)) {
            return (entityLivingBase instanceof EntityAnimal
                    || entityLivingBase instanceof EntityBat
                    || entityLivingBase instanceof EntitySquid
                    || entityLivingBase instanceof EntityVillager) && this.animals.getValue();
        } else if (entityLivingBase instanceof EntityCreeper) {
            return this.creepers.getValue();
        } else if (entityLivingBase instanceof EntityEnderman) {
            return this.enderman.getValue();
        } else {
            return entityLivingBase instanceof EntityBlaze ? this.blaze.getValue() : this.mobs.getValue();
        }
    }

    public Chams() {
        super("Chams", false);
    }

    /* Tinting (ChamsColors). Wrapped round RendererLivingEntity.renderModel
       only -- the body -- so armour and held items, drawn afterwards by the
       layers, keep their textures. Texture, lighting and the lightmap are
       off while the model is drawn, so the colour is flat and even through
       walls; blending is on for the group's fill transparency. What was on
       before is put back exactly as it was. */
    private static boolean tinting;
    private static boolean lightingWas;
    private static boolean blendWas;
    private static int blendSrcWas;
    private static int blendDstWas;

    public static void beginTint(EntityLivingBase entity) {
        if (Myau.moduleManager == null) {
            return;
        }
        Module module = Myau.moduleManager.modules.get(Chams.class);
        if (!(module instanceof Chams) || !module.isEnabled() || !((Chams) module).shouldRenderChams(entity)) {
            return;
        }
        ThemeStyle style = ThemeStyle.active(ChamsColors.class);
        if (style == null || !style.recolours()) {
            return;
        }
        java.awt.Color color = style.fill(java.awt.Color.WHITE, entity, -1);
        lightingWas = GL11.glIsEnabled(GL11.GL_LIGHTING);
        blendWas = GL11.glIsEnabled(GL11.GL_BLEND);
        blendSrcWas = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        blendDstWas = GL11.glGetInteger(GL11.GL_BLEND_DST);
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        mc.entityRenderer.disableLightmap();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(color.getRed() / 255.0F, color.getGreen() / 255.0F, color.getBlue() / 255.0F,
                color.getAlpha() / 255.0F);
        tinting = true;
    }

    public static void endTint() {
        if (!tinting) {
            return;
        }
        tinting = false;
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableTexture2D();
        mc.entityRenderer.enableLightmap();
        if (lightingWas) {
            GlStateManager.enableLighting();
        }
        GlStateManager.blendFunc(blendSrcWas, blendDstWas);
        if (!blendWas) {
            GlStateManager.disableBlend();
        }
    }

    @EventTarget
    public void onRenderLiving(RenderLivingEvent event) {
        if (this.isEnabled()) {
            if (this.shouldRenderChams(event.getEntity())) {
                switch (event.getType()) {
                    case PRE:
                        GL11.glEnable(32823);
                        GL11.glPolygonOffset(1.0F, -2500000.0F);
                        break;
                    case POST:
                        GL11.glPolygonOffset(1.0F, 2500000.0F);
                        GL11.glDisable(32823);
                }
            }
        }
    }
}

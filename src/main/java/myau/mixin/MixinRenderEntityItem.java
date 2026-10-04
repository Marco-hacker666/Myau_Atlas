package myau.mixin;

import myau.module.modules.ItemPhysics;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemTransformVec3f;
import net.minecraft.client.renderer.entity.RenderEntityItem;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

/**
 * ItemPhysics (2026-09-28): dropped items lie flat and tumble in the air.
 *
 * func_177077_a is where vanilla positions a dropped item before drawing it:
 * it translates to the entity with a bob, spins it about the vertical, and
 * returns how many copies of the model to draw. The drawing itself -- the
 * texture, blending, the model's own GROUND transform, one pass per copy --
 * stays vanilla's (doRender). Only the placement is replaced, and only while
 * the module is on.
 *
 * The item's GROUND transform is read from its model rather than assumed, so
 * its centre can be put exactly on the pivot: resting on the ground when
 * still, turning about its own middle when falling. doRender applies that
 * transform per copy, after this method, in the frame this method leaves.
 */
@SideOnly(Side.CLIENT)
@Mixin(RenderEntityItem.class)
public abstract class MixinRenderEntityItem {

    @Shadow
    private Random field_177079_e;

    @Shadow
    protected abstract int func_177078_a(ItemStack stack);

    /** The renderer's own random, kept while ItemPhysics swaps in its still one. */
    @Unique
    private Random myau$vanillaRandom;

    @Inject(
            method = "func_177077_a(Lnet/minecraft/entity/item/EntityItem;DDDFLnet/minecraft/client/resources/model/IBakedModel;)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void myau$itemPhysics(EntityItem item, double x, double y, double z, float partialTicks,
                                  IBakedModel model, CallbackInfoReturnable<Integer> cir) {
        ItemPhysics physics = ItemPhysics.instance;
        if (physics == null) {
            myau$restoreRandom();
            return;
        }

        /* Everything that could fail is worked out before any GL state is
           touched; if any of it does, vanilla draws the item as usual. */
        boolean gui3d;
        int copies;
        float tx;
        float ty;
        float tz;
        float lift;
        float yaw;
        float spin;
        try {
            ItemStack stack = item.getEntityItem();
            if (stack == null || stack.getItem() == null) {
                myau$restoreRandom();
                return;
            }
            gui3d = model.isGui3d();
            copies = this.func_177078_a(stack);
            ItemTransformVec3f ground = model.getItemCameraTransforms()
                    .getTransform(ItemCameraTransforms.TransformType.GROUND);
            /* doRender scales a 3D model by a half before its GROUND transform. */
            float outer = gui3d ? 0.5F : 1.0F;
            tx = ground.translation.x * outer;
            ty = ground.translation.y * outer;
            tz = ground.translation.z * outer;
            /* Half the model's extent along the axis that ends up vertical:
               a block's height (Y), or a flat sprite's 1/16 thickness (its Z,
               which points up once it is laid down). */
            lift = gui3d
                    ? 0.5F * ground.scale.y * outer
                    : 0.5F * (1.0F / 16.0F) * ground.scale.z + 0.005F;
            /* hoverStart is random per item, set once: a fixed resting angle. */
            yaw = item.hoverStart * (180.0F / (float) Math.PI);
            spin = item.onGround ? 0.0F
                    : ((item.getAge() + partialTicks) * 20.0F * physics.getRotationSpeed()) % 360.0F;
        } catch (RuntimeException e) {
            myau$restoreRandom();
            return;
        }

        if (this.myau$vanillaRandom == null) {
            this.myau$vanillaRandom = this.field_177079_e;
        }
        this.field_177079_e = ItemPhysics.STILL;

        GlStateManager.translate((float) x, (float) y + lift, (float) z);
        GlStateManager.rotate(yaw, 0.0F, 1.0F, 0.0F);
        if (gui3d) {
            GlStateManager.rotate(spin, 1.0F, 0.0F, 0.0F);
        } else {
            /* Laid down: the sprite's face turned to the sky. */
            GlStateManager.rotate(90.0F + spin, 1.0F, 0.0F, 0.0F);
        }
        /* Cancel the GROUND offset doRender is about to apply, so the model's
           centre sits on the pivot. */
        GlStateManager.translate(-tx, -ty, -tz);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        cir.setReturnValue(copies);
    }

    private void myau$restoreRandom() {
        if (this.myau$vanillaRandom != null) {
            this.field_177079_e = this.myau$vanillaRandom;
            this.myau$vanillaRandom = null;
        }
    }
}

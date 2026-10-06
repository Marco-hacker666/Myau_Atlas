/*
 * Ported from OpenSkid (https://github.com/loloshelly102-a11y/OpenSkid, GPL-3.0),
 * src/main/java/openskid/module/modules/EntityCulling.java, 2026-10-05: package openskid -> myau,
 * OpenSkid -> Myau. Changes after that are marked in comments.
 */
package myau.module.modules;

// Adapted from the MiauMinus EntityCulling donor. Rewritten for OpenSkid APIs.
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;

public class EntityCulling extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double MAX_DISTANCE = 48.0;
    private static final int MAX_ENTRIES = 512;
    private static EntityCulling instance;
    private final Map<Integer, CachedVis> cache = new ConcurrentHashMap<Integer, CachedVis>();
    public final IntProperty refreshMs = new IntProperty("refresh-ms", 150, 10, 1000);

    public EntityCulling() {
        super("EntityCulling", false, false, "Skips rendering hidden entities for more FPS.");
    }

    @Override
    public void onEnabled() {
        instance = this;
        this.cache.clear();
    }

    @Override
    public void onDisabled() {
        instance = null;
        this.cache.clear();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.cache.clear();
            return;
        }
        if (this.cache.size() > MAX_ENTRIES) {
            this.cache.clear();
        }
        long now = System.currentTimeMillis();
        for (Object obj : mc.theWorld.loadedEntityList) {
            if (!(obj instanceof EntityLivingBase)) {
                continue;
            }
            EntityLivingBase entity = (EntityLivingBase) obj;
            if (this.isExempt(entity)) {
                this.cache.remove(entity.getEntityId());
                continue;
            }
            CachedVis cached = this.cache.get(entity.getEntityId());
            if (cached != null && now - cached.time <= this.refreshMs.getValue()) {
                continue;
            }
            if (mc.thePlayer.getDistanceToEntity(entity) > MAX_DISTANCE) {
                this.cache.remove(entity.getEntityId());
                continue;
            }
            this.cache.put(entity.getEntityId(), new CachedVis(mc.thePlayer.canEntityBeSeen(entity), now));
        }
        Iterator<Map.Entry<Integer, CachedVis>> it = this.cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, CachedVis> entry = it.next();
            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            if (entity == null || entity.isDead || now - entry.getValue().time > this.refreshMs.getValue() * 10L) {
                it.remove();
            }
        }
    }

    public static boolean shouldCull(EntityLivingBase entity) {
        EntityCulling module = instance;
        if (module == null || !module.isEnabled() || entity == null || mc.thePlayer == null) {
            return false;
        }
        if (module.isExempt(entity)) {
            return false;
        }
        CachedVis cached = module.cache.get(entity.getEntityId());
        if (cached == null) {
            return false;
        }
        return !cached.visible;
    }

    private boolean isExempt(EntityLivingBase entity) {
        if (entity == mc.thePlayer || entity == mc.getRenderViewEntity()) {
            return true;
        }
        if (entity instanceof EntityArmorStand && entity.hasCustomName()) {
            return true;
        }
        return entity == this.currentTarget();
    }

    private EntityLivingBase currentTarget() {
        if (Myau.moduleManager == null) {
            return null;
        }
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return killAura == null ? null : killAura.getTarget();
    }

    private static final class CachedVis {
        private final boolean visible;
        private final long time;

        private CachedVis(boolean visible, long time) {
            this.visible = visible;
            this.time = time;
        }
    }
}

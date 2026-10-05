package myau.ui.impl.clickgui.atlas;

import myau.module.Module;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sub-groups of the Legit page, and which module goes in which.
 *
 * This table is the only place the grouping lives. Modules are matched by
 * class name, so a module's display name can change without moving it. A Legit
 * module missing from the table is never hidden: it lands in "More".
 */
final class LegitGroups {
    static final String HUD = "HUD";
    static final String VISUALS = "Visuals";
    static final String STATS = "Stats";
    static final String UTILITY = "Utility";
    /* Not "Other": the Modules page already has an "Other" category. */
    static final String MORE = "More";

    private static final String[] ORDER = {HUD, VISUALS, STATS, UTILITY, MORE};
    private static final Map<String, String> GROUP_OF = new HashMap<String, String>();

    static {
        put(HUD, "HUD", "LegitHUD", "ArmorHUD", "EffectsHUD", "FPScounter", "Hotbar", "DynamicIsland",
                "WaterMark", "WaterMark2", "KeyStrokes", "PotionHUD", "InventoryHUD", "PlayerList",
                "ClosestPlayerHUD");
        put(VISUALS, "Ambience", "Animations", "Capes", "FullBright", "HitParticleEffects", "ItemPhysics",
                "NoHurtCam");
        put(STATS, "Statistics", "PlayTracker", "FKCounter");
        put(UTILITY, "AutoRespawn", "FreeLook", "Sprint");
    }

    private LegitGroups() {
    }

    private static void put(String group, String... classNames) {
        for (String name : classNames) {
            GROUP_OF.put(name, group);
        }
    }

    /** The Legit modules split into their groups, in display order, leaving out empty groups. */
    static Map<String, List<Module>> group(List<Module> legit) {
        Map<String, List<Module>> buckets = new LinkedHashMap<String, List<Module>>();
        for (String name : ORDER) {
            buckets.put(name, new ArrayList<Module>());
        }
        for (Module module : legit) {
            if (module == null) {
                continue;
            }
            String group = GROUP_OF.get(module.getClass().getSimpleName());
            buckets.get(group == null ? MORE : group).add(module);
        }
        Map<String, List<Module>> out = new LinkedHashMap<String, List<Module>>();
        for (Map.Entry<String, List<Module>> entry : buckets.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /** The sidebar icon for a group, from the icons the menu already draws. */
    static String icon(String group) {
        if (HUD.equals(group)) {
            return "selection";
        }
        if (VISUALS.equals(group)) {
            return "render";
        }
        if (STATS.equals(group)) {
            return "motion";
        }
        if (UTILITY.equals(group)) {
            return "player";
        }
        return "folder";
    }
}

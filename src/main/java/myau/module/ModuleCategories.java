package myau.module;

import myau.module.modules.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which category every module is in -- the one place that says so
 * (2026-09-28, sorted after LiquidBounce's categories).
 *
 * The classic menu used to hardcode five lists of its own, and Atlas read
 * those; a module missing from them was in no menu at all (seven were).
 * The classic menu now builds its frames from here, Atlas follows it as
 * before, and a unit test fails when a registered module has no entry.
 *
 * Where LiquidBounce has the module, it sits where LiquidBounce puts it
 * (Scaffold, FastPlace, Timer and ChestStealer in World; AntiBot and the
 * name hider in Misc; GhostHand and Disabler in Exploit). The packet-lag
 * tools used in fights -- FakeLag, LagRange, ServerLag, KnockbackDelay,
 * BackTrack, TickBase -- stay together in Combat, as LiquidBounce nextgen
 * files Backtrack, FakeLag and TickBase.
 */
public final class ModuleCategories {

    private static final Map<Class<? extends Module>, Category> CATEGORY =
            new LinkedHashMap<Class<? extends Module>, Category>();

    static {
        put(Category.COMBAT,
                AimAssist.class, AutoClicker.class, KillAura.class, PlainAura.class, Criticals.class,
                Velocity.class, HitBox.class, Reach.class, BackTrack.class, TickBase.class, TimerRange.class,
                FakeLag.class, LagRange.class, KnockbackDelay.class, ServerLag.class, MoreKB.class,
                SprintReset.class, Wtap.class, KeepSprint.class, BlockHit.class, HitSelect.class,
                Hitflick.class, InvulnTiming.class, ClickAssits.class, Piercing.class, Displace.class,
                KeepRange.class, AimBacktrack.class, KBDisplacement.class, ThrowAura.class, AutoHeal.class,
                AutoGapple.class, FastBow.class, NoHitDelay.class);
        put(Category.PLAYER,
                Blink.class, NoFall.class, AntiVoid.class, Clutch.class, AutoTool.class, AutoSwap.class,
                InvManager.class, Refill.class, AntiAFK.class, AntiFireball.class, AutoAnduril.class,
                InventoryClicker.class);
        put(Category.MOVEMENT,
                Fly.class, Speed.class, LongJump.class, Jesus.class, NoSlow.class, NoJumpDelay.class,
                SafeWalk.class, Eagle.class, InvWalk.class, TargetStrafe.class, MoveFix.class,
                Stasis.class);
        put(Category.RENDER,
                ESP.class, ESP2D.class, Chams.class, NameTags.class, Tracers.class,
                TargetESP.class, TargetHUD.class, ItemESP.class, ChestESP.class, BedESP.class, Xray.class,
                ViewClip.class, Trajectories.class, BlockOverlay.class,
                BreakProgress.class, Indicators.class, Radar.class, LatencyCrosshair.class,
                TeamHealthDisplay.class, RenderFixes.class,
                AntiDebuff.class, BedPlates.class, TNTTimer.class, DamageTags.class, ItemTags.class,
                EntityCulling.class);
        put(Category.WORLD,
                Scaffold.class, AutoBlockIn.class, AutoBedDef.class, AutoHeadHitter.class, BedNuker.class,
                ChestAura.class, ChestStealer.class, FastPlace.class, SpeedMine.class, Timer.class,
                AntiObbyTrap.class);
        put(Category.MISC,
                AntiBot.class, TargetFilter.class, MCF.class, NickHider.class, Spammer.class, AutoAuth.class,
                AutoHypixel.class, BedwarUtils.class, BedTracker.class, LightningTracker.class,
                ESPDetector.class, AntiCheat.class, AntiObfuscate.class, MouseRawInput.class,
                ResourceSpoofer.class, NoRotate.class, ExploitFixer.class);
        put(Category.EXPLOIT,
                Disabler.class, GhostHand.class, ClientSpoofer.class);
        put(Category.CLIENT,
                ClickGUIModule.class, GuiModule.class, FlagDetector.class,
                FlagResponder.class, HitCheck.class, FightLog.class, Debug.class, PacketLogger.class,
                ServerFingerprint.class, AutoTune.class, Adaptive.class, ServerProfiles.class,
                LatencyGovernor.class, Panic.class, Rotations.class, Notifications.class);
        put(Category.LEGIT,
                Ambience.class, Animations.class, ArmorHUD.class, AutoRespawn.class, Capes.class,
                DynamicIsland.class, EffectsHUD.class, FPScounter.class, FreeLook.class, FullBright.class,
                HUD.class, HitParticleEffects.class, Hotbar.class,
                ItemPhysics.class, LegitHUD.class, NoHurtCam.class, Sprint.class, Statistics.class, PlayTracker.class,
                WaterMark.class, WaterMark2.class, KeyStrokes.class, PotionHUD.class, InventoryHUD.class,
                PlayerList.class, ClosestPlayerHUD.class, FKCounter.class);
        /* In the order they appear on screen, not sorted: the order is the point. */
        put(Category.THEME,
                Theme.class, PlayerColors.class, TracerColors.class, TargetColors.class, BacktrackColors.class,
                BedColors.class, ChestColors.class, ItemColors.class, BlockColors.class, ProjectileColors.class,
                InterfaceColors.class, ChamsColors.class, NameTagColors.class, WidgetColors.class,
                EffectColors.class);
    }

    private ModuleCategories() {
    }

    @SafeVarargs
    private static void put(Category category, Class<? extends Module>... types) {
        for (Class<? extends Module> type : types) {
            Category previous = CATEGORY.put(type, category);
            if (previous != null) {
                throw new IllegalStateException(type.getSimpleName() + " is in " + previous + " and " + category);
            }
        }
    }

    /** The module's category; null for a module this table does not know. */
    public static Category of(Class<?> type) {
        return CATEGORY.get(type);
    }

    /** Every module type in a category, in the order listed. */
    public static List<Class<? extends Module>> typesIn(Category category) {
        List<Class<? extends Module>> out = new ArrayList<Class<? extends Module>>();
        for (Map.Entry<Class<? extends Module>, Category> entry : CATEGORY.entrySet()) {
            if (entry.getValue() == category) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    /**
     * The registered modules in a category: sorted by name, except Theme,
     * which keeps its listed order.
     */
    public static List<Module> modulesIn(Category category, Map<Class<?>, Module> registered) {
        List<Module> out = new ArrayList<Module>();
        for (Class<? extends Module> type : typesIn(category)) {
            Module module = registered.get(type);
            if (module != null) {
                out.add(module);
            }
        }
        if (category != Category.THEME) {
            out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        }
        return out;
    }

    /** Every module type with a category. */
    public static Map<Class<? extends Module>, Category> all() {
        return Collections.unmodifiableMap(CATEGORY);
    }
}

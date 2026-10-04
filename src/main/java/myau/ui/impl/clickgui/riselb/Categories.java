package myau.ui.impl.clickgui.riselb;

import myau.Myau;
import myau.module.Module;
import myau.module.modules.*;
import myau.module.modules.Timer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Category-to-module mapping for the RiseLB ClickGUI.
 *
 * The module lists are copied verbatim from ModernClickGui's constructor.
 * Module has no category field, so every GUI style has to carry its own
 * grouping; keeping this in one place means the Liquid Glass screen itself
 * stays about rendering.
 *
 * Entries may be null when a module class is absent from a build, so every
 * list is filtered before it is handed out.
 */
public final class Categories {

    private Categories() {
    }

    public static Map<String, List<Module>> build() {
        List<Module> combatModules = Arrays.asList(
                Myau.moduleManager.getModule(AimAssist.class),
                Myau.moduleManager.getModule(MoveFix.class),
                Myau.moduleManager.getModule(AutoClicker.class),
                Myau.moduleManager.getModule(KillAura.class),
                Myau.moduleManager.getModule(Wtap.class),
                Myau.moduleManager.getModule(Velocity.class),
                Myau.moduleManager.getModule(ServerLag.class),
                Myau.moduleManager.getModule(Reach.class),
                Myau.moduleManager.getModule(TargetStrafe.class),
                Myau.moduleManager.getModule(NoHitDelay.class),
                Myau.moduleManager.getModule(AntiFireball.class),
                Myau.moduleManager.getModule(KnockbackDelay.class),
                Myau.moduleManager.getModule(LagRange.class),
                Myau.moduleManager.getModule(HitBox.class),
                Myau.moduleManager.getModule(MoreKB.class),
                Myau.moduleManager.getModule(Refill.class),
                Myau.moduleManager.getModule(HitSelect.class),
                Myau.moduleManager.getModule(BackTrack.class),
                Myau.moduleManager.getModule(Hitflick.class),
                Myau.moduleManager.getModule(TimerRange.class),
                Myau.moduleManager.getModule(ClickAssits.class),
                Myau.moduleManager.getModule(Criticals.class),
                Myau.moduleManager.getModule(BlockHit.class),
                Myau.moduleManager.getModule(SprintReset.class),
                Myau.moduleManager.getModule(Displace.class),
                Myau.moduleManager.getModule(Piercing.class),
                Myau.moduleManager.getModule(Stasis.class),
                Myau.moduleManager.getModule(TargetFilter.class),
                Myau.moduleManager.getModule(Panic.class),
                Myau.moduleManager.getModule(TickBase.class)
        );

        List<Module> movementModules = Arrays.asList(
                Myau.moduleManager.getModule(AntiAFK.class),
                Myau.moduleManager.getModule(Fly.class),
                Myau.moduleManager.getModule(FastBow.class),
                Myau.moduleManager.getModule(Timer.class),
                Myau.moduleManager.getModule(Speed.class),
                Myau.moduleManager.getModule(LongJump.class),
                Myau.moduleManager.getModule(Sprint.class),
                Myau.moduleManager.getModule(SafeWalk.class),
                Myau.moduleManager.getModule(Jesus.class),
                Myau.moduleManager.getModule(Blink.class),
                Myau.moduleManager.getModule(NoFall.class),
                Myau.moduleManager.getModule(NoSlow.class),
                Myau.moduleManager.getModule(KeepSprint.class),
                Myau.moduleManager.getModule(Eagle.class),
                Myau.moduleManager.getModule(NoJumpDelay.class),
                Myau.moduleManager.getModule(AntiVoid.class)
        );

        List<Module> renderModules = Arrays.asList(
                Myau.moduleManager.getModule(ESP.class),
                Myau.moduleManager.getModule(Chams.class),
                Myau.moduleManager.getModule(FullBright.class),
                Myau.moduleManager.getModule(BlockOverlay.class),
                Myau.moduleManager.getModule(Tracers.class),
                Myau.moduleManager.getModule(NameTags.class),
                Myau.moduleManager.getModule(Xray.class),
                Myau.moduleManager.getModule(TargetESP.class),
                Myau.moduleManager.getModule(TargetHUD.class),
                Myau.moduleManager.getModule(Indicators.class),
                Myau.moduleManager.getModule(BedESP.class),
                Myau.moduleManager.getModule(ItemESP.class),
                Myau.moduleManager.getModule(BreakProgress.class),
                Myau.moduleManager.getModule(ViewClip.class),
                Myau.moduleManager.getModule(NoHurtCam.class),
                Myau.moduleManager.getModule(HUD.class),
                Myau.moduleManager.getModule(ChestESP.class),
                Myau.moduleManager.getModule(Trajectories.class),
                Myau.moduleManager.getModule(Radar.class),
                Myau.moduleManager.getModule(FPScounter.class),
                Myau.moduleManager.getModule(WaterMark.class),
                Myau.moduleManager.getModule(WaterMark2.class),
                Myau.moduleManager.getModule(HitParticleEffects.class),
                Myau.moduleManager.getModule(DynamicIsland.class),
                Myau.moduleManager.getModule(ESP2D.class),
                Myau.moduleManager.getModule(RiseClickGUIModule.class),
                Myau.moduleManager.getModule(TeamHealthDisplay.class),
                Myau.moduleManager.getModule(Statistics.class),
                Myau.moduleManager.getModule(Animations.class),
                Myau.moduleManager.getModule(Hotbar.class),
                Myau.moduleManager.getModule(Capes.class),
                Myau.moduleManager.getModule(Animations.class),
                Myau.moduleManager.getModule(Ambience.class),
                Myau.moduleManager.getModule(GuiModule.class),
                Myau.moduleManager.getModule(RenderFixes.class),
                Myau.moduleManager.getModule(FreeLook.class),
                Myau.moduleManager.getModule(ItemPhysics.class),
                Myau.moduleManager.getModule(ClickGUIModule.class)
        );

        List<Module> playerModules = Arrays.asList(
                Myau.moduleManager.getModule(AutoHeal.class),
                Myau.moduleManager.getModule(FakeLag.class),
                Myau.moduleManager.getModule(AutoTool.class),
                Myau.moduleManager.getModule(ChestStealer.class),
                Myau.moduleManager.getModule(ChestAura.class),
                Myau.moduleManager.getModule(AutoBedDef.class),
                Myau.moduleManager.getModule(InvManager.class),
                Myau.moduleManager.getModule(InvWalk.class),
                Myau.moduleManager.getModule(Scaffold.class),
                Myau.moduleManager.getModule(Clutch.class),
                Myau.moduleManager.getModule(PacketLogger.class),
                Myau.moduleManager.getModule(AutoBlockIn.class),
                Myau.moduleManager.getModule(AutoSwap.class),
                Myau.moduleManager.getModule(SpeedMine.class),
                Myau.moduleManager.getModule(FastPlace.class),
                Myau.moduleManager.getModule(GhostHand.class),
                Myau.moduleManager.getModule(MCF.class),
                Myau.moduleManager.getModule(AntiDebuff.class),
                Myau.moduleManager.getModule(FlagDetector.class),
                Myau.moduleManager.getModule(AutoGapple.class),
                Myau.moduleManager.getModule(AutoHeadHitter.class),
                Myau.moduleManager.getModule(ThrowAura.class)
        );

        List<Module> miscModules = Arrays.asList(
                Myau.moduleManager.getModule(Spammer.class),
                Myau.moduleManager.getModule(BedNuker.class),
                Myau.moduleManager.getModule(AntiBot.class),
                Myau.moduleManager.getModule(BedTracker.class),
                Myau.moduleManager.getModule(LightningTracker.class),
                Myau.moduleManager.getModule(NoRotate.class),
                Myau.moduleManager.getModule(NickHider.class),
                Myau.moduleManager.getModule(AntiObbyTrap.class),
                Myau.moduleManager.getModule(AntiObfuscate.class),
                Myau.moduleManager.getModule(AutoAnduril.class),
                Myau.moduleManager.getModule(InventoryClicker.class),
                Myau.moduleManager.getModule(Disabler.class),
                Myau.moduleManager.getModule(ClientSpoofer.class),
                Myau.moduleManager.getModule(ResourceSpoofer.class),
                Myau.moduleManager.getModule(MouseRawInput.class),
                Myau.moduleManager.getModule(BedwarUtils.class),
                Myau.moduleManager.getModule(AutoAuth.class),
                Myau.moduleManager.getModule(AutoHypixel.class)
        );

        Comparator<Module> comparator = Comparator.comparing(m -> m.getName().toLowerCase());
        combatModules.sort(comparator);
        movementModules.sort(comparator);
        renderModules.sort(comparator);
        playerModules.sort(comparator);
        miscModules.sort(comparator);

        Map<String, List<Module>> categories = new LinkedHashMap<String, List<Module>>();
        put(categories, "Combat", combatModules);
        put(categories, "Movement", movementModules);
        put(categories, "Render", renderModules);
        put(categories, "Player", playerModules);
        put(categories, "Misc", miscModules);
        return categories;
    }

    private static void put(Map<String, List<Module>> target, String name, List<Module> modules) {
        List<Module> cleaned = new ArrayList<Module>(modules);
        cleaned.removeIf(m -> m == null);
        if (!cleaned.isEmpty()) {
            target.put(name, cleaned);
        }
    }
}

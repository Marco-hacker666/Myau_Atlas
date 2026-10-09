package myau;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.florianmichael.viamcp.ViaMCP;
import myau.command.CommandManager;
import myau.command.commands.*;
import myau.config.Config;
import myau.event.EventManager;
import myau.font.FontManagers;
import myau.management.*;
import myau.module.Module;
import myau.module.ModuleManager;
import myau.module.modules.HUD;
import myau.module.modules.Hotbar;
import myau.module.modules.*;
import myau.property.Property;
import myau.property.PropertyManager;
import myau.ui.impl.clickgui.normal.ClickGuiScreen;
import myau.util.font.FontManager;
import org.lwjgl.opengl.Display;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Objects;

public class Myau {
    public static String clientName = "&7[&cM&6y&ea&au&7+]&r ";
    public static String version;
    private static final String MC_VERSION = "1.8.9";
    public static RotationManager rotationManager;
    public static FloatManager floatManager;
    public static BlinkManager blinkManager;
    public static DelayManager delayManager;
    public static LagManager lagManager;
    public static PlayerStateManager playerStateManager;
    public static FriendManager friendManager;
    public static TargetManager targetManager;
    public static PropertyManager propertyManager;
    public static ModuleManager moduleManager;
    public static NotificationManager notificationManager;
    public static CommandManager commandManager;
    private static boolean anticheatRegistered;
    public static FontManagers fontManagers;

    public Myau() {
        this.init();
    }

    public void init() {
        rotationManager = new RotationManager();
        floatManager = new FloatManager();
        blinkManager = new BlinkManager();
        delayManager = new DelayManager();
        lagManager = new LagManager();
        playerStateManager = new PlayerStateManager();
        friendManager = new FriendManager();
        targetManager = new TargetManager();
        propertyManager = new PropertyManager();
        moduleManager = new ModuleManager();
        notificationManager = new NotificationManager();
        commandManager = new CommandManager();
        fontManagers = new FontManagers();
        fontManagers.load();
        EventManager.register(rotationManager);
        EventManager.register(floatManager);
        EventManager.register(blinkManager);
        EventManager.register(delayManager);
        EventManager.register(lagManager);
        EventManager.register(moduleManager);
        EventManager.register(commandManager);
        EventManager.register(new myau.management.PerfLog());
        EventManager.register(new myau.management.HitTimer());
        EventManager.register(new myau.management.LogUploader());
        EventManager.register(new myau.management.PlaceRotations());
        /* One definition of "a connection to a server" (Phase 2, 2026-09-28). */
        EventManager.register(new myau.management.ServerSession());
        /* What action packets this tick has already sent (Rise item 2). */
        EventManager.register(new myau.management.TickActions.Listener());
        /* Ends holds that outlive their reason (PacketHolds leases, 2026-09-28). */
        EventManager.register(new myau.management.PacketHolds.Watchdog(name -> {
            myau.module.Module module = moduleManager.getModule(name);
            return module == null || module.isEnabled();
        }));
        registerClientAnticheat();
        moduleManager.modules.put(AimAssist.class, new AimAssist());
        moduleManager.modules.put(AntiAFK.class, new AntiAFK());
        moduleManager.modules.put(AntiDebuff.class, new AntiDebuff());
        moduleManager.modules.put(AntiFireball.class, new AntiFireball());
        moduleManager.modules.put(AntiObbyTrap.class, new AntiObbyTrap());
        moduleManager.modules.put(AntiObfuscate.class, new AntiObfuscate());
        moduleManager.modules.put(AntiVoid.class, new AntiVoid());
        moduleManager.modules.put(AutoClicker.class, new AutoClicker());
        moduleManager.modules.put(AutoAnduril.class, new AutoAnduril());
        moduleManager.modules.put(KnockbackDelay.class, new KnockbackDelay());
        moduleManager.modules.put(TargetESP.class, new TargetESP());
        moduleManager.modules.put(AutoHeal.class, new AutoHeal());
        moduleManager.modules.put(AutoTool.class, new AutoTool());
        moduleManager.modules.put(AutoSwap.class, new AutoSwap());
        moduleManager.modules.put(BedNuker.class, new BedNuker());
        moduleManager.modules.put(BedESP.class, new BedESP());
        moduleManager.modules.put(BedTracker.class, new BedTracker());
        moduleManager.modules.put(Blink.class, new Blink());
        moduleManager.modules.put(Clutch.class, new Clutch());
        moduleManager.modules.put(PacketLogger.class, new PacketLogger());
        moduleManager.modules.put(ResourceSpoofer.class, new ResourceSpoofer());
        moduleManager.modules.put(BackTrack.class, new BackTrack());
        moduleManager.modules.put(Hitflick.class, new Hitflick());
        moduleManager.modules.put(AutoHeadHitter.class, new AutoHeadHitter());
        moduleManager.modules.put(FPScounter.class, new FPScounter());
        moduleManager.modules.put(Chams.class, new Chams());
        moduleManager.modules.put(WaterMark.class, new WaterMark());
        moduleManager.modules.put(ChestESP.class, new ChestESP());
        moduleManager.modules.put(ClickGUIModule.class, new ClickGUIModule());
        moduleManager.modules.put(ChestStealer.class, new ChestStealer());
        moduleManager.modules.put(Eagle.class, new Eagle());
        moduleManager.modules.put(ESP.class, new ESP());
        moduleManager.modules.put(FastPlace.class, new FastPlace());
        moduleManager.modules.put(ServerLag.class, new ServerLag());
        moduleManager.modules.put(Fly.class, new Fly());
        moduleManager.modules.put(FakeLag.class, new FakeLag());
        moduleManager.modules.put(FullBright.class, new FullBright());
        moduleManager.modules.put(GhostHand.class, new GhostHand());
        moduleManager.modules.put(GuiModule.class, new GuiModule());
        moduleManager.modules.put(HitSelect.class, new HitSelect());
        moduleManager.modules.put(AutoHypixel.class, new AutoHypixel());
        moduleManager.modules.put(HUD.class, new HUD());
        moduleManager.modules.put(Hotbar.class, new Hotbar());
        moduleManager.modules.put(MoreKB.class, new MoreKB());
        moduleManager.modules.put(Indicators.class, new Indicators());
        moduleManager.modules.put(InventoryClicker.class, new InventoryClicker());
        moduleManager.modules.put(InvManager.class, new InvManager());
        moduleManager.modules.put(InvWalk.class, new InvWalk());
        moduleManager.modules.put(Criticals.class, new Criticals());
        moduleManager.modules.put(FastBow.class, new FastBow());
        moduleManager.modules.put(BlockHit.class, new BlockHit());
        moduleManager.modules.put(ThrowAura.class, new ThrowAura());
        moduleManager.modules.put(ESP2D.class, new ESP2D());
        moduleManager.modules.put(ClientSpoofer.class, new ClientSpoofer());
        moduleManager.modules.put(ItemESP.class, new ItemESP());
        moduleManager.modules.put(Jesus.class, new Jesus());
        moduleManager.modules.put(Disabler.class, new Disabler());
        moduleManager.modules.put(Displace.class, new Displace());
        moduleManager.modules.put(KeepSprint.class, new KeepSprint());
        moduleManager.modules.put(FlagDetector.class, new FlagDetector());
        moduleManager.modules.put(ESPDetector.class, new ESPDetector());
        moduleManager.modules.put(AntiCheat.class, new AntiCheat());
        moduleManager.modules.put(HitCheck.class, new HitCheck());
        moduleManager.modules.put(FightLog.class, new FightLog());
        moduleManager.modules.put(Debug.class, new Debug());
        moduleManager.modules.put(Theme.class, new Theme());
        moduleManager.modules.put(PlayerColors.class, new PlayerColors());
        moduleManager.modules.put(TracerColors.class, new TracerColors());
        moduleManager.modules.put(TargetColors.class, new TargetColors());
        moduleManager.modules.put(BacktrackColors.class, new BacktrackColors());
        moduleManager.modules.put(BedColors.class, new BedColors());
        moduleManager.modules.put(ChestColors.class, new ChestColors());
        moduleManager.modules.put(ItemColors.class, new ItemColors());
        moduleManager.modules.put(BlockColors.class, new BlockColors());
        moduleManager.modules.put(ProjectileColors.class, new ProjectileColors());
        moduleManager.modules.put(InterfaceColors.class, new InterfaceColors());
        moduleManager.modules.put(ChamsColors.class, new ChamsColors());
        moduleManager.modules.put(NameTagColors.class, new NameTagColors());
        moduleManager.modules.put(WidgetColors.class, new WidgetColors());
        moduleManager.modules.put(EffectColors.class, new EffectColors());
        moduleManager.modules.put(ServerFingerprint.class, new ServerFingerprint());
        moduleManager.modules.put(AutoTune.class, new AutoTune());
        moduleManager.modules.put(Adaptive.class, new Adaptive());
        moduleManager.modules.put(PlainAura.class, new PlainAura());
        moduleManager.modules.put(InvulnTiming.class, new InvulnTiming());
        moduleManager.modules.put(LatencyCrosshair.class, new LatencyCrosshair());
        moduleManager.modules.put(FlagResponder.class, new FlagResponder());
        moduleManager.modules.put(LatencyGovernor.class, new LatencyGovernor());
        /* Rise comparison items 4, 6 and 7 (2026-09-28). */
        moduleManager.modules.put(KeepRange.class, new KeepRange());
        moduleManager.modules.put(AimBacktrack.class, new AimBacktrack());
        moduleManager.modules.put(KBDisplacement.class, new KBDisplacement());
        moduleManager.modules.put(ServerProfiles.class, new ServerProfiles());
        moduleManager.modules.put(HitBox.class, new HitBox());
        moduleManager.modules.put(KillAura.class, new KillAura());
        moduleManager.modules.put(LagRange.class, new LagRange());
        moduleManager.modules.put(LightningTracker.class, new LightningTracker());
        moduleManager.modules.put(LongJump.class, new LongJump());
        moduleManager.modules.put(MCF.class, new MCF());
        moduleManager.modules.put(Ambience.class, new Ambience());
        moduleManager.modules.put(ChestAura.class, new ChestAura());
        moduleManager.modules.put(NameTags.class, new NameTags());
        moduleManager.modules.put(NickHider.class, new NickHider());
        moduleManager.modules.put(NoFall.class, new NoFall());
        moduleManager.modules.put(Stasis.class, new Stasis());
        moduleManager.modules.put(TargetFilter.class, new TargetFilter());
        moduleManager.modules.put(Rotations.class, new Rotations());
        moduleManager.modules.put(Panic.class, new Panic());
        moduleManager.modules.put(NoHitDelay.class, new NoHitDelay());
        moduleManager.modules.put(NoHurtCam.class, new NoHurtCam());
        moduleManager.modules.put(NoJumpDelay.class, new NoJumpDelay());
        moduleManager.modules.put(NoRotate.class, new NoRotate());
        moduleManager.modules.put(BlockOverlay.class, new BlockOverlay());
        moduleManager.modules.put(MouseRawInput.class, new MouseRawInput());
        moduleManager.modules.put(Piercing.class, new Piercing());
        moduleManager.modules.put(BedwarUtils.class, new BedwarUtils());
        moduleManager.modules.put(NoSlow.class, new NoSlow());
        moduleManager.modules.put(NoItemRelease.class, new NoItemRelease());
        moduleManager.modules.put(AutoAuth.class, new AutoAuth());
        moduleManager.modules.put(Capes.class, new Capes());
        moduleManager.modules.put(MoveFix.class, new MoveFix());
        moduleManager.modules.put(ClickAssits.class, new ClickAssits());
        moduleManager.modules.put(Timer.class, new Timer());
        moduleManager.modules.put(BreakProgress.class , new BreakProgress());
        moduleManager.modules.put(SprintReset.class, new SprintReset());
        moduleManager.modules.put(Radar.class, new Radar());
        moduleManager.modules.put(Reach.class, new Reach());
        moduleManager.modules.put(RenderFixes.class, new RenderFixes());
        moduleManager.modules.put(Refill.class, new Refill());
        moduleManager.modules.put(SafeWalk.class, new SafeWalk());
        moduleManager.modules.put(DynamicIsland.class, new DynamicIsland());
        moduleManager.modules.put(Scaffold.class, new Scaffold());
        /* Ported from OpenSkid (GPL-3.0), 2026-10-05: registered in source, where the
           Atlas UI jar had patched Myau.class to do it (PortRegistry). Same place, same order. */
        moduleManager.modules.put(KeyStrokes.class, new KeyStrokes());
        moduleManager.modules.put(PotionHUD.class, new PotionHUD());
        moduleManager.modules.put(InventoryHUD.class, new InventoryHUD());
        moduleManager.modules.put(PlayerList.class, new PlayerList());
        moduleManager.modules.put(ClosestPlayerHUD.class, new ClosestPlayerHUD());
        moduleManager.modules.put(FKCounter.class, new FKCounter());
        moduleManager.modules.put(BedPlates.class, new BedPlates());
        moduleManager.modules.put(TNTTimer.class, new TNTTimer());
        moduleManager.modules.put(DamageTags.class, new DamageTags());
        moduleManager.modules.put(ItemTags.class, new ItemTags());
        moduleManager.modules.put(EntityCulling.class, new EntityCulling());
        moduleManager.modules.put(Notifications.class, new Notifications());
        moduleManager.modules.put(ExploitFixer.class, new ExploitFixer());
        moduleManager.modules.put(AutoBlockIn.class, new AutoBlockIn());
        moduleManager.modules.put(AntiBot.class, new AntiBot());
        moduleManager.modules.put(AutoBedDef.class, new AutoBedDef());
        moduleManager.modules.put(TickBase.class, new TickBase());
        moduleManager.modules.put(Statistics.class, new Statistics());
        moduleManager.modules.put(PlayTracker.class, new PlayTracker());
        moduleManager.modules.put(FreeLook.class, new FreeLook());
        moduleManager.modules.put(ItemPhysics.class, new ItemPhysics());
        moduleManager.modules.put(Spammer.class, new Spammer());
        moduleManager.modules.put(Speed.class, new Speed());
        moduleManager.modules.put(SpeedMine.class, new SpeedMine());
        moduleManager.modules.put(Sprint.class, new Sprint());
        moduleManager.modules.put(TargetHUD.class, new TargetHUD());
        moduleManager.modules.put(TargetStrafe.class, new TargetStrafe());
        moduleManager.modules.put(Tracers.class, new Tracers());
        moduleManager.modules.put(WaterMark2.class, new WaterMark2());
        moduleManager.modules.put(TimerRange.class, new TimerRange());
        moduleManager.modules.put(Trajectories.class, new Trajectories());
        moduleManager.modules.put(Velocity.class, new Velocity());
        moduleManager.modules.put(ViewClip.class, new ViewClip());
        moduleManager.modules.put(Wtap.class, new Wtap());
        moduleManager.modules.put(Xray.class, new Xray());
        moduleManager.modules.put(TeamHealthDisplay.class, new TeamHealthDisplay());
        moduleManager.modules.put(Animations.class, new Animations());
        moduleManager.modules.put(AutoGapple.class, new AutoGapple());
        moduleManager.modules.put(HitParticleEffects.class, new HitParticleEffects());
        moduleManager.modules.put(LegitHUD.class, new LegitHUD());
        moduleManager.modules.put(ArmorHUD.class, new ArmorHUD());
        moduleManager.modules.put(EffectsHUD.class, new EffectsHUD());
        moduleManager.modules.put(AutoRespawn.class, new AutoRespawn());
        commandManager.commands.add(new BindCommand());
        commandManager.commands.add(new myau.command.commands.ReportCommand());
        commandManager.commands.add(new ClickGuiCommand());
        commandManager.commands.add(new ConfigCommand());
        commandManager.commands.add(new DenickCommand());
        commandManager.commands.add(new FriendCommand());
        commandManager.commands.add(new HelpCommand());
        commandManager.commands.add(new HideCommand());
        commandManager.commands.add(new IgnCommand());
        commandManager.commands.add(new ItemCommand());
        commandManager.commands.add(new ListCommand());
        commandManager.commands.add(new ModuleCommand());
        commandManager.commands.add(new PlayerCommand());
        commandManager.commands.add(new ShowCommand());
        commandManager.commands.add(new TargetCommand());
        commandManager.commands.add(new ToggleCommand());
        commandManager.commands.add(new VclipCommand());
        for (Module module : moduleManager.modules.values()) {
            ArrayList<Property<?>> properties = new ArrayList<>();
            /* The Theme groups keep their settings in ThemeStyle, so for them
               the superclass's fields are read too; every other module stays
               exactly as before (its own class only). */
            ArrayList<Field> fields = new ArrayList<>(java.util.Arrays.asList(module.getClass().getDeclaredFields()));
            if (module instanceof ThemeStyle) {
                fields.addAll(java.util.Arrays.asList(ThemeStyle.class.getDeclaredFields()));
            }
            for (final Field field : fields) {
                field.setAccessible(true);
                final Object obj;
                try {
                    obj = field.get(module);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
                if (obj instanceof Property<?>) {
                    ((Property<?>) obj).setOwner(module);
                    properties.add((Property<?>) obj);
                } else if (obj instanceof myau.property.PropertyGroup) {
                    /* A composite setting (IntRange, FloatRange): its members,
                       in order, as if they were fields (2026-09-28). */
                    for (Property<?> member : ((myau.property.PropertyGroup) obj).properties()) {
                        member.setOwner(module);
                        properties.add(member);
                    }
                }
            }
            /* Descriptions, headings, hints and menu order (ModuleDocs, 2026-10-04). */
            propertyManager.properties.put(module.getClass(),
                    new ArrayList<Property<?>>(myau.module.ModuleDocs.apply(module, properties)));
            EventManager.register(module);
        }
        Config config = new Config("default", true);
        if (config.file.exists()) {
            config.load();
        }
        if (friendManager.file.exists()) {
            friendManager.load();
        }
        if (targetManager.file.exists()) {
            targetManager.load();
        }
        FontManager.initializeFonts();
        ClickGuiScreen.getInstance();

        /* On exit the configuration is saved to the profile in use, not
           always to default.json (F-08, 2026-09-28). The old hook held the
           "default" instance created above, so after `.config load pika` or a
           ServerProfiles switch, quitting wrote that profile's values over
           default.json. Config.lastConfig is the profile last loaded or saved
           -- the same one `.config save` with no name writes to. Runs after
           the RESTORE stage, so borrowed values are put back first. */
        myau.management.Shutdown.register(myau.management.Shutdown.Stage.SAVE_CONFIG, "config", () -> {
            String name = Config.lastConfig == null || Config.lastConfig.isEmpty() ? "default" : Config.lastConfig;
            new Config(name, false).save();
        });

        me.ksyz.accountmanager.AccountManager.init();
        ViaMCP.create();

        /* Our own name (2026-10-08): "/version.json" found Forge's launcher profile first,
           which has no "version", so every build said "dev". */
        try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(Myau.class.getResourceAsStream("/myau-version.json")), StandardCharsets.UTF_8)) {
            JsonObject modInfo = new JsonParser().parse(reader).getAsJsonObject();
            version = modInfo.get("version").getAsString();
        } catch (Exception e) {
            version = "dev";
        }
        /* Setup: keep OneConfig's GUI key off RightShift, which is our ClickGUI key,
           and re-check it whenever a world loads. */
        try {
            java.io.File gameDir = net.minecraft.client.Minecraft.getMinecraft().mcDataDir;
            myau.setup.OneConfigPatcher.run(gameDir);
            myau.setup.OneConfigPatcher.watch(gameDir);
        } catch (Throwable ignored) {
        }
        updateDisplayTitle();

    }

    public static String getDisplayTitle() {
        String versionText = version == null || version.isEmpty() ? "dev" : version;
        return "OpenMyau+ (Main) - " + versionText + " | MC " + MC_VERSION;
    }

    public static void updateDisplayTitle() {
        if (Display.isCreated()) {
            Display.setTitle(getDisplayTitle());
        }
    }

    private void registerClientAnticheat() {
        if (anticheatRegistered) {
            return;
        }

        EventManager.register(new myau.anticheat.flag());
        EventManager.register(new myau.anticheat.AutoBlock());
        EventManager.register(new myau.anticheat.Noslow());
        EventManager.register(new myau.anticheat.KillAura());
        EventManager.register(new myau.anticheat.Scaffold());
        anticheatRegistered = true;
    }
}

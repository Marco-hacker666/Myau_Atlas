package myau.module;

import java.util.HashMap;
import java.util.Map;

/**
 * The English side of ModuleDocs (2026-10-04, language chosen in Atlas's
 * Appearance page): a description for every module, the hints, and the
 * headings. Kept apart so ModuleDocs stays readable.
 */
final class ModuleDocsEn {

    static final Map<String, String> DESCRIPTIONS = new HashMap<String, String>();
    static final Map<String, String> HELP = new HashMap<String, String>();
    static final Map<String, String> MODULE_HELP = new HashMap<String, String>();
    /** Chinese heading -> English heading. */
    static final Map<String, String> HEADINGS = new HashMap<String, String>();

    private ModuleDocsEn() {
    }

    private static void d(String module, String text) {
        DESCRIPTIONS.put(module, text);
    }

    private static void help(String key, String text) {
        HELP.put(ModuleDocs.norm(key), text);
    }

    private static void help(String module, String key, String text) {
        MODULE_HELP.put(module + ":" + ModuleDocs.norm(key), text);
    }

    private static void heading(String zh, String en) {
        HEADINGS.put(zh, en);
    }

    static {
        // ------------------------------------------------------------ COMBAT
        d("KillAura", "Attacks targets in range: picks a target, turns to it (several modes, silent or not), clicks at your CPS and can block for you.");
        d("AimAssist", "Nudges your aim toward a nearby target while you attack, like a small hand correction; never clicks by itself.");
        d("AutoClicker", "Clicks for you while the attack key is held, at a random CPS within your range; can block-hit or break blocks.");
        d("KnockbackDelay", "Holds incoming knockback for a moment so the push lands late.");
        d("AutoHeal", "Uses healing items (soup, potions, heads) when health is low.");
        d("BackTrack", "Keeps a retreating target where you last saw them a moment longer, so a swing reaches the position the server still has.");
        d("Hitflick", "Flicks the view away on a hit and back, to steer the knockback.");
        d("ServerLag", "Holds back some server packets to simulate a lagging server.");
        d("FakeLag", "Delays your outgoing movement so the server's copy of you runs behind, for the first hit.");
        d("HitSelect", "Lets an attack through only when it suits you, e.g. when it costs less knockback.");
        d("MoreKB", "Resets sprint on attack so each hit deals more knockback.");
        d("Criticals", "Makes attacks critical hits (+50% damage) by packets or small hops.");
        d("FastBow", "Draws and shoots the bow faster.");
        d("BlockHit", "Blocks with the sword between hits, timed by hurt time, prediction or rhythm.");
        d("ThrowAura", "Throws snowballs and eggs at enemies.");
        d("Displace", "Turns at the moment of a hit to aim the target's knockback at the void or a chosen direction.");
        d("KeepSprint", "Keeps sprinting after an attack instead of slowing down.");
        d("PlainAura", "A minimal aura: attacks only when the rotation actually sent is on the target. The hardest to flag.");
        d("InvulnTiming", "Times attacks to the end of the target's invulnerability, and spends spare swings on someone who can be hurt.");
        d("KeepRange", "Lets go of forward during a combo (S-tap) so you do not walk into the target and waste their knockback.");
        d("AimBacktrack", "A swing that just missed lands on whoever your aim was on a few ticks ago.");
        d("KBDisplacement", "In fights by hand, silently aims your knockback at nearby hazards (void, lava).");
        // Ported from OpenSkid (2026-10-05)
        d("KeyStrokes", "Shows WASD, space and the mouse buttons (with CPS) on screen. Movable in the HUD editor.");
        d("PotionHUD", "Lists your active potion effects and their time left. Movable in the HUD editor.");
        d("InventoryHUD", "Shows your worn armour and hotbar items on screen. Movable in the HUD editor.");
        d("PlayerList", "A custom on-screen player list (ping, sorting, game-mode filter). Movable in the HUD editor.");
        d("ClosestPlayerHUD", "The nearest player of each team: distance, count, height difference, health. Movable in the HUD editor.");
        d("FKCounter", "Counts final kills per team from the chat. Movable in the HUD editor.");
        d("BedPlates", "Coloured overlays on beds and the blocks defending them.");
        d("TNTTimer", "A countdown above primed TNT.");
        d("DamageTags", "Floating damage numbers above the entities you hit.");
        d("ItemTags", "Names and counts above dropped items.");
        d("EntityCulling", "Skips drawing entities that are hidden from view, for more FPS.");
        d("Notifications", "Animated notifications in the bottom-right corner (module toggles and more), with toggle sounds. Movable in the HUD editor.");
        d("ExploitFixer", "Blocks malformed payload and chat packets you send (defensive).");
        d("HitBox", "Expands other entities' hitboxes so they are easier to hit; can draw them.");
        d("LagRange", "Delays your packets while a target is in range, for a reach advantage.");
        d("NoHitDelay", "Removes the 1.8 miss-click cooldown.");
        d("Piercing", "Lets the crosshair pass through blocks or teammates to the target behind.");
        d("ClickAssits", "Adds extra clicks while you click, for a steadier CPS.");
        d("SprintReset", "Resets sprint on a hit (W-tap and others) for more knockback.");
        d("Reach", "Increases attack reach. Easy for servers to detect; use with care.");
        d("TickBase", "Runs a few ticks ahead the moment a target comes into range, then repays them, to strike first.");
        d("TimerRange", "Briefly speeds up the game clock when approaching a target, to reach attack range first.");
        d("Velocity", "Reduces or changes the knockback you take.");
        d("Wtap", "Briefly releases forward after a hit (W-tap) to reset sprint.");
        d("AutoGapple", "Eats a golden apple when health falls below a percentage.");
        d("AutoTool", "Switches to the best tool for the block being mined.");
        // ------------------------------------------------------------ PLAYER
        d("Blink", "Holds your outgoing movement and releases it at a pace the server accepts (a teleport-like effect).");
        d("Clutch", "When knocked off a bridge or walking off an edge over the void, turns and places a block to catch you.");
        d("NoFall", "Avoids fall damage.");
        d("AntiVoid", "Pulls you back or catches you when you are about to fall into the void.");
        d("AutoSwap", "When blocks, projectiles or pearls run out, switches to the next slot of the same kind.");
        d("InvManager", "Sorts the inventory: wears the best armour, drops junk, keeps weapons, tools and blocks in set slots.");
        d("Refill", "Refills the hotbar with soup or potions while the inventory is open.");
        d("AntiAFK", "Makes small movements so the server does not kick you for being idle.");
        d("AntiFireball", "Turns and hits fireballs flying at you.");
        d("AutoAnduril", "Switches to the Andúril sword at intervals for its speed effect, then back.");
        d("InventoryClicker", "Clicks quickly over inventory slots while the mouse is held and dragged.");
        // ---------------------------------------------------------- MOVEMENT
        d("Fly", "Flight.");
        d("Speed", "Moves faster.");
        d("LongJump", "Jumps further.");
        d("Jesus", "Walk on water.");
        d("NoSlow", "No slowdown while using items (blocking, eating, drawing a bow).");
        d("NoJumpDelay", "Removes the delay between consecutive jumps.");
        d("SafeWalk", "Stops you walking off block edges, as if sneaking.");
        d("Eagle", "Sneaks automatically at edges, for bridging backwards.");
        d("InvWalk", "Move while the inventory or a menu is open.");
        d("TargetStrafe", "Circles around the target.");
        d("MoveFix", "With silent rotations, corrects movement so it matches the rotation the server sees.");
        d("Stasis", "Freezes your momentum for a moment, then lets it go.");
        d("Timer", "Changes the game clock speed (faster or slower overall).");
        // ------------------------------------------------------------- WORLD
        d("Scaffold", "Places blocks under you to bridge: towering, keep-Y and several rotation modes.");
        d("AutoBlockIn", "Walls you in with blocks: the ring around your body first, then the roof.");
        d("AutoBedDef", "Places blocks around your own bed to defend it.");
        d("AutoHeadHitter", "Keeps head-hitter jumps going when a block is above you.");
        d("BedNuker", "Breaks nearby enemy beds (and the blocks protecting them).");
        d("ChestAura", "Opens nearby chests.");
        d("ChestStealer", "Takes the useful items when a chest opens.");
        d("FastPlace", "Places blocks faster.");
        d("SpeedMine", "Mines blocks faster.");
        d("AntiObbyTrap", "When your head is stuck in an obsidian trap, treats that block as air so you can see and get out.");
        // ------------------------------------------------------------ RENDER
        d("ESP", "Draws outlines or glow on other players, visible through walls.");
        d("ESP2D", "Shows players as 2D boxes with health bar, armour bar and name.");
        d("Chams", "Shows player models through walls.");
        d("NameTags", "Larger, richer name tags: health, distance, gear and enchantments.");
        d("Tracers", "Draws lines from the screen centre to other players.");
        d("TargetESP", "Marks your current attack target.");
        d("TargetHUD", "An info panel for the current target (health, head and more).");
        d("ItemESP", "Marks dropped items (emeralds, diamonds, gold, iron).");
        d("ChestESP", "Marks chests, trapped chests and ender chests.");
        d("BedESP", "Marks beds.");
        d("Xray", "Sees through blocks to highlight ores and chosen blocks.");
        d("ViewClip", "Lets the third-person camera pass through blocks.");
        d("Trajectories", "Shows the predicted path of arrows, throwables and pearls.");
        d("BlockOverlay", "A stronger outline for the block under the crosshair.");
        d("BreakProgress", "Shows how far a block is broken.");
        d("Indicators", "Edge-of-screen warnings for fireballs, pearls, arrows and other projectiles coming at you.");
        d("Radar", "A minimap radar of nearby players' direction and distance.");
        d("LatencyCrosshair", "A second crosshair where the server still thinks you are aiming (the latency gap).");
        d("TeamHealthDisplay", "Shows teammates' or enemies' health.");
        d("RenderFixes", "Modern chat and scoreboard rendering (rounded, movable).");
        d("AntiDebuff", "Removes blindness and nausea screen effects.");
        // -------------------------------------------------------------- MISC
        d("AntiBot", "Recognises server bots (NPCs, anticheat bots) so other modules ignore them.");
        d("TargetFilter", "One shared answer to who counts as a target (friends, team, bots, invisible, distance) for combat and latency modules.");
        d("MCF", "Middle-click a player to add or remove a friend.");
        d("NickHider", "Hides your name (chat, scoreboard, level).");
        d("Spammer", "Sends chat messages on a timer.");
        d("AutoAuth", "Types the server's login / register password for you.");
        d("AutoHypixel", "Hypixel helpers: accept policies, next game and more.");
        d("BedwarUtils", "Bedwars helpers: diamond upgrades, item tracker, invisibility, pearl and approach alerts.");
        d("BedTracker", "Tracks beds; alerts when enemies near yours and can send a message.");
        d("LightningTracker", "Reports where lightning struck (often a death) in chat.");
        d("ESPDetector", "Reports players whose aim follows you through walls (likely ESP).");
        d("AntiCheat", "Reports players who hit you from too far or through blocks.");
        d("AntiObfuscate", "Removes the scrambled-text effect (§k) so obfuscated text is readable.");
        d("MouseRawInput", "Uses raw mouse input, unaffected by system mouse acceleration.");
        d("ResourceSpoofer", "Answers resource pack requests without loading them and hides modded channels.");
        d("NoRotate", "Keeps your view when the server sets your position.");
        // ----------------------------------------------------------- EXPLOIT
        d("Disabler", "A collection of anticheat bypasses, each for particular servers. Can be detected.");
        d("GhostHand", "Lets the crosshair pass through teammates to what is behind.");
        d("ClientSpoofer", "Changes the client brand reported to the server.");
        // ------------------------------------------------------------ CLIENT
        d("ClickGUIModule", "Click menu style: look, colours and window size.");
        d("GuiModule", "Opens the click menu (Right Shift by default).");
        d("RiseClickGUIModule", "A Rise-style click menu.");
        d("FlagDetector", "Detects server corrections, refused blocks and reduced damage, and works out which module caused them.");
        d("FlagResponder", "Switches off a module the server keeps correcting.");
        d("HitCheck", "Counts which swings landed and explains the misses.");
        d("FightLog", "One line per fight: hits, combos, knockback and outcome.");
        d("Debug", "One switch for all diagnostic tools.");
        d("PacketLogger", "Records packets and writes them to a file when you are kicked.");
        d("ServerFingerprint", "Records what a server reveals about itself and guesses its anticheat.");
        d("AutoTune", "Tries setting values and keeps the ones that measure better.");
        d("Adaptive", "Learns over hours which modules this server objects to and turns them down.");
        d("ServerProfiles", "Loads the saved config that matches the server you joined.");
        d("LatencyGovernor", "Makes latency-sensitive settings more cautious when the connection gets worse.");
        d("Panic", "One key that switches every behaviour module off and releases anything held back.");
        d("Rotations", "Shared rotation engine settings: speed variation, curved paths and easing near the target.");
        // -------------------------------------------------------------- LEGIT
        d("HUD", "The on-screen module list and interface look (colours, background, blur, glow, fonts).");
        d("Hotbar", "A custom look for the hotbar.");
        d("FPScounter", "Shows FPS on screen.");
        d("WaterMark", "The client watermark on screen.");
        d("WaterMark2", "Another watermark style.");
        d("DynamicIsland", "A Dynamic Island style status bar (state, notifications).");
        d("Statistics", "Time, kills and wins for this connection.");
        d("PlayTracker", "A small HUD: time played and games played (with today's totals and wins/losses), and a warning when hits look damage-cut.");
        d("Ambience", "Changes world time and weather (only for you).");
        d("FullBright", "Full brightness, even in the dark.");
        d("NoHurtCam", "No (or less) screen shake when hurt.");
        d("Sprint", "Sprints automatically.");
        d("FreeLook", "Look around freely while a key is held, without turning your body.");
        d("ItemPhysics", "Dropped items lie flat and tumble in the air.");
        d("Capes", "Shows custom capes.");
        d("Animations", "Custom sword swing and blocking animations.");
        d("HitParticleEffects", "Particles on hits; an optional sound on kills.");
        d("LegitHUD", "Coordinates, FPS, ping, CPS and keystrokes.");
        d("ArmorHUD", "Shows worn armour and durability.");
        d("EffectsHUD", "Shows active potion effects and their time left.");
        d("AutoRespawn", "Respawns automatically after death.");
        // ------------------------------------------------------------- THEME
        d("Theme", "Theme master switch and main colours; groups can follow it or be coloured on their own.");
        d("PlayerColors", "Theme group: player ESP and 2D ESP colours.");
        d("TracerColors", "Theme group: tracer line and arrow colours.");
        d("TargetColors", "Theme group: attack target marker colours.");
        d("BacktrackColors", "Theme group: the colour of Backtrack's real-position box.");
        d("BedColors", "Theme group: bed ESP colours.");
        d("ChestColors", "Theme group: chest ESP colours.");
        d("ItemColors", "Theme group: dropped item ESP colours.");
        d("BlockColors", "Theme group: crosshair block outline colours.");
        d("ProjectileColors", "Theme group: trajectory and incoming projectile colours.");
        d("InterfaceColors", "Theme group: HUD module list and interface colours.");
        d("ChamsColors", "Theme group: Chams model colours.");
        d("NameTagColors", "Theme group: name tag background and border colours.");
        d("WidgetColors", "Theme group: hotbar, radar and other widget colours.");
        d("EffectColors", "Theme group: hit particle colours.");

        // ========================================================== HEADINGS
        heading("一般", "General");
        heading("目標", "Targets");
        heading("出手", "Attacking");
        heading("格擋", "Blocking");
        heading("轉頭", "Rotations");
        heading("Hypixel 轉頭", "Hypixel rotations");
        heading("LiquidBounce 轉頭", "LiquidBounce rotations");
        heading("Advanced 轉頭", "Advanced rotations");
        heading("顯示與除錯", "Display & debug");
        heading("觸發", "Trigger");
        heading("放置", "Placing");
        heading("物品", "Items");
        heading("其他", "Other");
        heading("移動", "Movement");
        heading("疊高與高度", "Tower & height");
        heading("顯示", "Display");
        heading("模式與比例", "Mode & amounts");
        heading("Reduce 模式", "Reduce mode");
        heading("Jump / Rotate", "Jump / Rotate");
        heading("依延遲調整（毫秒）", "By latency (ms)");
        heading("顏色", "Colours");
        heading("位置與大小", "Position & size");
        heading("背景與特效", "Background & effects");
        heading("模組清單", "Module list");
        heading("字型與資訊", "Font & info");
        heading("通知", "Notifications");
        heading("對象", "Who");
        heading("一般樣式", "Default style");
        heading("Raven 樣式", "Raven style");
        heading("基本檢查", "Basic checks");
        heading("行為檢查", "Behaviour checks");
        heading("血量檢查", "Health checks");
        heading("重複檢查", "Duplicate checks");
        heading("處理", "Handling");
        heading("Grim / 通用", "Grim / general");
        heading("取消封包", "Cancel packets");
        heading("Watchdog / 其他伺服器", "Watchdog / other servers");
        heading("重送與除錯", "Resend & debug");
        heading("模式與時鐘", "Mode & clock");
        heading("距離", "Range");
        heading("預測", "Prediction");
        heading("限制", "Limits");
        heading("自動格擋", "Auto block");
        heading("受傷時機", "Hurt timing");
        heading("節奏", "Rhythm");
        heading("樣式", "Style");
        heading("顯示條件", "When shown");
        heading("方框", "Box");
        heading("血量", "Health");
        heading("護甲", "Armour");
        heading("名字", "Names");
        heading("扣住", "Holding");
        heading("釋放", "Releasing");
        heading("拉回偵測", "Corrections");
        heading("歸因", "Blame");
        heading("方塊與傷害", "Blocks & damage");
        heading("提示", "Alerts");
        heading("資訊 HUD", "Info HUD");
        heading("床追蹤 HUD", "Bed tracker HUD");
        heading("提醒", "Alerts");
        heading("巨集", "Macro");
        heading("礦物", "Ores");
        heading("追蹤線", "Tracers");
        heading("時機", "Timing");
        heading("整理", "Sorting");
        heading("固定格子", "Fixed slots");
        heading("HUD 顯示", "HUD");
        heading("背景", "Background");

        // ======================================================= SHARED HELP
        help("move-fix", "Movement fix: with silent rotations, keeps movement in line with the rotation the server sees, avoiding movement flags.");
        help("movefix", "Movement fix: with silent rotations, keeps movement in line with the rotation the server sees, avoiding movement flags.");
        help("swing", "Swing the arm when acting. Vanilla swings on every attack and placement.");
        help("range", "Range in blocks.");
        help("fov", "Only targets within this angle in front of the crosshair; 360 is all around.");
        help("teams", "Skip teammates.");
        help("ignore-teammates", "Skip teammates.");
        help("bot-check", "Skip players AntiBot marks as bots.");
        help("botcheck", "Skip players AntiBot marks as bots.");
        help("weapons-only", "Only while holding a weapon.");
        help("weapon-only", "Only while holding a weapon.");
        help("allow-tools", "Axes, pickaxes and other tools count as weapons too.");
        help("show-target", "Mark the target.");
        help("min-cps", "Lowest clicks per second (each click is random between the two).");
        help("max-cps", "Highest clicks per second (each click is random between the two).");
        help("min-delay", "Shortest delay (low end of the random range).");
        help("max-delay", "Longest delay (high end of the random range).");
        help("open-delay", "Wait this long after a screen opens before acting.");
        help("delay", "Delay.");
        help("chance", "Chance to trigger (%).");
        help("mode", "How it works.");
        help("speed", "Speed.");
        help("rotations", "How it turns.");
        help("require-press", "Only while the matching key is held.");
        help("chat", "Messages in chat.");
        help("sound", "Play a sound when it happens.");
        help("log-file", "Also write a log file under config/Myau.");
        help("log", "Write a log file under config/Myau.");
        help("debug", "Debug messages.");
        help("hud", "Show the HUD panel.");
        help("hud-x", "HUD horizontal position.");
        help("hud-y", "HUD vertical position.");
        help("x", "Horizontal position.");
        help("y", "Vertical position.");
        help("position-x", "Horizontal position.");
        help("position-y", "Vertical position.");
        help("offset-x", "Horizontal fine-tune.");
        help("offset-y", "Vertical fine-tune.");
        help("scale", "Size.");
        help("color", "Colour.");
        help("opacity", "Opacity.");
        help("bg-alpha", "Background opacity.");
        help("background-alpha", "Background opacity.");
        help("background", "Show a background.");
        help("shadow", "Text shadow.");
        help("outline", "Outline.");
        help("players", "Include players.");
        help("friends", "Include friends.");
        help("enemies", "Include enemies.");
        help("self", "Include yourself.");
        help("bots", "Include bots.");
        help("mobs", "Include mobs.");
        help("animals", "Include animals.");
        help("cooldown", "Cooldown: after triggering, wait this long before it can trigger again.");
        help("item-spoof", "The server sees you switch to blocks; your hand on screen does not change.");
        help("keep-y", "Bridge at one height instead of building up.");
        help("through-walls", "Allow acting through blocks.");
        help("throughwalls", "Allow acting through blocks.");
        help("alerts", "Turn alerts on.");
        help("alerts-range", "Alert distance.");
        help("alerts-sound", "Play a sound with alerts.");
        help("macro", "Send the set message when triggered.");
        help("macro-text", "The text the macro sends.");
        help("macro-delay", "Shortest gap between two macro messages.");
        help("dry-run", "Only report what it would do, without doing it.");
        help("ping-aware", "Adjust timings to your latency.");
        help("auto-switch", "Switch to the right item's slot when needed.");
        help("switch-back", "Switch back to the previous slot afterwards.");
        help("multi-place", "Place up to four blocks in one tick. Grim flags this (MultiPlace); off by default.");

        // ======================================================= MODULE HELP
        String ka = "KillAura";
        help(ka, "Mode", "Single keeps one target; Switch moves to the next one every SwitchDelay.");
        help(ka, "Sort", "Target priority: distance, health, hurt time, or nearest the crosshair.");
        help(ka, "SwitchDelay", "How often the target is re-chosen (ms); in Switch mode also the switch interval.");
        help(ka, "CPS Mode", "Normal: random between MinCPS and MaxCPS. Record: a recorded human click rhythm (about 9.8 CPS).");
        help(ka, "MinCPS", "Lowest attacks per second (random between the two; ticks cap it at 20).");
        help(ka, "MaxCPS", "Highest attacks per second.");
        help(ka, "AttackRange", "Distance at which it actually attacks (blocks). Vanilla is 3.0.");
        help(ka, "SwingRange", "Distance at which it starts turning and swinging; a little more than the attack range.");
        help(ka, "RequirePress", "Only attack while the attack key is held.");
        help(ka, "AllowMining", "With the crosshair on a block and the attack key held, mine instead of attacking.");
        help(ka, "InventoryCheck", "No attacks while an inventory or chest screen is open.");
        help(ka, "auto-block", "How it blocks. LEGIT: hit and raise the sword, lower it next tick, like a real block hit.");
        help(ka, "AutoBlockCPS", "Attack speed while blocking.");
        help(ka, "AutoBlockRange", "Raise the sword when the target is within this distance.");
        help(ka, "AutoBlockRequirePress", "Only block while the use key is held.");
        help(ka, "AttackTick", "With SWAP blocking: the tick on which NoSlow's No Attack holds the hit.");
        help(ka, "Rotations", "Silent: the server sees you turn, your view does not. Legit: your view turns too. LockView: locks the view.");
        help(ka, "MoveFix", "Corrects movement during silent rotations to avoid movement flags.");
        help(ka, "SmoothBack", "With no target or when switched off, turns the rotation the server sees back to your view smoothly instead of jumping.");
        help(ka, "AimMode", "SMOOTHSTEP: fast far away, slow close, always arrives. LEGACY: the old proportional smoothing.");
        help(ka, "MinTurnSpeed", "Fewest degrees per tick when nearly on target.");
        help(ka, "MaxTurnSpeed", "Most degrees per tick when far off (also the speed of SmoothBack).");
        help(ka, "Multipoint", "Aim at the point of the hitbox nearest the crosshair rather than its centre: the least turning.");
        help(ka, "TurnAccel", "How much faster the turn may get each tick, building up like a hand.");
        help(ka, "Smoothing", "LEGACY mode's smoothing.");
        help(ka, "AngleStep", "LEGACY mode's most degrees per tick.");
        help(ka, "AimLead", "Aim ahead along the target's movement by your latency (0 = no lead; 0 recommended on Grim).");
        help(ka, "AimLeadCap", "Largest lead in blocks.");
        help(ka, "FOV", "Only attack targets within this angle in front of the crosshair.");
        help(ka, "ThroughWalls", "Allow attacking targets behind blocks.");
        help(ka, "KBDisplace", "Advanced only: aim the knockback at hazards. SAFE only while the hit still lands.");
        help(ka, "ShowTarget", "Draw a box on the target.");
        help(ka, "Debug", "Health: show each of your own health changes in chat.");

        String cl = "Clutch";
        help(cl, "trigger", "AUTO: catches whenever a fall is coming. HOLD: only while hold-key is held.");
        help(cl, "hold-key", "The key to hold when trigger is HOLD.");
        help(cl, "edge-fall", "Without a hit, how far toward the void before it acts (a small hop is not a fall).");
        help(cl, "void-only", "Only catch over the void.");
        help(cl, "safe-drop", "No catch when there is ground below within this height.");
        help(cl, "combat-window", "How long after a hit (ms) a fall counts as being knocked off.");
        help(cl, "reach", "Farthest a block is placed.");
        help(cl, "place-interval", "At least this many ticks between placements.");
        help(cl, "safe-mode", "Safe mode: click along the look the server already has first; make big turns a tick early when the fall allows. Chain pace unchanged. Off = turn and click in the same tick, as before.");
        help(cl, "early-turn", "A turn bigger than this, when the fall leaves a tick to spare, is made a tick early and clicked the next tick (the server sees the look first). With no tick to spare it turns and clicks in one tick as before, so nothing gets slower. 180 = off.");
        help(cl, "chain", "When a cell cannot be reached directly, build a short bridge to it first.");
        help(cl, "chain-length", "Longest bridge in blocks.");
        help(cl, "keep-y", "Never places above the layer you stood on before the fall.");
        help(cl, "extra-block", "When about to land on a block's edge, also places one under the middle of your feet.");
        help(cl, "humanize", "Random click spot on the face (normal distribution); turns use the Rotations engine's speed variation and curve.");
        help(cl, "ease", "Slow the last part of a turn (costs ticks; a catch may be missed slightly more often).");
        help(cl, "rotation-random", "How random (how far the click spot spreads on the face).");
        help(cl, "speed", "Fewest degrees per tick.");
        help(cl, "max-speed", "Most degrees per tick.");
        help(cl, "pre-aim", "Turn roughly toward the spot before it is time, so less turning is left.");
        help(cl, "counter-knockback", "Walk back when knocked, cancelling part of the knockback.");
        help(cl, "auto-ladder", "Try a ladder when no block can be placed.");
        help(cl, "only-on-depletion", "Only switch slots when the blocks in hand run out.");
        help(cl, "pause-autoclicker", "Pause AutoClicker during a catch.");
        help(cl, "disable-after", "Switch Clutch off after one catch.");

        String rt = "Rotations";
        help(rt, "speed-noise", "How much turn speed varies (standard deviation, % of each step).");
        help(rt, "curve", "How far a turn bows off a straight line (% of each step). 0 = straight.");
        help(rt, "curve-smooth", "How smooth the bow is: higher carries more of each tick's offset into the next.");
        help(rt, "ease", "Slow down near the target instead of stopping dead at full speed.");
        help(rt, "ease-zone", "Start slowing within this many degrees.");
        help(rt, "ease-floor", "While slowing, still at least this many degrees per tick, so it always arrives.");

        String pt = "PlayTracker";
        help(pt, "show-today", "Also show today's totals.");
        help(pt, "show-wins", "Show wins and losses.");
        help(pt, "show-mitigation", "An extra line when hits look damage-cut or keep being ignored (needs FlagDetector).");
        help(pt, "mitigation-always", "Show \"no damage cut\" the rest of the time too.");

        String fd = "FlagDetector";
        help(fd, "detect-rejects", "Report blocks the server takes back, blamed on the module that placed them.");
        help(fd, "detect-low-damage", "Detect hits that land but do too little (damage cut), and runs of ignored hits.");
        help(fd, "damage-chat", "Show each hit's damage and vanilla's least possible damage in chat.");
        help(fd, "blame", "Show which module most likely caused a flag.");
        help(fd, "blame-window", "Seconds of module actions looked at when blaming.");
        help(fd, "min-distance", "Corrections shorter than this are not reported.");
        help(fd, "group-bursts", "Merge corrections close together into one line.");
    }
}

package myau.module;

import myau.property.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the menus say about each module and its settings, in one place
 * (2026-10-04, asked for: "每個功能都要做說明", and the settings tidied).
 *
 *   DESCRIPTIONS  a short Chinese description for every registered module,
 *                 shown over the module's own (110 of 161 had none).
 *   GROUPS        for the modules with the longest lists, headings and an
 *                 order: settings are listed under the first heading whose
 *                 patterns match their key ("x*" a prefix, otherwise the
 *                 exact key, case ignored); anything unmatched goes first,
 *                 under "一般". Other modules keep their own order, with
 *                 runs of hud-/alerts-/macro- settings gathered under one
 *                 heading each.
 *   HELP          the hint line for a setting: per module where a key means
 *                 something particular, else a meaning shared by every module
 *                 that uses the same key (range, fov, move-fix, ...).
 *
 * Presentation only: no key changes, so every saved config still loads.
 * Applied once at start-up, after the settings are found (Myau.java).
 */
public final class ModuleDocs {

    private static final Map<String, String> DESCRIPTIONS = new HashMap<String, String>();
    private static final Map<String, String[][]> GROUPS = new HashMap<String, String[][]>();
    private static final Map<String, String> HELP = new HashMap<String, String>();
    private static final Map<String, String> MODULE_HELP = new HashMap<String, String>();
    /* Key prefixes gathered under a heading in modules without a GROUPS entry. */
    private static final String[][] AUTO = {
            {"HUD 顯示", "hud", "tracker-hud"},
            {"提醒", "alerts"},
            {"巨集", "macro"},
            {"Raven 樣式", "raven"},
            {"背景", "bg-"},
    };

    private ModuleDocs() {
    }

    /* The menu's language: Atlas sets it from its Appearance page each frame. */
    private static volatile boolean english;

    public static boolean isEnglish() {
        return english;
    }

    public static void setEnglish(boolean value) {
        english = value;
    }

    /** A settings heading in the menu's language. */
    public static String heading(String group) {
        if (group == null || !english) {
            return group;
        }
        String en = ModuleDocsEn.HEADINGS.get(group);
        return en != null ? en : group;
    }

    /** Sets the description, headings and hints, and returns the settings in menu order. */
    public static List<Property<?>> apply(Module module, List<Property<?>> properties) {
        String key = module.getClass().getSimpleName();
        String description = DESCRIPTIONS.get(key);
        if (description != null) {
            module.setShownDescription(description);
        }
        String descriptionEn = ModuleDocsEn.DESCRIPTIONS.get(key);
        if (descriptionEn != null) {
            module.setShownDescriptionEn(descriptionEn);
        }
        for (Property<?> property : properties) {
            String name = norm(property.getName());
            String help = MODULE_HELP.get(key + ":" + name);
            if (help == null) {
                help = HELP.get(name);
            }
            if (help != null) {
                property.setHelp(help);
            }
            String helpEn = ModuleDocsEn.MODULE_HELP.get(key + ":" + name);
            if (helpEn == null) {
                helpEn = ModuleDocsEn.HELP.get(name);
            }
            if (helpEn != null) {
                property.setHelpEn(helpEn);
            }
        }
        String[][] spec = GROUPS.get(key);
        return spec != null ? grouped(properties, spec) : autoGrouped(properties);
    }

    private static List<Property<?>> grouped(List<Property<?>> properties, String[][] spec) {
        List<List<Property<?>>> buckets = new ArrayList<List<Property<?>>>();
        for (int i = 0; i < spec.length; i++) {
            buckets.add(new ArrayList<Property<?>>());
        }
        List<Property<?>> rest = new ArrayList<Property<?>>();
        for (Property<?> property : properties) {
            int at = match(spec, property.getName());
            if (at < 0) {
                rest.add(property);
            } else {
                property.setGroup(spec[at][0]);
                buckets.get(at).add(property);
            }
        }
        List<Property<?>> out = new ArrayList<Property<?>>();
        for (Property<?> property : rest) {
            property.setGroup("一般");
            out.add(property);
        }
        for (List<Property<?>> bucket : buckets) {
            out.addAll(bucket);
        }
        return out;
    }

    private static List<Property<?>> autoGrouped(List<Property<?>> properties) {
        Map<String, List<Property<?>>> clusters = new LinkedHashMap<String, List<Property<?>>>();
        for (Property<?> property : properties) {
            int at = match(AUTO, property.getName(), true);
            String title = at < 0 ? null : AUTO[at][0];
            List<Property<?>> cluster = clusters.get(title);
            if (cluster == null) {
                cluster = new ArrayList<Property<?>>();
                clusters.put(title, cluster);
            }
            cluster.add(property);
        }
        /* Ungrouped first (a lone match is not a section), then each section. */
        List<Property<?>> plain = new ArrayList<Property<?>>();
        List<Property<?>> sections = new ArrayList<Property<?>>();
        for (Map.Entry<String, List<Property<?>>> cluster : clusters.entrySet()) {
            if (cluster.getKey() == null || cluster.getValue().size() < 2) {
                plain.addAll(cluster.getValue());
                continue;
            }
            for (Property<?> property : cluster.getValue()) {
                property.setGroup(cluster.getKey());
            }
            sections.addAll(cluster.getValue());
        }
        /* Keep the module's own order among the ungrouped ones. */
        List<Property<?>> ordered = new ArrayList<Property<?>>();
        for (Property<?> property : properties) {
            if (plain.contains(property)) {
                ordered.add(property);
            }
        }
        if (!sections.isEmpty()) {
            for (Property<?> property : ordered) {
                property.setGroup("一般");
            }
        }
        ordered.addAll(sections);
        return ordered;
    }

    private static int match(String[][] spec, String name) {
        return match(spec, name, false);
    }

    /** The first heading with a pattern for this key; prefixOnly treats every pattern as a prefix. */
    private static int match(String[][] spec, String name, boolean prefixOnly) {
        String key = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < spec.length; i++) {
            for (int j = 1; j < spec[i].length; j++) {
                String pattern = spec[i][j].toLowerCase(Locale.ROOT);
                if (pattern.startsWith("*")) {
                    if (key.endsWith(pattern.substring(1))) {
                        return i;
                    }
                } else if (prefixOnly || pattern.endsWith("*")) {
                    String prefix = pattern.endsWith("*") ? pattern.substring(0, pattern.length() - 1) : pattern;
                    if (key.startsWith(prefix)) {
                        return i;
                    }
                } else if (key.equals(pattern)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** A key with case, dashes, underscores and spaces taken out, for the HELP tables. */
    static String norm(String name) {
        return name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
    }

    private static void d(String module, String text) {
        DESCRIPTIONS.put(module, text);
    }

    private static void g(String module, String[]... groups) {
        GROUPS.put(module, groups);
    }

    private static String[] h(String title, String... patterns) {
        String[] out = new String[patterns.length + 1];
        out[0] = title;
        System.arraycopy(patterns, 0, out, 1, patterns.length);
        return out;
    }

    private static void help(String key, String text) {
        HELP.put(norm(key), text);
    }

    private static void help(String module, String key, String text) {
        MODULE_HELP.put(module + ":" + norm(key), text);
    }

    static {
        // ------------------------------------------------------------ COMBAT
        d("KillAura", "自動攻擊範圍內的目標：選目標、轉頭（多種模式，可靜默）、依 CPS 出手，並可自動格擋。");
        d("AimAssist", "攻擊時把準心輕微往附近目標拉，手感接近真人微調，不會自己出手。");
        d("AutoClicker", "按住左鍵時自動連點，CPS 在設定範圍內隨機，可順便格擋或挖方塊。");
        d("KnockbackDelay", "把收到的擊退延後一小段時間才吃下去，讓被打飛的時機變晚。");
        d("AutoHeal", "血量低時自動使用治療物品（湯、藥水、頭）。");
        d("BackTrack", "對手往後退時，暫時把他留在你最後看到的位置，讓攻擊還能打到伺服器記錄中的位置。");
        d("Hitflick", "打中目標的瞬間把視角甩開，再轉回來（偏移擊退方向）。");
        d("ServerLag", "暫時攔下伺服器送來的部分封包，模擬伺服器延遲。");
        d("FakeLag", "延後送出自己的移動，讓伺服器上的你落後於實際位置，方便先手。");
        d("HitSelect", "挑出手時機：只在對自己有利（例如減少吃擊退）的時候讓攻擊送出。ACTIVE 也會管 KillAura 的攻擊。");
        d("MoreKB", "攻擊時重置衝刺，讓每一下打出更多擊退。");
        d("Criticals", "讓攻擊判定為爆擊（多 50% 傷害），以封包或小跳實現。");
        d("FastBow", "加快拉弓射箭的速度。");
        d("BlockHit", "攻擊之間自動舉劍格擋（block hit）。Manual：照你的點擊，打完舉劍一下。Predict：在你快能再被打到之前舉劍、被打到就放下。Helper / Auto / Lag：其他方式。");
        help("BlockHit", "ManualChance", "每次打中後舉劍一下的機率（每秒格擋次數 = CPS × 機率）。KillAura 自己有格擋時不作用。");
        d("ThrowAura", "自動把雪球、蛋等投擲物丟向敵人。");
        d("Displace", "攻擊瞬間轉向，把對手的擊退方向導向虛空或指定方向。");
        d("KeepSprint", "攻擊後不讓衝刺被打斷，保持移動速度。");
        d("PlainAura", "精簡版自動攻擊：只在實際送出的視角真的對準目標時才出手，最不容易被抓。");
        d("InvulnTiming", "算準目標無敵時間結束的時機再出手，並把多餘的攻擊轉給打得動的人。");
        d("KeepRange", "連擊中自動放開前進（S-tap），避免貼太近浪費對手的擊退。");
        d("AimBacktrack", "剛好揮空的一刀，改算到前幾個 tick 準心對到的人身上。");
        d("KBDisplacement", "手動打人時，靜默把擊退方向轉向附近的危險（虛空、岩漿）。");
        // Ported from OpenSkid (2026-10-05)
        d("KeyStrokes", "在畫面上顯示 WASD、空白鍵和滑鼠按鍵（含 CPS）。可在 HUD 編輯器拖曳。");
        d("PotionHUD", "在畫面上列出目前的藥水效果和剩餘時間。可在 HUD 編輯器拖曳。");
        d("InventoryHUD", "在畫面上顯示身上的裝備和快捷欄物品。可在 HUD 編輯器拖曳。");
        d("PlayerList", "在畫面上顯示自訂的玩家列表（可顯示延遲、排序、篩選模式）。可在 HUD 編輯器拖曳。");
        d("ClosestPlayerHUD", "顯示每一隊離你最近的玩家：距離、人數、高低差、血量。可在 HUD 編輯器拖曳。");
        d("FKCounter", "從聊天訊息統計每一隊的最終擊殺數（Final Kill）。可在 HUD 編輯器拖曳。");
        d("BedPlates", "在床上顯示彩色標示，以及床周圍的防禦方塊。");
        d("TNTTimer", "在點燃的 TNT 上方顯示倒數秒數。");
        d("DamageTags", "打到實體時，在它上方飄出傷害數字。");
        d("ItemTags", "在掉落物上方顯示物品名稱和數量。");
        d("EntityCulling", "不繪製被擋住、看不到的實體，提高 FPS。");
        d("Notifications", "右下角顯示動畫通知（模組開關等），可設定開關音效。可在 HUD 編輯器拖曳。");
        d("ExploitFixer", "擋下你自己送出的異常資料包和聊天封包（防禦用）。");
        d("HitBox", "放大其他實體的碰撞箱，比較容易打中，可選擇顯示放大的範圍。");
        d("LagRange", "對手在範圍內時暫時延遲自己的封包，製造距離優勢。");
        d("NoHitDelay", "移除攻擊後的點擊冷卻（1.8 揮空延遲）。");
        d("Piercing", "準心可以穿過方塊或隊友，直接選中後面的目標。");
        d("ClickAssits", "在你點擊時額外補點，讓 CPS 更穩定。");
        d("SprintReset", "打中時自動重置衝刺（W-tap 等），增加擊退。");
        d("Reach", "增加攻擊距離（伺服器容易偵測，慎用）。");
        d("TickBase", "操控 tick 時序，在進入攻擊範圍的瞬間先跑幾個 tick 搶先手。");
        d("TimerRange", "接近目標時短暫加速遊戲時鐘，搶先進入攻擊距離。");
        d("Velocity", "減少或改變自己吃到的擊退。");
        d("Wtap", "打中後真的放開 W 鍵再按回去（W-tap），由遊戲自己重置衝刺，下一下擊退更大。");
        help("Wtap", "chance", "打中後有多少機率 W-tap。");
        help("Wtap", "release-delay", "打中後幾毫秒放開 W（每次隨機 ±20%）。");
        help("Wtap", "re-press-delay", "W 放開多久再按回去（毫秒，每次隨機 ±20%）。");
        help("Wtap", "select-hits", "只在這一下打得出傷害時才 W-tap（對方大部分無敵時間已過）。");
        d("AutoGapple", "血量低於設定百分比時自動吃金蘋果。");
        d("AutoTool", "挖方塊時自動切到最適合的工具。");
        // ------------------------------------------------------------ PLAYER
        d("Blink", "暫時扣住自己送出的移動封包，之後以伺服器能接受的節奏放出（瞬移般的效果）。");
        d("Clutch", "被打下橋或走到虛空邊緣時，自動轉頭放方塊把自己接住。");
        d("NoFall", "避免摔落傷害。");
        d("AntiVoid", "快掉進虛空時自動把你拉回或接住。");
        d("AutoSwap", "方塊、投擲物、珍珠等用完時，自動換到同類物品的格子。");
        d("InvManager", "自動整理背包：穿最好的裝備、丟垃圾、把武器工具方塊放到固定格子。");
        d("Refill", "打開背包時，自動把湯或藥水補到快捷欄。");
        d("AntiAFK", "自動做一些小動作，避免被伺服器判定掛機踢出。");
        d("AntiFireball", "自動轉頭打掉飛向你的火球。");
        d("AutoAnduril", "定時切到 Andúril 劍拿速度效果，再切回原本的物品。");
        d("InventoryClicker", "在背包畫面按住滑鼠拖過格子時，自動快速點擊。");
        // ---------------------------------------------------------- MOVEMENT
        d("Fly", "飛行。");
        d("Speed", "加快移動速度。");
        d("LongJump", "跳得更遠。");
        d("Jesus", "可以在水面上行走。");
        d("NoSlow", "使用物品（格擋、吃東西、拉弓）時不減速。");
        help("HUD", "hud-theme", "所有 HUD 的外觀。JELLO：LiquidBounce JelloBounce 主題（黑 45%、柔和陰影、圓角卡片、模組清單貼齊）。CLASSIC：各 HUD 原本的畫法。");
        d("NoItemRelease", "放開右鍵時不送出「停止使用物品」封包，伺服器仍當你在使用；本機照常停止（來源：Slinky）。");
        help("NoItemRelease", "safe-release", "一移動或疾跑，就在該 tick 的移動封包前補送放開封包（避免 Grim NoSlow）；關掉則一直不送。");
        d("NoJumpDelay", "移除連續跳躍之間的延遲。");
        d("SafeWalk", "走到方塊邊緣時不會掉下去（像一直按著潛行）。");
        d("Eagle", "走到邊緣時自動潛行，用來後退搭橋。");
        d("InvWalk", "開著背包或選單時也能移動。");
        d("TargetStrafe", "繞著目標轉圈移動。");
        d("MoveFix", "靜默轉頭時修正移動方向，讓移動跟伺服器看到的視角一致。");
        d("Stasis", "短暫凍結自己的動量，之後再放開。");
        d("Timer", "改變遊戲時鐘速度（整體加速或減速）。");
        // ------------------------------------------------------------- WORLD
        d("Scaffold", "自動在腳下放方塊搭橋，支援疊高（tower）、保持高度與多種轉頭模式。");
        d("AutoBlockIn", "自動用方塊把自己圍起來：先圍身體一圈，再補屋頂。");
        d("AutoBedDef", "自動在自己的床周圍放方塊防守。");
        d("AutoHeadHitter", "跳躍時頭頂碰到方塊會自動調整，維持頂頭跳加速。");
        d("BedNuker", "自動挖附近敵方的床（可以先挖外圍的保護方塊）。");
        d("ChestAura", "自動打開附近的箱子。");
        d("ChestStealer", "打開箱子時自動把有用的東西拿走。");
        d("FastPlace", "加快放方塊的速度。");
        d("SpeedMine", "加快挖方塊的速度。");
        d("AntiObbyTrap", "頭卡在黑曜石等陷阱方塊裡時，把那格當成空氣，讓你看得到、出得去。");
        // ------------------------------------------------------------ RENDER
        d("ESP", "在其他玩家身上畫外框或光暈，隔牆也看得到。");
        d("ESP2D", "以 2D 方框顯示玩家，附血條、護甲條與名字。");
        d("Chams", "隔牆顯示玩家模型。");
        d("NameTags", "放大並強化名牌：顯示血量、距離、裝備與附魔。");
        d("Tracers", "從畫面中央畫線指向其他玩家。");
        d("TargetESP", "在目前的攻擊目標身上畫標記。");
        d("TargetHUD", "顯示目前目標的資訊面板（血量、頭像等）。");
        d("ItemESP", "標出地上掉落的物品（綠寶石、鑽石、金、鐵）。");
        d("ChestESP", "標出箱子、陷阱箱和終界箱的位置。");
        d("BedESP", "標出床的位置。");
        d("Xray", "透視方塊，標出礦物和指定方塊。");
        d("ViewClip", "第三人稱視角時鏡頭可以穿過方塊。");
        d("Trajectories", "顯示弓箭、投擲物、珍珠的預測軌跡。");
        d("BlockOverlay", "強化準心指著的方塊外框。");
        d("BreakProgress", "顯示方塊被挖的進度。");
        d("Indicators", "在畫面邊緣提示飛向你的火球、珍珠、箭等投擲物。");
        d("Radar", "小地圖雷達，顯示附近玩家的方向與距離。");
        d("LatencyCrosshair", "多畫一個準心，標出伺服器目前認為你瞄準的位置（延遲造成的落差）。");
        d("TeamHealthDisplay", "顯示隊友或敵人的血量。");
        d("RenderFixes", "較現代的聊天框與計分板外觀（圓角、可移動）。");
        d("AntiDebuff", "移除失明、反胃等畫面效果。");
        // -------------------------------------------------------------- MISC
        d("AntiBot", "辨識伺服器的假人（NPC、反作弊機器人），讓其他模組略過它們。");
        d("TargetFilter", "統一判斷「誰算目標」（好友、隊友、假人、隱身、距離），給戰鬥與延遲類模組共用。");
        d("MCF", "中鍵點玩家加入或移除好友。");
        d("NickHider", "隱藏自己的名字（聊天、計分板、等級）。");
        d("Spammer", "定時在聊天欄發送訊息。");
        d("AutoAuth", "自動輸入伺服器的登入／註冊密碼。");
        d("AutoHypixel", "Hypixel 小工具：自動接受規範、自動下一場等。");
        d("BedwarUtils", "Bedwars 輔助：鑽石升級、物品追蹤、隱身提醒、珍珠／接近提醒等 HUD。");
        d("BedTracker", "追蹤床的狀態，敵人靠近自己的床時提醒，可自動發訊息。");
        d("LightningTracker", "在聊天欄報出閃電落下的座標（常代表有人死亡）。");
        d("ESPDetector", "偵測隔牆一直把準心跟著你的玩家（疑似開 ESP）。");
        d("AntiCheat", "偵測從過遠距離或隔牆打你的玩家並回報。");
        d("AntiObfuscate", "移除文字的亂碼效果（§k），讓被混淆的字看得清楚。");
        d("MouseRawInput", "使用原始滑鼠輸入，不受系統滑鼠加速影響。");
        d("ResourceSpoofer", "回應伺服器的材質包要求但不真的載入，並隱藏模組專用的頻道。");
        d("NoRotate", "伺服器拉回你的位置時，不讓它改掉你的視角。");
        // ----------------------------------------------------------- EXPLOIT
        d("Disabler", "針對特定反作弊的繞過手段集合（多數只對特定伺服器有效，可能被抓）。");
        d("GhostHand", "準心可以穿過隊友，直接點到後面的東西。");
        d("ClientSpoofer", "偽裝回報給伺服器的客戶端品牌。");
        // ------------------------------------------------------------ CLIENT
        d("ClickGUIModule", "點擊選單的樣式設定：外觀、顏色、視窗大小。");
        d("GuiModule", "開啟點擊選單（預設右 Shift）。");
        d("FlagDetector", "偵測伺服器的拉回、方塊被退回、傷害被減，並推斷是哪個模組造成的。");
        d("FlagResponder", "某個模組一直被伺服器拉回時，自動把它關掉。");
        d("HitCheck", "統計哪幾刀真的有打到，並解釋沒打到的原因。");
        d("FightLog", "每場對戰記一行：命中、連擊、擊退、勝負。");
        d("Debug", "所有診斷工具的總開關。");
        d("PacketLogger", "記錄封包，被踢出時寫成檔案方便查原因。");
        d("ServerFingerprint", "記錄伺服器透露的資訊，推測它用哪種反作弊。");
        d("AutoTune", "自動試不同的設定值，保留實測表現較好的。");
        d("Adaptive", "長時間學習這個伺服器會抓哪些模組，逐步把它們調弱。");
        d("ServerProfiles", "依照進入的伺服器自動載入對應的設定檔。");
        d("LatencyGovernor", "網路變差時，自動把對延遲敏感的設定調保守。");
        d("Panic", "一鍵關掉所有功能模組，並放出所有被扣住的封包。");
        d("Rotations", "共用轉頭引擎的參數：速度隨機、弧線路徑、接近目標時減速。");
        // -------------------------------------------------------------- LEGIT
        d("HUD", "畫面上的模組清單與介面外觀（顏色、背景、模糊、光暈、字型）。");
        d("Hotbar", "自訂快捷欄的外觀。");
        d("FPScounter", "在畫面上顯示 FPS。");
        d("WaterMark", "畫面上的客戶端浮水印。");
        d("WaterMark2", "另一種樣式的浮水印。");
        d("DynamicIsland", "仿動態島的資訊條（目前狀態、通知）。");
        d("Statistics", "這次連線的時間、擊殺與勝場統計。");
        d("PlayTracker", "小 HUD：記錄玩了多久、玩了幾場（含今天累計、勝敗），並在疑似被減傷時提醒。");
        d("Ambience", "改變世界的時間與天氣（只有自己看得到）。");
        d("FullBright", "全亮，暗處也看得清楚。");
        d("NoHurtCam", "受傷時畫面不搖晃（或減少搖晃）。");
        d("Sprint", "自動衝刺。");
        d("FreeLook", "按住按鍵時可以自由轉動鏡頭，身體方向不變。");
        d("ItemPhysics", "掉落物平躺在地上，空中會翻滾。");
        d("Capes", "顯示自訂披風。");
        d("Animations", "自訂揮劍與格擋動畫。");
        d("HitParticleEffects", "打中時的粒子效果，擊殺時可播音效。");
        d("LegitHUD", "座標、FPS、延遲、CPS 與按鍵顯示。");
        d("ArmorHUD", "顯示身上的護甲與耐久。");
        d("EffectsHUD", "顯示目前的藥水效果與剩餘時間。");
        d("AutoRespawn", "死亡後自動重生。");
        // ------------------------------------------------------------- THEME
        d("Theme", "主題總開關與主色；各主題群組可以統一或個別上色。");
        d("PlayerColors", "主題群組：玩家 ESP 與 2D ESP 的顏色。");
        d("TracerColors", "主題群組：Tracer 線與箭頭的顏色。");
        d("TargetColors", "主題群組：攻擊目標標記的顏色。");
        d("BacktrackColors", "主題群組：Backtrack 顯示真實位置的方框顏色。");
        d("BedColors", "主題群組：床 ESP 的顏色。");
        d("ChestColors", "主題群組：箱子 ESP 的顏色。");
        d("ItemColors", "主題群組：掉落物 ESP 的顏色。");
        d("BlockColors", "主題群組：準心方塊外框的顏色。");
        d("ProjectileColors", "主題群組：投擲物軌跡與來襲提示的顏色。");
        d("InterfaceColors", "主題群組：HUD 模組清單等介面的顏色。");
        d("ChamsColors", "主題群組：Chams 模型的顏色。");
        d("NameTagColors", "主題群組：名牌背景與外框的顏色。");
        d("WidgetColors", "主題群組：快捷欄、雷達等小工具的顏色。");
        d("EffectColors", "主題群組：打擊粒子的顏色。");

        // ============================================================ GROUPS
        g("KillAura",
                h("常用", "Rotations", "CPS Mode", "MinCPS", "MaxCPS", "AttackRange", "SwingRange", "auto-block",
                        "Mode", "MoveFix"),
                h("目標", "Sort", "SwitchDelay", "FOV", "Players", "Bosses", "Mobs", "Animals", "Golems",
                        "Silverfish", "Teams", "BotCheck", "ThroughWalls"),
                h("出手", "ScanExtra", "Raycast", "ExitClick", "RequirePress",
                        "AllowMining", "WeaponsOnly", "AllowTools", "InventoryCheck"),
                h("格擋", "AttackTick", "AutoBlockRequirePress", "AutoBlockCPS", "AutoBlockRange"),
                h("轉頭", "SmoothBack", "AimMode", "MinTurnSpeed", "MaxTurnSpeed",
                        "Multipoint", "AimDrift", "FlickOvershoot", "TurnAccel", "Smoothing", "AngleStep", "AimLead", "AimLeadCap",
                        "RotationTiming", "LazyRotation", "ShortStop", "FailAim"),
                h("Hypixel 轉頭", "Hypixel*"),
                h("LiquidBounce 轉頭", "LB-*", "DeadZone", "MaxSpeed", "MinSpeed", "Acceleration", "Deceleration",
                        "Overshoot", "OverStr", "OverRecov", "Noise", "Randomize", "RandomRange", "YRandomize",
                        "VisualizeAim"),
                h("Advanced 轉頭", "Adv-*", "KBDisplace"),
                h("顯示與除錯", "ShowTarget", "Debug"));
        g("Clutch",
                h("觸發", "trigger", "hold-key", "edge-fall", "void-only", "safe-drop", "min-height", "combat-window",
                        "only-sideways", "fov"),
                h("放置", "reach", "place-interval", "safe-mode", "early-turn", "chain", "chain-length", "keep-y", "extra-block", "auto-ladder",
                        "swing", "pre-aim", "counter-knockback", "counter-delay"),
                h("轉頭", "mode", "move-fix", "speed", "max-speed", "rotation-random", "humanize", "ease",
                        "reset-angle", "snapback-speed", "snapback-delay", "snap-on-jump"),
                h("物品", "auto-switch", "switch-back", "item-spoof", "only-on-depletion", "pause-autoclicker"),
                h("其他", "disable-after", "log"));
        g("Scaffold",
                h("常用", "rotations", "sprint", "tower", "keep-y", "safe-walk", "move-fix", "turn-speed"),
                h("轉頭", "humanize", "telly-*"),
                h("移動", "ground-motion", "air-motion", "speed-motion", "eagle",
                        "edge-distance", "sneak-delay", "blocks-per-sneak"),
                h("疊高與高度", "hypixeltower", "keep-y-on-press", "no-keep-y-on-jump-potion"),
                h("放置", "pause-on-correction", "safe", "safe-delay-ticks", "multi-place", "swing", "item-spoof"),
                h("顯示", "block-counter", "outline-esp", "outline-color"));
        g("Velocity",
                h("模式與比例", "mode", "chance", "horizontal", "vertical", "explosions-horizontal",
                        "explosions-vertical"),
                h("Reduce 模式", "reduce", "attack-times", "only-sprinting", "reduce-when-can-attack", "slap-reduce"),
                h("Jump / Rotate", "jump", "rotate", "rotate-ticks", "tickExact"),
                h("依延遲調整（毫秒）", "500", "1000", "2000", "3000", "4000", "5000", "6000", "7000", "8000",
                        "9000", "10000"),
                h("其他", "fake-check", "debug-log"));
        g("HUD",
                h("顏色", "color", "color-*", "custom-color-*"),
                h("位置與大小", "position-*", "offset-*", "scale", "interface"),
                h("背景與特效", "background", "bg-alpha", "blur", "blur-radius", "glow", "glow-*", "bar",
                        "sidebar-mode", "shadow", "Shaders", "rounded", "corner-radius", "padding", "chat-outline"),
                h("模組清單", "suffixes", "separator-mode", "lower-case", "hide-*"),
                h("字型與資訊", "font-mode", "creida-*", "client-info", "blink-timer"),
                h("通知", "notifications", "toggle-sounds", "toggle-alerts"));
        g("NameTags",
                h("對象", "players", "friends", "enemies", "bosses", "mobs", "creepers", "endermen", "blazes",
                        "animals", "self", "bots", "show-invis", "show-self"),
                h("一般樣式", "mode", "scale", "auto-scale", "background", "shadow", "distance", "health", "armor",
                        "effects"),
                h("Raven 樣式", "raven-*", "bg-opacity", "bg-border", "only-name", "health-display",
                        "heart-symbol", "enchantments", "durability", "friend-color", "enemy-color"));
        g("AntiBot",
                h("基本檢查", "Basic", "MatrixBot", "Tab", "TabMode", "EntityID", "Color", "IllegalName",
                        "ExperimentalNPCDetection"),
                h("行為檢查", "LivingTime", "LivingTimeTicks", "Ground", "Air", "InvalidGround", "Swing", "Derp",
                        "WasInvisible", "Armor", "Ping", "NeedHit", "SpawnInCombat"),
                h("血量檢查", "Health", "MinHealth", "MaxHealth"),
                h("重複檢查", "DuplicateInWorld", "DuplicateInTab", "DuplicateCompareMode"),
                h("處理", "RemoveFromWorld", "Remove-Interval", "Debug"));
        g("Disabler",
                h("Grim / 通用", "grim-place", "start-sprint", "basic-disabler", "move-disabler",
                        "no-rotation-disabler", "modify-mode", "offset-amount", "only-combat"),
                h("取消封包", "cancel-*", "c03-no-move"),
                h("Vulcan", "vulcan-*"),
                h("Verus", "verus-*"),
                h("Intave / Matrix", "intave-*", "matrix-*", "ta-packet-counter"),
                h("Watchdog / 其他伺服器", "watchdog-*", "lifeboat", "spigot-spam", "message", "exp-void-tp*"),
                h("重送與除錯", "buffer-size", "repeat-times*", "flag-delay", "chat-debug"));
        g("TimerRange",
                h("模式與時鐘", "mode", "ticks", "timer-boost", "boost-delay-*", "timer-charged",
                        "charged-delay-*", "min-tick-delay", "max-tick-delay", "cooldown-tick"),
                h("距離", "range", "min-range", "max-range", "scan-range", "max-angle-difference"),
                h("預測", "blink", "predict-*"),
                h("限制", "on-web", "on-liquid", "on-forward-only", "reset-on-*"),
                h("顯示與除錯", "mark", "outline", "chat-debug"));
        g("BlockHit",
                h("一般", "Mode", "StopTicks", "Smart", "BlockHitChance", "Range"),
                h("自動格擋", "AutoBlockTime", "AutoMode", "AutoBlockRange", "HoldTick", "BlockDelay", "BlockTick",
                        "DelayPacketTick"),
                h("受傷時機", "MinHurtTime", "MaxHurtTime"),
                h("Manual", "ManualChance"),
                h("Predict", "Rhythm*"),
                h("Swing（舊）", "Predict*"));
        g("TargetHUD",
                h("樣式", "style", "Anim Mode", "animations", "head", "indicator", "outline", "shadow"),
                h("顏色", "color", "CustomColor", "Bg-Alpha", "background"),
                h("位置與大小", "position-*", "offset-*", "scale"),
                h("顯示條件", "ka-only", "chat-preview"));
        g("ESP2D",
                h("方框", "Outline", "Mode", "Local-Player", "Dropped-Items"),
                h("血量", "Health-Bar", "HBar-Mode", "Render-Absorption", "HealthNumber", "HP-Mode"),
                h("護甲", "Armor-Bar", "ABar-Mode", "ItemArmorNumber", "ArmorItems", "ArmorDurability"),
                h("名字", "Tags", "Tags-Background", "Item-Tags", "Font-Scale"),
                h("顏色", "Color", "Red", "Green", "Blue", "Saturation", "Brightness"));
        g("Blink",
                h("扣住", "direction", "mode", "hold-transactions", "auto-off", "timeout"),
                h("釋放", "auto-send", "send-threshold", "max-drift", "latency-budget", "ping-aware",
                        "release-on-correction", "release-per-tick", "release-every", "release-on-place"),
                h("顯示", "announce-release", "server-position", "breadcrumbs"));
        g("FlagDetector",
                h("拉回偵測", "min-distance", "teleport-distance", "show-teleports", "group-bursts", "burst-window"),
                h("歸因", "context", "blame", "blame-window"),
                h("方塊與傷害", "detect-rejects", "place-log", "detect-low-damage", "damage-chat"),
                h("提示", "chat", "sound", "log-file"),
                h("HUD", "hud", "hud-*"));
        g("BedwarUtils",
                h("資訊 HUD", "hud", "hud-x", "hud-y", "hud-scale", "hud-shadow", "diamond-upgrades", "item-tracker",
                        "invis-alert", "auto-inc"),
                h("床追蹤 HUD", "bedtracker", "tracker-hud", "hud-position-*", "hud-offset-*", "tracker-hud-*"),
                h("提醒", "alerts*"),
                h("巨集", "macro*"));
        g("Xray",
                h("一般", "mode", "opacity", "range", "caves-only", "caves-radius"),
                h("礦物", "diamonds", "gold", "iron", "coal", "redstone", "lapis", "emeralds", "spawners", "canes",
                        "warts"),
                h("追蹤線", "*-tracers"));
        g("InvManager",
                h("時機", "min-delay", "max-delay", "open-delay"),
                h("整理", "auto-armor", "auto-armor-interval", "drop-trash", "check-durability", "blocks",
                        "projectiles", "arrow"),
                h("固定格子", "*-slot"));

        // ======================================================= SHARED HELP
        help("move-fix", "移動修正：靜默轉頭時讓移動方向跟伺服器看到的視角一致，避免移動類 flag。");
        help("movefix", "移動修正：靜默轉頭時讓移動方向跟伺服器看到的視角一致，避免移動類 flag。");
        help("swing", "動作時揮手（送出揮手動畫）。原版每次攻擊或放方塊都會揮手。");
        help("range", "作用距離（格）。");
        help("fov", "只對準心前方這個角度內的目標作用；360 為全方向。");
        help("teams", "略過隊友。");
        help("ignore-teammates", "略過隊友。");
        help("bot-check", "略過 AntiBot 判定為假人的玩家。");
        help("botcheck", "略過 AntiBot 判定為假人的玩家。");
        help("weapons-only", "只有手上拿武器時才作用。");
        help("weapon-only", "只有手上拿武器時才作用。");
        help("allow-tools", "拿斧頭、鎬子等工具時也算拿武器。");
        help("show-target", "在目標上畫標記。");
        help("min-cps", "每秒點擊次數下限（實際在上下限之間隨機）。");
        help("max-cps", "每秒點擊次數上限（實際在上下限之間隨機）。");
        help("min-delay", "最短延遲（隨機範圍下限）。");
        help("max-delay", "最長延遲（隨機範圍上限）。");
        help("open-delay", "打開畫面後先等這麼久才開始動作。");
        help("delay", "延遲。");
        help("chance", "觸發機率（%）。");
        help("mode", "運作模式。");
        help("speed", "速度。");
        help("rotations", "轉頭方式。");
        help("require-press", "只有按住對應按鍵時才作用。");
        help("chat", "在聊天欄顯示訊息。");
        help("sound", "出現時播放提示音。");
        help("log-file", "同時寫入 config/Myau 底下的紀錄檔。");
        help("log", "寫入 config/Myau 底下的紀錄檔。");
        help("debug", "輸出除錯訊息。");
        help("hud", "顯示 HUD 面板。");
        help("hud-x", "HUD 的水平位置。");
        help("hud-y", "HUD 的垂直位置。");
        help("x", "水平位置。");
        help("y", "垂直位置。");
        help("position-x", "水平位置。");
        help("position-y", "垂直位置。");
        help("offset-x", "水平微調。");
        help("offset-y", "垂直微調。");
        help("scale", "大小縮放。");
        help("color", "顏色。");
        help("opacity", "不透明度。");
        help("bg-alpha", "背景不透明度。");
        help("background-alpha", "背景不透明度。");
        help("background", "顯示背景。");
        help("shadow", "文字陰影。");
        help("outline", "外框。");
        help("players", "包含玩家。");
        help("friends", "包含好友。");
        help("enemies", "包含敵人。");
        help("self", "包含自己。");
        help("bots", "包含假人。");
        help("mobs", "包含怪物。");
        help("animals", "包含動物。");
        help("cooldown", "冷卻時間，觸發一次後要等這麼久才會再觸發。");
        help("item-spoof", "伺服器看到你換成方塊，但手上顯示的物品不變。");
        help("keep-y", "搭橋時維持在同一高度，不往上疊。");
        help("through-walls", "允許隔著方塊作用。");
        help("throughwalls", "允許隔著方塊作用。");
        help("alerts", "開啟提醒。");
        help("alerts-range", "提醒的距離。");
        help("alerts-sound", "提醒時播放音效。");
        help("macro", "觸發時自動送出設定的訊息。");
        help("macro-text", "巨集要送出的文字。");
        help("macro-delay", "巨集兩次送出之間的最短間隔。");
        help("dry-run", "只報告會做什麼，不真的動手（觀察用）。");
        help("ping-aware", "依你的延遲自動調整時間。");
        help("auto-switch", "需要時自動切到對應物品的格子。");
        help("switch-back", "用完切回原本的格子。");
        help("multi-place", "同一個 tick 最多放 4 格。Grim 會抓（MultiPlace），預設關。");

        // ======================================================= MODULE HELP
        String ka = "KillAura";
        help(ka, "Mode", "Single 一直打同一個人；Switch 每過 SwitchDelay 換下一個目標。");
        help(ka, "Sort", "選目標的優先順序：距離、血量、受傷時間、或離準心最近。");
        help(ka, "SwitchDelay", "多久重新挑一次目標（毫秒）；Switch 模式下也是換人的間隔。");
        help(ka, "CPS Mode", "Normal：在 MinCPS～MaxCPS 間隨機。Record：照一段真人錄下的點擊節奏（約 9.8 CPS）。Human：真人點擊器，每輪連擊在 MinCPS～MaxCPS 間抽一個速度，間隔呈對數常態分布。");
        help(ka, "MinCPS", "每秒攻擊次數下限（實際在上下限間隨機，最高受 tick 限制為 20）。");
        help(ka, "MaxCPS", "每秒攻擊次數上限。");
        help(ka, "AttackRange", "真正出手的距離（格）。原版是 3.0。");
        help(ka, "SwingRange", "開始轉頭與揮手的距離，略大於攻擊距離。");
        help(ka, "RotationTiming", "Normal：一直瞄準。Snap：只在「轉到位時剛好要點」才轉頭。");
        help(ka, "LazyRotation", "視角已經打得到目標就不再轉。");
        help(ka, "ShortStop", "偶爾（每 tick 3%）停頓 1–2 tick，只轉一點點，像真人。");
        help(ka, "FailAim", "偶爾（每 tick 3%）瞄偏 5–10 度 1–4 tick，像真人失誤。");
        help(ka, "Raycast", "Enemy：視角先碰到別的敵人，就打那個人並改成目標（像真的準星）。None：永遠打選定的目標。");
        help(ka, "ExitClick", "目標下一 tick 就要離開攻擊距離時，把下一下提早一 tick 打出去。");
        help(ka, "ScanExtra", "比揮手距離再遠幾格就先轉頭（不揮手），每個目標隨機 ±0.5；0 = 只從揮手距離才轉。");
        help(ka, "RequirePress", "只有按住左鍵時才攻擊。");
        help(ka, "AllowMining", "準心對著方塊且按住左鍵時，讓你挖方塊而不是打人。");
        help(ka, "InventoryCheck", "開著背包、箱子等畫面時不攻擊。");
        help(ka, "auto-block", "自動格擋方式。LEGIT：只在對方面向你、正在揮劍（或你剛被打）時才 block hit——打完立刻舉劍、下一下之前放下，不降 CPS；對方沒威脅就不格擋，跑得快。SameTick：要點的那 tick 先放下、打、立刻再舉，其餘時間舉著；離開揮手距離就放下。");
        help(ka, "AutoBlockCPS", "格擋時的攻擊速度（LEGIT、SameTick 不用，照 MinCPS～MaxCPS）。");
        help(ka, "AutoBlockRange", "目標在這個距離內就舉劍格擋。");
        help(ka, "AutoBlockRequirePress", "只有按住右鍵時才自動格擋。");
        help(ka, "AttackTick", "SWAP 格擋時，NoSlow 的 No Attack 在哪個 tick 不出手。");
        help(ka, "Rotations", "Silent：伺服器看到你轉頭，畫面不動。Legit：同時轉畫面。LockView：鎖定視角。");
        help(ka, "MoveFix", "靜默轉頭時修正移動方向，避免移動類 flag。");
        help(ka, "SmoothBack", "沒目標或關閉時，把伺服器看到的視角平滑轉回你的畫面，不會一瞬間跳回去。");
        help(ka, "AimMode", "SMOOTHSTEP：遠快近慢、保證到位。LEGACY：舊的比例平滑。");
        help(ka, "MinTurnSpeed", "快對準時每 tick 最少轉幾度。");
        help(ka, "MaxTurnSpeed", "離目標很遠時每 tick 最多轉幾度（平滑轉回也用這個速度）。");
        help(ka, "Multipoint", "瞄準碰撞箱上離準心最近的點，而不是中心，轉頭量最小。");
        help(ka, "TurnAccel", "轉頭速度每 tick 最多能加快幾度，像手一樣慢慢加速。");
        help(ka, "AimDrift", "瞄準點在碰撞箱裡慢慢飄動的程度。0 = 永遠瞄最近的邊緣；越高越往箱子裡面一個會慢慢移動的點瞄，不會一直鎖同一點。");
        help(ka, "FlickOvershoot", "大幅度轉頭時偶爾會稍微轉過頭（最多 4 度），再修正回來，像真人甩槍。");
        help(ka, "Smoothing", "LEGACY 模式的平滑程度。");
        help(ka, "AngleStep", "LEGACY 模式每 tick 最多轉幾度。");
        help(ka, "AimLead", "依延遲往目標移動方向多瞄一點（0 = 不預判；Grim 建議 0）。");
        help(ka, "AimLeadCap", "預判最多偏移幾格。");
        help(ka, "FOV", "只攻擊準心前方這個角度內的目標。");
        help(ka, "ThroughWalls", "允許攻擊隔著方塊的目標。");
        help(ka, "KBDisplace", "Advanced 專用：把擊退方向轉向危險處。SAFE 只在還打得到時轉。");
        help(ka, "ShowTarget", "在目標身上畫方框。");
        help(ka, "Debug", "Health：在聊天欄顯示自己每次血量變化。");

        String cl = "Clutch";
        help(cl, "trigger", "AUTO：偵測到會摔下去就自動接。HOLD：按住 hold-key 時才作用。");
        help(cl, "hold-key", "trigger 為 HOLD 時要按住的按鍵。");
        help(cl, "edge-fall", "沒被打時，往虛空掉多少格才出手（小跳不算掉落）。");
        help(cl, "void-only", "只在下面是虛空時才接。");
        help(cl, "safe-drop", "落差在這個高度以內、下面又有地面時就不接。");
        help(cl, "combat-window", "被打後多少毫秒內的掉落算「被擊落」。");
        help(cl, "reach", "放方塊的最遠距離。");
        help(cl, "place-interval", "兩次放置之間至少隔幾個 tick。");
        help(cl, "safe-mode", "安全模式：先用伺服器已經知道的視角點；大角度轉頭在時間夠時提早一個 tick 轉。不降連續放置速度。關閉 = 原本同一 tick 轉頭加點擊。");
        help(cl, "early-turn", "要轉超過這個角度、而且時間還來得及時，提早一個 tick 先轉頭，下一個 tick 再點（伺服器先看到視角）。來不及就照原本同一 tick 轉加點，不會變慢。180 = 關閉。");
        help(cl, "chain", "直接接不到時，先鋪一條橋延伸過去再接。");
        help(cl, "chain-length", "橋最多鋪幾格。");
        help(cl, "keep-y", "不會放到你起跳前站的那一層以上。");
        help(cl, "extra-block", "快落地且會踩在方塊邊緣時，在腳下正中央多補一格。");
        help(cl, "humanize", "點擊位置用常態分佈隨機，轉頭走 Rotations 引擎的速度隨機與弧線。");
        help(cl, "ease", "轉頭最後一段減速（會多花 tick，接不到的機率稍增）。");
        help(cl, "rotation-random", "隨機程度（點擊位置的分散範圍）。");
        help(cl, "speed", "每 tick 最少轉幾度。");
        help(cl, "max-speed", "每 tick 最多轉幾度。");
        help("Scaffold", "turn-speed", "每 tick 最多轉幾度（DEFAULT～Hypixel 模式）。不再一次甩到位，轉不到就等下一 tick，不會亂點。180 = 不限制。");
        help("Scaffold", "humanize", "轉頭加上 Rotations 引擎的速度隨機與弧線，比較像人手。");
        help("Scaffold", "pause-on-correction", "被伺服器拉回後，等一個來回延遲再放方塊；被退回的方塊也會暫停。避免在錯的位置連續放出 ghost block。");
        help(cl, "pre-aim", "還沒到時機前先把頭轉到大概的位置，等一下轉比較少。");
        help(cl, "counter-knockback", "被打飛時往回走，抵銷一部分擊退。");
        help(cl, "auto-ladder", "接不到方塊時嘗試放梯子。");
        help(cl, "only-on-depletion", "只有手上方塊用完時才自動換格子。");
        help(cl, "pause-autoclicker", "接的時候暫停 AutoClicker。");
        help(cl, "disable-after", "接完一次就自動關閉 Clutch。");

        String rt = "Rotations";
        help(rt, "speed-noise", "轉頭速度上下浮動的幅度（常態分佈的標準差，% 為每步的比例）。");
        help(rt, "curve", "轉頭路徑偏離直線的幅度（每步的 %）。0 = 直線。");
        help(rt, "curve-smooth", "弧線的平滑度：越高，每 tick 的偏移越延續上一 tick。");
        help(rt, "ease", "接近目標時減速，不會全速衝到再急停。");
        help(rt, "ease-zone", "剩下幾度以內開始減速。");
        help(rt, "ease-floor", "減速時每 tick 最少還轉幾度，保證一定轉得到。");

        String pt = "PlayTracker";
        help(pt, "show-today", "同時顯示今天累計的時間與場數。");
        help(pt, "show-wins", "顯示勝敗場數。");
        help(pt, "show-mitigation", "偵測到被減傷或連續打不到時，多顯示一行提醒（需要 FlagDetector）。");
        help(pt, "mitigation-always", "沒有減傷時也顯示「減傷 無」。");

        String fd = "FlagDetector";
        help(fd, "detect-rejects", "方塊被伺服器退回時回報，並直接算給放那格的模組。");
        help(fd, "detect-low-damage", "偵測打中但傷害偏低（減傷），以及連續打不到。");
        help(fd, "damage-chat", "每一刀都在聊天欄顯示實際傷害與原版最低應有的傷害。");
        help(fd, "blame", "顯示是哪個模組最可能造成這次 flag。");
        help(fd, "blame-window", "歸因時往前看幾秒內的模組動作。");
        help(fd, "min-distance", "拉回小於這個距離就不回報。");
        help(fd, "group-bursts", "短時間內連續的拉回合併成一行。");
    }
}

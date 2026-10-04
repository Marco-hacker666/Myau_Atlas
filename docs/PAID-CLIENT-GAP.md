# OpenMyau+ 與付費 client 的差距（2026-09-25）

寫在下一局之前，當作之後改進的路線圖。

**資料來源與限制**
- 付費 client 是閉源的，這裡只能比對它們**公開**的東西：Slinky 的模組文件 (docs.slinky.gg)、官網，以及 Rise 的功能列表。
- 我們這邊的依據是原始碼和這幾週的 log（flags / clutch / fakelag / perf）。
- 「付費 client 內部怎麼做」的部分是推論，標為（推測）。

---

## 總結：差在哪裡

模組**數量**不是問題：我們有 152 個 class，Slinky 約 50+ 個。差距主要在四件事：

1. **驗證迴圈。** 付費 client 有團隊和大量使用者持續對特定 anticheat 測試，壞了很快就知道（推測）。我們只有一個玩家的 log：
   - 674 次方塊被拒，到現在還找不到原因。
   - place-log 才剛加上，還沒有資料。
2. **共用的底層。** Slinky 每個模組都用同一套東西：「Weapons」定義、「Conditions」啟動條件、「Randomization」、「Select blocks」、aiming（base speed / acceleration / multipoint）。
   - 我們是每個模組各自實作。
   - GCD 量化和加速度上限只有 Clutch 有，Scaffold、KillAura、AimAssist 各算各的。
3. **精簡與打磨。**
   - Slinky 約 50 個模組，每個設定都有文件說明用途和風險。
   - 我們有 152 個，其中有重複或半成品（KillAura/PlainAura、WaterMark/WaterMark2、ClickGUIModule/RiseClickGUIModule…），使用者文件是 0。
4. **注入與相容性。**
   - Slinky 用注入的方式，支援 Vanilla / Forge / Lunar / Badlion。
   - 我們是放在 mods 資料夾的 Forge mod，只能在 Forge 上跑。

---

## 逐模組對照（以 Slinky 公開文件為準）

### Clutch

| Slinky 有 | 我們 | 差距 |
|---|---|---|
| Range：以「要放幾塊才接得到」計算 | `reach`（距離） | 我們用距離，不是以要放的方塊數計算 |
| FOV 限制，只找視野內的方塊 | 無 | 缺。沒有 FOV 限制時會大角度轉頭，這可能是 flag 的來源之一 |
| Minimum height：下方至少 N 格空氣才啟動 | `void-only` / `safe-drop` | 我們只分「是不是虛空」，沒有可調的格數 |
| Click speed + Randomization | `place-interval`、`rotation-random` | 大致有 |
| Select blocks：No / On depletion / Always | `auto-switch` / `switch-back` | 缺「On depletion」 |
| Only place sideways（維持 Y 高度） | 無 | 缺 |
| Aiming：base speed / acceleration / acceleration strength | `speed` / `max-speed`，加上 GCD 和加速度上限 | 相當；我們的加速度強度不能調 |
| Multipoint：瞄準方塊上最近的點 | 固定的點（推測較不自然） | 待查，可能缺 |
| Snap-back：delay / duration / keep jump direction / disable afterwards | `snapback-speed` | 缺 delay、duration 和「跳躍時立即回正」 |
| 啟動條件：空中、剛被打、往後走 | `trigger` / `combat-window` / `counter-knockback` | 相當 |

### Backtrack

| Slinky 有 | 我們 | 差距 |
|---|---|---|
| Target distance 區間（例如 1.0–4.0） | `range` | 缺下限；貼身時還 hold 沒有意義，反而增加風險 |
| Maximum delay | `normal-delay` / `adaptive-delay` | 有，而且多了 adaptive |
| Maximum hurt time：對手無敵幀太高就不 hold | 無 | 缺。能減少白白 hold 的時間 |
| Cooldown：停止後要等一段時間才能再啟動 | 無 | 缺。連續不斷的延遲最容易被時間分析抓到 |
| Real position indicator：顏色、線寬、填色、**頭部朝向** | 平滑淡出的 ESP，接 Theme | 缺頭部朝向 |
| Disable on hit（被擊退就放） | `release-on-hit` | 有 |
| Conditions：只在拿武器時 | 無 | 缺（要先做全域 Weapons） |

### Fake Lag

| Slinky 有 | 我們 | 差距 |
|---|---|---|
| Static / Pulse 模式 | `mode`，加上 taper / catch-up 漸放 | 相當，漸放算是我們的優勢 |
| **Inbound 與 outbound 延遲分開設定** | 只有 outbound | **缺 inbound 延遲** |
| Realtime damage：inbound 延遲時，傷害封包不延遲 | 無 | 缺（跟著 inbound 一起做） |
| Require attack：最近有攻擊才 lag | `release-on-attack` / `range` | 概念不同，可以補上 |
| Holding weapon / In-game 條件 | 部分（screen / using-item 算 soft reason） | 缺拿武器的條件 |

### Bridge Assist（對應我們的 Eagle / SafeWalk / Scaffold 的 eagle）
- Slinky 的 Bridge Assist 有這些設定：
  - edge offset 和 unsneak delay 都能**隨機化**。
  - Sneak on jump。
  - **Avoid double-sneaking**：斜著搭橋時延後放置，避免一塊方塊蹲兩次。
  - 啟動條件：按蹲、拿方塊、往下看、沒往前走。
- 我們的 Eagle 很陽春，Scaffold 裡的 `edge-distance` / `sneak-delay` 沒有隨機化。

### 我們沒有的模組
- **Movement**：Null Move（同時按左右 / 前後時的處理）、Instant Stop、Fast Accel。
- **Utility**：Auto Rod、Auto Pot / Auto Soup（BedFight 用不太到）、Item Use Fix、No Item Release、No Use Delay、Ping Fix。
- **Visual**：Pointers（螢幕邊緣指向對手的箭頭）、Notifications（模組開關、flag 的提示）。
- **全域**：Weapons（什麼算武器，由所有模組共用）、Friends 已經有、Teams 已經有。

### 我們有、Slinky 沒有的
這些是我們的優勢，要保留。
- FlagDetector + place-log + PerfLog + clutch / fakelag trace：以資料驅動的除錯工具。
- Arbiter：模組之間的協調，避免 Clutch 在接的時候被 Blink / FakeLag 拖住。
- Theme 分頁：14 個群組，全部可以自訂。
- 更多 Blatant 模組（Fly、Speed、LongJump、BedNuker…）。不過 Slinky 定位是 ghost client，這些本來就不做。

---

## 我們自己的已知問題（來自 log）

1. **方塊被拒**：674 次（15 天）。
   - 61% 附近沒有 lagback。
   - 被拒大多發生在放置後 4–6 tick（一個來回）。
   - 往上疊的時候最常連續被拒，接著被往下拉回。
   - 原因未知 → 等 place-log 的資料。
2. **Clutch 就算把 GCD、加速度都做了，仍然被拒 20%**，找不到區分因子。
   - 可能跟上面第 1 點是同一個原因。
   - 也可能是 FOV / 轉頭幅度（Slinky 有 FOV 限制，我們沒有）。
3. **效能**：HUD 的 blur / glow shader 每幀約 200µs，是自己的程式裡最大的開銷。
   - 付費 client 通常把 blur 做成可快取的 framebuffer（推測）。
4. **Backtrack**：網路執行緒持有鎖時，會排程任務進 scheduledTasks，順序很脆弱（目前沒出事）。

---

## 建議的優先順序

依「對 flag / 勝率的影響 ÷ 工作量」排序：

1. **看 place-log 找出被拒的原因**（下一局後）。這是目前最大的未知，其他改動都可能被它影響。
2. ✅（14:17 版）**共用的 RotationEngine**：把 Clutch 的 GCD、加速度上限、速度飄移抽出來，讓 Scaffold / KillAura / AimAssist 共用，再加上 multipoint 和 FOV 限制。
3. ✅（14:17 版，snap-back duration 用 snapback-speed 代替）**Clutch 補齊**：
   - FOV 限制
   - minimum height（格數）
   - only sideways
   - On-depletion 換方塊
   - snap-back 的 delay / duration 和跳躍時立即回正
4. ✅（14:17 版）**Backtrack 補齊**：
   - 距離下限
   - max hurt time
   - cooldown
   - 拿武器的條件
   - ESP 頭部朝向
5. **全域 Weapons + Conditions**：一次定義，所有 combat / utility 模組共用。
6. **FakeLag 的 inbound 延遲 + realtime damage。**
7. **Bridge Assist**：隨機化 edge offset / unsneak delay，加上 avoid double-sneak。
8. **精簡模組**：合併或隱藏重複、壞掉的模組，替常用模組寫一頁使用說明。
9. **HUD blur 快取**：只在內容變的時候重畫。
10. **新增小模組**：Pointers、Notifications、Null Move、Instant Stop。

## 大概差多少（主觀估計）

- **功能面**：常用的 combat / block 模組大約是 Slinky 的 70–80%，缺的多半是「設定的細緻度」，不是整個模組。
- **穩定度 / 過 anticheat 的可靠度**：差距最大，但也最難估。付費 client 靠大量使用者回報來修（推測）。我們靠 log 自己找，速度慢，但每次的修正都有根據。
- **完成度**（文件、UI 一致性、不重複）：差距明顯，而且是純工作量，不需要研究。

來源：
- [Slinky](https://slinky.gg/)
- [Slinky 模組文件](https://docs.slinky.gg/modules/combat/velocity/)：Clutch、Backtrack、Fake Lag、Bridge Assist 各頁
- [Rise features](https://riseclient.org/features)

---

# 和 Vape 的比較（2026-09-25 晚）

**來源與限制**
- Vape 的官方文件 [docs.vape.gg](https://docs.vape.gg/features/modules/)。只看得到模組名稱和設定說明，看不到它的內部做法。
- 網路上很多「Vape V4 下載」的 GitHub 頁面是假的或是病毒，沒有採用。

## 戰鬥模組對照

| Vape | 設定重點 | 我們 | 差距 |
|---|---|---|---|
| AimAssist | Simple / Adaptive、**Strafe Increase**（左右移動時轉速加快）、Target Area：中心 / 最近點、Target Mode：距離 / 角度 / 護甲 / 威脅 / 血量 | AimAssist：multipoint（＝最近點）、fov、速度 | 缺 Adaptive、Strafe Increase，以及用護甲 / 威脅挑目標 |
| AutoClicker | — | AutoClicker（左右鍵、hit-box 輔助） | 相當 |
| BlockHit | Manual / Auto / **Predict**（預判對手出手就先格擋）/ **Lag** | BlockHit：Helper，有 Predict 相關設定 | 大致相當，缺 Lag 模式 |
| HitSelect | Pause / **Active**（依局勢挑選出手，偏重少吃擊退或打爆擊） | HitSelect：SECOND / CRITICALS / W_TAP | 缺 Active 的動態判斷 |
| Reach | 範圍、機率、Advanced chance、**Misplace**（讓錄影看起來合理）、垂直檢查、只在疾跑時 | Reach：範圍、機率 | 缺 Advanced chance、Misplace、垂直檢查 |
| **WTap** | 機率、**放開延遲**、**重按延遲**、**Select Hits**（只在有利時 W-tap） | SprintReset LEGIT + min-gap（20:28 版） | 缺機率、兩個延遲、Select Hits |
| **JumpReset** | 機率、準確度（故意偶爾失準）、只在對手在準心附近時、水中停用 | Velocity 的 Jump 模式（有沒有同時改動 motion 還沒確認） | **缺獨立、只靠跳躍的 JumpReset** |
| Triggerbot | 準心在對手身上才自動點 | 無 | 缺 |
| RightClicker | 右鍵連點 | AutoClicker 的 right-click | 相當 |
| HitFlick / SilentAura / Sprint | — | Hitflick / KillAura / Sprint | 相當 |
| Network：BackTrack / Blink / FakeLag / KnockbackDelay | 看不到設定細節 | 都有，而且有 log 可以調 | 無法比較細節 |

## 結論
- **戰鬥模組的種類**：大約 85% 有對應。真的缺的是 **JumpReset** 和 **Triggerbot**。
- **設定的細緻度**：差距比種類大。Vape 很多模組都有「機率」「故意失準」「只在有利時才做」這三類設定。它們是用來讓行為看起來不規則、不像機器，我們多半沒有。
- **看不到、也最難追的差距**：Vape 針對各個反作弊有每日輪替的設定檔（它自己的宣傳說法）。我們只能靠自己的 log 一個一個調。
- **我們有、Vape 文件裡沒提到的**：FightLog、FlagDetector、place-log 這類量化工具。

## 參考 Vape 4.21 之後補上的（20:48 版，還沒測）

| 項目 | 狀態 |
|---|---|
| 出手到對手受傷的延遲追蹤（HitTimer） | ✅ 新增，HitSelect / SprintReset / BlockHit 共用 |
| WTap：機率、放開延遲、放開多久、只在有利時才做 | ✅ 加進 SprintReset |
| HitSelect Active | ✅ 新模式 ACTIVE |
| BlockHit Predict（照自己受傷的節奏格擋） | ✅ 新模式 Rhythm |
| AimAssist：預判對手移動、橫移時加速 | ✅ `lead`、`strafe-increase` |
| AimAssist：速度模型、每幀加滑鼠移動量 | ❌ 還沒做 |
| Reach | ❌ 不做：Vape 自己的說明都叫人不要用 |
| JumpReset、Triggerbot | ❌ 還沒做 |

## FakeLag 對照（2026-09-25 晚）

**來源**：Vape 4.21（桌面《FakeLag模組技術分析.md》加上 jar 靜態閱讀）、LiquidBounce 原始碼 ModuleFakeLag.kt、Slinky 文件。

**我們已經有的**：LATENCY、DYNAMIC、REPEL、LIQUID 四種模式，另外還有：
- 慢慢放封包（taper / catch-up）
- 被打後冷卻一段時間才能再扣（recoil）
- 跟 Clutch 協調（Arbiter）
- 拿方塊時不扣、放方塊的封包照順序排隊
- 每次扣和放都有 log

**缺的（依優先度）**
1. **每次延遲長度隨機**。LiquidBounce 每次在 300–600ms 間重抽，Vape Repel 加 0–99ms 隨機。我們固定 250ms。
2. **延遲收到的封包，傷害即時顯示**（Slinky）。我們只有 Backtrack 延遲收到的封包。
3. **只在拿武器時扣**（Slinky）。
4. **Vape Repel**：扣住攻擊封包，等對手無敵時間 ≤ HitTimer 的預期 tick 才送出。我們的 REPEL 其實是 Vape Dynamic 的「位置有沒有更靠近」檢查。
5. **Pulse 模式**（Slinky）。
6. **閃箭**：只放出到躲得過箭的那個位置（LiquidBounce），價值低。

---

# 和 Rise 6.9.5 原始碼的比較（2026-09-28）

**來源與限制**
- `<reference clients>\Rise\Rise-6.9.5-main`：反編譯後的原始碼，約 1300 個 Java 檔。
  - 名稱部分是復原的，部分還是混淆名（`aEg`、`wo()`）。
  - README 自己說可能有和原版不一致的地方。
- 只讀原始碼，沒有執行 `Rise.jar`，也沒有複製程式碼。
- 我們可以參考它的做法，但實作要自己寫（跟 Vape 的規則一樣）。
- 和 Vape、Slinky 不同，這次看得到**內部做法**，所以以下都是從程式碼讀出來的，不是推測。

## 總結

1. **可靠度、可觀察性：我們領先。**
   - Rise 到處是 `public static` 可變欄位，沒有任何測試。
   - 它沒有數值擁有權（Property override）、連線生命週期（ServerSession）、分階段關閉（Shutdown），也沒有 FlagDetector / Attribution / Brain / FightLog 這一類「用資料找原因」的工具。
2. **共用底層：Rise 有三個我們沒有、值得學的設計。**
   - 封包暫扣的「租約」。
   - 每 tick 動作封包的守門。
   - 數值範圍（min–max）。
   - 細節見下一節。
3. **戰鬥的轉頭模型：差距最大。**
   - KillAura 的 Advanced 模式是一整套擬人的滑鼠模型，參數約 20 個。
   - 我們的 RotationEngine 有滑鼠格點（GCD）、加速度上限、速度飄移和 multipoint，但沒有反應時間、過衝、死區、停頓。
4. **模組種類：戰鬥和幽靈（ghost）類大致相當。**
   - Rise 多的主要是：KeepRange（S-Tap）、AimBacktrack，以及擊退導向危險區（Knockback Displacement）。
   - 其他多出來的是 blatant、exploit 類，或特定伺服器的東西。

## 底層架構對照

### 1. 封包暫扣：BlinkComponent 對上我們的六個暫扣者

**Rise 的做法**
- 所有暫扣都進**同一條有順序的佇列**。
- 每個呼叫的類別有一個自己的 channel，名稱從 stack trace 取得。
- 每個被扣的封包身上記著「哪些 ticket 還要扣它」。
  - 只要還有任一個有效的 ticket，它就不出去。
  - 前面的封包沒放，後面的也不放，所以順序永遠正確。
- **租約（lease）**：channel 超過 100 ms 沒被續約，就自動放掉。
  - 模組必須每 tick 呼叫一次 `blink()` 才能繼續扣。
  - 模組出錯、被關掉、忘了放，最多 100 ms 內也會放出來。
- 每個 channel 有自己的最長暫扣時間。
- 換世界、進入下載地形畫面時，全部放掉。
- 可以按 tick 分批放：每 tick 記錄扣了幾個，之後一次放 N 個 tick 的量。

**我們的做法**
- LagManager、BlinkManager、DelayManager、BackTrack、ServerLag、KnockbackDelay 各有自己的佇列。
- Arbiter 管先來先得，PacketHolds 只負責「看」。

**差距**
- **沒有租約。** 一個暫扣者要是沒走到釋放的程式，封包就會一直卡著。
  - 這正是 reliability pass 裡「每個模組要保證自己放」那一類問題的根源。
- 兩個模組不能同時扣同一個封包。現在是第二個被拒絕，而且不同暫扣者之間的順序沒有保證。

**不該學的地方**
- Rise 把送出和收到的封包放在同一條佇列，一個方向卡住會擋到另一個方向。
- 它每扣一個封包就取一次 stack trace，成本高。我們只在 `setDelay` 時取一次 `callerModule`，比較好。

**建議**
- 不必把六個暫扣者合併成一個（那是重寫架構）。
- 在現有的 manager 加上**租約**就能拿到大部分好處：沒被續約就自動釋放，並且記一筆「lease-expired」到 log。

### 2. 轉頭：RotationComponent

**Rise 的做法**
- 轉頭也是租約制：這個 tick 沒人設定目標角度，就開始回到玩家真正的視角。
  - 回去的時候會對齊滑鼠格點（`applySensitivityPatch`），不會瞬間跳回。
- 移動修正有三種：NORMAL、TRADITIONAL、BACKWARDS_SPRINT。
  - BACKWARDS_SPRINT 的意思是：轉頭後的方向和移動方向差超過 45° 就不疾跑。
- **有驗證的雜訊**：瞄準點在 hitbox 上隨機漫步，每一步都用 raycast 檢查還打不打得到。打不到就換方向，還是打不到才回中心。
  - 這樣雜訊永遠不會讓攻擊落空。

**我們的做法**
- RotationEngine 有 GCD、加速度、飄移、multipoint、FOV。
- RotationManager / RotationState 管優先權。

**缺的**
- 有驗證的雜訊。
- 平滑地回到真實視角。
- 疾跑的方向修正。

### 3. 每 tick 動作封包的守門：BadPacketsComponent

**Rise 的做法**
- 記錄這個 tick 已經送過哪些封包：換格（C09）、攻擊（C02）、放置（C08）、挖掘（C07）、點擊視窗。
- 下一個 C03 移動封包送出時歸零。
- 模組在送之前先問一聲。例如 TickBase 在這個 tick 已經攻擊過，就不 blink。

**我們的做法**
- 沒有集中管理，只有 NoSlow 和 Velocity 各自檢查。

**為什麼重要**
- 很多反作弊的 BadPackets 檢查，抓的就是「同一 tick 出現不該同時出現的動作」。
- 這個守門的工作量很小，而且 FlagDetector 可以直接驗證它有沒有效。

### 4. 數值範圍（BoundsNumberValue）

- Rise 很多設定本身就是一個範圍，每次使用時在範圍內隨機取值。
  - 例如 CPS 8–14、轉頭速度 5–10、Blink 延遲、放置延遲。
- 我們大多是單一數值，只有 AutoClicker 的 min / max-cps 例外。其他隨機化是各模組自己寫的。
- 這和之前 Slinky、Vape 的比較是同一個結論：「隨機化」應該是共用的底層，而不是每個模組各自做。

## 戰鬥模組對照（Hypixel 相關）

| Rise | 做法 | 我們 | 差距 |
|---|---|---|---|
| KillAura **Advanced** 轉頭 | 類似 WindMouse 的滑鼠模型。重力、風、減速距離、最大和最小步長；**過衝**（機率 77%、比例、上限 17°）；**反應時間** 180 ms ± 44；**死區** 1°；**停頓** 2 tick；**Flick Guard**（單 tick 最大轉角 29°）；pace jitter / burst | RotationEngine：GCD、加速度、飄移 | **最大差距**。反應時間、過衝、死區、停頓都沒有 |
| Velocity：23 種模式 | 針對各反作弊。Hypixel 相關的有三種：Watchdog（取消或疊加擊退，加上扣住 transaction）、**Watchdog Reduce**（扣住自己的 S12 和 transaction，直到你攻擊或落地才放）、Watchdog Prediction（放行一部分擊退） | Vanilla / Jump / Hypixel / Slap_Attack | Watchdog Reduce 基本上就是我們失敗多次的 KnockbackDelay，**不建議再試**。扣住 transaction 風險高 |
| Legit / Jump Reset | 在地上收到 S12 且 motionY > 0 時下一個 input 跳；有機率、「只在揮手時」和 Legit Timing | Velocity Jump 模式：要在地上、疾跑中、沒有跳躍藥水、不在液體裡 | 相當。Rise 多了「只在揮手時」，也就是自己也在打的時候才跳 |
| **KeepRange（S-Tap）** | **自己在 combo 對手時**（對手連續受傷、自己沒被打），距離小於設定值就停下或往後退，保持距離。附近是懸崖邊時停用。combo 要持續幾拍才啟動 | 無 | **缺**。能延長自己的 combo，減少被反打。可以用 FightLog 的 combo 長度驗證 |
| **AimBacktrack** | 保留最近 N 個 tick 的視角。目前的視角打不到、但之前某個打得到時，就用那個 | 無 | 缺。對手突然橫移時很有用 |
| **Knockback Displacement** / ManualKBDisplacement | 出手後，在打得到的前提下調整 server 端視角，讓對手的擊退方向朝向虛空、岩漿或火。為每個候選方向打分數（hazard, score），只有分數 ≥ 45 才做 | 無 | 缺。對 BedWars、邊緣戰有用 |
| LagBreak | 接近目標時 blink，攻擊時放（dispatch on attack）；最多扣 9 tick；對手沒看你時隨機放；有進度條、圓圈、文字三種顯示 | FakeLag（LIQUID 等 4 種模式） | 大致相當。缺「還扣多少」的畫面提示 |
| Reach / KeepSprint「Buffer Abuse」 | 只在前 N 下啟用，之後照反作弊的 buffer 衰減速度慢慢恢復 | Reach：只有 chance | 缺。概念是「把違規壓在反作弊的容許額度裡」，要有 FlagDetector 的資料才能調 |
| TickBase | Post / Legit，很短 | PAST / FUTURE，有 balance、pause-on-flag | **我們比較完整** |
| AimAssist | speed、sticky、require swinging、require mouse movement | 有 lead、strafe-increase、multipoint；只在點擊後一段時間才輔助 | 相當。只缺「滑鼠有動才輔助」 |
| WTap | Legit（對手 hurtTime ≥ 6 時放開 W）、Silent（對手 hurtTime == 9 時那個 tick 不疾跑） | SprintReset：機率、兩個延遲、有利時才做 | **我們比較細** |
| CheatDetector | 在客戶端檢查**別的玩家**：AutoBlock A/B、VelocityCancel、FlightPrediction、SpeedLimit、TowerWatchdog | 無 | 價值低。可以在 FightLog 標記對手是不是外掛，統計時排除 |
| Mimic | 模仿對手的視角和點擊 | 無 | 噱頭，不做 |

## 不採用的
- Crasher、ComboOneHit（一次送 50–1000 個攻擊封包）、Disabler、TeleportAura、GodMode 這類 exploit。
  - 不是 ghost PvP，等於直接送封號。
- 遠端腳本、IRC、Spotify、帳號服務這類和戰鬥無關的功能。

## 建議的優先順序

依「可靠度 / 被 flag 的風險 / 勝率」的影響除以工作量排序，並且符合「先打穩底層」的原則：

1. **暫扣租約**（改在現有的 manager，不合併）。
   - 沒續約就自動放，並且記一筆 log。
   - 這直接屬於 reliability pass 的範圍：state corruption resistance。
2. **每 tick 動作封包守門**（BadPackets）。
   - 工作量很小，可以用 FlagDetector 驗證。
3. **RotationEngine 補上反應時間、過衝、死區、停頓，加上有驗證的雜訊。**
   - KillAura、AimAssist、Clutch 共用。
   - 用 FlagDetector 的 rotation 類 flag 做 A/B。
4. **KeepRange（S-Tap）。**
   - 用 FightLog 的 combo 長度和「THEY FELL / WE FELL」驗證。
5. **數值範圍（min–max）變成共用的 Property 型別。**
6. AimBacktrack。
7. Knockback Displacement。

原本排定的 JumpReset：Rise 的版本很陽春，我們的 Velocity Jump 已經相當，只差「只在揮手時」這個條件。

### 進度（2026-09-28）
- ✅ 1. 暫扣租約（PacketHolds leases）
- ✅ 2. 每 tick 動作封包守門（TickActions；KillAura 已經接上）
- ✅ 3. KillAura Rotations「Advanced」：做法和 Rise 一樣（util/AdvancedAim）
- ✅ 4. KeepRange（S-Tap）
- ✅ 5. 數值範圍：IntRange / FloatRange，KillAura 和 AutoClicker 的 CPS 已經改用
- ✅ 6. AimBacktrack
- ✅ 7. 擊退導向：KBDisplacement 模組，加上 KillAura 的 KBDisplace（OFF / SAFE / FULL）

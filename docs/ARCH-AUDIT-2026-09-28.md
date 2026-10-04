# Architecture Audit — Phase 0 (2026-09-28)

> **Status (updated after step 14, 2026-09-28):** plan steps 1–14 are done.
> - **Fixed:** F-29 and F-35 are closed too.
> - **Tests:** 141 unit tests pass.
> - **Remaining:** 15 (shared-util tests), 16 (notifications F-31), 17 (diagnostics), 18 (final).
> - **Next:** the user put JumpReset first.

Read-only audit. **No source file was modified.** Every claim below cites the code it comes
from (`path:line`, paths relative to `src/main/java/myau/`). Each finding is marked:

- **PROVEN**: follows directly from the code as written (or from vanilla bytecode, where noted).
- **SUSPECTED**: plausible from the code, but not confirmed at runtime or in logs.
- **LIVE**: reachable with the current `config/Myau/default.json`.
- **LATENT**: only reachable with a module that is currently off.

Current config (read, not written): FlagDetector ON, FlagResponder ON but `dry-run: true`,
ServerFingerprint ON, LagRange ON, BlockHit ON (Helper), ItemPhysics ON.
Adaptive, AutoTune, LatencyGovernor and ServerProfiles are OFF.
So most of the feedback-loop risks below are **latent** today.

---

## 0. Corrections to the brief's assumptions

| Brief assumed | Code says |
|---|---|
| ServerFingerprint feeds Brain/Adaptive | It is a **leaf observer**. Nothing reads it except the GUI module lists (`ui/.../ClickGuiScreen.java:150`, `ModernClickGui.java:153`). |
| One loop: FlagDetector → Brain → Adaptive → modules | There are **three independent actuators**: FlagResponder (disables modules on short-window blame), Adaptive (Brain statistics, disables modules or turns knobs down) and AutoTune (trials knob values). There are also **two other writers** of the same settings: LatencyGovernor and ServerProfiles (via `Config.load`). No component owns a setting. |
| LatencyGovernor tiers NORMAL/MID/HIGH | The tiers are `clear` / `holding back` / `minimum` (0/1/2), `LatencyGovernor.java:317`. |
| Notifications were merged into HUD | `management/NotificationManager` still owns the state. HUD only renders it (`HUD.java:897-940`). |
| MixinRenderEntityItem was removed | There is no trace of it in the current source, any `backups/src/*` snapshot, or `docs/`. The removal predates every local backup. What is verifiable: **nothing reads ItemPhysics** (see F-23). |
| Existing test infrastructure | **None.** There is no `src/test` and no JUnit dependency in `build.gradle.kts`. |
| A ServerSession-like lifecycle exists | **No.** Six components detect "new server" in six different ways (§4.2). |

---

## 1. Repository facts

- **Build:** Gradle Kotlin DSL with `gg.essential.loom 0.10.0.+`, Forge `1.8.9-11.15.1.2318`, MCP `stable_22`, Java 8 toolchain, and shadow.
  - Build command: `JAVA_HOME=jdk-17 ./gradlew build`. The toolchain compiles for 8.
  - Mixin 0.7.11. Mixins are discovered by `init/FMLLoadingPlugin.getMixins()`, which scans the package. They are not listed in `mixins.myau.json`.
  - Shaded dependencies: ViaVersion, ViaBackwards and ViaRewind, and Reflections.
- **Size:** 544 Java files. 153 of them are under `module/modules/`.
- **Tests:** none (see §0).
- **Module registration** (`Myau.java:52-296`, run once at `MixinMinecraft.postStartGame`):
  1. Managers are registered on the EventManager: Rotation, Float, Blink, Delay, Lag, ModuleManager, CommandManager, PerfLog, HitTimer, and five `anticheat.*` listeners.
  2. The modules are `put` into a **LinkedHashMap**, so insertion order = config load order = GUI order.
  3. The fields of each module are reflected to collect its Properties, and then `EventManager.register(module)` runs.
  4. `config/Myau/default.json` is loaded, which toggles modules on and runs their `onEnabled`.
  5. The shutdown hook is registered.
- **Module enable model:** modules are **always registered**. Dispatch skips a disabled module's handlers unless `@EventTarget(whenDisabled = true)` (70 handlers). `Module.setEnabled` is synchronous and calls `onEnabled`/`onDisabled` inline, on whatever thread calls it.
- **Config persistence:** one JSON per profile in `config/Myau/`. `Config.load` reads properties **and toggles modules**, calling `onEnabled`/`onDisabled` in LinkedHashMap order.

---

## 2. Subsystem audit

### 2.1 EventManager (`event/EventManager.java`)
- **Responsibilities:** reflection-based pub/sub, priority ordering, disabled-module filtering, cancel attribution into the ActionLedger, and per-handler timing.
- **State:** static `HashMap<Class, CopyOnWriteArrayList<MethodData>> REGISTRY_MAP` (:30). Each `MethodData` holds a timing profile (nanos, calls, max).
- **Inputs:** `call(event)` from mixins (tick, update, packet, render, key, world, move-input…).
- **Outputs:** handler invocations and `ActionLedger.note(module, "held-send"/"held-recv")` (:262-275).
- **Threading:**
  - Dispatch runs on the caller's thread. RECEIVE packet events run on the **Netty IO thread** (`mixin/MixinNetworkManager.java:30-41`).
  - SEND events run on the client thread, **and on Netty for C00 keep-alive replies**. This was verified by `javap` of vanilla `NetHandlerPlayClient.handleKeepAlive`: it calls `addToSendQueue` with no `checkThreadAndEnqueue`.
  - Registration happens only at startup, on the client thread.
- **Persistence:** none.
- **Callers:** `Myau.init` (register) and 13 mixin fire sites. `PerfLog` calls `takeProfile`.
- **Semantics as found** (document these; do not change them silently):
  1. Dispatch is by **exact class** (`REGISTRY_MAP.get(event.getClass())`, :222). Subclass events do not reach supertype listeners.
  2. **Cancelled events keep being delivered** to later handlers (:233-256). Every handler must check `isCancelled()` itself. KnockbackDelay does (`KnockbackDelay.java:307`); FlagDetector and FightLog do not.
  3. The disabled check runs **per handler at dispatch time** (:234, :248). A module disabled by an earlier handler of the same event is skipped by the later ones.
  4. Order within the same priority = registration order × `getDeclaredMethods()` order. The latter is JVM-unspecified; this only matters for two same-event handlers in one class.
  5. Exceptions: a handler's `InvocationTargetException` is caught, stack-printed, and dispatch continues (:283-291). There is no rate limit, the module name is only in the stack, and the handler's half-done state mutation stays.
  6. Cancel attribution records the **first false→true transition** only, and only for `Module` sources (:239, :262-266). A cancel by a manager, a cancel by DelayManager (which runs before the event, `MixinNetworkManager.java:32`), and un-cancel/re-cancel sequences are not attributed by this path.
- **Failure modes:** F-01, F-02, F-03, F-04 and F-05 (§8).

### 2.2 ActionLedger (`util/ActionLedger.java`)
- **Responsibilities:** a 15-second ring of `(time, module, kind)` entries (:48-49, CAP 512).
- **Inputs:**
  - Every attributable SEND packet, with the module found by stack walk (`MixinNetworkManager.java:57-59`, `ActionLedger.java:90-103`).
  - Cancel transitions from the EventManager.
  - Manual notes from BlinkManager, LagManager and FakeLag.
- **Outputs:** `within(ms)`, `summary()` and `kindsFor()`, read only by FlagDetector (`FlagDetector.java:941-944, 994`).
- **Threading:** a `synchronized(LOCK)` block. Writes come from both the Netty and client threads.
- **Persistence:** none. It is **never cleared**: `clear()` has no caller.
- **Attribution model:** coincidence, not cause.
  - Every module with any entry in the window is implicated **equally** (`FlagDetector.java:941-944`).
  - `cancelMatters` excludes only an explicit cosmetic list (:143-150). BackTrack's `held-recv` of **other entities'** movement packets (S14/S18) counts as evidence against a correction of **our own** position.
  - `held-send` is stamped when a packet is **held**, not when it is released. The release burst is usually what the server reacts to.
- **Failure modes:** F-10 and F-11.

### 2.3 FlagDetector (`module/modules/FlagDetector.java`, 1453 lines)
- **Responsibilities:** detects S08 corrections, placement rejects, timer orphans and drain stalls. It classifies each one (LAGBACK, VERTICAL, TELEPORT, REJECT, RELOCATE, BLOCKED-BY-BODY), does blame, keeps the HUD list, and writes logs.
- **State:**
  - Session counters (`resetSession`, :298-318).
  - `pendingPlacements` (HashMap).
  - `implicated` (HashMap of name → timestamps, :874).
  - Sent position and rotation, and ticks-since-X counters.
- **Inputs:**
  - SEND C03/C02/C07/C08, handled on the event thread (:479-530).
  - RECEIVE S01/S07/S12/S23/S08, which are **re-scheduled to the client thread** with `mc.addScheduledTask` (:545-567). This is correctly ahead of vanilla's own enqueue.
- **Outputs:**
  - Chat, the HUD, and `flags-*`/`places-*` via AsyncLog.
  - `Adaptive.observeFlag()` (:1154) → Brain.
  - `implicated` read by FlagResponder.
  - `resetCounters`, `violationCount` and `sessionMillis` for AutoTune.
- **Lifecycle:** `resetSession` runs on S01 JoinGame and on enable. It does **not** clear `implicated` (F-12).
- **Failure modes:** F-12, F-13 and F-14.

### 2.4 Brain (`util/Brain.java`)
- **Responsibilities:** per-server persistent statistics.
  - For each flag signature and module: flags while the module was on vs off.
  - For each module: minutes on vs minutes off.
  - `suspects()` compares the rates. Confidence = thinner side / 16 minutes.
- **State:** static `KINDS`, `EXPOSURE` and `serverKey`. `synchronized` static methods.
- **Inputs:** `Adaptive.observeFlag` → `noteFlag` (Netty or client thread), and `Adaptive.onTick` → `noteExposure`.
- **Outputs:** `suspects()` and `leastKnown()` for Adaptive.
- **Persistence:**
  - `config/Myau/brain-<serverIP-sanitised>.txt`. The format is `brain-1` followed by tab-separated E/K/F lines.
  - It is saved on a server switch, on every Adaptive decision, at probe end, on Adaptive disable, and from the shutdown hook.
  - There are no timestamps, no decay, no firstSeen/lastSeen, no session boundary, and no per-server identity beyond the typed hostname.
- **Failure modes:** F-15, F-16, F-17 and F-18. Evidence of past contamination exists on disk: `brain-play.pika-network.net.txt.contaminated` (2026-09-19).

### 2.5 Adaptive (`module/modules/Adaptive.java`) — OFF
- **Responsibilities:** every `decide-minutes` (5), acts on the best Brain suspect with confidence ≥ 0.55.
  - "Acting" means turning a knob down (Reach.range, BackTrack delay, KnockbackDelay.chance) or disabling the module.
  - Otherwise it **probes**: it disables the least-known focus module for 8 minutes.
- **State:** a `restore` map (module → Boolean), `probing`, `probeUntil`, `currentServer` and `unprobeable`.
- **Server detection:** it compares `ServerData.serverIP` every tick (:280-285).
- **Persistence:** `adaptive.txt` (a synchronous FileWriter on the client thread) and Brain.
- **Shutdown:** its own hook runs `restoreAll()` and `Brain.save()` (:158-164). This hook calls `Module.setEnabled`, and so `onEnabled`, **from the shutdown thread**.
- **Failure modes:** F-06, F-19 and F-20.

### 2.6 AutoTune (`module/modules/AutoTune.java`) — OFF
- **Responsibilities:** round-robins through knobs (Reach.range, both BackTrack delays, and optionally KillAura).
  - It runs a 5-minute trial and computes `score = hitRate − flags/min × 6`.
  - It keeps the new value if `score > baseline` (:293), then moves on.
- **State:** knobs (startValue, step, direction, pendingFrom), baseline, trial timer.
- **Side effects on other modules:**
  - `FlagDetector.resetCounters()` and `HitCheck.resetCounters()` at every trial start (:241-254). This wipes the counters FlagDetector's HUD shows as "session".
  - It writes the Reach and BackTrack properties directly.
- **Persistence:** `autotune.txt`. It reverts on `onDisabled` only. There is **no shutdown hook** (F-07).
- **Failure modes:** F-07, F-21 and F-22.

### 2.7 ServerProfiles (`module/modules/ServerProfiles.java`) — OFF
- **Responsibilities:** on S01 (Netty) it records the target, then on the next client tick it runs `new Config(target,false).load()` (:81-103, :147).
- **Effect:** loads **every module's properties and toggles**, including AutoTune, Adaptive, LatencyGovernor, FlagResponder and ServerProfiles itself.
- **Failure modes:** F-08 and F-09.

### 2.8 LatencyGovernor (`module/modules/LatencyGovernor.java`) — OFF
- **Responsibilities:** takes one ping sample per second over a 40-sample ring, computes the mean and the mean absolute deviation, and derives a tier.
  - Tier 0 re-samples the "baseline" from whatever the settings currently are (:280-284, :322-333).
  - Tier 1/2 scale the BackTrack delays and cap Reach.range.
  - Tier 2 **disables** LagRange, Blink, ServerLag and FakeLag.
- **Input:** `Ping.own()`, which is the tab ping, or HitTimer's measured delay when the tab shows < 2 (Hypixel).
- **Failure modes:** F-24, F-25 and F-26.

### 2.9 Arbiter (`management/Arbiter.java`)
- **Responsibilities:** two volatile flags.
  - `catching` (owner name): set by Clutch only (`Clutch.java` `setCatching`).
  - `viewHeld`: set by Clutch.
  - A `yielded` set, drained by Clutch for its trace.
- **Readers:**
  - LagManager, BlinkManager, FakeLag, BackTrack and KnockbackDelay (they release and stop holding).
  - SafeWalk and Eagle (they stand aside).
  - FlagDetector (context).
- **Not consulted:** **ServerLag** (inbound hold) and **DelayManager** (pre-event inbound hold). A catch does not release them.
- **Model:** a single owner, last writer wins. `setCatching(false)` by a non-owner is a no-op. This is correct.
- **Failure modes:** F-27 (partial coverage).

### 2.10 Other components in the loop (brief)
- **FlagResponder** (ON, dry-run): every second, for each implicated module with ≥ 5 implications in 30 s, it disables the module (if not dry-run) and clears its implications. It uses FlagDetector's equal-weight coincidence blame. Its PROTECTED list omits Adaptive, AutoTune and ServerFingerprint.
- **HitTimer** (`management/HitTimer.java`): measures attack→S19 hurt over 20 samples.
  - It resets on a server change **only when the next attack is sent** (:95-104). Until then `Ping.own()` on the new server returns the old server's figure.
- **AsyncLog:** see F-28 and F-29.
- **PerfLog:** see F-30.
- **NotificationManager:** see F-31.

---

## 3. Dependency graph (from the code, not from names)

```text
                       ┌──────────────────────────── server ◄───────────────────────────┐
                       ▼                                                               │
 Mixins ──► EventManager ──(cancel transition)──► ActionLedger ◄── BlinkMgr/LagMgr/FakeLag (manual notes)
   │  RECEIVE on Netty  │                               │                               ▲
   │  SEND on client    ▼                               ▼                               │
   │            Modules' handlers                FlagDetector ──(implicated map)──► FlagResponder ──setEnabled──┐
   │                    │                           │   │                                                       │
   │                    │                           │   └─observeFlag──► Brain ◄──exposure── Adaptive ──setEnabled/knob.set──┤
   │                    │                           │                                    ▲                                   │
   │                    │                           └─resetCounters/violationCount◄── AutoTune ──knob.set──────────────────────┤
   │                    │                                                                                                   ▼
   │                    │    Ping ◄── HitTimer ◄── (C02 send / S19 recv)               BackTrack.delay, Reach.range, KB.chance
   │                    │     └──► LatencyGovernor ──scale/cap──────────────────────────────►  (same properties)          │
   │                    │                      └──disable──► LagRange/Blink/ServerLag/FakeLag                              │
   │                    │    ServerProfiles ──Config.load──► ALL properties + ALL toggles (incl. the tuners themselves) ──┘
   │                    ▼
   └──► DelayManager (pre-event inbound hold) ; BlinkManager / LagManager (post-event outbound hold)
 ServerFingerprint: leaf (reads packets, writes servers.txt / server-profiles.csv, read by nobody)
 Arbiter: Clutch writes; FakeLag/LagMgr/BlinkMgr/BackTrack/KBDelay/SafeWalk/Eagle read
```

### Cycles found

| # | Cycle | Kind |
|---|---|---|
| C1 | Module X acts or holds → ActionLedger → S08 → FlagDetector.implicate(X) → FlagResponder → X.setEnabled(false) | Behavioural loop, closed through the server. Blame is equal-weight coincidence. |
| C2 | X on → Brain on-side flags → Adaptive.act → X knob↓ → (evidence not reset) → next decision acts on the **same** evidence again → knob↓ … floor | Ratchet (F-19) |
| C3 | Adaptive probe disables X → produces X's off-side exposure → Brain.leastKnown chooses the next probe | Intended experimental loop. Its evidence is confounded by the user's own toggles. |
| C4 | AutoTune writes BackTrack.delay ↔ LatencyGovernor reads it as the "user baseline" at tier 0 and rescales it at tier 1/2 ↔ AutoTune reverts to `pendingFrom` (pre-governor) | Write–write conflict, no owner (F-24) |
| C5 | ServerProfiles `Config.load` → `AutoTune.setEnabled(false)` → `AutoTune.onDisabled` writes BackTrack.delay **after** BackTrack's just-loaded value (BackTrack precedes AutoTune in the LinkedHashMap, `Myau.java:98` vs `:159`) | Load-order clobber (F-09) |
| C6 | AutoTune → FlagDetector.resetCounters() every trial | A consumer mutating a producer's state |

There are **no circular class-initialisation or constructor dependencies**. All cycles are runtime state cycles.

---

## 4. Lifecycle map

### 4.1 Client
`startGame RETURN` → `new Myau()` → managers and modules constructed → properties reflected → all registered → `default.json` loaded (modules toggled, `onEnabled` run with **no world**) → friend/target files → fonts/GUI → **shutdown hook: save default.json** → ViaMCP.

### 4.2 "Server session": there is no single definition

| Component | New-server signal | Clears |
|---|---|---|
| FlagDetector | S01 JoinGame | Session counters. **Not** `implicated`. |
| ServerFingerprint | S01 where the address ≠ the stored address (on Netty) | Everything |
| ServerProfiles | S01 → next tick | — (loads a profile) |
| Adaptive / Brain | `serverIP` compared every tick | Brain swaps its file |
| HitTimer | `serverIP` compared **on the next attack** | Samples |
| LatencyGovernor | none | Nothing. Pings carry over. |
| LagManager / BlinkManager / DelayManager | C00 Handshake/LoginStart/etc. SEND, and LoadWorld (DelayManager) | Queues |
| FightLog, AntiBot, BedTracker, … | LoadWorldEvent | Their own state |
| ActionLedger | never | Relies on 15 s retention |

**Disconnect** has no event of its own. `Minecraft.loadWorld(null)` fires `LoadWorldEvent` (`MixinMinecraft.java:100-106`). That is the natural hook for a session end, but nothing uses it as one.

### 4.3 Shutdown
The JVM runs **all shutdown hooks concurrently, in unspecified order**:
1. `Config.save` of **default.json** (`Myau.java:283`).
2. Adaptive `restoreAll()` and `Brain.save()` (`Adaptive.java:158`).
3. AsyncLog drain (`AsyncLog.java:73`).

Temporary values from AutoTune trials and LatencyGovernor scaling have **no** shutdown restore.

---

## 5. Threading map

| Thread | What runs there |
|---|---|
| Client thread | Tick/Update/Render/Key/LoadWorld/MoveInput events; most SEND events; `setEnabled` from GUI, keys, config and tuners; scheduled tasks. |
| Netty IO | **All RECEIVE events**; DelayManager.shouldDelay; **SEND of C00 keep-alive** (vanilla, verified) → `LagManager.handlePacket` → `flushQueue()`; ServerProfiles S01 handler; ServerFingerprint's whole receive path; HitTimer S19; FightLog S12 (onto a concurrent queue, OK); BackTrack/KnockbackDelay/ServerLag cancels. |
| Myau-AsyncLog | File appends (daemon) |
| Shutdown hooks (×3) | Config save; Adaptive restore (calls `Module.setEnabled`) and Brain save; AsyncLog drain |
| Others | ViaMCP, account manager (out of scope) |

**Thread-safety design in the codebase:** it is ad hoc.
- Some components re-schedule onto the client thread: FlagDetector, HitCheck, FakeLag parts, ServerLag.
- Some use concurrent collections: the packet queues and FightLog velocities.
- Some use `volatile`: Arbiter, BackTrack, KnockbackDelay, FakeLag.
- Some do none of these: ServerFingerprint, the LagManager fields and the BlinkManager fields.

---

## 6. Persistence map

| File (config/Myau/) | Writer | When | Atomic? | Schema/version |
|---|---|---|---|---|
| `<profile>.json` | `Config.save` (hook, commands, 3 GUIs) | Exit (default.json only), on demand | **No** (FileWriter truncate) | None |
| `brain-<host>.txt` | Brain | Server switch, 5-min decisions, probe end, disable, exit | **No** | `brain-1`. A mismatch is ignored, and the file is later **overwritten**. |
| `adaptive.txt`, `autotune.txt`, `servers.txt`, `server-profiles.csv` | Their modules, synchronous on the client thread | Per event | Append | None |
| `flags-*`, `places-*`, `fights-*`, `hits-*`, `fakelag-*`, `clutch-*`, `perf-*` | AsyncLog | Queued | Append per entry | None |
| friends/targets | Friend/TargetManager | On change | — | — |

---

## 7. Packet ownership map

| Holder | Direction | Queue | Hold mechanism | Release path | Visible to ledger? | Obeys Arbiter? |
|---|---|---|---|---|---|---|
| DelayManager (BedNuker) | in | own CLDeque | **Before the event** (mixin) | `processPacket` on a tick/disable | **No** | **No** |
| BackTrack | in | own CLQueue | Event cancel | `processPacket` (client) | Yes (held-recv) | Yes |
| KnockbackDelay | in | own CLQueue | Event cancel (HIGHEST) | `processPacket` | Yes | Yes |
| ServerLag | in | own CLQueue | Event cancel (HIGHEST) | `processPacket` via scheduled task | Yes | **No** |
| FakeLag | out | own CLQueue | Event cancel | `sendPacketNoEvent` → **re-enters** BlinkManager/LagManager | Yes (+manual) | Yes |
| BlinkManager (8 owners) | out | shared CLDeque | Mixin after the event | `sendPacketNoEvent` → re-enters LagManager | Manual note | Yes |
| LagManager (LagRange, BlockHit mode 2) | out | shared CLDeque | Mixin after the event | Per-packet flush on **any** send and on tick POST | Manual note, **wrong names** (F-11) | Yes |

**Rules the code actually enforces:** Clutch's `catching` → the outbound holders and BackTrack/KBDelay release everything.

**Rules it does not enforce:**
- Single ownership of BlinkManager (F-32).
- Single writer of `LagManager.tickDelay`. Last write wins; today it is correct only because BlockHit (MEDIUM) runs before LagRange (LOW) on tick PRE.
- Ordering between FakeLag's release and a Blink hold.
- ServerLag/DelayManager versus a catch.

There is no structure that answers "who holds packet P, why, since when, until what".

---

## 8. Failure points

Each entry gives: **ID** — subsystem — evidence — classification — proven/suspected, live/latent — severity.

- **F-01** EventManager — `cleanMap(false)` calls `iterator.remove()` without `next()` (:156-163). This throws IllegalStateException. No caller passes false. — BUG FIX — PROVEN, LATENT — low
- **F-02** EventManager — duplicate registration is not prevented: `contains(data)` on a `MethodData` without `equals` (:119). — BUG FIX — PROVEN, LATENT — low
- **F-03** EventManager — REGISTRY_MAP is an unsynchronised HashMap, read from the Netty and client threads. It is safe only because registration is startup-only; any runtime (un)register would race. — ARCH — PROVEN, LATENT — medium
- **F-04** EventManager — cancelled events are delivered to all later handlers (:233-256), and many handlers do not check. This is part of the API and must be documented and tested, not changed. — OBSERVABILITY — PROVEN — n/a
- **F-05** EventManager — handler exceptions are printed with no module name or rate limit. Partial state mutation stays. — OBSERVABILITY — PROVEN, LIVE — low
- **F-06** Shutdown — three concurrent hooks. Adaptive's "restore first" has **no ordering guarantee** against `Config.save`, so a probed (disabled) module can be saved as off. Adaptive's hook calls `setEnabled` (→ `onEnabled`) off the client thread. — BUG FIX — PROVEN, LATENT (Adaptive off) — **high when on**
- **F-07** AutoTune — trial values are **persisted on quit**: there is no shutdown revert, and `Config.save` runs. On the next start the config re-enables AutoTune and `onEnabled` takes the trial value as the new `startValue`. A 5-minute trial becomes the configuration. — BUG FIX — PROVEN, LATENT — **high when on**
- **F-08** Config / ServerProfiles — the exit hook **always writes default.json** (`Myau.java:270,283` captures the default instance). After ServerProfiles (or `.config load X`) loads profile X, quitting writes X's values into **default.json**. ServerProfiles' own javadoc (:33-36) claims the opposite. — BUG FIX / BEHAVIOR — PROVEN, LIVE for manual profile loads — **high** (server-to-server contamination)
- **F-09** Config.load — order clobber (C5). `Config.load` toggles modules mid-loop; `AutoTune.onDisabled` overwrites BackTrack's freshly loaded delays. More generally, any `onEnabled`/`onDisabled` that writes another module's settings is order-dependent during a load. — BUG FIX — PROVEN, LATENT — medium
- **F-10** ActionLedger — equal-weight coincidence. Every module active within 2 s gets one implication per flag. The ledger counts held **other-entity** inbound packets as evidence against our own correction. Holds are stamped at hold time, not release time. — ARCH (Phase 3) — PROVEN, LIVE (feeds FlagResponder, dry-run) — medium
- **F-11** LagManager — `currentHolder()` (:110) names LagRange, ServerLag, FakeLag and Blink. **ServerLag, FakeLag and Blink never use LagManager**, and **BlockHit does** (`BlockHit.java:253`). `FlagDetector.recordSuspects` (:914-921) repeats the wrong list. Holds are attributed to the wrong modules. — BUG FIX (attribution) — PROVEN, LIVE — medium
- **F-12** FlagDetector — `implicated` survives server changes and disable (`resetSession` :298-318 does not clear it). FlagResponder can act on server A's blame on server B for up to its window. — BUG FIX — PROVEN, LIVE (dry-run) — low/medium
- **F-13** FlagDetector — the RECEIVE path does not check `isCancelled()`. An S08 that another handler cancels and later replays is analysed at **arrival**, against the player's position at arrival. — SUSPECTED impact — LIVE when BackTrack/KBDelay/ServerLag hold S08 — low
- **F-14** FlagDetector — `pendingPlacements` (HashMap) is mutated on the SEND path. That path is normally the client thread, but it runs on the thread of any caller of `sendPacket`. — SUSPECTED — low
- **F-15** Brain — non-atomic save. A crash mid-write gives a truncated file. On load, a parse exception **clears all evidence** (:356-358). A silently truncated file loads partially. Either way the next save overwrites the good data. — BUG FIX — PROVEN — medium
- **F-16** Brain — a version mismatch is "left alone" on load (:326-331), but the next `save()` of the (empty) in-memory state **overwrites** it. — BUG FIX — PROVEN — medium
- **F-17** Brain — no timestamps, decay or session split. Old evidence dominates forever, which the brief explicitly calls out. — ARCH (Phase 4) — PROVEN — medium
- **F-18** Brain — `exposure()` and `kinds()` are unsynchronised, and `kinds()` returns the **live map**. The key is the hostname as typed, so `mc.hypixel.net` and `hypixel.net` are two brains. — BUG FIX — PROVEN — low
- **F-19** Adaptive — **ratchet**. `act()` does not record that it acted on a piece of evidence, so the same historical suspect triggers again every 5 minutes until the knob reaches its floor (:312-331, :340-367). — BUG FIX — PROVEN, LATENT — **high when on**
- **F-20** Adaptive — knob changes are **not restored** on disable. The `restore` map only holds Booleans (:137, :351). This contradicts its javadoc ("restores everything it changed"). — BUG FIX — PROVEN, LATENT — high when on
- **F-21** AutoTune — a single noisy trial versus a single noisy baseline, compared with `score > baseline` with no margin (:293). A lucky trial raises the baseline, so later honest trials fail (winner's curse). — ARCH (Phase 7) — PROVEN — medium
- **F-22** AutoTune — both BackTrack delays are tuned, but only one is active (`adaptive` flag). Trials of the inactive one are pure noise, yet can be "KEPT". — BUG FIX — PROVEN, LATENT — low
- **F-23** ItemPhysics — `instance` and `getRotationSpeed()` have **no readers** anywhere (repo-wide grep). There is no mixin on item rendering. The module is functionally inactive, and it is ON in the config. — BEHAVIOR (decide: restore the hook or remove/mark it) — PROVEN, LIVE — low
- **F-24** LatencyGovernor — no ownership. It takes the baseline from whatever the value currently is, including AutoTune, Adaptive and profile writes, and it overwrites them. — ARCH (Phase 5) — PROVEN, LATENT — medium
- **F-25** LatencyGovernor — **no hysteresis or dwell**. It uses the same threshold to enter and exit (:310-315), and the jitter bump (:277-279) toggles independently. The user's ~225 ms sits under the 250 ms high threshold and flaps. — BUG FIX (Phase 8) — PROVEN, LATENT — medium
- **F-26** LatencyGovernor — tier 2 disables four modules "for this connection" (:382) but **never re-enables them**. On quit they are saved as off. Governed (reduced) values are also saved on quit, and the next session takes them as its baseline, so they ratchet down. — BUG FIX — PROVEN, LATENT — high when on
- **F-27** Arbiter — ServerLag and DelayManager ignore `catching`. — BUG FIX — PROVEN, LATENT — low
- **F-28** LagManager — `flushQueue()` runs on **Netty** (the C00 keep-alive send → `handlePacket` → `flushQueue`, :76) concurrently with the client thread's tick flush. The loop is non-atomic (`peek`, send, `poll`), so two threads can **send the same packet twice and drop the next one**. The `flushing` and `tickDelay` fields are plain (not volatile). — BUG FIX — PROVEN (race by construction; frequency unknown), LIVE with LagRange — **high**
- **F-29** AsyncLog — an unbounded queue. An `Error` in the worker kills it permanently: `worker != null`, so it is never restarted, and all logs are lost silently. An entry taken by the worker during the exit drain can be written out of order or lost. — BUG FIX — PROVEN — low
- **F-30** PerfLog — its comment says "only while in a world", but TickEvent is only fired with a world, so this holds. It is always on (not a module), writing `perf-*.txt` every 60 s. — OBSERVABILITY — PROVEN — info
- **F-31** NotificationManager — entries are pruned **only when HUD renders them** (`getActive`). With HUD or notifications off, every module toggle leaks one entry. `messageCooldowns` is never pruned. `clear()` has no caller (not on world change or disconnect). — BUG FIX — PROVEN — low
- **F-32** BlinkManager — **ownership steal**. `setBlinkState(true, B)` overwrites the current owner A with no check (:111-112). A's later `setBlinkState(false, A)` returns false **without releasing** (:115), so A believes it released while its packets stay held under B. Blink (`Blink.java:486`) and Clutch (`Clutch.java:1695`) force-release other owners by design. — BUG FIX — PROVEN mechanism; LIVE whenever two blink owners overlap (AntiVoid, Scaffold safe-stuck, NoFall, KillAura AUTO_BLOCK, …) — medium
- **F-33** ServerFingerprint — every collection (TreeSet, TreeMap, LinkedHashMap and Stats) is mutated on **Netty** and iterated on the client thread in `report()`. It can throw ConcurrentModificationException or produce torn reports. `resetAll()` runs on Netty. — BUG FIX — PROVEN, LIVE — low/medium
- **F-34** HitTimer — samples from the previous server remain until the first attack on the new one. `Ping.own()` feeds them to LatencyGovernor, KillAura and HitCheck in the meantime. — BUG FIX (fold into ServerSession) — PROVEN — low
- **F-35** FightLog — swings are recorded on C02 SEND **even when cancelled**: REPEL merges and FakeLag holds. They are stamped at click time with the click-time sprint state, while the held hit really leaves later via `sendPacketNoEvent`. This affects REPEL's sprint-hit % and swing matching. — BUG FIX (measurement) — SUSPECTED magnitude, PROVEN mechanism — medium (it is your A/B instrument)

---

## 9. Implementation plan (ordered by risk × dependency)

Each step: small, compiled, `git`-less, so a `backups/src/src-before-<tag>` snapshot is taken first. The diff is inspected before continuing.

| Step | What | Brief phase | Class | Risk | Depends on | Decision needed from you |
|---|---|---|---|---|---|---|
| **1** | Add JUnit 4 (`testImplementation`), and prove `./gradlew test` runs under loom with a trivial test | prereq | infra | low (build only) | — | — |
| **2** | EventManager characterisation tests (the 8 in the brief, plus "cancelled still delivered", "disabled mid-dispatch", "exact-class dispatch"). Fix F-01/F-02 only. Add a package-private test reset (a new API, stated). Document the semantics. | 1 | tests + BUG FIX (latent) | low | 1 | — |
| **3** | **Packet safety:** F-28 (LagManager flush confined to the client thread or made atomic), F-32 (BlinkManager ownership: refuse or queue a second owner, log it), F-11 (correct holder names) | 9 (subset) | BUG FIX | **medium** (touches live packet paths) | 2 | F-32: when B asks while A holds, should B be refused, or should A be released first? |
| **4** | **Single ordered shutdown:** one hook, in this order: (a) revert temporary overrides → (b) save config → (c) Brain save → (d) AsyncLog drain. Fixes F-06/F-07 and part of F-26. | 2/5 | BUG FIX | low | 2 | — |
| **5** | **F-08 save target:** decide what exit saves after a profile is loaded | 5 | BEHAVIOR CHANGE | low code, high meaning | 4 | Save to the loaded profile? Save to default only? Save nothing automatically? |
| **6** | **ServerSession:** connect (S01, first per address) / world change (LoadWorld) / disconnect (LoadWorld(null) + handshake) with a session id. Migrate HitTimer, LatencyGovernor, FlagDetector.implicated, Brain.setServer, ServerFingerprint (+ confine its state to the client thread, F-33), and Adaptive to it. Tests: A→B isolation, and reconnect to the same server. | 2 | ARCH REFACTOR | medium | 4 | Server identity: hostname as typed, or resolved IP/port? |
| **7** | **Value ownership / precedence:** `Default ← Profile ← (persistent learned) ← Runtime override (Governor) ← Trial (AutoTune)`. Overrides are layers with source+timestamp, never persisted; the user's value is the base. Fixes C4/C5, F-09, F-20, F-24 and F-26 structurally. | 5 | ARCH REFACTOR | **medium-high** (Property system) | 4, 6 | Approve the precedence order |
| **8** | LatencyGovernor hysteresis + dwell + re-enable what it disabled (F-25/F-26). Rapid-latency tests. | 8 | BUG FIX | low | 7 | Enter/exit thresholds |
| **9** | Brain persistence: atomic write (tmp+rename), schema `brain-2` with firstSeen/lastSeen/sessions, decay or recency weighting, migrate `brain-1` without overwriting, corrupt → quarantine not wipe (F-15..F-18). Tests: save/load/corrupt/missing/old/new/different servers. | 4 | BUG FIX + ARCH | medium | 6 | Decay half-life |
| **10** | Adaptive safety pipeline: evidence → decision record (detector, evidence, suspect, confidence, old/new, reason). Act once per evidence epoch (F-19), knob restore (F-20), no action in the first N minutes of a session, anti-oscillation. | 6 | BUG FIX + ARCH | medium | 7, 9 | — |
| **11** | AutoTune states `IDLE→TRIAL→EVALUATING→VALIDATING→COMMITTED/EXPIRED`, margin + repeat validation, skip the inactive knob (F-21/F-22), stop resetting FlagDetector's counters (use its own counter window, C6). | 7 | ARCH | medium | 7, 10 | — |
| **12** | ActionLedger attribution: candidates with confidence, stamp at release, weight by kind (a held outbound move ≫ a held other-entity inbound), UNKNOWN/INCONCLUSIVE/MULTIPLE_CANDIDATES; FlagResponder only acts on confident single culprits. | 3 | ARCH | medium | 3, 6 | — |
| **13** | Packet ownership registry (who/why/since/until) + Arbiter coverage for ServerLag/DelayManager (F-27). | 9 | OBSERVABILITY + BUG FIX | medium | 3, 12 | — |
| **14** | AsyncLog: bounded queue with a drop counter, worker restart, ordered exit drain (F-29). FightLog: count only sent attacks (F-35). | 11 | BUG FIX | low | 4 | F-35 changes your FightLog numbers going forward; note the break in comparability |
| **15** | Shared-utility tests (RotationEngine with a seeded random, TargetFilter, HealthUtil sources). Some need a small seam, because they read `mc` and static state directly. | 10 | tests | low | 1 | — |
| **16** | Notifications lifecycle (F-31), ItemPhysics decision (F-23) | 12, 13 | BUG FIX / BEHAVIOR | low | — | ItemPhysics: re-implement the hook, or remove the module? |
| **17** | Developer diagnostics overlay/command reading the session, governor, ledger, Brain, Adaptive, AutoTune and packet-holder registry | 14 | OBSERVABILITY | low | 6–13 | — |
| **18** | Final verification (Phase 15). Build + tests here. **Startup, connect/reconnect, world change and GUI can only be verified by you in game.** I will give a checklist and read the logs afterwards. | 15 | — | — | all | — |

**Why this order and not the brief's:**
- F-28 and F-32 are live packet bugs, and F-08 is live contamination. They are cheap, isolated fixes that do not need the new architecture, so they come first (steps 3–5), right after the test harness.
- ServerSession (6) and value ownership (7) are the two foundations every later phase writes through. Doing Brain, Adaptive or AutoTune before them would mean rewriting those phases.
- Attribution (12) comes after packet ownership is correct, because the ledger's input is the holders.

**Limits I cannot remove:** there is no git, so snapshot diffs replace `git diff`. There is no automated in-game test; runtime behaviour is verified by you playing, and by the logs.

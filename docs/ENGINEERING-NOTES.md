# OpenMyau+ — Engineering Notes

Everything learned building and debugging this client, written down so it does
not have to be learned again. Facts first, then the patterns behind the bugs,
then the workflow.

Target: Minecraft 1.8.9 Forge, `play.pika-network.net` Bedwars/BedFight, real
ping **220–235 ms**. Numbers below are measured on that connection unless
stated otherwise — several of the conclusions do not transfer to a 40 ms one.

---

## 1. The single most important law

**N ticks of movement cannot be delivered to the server in one tick.**

A vanilla client emits exactly one `C03PacketPlayer` per tick. Everything a
server's speed check measures follows from that. If a module withholds
movement and then hands it back, the only rate that is not a violation is
**one position packet per tick**, and at that rate it never catches up —
because the client is still producing one per tick while you release one per
tick.

There is no arrangement of packets that escapes this. Not two per tick, not
"a couple", not bursting only occasionally.

The only ways out:

| Way out | What it costs |
|---|---|
| Never catch up (permanent lag) | You are just FakeLag now |
| Discard the path, send the destination | The server sees one jump of *drift* blocks |
| Do not hold movement at all | No effect |

Where the drift is small enough that latency already explains it (~1–3 blocks
at 220 ms), the jump is accepted. That is what `max-drift` is for, and it is
the only lever that actually decides whether a release gets corrected.

**This law was violated three separate times in three different modules, by
me, each time with a different-looking symptom.** See §5.

---

## 2. Server facts (1.8.9 / Pika)

### Transactions kick, they do not flag

`C0FPacketConfirmTransaction` is a **reply the protocol owes the server**, not
an action the client chose. The server sends `S32PacketConfirmTransaction` and
times the answer. Hold it and Pika disconnects with:

```
Unexpected pong response -380 -366 window: 0
```

The numbers are the transaction ids it gave up waiting for.

**Grim is the opposite (2026-10-02, test.ccbluex.net).** Grim times the client by its replies. If the
replies keep flowing while movement is held, the release is "more movement than the clock allows":
Timer, then a setback. Held *in order with* the movement, a hold looks like lag. That is why Blink has
`hold-transactions`, on by default; see the Blink sections of 2026-10-02.

**And Pika did not kick (2026-10-02 22:57-22:59).** With replies held in order (all of them, from any
thread, released with the movement), 5 Blink holds on Pika gave no disconnect, and the user called it "very
smooth". The kick above most likely came from the drain bug (§5 Blink), whose queue never emptied and so held
replies for seconds without end. That is an inference, not a measurement. The rule below still holds for
anything that *drops* or *reorders* a reply.

Measured cadence on Pika: **~26 ms on window 0, about 37/second.** Something
is actively timing the client at a rate far above vanilla's needs.

**Never delay:** `C0FPacketConfirmTransaction`, `C00PacketKeepAlive`,
`C01PacketChatMessage`, window clicks (`C0EPacketClickWindow`,
`C0DPacketCloseWindow`), `C10PacketCreativeInventoryAction`,
handshake/login/status packets.

### Use an allow list, never a deny list

> A deny list is one forgotten packet away from a disconnect.
> An allow list is one forgotten packet away from that packet not being delayed.

The safe delayable set, arrived at empirically:

```java
C03PacketPlayer     // and its C04/C05/C06 subclasses
C02PacketUseEntity
C0APacketAnimation
```

Everything else goes immediately.

### Placements are judged against the server's copy of your position

`canBlockBePlaced` → `checkNoEntityCollision`. Two consequences:

1. **A block cannot be placed inside an entity's bounding box.** At 220 ms the
   server's copy of an opponent is behind where you see them, which is why
   blocking someone off at high ping produces `REJECT` that looks unexplained.
   Distinguish this from a genuine refusal by sampling the nearest opponent's
   body distance per tick (`BLOCKED-BY-BODY` vs `REJECT` in FlagDetector).
   The user confirms this from play (2026-09-24): at high ping, blocking an
   opponent off gets refused routinely because the server registers the block
   where the opponent already stands. **But not every unexplained `REJECT` is
   this.** The six on 2026-09-24 19:43 (BedFight, bridging and pillaring on
   the middle lane, ~190 ms) had the nearest opponent at 6.4 blocks or more,
   or no other player loaded at all (the opponent had just fallen into the
   void). Those are still unexplained. FastPlace and Eagle act through vanilla
   input, so ActionLedger can never name them; both have been on for every
   minute the Brain has recorded, so it cannot compare them either.
2. **Anything that keeps your own position stale gets placements refused.**
   Delaying movement and then placing a block is asking to be refused. Modules
   that hold movement must flush the queue *before* a placement, dig, held-item
   change or sprint toggle — those must always be judged against the truth.

### Corrections feed back on themselves

When `S08PacketPlayerPosLook` arrives, the server has just stated where you
are. Every held position describes somewhere you were *before* it said so.
**Sending them asks to be corrected again**, and if that correction also
triggers a flush you have a loop that ends in a kick.

Discard held positions on a correction. Keep the actions (attack, swing,
placement) — those are things the player did.

**But never hold or discard the client's reply to the correction.** Verified
by decompiling 1.8.9 `NetHandlerPlayServer`:

- `setPlayerLocation` (every S08) sets `hasMoved = false`.
- While `hasMoved` is false the server **ignores every movement packet** until
  one arrives within 0.5 blocks of the teleport target (`d3 < 0.25`).
- While `hasMoved` is false **every block placement is refused**
  (`processPlayerBlockPlacement`: `if (this.hasMoved && …)`).

Vanilla's `handlePlayerPosLook` answers with a `C06` at exactly the target;
that is the packet that clears the state. It is a `C03` subclass, so any
allow-list holder will hold it. FakeLag held it and then discarded it with the
stale positions, leaving the server ignoring the player: "stuck, cannot move",
plus REJECTs with nobody within 18 blocks. Let the first `C06` after an S08
through untouched.

### The invulnerability window

10 ticks = 500 ms. The client's `hurtTime` runs roughly **one full round trip
behind** the server's, and always *overstates* the remaining invulnerability:

```java
predicted = hurtTime - ping / 50.0F
```

A second attack inside the window cannot deal damage, so sending one is pure
signal with no benefit — and at a high click rate it is the clearest tell there
is.

Track the window in **wall time, not self-counted ticks**. `UpdateEvent` does
not fire exactly once per tick; counting it produced 11 attacks in 2 seconds
where at most 3 could land.

### Other server-side signatures worth knowing

- `S19PacketEntityStatus` opcode **2** = hurt animation. Use it to tell a hit
  that landed from one that was silently dropped.
- `S12PacketEntityVelocity` divides by 8000 for blocks/tick.
- `S08PacketPlayerPosLook.func_179834_f()` gives the relative-flag set — needed
  to tell a real teleport from a relative nudge.
- Pika duels move each player to their spawn **2–3 s after `Game starts in
  1...`**: an S08 of 0.4–7.6 blocks with no S07 before it, so neither the
  respawn grace nor `teleport-distance` catches it. FlagDetector files it as
  `RELOCATE` (§5).
- ~~Pika emits an `S3FPacketCustomPayload` that vanilla's brand reader cannot
  parse … not ours. Do not chase it.~~ **Wrong (2026-09-24): it was ours.**
  `ServerFingerprint.readPayload` read the `MC|Brand` string off the packet's
  own buffer in the RECEIVE event, which runs before vanilla's
  `handleCustomPayload` reads the same buffer — vanilla found it already
  consumed: `readerIndex(23) + length(1) exceeds writerIndex(23)`. Fixed by
  reading from `buffer.duplicate()`. **Rule: a PacketEvent listener that reads
  a packet's buffer must never move its reader index.**

---

## 3. Client architecture facts

### Event firing conditions — read this before choosing an event

This is the one that cost the most.

| Event | Fired from | Guard |
|---|---|---|
| `TickEvent` | `MixinMinecraft.runTick` | `theWorld != null && thePlayer != null` |
| `UpdateEvent` | `MixinEntityPlayerSP.onUpdate` | **`worldObj.isBlockLoaded(player x/z)`** |

`UpdateEvent` **stops firing while chunks are loading** — during a server
transfer, a teleport between Pika's lobby and a game, a respawn.

The outgoing packet path (`MixinNetworkManager.sendPacket`) has no such
condition. So:

> **A consumer gated more tightly than its producer is a leak.**

FakeLag held packets off the send path and released them off `UpdateEvent`.
During one server transfer several hundred packets accumulated, the player
could not move (the server had heard nothing), and when chunks arrived the
whole backlog went out at once: **21 lagbacks in 0.3 s, then a kick.**

Use `TickEvent` for anything that must drain a queue.

Ordering within a tick, for reference:

```
Minecraft.runTick  HEAD          -> TickEvent PRE
  EntityPlayerSP.onUpdate HEAD   -> UpdateEvent PRE
    ... physics / movement ...
    onUpdateWalkingPlayer()      -> sends C03/C04/C05/C06
  EntityPlayerSP.onUpdate RETURN -> UpdateEvent POST
Minecraft.runTick  RETURN        -> TickEvent POST
```

### Threads

- `MixinNetworkManager.channelRead0` (RECEIVE) runs on the **Netty thread**.
  Touching `mc.theWorld.loadedEntityList` there is a crash risk.
- `sendPacket` (SEND) is called from the **client thread** (game loop).
- The client thread is named exactly `"Client thread"` — `BlinkManager` uses
  this to refuse to hold anything originating on the network thread, which is
  correct: those are protocol replies, not player actions.
- Pattern for cross-thread work: set a `volatile` flag on the netty thread,
  act on it in the next `TickEvent`. Blink's `stopRequested`, FakeLag's
  `flushRequested`.
- Read state **from the packet**, not from the world, when on the netty thread.
  `S23PacketBlockChange.getBlockState()` gives you the new block without
  touching `theWorld`.
- **Replaying a packet on the netty thread does not run it.** Every vanilla
  handler starts with `PacketThreadUtil.checkThreadAndEnqueue`, which, off the
  client thread, reschedules itself and throws `ThreadQuickExitException`. A
  release loop that does not catch it stops at the first packet. BackTrack did
  this from its receive handler: 799 of these in one session's
  `stderr_stream.log`, each one a release that sent one packet and left the
  rest. Either catch it (per packet — vanilla then runs them in order) or
  schedule the whole release onto the client thread.
- **The standard hand-off** used throughout the 2026-09-24 fixes:
  `mc.addScheduledTask(...)` from the RECEIVE listener. It queues ahead of
  vanilla's own task for the same packet (vanilla enqueues after the event),
  so the listener still sees the world *before* the packet is applied —
  which FlagDetector's S08 comparison and TeamHealthDisplay's health delta
  both need. When the work needs the *new* world instead (ServerProfiles on
  JoinGame), store it and do it on the next `TickEvent`: a scheduled task
  would still run before `handleJoinGame`.

### The two ways a packet gets swallowed, and the attribution blind spot

```java
// MixinNetworkManager.sendPacket(Packet) HEAD
PacketEvent event = new PacketEvent(EventType.SEND, packet);
EventManager.call(event);
if (event.isCancelled()) {
    callbackInfo.cancel();                       // (A) event cancel
} else if (...) {
    if (Myau.blinkManager.isBlinking()) {
        if (Myau.blinkManager.offerPacket(packet)) {
            callbackInfo.cancel(); return;       // (B) mixin swallow
        }
    }
    if (Myau.lagManager.handlePacket(packet)) {
        callbackInfo.cancel();                   // (B) mixin swallow
    }
}
```

`ActionLedger` learns who withheld a packet by watching a `PacketEvent` go
from not-cancelled to cancelled between two listeners — **it only sees (A)**.

Blink, AntiVoid, AutoBlock, NoFall, NoSlow, Displace, Hitflick, LagRange,
ServerLag and FakeLag all used path (B), so for weeks every correction they
caused was attributed to whichever module happened to be cancelling an event
nearby. **Backtrack absorbed the blame for 24 flags it did not cause.**

Fix: path (B) reports itself explicitly —
`ActionLedger.note(moduleName, "held-send")` inside `BlinkManager.offerPacket`
and `LagManager.handlePacket`. Any new swallow path must do the same or it is
invisible.

**This was the user's insight, not mine.** Worth remembering as a class of
bug: a measurement system that only sees one of two code paths reports
confident wrong answers rather than no answer.

### Sending packets from inside the send path

- `PacketUtil.sendPacket(p)` → 1-arg overload → **fires PacketEvent** → your
  own handler will re-queue it. Do not use this to release held packets.
- `PacketUtil.sendPacketNoEvent(p)` → `sendPacket(p, null)` → varargs overload
  → **no PacketEvent**, but still passes through blinkManager/lagManager. That
  is correct composition: if another holder is active it should get the packet
  too.
- **Except when the holder releasing is the blink manager itself.** Sending a
  packet taken out of `blinkedPackets` with `sendPacketNoEvent` while
  `blinking` is still true offers it straight back to the same queue. Use
  `BlinkManager.release(p)`, which suppresses `offerPacket` for that one send.
  (This is what broke Blink's drain for its whole life — see
  `BUG-AUDIT-2026-09-23.md` #1.)
- Re-entering `NetworkManager.sendPacket` from inside a mixin HEAD injection is
  safe — `LagManager` has always done it.

### Config lifecycle — the rule that bites

`Myau.java:255` registers `addShutdownHook(config::save)`.

> **The config is held in memory for the whole session and written once on
> exit. Anything written to `config/Myau/default.json` while the game is
> running will be overwritten.**

Consequences:

- Never edit the config externally while the game runs.
- A module that temporarily disables another module (Adaptive's probing) must
  restore it in its own shutdown hook, or the probed-off state is saved as
  though it were intentional. This actually happened.
- A GUI that wants to remember its own state must use a **separate file**.
  `AtlasClickGui` writes `config/Myau/atlas-ui.txt` for exactly this reason.

Config loading is safe against unknown/missing keys — `Config.java:63` guards
with `object.has(name)` and a try/catch — so renaming a property silently
drops the old value rather than crashing.

Backups rotate as `default.json.bak` … `.bak10`, but **only on some path** (no
code in `myau/config` writes them; the surviving ones now sit in
`backups/config/`), and the newest was 2 days stale when it was needed. Do not rely on them; prefer
reconstructing from evidence (see §6, Panic).

### Module name ≠ class name

```java
class ClickGUIModule      -> super("ClickGUI",     ...)
class GuiModule           -> super("ClickGui",     ...)
class RiseClickGUIModule  -> super("RiseClickGUI", ...)
class FullBright          -> super("Fullbright",   ...)
```

Writing a name list from `ls module/modules/*.java` produces entries that match
**nothing**, silently. Panic's keep-list was written this way and its first
real use switched off the ClickGUI — which is the worst possible thing for an
emergency key to do, since the menu is how anything gets switched back on.

Match **both** `module.getName()` and `module.getClass().getSimpleName()`.

### Registering a new module — seven places

1. `Myau.java` — `moduleManager.modules.put(Foo.class, new Foo());`
2. `ui/ClickGui.java`
3. `ui/impl/clickgui/normal/ClickGuiScreen.java`
4. `ui/impl/clickgui/modern/ModernClickGui.java`
5. `ui/impl/clickgui/riselb/Categories.java`
6. `ui/impl/clickgui/cheadle/CheadleClickGui.java` — **name strings**
7. `ui/impl/clickgui/raven/RavenClickGui.java` — **class literals**

The Atlas GUI needs nothing: it reflects over `ClickGuiScreen.frames` via
`AtlasCatalogue`. Easiest anchor for a script is an existing module's line
(`Stasis` appears in all six GUI files).

### Property classes

| Class | Extends | Notes |
|---|---|---|
| `IntProperty` | `Property<Integer>` | `getMinimum()/getMaximum()` |
| `FloatProperty` | `Property<Float>` | |
| `PercentProperty` | **`Property<Integer>` directly** | *not* an IntProperty — any `instanceof IntProperty` check misses it |
| `ModeProperty` | `Property<Integer>` | modes list is **private**; recover names from `getValuePrompt()`, which is `String.join(", ", modes)` |
| `BooleanProperty`, `TextProperty`, `LongProperty`, `ColorProperty`, `KeyProperty`, `ItemListProperty`, `FileProperty`, `DragProperty` | | |

`Property.setValue(Object)` is untyped, so `setValue(Integer.valueOf(n))` on a
`Property<?>` compiles and works.

Visibility gating is a `BooleanSupplier` 5th arg:
`new IntProperty("x", 0, 0, 10, () -> this.mode.getValue() == 2)`.

A keybind is **not** a property — it lives on `Module` (`getKey`/`setKey`),
which is why the first Atlas GUI had no way to set one.

### Rendering gotchas (1.8.9 + this codebase)

- `RenderUtil` **clamps corner radius to 4 px**: `radius = Math.min(radius, 4.0f)`.
  Anything rounder needs its own geometry.
- **`GL_CULL_FACE` is still enabled** from world rendering when a GUI draws.
  Custom geometry must `GlStateManager.disableCull()` or back-facing triangles
  vanish. This made an entire panel invisible.
- Triangle-fan winding must be continuous **180° → −180°**, with the arc centre
  changing per quadrant and vertices `(cx + sin a, cy + cos a)` — screen Y is
  downward. Emitting each corner from its own start angle puts two corners'
  vertices *inside* the rect, producing a self-crossing fan that collapses.
  Verify corner mappings numerically before writing the loop:

  ```
  case 0: cx = x2 - r; cy = y  + r; from =  180; break;  // TR
  case 1: cx = x2 - r; cy = y2 - r; from =   90; break;  // BR
  case 2: cx = x  + r; cy = y2 - r; from =    0; break;  // BL
  default:cx = x  + r; cy = y  + r; from =  -90; break;  // TL
  ```
- `BlurUtils.prepareBlur()` binds an offscreen framebuffer and **everything
  drawn afterwards goes into it** until `blurEnd(passes, radius)` unbinds it.
  An exception between the two leaves the game rendering into a texture nobody
  displays — a black screen that survives closing the menu. Always `try/finally`.
- Scissor works in **real framebuffer pixels, origin bottom-left**. Convert:
  `glScissor(x*f, (scaledHeight - (y+h))*f, w*f, h*f)` where `f = sr.getScaleFactor()`.
- **A screen's mouse events arrive at 20 Hz.** `GuiScreen.handleInput()` is
  called from `Minecraft.runTick`, so `mouseClickMove` fires twenty times a
  second whatever the frame rate: a drag driven by it moves in visible jumps.
  The `mouseX/mouseY` passed to `drawScreen` are per frame -- follow drags
  there and end them on `Mouse.isButtonDown(button) == false` (Atlas
  `followDrag`, 2026-09-25).
- **No `glGet*` in per-draw paths.** With NVIDIA threaded optimisation each
  one waits for the driver thread. Atlas used `glGetFloat(MODELVIEW)` on every
  shape draw (~300/frame); the GUI now tells `Liquid.setView` its transform.
- **F11 is the game's fullscreen toggle**, handled in the frame loop, not in
  `GuiScreen.keyTyped` — a GUI cannot take it. F3 is the debug prefix, F5 the
  camera, F1 the HUD. Use `Ctrl`+letter for GUI commands; nothing in a screen
  claims those, and holding Ctrl also keeps the character out of a search box.

---

## 4. Recurring bug patterns

These are the ones that showed up more than once, in different clothes.

### 4.1 Producer/consumer gating asymmetry

A queue whose consumer runs on a more tightly-gated event than its producer
will grow without bound the first time that event stops arriving.

**Rule:** whatever fills a queue must also be able to drain it. Apply limits on
the *producer's* thread, not only in the consumer.

```java
// in the SEND handler, right after offering to the queue
private void enforceLimits() {
    while (queue.size() > maxHeld.getValue()) sendOne();
    long deadline = delay.getValue() + 500L;
    long now = System.currentTimeMillis();
    while (!queue.isEmpty() && now - queue.peek().stamp > deadline) sendOne();
}
```

Seen in: FakeLag (kick), Blink (3-second release).

### 4.2 Two copies of one layout

Computing a UI layout once to draw and again to hit-test guarantees they drift.
The Atlas GUI's two copies disagreed by **6 px** — exactly the height of a
slider track — so no slider in the menu could be dragged at all. The row
registered the click; the "is this on the track" test then looked at a band of
pixels the track was never drawn in.

**Rule:** draw-time registration. Drawing a control records its rectangle; the
click path looks the pointer up in that list. A control that moves takes its
hit box with it, for free, permanently. This also fixes open-animation offsets
and smooth-scroll offsets at the same time.

Search newest-first so later drawing (an open dropdown) wins the click.

### 4.3 Retry with no failure memory

AutoBlockIn produced **39 refused placements in one game**, every one at a
position it had already been refused at seconds earlier, with nobody within 40
blocks.

The loop is closed and cannot end on its own:

```
place -> server refuses -> S23PacketBlockChange states air back ->
client applies it -> next tick's scan finds the goal empty -> place
```

At `place-delay = 50 ms` that is once a tick, forever.

**Rule:** anything that retries needs to remember failure. Two signals:
- **Precise:** the server stating air back at a position just placed at.
- **Backstop:** a plain attempt counter. It does not need to know *why* a
  position is not working to stop trying it.

Use a **cooldown**, not a permanent ban — the usual cause (player in the way,
stale position, support about to break) is temporary, and a permanent ban
leaves a hole in the wall the module exists to build.

### 4.4 Deny list where an allow list belongs

Whenever the failure mode of "forgot an entry" is catastrophic on one side and
harmless on the other, list the harmless side. See §2.

### 4.5 Name matching by the wrong name

See §3, module name ≠ class name. Generalise: when matching by string, match
every name the object plausibly has, because a list entry that matches nothing
fails silently and looks like correct behaviour.

### 4.6 Counting something that is not what you think

- `unsent` counted every left click, including ones not aimed at an entity —
  meaningless until gated on `objectMouseOver.typeOfHit == ENTITY`.
- Killing blows were counted as misses: the entity is gone by the time the
  result is judged, so the hit is *undetermined*, not missed. Removing them
  from the denominator moved the measured hit rate from **88% to 94–97%**
  with no behaviour change at all.
- `BlockHitChance` was a percentage compared against `Math.random()` (0..1), so
  the roll could never skip for any chance above 0.

**Rule:** before drawing a conclusion from a counter, ask what it counts when
the interesting thing *did not happen*.

### 4.7 A default chosen by vibes, justified after the fact

`release-per-tick = 2` shipped with a comment claiming it was "a rate a
stuttering connection could produce". That is false: a stuttering connection
delivers packets **late**, not in greater number. The comment sounded
reasonable enough that it survived several readings.

**Rule:** a number in a config needs a derivation, not a rationalisation. If
the comment cannot say what the number is measured against, it is guessed.

### 4.8 Comparing against the wrong quantity

Blink's collapsing release used `catch-up-step = 0.45` "because sprinting is
0.28". But skipping a queued position requires **two consecutive** positions
inside one step — the real threshold was `2 × 0.28 = 0.56`. Nothing was ever
skipped; the release never terminated; the module held the player's movement
forever and was unusable.

**Rule:** write down the inequality before picking the constant.

### 4.9 Handlers that run while the module is off

`Myau.java` registers **every** module with `EventManager` at startup,
enabled or not, and never unregisters. Every `@EventTarget` is called
regardless, so every handler must check `isEnabled()` itself.

Seen in: TimerRange (boosted the timer while off), Ambience (pinned world time
while off). Also check `onEnabled`/`onDisabled` for `mc.thePlayer` access —
`Config.load()` calls `setEnabled` at startup with no player, and one throw
used to abort the whole load (KillAura, Fly).

Quick audit: list `@EventTarget` methods whose first lines do not mention
`isEnabled`; world-load resets are the only legitimate ones.

### 4.10 Whoever sends an attack announces it

`AttackEvent` is fired by `MixinPlayerControllerMP` inside vanilla's
`attackEntity`, before the C02. KillAura and PlainAura send their own C02 and
never passed through there, so ten modules that react to attacks —
Criticals, MoreKB, SprintReset, BlockHit, BackTrack, AntiBot's need-hit,
TimerRange, Hitflick, HitParticleEffects, Disabler — did nothing for any hit
an aura made. That was most of "the combat modules don't work".

**Rule:** any code that sends an ATTACK packet itself fires
`EventManager.call(new AttackEvent(target))` first, after the slot sync.
Velocity already did; the auras now do.

### 4.11 Sprint changes inside one tick are invisible

Sprint packets are sent once a tick, in `onUpdateWalkingPlayer`, by comparing
`isSprinting()` with the state last sent. Worse, `onLivingUpdate` turns
sprint straight back on in the same tick whenever the sprint key is held —
and the Sprint module holds it. So `setSprinting(false)` followed by
anything, or `sprintingTicksLeft = 0` (only acted on when counting down from
above 0), sends nothing. MoreKB's LEGIT and LEGIT_FAST and SprintReset's
LEGIT were all no-ops for this reason.

**What works:** zero `movementInput.moveForward` for one tick in
`MoveInputEvent` (it fires right after `updatePlayerMoveState`, before the
sprint checks). Vanilla then stops sprint and sends STOP; next tick sprint
resumes and START goes out. That is Wtap's method, and a real W-tap.

Also: `AttackEvent` fires **before** the C02, so a STOP sent from it makes the
current hit land without sprint knockback (SprintReset SILENT did this).

### 4.12 A silent rotation that only goes on the packet

`event.setRotation(yaw, pitch, p)` changes what the C03 says. Movement is
computed separately: `moveFlying` (MixinEntityLivingBase) and `jump` use
`RotationState.getSmoothedYaw()`, which is whatever `setPervRotation` set --
the camera's yaw if nobody did. A module that rotates without also calling
`event.setPervRotation(yaw, p)` moves by the camera while reporting another
yaw; a server that re-simulates movement from the reported yaw sees
sideways drift and sets the player back (Clutch 2026-09-25: `LAGBACK x3
~2.3 blocks, exceeds travel`, then every placement in the window refused).
The global MoveFix module cannot help: it remaps keys toward
`getSmoothedYaw()`, which is still the camera's. Pattern every rotating
module follows: `setPervRotation(moveFix ? yaw : camera, same priority)`,
and in `MoveInputEvent`, when `RotationState.getPriority()` is yours,
`MoveUtil.fixStrafe(getSmoothedYaw())` (reads the raw keys, so running it
twice with MoveFix on is harmless).

**Applied 2026-09-25** to the four that only rotated the packet: AimAssist
(SILENT mode, priority 1), ThrowAura (1), PlainAura (2) and Hitflick (0).
Each has `move-fix` NONE/SILENT, SILENT by default except Hitflick. A move-fix
on a 90 or 180 degree flick leaves no forward key, so every flick would send
STOP/START sprint (a W-tap); its comment explains why, and when to switch.
Two things the plain pattern got wrong:

- **Ties.** `setRotation` gives a tie to the *last* caller, but
  `setPervRotation` used `<` and gave it to the *first*. KillAura's handler
  runs at `Priority.LOW`, after AimAssist and ThrowAura at rotation priority
  1, and Velocity runs after PlainAura at 2. So adding `setPervRotation` would
  have left KillAura on the packet and the other module in control of
  movement. `UpdateEvent.setPervRotation` now uses `<=`. Whoever wins the
  packet at a priority also sets the movement yaw. That also fixes the ties
  that already existed (Speed/ChestAura/KillAura at 1; AutoBlockIn,
  AutoBedDef and AutoHeadHitter at 6).
- **Whose rotation.** `getPriority() == P` is also true when another module
  at P rotated. Speed (1) and Velocity (2) move by their own yaw with the
  keys as pressed, on purpose. So the four new handlers also require
  `getSmoothedYaw() == moveYaw`, the yaw they set this tick (NaN when they
  did not rotate). KillAura's and AntiFireball's older handlers still check
  only the priority.

Backup: backups/src/src-before-movefix-20260925, jar
backups/jars/Myau+.jar-2.1+4.jar.pre-movefix. Not yet
measured in game.

---

## 5. Module-specific knowledge

### FakeLag — abandoned, and why

Rewritten three times. Each rewrite fixed a real bug. It still rubber-banded.

1. v1 (original): delayed **every** packet including transactions → kick.
2. v2: allow list + 3 Vape modes, released off `UpdateEvent` → 262 held-sends
   in a 2 s window, 21 lagbacks in 0.3 s → kick.
3. v3: `TickEvent`, producer-side limits, actions flush first → still
   `LAGBACK x21 in 1.0s, 3.04 blocks, exceeds travel`.
4. v4: one-position-per-tick cap, collapsing flush → **still rubber-banded**.

5. v5 (2026-09-23): teleport-reply fix (the dropped `C06`, §2) — plumbing now
   believed correct. DYNAMIC 200 ms, one BedFight: **31 corrections in 9 s,
   every one 1–4 t after a hit or 1–7 t after knockback, none before combat.**
   Two structural triggers: positions held through knockback (the server sees
   no response to the velocity it sent), and `release-on-attack` collapsing the
   queue into one jump (§1). Switched off by the user.

**Conclusion: the implementation was probably never the main problem.** v5
confirmed it with the plumbing fixed.

8. v8 (2026-09-24): every hold is reported (`log`: OFF / FILE / CHAT+FILE,
   default CHAT+FILE, not in LATENCY). One `HOLD` line per hold — packets,
   duration, peak queue, target and distance at start → end, and what
   released it (`attack`, `knockback`, `right-click`, `start_sprinting`,
   `melee`, `retreating`, `correction`, …). One `ENGAGE` line per engagement,
   file only: seconds an opponent was in range, ticks holding was allowed,
   holds, and ticks per ruling-out reason, most frequent first. Added because
   a full LIQUID match (range 8, delay 200) left no evidence either way: it
   was never named by FlagDetector and the user never saw the suffix count.
   Note that every `C0BPacketEntityAction` — including the sprint toggles WTap
   and Sprint produce — releases the queue and starts the recoil.
7. v7 (2026-09-24, untested): LIQUID's rules applied to every mode —
   knockback/damage/explosion/placement/dig release everything and start the
   recoil; DYNAMIC and REPEL stop holding inside `melee-distance` and release
   on their own attack. New `release-style` IN_ORDER (default) / COLLAPSE, so
   the two release shapes can be compared in play instead of argued.
6. v6 (2026-09-23, untested): mode `LIQUID`, after LiquidBounce legacy
   `combat/FakeLag.kt` + nextgen `ModuleFakeLag.kt`. The inversion that
   matters: **hold only on the approach; release everything (in order) the
   moment the fight starts** — opponent within `melee-distance` of the
   server-side position, own S12/explosion/S06 damage, an attack, a
   placement — then `recoil-time` of no holding. Our DYNAMIC did the opposite
   and held hardest while hits were traded. S08 keeps our discard + C06 pass
   (legacy LB flushes stale positions on S08, which §2 says not to do).

At 233 ms real ping, Pika's `exceeds travel` budget is computed against path
length versus what latency explains. That budget is already spent by the real
connection. FakeLag works by adding *more* delay on top. Vape's FakeLag is
designed for 30–60 ms connections, where there is headroom; at 233 ms the thing
it adds is the same order of magnitude as the thing it is trying to hide in.

`LagRange` and `Backtrack` achieve related effects far more cheaply — they hold
only at specific moments rather than adding latency across the board.

**Vape v4's documented FakeLag surface**, for reference:
`Mode {Latency, Dynamic, Repel}`, `Delay` (ms), `Transmission Offset`
(Repel only).

### Blink

- Reverted to its pre-experiment state at the user's request; the graduated
  release is gone. Current behaviour: hold, then send the queue in one pass.
- The **release rate is the whole problem** and there is no clever fix (§1).
  `max-drift` is the real lever.
- ~~`release-per-tick = 2` was load-bearing: 2-out/1-in was the only reason the
  queue ever emptied.~~ **Wrong (2026-09-23).** Because `offerPacket` kept
  holding during the drain, *every* released packet was re-queued: it was
  0-out/1-in, the queue never emptied, and releases only ended when an S08
  cleared it (`release-on-correction`). Log evidence: queue 78 → 368 → 1221 on
  09-20; `released → LAGBACK BLINKx10 → OFF` on 09-22. Fixed with
  `BlinkManager.release()`. Now it really is 2-out/1-in, which terminates but
  still breaks §1.
- Two stop paths existed and only one had a rate limit: auto-send went through
  `Blink.drain()`, but **pressing the keybind** went `onDisabled()` →
  `BlinkManager.setBlinkState(false)` → a for-loop dumping everything in one
  tick. The commonest way to end a blink was the unlimited one. Whenever a
  resource has two owners, check both exits.
- `auto-off` (default on, 2026-09-24): with auto-send off, the hold ends by
  itself at `timeout` (1000 ms) or `max-drift`, through the keybind's release
  path. Every rollback in the 09-19..09-22 logs came 0–2 s into a hold. The
  1000 ms is a starting point, not a measured edge.
- `budget()` must not return 0 when ping is unreadable — the tab list has no
  entry for the local player for a moment after joining. Returning 0 made every
  hold end instantly with "released on latency budget (0.0 blocks, 1 packet)".
  Return `Integer.MAX_VALUE` instead: no reading is not the same as no budget.
- `C0FPacketConfirmTransaction` must be excluded **unconditionally**. The
  original condition only skipped them *while the queue was empty*, which is
  exactly backwards.

### KillAura / PlainAura

- `PlainAura` is the minimal-signature design: raytrace from the **rotation
  that will be sent**, attack only if it lands. Reach, lead-aim and
  through-walls then become impossible by construction rather than by a setting
  being left alone.
- `EntityPlayerSP.swingItem()` **already sends `C0APacketAnimation`.** Sending
  one alongside it puts two animations on the wire per attack — a hand cannot
  do that. Verified with `javap -c`.
- Aim at the **nearest point of the hitbox**, not its centre. The centre of an
  unseen bounding box is a place nobody's crosshair naturally sits.
- `AimLead` offsets the **box**, not the rotation, and is set to 0 by request.

### BlockHit

Vape v4 has four modes: `Manual`, `Auto`, `Predict`, `Lag`. This client had
`Helper`(≈Manual), `Auto`, `Lag`; `Predict` was added.

`Predict` watches for `swingProgress > 0 && prevSwingProgress == 0` on nearby
opponents — one tick wide, the earliest warning available, and the same signal
`anticheat/AutoBlock.java` already uses to detect *other people's* autoblock.

**Honest limitation:** the swing animation reaches you via the server, so by
the time it arrives the attacker has been swinging for their latency plus
yours. At 220 ms each way the hit is usually resolved server-side before the
animation draws. Predict is worth having at low ping and close to decorative at
high ping.

Filters that matter: a facing cone (people swing at air, beds and blocks
constantly) and a chance below 100% (reacting to every swing is the tell).

### AutoBlockIn

Surround / self-enclose. Walls the ring around **every cell the bounding box
occupies**, at every level it spans, plus one roof cell over each body column;
no search may target or path through a body cell (2026-09-23 — it used to use
the single feet block, and the roof BFS walked through the player's own
feet/head cells). Material priority `BLOCK_SCORE`, keyed by `Blocks.*`
constants — it was keyed by unlocalized-name strings and 5 of 9 never matched
(`whiteStone`, `wood`, `stainedGlass`, `clayHardened`, `clayHardenedStained`)
(lower = preferred):

```
obsidian 0 > end_stone 1 > planks/log 2 > glass 3 > hardened_clay 4 > wool 5
```

Aims at a grid of candidate points on the target face with jitter rather than
at the face centre. `item-spoof` places without a real held-item switch;
`move-fix SILENT` rotates only in packets.

Now has refusal memory (§4.3): `max-retries 3`, `retry-cooldown 2000 ms`,
filtered at `findBestForGoals` **and `tryPlaceOnBlock`**. Not cleared on
enable (it is toggled in 1–3 s bursts), only on world load; answers matched
per position through a `pending` map, not a single slot. The earlier claim that
`findBestForGoals` was the only chokepoint was wrong: the roof/path search
places through `tryPlaceOnBlock` and kept retrying refused roof positions.

### Panic

One key, everything off, in order.

**Order matters and it is not obvious.** Packet holders are dealt with first
and explicitly — disabling Blink first would make it release its queue on the
way out, which is the burst a panic exists to avoid. So: clear the blink queue,
zero the lag manager, disable FakeLag, *then* loop the rest.

Keeps everything that only draws or only measures: the display on the way out
and the record afterwards are the two things most wanted in the minute after a
panic.

**No undo, deliberately.** Whatever was on was by hypothesis part of the
problem, and a key that restores the state that just went wrong is a key that
fires twice by accident. It prints what it stopped instead — and that printout
is what made the config recoverable when it was actually used (§6).

It disables itself after running: a switch that stays on makes the second press
a no-op, and the second press is the one somebody makes when the first did not
appear to work.

### TargetFilter

One static `accepts(EntityPlayer)` for a question 14 modules answered
differently. **When it is switched off it answers exactly what each module
answered by itself**, which is what makes it safe to wire in everywhere at once.

Adds what had nowhere to live: skip-invisible/sleeping/shops, a global distance
ceiling, and `settle-ticks` — the tick a player spawns is the tick their team,
ping and bot-status are all unknown, and every check returns a confident wrong
answer during that window.

Currently consulted by: **KillAura, PlainAura, FakeLag, LagRange, BlockHit
Predict.** The rest still decide for themselves — stated plainly, because a
filter some modules ignore is worse than none if you believe it covers
everything.

### Clutch (rewritten 2026-09-25)

Evidence (09-24 21:57-22:02 BedFight, debug on): the Raven-bS port failed by
(1) searching supports only 1-4 below the feet and placing *beside* the path
("no aim" fall 2->29 then void; blocks at y84/79/73 against walls, player fell
past), (2) aiming from the pre-move position and clicking next tick, so the
server's post-move eyes no longer saw the face (REJECT body 14 + pullbacks),
(3) triggering on fallDistance, which only starts at the knockback apex.

Now: simulate the fall (collision-aware, 60 t) -> trigger on void, or after a
hit on a drop > safe-drop -> catch cells = under the predicted footprint whose
top the feet cross, highest level first -> click any solid neighbour face
(incl. DOWN, to hang a block under the bridge) with a raytrace from the
predicted post-move eyes -> else BFS a chain (<= chain-length) from the
nearest support, only if (depth+1)*interval fits before the crossing ->
rotation step = angle / ticks-left, clamped [speed, max-speed] -> place in
UpdateEvent POST (after the C03 with this rotation), re-raytraced from the
real post-move eyes. Rotation priority 7 (combat uses <= 6). Suffix shows
saves/attempts; Pika's void teleport (>8 blocks in a tick) counts as lost.
Old keys `delay`, `combat-fall`, `simulate`, `scan-depth`, `min-fall` are
gone; `place-interval` defaults to 2 (raise it if Pika kicks for placements).
Backup: backups/src/src-before-clutch2-20260925.

**Second pass (same day, 08:27).** First session with it (08:09-08:14, two
BedFight matches): 3/5 in practice (both losses said only "nothing to build
from within reach"), then 1/2 in a real match with 6 of 8 blocks REJECTed
(body 4.8-9.1, nobody near). Both refusal bursts came with `LAGBACK x3 ~2.3
blocks, exceeds travel` + `RELOCATE 0.58`, Clutch active. Causes and fixes:
(1) no `setPervRotation` -- see 4.12; added, plus `move-fix` SILENT
(`fixStrafe` at priority 7). (2) it kept clicking through the correction
window: now a teleport confirm (C06 sent *outside* UpdateEvent PRE..POST)
stops clicks for RTT+1 ticks and resets `sentYaw/Pitch` to what the C06
carried (the camera's); an S23-air on a block it placed stops clicks for RTT
and counts as refused; 3 refusals end clicking for that fall. (3) `debug`
replaced by `log` OFF/FILE/CHAT+FILE: per-attempt tick trace in
`clutch-<stamp>.txt` (pos, motion, catch levels `L<y>x<cells>@<tick>`, plan
or diagnosis: faces out of reach + nearest distance / blocked / turned away /
chain needs Nt vs Mt left / nearest block, and why each POST did not click).
Chat only reports a reason after it has lasted 2 ticks. Not yet measured
in game. Backup: backups/src/src-before-clutch3-20260925.

The rest of that session (08:14-08:28, still the old jar) repeated both
patterns: 3 more "nothing to build" losses with 0 blocks, and 3 more
refusal bursts next to `LAGBACK/RELOCATE ... exceeds travel` with Clutch
active -- one of them after a chat "saved" whose 3 blocks were all REJECTed
and the player RELOCATEd 6.17 blocks, so a "saved" line is not proof.

**Third pass (09:15), from Vape 4.21's clutch.** See "Vape reference" below
for how it was read. Vape plans the whole sequence once (supports at the
height last stood on, joined to the landing cell, one block per tick,
Manhattan distance <= ticks), verifies it in a full simulation (rotation
controller, simulated placements, landing), then presses the use key
through vanilla when its raytrace is on the target. It drops the plan on
velocity, S08, or S23-air on a planned cell ("Server rejected block
placement!"). Default is *real* rotation with its own strafe correction;
speed cap `15 + 85*speed/10` (default 3.5 -> ~45/tick). Taken, adapted to
per-tick re-planning:
- `counter-knockback`: after a hurt < 1 s old that carries the player away
  from `lastGround` (and not while their keys point away -- a gap jump),
  hold the keys toward it for the rest of the fall (8-direction remap
  against the movement yaw, sneak-scaled, `MoveInputEvent` at LOWEST so
  MoveFix cannot undo it). `predict(true)` adds the 0.02/0.026 air push, so
  catch cells match the steered path; if steering alone lands within
  safe-drop, nothing is placed. 3 ticks of zero input after a steered save.
- `pre-aim`: with no face clickable now, plan from the eyes 1-4 ticks down
  the path (catch must cross after the lead; chain deadline includes it)
  and start turning; with nothing at all, look back at the block last
  stood on at max(speed, max-speed/2).
First left out: auto-ladder, real-rotation default, one block per tick. The
user corrected that BedFight does have ladders and asked for all three:

**Fourth pass (09:29).**
- `auto-ladder` (default on): fallback after block catch, chain and pre-aim
  all fail, with a ladder in the hotbar, one per attempt (cleared if refused,
  missed, or left). 1.8.9 facts it rests on (checked in the mapped jar,
  `EntityLivingBase.isOnLadder` / `moveEntityWithHeading`, `World.
  canBlockBePlaced`, `BlockLadder`): "on a ladder" = block at (floor posX,
  floor minY, floor posZ), tested at the start of each move; there motionX/Z
  are clamped to 0.15, motionY to >= -0.15, fallDistance = 0; colliding
  sideways gives motionY = 0.2 (climb); sneaking holds. A ladder needs a
  `isNormalCube` block behind it (glass is not). **Placement checks bodies
  against `BlockLadder`'s shared, stale bounds** (setBlockBoundsBasedOnState
  reads the world at the target, which is still air), so a body anywhere in
  the cell may get it refused, client and server: the click is only made
  while the body is clear of the cell, for a cell the body enters later
  (`planLadder`: cell of path[t], t >= 1; last clear tick k; turn budget
  (k+1)*max-speed; aimed from path[k] eyes). After it is up, steering
  presses toward a point inside the support so the body climbs, then steps
  onto the support; the save counts when the player rode it and landed.
- `mode` default REAL (Vape's default) + `reset-angle`: the camera is handed
  back to the pre-catch view at snapback speed unless the mouse moves; while
  the catch holds the camera, keys are remapped to mean what they meant
  against that view (`press`, same 8-way remap the steering uses).
- `place-interval` default 1 (one block a tick, as Vape), `chain-length` max 6.
The user's config was set to REAL / interval 1 (backup
backups/config/default.json.pre-clutch-real-20260925); jar replaced is
backups/jars/Myau+.jar-2.1+4.jar.pre-ladder. If REJECTs appear in bursts of
consecutive placements, interval 1 is the first suspect: go back to 2.

**Fifth pass (12:17), after the 09:41-10:28 Pika games.** User report:
"keeps getting flagged, view not smooth". Evidence (clutch-20260925-*.txt,
logs/2026-09-25-6.log.gz):
- Every Clutch-time LAGBACK (09:41:07 x3 5.0 blocks, 09:43:48 x4, 10:28:20)
  came in an attempt that **started steering at "hit 0-50ms ago"**. 09:43:48
  placed nothing at all: steering alone, "5t after hit". Pressing back from
  the tick of the hit is what a knockback check sees as velocity not taken.
  Fix: `counter-delay` (default 6 ticks) before the push back starts.
- After such a lagback Pika sends an S08 **every tick** along its own
  simulated arc (motion zeroed each time, so "v 0.00" in the trace) until
  it lets go; Clutch logs each as "corrected by the server". That is the
  server steering, not a Clutch bug; the attempt is lost from there.
- 09:43:57 (RELOCATE 6.54, three chain blocks refused) was **Blink**, switched
  on by hand at 09:43:56 and released on drift: placements made while Blink
  holds the movement cannot stand. Not changed; told the user not to mix
  them.
- Camera: REAL wrote `rotationYaw` once a tick in PRE, and `onEntityUpdate`
  then copies it to `prevRotationYaw`, so nothing interpolated: the view
  jumped 30-90 degrees at 20 Hz. Now REAL goes through `RotationManager`
  (spread over the frames of the tick, finished by the next; mouse locked
  while catching, free while handing back). Steps are whole mouse counts
  (`RotationUtil.gcd()`), rise at most max(speed, max-speed/2) over last
  tick's step, and the random factor drifts (low-pass 0.35) instead of being
  redrawn each tick. `max-speed` default 90 -> 60 (the user had already set
  60, plus interval 2 and rotation-random 20 by hand).
Source backup backups/src/src-before-smooth-20260925; replaced jar is
backups/jars/Myau+.jar-2.1+4.jar.pre-smooth (installed 12:2x).

### Backtrack, second version (2026-09-25)
User asked for a stronger Backtrack "like Slinky" (closed source, nothing of
it on disk: nothing was referenced). Fixed in BackTrack.java (class Javadoc
lists them): real position now = `serverPosX/Y/Z` + held deltas (was the
interpolated `posX` + deltas); distances eyes-to-hitbox with the 0.1 border
(were feet-to-feet); release-on-hit on the hurt's start (was hurtTime == 1,
its end); new `hold` = ALL (default) holds the whole inbound stream in order
while a hold is open, TARGET the old way; releases drain synchronously under
a lock on either thread (network-thread processPacket reschedules in order);
own S12/S08/S27/S07/S01/S40 flush first and pass; 400-packet cap; ESP box
eased 0.5/tick, interpolated per frame, fades, outline + fill.
`RenderUtil.drawFilledBox` gained an alpha overload. If Pika flags rise with
ALL (held transactions/keep-alives read as latency spikes), try TARGET.
Source backup backups/src/src-before-backtrack-20260925; jar replaced goes to
backups/jars/Myau+.jar-2.1+4.jar.pre-backtrack.

**Clutch, 12:22-12:29 games (smooth jar; clutch-20260925-122235.txt).** 25
attempts: 11 saved, 7 lost, 7 landed on existing ground. counter-delay held
(no steering in the first ~5 ticks, no "0t after hit" lagback tied to a
catch). Three "saves" (attempts 8, 14, 17) stood on blocks the server had
refused; each was followed 0.1 s later by a lost attempt as the server
replayed the fall (9, 15, 18). Refusals do **not** track the turn size
(tabulated: refused and accepted placements had the same per-tick deltas).
FlagDetector's REJECTs show "body 5.9-6.2". **Correction (13:1x): "body" is
the nearest OPPONENT's closest approach to the block while the placement was
outstanding (FlagDetector.Placement.closestOpponent), not this player's
distance -- it only says no opponent was in the way. The "server stopped
accepting the movement first" reading rested partly on misreading it; the
per-tick replays after lagbacks are still real.** In the
replays the server's horizontal speed after the hit is ~0.6 against the
client's ~0.37-0.4 -- the ratio of vanilla's sprint-attack slowdown (x0.6),
which the client applies on every sprinting swing (EntityOtherPlayerMP.
attackEntityFrom returns true) and the server only on hits it counts.
Unproven: HitSelect SECOND + AutoClicker swing right after being hit.
Instrumented to test it (12:36 jar): trace lines now say "swung
(sprinting)" on the tick of an own swing, and "blink <owner> Np" / "fakelag
Np" while outgoing packets are held; the ATTEMPT header says "swung Nt ago".
Also: a refusal of a block the last save stood on (within 40 ticks) now
un-counts the save ("that save did not stand"); AntiVoid's blink is released
when a catch starts (it held the clicks: 12:27:22 REJECT + 5.74 lagback,
ANTI_VOIDx23). `FakeLag.heldCount()` added.
12:38-12:40 games (with that jar) **disproved the swing theory**: attempts 3
and 5 were refused and lagged back with "no swing". 12:40:04's REJECT +
LAGBACK came before any attempt and without Clutch in the blame; the
attempts after it ran inside Pika's per-tick replay. 12:43 jar: `void-only`
(default on, user request) -- no catches onto ground; while the server is
correcting every tick (a C06 answer this tick or last) Clutch holds still:
no steering, no turning, no clicks, trace "the server is moving the player".
Backtrack: suffix shows the hold count; its box is on at once and fades over
~8 ticks (holds at 75 ms were one or two ticks, too short to see). Source
backup backups/src/src-before-voidonly-20260925; jar to be replaced goes to
backups/jars/Myau+.jar-2.1+4.jar.pre-voidonly.

**FakeLag LIQUID, 12:48 jar.** Today: 10 of 233 flags named FakeLag; engagement
lines show holding mostly ruled out (not-moving, melee, recoil, hurt) and
holds of 7-17 packets released in one tick. 12:45:04: a 10-packet release on
a slot change, then a placement REJECTed at "body 5.71". Change: `taper`
(1.5 blocks) runs the delay down to 0 over the last blocks before
melee-distance; soft stops (retreating, not-moving, no-target, screen,
using-item, water) let the queue out at `catch-up` (2) positions a tick with
new packets queued behind (order kept, `easing` flag); attack, knockback,
damage, actions, melee, correction, death still flush at once. Source backup
backups/src/src-before-fakelag2-20260925; replaced jar
backups/jars/Myau+.jar-2.1+4.jar.pre-fakelag2.

### Arbiter (2026-09-25, 12:57 jar)
`myau.management.Arbiter`: one place that says a fall is being caught
(`catching`, set by Clutch from `attempt` every PRE) or the catch holds the
camera (`viewHeld`, REAL + hasSent). While catching: BlinkManager refuses new
blinks (Blink then switches itself off, its tick checks the owner); Clutch
releases whatever blink is on (was AntiVoid only); LagManager flushes and
holds nothing; FakeLag releases ("catch") and holds nothing; Backtrack
releases and opens no hold; KnockbackDelay flushes and holds nothing. While
catching or viewHeld: SafeWalk and Eagle do nothing (the catch looks down
with blocks in hand, which is their trigger). Modules that stood aside are
traced per attempt: "stood aside for the catch: ...". Rotation needs no
entry: priorities already put Clutch (7) above KillAura/AimAssist.
Source backup backups/src/src-before-arbiter-20260925; jar to be replaced
goes to backups/jars/Myau+.jar-2.1+4.jar.pre-arbiter.

### Event bus and PerfLog (2026-09-25, 13:02 jar, installed with Arbiter)
Every module is registered at start and every handler was called through
reflection whether the module was on or not (296 handlers; 73 on TickEvent,
45 on PacketEvent, 24 on Render3DEvent). Estimated cost well under 1 ms/s,
so this is housekeeping, not the fix for any stutter. Change: dispatch skips
a disabled module's handler unless it is `@EventTarget(whenDisabled = true)`.
The 69 handlers marked so were found by a script (scratchpad classify.py):
a handler is skippable only if its body is `if (!isEnabled() [|| ...])
return;` first, or one `if (isEnabled() [&& ...]) {...}` with nothing after;
everything else (world-change resets, NoSlow bookkeeping, KillAura's
blocking cancels, ...) keeps running as before. New handlers default to
skipped-while-off. `Module.isEnabled()` is not overridden anywhere.
`PerfLog` (a manager) writes config/Myau/perf-<stamp>.txt every 60 s in a
world: ms/s per handler (top 20, with calls, avg, max) and per event type,
plus average FPS -- to decide what is worth optimising from data. Source
backup backups/src/src-before-eventperf-20260925. Source backup
backups/src/src-before-trace2-20260925. Installed 12:3x together with the
Backtrack rewrite; replaced jar backups/jars/Myau+.jar-2.1+4.jar.pre-backtrack.

### Atlas Liquid (2026-09-25)

- `Liquid` does all menu drawing with four shaders: SDF shapes/lines/shadows,
  a glass pane over a copied + dual-Kawase-blurred world (clear refracting
  rim sampled from the unblurred copy), and lenses over a copy of the menu's
  own content. Falls back to `Glass` if shaders/FBOs are unavailable.
  Shaders verified to compile on the user's RTX 5080 (scratchpad harness,
  LWJGL natives in `Install/natives/forge-11.15.1.2318`).
- `LiquidFont` rasterises SF Pro at size x GUI scale; `Spring` drives all
  motion. The opening pop scales the window via the modelview; `Liquid`
  reads the matrix per draw and the GUI inverse-transforms the pointer.
- Hidden toggle = "ArrayList" chip in the detail pane (Ctrl+H too).
- Profiles view (sidebar, under categories): lists `config/Myau/*.json`
  via `Config`, same files as `.config save/load`. Delete needs two clicks
  and refuses `default`. The shutdown hook still saves the *startup* config
  object to default.json, so edits after loading a profile are only kept in
  that profile if "Save here" is pressed.
- Prototype (WebGL, same GLSL): scratchpad `proto/`.
- Appearance page (sidebar CLIENT > Appearance): `AtlasTheme` holds ~40
  standalone Property objects (no module owner), drawn with the normal
  settings card, saved to `config/Myau/atlas-theme.json` 0.5 s after the last
  change and on close -- deliberately not in the client config/profiles.
  `applyTheme()` pushes blur levels/offset, font file, spring damping/speed,
  jelly, density (ROW/CAT sizes are per-frame fields now), radius, footer.
  Text size moves in 5% steps and clears LiquidFont's sheets on change;
  continuous sizes leaked a sheet set per value.
- Row click selects only; the row's switch toggles (user request 09-25).
- AtlasInspector Ctrl+K now saves to `config/Myau/ui/screenshots/`
  (ScreenShotHelper always appends `screenshots/`; the old path never saved).

### The instrument suite (what Vape and Raven do not have)

| Module | What it answers |
|---|---|
| `FlagDetector` | What the server just objected to, with a derived signature and a blame suffix |
| `HitCheck` | Per-swing hit/miss with a classified reason |
| `ActionLedger` | Which module withheld a packet |
| `Brain` | Per-server, cross-session flag rates on/off per module |
| `Adaptive` | Closed loop: probe a module off, compare, adjust |
| `ServerFingerprint` | Behaviour-class of the anticheat, not its brand |
| `AtlasInspector` | The menu checking its own layout |

Every bug found in this whole effort was located with these, not by guessing.
That is the actual competitive advantage over a commercial client; it is not a
feature count.

`FlagDetector` client-fault labels (added 2026-09-23, magenta, shown as
`BUG<n>`): `TIMER-ORPHAN` (timer ≠ 1.0 with Timer/TimerRange/NoFall all off)
and `DRAIN-STALL` (Blink releasing without progress, or packets stranded in the
blink queue with nothing blinking). They are **not** violations: excluded from
counters, rate, blame, `violationCount()` and Adaptive. Any new "the client
contradicted itself" check belongs in this group, not among the flags.

`FlagDetector` classification worth preserving:
- `ignoreReason()` filters spectator / dead / respawn<60t / riding / flying /
  post-teleport<20t.
- `BLOCKED-BY-BODY` is split from `REJECT` by per-tick sampling of the nearest
  opponent's body distance (`OCCUPIED_DISTANCE = 1.2`) and excluded from the
  flag rate.
- Corrections are compared against **summed path length per tick**, not
  displacement — hence the `exceeds travel` wording.
- `RELOCATE` (2026-09-24, dark aqua): a correction whose target is not within
  0.3 blocks of any position the player occupied in the last 200 ticks (10 s).
  A setback only ever returns the player to a position the client sent, so a
  target it never occupied was chosen by the server. Shown, counted
  separately, excluded from rate, blame, Brain and Adaptive; opens the 20-tick
  post-teleport grace like `TELEPORT`. If a real setback ever shows up as
  `RELOCATE`, it was to a position older than 10 s: lengthen `HISTORY`, do not
  remove the check.

---

## 6. Workflow

### Build and install

```bash
cd "<instance>/OpenMyau-Plus-2.1-4"
JAVA_HOME="C:/Program Files/Java/jdk-17" ./gradlew build -q
```

Output: `build/libs/Myau+.jar-2.1+4.jar` → copy to
`<instance>/mods/`.

**Every backup goes under `OpenMyau/backups/`** (user's rule, 2026-09-25) —
never beside the thing it backs up, so `mods/` holds only the live jar and
`config/Myau/` only live files:

```
backups/jars/    Myau+.jar-2.1+4.jar.pre-<tag>   the jar replaced by an install
backups/src/     src-before-<tag>-<yyyymmdd>/    copy of src/ before an edit
backups/config/  <name>.json.<tag>               a config before an external edit
```

Before an edit: `cp -r src ../backups/src/src-before-<tag>-<date>`. At install:
move the old jar to `backups/jars/…pre-<tag>`, copy the new one, compare hashes.

### Detecting whether the game is running — do it properly

Replacing the jar while the game runs causes `NoClassDefFoundError`.

**Checking for any `java.exe`/`javaw.exe` is wrong** — the Gradle daemon and
its workers are `java.exe`, so a check that coarse reports "running"
immediately after every build, which is exactly when an install is wanted.

```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
  Where-Object { $_.CommandLine -match 'launchwrapper|OpenMyau|minecraft' }
```

Empty result = safe to copy.

### Decompiling for ground truth

CFR, already in the Gradle cache:

```
<home>/.gradle/caches/modules-2/files-2.1/net.fabricmc/cfr/0.0.9/.../cfr-0.0.9.jar
```

Run `org.benf.cfr.reader.Main`. Also `javap -c` for a quick check of what a
vanilla method actually emits — that is how the duplicate animation packet was
confirmed.

### Third-party references

Other clients were studied for ideas while this was developed. Their binaries,
the tools used to read them, and anything derived from decompiled code are not
part of this public repository. Their behaviour is described in these notes in
our own words.

## Vape 4.21 studied; five combat changes (20:48 jar, not installed yet)

**Source.** `<reference clients>\OpenVape-v4.21.4\Vape421Native.dll` is a community reverse-engineered rebuild of Vape 4.21.
- The DLL embeds a jar (8189 entries).
- The classes under gg/vape/module/combat were extracted to the scratchpad and read with javap only. The DLL and its classes were never loaded or executed.
- Nothing was copied. What each module does is described below in our own words and reimplemented against our own code.
- Source backup: backups/src/src-before-vaperef-20260925.

**Findings (Vape's behaviour)**
- **AttackPacketTimingTracker.** It stamps each C02 ATTACK and, when the same entity's hurt status (S19, op 2) arrives, records the delay: under 500 ms only, a rolling 20. Expected ticks are the average delay / 50. HitSelect, WTap and BlockHit Predict are built on it.
- **WTap.**
  - Chance defaults to 90%. Release delay and re-press delay default to 50 ms each.
  - Select hits: it skips the tap when the target was hurt too recently.
  - No new tap starts while one is pending. W is re-pressed only if the physical key is still held.
- **HitSelect "Active".** While moving toward the target it cancels the click itself (not the packet) unless the target's hurtTime ≤ expected ticks. It allows one click per opening, spaced 2x the average delay, plus one at hurtTime == expected+1.
  - After own velocity (7 ticks, until landing), "KB reduction" allows every click.
  - "Critical hits" holds clicks while rising.
- **BlockHit "Predict".** It does not watch the opponent. It uses the player's own hurtResistantTime and blocks when it is ≤ 10 + early ticks.
  - Early is 50 ms + the average hit delay + 50 ms.
  - It holds 2 ticks past damageable if no hit arrives.
  - With 3 or more damage intervals of 250-1500 ms it times the block to the average interval instead.
- **AimAssist.**
  - It runs on a worker thread and feeds mouse-count accumulators per frame.
  - The target is predicted at 1.7× its last movement.
  - Speed is an acceleration integrated into a decaying velocity: base 1 + rand(0..2) + angle/50, plus a close-range boost of max(0, (9 - dist)/2.5 - 2).
  - Strafe increase (×1.6) applies when strafing away from the target's side.
  - Inside 5° there is no new acceleration and the velocity decays ×0.7. The velocity is reversed when the aim crosses the target.
  - Output is damped by 1/(10 - angle) inside 9°.
- **Reach.** Vape's own description calls Reach unsafe and detected by most servers, and advises against it. Its options are vertical check, only while sprinting, disable in water, advanced chance, and misplace.
- **JumpReset.** Settings are chance, accuracy (deliberate mistiming, 40-60%), only when targeting, and water check. It only jumps.

**Our changes**
- **management/HitTimer (new, registered like PerfLog).** It measures the attack→hurt delay as above and falls back to the tab ping before any sample exists.
  - `ticks()`: the delay in ticks.
  - `canHurt(target)`: target.hurtTime ≤ ticks.
- **SprintReset.**
  - `chance` (90).
  - `release-delay` (0 ticks) and `release-ticks` (1), LEGIT only.
  - `select-hits` (on): skipped when target.hurtTime > HitTimer.ticks()+1.
  - A tap in progress blocks a new one.
- **HitSelect ACTIVE (new mode 3)**, on LeftClickMouseEvent at HIGH priority:
  - `chance` 90 and `preference` KB_REDUCTION or CRITICALS.
  - It uses our isMovingTowards within 90°.
  - The suffix shows how many clicks were dropped.
  - The old modes cancel the C02 after the client has already slowed itself. That is why they need the KeepSprint patch.
- **BlockHit Rhythm (new mode 4):** the own-damage-rhythm block described above, with `RhythmEarly` (50 ms), `RhythmPing` (on) and `RhythmHold` (2). It needs a sword and an opponent within 6 blocks.
- **AimAssist:**
  - `lead` (1.0 tick of target movement ahead).
  - `strafe-increase` (×1.6, capped at a full step, so there is no effect at speed 10).
  - Vape's velocity model and per-frame mouse injection are **not** ported yet.
- **Reach:** unchanged.
- **InvulnTiming** only ever served KillAura. It does nothing for an AutoClicker player; HitTimer now covers that need.

## FakeLag: random delay, weapons-only, hold-hits (21:13 jar, not installed yet)

Source backup: backups/src/src-before-fakelag-rand-20260925.

- **`delay-random` (100 ms).** Each hold draws delay + U(0..spread) in noteHeld when an episode starts. LATENCY, whose queue never empties, redraws every 2 s. Every former use of `delay` in hold timing now goes through holdDelay(): enforceLimits' deadline, LATENCY, DYNAMIC, the LIQUID cutoff, and REPEL's cap and non-position wait. The HOLD log line shows the delay drawn.
- **`weapons-only` (on, not in LATENCY).** Holding a weapon means ItemUtil.hasRawUnbreakingEnchant: a sword or Sharpness.
  - LIQUID: a new ineligibility reason `no-weapon`, which eases out.
  - DYNAMIC and REPEL: the same reason blocks the hold.
- **`hold-hits` (off)**, after Vape's Repel.
  - When to hold: a C02 ATTACK on a player whose hurtTime − HitTimer.ticks() = early > 0, with early×50 ≤ delay + U(0..99) ms. The packet is cancelled and kept apart from the movement queue.
  - When it is sent: at the tick (POST) once target.hurtTime ≤ HitTimer.ticks() or the window is over. A newer attack, a correction (S08) or a catch (Arbiter) also sends it; so does switching the module off.
  - What is sent: the held positions first when release-on-attack applies, then a fresh C0A swing, then the C02.
  - When it is dropped instead: the target is dead or no longer the same entity in the world, or the player is disconnected or dead.
  - It logs `HIT held Nms | target hurt a->b | reason` and the suffix shows "N hits held".
- **HitTimer** now listens at LOWEST priority and ignores cancelled sends. FakeLag.send() reports every released C02 through HitTimer.noteAttackSent, so time spent holding is never measured as latency.
- **FightLog** LAND_WINDOW is now 16 ticks (was 12), to leave room for held hits.

**Revised in the 21:16 jar: `hold-hits` is gone; REPEL is Vape's Repel.** At the user's request, holding attacks is now what the REPEL mode does, and all it does.
- REPEL holds no movement. A switch into it releases whatever was queued.
- The hit window is delay + transmission-offset + U(0..delay-random). weapons-only applies to it.
- The old REPEL (send only the positions that do not close the distance: `repel()`, `sentDistanceTo()`) was removed. It overlapped DYNAMIC and LIQUID and was unused (the user runs LIQUID).
- So REPEL and LIQUID are now alternatives, compared a game each through fights-*.txt.
- Backup: backups/src/FakeLag.java.pre-repelonly-20260925.

## Code review of the day's changes (21:22 jar, not installed yet)

Scope: every file changed since the 14:11 backup, read diff by diff: Clutch, KillAura, Scaffold, AimAssist, BackTrack, AntiVoid, FakeLag, FastPlace, FlagDetector, MouseRawInput and RawMouseHelper, SprintReset, HitSelect, BlockHit, FightLog, HitTimer, RotationEngine, Debug and Myau. Earlier-day changes had been reviewed at ~13:40. Source backup: backups/src/src-before-reviewfix-20260925.

**Fixed**
1. **FakeLag REPEL (real bug).** A new attack while one was held sent the held one first. At 12-16 CPS every held attack therefore left about 1.5 ticks later, still inside the target's immunity, so the mode did almost nothing.
   - A new attack on the same target is now dropped (`merged`); its swing has already gone and reads as a miss. Only a different target sends the held one.
   - The suffix now shows "N held, M merged".
2. **FightLog.** WE FELL and THEY FELL now also require an empty column down to y=0 (nothingBelow). A 10-block drop onto ground (jumping off a tower) was being logged as a fall.
3. **BackTrack esp-head.** glLineWidth is reset to 2.0 afterwards, as RenderUtil does.
4. **Clutch disable-after.** It now fires only when the attempt placed something (attemptPlaced > 0). A pre-aim turn, or a fall that sorted itself out, no longer switches Clutch off.

**Checked and fine**
- MoveInputEvent fires right after updatePlayerMoveState and before the sprint logic, so SprintReset's LEGIT tap does drop sprint.
- Scaffold's quantized yaw now continues from lastReportedYaw, which incidentally removes 360° jumps.
- HitTimer runs at LOWEST priority, skips cancelled sends, and gets noteAttackSent from FakeLag.send. No hold time is counted as latency.
- EntityPlayerSP sets hurtResistantTime client-side on damage (setPlayerSPHealth and status 2), so BlockHit Rhythm's timing source is valid.
- New handlers default to enabled-only, except FightLog.onLoadWorld (whenDisabled) and HitTimer (not a module).
- The two key-holding modules (BlockHit Rhythm, AntiVoid's resetBlink) always release through updateKeyState or setBlinkState, and also on disable.

## BlockHit Rhythm yields to hits (21:41 jar)

The user found "no attacks while it blocks" annoying. Rhythm now drops the block when the physical attack button is held (read from Mouse/Keyboard, because AutoClicker flips the key binding every click), the crosshair is on a living entity, and HitTimer.canHurt(target) holds. It still blocks when a swing would be wasted anyway.

## Hypixel reports ping 1; shared Ping.own() (2026-09-28, 08:39 jar, not installed yet)

From 2026-09-28 06:43 the user plays on Hypixel. Every FightLog line there says `ping 1`: Hypixel's tab list
shows 1 ms, and 14 places read the tab list for our own ping. All callers treat `<= 0` as "no reading" with a
fallback, but 1 slipped through as a real number: Clutch's round trip clamped to its 2-tick minimum, HitCheck /
AntiCheat / LatencyCrosshair windows 1 tick instead of their 4-tick fallback, KillAura aim-lead ~0, InvulnTiming
and PlainAura compensation ~0, Blink's latency budget not reduced.

- New `myau.util.Ping`: `own()` = tab figure if >= 2 ms (so Pika behaves exactly as before), else HitTimer's
  measured attack-to-hurt average once it has >= 3 samples, else -1. `tab()` = raw figure.
- HitTimer: `measured()`; samples cleared when the server address changes; fallback ignores a tab figure < 2.
- Switched: AntiCheat, Blink, Clutch, FightLog, FlagDetector, HitCheck, InvulnTiming, KnockbackDelay,
  LatencyGovernor, PlainAura, KillAura (lead), LatencyCrosshair. Not switched: displays (WaterMark,
  DynamicIsland, Atlas) and the other-player bot checks (AntiBot, TeamUtil).
- Measured includes up to one server tick of waiting, so on Hypixel it reads ~25 ms high. Before the first
  three hits of a session it is -1 (callers' fallbacks).

ServerFingerprint only reset on enable, so disconnecting from Pika and joining Hypixel filed Hypixel's numbers
under `play.pika-network.net` (server-profiles.csv 06:43 rows). A join on a different address now starts a new
session.

FightLog's `kb dealt vel` is `-` in 563 of ~600 fights on both servers: the S12 for the player we hit rarely
arrives, or arrives outside the 4-tick window. Use `pushed` instead; not fixed.

## Architecture stabilisation, batch 1 (2026-09-28, 09:06 jar, not installed yet)

Audit first: `docs/ARCH-AUDIT-2026-09-28.md` (read-only Phase 0, findings F-01..F-35 with file:line evidence).
The user chose: F-08 = save on exit to the profile in use; F-32 = left to me (first come, first served);
ItemPhysics = wanted. This batch = audit steps 1-5 plus F-23.

- **Unit tests exist now.** `JAVA_HOME=jdk-17 ./gradlew test` (JUnit 4, `src/test/java`, not in the jar). `build`
  runs them too. 48 tests: EventManager 26, BlinkManager 10, LagManager 8, Shutdown 4. Classes that read
  `Minecraft.getMinecraft()` load fine in tests (it returns null); give them a small overridable seam
  (`connected()`, `send()`) rather than a mock framework. A test helper in `myau.module.modules` makes
  `ActionLedger.callerModule()` see it as a module.
- **EventManager** (Phase 1): the semantics are now pinned by tests and must not change silently. Cancelled events
  are still delivered to later handlers; dispatch is by exact class; the enabled check is per handler at
  dispatch time; handler exceptions are logged and dispatch continues. Fixed: `cleanMap(false)` threw (F-01),
  registering one object twice doubled its handlers (F-02). Registry is a ConcurrentHashMap. Exceptions name the
  handler. `clearForTests()` is package-private.
- **LagManager (F-28)**: `flushQueue` runs on the network thread too (vanilla sends the keep-alive reply from the
  packet handler; verified with javap), concurrently with the tick flush. peek/send/poll is now synchronized.
  With the lock removed, the new test sent 401 packets for 400 (duplicates and losses), so the race was real.
  `isFlushing()` is now per thread: before, a client-thread movement packet sent during a network-thread flush
  bypassed blink and lag holding and went out early. A catch flushes all without zeroing and restoring the
  delay.
- **F-11**: the lag holder is whoever called `setDelay(n>0)` (stack walk, only when the value changes), not a
  guessed list that named FakeLag/Blink/ServerLag (which never use LagManager) and missed BlockHit.
  FlagDetector blames `lagManager.holder()` and prints it in "lagging N pkt (holder)".
- **BlinkManager (F-32)**: a second module asking to blink while another holds is refused (false, counted in
  `refusals()` / `lastRefusal()`). Before, it silently became the owner of the first module's packets, and
  the first module's release did nothing. Explicit takeovers (Blink, NoFall and Hitflick release the owner first)
  are unchanged. AntiVoid already checks the return value. Scaffold safe-stuck, Displace and KillAura
  AUTO_BLOCK simply do not hold when refused.
- **One ordered shutdown** (`management/Shutdown`): RESTORE → SAVE_CONFIG → SAVE_STATE → FLUSH_LOGS in one JVM hook.
  It replaced three racing hooks (config save, Adaptive restore + Brain save, AsyncLog drain). New restores:
  AutoTune reverts its trial values (F-07), and LatencyGovernor puts back governed values and re-enables the
  modules it cut (F-26; `onDisabled` gives them back too).
- **F-08**: exit saves to `Config.lastConfig` (the profile last loaded or saved, the same target as
  `.config save` with no name), not always default.json. Startup still loads default.json.
- **ItemPhysics (F-23)**: new `MixinRenderEntityItem` injects at HEAD of `func_177077_a` (vanilla's placement: bob,
  spin, copy count). While the module is on, items lie flat (sprites face up), rest on the ground, and tumble
  about their centre in the air (`rotation-speed`). Drawing stays vanilla's. The model's GROUND transform is
  read and cancelled so the centre sits on the pivot. Stack copies are made to coincide by swapping the
  renderer's random for one returning 0.5 (their offsets would go into the ground once laid flat).
  **Not verified in game yet**: needs a visual check.

## ServerSession (Phase 2 / plan step 6, 2026-09-28, 09:14 jar, not installed yet)

The 09:06 jar was installed at ~09:10 (old jar: `backups/jars/...pre-arch1`). Step 6 is built on top of it.

- `management/ServerSession` (registered in Myau.init) is the one definition of a connection:
  - **START**: the first S01 after a disconnect, handed to the client thread ahead of vanilla's own join
    handling, so the world and player may not exist yet.
  - Further S01 on the same connection are proxy hops: the same session, counted in `joins()`.
  - **WORLD**: any non-null `loadWorld`.
  - **END**: `loadWorld(null)`, which happens on disconnect, quit to menu, and exit.
  - A join for another address while a session is open ends the old one first.
  - It is announced as `events.SessionEvent`. `serverChanged()` on START says the server differs from the
    previous session's.
- Server identity (user's choice): the typed address, lower-cased, without `:25565` or a trailing dot.
  `ServerSession.keyFor()` is the only normaliser. `serverKey()` returns the current key, or the last one
  when disconnected.
- `LoadWorldEvent` now carries the world (`isUnload()`). The old no-arg constructor is kept.
- **Two scopes.** Server-scoped state resets only when the server changes; a reconnect to the same server keeps
  it on purpose. Connection-scoped state resets on every START.
  - Server-scoped: HitTimer samples (F-34: they used to reset on the next attack), LatencyGovernor ping samples
    (they used to never reset), the Brain file (Adaptive switches it on START instead of polling the address
    every tick, and saves on END), and ServerFingerprint's record (it used to compare addresses itself).
  - Connection-scoped: the ActionLedger (cleared by ServerSession), FlagDetector's `implicated` blame (F-12,
    cleared even while the module is off), and HitTimer's pending attack.
- **ServerFingerprint (F-33)**: the arrival time and payload bytes are read on the network thread (a payload
  buffer may be released before the client thread runs), and all state changes run on the client thread. The
  measurements are unchanged, and the report no longer iterates collections another thread is writing.
- Tests: `ServerSessionTest` (8), covering key normalisation, lifecycle, proxy hops, A→B isolation (samples and
  ledger reset), and reconnecting to the same server (samples kept, ledger reset). 56 tests in total.

## Value ownership and LatencyGovernor hysteresis (plan steps 7-8, 2026-09-28, 09:27 jar, installed 09:27)

Checked the 09:06 jar's first session (09:12-09:20, Hypixel):
- ItemPhysics and the profile save were confirmed by the user. The logs show no config load that session;
  default.json was written at exit, which is correct for the profile in use.
- Flags: 4 in 8 minutes (VERTICAL 0.39 right after joining, LAGBACK 1.36 on the ground, one REJECT 13 blocks
  from the body, LAGBACK 0.43 after a hit). Placements: 209 OK, 1 REJECT. No `lagging N pkt`, and no mixin
  errors. The `Error executing task ... Scoreboard.func_96519_k` NPEs at 09:13:57 are vanilla (removing a
  scoreboard objective the client does not have, on a lobby switch), with no myau frames.

**Step 7, value ownership (`property/Property`).** A value is one base (the player's choice or a config file's) plus
overrides. `getValue()` returns the effective value; `setValue()` sets the base; `override(source, owner, v)` and
`release(owner)` belong to the tuners. **Only the base is ever saved** (Int/Float `write()` uses
`getPersistedValue()`). Only Int and Float accept overrides (`supportsOverride`); the others throw. Precedence,
low to high:
- Base sources: DEFAULT, PROFILE (set while `Config.loading`), USER.
- Overrides: LEARNED (Adaptive), then TRIAL (AutoTune), then GOVERNOR (LatencyGovernor). The governor is a
  safety limit, so a trial cannot step over it.

`describe()` says where a value came from, e.g. `240 (TRIAL by AutoTune 12s ago; base 200 USER)`. A property
nobody overrides behaves exactly as before.

The three tuners were converted:
- AutoTune trials start from the base. It releases on disable and on exit, and a trial whose value was masked
  (the governor on top) is extended rather than scored (`MASKED` in autotune.txt).
- Adaptive's turned-down knobs are LEARNED overrides, released on disable (F-20).
- LatencyGovernor scales the base, never another tuner's value (F-24).

As a result, trials and cuts can never reach a config file, even without the shutdown RESTORE. Loading a
profile mid-trial keeps the loaded value as the base (F-09).

**Behavior change:** Adaptive's cuts and AutoTune's "KEEP"s last only while the module is on. They used to leak
into the config on exit. A validated commit comes with step 11.

**Step 8, LatencyGovernor (`util/LatencyTiers`, pure).**
- **Hysteresis:** a tier is entered at its threshold and left only `hysteresis` ms below it (default 20).
  Jitter is flagged above the limit and cleared below 4/5 of it.
- **Settling:** a new tier must be wanted for `settle-seconds` consecutive samples (default 5). The first
  decision is taken at once.
- **Cuts at tier 2** happen only on entering it. Everything is given back on session END (the "for this
  connection" in the message is now literally true), on disable, and at exit. On START with a different
  server, the samples are reset and all cuts released.
- New properties: `hysteresis` and `settle-seconds`.

Tests: PropertyOverrideTest (13) and LatencyTiersTest (10), including the brief's noisy 79/83/80/92/77/88 line
and a ~225 ms line against the 250 ms threshold (0-1 changes, not one per crossing). 79 tests in total.

Source snapshots: `backups/src/src-before-session-20260928` (before step 6) and
`backups/src/src-after-step8-20260928`. There is no separate snapshot between steps 6 and 7; the 09:14 jar has
step 6 alone.

## Brain persistence and Adaptive safety (plan steps 9-10, 2026-09-28, 09:40 jar, not installed yet)

**Step 9, `util/Brain` (format brain-2).**
- **Ageing:** all counts and minutes decay with a **14-day half-life** (the user's choice), so rates become
  exponentially weighted averages that follow the server, and confidence falls as evidence gets old. Three
  horizons are kept per kind: `total` (lifetime, never decayed, i.e. the sample size), `recent` (decayed) and
  `session` (since `beginSession`, which Adaptive calls on every START). `firstSeen` and `lastSeen` are kept too.
- **The file:** header `brain-2`, `server`, `asof`, `halflife-days`; then the E/K/F records; then `end`. It is
  written to `.tmp` and moved into place, so a save is never half a file.
- **Reading problems:** the good lines are still used, and the original is copied to
  **`backups/brain/`** (the backups rule) as `.corrupt-`, `.v1-`, `.unknown-` or `.foreign-` plus a stamp.
  Before, a read error cleared everything and the next save overwrote the file.
  - A truncated file (no `end`) counts as a problem.
  - A file naming another server is not used.
  - brain-1 is migrated, aged from the time the file was last written.
  - `Brain.lastProblem()` reports what happened.
- The files are UTF-8, formatted with `Locale.ROOT`. `exposure()` and `kinds()` return copies.
- Verified on a copy of the real `brain-play.pika-network.net.txt` (brain-1, 9319 lines): 111 kinds and 111
  modules migrated, and the original was untouched. That test (`BrainRealFileTest`) skips when the file is absent.

**Step 10, `util/AdaptivePolicy` (pure) and Adaptive.**
- **Pipeline:** Observation (a FlagDetector flag) → Evidence (Brain) → Hypothesis (a Suspect) → Confidence →
  Decision (the policy) → Action (Adaptive).
- **Refusal rules**, each counted as a reason:
  - not connected, or in the first `warmup-minutes` (3) of a connection (probing too);
  - below the excess threshold, below min-confidence, or `min-flags` (8) lifetime flags not reached for the kind;
  - fewer than 3 weighted flags on the module's side;
  - stale (the kind not seen for 14 days);
  - the module is protected, off, or `cannot-cause`;
  - `awaiting-evidence`: fewer than `react-minutes` (20) of play with the module on since the last action on it;
  - `resolved`: the flags since that action no longer show the excess;
  - `act-cap`: `max-acts` (2) per module until the server changes.
- The post-action evidence (`Epoch`) is gathered by Adaptive: minutes on the client thread, flags from
  observeFlag. **This fixes F-19**: adaptive.txt on 09-23/24 shows "would disable Trajectories" nine times in a
  row, five minutes apart. A replay test now gives 1 action if the problem resolves, and 2 (the cap) if it persists.
- **Plausibility:** a module whose every handler is a render event cannot cause a correction. Trajectories and
  Tracers, both named as lagback causes in that log, are now never acted on. A module with no handlers works
  through mixins and stays a suspect (KeepSprint changes movement), except a short cosmetic list: NoHurtCam,
  ItemPhysics, Animations, Capes, ViewClip.
- **Every action writes one `DECIDE` line** to adaptive.txt: the detector, kind, suspect, excess, on and off
  rates, minutes on each side, flags on, confidence, lifetime/recent/session counts, last seen, old and new
  value, the action count out of the cap, the reason, and what was passed over and why. A round that holds
  writes `HOLD <reason>; passed over: ...`, only when that line changes. The last 20 decisions are kept for
  diagnostics (`recentDecisions()`). Dry-run follows the same rules, so its log no longer repeats itself.
- **On START on a different server,** everything Adaptive changed for the old server is put back and its
  per-module memory cleared.
- New properties: `min-flags`, `warmup-minutes`, `max-acts`, `react-minutes`.

**Tests:** BrainTest (12), BrainRealFileTest (1), AdaptivePolicyTest (10), AdaptivePlausibilityTest (4). 106 in total.
**Snapshots:** `backups/src/src-before-step9-20260928` and `backups/src/src-after-step10-20260928`.

## AutoTune validation, evidence-weighted attribution, LatencyGovernor follow-up (plan steps 11-12, 2026-09-28, 09:54 jar, installed 09:54)

**The 09:27-jar session (09:31-09:44, hypixel.net)**:
- The user had **Adaptive on (dry-run, but probe on)**. It probed AimAssist *off* for 8 minutes from 09:37:46;
  probing ignores dry-run by design. AimAssist was restored at exit (default.json has it on).
- LatencyGovernor was on. Its tiers were clear / holding back / minimum. It went to **minimum at 09:36:24 on a
  68 ms jitter spike and left 6 s later**. Tier 2 cuts the packet-holding modules for the rest of the connection,
  so a 6 s visit costs a whole connection (fixed below).
- 334 "Error executing task" are vanilla NPEs:
  - Scoreboard objectives and teams on lobby switches (S3B, S3E).
  - 6 × `handleSpawnPlayer` (S0C): a player whose tab entry has not arrived. The same 6 also occur in the 09-27
    log (Pika, old jar), so this is **not from today's changes**. It is still open: it could be server ordering, or
    an inbound holder delaying S38 but not S0C. Not investigated.

**Step 11, AutoTune (`util/TuneExperiment`, pure).**
- **Blocks:** each candidate is played as 4 blocks of `trial-minutes`: current, candidate, current, candidate.
  - It is COMMITTED only if better in both pairs and by `margin` (2.0 score points) on average.
  - It is REJECTED early if the first pair is worse by the margin.
  - A block with too few swings, a counter reset (join), or a masked value (a higher override) is replayed, up to
    `max-replays` (3); after that the experiment is EXPIRED. A server change also expires it.
- **Commit:** the one path into the config is `Property.setBase(Source.TUNED, value)`, a new base source that is
  saved. The blocks run as TRIAL overrides.
- **Instruments:** it reads counter deltas and no longer resets FlagDetector's or HitCheck's counters (C6).
- **Active knob only:** it tunes only the BackTrack delay in use (F-22).
- **autotune.txt** gets START / BLOCK / REPLAY / COMMIT / REJECT / EXPIRE / ABANDON lines, each with the trial
  id, parameter, current value, candidate, swings, hit%, flags, score and reason.
- **Behavior change:** a candidate needs about 20 minutes of fighting to be kept, not 5. Kept values are now
  saved; before, they were reverted on disable (and leaked on exit).

**Step 12, attribution (`util/Attribution`, pure).**
- **Weights by kind:** 1.0 for held-send and release (own movement); 0.8 for held-self and release-self (own
  knockback or position, S08/S27/our S12); 0.5 place; 0.4 dig; 0.3 action; 0.2 attack; 0.1 held-recv (other
  entities); 0.05 swing.
- **Recency and volume:** actions are weighted by recency (τ = 1 s) within the blame window, and saturate per kind
  (volume does not multiply). An UNKNOWN prior of 0.5 is left to causes the ledger cannot see.
- **Verdicts:**
  - SINGLE: at least 50%, and at least 2× the next candidate.
  - MULTIPLE_CANDIDATES: the top two within 2×, the second at least 20%.
  - INCONCLUSIVE: anything else with evidence.
  - UNKNOWN: nothing in the window.
- **FlagResponder acts only on SINGLE.** FlagDetector now implicates only the SINGLE culprit. The old blame named
  everyone active, plus modules that were merely enabled. Replayed on the 09:41:16 flag ("Backtrackx41
  BLINKx2"), the verdict is SINGLE Blink, with BackTrack at about 7%.
- **Timing fix:** the attribution is computed at the correction. For a burst it is the attribution of the largest
  correction; before, it was computed when the burst was reported, possibly a second later.
- **Flags lines** now end with e.g. `SINGLE Blink 59% (held-send x2) | BackTrack 7% (held-recv x41) | unknown 34%`.
- **Ledger changes:**
  - Records carry a count, and `records(ms)` is a snapshot.
  - `holdKind` separates self from other inbound holds.
  - Releases are stamped when they happen: BlinkManager (release of a queue, and single drains), LagManager flush
    (count), FakeLag `send`, KnockbackDelay `flush` (release-self).
  - Blink holds are named after the module (`BlinkModules.moduleName()`: AntiVoid, not ANTI_VOID).
  - FlagResponder's PROTECTED list adds Adaptive, AutoTune, ServerFingerprint and FightLog.

**LatencyGovernor follow-up.**
- The first decision needs 10 samples (was 3), and is never tier 2.
- Entering tier 2 needs 3× `settle-seconds`.
- Tests replay the 09:36:24 spike: it never reaches tier 2.

**Tests:** TuneExperimentTest (8), AttributionTest (13), and LatencyTiersTest (+2). 129 in total.
**Snapshots:** `backups/src/src-before-step11-20260928` and `backups/src/src-after-step12-20260928`.

## Two fixes from the 09:54-jar games, and the packet-holder registry (plan step 13, 2026-09-28, 10:15 jar, not installed yet)

**The 09:54-jar games (09:56-10:08, Hypixel then Pika, about 5 short 1v1s).**
- Brain migration worked for hypixel, pika and singleplayer; the originals are in `backups/brain/`.
- Attribution, examples:
  - `SINGLE Blink 80% (held-send x18 release x18)` on a 6.87-block relocate.
  - `INCONCLUSIVE Backtrack 17% (held-recv x6)` where Backtrack held only others' packets.
- Placements: 584 OK and 1 REJECT. FightLog: 29 fights, 82-58 (1.41), first hit 16, THEY FELL 10 vs WE FELL 5.
- Errors: all vanilla scoreboard NPEs.

Two problems:
1. **LatencyGovernor flapping.** It changed tier 16 times in 11 minutes on Pika. The average swung 110-210 ms with
   jitter sitting at the limit, so jitter on top of a middling average reached tier 2 four times (twice for under
   10 s) and cut LagRange for the connection.
   - Fix: jitter can lift a clear line to tier 1 but never to tier 2; tier 2 is for a slow average only.
   - `settle-seconds` default is now 10. The user's config still says 5; change it when the game is closed.
2. **Adaptive dry-run: "would disable TargetFilter".** It rested on a kind last seen 3.5 days earlier (0 this
   session) and only 9.3 min with the module on.
   - Fix: settings-only modules (ThemeStyle subclasses, TargetFilter, Theme, the GUI modules, Debug) are never
     acted on (`settings-only`).
   - Fix: the policy needs the kind to have occurred **this session** (`not-this-session`).

**Step 13: `management/PacketHolds`.**
- All seven holders register a Source that reports holder, direction, count, since, reason and release
  condition: LagManager (holder name, delay), BlinkManager (`Blink/<owner>`, since the owner took it),
  DelayManager (`DelayManager/<module>`), FakeLag (mode, plus a held attack), Backtrack, KnockbackDelay (time to
  release) and ServerLag.
- `PacketHolds.describe()` gives one line; FlagDetector's context shows it as `holding ...`. It used to name two
  holders ("lagging N pkt", "KBDelay holding N").
- **F-27:** ServerLag and DelayManager now obey `Arbiter.catching()`. During a catch they release what they hold,
  in order, and hold nothing more; before, they kept holding incoming packets through a catch.

**Tests:** PacketHoldsTest (5), plus new cases in AdaptivePolicyTest, LatencyTiersTest and AdaptivePlausibilityTest.
137 in total.
**Snapshots:** `backups/src/src-before-fix13-20260928` and `backups/src/src-after-step13-20260928`.

## AsyncLog and FightLog counting (plan step 14, 2026-09-28, built, not installed: the game was running)

- **AsyncLog (F-29):**
  - The queue is bounded at 20000 entries; overflow is dropped and counted (`dropped()`).
  - The worker restarts if it has died. Before, an Error in write killed it for the session, and all logs were
    lost silently.
  - Write failures are counted (`failed()`).
  - The exit drain stops the worker and waits up to 2 s for it, then writes the rest in order. An entry the worker
    had already taken used to land after the drained ones, or never.
  - Tests: AsyncLogTest (4).
- **FightLog (F-35):**
  - Its packet handler is now LOWEST and skips cancelled sends.
  - A held attack released by FakeLag is counted when it is sent (`FightLog.noteAttackSent`), with the sprint
    state at that moment.
  - Before, REPEL-merged clicks (never sent) and FakeLag-queued attacks were counted as swings at click time.
  - **This breaks comparability with FightLog numbers from REPEL/FakeLag sessions before it.** It has no effect
    when neither is on (today's LagRange games).
- 141 tests in total. Snapshots: `src-before-step14-20260928` and `src-after-step14-20260928`.
- Next, by the user's choice: **JumpReset** (anti-combo), before plan step 15. Evidence from 692 fights since
  09-26: once the opponent combos 3+, THEY FELL is 2 and WE FELL 7 (81 fights); at 0-1 combo it is 139 vs 67.

## Reliability pass 1/6: ServerSession (2026-09-28, afternoon)

The user asked for a reliability pass in six stages, stopping after each for approval.
- **Split the key:** `serverKey()` (current, or last after a disconnect) is replaced by `currentServerKey()`
  (null when not connected) and `lastServerKey()`. Adaptive, the only caller, uses current, then last, then
  ServerData, which is the same behaviour stated explicitly.
- **Stale-join race:** S01 arrives on the network thread and is queued to the client thread. If the world was
  unloaded (a disconnect) between arrival and execution, the queued join used to open a session for a dead
  connection, and it stayed open until the next connect.
  - `worldLoaded(false)` now bumps a `generation`.
  - `joinArrived(address)` binds the join to the generation current when the packet arrived.
  - A stale join is dropped and counted (`staleJoins()`).
- **Tests:** ServerSessionTest has 13 (+5): current vs last server, a join queued before a disconnect, two queued
  joins on one connection, a stale join then a new connection, and a reconnect after a stale join. A mutation
  check (guard removed) fails the two stale-join tests. 146 in total.
- **Files:** `management/ServerSession.java`, `module/modules/Adaptive.java` (one call site), and the test.
- The user stopped the pass after stage 1 ("這邊應該夠了") and asked for a Rise 6.9.5 comparison
  (docs/PAID-CLIENT-GAP.md, last section). Stages 2-6 are not done.

## Packet hold leases (2026-09-28, afternoon; item 1 of the Rise comparison)

Rise ends a hold that its owner stops renewing (BlinkComponent: 100 ms). Our seven holders each release through
their own code, and a holder that never reaches that code (a module switched off by a path that skips the
release, a handler throwing every tick, a forgotten branch) held forever, with nothing noticing.
- **Mechanism (`PacketHolds`):**
  - `register(Source, Lease)`. A `Lease` names the owning module, gives a ceiling (the longest hold its settings
    can ask for, plus `LEASE_MARGIN_MS` = 1 s; negative = none), and gives `expire()` (release everything, in
    order).
  - `PacketHolds.Watchdog` runs `enforce()` on every POST tick at LOWEST, after the holders' own releases. It ends
    a hold when its owner module is off, or when the hold is older than its ceiling.
  - Each ending is counted (`expiries()`, `failures()`, `lastExpiry()`), printed to latest.log ("packet hold lease
    ended: ..."), and shown in FlagDetector lines for 3 s ("lease ended Nms ago: ...").
  - An unknown module name counts as on. A lease that throws is left alone and counted. One holder failing to
    let go does not stop the others.
  - `register(Source)` without a lease is unchanged.
- **Leases, by holder:**

  | Holder | Owner | Ceiling |
  |---|---|---|
  | BlinkManager | The blinking module | Per `BlinkModules.leaseMs()` |
  | LagManager | The module that set the delay (none if unknown) | `tickDelay`×50 ms + 1 s. Expiry sets the delay to 0 and flushes all |
  | DelayManager | Velocity / BedNuker | None (a bed takes as long as it takes). Orphans (NONE) go at once |
  | FakeLag | Itself | `delay` + `delay-random` + 1 s |
  | BackTrack | Itself | max(normal, adaptive) + `randomize` + 1 s |
  | KnockbackDelay | Itself | max(air, ground) + `Randomize` + 1 s |
  | ServerLag | Itself | `Lag ms` + 1 s |

  `BlinkModules.leaseMs()`:
  - KillAura (autoblock), Displace, Hitflick, NoSlow: 2 s.
  - Scaffold: 5 s.
  - NoFall, AntiVoid: 10 s (a whole fall).
  - Blink: none.
  - NONE: 0 (orphans).
- **Behaviour:** unchanged when every holder releases by itself, because both limits sit outside anything a
  working holder does.
- **Real case found while auditing:** KillAura's Hypixel3 autoblock (mode 9) sets the blink every tick while a
  target is valid, but only advances the cycle that releases it when not digging or placing. If you dig or place
  near a target, the blink never ends. Its lease ends it at 2 s. KillAura itself is unchanged (out of scope).
- `BlinkManager.setBlinkState(false)`'s release body moved to `releaseAll()`, shared with the lease. Its
  behaviour is unchanged.
- **Tests:**
  - PacketHoldsLeaseTest (19):
    - The rules.
    - Failure isolation.
    - No double ending.
    - For Blink, Lag and Delay: owner off, past the ceiling, no ceiling for the player's Blink, blinking again
      after a lease ends, order kept, the delay reset, and an unknown holder.
  - Mutation checks: owner check removed, 9 fail; ceiling check removed, 3 fail. 165 tests in total.
- **Snapshots:** `backups/src/src-before-lease-20260928` and `src-after-lease-20260928`.
- Installed at ~14:50 (old jar in `backups/jars/...pre-lease`).

## Rise items 2 and 3: per-tick action guard, KillAura "Advanced" rotations (2026-09-28, ~15:10)

The user asked for KillAura to "do exactly what Rise does". The model below reproduces Rise 6.9.5's behaviour: the
same steps, constants and defaults. It was rewritten from a reading of the decompiled source; no code was copied.

- **`management/TickActions` (item 2, Rise's BadPacketsComponent):**
  - Records which action kinds were sent uncancelled through the bus since `runTick` HEAD: SLOT (C09),
    ATTACK / INTERACT (C02), SWING (C0A), USE (C08), DIG (C07), and INVENTORY (C0E, C0D, C16 open-inventory).
  - Registered in Myau as `TickActions.Listener`.
  - KillAura takes a snapshot at the start of its PRE update, before its own blocking packets. `performAttack`
    refuses when USE, DIG or INVENTORY already went out that tick (someone else's placement, dig or inventory
    click). This is Rise's `canAttack = !bad(false, false, false, true, true)`.
  - Refusals are counted: `TickActions.refusals()`.
- **`util/AdvancedAim` (item 3):** a pure class that is testable, with a seeded Random.
  - Eye:
    - Motion is an EMA: target 0.72/0.28, player 0.76/0.24.
    - Predicted point: height clamp(h×0.82, 0.35, h−0.12); lead clamp(prediction + dist×0.017, 0, 3.5) times the
      relative motion; clamped to box ±(0.18, 0.1, 0.18).
    - The aim point follows after a reaction (180 ± 44 ms, gaussian, clamped 20–700).
    - A jump bigger than 0.36 + min(0.85, d×0.07) is taken early, with reaction ×0.6 and jitter ×0.5.
    - Each update moves clamp(anchor + moved×0.32, 0.08, 0.7) of the way.
  - Target pick (Rise `RotationUtil.a`): the box's nearest point to the eyes, inset 0.03, while a look at it lands
    within range. Otherwise, the landing candidate nearest the eyes (192-point grid), else the aim point.
  - Hand (WindMouse):
    - Gravity 9. Wind 6, in the form w/√3 + N(w/√5) beyond the damped distance of 12.
    - Step size 15, shrinking by /√5 close in.
    - Burst: 21%×0.12 per tick. Velocity is capped to step×pace×U(0.52, 1).
    - Damping below 5° and 2°. Cruise floor 1.0 below 14°.
    - Proportional gain (0.16 + 0.42×d/45)×(0.55 + 0.45×acc).
    - Fine settle below 1.65°. Optional gaussian.
    - Flick guard: 29° × (0.85 + 0.4×d/24) × U(0.92, 1.12).
    - Deadzone hold: 1°, on target, 2 ticks, renewed while inside.
    - Overshoot on a new target 9° or more away: chance 77×(1.15 − 0.35×acc). Size max(1.5, d×scale) capped at
      17. Lasts 8–13 ticks, until within 1.15°.
  - Finger: trigger reaction 95 ± 30 ms (0–450) after the look lands; re-armed when it leaves or the target changes.
  - Rise's decompiled `b(spread, max)` lost the draw's assignment (it returns 0, or +max on a rare tail). A clamped
    gaussian is implemented, which is what the surrounding clamp means.
- **KillAura:**
  - Rotations mode "Advanced" (index 6), with 25 `Adv-*` settings and Rise's defaults and ranges.
  - The current rotation is `event.getYaw()`, which is lastReportedYaw (the server rotation, like Rise's).
  - GCD is `RotationUtil.gcd`. `performAttack` requires a box intercept, as LiquidBounce mode does.
  - `Adv-Swing`: when a click is due and the look is off the target, it swings at the air and consumes the click.
  - SmoothBack and MoveFix cover mode 6. Other modes are unchanged.
- **Config:** KillAura Rotations Silent → Advanced (backup in `backups/config/default.json.pre-advanced-20260928`).
- **Tests:**
  - TickActionsTest (4) and AdvancedAimTest (19).
  - Mutation checks: flick guard off fails 2 tests; trigger immediate fails 2.
  - 188 in total.
- **Installed:** yes (the old jar is `pre-advanced`). Snapshot: `backups/src/src-after-rise23-20260928`.

## Game 14:56-15:03 (Pika) and the LOW-DAMAGE detector (2026-09-28, ~15:10)

- **Game (first with Advanced rotations):** 13 fights, 40-41 (0.98); THEY FELL 2, WE FELL 4.
  - HitCheck: 89/106 (84%), 11 dropped and 6 invuln, the same as earlier sessions (82-92%).
  - KillAura (Advanced) was on from about 14:59 to 15:01:20. Those fights went 15-21 (0.71); the manual fights
    after them went 19-14. The sample is small and ping was 187-236.
  - The LAGBACK and VERTICAL clusters are SINGLE LagRange 74-80% (LagRange was on this game). There were 8 REJECTs
    at 14:57:52, which are AutoBlockIn.
  - No lease endings, and no Myau exceptions.
- **The user's ask:** use KillAura's Debug Health to detect a "silent flag" (hits that land but take little
  health). Debug Health prints the health *this* player loses (S06/S1C for self), not damage dealt. So a separate
  detector reads the target's health.
- **`util/DamageWatch`:**
  - Once a tick, for targets attacked in the last 5 s, it reads health + absorption and hurtTime.
  - A flinch (hurtTime rising) within ping + 350 ms of our C02 is our hit. Damage = the health the tick before
    minus the lowest health over the next 6 ticks. The killing hit is not counted.
  - Usual damage is the target's own median once it has 6 hits, else the session median (last 12 hits).
  - 3 hits in a row at 35% of usual or less (0 counts) → one LOW-DAMAGE verdict per run.
  - Health hidden: 5 hits resolved with none moving health means the server hides it, and the check goes quiet
    (reported once).
  - Reset at every SessionEvent START.
- **FlagDetector:**
  - `detect-low-damage` (default on) and `damage-chat` (every hit's damage in chat).
  - LOW-DAMAGE is a violation: it is counted, goes into total(), gets blame, and gets an Adaptive signature
    (LOW-DAMAGE|ground|sprint|hit...).
  - It is coloured &5 and has no "blocks" field.
- **Tests:** DamageWatchTest (11). A mutation check (low rule off, hidden rule off) fails 4. 199 tests in total.
- **Not installed** (the game was running). Snapshot: `backups/src/src-after-lowdamage-20260928`.
- LOW-DAMAGE build installed at ~15:12 (old jar `pre-lowdamage`).

## Debug pass, Rise items 4-7, full check (2026-09-28, ~15:30)

**The 15:03-15:09 games (by hand, KillAura off):**
- 25 fights, 56-42 (1.33); THEY FELL 6, WE FELL 4.
- HitCheck 65/83 (78%). There were bursts of 7-9 "dropped" hits at dist 0.00 (15:02:11, 15:09:22): hits the server
  never acted on while the player was on top of the target.

**Debug of the earlier features:**
- **LOW-DAMAGE read health from metadata.** Minigame servers often do not keep it current. It now uses
  `HealthUtil.resolve` (tab list, then below-name, then metadata, the same source HitCheck uses), plus absorption.
  The verdict names the source.
- **HITS-DROPPED (new FlagDetector label):**
  - HitCheck reports each swing to `FlagDetector.noteHitResult`. It is "dropped" when the target was in range, in
    sight and out of invuln, and still no reaction came.
  - 4 in a row within 3 s gap each → one flag per run, with blame (`DamageWatch.dropped/landed`).
- **HitCheck summaries** now print `same-tick N` (`TickActions.refusals`).
- **Reviewed, no change needed:**
  - Lease ceilings: FakeLag caps itself at delay+500. BlockHit's right clicks are cancelled by KillAura while it
    has a target.
  - KillAura's own C08/C07 go out after its TickActions snapshot.
  - `kbDisplacing` is reset every PRE.
- **AimBacktrack** is off while KillAura is on, because Advanced's deliberate miss-swings are not its to fill.

**New features:**
- **`property/IntRange`, `FloatRange`, `PropertyGroup` (item 5):**
  - A range is two ordinary settings (`name-min`/`name-max`). The Myau registration loop adds the members of
    PropertyGroup fields, so every GUI and the config handle it with no UI change.
  - `IntRange.of(min, max)` wraps an existing pair without new keys. KillAura (MinCPS/MaxCPS) and AutoClicker
    (min-cps/max-cps) use it.
  - Draws are normalised: the sliders can be either way round.
- **`KeepRange` (item 4):**
  - Settings: range 3.0 (−0.2 within 7 ticks of an attack), STOP/BACKWARDS, combo-to-start 2 (target hurt-ticks
    > 8×N since last hurt), disable-near-edge (a 6-deep air column within edge-range 5).
  - Runs on MoveInputEvent at LOWEST (after the move fix), in the movement yaw (RotationState smoothed when
    active). Its maths is in `util/KeepRangeMath`.
- **`AimBacktrack` (item 6):**
  - Keeps the last `ticks` (1-20) sent rotations.
  - On a C0A with objectMouseOver MISS, the first rotation whose ray (reach 3 or Reach's range, not through blocks)
    meets a trackable player is attacked at this tick's PRE update via `playerController.attackEntity`. The
    order is swing → attack, and it happens at most once a tick.
- **Knockback displacement (item 7):**
  - `util/KnockbackPlanner`:
    - Scans 32 directions × 0.8..5.0 by 0.35, stopping at walls.
    - Hazards and scores: Lava 150−8d, Web 125−7d, Fire 100−7d, Cactus 96−6d, Void (minY ≤ 8) 145−7d, Deep
      Drop 108−7d, Ditch 88+3.5·min(drop,10)−6d, Water 58+2·drop−5d. The minimum score is 45.
  - `KBDisplacement` (manual, off while KillAura is on):
    - After a hit, while the target is hurt, the server yaw turns to the plan yaw (priority 2, speed×36°/tick, GCD).
    - The move is fixed to the camera. It skips a falling crit and needs a knockback source.
  - KillAura `KBDisplace` (Advanced only): OFF (default) / SAFE (within the box's yaw span ×0.85, still
    intercepts) / FULL (Rise: plan yaw, the hit allowed on range).
- **Menus:** 7 registered modules were in no classic list and so in no menu (Atlas reads those lists): FightLog,
  FlagDetector, FlagResponder, HitCheck, Hotbar, LatencyCrosshair and LatencyGovernor.
  - They are added to lists, and the 3 new modules go in Combat.
  - Atlas also adds an "Other" category for any registered module in no list.

**Full check:**
- `AllSettingNamesTest` builds 149 modules and asserts setting names are unique per module (config keys).
- It found 11 real clashes. Each clashing pair saved over itself; for NameTags the types differed, and "Failed to
  load property background for module NameTags" was in every latest.log.
- Renamed:
  - NameTags Raven-mode settings → `raven-*`.
  - BedwarUtils bed-tracker HUD → `tracker-hud*`.
  - Velocity Slap_Attack `reduce` → `slap-reduce`.
  - Those settings start from defaults once.
- TargetHUD `color`/`Color` differ in case only; config keys are case-sensitive, so this is left as it is.

**Tests:**
- 222 in total.
- New: RangeTest (7), KeepRangeAndKnockbackTest (11), SettingNamesTest (2), AllSettingNamesTest (1), and
  DamageWatchTest +2.
- Mutation check: the KeepRange stop rule off fails 1.
- Installed at ~15:28 (old jar `pre-keeprange`). Snapshot: `backups/src/src-after-rise4567-20260928`.

## Module categories, after LiquidBounce (2026-09-28, ~15:45)

- **Categories:** `module/Category` has Combat, Player, Movement, Render, World, Misc and Exploit (LiquidBounce),
  plus Client (nextgen: the menus, the diagnostics, the learning and tuning modules) and Theme (ours).
- **`module/ModuleCategories`** is the one table: class → category, and a duplicate throws.
  - The classic ClickGuiScreen builds one frame per category from it; five hand-kept lists were removed.
  - Atlas reads those frames, as before, and keeps its "Other" safety net.
- **Placement rules:**
  - A module LiquidBounce has goes where LiquidBounce puts it: Scaffold, FastPlace, Timer and ChestStealer
    in World; AntiBot and NickHider in Misc; GhostHand and Disabler in Exploit.
  - The fight packet-lag tools stay together in Combat, as nextgen files Backtrack, FakeLag and TickBase.
- **Counts:** Combat 34, Player 12, Movement 13, Render 36, World 11, Misc 16, Exploit 3, Client 15, Theme 15.
- **`ModuleCategoriesTest`:** every module registered in Myau.java has a category, and the table names nothing
  unregistered. It caught NoHitDelay on its first run. 225 tests in total.
- **Other menu styles** (Modern, Raven, Rise, RiseLB, Cheadle, the old ClickGui) still hold their own lists and are
  unchanged. The user uses Atlas.
- **Installed** (old jar `pre-categories`). Snapshot: `backups/src/src-after-categories-20260928`.

## AutoBlockIn/Clutch did nothing: an out-of-tree patch (2026-10-02)

**Symptom.** In the 18:28 game both modules had no effect. AutoBlockIn printed `blocked in, switching off` the
same second it was switched on, every time (logs/latest.log).

**Cause: classes compiled outside Gradle.** The installed `mods/Myau__fixed.jar` was not a Gradle build.
- On 10-02 10:09 a rewritten AutoBlockIn, a reduced 12 KB Clutch and a new `util/PlaceUtil` were patched straight
  into it. They were compiled against hand-written stand-ins for the Minecraft classes. In those stand-ins
  `AxisAlignedBB.minX..maxZ` and `Vec3.xCoord..zCoord` were constants, so javac inlined all 29 reads as `0.0`
  (`Objects.requireNonNull(box); dconst_0` in javap).
- AutoBlockIn's body cells were therefore always empty, so it found no goals and turned itself off. Clutch's catch
  cells were always empty, and every aim was `atan2(0,0)`. The event bus, mixins and registration were all fine:
  the handlers ran every tick.
- **Check for it:** `javap -c` on any class that reads box or vector coordinates must show `getfield
  ...AxisAlignedBB.field_72340_a` and the like, never `requireNonNull` followed by `dconst_0`.

**My own mistakes that day.** Kept here because they are the kind to repeat.
- I rebuilt the reduced Clutch instead of the source one. The source Clutch is the measured 09-25 version (five
  passes above), and its last log is clutch-20260927. **Restored.**
- I installed by rewriting the jar with .NET `ZipArchive` (Update mode). `FMLLoadingPlugin.walkJar` lists mixins
  with `ZipInputStream`, which stopped after 2 entries of the rewritten file. The result was `Found mixins: []`
  and **the whole client gone in game**. Never hand-edit a jar. Install exactly as §6 says.
- My first AutoBlockIn dropped BLOCK_SCORE (§5) and would have accepted TNT. **Restored.**

**What is in source now.**
- AutoBlockIn is new (`util/PlaceUtil`):
  - Tiers, in order: walls, a one-step support for a wall, roof, roof support. This replaces the 8964-node BFS.
  - The aim is the cheapest valid one. It keeps the server's current look if that already places, then the
    locked aim if it is still valid, then grid points in turn-cost order, each ray-checked against its own support
    face, capped at 96 rays.
  - It clicks in UpdateEvent PRE, only when a ray from the rotation the server already has hits the face.
  - When `onPlayerRightClick` returns false, nothing is counted, the face is skipped for 10 ticks, and the next
    plan is made the same tick.
  - It keeps BLOCK_SCORE, refusal memory (ms, `retry-cooldown`), show-progress, item-spoof and move-fix.
  - New `debug` writes `config/Myau/placedebug-*.txt` (at most 4 chat lines a second).
- Clutch is the 09-25 source plus `failedFaces`: a face whose click returned false is skipped for 10 ticks in
  `aim`, `planLadder` and `post`. Before, the per-tick re-plan usually picked the same refused face again.
- From the 10-01 23:10 jar, which was also built outside Gradle, three changes were taken:
  - ParticleFix, used by HitParticleEffects: particles go straight into EffectRenderer, within 32 blocks.
  - `PlayerStateManager.isInCombat` expires the combat state itself.
  - BackTrack shows only the delay that applies.
- Two other 23:10 changes were **not** taken:
  - `Property.setValue` cleared every override, the governor included. That breaks value ownership (step 7), and
    `PropertyOverrideTest.thePlayerChangingTheValueWhileGovernedChangesTheBase` fails with it.
  - The Atlas slider read and wrote the effective value instead of the base.
- New `PlaceUtilTest` (6 tests). 231 tests, all pass.

**Install.** Built 19:18. NOT installed: the game was running (pid 1820, started 19:11). The jar to install is
`build/libs/Myau+.jar-2.1+4.jar`, and it replaces `mods/Myau__fixed.jar`; there must be only one Myau jar in mods.
Backups:
- `backups/src/src-before-placement-repair-20261002` holds the source before any of this, plus the decompiled
  bad classes.
- `backups/src/src-before-clutch-restore-20261002`.
- `backups/jars/Myau__fixed.jar.pre-placement-repair` is the 10-02 10:09 jar.
- `backups/jars/Myau__fixed.jar.placement-repair-repacked` is the jar of the second install.

## The broken jar came back; the Gradle build installed (2026-10-02, ~20:00)

**Symptom (user report).** AutoBlockIn and Clutch did nothing again.

**Cause.** `mods/Myau+.jar`, put there at 19:32, was **byte-identical** (md5 7387621515…) to
`backups/jars/Myau__fixed.jar.pre-placement-repair`, the 10:09 out-of-tree jar from the section above. javap on its
AutoBlockIn/Clutch/PlaceUtil: 0 `getfield` of AxisAlignedBB/Vec3 coordinates, 6/14/9 `requireNonNull` + `dconst_0`.
The 19:18 Gradle jar has the field reads and no `requireNonNull`. The source was not at fault; no code was changed.

**Runtime evidence that the source versions reach placement.**
- AutoBlockIn (19:11-19:25 game, repacked jar with the same AutoBlockIn minus BLOCK_SCORE,
  `placedebug-20261002-191300.txt`): 101 `controller click result: true` / `success: placed`, six wall blocks in
  ~0.5 s per activation, aim from the server's own rotation, PRE.
- Clutch: the source version (restored today, + `failedFaces`) is the 09-25 one. Its last logs
  (clutch-20260926/27) show `placed ... (catch in Nt)` and `saved with 1 block`. The 19:12 void fall in the
  placedebug file was the reduced out-of-tree Clutch (`catch: 0`), which is no longer in source.

**Open, not changed (out of scope for "does nothing").** In that game the roof was usually not placed:
`roof-support: 4 goal(s), candidate count = 0`. Standing, the eyes are at y+1.62 (63.62 for feet 62) and a y64
roof-support can only be placed on the top face of a y63 wall (plane 64, above the eyes, so no ray can hit it) or
on another y64/y65 block. That is geometry, not a bug; a roof needs a jump or something already above. The old
942-line version had the same limit.

**Not the same as the jar it replaced.** The Gradle jar does not have the two 10-01 23:10 changes that were
deliberately not taken (Property.setValue clearing all overrides; the Atlas slider editing the effective value).

**Install.** Game not running. Old jar moved to `backups/jars/Myau+.jar.pre-gradle-reinstall-20261002`;
`build/libs/Myau+.jar-2.1+4.jar` copied to `mods/` (md5 685a3126…, matches). ZipInputStream reads all 5936
entries, 62 mixin classes (same as the `Found mixins` list in the 19:11 log). 231 tests pass.
The two other jars the user named, `Myau_Ultra_final_fixed.jar` and `Myau+.jar-2.1+4(3).jar`, are not anywhere
under <home>, so they could not be compared.

### Review against daikirai-0 (same evening, ~20:00)

The user asked for a bug check of today's AutoBlockIn/Clutch/PlaceUtil, with
`<reference clients>/Raven daikirai/daikirai-0.jar` as the reference (decompiled with CFR).
- daikirai's AutoBlockIn does the roof first, but `roofAim` only clicks blocks at y >= floor(eyes)+1. So it has the
  same limit as ours: standing on flat ground, there is no roof. That confirms the geometry note above.
- Checked and correct: PRE `event.getYaw()` is `lastReportedYaw` (MixinEntityPlayerSP), `item-spoof` is read by
  MixinEntityRenderer/MixinGuiIngame through `getSlot()`, the Clutch `failedFaces` keys are the clicked face at all
  three call sites (aim, planLadder, post/postLadder), and `planChain` goes through `aim()`.
- **Fixed:** with `debug` on, `AutoBlockIn.onPacket` (network thread) -> `refuse()` -> `trace.log()` wrote chat
  off the client thread (`addChatMessage`, a possible ConcurrentModificationException) and shared the trace's
  HashMaps between threads. `refuse()` now logs only when `mc.isCallingFromMinecraftThread()`. One `if`.
- Not changed, noted: `PlaceUtil.step` snaps to the mouse grid, so a remaining turn under half a grid step rounds to
  zero. If the ray from that rotation then just misses the face, the locked aim would not move. Not seen in the
  19:13 trace (every aim became `current` the next tick).
Backup `backups/src/src-before-tracethread-20261002`; replaced jar `backups/jars/Myau+.jar-2.1+4.jar.pre-tracethread`.
231 tests pass; installed (game not running).

### Checking an outside review table (2026-10-02, ~20:30, no code change)

The user pasted another AI's verdict table on AutoBlockIn/Clutch. Each "warning" item was checked against the code
and the logs. Nothing was changed. Backup: none needed (diagnosis only).

| Claim | Verdict | Evidence |
|---|---|---|
| `itemSpoof` not wired | **Wrong** | MixinEntityRenderer (~line 102) and MixinGuiIngame (~line 55) read `autoBlockIn.itemSpoof` + `getSlot()`, the same mechanism as Scaffold. It is display-only by design: the real slot is switched either way. |
| `usableBlock()` too strict | No | It rejects only non-full, non-solid, TNT, falling, tile-entity and interactable blocks. Every Bedwars wall block passes (wool, clay, glass, stained glass, end stone, planks, obsidian). daikirai also scores ladders, which cannot make a wall. |
| 96-ray cap / 4 misses per face | Possible in theory, not seen | In `placedebug-20261002-191300.txt`, 2 of 251 side plans found nothing. Both came right after a click, with candidate count 0 (filtered before any ray, so not the cap). Both recovered the next tick with 50/75 candidates and no slot swap. The cause is not proven; the stack running out and the server refilling it fits but is unverified. Cost: one tick. |
| Clutch 5-level search | Limit, harmless | Levels are feet-1..feet-5 of path[0] and re-planned every tick, so the window slides down with the fall. Supports the player fell past are above it anyway. |
| Clutch 16-tick window | Does not bind | From rest, 16 ticks is about 9 blocks of fall, so the 5-level limit binds first. |
| Future eyes vs actual eyes | By design | Plans use the eyes after this tick's move (`path[0]`). POST re-traces from the real post-move eyes with the rotation the C03 just carried. A mismatch means no click that tick, never a wrong click (the 09-25 fix for REJECTs). Logs 09-26/27: 25 losses = 19 `no blocks in the hotbar`, 5 `no block within 6 of the fall`, 1 `moved by the server`, and 0 from a mismatch. The 22 POST `turning: the look would place at` notes were mid-turn (look still 20-100 degrees off) or pre-aim plans. |
| POST strict ray check | Necessary | Without it, the click names a face the server's ray does not reach, and that is what got refused on 09-25. |

Side note: no module nulls a stack that reaches 0 after `onPlayerRightClick`. Vanilla `rightClickMouse` does.
Every module skips stacks of size 0, so there is no effect on behaviour. Left alone because it is codebase-wide.

Worth telling the user: most Clutch losses on 09-26/27 were simply no blocks in the hotbar.

## `move-fix REAL`: a real head turn for the rotating modules (2026-10-02, ~20:30)

**Request.** The user wants the modules that have a SILENT move-fix to also be able to turn the head for real.

**What REAL does.** It is the same two-part pattern as Clutch REAL (§5 Clutch, fifth pass):
1. The packet still carries the module's rotation (`event.setRotation`), so the server sees it this tick.
2. The camera follows through `Myau.rotationManager.setRotation(yaw, pitch, priority, false)`. RotationManager spreads
   the turn over this tick's render frames and finishes it at the next TickEvent PRE. Writing `rotationYaw` once
   a tick would give the 20 Hz view stutter Clutch had on 09-25.
3. Movement follows the turned head (`setPervRotation(yaw)`, because `move-fix != 0`). There is no `fixStrafe`:
   every `onMoveInput` checks `== 1` (SILENT) only. W goes where the head looks, as in vanilla.
4. `force = false`: the mouse keeps working. The camera goes to target + mouse delta, and the next tick re-aims
   from there. Only Clutch (during a catch) and KillAura LockView lock the mouse.

**Added to** (appended as the last option; configs store the mode *name*, so saved configs are unaffected):
AntiFireball, AutoBedDef, AutoBlockIn, AutoHeadHitter, BedNuker, Hitflick, PlainAura, Scaffold, ThrowAura.
Each module got one property option, one `if` after its `setPervRotation`, and an `import myau.Myau` where it
was missing. In Scaffold, `setPervRotation` was SILENT-only (`== 1`). It is now `!= 0`, so REAL movement follows
the head; NONE and SILENT behave as before.

**Not added.**
- KillAura already has a real turn: `Rotations = LockView` (rotationManager, mouse locked).
- AimAssist: `mode NORMAL` already turns the camera.
- Clutch: has `mode REAL`.

**Known consequences, by design.**
- The camera stays where the module left it. Hitflick turns back itself (its RESTORING step); the others do not
  hand the view back, unlike Clutch's `reset-angle`.
- Scaffold REAL turns the view backwards while bridging. The keys then mean what they mean for that view.
- If a higher rotation priority that is *not* REAL wins the packet later in the same tick, the camera shows the
  lower module's aim for that tick.
- Not measured in game yet.

STRICT is still unused: no module checks `== 2`. Not touched.

Backup `backups/src/src-before-realrot-20261002`; replaced jar `backups/jars/Myau+.jar-2.1+4.jar.pre-realrot`.
9 files, +61/-10. 231 tests pass. Installed (game not running), md5 f8d22040…, 62 mixins by ZipInputStream.

## Grim flags on AutoBlockIn/Clutch, Atlas key and colour editors, AutoTune vs the slider (2026-10-02, ~22:40)

User report after the 22:06-22:16 session on `test.ccbluex.net` (Grim). Four items:
1. Both modules flag; Raven daikirai (video) does not.
2. Clutch's hold key cannot be set.
3. Theme colours cannot be changed: they want a colour picker.
4. The BackTrack adaptive-delay slider moves, but the number does not.

### 1. Grim: what was flagged and why
Evidence: `logs/latest.log` chat, `places-20261002-220739.txt` (every C08 with `sentAge`), and
`clutch-20261002-220852.txt`.

- **AutoBlockIn: `RotationPlace post-flying`, every block but the last** (22:07:40, and 22:11:04-26 vl 2→16).
  For a 1.8 client, Grim judges a placement at the *next* flying packet, by the yaw in that packet (pitch: either
  one), from the position at the click.
  - AutoBlockIn clicked in PRE along the rotation the server already had. That is right for the position, but it
    then sent the turn toward the next cell in the same tick's C03. So the packet after the click looked elsewhere.
  - **Fix:** in PRE, compute this tick's step first. Click only if a ray along *that* rotation, from the current
    (pre-move) eyes, hits the face (`PlaceUtil.verify`). Then send that rotation. This is vanilla's own order:
    the click uses the look about to be sent. It is also daikirai's order (`onClientRotation` computes `smoothed`,
    raycasts it, queues the place; `onPreUpdate` places before the C03). The re-plan after a click is gone: the
    next cell is planned next tick.
- **Clutch: `Post` ("player block placement v1.8"), `RotationPlace post-flying`, `MultiPlace`,
  `AirLiquidPlace`, `DuplicateRotPlace`.**
  - Clutch clicked in UpdateEvent POST, after this tick's C03. Grim flags any place between a flying packet and the
    next transaction reply. It then judges the place by the next C03, which Clutch had already turned.
  - **Fix:** the click for tick N's plan now runs at the start of PRE N+1. It runs after the cooldown, pause and
    refusal bookkeeping and before `predict`. For the server this is the same moment (after C03 N, before
    C03 N+1) and the same position (nothing moved). But it comes after the tick's transaction replies, and tick
    N+1 then *holds* the rotation (`holdThisTick`: `stepRotation` and the jump snap do nothing; `applyRotation`
    re-sends it). So C03 N+1 carries the look that made the click.
  - Cost: after a click, one tick without turning. Planning (eyes `path[0]`, catch ticks) is unchanged. An S08
    between POST N and PRE N+1 is now seen *before* the click (`corrected()` → `pauseTicks`), which POST could not
    do.
  - **MultiPlace/AirLiquidPlace** (22:08:51, 22:09:19): `places-*.txt` shows the second place was the *player's*
    right click with FastPlace (`face up`, `sentAge 1t`, towering). It landed in the same flying window as
    Clutch's, against the block Clutch had just placed, which Grim had not applied yet.
    - Clutch now cancels `RightClickMouseEvent` while `active` (last tick's decision = the tick whose plan clicks
      next). daikirai cancels mouse buttons and unpresses use/attack.
    - AutoBlockIn cancels it while enabled.
  - **Why Clutch was catching at all:** the player had been hit 1.9 s earlier (`combat-window` 2500 ms) and
    jumped on a 1×1 tower with a little drift, so the prediction left the pillar: "5.3 block drop". The `edge`
    trigger already ignores falls with right click held; the `hit` trigger does not. Not changed. The user can
    use `trigger HOLD` now that the key can be set (item 2), or shorten `combat-window`.
- **DuplicateRotPlace** (experimental in Grim) flags a placement whose |yaw step| (> 2°) equals the one at the
  previous placement. Both modules now remember the yaw step that was last when they placed (`placedTurn`), and
  move a step that would equal it by one mouse count (`RotationUtil.gcd()`).
- Not ours, not changed, listed for the next session: `Timer` ×11, `TransactionOrder`, `Post click window` ×6
  (an inventory click after a flying packet), `Simulation` ×15, `GroundSpoof` ×4. FakeLag, Backtrack and Eagle
  were on.
- The video was not decoded. The daikirai behaviour above is from its decompiled `Clutch`/`AutoBlockIn` (CFR,
  `<reference clients>/Raven daikirai/daikirai-0.jar`).

### 2-3. Atlas: KeyProperty and ColorProperty were read-only
`drawProperty` had Int/Float/Percent, Boolean, Mode and Text. Everything else fell through to "shown but not
editable". Added:
- **KeyProperty** (Clutch `hold-key`, …): a capsule. Click it, press a key; Esc clears. A click elsewhere cancels.
  Same flow as module binds (`keyEditing` beside `binding`). Keyboard only, as `KeyProperty` is
  (`v >= 0`, `Keyboard.isKeyDown`).
- **ColorProperty** (Theme `color-1..3`, HUD custom colours, every ThemeStyle group):
  - The row shows a swatch and a `#RRGGBB`. Click the hex to type one (reuses the `type` edit; `parseString`
    strips `#`).
  - Click the row to open a picker under it (`PICKER_HEIGHT` 74): a saturation/brightness field (white→hue
    `rectH`, then clear→black `rect`) and a hue strip.
  - Edited in HSB kept by the menu (`colorHsb`), so the hue survives grey. It is re-read whenever the RGB changes
    elsewhere (`colorSynced`).
  - Dragging goes through `followDrag`/`applyDrag` like the sliders (`colorDrag`). Each part takes clicks only while
  wholly inside the pane, so a half-scrolled open picker still closes from its header.
  - `rowHeightOf` is an instance method now.
- Theme note for the user: `color-1..3` are only shown, and only used, in STATIC/GRADIENT/TRIPLE/PULSE (`uses()`
  in ThemeStyle). Their Theme was ASTOLFO. Changing a colour by hand already switches `preset` to CUSTOM
  (`Theme.verifyValue`).

### 4. AutoTune held the value under the slider
`autotune.txt` 22:06:30: `START #2 BackTrack.adaptive-delay 68.000 -> 92.000`. The slider writes the base
(value ownership, step 7) and the bar shows the base, but the number is `formatValue()`, the *effective* value:
the TRIAL. AutoTune would then have scored the block as `masked` and replayed it, and a COMMIT would have
overwritten the player's choice with `setBase(TUNED, …)`.
- **Fix in AutoTune:** if the knob's base differs from `experiment.current`, the player changed it. AutoTune
  then expires the experiment ("changed by the player"), releases the override (`finish()`), marks the knob
  `byHand`, and skips it until AutoTune is toggled (`startNext`).
- **Atlas:** a numeric setting under an override shows `value (Owner)`. On hover it shows `describe()` and
  "the bar is your value".
- The 10-01 23:10 approach (setValue clears every override, governor included) stays rejected. LatencyGovernor
  overrides are untouched.

Backups: `backups/src/src-before-grim-gui-20261002`, `backups/config/default.json.pre-grim-gui-20261002`;
replaced jar `backups/jars/Myau+.jar-2.1+4.jar.pre-grim-gui`. 5 files, +379/-51. 231 tests pass. javap: no
`requireNonNull`/`dconst_0` inlining. 62 mixins by ZipInputStream. Installed (game not running), md5 e531bb41…
Not measured in game yet.

## Blink: Timer on Grim, `hold-transactions` and `release-every` (2026-10-02, ~22:50)

**User report:** the Timer flags (11 in the 22:06-22:16 session) are Blink. Raven Alter's Blink "releases by
itself" and is not flagged.

**Evidence.**
- Chat: `Blink: ON` → `released on latency budget (4.6 blocks, 14 packets)` → `Timer vl0..4` in the same
  second → `LAGBACK 3.14` (FlagDetector: `SINGLE Blink 80% (held-send x22 release x33)`). The same again at
  22:10:54-56. The user also toggled Blink on and off by key six times in 22:10:46-48, and every OFF releases the
  whole queue.
- Config: Blink `mode PULSE`, `auto-send` on, `release-per-tick 2`.

**Cause.** Grim's Timer check counts 50 ms per movement packet against a clock it reads from the client's
transaction replies (C0F). BlinkManager never held C0F (the Pika rule, §2), so during a hold Grim's clock for the
client kept running while no movement arrived. Then 14 movement packets (700 ms worth) arrived faster than one a
tick, which is more than the clock allowed. §1 again: a catch-up faster than one packet a tick is a violation,
and Grim can see it because the replies say what time it is. `release-per-tick 2` cannot help, and 1 never
catches up.

**What Raven Alter does** (`Desktop/Mods/CHEEAT/Raven(alter).jar`, md5-identical to daikirai-0.jar,
decompiled):
- `UnifiedLagHandler` / `BiTrackLagNodeQueue` queue *every* outgoing packet, transactions included, in one
  ordered track.
- When the module turns off (`ModuleBackedTimeout`), the track is popped in order.
- "Release packet every N tick" (`releaseNextPacket`, default −1 = off) sends the oldest node while holding.
- The trickle is not what avoids Timer. The ordered replies are.

**Changes.**
- `BlinkManager.holdTransactions` (static `BooleanSupplier`, default false): when true, `offerPacket` queues C0F
  from *any* thread, so no reply overtakes a queued one (Grim TransactionOrder). Keep-alives and chat are still
  never held.
- `BlinkManager.discardHeld()`: drops held movement but sends held C0F in order. It replaces the two
  `blinkedPackets.clear()` calls: Blink's `release-on-correction` (S08) and Panic's `drop-held`. With the option
  off the queue has no C0F, and this is a plain clear.
- Blink `hold-transactions` (default **off**, the Pika-safe rule). The constructor hands it to the manager, so
  it applies to every blink owner (AntiVoid, NoFall, Hitflick, KillAura's autoblock blink...), not only Blink.
- Blink `release-every` (0-20 ticks, default 0): Raven Alter's trickle. Every N held ticks it sends the oldest
  held packet. If that packet has a position, it becomes the anchor (marker and drift).
- The user's `default.json`: Blink `hold-transactions true`, `release-every 0`. Turn it **off before Pika**.
- Grim's own limit still applies: with a transaction ping (hold + real ping) at or above ~1000 ms, Grim's
  Timer uses an adjusted test. `latency-budget 900` (hold = 900 − ping) stays under it; `timeout 1000` with
  `auto-off` does not, on its own.

Tests: BlinkManagerTest +4 (C0F held in order, from any thread, when asked; keep-alive still never held;
discardHeld sends the replies; discardHeld without held replies = clear). 235 pass.
Backups: `backups/src/src-before-blink-tx-20261002`, `backups/config/default.json.pre-blink-tx-20261002`;
replaced jar `backups/jars/Myau+.jar-2.1+4.jar.pre-blink-tx`. Installed (game not running), md5 44b0c0e6…,
62 mixins. Not measured in game yet.

### `hold-transactions` on by default (2026-10-02, 23:00)

The user tested the 22:50 jar (`hold-transactions` on in their config):
- **Grim** (test.ccbluex.net, 22:51-22:57): 25 Blink holds and **Timer 0**, against 11 in the 22:06 session
  without the option.
- **Pika** (22:57:44-22:59): 5 holds, no disconnect. "Pika doesn't need it off, it was super smooth."

Other Grim flags in that segment are **not** from this change:
- `TransactionOrder` (22:55:45) and `BadPacketsN` (22:55:35, 22:56:13) came 18-28 s after Blink's last release
  (22:55:17). The 22:06 session had the same two, before the change.
- The suspect is an inbound holder (Backtrack). Not investigated.

**Change:** Blink `hold-transactions` default false → true. Comments updated in Blink and BlinkManager.
BlinkManager's own static default stays false, so the unit tests still exercise the old rule as well.

**Side effect, noted and not changed:** `send-threshold` counts every held packet, and now that includes
replies. On Grim (~1 reply per movement) releases come on "packet limit" at 45 (~1 s of hold) instead of the
budget. The user found it smooth, so it was left alone.

Backup: `backups/src/src-before-blink-tx-default-20261002`. 235 tests pass. **Built (23:00), not installed:**
the game was running (pids 16812, 32120). Nothing urgent: only a fresh config sees the new default, and the
user's `default.json` already has it on. Install `build/libs/Myau+.jar-2.1+4.jar` per §6 next time the game is
closed; the jar it replaces is the 22:50 one (md5 44b0c0e6…).

## KNOWN-GOOD SNAPSHOT: Clutch + AutoBlockIn (2026-10-02, ~23:10)

The user tested the 22:50 jar (md5 `44b0c0e6…`) on test.ccbluex.net (Grim) and Pika: **"Clutch and AutoBlockIn
are perfect, keep this version."** It contains:
- AutoBlockIn and Clutch with the Grim timing fixes (§ "Grim flags on AutoBlockIn/Clutch…").
- The Atlas key/colour editors and the AutoTune by-hand fix.
- `move-fix REAL`.
- Blink `hold-transactions`.

Saved as:
- `backups/jars/Myau+.jar-2.1+4.jar.GOOD-clutch-autoblockin-20261002`: the tested jar itself.
- `backups/src/src-GOOD-clutch-autoblockin-20261002`: source at ~23:10. It is the 22:50 source plus only the Blink
  `hold-transactions` default false→true (23:00 build, not installed).
- `backups/config/default.json.GOOD-20261002`.

**Do not rework AutoBlockIn's or Clutch's placement timing without a reason measured against this version.** If a
later change breaks them, diff against this snapshot first.

## TransactionOrder / BadPacketsN: investigation stopped, probably false flags (2026-10-02, ~23:10)

The user asked which module causes them, then stopped the search: the flags are rare, the result was fine, and
they may be false flags. Recorded so the work is not redone.

**What Grim's checks actually test** (GrimAnticheat/Grim, branch 2.0):
- `TransactionOrder` ("Sent transaction or ping responses in an invalid order"): in
  `GrimPlayer.addTransactionResponse`, `skipped` = how many transactions sent *before* the answered one are still
  unanswered. It flags when skipped > 0, after 5 s connected. Either a reply was never sent (S32 cancelled, C0F
  dropped, handler returned early), or replies were reordered.
- `BadPacketsN` ("Ignored or failed to accept a required server teleport"): in
  `SetbackTeleportUtil.checkTeleportQueue`, the client has already acknowledged a transaction sent *after* a
  pending teleport, but the flying packet is not that teleport. Grim then resends the setback.

**Occurrences.** TransactionOrder: 19:14:49 (skipped=1), 21:35:20 (1), 22:10:30 (3), 22:55:45 (4).
BadPacketsN: 22:11:48, 22:55:35, 22:56:13. All on test.ccbluex.net, 1-4 per session. Each BadPacketsN follows
a Grim movement flag (Simulation / AntiKB / Timer), so it follows a setback. Two of the TransactionOrders were
near the ClickGUI being open or just closed. The flags predate `hold-transactions`.

**Ruled out:**
- Blink: off 18-28 s before the 22:55-56 flags.
- FakeLag: no `fakelag-*.txt` at all for 22:06-22:16 or 22:51-22:57, and no fights there.
- Disabler, KnockbackDelay, LagRange, Velocity, NoRotate, TimerRange (`PacketUtil.handlePacket`): all off.
- ResourceSpoofer: cancels S48 only.
- HitSelect: cancels attacks.
- AimBacktrack: reads C0A only.
- ServerFingerprint: reads S32 only.
- The ViaVersion `handleConfirmTransaction` overwrite: `checkThreadAndEnqueue` first, so replies are sent on the
  client thread, in order.

**Open leads, unconfirmed** (the next step would be a packet-order log of S08/S32/C06/C0F with thread names):
1. **Backtrack `releaseIncoming()` on the client thread** (`tickPre`, disable, target change):
   `processPacket` then runs handlers immediately. If older S32/S08 were not held and still sit in the client's
   scheduled-task queue, the released newer ones overtake them. That gives skipped replies, or a
   post-teleport transaction acknowledged before the S08 is applied. Releases on the network thread are safe
   (vanilla reschedules in order).
2. **LagManager holds every outgoing packet except keep-alive and chat**, C0F included, while `tickDelay > 0`
   (set by BlockHit). This contradicts the allow-list rule in §2. It holds in order, so it should not reorder
   by itself, but it is the one holder that ignores the rule.
3. **FakeLag teleport-confirm race:** `flushRequested` is set when the S08 *arrives*, and can be consumed by
   FakeLag's tick before the S08 is *processed*. The confirm C06 sent later is then treated as ordinary
   movement and held behind a stale position, while its C0F goes out at once. That is exactly BadPacketsN's
   condition. It needs FakeLag holding at a setback, which was not the case in these sessions.
4. `handleConfirmTransaction` returns without replying when `thePlayer == null`.

No code changed for this.

## Review of the 22:51-23:08 session; Clutch hand-back and Blink release-on-place (2026-10-03, ~07:15)

The user asked "what else can be done". Read `logs/2026-10-02-7.log.gz`, `places-20261002-225227.txt`,
`clutch-20261002-225405.txt` and `flags-20261002-225242.txt`.

Grim (test.ccbluex.net): Simulation 20, RotationPlace 6, GroundSpoof 6, AirLiquidPlace 4, AimModulo360 2,
BadPacketsN 2, and one each of TransactionOrder, DuplicateRotPlace, BadPacketsV, AntiKB, AimDuplicateLook.
FlagDetector (both servers): REJECT 25, LAGBACK 14, VERTICAL 5, RELOCATE 3, HITS-DROPPED 3. Attribution: Clutch 16
(8 single), FakeLag 6, Blink 5.

**1. Clutch hand-back against the player's own clicks. Fixed.**
- 22:54:29: RotationPlace ×6 + DuplicateRotPlace (x=0.0). The clutch trace shows "not needed now" from
  22:54:29.014, i.e. REAL + reset-angle handing the view back at snapback 14 (~12°/tick). The place log shows the
  player's own FastPlace blocks (`catching false`, gap 1) with `rot` 11.3-12.5 every tick.
- A vanilla click uses the look at the start of the tick (runTick picks after RotationManager has finished the
  turn), and the hand-back then sends its next step in the same tick's C03.
- **Fix:** `Clutch.onRightClick` while not `active` and `hasSent` → `resetting = false; hasSent = false`. The
  player has taken the view, as when the mouse moves. No rotation is sent that tick, so the C03 carries the
  camera, which is the look the click used. Clicks during an active catch are still cancelled, and placement
  timing is untouched (known-good snapshot).

**2. GroundSpoof + Simulation while towering by hand: cause NOT found; nothing changed.**
- Three bursts (22:54:45, 22:55:35-36, 22:56:13-14), each starting `GroundSpoof claimed true` + `Simulation
  .078400` (one tick of gravity), followed by setbacks.
- I first said "the block is placed on the landing tick". **Wrong:** the place log shows those placements at
  `feet +0.00` with `v +0.16`. That is rising, 3 ticks into the jump (0.42+0.33+0.25 = 1.00), the earliest legal
  moment, with gaps of 4-7 ticks, not FastPlace spam.
- Places were `result OK` (no S23 air back).
- Open: the next step is a per-tick trace of onGround/motionY around a tower jump next to the Grim flag.

**3. FakeLag during block placement on Pika: config advice, no change.** 5 REJECTs `SINGLE FakeLag` (23:00:20,
23:00:45, 23:01:13). FakeLag is the abandoned module (§5) and was on. The advice is to switch it off for
building.

**4. Blink release-on-place. Added.**
- 22:52:42: REJECT ×2 while "holding Blink/Blink out 14 for 21016ms" (PULSE re-anchors forever).
- New `release-on-place` (default on): `Blink.onRightClick`, when the hold is BLINK's, a block is in hand and the
  crosshair is on a block → `setEnabled(false)`. That releases the queue in order, on the client thread, before
  vanilla sends the C08, so the placement itself is not held.
- This is AntiVoid's existing rule (09-25 place log) applied to Blink.

Also noticed, left alone: HITS-DROPPED against `MapMarker` (an NPC/marker, not a player).

Backup `backups/src/src-before-handback-blinkplace-20261003`. 235 tests pass. Built 07:14 (md5 4faaa528…), 62
mixins. **Not installed:** the game was running. The GOOD snapshot remains the 22:50 jar. If this build is ever
suspected for Clutch, the only Clutch change since the snapshot is `onRightClick`.
**Installed 2026-10-03 ~07:20** at the user's request (game closed): md5 4faaa528…, 62 mixins by
ZipInputStream. The replaced jar (= the GOOD 22:50 jar) is `backups/jars/Myau+.jar-2.1+4.jar.pre-handback-blinkplace`.
This install also brings in the 23:00 change (Blink `hold-transactions` default on), which was built but never
installed.

## KillAura: attacks only along a look that reaches the real hitbox (2026-10-03, ~07:25)

**User report:** KillAura "doesn't hit / low hit rate", "gets flagged", and "autoblock has problems". They asked
for a fix with the other clients as references.

**Evidence.** KillAura was on for under a minute in total in the recent sessions:
- 23:02:17-27: vs Opponent6, hits 0-1.
- 21:55:06-36: 1-0 and 2-3.
- HitCheck around 23:02: `25/31 (81%) | dropped 6` (mixed with manual play).

That is too little for flag attribution, so the fix below comes from reading the code against the rule the
servers apply, not from a measured flag.

**Defect (KillAura.performAttack).**
- For Rotations 1/2/3/5 (Legit, **Silent** = the user's, LockView, Hypixel/RavenBS), the refusal was
  `!isBoxInAttackRange(box) && rayTrace(...) == null`. With the box within 3.0 the attack went out wherever the
  look was.
- Silent's capped turn (SMOOTHSTEP, 14-70°/tick) is often still behind a strafing target.
- Grim judges an attack by the look in the movement packet after it (as for placements), and drops it. That is
  HitCheck's `dropped`.
- Rotations NONE was not checked at all.
- Modes 4/6 did require the ray, but against `getBox()`.

**Reference.** Raven Alter (= daikirai-0) `KillAura.modifyMouseOverFromGetMouseOver`:
- It points `objectMouseOver` at the target only if the look ray hits `getEntityBoundingBox().expand(
  getCollisionBorderSize())` within attack range, or the eyes are inside it, and not through blocks/entities.
- The vanilla click then attacks; otherwise it swings at the air.

**Fix.**
- New `aimedAt(yaw, pitch)`: the eyes are inside, or `RotationUtil.rayTrace(real box + 0.1 border, yaw, pitch,
  attackRange)` hits.
- Used for every Rotations mode. Modes 4/6 check it before the swing (as before); the others after the swing,
  so a miss is a miss-swing.
- Advanced's deliberate KBDisplace exception is kept.
- **The real box, not `AttackData.getBox()`**, which AimLead moves ahead. Leading is for aiming. A hit is judged
  against where the target was on this client's screen: Grim compensates for latency. The user's AimLead is
  0.2; on Grim 0 is the better setting.

**Not changed: autoblock.** LEGIT (mode 7) alternates [attack + INTERACT_AT, INTERACT, C08] / [C07 release, no
attack] every tick. That order is vanilla's right click on an entity, and no defect could be shown from the code.
Waiting for the user to describe the symptom.

Expected effect: fewer attacks while the aim is catching up (miss-swings instead), and the hits that go out
count. Backup `backups/src/src-before-killaura-aim-20261003`. 235 tests pass. Built 07:24 (md5 075f5f12…),
62 mixins. **Not installed:** the game was running.
**Installed 2026-10-03 ~07:30** (game closed): md5 075f5f12…, 62 mixins. The replaced jar is
`backups/jars/Myau+.jar-2.1+4.jar.pre-killaura-aim`.

## Clutch: one block a tick, `keep-y`, `extra-block` (2026-10-03, ~07:40)

**User asked:** "place a bit faster", a keep-Y option, and "one more block under the feet at the end", with Vape
as the reference.

**Faster: the click moved into this tick's PRE, after the turn.**
- The 10-02 Grim fix clicked at the start of the next PRE and held that tick's turn, so a sequence of blocks went
  one per two ticks.
- Now plans are made from the eyes **before** this tick's move (`eyesAt(pathStart)`; pre-aim lead L uses
  `path[L-1]`), and `post()` runs at the end of the plan branch. It casts the ray from where the player is now
  along `sentYaw` (this tick's rotation) and clicks before this tick's C03.
- That is vanilla's order and Vape's (Vape presses use through vanilla, with the rotation in the packet after the
  click). Grim RotationPlace post-flying and a vanilla server both judge exactly that.
- The 09-25 failure ("aimed from pre-move, clicked next tick") was the *mismatch*: here aim and click share one
  moment.
- Consequences:
  - `catchCells` starts from `pathStart` (levels and the body exclusion).
  - `planCatch` accepts crossing == lead (`<` instead of `<=`): the block goes down before the move that crosses.
  - `planChain`'s deadline is `d*interval + lead` (was `(d+1)*interval + lead`), with the body box `pathStart`.
  - Ladder plans are unchanged: a ladder planned from `path[last]` is clicked in PRE of tick last+1, the same
    moment and the same box as POST of `last` was.
  - `holdThisTick` stays as a reset-to-false field, now inert.

**`keep-y` (default off).** `catchCells` starts at `min(feet-1, floor(lastGround.y))`, so no catch layer is above
the one last stood on. A step-up when knocked upward is prevented; lower layers are still allowed.

**`extra-block` (default on).** `extraCell()` returns the cell under the middle of the predicted landing
(`path[landingTick]`), on its layer, when all of these hold:
- this catch has placed a block within one of it on that layer;
- that cell is air;
- the feet cross it (`catchTick`).

When it is set:
- It keeps `want` true; the levels are just that cell, with no ladder and no steering early-out.
- It is placed once (`extraDone` is set on placement, or when it cannot be planned).
- It is Vape's landing extension (`extensionTarget` in `simulateClutchPath`).

Backups: `backups/src/src-before-clutch-fast-keepy-20261003`, `backups/config/default.json.pre-clutch-fast-keepy-20261003`.
235 tests pass. **Installed 07:4x** (game closed), md5 9ae58f92…, 62 mixins. The replaced jar (KillAura build) is
`backups/jars/Myau+.jar-2.1+4.jar.pre-clutch-fast-keepy`. The GOOD snapshot (10-02 22:50) is still the fallback
for Clutch. **Not measured in game yet.**

## Diagnosis: "Clutch places nothing" was a hand-patched `Myau+.jar-2.1+5.jar` (2026-10-03, ~23:30)

Report: "放了新版的，方塊都沒有放出來". `mods/` held `Myau+.jar-2.1+5.jar` (md5 6906b90d…, 5939 entries).
**This is not a Gradle build.** It is our 07:35 jar with entries rewritten at 13:04 by someone else:
`Clutch.class`, `myau/util/RotationProfile.class`, `myau/util/RotationStepper.class`, and a new `ClutchPatch.class`.
None of these files exist in `src/`, and `src/` has not changed since 07:35. `mcmod.info` still reads `2.1+4`.

The log (latest.log, 23:25) shows every attempt as `catching a N block drop` followed by
`no cell under the fall within 16 ticks`. There is no `saved`.

Root cause: the stub-compile signature from the original 10-02 breakage. CFR shows `ClutchPatch.catchCells`
computing the feet level as `(int)Math.floor(0.0 + 0.001)`. The `pathStart.minY` read was compiled against a stub
`AxisAlignedBB`: javap gives 0 `minY`/`field_72338_b` reads, plus `requireNonNull` and `dconst_0`. The same goes
for `lastGround.yCoord`. So `feet = 0` and the scan covers y = -1..-5, which never holds a reachable cell.

The Gradle build `build/libs/Myau+.jar-2.1+4.jar` (md5 9ae58f92…) has 11 `minY` reads in `Clutch`. It was
installed at 07:4x but **never launched**: the 07:17 session ended at 07:28, and the next launch was already the 2.1+5 jar.

Action: copied the broken jar to `backups/jars/Myau+.jar-2.1+5.jar.handpatched-broken-20261003`. Reinstall
9ae58f92… once the game is closed. Rule reminder: the jar in `mods/` must come from `./gradlew build`. To check a
jar of unknown origin, list the entries whose timestamp differs from the build time, then javap them for
`requireNonNull`/`dconst_0` in place of field reads.

Follow-up (~23:40): the user confirmed another session had patched the jar. With the game closed, removed
`mods/Myau+.jar-2.1+5.jar` (backup above) and reinstalled the Gradle build `Myau+.jar-2.1+4.jar`:
md5 9ae58f92… matches build/libs, 5936 entries, 62 mixins, no `ClutchPatch`/`RotationStepper`, and it is the only Myau jar.
The Clutch fast/keep-y/extra-block build is now actually live, but **still not measured in game**.

## Clutch: keep-y leaked through the "partly turned look" path (2026-10-03, ~23:45)

Report: "keep y在跳的時候還是可能會往上放一格". Trace `config/Myau/clutch-20261003-233317.txt`, ATTEMPT 36
(stood 70.00, so the keep-y layer is 69). From t2 the levels are capped at `L69`, so keep-y did apply to the plan.
At t5 the plan was `catch 254,69,191 on 255,69,191 west`. But the look at that moment (pitch 87, pre-aimed)
hit the **top** of 255,69,191, and `post()` placed `255,70,191 ... up`. post()'s fallback ("a partly turned look
can still land somewhere useful") accepted any cell with `catchTick >= 0` and never checked keep-y. The result
was one block up per jump.

Fix (Clutch only): a new `keepYTop()` (the stood layer, or MAX_VALUE when keep-y is off) is used by `catchCells`
(same behaviour as before) and by the post() fallback (`cell.getY() <= keepYTop()`). planChain was already fine:
it only spreads horizontally on the level it got from catchCells. extra-block cells sit on the landing layer,
which is at or below the cap.
With keep-y off, nothing changes.

Backups: `backups/src/src-before-keepy-fallback-20261003`, `backups/config/default.json.pre-keepy-fallback-20261003`.
Gradle build md5 eff80889…. **Installed** after the game was closed: md5 matches, 5936 entries, 62 mixins, one Myau jar.
The replaced jar (9ae58f92…) is `backups/jars/Myau+.jar-2.1+4.jar.pre-keepy-fallback`. Not measured in game yet.

## Clutch measurement after the keep-y fix (2026-10-04, ~06:50, diagnosis only)

User: "可以使用". Trace `config/Myau/clutch-20261004-063557.txt` (Pika plus singleplayer, jumping off bridge edges).
- 106 attempts: 93 saved, 12 landed on existing ground, 1 "ended unresolved: world changed" (a 31-block drop; it
  waited "not needed now" because the landing was beyond PLAN_TICKS).
- 387 blocks placed, **about 4.2 per save** (1 block 13x, 4 blocks 24x, 6-9 blocks 21x). By kind: chain 198,
  catch 163, extra 26. **209 of 387 went down while still rising** (vy > 0), i.e. before the fall had begun.
- Turn per placed block: median 18°, p90 135°, max 174°. 67 placements needed more than 90° in one tick.
  The config has speed/max-speed 180.
- 164 "post: turning" ticks (the look was not on a useful face yet).
- flags-20261004-063610: 7 REJECT (5 attributed to Clutch, some at body 18 blocks, i.e. resolved late) and
  1 VERTICAL. No Grim checks were involved (Pika).
Candidate improvements (not done; waiting for the user's choice): fewer blocks (do not chain while rising when a
direct catch is reachable later), smaller snaps (weight turn cost more, or cap the per-tick turn).

## Why FlagDetector blame shows "unknown ~48%" (2026-10-04, diagnosis only)

User: "為什麼每次flag，unknown比率那麼高". This is by construction, not a measurement (`util/Attribution.java`).
- Every flag reserves a fixed `UNKNOWN_PRIOR = 0.5`, standing for the server, the route, or vanilla movement.
- A module's score saturates per kind at that kind's weight. A placement is 0.5 and a swing 0.05.
- So a module that only places and swings can never exceed (0.5 + 0.05) / (0.55 + 0.5) ≈ 52%, which leaves unknown
  at 48%. That is exactly "SINGLE Clutch 52% (place x12 swing x12) | unknown 48%".
- Only held/released own movement (weight 1.0) can push unknown below a third.
Possible improvement (not done): a REJECT knows the exact cell, and FlagDetector's `pendingPlacements` knows when
it was placed. If the placing module were recorded per cell, a REJECT could be attributed to that module at ~100%
and not through the generic time-window weighting.

## Clutch: `humanize` (2026-10-04, ~06:50)

User asked for "數學randomize計算". Clutch only; RotationEngine (shared with KillAura/Scaffold/...) is untouched.
New `humanize` (default true). Both effects scale with the existing `rotation-random`, and both are off with
humanize off or rotation-random 0.
1. **Aim spot**: each block gets a spot on the face drawn from N(0.5, 0.18) per axis, clamped to [0.1, 0.9]. It is
   redrawn at attempt start and after every successful click. aimFace now selects by
   `turn + spreadWeight * distance(sample, spot)`, with spreadWeight = rotation-random × 0.24 (6°/unit at 25).
   `Plan.cost` stays the bare turn, so the timing/feasibility checks (`cost > (last+1)*turn`, the step-need at
   `needed = cost / catchTick`) are unchanged. The cross-face bound still compares bare cost.
   Before this, Clutch always chose the sample nearest the crosshair (a face edge), every time.
2. **Bow**: in stepRotation, after engine.step and before the DuplicateRotPlace nudge. A step that does not reach the
   target is pushed perpendicular to its direction by `bow × rotation-random/100 × 0.3 × stepLength`, where bow is an
   OU walk (`bow = 0.7·bow + 0.714·N(0,1)`, stationary sd 1), then re-quantized to the mouse grid. A step that
   reaches the target (`left < 0.5°`) is not bowed, so the click tick's ray is exact. With speed/max-speed 180
   most turns land in one tick, so the bow mostly shows on long or slow turns.
Backups: `backups/src/src-before-humanize-20261004`, `backups/config/default.json.pre-humanize-20261004`.
Gradle build md5 19b75731…; tests pass. **Installed** (game closed): md5 matches, 5936 entries, 62 mixins, one Myau jar.
The replaced jar (eff80889…) is `backups/jars/Myau+.jar-2.1+4.jar.pre-humanize`. Not measured in game yet.

## RotationEngine review (2026-10-04, diagnosis only, nothing changed)

User asked where `util/RotationEngine` can improve. Callers of `step()`: Clutch, and KillAura's simple aim
(`stepTowards`, which adds its own smoothstep easing and ±8% speed noise outside the engine). KillAura's advanced
mode uses `util/AdvancedAim` (531 lines, its own model). AutoBlockIn uses **`PlaceUtil.step`**: no acceleration
limit, no randomness, a straight line. Scaffold uses `quantize` only.
Gaps in `step()` itself:
1. Straight line: yaw and pitch move in a fixed ratio every tick. Clutch now bows this locally (humanize);
   nothing else does.
2. No deceleration: `accel` only bounds speed-up. A turn runs at full step and stops dead on the target. Humans
   decelerate on approach (Fitts / minimum-jerk). KillAura eases outside the engine; Clutch does not.
3. Speed noise is uniform and one-sided (`1 - U(0, r)`, EMA 0.35), so the speed is only ever reduced.
4. Duplication: easing (KillAura), bow and DuplicateRotPlace nudge (Clutch), PlaceUtil.step (AutoBlockIn), and
   AdvancedAim each live in their own module. A server sees the weakest.
5. With Clutch speed/max-speed 180 the accel cap is 180 too, so 0→174° can happen in one tick from rest
   (measured 2026-10-04: 67 placements needed more than 90° in one tick).
Proposed direction (pending the user's choice): opt-in features in the engine (bow, symmetric/Gaussian speed noise,
ease-out with a floor so arrival is guaranteed), then AutoBlockIn moves from PlaceUtil.step to the engine.
Caveat: Clutch/AutoBlockIn timing is user-approved (KNOWN-GOOD), so anything that slows arrival must be opt-in
or limited to non-final ticks.

## RotationEngine features + `Rotations` settings module; FlagDetector direct REJECT blame (2026-10-04, ~07:05)

User: "改準一點(flag detector)。先做1,2,3 ... 最後有一個可用的rotation engine，這些參數也能調整".

**FlagDetector / Attribution.**
- `Placement.module` is now taken when the C08 is seen, via the new `ActionLedger.callerModuleExcept("FlagDetector")`.
  The plain `callerModule()` would have returned FlagDetector itself, since its listener is on the stack.
- On a REJECT / BLOCKED-BY-BODY with a known module, the blame is the new `Attribution.direct(module, "placed-this-block")`:
  SINGLE, 100%, unknown 0%. A click by hand (module null) still goes through the time window.
- FlagResponder implication is unchanged in practice: a placing module was already SINGLE at 52%.
- Test `AttributionTest.aDirectCauseIsSingleWithNothingUnknown`.

**Engine.** `RotationEngine.step(..., Set<Feature>)` with `Feature {NOISE, CURVE, EASE}`. The old 8-arg `step()` delegates
with an empty set and is byte-for-byte the old behaviour, so KillAura is unchanged. The amounts live in the new module
**`Rotations`** (CLIENT category, enabled by default, registered in Myau.java). Off means no feature anywhere.
- `speed-noise` (12%): speed factor ~ N(1-sd, sd), clamped to [max(0.2, 1-3sd), 1], EMA 0.35. This replaces
  randomPercent's uniform, only-slower drift for callers asking NOISE. It never exceeds the module's cap.
- `curve` (8%), `curve-smooth` (70%): OU walk `bow = k·bow + sqrt(1-k²)·N(0,1)`. A step that does not land is offset
  perpendicular by `bow·curve·stepLength`. The landing step is never bowed.
- `ease` (on), `ease-zone` (25°), `ease-floor` (5°/t): when less than zone remains,
  `step = min(step, max(floor, step·left/zone))`. The floor guarantees arrival.
Clutch: `humanize` asks NOISE+CURVE. A new Clutch `ease` (default **off**, visible with humanize on) adds EASE,
off because it costs ticks the catch planner does not budget for. The local `bowed()` from this morning was
removed (moved into the engine). The aim-spot spread stays in Clutch, scaled by rotation-random. With humanize on,
rotation-random no longer drives Clutch's speed drift (speed-noise does).
Not yet on the engine: KillAura (own easing/tremor/AdvancedAim), AutoBlockIn (PlaceUtil.step), Scaffold.
Backups: `backups/src/src-before-rotation-engine-20261004`, `backups/config/default.json.pre-rotation-engine-20261004`.
236 tests pass. **Installed** (game closed): md5 d04ab636…, 5938 entries, 62 mixins, one Myau jar. The replaced jar
(19b75731…) is `backups/jars/Myau+.jar-2.1+4.jar.pre-rotation-engine`. Not measured in game.

## Log review after the engine change, and PlayTracker HUD (2026-10-04, ~07:25)

**Log (07:01-07:20, Pika practice BedFight, jar d04ab636…).**
- Clutch: 124 attempts, 105 saved, 16 on existing ground, 3 "corrected by the server"; 3.8 blocks per save.
- Direct blame works: `BLOCKED-BY-BODY ... SINGLE Clutch 100% (placed-this-block x1) | unknown 0%`.
- One REJECT fell back to the window (FakeLag 76%), so that click had no module on the stack (by hand).
- Most flags this session: LOW-DAMAGE x4, HITS-DROPPED x2 and LAGBACK x5, nearly all SINGLE FakeLag 72-78% (held-send).
  FakeLag is the leading cause on Pika, as before. No rotation-type flags.

**PlayTracker** (new, LEGIT category, enabled by default): a two-line HUD showing
"遊玩 h:mm:ss (今天 …)" and "場數 N 勝W 敗L (今天 N)".
- Time counts every tick while in a world. A gap over 1 s, such as a freeze, is not counted.
- Games are read by the new pure `util/MatchChat`, with patterns taken from the logs (39k chat lines):
  - starts: "MATCH START!", "Game starts in 1...", "The game starts in 1 second";
  - results: Pika's names row + "LOSER! WINNER!" row (side by position), duels' "name WINNER! other", Hypixel's
    "YOU WON!", and titles VICTORY!/GAME OVER!.
  - Lines containing ": " (players talking) are ignored.
  - Dedupe: starts within 15 s (Pika sends both MATCH START and the countdown), results within 5 s.
    A result with no start seen still counts as a game.
- Persisted in `config/Myau/playtracker.txt`: today and all-time. It is saved every 60 s, after each result, and
  at Shutdown SAVE_STATE. The day rolls over at local midnight.
- Records while the module is off. Off only hides the HUD.
- Position: `x`/`y` properties (there is no HUD drag editor in this client; DragProperty is config-only).
Test `MatchChatTest` uses real lines. 241 tests pass. Gradle md5 0813ddec….
Backups: `backups/src/src-before-playtracker-20261004`, `backups/config/default.json.pre-playtracker-20261004`.
Installed together with the mitigation change below (one jar).

## Mitigation check (damage cut) on the PlayTracker HUD (2026-10-04, ~07:45)

User: show when the server is mitigating, i.e. hits do 1-3 hp. The old LOW-DAMAGE check compares against the
*usual* damage (median of the last hits), so a mitigation present from the first hit becomes the usual and is never
caught. Pika logs show "usual 1.50/2.00" with swords, which looks like exactly that.
- New pure `util/DamageModel`: the vanilla 1.8 minimum for a melee hit. It covers the weapon's attackDamage modifier,
  1.25 × sharpness, ×(1+1.3·strength), −0.5 × weakness, ×(25−armour)/25, and protection EPF (per piece
  floor((6+L²)/3·0.75), capped at 25, randomised (raw+1)/2 .. +raw/2, max 20, 4% each) as a range.
  The **minimum never assumes a crit** (Criticals/fall state may disagree with the server). Not modelled:
  resistance, sword blocking, modded damage. Those only lower real damage, so the threshold is generous.
- `DamageWatch.attack(id, now, expected)` stores that minimum per attack and the resolved `Hit` carries it.
  `mitigation(now, maxAge)` is active when ≥3 of the last 4 hits with an expectation ≥ 2 took
  `dealt + precision <= 0.5 × expected`. Precision is 0 for entity health and 1 for tab/below-name scores (whole numbers).
- FlagDetector: `leastDamage()` at C02 (players only). The per-hit damage chat now adds "vanilla at least X".
  One chat line goes out when the check turns active. `mitigation()` / `droppedRun()` serve the HUD.
  It is not a new flag label, so flag counts and FlagResponder are unchanged.
- PlayTracker: third line "! 減傷 dealt/expected hp (n/m 下)" while active (20 s after the last hit), or "! 打不到 N 下沒反應"
  for a run of ≥2 ignored hits. `show-mitigation` (on); `mitigation-always` (off) also shows "減傷 無" otherwise.
  It needs FlagDetector on with detect-low-damage.
Tests: `MitigationTest` (model vs hand-computed 1.8 numbers, the 3-of-4 rule, the integer-health margin, unknown
expectations ignored). 246 pass.
Backups: `backups/src/src-before-mitigation-20261004`, `backups/config/default.json.pre-mitigation-20261004`.
**Installed** (game closed): md5 1b3f249d…, 5944 entries, 62 mixins, one Myau jar. The replaced jar (d04ab636…) is
`backups/jars/Myau+.jar-2.1+4.jar.pre-playtracker`. Not measured in game.

## KillAura audit (2026-10-04, ~20:10)

Scope: the user's review table, item 1. Field data is thin: chat shows KillAura toggled ON for seconds at a time,
and hits-*.txt (HitCheck) for Pika shows 64-94% hits with mostly "dropped"/"invuln" misses.
The user's config: Switch, Silent + SMOOTHSTEP, auto-block LEGIT, CPS 10-16, AimLead 0.2, MoveFix Silent.
The audit is mainly by reading the whole file. **Fixed:**
1. **CPS was a flat 10.** `performAttack` *set* `attackDelayMS = delay` after the countdown had reached (-50, 0],
   which threw away the remainder. Any delay of 51-100 ms (10-19 CPS) therefore became exactly one hit per 2 ticks.
   Simulated: 10-16 CPS gave 10.0 CPS with every gap 2 ticks, and AutoBlockCPS 8 gave 6.7.
   Now `+=` (also in Advanced's miss-swing): 12.8 CPS with gaps of 1/2. In LEGIT autoblock the
   attack/release cycle still caps it near 10.
2. **interactAttack** traced the AimLead-shifted box out to **8 blocks**, and sent INTERACT_AT with the hit point
   relative to the real position, so with AimLead on the point could fall outside the hitbox. It could also name
   an entity no vanilla crosshair would be on. On a trace miss it sent nothing, so no block started.
   Now: the real box within attackRange (as `aimedAt`), and on a miss `sendUseItem()` (C08 only, as vanilla does
   with the crosshair off the entity). The order INTERACT_AT, INTERACT, C08 was checked against vanilla rightClickMouse
   on a player.
3. `canAttack` dereferenced `mc.objectMouseOver` without a null check. It is now guarded.
**Found, not changed (waiting for the user):**
- Silent/Legit/Hypixel have no smooth-back: when the target is lost, the next C03 jumps straight to the camera yaw
  (up to 180° in a tick). Grim does not check this; heuristic ACs do. Modes 4/6's smooth-back sits inside
  `if (attack)`, so on a lost target (target == null) it is skipped too, and onTick resets serverRotation.
- Target re-pick (onTick) treats a target outside attackRange but inside swingRange as needing a new target
  every tick. In Switch mode with hitRegistered, that advances switchTick every tick, i.e. rapid switching.
- CPS Mode "Record": clickPattern values are 0-81 ms per entry (they look like press/release sub-intervals), used
  as whole click delays, so up to ~20 CPS. The user uses Normal.
- `AttackTick` (default 0 < min 1) is read nowhere. It is a dead setting.
- `isValidTarget` does `loadedEntityList.contains` (O(n)) per call, so O(n²) a tick. Performance only.
Backups: `backups/src/src-before-killaura-audit-20261004`, `backups/config/default.json.pre-killaura-audit-20261004`.
246 tests pass. Gradle md5 e92e8fad…. Not installed then (game running).

### KillAura audit, part 2: the four open items fixed (2026-10-04, ~20:30, user: "都修")
- **SmoothBack for every rotating mode.** New `returning` state. A tick where the aura set the rotation itself
  (`!rotatedBefore && event.isRotated()`) arms it, except LockView. On later ticks where nothing rotates, `stepBack()`
  turns the sent look (event.getYaw = lastReported) toward the camera through `RotationEngine` with all features, at
  max(20, MaxTurnSpeed) and TurnAccel, until it is within 1°. If another module rotates first, returning is dropped.
  Disabling: `onDisabled` re-enables into the existing `wantsToDisable` path whenever the last *sent* look
  (recorded at POST) is more than 1° off the camera, for all modes except NONE/LockView, not only 4/6. The
  wantsToDisable branch now uses stepBack too. A `closing` flag stops the setEnabled(false) at the end of that path
  from being re-armed by onDisabled. Without it, another module rotating meant the aura never switched off.
  The `SmoothBack` setting is now visible for every mode except NONE/LockView.
- **Switch re-pick.** A target in swing range but outside attack range is replaced only when some candidate *is* in
  attack range. Switch's `switchTick++` now needs the switch delay to have run out (`switchDue`), not just any re-pick.
- **Record CPS.** Each group of four recorded intervals is one click (groups sum to 78-131 ms, 102 on average,
  9.8 CPS); patternIndex advances by 4. 1263 entries, so the index wraps with a modulo.
- **isValidTarget**: `isDead || worldObj != mc.theWorld` instead of `loadedEntityList.contains`.
- Correction to part 1: **`AttackTick` is not dead.** NoSlow reads it ("No Attack" with SWAP autoblock). Removing it
  broke the build, so it was restored unchanged, with a comment saying who reads it.
246 tests pass. **Installed** (game closed): md5 b2124084…, 5944 entries, 62 mixins, one Myau jar. The replaced jar
(1b3f249d…) is `backups/jars/Myau+.jar-2.1+4.jar.pre-killaura-audit`. Not measured in game.

## ClickGUI tidy: descriptions for every module, headings, labels, hints (2026-10-04, ~21:00)

User: "click gui 每個功能的 customization 開關都有點亂，把它整理，然後每個功能都要做說明". 110 of the 161 registered
modules had no description, and setting keys come in four styles (`release-every`, `AutoBlockCPS`, `LB-HSpeed`,
`Color Mode`). KillAura has 94 settings in one flat list. **Presentation only. No config key changed.**
- `Property`: added `label` (default `prettify(key)`, e.g. "AutoBlockCPS" → "Auto block CPS", "LB-HSpeed" → "LB H speed"),
  `group`, `help`, and `when(BooleanSupplier)`, an extra visibility condition ANDed with the constructor's.
  None of these is saved.
- `Module.setShownDescription`: `getDescription()` prefers it.
- New `module/ModuleDocs.java`, applied in Myau.java right after a module's settings are collected; its returned list
  is the menu order.
  - A Chinese description for **all 161** modules.
  - Headings and order for the long lists: KillAura, Clutch, Scaffold, Velocity, HUD, NameTags, AntiBot, Disabler,
    TimerRange, BlockHit, TargetHUD, ESP2D, Blink, FlagDetector, BedwarUtils, Xray, InvManager.
    Patterns are `x*` (prefix), `*x` (suffix) or exact, case ignored; unmatched settings go first under "一般".
    Other modules keep their order, with ≥2-member runs of hud*/alerts*/macro*/raven*/bg-* gathered under a heading.
  - Hints: a shared meaning per key (range, fov, move-fix, swing, teams, …), plus per-module ones for KillAura,
    Clutch, Rotations, PlayTracker and FlagDetector.
- Atlas: a heading row (16 px, accent-tinted, small caps) wherever the group changes. A list that has one group
  only shows no heading. Rows show `getLabel()`, and the hint line is "label · help · value prompt".
- `LiquidFont` (Latin-1 sheets) now draws any string containing characters beyond Latin-1 with the game font,
  which has the unicode pages, as it already did when the typeface failed. `wrap()` breaks Chinese between
  characters. Without this the Chinese text would have shown as "?".
- KillAura visibility: AimMode/MinTurnSpeed/Multipoint only for Legit/Silent/LockView. Smoothing/AngleStep only
  for LEGACY there. MaxTurnSpeed/TurnAccel also whenever SmoothBack is on (stepBack uses them); their
  constructor condition `aimMode == 1` was removed for that reason. Min/MaxCPS only for CPS Mode Normal.
  MoveFix hidden for NONE.
Tests: `ModuleDocsTest`. Every class registered in Myau.java must have `d("Class",` in ModuleDocs, and prettify
cases are checked. 248 pass.
Backups: `backups/src/src-before-clickgui-docs-20261004`, `backups/config/default.json.pre-clickgui-docs-20261004`.
**Installed** (game closed): md5 d7d3668a…, 5945 entries, 62 mixins, one Myau jar. The replaced jar (b2124084…) is
`backups/jars/Myau+.jar-2.1+4.jar.pre-clickgui-docs`. Not seen in game yet (CJK fallback rendering untested on screen).

## NoSlow audit (2026-10-04, ~21:15)

Review-table item 2. The user's config has NoSlow off with Sword Vanilla, so none of this touched their play. Checked
the hook order in MixinEntityPlayerSP: `LivingUpdateEvent` fires at the `super.onLivingUpdate()` call, i.e.
*after* `updatePlayerMoveState()` and vanilla's use-item ×0.2. The `isUsingItem()` redirect covers **every**
isUsingItem call in EntityPlayerSP.onLivingUpdate (slowdown and sprint checks). **Fixed:**
1. Hypixel "No Attack": the inline condition `A||B||C||D && enabled && blocking` let `&&` bind to D only. With
   KillAura off, C (autoblock not SPOOF/SWAP) held, so No Attack disabled the whole mode. It is now
   `auraAttacksThisTick()` = `(A||B||C||D)` only when KillAura is on and blocking.
2. NewGrim: `shouldCancelMiauSlowdown()` counted every redirect call (2-3 a tick). The slowdown call always saw the
   "keep" half, so NewGrim never cancelled the slowdown. It now decides once per `ticksExisted`.
3. Miau multiplier (Grim 0.35): applied from UpdateEvent, then overwritten by the next tick's
   updatePlayerMoveState, so it **never held** and Grim mode ran at full speed. It is now applied in
   `onLivingUpdate`, before the existing per-item Motion%. Watchdog/OpalWatchdog's `×5` in UpdateEvent is
   dead for the same reason but harmless: the redirect already cancels the slowdown. Left as is.
4. OpalWatchdog `opalRelease()` sent C07 RELEASE_USE_ITEM every tick with a screen open or without a sword in hand,
   even when nothing was in use. It now only releases when `opalBlocking`.
Backups: `backups/src/src-before-noslow-audit-20261004`, `backups/config/default.json.pre-noslow-audit-20261004`.
248 tests pass. Gradle md5 32297eb2….
**Installed** (game closed): md5 32297eb2…, 5945 entries, 62 mixins. The replaced jar (d7d3668a…) is `backups/jars/Myau+.jar-2.1+4.jar.pre-noslow-audit`.

## Scaffold audit (2026-10-04, ~21:30)

Review-table item 3. The user's config: GODBIRGDE, move-fix REAL, multi-place on, eagle on. The flag logs hold 49 REJECTs
blamed on Scaffold (2026-09-24 … 10-02). The cleanup really is good: blink, motion and slot restore are all in
onDisabled. **Fixed: clicks that the sent look does not make.**
- Main place: the hitVec was re-aimed along the quantised sent rotation only when that hit (`if sentMop != null`),
  never while towering. Otherwise it **kept the planned hitVec and clicked anyway**. Snap modes that did not
  rotate this tick sent the camera's look and still clicked the planned face.
  Now, for every mode except NONE: verify with `getPlacementMop(blockData, event.getNewYaw(), event.getNewPitch())`,
  i.e. the look the C03 will carry, whoever set it. On a miss, no click this tick. This generalises the
  3FMC-only check.
- Multi-place and the keep-y EXTRA second place traced `this.yaw/this.pitch` (pre-clamp/pre-quantise). They now
  use the sent look (NONE keeps the old trace).
Not changed (noted for the user): tower EXTRA's `targetFacing` place uses `BlockUtil.getHitVec` without checking the
sent look. Multi-place (on by default) places up to 4 blocks in one flying window, which is what Grim's MultiPlace
flags (see the Clutch notes, 10-02 22:08).
Backups: `backups/src/src-before-scaffold-audit-20261004`, `backups/config/default.json.pre-scaffold-audit-20261004`.
248 tests pass. Gradle md5 0c656509…. Installed if the game was closed (see the next line).
**Installed** (game closed): md5 0c656509…, 62 mixins, one Myau jar. The replaced jar (32297eb2…) is `backups/jars/Myau+.jar-2.1+4.jar.pre-scaffold-audit`.

## Velocity audit (2026-10-04, ~21:40)

Review-table item 4 ("global state"). The user's config has Velocity off, mode Vanilla. Checked: `KnockbackEvent.setX/Y/Z`
cancels the event, which is what makes MixinEntity apply the new motion. So Vanilla/Jump do take effect. The
fake-check (only reduce knockback that followed an S19 hurt status) is intended. **Fixed:**
1. **Reduce hits (Hypixel `reduce`, Slap) went out at any distance and any angle.** KillAura keeps a target out to
   its AutoBlockRange (6 blocks by default), and Velocity sent C0A+C02 ATTACK on it from PRE with no range or aim
   check. Velocity also runs before KillAura (LOW), so the same tick could carry two attacks. Now
   `canReduceHit()` requires the real hitbox to be within KillAura's AttackRange, and the *last sent* look
   (event.getYaw/getPitch) to ray into it, or the eyes to be inside it. After a reduce hit,
   `KillAura.noteExternalAttack()` sets its delay to ≥51 ms, so it skips this tick's attack.
2. **Dead global state removed:** `public static boolean velocityAttacked/extraAttacked` were never set true
   anywhere in the codebase, so the `if (velocityAttacked)` branch could never run. Both were only read and
   reset inside Velocity. Also dead and left in place: `handleJumpReset()` (never called; `ShouldJump` never true).
Backups: `backups/src/src-before-velocity-audit-20261004`, `backups/config/default.json.pre-velocity-audit-20261004`.
248 tests pass. Gradle md5 8b670b2e…. Installed if the game was closed; the replaced jar is `…pre-velocity-audit`.

## TickBase audit (2026-10-04, ~21:50)

Review-table item 5 ("tick lifecycle"). The user's config has TickBase off. **The skip half never happened:**
TickBase "paid back" its extra ticks by `event.setCancelled(true)` on TickEvent PRE. But MixinMinecraft's runTick
HEAD injection is **not cancellable** and never reads the flag, so the cancel did nothing. Every activation ran
`mc.thePlayer.onUpdate()` N extra times (N extra C03s) and repaid none. In effect it was a timer burst, the shape
Grim Timer counts.
- Fix: `TickBase.skipPlayerTick` is decided once per tick in TickEvent PRE (`ticksToSkip > 0` → skip and decrement).
  `MixinEntityPlayerSP.onUpdate` HEAD is now `cancellable = true` and cancels the player update on those ticks:
  no movement and no movement packet. This is LiquidBounce's design (it skips the player tick, not the game tick).
  The flag is cleared when the module is disabled or not applicable, and on S08.
- `selectedTick == -1` ("no tick gets closer") was not rejected; only 0 was. With pause-after-tick > 0, that ran
  pause-1 extra ticks every tick while an enemy was near. Now `<= 0` returns.
- No new run starts while the last one is still being repaid (`ticksToSkip > 0 || skipPlayerTick`), since its
  extra `onUpdate()` calls would be cancelled as skipped ticks while the balance is still charged.
- The old post-decrement left `ticksToSkip` decreasing without bound below 0. That is gone.
Mixin count is unchanged (62); one injection gained `cancellable`.
Backups: `backups/src/src-before-tickbase-audit-20261004`, `backups/config/default.json.pre-tickbase-audit-20261004`.
248 tests pass. **Installed** (game closed): md5 30e45b90…. The replaced jar (8b670b2e…) is `…pre-tickbase-audit`.

## Blink / FakeLag audit: holds across a world change (2026-10-04, ~22:00)

Review-table item 6 ("queue / disconnect cleanup"). What was already right:
- FakeLag discards stale positions on S08/S07 (`flushRequested` → `discardStalePositions`), releases on knockback
  and damage (scheduled to the client thread), and clears its queue when there is no net handler.
- Both managers stop holding on a new handshake/login, and clear rather than send when there is no connection.
- BlinkManager's lease (PacketHolds) still ends a forgotten hold.
**Fixed: holds carried into the next world.** `LoadWorldEvent` (MixinMinecraft loadWorld HEAD, fired on a proxy server
switch, a dimension change, or leaving) found BlinkManager/LagManager still holding the old world's packets:
- Blink's own `onWorldLoad` switched it off, which **released** the whole queue. Every other blink owner
  (KillAura autoblock, Scaffold, NoFall, AntiVoid, Hitflick, Displace) released on its own exit path.
- LagManager kept flushing the old queue as packets came due.
The result was movement (and clicks) from a world the server had already moved this player out of, sent into the
new one. Now both managers handle LoadWorldEvent at **Priority.HIGHEST**, before any module, via
`dropForWorldChange(world != null)`:
- On a world change, transaction replies are sent in order (the server is owed them) and everything else is dropped.
- On leaving, everything is dropped.
- BlinkManager also clears the owner, so the old owner's later `setBlinkState(false)` has nothing to send.
Tests: `BlinkManagerTest.aWorldChangeDropsTheHoldButSendsTheReplies`, `leavingDropsEverything`. 250 pass.
Backups: `backups/src/src-before-blink-fakelag-audit-20261004`, `backups/config/default.json.pre-blink-fakelag-audit-20261004`.
Gradle md5 02fbca16…. Installed if the game was closed; the replaced jar is `…pre-blink-fakelag-audit`.

## NoFall, Speed, FastPlace, AutoClicker, Reach/HitBox audit (2026-10-04, ~22:10)

Review-table items 7-11, read in full.
- **NoFall (fixed):** on S08 it called `onDisabled()` from the network thread. That released BlinkManager, so BLINK
  mode's held fall went out **after** the server's teleport, judged from the new position. Now a scheduled task on
  the client thread (it runs before vanilla's own handling of that S08) does `discardHeld()` when NO_FALL owns the
  blink (replies kept, movement dropped, as Blink does), then `onDisabled()`. This also takes the timer reset off
  the network thread.
- **Speed:** clean. Legit's +45° silent yaw and strafe fix is the design; the flag state resets on disable.
- **FastPlace:** clean. Correction pause (`pause-on-correction`, 3 ticks) is present; `place-fix` checks the camera ray.
- **AutoClicker:** clean. The delay carries its remainder (`+=`), unlike KillAura's did. Pending key states are
  restored the next tick and cleared when a screen opens.
- **Reach/HitBox:** consistent. HitBox's pick uses `Reach.effectiveRange()` (this tick's roll) and runs at TickEvent
  PRE with partialTicks 1, i.e. the same moment as vanilla's pick before clicks. It only overrides the mouse-over
  with an ENTITY result. Any HitBox multiplier above 1 is, by design, a box the server does not have.
Backups: `backups/src/src-before-nofall-audit-20261004`, `backups/config/default.json.pre-nofall-audit-20261004`.
250 tests pass. **Installed** (game closed): md5 402d1f50…, 62 mixins. The replaced jar (02fbca16…) is `…pre-nofall-audit`.

The review table (KillAura → AutoClicker) is done. No in-game measurement yet for any of today's fixes.

## Clutch DuplicateRotPlace, AirLiquidPlace review, Scaffold multi-place/tower EXTRA, Atlas language + Esc (2026-10-04, ~22:40)

User: multi-place off by default, fix tower EXTRA, Clutch gets DuplicateRotPlace / AirLiquidPlace, a language choice in
Client Settings, and Esc does not close the Client Settings page. Logs are from the 19:52 session (test.ccbluex.net,
jar 1b3f249d).
- **AirLiquidPlace ×4 (19:53:40-41) was not Clutch.** The clutch trace is empty there, and places-*.txt shows
  `catching false`: it was the player towering by hand with FastPlace.
  - 256,63,170 was REJECTed. The player had been hit 9 ticks earlier and was LAGBACKed at 19:53:41.
  - The next blocks were clicked on top of the refused one, i.e. "against air" for the server.
  - This is a knockback/lagback cascade inside one round trip. It cannot be prevented client-side without
    predicting the refusal. Not changed.
- **DuplicateRotPlace (19:55:31, x=0.0) was during Clutch bridging.** Cause: Clutch and AutoBlockIn compared against
  their own `placedTurn`, which differs from Grim's memory in three cases:
  - a placement on a tick whose C03 carried no look, where the module still recorded a stale turn;
  - the player's own clicks in between;
  - looks the module did not send.
  New `management/PlaceRotations` (registered in Myau.java) replays Grim's state from PacketEvent SEND
  (LOWEST, not cancelled; generation order = arrival order, holds included, no double count on release):
  - a C08 with direction != 255 → pending;
  - on each C03, a look first updates deltaX = |yaw - lastYaw| (raw floats, as Grim), then pending placements
    are judged (`lastPlacedDeltaX = deltaX` only if a look came since).
  - Reset on LoadWorldEvent.
  Clutch nudges while `turn > 2` and either its own check or `PlaceRotations.wouldDuplicate(nextYaw)` holds,
  at most 3 times. AutoBlockIn uses the same OR. Test: `PlaceRotationsTest` (4 cases, incl. a no-look placement).
- **Scaffold:** `multi-place` default **false**. It is also set false in the user's `config/Myau/default.json`, with
  the game closed and a backup `backups/config/default.json.pre-duprot-lang-20261004`. The only `"multi-place"`
  key is Scaffold's. Tower EXTRA's `targetFacing` place now uses `getPlacementMop(BlockData(below, facing), sentYaw,
  sentPitch)` for every mode but NONE, and skips when the sent look misses that face.
- **Atlas language:** AtlasTheme has a new "Language" group, `language` = 中文 / English (saved in atlas-theme.json).
  Atlas sets `ModuleDocs.setEnglish()` every frame.
  - `Module.getDescription()` and `Property.getHelp()` return the chosen language, falling back to the module's
    own text.
  - Headings go through `ModuleDocs.heading()`.
  - English texts are in the new `module/ModuleDocsEn.java`: all 161 descriptions rewritten (several originals were
    jokes), the shared/per-module hints, and a heading map.
  - Setting names stay English in both languages, since they are the config keys.
- **Esc on Client Settings:** keyTyped returned for *every* key on that page when the search was not focused,
  Escape included. Escape now passes through to the normal close path.
Backups: `backups/src/src-before-duprot-lang-20261004`, config as above. 254 tests pass.
**Installed** (game closed): md5 c2a0a87f…, 62 mixins. The replaced jar (402d1f50…) is `…pre-duprot-lang`.

## Mitigation on Pika traced to Clutch's same-tick click; `click-timing` (2026-10-04, ~21:00)

**User:** the mitigation check *did* fire, often, and they suspect Clutch. Session 20:33-20:50 with jar c2a0a87f:
test.ccbluex.net 20:34-20:37, then Pika duels 20:37:33 onward (nick `Nick1`).

**What fired:**
- 5 "mitigation?" lines:
  - 20:34:21 on ccbluex: 2.3 against vanilla ≥ 8.0, health from the entity. **No Clutch attempt before it**; the
    clutch trace starts 20:35:32.
  - 20:39:42, 20:44:02, 20:45:44, 20:48:36 on Pika: dealt 0.0 against ≥ 3.6, health from **tab**. Tab health is
    whole numbers and may update late, so these are weaker evidence. Two of them sit right next to a death or
    respawn (20:44:02 we died; the hits log has runs of `dropped dist=0.00`).
- Of the 3 LOW-DAMAGE flags, two were blamed `SINGLE FakeLag 77%` and one INCONCLUSIVE. HITS-DROPPED x6 were mostly
  FakeLag/Blink. Adaptive: "would disable Clutch" once (LOW-DAMAGE|other) and "would disable FakeLag" once.
- So Clutch is not the only suspect. FakeLag holding hits is the other one.

**What Clutch does differently since the GOOD jar (measured, `places-*.txt`, `catching true` rows only):**

| session | jar / mode | clicks | median turn | p90 turn | turn > 90° | `ray miss` |
|---|---|---|---|---|---|---|
| 10-02 22:52 | GOOD, REAL | 428 | 17.9° | 51° | 9 | **1** |
| 10-03 07:08 | pre-07:40, REAL | 307 | 15.8° | 48° | 3 | **1** |
| 10-03 07:17 | pre-07:40, REAL | 583 | 20.8° | 95° | 64 | **0** |
| 10-03 23:33 | 07:40 click timing | 373 | 20.0° | 97° | 45 | **231** |
| 10-04 06:35 | same | 384 | 17.7° | 155° | 83 | **248** |
| 10-04 07:03 | same | 1251 | 16.0° | 155° | 246 | **709** |
| 10-04 19:53 | same | 408 | 18.2° | 160° | 84 | **210** |
| 10-04 20:35 | same | 530 | 17.7° | 143° | 105 | **302 (57%)** |

- `ray miss` is FlagDetector's place-log field. It records whether the look the server **already has** (the last C03
  sent) reaches the clicked block from the last sent position.
- It went from ~0 to ~57% at the first session after the 10-03 07:40 install ("one block a tick"). That change moved
  the click into the same tick as the turn, so the C08 goes out **before** the C03 that carries its look.
- Grim judges a 1.8 placement post-flying (the next C03), which is why Grim stayed quiet. A check that tests the
  click against the look already sent sees a click aimed somewhere the player was not looking, often after a turn
  of more than 90°. Vanilla can only produce a few degrees of that, from mouse movement between frames.
- The mode switch REAL→SILENT (between 10-03 23:30 and 10-04 06:33, by the user) does not explain it. The 23:33
  session already shows 231 misses, and the code path is the same for both modes.

**Change: `click-timing` {NEXT_TICK (default), SAME_TICK}.**
- **NEXT_TICK** (the GOOD jar's order):
  - In PRE, `post()` first tries the click along the look already sent, from the eyes where the server has them.
  - If the click goes out, `holdThisTick` makes `stepRotation` a no-op, so this tick's C03 repeats that look.
    Both the pre-flying and the post-flying view then agree.
  - Otherwise the tick only turns.
  - Plans are made one tick ahead: `planCatch/planChain(levels, eyesAt(path[0]), 1)`, and pre-aim leads start at 2.
  - `planChain`'s interval is `max(2, place-interval)`, since a block that needs a turn takes two ticks.
  - `stepFor` spreads the turn over `catchTick - 1` ticks.
  - `aimedCell` (last tick's plan cell) also counts as wanted in `post()`, so a held look whose plan moved on still
    places.
- **SAME_TICK** is the 10-03 behaviour, unchanged.
- Cost: chains are slower (one block per two ticks while turning). That is the speed the user called "perfect" on
  10-02.
- Not changed:
  - speed and max-speed (the user's config has 180/180). A >90° turn is still possible, but now a tick before the
    click and not in the same tick.
  - The number of blocks per jump, which is about 4. 209 of 387 went down while still rising (see the 06:50
    section). That is the next lever if mitigation continues.
- The docs (zh/en) list `click-timing` under 放置 / Placement.

Backups: `backups/src/src-before-clutch-clicktiming-20261004`, `backups/config/default.json.pre-clutch-clicktiming-20261004`,
`backups/jars/Myau+.jar-2.1+4.jar.pre-clutch-clicktiming-20261004` (c2a0a87f). 254 tests pass. Build md5 1cec0ece…,
62 mixin classes. **Installed** once the game closed (md5 matches, one Myau jar in mods). No config change is needed; the key is
absent, so NEXT_TICK applies.
**How to judge it next session:** `ray miss` among `catching true` rows should fall back toward 0, REJECTs should not
rise, and saves per attempt should be similar (`saved with` in clutch-*.txt). If mitigation lines still appear with no
Clutch attempt in the preceding 30 s, look at FakeLag next.

## Reverted: `click-timing` (2026-10-04, ~21:15)

**User:** "用回去舊版的，現在變成沒辦法連續clutch，當初設計這樣是有原因的". NEXT_TICK made the clutch unable to place
continuously: two ticks per turned block is too slow to catch a fall.
- The jar is back to **c2a0a87f** (from `backups/jars/…pre-clutch-clicktiming-20261004`). md5 checked, one Myau
  jar in mods/, 62 mixin classes.
- `src/` is back to `backups/src/src-before-clutch-clicktiming-20261004`, so the source matches the jar again. The
  reverted change is kept at `backups/src/src-clutch-clicktiming-reverted-20261004`, and its jar (1cec0ece) at
  `backups/jars/…clutch-clicktiming-reverted-20261004`.
- Config untouched.
- **Lesson:** do not trade Clutch's one-block-a-tick cadence for safety. The user needs continuous placement. The
  57% "look not sent yet" finding in the section above still stands. If it has to be addressed, it must be done
  without losing the cadence. Two ideas:
  - plan the turn a tick earlier, i.e. pre-aim harder, so the look is already sent when the click is due;
  - only hold the click when the held look cannot reach any useful cell.
  Neither is done.

## Clutch `early-turn`: big turns a tick early, only when the fall allows (2026-10-04, ~21:30)

**User:** "做提早轉頭的方法，不要降速度，不要出邏輯錯誤". This follows the revert above. The same-tick click and the
one-block-a-tick cadence stay; two things are added in front of them, in the plan branch of `onUpdate`.

**1. Click along the look already sent.** `post()` runs first, before this tick's turn.
- If the look the server already has reaches a wanted cell from where it has the eyes, the click goes out.
  `holdThisTick` then makes `stepRotation` a no-op, so this tick's C03 repeats the look. The look before the click
  and the one after it are the same, so both pre-flying and post-flying checks pass.
- The click would have gone out this tick anyway, so it costs no time.
- The last `post()` is skipped after it, which keeps it to one click a tick. With `place-interval 0` a second
  `post()` could otherwise place again along the same look.
- Its "why not" notes are muted (`quietNotes`). They would repeat every tick.

**2. Early turn** (`early-turn`, default 45°, range 10-180, 180 = off). All of these must hold:
- the turn the plan needs is larger than the setting (`RotationEngine.angle(sent, plan)`);
- it is a lead-0 plan (no pre-aim) and not a ladder;
- the planners still find a plan with the click **a tick later**: `planCatch(levels, eyesAt(path[0]), 1)`, and
  for a chain plan also `planChain(…, 1)`.

When they do, it turns toward that later plan now and does not click. Next tick, step 1 clicks along it.
- The later plan must be of the same kind and on the same layer. A direct catch is never traded for a chain, and the
  catch never drops a layer.
- "A tick to spare" is the planners' own deadline test (`crossing >= lead`, `d*interval + lead <= deadline`), so a
  delayed click is still in time by the same rules every other click is judged by.
- With no tick to spare, it turns and clicks in one tick exactly as before. Small turns (≤ 45°, most of a chain)
  are never delayed. So the chain cadence is unchanged, and only big snaps that have slack are split.
- If the early turn does not land exactly (ease, a misprediction), the next tick re-plans. The remaining turn is then
  small, so it turns and clicks in that tick as before. It cannot keep delaying: each delay needs a valid lead-1
  plan, and the slack shrinks every tick.
- `aimedCell`/`aimedTick`: the early turn's cell is also "wanted" in `post()`, for **only** the next tick.

**Expected effect:** fewer placements with a big turn in the click's own tick (105 over 90° on 10-04 20:35), and so
fewer `ray miss` rows in `places-*.txt`. Small-turn same-tick clicks still show as `ray miss`, because FlagDetector
judges against the look sent before the click. Measure `catching true` rows: turns over 45° in the click tick and
`ray miss` should drop. `saved with` counts and REJECTs should not get worse.

The docs (zh/en) list `early-turn` under 放置 / Placement. 254 tests pass, build md5 8c5244fd…, 62 mixin classes.
Backups: `backups/src/src-before-clutch-earlyturn-20261004`, `backups/config/default.json.pre-clutch-earlyturn-20261004`,
`backups/jars/Myau+.jar-2.1+4.jar.pre-clutch-earlyturn-20261004` (c2a0a87f, the installed jar). **Not installed
yet: the game was running at build time.** No config change; the key is absent, so 45 applies.

## Clutch `safe-mode` switch (2026-10-04, ~21:40)

**User:** "你把這個版本做成一個開關(safe mode)".
- New `safe-mode` (BooleanProperty, **default off**) gates both parts of the early-turn section above:
  - the click along the look already sent;
  - the early turn.
- `early-turn` is shown only while it is on.
- **Off:** `clickedHeld` and `early` stay false, `aimedCell` stays null, and the final `post()` runs. That is the
  same code path as the c2a0a87f jar, which turns and clicks in the same tick.
- Default off so nothing changes until the user turns it on (Clutch → 放置 → safe-mode).
- The docs (zh/en) give help for `safe-mode`.

Backups: `backups/src/src-before-clutch-safemode-20261004` (the early-turn source), config and jar
`…pre-clutch-safemode-20261004`. 254 tests pass, build md5 00f0895a…, 62 mixin classes. This build supersedes the
8c5244fd early-turn build, which was never installed. **Installed** (game closed): md5 matches, one Myau jar in mods/, 62 mixins; replaced jar c2a0a87f.

## Session 21:17-21:25 with `safe-mode` on (2026-10-04, diagnosis only)

**User:** "還是減傷了，我用太多". Jar 00f0895a with safe-mode on. Pika BedFight from 21:21 (nick `Nick2`). Clutch was
in REAL mode with speed 30, which the user changed.

**Clutch with safe-mode:**
- 78 attempts; the trace shows 94 early turns and 122 clicks along the held look.
- Every attempt was saved, at the usual 3-6 blocks.
- `catching true` placements: 292, compared with 561 at 20:35:

| | 20:35 | 21:17 |
|---|---|---|
| `ray miss` | 57% | **33%** |
| REJECT | 8 | **0** |

  `rot` is no longer the click tick's turn after a held click: it is the early turn the tick before.

**Mitigation:** 3 lines, all on Pika, all "0.0 against ≥ 3.6, health from tab".

| time | last Clutch attempt | FakeLag hold before it |
|---|---|---|
| 21:22:28 | 6 s earlier | 32 packets for 1251 ms, released 21:22:27 |
| 21:23:24 | 1 s earlier | — (a LAGBACK and a VERTICAL at the same second) |
| 21:24:32 | **46 s earlier** | 13 packets for 399 ms; the LOW-DAMAGE line says "holding FakeLag out 8 for 251ms" |

- 21:24:32 had "usual 0.00", so tab health never moved for that target. That one is probably a hidden-health false
  positive.
- Adaptive again said "would disable FakeLag" (HITS-DROPPED).

**FakeLag holds are far longer than configured.** The config is `delay 150`, `delay-random 100`, LIQUID. Yet of
99 holds, 9 lasted ≥ 600 ms and 2 lasted ≥ 1000 ms (max 1251 ms, 32 packets), released on melee, attack or
not-moving. A second of held movement and hits, arriving in a burst, is the strongest lag signal a server sees.

**Conclusion:** Clutch with safe-mode is no longer the main suspect. FakeLag's long holds are. Next: find why LIQUID
holds run 4-8 times past `delay`. The quickest confirmation is a session with FakeLag off.
**User's verdict (same evening):** "是因為clutch，我前面用來疊橋。不用修，我單純用太兇".
- The user attributes the damage cut to using Clutch to build bridges heavily, which is usage and not a defect.
- **No fix wanted.** FakeLag's long holds are recorded above for reference only; do not change FakeLag on the
  strength of this session.

## Public GitHub release folder (2026-10-04, ~22:00)

**User:** wants the project open-sourced on GitHub. The release is a **separate folder**,
`../OpenMyau-Plus-release`. This working tree is not touched.

**Not copied:**
- `build/`, `.gradle/`, `.vscode/`, `run/`, `logs/`, `reports/`, `backups/`;
- the empty `FETCH_HEAD`/`git` files;
- `docs/tools/carve_embedded_jar.py`, which extracts a jar from a commercial client's DLL.

**Anonymised:**
- Account names → `Player1-3`. Nicks → `Nick1-2`. Other players' names → `Opponent1-6`.
- `<home>...` paths → `<home>`/`<instance>`.
- Local reference-client folders → `<reference clients>`.
- The notes' "Vape reference" how-to was replaced by a short "Third-party references" paragraph.
- MatchChat comment and test names are anonymised too, and the tests still pass.

**Modules:**
- AdvancedAim and KillAura "Advanced" were first left out (Rise-derived). The user then decided to keep them ("學來的不是抄襲，是參考借鑑").
- So all modules are identical to this tree.

**State of the release folder:**
- New README (fork credit, GPL-3.0, usage disclaimer, zh/en). `.gitignore` also excludes local data.
- Builds; **254 tests pass**.
- `git init -b main` with 1043 files staged and **no commit yet**. There is no git identity on this machine, and the
  author should be the user's GitHub noreply address, so it waits for their username.
- **Pushed** 2026-10-04 to https://github.com/Marco-hacker666/Myau_Atlas (renamed from Myau_Atalas; branch main, commit 6cebe25, then dba7ad7 adding Atlas screenshots; description and topics set by the user), author Marco-hacker666 noreply. Future updates: change the working tree, re-copy into the release folder, re-run the anonymisation, then commit and push from there.

## GitHub release v1.0.0 prepared (2026-10-04, ~22:35)

- Asset: `../release-assets/Myau-Atlas-v1.0.0.jar`.
  - Rebuilt from the release folder at commit dba7ad7. 254 tests pass, 62 mixin classes.
  - sha256 `74738a121b08399f8e4721e57e5e3e2c448b766846d53c11c4de0b018853fe4c`.
- Tag `v1.0.0` on `main`. The user creates the release on github.com; tag, title and notes were given in chat.

## Atlas UI port: paused half-way (2026-10-05, ~23:40)

**User:** another AI made `mods/Myau_Atlas_UI_ported.jar` (md5 ec36f1a7). It added:
- a HUD editor (`myau.ui.hud.HudLayout` / `HudEditorScreen`);
- a Light/Dark mode (`myau.ui.UiMode`);
- Legit groups;
- 13 ported modules: InventoryHUD, PotionHUD, ClosestPlayerHUD, PlayerList, FKCounter, TNTTimer, DamageTags, ItemTags,
  KeyStrokes, Notifications, BedPlates, EntityCulling, ExploitFixer.

The user asked for a fix: the new HUDs are not found by the HUD editor. **No source was provided.** The
`Myau-Atlas-jar__1_.zip` from the Desktop holds an older jar (cca24283), also without source.

**What was found:**
- Compared with our 00f0895a build, only 15 classes differ; everything else is byte-identical. So the other AI worked
  from our source.
- **`Myau.class` was bytecode-patched.** `modules.put(Scaffold…)` became `PortRegistry.put(…)`, and that hook adds the 13
  modules. There is no source equivalent.
- **Root cause of the bug:** existing HUDs call `HudLayout.report(id, label, x, y, w, h, mover)` every frame they
  draw. `HudLayout.visible()` returns only elements reported in the last 250 ms, so the editor shows what is being
  drawn right now. The 13 new modules never call `report`, so the editor cannot see them.
- **UiMode persistence is not a bug:**
  - `appearance-mode` is a property in AtlasTheme's Colors group. AtlasTheme autosaves it to
    `config/Myau/atlas-theme.json` (snapshot every 250 ms, written 500 ms after a change, and on close).
  - `UiMode.setLight` only mirrors it at runtime. `UiMode.load()` reads it once for HUDs drawn before the menu opens.
  - The user's file already has `"appearance-mode": "Dark"`.

**Done so far (ported into our source by hand, from the CFR diff of the two builds):**
- ArmorHUD, EffectsHUD, FPScounter, Hotbar, DynamicIsland, LegitHUD, Statistics, WaterMark, WaterMark2 and HUD
  (module list): the `HudLayout.report` calls, `offset-x/-y` where added, and the `UiMode.adapt` colours.
- AtlasTheme: `appearance-mode`, `LIGHT_TINT`, light pane, `isLight`/`toggleMode`, and `reset` keeping the mode.

**Stopped:** copying the 17 new classes (decompiled from the other AI's jar) into `src/` was refused by the
session's safety classifier as untrusted-code integration. It now waits for the user's decision. Also not done:
- the AtlasClickGui changes;
- the Myau/ModuleCategories registration;
- the HUD fix itself.

**State:** `src/` does **not build** right now. The ported modules reference `myau.ui.UiMode` / `myau.ui.hud.HudLayout`,
which are not in `src/` yet. The clean source is `backups/src/src-before-atlas-ui-port-20261005`. Other backups:
`backups/jars/Myau_Atlas_UI_ported.jar.pre-atlas-ui-port-20261005` and
`backups/jars/Myau-Atlas-jar__1_.zip.received-20261005`. Nothing was installed. mods/ still has the other AI's jar.

## Atlas UI update from source; ArmorHUD lighting, CJK font, Fast Render (2026-10-05, ~23:55)

**Supersedes the "paused half-way" section above.** The user then provided `Myau_Atlas_source_2026-10-05.zip`
(Desktop):
- `1_ui_update/` holds the other AI's real Java source for its 16 UI files, plus `atlas-ui.patch`.
- `2_port/` holds its bytecode tools.
- `3_github_build/build.yml`.
- Its README says the 13 extra modules were moved in as **compiled OpenSkid classes** with no source.
  `Myau.class`/`ModuleCategories` were bytecode-patched (`PortRegistry`).

**What was done:**
1. My hand-port was reverted (src equalled `backups/src/src-before-atlas-ui-port-20261005` again).
2. `git apply atlas-ui.patch` was applied cleanly. It adds `myau/ui/UiMode`, `myau/ui/hud/HudLayout`,
   `myau/ui/hud/HudEditorScreen` and `atlas/LegitGroups`, and modifies AtlasClickGui, AtlasTheme, LegitHUD,
   ArmorHUD, EffectsHUD, WaterMark(2), FPScounter, Hotbar, DynamicIsland, Statistics and HUD.
   - Scanned first for Runtime/ProcessBuilder/URL/Socket/file writes: none.
   - It builds, and the 254 tests pass.
3. **ArmorHUD lighting leak (user: chat turned dark/tinted when the boots came off).**
   - `renderItemOverlayIntoGUI` turns GL lighting back **on** after drawing a durability bar. ArmorHUD drew each
     piece with no item-lighting bracket, so a damaged last piece left lighting on for everything drawn
     afterwards, the chat included.
   - With boots on, the last piece was undamaged boots, whose `renderItemIntoGUI` ends with `disableLighting`.
     Taking them off made the damaged leggings last.
   - This bug predates the UI update.
   - Fix: draw the backgrounds first, then the items inside `RenderHelper.enableGUIStandardItemLighting()` /
     `disableStandardItemLighting()` (as the vanilla hotbar does), restoring rescale-normal, alpha, blend and colour
     in a `finally`, then draw the durability text.
   - Hotbar and RenderUtil already bracket correctly.
4. **Chinese text quality in Atlas (LiquidFont).**
   - Non-Latin-1 strings used to be drawn whole with the game's 16-px unicode bitmaps at a fractional scale, which
     came out jagged and broken.
   - Now characters beyond Latin-1 are rasterised one at a time on first use from the bundled
     `NotoSansSC-Regular.ttf`. They use the same size×scale, hints and alpha lift as the Latin sheet, and go into
     1024² pages that grow as needed.
   - Uploads go through `TextureUtil.uploadTextureMipmap(..., blur=true)`, and pages start cleared. Drawing rebinds
     between pages and the Latin sheet outside `glBegin/glEnd`.
   - A string with a character neither face has (an emoji, a surrogate pair) still falls back to the game font
     (`drawable()`).
   - Checked offline: the font renders traditional characters (攻擊…標) cleanly.
5. **OptiFine "Fast Render" → white screen.**
   - OptiFine reports framebuffers as unavailable with Fast Render (and antialiasing), so `bindFramebuffer` does
     nothing. The blur/glass passes then draw their full-screen quads straight onto the screen.
   - Liquid checked `isFramebufferEnabled()` only once, at shader compile time.
   - New `util/render/FramebufferCompat.available()` checks, every frame: shaders supported,
     `isFramebufferEnabled()`, and not `GameSettings.ofFastRender` (by reflection; false without OptiFine).
   - Gated:
     - `Liquid.beginFrame`: no copies, so the plain glass is used.
     - `BlurUtils.prepareBlur/prepareBloom`: colour writes off until the matching end, so the caller's mask never
       reaches the screen, and the blur is skipped.
     - `BlurShader.renderBlur` (TargetHUD): skipped.
   - **Not testable here:** no OptiFine in this instance.
6. **UiMode persistence: not a bug.** `appearance-mode` is saved by AtlasTheme's autosave.
7. **HudLayout design reviewed and kept.**
   - The 250 ms "seen" window means "being drawn now". Disabled HUDs and world changes drop out within 250 ms.
   - Elements are keyed by id, so there are no duplicates.
   - HUDs with nothing to show report a placeholder while editing (ArmorHUD, EffectsHUD), so opening the editor
     before a HUD has drawn cannot NPE (`visible()` only returns reported elements).

**Not done: the 13 OpenSkid modules.**
- They exist only as compiled classes in the other AI's jar.
- GPL-3.0 source exists at https://github.com/loloshelly102-a11y/OpenSkid (`src/main/java/openskid/module/modules/`;
  all 13 files are present).
- Downloading it into the project was refused **twice** by the session's safety classifier (untrusted code
  integration), the second time after the user's explicit go-ahead. It needs the user to allow it in their Claude
  Code permission settings, or to download the files themselves.
- All 13 were **disabled** in the user's config. Their settings are preserved in
  `backups/config/default.json.pre-ui-fixes-install-20261005`.

**Installed** (game closed): `mods/Myau+.jar-2.1+4.jar` md5 68d8475f, one Myau jar, 62 mixin classes. 254 tests pass.
- The other AI's jar was removed from mods/. It is kept as `backups/jars/Myau_Atlas_UI_ported.jar.pre-atlas-ui-port-20261005`
  (ec36f1a7).
- Backups: `backups/src/src-before-openskid-port-20261005` (= the installed source),
  `backups/jars/Myau+.jar-2.1+4.jar.ui-fixes-no-openskid-68d8475f`.

**CHANGELOG:**
- Atlas UI update: compact header, HUD Editor, Light/Dark mode, Legit groups, cross-page search.
- Fix: ArmorHUD no longer leaves GL lighting on, which had darkened the chat after a damaged last armour piece.
- Fix: Chinese and other non-Latin text in Atlas is drawn with Noto Sans SC instead of scaled unicode bitmaps.
- Fix: blur and glass effects fall back to plain drawing when framebuffers are unavailable (OptiFine Fast Render),
  instead of whitening the screen.

**GitHub (same night): not pushed, on the user's word ("github先不用").**
- The release folder has a local commit, 1f42f94: the Atlas UI update, the three fixes and a README update. 254
  tests pass.
- The push was rejected because the remote has two commits the local repo lacks: 6738434 (web upload of
  `atlas-ui.patch` to the repo root) and a8547fe (`.github/workflows/build.yml`).
- That workflow applies `atlas-ui.patch` only if `git apply --check` passes. On a tree that already has the
  changes, the check fails and it builds as-is.
- When publishing: `git pull --rebase`, then build, then push. Never force.

## The 13 OpenSkid modules ported from source; HUD editor support (2026-10-06, ~00:20)

**User:** "你趕快去加回來，我給你開權限了". The download was then allowed.

**Source.**
- https://github.com/loloshelly102-a11y/OpenSkid (GPL-3.0), `src/main/java/openskid/module/modules/`, main branch:
  KeyStrokes, PotionHUD, InventoryHUD, PlayerList, ClosestPlayerHUD, FKCounter, BedPlates, TNTTimer, DamageTags,
  ItemTags, EntityCulling, Notifications, ExploitFixer.
- Scanned for Runtime/ProcessBuilder/URL/Socket/file writes: none.
- Ported by renaming the package `openskid` → `myau` and `OpenSkid` → `Myau`. Every other dependency already exists
  in this tree. Each file has a header naming its origin.
- **Registration in source:**
  - `Myau.java`: right after Scaffold, in the same order the bytecode-patched `PortRegistry` used.
  - `ModuleCategories`: LEGIT for the six screen HUDs, RENDER for BedPlates, TNTTimer, DamageTags, ItemTags and
    EntityCulling, CLIENT for Notifications, MISC for ExploitFixer.
  - `ModuleDocs` / `ModuleDocsEn`: descriptions. `ModuleDocsTest` caught their absence.

**HUD editor (the original bug).** Each screen HUD now calls `HudLayout.report` with the rectangle it draws:
- **KeyStrokes, InventoryHUD, PotionHUD.** Anchored by `position-x/-y` plus `offset-x/-y`. The mover's sign is
  -1 on the RIGHT/BOTTOM anchors, where a larger offset moves the element left/up.
- **PlayerList, ClosestPlayerHUD, FKCounter.** Positioned top-left by `offset-x/-y` (0..1000). The box is the
  background's: -2 px around the text, times scale.
- **Placeholders while editing.** PotionHUD (no effects), PlayerList (no lines), ClosestPlayerHUD (nobody near) and
  Notifications (none showing) draw nothing when empty, so while the editor is open they report a placeholder box
  and draw nothing.
- **Notifications** had no position settings. It now has `offset-x/-y` (-1000..1000, default 0 = the old
  bottom-right place), applied to the whole stack. It reports the stack's bounds.
- **Not in the editor, on purpose:** TNTTimer, DamageTags and ItemTags are world-space labels drawn in
  Render3DEvent at blocks and entities. BedPlates is world-space too. None has a screen position.
- InventoryHUD brackets item lighting per slot, so it does not have ArmorHUD's lighting leak.

**Result.** 254 tests pass. **Installed** (game closed): md5 fadec5b7, one Myau jar, 62 mixin classes, all 13 classes
present. The user's config still holds the 13 modules' entries; all are off, as before.

**Backups:**
- `backups/jars/Myau+.jar-2.1+4.jar.pre-openskid-port-20261005` (68d8475f).
- `backups/config/default.json.pre-openskid-port-20261005`.
- `backups/src/src-before-openskid-port-20261005`, `backups/src/src-after-openskid-port-20261005`.

**GitHub:** not pushed, per the user. The release folder's local commit 1f42f94 predates this port.

## 2026-10-06 — ClickGUI cleanup: only Atlas and Normal; the Myau Atlas logo in the Atlas header

**What changed**
- Removed the click-menu styles Raven B3 (`myau.ui.ClickGui` with `ui/components`, `ui/dataset`, `ui/callback`,
  `ui/Component`), Raven B4 (`clickgui/raven`), Cheadle, Modern, RiseLB (`clickgui/riselb`) and the separate
  Rise v6 menu (`clickgui/rise` + the `RiseClickGUI` module). Only **Atlas** and **Normal** (kept as the backup)
  remain.
- `ClickGUIModule.style` is now `{"Normal", "Atlas"}`, default **Atlas**. The style is saved by *name*
  (`ModeProperty.write` stores the mode string), so a config that says "Atlas" or "Normal" keeps its choice and one
  naming a removed style falls back to Atlas. `ClickGUIModule.isClickGui(screen)` replaces the long instanceof chains.
- The `RiseClickGUI` entry left in an old config is simply not loaded any more (no module of that name).
- `AutoAnduril` and `InvWalk` had checks for `myau.ui.ClickGui` (Raven B3 only). They are removed; behaviour in
  Atlas and Normal is unchanged (neither ever matched them). InvWalk's `click-gui` setting is therefore inert:
  Normal moves the player itself while it is open (`ClickGuiScreen.handleInvWalk`), Atlas never did.
- References cleaned up in `Myau`, `ModuleCategories`, `ModuleDocs`/`ModuleDocsEn`, `HUD`, `Panic`, `Adaptive`.
- Atlas header: the accent bead and "Myau+ Atlas" were replaced by the Myau Atlas logo
  (`assets/myau/assets/atlas-logo.png`, 64 px, drawn 18 GUI px with linear filtering, faded with `Liquid.alpha`)
  and **MYAU** (text colour) + **ATLAS** (lime `#C6FF00` in dark mode, `#5E8000` in light mode for contrast) —
  the style of the README banner. The logo source is `branding/atlas-logo-lime.svg` outside the project.

**Checks**: Gradle build, 254 tests pass; jar has 62 mixin classes and none of the removed packages.
Backups: `backups/src/src-before-gui-cleanup-20261006`, `…-after-…`, `backups/jars/Myau+UI_fixed.jar.pre-gui-cleanup-20261006`,
`backups/config/default.json.pre-gui-cleanup-20261006`.

## 2026-10-06 (later) — Atlas logo fix and the "Cosmos" look

**Logo showed the missing-texture checkerboard.** The header drew `new ResourceLocation("myau", "assets/atlas-logo.png")`
through the TextureManager, but this mod's assets are not in the game's resource packs, so nothing was found. Now the
PNG is read with `getResourceAsStream` and uploaded once as a `DynamicTexture` (`getDynamicTextureLocation`), the same
way WaterMark loads its pictures. **Rule for future pictures in this client: load from the jar, never by plain
ResourceLocation.**

**Cosmos** (`atlas/Cosmos.java`), the README banner's style behind the Atlas window, drawn in screen space after the
world dim and before the window (so the glass blurs it):
- up to 170 stars from a fixed seed (the same sky each time), twinkling; 1 in 6 tinted with the accent;
- up to 3 comets from the top / right edge heading down-left, 1.7 s each, 1.2–4.7 s apart, tail of 12 fading segments;
- a planet in the lower right with a tilted ring (far half behind, near half in front) and a moon riding the ring
  (hidden while behind the planet), plus a small far planet top-left.
Settings (Appearance → Cosmos, saved in `atlas-theme.json`): `cosmos-background` (on), `stars` 60 %, `comets`, `planet`.
New colours: accent **Lime** `#C6FF00` (index 9, after Custom so Custom stays 8), tint **Void** `#07080A` (index 7).
New preset **Cosmos**: Lime + Void, opacity 62, saturation 110, sheen 25, world dim 70, selection tint 16.
The user's `atlas-theme.json` was switched to these values (game closed; backup
`backups/config/atlas-theme.json.pre-cosmos-20261006`); their other settings were kept.

Build + 254 tests pass, 62 mixins, installed md5 215f545f. Backup of sources: `backups/src/src-after-cosmos-20261006`.

### 2026-10-06 — Cosmos planet, more detailed
The planet got: a three-layer halo that breathes (0.8 rad/s), cloud bands along the ring's tilt (lines cut to the
chord so they stay inside the disc), a day/night shade (`rectH` to 62 % black on the right), a two-part atmosphere
edge, a highlight where the light lands, three rings with gaps that are brighter on the lit side and thinner behind,
a moon with its own glow, and a four-pointed glint on the lit limb. The far planet has a halo and night side; the
brightest stars glow. Liquid already multiplies every colour by `Liquid.alpha`, so the extra `alpha` passed in Cosmos
only makes the fade-in a little faster. Previous version: `backups/src/Cosmos.java.pre-planet-art-20261006`.

### 2026-10-06 — Cosmos v3: a rendered planet ("it looks fake and nothing moves")
User screenshot of v2: the rings were chains of `Liquid.line` segments whose round caps overlapped, so every joint
doubled the alpha and the ring read as beads; the cloud bands were flat grey pills; the edge was a hard lime outline;
almost nothing moved. Rewritten (`Cosmos.java`):
- **Planet disc rendered per pixel** into a 160 px `DynamicTexture`, at most every 40 ms: sphere normal per pixel,
  light from the upper left with a smoothstep terminator, rim atmosphere scattering in the accent, a specular spot,
  1-px antialiased edge. The surface is a 512×256 cloud map built once from 3-D value-noise fBm sampled on a cylinder
  (so it wraps) — latitude bands warped by noise plus fine turbulence — coloured by a 256-entry palette from near
  black to the (slightly whitened) accent. **It rotates, with differential rotation** (equator faster than poles).
- **Rings**: smooth GL triangle strips (34 radial slices × 90 steps per half), density profile with two gaps and soft
  edges, lit side brighter, a slow shimmer travelling round, the near half dimmed where it lies in the planet's
  shadow. Far half before the disc, near half after.
- **Dust**: 170 specks distributed by ring density, orbiting at Keplerian speed (ω ∝ r^-1.5), twinkling.
- Moon outside the rings with a day/night side; comet tails and the glint are tapered strips (no beads);
  stars drift slowly left with parallax by size.
- Plain-GL helpers `begin()/end()` set SRC_ALPHA blending, smooth shading, no texture/alpha test/cull, and restore.
  Vertex alphas include `Liquid.alpha`.
- Checked offline before installing: the disc and ring maths were copied into a standalone Java2D preview
  (`backups/src/Prev.java`, output `branding/planet-preview.png`).
Backups: v2 `backups/src/Cosmos.java.pre-planet-art-20261006`, v3 `backups/src/Cosmos.java.v3-rendered-planet-20261006`.
Installed (game closed, checked with a gating PowerShell step): md5 b27faf80, 62 mixins, 254 tests. Previous jar: backups/jars/Myau+UI_fixed.jar.pre-cosmos-v3-20261006.

## 2026-10-06 — Scaffold rebuilt on Clutch's placement discipline (reference: LiquidBounce nextgen ModuleScaffold)

**Reports** (from the user, "many people"): too many flags, ghost blocks, setbacks / refused blocks, unnatural turning,
in every setup. The logs hold 49 REJECTs and several LAGBACK x3–x7 blamed on Scaffold. The user's config: GODBIRGDE,
move-fix SILENT, eagle on, tower NONE, keep-y NONE, multi-place off.

**What was wrong, and the fix (each from Clutch, checked against LiquidBounce's design):**
1. **The click was made at Priority.HIGH**, checked against `event.getNewYaw()` *at that moment*. A module running later
   with a higher rotation priority (AutoHeadHitter 6, BedNuker 5) could replace the rotation after the click, so the
   C08 went out with a look that did not make it. Now the HIGH handler only plans and turns; the click happens in a
   new `onUpdateClick` at **Priority.LOWEST**, against the final sent look. (LiquidBounce: rotation in
   RotationUpdateEvent, placement in the tick from `RotationManager.currentRotation`.)
2. **It clicked only the planned face.** Now, like Clutch `post()` and LiquidBounce's crosshair-target check, it clicks
   **what the sent look actually hits** (`lookHit`) when that puts a block in the planned cell, or in another cell of the
   same layer under the player (current box swept by this tick's motion). Support must be solid and not interactable.
3. **Ghost blocks: a C08 for a placement the game refuses.** `onPlayerRightClick` sends the C08 whatever it answers;
   when the client would not place (cell taken, an entity in the way) the server may still place from where it has the
   player. `place()` now checks `ItemBlock.canPlaceBlockOnSide` (which includes the entity-collision check) first, and a
   face answered false is skipped for 10 ticks.
4. **Clicking through setbacks and refusals.** `onPacket` (network thread, hand-over only): S08 → pause clicks for a
   round trip + 1 (`pause-on-correction`, on); S23 air on a block this placed → refused → pause a round trip, ×3 after
   three refusals in 40 ticks. Placed cells are remembered 100 ticks.
5. **Snapping.** For DEFAULT…Hypixel the sent look now steps toward the target through `RotationEngine` at
   `turn-speed` (80°/tick default, 180 = old behaviour) with `humanize` (NOISE + CURVE), from the last reported look,
   then onto the mouse grid; a step equal to the last placement's yaw step is nudged by one mouse count (Grim
   DuplicateRotPlace, `PlaceRotations.wouldDuplicate`, as Clutch does). SNAP/SNAP2/3FMC (snap by design) and towering
   are unchanged. Because the click is verified against the sent look, a slower turn means a click waits, never a wrong
   click.
Not changed: tower VANILLA/EXTRA/TELLY set motion directly (prediction ACs flag that by nature); multi-place stays off
by default; NONE mode keeps its planned click. Vanilla already drops sprint when the remapped forward input is < 0.8.
New settings documented (zh/en): `turn-speed`, `humanize`, `pause-on-correction`.
Build + 254 tests pass. Backups: `backups/src/src-before-scaffold-clutch-logic-20261006`, `…-after-…`,
`backups/config/default.json.pre-scaffold-clutch-logic-20261006`. **Not installed yet: the game was running.**
**Installed** (game closed, gated check): md5 ff7ba658, 62 mixins, one Myau jar. Replaced jar: backups/jars/Myau+UI_fixed.jar.pre-scaffold-clutch-logic-20261006.

### 2026-10-06 22:11 — first test: REJECT x4 and one Grim "Simulation" (.0298) on test.ccbluex.net
The place log showed the refused clicks judged along the **last sent** look: `rot 70.1 | ray miss` on the first one
(Scaffold had just turned 70° and clicked in the same tick), `ray side:up` on the next. The server judges a 1.8
placement with the rotation it already has — the place log has always assumed so, and Clutch's safe-mode exists for it.
**Fix:** `onUpdateClick` verifies and clicks along `event.getYaw()/getPitch()` (the last reported look) for every
turning mode; on a tick that clicks, the rotation and the movement yaw are **held** at that look
(`setRotation`/`setPervRotation` at HOLD_PRIORITY 8, above Clutch's 7), so the packet after the click carries the
same look and movement is simulated with the same yaw (one source of a Simulation mismatch removed; the single .0298
came at the 70° turn as Scaffold switched on). Turning ticks and clicking ticks now alternate when a turn is needed;
a held look that still lands on a wanted cell keeps clicking. Built md5 a5b2f520; not installed while the game ran.
**Installed** (game closed, gated check): md5 a5b2f520, 62 mixins. Replaced jar: backups/jars/Myau+UI_fixed.jar.pre-scaffold-held-look-20261006.

### 2026-10-06 22:15 — second test: no REJECTs, but Grim "Simulation" .005–.19 (vl 51) + "GroundSpoof claimed true"
Every place-log line was `ray face` (the held-look fix works: zero REJECTs). The Simulation flags came only while
bridging **on the ground**, none while towering. Cause: Scaffold's `safe-walk` was a **silent clamp at the edge**
(SafeWalkEvent without sneaking) — Grim simulates the edge stop only for a crouching player, so each clamp was a
movement it could not explain (and an onGround it disputed). The old, faster placement reached edges less often; the
turn-then-click version reached them constantly. Older logs (10-04) show the same family mixed with RotationPlace,
AirLiquidPlace and MultiPlace.
**Fix (LiquidBounce's Ledge feature):** `safe-walk` now **crouches** for real when, on the ground, two ticks of the
current motion would leave nothing underneath (`atLedge`): `movementInput.sneak = true`, input ×0.3 (vanilla's own
sneak scaling, applied after the SILENT strafe remap). The silent clamp is gone; `onSafeWalk` only confirms vanilla's
crouch behaviour. Default `turn-speed` raised 80 → 120 so fewer ticks are spent turning.
Build + 254 tests. **Installed** (game closed, gated): md5 4b0ba41b, 62 mixins. Replaced jar:
`backups/jars/Myau+UI_fixed.jar.pre-scaffold-ledge-20261006`.

### 2026-10-06 — "LiquidBounce doesn't get slower": stabilized aim
The user pointed out LiquidBounce does not slow down. It doesn't because its aim is **stable** (Normal technique,
RotationMode STABILIZED): while bridging the look barely moves, so the look the server already has keeps hitting the
next face, every tick can click, and its Ledge crouch only fires when the rotation is not ready. Ours re-aimed at the
best point of each new block (minimising the turn from the last *target*), so the look kept moving, a turning tick came
before most clicks, and the player reached edges and crouched.
**Fix:** (1) for the turning modes, when `lookHit` says the last reported look already puts a block in a wanted cell,
that look is kept (no turn this tick); (2) face points are chosen by the least turn from the **sent** look, so a needed
turn is as small as possible; (3) "wanted" cells include where the player will be over the next three ticks of motion
(LiquidBounce plans from the predicted position). Built md5 a93c25ef; not installed — the game was running.
**Installed** (game closed, gated): md5 a93c25ef. Rollback candidates: pre-scaffold-held-look (ff7ba658, first Scaffold change) and pre-scaffold-clutch-logic (Scaffold before today's work).

### 2026-10-06 22:22 — rolled back to the first Scaffold change (ff7ba658), at the user's word
Test of a93c25ef: the player walked off the edge of a y68 bridge (last block 250,68,183, next at y62). The user had
said beforehand to return to the version before "超爛" if this one was not as expected. The source of Scaffold.java and
ModuleDocs/ModuleDocsEn was rebuilt to that version (before-backup + `scaffold_edit.py` + the LOWEST comment + docs)
and checked: its Scaffold*/ModuleDocs* classes are **byte-identical** to those in
`backups/jars/Myau+UI_fixed.jar.pre-scaffold-held-look-20261006` (md5 ff7ba658). That jar is what gets installed.
Kept for later: the held-look / ledge-crouch / stabilized source in `backups/src/src-scaffold-stabilized-a93c25ef-20261006`
(zero REJECTs measured with the held look; the ledge crouch and stabilized aim did not hold the edge).
Open problem in ff7ba658: same-tick turn+click is judged along the old look (REJECT `ray miss`), and the silent
safe-walk clamp gives Grim Simulation/GroundSpoof on edges.
**Installed rollback** (game closed, gated): md5 ff7ba658, 62 mixins, one Myau jar. Replaced a93c25ef kept as backups/jars/Myau+UI_fixed.jar.a93c25ef-stabilized-20261006.

## 2026-10-07 — One-click bug report (Atlas "Report" button and `.report`)
Testers in the new Discord rarely know what to send. **Report** (Atlas header, left of HUD Editor) and the command
`.report` / `.bugreport` build one text block, copy it to the clipboard and save it as
`config/Myau/reports/report-<stamp>.txt` (`myau.util.BugReport`). It starts with two lines for the tester to fill
("What happened", "How to reproduce"), then a code block with: client version, ClickGUI style, Forge, Java, OS, max
memory, GPU and GL version, OptiFine (detected by `GameSettings.ofFastRender`) and Fast Render state, framebuffers /
shader support, display size, GUI scale, fps, server (or singleplayer) and ping, the other Forge mods, **every enabled
module with all its settings**, the last 25 FlagDetector lines, 15 place-log lines, 15 Clutch-log lines, and the last 40
game-log lines that are warnings, errors, stack traces or client / anticheat messages. **Other players' chat is never
included** (a [CHAT] line is kept only if it is from the client or an anticheat).
**Redaction** (`myau.util.Redactor`, pure, 7 tests in `RedactorTest`): session token and JWT-shaped strings → `<token>`;
own UUID → `<my-uuid>`, other UUIDs → `<uuid>`; 40+ char tokens; e-mails; the OS home folder (both slash styles) →
`<home>`; own name → `<me>`; tab-list names → `<player>` (whole words, case-insensitive, longest first); the OS account →
`<user>`; IPv4 → `<ip>` / `<lan-ip>` / `127.x.x.x`. A long paste becomes a message.txt in Discord automatically.
Build + 261 tests. **Installed** (game closed, gated): md5 b37a5991, 62 mixins. Backups: `backups/src/src-before-bug-report-20261007`,
`…-after-…`, `backups/jars/Myau+UI_fixed.jar.pre-bug-report-20261007`. Scaffold is unchanged (still the v1.2.0 version).

### 2026-10-07 — Report goes straight to Discord through a relay
The user chose the safest option: a **Cloudflare Worker relay** (`report-relay/` next to the mod, not in the mod) holds
the Discord webhook as a secret, so neither the jar nor GitHub carries it. Worker guards: POST+JSON, header
`X-Myau-Report: 1`, body ≤ 256 KB, report ≤ 200 KB and must contain "Myau Atlas bug report", 3 valid reports per IP per
10 min (Cache API, per location; junk does not use the allowance), mentions neutralised (`allowed_mentions: []`,
@everyone/@here broken, <@id> removed). Posts as a forum thread (`thread_name`), retrying without it for a plain channel;
the full report is attached as `report.txt`. Tested locally with Node 24 against a fake webhook: 403 / 400 / 200×3 /
429 / 405 as intended, file attached, mentions stripped.
Client: `BugReport.send(description, result)` builds and saves on the game thread, POSTs on a daemon thread
(`HttpURLConnection`, 8 s connect / 15 s read), and reports back on the game thread; on any failure it copies to the
clipboard instead. Relay address: `RELAY_URL` in BugReport (empty until deployed), overridable by one https line in
`config/Myau/report-relay.txt`. The Atlas **Report** button now sends; `.report [what happened]` sends with a
description, `.report copy` only copies. Built, 261 tests pass; not installed yet (waiting for the Worker address).
Relay deployed by the user (wrangler 4.148, Worker `myau-report`); a curl test report returned `sent`. The account
subdomain carried the player's name, so it was changed to `myau-atlas` (old address now dead). `RELAY_URL` =
`https://myau-report.myau-atlas.workers.dev/report`. **Installed** (game closed, gated): md5 e2767a0c, 62 mixins.
**Rate limit fixed (same day).** The first relay counted reports in the Cache API, which stores nothing on
`workers.dev` — so live, the limit did nothing (the local test had mocked a working cache). Now the counts are in a
**KV namespace** (`RATE`, bound in `report-relay/wrangler.toml`): key per IPv4 address, or per **IPv6
/64** (one connection can rotate addresses inside its /64), 3 valid reports per window, window = 10 min from the first
report (key expiry). Verified **live without posting**: the test machine's key was seeded as used up with
`wrangler kv key put`, a valid report then got HTTP 429 and nothing reached Discord; the key was deleted after.
Local tests: junk does not count; three addresses in one /64 share one allowance. Also set `workers_dev = true`,
`preview_urls = false` in wrangler.toml. Deployed version cb214f20.

### 2026-10-07 — Report asks first
The Atlas **Report** button now carries an amber warning triangle (drawn with Liquid lines, no font glyph needed) and
opens a confirmation dialog over the window instead of sending: a large warning sign, "Send a bug report?", what is
uploaded (versions, OptiFine, enabled modules and settings, latest flag / placement / Clutch logs, game warnings) and
that names, tokens and IPs are removed, then **Cancel** / **Send**. While it is open it takes every click and key:
Send or Enter sends; Cancel, Esc or a click outside the box closes it. `.report` in chat still sends directly (typing
the command is the confirmation). **Installed** (game closed, gated): md5 417810e3, 62 mixins.
Backup: `backups/src/AtlasClickGui.java.pre-report-confirm-20261007`.

## 2026-10-07 — Vape 4.21 / LiquidBounce nextgen study of Scaffold and KillAura; plan; S1
LiquidBounce nextgen was read from its Kotlin source; Vape 4.21 is described from its behaviour.
Main lessons: Vape's Scaffold is fully vanilla-shaped (camera turned in whole mouse counts, keys really pressed, the
game's own right-click only when the real crosshair is on a placeable face, a real sneak at edges); LiquidBounce uses
silent rotations but verifies every interaction by a raycast along the rotation being sent, picks a stable aim point
along the movement line, and crouches (Ledge) when the turn is not ready. Plan: Scaffold S1-S3, then KillAura K1-K4,
**one at a time, each tested in game before the next**.
**S1 (built, md5 ec6bf6f8; not installed — game running):** Scaffold `safe-walk` no longer clamps the player at the edge
without crouching (the cause of Grim Simulation/GroundSpoof measured 2026-10-06). It crouches for real when, on the
ground, the box shrunk by 0.2 a side, moved by this tick's motion and one block down touches nothing (Vape's test), and
keeps crouching for a random 100-200 ms after; input ×0.3 as vanilla's sneak key does, after the SILENT strafe remap.
`onSafeWalk` only confirms vanilla's crouch edge rule. Placement logic is unchanged (v1.2.0).
Backups: `backups/src/Scaffold.java.pre-S1-edge-sneak-20261007`, `…S1-edge-sneak-20261007`.
**Report rewritten LiquidBounce-first (same day), at the user's word ("看的是 liquid bounce").** Read in full: all 30
Scaffold files, all 11 KillAura files, and the aiming / clicking / target-finding layers they use. Facts worth keeping:
- LB decides the rotation at the **start of the tick** (RotationUpdateEvent → RotationManager.update, normalised to the
  mouse GCD); interactions in that tick use it and are verified by a world raytrace from the current eye
  (`verifyClick`: same clicked block, same face, legal height). Its OnTick timing even sends an extra PosRot before
  the interaction so the server has the look first — LB knows the server judges with the look it already has.
- Scaffold aim: Stabilized point (face trimmed 15 %, cut to the optimal line's side, nearest to the current look ray);
  optimal line from the last two placements or the support block keeping the lateral offset, 8 directions with 30°
  hysteresis; placement position predicted from the last 4 placements' offsets to the fall-off edge.
- LB Scaffold's default SafeWalk is the silent "Safe" clamp (fine on 1.21, flagged on 1.8 Grim as measured here);
  its OnEdge mode works on inputs (stop / invert / centre for 1-2 ticks, optional sneak) and is simulatable.
  Ledge crouches when the turn needs ≥1 more tick; GodBridge's ledge simulates the next tick and jumps/sneaks if
  that look cannot place. GodBridge looks: straight = move yaw ±45° (side of the block), pitch 75.7; diagonal = move
  yaw, pitch 75.6.
- KillAura: scan range = reach + random 2-3 blocks (aim before reach); attack only if the current rotation raycasts
  the target in range; Human clicker = log-normal intervals (σ 0.45, mean on a per-combo CPS drawn from the range),
  ≤2 per tick, reset after 250 ms idle; Snap timing turns only when the clicker will click on arrival; Lazy rotation;
  ShortStop 3 %/1-2 t; Fail 3 %/5-10°.
Plan re-ordered to follow LB: S1 (done, awaiting test), S2 no click on a big-turn tick, S3 Stabilized + optimal line,
S4 LB GodBridge; K1 attack after all rotations, K2 scan range, K3 Human clicker, K4 Lazy/ShortStop/Fail, K5 Snap.
**S1 installed** (game closed, gated): md5 ec6bf6f8, 62 mixins. Replaced jar: backups/jars/Myau+UI_fixed.jar.pre-S1-20261007.

### 2026-10-07 18:57-19:00 — S1 measured on test.ccbluex.net: no Scaffold flags
Seven Scaffold sessions: **zero GrimAC flags** (no Simulation, no GroundSpoof — 51 Simulation before S1), place log 201
placements, **zero REJECT**. The place log's own last-sent-look ray still reads "miss" or another face on 135 of them,
yet the server took every block — so that ray is not what Grim judges by here; do not use it as evidence of a bad
click without a REJECT or a GrimAC line. The one LAGBACK (18:58:49) followed digging, not Scaffold.
S2 (no click on a big-turn tick) was meant for REJECTs; with none measured it is **not done** unless they come back.

### 2026-10-07 — S3: stabilized aim (built, md5 3eb44055; not installed — game running)
The user reports Scaffold still draws silent flags. Evidence in the S1 place log: 135 of 201 clicks were not on the
clicked face along the look the server already had (83 miss, 52 another face) — accepted by Grim, but exactly what a
stricter check catches. S3 changes **only the aim** (S1's edge crouch and the click timing stay):
1. For the turning modes, when the last reported look already places a block in a wanted cell (`lookHit`), that look is
   kept — no turn, the click uses the look the server knows (LiquidBounce's Stabilized intent).
2. Otherwise the aim point is the one with the least turn from the **sent** look (it was from the last target), and aim
   points stay off the outer 15 % of the face (`aimOffsets`, LiquidBounce's trimFace).
Expected: far fewer "ray miss / side" lines in the place log; no new Grim flags. Backups:
`backups/src/Scaffold.java.pre-S3-stabilized-20261007`, `…S3-stabilized-20261007`.

- 2026-10-07 installed S3 (md5 3eb44055cba673b5bab3998458c7733e) as mods/Myau+UI_fixed.jar with the game closed; previous jar (S1, ec6bf6f8) saved as backups/jars/Myau+UI_fixed.jar.pre-S3-20261007.

## 2026-10-07 S3 test results and S4: LiquidBounce GodBridge for the GODBIRGDE mode

**S3 test (19:08–19:13, S3 jar 3eb44055).** The user reports that Grim flags were few but Polar flagged a lot, and that the S1 test was too short to count as clean. The Grim log for this session has RotationPlace x17, AirLiquidPlace x10, MultiPlace x4 and NoFall x5, almost all while towering (face up, rot 229.5) or jump-bridging. There are also two Simulation/GroundSpoof bursts at 19:12:16 and 19:12:33, during knockback and tower.

Polar is the anticheat on play.pika-network.net (servers.txt fingerprint). Its alerts are not shown to the player, so they are not in any log here. The Pika placement log (19:10:30–19:11:20, 140 placements) shows:
- **Settings in use:** rotations=GODBIRGDE, turn-speed 180, sprint VANILLA, safe-walk off, keep-y NONE, and the player jumping while bridging.
- **Large turns on click ticks:** 30–37 degrees every few placements. The old GODBIRGDE case only set the yaw while `!canRotate`; the face loop then re-aimed at the block every tick.
- **Long reaches:** placements at 4.4–4.5 blocks from the eye while jumping.
- **Look did not hit the face:** about 75% of clicks were "ray miss/side".

**S4.** `rotations=GODBIRGDE` now behaves like LiquidBounce nextgen's ScaffoldGodBridgeTechnique:
- **Look** (`godBridgeRotation`):
  - movingYaw = round((movement yaw + 180) / 45) * 45.
  - Straight: yaw = movingYaw ± 45, pitch 75.7. The side follows LB's isOnRightSide test, flipped when leaning off the block with air ahead.
  - Diagonal: yaw = movingYaw, pitch 75.6.
  - No keys pressed: yaw = floor(aimed yaw / 90) * 90 + 45, pitch 75.
- **Aim:** the look is not aimed at the block. hitVec is only a placeholder; onUpdateClick still clicks only where the sent look lands on a wanted cell (lookHit), so a look that misses clicks nothing. The S3 "keep the current look" shortcut is skipped in this mode. The look is reached with the normal turn-speed and humanize stepping. Once there, RotationEngine.step returns it unchanged (total < 0.05), so the look holds still the way LB's does.
- **Ledge** (`godBridgeLedge`, in onMoveInput): this mode only, whatever safe-walk is set to.
  - The player crouches for the tick (real sneak, input × 0.3, as in S1) when:
    - the box after this tick's motion, shrunk by 0.2 per side, has nothing under it; and
    - the look sent this tick (recorded in onUpdateClick), traced from the eye after the move, would not land on a valid side face (not UP) of a solid block, with the new cell replaceable, on the layer under the feet, and under the moved box.
  - It always crouches at the edge with fewer than 3 blocks in hand (LB forceSneakBelowCount).
  - LB's default ledge action is JUMP; this uses LB's SNEAK action instead, because a real crouch is what S1 measured clean on Grim.
- **Tower and other modes:** unchanged.

**Files.**
- Source: backups/src/Scaffold.java.pre-S4-godbridge-20261007, Scaffold.java.S4-godbridge-20261007.
- Config: backups/config/default.json.pre-S4-20261007.
- Build: md5 75274f48358cea975fd07689ac0da9a9.

**What to look for when testing** with GODBIRGDE on flat bridging:
- "rot" in places-*.txt near 0 on most placements;
- "dist" around 2–3, not 4.5;
- "ray face" being most of the placements;
- no new Grim flags;
- no falling at the edge.

- 2026-10-07 installed S4 (md5 75274f48358cea975fd07689ac0da9a9) with the game closed; S3 jar saved as backups/jars/Myau+UI_fixed.jar.pre-S4-20261007. Install checks: 62 classes under myau/mixin, one Myau jar in mods.

### 2026-10-07 S4b: GodBridge without the crouch
The user wants GodBridge with no crouching. The S4 ledge crouch has been removed: `godBridgeLedge`, its call in onMoveInput, and the `sentLookYaw`/`sentLookPitch` fields.

GODBIRGDE now does only two things:
- holds LiquidBounce's fixed look;
- clicks when that look lands on a wanted face.

No ledge action is taken. S1's edge crouch still runs only when safe-walk is turned on, and the user has it off.

**Files.**
- Backups: backups/src/Scaffold.java.pre-S4b-nosneak-20261007 and Scaffold.java.S4b-nosneak-20261007.
- Build: md5 9ec003ec9811fbe2dd324c28e48a7443. Not installed at first, because the game was running.
- 2026-10-07 installed S4b (md5 9ec003ec9811fbe2dd324c28e48a7443) with the game closed; the S4 jar was saved as backups/jars/Myau+UI_fixed.jar.pre-S4b-20261007. Install checks: 62 classes under myau/mixin, one Myau jar in mods.

## 2026-10-07 — v1.3.0 pushed to GitHub
The working tree was synced into `../OpenMyau-Plus-release` with the same anonymisation as before; line endings are preserved.

**Changes in this release:**
- Scaffold: S1, S3, S4b.
- The bug report: BugReport, Redactor, RedactorTest, ReportCommand, and the AtlasClickGui dialog.
- Myau.java registers `.report`.
- ENGINEERING-NOTES: Vape notes reworded; REFERENCE-SCAFFOLD-KILLAURA.md not published; KV id removed.

**Build and push:**
- Release folder build: 261 tests pass, 62 mixin classes.
- Commit 6d07f28 and tag v1.3.0 pushed to main.

**Release:**
- Asset: `../release-assets/Myau-Atlas-v1.3.0.jar`, sha256 eb6f87487a936024f7ae31457d53cc9447c923b805089f849524d3d4d8186579.
- Release notes: `../release-assets/RELEASE-NOTES-v1.3.0.md`.
- The built-in browser is not signed in to GitHub, so the user creates the release page and uploads the jar (27 MB).

### 2026-10-07 S4c: GodBridge jumps at the ledge and aims at the edge
**User, after testing S4b:** GodBridge is close. It should jump by itself, it sometimes falls after a few blocks, and it
should still aim at the edge properly. The 19:23 log: 1583 placements, most with rot 0.1–0.8 (the fixed look holds).

**1. Ledge with JUMP** (`godBridgeLedge`, LiquidBounce's default ledge action). It applies in GODBIRGDE mode, on the
ground, while moving and not sneaking. The player jumps (`movementInput.jump`) when:
- after this tick's motion, the box shrunk by 0.2 per side has nothing under it; and
- the look sent this tick (`sentLookYaw/Pitch`, recorded in onUpdateClick), traced from the moved eye, would not
  place a valid block (not on an UP face, cell replaceable, on the layer under the feet, under the moved box).

There is no jump with Jump Boost II or higher; LB skips JUMP when the apex is 2 blocks or more. There is no crouch.

**2. Edge aim.**
- The face loop now measures its least turn from the GodBridge fixed look (`refYaw/refPitch`), not from the sent
  look.
- If the fixed look's lookHit misses from here, but an aim point on the face is within `GODBRIDGE_EDGE_AIM`
  (25°, |yaw| + |pitch|) of the fixed look, that point is used.
- Otherwise the fixed look stays.
- Other modes are unchanged: refYaw/refPitch fall back to the sent look.

**Files.**
- Backups: backups/src/Scaffold.java.pre-S4c-jump-edge-20261007 and Scaffold.java.S4c-jump-edge-20261007.
- Build: md5 11035aba6f1db3b2caf9fd6cf875355b.
- 2026-10-07 installed S4c (md5 11035aba6f1db3b2caf9fd6cf875355b) with the game closed; the S4b jar was saved as backups/jars/Myau+UI_fixed.jar.pre-S4c-20261007. Install checks: 62 classes under myau/mixin, one Myau jar in mods.

## 2026-10-07 — KillAura K1: attack after every module's rotation
**Context.** Scaffold S4c is installed. The user then reported slow single-player loads and one crash. Neither was
Myau:
- Java's IPv4 route to Mojang/Microsoft (Azure Front Door) timed out, while IPv6 and Google over IPv4 worked.
- `fillProfileProperties` on the client thread waited 15 s, twice per world load.
- The fix the user applied was WARP or `-Djava.net.preferIPv6Addresses=true`.

The user then asked to start KillAura (plan K1–K5 in REFERENCE-SCAFFOLD-KILLAURA.md).

**Why.** KillAura runs at UpdateEvent LOW and attacked inside that handler, judging the hit (aimedAt) along
`event.getNewYaw()` as it stood then. Later handlers can still change the look sent in the movement packet:
- Displace at LOWEST, priority 100;
- Speed at LOW, priority 1, registered after KillAura (ties go to the last caller).

When that happens, the C02 goes out judged along a look that is never sent. Grim judges the hit by the next movement
packet's look, so the hit is dropped or flagged. LiquidBounce attacks after all rotations.

**Change.**
- In the NONE, Legit, Silent, LockView and Hypixel (Raven) rotation modes, onUpdate no longer calls performAttack. It
  records `deferredAttack`, `deferredSwap` and `deferredBlocked`.
- `KillAura.afterRotations(event)` runs them. MixinEntityPlayerSP.onUpdate calls it right after
  `EventManager.call(PRE)`, when no module can turn the look any more.
- The call does performAttack along the final `getNewYaw/Pitch`, then the same block handling as before
  (`finishAttack`: interactAttack or sendUseItem, the blink reset).
- The packet order within the tick is unchanged: unblock, then attack, then block, all before the C03.
- The LiquidBounce and Advanced modes keep their inline attack, because they judge along their own rotation.
- `deferredPending` is cleared at the start of each PRE, so a hit held across a skipped tick never fires late.

**Files.**
- Backups: backups/src/KillAura.java.pre-K1-20261007 and KillAura.java.K1-20261007;
  MixinEntityPlayerSP.java.pre-K1-20261007 and MixinEntityPlayerSP.java.K1-20261007.
- Build: 261 tests pass, md5 3062d1d72c1523c90601eea1a275cc04.

### 2026-10-07 KillAura K2: scan range (turn before reach, no swing)
The user said to continue without testing K1 first. K1 is built but not installed, because the game was running.

**Before.** The aim only started turning once the target's box was inside SwingRange (3.3 for the user). From
standstill it then had to catch up within reach, at the turn-speed cap, and the first clicks were judged off target.

**LiquidBounce.** The target is kept and the aim turned within reach plus ScanExtraRange (2–3 blocks). Attacks happen
only within reach.

**Change.**
- **New setting `ScanExtra`** (default 2.5, 0–4): blocks beyond SwingRange at which the aim turns. 0 restores the old
  behaviour.
- **Per-target roll:** `scanRoll` = ScanExtra ± 0.5, drawn when a new target is picked.
- **Targeting:**
  - `isInRange` also accepts candidates within SwingRange + ScanExtra + 0.5.
  - A target is kept while its box is within `scanRange()`. It used to be kept only within SwingRange.
  - It is given up for someone in SwingRange, as it already was for someone in AttackRange.
- **Rotation:** all rotation branches and the smooth-back / Advanced "lost target" checks use the scan range.
- **Attack:** `attack` is forced false unless the box is within SwingRange, so nothing swings at the air from scan
  range. performAttack swings before it checks the aim. Blocking runs as before.
- **ModuleDocs / ModuleDocsEn:** ScanExtra added to the 出手 group, with help text in both languages.

**Files.**
- Backups: backups/src/KillAura.java.pre-K2-20261007 and KillAura.java.K2-20261007; ModuleDocs(.En).java.pre-K2-20261007.
- Build: 261 tests pass, md5 8daa533ccf4a0eee2b28ff7ed48255dd. This jar includes K1.

### 2026-10-07 KillAura K3: "Human" CPS mode (LiquidBounce HumanClickTiming)
**Change.** New `CPS Mode` option **Human**; the user's default "Normal" is unchanged. MinCPS and MaxCPS are shown
for both Normal and Human.

`humanInterval()`:
- **Combo rate:** a combo ends after more than 250 ms without a click (LB ClickPlan IDLE_MS). Each combo draws one
  rate evenly from MinCPS–MaxCPS.
- **Interval:** exp(mu + 0.45·N(0,1)), with mu = ln(1000/rate) − 0.45²/2, so the mean interval matches the rate.
  It is clamped to 10–1000 ms.
- **Not ported:** LB's two clicks per tick. The existing countdown (`attackDelayMS`, remainder carried) allows at
  most one attack per tick, and a short interval is carried into the next tick.

ModuleDocs and ModuleDocsEn: the CPS Mode help now explains Human.

**Files.**
- Backups: backups/src/KillAura.java.pre-K3-20261007, KillAura.java.K3-20261007 and ModuleDocs(.En).java.pre-K3-20261007.
- Build: 261 tests pass, md5 c740661e28a7b1ba097a1a1824c1516e. This jar includes K1, K2 and K3.
- 2026-10-08 installed K1+K2+K3 (md5 c740661e28a7b1ba097a1a1824c1516e) with the game closed; the S4c jar was saved as backups/jars/Myau+UI_fixed.jar.pre-K123-20261007. Install checks: 62 classes under myau/mixin, one Myau jar in mods.
- 2026-10-08 Synced the release folder and pushed commit 2ba6046 (S4c + K1-K3; 261 tests pass, 62 mixin classes). No tag or release was made for it.

## 2026-10-08 — Scaffold S4d (aim instead of jump), KillAura K4/K5, LEGIT auto-block fix (K6)
**User after playing K1–K3.**
- Auto-block "一直 block，導致速度很慢"; the user said to continue and fix it along the way.
- Scaffold should not keep jumping; it should aim at the block and place it.

**S4d (Scaffold, GODBIRGDE).**
- The S4c ledge jump is removed: `godBridgeLedge`, its onMoveInput call, `sentLookYaw/Pitch`, and two now-unused imports.
- The edge-aim limit `GODBRIDGE_EDGE_AIM` (25°) is removed. Whenever the fixed look misses the face, the aim goes to the
  face point nearest the fixed look, measured from the fixed look, and places there.
- The fixed look is still kept whenever it places by itself.
- Line endings: Scaffold.java had 2 stray CRLF lines in an LF file, from an earlier sed. They were normalised to LF.
- Backups: backups/src/Scaffold.java.pre-S4d-aim-20261008 and Scaffold.java.S4d-aim-20261008.

**K4 (KillAura, LiquidBounce rotation processors).** These apply to the Legit, Silent and LockView aim and are all off
by default:
- `LazyRotation`: no turn while the sent look already hits (aimedAt). This is LB findRotation with lazyRotation.
- `ShortStop`: a 3% chance per tick to move only 0–10% of the way for 1–2 ticks (LB ShortStopRotationProcessor).
- `FailAim`: a 3% chance per tick to add a 5–10° yaw and 0–2° pitch offset for 1–4 ticks (LB FailRotationProcessor).
  The offset is applied to this tick's aim step, after stepTowards, without LB's failFactor term.
- All three run in `processAim`, after stepTowards / getRotationsToBox and before the tremor and GCD steps.

**K5 (Snap rotation timing).** New setting `RotationTiming`, Normal or Snap, default Normal. In Snap, `snapHold` skips
the turn toward the target while:
- the click is further away than the turn needs, i.e. ceil(attackDelayMS / 50) > ceil(angle to box centre /
  MaxTurnSpeed), at least 1; and
- the look is not already on the target.

While held, nothing sets the rotation, so the existing smooth return (`returning` / stepBack) eases the look toward the
camera. This is LB's SNAP: `if (!clicker.willClickAt(ticks)) return`.

**K6 (LEGIT auto-block).**
- **Cause:** case 7 blocked whenever `hasValidTarget()` was true, which means anyone within AutoBlockRange (6 for the
  user). Chasing a target 3.3–6 blocks away was therefore done at blocking speed.
- **Now:**
  - It blocks only with the current target inside SwingRange.
  - Otherwise it calls stopBlock() if the client is still blocking and the player is not digging or placing, then resets
    blockTick, so the chase runs at full speed.
  - The block/unblock alternation within reach is unchanged.
- **Other auto-block modes** are untouched.

**ModuleDocs and ModuleDocsEn:** RotationTiming, LazyRotation, ShortStop and FailAim were added to the 轉頭 group, with
help text in both languages.

**Files.**
- Backups: backups/src/KillAura.java.pre-K456-20261008, KillAura.java.K456-20261008 and ModuleDocs(.En).java.pre-K456-20261008.
- Build: 261 tests pass, md5 454496fbb64d7bc1290dc2521b706430. This jar includes S4d and K1–K6.
- 2026-10-08 installed S4d + K1–K6 (md5 454496fbb64d7bc1290dc2521b706430) with the game closed; the previous jar was saved as backups/jars/Myau+UI_fixed.jar.pre-K456-20261008. Install checks: 62 classes under myau/mixin, one Myau jar in mods.

## 2026-10-08 — NoItemRelease (from Slinky), and the HitSelect question
**User:** asked whether a Rise-style Hit Select in KillAura (K7) would clash with the existing HitSelect module, then asked
to add NoItemRelease, using Slinky's `NoItemRelease.java`. The user placed that file in the instance folder; a copy is in
backups/src/NoItemRelease.java.from-user-20261008.

**HitSelect.** There is no clash today, and no interplay either:
- HitSelect ACTIVE (the user's mode) only filters `LeftClickMouseEvent`, i.e. the player's own clicks.
- KillAura sends its own C02 and cancels the left click, so ACTIVE never sees an aura hit.
- The other three modes (SECOND, CRITICALS, W_TAP) act on the C02 packet itself. They would cancel aura hits that were
  already swung and applied locally.

K7 is therefore planned as "KillAura asks HitSelect ACTIVE before a hit", not as a second implementation.

**NoItemRelease.** The module file is used unchanged.
- **What it does:** it drops the vanilla C07 RELEASE_USE_ITEM that the game sends when right click is let go, so the
  server keeps the item in use. The client stops as usual.
- **Modes:** CONSUMABLE (eat/drink, the default), SWORD (block) and ALL. Bows and rods are never covered.
- **Wiring:**
  - `MixinPlayerControllerMP.onStoppedUsingItem`:
    - HEAD: if CancelUseEvent was not cancelled, call `beginVanillaRelease()`. It now `return`s after a cancel.
    - New RETURN inject: `endVanillaRelease()`.
  - `MixinNetworkManager.sendPacket(Packet)`:
    - First call, for client packets: `claimVanillaRelease(packet)`.
    - After `playerStateManager.handlePacket` and before Blink/Lag: if `dropVanillaRelease(packet)`, cancel. Atlas's own
      bookkeeping therefore still sees the release.
  - Registered in Myau.java and in ModuleCategories MOVEMENT next to NoSlow. ModuleDocs and ModuleDocsEn descriptions
    added. The mixin class count is unchanged (62).
- **Checked for bugs:**
  - **Module-sent releases** carry a `myau.module.modules` frame on the stack, so they are never claimed:
    - KillAura.stopBlock and NoSlow send C07 directly.
    - FastBow calls onStoppedUsingItem itself, and bows are excluded anyway.
  - **KillAura blocking:** KillAura.onCancelUse cancels the whole vanilla release while it blocks, so the window never
    opens.
  - **Window lifetime:** it is closed at RETURN and on every TickEvent.
  - **A claim that is never dropped** (a listener cancelled the packet first) is forgotten when the window closes.
  - **Flushing:** while LagManager is flushing, the drop is skipped and the release goes out.
- **Risk, not a bug:**
  - With the release withheld, the server and Grim or Polar keep the player "using an item". Full-speed movement or
    sprint after letting go can then be flagged (Simulation / NoSlow), until the item finishes or the slot changes.
  - SWORD and ALL make this likely while fighting. CONSUMABLE is the safest mode.
- **Tests:** new `myau.util.NoItemReleaseTest`, 5 tests, all passing:
  - inside the window: dropped once, by instance, first release only;
  - outside the window: nothing dropped;
  - other C07 actions: never claimed;
  - claim forgotten when the window closes.
  It lives in myau.util because a test frame in myau.module.modules would count as a module caller.

**Files.**
- Backups: backups/src/*.pre-NoItemRelease-20261008 and *.NoItemRelease-20261008.
- Build: md5 fbb75829db6cb59721e535d8b35327f8. This jar includes S4d and K1–K6.
- 2026-10-08 installed the NoItemRelease build (md5 fbb75829db6cb59721e535d8b35327f8) with the game closed; the previous jar was saved as backups/jars/Myau+UI_fixed.jar.pre-NoItemRelease-20261008. Install checks: 62 classes under myau/mixin, one Myau jar in mods.

### 2026-10-08 NoItemRelease: safe-release (the user saw "NoSlow 嚴重被抓")
**Measured.** test.ccbluex.net legacy (Grim), 19:04:17–19:04:34: 133 × `failed NoSlow`, plus LAGBACK ×4–10 bursts at
sprint speed. This is the predicted risk, not a defect:
- With the vanilla release dropped, Grim keeps the item in use.
- It expects the 0.2× input and no sprint.
- The client walks and sprints at full speed.

**Fix.** New setting `safe-release`, default on.
- The module records `withheld` when it drops a release.
- `onUpdate`: an UpdateEvent PRE handler at HIGHEST, `whenDisabled`. While a release is withheld, it sends the C07
  release via PacketUtil before that tick's movement packet, in either case:
  - the module is off; or
  - safe-release is on and the player moves or sprints (MoveUtil.isForwardPressed, isSprinting, or the sprint key).
- **Order within a tick:** the release key is handled in runTick before the player's update, so a release while moving
  is resent in the same tick, before the C03.
- **Not claimed:** the resent release is sent from a module frame, outside the vanilla window.
- `withheld` clears on:
  - any C09 slot change;
  - a C08 use (direction 255);
  - any release;
  - leaving the world.
- **Effect:** the release now stays withheld only while standing still.
- **safe-release off** gives the original Slinky behaviour.

**Files.**
- Help text: ModuleDocs and ModuleDocsEn.
- Backups: backups/src/NoItemRelease.java.pre-safe-release-20261008 and NoItemRelease.java.safe-release-20261008.
- Build: 266 tests pass (including NoItemReleaseTest), md5 e87f4450e4a1d07385ac299090deadf6.

### 2026-10-08 NoItemRelease decision; KillAura K7 (HitSelect for aura hits) and K8 (LiquidBounce auto-block)
**NoItemRelease.** The user chose "A": keep safe-release as built (full speed, release resent on the first moving tick).

**K7.** KillAura now asks the user's HitSelect (mode ACTIVE) before an aura hit:
- **Refactor:** HitSelect's ACTIVE rule moved out of `onClick` into `activeDrops(target)`, so a hand click and an aura
  hit follow one rule. The rule covers chance, KB_REDUCTION/CRITICALS after own knockback, moving-towards, and the
  hurt-time opening with its spacing. `onClick` keeps its old behaviour, target null included.
- **Aura entry point:** new `dropsAuraHit(target)`. It also counts drops.
- **KillAura side:** `performAttack` checks `hitSelectHolds()` after the delay and other-actions checks and before the
  swing. A held click adds its interval (`attackDelayMS += getAttackDelay()`), the way a dropped hand click is still a
  click, so the CPS rhythm and the chance roll per click are unchanged. There is no swing, no hit and no sprint
  slowdown.
- **Scope:** it covers Legit/Silent/LockView/Hypixel/LiquidBounce rotations (performAttack). The Advanced mode's own
  attack path is not covered.
- **Answer to the user's question:** no conflict. Before K7, ACTIVE never saw aura hits. The other HitSelect modes act
  on C02 and would still cancel aura hits after the swing.

**K8.** New auto-block mode `LiquidBounce` (index 10), using LB KillAuraAutoBlock with its defaults (Reblock 0,
StopUsingItem):
- **Target in SwingRange, on a tick the click is due** (attackDelayMS ≤ 0): stopBlock if blocking, then swap = true.
  After the deferred attack, finishAttack therefore sends interact + use, which blocks again in the same tick.
- **Between clicks:** block if not blocking.
- **Out of SwingRange:** stopBlock, so the chase runs at full speed.
- **Packet order in a click tick:** C07 release (onUpdate, LOW), C02 attack (afterRotations), C02 interact + C08 use.
- **Note:** this is LB's same-tick unblock/hit/reblock. Strict 1.8 anticheats may dislike it, which is why LEGIT stays
  the default choice.

**ModuleDocs and ModuleDocsEn:** auto-block help describes LiquidBounce, and the HitSelect description notes that
ACTIVE also decides KillAura hits.

**Files.**
- Backups: backups/src/*.pre-K78-20261008, KillAura.java.K78-20261008 and HitSelect.java.K78-20261008.
- Build: 266 tests pass, md5 1026e00f18524fcf3d5df869e2cc9c20. This jar includes safe-release, NoItemRelease, S4d and K1–K8.
- 2026-10-08 installed K7/K8 + safe-release (md5 1026e00f18524fcf3d5df869e2cc9c20) with the game closed; the previous jar was saved as backups/jars/Myau+UI_fixed.jar.pre-K78-20261008. Install checks: 62 classes under myau/mixin, one Myau jar in mods.

### 2026-10-08 KillAura K10 (Raycast), K11 (ExitClick); K9 dropped — KillAura plan complete
The user asked to finish KillAura, after asking whether KeepSprint or W-tap is better. The answer given was W-tap
(SprintReset LEGIT):
- On 1.8 the server clears sprint after a sprint hit, so KeepSprint keeps only client speed, not the knockback.
- Grim's Simulation catches the missing slowdown.

**K9 dropped.** A built-in KeepSprint would duplicate the existing KeepSprint module, and the recommendation is
SprintReset.

**K10 — `Raycast`** (None or Enemy, default Enemy). This is LB TRACE_ONLYENEMY.
- In `afterRotations`, before the hit, `raycastRetarget` traces the final look within AttackRange against living,
  collidable entities (border-grown boxes; eyes inside counts as distance 0).
- If the first one is another valid target (isValidTarget: team, bot, FOV, walls), it becomes `target` and takes the
  hit.
- Blocks are not traced; ThroughWalls already governs walls.
- Only the deferred path (K1 modes) uses it.

**K11 — `ExitClick`** (default on). This is LB IgnoreWhenExitingRange, adapted to 1.8, which has no weapon cooldown.
performAttack lets a click through up to one tick early (attackDelayMS ≤ 50) when all of these hold:
- the target is within AttackRange now (box distance from the eyes);
- it would be out of range next tick, with both entities extrapolated by this tick's movement;
- target.hurtTime ≤ 7.

The positive remainder is kept, so the interval after the early click is unchanged and the average CPS does not rise.

**ModuleDocs and ModuleDocsEn:** both settings were added to the 出手 group, with help text in both languages.

**Plan status.** K1–K8, K10 and K11 are done, and K9 is deliberately skipped. Defaults chosen so the user's current
behaviour holds:
- auto-block stays LEGIT;
- CPS Mode stays as set;
- all K4/K5 options are off.

**Files.**
- Backups: backups/src/KillAura.java.pre-K1011-20261008, KillAura.java.K1011-20261008 and ModuleDocs(.En).java.pre-K1011-20261008.
- Build: 266 tests pass, md5 cd888b54e48a8ec68d944a5c311cba13.
- 2026-10-08 installed K10/K11 (md5 cd888b54e48a8ec68d944a5c311cba13) with the game closed; the previous jar was saved as backups/jars/Myau+UI_fixed.jar.pre-K1011-20261008. Install checks: 62 classes under myau/mixin, one Myau jar in mods.

## Backlog (2026-10-08): user request, NOT started
- **HUD "liquid glass" + rounded shapes** (DONE 2026-10-08, see the HudGlass section below). A user asked for the on-screen HUD elements to get the Atlas ClickGUI's
  Liquid Glass look (Liquid / Glass blur panels) and round shapes. The HUD elements include WaterMark, ArrayList/HUD,
  ArmorHUD, EffectsHUD, TargetHUD, KeyStrokes, PotionHUD, InventoryHUD and DynamicIsland.
- The owner said to record it only. Do not start until asked.

## 2026-10-08 — HudGlass: the on-screen HUD in Liquid Glass with rounded panes (backlog item, now done)
**User:** "做一下hud吧". This is the backlog request from players: HUD elements in the Atlas menu's Liquid Glass, with
round shapes.

**Design.** One shared renderer, one switch, and every HUD falls back to its old drawing when the switch is off.
- **Module `HudGlass`** (Render, enabled by default):
  - settings: `radius` 6 (0–12), `opacity` 55%, `blur` on, `rim` on, `shadow` on;
  - a Render2DEvent handler at HIGHEST calls `HudPanel.newFrame()`.
- **`myau.ui.impl.clickgui.atlas.HudPanel`** is public, the HUDs' door into the package-private `Liquid` renderer.
  - `panel(x, y, x2, y2[, dropShadow[, fade]])` draws, in order:
    - an optional shadow;
    - the Liquid pane, i.e. the world blurred and refracted at the rim, with a dark tint at `opacity` (light tint in
      light mode);
    - an optional rim.
  - `fill(...)` draws a rounded plain fill at the pane radius, used for pressed keys, inventory slots and the damage
    flash.
  - **Per-frame copy:** the world is copied and blurred once a frame (`Liquid.beginFrame`), lazily, by the first pane
    with blur on.
  - **Transform:** the modelview is read once per pane (glGetFloat), so panes land correctly inside each HUD's own
    translate/scale. That is a handful of reads per frame.
  - **GL state:** saved and restored exactly (texture2D, blend, alpha test, cull, depth). HUDs keep drawing their own
    quads after the background with whatever state they had set up.
  - **Liquid state:** Liquid's alpha and light are saved and restored, so the open ClickGUI is unaffected.
  - Any throwable is swallowed, so the text still draws.
- **`Liquid.ensureCompiled()`** is new. It compiles the shaders and sets the framebuffer size without copying the
  screen, for panes with blur off before any frame has begun.

**HUDs converted** (old drawing kept in the `else`):
- ArmorHUD, EffectsHUD, PotionHUD, PlayerList, ClosestPlayerHUD, FKCounter.
- LegitHUD: rows; keys get an accent fill when down.
- KeyStrokes: a white fill when down.
- InventoryHUD: box plus rounded slot fills.
- HUD module list (MYAU interface): one pill per row (0.5 px apart, no drop shadow); the old blur/glow passes are
  skipped.
- WaterMark: a pane behind every style from `watermarkArea()`; WeedHack's stacked boxes are skipped.
- WaterMark2, Hotbar, Statistics, FPScounter, DynamicIsland.
- Notifications: the classic style, faded with the entry.
- TargetHUD: the DEFAULT style and the Myau+ style (its colour/outline layers give way to the pane, and the damage flash
  is drawn as a red fill over the glass).

**Not converted:** the other 13 TargetHUD styles, and HUD's CREIDA interface. Each is a designed look of its own.

**Files and build.**
- Docs: ModuleDocs and ModuleDocsEn, the HudGlass description and its 5 settings.
- Registration: Myau.java; ModuleCategories RENDER.
- Backups: backups/src/pre-hudglass-20261008/ (before) and backups/src/hudglass-20261008/ (after).
- Build: 266 tests pass, 62 mixin classes, md5 b0e3eea8411a092197a16576147c1888.
- 2026-10-08 installed HudGlass (md5 b0e3eea8411a092197a16576147c1888) with the game closed; the previous jar was saved as backups/jars/Myau+UI_fixed.jar.pre-hudglass-20261008. Install checks: 62 classes under myau/mixin, one Myau jar in mods.

### 2026-10-08 HudGlass CLEAN style (the owner found Liquid Glass ugly)
**User.** Called the glass HUD ugly and sent a LiquidBounce nextgen "jellobounce" screenshot as the look wanted:
- flat dark translucent panes with small corners (about 4);
- no bevel, refraction, rim or shadow;
- module-list rows as one flush stepped slab with square rows;
- compact info panel, TargetHUD and key boxes.

**Change.**
- **`HudGlass.style`:** new setting, CLEAN (default) or LIQUID_GLASS. Defaults are now radius 4 and opacity 60. blur, rim
  and shadow are shown only in LIQUID_GLASS.
- **HudPanel, CLEAN:** `panel()` draws `flat()`, a single Liquid.rect rounded fill. The tint is the opacity over near
  black (0x07080B), or near white in light mode. There is no frame copy, so CLEAN costs no blur pass.
- **`HudPanel.row()`:** used by the module list. CLEAN draws a square-cornered flush slab; LIQUID_GLASS draws the earlier
  pill, inset 0.5.
- **Config:** HudGlass had not been saved to config yet, so the new defaults apply on first launch.
- **Docs:** ModuleDocs and ModuleDocsEn explain `style`.

**Files.**
- Backups: backups/src/hudglass-clean-20261008/.
- Build: 266 tests pass, md5 0f32595ac2959acdc9839d0f175a5a7c.
- 2026-10-08 installed HudGlass CLEAN (md5 0f32595ac2959acdc9839d0f175a5a7c) with the game closed; the previous jar was saved as backups/jars/Myau+UI_fixed.jar.pre-hudclean-20261008. Install checks: 62 classes under myau/mixin, one Myau jar in mods.

## 2026-10-08 — The HUD's default look is LiquidBounce's JelloBounce theme; HudGlass removed
**User:** "去看 liquid bounce 的 jello bounce theme 怎麼做 hud 跟 target hud 的，不要多一格 hud class，直接改預設".

**Source.** github.com/CCBlueX/LiquidBounce-Theme-JelloBounce, a CCBlueX-maintained fork of larryngton2/jellobounce.
- It was shallow-cloned into the session scratchpad for reading only; nothing from it is in the jar.
- Only measurements and colours were taken, from colors.scss, ArrayList, Effects, Key, Notification, TargetHud,
  HealthProgress and Watermark .svelte.
- **Values read:**

| Element | Value |
|---|---|
| `$background-color` | black |
| `$opacity` | 0.45 |
| `$primary-shadow` | 0 0 50px rgba(0,0,0,.5) |
| ArrayList rows | square corners, flush, 5×7 padding, tag #AAAAAA |
| Effects | one block with 12px outer corners |
| Key | 7px corners; active rgba(#2e2e2e, .7) at 95% scale, text #d3d3d3 |
| Notifications | 12px corners |
| TargetHud card | 250×79, 12px corners |
| TargetHud avatar | rounded 8, red .4 damage wash for 250 ms |
| TargetHud text | name 20px; heart and health 20px in #c8c8c8; Winning/Losing/Draw 15px in green/red/orange at grayscale 50% |
| TargetHud bar | rgba(0,0,0,.2) under a #c8c8c8 thumb, #646464 for health just lost |
| Watermark | "jello" 40px + "bounce" 27px darkened 10%, opacity .8, no background |

**Changes.**
- **HudGlass removed:** the class, its registration, its category entry and its docs. The owner wanted no extra HUD module.
- **`HUD.hudTheme`:** new setting "hud-theme", JELLO (default) or CLASSIC. CLASSIC is every HUD's own older drawing.
  `HudPanel.active()` reads it.
- **HudPanel rewritten as the Jello painter.**
  - `rounded()` draws the soft halo (Liquid.shadow, spread 12, alpha 0x40) and a black 45% fill.
  - Card radius 6 and small radius 3.5 (the theme's 12px and 7px at GUI scale).
  - `row()` is square-cornered. `fill()` draws bars, slots and pressed keys.
  - Liquid Glass, blur and the per-frame copy are no longer used.
- **Module list:** `row()` per row, flush.
- **EffectsHUD:** one card behind all rows, instead of a box per row.
- **KeyStrokes and LegitHUD keys:** Jello keys. Pressed shrinks to 95% with the #2e2e2e 70% fill, and the text is
  dimmed to #d3d3d3.
- **Notifications and TargetHUD cards:** radius 6.
- **WaterMark:** new mode "Jello", now the default.
  - Draws "myau" at 20 px and "atlas" at 13.5 px, a shade darker, both at 80%, in Product Sans Light scaled.
  - The earlier "pane behind every watermark" is removed, and WeedHack's own boxes are restored.
- **TargetHUD:** new style "JELLO" (index 15), now the default; `isMyauPlusStyle` excludes it. `drawJelloStyle`
  builds the card from the values above:
  - avatar via drawMyauPlusFace (stencil-rounded);
  - Product Sans 20 for the name and health, 16 for the verdict;
  - trail from animatedHealth;
  - expoOut scale-in, and fade-out handling as in the other styles.
- **The owner's config** (game closed, backed up to backups/config/default.json.pre-jello-20261008):
  - WaterMark Mode Vape → Jello;
  - TargetHUD style DEFAULT → JELLO;
  - HUD hud-theme set to JELLO;
  - the stale HudGlass entry removed.
  - HUD colour (RAINBOW) left as the owner set it. Jello's text is white, which is CUSTOM1 in the code.

**Files and build.**
- Backups: backups/src/pre-jello-20261008/ (before) and backups/src/jello-20261008/ (after).
- Build: 266 tests pass, 62 mixin classes, md5 765f9b82491e021e7b7a9a27d288963d.
- Installed with the game closed; the previous jar is backups/jars/Myau+UI_fixed.jar.pre-jello-20261008.

### 2026-10-08 Jello follow-up: module list, watermark off, Flags/Hits lists off
**User:** "旁邊那欄沒有變", "我不要 myau atlas 浮水印", "flags hit 也可以移除（畫面上的）", with a screenshot. A crop of the screenshot
showed the rows did get the Jello slab and shadow. They still read as the old list because:
- the text was in the owner's RAINBOW colour;
- the white edge bar was still drawn.

**Code changes.**
- **HUD MYAU list under JELLO:**
  - names are white (ArrayList.svelte $text-color); tags stay #AAAAAA;
  - the edge bar is not drawn;
  - rows are 1.5 px wider a side (7 CSS px of padding, against the setting's 2).
- **Defaults:** FlagDetector and HitCheck `hud` now default to NONE, with their log files unchanged. HitCheck
  chat-summary already defaulted to false.

**Owner's config edits** (pending until the game is closed):
- WaterMark off;
- FlagDetector hud → NONE;
- HitCheck hud → NONE and chat-summary → false.

**Build:** 266 tests pass, md5 1019a9b46bf32f068f7b1a78631c3ddd. Not installed yet, because the game was running.
- 2026-10-08 installed the Jello follow-up (md5 1019a9b46bf32f068f7b1a78631c3ddd) with the game closed; the previous jar was saved as backups/jars/Myau+UI_fixed.jar.pre-jello2-20261008. Install checks: 62 classes under myau/mixin, one Myau jar in mods. Config backed up to backups/config/default.json.pre-jello2-20261008, then: WaterMark off, FlagDetector hud NONE, HitCheck hud NONE, HitCheck chat-summary false.

### 2026-10-08 Module-list rows rounded
**User:** "有出現，但我要 round". The Jello rows showed, but the owner wants rounded rows.

**Change.** `HudPanel.row()` now uses `SMALL_RADIUS` (3.5) instead of 0. The rows stay flush and shadowed; the theme
itself draws them square.

**Files and install.**
- Backup: backups/src/jello-20261008/HudPanel.java.pre-round.
- Build: 266 tests pass, md5 8d9292d7b6adcacbc33c03885075ff28.
- Installed with the game closed; the previous jar is backups/jars/Myau+UI_fixed.jar.pre-round-20261008.
- Install checks: 62 classes under myau/mixin, one Myau jar in mods.

### 2026-10-08 HUD animations: module list on/off, TargetHUD
**User:** "開關都要有動畫阿，target hud 也要有動畫".

**Module list (JELLO theme, MYAU interface).** This follows ArrayList.svelte (fly in from x 50px over 200ms;
animate:flip 200ms).
- **State:** `RowAnim` per module (`appear` 0..1, eased `y`), kept in `rowAnims`. `animateRows(rowStep)` runs per
  frame on real frame time.
- **Enabled modules:** `appear` rises over 0.2 s, and `y` eases to the module's slot (exp, tau 50 ms ≈ settles in
  200 ms).
- **Modules just turned off:** stay in the list, `appear` falls over 0.2 s, and they are removed at 0.
- **Drawing each row:**
  - its own animated `y`;
  - translated 25 GUI px toward the screen edge by (1 − easeOutCubic(appear));
  - background faded via the new `HudPanel.row(..., fade)`;
  - name alpha = appear, floored at 4 because FontRenderer treats alpha 0 as opaque.
- **CLASSIC:** unchanged.

**TargetHUD JELLO.**
- **Card:** already scaled in and out with expoOut; it now also fades, both the card (rounded fade) and all text
  (alpha).
- **Health thumb:** eases toward the health (tau 0.1 s ≈ ease-out 0.3 s).
- **Trail:** eases toward the thumb (tau 0.2 s ≈ 0.6 s), as in HealthProgress.svelte. Reset when the target changes.
- **Damage wash:** fades out over 250 ms instead of switching off.

**Fix.** The heart in drawJelloStyle had been written to the source as a raw ❤ character, because a Python string
turned `❤` into the character. It is now the `❤` escape.

**Note for future edits.** Bash heredocs here eat backslashes. Use `chr(92)` or a scratchpad .py written with the
Write tool.

**Files and install.**
- Backups: backups/src/pre-anim-20261008/ (before) and backups/src/anim-20261008/ (after).
- Build: 266 tests pass, md5 c41c89b6804df7779eb682b007799ded.
- Installed with the game closed; the previous jar is backups/jars/Myau+UI_fixed.jar.pre-anim-20261008.
- Install checks: 62 classes under myau/mixin, one Myau jar in mods.

## 2026-10-08 — KillAura: more human aim (AimDrift, FlickOvershoot, per-flick speed)

Gap 3 of the "what is missing against paid clients" list: the SMOOTHSTEP aim
(the owner's setup: Rotations Legit, AimMode SMOOTHSTEP, Multipoint on) was
too regular for behaviour checks such as Polar's. Three changes, all in
`KillAura.stepTowards`:

- **AimDrift** (FloatProperty, 0.5, 0-1; shown with Multipoint): the aim point
  is no longer always the nearest point of the aim box. A point inside the box
  wanders (a new random goal every 6-14 ticks, eased 20% a tick), and the aim
  point is the nearest point moved `AimDrift` of the way toward it. 0 is the
  old behaviour. The box is the same inset aim box, so hits still land.
- **FlickOvershoot** (BooleanProperty, true): when the angle to the target
  crosses above 20 degrees (the start of a flick), 60% of the time the aim
  target is pushed 4-12% of the angle past it along the turn (max 4 degrees,
  pitch half). Once within 1.5 degrees of the pushed point the push decays
  x0.45 per tick: the aim comes back by itself. Reset on a new target.
- **Per-flick speed**: the old independent +-8% per-tick speed jitter is
  replaced by a speed picked per flick (0.85-1.15, when the angle crosses 12
  degrees) times a slow AR(1) wobble (0.8 decay, sigma 0.03, clamped +-12%).
  Independent per-tick noise is itself a statistical signature.

Unchanged: TurnAccel still caps how fast the turn grows, the smoothstep still
slows it near the target, the tremor and LazyRotation/ShortStop/FailAim still
apply after (processAim), and the mouse-grid snapping in RotationEngine.

Docs: ModuleDocs / ModuleDocsEn help for both settings; both added to the
"轉頭" group. Build 266 tests OK, md5 e3ff281df7e2e6fd55c04dfecdef3281,
installed (previous jar: backups/jars/Myau+UI_fixed.jar.pre-humanaim-20261008),
62 mixins. Sources: backups/src/pre-humanaim-20261008 and humanaim-20261008.
To tune: AimDrift 0 = old aim point; FlickOvershoot off = no overshoot.

## 2026-10-08 — Fewer modes in KillAura / Scaffold menus, important settings first

The owner asked for the many KillAura and Scaffold modes to be cut down to a
few, with the important settings at the top. Modes are hidden, not deleted:
the code and every index-based branch stay as they were, and an old config
naming a hidden mode still loads and works.

- `ModeProperty.hide(names...)`: hidden modes are left out of
  `getValuePrompt()` (the list both ClickGUIs draw) and skipped by
  `nextMode` / `previousMode`. While a hidden mode is the current value it is
  listed too, so it can be switched away from. `visibleIndices()` maps a menu
  row to the real index; `setVisible(row)` picks by row. AtlasClickGui
  (dropdown pick, current-row highlight, theme swatches) and the normal
  Dropdown now go through it -- before, both used the row as the index.
- `ModeProperty.alias(old, now)`: an old name in a config loads as the new one
  (and is saved under the new name next time).
- KillAura `Rotations`: menu shows NONE / Legit / Silent / Advanced (hidden:
  LockView, LiquidBounce, Hypixel, with their LB-* / Hypixel* settings, which
  were already conditional on the mode). `AimMode` row only shows while on
  LEGACY (SMOOTHSTEP is the aim), so Smoothing / AngleStep disappear with it.
- KillAura `auto-block`: NONE / HYPIXEL / LEGIT / SameTick (hidden: VANILLA,
  SPOOF, BLINK, INTERACT, SWAP, FAKE, Morden). The mode formerly named
  "LiquidBounce" is now "SameTick" (alias keeps old configs).
- Scaffold `rotations`: NONE / DEFAULT / GODBRIDGE / SNAP (hidden: BACKWARDS,
  SIDEWAYS, SMOOTH, Hypixel, 3FMC, SNAP2). Typo GODBIRGDE fixed to GODBRIDGE
  (alias).
- ModuleDocs groups: a "常用" group first. KillAura: Rotations, CPS Mode,
  MinCPS, MaxCPS, AttackRange, SwingRange, auto-block, Mode, MoveFix.
  Scaffold: rotations, sprint, tower, keep-y, safe-walk, move-fix, turn-speed.
  They were removed from their old groups.
- KillAura help texts no longer name another client.

Test: `src/test/java/myau/property/ModePropertyTest.java` (5). Build 271 tests
OK, md5 c39998f336ecb30b08a52b774193ee4f. To bring a mode back: remove it from
the `.hide(...)` call.

## 2026-10-08 — Report dialog with reasons and description; longer logs; log and config apart; auto-send logs

**Dialog** (AtlasClickGui `drawReportConfirm`, opened by `openReport()`): the owner wanted testers to say why.
Six reasons as chips, one required before Send works (`BugReport.REASONS`, sent in English; `REASONS_ZH` shown
when the menu language is 中文): Flagged / banned, Crash / freeze, Lag / low FPS, Module not working,
Visual / menu bug, Other. Below, a text box (always focused while the dialog is open): typing, Backspace,
Ctrl+V paste, 500 characters max, wrapped to the last 4 lines. Enter sends, Esc / Cancel / a click outside
closes. Dialog text follows the menu language. `.report [text]` still sends with no reason ("(not given)").

**Report** (`BugReport`): `send(reason, description, result)`. The log and the config are now two parts and
two Discord attachments: `report.txt` (header + logs) and `config.txt` (every module, `[x]` when enabled, with
all its settings -- before, only enabled modules were in the report). Longer logs: FlagDetector 150 lines,
placements 80, Clutch 80, fights 60 (new), hits 60 (new), game log 250 (was 25/15/15/-/-/40), read from the last
1 MB of each file (was 256 KB). Still redacted (Redactor) before leaving. The saved copy and the clipboard copy
hold both parts.

**Auto-send logs** (`management.LogUploader`, registered in Myau.java): every 30 minutes of **play** (ticks with
a world loaded; 36000 PRE ticks) it calls `BugReport.sendAuto()`: header and config built on the game thread,
the log files read and the upload done on a daemon thread (no frame cost). Nothing in chat, nothing saved.
Switch: `auto-send-logs` in Client Settings > Appearance > **Privacy** (AtlasTheme group), default on. Stored in
`atlas-theme.json`; LogUploader reads it from the file the first time (the menu may never be opened) and
AtlasTheme pushes it on load and on save. Appearance presets / reset never change it. The first time it would
send, a one-time chat line says what is sent and where to turn it off (marker
`config/Myau/auto-send-logs-notice.txt`).

**Relay** (`report-relay/src/index.js`, deployed version b1de3254): body up to 1 MB, log up to 600 KB, config up
to 300 KB (must start with "Myau Atlas config"), attached as config.txt. `kind: "auto"` needs the marker
"Myau Atlas log", goes to the **LOG_WEBHOOK** secret (not #bug-reports), 4 per IP per hour (KV key `auto:`);
reports keep 3 per 10 min (key `ip:` unchanged). Without LOG_WEBHOOK, auto logs get 503 and nothing is posted --
**the owner has to create a log channel webhook and run `npx wrangler secret put LOG_WEBHOOK`**. Old clients
(no kind / config) still work. Local tests (mocked Discord + KV): report → report.txt + config.txt; auto without
LOG_WEBHOOK 503; auto ×4 200 then 429; reports unaffected by the auto allowance; wrong marker 400; bad config
400; old client 200. Live: auto → 503, junk → 400, nothing posted.

Build 271 tests OK, md5 d244423a069cbbf0b125245486ffe568, **installed** (game closed, gated), 62 mixins.
Backups: `backups/src/pre-report2-20261008` (incl. relay-src), `backups/src/report2-20261008`,
`backups/jars/Myau+UI_fixed.jar.pre-report2-20261008`.

## 2026-10-08 — English heading for the new "常用" settings group
The "常用" group added to KillAura / Scaffold had no English heading, so it showed in Chinese with the menu in
English (the owner's setting). `ModuleDocsEn`: `heading("常用", "Main")`. Built, md5 734c1c75; not installed yet
(game running). Backup: `backups/src/ModuleDocsEn.java.pre-main-heading-20261008`.

## 2026-10-08 — Real version numbers (every build said "dev")
Reports, the window title and HUD watermarks showed "dev". Cause: `Myau` read `/version.json` with
`getResourceAsStream`, and Forge's own jar (forge-1.8.9-11.15.1.2318) has a `version.json` (its launcher profile:
id, time, ... no "version") that the class loader finds first, so `get("version")` threw and the fallback "dev"
won. Fix: the resource is now `myau-version.json` (renamed in src/main/resources, `filesMatching` in
build.gradle.kts, Myau.java). The version follows the Myau Atlas release numbering instead of the old fork's
`2.1+4`: `gradle.properties` `version = 1.5.0` (v1.4.0 is the last GitHub release; this build is the next one).
**Bump it for every release.** The report's Version line now reads "Myau Atlas v1.5.0". Build output is now
`build/libs/Myau+.jar-1.5.0.jar`. Built, md5 eb36ba58976c22cdf09a9be3b443d523, **installed** (game closed,
gated), 62 mixins; includes the "Main" heading fix. Backups: `backups/src/*.pre-version-20261008`,
`backups/jars/Myau+UI_fixed.jar.pre-version-20261008`.

## 2026-10-09 — Owner's KillAura settings: recommended balanced preset written to config
At the owner's request, `config/Myau/default.json` KillAura (game closed; backup
`backups/config/default.json.pre-ka-preset-20261009`): MinCPS 11→10, MaxCPS 16→14, auto-block LEGIT→NONE (LEGIT
held block too long and slowed movement; W-tap instead), FOV 189→180, ScanExtra 2.5→1.5, MaxTurnSpeed 70→60,
AimDrift 0.5→0.4, AimLead 0.2→0.3 (220 ms ping). Everything else as before (Legit / Human / SMOOTHSTEP / Multipoint /
Lazy / ShortStop / Raycast Enemy / ExitClick). If Polar still flags: MaxCPS 12, then MaxTurnSpeed 50.

## 2026-10-09 — LEGIT autoblock rework: block only under threat, no CPS cap
Owner: "auto block 沒做好". Found in KillAura:
1. `getAttackDelay` used `AutoBlockCPS` (owner: 8) whenever `isBlocking`, and LEGIT set `isBlocking` for any target
   in SwingRange -- so in reach the aura dropped from MinCPS-MaxCPS to 8 CPS.
2. LEGIT blocked on every hit whatever the target was doing, so chasing or a fleeing target meant block slowdown.
3. The release tick set `attack = false` in a fixed 2-tick cycle regardless of when the click was due.

Now (case 7): `legitBlockThreat()` = we were just hit (hurtTime > 0), or the target is within 3.6, swinging
(`isSwingInProgress`) and facing us (head yaw within 60 degrees). Not blocking + threat + click due: hit, then block
in the same tick after it (`swap`, finishAttack's interact + use). Blocking: release when there is no threat any
more or the next click is due within a tick (attackDelayMS <= 50); if the click is already due, release and hold
the click one tick (not consumed: performAttack is not called). So every hit lands unblocked and never shares a tick
with a release. `isBlocking = threat`. `getAttackDelay` ignores AutoBlockCPS for LEGIT (7) and SameTick (10);
AutoBlockCPS is hidden for those. Help texts updated.
Built (271 tests), md5 a9a35bd248dbd9490f2dd1327406b194; **not installed** (game running). After install the owner's
auto-block should go back to LEGIT (set NONE on 2026-10-09 in the preset). Backups:
`backups/src/pre-legitblock-20261009`, `backups/src/legitblock-20261009`.

## 2026-10-09 — NoItemRelease review; server-finish expiry; installed with the LEGIT autoblock rework
Owner: Slinky's NoItemRelease (same logic as ours with safe-release off) is not flagged on **Polar**; our 133 NoSlow
flags were **Grim** on test.ccbluex.net. Plan B: safe-release off, test on a Polar server with NoSlow off, then on,
and read FlagDetector. Slinky is a Myau fork (its file uses our `myau.util.ActionLedger`); its own NoSlow may differ.
Review of our path (claim/drop window, module-sent releases passing, Blink/Lag order, KillAura `onCancelUse` cancels
the stop only while `isBlocking`, reset on disable) found no flaw. One fix: after a dropped release of food/potion the
server finishes the item by itself after the remaining use count; `withheld` stayed true, so a later release (module
off, or safe-release on movement) went out for an item no longer in use. Now `withheldTicks` = use count + 3 at the
drop (-1 for a sword: never ends), counted down on PRE ticks, clearing `withheld`.
Built 271 tests, md5 c176475f500c4500edd6d433bf4390a1, **installed** (game closed, gated), 62 mixins. Config
(backup `backups/config/default.json.pre-legit-nir-20261009`): KillAura auto-block NONE→LEGIT, NoItemRelease
safe-release true→false (module itself still off; mode CONSUMABLE).

## 2026-10-09 — Clutch item-spoof
Owner asked for Clutch to work with item spoof. Here "item-spoof" is drawing only (as Scaffold / AutoBlockIn): the
slot really changes and the server sees the C09; the hand, the hotbar highlight and the item name keep showing the
slot held before. Clutch: `item-spoof` (BooleanProperty, default true, shown with auto-switch), `shownSlot` recorded
on the first switch of a catch in `selectSlot`, cleared in `stopPlacing`; `getSpoofSlot()` returns it while
enabled + item-spoof + slotSwapped. Mixins: MixinEntityRenderer (updateCameraAndRender, updateRenderer -- only if no
other module already swapped the drawn slot this frame) and MixinGuiIngame.updateTick. **Placement code and timing
untouched** (known-good Clutch of 2026-10-02). Group "物品" lists item-spoof; generic help text already existed.
Built 271 tests, md5 e204e1055d81c49c383171fddc7c2b08, installed (game closed, gated), 62 mixins. Backups:
`backups/src/pre-clutch-spoof-20261009`, `backups/src/clutch-spoof-20261009`.

## 2026-10-09 — Vape V4 comparison; WTap rewritten after Vape's
Owner felt the modules are below Vape V4. Studied the behaviour of Vape 4.21's NoItemRelease, NoSlowdown, WTap,
BlockHit, Velocity, LeftClicker/ClickerMod, JumpReset, HitSelect, BlockIn, Clutch and its `module/control` claims.
Findings: (1) Vape acts through keys and the mouse (KeyBinding states, mouse-count turns) so the game sends vanilla
packets; we often send or edit packets. (2) Vape has one owner per control (rotation, primary/secondary action,
right-click use, mouse-over) with priorities (`ModuleControlClaim`); we fix conflicts case by case. (3) Vape modules
have 3-10 settings and item allow-lists. Vape's NoItemRelease only cancels the release for allowed items (swords,
food, potions) and always while a GUI is open -- no anti-flag logic at all. Plan agreed: 1 WTap, 2 NoItemRelease
allow-list + GUI, 3 AutoClicker randomisation, 4 control claims, 5 keys/mouse instead of packets.

**WTap** (`modules/Wtap.java`, module "WTap") rewritten: on our C02 ATTACK while sprinting and physically holding W,
with `chance` (90%), only if `select-hits` passes (target `hurtResistantTime <= 14`, Vape's value), after
`release-delay` ms (0) really set the forward KeyBinding up, and after `re-press-delay` ms (50) put it back to the
physical key state (Keyboard / Mouse), each delay varied +-20%. Restored on a GUI opening and on disable; no new tap
while one is pending. Old version zeroed movementInput on every hit (500 ms cooldown) with a fixed 5.5 / 1.5 tick
rhythm and also on hits inside the hurt time. Old settings delay / duration are gone (owner's config had 2.5 / 1.0).
Old file was overwritten before backing up -- recovered from the release repo (unchanged since the first public
commit) into `backups/src/pre-wtap-20261009`. Built 271 tests, md5 b92279f3e6e99cf602301cbf2b04e5ff, installed, 62
mixins. Backup after: `backups/src/wtap-20261009`.

## 2026-10-09 — BlockHit after Vape 4.21's: Manual mode, Rhythm shown as Predict, swing Predict hidden
Owner chose BlockHit next (skipping the NoItemRelease item). Vape's BlockHit (as observed): modes
Manual (on each physical left click, with Chance 70-90%, press the use KeyBinding for 50 ms; idle while its
LeftClicker / SilentAura run their own), Predict (block when the player's own hurtResistantTime <= 10 + early window
[max hurt time ms + ping + 50]; release on damage; after 3 steady 250-1500 ms damage intervals, block from just
before the predicted next hit to HoldAfter ticks later), Auto (legacy), Lag; plus Require mouse down, Angle 90,
Distance 5. Ours already blocks through the use KeyBinding, and our "Rhythm" (2026-09-25) is that Predict.
Changes: Mode list {"Helper","Auto","Lag","Swing","Predict","Manual"} -- indices unchanged; old swing-watching
Predict (3) renamed "Swing" and hidden (its own note: decorative at high ping); "Rhythm" (4) is shown as "Predict"
(`alias("Rhythm","Predict")`; an old config's "Predict" now loads as the rhythm one); new **Manual** (5):
`ManualChance` (80%); on our AttackEvent with a sword (not using an item, no GUI, and not while KillAura's own
auto-block is on) the use key is set down for one tick, then `updateKeyState` returns it to the physical state.
Docs: descriptions and help, groups Manual / Predict / Swing（舊） (English heading "Swing (old)").
Built 271 tests, md5 b9fd029fa8dbd05a03cabedb545212a5, installed, 62 mixins. Owner's BlockHit mode is Helper
(unchanged). Backups: `backups/src/pre-blockhit-20261009`, `backups/src/blockhit-20261009`.

## 2026-10-09 — BlockHit full Vape parity: report only (no code yet)
Owner asked "is it exactly like Vape?" -- no: Manual triggers on hits not mouse presses, fixed chance, 1-tick release;
Predict uses half ping and a RhythmMax cap; no Require mouse down / Ignore manual block / Angle / Distance; Auto and Lag
work differently. Owner: do it completely, then "write the report first, don't build yet". Report (Chinese, with the
open decisions -- keep Helper?, Require mouse down default on?, drop RhythmMax?, Lag's packet hold):
a private report (not in this repository). Vape also tracks attack-to-hurt latency like our HitTimer.
Nothing changed in the code for this step.
Decisions recorded in the report (2026-10-09): keep Helper as a fifth mode; Require mouse down default **off**;
Predict without the RhythmMax cap (Vape's Hold after + release on damage instead); Lag done Vape's way (hold the
release and everything after it 50-100 ms). Owner then said "not now": only a pre-change backup was taken
(`backups/src/pre-blockhit-vape-20261009`); no code changed.

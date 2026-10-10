# Client review, 2026-10-10

Findings from installing the Gradle build into a real client (Modrinth "Test" profile,
Minecraft 1.8.9 + Forge 11.15.1.2318) and driving it to the main menu. Everything below
was observed in a log or in the running client; nothing here is speculative. Ordered by
how much it costs to leave alone.

## 1. The `myau:` resource domain never resolves (worst of the set)

All 16 custom fonts fail to load, every launch:

```
[myau.util.font.impl.FontUtil:getResource:44]: [Myau] Failed to load font: regular.ttf
... icon.ttf, product_sans_*.ttf, Google-Sans.ttf, tenacity*.ttf, Vision.otf, ... (16 total)
```

The files are in the jar (`assets/myau/font/`, 20 of them). The domain is the problem:

- FML says `FML has found a non-mod file Myau+.jar-1.5.0.jar in your mods directory.
  It will now be injected into your classpath.`
- `mcmod.info` declares `name`/`version`/`mcversion` but **no `modid`**, and there is no
  `@Mod` class anywhere in `src/main/java/myau/`.
- Consequently the jar is a coremod, not a mod: no mod container, no `FMLFileResourcePack`,
  and it appears in neither `Reloading ResourceManager:` list (9 packs load; Myau is none
  of them).

Without a resource pack for the jar, every `ResourceLocation("myau:...")` lookup fails, so
the client silently falls back to `new Font("default")`. The same applies to
`WaterMark`'s `myau:assets/textvape.png` and `myau:assets/textv4.png`, which ship but can
never load, and to any future `myau:` asset.

Independent bug in the same area: `src/main/java/myau/util/FontUtil.java:25` builds
`new ResourceLocation("myau/font/" + name)` — with no colon the namespace becomes
`minecraft`, so that copy cannot work even once the domain resolves. There are two
`FontUtil` classes doing the same job (`myau.util.FontUtil` and
`myau.util.font.impl.FontUtil`).

Two ways out, and they are not exclusive:

1. **Load the fonts from the classloader.** They are ordinary jar resources, so
   `Myau.class.getResourceAsStream("/assets/myau/font/" + name)` works today with no
   packaging change. Smallest fix; fixes fonts only.
2. **Give the jar a resource pack of its own** so every `myau:` path resolves. This is the
   real fix for the class of bug. Care needed: adding an `@Mod` container changes what the
   client announces during the FML handshake, which is exactly why this jar may have been
   kept coremod-only. Registering a pack at runtime avoids that.

## 2. Startup sound — fixed on this branch

Reported as "the start up sound doesnt work". Four separate reasons, all now fixed (see the
`Add a startup sound to the main menu` commit):

| Problem | Effect |
| --- | --- |
| The feature only existed on the `add-startup-sound` branch | No build of the port contained it; the jar shipped no `startup.wav` at all |
| Every failure was swallowed (`catch (Exception ignored)`, twice) | "No sound" was undiagnosable — no audio device, undecodable clip and missing resource all looked identical |
| The clip plays outside Minecraft's mixer, so the game's volume never reached it | A client at 6% master volume still got the full clip; a muted client got it too |
| `Clip#getMicrosecondLength()` can return `NOT_SPECIFIED` | The clip was closed 200 ms in and the sound cut off |

Now: failures are reported once on `stderr` (the clip still can never crash or stall the
client), a genuinely muted client stays silent, the length falls back to the stream's frame
count, and `config/Myau/menu.json` has a `startupSound` switch.

Deliberately **not** scaled by the master volume: with the reported settings (6%) a
proportional clip would be ~4% and inaudible, which is the same bug in a new costume.

## 3. `Adaptive.releaseKnobs` throws NPE on shutdown

`./gradlew build` prints this on every run, and it reproduces on this branch:

```
[Myau] shutdown task RESTORE/Adaptive.restore failed:
java.lang.NullPointerException
	at myau.module.modules.Adaptive.releaseKnobs(Adaptive.java:198)
	at myau.module.modules.Adaptive.restoreAll(Adaptive.java:194)
	at myau.management.Shutdown.run(Shutdown.java:84)
```

`releaseKnobs` dereferences `Myau.moduleManager.modules` with no guard, while the code just
above it checks `module != null` first. Any shutdown without a live client (tests, a
headless run) throws. It is harmless today only because `Shutdown.run` catches `Throwable`
— which is also why nobody noticed, and why a real failure in that stage would look the
same.

## 4. The build only works with `JAVA_HOME=jdk-17`

`./gradlew build` on the machine's default JDK 25 dies in 9 seconds with
`What went wrong: 25.0.4.1` — Gradle 8.8 cannot run there and the message says nothing
useful. `docs/ARCH-AUDIT-2026-09-28.md` records the right invocation and README's Chinese
section says JDK 17, but nothing detects or enforces it. A toolchain resolver, or
`org.gradle.java.installations.paths` in `gradle.properties`, would make a bare
`./gradlew build` work.

## 5. ~1.6 MB of unreferenced assets ship in the jar

`assets/myau/sounds/kill-*.wav` — seven clips (`kill-valorant-kill-sound.wav`,
`kill-quake-killsound.wav`, `kill-kill-sound-for-tsb.wav`, …) totalling about 1.6 MB — are
in the jar and **no code reads any of them**. Before `SoundPlayer` there was no `.wav` or
`javax.sound` reference in the tree at all. Either wire them into the kill-sound feature or
stop shipping them.

## 6. Ported code that nothing calls

- `myau/module/modules/FallView.java` — ported, no caller.
- `ScriptManager.fireChat` / `Script#onChat` — defined, never invoked, so a script never
  sees chat.
- `ScriptModule` exposes no settings, so a loaded script cannot be configured from the menu.

## 7. Naming has no single source of truth

`mcmod.info` says `Myau+`; the jar is `Myau+.jar-1.5.0.jar` (base name plus version); the
window title says `OpenMyau+ (Main) - 1.5.0 | MC 1.8.9`; the config lives in
`config/Myau/`. Harmless, but "which version am I running" is answered differently in four
places.

## How the two verified items were checked

- Clean `./gradlew build` (JDK 17 Gradle, Java 8 toolchain): **BUILD SUCCESSFUL**,
  299 tests, 0 failures, 1 skipped.
- Jar md5-matched into the profile's `mods/`, then launched: 9 mods loaded, `Sound engine
  started`, window retitled `OpenMyau+ (Main) - 1.5.0 | MC 1.8.9`, and
  `[Myau] Playing /assets/myau/sounds/startup.wav at 60%` in the log with no failure line.
- Not verified: that the speaker actually emits audio (nobody can hear it from a log), and
  no ported gameplay module was exercised in a world.

# YpsoPump driver — porting notes

Driver for an **unlocked plaintext research YpsoPump** on current AndroidAPS (3.4.2.4).

**Status after session 2: the project builds green and installs. The driver connects, bonds,
authenticates and reads. It cannot deliver insulin — there is no code path from AAPS to a
delivery command.**

The first hardware milestone (bond → auto-auth → stay connected with no key exchange) has
**not yet been run against the pump.** Everything below describing pump behaviour is derived
from the sources, not observed. §7 is the list of what to check when you first power it on.

---

## 0. Read this first — safety posture

This build reads and never writes. That is enforced in three independent places, deliberately,
so no single edit can undo it:

1. **The SDK has no encryption or key-exchange code at all.** vicktor's `crypto/` and
   `keyexchange/` packages were not vendored. There is no `PumpCryptor` to leave null and no
   relay client to accidentally call. Plaintext is a property of what exists, not a setting.
2. **`YpsoService` exposes no delivery method.** No bolus, TBR, basal-write or clock-set entry
   point is declared on it. This is an absence of method, not a flag that could be flipped.
3. **`YpsoPumpManagerImpl` refuses delivery without touching BLE.** The `PumpManager` interface
   requires `deliverStandardBolus`, `setTbr`, `writeBasalProfile`, `syncDateTime` and friends, so
   they exist — and each logs `REFUSED` and returns failure before reaching the pump.

`YpsoPlugin` overrides every delivering `Pump` method individually rather than relying on a
shared default, so "this does nothing" is visible at each call site and re-enabling one has to
be a deliberate act.

**Never make a refusing method report success.** Session 1's notes recorded the same rule after
Andy Rozman's dummy connector returned `success=true` for boluses; anything above a driver that
trusts a delivery result will act on it.

---

## 1. Architecture

```
AAPS core ── YpsoPlugin  (Pump interface)              [shape: MedtrumPlugin]
                 │  bindService + LocalBinder
                 ▼
             YpsoService (bound DaggerService)          [shape: MedtrumService]
                 │  suspend calls                        internals: coroutine-native
                 ▼
        com.ypsopump.sdk.YpsoPumpManagerImpl            [vendored vicktor SDK]
                 │
                 ▼
        YpsoBleManager (Nordic BLE) ── plaintext, no cryptor
```

### Modules

| Gradle module | Package | What it is |
|---|---|---|
| `:pump:ypso` | `app.aaps.pump.ypso` | The AAPS driver: plugin, service, pairing screen, overview fragment |
| `:pump:ypso:sdk` | `com.ypsopump.sdk` | vicktor's ypso-sdk, vendored, minus `crypto/` and `keyexchange/` |
| `:pump:ypso:sdk-common` | `com.pump.common` | vicktor's pump-common, vendored verbatim (the `PumpManager` contract) |

`:pump:ypsopump` — session 1's forward-port of Andy Rozman's module — is **still in the tree but
unregistered from the app**. It is the parts donor it was declared to be (Ypso domain enums,
response-translator logic, config UI ideas). Registering both would have put two YpsoPump entries
in the pump list, one of them backed by a dummy connector. It is gone from
`app/build.gradle.kts`, `PluginsListModule`, `AppComponent` and `TestAppComponent`.

### Why the service is Medtrum-shaped but not Medtrum-internally

Copied from Medtrum: the bound `DaggerService`, the plugin↔service binder, DI wiring,
preference-key enums, the state holder (`MedtrumPump` → `YpsoPump`), and the `PumpSync` usage.

Deliberately **not** copied: `sendPacketAndGetResponse` and the `SystemClock.sleep` poll loops.
Those are blocking and fight vicktor's suspend API. `YpsoService` owns a `CoroutineScope`, calls
suspend functions directly, and collects the SDK's `StateFlow`s.

`runBlocking` appears **once**, in `YpsoService.awaitResult()`, because the AAPS command queue
calls pump drivers from its own thread and expects a synchronous answer. `connect()` and
`disconnect()` are deliberately *not* routed through it — they launch into the service scope and
return immediately, because the command queue calls `connect()` and then polls `isConnected()`;
blocking there would stall the queue for the length of a BLE handshake.

### Deviation from the plan: held connection, not connect-on-demand

vicktor's SDK opens and closes a BLE link around **every single command** (`withPumpConnection`),
to dodge timeouts in a standalone app. Under AAPS that is the wrong shape: the command queue
already brackets pump work between `connect()` and `disconnect()`, so connect-on-demand would
mean several connect/disconnect cycles per queue run.

`YpsoPumpManagerImpl.withConnection` instead reconnects only if the link has dropped, and holds
it until AAPS asks for a disconnect. A `Mutex` serialises commands so two queue entries never
interleave on one GATT connection.

---

## 2. Pairing (DanaRS-shaped)

`YpsoBLEScanActivity` is modelled on DanaRS's `BLEScanActivity`, because the YpsoPump needs a real
Bluetooth bond — Medtrum's serial-number-only model does not apply.

1. Scan with the platform BLE scanner; keep devices whose advertised name starts with
   `YpsoPump_` and whose RSSI clears `YpsoRssiThreshold` (default **-99**, from the Lotus export).
2. The serial is embedded in the name (`YpsoPump_10178681`), so the user can confirm the unit.
3. On tap: store MAC + name + serial, then `createBond()`. Android raises the pairing dialog and
   the pump displays the matching code. Once per phone.
4. `EventYpsoPumpDeviceChanged` tells the service to drop the current session, clear cached
   pump state and adopt the new MAC — immediately, not at the next app start.

The SDK's own scan/auto-connect path was dropped; upstream connects to the *first* `YpsoPump_` it
sees, which is wrong when more than one pump is in range. Device choice belongs to the user.

**Only the MAC is stored. There is no credential field anywhere in the driver** — see §3.

---

## 3. Authentication — and a discrepancy the plan did not anticipate

Auth is automatic and secret-free. On connect, `YpsoBleManager.authenticate()` computes
`MD5(6 MAC bytes ‖ 10 salt bytes)` and writes it to `CHAR_AUTH_PASSWORD`
(`669a0c20-…-b2147bc5`). Nothing is stored, nothing is asked of the user, and it works for any
pump.

**MAC format used** (both implementations agree, and this is what the driver does):

- Take the MAC as Android reports it: `"EC:2A:F0:01:22:34"` — uppercase hex, colon-separated.
- Strip the colons, parse as 6 hex bytes, **big-endian / left-to-right** (`EC 2A F0 01 22 34`).
- Append the 10-byte salt. MD5 the 16 bytes. Write the 16-byte digest.
- Case does not matter: both parse with radix 16. Colons are removed either way.

**The salts differ, and the plan assumed they were the same mechanism.** They are the same
*construction* with a different constant:

| Source | 10-byte salt |
|---|---|
| vicktor `YpsoBleManager.AUTH_SALT` | `4F C2 45 4D 9B 81 59 A4 93 BB` |
| Andy `YpsoPumpUtil.computeUserLevelPassword` | `5B D7 10 66 01 F2 4F 3C 8F 6B` |

So the plan's "cross-check the exact MAC string format" was checking the wrong thing — the MAC
handling is identical; the salt is not. Only hardware can say which one this firmware wants.
`YpsoLegacyAuthSalt` (Advanced settings, default off = vicktor's) switches between them.

**Detecting a wrong salt:** the auth write itself will *succeed* either way — it is an ordinary
GATT write and the pump does not NAK a bad password. The failure shows up on the next read. So
`establishSession()` deliberately treats the first system-status read as part of authentication:
if it fails, the session is torn down and reported as an error rather than being left in a
half-authenticated state that would look connected. **If the pump connects and then immediately
fails to read status, flip the legacy-salt switch before looking anywhere else.**

**No key exchange, ever.** `pumpCryptor` does not exist to be left null; the whole encryption
layer is absent from the vendored copy (§0). This also keeps gRPC and Google Play Integrity out
of the AAPS dependency graph — upstream needed both only for Proregia.

---

## 4. Read path

`getPumpStatus` (queued as a normal status read) does:

| Value | Source | Notes |
|---|---|---|
| Reservoir U | `getFullStatus()` → `CHAR_SYSTEM_STATUS` | centi-units ÷ 100 |
| Battery % | same read | |
| Delivery mode | same read | "Basal", "TBR Active", "Priming", … |
| Active program | `readActiveProgram()` → setting index 1 | 3 = A, 10 = B |
| Basal profile | `readBasalProfile(active)` → setting indices 14–37 (A) / 38–61 (B) | 24 hourly rates; `-1` slot = 0.0 U/h |
| Serial / firmware / manufacturer | standard BLE device-info characteristics | read once per session |

`baseBasalRate` is the active program's rate for the current hour, and is **0.0 until the
profile has actually been read** — the driver reports what it knows rather than a plausible
guess.

**Known gap — temporary basal detail.** Plan §3 expected `getFullStatus().activeTbr` to carry
`ActiveTbr(percentage, remainingMinutes, initialDurationMinutes)`. It does not: the system-status
characteristic carries only delivery mode, reservoir and battery. So the driver exposes
`isTbrActive` (true when the mode is TBR) and leaves `PumpStatus.activeTbr` **null**, and the
fragment says "Running (rate and duration not readable yet)". Filling those fields with zeroes
would have looked like a real measurement. Finding where the pump exposes TBR detail is session
3 work and is a prerequisite for TBR support.

---

## 5. History → PumpSync, and why it is off by default

`readHistoryWithDump()` walks the pump's event log and alert log in **one pass**, returning both
the mapped `PumpHistoryEvent`s and a raw text dump. One pass matters: each entry costs a write of
its index followed by a read of its value, so reading history twice doubles the slowest thing the
driver does.

Mapping (`YpsoPumpManagerImpl.toHistoryEvent`, event IDs from `YpsoProtocolConstants`):

| Pump event | → |
|---|---|
| 2 / 3 / 18 / 26 bolus completed (immediate / delayed / combined / blind) | `BolusDelivered` |
| 9 TBR running | `TbrStarted(pct, minutes)` |
| 10 / 32 TBR completed / aborted | `TbrEnded` |
| 6 / 7 / 8 basal profile changed | `BasalRateChanged` |
| 16 rewind finished | `CartridgeChanged` |
| 4 priming, 25 cannula priming, 24 battery removed | `Unknown` carrying type + values |
| 100–108 alerts | `Alert` |

Therapy events (`YpsoService.syncConsumableChange`), per the Lotus-confirmed rules:
rewind → **Insulin Change**; cannula priming → **Site Change**; battery removed → **Battery
Change**; priming → **Tube Change only when the primed amount exceeded 9 U** (below that it was a
top-up, not a set change). Each is gated by its own preference.

`pump-common`'s `PumpHistoryEvent` has no variants for consumable changes, so those arrive as
`Unknown` with the raw values packed into `rawData`; `YpsoEventTypes` re-exports the IDs and
decodes the primed amount.

### ⚠ Writing history into AAPS is disabled by default — settle this before enabling

`YpsoSyncHistoryToAaps` defaults to **off**. The reads and the mapping run regardless; only the
`PumpSync` writes are gated.

Plan §4 says to use `event.position` (the pump-memory slot index) as the `pumpId`, and that is
what the code does. But **a slot index is only a stable identifier if the pump's log never
wraps.** The read path is "read count, then read slots `count-maxEntries` … `count-1`", which is
exactly what a growing log *and* a ring buffer both look like from outside. If it is a ring, slot
numbers get reused, and `pumpId` collisions make PumpSync either silently merge two different
boluses or reject a new one as a duplicate. Either way IOB is wrong, and the records are
persistent — this is not a mistake you can back out cleanly.

The raw dump exists to settle this. It prints **both** `idx` (slot) and `seq` (the pump's own
sequence number) for every entry. On hardware:

1. Read history, note the `idx` and `seq` of a few known events.
2. Deliver nothing; let the pump record more events over a day or two.
3. Read again. If `count` keeps growing and a given event keeps its `idx`, the slot index is
   stable and the plan's mapping is safe — turn the switch on.
4. If `count` plateaus, or an `idx` now names a different event, switch `pumpId` to `seq`
   (or to the event timestamp) **before** enabling the sync.

---

## 6. Config pre-seeded from plan §5

| Setting | Value | Where |
|---|---|---|
| Bolus speed | index `1` = 12 U/min | `YpsoBolusSpeed`, driver preference |
| Nano/tiny bolus | `true` | `YpsoNanoBolus`, driver preference |
| RSSI scan gate | `-99` | `YpsoRssiThreshold`, used by the pairing scan |
| Pump max bolus | `10.0 U` | `YpsoDoubleNonKey.MaxBolus` |
| Insulin density | U100 | implicit in the unit maths |
| Pump-unreachable threshold | 30 min | **already the AAPS default** (`AlertsPumpUnreachableThreshold`), nothing to seed |

Two judgement calls worth knowing about:

- **Max bolus is stored as a driver value, not written into AAPS's safety settings.**
  `SafetyMaxBolus` is the user's own limit; silently overwriting it would be an override of a
  safety setting the user chose. The 10 U figure is the *pump's* cap and will be used to clamp
  driver-side once delivery exists.
- **Bolus speed and nano-bolus are recorded but not yet load-bearing.** They only matter for
  bolus progress timing and bolus step, both of which are session 3. Applying nano-bolus would
  mean editing `PumpType.YPSOPUMP`, which the old `:pump:ypsopump` module also reads — not worth
  touching for a read-only build.

---

## 7. First hardware session — what to check, in order

### Before anything: the test device

**Use a phone that is not running another pump controller.** If a licensed closed-loop app
(CamAPS FX, mylife Loop, …) is paired to the same pump, do not put this driver on that phone or
point it at that pump. This driver *holds* its GATT connection rather than reconnecting per
command (§1), so it can keep the other app from reaching the pump to deliver insulin. Read-only
does not mean harmless when two controllers compete for one BLE peripheral.

Device requirements: Android 12+ (`minSdk 31`). Developer options → USB debugging on. At runtime
the pairing screen needs Bluetooth scan/connect permission granted, or it finishes immediately
with a permission toast.

Installing the debug APK can fail with `INSTALL_FAILED_VERIFICATION_FAILURE: Install not
allowed`, which is **not** a signature problem and no amount of `-r` will fix it. It is
on-device install verification: Play Protect's "Verify apps over USB" (Developer options) and,
on Samsung, Auto Blocker (Settings → Security and privacy). Turn the relevant one off on the
test device. `adb shell settings put global verifier_verify_adb_installs 0` does the same thing
from the host — appropriate on a dedicated test phone, not on a personal one.

A genuine signature mismatch looks different: `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. That one
does need an uninstall, which **deletes the AAPS database** — export settings *and* database
from within AAPS first, and verify both landed in `/storage/emulated/0/AAPS/preferences/` and
`/AAPS/exports/` before uninstalling. The database export is a separate action from the settings
export and is easy to believe you did.

### On the pump

1. **Bond.** Settings → YpsoPump → "Pair YpsoPump". The pump should appear as
   `YpsoPump_<serial>`. Confirm the code on the pump. If nothing shows up, lower
   `YpsoRssiThreshold` before assuming a scan problem.
   - Confirm which unit you hold: the decrypted prefs are pump **10074292**
     (MAC `EC:2A:F0:01:22:34`), the photos show **10178681**. Auth is MAC-derived so either
     works, but the stored MAC belongs to the older pump.
2. **Connect + auth.** Watch for `Authenticated (plaintext, no key exchange)` followed by
   `Session ready` in the log. **This is the session's headline result: it proves the plaintext
   assumption.**
3. **If it connects but the status read fails:** flip `YpsoLegacyAuthSalt` (§3) and retry. That
   is far more likely than a MAC-format problem.
4. **Parity check.** Compare against the pump's own display, in this order — reservoir U,
   battery %, active program (A/B), the 24 basal rates, firmware version. A mismatch in the basal
   rates specifically would mean the setting indices (14–37 / 38–61) differ on this firmware.
5. **History.** "Read history" on the overview screen. Compare the dump against the pump's
   history screen: types, timestamps, bolus amounts. Then start the `idx`/`seq` observation
   in §5.
6. Leave the sync switch off until §5 step 3 or 4 is answered.

Confirm firmware version while you are there — the UUIDs were verified against **V05.02.03**.

---

## 8. What session 3 (saline delivery) needs

Blocking work, roughly in order:

1. **Settle the `pumpId` question** (§5). Retrofitting stable IDs after boluses have been
   recorded means duplicate IOB. Do this first.
2. **Find the TBR detail read** (§4). No TBR support without percentage and remaining time.
3. **`syncDateTime` first** — plan §7 step 5, the first non-delivering write. It exercises the
   write path (`writeFramesSequentially` → `CHAR_SYSTEM_DATE` / `CHAR_SYSTEM_TIME`) with nothing
   at stake. `YpsoPumpManagerImpl.syncDateTime` currently refuses; the BLE call underneath
   (`bleManager.syncTime`) is already vendored and intact.
4. **Then delivery**, on saline, off-body, in plan §7 step 6 order: 0.05 U bolus (with progress
   and abort) → TBR → basal write. For each, the SDK method already exists and is wired to the
   pump; what has to be written is the `YpsoService` entry point and the `YpsoPlugin` override.
5. **Bolus progress** should come from collecting `pumpEvents` / the bolus-notification
   characteristic into `EventOverviewBolusProgress` — a Flow collection, not Medtrum's sleep-poll.
   Scale the completion timeout to the configured bolus speed (`YpsoBolusSpeed`, 12 U/min).
6. **Re-check the refusals.** Every `REFUSED` log line and every `notImplemented()` in
   `YpsoPlugin` is a deliberate stop. Remove them one at a time, with a saline test each, not in
   a batch.

Also worth doing eventually, none of it blocking: a history screen (Medtronic's
`MedtronicHistoryActivity` is the pattern), unit tests for the history mapping (nothing in the
new module is tested yet), and deleting `:pump:ypsopump` once the last useful reference is mined
out of it.

---

## 9. Build environment gotchas

Carried forward from session 1, all still true:

- `app/build.gradle.kts` fails the build on **any** modified tracked file
  (`isMaster() && !allCommitted()`), and `isMaster()` is true whenever `Versions.appVersion`
  contains no `-`. Untracked files are ignored; tracked modifications are not. Commit first, or
  temporarily suffix `Versions.appVersion` (e.g. `3.4.2.4-dev`) while iterating.
  **Revert the suffix before committing.**
- The guard is evaluated when `:app` is *configured*, which happens even for module-only tasks
  like `:pump:ypso:compileFullDebugKotlin`.
- Keep Android Studio closed while building from the CLI — its Gradle daemon holds
  `core/interfaces/build/.../classes.jar` open and the build fails with `FileSystemException`.
- `JAVA_HOME` must be set for `gradlew.bat`; the Android Studio JBR
  (`C:\Program Files\Android\Android Studio\jbr`) works.
- `:app:kspFullDebugKotlin` can fail spuriously with `ComponentProcessingStep was unable to
  process 'app.aaps.di.AppComponent' because 'app.aaps.di.ActivitiesModule_ContributesHistoryBrowseActivity'
  could not be resolved` after build inputs change under stale incremental state. Not a real
  error. `./gradlew :app:clean` then rebuild. **This happened again this session**, right after
  the ypso module gained new classes — expect it whenever the driver's shape changes.

### New this session

- Nordic's BLE library (`no.nordicsemi.android:ble:2.11.0`) was added to
  `gradle/libs.versions.toml` as `no-nordicsemi-android-ble`. It is the only new third-party
  dependency; dropping the key-exchange code avoided pulling in gRPC and Play Integrity.
- `no.nordicsemi.android.support.v18:scanner` was **not** needed — the pairing screen uses the
  platform scanner, like DanaRS.

# YpsoPump driver — porting notes

Driver for an **unlocked plaintext research YpsoPump** on current AndroidAPS (3.4.2.4).

**Status after session 2: verified on hardware.** The driver bonds, connects, authenticates in
plaintext with no key exchange, and reads reservoir, battery, delivery state, active program,
the 24-slot basal profile, the active temporary basal and the event and alert history — every
value cross-checked against the pump's own display or against actions performed on the pump
minutes earlier. It cannot deliver insulin: there is no code path from AAPS to a delivery
command.

Test pump: **YpsoPump_10074292**, MAC `EC:2A:F0:01:22:34`, firmware V05.02.03 — the unit whose
settings are in the decrypted Lotus export. (Serial 10178681 is a *different*, standard pump
running mylife CamAPS FX; see §7.)

Findings marked **[verified]** below were confirmed against the pump display. Everything else is
still inference from the sources.

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
handling is identical; the salt is not. `YpsoLegacyAuthSalt` (Advanced settings, default off)
switches between them.

**[verified] vicktor's salt is the right one for V05.02.03.** Authentication succeeds with the
default and the legacy toggle has not been needed. It is retained for older firmware.

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

## 4. Read path — and four wire-format corrections to the vendored SDK

**Upstream's framing does not match firmware V05.02.03.** Four separate bugs, each found by
looking at raw bytes rather than at the code's own assumptions. Together they broke every read.

### 4a. Reads carry no framing header

The system-status reply is a bare CRC-terminated message:

```
03 8B 16 00 00 02 78 00 00 00 64 00 00 00 00 00 00 00  F1 52
└─ payload (18 bytes) ─────────────────────────────┘  └CRC┘
```

CRC-16 over the first 18 bytes is `F1 52` — it matches, so the 20-byte read is complete.
Recomputing with the leading byte treated as a framing header does *not* match. Upstream assumed
reads are framed like writes, took the low nibble of the first *data* byte as a frame count, read
`0x03` as "frame 1 of 3", and chased continuation frames on `CHAR_EXTENDED_READ` that do not
exist — `GATT_INVALID_OFFSET` (status 7), killing every session about a second after auth.

`readExtended` now validates the CRC first and returns the message when it is already complete.
**The CRC is the discriminator, never the nibble** — the nibble may be data.

### 4b. GLB writes must not be framed

Writing an 8-byte GLB safe variable through `chunkPayload` makes it 9 bytes, and the pump answers
`GATT_INVALID_ATTRIBUTE_LENGTH` (status 13). That broke every basal profile read. `writeGlb`
sends the bare 8 bytes.

### 4c. GLB reads must not go through `readExtended`

Setting values come back as a bare GLB pair — value then its bitwise complement — with **no CRC
and no framing**:

```
readSetting(1)  -> 03 00 00 00 FC FF FF FF   value=3   (program A)
readSetting(14) -> 78 00 00 00 87 FF FF FF   value=120 (1.20 U/h)
```

With no CRC to validate, `readExtended` fell through to its frame-count fallback and read a value
of 120 (`0x78`) as "frame 1 of 8". `readGlb` reads these directly; the GLB's own complement is
the integrity check. History counts are the same shape.

### 4d. Rates are Double, not widened Float

`120 / 100f` widened to Double is `1.2000000476837158`. `isThisProfileSet()` compares pump
against AAPS profile, so that would report a mismatch on a correct profile forever once writes
exist. Divide in Double.

### What is read, and what was verified

| Value | Source | Status |
|---|---|---|
| Reservoir U | `CHAR_SYSTEM_STATUS` bytes 1–4, centi-units LE | **[verified]** 5771 → 57.71 U vs 57.7 U on pump; falls steadily while running |
| Battery | byte 5 | **[verified]** it is **bars, not percent** — see below |
| Delivery state | byte 0 | **[verified]** `3` = Stopped, `10` = Running |
| Active program | setting index 1 | **[verified]** value 3 = program A |
| Basal profile | setting indices 14–37 (A) / 38–61 (B) | **[verified]** all 24 slots read, 1.20 U/h |
| Serial / firmware | standard BLE device-info characteristics | read once per session |

**Battery is a bar count.** The pump displayed "2 of 4 bars" for a raw value of 2. Upstream
passed that through as `2%`, turning a half-charged battery into one apparently about to die —
and pump battery level is something AAPS can act on. Now scaled by `BATTERY_BARS_MAX = 4`.

**Delivery mode: upstream's table is wrong and is not used.** With the pump displaying *Stopped*,
byte 0 reads `3`, which upstream calls `BOLUS_FAST`. That also made `isDelivering` true, so AAPS
believed a stopped pump was running basal — the dangerous direction, since anything reasoning
about IOB would assume background delivery that was not happening. `DeliveryMode` now reports
only what has been confirmed: `3` = Stopped, `10` = Running, everything else "Unverified mode N"
with `isDelivering` **not** asserted. Extend it only from observation.

**Unmapped bytes**, both plausible and both unverified:

- **bytes 6–9** went `0` → `120` when the pump was started, matching the 1.20 U/h basal read
  independently from setting 14. Very likely the active rate in centi-units/hour. If confirmed
  it gives `baseBasalRate` without reading the whole profile.
- **byte 10** is `100` in every sample so far, stopped or running. Plausibly TBR percent with 100
  meaning "none" — which would supply the TBR detail §3 of the plan expected and which is
  otherwise missing. Testing needs a TBR set, so it is session 3 work.

`baseBasalRate` is the active program's rate for the current hour and is **0.0 until the profile
has actually been read** — the driver reports what it knows rather than a plausible guess.

**Known gap — temporary basal detail.** `PumpStatus.activeTbr` stays **null**: no field carrying
TBR percentage and remaining time has been identified yet (byte 10 is the candidate). The driver
reports `isTbrActive = false` rather than inventing one. Resolving this is a prerequisite for TBR
support.

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

**Not yet exercised on hardware.** History counts read through the same `readGlb` path that the
basal profile now uses, and entries through the same index-write-then-value-read mechanism, so
the wire format should be sorted (§4). What is unproven is `HistoryEntry.parse` — the 17-byte
entry layout, the timestamp epoch (the pump counts from 2000-01-01, converted in `parse`) and
the event-type IDs. Read history, then check the dump's timestamps and types against the pump's
own history screen before trusting any of it.

### ⚠ [verified] Index 0 is the NEWEST entry — the plan's `pumpId` would have corrupted IOB

Two hardware findings, from one 50-entry read of each log:

**As the slot index falls, the pump's sequence number rises and so do the timestamps.** So
**index 0 is the most recent entry**, not the oldest. Upstream read `count - maxEntries` ..
`count - 1` on the opposite assumption and returned the *oldest* slots — on the test pump the
newest entry it fetched was four months stale, and that day's activity was missing entirely.
Fixed: read `0 .. maxEntries - 1`.

**The slot index is therefore useless as a `pumpId`.** If index 0 is always the newest, every
new event renumbers every older one: today's bolus inherits yesterday's index. Feeding that to
`syncBolusWithPumpId` makes PumpSync either merge two unrelated boluses or reject a real one as
a duplicate — silent, persistent IOB corruption that cannot be cleanly undone.

**Plan §4 specified the slot index. Do not use it.** `PumpHistoryEvent.position` now carries the
pump's own **sequence number**, which was verified strictly monotonic and unique across both
logs and does not move when new events arrive. This is a deliberate departure from the plan,
and it is the reason the sync switch was gated off rather than shipped on.

| log | count | index range read | seq range | timestamps |
|---|---|---|---|---|
| events | 3000 | 2950–2999 (old code) | 156828–156880 | 2026-03-14 → 03-15 |
| alerts | 142 | 92–141 (old code) | 392–67766 | 2024-06-11 → 2025-02-28 |

**[verified] The 2000-01-01 epoch is right.** Converted timestamps land on plausible real dates
across a two-year span, so `HistoryEntry.parse`'s offset is correct.

**[verified] The corrected read returns today's activity, matched against known actions.**
After fitting a cartridge and starting the pump, the read returned:

| idx | time | type | |
|---|---|---|---|
| 0 | 21:07 | 14 Pump Mode Changed | `v1=10` — the pump being set to Running |
| 1–4 | 20:37–20:40 | 4 Priming Finished | primed 0.03 / 0.15 / 0.14 / 2.00 U |
| 5 | 20:37 | 16 Rewind Finished | the cartridge change itself |

That is exactly what was done to the pump, in order, with correct timestamps — the strongest
confirmation available that ordering, epoch and type IDs are all right.

**[verified] Third independent confirmation of the delivery-mode values.** History type 14
"Pump Mode Changed" carries the new mode in `v1`: `3` when stopped, `10` when running. That
agrees with byte 0 of the system status and with the pump's own display — three separate
sources.

**[verified] Event fields**, checked against actions performed on the pump minutes earlier:

| idx | time | type | v1 | actual action |
|---|---|---|---|---|
| 0 | 21:58 | 2 Bolus Immediate | 150 | a **1.5 U** bolus → decodes to 1.50 U |
| 1 | 21:54 | 9 Basal Profile Temp Running | 130 | a **130%** TBR |
| 2 | 21:07 | 14 Pump Mode Changed | 10 | set to **Running** |
| 3–6 | 20:37–20:40 | 4 Priming Finished | 3/15/14/200 | four primes, 0.03–2.00 U |
| 7 | 20:37 | 16 Rewind Finished | — | the cartridge change |

So `2` carries bolus centi-units in `v1`, `9` carries TBR percent, `14` carries the new delivery
mode, and `4` carries the primed amount. The 9 U tube-change threshold behaved correctly: the
largest prime was 2.00 U, so none was recorded as a tube change.

**⚠ `12 Date Changed` carries a pre-change timestamp.** One such entry has `ts` = 2022-01-01
while `v1/v2/v3` = 29/7/26, i.e. the *new* date being set (2026-07-29). The entry's own
timestamp is whatever the clock said before the change, so events around a clock change are not
chronologically trustworthy. `toHistoryEvent` does not map type 12, so nothing reaches PumpSync
from it — keep it that way, and be careful if `sincePosition` filtering ever sorts by timestamp
rather than sequence.

**`24 Battery Removed` is unverified, but nothing observed argues against it.** Three of these
appear in the log, all dated **2026-03-29** — 122 days before the cartridge change, and not
corresponding to anything done in the bring-up session. Today's cartridge change logged a
rewind, four primes and a mode change and *no* battery event, which is correct. Whether type 24
means a genuine battery swap is still unconfirmed.

(An earlier revision of this file claimed these events fired *during* the cartridge change and
warned they would write spurious treatments. That was a misreading: their clock time, 20:41, is
close to the cartridge change's 20:37, and the dates were not checked. Compare full timestamps,
not times of day — history spanning two years makes that mistake easy.)

### Writing history into AAPS is still disabled by default

`YpsoSyncHistoryToAaps` defaults to **off**. Reads and mapping run regardless; only the
`PumpSync` writes are gated. The `pumpId` blocker above is now resolved — what remains before
switching it on is confirming the event-type mapping and bolus amounts against the pump display,
and deciding how `sincePosition` should filter already-seen events.

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

### Pump identity — settled

The research unit is **10074292**, MAC `EC:2A:F0:01:22:34` — the pump in the decrypted Lotus
export. **10178681** (seen in the photos) is a different, standard pump running mylife CamAPS FX
for actual therapy. Both advertise as `YpsoPump_*` and both appear in the scan list, so the
pairing screen makes the user confirm the serial before bonding. **Never select 10178681.**

### Bring-up, in order

1. **Make the plugin's tab visible.** Config Builder → Pump → YpsoPump → tick the visibility
   checkbox. Pump fragments default to `visibleByDefault = false`, so without this the status
   screen with Refresh and Read history simply is not there.
2. **Bond.** Preferences → YpsoPump → "Pair YpsoPump", pick the pump, confirm the serial, then
   confirm the code on the pump. If nothing appears in the list, lower `YpsoRssiThreshold` before
   assuming a scan problem — and note a *connected* pump may stop advertising, so if you are
   re-pairing you may need to forget it in Android Bluetooth settings first.
3. **Connect + auth.** Watch for `Authenticated (plaintext, no key exchange)` then
   `Session ready`. **[verified]**
4. **If it connects but the status read fails:** flip `YpsoLegacyAuthSalt` (§3) and retry — but
   the default salt is now confirmed correct for V05.02.03, so suspect framing (§4) first.
5. **Parity check** against the pump's display: reservoir, battery, delivery state, active
   program, the 24 basal rates. **[verified]** — and this is what found the battery and
   delivery-mode bugs, neither of which was visible from the driver's own output. Do it again
   after any decode change.
6. **History.** "Read history" on the YPSO tab. Compare types and timestamps against the pump's
   history screen, then start the `idx`/`seq` observation in §5.
7. Leave the sync switch off until §5 step 3 or 4 is answered.

Firmware confirmed **V05.02.03**.

---

## 8. What session 3 (saline delivery) needs

Blocking work, roughly in order:

1. **Finish verifying the history mapping** (§5). Reads, ordering, epoch and `pumpId` are
   settled; what is left is checking event types and bolus amounts against the pump's own
   history screen, then deciding how `sincePosition` filters already-seen events. Only then
   turn `YpsoSyncHistoryToAaps` on.
2. **Confirm the unmapped status bytes** (§4): bytes 6–9 as active basal rate, byte 10 as TBR
   percent. Byte 10 is the only TBR candidate found, and no TBR support is possible without it.
3. **Extend the delivery-mode map** (§4) by observation — start, stop, prime and bolus the pump
   and record byte 0 each time. Until then anything but `3` and `10` reads as unverified and
   `isDelivering` stays false, which under-reports a delivering pump.
4. **`syncDateTime` first** — plan §7 step 5, the first non-delivering write, with nothing at
   stake. `YpsoPumpManagerImpl.syncDateTime` currently refuses; `bleManager.syncTime` underneath
   is vendored and intact — **but it still writes through `writeFramesSequentially`**, and §4b
   showed this firmware rejects framed writes of short payloads with
   `GATT_INVALID_ATTRIBUTE_LENGTH`. Expect to strip the framing there too. That is precisely why
   syncDateTime is the right first write: it fails harmlessly.
5. **Then delivery**, on saline, off-body, in plan §7 step 6 order: 0.05 U bolus (with progress
   and abort) → TBR → basal write. The SDK methods exist and are wired to the pump; what has to
   be written is the `YpsoService` entry point and the `YpsoPlugin` override. Note the bolus and
   TBR payloads also go out framed — check each against §4b before trusting a "sent" result.
6. **Bolus progress** should come from collecting `pumpEvents` / the bolus-notification
   characteristic into `EventOverviewBolusProgress` — a Flow collection, not Medtrum's sleep-poll.
   Scale the completion timeout to the configured bolus speed (`YpsoBolusSpeed`, 12 U/min).
7. **Re-check the refusals.** Every `REFUSED` log line and every `notImplemented()` in
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
- `FileSystemException: core/interfaces/build/.../classes.jar: The process cannot access the
  file because it is being used by another process` means **two Gradle daemons are fighting over
  the build directory**. `./gradlew --stop`, then rebuild.
  - Android Studio being open is one way to get there, and is what session 1 recorded — but it
    is not the only way, and the fix is the same either way. Session 2 hit this twice with
    Android Studio **closed**: invoking `gradlew` alternately from PowerShell and from Git Bash
    starts a second daemon, because the differing environment doesn't match the first daemon's
    JVM args. Pick one shell for Gradle in this repo and stay in it.
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

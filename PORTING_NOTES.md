# YpsoPump driver — porting notes

Driver for an **unlocked plaintext research YpsoPump** on current AndroidAPS (3.4.2.4).

**Status after session 2: the read path is verified on hardware.** The driver bonds, connects,
authenticates in plaintext with no key exchange, and reads reservoir, battery, delivery state,
active program, the 24-slot basal profile, the active temporary basal and the event and alert
history — every value cross-checked against the pump's own display or against actions performed
on the pump minutes earlier.

**Session 3: the write path is open. [verified] on hardware, 2026-07-30.** Setting the pump's
clock (§4e) is implemented, and it worked: with the pump's clock deliberately wound back 14
months, the driver wrote the phone's date and time, read them back, and the pump's own display
and its own history log both agree. That is the first write this driver has ever made that
changed the pump's state.

Everything that could move insulin is **still refused**: there is no code path from AAPS to a
delivery command. Phase 2 of `SALINE_TEST_PROCEDURE.md` (standard bolus, on saline) is the next
gate and has no code yet.

Test pump: **YpsoPump_10074292**, MAC `EC:2A:F0:01:22:34`, firmware V05.02.03 — the unit whose
settings are in the decrypted Lotus export. (Serial 10178681 is a *different*, standard pump
running mylife CamAPS FX; see §7.)

Findings marked **[verified]** below were confirmed against the pump display. Everything else is
still inference from the sources.

---

## 0. Read this first — safety posture

This build delivers no insulin. That is enforced in three independent places, deliberately, so
no single edit can undo it:

1. **The SDK has no encryption or key-exchange code at all.** vicktor's `crypto/` and
   `keyexchange/` packages were not vendored. There is no `PumpCryptor` to leave null and no
   relay client to accidentally call. Plaintext is a property of what exists, not a setting.
2. **`YpsoService` exposes no delivery method.** No bolus, TBR or basal-write entry point is
   declared on it. This is an absence of method, not a flag that could be flipped. The one
   write it does expose is `syncPumpTime`, which sets the clock (§4e).
3. **`YpsoPumpManagerImpl` refuses delivery without touching BLE.** The `PumpManager` interface
   requires `deliverStandardBolus`, `setTbr`, `writeBasalProfile` and friends, so they exist —
   and each logs `REFUSED` and returns failure before reaching the pump.

`YpsoPlugin` overrides every delivering `Pump` method individually rather than relying on a
shared default, so "this does nothing" is visible at each call site and re-enabling one has to
be a deliberate act.

**A successful GATT write proves nothing on this pump.** It does not NAK a bad write — the
wrong auth password produced a perfectly successful write and only failed on the *next read*
(§3). So every write must be confirmed by a subsequent read-back or history entry, never by the
write callback, and a method may only report success once the pump has confirmed the effect.
`syncDateTime` is built that way and is the pattern for everything that follows: it reads the
clock first, refuses to write at all if it cannot (no read-back means no confirmation), and
reports success only when the clock reads back agreeing with the phone's.

**Never make a refusing or failed method report success.** Session 1's notes recorded the same
rule after Andy Rozman's dummy connector returned `success=true` for boluses; anything above a
driver that trusts a delivery result will act on it.

**Remove refusals one at a time, each with its own saline test.** Never in a batch. The
procedures are in `SALINE_TEST_PROCEDURE.md`, one phase per refusal, each naming the read-back
or history entry that confirms it.

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

## 4. Wire format — five corrections to the vendored SDK

**Upstream's framing does not match firmware V05.02.03.** Four separate bugs, each found by
looking at raw bytes rather than at the code's own assumptions; together they broke every read.
The fifth (§4e) is the same lesson applied to the write path, and is the one still unproven.

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

### 4e. Short writes are bare — observed, not assumed **[verified]**

#### ⚠ Correction: writes to this pump already work — just not *command* writes

The plan for this session opened with "no command write has ever succeeded, the write path is
unproven". The first half is true; the second is not, and the distinction matters because it
changes what phase 1 is actually testing.

**Two write paths are already proven on hardware:**

| Write | Shape | How we know it lands |
|---|---|---|
| `CHAR_AUTH_PASSWORD` | 16 bytes bare, no CRC | authentication succeeds, and reads afterwards return data (§3) |
| `CHAR_SETTING_ID` / history `INDEX` via `writeGlb` | 8 bytes bare (GLB pair) | **every setting read is a write-then-read.** `readSetting(14)` writes index 14 and reads back 1.20 U/h; a history read writes each entry index and gets that entry back. Distinct indices return distinct, correct values — impossible unless the index write lands |

So the fixed-length, unframed reading in §4b is not an inference from one failure. It is
supported by two independent writes that work, in two different shapes, both bare.

What has *never* been done is a write that **changes pump state** rather than selecting what to
read next. That is the real gap, and it is what phase 1 tests. It also means the interesting
unknown is narrower than "does writing work": it is whether the command characteristics
(`CHAR_SYSTEM_DATE`/`TIME`, `CHAR_BOLUS_START_STOP`, `CHAR_TBR_START_STOP`) use the same
fixed-length convention as the ones that already work.

#### The remaining question

4b established that an 8-byte GLB write must go out bare, because framing it to 9 bytes gets
`GATT_INVALID_ATTRIBUTE_LENGTH` (13). 4a established that reads carry no framing header either.
Together with the two proven writes above, this firmware appears to use **fixed-length
attributes** throughout, in both directions, and the framing header belongs to some other
firmware revision than V05.02.03.

But "unframed" leaves a second question open — whether a CRC still terminates the message — and
the three short messages already observed answer it three different ways:

| Message | Length | Integrity check |
|---|---|---|
| GLB setting value (§4c) | 8 bytes bare | the GLB's own bitwise complement |
| system status reply (§4a) | 18 bytes + 2 | CRC-16 |
| auth password (§3) | 16 bytes bare | none |

So a short write is either bare or CRC-terminated, and nothing in the sources says which applies
to the clock characteristics. Upstream sent them **both** CRC-terminated *and* framed, which 4b
says cannot be right.

**Rather than swap one assumption for another, `syncTime` reads each characteristic before it
writes it and matches the shape that came back.** A reply of exactly the payload length is
written bare; a reply two bytes longer ending in a valid CRC is written with a CRC. Anything
else — a third length, or fields that do not decode to a plausible date — aborts *without
writing*. There is no third envelope this code knows how to produce, and guessing at one is how
a pump ends up with a garbage clock.

The read is also what proves the field layout, which was previously only inferred from
upstream's write: `CHAR_SYSTEM_DATE` as year u16 LE + month u8 + day u8, `CHAR_SYSTEM_TIME` as
hour + minute + second. If the pump's clock decodes to a date that matches its own display, the
layout is right.

#### ⚠ [verified] Both clock characteristics are bare — no framing, no CRC

Read from the pump on 2026-07-30. The reply is exactly the payload length in both cases:

| Characteristic | Bytes observed | Envelope | Decodes to |
|---|---|---|---|
| `CHAR_SYSTEM_DATE` (`…fcbedc3b7bc5`) | `E9 07 05 1C` (4B) | **bare** | 2025-05-28 |
| `CHAR_SYSTEM_TIME` (`…fcbedd3b7bc5`) | `01 08 32` (3B) | **bare** | 01:08:50 |

So the fixed-length reading is right, and it now rests on **four** observed messages rather than
an inference: the auth write (16B bare), the GLB write (8B bare), and both of these.
Upstream's `chunkPayload(appendCrc(datePayload))` would have offered 7 bytes where the pump
wants 4, and been refused with `GATT_INVALID_ATTRIBUTE_LENGTH` exactly as §4b's GLB write was.

**The field layout is confirmed too** — year u16 LE, month u8, day u8 / hour, minute, second.
The clock was read as 2025-05-28 01:08 while the pump's own screen displayed 28/05/2025 01:08,
and successive reads advanced in step with real time, which is what rules out these being a
static build date rather than the live system clock. (Their UUIDs sit in the same family as
`CHAR_BASE_VERSION` and `CHAR_SETTINGS_VERSION`, so that was worth ruling out.)

#### ⚠ [verified] The write works, confirmed four independent ways

With the clock deliberately wound back 14 months (skew −37,000,842 s):

```
WRITE  …fcbedc3b7bc5 : EA 07 07 1E    0x07EA=2026, 07, 0x1E=30   = 2026-07-30
WRITE  …fcbedd3b7bc5 : 07 09 20       = 07:09:32
read   back           : EA 07 07 1E / 07 09 20    ← identical
```

1. **Read-back** of both characteristics returned exactly the bytes written; final skew −1 s.
2. **The pump's own display** showed the correct date and time afterwards.
3. The status screen's pump-clock row read `In sync with phone (1 s)`.
4. **The pump's own history logged the change**, through a path that does not touch these
   characteristics at all — see §5.

That fourth one is the strongest, because it is the pump describing what happened to it rather
than the driver reading back its own write.

**The bolus and TBR payloads still go out framed** — `sendBolusCommand`, `startTbr` and
`cancelTbr` all still call `chunkPayload`, as do the generic `sendCommand` and `writeMultiFrame`.
That is deliberate: they are refused anyway, and the envelope rule should be settled by the
harmless write before it is applied to anything that moves insulin.

Two predictions worth writing down before they are tested, so they can be scored honestly:

- **The bolus payload is 13 bytes + CRC and framed to 16.** If §4e's fixed-length reading is
  right, that is refused with status 13 and the framing has to come off, exactly as it did for
  the GLB write.
- **`startTbr`/`cancelTbr` send a 16-byte payload of two GLB pairs — percent with its complement,
  minutes with its complement — with no CRC, framed to 17.** That is the same *shape* as the
  8-byte GLB in §4b, just doubled, and §4b says that shape goes out bare. Expect the framing to
  come off and no CRC to be needed.

**The basal profile write is the exception: it is already unframed.** It goes through
`writeSetting` → `writeGlb`, which §4b already fixed to send the bare 8 bytes. So phase 4's write
path has, in a sense, already been exercised — every basal profile *read* writes a setting index
through it, and those reads work.

The read-before-write approach paid for itself immediately and should be reused: it cost one
round trip, it removed the guess about the envelope, and the same read is the confirmation
channel. Any future short write should do the same rather than hard-coding "bare" from this
finding — the bolus and TBR characteristics are different attributes and have not been observed.

(A length probe was designed as a fallback in case the characteristics would not read — try
candidates in ascending length, treat status 13 as "wrong length, next". It was never needed
and is not implemented.)

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

### ⚠ [verified] The status payload is fully mapped — including the temporary basal

An earlier revision of this section listed bytes 6–17 as unmapped and recorded
`PumpStatus.activeTbr` as a known gap that "no field carrying TBR percentage and remaining time
has been identified" for. **That is out of date.** Setting a temporary basal on the pump moved
three fields at once and made the rest of the 18 bytes readable:

```
0A  2B 16 00 00  02  9C 00 00 00  82 00 00 00  F0 00 00 00
│   └reservoir┘  │   └── rate ─┘  └─ TBR % ─┘  └─ TBR min ┘
└mode           battery bars
```

| Bytes | Field | How it was confirmed |
|---|---|---|
| 6–9 | the rate the pump is *actually* running, centi-units/hour | 1.20 U/h under a 130% TBR read back as exactly **1.56 U/h** |
| 10–13 | TBR percentage, **100 when none is running** | read `130` while a 130% TBR was set on the pump |
| 14–17 | TBR minutes remaining | counted down once a minute across successive reads |

The 1.56 U/h is what makes this more than three plausible offsets: it is the programmed rate
scaled by the percentage in the neighbouring field, so the three belong together rather than
happening to sit next to each other. The pump's own history agreed independently, logging type
`9` with `v1=130` for the same TBR.

So `activeTbr` is populated for real and `activeBasalRate` carries the effective rate.
**Byte 10 needs no further confirmation** — the phase-3 prerequisite the plan described as
blocking is already satisfied, and what is left for temporary basals is only the write.

`baseBasalRate` is the active program's rate for the current hour and is **0.0 until the profile
has actually been read** — the driver reports what it knows rather than a plausible guess. It is
deliberately *not* taken from bytes 6–9, which carry the TBR-scaled rate rather than the
programmed one.

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

**[verified] on hardware, and the findings below are why this section is long.** History counts
read through the same `readGlb` path as the basal profile, and entries through the same
index-write-then-value-read mechanism. `HistoryEntry.parse` — the 17-byte entry layout, the
2000-01-01 epoch and the event-type IDs — is confirmed against actions performed on the pump
minutes before the read. Two things about it were *wrong* in the sources, and both would have
been silent; they are recorded next.

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

### ⚠ [verified] Clock-change entries, and what they proved about the write

Setting the clock over BLE (§4e) produced these two entries, the two newest in the log:

| idx | seq | type | v1 | v2 | v3 | meaning |
|---|---|---|---|---|---|---|
| 1 | 160285 | 12 Date Changed | 30 | 7 | 26 | new date **2026-07-30** |
| 0 | 160286 | 13 Time Changed | 7 | 9 | 32 | new time **07:09:32** |

`07:09:32` is exactly the payload that went out (`07 09 20`), so the pump independently
described the change the driver made. Because this arrives through the history characteristics
rather than the clock ones, it is the confirmation that does not depend on the driver reading
back its own write.

**New: `13 Time Changed` carries the new time as `v1/v2/v3` = hour/minute/second**, the same
shape as type 12's day/month/year. Earlier revisions recorded type 12's fields but not type 13's.
A manual clock change made on the pump minutes earlier logged the same pair (`28/5/25` and
`0/51/0`), so the encoding is the same whether the change came over BLE or from the pump's
own menus.

Neither type is mapped to PumpSync, and both should stay unmapped: their timestamps are
pre-change, so syncing them would place therapy records at the wrong time.

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
`PumpSync` writes are gated. Two of the three blockers are now resolved: `pumpId` carries the
pump's sequence number, and the event types and bolus amounts are verified against actions
performed on the pump. **The one remaining question is how `sincePosition` should filter entries
already seen** — and note the type-12 trap above, which rules out sorting by timestamp.

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
  bolus progress timing and bolus step, both of which arrive with phase 2. Applying nano-bolus
  would mean editing `PumpType.YPSOPUMP`, which the old `:pump:ypsopump` module also reads — not
  worth touching until a bolus can actually be delivered.

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
   screen with Refresh, Read history and Set pump clock simply is not there.
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
   history screen. **[verified]** — the `idx`/`seq` question this step used to open is answered
   in §5, and index 0 is the newest entry.
7. Leave `YpsoSyncHistoryToAaps` off until `sincePosition` filtering is decided (§8, last item).
8. **The clock write** — phase 1 of `SALINE_TEST_PROCEDURE.md`. **[verified]** Also the quickest
   way to prove the write path still works after any change to the framing code.

Firmware confirmed **V05.02.03**.

**Two cosmetic decode faults noticed while running phase 1, neither blocking:**

- `ypso_serial_number` is stored as `10074292 ` — `getStringValue(0)` on the device-name
  characteristic keeps the NUL terminator. It is harmless on screen but it also feeds
  `pumpSerial` on every `PumpSync` call, so it wants fixing **before** `YpsoSyncHistoryToAaps`
  is switched on, or records could split on a serial mismatch.
- Firmware displays as `0.2.2.1` where the pump says **V05.02.03**. `formatVersion` joins the
  raw bytes as decimal, which is not this characteristic's encoding. Cosmetic, but it is the
  field a user would quote in a bug report.

---

## 8. What remains before insulin

Two of the four items this section used to list are now settled and have been struck: the
status bytes are fully mapped (§4, including byte 10 as TBR percent), and the history mapping is
verified against actions performed on the pump (§5). What is left is the write path, and it is
gated one step at a time by `SALINE_TEST_PROCEDURE.md`.

**Phase 1 is done.** ✅ The clock write passed on hardware on 2026-07-30 (§4e), so the write
path is open and the envelope rule is observed rather than guessed.

**Next, in order, one refusal per hardware test:**

1. **Standard bolus** (phase 2). Add the `YpsoService` entry point and the `YpsoPlugin` override
   → `deliverStandardBolus`. **Read `CHAR_BOLUS_START_STOP` before writing it**, the way
   `syncTime` reads the clock characteristics — the 13-byte payload currently goes out framed
   and CRC'd, which §4e says will be refused, but "bare" should be confirmed on that specific
   attribute rather than assumed from the clock's. **Abort is part of this phase, not a
   follow-up:** wire AAPS's bolus-stop to `cancelBolus()` and confirm a partial delivery in
   history before the phase counts as done. Confirm every bolus by the history entry (type 2,
   `v1` in centi-units), never by the write.
3. **Bolus progress** — collect `pumpEvents` / the bolus-notification characteristic into
   `EventOverviewBolusProgress`. A Flow collection, not Medtrum's sleep-poll. Scale the
   completion timeout to the configured bolus speed (`YpsoBolusSpeed`, 12 U/min); a progress bar
   that finishes early is worse than one that finishes late.
4. **Temporary basal** (phase 3). Only the write is left — the read side is confirmed (§4).
   `setTempBasalAbsolute` converts U/h→% in the plugin, clamped to `tbrPercentRange`, and must be
   checked at a profile-block boundary. Confirm via history (type 9, `v1` = %) *and* the byte-10
   read-back.
5. **Basal profile write** (phase 4). AAPS `Profile` → 24 `BasalBlock`s; read back and diff all
   24 in `Double` (§4d). Record the pump's existing 24 rates first — this one is destructive.
6. **Delivery-mode map.** Extend `DeliveryMode` by observation as the phases above run: record
   byte 0 while priming, bolusing and running a TBR. Anything but `3` and `10` still reads as
   unverified, and `isDelivering` stays false, which under-reports a delivering pump.
7. **`YpsoSyncHistoryToAaps`.** Still off. Ordering, epoch, `pumpId` and the event mapping are
   all settled (§5); what remains is deciding how `sincePosition` filters entries already seen.
   Its own gate, because getting it wrong corrupts IOB silently rather than visibly.

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

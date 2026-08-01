# YpsoPump driver — porting notes

Driver for an **unlocked plaintext research YpsoPump** on current AndroidAPS (3.4.2.4).

**Status, 2026-08-01: all four delivery phases are verified on hardware, and the remaining work is
observation rather than capability.** The driver bonds, connects, authenticates in plaintext with no
key exchange, reads every value it needs, and **writes** — the pump clock (§4e), a standard bolus
with abort (§4f), a temporary basal with cancel (§4h), and the 24-slot basal profile (§4i). Each was
gated behind its own saline test and each is confirmed by reading the pump back, never by the write
callback.

Deliveries reach `PumpSync` under the pump's own sequence number, pump alarms reach AAPS as urgent
notifications (§4g, §8.2), and history sync is **built but switched off** (§8.5).

⚠ **This driver can now move insulin.** What stops it doing so is the cartridge, not the code. Every
test to date has been on **saline, off-body, cannula to air**, and §8.1 lists what must still be true
before that changes.

**What stands between here and an insulin trial is that the pump has states nobody has looked at.**
Byte 0 of the system status is identified for two values out of 256 (§8.3), and an unidentified one
makes AAPS hold the loop off while still counting basal insulin it is not receiving. That is now
*instrumented* — every status read logs the raw byte and the whole payload, and a bolus logs its own
— but instrumented is not observed. `SALINE_TEST_PROCEDURE.md` Phases 5, 6 and 7 are the procedures,
and all three are unrun.

Test pump: **YpsoPump_10074292**, MAC `EC:2A:F0:01:22:34`, firmware V05.02.03 — the unit whose
settings are in the decrypted Lotus export. (Serial 10178681 is a *different*, standard pump
running mylife CamAPS FX; see §7.)

Findings marked **[verified]** below were confirmed against the pump display. Everything else is
still inference from the sources.

---

## 0. Read this first — safety posture

**This section used to say the build could not deliver insulin, enforced in three independent
places.** That was true through session 2 and is comprehensively false now: `YpsoService` exposes
bolus, temporary basal and basal-profile entry points, and `YpsoPumpManagerImpl` carries them out.
The refusals were removed one at a time, each behind its own hardware test, which is what §8.1
records. It is left stated plainly here because a stale safety claim is worse than none — anyone
reading top-down would otherwise take a delivering driver for an inert one.

**What still is true:**

1. **The SDK has no encryption or key-exchange code at all.** vicktor's `crypto/` and
   `keyexchange/` packages were not vendored. There is no `PumpCryptor` to leave null and no
   relay client to accidentally call. Plaintext is a property of what exists, not a setting — so
   this driver only works against an unlocked research pump, and cannot talk to a standard one.
2. **The extended and combined bolus are still refused**, in `YpsoPumpManagerImpl`, before
   touching BLE (§8.4). `YpsoPlugin` overrides them individually rather than relying on a shared
   default, so "this does nothing" stays visible at the call site.
3. **`YpsoSyncHistoryToAaps` is off**, so nothing the pump did on its own reaches AAPS's treatment
   records yet (§8.5).
4. **Nothing added this session can move insulin.** The delivery-mode instrumentation is reads and
   log lines only. The one read it added to the delivery path — a system-status read during a bolus
   — is deliberately kept out of the status pipeline, so that looking at an unidentified state
   cannot cause the driver to act on it (§8.3).

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

### ⚠ …but that rule is about *reporting*, not about *recording IOB*

These are two different claims and their safe directions are **opposite**. Getting them confused
produces a driver that is scrupulous about one and dangerous about the other.

| | What it is | Safe direction |
|---|---|---|
| `PumpEnactResult.success`, the dialog, the log | a statement **about the pump** | never exceed what the pump confirmed |
| The treatment record AAPS counts as IOB | a **safety estimate** | err **high** |

Over-stating IOB makes AAPS dose *less*. Under-stating it causes stacking, which is the hypo
direction. So a bolus whose outcome is unknown must still be **recorded in full** while being
**reported as failed** — `success = false` with a full-amount treatment record is coherent, and it is
what should happen when a bolus is sent and the connection then dies.

This matters here because **the pump owns the bolus once it has been told**: delivery continues
whether or not the phone is still there. See §4g's temporary-id record, which is how the window
between sending and confirming stops being a window in which AAPS knows nothing.

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

## 4. Wire format — six corrections to the vendored SDK

**Upstream's framing does not match firmware V05.02.03.** Four separate bugs, each found by
looking at raw bytes rather than at the code's own assumptions; together they broke every read.
§4e and §4f are the same lesson applied to the write path, and both are now verified on hardware.

**The one-line version, if you read nothing else here:** the envelope is chosen **per
characteristic**, not per firmware, and the pump **never rejects a wrong payload** — it accepts the
write and does nothing. Only a length error comes back (`GATT_INVALID_ATTRIBUTE_LENGTH`, 13).
Assume nothing carries over from one attribute to the next, and confirm every write by an effect
you can go and read.

### The envelope table — **[verified]**, and no two of these were predictable

| Characteristic | On the wire | Integrity |
|---|---|---|
| `CHAR_AUTH_PASSWORD` | 16 B bare | none |
| `CHAR_SYSTEM_DATE` | 4 B bare | none |
| `CHAR_SYSTEM_TIME` | 3 B bare | none |
| GLB settings / history index | 8 B bare | the value's own complement |
| `CHAR_BOLUS_START_STOP` | 13 B **+ CRC-16** = 15 B | CRC |
| `CHAR_TBR_START_STOP` | 16 B bare | two GLB complements |
| `CHAR_SYSTEM_STATUS` (read) | 18 B + CRC = 20 B | CRC |
| `CHAR_BOLUS_STATUS` (read) | **3 frames**, 42 B + CRC | CRC + framing |

Nothing is framed except `CHAR_BOLUS_STATUS`, which is framed *and* CRC'd. The bolus command is
the only write carrying a CRC. Upstream, incidentally, sent the bolus with a CRC and the TBR without
one — so it had this difference encoded all along, and losing that detail while stripping the
framing is what cost two hardware attempts.

**This is why `writeCommandPayload` discovers rather than assumes.** Each of the three
state-changing writes needed a different answer, and going from the clock to the bolus by
assumption failed. Candidates are complete, correct encodings differing only in wrapper, a wrong
length is refused by the ATT layer without reaching the pump, and the accepted one is remembered
per characteristic. The map is now **seeded** with the two verified answers so the normal case costs
no round trips — before that, every app start rediscovered facts already written down here — and
discovery stays as the fallback, entered when a seeded envelope is refused for its length.

On the TBR, before seeding, discovery cost two harmless rejections and 300 ms:

```
18B payload+CRC        → refused (13)
19B frame(payload+CRC) → refused (13)
16B bare               → ACCEPTED
```

### 4a. Reads carry no framing header

The system-status reply is a bare CRC-terminated message. Logged verbatim off the pump:

```
03 42 14 00 00 02 00 00 00 00 64 00 00 00 00 00 00 00  15 30
└─ payload (18 bytes) ─────────────────────────────┘  └CRC┘
```

CRC-16 over the first 18 bytes is `15 30` — it matches, so the 20-byte read is complete.
Recomputing with the leading byte treated as a framing header does *not* match. Upstream assumed
reads are framed like writes, took the low nibble of the first *data* byte as a frame count, read
`0x03` as "frame 1 of 3", and chased continuation frames on `CHAR_EXTENDED_READ` that do not
exist — `GATT_INVALID_OFFSET` (status 7), killing every session about a second after auth.

`readExtended` now validates the CRC first and returns the message when it is already complete.
**The CRC is the discriminator, never the nibble** — the nibble may be data, and in this frame it
is: the leading `0x03` is the delivery mode.

(An earlier revision of this section printed a different frame,
`03 8B 16 00 00 02 78 … F1 52`, whose payload is real but whose CRC is not — `F1 52` is not the
CRC of those 18 bytes. `YpsoWireFormatTest` is what caught it, which is the argument for having
these vectors in tests rather than only in prose. The frame above is copied from a log.)

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

### 4f. The bolus command — **[verified]**, and every assumption about it was wrong once

Three separate mistakes, each caught by a different check. Worth reading in order, because the
lesson is the same each time: **this pump's conventions are per attribute, and it never says no.**

**1. The envelope is not the clock's.** §4e found the clock characteristics take a bare payload,
and carrying that over to `CHAR_BOLUS_START_STOP` got `GATT_INVALID_ATTRIBUTE_LENGTH` on a 13-byte
write. Upstream's flow (`appendCrc` → encrypt → `chunkPayload`) puts 16 bytes on the wire for a
plaintext pump, so 13 was never right — it came from stripping framing that should only have been
stripped where the pump asked for it. `CHAR_BOLUS_START_STOP` is **write-only** (properties `0x08`),
so there is nothing to read the envelope from. Candidates were tried in ascending order and the
first was accepted:

```
13B bare                   → refused, GATT status 13
15B payload+CRC, unframed  → ACCEPTED     ← 32 00 00 00 00 00 00 00 00 00 00 00 01 9B D3
```

So the convention across this firmware is: **simple registers bare, command and status channels
CRC-terminated**, and nothing is framed. The accepted envelope is remembered per characteristic.

**2. Upstream's payload layout was right after all.** With 15 bytes accepted, the pump delivered —
but the driver could not see it, and the temporary conclusion was that the layout must be wrong. A
GLB-style replacement (`total, ~total, duration, ~duration, type`) was written and reverted
unrun: the reservoir settled it, falling 51.86 → 51.30 U across a 0.5 U bolus. Upstream's
`total(4) + duration(4) + immediate(4) + type(1)` is correct.

**3. ⚠ The pump clears its bolus status when delivery ends, and that is not "no delivery".**
`CHAR_BOLUS_STATUS` reads `status=0 injected=0 total=0` once a bolus finishes. A 0.5 U bolus takes
2.5 s at 12 U/min, so every poll landed after it and saw zeros — which was read as "the pump never
started". **The driver reported failure and 0.0 U delivered for boluses that had gone in**, twice,
before the reservoir gave it away. On insulin that is AAPS under-counting IOB for a dose the
patient received.

So **the outcome comes from the pump's event log, never from its bolus status**: read the newest
sequence number before sending, then find the newer bolus entry afterwards. `type 2` with `v1` in
centi-units. If the baseline cannot be read, nothing is sent. If no entry appears afterwards the
result is "not confirmed" — never "zero delivered", because it may have gone in and not been
logged yet.

The status characteristic keeps two jobs it is good at: live progress, and the **total the pump
echoes back**, which cancels delivery on a disagreement. That is the only check that can catch a
misparsed payload *layout*, since a length check cannot.

**⚠ A bolus stopped from AAPS is logged as type 2, not type 29.** A 5 U bolus stopped after ~3 s
logged `type=2(Bolus Immediate) v1=182` — a completed-bolus entry carrying the partial amount.
Type 29 `Bolus Immediate Abort` was never written. So a cancellation cannot be recognised from the
entry type; the driver's own "stop was requested" flag is what distinguishes a user stop from the
pump falling short on its own. Both fail, but only the second means something is wrong with the
pump.

**Also: `CHAR_BOLUS_STATUS` is genuinely multi-frame**, unlike `CHAR_SYSTEM_STATUS` (§4a). It
answers in three frames assembled through `CHAR_EXTENDED_READ`:

```
13 …(19B)   0x13 = frame 1 of 3
23 …(19B)   frame 2 of 3
33 00 00 00 00 3E DB   frame 3 of 3 — 4 data bytes then the CRC
```

19 + 19 + 4 = the 42-byte payload the decoder expects. So "reads are not framed" is true of the
system status and false here — another per-attribute difference, and the reason the frame-count
fallback in `readExtended` has to stay.

**Bolus speed looks faster than the configured 12 U/min.** 1.82 U went in over roughly 4.4 s,
nearer 25 U/min. It only affects the completion timeout, which is generous, so nothing is broken —
but `YpsoBolusSpeed` should not be trusted as a rate until it is measured properly.

#### ⚠⚠ [verified] Cancelling on the *pump* made AAPS record the full amount

2026-07-31. A 4 U bolus was started from AAPS and stopped on the pump's own buttons partway.
**AAPS recorded 4.0 U. The reservoir says about 2.5 U went in.** IOB over-counted by roughly 1.5 U,
reported as a success, with every check the driver had passing.

Two independent defects lined up, and each alone would have been caught by the other:

**1. The pump rewrites the bolus entry in place, exactly like the TBR entry (§4h).** Type
**19 `Bolus Immediate Running`** while delivering, becoming type **2 `Bolus Immediate`** when it
stops — same sequence number, with `v1` rewritten from the amount *requested* to the amount
*given*. The driver's `STANDARD_BOLUS_TYPES` accepted type 19, so it read:

```
Bolus confirmed from the pump's log: seq=160492 type=19(Bolus Immediate Running) v1=400 → 4.0 U
deliverBolus: asked 4.0U, pump delivered 4.0U, success=true
```

`400` is the 4.00 U that was asked for. **The confirmation was the command, echoed back.** The
entire discipline of this driver is that a write is confirmed by an effect you go and read, never by
the command — and it was defeated by a log entry that restates the command. It was justified in a
comment on the grounds that "missing the entry entirely is worse than matching a type that never
appears"; the type does appear, and matching it is worse than missing it.

**2. Leaving `DELIVERING` was treated as finishing.** `isTerminal` was `status != IDLE && status !=
DELIVERING`, a blacklist, so every one of the 254 values nobody had observed counted as an end
state. The pump-side cancel produced status **2**, which is in no table here:

```
06:35:13.733  BolusNotification: 02 …    Pump bolus status left DELIVERING (Unknown(2))
06:35:15.200  Bolus confirmed from the pump's log … type=19 … → 4.0 U
06:35:15.914  BolusNotification: 04 …    COMPLETED — 2.2 s after the outcome was decided
```

So the log was read during the window in which the pump had not yet revised it. Had the wait run to
`COMPLETED`, the entry would already have settled to type 2 with the partial.

**Both fixed.** `isTerminal` is now a whitelist of `CANCELLED` and `COMPLETED` only. `findBolusRecord`
treats a type-19 match as *not yet an answer* and re-reads until the entry settles; if it never
settles the entry is still returned, but `bolusOutcome` refuses to call it complete at any value and
says plainly that the figure is the request rather than the delivery. Tests pin both.

**✅ [verified] Re-tested on hardware, 2026-07-31.** A 2 U bolus cancelled on the pump:

```
Pump bolus status left DELIVERING for Unknown(2) — not terminal, still waiting
Bolus confirmed from the pump's log: seq=160493 type=2(Bolus Immediate) v1=58 → 0.58 U
  — Pump delivered only 0.58 U of the 2.0 U requested — check the pump
deliverBolus: asked 2.0U, pump delivered 0.58U, success=false
```

Right amount, right verdict. A full 2 U bolus straight afterwards still confirmed at 2.0 U with
`success=true`, so nothing regressed.

**The whitelist was initially too strict, and the same log showed it.** Neither bolus ever reached a
terminal *polled* status: `COMPLETED` arrives on the **notify** characteristic, by which time the
polled status has already been cleared to `IDLE`. So every bolus ran to the elapsed-time fallback —
correct, but 14 s here and close to a minute for a large bolus cancelled early.

`IDLE` now counts as finished once `DELIVERING` has been seen. It is a *mapped* state meaning nothing
is running, and this section already established the pump clears its bolus status when delivery ends,
so `IDLE`-after-`DELIVERING` is exactly that clearing. Status `2` still does not count, which is the
distinction that matters: both observed endings are `DELIVERING → IDLE` (completed) and
`DELIVERING → 2 → IDLE` (pump-side cancel).

**[verified] The entry did settle, and to the right number.** A history dump 19 minutes later:

```
idx=0  seq=160492  type=2(Bolus Immediate)  v1=249   → 2.49 U
```

Same sequence, now type 2, `v1` rewritten from `400` to `249`. The reservoir fell 16.90 → 14.34 U
across the bolus and the basal either side of it, which agrees. **Waiting was all that was needed** —
the correct answer was going to be there, and the driver read the entry a second and a half too
early and then reported the wrong number as a success.

**The general rule this pump keeps teaching, now in a third place:** an entry that is still being
written is not evidence, and "not the state I was waiting for" is not the same as "the state I was
waiting for has passed". Clock, TBR and bolus have each produced one variant of it.

### 4g. Recording a bolus, and raising the pump's alarms — **[verified]**

**Boluses reach `PumpSync` under the pump's own sequence number.** That identity exists only
because delivery already confirms from the event log (§4f), and it is deliberately the same one
`readHistory` puts in `PumpHistoryEvent.position` — so a bolus recorded at delivery time and the
same bolus seen later by a history sync resolve to **one** record rather than two. This is the
entanglement §8 warned about, and it dissolves once both paths name the event the way the pump
names it.

Verified end to end on 2026-07-30:

```
baseline seq 160368 → confirmed seq=160369 type=2 v1=150 → 1.5 U, success=true
Recorded 1.5U under pumpId=160369, new=true
```

with the treatment then visible on the AAPS home screen at the right time and IOB reading 1.50 U.

#### ⚠⚠ The window between sending and confirming, and how AAPS is built to cover it

**This pump owns the bolus once it has been told.** The command carries the whole amount and delivery
continues to completion whether or not the phone is still connected — dropping BLE does not stop it.
So the driver's job is not to supervise a bolus; it is to be able to find out afterwards what
happened.

The original design got the ordering wrong. It sent the bolus, waited, read the log, and recorded the
treatment **only then**. Between the write landing and the confirmation returning, AAPS held **no
record at all** — and that window opens on every single bolus. A dropped connection, a killed app or
a flat battery inside it means insulin went in and AAPS never learned of it. Under-counted IOB is the
stacking direction.

**AAPS has a mechanism built for exactly this**, and the machinery is already in the app — the
`PumpSyncStorageTBR=[]` line in our own logs is `:pump:common`'s `PumpSyncStorage` starting up unused:

1. `addBolusWithTempId(…)` **before** sending, at the amount *requested*, under a temporary id. From
   that instant it counts towards IOB.
2. `syncBolusWithTempId(…)` once the outcome is known, with the *actual* amount and the pump's real
   `pumpId`. The record is updated in place — amount corrected, identity promoted.
3. `PumpSyncStorage` persists pending entries across an app restart, for drivers that need it.

The interface contract states the intended failure mode outright: *"If `syncBolusWithTempId` is not
called afterwards record remains valid and is calculated towards iob."* **An unreconciled record
survives as an over-estimate rather than vanishing.**

Implemented as `openPendingBolus` / `settlePendingBolus`. The confirmation logic of §4f is untouched —
this adds the missing first half rather than replacing the second. Three details worth keeping:

- **A bolus the pump never logged is left alone**, at the requested amount, still counting. Deleting
  it would assert that nothing was delivered, which is the one thing not known.
- **If the provisional record cannot be found to settle** — an app restart in the middle would do it —
  the confirmed amount is written under the pump's sequence instead, rather than being lost.
- **The reported result is unchanged.** `success` still comes only from the pump's own log, so
  `success = false` alongside a full-amount IOB record is the correct outcome for an interrupted
  bolus. See §0 on why those two directions are opposite.

**Still open, and the other half of the same problem:** a bolus given **on the pump itself** never
reaches AAPS at all, because nothing reads history automatically — `loadHistory` runs only when the
user presses the button, and `YpsoSyncHistoryToAaps` is off. Same identity scheme, same reconciliation
(§8.5); until it is solved, a manual bolus is invisible to the loop.

Three rules, each chosen for a reason:

- ~~**Unconfirmed bolus → not recorded.**~~ **Reversed.** This said an invented record was worse
  than a missing one. It is the other way round: the pump owns the bolus once told, so a missing
  record under-states IOB and AAPS doses on top of insulin already given. An unconfirmed bolus is now
  recorded **in full** and reported as failed — see the temporary-id record below, and §0 on why
  those two directions are opposite.
- **Partial delivery → recorded** for what actually went in. This is the case where *not* recording
  corrupts IOB.
- **Timestamp comes from the phone at the moment of delivery.** See the epoch trap in §5. ⚠ It does
  not stay that way: once history sync is on, `syncBolusWithPumpId` upserts on `pumpId` and **updates
  the timestamp**, so the pump's entry time wins on the next poll. That is deliberate (§8.5) — one
  authority rather than two — and it is why the pump's clock is now actively held within 10 s (§8.6)
  rather than merely watched.

**The amount stays a `Double` end to end.** `BolusDeliveryResult.deliveredUnits` is a `Float`, and
widening 1.82f gives 1.8200000524520874 — §4d's trap, on a figure that becomes a treatment record.
`deliverBolusConfirmed` carries the `Double`, the sequence and the timestamp; the `PumpManager`
contract method keeps the `Float` form for interface compliance only.

**Pump alarms now reach AAPS.** An occlusion stopped the pump during phase 2 and AAPS learned
nothing, because the alert log is only read on request. Each status poll now reads the alert
**count** — one GLB read — and fetches entries only when it has moved, so noticing costs almost
nothing for something that changes rarely. Occlusion, cartridge empty, no cartridge, auto-stop,
battery empty and reusable error raise an urgent notification with sound; every one of them means
insulin has stopped.

The stored count is a **high-water mark of what the user has been told about**, persisted on every
change rather than only when an alarm fires. That distinction is the whole feature: persist only on
alarms and an alert arriving while AAPS is closed gets absorbed into a fresh baseline at the next
start, which is precisely the moment AAPS could have said the pump had stopped.

**⚠ Not every notice the user sees on a pump app is a pump alert.** On 2026-07-31 the mylife
controller raised `Insulin remaining 18.2U passed 20U` while AAPS was talking to the same pump, and
the pump's alert count **did not move** — 144 before, 144 after, across every poll either side. So a
low-reservoir notice is computed by the *controller* from the reservoir reading, not logged by the
pump, and no amount of watching the alert log will ever surface it. If AAPS is to warn on a low
reservoir it has to do the same arithmetic on the phone. This matters for judging the alert feature:
the alert log carries events the pump itself raises, and reasoning about coverage from what another
app displays will overstate what is there to be seen.

**Also fixed: the NUL terminator on the pump's string characteristics.** `getStringValue` returns
it as part of the value, so the serial was stored as `10074292` plus `U+0000`. Invisible on screen,
but it becomes `pumpSerial` on every `PumpSync` call, and a serial differing by one unprintable byte
would split records across two apparent pumps.

### 4h. Temporary basal — **[verified]**

Confirmed on hardware 2026-07-30. Envelope: **16 bytes bare** (see the table above), two GLB pairs
— percent with its complement, minutes with its complement.

```
Starting TBR: 150% for 30min
writing 16B to …fcbee38b7bc5 as payload: 96 00 00 00 69 FF FF FF 1E 00 00 00 E1 FF FF FF
Status: Rate: 1.8U/h, TBR 150% 30min left
TBR confirmed by read-back: 150% with 30min left, logged as seq=160371 v1=150 v2=30
Recorded TBR 150% under pumpId=160371, new=true
```

**The load-bearing number is 1.8 U/h.** The programmed rate is 1.20 U/h and 1.20 × 150% = 1.80
exactly, so the pump is *running* the requested rate rather than merely echoing the percentage. The
same arithmetic confirmed 1.56 U/h at 130% in session 2. An echoed percentage on its own would not
distinguish "accepted the command" from "changed the delivery".

**Confirmation is byte 10 of the system status, not the history log** — the opposite choice from the
bolus, for a concrete reason: byte 10 is *not* cleared while the temporary basal runs, so it can be
read at any time and means what it says. The bolus status is cleared on completion (§4f), which is
what made it useless as evidence there.

**Cancel** is confirmed by byte 10 returning to 100, also verified. Cancelling when nothing was
running reports success with "no end entry to record" — correct on both counts: the caller's intent
(no temporary basal) is satisfied, and there is genuinely nothing for `PumpSync`.

**A 100% request is treated as a cancellation**, because 100 is how this pump expresses "none".
Setting it would otherwise leave AAPS believing a temporary basal was running while byte 10 says
none is.

### ⚠ The maximum percentage — and a guard that prevented its own test

Upstream had `tbrPercentRange = 0..200`; `PumpType.YPSOPUMP.tbrSettings` declares **500%**. Nothing
in the sources settles which is right.

The first attempt kept the narrow bound on the grounds that over-stating what the pump accepts is
the dangerous direction. On hardware that turned out to be self-defeating: a 250% request was
**refused by the driver before it reached the pump**, so the one experiment that could answer the
question was the one thing the guard made impossible. Guarding against the pump's limits by guessing
them is not a guard — it is a different guess, wearing the costume of caution.

The range now matches what AAPS declares, and **the pump is the arbiter**. That is safe precisely
because every set is confirmed by reading byte 10 back: a rate the pump will not honour shows up as
a read-back disagreeing with the request, and a wrong rate cannot be reported as a right one.

**[verified] The pump accepts 250%.** Asked for 250% for 15 minutes:

```
TBR: FA 00 00 00 05 FF FF FF 0F 00 00 00 F0 FF FF FF
Status: Rate: 3.0U/h, TBR 250% 15min left
TBR confirmed by read-back: 250% with 15min left, logged as seq=160373 v1=250 v2=15
```

1.20 U/h × 250% = **3.00 U/h exactly**, so it is genuinely running the rate.

**So upstream's 0–200% is simply wrong**, and the narrow guard would not merely have blocked the
experiment — it would have capped this driver at 200% for good, silently withholding a capability
the pump has. The true ceiling above 250% is still unmeasured; the pump arbitrates, so it does not
need to be guessed.

### ⚠⚠ [verified] The pump rewrites the TBR history entry in place

**A temporary basal has one history entry, not two.** The type-9 "Temp Running" entry *becomes* a
type-10 "Temp" entry when the TBR stops, and `v2` is rewritten from the requested duration to the
duration it actually ran:

| `seq=160371` | type | `v1` | `v2` |
|---|---|---|---|
| while running (19:46:57) | **9** Temp Running | 150 | 30 — requested |
| after a cancel (19:48:48) | **10** Temp | 150 | **2** — actual |

**What this broke.** The cancel looked for an entry *newer* than the TBR's own, found nothing, and
so never called `syncStopTemporaryBasalWithPumpId`. AAPS was left with an open temporary basal — the
ACT tab still offered "CANCEL 150%" while byte 10 read 100 — meaning it kept assuming elevated basal
delivery for the remaining 28 minutes. Basal **over**-counting, which is the direction that hurts.

Two fixes: the search is now `>=` the entry that existed before the cancel rather than `>`, and it
retries because the rewrite can lag. And, decisively, **the AAPS record is closed on the strength of
byte 10 regardless of what the log yields** — falling back to a timestamp id if the entry cannot be
identified. A possible dedupe collision on a sync switch that is off is a far smaller problem than a
temporary basal AAPS believes is still running.

#### ⚠ …and the `>=` was still wrong, because there is no lower bound at all

`>=` was measured against `newestEventSequence()`, on the reasoning that a running TBR's entry *is*
the newest one. That holds for a cancel a minute after the set, which was the only case phase 3 ever
ran. It fails the moment the pump logs anything else in between.

A 0% TBR cancelled after nine hours had three newer entries — two basal-slot changes and a daily
total — so its own `seq=160374` sat **below** the baseline and was filtered out. The cancel was
confirmed by byte 10, the entry was sitting at index 3 the whole time, and the driver still reported
"no end entry found" and closed the record under a timestamp id. The fallback did its job, which is
the only reason this was cosmetic rather than harmful.

The entry is written when the TBR **starts** and never re-appended, so no function of "the newest
sequence now" can bound it. The bound is gone. AAPS already holds the `pumpId` of the temporary basal
it believes is running, so `cancelTempBasal` passes that down as the identity to go and find; without
one — an app restart having lost it — the newest TBR-typed entry in the window stands in. The search
window went 5 → 12 entries: at index 3 after nine hours, one more event would have pushed the entry
out of a 5-entry read no matter what the filter said.

**[verified] Reproduced and fixed in one minute rather than nine hours:** set 149%, deliver a 0.5 U
bolus so a newer entry lands on top, cancel.

```
TBR confirmed by read-back: 149% with 15min left, logged as seq=160462 v1=149 v2=15
Bolus confirmed from the pump's log: seq=160463 type=2 v1=50 → 0.5 U
TBR cancel confirmed by read-back: byte 10 is back to 100, logged as seq=160462 type=10
Recorded TBR stop under endPumpId=160462, new=true
```

**A TBR's effective rate is not on the basal step grid.** 149% of 1.20 is 1.788 and the pump reported
**1.79 U/h** — rounded to 0.01, not to the 0.02 step that governs stored rates above 1 U/h. A
temporary basal can therefore put the pump on a rate that could not be programmed into a profile slot.

**The reservoir reading is not a delivery meter.** It fell 18.08 → 17.49 across that 0.5 U bolus
(0.59), but the preceding 33 minutes at 1.2 U/h moved it only 0.19 against an expected 0.66. It is
sound as gross confirmation — it is what disproved "the pump never delivered" in §4f — but an amount
cannot be checked against it.

**[verified] The entry's `timestamp` is the TBR's *start*, and it survives the rewrite.** `seq=160371`
was read at 19:52, after the cancel, and its timestamp decoded to 19:46:55 — the moment the TBR was
set (19:46:57), not the moment it stopped.

**That makes a finished TBR fully reconstructable after all**, which resolves what looked like a
history-sync blocker: a type-10 entry carries the start (`timestamp`), the percentage (`v1`) and the
duration it actually ran (`v2`). It is not an "end event with a missing start" — it is the whole
temporary basal.

`YpsoHistoryMapper` now maps types 9, 10 and 32 all to `TbrStarted`, because on this pump each of
them describes a complete temporary basal. Mapping type 10 to `TbrEnded` threw away the percentage
and the duration and handed AAPS an end for a start it had never seen — a read taken after the TBR
finished never sees the type-9 state at all. Both states share the sequence number, so both produce
the same `position` and PumpSync sees one temporary basal rather than two.

### 4i. Basal profile write — **[verified]**

Confirmed on hardware 2026-07-31, five writes on pump 10074292. The wire format needed no work:
`writeBasalProfile` → `writeSetting` → `writeGlb`, the bare 8 bytes already exercised by every
profile *read*. What was untested was writing a setting **value** rather than a setting **index**.

A 24-slot profile crossing both step boundaries — `0.10…0.55` on the 0.01 grid, `1.00…1.18` on 0.02,
`2.00 2.10 2.00 2.50` on 0.1 — was written and **all 24 slots read back exactly**, with hour 0 and
hour 23 checked against the pump's own display. Two later writes that differed in exactly one hour
changed that hour and left the other 23 alone, so the write is slot-precise.

**Rounding holds both ways.** 1.23 U/h entered in AAPS stored as **1.24** (the 0.02 step above 1
U/h), and `isThisProfileSet` still agreed afterwards. That only works because the write and the
comparison both go through `hourlyRatesFor`; had they rounded independently, a correctly written
profile would read as not set and AAPS would rewrite it on every loop iteration for ever.

**[verified] Type 7 `Basal Profile A Changed` is `v1 = slot, v2 = new value, v3 = previous value`**,
centi-U/h — and **the pump logs only slots whose value actually changed**:

```
seq 160383  type 7  v1=0 v2=120 v3=2     slot 0: 0.02 → 1.20
seq 160384  type 7  v1=1 v2=120 v3=96    slot 1: 0.96 → 1.20
```

Two entries for a write that set all 24 slots, because only two slots held anything different. So a
basal-profile-changed entry cannot be used as the confirmation that a write happened — a write that
changes nothing logs nothing. The read-back diff is the confirmation, which is what the code uses.

Confirmed at scale by a 50-entry dump: a write that genuinely changed all 24 slots produced **24
consecutive type-7 entries**, `seq=160465…160488`, slots 0–23 in order, each carrying its own before
and after (`v1=10 v2=100 v3=120` — slot 10, 1.20 → 1.00). Single-slot edits produced exactly one
entry each (`v1=18 v2=124 v3=116`, `v1=6 v2=124 v3=40`, `v1=2 v2=2 v3=20`). The write is slot-precise
in the pump's own record, not merely in the read-back.

Note the cost this implies for a history sync: **one profile write can add 24 entries**, which is
half the default 50-entry read window. Any `sincePosition` scheme has to cope with a burst that size.

**⚠ AAPS fires this write by itself.** `setNewBasalProfile` is called whenever `isThisProfileSet`
returns false, so un-refusing it meant the most destructive command in the driver ran **15 seconds
after the app started**, unattended, before the phase had been gated by a human. It behaved correctly
— read the old program, logged it, wrote, read back all 24 — but the phase discipline in
`SALINE_TEST_PROCEDURE` assumes a person presses a button, and for this one command that assumption
is simply false. Anyone repeating phase 4 should expect the write to have already happened.

**A zero rate cannot arrive from AAPS**, so the `-1`-versus-`0` question for an unprogrammed slot is
unreachable in the write direction: the profile editor refuses zero and `ProfileSealed` hard-limits
basal to `[0.01, maxBasal]`. The **read** direction is still live — `readBasalRateSequential` maps a
`-1` slot to `0.0`, which no valid AAPS profile can equal, so a pump holding an unprogrammed slot
makes `isThisProfileSet` false permanently and AAPS offers the write every loop iteration. It heals
on the first write; it is worth recognising rather than debugging from scratch.

**⚠ Two controllers can reach the research pump.** The old study phone running Lotus writes the basal
profile unprompted when it wakes. It did so twice on 2026-07-31, each time looking like an
unexplained pump-side change, and it is the likeliest origin of the `[0.02, 0.96, …]` program found
that morning. Before treating a pump-side change as a driver bug, establish that no other app is
connected.

**⚠ Inherent, not a bug: a percentage is only correct for the basal block it was computed in.** When
the pump crosses into an hour with a different programmed rate, the same percentage becomes a
different absolute rate. Unavoidable on a percent-only pump; AAPS re-evaluating each loop iteration
is what bounds the exposure. `setTempBasalAbsolute` therefore divides by the rate **the pump** is
programmed to run — read from the pump, not taken from the AAPS profile, which is a different number
until a profile write exists — and refuses outright when that rate has not been read yet rather than
inventing a divisor.

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
| 9 / 10 / 32 TBR running / completed / aborted | `TbrStarted(pct, minutes)` — **all three**, see below |
| 6 / 7 / 8 basal profile changed | `BasalRateChanged` |
| 16 rewind finished | `CartridgeChanged` |
| 4 priming, 25 cannula priming, 24 battery removed | `Unknown` carrying type + values |
| 100–108 alerts | `Alert` |

⚠ **That TBR row used to read `10 / 32 → TbrEnded`, and the mapper has not agreed with it since
§4h.** This pump keeps **one** entry per temporary basal and rewrites it in place, so a type-10
entry is not an end event with no start — it is the whole temporary basal, described completely:
started at its timestamp, ran `value2` minutes at `value1` percent. Mapping it to `TbrEnded` threw
away the percentage and the duration and handed AAPS an end for a start it had never seen, because
a read taken after the TBR finished never sees the type-9 state at all.

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

⚠ **Both rows are the *oldest* slots**, because they were captured before the index direction was
corrected. They say nothing about what is at the top of either log, and reading the alert row as
"the newest alert is from 2025" is what later cast unwarranted doubt on the alert feature (§8.2).

### ⚠ [verified] Events and alerts share one sequence counter

The 2026-07-31 dump read both logs newest-first, and the two interleave in one space:

| | newest sequences |
|---|---|
| events | …160462, 160463, …160492 |
| alerts | 160319, 160350 |

Alert `160350` sits *between* event sequences. So `position` is unique across **both** logs, not just
within one, and a single monotonic watermark is enough for `sincePosition` (§8.5) — no per-log
bookkeeping and no risk of an alert and an event colliding on an id in `PumpSync`.

It also means the two logs are windows onto one journal, which is consistent with the alert count
moving only when an alert is appended while events keep their own count of 3000.

### ⚠ The epoch was "verified" by a check that could not fail

An earlier revision of this section said: *"[verified] The 2000-01-01 epoch is right. Converted
timestamps land on plausible real dates across a two-year span."* **That was wrong, and the
verification was worthless** — not weak, but incapable of failing.

The pump counts **local wall-clock seconds** since 2000-01-01, not seconds since 2000-01-01 UTC.
It has no concept of a time zone: `syncTime` writes local hour, minute and second into it. Adding
the UTC epoch offset therefore produced the *right wall-clock reading attached to the wrong
instant*, out by the local offset — 12 hours in New Zealand.

And that is exactly why the check passed. A local counter read as UTC hands the original wall time
straight back, so comparing "the entry says 21:58" against "the bolus was at 21:58" agrees no matter
how wrong the instant is. Both the plausible-dates check and the matched-actions table below
compared **wall-clock readings**, and neither could have detected this.

The proof, from a capture already in this file: the 1.5 U bolus delivered at 21:58 local logged
`ts=1785362330`, which under the old arithmetic decodes to 2026-07-29 **21:58:50 UTC**.

**What finally caught it was a consumer that cares about the instant.** A bolus recorded at one of
these timestamps landed 12 hours in the future, showed nowhere in AAPS and produced no IOB, while
the delivery itself reported success because the *amount* had been confirmed.

`HistoryEntry.pumpSecondsToUnix` now converts through a local calendar rather than adding a
constant, which is also correct across daylight saving — the pump's epoch falls in NZDT while July
is NZST, so a fixed offset would be wrong half the year. Two tests pin it, one of them built with
`LocalDateTime` arithmetic because subtracting two instants across a DST boundary is itself an hour
out and would have quietly agreed with the wrong answer.

**The lesson worth keeping:** matching a wall-clock reading proves nothing about an instant. To
verify a timestamp decode, check it against something that consumes the instant — IOB, a graph
position, an interval — not against how the time reads.

**The times quoted in the tables below are still correct as wall-clock readings**, which is what
they were used for. They are not correct as UTC instants.

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

- `ypso_serial_number` is stored with a trailing NUL — `10074292` followed by `U+0000`, which the
  preferences XML shows as `&#0;`. `getStringValue(0)` on the device-name
  characteristic keeps the NUL terminator. It is harmless on screen but it also feeds
  `pumpSerial` on every `PumpSync` call, so it wants fixing **before** `YpsoSyncHistoryToAaps`
  is switched on, or records could split on a serial mismatch.
- Firmware displays as `0.2.2.1` where the pump says **V05.02.03**. `formatVersion` joins the
  raw bytes as decimal, which is not this characteristic's encoding. Cosmetic, but it is the
  field a user would quote in a bug report.

---

## 8. Where this stands, and what is still unmapped

### 8.1 Confirmed on hardware

All four delivery phases are verified on pump 10074292, firmware V05.02.03, on saline, off-body.

| | What | Confirmed by | §|
|---|---|---|---|
| Read | status bytes, 24 basal rates, clock, history, alert **count** | parity against the pump's display | §4, §4e |
| Write | pump clock | read-back, the pump's display, and a type 12/13 entry | §4e |
| Write | standard bolus **and abort** | the event log's type-2 entry and its amount | §4f |
| Write | temporary basal, set and cancel | byte 10, plus the effective rate arithmetic | §4h |
| Write | basal profile, 24 slots | all 24 read back, and the pump's display | §4i |
| Record | bolus, TBR and TBR-stop into `PumpSync` under the pump's sequence | IOB on the home screen | §4g |

Three wire envelopes are established and, per the envelope table in §4, **each is a property of the
characteristic, not of the firmware**: bolus is 13 B + CRC, TBR is 16 B bare, the clock and every GLB
setting are bare. Discovery remains as a fallback for any characteristic not yet written.

### 8.2 [verified] The alert log does append — and removing the cartridge is not an alert

An earlier revision of this section over-claimed the alert feature; a later one over-corrected and
doubted whether the log grew at all. A 50-entry dump on 2026-07-31 settles it.

**The log appends, and the newest entries are the right ones.** Index 0 and 1 are the two occlusions
from 2026-07-30 — the ones that stopped the pump during phase 2:

```
idx=0  seq=160350  type=105 (Alert: Occlusion)
idx=1  seq=160319  type=105 (Alert: Occlusion)
idx=2  seq=160207  type=100 (Alert: Battery Removed)
idx=7  seq=153818  type=103 (Alert: No Cartridge)
```

The doubt came from the one earlier dump having been taken with the **old code, which read the
oldest slots** — the §5 table showing alerts ending 2025-02-28 is the *bottom* of the log, not the
top. It was an artefact of which end was read, and it is now corrected.

**So the count-based detection is sound in principle, and simply has not been exercised**: the count
has read 144 throughout because no *new* alert has occurred since the baseline was taken. The
occlusions predate it.

**⚠ [verified] Removing the cartridge does not raise an alert.** Index 0 is still the 07-30 occlusion
afterwards. The pump can raise `103 No Cartridge` — index 7 is one — but a cartridge change made
through the pump's own menu is a *user-initiated procedure that stops the pump first*, so there is no
unexpected condition to report. **A deliberate action is not an alarm.** Any test of the notification
branch has to create a condition the pump did not expect.

**The reliable one is occlusion**, which this pump has raised repeatedly: clamp or kink the line while
it is delivering. `105 Occlusion` is in the urgent set.

#### ✅ [verified] The notification branch fires — 2026-07-31

An occlusion forced on saline was logged by the pump as `seq=160532 type=105`, moving the count 144 →
145, and the whole chain ran on the next status poll:

```
Pump alert log grew from 144 to 145 — reading the 1 newest
PUMP ALERT: Alert: Occlusion (type 105, seq 160532)
Pump alert 105 (Alert: Occlusion), urgent=true
Notification received: YpsoPump: Alert: Occlusion
Starting alarm from YpsoPump: Alert: Occlusion
```

Urgent AAPS notification **with sound**. The `replay = 0` `SharedFlow` worry recorded in the test
procedure was unfounded — the service's collector was subscribed in time.

#### ⚠⚠ [verified] …but it took 27 minutes, and that is the real limit

The occlusion happened at **07:04:48**. The notification fired at **07:31:51**. Nothing was wrong;
the driver simply had no reason to talk to the pump in between.

**AAPS only polls when the command queue has work, and this driver disconnects when it is empty.**
The measured idle cadence is **20 minutes** — status reads at 07:31:49, 07:51:48, 08:11:49, evenly
spaced. So the worst case between a pump alarm and AAPS knowing about it is one full poll interval,
and the observed case was 27 minutes.

For an occlusion that is a long time to believe basal is being delivered when it has stopped. Three
things bound the harm and none of them is this driver: the pump alarms locally and loudly, the user
is normally near it, and AAPS's own pump-unreachable alert will not help because the pump *is*
reachable — it is simply not being asked.

**⚠ This was written up as a decision the user had to make. It was not one — see §8.7.** Of every
AAPS pump driver, only Insight holds a persistent connection; command-queue-driven connect and
disconnect is the norm, and this driver is behaving normally. The pump alarms locally and loudly.
Nothing here needs changing, and presenting it as an open question was overstatement.

Two caveats that bound what this feature can ever catch:

- **A low reservoir is not a pump alert** (§4g). The mylife controller computes it on the phone; the
  pump's count did not move. Nothing in the alert log will ever surface it.
- **The count is only checked in `readPumpStatus`.** Pressing **Read history** also reads the alert
  count — the 145 first appeared there — but through `readHistoryAlerts`, which does not run the
  alert check. So a path that observes the count can still fail to announce what it saw. Harmless
  today because it does not move the baseline either, but it is a second entrance to the same room.

### 8.3 ⚠ The delivery-mode byte is mapped for two values out of 256

Byte 0 of the system status is confirmed for exactly two states, both checked against the pump's own
display: **3 = Stopped**, **10 = Running**. Everything else returns `null` from `isDeliveringOrNull`
and shows as `Unverified mode N`.

`YpsoPumpManagerImpl` collapses that with `isDeliveringOrNull(mode) == true`, so **an unmapped mode
makes AAPS report the pump suspended.** That is the deliberate direction — claiming delivery that is
not happening is the error that harms — but it means any state the pump enters that we have not seen
presents to AAPS as a suspended pump, which stops the loop acting.

**⚠ Read that together with §8.6, because the two point opposite ways and both are deliberate.**
An unmapped byte 0 makes `isSuspended()` true, which holds the loop off; and it makes
`operatingMode` `UNKNOWN`, which deliberately writes **no** suspend record. So a pump sitting in an
unmapped state while genuinely stopped gets both at once: AAPS will not act, *and* it goes on
counting basal insulin that is not being delivered. Neither half is wrong on its own. Only
identifying the byte resolves the pair, which is why this is the last item before an insulin trial
rather than a tidy-up.

What is known to still read `10`: a temporary basal running (checked repeatedly). What has **never
been looked at**:

| State | Why it matters |
|---|---|
| during a bolus | if byte 0 changes, AAPS flips to "suspended" mid-bolus, every bolus |
| during priming / a cartridge change | the pump is genuinely not delivering; does it say so, or does it say something new? |
| after an alarm has stopped the pump | **the most important one.** If an alarm stops delivery and byte 0 goes to a third value, this is where AAPS should learn insulin has stopped — and it may be a better channel than the alert log |
| paused | upstream lists a `PAUSED = 7`; unobserved on V05 |
| reservoir empty mid-delivery | never seen; the reservoir field is known to read `-1` after an occlusion alarm |

#### ✅ It is now observable — 2026-08-01

Mapping this is cheap, and the reason it had not been done is that the number could not be *read* at
the moment the pump was in the state. Every status read now logs one greppable line:

```
[ypso-state] byte0=10 name='Running' mode=STARTED delivering=true suspendRecord=none rate=1.2U/h tbr=none reservoir=51.3 raw=[0A 2B …]
```

Three things about it are deliberate:

- **The raw byte and the whole payload, not just the decode.** In a state nobody has seen, the
  fields *around* byte 0 are what say whether anything else moved with it. `SystemStatusData` now
  carries the payload hex through the decode for the same reason `PumpClockData` carries its wire
  bytes: a decode is only as good as the bytes under it.
- **In logcat, under tag `PUMP`, not only on the YPSO tab.** The states that would identify these
  values — an alarm sounding, a cartridge half-changed, a bolus running — are exactly the states in
  which the phone is not the thing being watched.
- **Pump bytes and AAPS's own records on one line.** `suspendRecord=` is sampled after
  `syncSuspendedState` has run, so the line says what the poll settled on. That is what makes
  §8.6's prediction checkable rather than arguable.

**A bolus needed its own path.** Nothing else in AAPS reads the system status while a bolus runs —
the command queue is blocked behind the delivery call for its whole duration, so pressing Refresh
cannot reach it. The bolus wait loop therefore takes one reading itself, on the first 500 ms poll
that confirms `DELIVERING`, and logs `probe=during-bolus`.

⚠ **That reading deliberately does not feed `updateStatusFromSystem`.** If byte 0 does turn out to
be a third value mid-bolus, letting it through would run `syncSuspendedState` on it and open a
spurious suspend record on *every bolus* — the exact harm §8.6 predicted, caused by the act of
looking for it. The question has to be answerable without the answer being acted on.

`SALINE_TEST_PROCEDURE.md` **Phase 5** is the procedure, with a results table that stays blank until
each reading has the pump's own display recorded beside it.

#### ✅ [verified] A bolus is **not** a distinct delivery mode — 2026-08-01

The first row of the table above is answered, and it is the good outcome. A 3 U bolus on saline,
read at the first 500 ms poll that confirmed `DELIVERING`:

```
before   raw=[0A 62 42 …]  reservoir 169.94
during   raw=[0A 56 42 …]  reservoir 169.82   ← probe=during-bolus, byte0=10 'Running'
after    raw=[0A 36 41 …]  reservoir 166.94
```

**Byte 0 read `10` throughout — the same value as an idle running pump.** So AAPS does not flip to
"suspended" mid-bolus, nothing needs adding to the delivering set, and §8.6's predicted failure —
a spurious `PUMP_SUSPEND` record on every bolus — cannot happen for this reason. `suspendRecord=`
stayed `none` on every line including the polls after.

**The reservoir is what makes this an observation rather than a lucky read.** The probe's own
payload reads 169.82 U, 0.12 U below the pre-bolus figure, so the pump was genuinely mid-delivery
when it answered — not idle before the bolus started or after it finished, which is exactly the
trap §4f fell into when it read a cleared bolus status as "nothing was delivered". The total fell
by 169.94 − 166.94 = **3.00 U exactly**.

Two things confirmed in passing:

- **§4f's settled-entry rule works.** The wait ran to `Idle`, *then* read the log, and got
  `seq=160590 type=2(Bolus Immediate) v1=300` — the settled entry, never the type-19 echo.
- **§8.6's recognition rule works, against the hardest case.** A real 0% TBR for 120 minutes was
  running (pulled from Nightscout, see below) and every line still read `suspendRecord=none`. A 0%
  TBR is the closest possible thing to the driver's own suspend record, and it was correctly not
  claimed as one — the duration and type together are doing their job.

⚠ **The test rig has a third controller nobody had listed: Nightscout.** The 0% TBR above was not
set by hand; it arrived from the *other* pump's CamAPS loop through a shared Nightscout and was
enacted on the research pump. **Inbound only** — uploading is off on the test tablet, and an earlier
revision of this paragraph claimed the saline bolus had been uploaded outward. It had not. That
claim came from reading `● RUN Starting upload NEW_DATA` as evidence of an upload; it is the
NSClient *worker starting*, it runs on a schedule regardless of whether uploading is permitted, and
it says nothing about whether anything left the device. **A log line that names an action is not
evidence the action had an effect** — the same mistake as §4f's type-19 echo, made against AAPS's
own logs this time. The inbound hazard stands: anything unexplained the pump does should be checked
against NS before it is investigated as a driver fault.

#### ✅ [verified] An alarm stop reads `3`, and alarming is indistinguishable from stopped — 2026-08-01

The most important row of the table, and the good outcome. An occlusion forced during a 5 U bolus on
saline, pump display reading **"Occlusion - check infusion set"**:

```
byte0=3 name='Stopped' mode=STOPPED delivering=false suspendRecord=open
raw=[03 0F 40 00 00 02 00 00 00 00 64 00 00 00 00 00 00 00]
```

**No third value.** An alarm stop reports the same `3` as a stop from the pump's own buttons, so the
driver already learns of it through the delivery-mode byte and acts without needing to be taught
anything new. The whole §8.6 chain ran unprompted: the suspend record opened, `delivering=false`,
basal IOB stopped accruing. The alert fired too — count 146 → 147, `105 Occlusion`, urgent — **26
seconds** after the occlusion, because AAPS was already connected for the bolus. §8.2's 27-minute
worst case is the *idle* path, not this one.

**⚠ But alarming and acknowledged-stopped are byte-identical.** Four readings while the alarm sounded
and four after acknowledging it produced the same 18 bytes, to the byte. So **byte 0 says the pump
has stopped and never says why** — §8.2's alert log is not made moot by this, it is the complement
to it. The driver reads both, which is what it needs to do.

`3` now covers three situations with no way to tell them apart from this characteristic: stopped by
button, stopped by alarm, and alarming.

**The bolus reported honestly under a real occlusion.** Asked 5.0 U, `type=2 v1=295` → 2.95 U,
`success=false`, settled to 2.95 U under `pumpId=160594`; the reservoir fell 166.94 → 163.99, which
is 2.95 U exactly. §4f's partial-amount path had only ever been exercised by a user cancel.

**And one thing nobody asked:** the alarm stop **cleared the running temporary basal on the pump**.
Byte 10 went from `0%, 112 min left` to `100`. Worth knowing before trusting a TBR to survive an
alarm.

#### ⚠ Found by watching: `cancelTempBasal` closes the suspend record

Not a state-mapping finding — it fell out of the same log. With the pump stopped and a suspend
record open, AAPS issued a temporary-basal cancel:

```
17:11:23  suspendRecord=open since 5:11 pm
17:11:52  cancelTempBasal: confirmed=true
          TBR is stopped but the entry AAPS expected (seq=1785561083912) was not in the log
          Recorded TBR stop under endPumpId=… , new=true          ← ended the suspend record
17:12:44  Pump is not delivering … new=true                        ← a second record opened
```

`syncStopTemporaryBasalWithPumpId` stops whatever temporary basal is currently running and does not
check whether that is the `PUMP_SUSPEND` record. So for 52 seconds **AAPS believed basal had resumed
while the pump was stopped.** The next poll re-asserted it, which is only quick because someone was
pressing Refresh; on idle polling the window is **up to 20 minutes of phantom basal IOB**. That is
the failure §8.6 exists to prevent, reached by a third door — after the `StateFlow` conflation and
the original missing record.

Note also `seq=1785561083912`: the driver went looking in the *pump's* log for the suspend record's
`pumpId`, which is a synthetic timestamp and can never be there. The two identifier spaces are
mixed.

**✅ Fixed**, after the hardware session ended rather than during it — changing delivery-adjacent
code while a human is running tests is how a test session becomes a debugging session.

`cancelTempBasal` now recognises the suspend record before it acts, and leaves it alone. The
pump-side cancel still runs, because asking a stopped pump to drop a temporary basal is harmless and
there may genuinely have been one programmed; what stops is the AAPS record being closed. Only the
pump reporting `STARTED` may close it, which `syncSuspendedState` already does.

**The two branches now argue opposite cases, and that is the point.** For a real temporary basal,
leaving the record open is the harm — AAPS assumes elevated delivery that has stopped. For the
suspend record it is exactly inverted: open means AAPS assumes *no* basal, which is true, and
closing it invents basal nobody is delivering. Same action, opposite sign. Telling the two apart is
what the distinctive type and duration are for, and this is the second place that has had to.

The synthetic-id hunt is gone too: the suspend record's `pumpId` is no longer passed to the log
lookup, because a millisecond clock reading was never going to be found in a log of pump sequence
numbers.

⚠ **No unit test pins this.** `:pump:ypso` has no test source set — the service needs Android test
doubles and does not have them — so `SALINE_TEST_PROCEDURE.md` **Phase 6f** is the regression test,
including the opposite case: a real TBR cancel must still close its record. Recording that plainly
rather than implying coverage that does not exist.

### 8.4 Other unmapped states, in rough order of how much they could cost

1. **A bolus while a temporary basal runs.** Never tried together. Both are confirmed from the event
   log by sequence, and a bolus entry landing between a TBR's entry and its cancel is exactly the
   condition that broke `findTbrEndRecord` (§4h) — which is now fixed, but the *forward* case, a TBR
   set while a bolus's entry is the newest, has not been exercised.
2. **BLE loss mid-command.** Pull the pump out of range during a bolus, a TBR write and a profile
   write. Since §8.8 the bolus case is actively handled — a provisional record goes in before sending
   and `ReconnectManager` chases the link back — but none of that has run on hardware. The profile
   write stops at the first failed slot and reads back to show how far it got, which has never been
   seen happen. A half-written basal program is the worst outcome the driver can produce.
3. **A temporary basal crossing an hour boundary.** Inherent, not a bug (§4h), but it has never been
   watched happen and the size of the discontinuity is unmeasured.
4. **The pump stopped from its own buttons.** Does AAPS notice, and how fast? The latency question is
   now **measured** — a 20-minute idle poll interval, and 27 minutes from occlusion to notification
   (§8.2) — but a *stop* is a different signal from an *alert*: it should show as delivery-mode 3,
   and whether that reaches `isSuspended()` promptly has never been watched. **Procedure written:**
   `SALINE_TEST_PROCEDURE.md` Phase 6.
5. **Reservoir `-1`.** Coded for (`reservoirKnown`), never observed. Likewise an unprogrammed basal
   slot reading `-1` → `0.0`, which would make `isThisProfileSet` false permanently (§4i).
   **Procedure written:** Phase 5c reaches it by removing the cartridge, and 5e by running one dry.
6. **Low battery.** Only 2-of-4 bars has ever been seen. The mapping of 0 and 1 bars, and whether the
   pump alerts, are both unknown — and AAPS acts on pump battery level.
7. **Bolus and basal caps.** Event types 33 and 34 exist for them. Whether the pump refuses an
   over-cap bolus, and what the driver then reports, is untested — and "the pump refused" must not
   surface as success.
8. **Extended and combined bolus.** Still refused by the driver. Out of scope until the standard
   bolus has soaked, but they are two of the four bolus types the history mapper already decodes.
9. **The event log filling or wrapping.** The count reads 3000 and looks capped. What happens to
   sequence numbers at the cap decides whether `sincePosition` can work at all (§8.5).
10. **A DST transition.** `pumpSecondsToUnix` converts through a local calendar and is tested across
    a boundary in unit tests, but no real transition has occurred while the driver was running.
11. **Two controllers at once.** Now known to be more than theoretical: the study phone running Lotus
    writes the basal profile unprompted when it wakes (§4i). What happens if both are connected
    during a delivery command is unknown, and the driver holds its connection rather than releasing
    it between commands.

### 8.5 History sync — built, and still switched off

**A bolus given on the pump's own menu reached AAPS nowhere.** Nothing read history automatically:
`loadHistory` ran only when the user pressed the button. So a manual bolus was invisible to the loop,
IOB was under-stated, and AAPS would dose on top of insulin already delivered — the stacking
direction. That was the largest gap left before insulin, and it is now implemented in
`YpsoService.syncHistoryToAaps`, running on every status read when the preference is on.

**What made it tractable was a mistaken assumption falling away.** `syncBolusWithPumpId` had been
treated as fragile; its contract is an **upsert keyed on `pumpId`** — *"If exists, amount, type (if
provided) and timestamp is updated"*. Re-reading an entry is therefore harmless, and the watermark is
an optimisation rather than a correctness requirement. Two other things settled it: events and alerts
share **one** monotonic sequence counter (§5), so a single watermark covers both; and `pumpId` is
already the pump's sequence rather than the slot index, which is what lets a bolus recorded at
delivery and the same bolus seen later resolve to one record.

**Shaped on Medtrum's `syncRecords`** — a persisted sequence watermark, read forward from it, advance
it, and treat a hole as a safety event. Dana does the same with a timestamp watermark and a
one-minute overlap, which only works because the sync is idempotent.

Three rules, each for a reason that cost something to learn:

| Rule | Why |
|---|---|
| **No watermark means take a baseline, not import everything** | Switching this on would otherwise turn the pump's recent history into treatments carrying IOB, including boluses from before AAPS was installed. The alert count already works this way (§4g) |
| **~~Entries the driver recorded itself are excluded~~** | **Dropped.** A sync *is* a sync: it upserts on `pumpId`, the amount comes from the same log entry either way, and the only field that moved was the timestamp. Keeping a skip list defended half a problem — manual boluses have no source but the pump — and created two classes of record with different provenance. The clock is the thing worth defending, so it is now corrected to within 10 s instead (§8.6) |
| **A gap is announced, not stepped over** | If the oldest entry read is above the watermark, entries between them existed and were never seen. The watermark still advances, or the same window is re-read for ever, but the user is told treatments are missing rather than left to infer it from IOB that does not add up |

#### ⚠⚠ [fixed] The gap warning could not fire, for any input

2026-08-01, found by reading the code rather than by running it. The rule above was right and the
number it was handed was not.

`missedSince` took the oldest sequence across **both** logs. The two do share one counter — that is
true, and it is why a single watermark covers them — but they are read as **two separate windows**
of 50 entries each, and they fill at rates that are nothing like each other. This pump has logged 50
events in an afternoon and 50 alerts since **2025**. So the union's oldest entry was always an
ancient alert, always far below any watermark, and `oldest - watermark - 1` always clamped to zero:

```kotlin
oldestSequence           // ≈ 150000, an alert from 2025
watermark                // ≈ 160500
(150000 - 160500 - 1).coerceAtLeast(0)   // → 0, always
```

Including in the one case it exists for — a burst of events overflowing the 50-entry event window
while the alert window sits three years back. **The check reported "no gap" in exactly the situation
that produces missing treatments.**

Measured per window now, and reported as the worse of the two, because each window is separately
what could overflow. A log with nothing above the watermark contributes no gap, or every poll would
announce one. Four tests pin it, including the case the old arithmetic could not see.

**This is the third confirmation in these notes that could not fail** — after the type-19 bolus echo
that restated the request (§4f) and the epoch "verified" by a check that passed for any epoch
(§5). All three read as working right up until the day they were needed. The pattern is not the
pump: it is a check whose *inputs* were never examined as carefully as its logic.

The decision logic is pure — `HistoryReadResult.freshEvents` and `missedSince` — and pinned by
thirteen tests, because it is the last gate before pump history becomes insulin on board. Note that
`freshEvents` **re-sorts by sequence**: `readHistoryWithDump` hands its events over sorted by
timestamp, and a clock change makes those go backwards.

**⚠ Still off, and switching it on is its own gate**, now written up as
`SALINE_TEST_PROCEDURE.md` **Phase 7**. Where the blockers stand:

| Blocker | Status |
|---|---|
| The gap warning is inert | ✅ **Cleared 2026-08-01.** It was measured across the union of both logs and could not fire; measured per window now, with tests |
| TBR reconstruction | ✅ **Cleared.** The entry's timestamp is the start and survives the rewrite, so a type-10 entry describes a complete temporary basal (§4h) |
| First enable must import nothing | ⏳ **Still open — and the reason a human has to watch.** It is the moment a baseline bug surfaces as phantom IOB. Phase 7a |
| A manual bolus arrives once, at the right time | ⏳ **Still open.** Phase 7b and 7c. 7c is what the dropped skip list used to defend: the timestamp moving from the phone's to the pump's is expected, and how far it moves measures whether the 10 s clock correction is working |
| What happens at the log cap | ⏳ **Still open, and not testable on demand.** The event count reads 3000 and looks capped. If sequences wrap or restart there, a monotonic watermark stops being sound |
| Cost under a real loop | ⏳ **Still open, measurable in Phase 7d.** ~195 ms per entry, and one basal profile write appends 24. A 50-entry window on every connection is ~10 s — fine now, far too slow for a loop issuing SMBs every five minutes |

### Unit tests

`:pump:ypso:sdk` now has a test source set, with **65 tests** covering the decodes the hardware
findings live in: the CRC and GLB formats (§4a–4c), the full 18-byte status payload against both
captured samples (§4), the 17-byte history entry and its 2000 epoch (§5), the clock decode and its
plausibility guard (§4e), and the history mapping — including that `position` is the sequence
number and never the slot index, which is the regression that would corrupt IOB.

They earned their keep on the first run by failing: §4a's illustrative frame carried a CRC that is
not the CRC of its payload. Prose drifts; a vector in a test does not.

`:pump:ypso` itself is still untested — the plugin and service need Android test doubles, and the
decodes were the valuable part.

Also worth doing eventually, none of it blocking: a history screen (Medtronic's
`MedtronicHistoryActivity` is the pattern), and deleting `:pump:ypsopump` once the last useful
reference is mined out of it.

---

## 8.6 What the other AAPS drivers do that this one did not

A read of the whole `Pump` interface against Combo v2, Medtrum, DanaRS, Medtronic, Omnipod (Dash and
Eros), Insight, EOPatch, Equil and Diaconn, done 2026-07-31. Two of these were real defects and are
fixed; the rest are recorded with a recommendation.

### ⚠⚠ [fixed] A stopped pump went on contributing basal IOB

**The biggest thing missing, and it was invisible.** `isSuspended()` was implemented and correct, and
it does stop the *loop* acting — but **AAPS's IOB accounting never consults it.** Basal insulin on
board is computed from the profile and whatever temporary basals are on record. So a pump that has
stopped, whether by the user's hand or because an occlusion halted it, kept contributing basal IOB
that was not being delivered, indefinitely, with nothing in the UI to say so.

Every driver that can detect a stopped pump answers this the same way: **hold a 0% temporary basal
open for as long as the pump is stopped**, typed `PUMP_SUSPEND` — the type reserved for a pump
stopping of its own accord rather than AAPS asking for zero. `MedtrumPump.setFakeTBRIfNotSet` is the
clearest version; Combo maps its own `COMBO_STOPPED` to the same type.

Now implemented in `YpsoService.syncSuspendedState`, with two things the pattern requires:

- **The record is recognised as ours before it is closed.** A real temporary basal has a different
  type and duration; closing one by mistake would tell AAPS basal had resumed when it had not.
- **It is re-asserted on every poll** while the pump stays stopped, rather than relied on to outlive
  the outage.

#### ⚠⚠ [fixed] …except it was not re-asserted on every poll, because a `StateFlow` conflates

2026-08-01. The claim above was written and was false the whole time, and the reason is worth having
because it will catch the next flow-driven thing too.

`syncSuspendedState` hung off `pumpManager.pumpStatus`, which is a `StateFlow`. **A `StateFlow` does
not emit a value equal to the one it already holds.** A stopped pump answers byte-for-byte
identically poll after poll — same mode, no rate, no TBR, and a reservoir that cannot move because
nothing is being delivered. So the collector fired **once**, on the transition into stopped, and
then never again for as long as the pump stayed stopped.

That matters because §8.7 shortened the record to **60 minutes** on purpose, so one orphaned by a
crash expires in an hour rather than three days. Expiry and re-assertion are two halves of one
design, and only one half was present. A pump stopped for longer than an hour would have had its
suspend record expire with nothing to replace it, and **AAPS would have gone back to counting basal
IOB for a stopped pump** — which is the exact bug this whole section exists to fix, arriving
through a different door about fifty lines below where it was fixed the first time.

The manager now also exposes `statusReads`, a `SharedFlow` that emits once per system-status read
regardless of whether anything changed, and everything that must happen *per poll* hangs off that.
The `StateFlow` keeps its proper job: holding the current value for the UI, where conflation is what
you want.

Phase 6b in `SALINE_TEST_PROCEDURE.md` is the check: an hour of `suspendRecord=open` with not one
`none` in between, and a second record opening around the 60-minute mark.

**And one thing the pattern does not require, but this pump does.** `isDelivering` is false both for
"confirmed stopped" and for "delivery mode never identified" — right for gating the loop, where the
safe answer to *don't know* is *don't act*, but wrong for writing a record. Only two byte-0 values out
of 256 are known (§8.3) and nobody has yet looked at what the pump reports **during a bolus**. If that
is a third value, treating unknown as stopped would open a suspend record on every bolus — and an app
killed mid-bolus would leave one open for its full hour, telling AAPS basal was off when it was not.

So `PumpStatus.operatingMode` is now populated as a genuine three-valued state and only `STOPPED`
opens the record. `UNKNOWN` logs loudly and writes nothing. The loop stays held off either way.

**The bolus question is now answerable** — see §8.3. The wait loop takes one system-status reading
per bolus and logs it without letting it reach this method, so looking cannot cause the thing being
looked for.

### ⚠ [fixed] Nothing corrected the pump's clock after a DST or timezone change

`timezoneOrDSTChanged` was not implemented. Ten drivers implement it; Combo and Medtrum both use it to
rewrite the pump's clock.

This pump needs it more than most, because it **has no concept of a time zone** — `syncTime` writes
local hour, minute and second and it counts local wall-clock seconds from there (§5). After a DST
change its clock is simply an hour wrong until somebody notices and presses the button on the YPSO
tab, with every bolus and temporary basal it logs in the meantime carrying the wrong instant. That is
the same class of error that already produced treatments 12 hours in the future.

It now queues a clock sync, which is safe to fire unattended for the reason §4e gives: the write
refuses unless it can read the clock first, and reports success only from the read-back. A failure
raises a notification saying the pump's clock is now wrong, rather than leaving it to be discovered.

`canHandleDST()` stays **false** — it must, because the pump genuinely cannot — so AAPS's own DST
safeguard is untouched.

### ⚠ [fixed] Clock drift was measured on every poll and never acted on

The skew is read every status poll and shown on the YPSO tab, but nobody watches a tab.

The first fix here raised a **notification above 30 minutes**, and that was wrong twice over. Wrong
mechanism: there is a proven write that refuses unless it can read the clock back first (§4e), so the
answer to a wrong clock is to *fix* it, not to complain. And wrong number, once history sync (§8.5)
makes the pump's timestamps into treatment times — a slow clock makes a bolus look older than it is,
its IOB decays from too early, AAPS reads less insulin on board than there is, and doses again. Drift
is a **dosing** problem measured in seconds, not a cosmetic one measured in half-hours.

Medtronic has the convention: `if (timeDiff > 20) … set time on pump`, and refuse over 24 h because
that is not drift. This now does the same at **10 seconds**, which is comfortably clear of the
measurement noise — the pump's clock reads to one second and a BLE round trip costs a fraction of one.

Two guards. A difference beyond **24 hours** is not corrected at all: that is a phone with no network
since a reset, or a pump whose clock went with its battery, and silently writing a year-wrong time is
not a correction. And corrections are **rate-limited to one an hour**, because each one appends two
entries (type 12 and 13) to the pump's 3000-entry log; needing the limit means the crystal is failing,
so hitting it is logged as a fault rather than absorbed.

It runs at the end of every `readPumpStatus`, so in practice: every status read AAPS asks for, and at
worst every **20 minutes** when idle (§8.2).

### ⚠ Still open: `YpsomedCapabilities` promises three things the driver refuses

```kotlin
YpsomedCapabilities(arrayOf(
    Capability.Bolus, Capability.ExtendedBolus, Capability.TempBasal,
    Capability.BasalProfileSet, Capability.Refill, Capability.ReplaceBattery,
    Capability.TDD, Capability.ManualTDDLoad
))
```

`ExtendedBolus`, `TDD` and `ManualTDDLoad` are all declared, and all three return a failed
`PumpEnactResult` without touching the pump. **A capability declaration is a promise to AAPS**, and
AAPS acts on it: it offers an extended bolus in the UI and a TDD load in the actions, both of which
cannot work.

Medtrum shows the convention — its capability list carries the comment *"Technically the pump supports
ExtendedBolus, but not implemented (yet)"* and simply **omits** it.

**Not changed here, deliberately.** `PumpCapability` lives in shared `core/data` and `YpsomedCapabilities`
is also used by the upstream `:pump:ypsopump` driver, so it is not this driver's to edit unilaterally.
Two ways forward, and they point in opposite directions:

- Drop `ExtendedBolus` until it is implemented and tested, matching Medtrum.
- Implement `loadTDDs` instead of dropping the TDD capabilities — **the data already exists.** The
  pump logs `23 Daily Total Insulin` with the total in `v1` as centi-units (`v1=1944` → 19.44 U), and
  `PumpSync.createOrUpdateTotalDailyDose` takes exactly that shape; Combo passes `bolusAmount = 0.0,
  basalAmount = 0.0` and only a total, which is all this pump offers. It needs a small SDK method to
  expose the entries, since `YpsoHistoryMapper` deliberately maps type 23 to null.

### ✅ [verified] Closed: `bolusSize = 0.1` is safe — the increment is a menu setting only

This section used to say that `PumpType.YPSOPUMP` hard-coding a 0.1 U step was a latent mis-dosing
risk, because the pump has a `5 Bolus Step Changed` event and the driver could not read the
increment. Both halves have now been settled on hardware, 2026-08-01, and **the conclusion was
wrong**:

```
pump increment set to 2 U     (index 3 = 200, §8.8)
AAPS asked for                0.3 U
pump delivered                type=2 v1=30 → 0.30 U, success=true
reservoir                     154.61 → 154.30
```

0.3 U is off the 2 U grid and off the 0.5 U grid the pump had been on all along, and it was
delivered exactly. **The increment governs what the pump's own menu offers, not what it will accept
over BLE**, which is consistent with the wire format carrying centi-units and with the pump logging
partials like `v1=249` at 0.01 U resolution.

So AAPS's 0.1 U grid is fine, nothing needs to read index 3 for safety, and this is closed rather
than mitigated.

**It was the pump's owner who said so first** — *"the bolus increment may be a pump manual bolus
thing only and not an AAPS restriction"* — before the test was run, and against what this file
asserted. Worth recording alongside §8.7: the concern here was reasoned from a capability the pump
has (a configurable step) to a consequence nobody had checked (that it binds a commanded bolus), and
it stood for three sessions as a ⚠ item because reasoning is cheaper than a 0.3 U bolus on saline.

### Smaller gaps, none of them safety-critical

| | What | Assessment |
|---|---|---|
| `isBusy()` | Always false. Medtrum and Combo report true while a command runs | The `pumpMutex` already serialises commands, so a second command blocks rather than corrupts. Changing it risks stalling the queue and wants care |
| `updateExtendedJsonStatus` | Not implemented. Combo puts its warning and error codes here | Cheap, and it is what a caregiver watching Nightscout sees. Worth adding: delivery mode and the last alert |
| `getCustomActions` | Not implemented | The YPSO tab already has the buttons. Only worth it to reach the Actions tab |
| `waitForDisconnectionInSeconds` | Default 5 s | Fine. Omnipod Eros uses 0, Virtual 0; nothing suggests this pump wants either |
| `setNeutralTempAtFullHour` | Default false | For pumps that beep at the hour on a TBR. Not observed here |
| `isUnreachableAlertTimeoutExceeded` | Default false, and `hasCustomUnreachableAlertCheck` is false for this pump type | Correct — the standard 30-minute check applies |
| `deliverTreatment` guards | `require(carbs == 0)` / `require(insulin > 0)` | Matches Medtrum exactly. Carbs-only never reaches the pump |

---

## 8.7 ⚠ An audit of this driver's own over-engineering

Prompted by the pump's owner, 2026-08-01, after eighteen months of using it: *"the pump is solid.
This is an example of you over engineering."* They were right, and the pattern is worth recording
because it will otherwise repeat.

**What was cut, and why it should not have been built:**

| Built | On what evidence | Verdict |
|---|---|---|
| Clock correction **rate-limited to one an hour** | One measurement: ~0 s drift over 9 hours | Defended a failing crystal nobody has seen. **Removed** — a correction firing often enough to matter is itself the finding, and the log line says so without machinery to anticipate it |
| `SUSPEND_TBR_MINUTES = 4800` (80 hours) | Copied wholesale from Medtrum | Medtrum polls near-continuously; this driver polls three times an hour. **Now 60 minutes** — three poll intervals, and a record orphaned by a crash expires in an hour rather than three days |
| History gap → **urgent notification** | Never happened | Read window is 50 entries against a 24-entry worst case. **Now a log line** |
| Clock drift → notification above 30 min | Reasoning, not measurement | Built and replaced inside one session. Pure churn |
| Skip list for self-recorded sequences | Reasoning about an upsert I had not read carefully | **Removed** (§8.5). The contract said it upserts on `pumpId`; a sync is a sync |
| *"Decide the alarm poll interval"* framed as a blocker | An assumption AAPS polls harder than it does | Overstated. **Of every AAPS pump driver, only Insight holds a persistent connection.** Command-queue-driven connect/disconnect is the norm, and the pump alarms locally and loudly |

**The pattern.** Every genuine bug in this driver has been in *this code* or in an AAPS-side
assumption: the epoch decode, the TBR lookup baseline, the type-19 confirmation, the missing suspend
record, the ordering of the bolus record. **The pump has behaved identically every single time** —
same envelopes, same sequence semantics, same in-place rewrites, no NAKs, no surprises beyond the
first reading of each attribute. Yet these notes are dense with ⚠ markers aimed at the pump.

The markers earned against *the pump* are few and specific: it does not NAK a bad payload, its
conventions are per-attribute, and it rewrites log entries in place. Everything else that looks like
pump misbehaviour in this file was this driver misreading it.

**The rule going forward: measure before defending.** A guard against a failure mode nobody has
observed is a guess wearing the costume of caution — the same mistake as `tbrPercentRange = 0..200`
(§4h), which blocked the very experiment that would have settled it.

#### The rule applied, 2026-08-01

The session after this audit was written spent itself on instruments rather than guards. Byte 0's
254 unmapped values were not defended against; they were made **readable** (§8.3), which is the
measurement the guard would have been a substitute for. Nothing was added to the *delivery* path
beyond a single read whose result is deliberately not acted on.

The audit's other half held up too. Both defects found this session — the suspend record that was
never re-asserted (§8.6) and the gap warning that could not fire (§8.5) — were **in this code**, in
AAPS-side assumptions about how a `StateFlow` and two read windows behave. The pump was not involved
in either. That is now five for five.

One thing this suggests looking at next, and it is not a guard: **checks whose inputs were never
examined.** Three of them have now been found, all of which passed for every possible input. It
would be worth reading the remaining confirmations with that question — not "is the logic right?"
but "could this ever have said no?"

## 8.8 What the vendored SDK left behind

The upstream SDK is at `reference/reference/ypso-sdk`. Three things in it were never brought across,
beyond `crypto/` and `keyexchange/` which are excluded deliberately (§0).

**`ReconnectManager` — now vendored.** Exponential backoff 5 s → 10 → 20 → 40 → 80 → 160 → 300,
reset on connect. This was the real omission: a dropped link previously did nothing until AAPS
happened to issue its next command.

⚠ **It is armed only for the duration of a bolus.** AAPS calls `disconnect()` every time its command
queue empties, many times an hour — that is the normal idle state. Reconnecting after it would fight
the queue and hold the link open permanently. A bolus is the one window where a lost link costs
something, because the pump finishes delivery without the phone and the amount actually given is
sitting in its event log (§4g).

**`PollManager` — not vendored, and deliberately.** A 60-second poll loop holding a
`PARTIAL_WAKE_LOCK` per cycle. It is the SDK's own answer to the polling question and 20× more
frequent than AAPS's idle cadence, but it fights AAPS's connection model and costs battery for a
latency that §8.7 concludes was never the problem it was made out to be.

**A third history log nobody has read.** `YpsoPumpUuids.System` — COUNT/INDEX/VALUE on a completely
different UUID family, wired up in `YpsoBleManager.readHistorySystem()` and called by nothing.
Contents unknown.

**And a correction to §8.6.** It said the pump's bolus increment "is a pump setting the driver cannot
read — no settings index for it is known." True but misleading. `SettingsIndex` knows index 1 and the
two 24-slot profile ranges; **indices 2–13 have never been read** — not here and not in the upstream
SDK. Twelve settings sit there unmapped, and the bolus increment is plausibly one of them, as are the
caps behind event types 33 and 34. `probeUnknownSettings()` now reads them and logs the values, hung
off the **Read history** button because it is a diagnostic and twelve extra round trips (~2.5 s)
should not land on every status poll. It is a read; nothing is written.

#### First reading of indices 2–13 — 2026-08-01

```
index 2 = 10        index 6 = 17505     index 10 = 3
index 3 = 50        index 7 = 188       index 11 = 0
index 4 = 26247     index 8 = 2         index 12 = 1
index 5 = 32107     index 9 = 0         index 13 = 1
```

#### ✅ [verified] Index 3 is the bolus increment, in centi-units

The increment was changed on the pump and the probe re-run:

```
pump on 0.5 U   →  index 3 = 50      every other index unchanged
pump on 2.0 U   →  index 3 = 200     every other index unchanged
```

Two settings, one moving value, eleven unchanged indices as the control. **This is the first
settings index mapped on this pump beyond the three the SDK already knew.**

⚠ **It was nearly mapped to the wrong index, and the near-miss is the lesson.** The first reading
was written up here as *"index 2 = 10 is a strong candidate: 10 centi-units is 0.10 U, exactly the
step `PumpType.YPSOPUMP` hard-codes and the pump is currently set to."* The pump was **not** set to
0.1 U — it was on 0.5, which its owner knew and this file had assumed away. The value agreed with
the *assumption*, and the assumption was the thing that was wrong. §4f's type-19 echo was the same
shape: a number that says what the reader expected is the weakest evidence there is, and it reads
identically to the strongest until something independent contradicts it.

Indices 4, 5 and 6 hold large values that look like an identifier or checksum and did not move
across either read. They stay unmapped; guessing at them is what made upstream's delivery-mode table
wrong.

**✅ And it does not constrain a commanded bolus.** With index 3 reading 200 (a 2 U menu step), AAPS
asked for **0.3 U** and the pump delivered `v1=30` → 0.30 U, `success=true`. Off the 2 U grid, off
the 0.5 U grid it had been on all along, delivered exactly. The increment is a setting for the
pump's own menu; the BLE command is unconstrained by it, which is consistent with the wire format
carrying centi-units. `bolusSize = 0.1` is therefore safe and §8.6's item is closed, not mitigated.

So index 3 is **mapped but not load-bearing**: worth knowing, and nothing needs to read it.

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

### Added in session 1, still true

- The SDK module's test task is **`:pump:ypso:sdk:testFullDebugUnitTest`**, not
  `:pump:ypso:sdk:test…Debug…` — the module has three flavours and `compileDebugKotlin` is ambiguous.
- Nordic's BLE library (`no.nordicsemi.android:ble:2.11.0`) was added to
  `gradle/libs.versions.toml` as `no-nordicsemi-android-ble`. It is the only new third-party
  dependency; dropping the key-exchange code avoided pulling in gRPC and Play Integrity.
- `no.nordicsemi.android.support.v18:scanner` was **not** needed — the pairing screen uses the
  platform scanner, like DanaRS.

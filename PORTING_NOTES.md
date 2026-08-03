# YpsoPump driver — porting notes

Driver for an **unlocked plaintext research YpsoPump** on current AndroidAPS (3.4.2.4).

> **New here? Read `README_YPSO.md` first.** It is the handover document — what the driver is, what
> it has been verified to do, what it cannot do, and how to bring one up from nothing. **This** file
> is the record of what the pump was observed doing and why the code is shaped the way it is,
> including every wrong turn, because the wrong turns are the part that is expensive to rediscover.

**Status, 2026-08-02: every phase has now run on hardware, and what is left is one performance
decision and two things only time can answer.** The driver bonds, connects, authenticates in
plaintext with no key exchange, reads every value it needs, and **writes** — the pump clock (§4e), a
standard bolus with abort (§4f), a temporary basal with cancel (§4h), and the 24-slot basal profile
(§4i). Each was gated behind its own saline test and each is confirmed by reading the pump back,
never by the write callback.

Deliveries reach `PumpSync` under the pump's own sequence number, pump alarms reach AAPS as urgent
notifications (§4g, §8.2), and **history sync is switched on** — a bolus given on the pump's own
menu now becomes insulin on board (§8.5).

⚠ **This driver can now move insulin.** What stops it doing so is the cartridge, not the code. Every
test to date has been on **saline, off-body, cannula to air**, and §8.1 lists what must still be true
before that changes.

**Phase 7 passed in full on 2026-08-02.** The first enable took a baseline and imported nothing; a
manual bolus arrived exactly once at the right amount and time; an AAPS bolus stayed one record
across two re-reads; a 24-entry burst reached no treatment and opened no gap; and four idle hours
produced fourteen empty syncs and not one row in any treatment table.

**The sync's cost is solved.** It was 20.4 s on every status read — twice what this file predicted,
because `YpsoHistoryReadCount` applies to *each* log. A probe that reads index 0 first and skips the
window when nothing has moved cut a quiet poll to **~580 ms**, verified in Phase 7f (§8.5).

**The next piece of work is the chunked bolus — `SALINE_TEST_PROCEDURE.md` Phase 9, written and not
yet built.** "Bolus speed" turned out to be a loop in the phone rather than a pump setting: Lotus
issues ordinary `type=2` boluses on a timer (1 U/5 s = 12 U/min), confirmed in the pump's own log.
It needs no new pump capability, and it is the answer to slow delivery for comfort that the extended
bolus is not (§8.6).

**Two things stand between here and an insulin trial, and neither is a capability:**

1. ✅✅ **6b is closed — verified twice on 2026-08-03, on the fix and then on HEAD.** 2h33m on the
   08-02 build (four renewal cycles), then HEAD 3.4.2.6 flashed onto the still-stopped pump and left
   to poll unattended (three more), with **zero uncovered time at every transition including across
   the reinstall**. Decided on `temporaryBasals.duration`, not on a log line (§8.6). **No correctness
   gate remains open.**
2. **The 3000-entry log cap now has a date on it** — roughly three months at this user's rate of
   profile changes. What happens when it wraps is now traced rather than guessed at, and the probe
   makes it audible instead of silent (§8.5).

**Byte 0 of the system status is identified for two values out of 256, and that is the answer, not a
gap — Phase 5's table is now complete.** Every state reachable on hardware came back as one of those
two. During a bolus it reads `10`; while alarming, after an alarm, stopped by hand, **through a whole
cartridge change, and with the cartridge run dry** it reads `3`; and there is no pause state on this
pump at all (§8.3). What separates those states is not byte 0 but the **reservoir field** — `-1` while
the cartridge is out or being changed, a known `0` when it has run empty. Only "during priming with
fluid moving" is unfilled, and it is recorded as **unreachable by this method** rather than blank:
the reservoir reads `-1` throughout a change, so no reading can be shown to have been taken live.

**Four checks in this file have now turned out to be incapable of failing** — the type-19 bolus echo
(§4f), the epoch "verified" against wall-clock readings (§5), the gap warning measured across both
logs (§8.5), and Phase 7d re-writing the profile the pump already held (§8.5). Every one read as a
pass. The pattern is never the logic; it is that nobody looked at what the **inputs** were.

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
3. **`YpsoSyncHistoryToAaps` is now ON**, and this used to say the opposite. What the pump does on
   its own — above all a bolus from its own menu — **now becomes insulin on board**. It reached that
   state through Phase 7, one preference validated in five steps with the database checked
   independently of the log at each one (§8.5). **The shipped default is `true`** —
   `YpsoBooleanKey.YpsoSyncHistoryToAaps("ypso_sync_history_to_aaps", true)`, flipped once 7e and 7f
   passed. It remains a preference so it can be switched off in one action.
4. **Nothing added in session 5 could move insulin**, and nothing added in session 6 can either.
   Session 5 added log lines and nothing else — the sync logic was deliberately not touched while it
   was being tested, which is the rule the procedure sets for itself. Session 6 removed refused
   capability *claims*, deleted the unregistered donor module and wrote documentation; it added no
   delivery path.

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

⚠ **Now that history sync is on, that conservative record does not stay conservative.** The sync
upserts on `pumpId` and rewrites the amount to whatever the pump's log says, so a bolus recorded
high because its outcome was unknown is silently corrected **downwards** the moment a sync reaches
it. That is the right answer — the pump's log is the ground truth the high figure was standing in
for — but it is a rewrite in the unsafe direction arriving without announcement, and the two rules
were written years apart without anyone noticing they meet. A live example is in §8.5.

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

`:pump:ypsopump` — session 1's forward-port of Andy Rozman's module — is **deleted, 2026-08-03**. It
was the parts donor it was declared to be (Ypso domain enums, response-translator logic, config UI
ideas), it had been unregistered from the app since session 2, and everything worth taking from it
had been taken. It is in git history; `git show <sha>:pump/ypsopump/...` retrieves any of it.

**Deleting it removed three changes to shared files as well**, which is why it was worth doing rather
than leaving 78 dormant files in place. `PumpDriverState.Sleeping`,
`PumpDriverState.ErrorCommunicatingWithPump` and their two strings in `pump/common`, and
`Notification.YPSOPUMP_PUMP_ALARM = 95` in `core/interfaces`, existed **only** for the donor — nothing
in `:pump:ypso` ever referenced them. All three files are now byte-identical to upstream, and each
one was a rebase conflict waiting to happen (§9).

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

**✅ Closed 2026-08-02, and it was the other half of the same problem:** a bolus given **on the pump
itself** used to reach AAPS nowhere, because nothing read history automatically. `syncHistoryToAaps`
now runs on every status read, using the same identity scheme and the same reconciliation (§8.5).
Phase 7b confirmed it on hardware — 1.5 U from the pump's own menu, arriving once, at the right
amount and time.

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
identified. A possible dedupe collision is a far smaller problem than a temporary basal AAPS believes
is still running. ⚠ That trade was priced when the sync switch was off; it is **on** as of
2026-08-02, so the collision is now reachable rather than hypothetical. Nothing in Phase 7 exercised
it — a TBR cancel whose history entry cannot be identified is not a state anyone has produced on
demand — and it stays worth the trade, but it is no longer free.

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

Note the cost this implies for a history sync: **one profile write can add up to 24 entries** — one
per slot that actually changes, and none at all if the profile written matches the one held (§5).
That is half the default 50-entry read window, and Phase 7d measured the surviving margin at 26
entries.

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

## 5. History → PumpSync, and what it took to switch on

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

### ⚠ [verified] A profile write of the *same* profile appends nothing — 2026-08-02

§4i already established that `7 Basal Profile A Changed` is slot-precise — `v1` slot, `v2` new rate
in centi-U/h, `v3` old — from single-slot edits. What nobody had drawn out of that is the case where
**no** slot changes: AAPS re-pushed the active profile after a reinstall
(`writeBasalProfile: confirmed=true`, 09:27) and the pump logged **zero** entries, because the
profile written was the profile already held.

So "a write appends 24 entries" is an upper bound, not a count, and Phase 7d — which asked for a
profile write and expected a 24-entry burst — would have produced no burst and passed anyway. It had
to be re-specified mid-session to demand a profile differing in **every** slot. See §8.5.

The `v2`/`v3` direction is also now confirmed by a chain across two days rather than by single
edits: slot 6 reads `0.40 → 1.24` on 30 July (`seq=160490 v1=6 v2=124 v3=40`), then `1.24 → 0.30`
on 31 July (`seq=160569 v1=6 v2=30 v3=124`) — the second entry's *old* value is the first's *new*.

### Writing history into AAPS is **on**

`YpsoSyncHistoryToAaps` was turned on and validated on 2026-08-02 (§8.5, `SALINE_TEST_PROCEDURE.md`
Phase 7). Reads and mapping always ran; only the `PumpSync` writes were gated. All three original
blockers are closed: `pumpId` carries the pump's sequence number, the event types and bolus amounts
are verified against actions performed on the pump, and `sincePosition` filtering is a monotonic
sequence watermark with a per-window gap check — **never a timestamp**, because of the type-12 trap
above.

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
7. **`YpsoSyncHistoryToAaps` is on by default and should be left on.** This step used to say the
   opposite, before Phase 7 settled it (§8.5). On a *new* pump the first enable takes a baseline and
   imports nothing, which is what makes leaving it on safe.
8. **The clock write** — phase 1 of `SALINE_TEST_PROCEDURE.md`. **[verified]** Also the quickest
   way to prove the write path still works after any change to the framing code.

Firmware confirmed **V05.02.03**.

**Two cosmetic decode faults noticed while running phase 1. One is fixed, one is not:**

- ✅ **Fixed.** `ypso_serial_number` was stored with a trailing NUL — `10074292` followed by
  `U+0000`, which the preferences XML shows as `&#0;`, because `getStringValue(0)` on the
  device-name characteristic keeps the terminator. Harmless on screen, but it also feeds
  `pumpSerial` on **every** `PumpSync` call, so records could have split on a serial mismatch the
  moment history sync went on. `cleanBleString()` — `trim { it.code <= 0x20 }` — now runs on the
  way out of the SDK.
- ⏳ **Still open, and still cosmetic.** Firmware displays as `0.2.2.1` where the pump says
  **V05.02.03**. `formatVersion` joins the raw bytes as decimal, which is not this characteristic's
  encoding. Nothing depends on the string, but it is the field a user would quote in a bug report,
  so it is worth an hour when somebody has one.

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

#### ✅ [verified] The delivery-mode byte does **not** supersede this channel — 2026-08-01

§8.3 raised the possibility that if an alarm stop showed up in byte 0, that would be a better channel
than the alert log and might make this section moot. It is a better channel — it is immediate and
needs no count — but it does **not** replace the alert log, and the reason is worth stating:

**Alarming and stopped-after-acknowledging are byte-identical.** Eight status reads across an
occlusion, four with the alarm sounding and four after it was acknowledged, produced the same
eighteen bytes. So byte 0 says *the pump has stopped* and never says *why*. The alert log is the only
thing that distinguishes an occlusion from a user pressing stop.

The two are complements, and the driver already reads both: the delivery-mode byte stops basal IOB
accruing within one poll, and the alert log tells the user what happened. Neither on its own is
enough.

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
| ~~during priming / a cartridge change~~ | ✅ **Answered 2026-08-03. It reads `3`, and the reservoir reads `-1`.** See below |
| after an alarm has stopped the pump | **the most important one.** If an alarm stops delivery and byte 0 goes to a third value, this is where AAPS should learn insulin has stopped — and it may be a better channel than the alert log |
| ~~paused~~ | ✅ **Retired 2026-08-01. There is no pause on this pump** — its menus offer stop only, so upstream's `PAUSED = 7` is unreachable on V05.02.03. Not an unmapped state; not a state |
| ~~reservoir empty mid-delivery~~ | ✅ **Answered 2026-08-03. It reads `3`, and the reservoir reads a known `0` — not `-1`.** See below |

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

#### ✅ [verified] Running dry reads `3`, and empty is **not** the same as unknown — 2026-08-03

The last unread row of the table above, reached by priming a cartridge down to 3.91 U and asking for
a 5 U bolus on saline. The pump delivered 3.92 U, stopped, and alarmed:

```
byte0=3 name='Stopped' mode=STOPPED delivering=false suspendRecord=open reservoir=0.0
raw=[03 00 00 00 00 02 00 00 00 00 64 00 00 00 00 00 00 00]

Pump alert log grew from 147 to 148 — reading the 1 newest
PUMP ALERT: Alert: Cartridge Empty (type 104, seq 160777)
Pump alert 104 (Alert: Cartridge Empty), urgent=true
```

**Three results, and the second is the one nobody predicted.**

**Byte 0 reads `3`.** No new value — and with this row filled, **byte 0 is now identified for every
state this pump has been made to enter.** Two values out of 256, and that is the answer rather than
a gap.

**The reservoir reads a known `00 00 00 00`, not `FF FF FF FF`.** The pump distinguishes *"the
cartridge is empty"* from *"there is no cartridge"*, and puts them in the same field as 0 and −1.
`reservoirKnown` reports the first as a genuine zero and the second as unknown, which is correct in
both cases and had never been exercised in either. So the delivery-mode byte cannot separate a
cartridge change from a dry cartridge, and **the reservoir field can** — the third time in these notes
the payload has carried information the decode's headline value did not.

**Running dry *does* alert**, which a low reservoir does not (§4g). The distinction is now concrete:
the mylife controller computes *low* on the phone and the pump's count never moves, but *empty* is a
real pump alarm and reached AAPS as an urgent notification with sound.

**And the partial-bolus path ran under a cause it had never seen.** Every previous partial was a user
cancel or an occlusion. Asked 5.0 U against 3.91 U in the cartridge, the pump logged
`seq=160776 type=2 v1=392` → 3.92 U, the driver reported `success=false` carrying the full 3.92 U,
and the provisional 5.0 U record settled down to 3.92 U under the pump's own sequence. §0's two rules
working in opposite directions at once, on the same bolus: recorded in full, reported as failed.

#### ✅ [verified] A cartridge change reads `3`, and the reservoir reads `-1` — 2026-08-03

Read live during a real cartridge change on saline, with AAPS driven from `adb` so the tester's hands
stayed on the pump:

```
byte0=3 name='Stopped' mode=STOPPED delivering=false suspendRecord=open reservoir=unknown
raw=[03 FF FF FF FF 02 00 00 00 00 64 00 00 00 00 00 00 00]
```

**No third value here either.** Five readings across the change — cartridge out, cartridge back in,
and twice more inside the prime procedure — all read `3`, the same as a button stop and an alarm
stop. Byte 0 is now confirmed for every state this pump has been made to enter.

**And this is the first sighting of `reservoir = -1`.** §8.4 item 5 listed it as *"coded for
(`reservoirKnown`), never observed"*. Bytes 1–4 read `FF FF FF FF`, `reservoirKnown` handled it, and
the UI showed `reservoir=unknown` rather than a garbage figure. Observed, and correct.

⚠ **The payload distinguishes this state; byte 0 does not.** Compare the three:

```
running          0A 2A 39 00 00 …
stopped / alarm  03 0F 40 00 00 …
cartridge change 03 FF FF FF FF …
```

So a cartridge change is separable from an alarm stop — by the reservoir field, not by the delivery
mode. That is precisely why §5c asks for the whole payload rather than the decode, and it is the
second time that instruction has paid.

⚠⚠ **One row of Phase 5 is now unreachable by the method that answered the rest, and it is worth
recording why.** "During priming, with fluid visibly moving" cannot be *proved* live. §8.3's bolus
reading was trustworthy because the reservoir in its own payload sat 0.12 U below the pre-bolus
figure — the payload carried its own evidence of liveness. During a cartridge change the reservoir
reads `-1`, so a reading taken mid-prime is byte-identical to one taken thirty seconds after it.
The value is near-certainly `3` — it is `3` on both sides and throughout — but that is inference,
and §8.3's own rule is that nothing is mapped on inference. **Recorded as unreachable rather than
as verified.** The cost of being wrong is nil; the cost of a row marked ✅ on a reading nobody could
stand behind is the thing this file keeps finding.

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

**✅ [verified] 2026-08-01, both directions.** Six readings held `open since 5:58 pm` across an
automatic cancel and two minutes of polling, then closed on restart; and a real 50% TBR cancelled
normally, its record closed under `endPumpId=160650` — the pump's own sequence. The fix
discriminates rather than merely refusing.

**⚠⚠ And the fault was larger than described above.** The cancel that exposed it was **not issued by
a human**. AAPS issues `cancelTempBasal` by itself whenever it sees the pump suspended — twice
observed, 22 seconds after a stop on one occasion and 29 on the other. So this was never an edge
case someone had to reach for: **it fired automatically on every pump stop**, closing the suspend
record seconds after `syncSuspendedState` opened it, and leaving AAPS to count basal IOB on a
stopped pump until the next poll. Unattended, that is a full idle poll interval — twenty minutes —
every single time the pump stopped, including every occlusion and every cartridge change.

The three variants of this bug now read as one story. The record was **not written** at all
(fixed §8.6); then written but **never re-asserted**, because a `StateFlow` conflates; then
re-asserted but **closed within seconds** by an automatic cancel. Each fix was correct and each
left a different way for the same wrong belief — *basal is being delivered* — to reach AAPS. What
none of them was, was a pump problem.

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
   and whether that reaches `isSuspended()` promptly is now **watched and confirmed**: a button stop
   showed `mode=STOPPED` on the first poll after it, opened the suspend record, and closed it on
   restart (Phase 6a/6c, 2026-08-01). An *alarm* stop does the same thing and reports the same byte.
5. ~~**Reservoir `-1`.**~~ ✅ **Observed 2026-08-03.** `reservoirKnown` handled it correctly through a
   whole cartridge change — bytes 1–4 read `FF FF FF FF` and the UI showed `reservoir=unknown`
   rather than a garbage figure (§8.3). What remains unobserved is reservoir `-1` reached by
   **running dry mid-delivery** — a different route to the same field, and Phase 5e's question.
   Still open too: an unprogrammed basal slot reading `-1` → `0.0`, which would make
   `isThisProfileSet` false permanently (§4i).
6. **Low battery.** Only 2-of-4 bars has ever been seen. The mapping of 0 and 1 bars, and whether the
   pump alerts, are both unknown — and AAPS acts on pump battery level.
7. **Bolus and basal caps.** Event types 33 and 34 exist for them. Whether the pump refuses an
   over-cap bolus, and what the driver then reports, is untested — and "the pump refused" must not
   surface as success.
8. **Extended and combined bolus.** Still refused, but no longer unscoped — **`SALINE_TEST_PROCEDURE.md`
   Phase 8 is written**, 2026-08-03, before any code. The motivation is **comfort on large boluses**:
   the pump delivers at a fixed ~33 U/min (§8.6), so 10 U goes in over 18 s where other pumps take
   minutes. The mechanism already exists — `startBolus` takes `durationMinutes` and sets
   `bolusType = 2`, so **what is missing is the gate, not the driver**. Two things the phase settles
   before anything is advertised: whether an extended bolus **blocks the command queue for its whole
   duration** (it would, if the pump reports busy rather than programmed — 8a, and a stop condition),
   and whether it reaches AAPS in `extendedBoluses` rather than `boluses`, since the two have
   different IOB decay. `cancelExtendedBolus` ships in the same change, deliberately breaking the
   one-refusal-at-a-time rule, because `LoopPlugin.goToZeroTemp` calls `cancelExtended` on every pump
   disconnect and a refusing cancel raises an audible delivery-error alarm. Sub-minute durations were
   considered and rejected: 1 min on a 10 U bolus is still 10 U/min, so it answers a question comfort
   does not ask. They remain two of the four bolus types the history mapper already decodes.
9. **The event log filling or wrapping.** The count reads 3000 and looks capped. What happens to
   sequence numbers at the cap decides whether `sincePosition` can work at all (§8.5).
10. **A DST transition.** `pumpSecondsToUnix` converts through a local calendar and is tested across
    a boundary in unit tests, but no real transition has occurred while the driver was running.
11. **Two controllers at once.** Now known to be more than theoretical: the study phone running Lotus
    writes the basal profile unprompted when it wakes (§4i) — **confirmed a second time 2026-08-03**,
    and this time *predicted before it happened* rather than discovered as a mystery. All 24 slots
    went 0.30 → 1.20 U/h within a minute of the phone powering up, which is what makes a read-back
    diff useless as a driver test while it is awake. What happens if both are connected
    during a delivery command is unknown, and the driver holds its connection rather than releasing
    it between commands.

### 8.5 History sync — **on** since 2026-08-02

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

#### ✅ [verified] Switched on 2026-08-02, after Phase 7a–7d passed on hardware

**`YpsoSyncHistoryToAaps` now defaults to `true`**, flipped once Phase 7 passed in full *and* Phase
7f validated the probe that keeps the cost survivable. The flip was deliberately held until then: it
is a statement that the shipped build is ready, and it should not be made about a build carrying
sync logic nobody has run. Where the blockers ended up:

| Blocker | Status |
|---|---|
| The gap warning is inert | ✅ **Cleared 2026-08-01.** It was measured across the union of both logs and could not fire; measured per window now, with tests |
| TBR reconstruction | ✅ **Cleared.** The entry's timestamp is the start and survives the rewrite, so a type-10 entry describes a complete temporary basal (§4h) |
| First enable must import nothing | ✅ **Cleared 2026-08-02, Phase 7a.** Baseline **160658**, predicted from a dump taken beforehand and matched exactly; nothing imported |
| A manual bolus arrives once, at the right time | ✅ **Cleared, Phase 7b/7c.** 1.5 U from the pump's menu arrived once at 13:16:59 and moved IOB by 1.51; an AAPS bolus stayed one row across two re-reads |
| What happens at the log cap | ⏳ **Still open, and now dated rather than theoretical.** See below |
| Cost under a real loop | ⚠ **Measured, and worse than assumed: 20.4 s.** See below |

**How 7a was confirmed, because "the log said nothing was imported" is not evidence.** Every bolus
in the read window was already in the database from delivery time, so an absence of new rows proves
little. `seq 160492` holds **4.0 U in AAPS against `v1=249` → 2.49 U in the pump's log** — an early
abort recorded high on purpose. `syncBolusWithPumpId` upserts and rewrites the amount, so an import
across that range would have pulled 4.0 down to 2.49. It still reads 4.0.

**⚠ That is also the first sighting of §0's two rules pulling against each other.** §0 says a bolus
of unknown outcome must be recorded **high**, because under-stating IOB is the hypo direction.
§8.5 says the sync **upserts** whatever the pump's log says. So a deliberately-conservative record
will be silently *reduced* to the pump's figure the moment a sync reaches it. That is the right
answer — the pump's log is ground truth and the conservative record was only ever a stand-in for it
— but it is a rewrite in the unsafe direction, arriving quietly, and nobody had noticed the two
rules meet. It only did not happen here because the baseline sat above the record.

#### ✅ [verified] 7e: four hours idle, nothing appeared — 2026-08-02

Fourteen consecutive polls from 13:56 to 17:56, pump untouched, every one `imported 0 event(s)
above 160710` with the watermark unmoved. **Zero rows created in any treatment table.** No phantom
IOB, and nothing from Nightscout or the Lotus phone, both of which have produced that signature
before.

It also priced the idle case exactly: those fourteen polls spent **4.8 minutes of radio** reading
1,400 entries to establish that nothing had happened.

#### ⚠⚠ The real cost is **20.4 s per sync**, not 10 — because there are two windows

Five syncs measured on 2026-08-02: **19.9–21.4 s, ~200 ms/entry**. The per-entry figure matches the
~195 ms this file already carried. The total does not, and the arithmetic above was wrong in the
same way `missedSince` was: `YpsoHistoryReadCount` is applied to **each** log, so a setting of 50
reads **100 entries**, not 50.

It runs at the end of every `readPumpStatus`, so **every status read now costs twenty seconds**.
That is survivable for a manually-driven pump and it is not survivable for a loop issuing an SMB
every five minutes — it delays every command by that much.

#### ✅ [verified] Ask one question instead of reading a hundred entries — 2026-08-02

Commit `5ac789dd8e`, gated behind Phase 7f and passed the same day: **20 500 ms → ~580 ms on a quiet
poll, a 36× cut**, with the probe correctly letting a manual bolus and a 24-entry profile burst
through on the two connections that had something to fetch. Eight probes, 572–621 ms, and
`grep -c "read 50 event"` returned 0 across the quiet period — the window was genuinely not read.
Across an idle day that is **24.6 min/day of radio down to 2.1**.

The fix is not "read fewer entries", it is "don't read at all unless something happened". **Index 0
is always the newest entry** (§5), so two entry reads — one per log — answer *"has anything
happened?"* in ~0.4 s. The full window is read only when the answer is yes. On 2026-08-02 the pump
logged nothing on 13 of 17 connections, and 7e's four idle hours were 14 for 14.

**The skip is equivalent, not approximate**, which is what makes it safe. If the newest sequence in
both logs is at or below the watermark then `freshEvents` can only return empty and `missedSince`
returns 0 from its own `newest <= watermark` guard in each window. Both answers are already
determined; reading 100 entries to reach them is work with no output.

A first enable no longer reads a window either: the baseline is the newest sequence across both
logs, which is exactly what the probe returns, and nothing is imported either way. 7a would drop
from ~21 s to ~0.4 s.

**This is also what finally makes the watermark earn its keep.** Its dedupe role is thin — four of
the five `PumpSync` paths are idempotent no-ops on re-offer, so re-importing costs nothing (only
`insertAnnouncement` leaks, and that is a defect, see below). What the watermark is genuinely for is
being **the number the cheap probe compares against**. That justification survives scrutiny; "it
stops duplicates" mostly does not.

A separate, still-worthwhile follow-up: the two logs need nothing like the same window. This pump
logged 24 event entries in one afternoon and has managed 50 alerts since **2025**, so splitting
`YpsoHistoryReadCount` into event and alert counts — 50 and 5 — would halve the cost of the reads
the probe *does* let through. Not done; the probe is the bigger win and one change at a time is the
rule.

#### ⚠ `insertAnnouncement` dedupes on `now()`, not on `pumpId` — 2026-08-02

Found while asking whether the watermark is load-bearing. It takes a `pumpId` and looks idempotent;
it is not:

```kotlin
insertPumpTherapyEventIfNewByTimestamp(
    therapyEvent = TE.asAnnouncement(error, pumpId, …),
    timestamp = dateUtil.now(),      // ← not the pump's entry time
)
…
findByTimestamp(therapyEvent.type, therapyEvent.timestamp)   // ← the dedupe key
```

The `pumpId` is stored on the record and never consulted. So without the watermark every alert in
the 50-entry window would be re-inserted as a fresh announcement on every poll — ~150 an hour,
mostly occlusions from 2019.

The watermark currently hides this. It should be fixed on its own terms rather than depended on:
the other four paths key on something stable, and this one should too. Bolus, TBR and therapy-event
syncs are all genuine no-ops when nothing changed (`SyncPumpBolusTransaction` and
`SyncPumpTemporaryBasalTransaction` compare fields before writing; therapy events key on the pump's
own timestamp).

#### ⏳ The log cap now has a date on it

The event log reads 3000 and looks capped. What was theoretical is now arithmetic: a **profile
percentage change for exercise rewrites all 24 slots**, and reverting costs another 24 — ~48 entries
an episode, 4–5× a week on this user's pattern, so **~200–240 a week**. Against 3000 entries that is
a wrap in roughly **three months**. If sequences restart or wrap there, a monotonic watermark stops
being sound and the failure is silent. This is the last Phase 7 blocker still standing, and it is
the one that cannot be tested on demand.

#### ⚠ [verified] A profile write logs only the slots that **changed** — 2026-08-02

§4i knew the write was slot-precise. What it did not draw out is that a write changing **no** slot
appends **nothing** — so 7d, which asked for a profile write and expected a 24-entry burst, would
have re-written the profile already on the pump, seen no burst, and passed. **A fourth check in
these notes that could not fail**, found the same way as the other three: by looking at what the
inputs actually were rather than at what the check did with them.

It surfaced by accident. AAPS re-pushed the active profile after a reinstall
(`writeBasalProfile: confirmed=true` at 09:27) and the pump logged nothing at all. Had that not been
noticed in the pre-flight dump, 7d would have been recorded as a pass on an empty test.

Re-specified to demand a profile differing in every slot — a flat rate appearing nowhere in the
current one — 7d produced exactly 24 entries, twice. Full detail and the confirming chain are in §5.

#### ⚠ The measured burst headroom is 26 entries

After a 24-entry write the event window spans 160581–160686 — 24 of its 50 slots are new, leaving
**26 entries of margin** below the watermark. So two 24-entry writes landing between one pair of
polls would just fit, and three would not. Worth knowing before `YpsoHistoryReadCount` is lowered
for the performance fix above: dropping the **event** count below ~30 puts a single profile write
within reach of overflowing it.

### Unit tests

`:pump:ypso:sdk` now has a test source set, with **65 tests** covering the decodes the hardware
findings live in: the CRC and GLB formats (§4a–4c), the full 18-byte status payload against both
captured samples (§4), the 17-byte history entry and its 2000 epoch (§5), the clock decode and its
plausibility guard (§4e), and the history mapping — including that `position` is the sequence
number and never the slot index, which is the regression that would corrupt IOB.

They earned their keep on the first run by failing: §4a's illustrative frame carried a CRC that is
not the CRC of its payload. Prose drifts; a vector in a test does not.

#### ✅ `:pump:ypso` now has **35 tests** — 2026-08-03

It had none. Two files, and what they cover was decided by what could be reached rather than by what
would be tidy:

**`YpsoPluginTest` — 20 tests.** `YpsoPlugin` constructs under `TestBaseWithProfile` with a mocked
`CommandQueue` and `UiInteraction`, which is enough to reach everything that does not need the bound
service. The two things most worth pinning turned out to be reachable:

- **What the driver tells AAPS it can do.** Extended bolus and TDD are withdrawn, bolus, TBR, basal
  profile, refill and battery are not, and `PumpType.YPSOPUMP` is asserted to *still* declare
  `ExtendedBolus` — so the test fails if someone withdraws it by editing shared `core/data` instead.
- **That a refusal never reports success**, §0's oldest rule. `setExtendedBolus`,
  `cancelExtendedBolus`, `loadTDDs`, and bolus and TBR with nothing paired, all assert
  `success=false, enacted=false` — and the bolus asserts `bolusDelivered=0.0`, because the dangerous
  version of this bug is a failed result carrying an amount.

Also `isThisProfileSet` at its rounding boundary, in both directions: too strict and AAPS offers to
rewrite a correct profile on every loop iteration, too loose and a wrong hour stays in the pump.

**`SuspendRecordDecisionTest` — 15 tests**, and this one required a change to make possible. See
below.

`YpsoService` is still not directly testable: it is a `DaggerService`, there is no Robolectric in
this project, and adding one for a single module is not proportionate. What *was* done is to lift
the one piece of arithmetic in it that has been wrong four times.

##### `SuspendRecordDecision` — the renewal logic, where a test can reach it

`syncSuspendedState` decided the fate of the 0% `PUMP_SUSPEND` record inline, so the only thing that
had ever exercised it was a fourteen-hour unattended hardware run — on a build that turned out to
predate the fix. `SuspendRecordDecision.decide(mode, remainingMs, renewBelowMs)` is now a total
function of three values and the service maps its answer onto records, notifications and log lines.
Behaviour is unchanged; the extraction was mechanical.

The tests are written in the run's own numbers — 60-minute record, ~20-minute poll, 30-minute
threshold — and include the boundary that the phase-lock turned on, the negative-remaining case, and
a nine-poll simulation asserting no lapse is ever reported across a clean run. `lapsedByMs` is
pinned in all three of its states, including *no lapse on the first poll of a suspension*: reporting
one there would fire every time the pump stopped and train the reader to ignore the line.

⚠ **6b-RERUN must be run on a build that includes this extraction**, not on one from before it. That
is the same trap the last run fell into, and it is worth saying twice.

Also worth doing eventually, none of it blocking: a history screen (Medtronic's
`MedtronicHistoryActivity` is the pattern), and `loadTDDs` from the pump's type-23 entries.
**`:pump:ypsopump` is gone** — deleted 2026-08-03, see §9.

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

#### ✅ [fixed] 6b: the record expired between polls — real, but far smaller than first written up

2026-08-01/02, a 13h40 unattended run with the pump stopped. **The record was not held
continuously.** Over thirteen expiry cycles:

| | |
|---|---|
| Cycles that renewed cleanly | **11 of 13**, within 0–4 seconds of expiry |
| Cycles that left a hole | **2 of 13** — 19m48s and 19m59s |
| Total uncovered | **39m47s of 13h40 = 4.9%** |

**The mechanism is a phase-lock, and the half of it that was this driver's fault is the constant.**
`syncSuspendedState` opened a record only once the previous had *already expired*. The record is
opened **by a poll**, so expiry falls exactly `SUSPEND_TBR_MINUTES` later; and AAPS polls every ~20
minutes — its own `KeepAliveWorker.STATUS_UPDATE_FREQUENCY` of 15 minutes, evaluated by a worker
ticking every 5. **60 is an exact multiple of 20**, so a poll landed within *seconds* of every expiry
and a second or two of drift decided the outcome:

```
missed:   record 21:30:34 → expiry 22:30:34.  Poll at 22:30:33 — ONE second early
caught:   record 22:50:33 → expiry 23:50:33.  Poll at 23:50:35 — two seconds late
```

**Fixed** by renewing once the record has under `SUSPEND_TBR_RENEW_BELOW_MINUTES` (30) left, which
both leaves a whole poll of margin and breaks the phase-lock. Renewal is logged at info and does
**not** re-raise the urgent notification — the user was told when the suspension started and nothing
has changed since.

##### ⚠⚠ …and the fix has still never run. The overnight test was on a pre-fix build

2026-08-02. `fca883077c` was committed at **08:46**; the pump was restarted at **08:49:20** and ran
for the whole of session 5. The 13h40 run above finished at 08:50. **The fix has not been exercised
against a stopped pump even once**, and it is easy to read the run above as having tested it.

The tell is in the database, and it is worth knowing because it does not depend on a log line
surviving:

```sql
select timestamp, duration from temporaryBasals
where isValid=1 and referenceId is null and type='PUMP_SUSPEND' order by timestamp;
```

Every record in that run reads `duration = 3600000` — **exactly 60.00 minutes**, meaning each one
ran its full course and its successor opened 0.1–4.2 s *after* it expired. So the record lapsed at
**every** boundary, not just the two that lapsed for a full poll. With the fix, renewal happens at
~40 minutes, AAPS truncates the running record, and durations should read **≈40 min**. A run of
60.00s is a run without the fix, whatever else the log says.

Re-deriving the run from `androidaps.db` also independently reproduced the original log analysis —
the same two cycles, 19m48s and 19m59s — which is worth something on its own, given §8.6's history
of checks that agreed with whatever they were shown.

##### ✅ [verified] 6b passed — 2026-08-03, four cycles, zero uncovered

Unattended, and not deliberate: the cartridge ran dry during Phase 5e at 07:08 and the pump stayed
stopped for the rest of the morning.

```
started              mins     expires
07:08:42             37.62 →  07:46:19   ┐
07:46:19             40.07 →  08:26:23   │  each starts EXACTLY when
08:26:23             34.98 →  09:01:22   │  its predecessor expires
09:01:22             40.00 →  09:41:22   ┘
09:41:22             60.00 →  10:41:22   (running, not yet truncated)
```

`started[n+1] − (started[n] + duration[n])` = **0 at every transition.**

**The durations prove the fix is present, and the argument is not circular.** A record is *always
created* with `duration = 60 min`, so a stored 37.62 can only mean AAPS truncated it when a successor
arrived 37.62 minutes in. Pre-fix the code returned early whenever a record was open, so a successor
could only arrive after expiry and every duration stored exactly `3600000`. **~40-minute durations
are producible only by renewal-before-expiry.** One observation entails both facts — which is the
opposite of the four checks in this file that entailed neither.

The procedure's denominator guard is met: it requires ≥ 2 renewals or the run never reached what it
tested. There were four.

##### ✅✅ [verified] Re-confirmed on HEAD the same day, across a reinstall

HEAD was flashed at **10:12:56** onto the still-stopped pump and left alone. The chain **across the
APK replacement**:

```
09:01:22   40.00 → 09:41:22    ┐  old build
09:41:22   32.14 → 10:13:30    │  ← truncated 34 s after the APK was replaced
10:13:30   37.90 → 10:51:24    │  HEAD 3.4.2.6
10:51:24   39.98 → 11:31:23    │
11:31:23   60.00 → 12:31:23    ┘  (running)
```

**Zero gap anywhere, the reinstall included.** The 09:41:22 record had 28 minutes left when HEAD's
first poll found it — under the threshold — so it was renewed at once instead of being left to lapse.
The record survives an APK replacement because it lives in the database, and the refactored code did
the right thing on its first hardware poll.

**The Hold/Renew boundary was observed directly**, which the morning's build could not show because
it lacked the field:

```
11:11:24  suspendRecord=open since 10:51 am (39min left of 60)   ← held,    39 > 30
11:31:23  renewed the 0% basal with 20 min left, new=true        ← renewed, 20 < 30
```

`SuspendRecordDecision.decide` returning `Hold` then `Renew` on hardware, matching the unit tests
that pin the same boundary. **The extraction preserved the behaviour**, which was the whole question.

⚠ **The `LAPSED` detector has still never fired.** Its silence here proves no lapse occurred — the
denominator is met, three renewals — but it does not prove the detector works. That direction rests on
`SuspendRecordDecisionTest`, which pins `lapsedByMs` in all three of its states. Recorded rather than
assumed away, though it differs from this file's four cautionary tales in the way that matters: it is
a total function of three values, and its inputs are examined by test rather than supplied by
whatever the hardware happened to do.

##### ⚠⚠ It first passed on a build that predates the refactor of the code it tested

The tested build is **3.4.2.4 of 2026-08-02 18:23**. HEAD is **3.4.2.6**, carrying the
`SuspendRecordDecision` extraction written 2026-08-03 — same logic, lifted out so a test could reach
it, pinned by 15 unit tests, **never run on hardware**. The `[ypso-suspend] LAPSED` detector and the
minutes-remaining field have not run either; they were added the same day and this run predates them.

**This is the fifth variant of the same mistake, and the first one caught before it was written up as
a pass.** Not written; written but never re-asserted; re-asserted but closed by an automatic cancel;
renewed only after a gap; and now **fixed, then refactored, and verified on the build from before the
refactor**. The pattern is not the pump and it is not even the logic — it is that *what was tested*
and *what will ship* drift apart between the fix and the test.

It costs one stopped pump and two hours on HEAD to close, and until then the honest statement is:
the renewal fix is verified; the code that implements it today is not.

##### ⚠ The first write-up of this was wrong twice, and both errors were mine

It said *"about half the time"* and read as a ⚠⚠ safety finding. Neither survives the full dataset:

- **Frequency.** "Half" came from a **two-sample** run — one miss, one catch. Thirteen cycles say
  2-in-13. Reporting a ratio from two observations is not measurement.
- **Severity, and this is the worse error.** A missing suspend record means AAPS assumes profile
  basal *is* being delivered, so it **over-states IOB** — and §0's own table says plainly that
  over-stating IOB makes AAPS dose *less*, which is the conservative direction; under-stating is what
  causes stacking. `isSuspended()` also stays true off the delivery-mode byte throughout, so the loop
  is held off regardless. The cost of a 20-minute hole is IOB over-stated by 20 minutes of basal,
  with the loop already inhibited.

**And in normal use it is close to unreachable**, which the pump's owner pointed out and the code
confirms. It needs the pump stopped for **over an hour continuously**:

| Real scenario | Reaches it? |
|---|---|
| Site change with the pump stopped, 15–30 min | **No** — the record never expires |
| Shower, swim, any planned disconnect | **No** — AAPS's own Disconnect Pump writes `EMULATED_PUMP_SUSPEND` ("Initiated by AAPS as zero TBR"), a different record type this code never sees |
| An occlusion noticed and cleared | **No** |
| Pump left stopped >1 h unattended | Yes — and that is an alarm condition already being ignored |

So this was found by constructing a fourteen-hour stopped-pump scenario that corresponds to nothing
anyone does. **That is §8.7's pattern with these notes' own name on it**, and the correction came
from the person who uses the pump — as it did for the bolus increment the day before. It is fixed
because "while the pump is stopped a 0% record is open" is either an invariant or it is not, and a
documented-but-false invariant is what somebody relies on later. Not because the exposure mattered.

##### ⚠⚠ The check written for this phase could not fail

`syncSuspendedState` runs *before* `logDeliveryState`, so by the time a line is written a fresh
record always exists. Every `[ypso-state]` line across the whole run reads `suspendRecord=open`,
including the pairs bracketing a twenty-minute hole. The stated failure test — *"a single `none`
while stopped is a failure"* — **reported zero across 13h40 containing two real gaps.**

The gap is only visible by arithmetic on consecutive record start times. **This is the fourth check
in these notes that could not fail**, after §4f's type-19 echo, §5's epoch, and §8.5's gap warning —
and it was written the same day as the write-up of the other three. Knowing the pattern did not
prevent it. What eventually caught it was reasoning about *ordering* — that the log line is written
after the thing it reports on — which is the same question in a different dress: **what would this
check look like if the thing it watches for had happened?**

**Fourth variant of one bug.** Not written; written but never re-asserted; re-asserted but closed by
an automatic cancel; renewed only after a gap. Each fix correct, each leaving another route to the
same wrong belief, and none of them the pump.

##### ✅ The check that can fail — added 2026-08-03, before the re-run

The re-run needed an instrument, because the pre-fix run had shown that the obvious one reports a
pass through a hole. Two additions, both read-only:

- **`suspendRecordExpiryMs`**, one field carried between polls, holding when the record observed at
  the previous poll was due to expire. If a stopped poll finds no open record *and* that field is
  set, the record lapsed, and the width is `now − expiry` rather than an inference. It logs at error
  as `[ypso-suspend] LAPSED`. This fires **before** the replacement is opened, which is the ordering
  the old check got wrong.
- **Minutes remaining on the `[ypso-state]` line.** `suspendRecord=open` is true on both sides of a
  hole; `open since 2:30:12 am (41min left of 60)` is not. A held record sawtooths 60 → 40 → 60 and
  never approaches zero, so **one line from the middle of a run** answers the question. That matters
  because a record that lapses partway reads as a pass from either end.

The mode-`UNKNOWN` branch clears the watch deliberately: no record is maintained in that state by
design, so an expiry there is not this failure and reporting it as one would train the reader to
ignore the line.

`SALINE_TEST_PROCEDURE.md` **6b-RERUN** is the procedure, and it names its own denominator: an empty
`LAPSED` grep is evidence only if `renewed the 0% basal` appears at least twice, or the run never
reached an expiry and the empty result means nothing. That guard is there because four checks in
this file have now passed on inputs that could not have made them fail.

**And one thing the pattern does not require, but this pump does.** `isDelivering` is false both for
"confirmed stopped" and for "delivery mode never identified" — right for gating the loop, where the
safe answer to *don't know* is *don't act*, but wrong for writing a record. Only two byte-0 values
out of 256 are known (§8.3), so treating unknown as stopped would write a therapy record on the
strength of a byte nobody had identified.

The sharpest version of that worry was **the bolus**: if the pump reported a third value while
delivering, unknown-as-stopped would open a suspend record on every bolus, and an app killed
mid-bolus would leave one open for its full hour telling AAPS basal was off when it was not.
**That has now been checked and the answer is no** — byte 0 reads `10` throughout a bolus, the same
as an idle running pump (§8.3, verified 2026-08-01).

The guard stays anyway, and deliberately. It was never really about the bolus; it is about the 253
values still unidentified, and the cartridge-out and reservoir-empty states are two of them that
have yet to be read. `PumpStatus.operatingMode` is a genuine three-valued state, only `STOPPED`
opens the record, `UNKNOWN` logs loudly and writes nothing, and the loop stays held off either way.

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

### ✅ [fixed] Closed: `YpsomedCapabilities` promised three things the driver refuses

**Fixed 2026-08-03, in `YpsoPlugin.pumpDescription` rather than in `PumpCapability`.** The three
flags are cleared where the plugin answers for itself:

```kotlin
override val pumpDescription: PumpDescription = PumpDescription().fillFor(PumpType.YPSOPUMP).apply {
    isExtendedBolusCapable = false
    supportsTDDs = false
    needsManualTDDLoad = false
}
```

Two reasons it went here and not into shared `core/data`. Every line changed outside `pump/ypso/` is
future rebase conflict surface (§9), and this cost none. And **every consumer reads these back
through `pump.pumpDescription`** — `LoopPlugin`, `ActionsFragment`, `TreatmentsActivity`,
`TDDStatsActivity` — which is this plugin's property to answer; `VirtualPumpPlugin` adjusts its own
description the same way. The `PumpCapability` entry is left as upstream ships it.

The combined ("multiwave") bolus has no capability flag of its own — AAPS reaches it through the
extended one — so clearing `isExtendedBolusCapable` withdraws both. Four tests pin it, including
that the underlying `PumpType.YPSOPUMP` still declares `ExtendedBolus`, so the test fails if someone
"fixes" this by editing shared code instead.

**TDD stays a genuine opportunity, now stated as one rather than promised.** The data exists — the
pump logs `23 Daily Total Insulin` with the total in `v1` as centi-units, and
`createOrUpdateTotalDailyDose` takes that shape — so when `loadTDDs` is implemented, the fix is to
delete two lines here, not to edit `core/data`.

The refusal comment moved too. It read `ypso_read_only_build` → "Saline test build", which named a
temporary state as the reason for a permanent refusal and would have gone on being shown after the
build stopped being a saline one. It now reads "Not supported by this driver".

#### The original entry

⚠ **`YpsomedCapabilities` promises three things the driver refuses**

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

**Not changed here, deliberately** — and this was right about the constraint and wrong about the
conclusion. `PumpCapability` lives in shared `core/data` and is not this driver's to edit; what was
missed is that it did not need editing, because `pumpDescription` is the plugin's own property and
every consumer goes through it. Two ways forward were listed, and they point in opposite directions:

- Drop `ExtendedBolus` until it is implemented and tested, matching Medtrum.
- Implement `loadTDDs` instead of dropping the TDD capabilities — **the data already exists.** The
  pump logs `23 Daily Total Insulin` with the total in `v1` as centi-units (`v1=1944` → 19.44 U), and
  `PumpSync.createOrUpdateTotalDailyDose` takes exactly that shape; Combo passes `bolusAmount = 0.0,
  basalAmount = 0.0` and only a total, which is all this pump offers. It needs a small SDK method to
  expose the entries, since `YpsoHistoryMapper` deliberately maps type 23 to null.

### ⚠⚠ [open] `YpsoBolusSpeed` controls nothing, and its default was measured wrong — 2026-08-03

**The preference never reaches the pump.** Its whole effect:

```kotlin
pumpManager.bolusSpeedUnitsPerMinute = configuredBolusSpeedUnitsPerMinute()   // YpsoService
private fun expectedDeliveryMs(amount: Double) = ((amount / (speed / 60.0)) * 1000)
```

`expectedDeliveryMs` and `bolusTimeoutMs`, and nothing else. It is a **local timeout estimate**, while
the plugin presents it as a three-way user choice and its own KDoc calls it *"the pump's own speed
index"*. Nothing is written to the pump; the bolus command carries amount, duration and type, and no
speed field.

**And the default is wrong.** It came from the decrypted Lotus export — *"defaults to the 12 U/min the
Lotus export had configured"* — an assumption with nothing to check it, because **this pump has no menu
item for bolus speed at all** (its owner, 2026-08-03). Two boluses measured the same day rule 12 out:

| | asked | sent → Idle | needs at 12 U/min | needs at 33.3 U/min |
|---|---|---|---|---|
| 06:45:17 | 0.3 U | **1.27 s** | 1.50 s | 0.54 s + overhead |
| 06:53:09 | 3.92 U | **8.33 s** | 19.6 s | 7.06 s + overhead |

The 0.3 U bolus is the one that settles it: it reached `Idle` in 1.27 s **including** the command
round trip and up to 500 ms of poll granularity, and 12 U/min needs 1.50 s for the delivery alone.
Both samples fit ~33 U/min with a consistent ~0.7 s overhead. **The pump appears to deliver at a
fixed ~33 U/min.**

**It fails in the safe direction, which is why nothing surfaced it.** The timeout is expected + slack,
so believing the pump slower than it is makes the driver wait *longer*; it can never abandon a bolus
still running. Nothing has been mis-dosed and nothing is at risk.

**What is wrong is the claim.** A dropdown offering "3 / 12 / 33.3 U/min" tells the user they are
configuring the pump. They are configuring a log line and a timeout. Same species as
`YpsomedCapabilities` promising an extended bolus — and worse in one respect: the capability could be
caught by pressing the button, whereas this one had no pump menu to contradict it and needed a
stopwatch.

#### ✅✅ [verified] SOLVED — "bolus speed" is a loop in the phone, and the parameters are known

2026-08-03. Lotus's own settings screen labels its four options:

```
unlimited              (33.3 U/min, the pump's native rate)
12 U/min   =  1 U   per 5 s
 6 U/min   =  0.5 U per 5 s
 3 U/min   =  0.4 U per 8 s
```

**It is not a speed. It is a chunk size and an interval, executed by the phone.** Confirmed in the
pump's own event log — a 2 U bolus issued from Lotus with the setting on 3 U/min:

```
seq=160875 type=2(Bolus Immediate) v1=41   -> 0.41 U
seq=160876 type=2(Bolus Immediate) v1=41   -> 0.41 U   +8 s
seq=160877 type=2(Bolus Immediate) v1=41   -> 0.41 U   +8 s
seq=160878 type=2(Bolus Immediate) v1=41   -> 0.41 U   +8 s
seq=160879 type=2(Bolus Immediate) v1=36   -> 0.36 U   +8 s
                                              = 2.00 U exactly
```

**Five ordinary standard boluses, eight seconds apart**, the last trimmed so the total lands exactly.
The pump's display showed it as "multiple small boluses with short breaks", which is literally what
it is. **There is no pump-side speed at all** — which is why nothing moved in indices 2–13, why an
AAPS-commanded bolus was unaffected, and why the settings hunt was always going to come back empty.
Four independent observations, one explanation.

**⚠ This reverses a recommendation made earlier the same day.** When asked whether the driver could
emulate a slower bolus, this file's author argued **against** chunking on §0 grounds — *"the pump owns
the bolus once it has been told"*, and chunking makes the phone the sequencer. That objection is
weaker than it was stated, and the evidence moved:

- **Each chunk is a complete, pump-owned bolus** that the pump logs as `type=2`. A phone that dies
  mid-sequence stops issuing chunks; the pump finishes only the one in flight. The result is
  **under**-delivery, and the driver can report exactly what went in because every chunk is in the
  log — which is what §4f's partial-bolus path already does.
- **The manufacturer's own app does this**, with these parameters, on this pump.
- **The history sync handles the result correctly**, demonstrated here without being asked to: all
  five chunks imported as separate records totalling 2.00 U, and AAPS's own 2 U bolus stayed a single
  live row alongside them (the extra rows carry `referenceId`, so they are historic versions, not
  duplicates — 7c's finding holds under a five-entry burst).

**What implementing it would cost**, stated so the decision is made with open eyes: the bolus flow
opens a provisional record and settles it against **one** log entry, and would need to settle against
N; the command queue would be held for the whole delivery (50 s for 10 U at 12 U/min, against 15
minutes for the extended-bolus route); and a 10 U bolus becomes ten treatment records — noisy, but
*accurate*, because the insulin genuinely went in as ten pulses.

**Recommendation: this, not the extended bolus, is the way to slow delivery for comfort.** It hits
exactly the requested 12 U/min, needs no new pump capability, keeps every delivery a confirmed
standard bolus, and the parameters come from the manufacturer rather than from a guess. It is a new
delivery behaviour and wants its own saline phase.

#### ✅ [verified] It is not a pump setting AAPS can reach — tested 2026-08-03

The settings-index hunt was run and came back negative, decisively.

**mylife Lotus offers four speeds, not three**: *unlimited (33.3), 12, 6, 3 U/min*. `YpsoBolusSpeed`
offers three and maps `0→3, 1→12, 2→33.3`, so the driver's list is missing **6 U/min** and its
index mapping is wrong independently of everything below.

**The experiment.** Lotus was set from unlimited to **3 U/min** on the study phone, then
`probeUnknownSettings()` re-read indices 2–13:

```
              BEFORE 14:23    AFTER 15:29
index 2           10              10
index 3           50              50        (bolus increment, mapped §8.8)
index 4        26247           26247
index 5        32105           32105
index 6        17505           17505
index 7          188             188
index 8            2               2        ← the obvious candidate. Unchanged.
index 9–13   0,3,0,1,1       0,3,0,1,1
```

**Nothing moved.** And `index 8 = 2` — which fit "speed index 2 = 33.3 U/min" so neatly — held. That
is the fit §8.8 warns is the weakest evidence there is, and it failed the moment it was tested,
which is the whole point of testing it rather than writing it down.

**Then the measurement that decides it.** With Lotus set to 3 U/min, a **2.0 U bolus commanded by
AAPS** was timed:

```
15:34:08.194  BolusNotification 01   (delivering)
15:34:12.040  BolusNotification 04   (done)
```

**3.85 s for 2.0 U = 31.2 U/min.** At 3 U/min it would have taken **40 seconds**. Unchanged from the
30.8 U/min measured before Lotus was touched.

**So the setting does not apply to a bolus commanded over BLE by another controller**, and Lotus had
certainly reached the pump — it rewrote all 24 basal slots on connect (§4i) in the same session.

**Conclusion: `YpsoBolusSpeed` should be deleted, not mapped.** There is no pump-side speed for an
AAPS-commanded bolus to obey, so the preference cannot be made to work by finding an index. It
configures a log line and a timeout estimate, and presenting it as a three-way choice over the
pump's behaviour is the same species of false promise as a capability the driver refuses.

⚠ **Two limits on this result, stated rather than glossed.** `probeUnknownSettings()` reads **only
indices 2–13** — nobody has ever read above 13, so "not in 2–13" is not "not anywhere". And whether a
bolus commanded by *Lotus itself* would run at 3 U/min is untested; if it would, the protocol
supports slow delivery by some means not yet found, and the hunt would be worth resuming above index
13. If it would not, Lotus's setting is cosmetic there too and the matter is closed.

⚠ **Do not map it to a settings index on the strength of the obvious candidate.** If speed index 2 =
33.3 U/min then `index 8 = 2` from the §8.8 probe is a tempting fit — and that is exactly the shape of
the near-miss that almost mapped the bolus increment to index 2, where *"the value agreed with the
assumption, and the assumption was the thing that was wrong"*. Settle it the way index 3 was settled:
change the speed from a controller that can, re-run `probeUnknownSettings()`, and require **one** index
to move with the other eleven as the control. If nothing can change it, it is firmware-fixed and the
preference should simply go.

**Not changed during the hardware session** that found it — §8.6's rule. The fix when it happens is to
relabel it as the timeout estimate it is, or delete it and hard-code a generous timeout.

#### The six Phase 9 design decisions, settled before a line of code — 2026-08-03

Phase 9's table asks six questions and says that each one changes what the tests must check. They are
answered here **first**, with the reasoning, so that 9a–9e are written against a decided design rather
than against whatever the code turned out to do. Nothing below is a hardware result; it is the shape
the hardware is about to be asked to falsify.

**1. The loop lives below the mutex, in `YpsoPumpManagerImpl`.**

`withConnection` is taken **once**, for the whole sequence. The alternative — a loop in `YpsoService`
calling `deliverBolusConfirmed` N times — releases the connection lock between chunks, and everything
that is wrong with that is a safety property rather than a performance one: AAPS's command queue could
land a status read, a temporary basal or a second bolus **between two chunks of the first one**. A
20-second history sync arriving between chunk 3 and chunk 4 would also stretch the interval to
whatever that read happened to cost. Holding the lock makes the sequence atomic against the rest of
the driver, which is the only version of this that can be reasoned about.

⚠ `pumpMutex` is a plain `Mutex` and is **not reentrant**, so the chunked path cannot call
`deliverBolusConfirmed`; the body of that method is extracted into a `deliverOneBolus` that assumes
the lock is already held, and both public entry points wrap it.

That extraction moves one line that matters more than it looks: **`cancelRequested = false` moves out
of the per-bolus body and up into the entry points**, so it is reset once per *sequence*. Left where
it is, chunk k+1 would wipe the cancel that arrived during chunk k, and the sequence would carry on
delivering after the user pressed stop — which is 9b's failure, built in.

**2. Every chunk is confirmed from the pump's log. The echo is not evidence.**

The table offered *"per-chunk echo (`fastTotal`) then one log read at the end"*. The second half is
kept and the first half is **rejected as confirmation**: `fastTotal` is the pump restating the total
it was told a moment ago. That is the type-19 shape exactly (§4f) — a number that agrees with the
request because it *is* the request — and §8.8 is the standing rule that such a value is the weakest
evidence there is. It keeps the job it already does well, catching a misparsed payload *layout*, and
it never decides how much went in.

What makes per-chunk log confirmation affordable is §5's index 0: the newest entry is the first one
read, and the chunk that just finished should be it. A **3-entry** read costs ~600 ms against a
5-second interval, where the standard 5-entry window costs ~1 s. Three rather than one because a
single interloping entry would otherwise hide the chunk entirely and turn a delivered chunk into "not
confirmed"; three covers two interlopers, and `findBolusRecord`'s existing retry covers the rest.

The end-of-sequence read is then not a second confirmation but a **reconciliation**: the chunk
sequences are already known, and what the summary reports is their sum.

**3. N records. This is not a preference — one synthetic record would be corrupted by the sync.**

Phase 9 framed this as accuracy versus tidiness. It is neither: `syncBolusWithPumpId` **upserts on
`pumpId`** (§8.5). A single 10 U record filed under chunk 1's sequence would be rewritten to chunk 1's
1 U by the first history sync that reached it, and the other nine chunks inserted alongside. One
record is not a simplification, it is a defect with a delay on it.

**The provisional record is opened per chunk, immediately before that chunk is sent** — not once for
the full amount. §4g opens a provisional at the full requested amount because *the pump owns the bolus
once it has been told*; in a chunked sequence the pump has only been told about one chunk, and insulin
in chunks that were never commanded will never be delivered by anything. Recording 10 U when the phone
died after 2 U is not conservatism in §0's sense — §0 is about outcomes that are *unknown*, and this
one is known. A per-chunk provisional covers exactly the window §4g exists for, which is the chunk in
flight, and leaves the un-sent chunks unrecorded because they are un-sent.

That needs the chunk results to reach `YpsoService` **as they happen** rather than as a list at the
end, so the manager takes two callbacks — one before a chunk goes out, one when it has settled. The
`PumpSync` calls stay in the app module where they already live.

**4. `success = false` at chunk 7 of 10, `bolusDelivered` = the sum of the confirmed chunks. Any
chunk that does not confirm ends the sequence.**

§0's rule is one-directional: never report more than the pump confirmed. Seven chunks confirmed is
seven chunks reported, and `complete` is true only when every chunk confirmed *and* the total matches
the request. Stopping on the first unconfirmed chunk is 9e's requirement — a pump that has alarmed and
stopped delivering should not be sent ten more boluses — and it also settles 9c, because a chunk
failing on a dead link is the same case. **A failed chunk never resumes**, even if `ReconnectManager`
gets the link back mid-sequence: insulin resuming after an unpredictable gap is not the comfort
feature the user asked for, and it makes the outcome depend on reconnect timing.

For the same reason a BLE exception inside the loop is caught per chunk rather than being allowed to
propagate. `awaitResult` turns a thrown exception into the fallback result, which reports
`deliveredUnits = 0.0` — for a sequence that already put six chunks in, that is under-reporting, and
9c calls under-reporting the failure and honest under-*delivery* the pass.

**5. `YpsoBolusSpeed` survives, stops lying — and its default flips to *unchunked*.**

It becomes real for the first time, and four things change with it:

- **It is no longer "the pump's own speed index".** It is a phone-side chunk size and interval, which
  is what it always was in Lotus and never was here.
- **Its stored values become self-describing** — `unlimited`, `12`, `6`, `3` — rather than the
  indices `0/1/2`. Adding a fourth option to an index list silently remaps every value already
  stored, and this particular list is missing its option in the *middle* of the range. An
  unrecognised value falls back to `unlimited`, which is the proven path.
- ⚠ **The default becomes `unlimited`.** Today's default is `1`, which under the new meaning would
  chunk **every bolus** on a delivery path nobody has run. Phase 7's precedent is explicit
  (§8.5): flipping a default is a statement that the shipped build is ready, and it is held until the
  phase passes. Every phase from 1 to 8 was run against unchunked delivery and stays that way.
- **The fourth option, 6 U/min, lands with the relabel after 9a–9e pass**, per Phase 9's own
  "then, and not before". 9a needs 12 and 9b needs 3, both of which the existing three-way list
  already offers, so nothing is blocked by holding it.

And the preference stops feeding the timeout. `expectedDeliveryMs` uses a constant **~33.3 U/min**,
the rate actually measured on this pump — which is not a tidy-up but a prerequisite: with the old
arithmetic a 1 U chunk at a configured 12 U/min is "expected" to take 5 s, and the wait before reading
the log would exceed the 5-second interval it is supposed to fit inside. The measured native rate is
what makes 12 U/min reachable at all.

**6. A five-minute ceiling on the sequence, enforced by stepping up to the next manufacturer rate.**

The held command queue is the cost: for the length of the sequence there is no status read, no
temporary basal, no SMB and **no alert check** — and an occlusion during a slow bolus is exactly when
the alert check earns its keep (§4g). Five minutes is one AAPS loop cycle, and the standard bolus
already holds the queue for ~20 s, so this is a factor of fifteen and not a factor of a thousand.

At 25 U (`maxBolusAmount`) the nominal spans are 125 s at 12 U/min, 250 s at 6, and **504 s at 3** —
so the ceiling binds only at 3 U/min above ~14.8 U. When it binds, the rate **steps up to the next
faster option Lotus publishes** rather than falling back to the pump's native ~33 U/min. Refusing the
bolus is the wrong failure (the user asked for insulin, and a comfort setting should not withhold it);
falling straight to unchunked would hand someone who configured 3 U/min a 20 U bolus in 36 seconds
without warning. Stepping up keeps every parameter a manufacturer figure — Phase 9's own instruction
not to invent others — and at the current 25 U maximum it never has to reach `unlimited`. Whatever it
does is logged and goes into the result comment.

**⚠ And one property that falls out of all six: the configured rate is an upper bound, never a
floor.** The interval is a minimum gap between chunk *starts*; if a chunk's confirmation overruns it,
the next chunk goes out late and the sequence runs slower than configured. Slower is the safe
direction for a comfort feature, and the driver will never deliver *faster* than configured. It is
recorded here because a test that reads 5.6-second spacing where 5.0 was asked for is a pass, and
somebody will otherwise file it as a defect.

#### What this makes 9a adjudicable on

9a's trap is that an unchunked 2 U bolus also ends 2.00 U down with `success=true`, so **only the
entry count and the spacing distinguish a working sequence from no sequence at all**. Both are made
directly readable rather than inferable, on one greppable line per sequence:

```
CHUNKED: asked 2.0U at 12 U/min -> 2 chunks of [1.00, 1.00] U every 5.0s
CHUNKED: chunk 1/2 1.00U sent +0.0s, seq=160901 v1=100 -> 1.00U (pump 15:02:11)
CHUNKED: chunk 2/2 1.00U sent +5.1s, seq=160902 v1=100 -> 1.00U (pump 15:02:16)
CHUNKED: 2/2 confirmed, 2.00U of 2.0U, starts [0.0, 5.1]s, pump entries 5s apart, seqs 160901..160902
```

Phone-side spacing and the pump's own entry timestamps are both printed, because they are different
measurements and the pump's is the one that matters — the phone's says when the command left.

### ✅ [verified] The extended bolus works, needs ≥15 min, and lands in the wrong table — 2026-08-03

Phase 8a, run on saline behind the YPSO tab's bench probe. **The pump does extended boluses.**

```
control: standard 0.1U (type=1)      -> accepted
1.0U over 1min  (type=2)             -> REJECTED   (GATT status 130, ATT application error)
1.0U over 15min (type=2)             -> ACCEPTED
t+0s   slow[status=1 seq=160798 injected=0.0  total=1.0] duration=0/15
t+2s   slow[status=1 seq=160798 injected=0.17 total=1.0] duration=0/15
t+30s  slow[status=1 seq=160798 injected=0.17 total=1.0] duration=0/15
cancel -> slow[status=0 total=0.0] duration=0/0          CONFIRMED by read-back
```

**Four results.**

**The pump enforces a minimum duration.** 1 minute refused, 15 accepted — and AAPS's
`extendedBolusSettings` 15-minute step, which this file dismissed the same morning as UI
granularity, turns out to have been describing the pump. The first probe run rejected at 1 minute
was written up as *"extended bolus is not available on this firmware"*; that was one observation
with three possible explanations and the conclusion was an over-claim. The sweep separated them.

**Delivery is spread, and pulsed rather than continuous.** `slowInjected` reached **0.17 U within
2 s and held there for 28 more**. 0.167 is exactly 1/6 of 1 U, so a 15-minute bolus looks like six
pulses ~2.5 min apart. At the pump's ~33 U/min the whole unit would have gone in 1.8 s, so the
duration is unambiguously honoured.

**Cancel works**, `cancelBolus("extended")`, confirmed by read-back — Phase 8d's safety half,
exercised for the first time.

**⚠⚠ And it reaches AAPS as the wrong kind of record.** `extendedBoluses` is empty; the delivery is
in `boluses` as `NORMAL`, `pumpId=160798`. The raw entry says why:

```
seq=160797 type=2(Bolus Immediate) v1=10   <- the control
seq=160798 type=3(Bolus Delayed)   v1=17 v2=0 v3=0
```

The pump gives an extended bolus **its own event type, 3 "Bolus Delayed"**, and `YpsoHistoryMapper`
maps both 2 and 3 to `BolusDelivered`. So the driver can tell them apart and does not.

**⚠ The entry carries no duration** — `v2` and `v3` are zero — and
`syncExtendedBolusWithPumpId` needs one. **An extended bolus therefore cannot be reconstructed from
history alone**; the duration has to come from a record the driver makes at delivery time, the same
shape as the bolus settle path. That is a design constraint worth having found before writing the
code rather than after.

**Why the table matters, in the direction that bites.** A *completed* 15-minute extended bolus
logged as one `NORMAL` bolus would place all its insulin at a single instant. If the pump writes the
entry when the bolus ends — which §4f established for the standard bolus — then for fifteen minutes
AAPS sees no IOB for insulin that is going in, and may dose on top of it. The stacking direction.

#### ⚠⚠ What this means for the queue, and for how the method must be written

`slowStatus` stayed **1** for the whole 30 s: the pump reports an extended bolus as *running* for its
duration, not as *programmed*. So `deliverExtendedBolus` must **not** reuse `deliverBolusConfirmed`'s
wait-for-`Idle` loop, or a 15-minute bolus would hold the AAPS command queue for fifteen minutes —
no status read, no temporary basal, no alert check.

What it must do instead is what the probe did: send, confirm by read-back that `slowTotal` and
`totalDuration` echo what was asked, and return. That satisfies §0 while changing what `success`
claims: *an extended bolus of this amount and duration is running*, not *the insulin has been
delivered*. That distinction has to be written at the call site, because every other delivery method
in this driver means the second thing.

#### ⚠ And the comfort goal is not served by this at 12 U/min

The motivation was comfort on large boluses at around 12 U/min. With a **15-minute minimum**, an
extended bolus gives 10 U over 15 min = **0.67 U/min** — far slower than asked for, and slower than
Omnipod's 1.5. There is no way to ask this pump for 12 U/min through the extended bolus: that would
need a 15-minute bolus of 180 U. **If ~12 U/min specifically is the target, the bolus-speed setting
is the mechanism, not this** — see the settings-index route in §8.8, still unmapped.

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

## 9. Tracking upstream, and the changed-file surface

This driver lives on a long-running branch of a project that keeps moving. Everything below exists
to make the next rebase cheap.

### The remotes

```
origin     https://github.com/andrewgoodnz/AndroidAPS      the fork
upstream   https://github.com/nightscout/AndroidAPS        the project
```

Work happens on **`ypso-integration`**, branched from `upstream/master`.

### The rebase, step by step

```bash
git fetch upstream
git log --oneline HEAD..upstream/master | wc -l        # how much has moved

git checkout ypso-integration
git checkout -b ypso-integration-rebase-$(date +%Y%m%d)   # ⚠ always. See below
git rebase upstream/master
```

**Always rebase on a throwaway branch.** A rebase of this branch replays every ypso commit, and the
commit messages *are* the record of why each change was made — several are the only place a
hardware finding is written down at full length. A rebase that goes wrong and gets resolved by
force is how that record is lost. If the throwaway branch comes out clean and builds, fast-forward
`ypso-integration` onto it; if it does not, delete it and nothing was risked.

Then, in order, and do not skip any of them:

```bash
./gradlew :pump:ypso:sdk:testFullDebugUnitTest     # 65 tests — the wire decodes
./gradlew :pump:ypso:testFullDebugUnitTest         # 35 tests — the plugin and the suspend logic
./gradlew :app:assembleFullDebug                   # the whole thing still builds
```

And then **read `SALINE_TEST_PROCEDURE.md` §0 before installing it on the test tablet.** A rebase
onto a moved AAPS is not a no-op for a pump driver: `PumpSync`'s contract, `Pump`'s interface and
the command queue's behaviour are all upstream's to change, and all three are load-bearing here.

### The changed-file surface, audited 2026-08-03

Every file this branch touches outside `pump/ypso/` is a place a rebase can conflict. The list is
deliberately short, and each entry is here because it cannot be anywhere else:

| File | Change | Why it cannot live in the module |
|---|---|---|
| `settings.gradle` | +3 lines: `:pump:ypso`, `:pump:ypso:sdk`, `:pump:ypso:sdk-common` | Gradle has to be told the modules exist |
| `app/build.gradle.kts` | +1 line: `implementation(project(":pump:ypso"))` | The app has to depend on the driver |
| `app/src/main/kotlin/app/aaps/di/AppComponent.kt` | +2 lines: `YpsoModule` | Dagger's component list is upstream's file |
| `app/src/androidTest/kotlin/app/aaps/di/TestAppComponent.kt` | +2 lines: `YpsoModule` | The same list again, for instrumented tests. Miss it and the androidTest build stops compiling |
| `app/src/main/kotlin/app/aaps/di/PluginsListModule.kt` | +7 lines: `bindYpsoPlugin` at `@IntKey(175)` | The plugin map is upstream's file |
| `gradle/libs.versions.toml` | +1 line: `no-nordicsemi-android-ble` | Version catalogue is central by design |
| `.gitignore` | +1 line: `/reference` | Keeps the vendor sources and decrypted exports out of the repo. Nothing under `reference/` is tracked |
| `PORTING_NOTES.md`, `SALINE_TEST_PROCEDURE.md`, `README_YPSO.md` | new files | New files never conflict |

**Seven files, 19 added lines and 2 changed** — down from 91 changed files before the donor module
was deleted. `git diff --numstat` says so exactly:

```
1  0  .gitignore
1  0  app/build.gradle.kts
3  1  app/src/androidTest/kotlin/app/aaps/di/TestAppComponent.kt
3  1  app/src/main/kotlin/app/aaps/di/AppComponent.kt
7  0  app/src/main/kotlin/app/aaps/di/PluginsListModule.kt
1  0  gradle/libs.versions.toml
3  0  settings.gradle
```

The two "changed" lines are both a trailing list entry gaining a comma. Every other line is an
additive insertion, which is the cheapest kind of conflict there is — and `PluginsListModule`,
`AppComponent` and `TestAppComponent` are exactly the files upstream edits when *it* adds a pump, so
those three are the ones that will actually conflict.

**Three files came off this list on 2026-08-03**, by deleting the donor module (§1):

- `core/interfaces/.../Notification.kt` — `YPSOPUMP_PUMP_ALARM = 95`
- `pump/common/.../defs/PumpDriverState.kt` — `Sleeping`, `ErrorCommunicatingWithPump`
- `pump/common/src/main/res/values/strings.xml` — the two strings behind them

All three were added for `:pump:ypsopump` and referenced by nothing in `:pump:ypso`. They are now
byte-identical to upstream. `bash.exe.stackdump`, a crash artefact committed by accident, went too.

**`PumpType.YPSOPUMP` and `PumpCapability.YpsomedCapabilities` are upstream's already** and are not
modified here — which is why the extended-bolus and TDD capabilities are withdrawn in
`YpsoPlugin.pumpDescription` instead (§8.6). Do not "fix" that by editing `core/data`; a test asserts
the upstream declaration is still there.

### Checking the surface has not grown

```bash
git diff --stat $(git merge-base HEAD upstream/master) HEAD -- . ':(exclude)pump/ypso'
```

Anything in that output not in the table above is new, and each new entry should have to justify
itself. The question to ask is the one that took three files off the list: **is this here for the
driver, or for something the driver no longer contains?**

## 9a. Build environment gotchas

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

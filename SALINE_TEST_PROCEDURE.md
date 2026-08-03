# YpsoPump driver — saline test procedure

Hardware procedures for the YpsoPump driver's write path. Companion to `PORTING_NOTES.md`,
which records what the pump has actually been observed to do.

## ⏳ Everything still to run — read this first

As of 2026-08-03, **all four delivery phases have passed, Phase 5 is answered except two rows,
Phase 6 has passed five of six steps, and Phases 7 and 7f have passed in full.** History sync is
**on by default**, and a quiet poll costs ~580 ms rather than 20 s. What remains is observation, not
capability — with one exception, and it is first in the list below.

✅✅ **6b passed on 2026-08-03, and was re-confirmed the same day on HEAD after a flash** — seven
renewal cycles across two builds, **zero uncovered time**, decided on the database. **Phase 6 is
complete and no correctness gate remains open.**

**Phase 8 (extended bolus) ran 2026-08-03**: it works, needs ≥15 min, and lands in the wrong table.
**Phase 9 (chunked bolus) is written and is the next thing to build** — it is the answer to slow
delivery for comfort, and it needs no new pump capability.

**Still to run, most valuable first:**

| # | Test | Why it matters | Where |
|---|---|---|---|
| A | ~~**6b — the renewal fix**~~ | ✅✅ **CLOSED 2026-08-03, verified twice.** 2h33m on the 08-02 build (4 cycles) and then re-confirmed on **HEAD 3.4.2.6** after a flash (3 cycles), with **zero uncovered time at every transition including across the reinstall**. Adjudicated on `temporaryBasals.duration`. The Hold→Renew boundary was seen on real polls (39 min left held, 20 min left renewed) | **6b**, §8.6 |
| B | **Fix `insertAnnouncement`'s dedupe key** | It keys on `now()`, not `pumpId`, so it is the one sync path that is not idempotent. The watermark currently hides it. Worth fixing on its own terms rather than depending on the thing that hides it | §8.5 |
| C | ~~**Phase 5c — cartridge out and priming**~~ | ✅ **CLOSED 2026-08-03.** Cartridge out reads `byte0=3` with the reservoir `FF FF FF FF` (−1) — the first sighting of reservoir `-1`, handled correctly. The *priming* row is recorded as **unreachable by this method**: liveness cannot be proved because the reservoir reads −1 throughout, so a mid-prime reading is byte-identical to one taken after it | **Phase 5c**, §8.3 |
| D | ~~**Phase 5e — reservoir empty mid-delivery**~~ | ✅ **CLOSED 2026-08-03.** Reads `byte0=3` with the reservoir a **known `0`** — not the `-1` of a cartridge change, so the pump does distinguish empty from absent. Alert count 147 → 148, `104 Cartridge Empty`, urgent. **Phase 5's table is complete** | **Phase 5e**, §8.3 |
| E | **Reconnect mid-bolus** | Pull BLE range during a 2 U bolus. The pump finishes regardless; what is untested is whether AAPS's *record* settles. A provisional record goes in before sending and `ReconnectManager` chases the link back, armed for the bolus only | §4g |
| F | **Kill AAPS mid-bolus** | Force-stop during delivery. The provisional record must survive and keep counting — this decides whether `PumpSyncStorage` is needed | §4g |
| G | **Change the phone's timezone** | Untested: the driver should queue a clock sync and the pump's clock should follow | §8.6 |
| H | **A bolus while a TBR runs** | Never tried together; both are identified by sequence | — |
| I | **A temp basal across an hour boundary** | The same percentage becomes a different absolute rate. Inherent, but see it once | Phase 3 |
| J | **Restore a profile you care about** | A write you cannot reverse is not finished | Phase 4 |
| K | **Chunked bolus for comfort** ← **START HERE NEXT SESSION** | ✅ Mechanism solved 2026-08-03, **procedure written (Phase 9), code not started.** "Bolus speed" is a loop in the phone, not a pump setting: Lotus sends ordinary `type=2` boluses on a timer — **1 U/5 s = 12 U/min, 0.5 U/5 s = 6, 0.4 U/8 s = 3** — confirmed in the pump's log (five entries, 8 s apart, summing to 2.00 U). Needs no new pump capability. **Phase 9 lists the design decisions to make before coding** | **Phase 9**, §8.6 |
| L | **The extended bolus** | ⚠ Works, 15-min minimum, lands in `boluses` as NORMAL and its log entry carries no duration (8a/8c passed/found 2026-08-03). Superseded as the comfort route by K, which hits 12 U/min where this floors at 0.67 | **Phase 8**, §8.6 |

**Closed on 2026-08-02, no longer on the list:**

| Was | Outcome |
|---|---|
| **Phase 7 — switch history sync on, with someone watching** | ✅ **7a–7d passed, sync is ON.** The first enable took a baseline of 160658 and imported nothing; a manual bolus arrived once at the right amount and time; an AAPS bolus stayed one record; a 24-entry burst reached no treatment. 7e was still running when this was written |
| **The log cap — what happens at 3000 entries** | ⏳ **Still open, and now dated.** A profile percentage change for exercise rewrites all 24 slots and reverting costs another 24 — ~48 entries per episode, 4–5× a week, so ~200–240 a week against a 3000-entry log. That is a wrap in roughly **three months**, not a theoretical concern. If sequences restart there, a monotonic watermark stops being sound |

**Closed on 2026-08-01, no longer on the list:**

| Was | Outcome |
|---|---|
| **The delivery-mode byte in every state** | ✅ **No third value exists.** `10` during a bolus, `3` while alarming / after an alarm / stopped by hand, and **no pause state on this pump at all**. Only cartridge-out and reservoir-empty remain (C and D above) |
| **Stop the pump from its own buttons** | ✅ Urgent notification, `PUMP_SUSPEND` record opened, closed on restart (6a/6c) |
| **A TBR cancel closing the suspend record** | ✅ Found, fixed and verified the same day (6f). It was firing **automatically on every pump stop**, not on an edge case |
| **Change the pump's bolus increment** | ✅ **It does not bind a commanded bolus.** With the menu step at 2 U, AAPS asked for 0.3 U and got exactly that. `bolusSize = 0.1` is safe |
| **Read the setting probe, indices 2–13** | ✅ Read. **Index 3 is the bolus increment** in centi-units, confirmed against two settings |
| **Does a cannula-priming event exist?** | ❌ **No `type=25`, anywhere.** AAPS's site-change timer (CAGE) can never restart itself on this pump. A documented limitation now, not a silent failure waiting for Phase 7 |
| ~~Decide the alarm poll interval~~ | **Withdrawn.** Command-queue-driven connect/disconnect is how AAPS drives every pump but Insight. See §8.7 |

**✅ Passed 2026-07-31 and no longer on the list:** the bolus cancelled *on the pump* (recorded 0.58 U
of 2.0 U requested, `success=false`, after previously recording the full amount — §4f); and the alarm
notification, raised by **occlusion** with an urgent AAPS alert and sound (§8.2).

**Removing the cartridge is not a valid alarm test.** It raises nothing, and correctly so: a cartridge
change through the pump's menu stops the pump first, so there is no unexpected condition. Use an
occlusion — clamp the line while it delivers.

**Retired: 4e, the 0.00 U/h slot.** It cannot be reached from AAPS. The profile editor refuses a
zero rate and `ProfileSealed` hard-limits basal to `[0.01, maxBasal]`, so the driver is never handed
one. The *read* direction is still live though: the pump uses `-1` for an unprogrammed slot and the
SDK maps it to `0.0`, which no valid AAPS profile can equal — so a pump holding an unprogrammed slot
makes `isThisProfileSet` false for ever and AAPS offers the write on every loop iteration. It heals
on the first write, and is worth recognising rather than debugging from scratch.

**Two controllers can reach the research pump.** The old study phone running Lotus writes the basal
profile unprompted the moment it wakes; it did so twice on 2026-07-31 and both times looked like an
unexplained pump-side change. Turn it off before any basal test, or a read-back diff cannot
distinguish a driver bug from the other app.

**⚠⚠ And a third one, found 2026-08-01: Nightscout.** The tablet's NSClient shares a Nightscout
instance with the *other* pump's live CamAPS loop, and it runs **both ways**:

- **Inbound**, it enacts real therapy decisions on the research pump. A 0% TBR for 120 minutes
  appeared mid-session with nobody having touched anything; it was CamAPS's zero temp, arriving
  through NS. Anything unexplained the pump does should be checked against NS before it is
  investigated as a driver fault.
- **Outbound**, and this is the one that matters: **an upload fired 10 ms after a 3 U saline bolus
  settled.** A saline bolus that reaches a real therapy record is insulin the record says was given
  and the patient never received, with nothing in it to say otherwise — and every bolus, TBR and
  profile write in Phases 5–7 will do the same.

**Before any phase below: turn NS uploading off on the test tablet, or point it at a separate
instance.** Read-only inbound is survivable if you remember it is there. Outbound is not.

---

**All four phases have passed on hardware.** Each was written up before its code, so that its
confirmation check was decided while nobody was under pressure to accept a weaker one — which turned
out to matter, because three of them initially "passed" against a check that could not fail.

---

## 0. Rules that apply to every phase

**Saline. Off-body. Cannula to air.** No insulin in the cartridge, the pump not attached to
anyone, and the infusion set visible so you can see whether fluid actually moves. This holds for
every phase below, including the ones that look like they only read.

**A successful GATT write proves nothing.** This pump does not NAK a bad write — a wrong auth
password produced a perfectly successful write and only failed on the next read (`PORTING_NOTES`
§3). So every step below names the *read-back or history entry* that confirms the pump actually
did the thing. If a step's confirmation check does not pass, the step failed, no matter what the
app said. Record it as a failure.

**One refusal at a time.** Each phase removes exactly one refusal and gets its own hardware test.
Never batch two. If a phase fails, do not start the next one.

**Use the research pump.** `YpsoPump_10074292`, MAC `EC:2A:F0:01:22:34`. **Never select
10178681** — that is a different pump running mylife CamAPS FX for actual therapy, and this
driver holds its BLE connection rather than releasing it between commands.

**Capture the log for every phase.** The driver's own BLE traffic is logged under `PUMPBTCOMM`
with a `[ypso-sdk]` prefix, and it contains the raw bytes. That log is the evidence; the
pass/fail in the app is a summary of it.

### Before you start, every session

1. Config Builder → Pump → YpsoPump → tick the visibility checkbox, or the YPSO tab is not there.
2. Open the YPSO tab, press **Refresh**, and check reservoir, battery, delivery mode, active
   program and base basal against the pump's own display. If the parity check fails, stop —
   something in the read path regressed and nothing below is trustworthy.
3. Note what the pump's display says the time is, and what the phone says. You will need both.

---

## Phase 1 — the write path, via the pump clock ✅ **PASSED 2026-07-30**

> **Result.** All four confirmation checks passed on pump 10074292, firmware V05.02.03.
> Both clock characteristics are **bare** — `CHAR_SYSTEM_DATE` 4 bytes, `CHAR_SYSTEM_TIME`
> 3 bytes, no framing header and no CRC. With the clock wound back 14 months, the driver wrote
> `EA 07 07 1E` / `07 09 20`, read back identical bytes, the pump's display showed the correct
> date and time, and the pump's history logged `12 Date Changed v1=30 v2=7 v3=26` and
> `13 Time Changed v1=7 v2=9 v3=32`. Final skew −1 s. Details in `PORTING_NOTES` §4e and §5.
>
> Keep this procedure: it is the cheapest regression test for the write path, and worth
> re-running after any change to the framing code.

**What is being proven:** that this driver can write something that **changes the pump's state**,
and that we know the wire envelope for such a write. Nothing here can move insulin. If the
envelope is wrong, the pump refuses the write at the ATT layer and the clock is untouched.

Worth knowing before you start: writing to this pump is not itself unproven. The auth password
write lands (or nothing would authenticate), and every setting and history read is a
write-then-read — `readSetting(14)` writes the index and gets 1.20 U/h back. Both of those go out
**bare**, which is why §4e expects the clock write to be bare or CRC-terminated but not framed.
What has never been done is a write that makes the pump *do* something.

**What was changed:** `YpsoBleManager.syncTime` no longer sends framed, CRC-terminated payloads
through `writeFramesSequentially`. It reads `CHAR_SYSTEM_DATE` and `CHAR_SYSTEM_TIME` first and
wraps its write to match whatever came back. `YpsoPumpManagerImpl.syncDateTime` no longer
refuses; it reports success only after reading the clock back.

### 1a. Read the clock — do this before writing anything

1. Press **Refresh** on the YPSO tab.
2. Look at the new **Pump clock** row.

| What you see | What it means | Do next |
|---|---|---|
| A skew, e.g. `In sync with phone (3 s)` or `2 h 14 min ahead of phone` | The clock characteristics read, and decoded to a plausible date | Go to 1b |
| `---` | The clock did not read, or decoded to nonsense | **Stop.** Go to 1d |

3. Find these lines in the log and **record them verbatim in `PORTING_NOTES`** — they are the
   only record of this firmware's clock wire format, and phases 2–4 depend on the same envelope
   rule:

```
readShortMessage(669a0c20-...-fcbedc3b7bc5): NB = ...      <- CHAR_SYSTEM_DATE
readShortMessage(669a0c20-...-fcbedd3b7bc5): NB = ...      <- CHAR_SYSTEM_TIME
Pump clock: YYYY-MM-DD HH:MM:SS (date wire ... bare|+CRC, time wire ... bare|+CRC)
```

4. **Check the decoded clock against the pump's own display.** If the app says the pump clock is
   2 h 14 min ahead and the pump's screen agrees it is 2 h 14 min ahead of the phone, the field
   layout is confirmed. If they disagree, the decode is wrong — stop and record it. Do not write.

### 1b. Set the pump clock deliberately wrong

On the pump itself (not in AAPS), set the clock **at least 30 minutes off** — an hour or more is
better. The driver treats anything within 90 seconds of the phone as "in sync", so a small offset
would not distinguish "the pump adopted our time" from "the pump ignored us and happened to be
close".

Press **Refresh**. The Pump clock row must now show roughly the offset you dialled in. If it does
not, the read is not tracking the pump — stop.

### 1c. Write the clock

1. Press **Set pump clock**.
2. Confirm the dialog.
3. Read the result dialog.

**The confirmation checks — all three must pass:**

| # | Check | Where |
|---|---|---|
| 1 | The result dialog says the clock was set **and confirmed by reading it back** | app |
| 2 | The **pump's own display** now shows the phone's time | pump |
| 3 | Press **Refresh**: the Pump clock row reads `In sync with phone (…)` | app |

Check 2 is the one that cannot be faked by a bug in the driver. Do not skip it.

Then confirm it a fourth way, independently of the clock characteristics entirely:

4. Press **Read history**. Look for a recent **`12 Date Changed`** and/or **`13 Time Changed`**
   entry. Type 12 carries the *new* date in `v1/v2/v3` as day/month/year — a clock set on
   2026-07-30 reads `v1=30 v2=7 v3=26`. Note that the entry's own `ts` is the time *before* the
   change, so it will look misplaced in the log; that is expected and is documented in
   `PORTING_NOTES` §5.

If checks 1–3 pass but no type 12/13 entry appears, that is still a pass — record that this
firmware does not log a BLE-initiated clock change. It matters for phases 2–4, which lean on
history as their confirmation channel.

### 1d. If it refuses, or fails

The driver refuses to write when it cannot read the clock first, because it would then have no
way to confirm what it did. This is the expected failure mode, not a bug.

| Log line | Meaning | What to record |
|---|---|---|
| `readShortMessage(…) failed (GATT status=N)` | The characteristic will not read at all | the UUID and the status number |
| `unrecognised shape — expected 4B bare or 6B ending in a valid CRC, got NB …` | It reads, but in a third envelope | **the full hex** — this is the finding |
| `implausible reading … — these characteristics do not carry the layout this code assumes` | It reads at the right length but the fields are not year/month/day | **the full hex**, plus what the pump display says the date and time are |
| `syncDateTime refused: the pump's clock did not read back` | Consequence of any of the above; nothing was written | — |
| `syncDateTime FAILED: the write was accepted but the pump clock still reads …` | The length was right, the content was not | the hex that went out, and what the pump display shows |
| `the clock did not read back after the write — the pump's clock is now UNKNOWN` | **Check the pump's clock manually and fix it on the pump** | everything |

If the characteristics turn out to be unreadable, the next move is a length probe: try the
candidate encodings in ascending length (4B bare, 6B with CRC, 7B framed-with-CRC) and take
`GATT_INVALID_ATTRIBUTE_LENGTH` (13) as "wrong length, try the next". Each candidate is a
complete, correct encoding differing only in envelope, so a rejected one never reaches the pump's
application layer. That is deliberately **not** implemented yet, because without a readable
characteristic there is also no read-back, and a write nobody can confirm is not one this driver
should be making. Deciding that trade-off needs the evidence from 1d first.

### Phase 1 is passed when

Checks 1, 2 and 3 in 1c all pass, and the wire format from 1a is recorded in `PORTING_NOTES`.

**Do not start phase 2 until this passes.** Phase 2's payloads use the same envelope, and the
whole point of the canary is that it fails cheaply.

---

## Phase 2 — standard bolus ✅ **PASSED 2026-07-30**

> **Result.** Delivery, confirmation and abort all verified on pump 10074292, on saline, off-body.
>
> - **Envelope:** `CHAR_BOLUS_START_STOP` is write-only (`0x08`) and takes **13 bytes + CRC,
>   unframed — 15 on the wire**. A 13-byte bare write was refused with status 13 first.
> - **Layout:** upstream's `total(4) + duration(4) + immediate(4) + type(1)` is correct.
> - **2.0 U:** confirmed from the pump's log, `seq=160362 type=2 v1=200 → 2.0 U`, `success=true`,
>   resolved in 5.4 s.
> - **Abort:** a 5.0 U bolus stopped from AAPS after ~3 s logged
>   `seq=160363 type=2 v1=182 → 1.82 U`, reported `success=false` with the partial amount.
>
> **Two traps found the hard way, both recorded in `PORTING_NOTES` §4f:** the pump *clears* its
> bolus status when delivery ends, so polling it reported successful boluses as failures until the
> confirmation was moved to the event log; and a bolus stopped from AAPS is logged as **type 2**
> carrying the partial, **not** type 29.
>
> **Bolus recording and pump alarms were added and verified afterwards** (`PORTING_NOTES` §4g). A
> 1.5 U bolus confirmed as `seq=160369 type=2 v1=150`, recorded under `pumpId=160369`, visible on
> the home screen with IOB reading 1.50 U.
>
> **⚠ That test is what exposed the epoch bug** (`PORTING_NOTES` §5): treatments were landing 12
> hours in the future because the pump counts *local* wall-clock seconds and the decoder read them
> as UTC. The bolus reported success while showing nowhere and producing no IOB. Two sessions of
> "verified" timestamps had compared wall-clock readings, which cannot detect it.
>
> **So when checking a timestamp, check something that consumes the instant** — IOB, or the marker's
> position on the graph — never how the time reads.

**What will be proven:** that a delivery command reaches the pump, that its progress can be
followed, and — before anything else — that it can be stopped.

**Before writing this code**, apply what phase 1 learned. Phase 1 showed short writes on this
firmware are **bare** — no framing header, no CRC — so the 13-byte bolus payload, currently sent
as `chunkPayload(appendCrc(payload))` (16 bytes with a header) on `CHAR_BOLUS_START_STOP`, will
almost certainly be refused with status 13 exactly as the GLB write was. The framing has to come
off.

**But read that characteristic before writing it**, the way `syncTime` reads the clock ones,
rather than hard-coding "bare" from phase 1. `CHAR_BOLUS_START_STOP` is a different attribute and
has never been observed; the read costs one round trip and turns an assumption into a fact.
A "sent" result means nothing until the pump's history says otherwise.

**Abort is not optional and is not deferred.** Wiring AAPS's bolus-stop to `cancelBolus()` is part
of this phase, not a follow-up to it. A bolus that has started and cannot be stopped is the
failure mode that matters here, and it must be exercised on saline before any larger amount is
tried.

### Setup

Saline cartridge, pump off-body, cannula to air, and **watch the set** — you should see fluid at
the end of the line. Reservoir before the test: note it. A 0.05 U bolus will not visibly move the
reservoir reading (it is reported to 0.01 U), so history is the confirmation, not the reservoir.

### 2a. Smallest possible bolus

1. Note the reservoir and the newest history sequence number (**Read history**, top entry).
2. Deliver **0.05 U** from AAPS.

**Confirmation — history, not the write result:**

3. **Read history.** There must be a new entry, newer than the sequence number you noted:
   - type **2** `Bolus Immediate`
   - `v1 = 5` — centi-units, so 0.05 U

That decode is already verified: a 1.5 U bolus logged `v1=150` (`PORTING_NOTES` §5). If `v1` is
anything other than 5, **the pump delivered a different amount than AAPS asked for** — stop
immediately, record it, and do not increase the dose.

4. Check the amount AAPS recorded as a treatment matches 0.05 U.

### 2b. A visible bolus

Repeat 2a with **1.0 U**. Confirm `v1 = 100` in history, and confirm you saw fluid move at the
cannula. Check the reservoir reading fell by about 1.0 U.

### 2c. Abort mid-delivery — the important one

1. Start a **5 U** bolus. At 12 U/min this takes about 25 seconds, which is enough time to react.
2. After roughly 2 seconds, press stop in AAPS.

**Confirmation:**

3. The pump must stop delivering — visible at the cannula, and the pump's own display.
4. **Read history.** The entry must be type **29** `Bolus Immediate Abort`, and `v1` must be the
   *partial* amount, materially less than 500. An entry showing the full 500 means the abort did
   not take, and phase 2 has failed.
5. AAPS's treatment record must show the partial amount, not 5 U. This is the one that corrupts
   IOB if it is wrong.

### 2d. Progress reporting

During 2b, the AAPS bolus progress dialog should advance and reach completion without stalling.
The completion timeout must be scaled to the configured bolus speed (`YpsoBolusSpeed`, 12 U/min).
A progress bar that finishes early is worse than one that finishes late — it means AAPS believes
delivery is done while the pump is still running.

### Phase 2 is passed when

2a, 2b and 2c all confirm from history, and 2c's partial amount is right in both the pump's log
and the AAPS treatment record.

---

## The alarm notification ✅ **PASSED 2026-07-31**

> **Result.** An occlusion forced on saline was logged as `seq=160532 type=105`, the count moved
> 144 → 145, and the whole chain ran on the next status poll — urgent AAPS notification **with
> sound**. The `replay = 0` `SharedFlow` worry recorded here was unfounded; the service's collector
> was subscribed in time. Details in `PORTING_NOTES` §8.2.
>
> ⚠ **It took 27 minutes**, because AAPS had no reason to talk to the pump in between. That is the
> real limit and it is not a defect — see §8.7.

**Two things this test settled about how to run it again:**

- **Pulling the cartridge raises nothing**, and correctly so: a cartridge change through the pump's
  menu stops the pump first, so there is no unexpected condition to report. An earlier version of
  this section said to use it, and it cannot work.
- **Use an occlusion** — clamp or kink the line while the pump delivers. `105 Occlusion` is in the
  urgent set and this pump raises it reliably.

Phase 5b reuses the same occlusion to answer a different question: what the *delivery-mode byte*
reads once the alarm has stopped the pump.

---

## Phase 3 — temporary basal ✅ **PASSED 2026-07-30**

> **Result.** Set and cancel both verified on pump 10074292, saline, off-body.
>
> - **Envelope: 16 bytes bare** — a *third* distinct convention (see the envelope table in
>   `PORTING_NOTES` §4). `18B payload+CRC` and `19B frame(payload+CRC)` were both refused with
>   status 13 first; the discovery cost 300 ms and two harmless rejections.
> - **150% for 30 min:** `Status: Rate: 1.8U/h, TBR 150% 30min left`, confirmed from byte 10,
>   logged `seq=160371 v1=150 v2=30`, recorded under `pumpId=160371`.
> - **1.20 U/h × 150% = 1.80 U/h exactly** — the pump *running* the rate, not just echoing the
>   percentage. This is the check that matters; an echoed percentage alone would not distinguish
>   "command accepted" from "delivery changed".
> - **Cancel:** byte 10 back to 100. Cancelling with nothing running reports success and "no end
>   entry to record", which is correct on both counts.
>
> **⚠ The cancel initially left AAPS believing the TBR was still running** — the ACT tab kept
> offering "CANCEL 150%" while byte 10 read 100, because this pump *rewrites* the TBR entry in place
> instead of appending an end record (`PORTING_NOTES` §4h). Fixed and re-verified: two full
> set→cancel cycles recorded start and stop under the same sequence (160371, 160372), and the ACT tab
> now reads "TEMPBASAL".
>
> **The UI was the check that caught it.** Byte 10, the history entry and the driver's own log all
> said the cancel had worked. Only "CANCEL 150%" still being offered showed that AAPS disagreed —
> so when a write changes AAPS state, look at AAPS, not just at the pump.
>
> **Still open:** ask for **250%** to settle whether the pump's ceiling is 200% or 500% — the driver
> no longer refuses it, so byte 10 will answer. And see a temporary basal cross an **hour boundary**,
> where the same percentage becomes a different absolute rate.

---

## Phase 3 — the original procedure

**The read side is already confirmed**, which was not true when this phase was planned. Setting a
130% TBR on the pump moved status bytes 6–9, 10–13 and 14–17 together: the effective rate read
back as exactly 1.56 U/h against a programmed 1.20 U/h, byte 10 read 130, and the remaining
minutes counted down across successive reads. The pump's history agreed, logging type 9 with
`v1=130`. So `activeTbr` is populated for real and no separate confirmation step is needed —
see `PORTING_NOTES` §4.

That means this phase is only about the *write*.

### 3a. Set a TBR from AAPS

1. Note the current base basal rate from the YPSO tab.
2. Set **150% for 30 minutes** from AAPS.

**Confirmation — all four:**

3. The pump's own display shows a temporary basal running at 150%.
4. Press **Refresh**: the Temp basal row reads `150% (… min left)`.
5. The base rate row shows the *effective* rate — 1.5 × the programmed rate for this hour.
6. **Read history**: a type **9** `Basal Profile Temp Running` entry with `v1 = 150` and
   `v2 = 30`.

### 3b. Absolute-rate conversion

AAPS asks for temp basals in U/h; the pump takes a percentage. Set an absolute rate and check the
conversion landed where you expect, then repeat **at a profile block boundary** — set a rate a
few minutes before the hour changes and check what the pump is running after the boundary passes.
A percentage is relative to whichever block is now active, so a conversion computed against the
old block is silently wrong from the boundary onward.

Clamp behaviour: the pump's range is 0–200%. Ask for something outside it and confirm the driver
clamps rather than sending an out-of-range value.

### 3c. Cancel

1. With the TBR from 3a running, cancel it from AAPS.

**Confirmation:**

2. The pump's display no longer shows a temporary basal.
3. **Refresh**: Temp basal reads `---`, and byte 10 is back to 100 (the driver reports no TBR).
4. **Read history**: a type **32** `Basal Profile Temp Abort` entry.
5. AAPS's own temp-basal record is closed at the right time.

### Phase 3 is passed when

3a, 3b and 3c confirm on the pump display, the read-back and history, and the boundary case in 3b
produces the rate you predicted before running it.

---

## Phase 4 — basal profile write ✅ **PASSED 2026-07-31**

> **Result.** Verified on pump 10074292, saline, off-body. Five writes, every one confirmed slot by
> slot.
>
> - **A 24-slot profile crossing both step boundaries** — `0.10…0.55` on the 0.01 grid, `1.00…1.18`
>   on 0.02, `2.00 2.10 2.00 2.50` on 0.1 — read back **exactly**, all 24. Hour 0 and hour 23 checked
>   against the pump's own display.
> - **`isThisProfileSet` agrees afterwards.** It logs only on mismatch and went quiet after every
>   write, so AAPS stopped offering to rewrite. That is 4d.
> - **Rounding (4f) passed with a real value:** 1.23 U/h entered in AAPS, stored as **1.24** — the
>   0.02 step above 1 U/h — and `isThisProfileSet` still agreed. Write and comparison round through
>   the same `hourlyRatesFor`, which is what makes that possible.
> - **Single-slot edits change one slot.** Two writes that differed from the pump in exactly one hour
>   confirmed with the other 23 untouched, so the write is slot-precise.
> - **History: type 7 is `v1 = slot, v2 = new value, v3 = previous value`**, centi-U/h — and the pump
>   logs **only slots whose value actually changed**. A 24-slot write where 22 slots already held the
>   target produced two entries, not 24. So "expect a basal-profile-changed entry" is wrong as a
>   confirmation rule: a write that changes nothing logs nothing.
>
> **⚠ The first two of those five writes fired with nobody asking.** AAPS pushes the profile itself
> whenever `isThisProfileSet` is false, so un-refusing `setNewBasalProfile` meant the destructive
> write ran 15 seconds after the app started — before the 24 rates had been written down, and before
> a human had gated the phase. The code behaved correctly; the *procedure* assumed a human presses a
> button, and for this phase alone that assumption does not hold. Anyone re-running phase 4 from
> scratch should expect the write to have happened already by the time they open the tab.

**This is the destructive one.** Read this section before running any of it.

**Write down the pump's 24 rates by hand first.** The driver logs the program it is about to replace
(`BASAL PROGRAM x BEFORE OVERWRITE …`), and that log line is the only copy — the pump has no undo. Do
not rely solely on the log surviving.

**What the code does:**

1. Reads which program is active, and **refuses** if it cannot tell — there would be no safe target.
2. Reads and logs the existing 24 rates, and **refuses** if it cannot, so nothing is overwritten
   without a record of what was there.
3. Writes the 24 slots one at a time, **stopping at the first failure** and then reading back to show
   how far it got. A half-written program is the worst outcome available.
4. Reads all 24 back and diffs them. Success requires every slot to match; otherwise it names the
   slots that differ.

**Rates are rounded to the pump's step before writing**, because the step varies with the rate:
0.01 U/h below 1, 0.02 to 2, 0.1 to 15, 0.5 above. `isThisProfileSet` compares against the same
rounded values through the same function — if the two rounded differently, a freshly written profile
would read as "not set" and AAPS would offer to rewrite it every loop iteration.

### Tests, in order

| # | Step | Confirms |
|---|---|---|
| 4a | Note the pump's 24 rates **on the pump itself** | the only reliable backup |
| 4b | Build an AAPS profile differing in **every** slot, and push it | a flat profile would hide a partial write |
| 4c | Refresh, then check several slots on the pump's own display, including hour 0 and hour 23 | the read-back diff agrees with the pump's screen |
| 4d | Check AAPS stops offering to set the profile | `isThisProfileSet` agrees, i.e. the rounding matches |
| 4e | Include a slot at **0.00 U/h** | ⚠ unknown — the pump uses `-1` for an unprogrammed slot and the driver writes `0`. This may be rejected or read back as something else |
| 4f | Include a rate needing rounding, e.g. **1.23 U/h** | it should be stored as 1.22 or 1.24, and `isThisProfileSet` should still agree afterwards |
| 4g | Restore the original 24 rates and confirm the same way | a write you cannot reverse is not finished |
| 4h | Read history | expect a type **6/7/8** basal-profile-changed entry |

**4e is the one I would expect to fail**, and it is a guess either way — see §4d in the notes. If the
pump will not store 0.00, the driver needs to write `-1` instead, which is a one-line change once
hardware says so.

---

## Phase 4 — the original procedure

The most destructive of the four: it overwrites the pump's stored program. **Write down all 24
rates from the pump before starting**, so the pump can be put back by hand if the write goes
wrong.

Its wire format is the one that needs least work: `writeBasalProfile` goes through
`writeSetting` → `writeGlb`, which already sends the bare 8 bytes and is already exercised on
hardware by every profile *read*. What is untested is writing a setting *value* rather than a
setting *index* — the same envelope on a different characteristic, with an effect that sticks.

### 4a. Write a profile

1. Record the pump's current 24 rates (the YPSO tab's Refresh reads them; the log lists all 24).
2. Build an AAPS profile that differs from the pump's in **every** slot — a flat profile is a poor
   test, because a partial write can look like a success.
3. Push it to the pump from AAPS.

**Confirmation:**

4. **Refresh**, and diff all 24 slots read back from the pump against the profile you sent.
   `isThisProfileSet` compares in `Double` (`PORTING_NOTES` §4d), so an exact match is expected —
   not an approximate one.
5. Check several slots against the pump's own display, including slot 0 and slot 23.
6. **Read history**: a type **6/7/8** basal-profile-changed entry.
7. Check a slot that should be **0.00 U/h**. The SDK maps an unprogrammed slot (`-1`) to 0, and
   the round trip through that mapping is where a zero rate can come back as something else.

### 4b. Put it back

Restore the original 24 rates and confirm the same way. A profile write you cannot reverse is not
finished.

### Phase 4 is passed when

All 24 slots read back exactly, including a zero slot, and the original profile restores cleanly.

---

## After all four phases

Still not insulin. What remains is Phases 5–7 below: mapping the pump states nobody has looked at,
watching the suspend record behave, and turning `YpsoSyncHistoryToAaps` on — which is its own gate,
because getting it wrong corrupts IOB silently and persistently rather than visibly.

---

# Phase 5 — mapping the delivery-mode byte ⏳

**Nothing is written and no new refusal is removed.** This phase only puts the pump into states
nobody has looked at and writes down what it says. It is the last thing standing between here and
an insulin trial, and it is cheap: the whole cost is putting the pump in a state and reading a
number.

## Why this one matters more than it sounds

Byte 0 of the system status is identified for **two values out of 256** — `3` = Stopped and
`10` = Running — both checked against the pump's own display (`PORTING_NOTES` §8.3). Everything
else is `UNKNOWN`, and `UNKNOWN` is not neutral. It has three consequences at once:

| | What an unmapped byte 0 does today |
|---|---|
| The loop | `isSuspended()` is true, so AAPS **will not act**. Conservative and correct |
| The 0% suspend record | **Not written.** `UNKNOWN` deliberately writes nothing (§8.6) |
| Basal IOB | Therefore **keeps accruing**, whether or not the pump is delivering |

Read those two rows together, because they point in opposite directions and both are deliberate.
If the pump enters an unmapped state *while stopped* — which is exactly what an alarm does — AAPS
holds the loop off **and** goes on counting basal insulin that is not being delivered. Neither
half is wrong on its own; the combination is only resolved by identifying the byte.

So `5b` below is the important one, and it is worth doing first if you only do one.

## Reading the instrument

Every status read now logs one line. There are two forms and they share the string `ypso-state`:

```
[ypso-state] byte0=10 name='Running' mode=STARTED delivering=true suspendRecord=none rate=1.2U/h tbr=none reservoir=51.3 raw=[0A 2B 16 00 00 02 78 00 00 00 64 00 00 00 00 00 00 00]
[ypso-sdk] [ypso-state] probe=during-bolus byte0=? name='?' rate=?U/h tbr=100% raw=[...]
```

The first is one per status read, under logcat tag **`PUMP`**. The second fires **once per bolus**,
on the first poll that confirms the pump is delivering, under tag `PUMPBTCOMM`. Grep for
`ypso-state` and you get both.

```
adb connect 192.168.1.39:5555          # the tablet's USB drops; get on Wi-Fi first
adb logcat -v time | grep --line-buffered ypso-state | tee phase5.log
```

Start the capture **before** you touch the pump, and leave it running for the whole phase. The
same lines are in `AndroidAPS.log` on the tablet if the capture dies, but a live terminal is the
point: several of these states are ones where you are holding the pump and cannot look at a phone.

**Byte 0 is also the first byte of `raw=[…]`.** They should always agree; if they ever do not,
stop, because the decode has drifted from the bytes and nothing else in this phase means anything.

**If no line appears at all:** check the `PUMP` log tag is enabled in AAPS (Preferences →
Maintenance → log settings). It is on by default.

**Forcing a read.** AAPS polls about every 20 minutes when idle (§8.2). Press **Refresh** on the
YPSO tab to force one. The one state where that does not work is during a bolus — the command
queue is blocked behind the delivery call for its whole duration, which is why the driver takes
that reading itself.

## What to record, for every state

Six things, every time. Five of them are the pump's and one is AAPS's, and the point is to compare
them without trusting the decode in between:

1. **`byte0=`** — the raw number.
2. **`raw=[…]`** — the whole payload. Copy it in full. The unread fields are the ones that matter
   in a state nobody has seen: they say what else moved when byte 0 did.
3. **What the pump's own display says**, in the pump's words. This is the authority. A byte value
   is only ever mapped against a display, never against upstream's table.
4. **`suspendRecord=`** — whether AAPS opened a 0% basal in response.
5. **Whether the pump's alert log grew** — the count is in the log around the same lines.
6. **The time**, so the line can be found again in the full log.

**Nothing gets mapped on inference.** Upstream's table lists `PAUSED = 7` and `PRIMING = 6`; it
also lists `3` as `BOLUS_FAST`, and on this firmware `3` is a stopped pump. That table has been
wrong on the one value it could be checked against, so it is not evidence for the others.

---

### 5a. During a bolus

**The question:** does byte 0 change while the pump delivers a bolus? If it does, and the new
value is unmapped, then AAPS reports the pump suspended *during every bolus* — and §8.6 warns that
if the driver treated unknown as stopped it would open a suspend record on every bolus too.

**How:**

1. Cartridge on saline, off-body, cannula to air, as always.
2. Pump **running** (not stopped) — confirm `byte0=10` on a Refresh first.
3. Give a **3 U** bolus from AAPS. Large enough to still be delivering when the driver takes its
   reading, which happens on the first 500 ms poll that sees `DELIVERING`.
4. Watch the pump's display while it runs and note what it says.

**Expect** exactly one `probe=during-bolus` line per bolus.

**What it would mean:**

| Reading | Meaning | What follows |
|---|---|---|
| `byte0=10` | A bolus is not a distinct delivery mode on this firmware | Nothing to change. §8.6's worst case is closed, and this is the good outcome |
| A third value | The pump distinguishes bolus delivery | It can be added to the *delivering* set — but only on this observation, and only with the display's own word for it recorded next to it |
| No probe line at all | The bolus finished before the first poll saw `DELIVERING` | Repeat with 5 U. If it still does not appear, say so — the probe is on the wrong path |

⚠ **The reading is logged and deliberately not acted on.** It does not feed the status, so it
cannot open a suspend record, and it cannot change what AAPS believes mid-bolus. That is the whole
reason it is a separate call. Do not "helpfully" widen the delivering set from this reading alone
without the pump's display agreeing.

**Also record:** whether `suspendRecord=` is still `none` on the first status read *after* the
bolus. That is the check for the failure §8.6 predicted.

---

### 5b. After an alarm has stopped the pump — **the important one**

**The question:** when an alarm stops delivery, does byte 0 say so? If it reads `3`, the driver
already knows the pump is stopped through a channel far better than the alert log — immediate,
not dependent on a count, and it opens the suspend record automatically. If it is a third value,
then **an alarm-stopped pump goes on contributing basal IOB in AAPS indefinitely**, and only the
alert notification tells anyone.

**How:** use an **occlusion**, which this pump raises reliably (§8.2) and which genuinely stops it.
Removing the cartridge does *not* raise an alarm — the pump treats a menu-driven cartridge change
as a deliberate procedure and there is nothing unexpected to report.

1. Pump running on saline, delivering.
2. Start a bolus large enough to give you time — **5 U** — and **clamp or kink the line** while it
   runs.
3. Wait for the pump to alarm and stop. Note the alarm's exact wording on the pump.
4. **Do not silence it yet.** Press **Refresh** on the YPSO tab and record the line.
5. Silence/acknowledge the alarm on the pump, but leave the pump stopped. Refresh, record again.
6. Restart the pump on its own buttons. Refresh, record again.

Three readings, because the pump may distinguish *alarming*, *stopped after an alarm*, and
*running* — and if it does, step 4's value is the one worth having.

**Also record:**

- The **reservoir** field. §4 notes the pump reported raw `-1` after an occlusion alarm, which the
  driver now shows as `reservoir=unknown` rather than as an almost-empty cartridge. Confirm that is
  what the line says, not `reservoir=-0.01`.
- Whether the alert notification fired, and how long after the alarm. The measured worst case is
  one idle poll interval — 27 minutes has been seen (§8.2).
- Whether `suspendRecord=` opened.

**What it would mean:**

| Reading at step 4 or 5 | What follows |
|---|---|
| `byte0=3` | The best outcome. The suspend record opens by itself, basal IOB stops accruing, and §8.2's alert-log channel is a backup rather than the only signal |
| A third value | Map it as **not delivering**, against the display. Until that is done, an alarm-stopped pump silently over-counts basal IOB — record this prominently, because it changes what §8.6 is allowed to claim |

---

### 5c. During priming and a cartridge change

**The question:** the pump moves fluid while priming, but none of it is therapy. Does byte 0
distinguish that from delivering basal?

**How:** run a normal cartridge change through the pump's own menu, on saline, and Refresh at each
step you can reach:

1. Pump stopped for the change (the pump does this itself). Record.
2. **Cartridge removed.** Record — especially the reservoir field, which should read
   `reservoir=unknown` rather than a number.
3. **During priming**, while fluid is visibly moving. Record.
4. After the change completes and the pump is running again. Record.

**What it would mean:** a value seen only while priming belongs in the **not delivering** set —
priming is not therapy, and AAPS should not count it as basal. Note that mapping it that way means
a suspend record will open during every cartridge change, which is correct: basal is genuinely not
being given. Record whether that is what you see once the mapping exists.

#### 5c-ii. …and while you are in there, find out whether type 25 exists

**A second question the same cartridge change answers**, and it decides whether AAPS's **site-change
timer (CAGE) will ever restart by itself.**

Three of the four consumable timers are driven from pump events, and only one of them has been seen
on this pump:

| Pump event | → therapy event | → AAPS timer | Ever seen in a dump? |
|---|---|---|---|
| `16 Rewind Finished` | Insulin Change | **IAGE** | ✅ yes, at a real cartridge change |
| `25 Cannula Priming Finished` | Site Change | **CAGE** | ❌ **never** |
| `24 Battery Removed` | Battery Change | **BAGE** | ⚠ three exist, all from 2026-03-29, matching nothing anyone did |
| `4 Priming Finished`, over 9 U | Tube Change | *(none — no AAPS timer consumes it)* | ✅ yes, four of them, 0.03–2.00 U |

⚠ **Type 25 has never appeared.** The mapping to a site change comes from mylife Lotus's convention,
not from anything this pump has been observed to log. The one cartridge change captured in full
produced a rewind, four primes and a mode change — **and no type 25.** Either this firmware does not
log cannula priming separately, or that session never primed a cannula.

That matters because the failure is **silent**: with history sync on, CAGE would simply never
restart, and nothing would say why. A timer that quietly does not restart is worse than one that
obviously does not exist.

**How:** do the cartridge change properly, **including priming the cannula** — not just filling the
tubing. Then press **Read history** and read the dump.

**Record, from the dump:**

1. Every entry the change produced, in order, with `seq=`, `type=` and `v1=`.
2. **Whether a `type=25 (Cannula Priming Finished)` appears at all.** This is the answer.
3. The `v1` on each `type=4 (Priming Finished)` — it is the primed amount in centi-units, and only a
   prime above **9 U** (`v1 > 900`) is treated as a tube change.
4. Whether a `type=16 (Rewind Finished)` appears, which is the one already confirmed.

**What it would mean:**

| Dump shows | What follows |
|---|---|
| A `type=25` | The mapping is real. CAGE will restart once Phase 7 turns history sync on |
| No `type=25`, but a large `type=4` right where the cannula prime happened | The prime is the only signal this pump gives, and the site-change mapping needs rethinking against it |
| No `type=25` and nothing else new | ⚠ **CAGE cannot restart automatically on this pump.** Record it plainly — it is a documented limitation, not a bug to hunt |

**This is a read.** Nothing is written, and it can be done on the same cartridge change as 5c above.

---

### 5d. Paused ✅ **RETIRED 2026-08-01 — there is no pause on this pump**

> **Result.** The pump's menus offer **stop only**. There is no pause or hold function, so upstream's
> `PAUSED = 7` is not reachable on V05.02.03 and byte 0 will never carry it.
>
> That closes the row rather than leaving it open, which is the point of asking. It also removes one
> of the four states §8.3 listed as unmapped: it is not unmapped, it does not exist.

Upstream lists `PAUSED = 7`. Its table has already been wrong about the one value that could be
checked — it calls `3` a fast bolus, and on this firmware `3` is a stopped pump — so an entry in it
is not evidence that a state exists.

---

### 5e. Reservoir empty mid-delivery

**The question:** what byte 0 reads when the cartridge runs dry while delivering, and whether it
differs from an occlusion stop.

**How:** the practical version is to start the phase with a nearly empty saline cartridge rather
than to drain a full one. Put in a small amount, run a bolus larger than what is left, and let it
run out.

**Record** byte 0 at the moment it stops, the reservoir field, the pump's own message, whether the
alert log grew, and whether `suspendRecord=` opened. Note that a **low** reservoir is not a pump
alert at all (§4g) — the mylife controller computes that on the phone — so an alert here, if one
appears, is specifically the empty condition.

---

### Phase 5 results

Fill this in from the log. **One row per observation, and only from a reading with the pump's own
display recorded beside it.** Leave a row blank rather than guessing it.

| State | `byte0` | Pump display said | `suspendRecord` | Alert log grew? | Raw payload |
|---|---|---|---|---|---|
| Running, idle | 10 | "Running" | none | no | `0A …` (already mapped) |
| Stopped from the buttons | 3 | "Stopped" | | | `03 …` (already mapped) |
| **During a bolus (5a)** ✅ | **10** | "Bolus complete" (after) — reservoir 169.82 mid-delivery proves the read was taken while delivering | none | no | `0A 56 42 00 00 02 05 00 00 00 64 00 00 00 00 00 00 00` |
| **Alarming, not yet acknowledged (5b)** ✅ | **3** | "Occlusion - check infusion set" | **open** | yes, 146 → 147, `105 Occlusion` | `03 0F 40 00 00 02 00 00 00 00 64 00 00 00 00 00 00 00` |
| **Stopped after an alarm (5b)** ✅ | **3** | same alarm acknowledged, pump stopped | open | — | `03 0F 40 00 00 02 00 00 00 00 64 00 00 00 00 00 00 00` — **byte-identical to the line above** |
| **Cartridge removed (5c)** ✅ | **3** | pump stopped for the change, cartridge out | **open** since 06:28 | — | `03 FF FF FF FF 02 00 00 00 00 64 00 00 00 00 00 00 00` — **reservoir reads `FF FF FF FF` = −1**, and `reservoir=unknown` |
| **Priming (5c)** ⚠ **unreachable by this method** | (`3` on both sides and throughout the procedure, but see below) | pump in the cartridge-change menu | open | — | **Liveness cannot be proved.** §8.3's bolus reading was trustworthy because its own payload showed the reservoir 0.12 U down mid-delivery. During a change the reservoir reads `-1`, so a mid-prime reading is byte-identical to one taken after it. Left unverified rather than filled in from inference |
| **Does `type=25` exist? (5c-ii)** ✅ **CLOSED** | **No — confirmed twice, the second time with a deliberate cannula prime.** See below | | | | |

#### ✅ [verified] 5c-ii closed 2026-08-03 — and the answer is worse than "type 25 is missing"

The 2026-08-01 change produced `seq=160635 type=16` and `seq=160637 type=4 v1=1000` and no type 25 —
but that change **may never have primed a cannula**, so it could not settle the question. §5c-ii said
so, and asked for a change that primed the cannula deliberately.

Done 2026-08-03. A full change with a **30 U cannula prime**, adjudicated against the database rather
than the log:

```sql
select datetime(timestamp/1000,'unixepoch','localtime'), type, pumpId from therapyEvents …
2026-08-03 06:28:06  INSULIN_CHANGE   160742    rewind
2026-08-03 06:31:24  TUBE_CHANGE      160745    14.35 U prime (tubing)
2026-08-03 06:35:13  TUBE_CHANGE      160751    30 U prime — THE CANNULA PRIME
2026-08-03 06:37:43  INSULIN_CHANGE   160756    second rewind
```

**No `CANNULA_CHANGE`, no `type=25`** — and the cannula prime arrived as **`type=4`, the same event
as the tubing prime**, over the same 9 U threshold, mapped the same way. So the pump does not merely
omit a cannula event: **it emits no signal that distinguishes a cannula prime from a tubing prime.**

That closes the middle row of 5c-ii's own decision table. There is nothing to remap the site change
onto, because the only candidate is indistinguishable from an event that is not a site change.
**CAGE cannot restart automatically on this pump, and no amount of driver work will change that.**
Site changes must be recorded by hand, as an MDI user does.

⚠ **The log could not have answered this.** `insertTherapyEvent` does not log its success path, so
both entries read only as `importing Unknown seq=…`. Every branch that *would* have logged —
"Prime of X U … below tube-change threshold", "Unmapped pump event type N", "its preference is off" —
stayed silent, which narrowed it to three possibilities and settled none. The database was the only
thing that could tell `CANNULA_CHANGE` from `TUBE_CHANGE`, which is the fifth time in these notes
that a question was decided by the database after the log had been read as if it were evidence.

⚠ **Press Refresh *during* each step of 5c.** The 2026-08-01 attempt lost both readings: AAPS
disconnected on "Queue empty" at 17:13:32 and did not reconnect until 17:17:44, so the cartridge-out
and priming states came and went with nothing connected. The driver only reads when AAPS connects,
and a cartridge change is comfortably longer than the gap between commands.
| ~~Paused (5d)~~ ✅ | — | **No pause function exists.** The pump offers stop only, so `PAUSED = 7` is unreachable on V05.02.03 | — | — | — |
| **Reservoir empty (5e)** ✅ | **3** | pump stopped, requesting a cartridge change | **open** | **yes, 147 → 148, `104 Cartridge Empty`, urgent** | `03 00 00 00 00 02 00 00 00 00 64 00 00 00 00 00 00 00` — reservoir `00 00 00 00` = a **known zero**, not the `FF FF FF FF` (−1) of a cartridge change |

### Phase 5 is finished when

Every row above is either filled in or struck out as unreachable on this firmware, and each filled
row names what the **pump's display** said. `PORTING_NOTES` §8.3 is then updated from this table
and from nothing else.

5c-ii is the exception to the display rule: its answer comes from the history dump, and "no type 25
exists" is a complete answer rather than a blank row.

---

# Phase 6 — the suspend record ⏳

**Depends on Phase 5a.** Run 5a first: 6d cannot be interpreted without knowing what byte 0 does
during a bolus.

## What is being proven

A stopped pump delivers no basal, and AAPS's IOB accounting does not consult `isSuspended()` —
basal insulin on board is computed from the profile and whatever temporary basals are on record
(`PORTING_NOTES` §8.6). So every driver that can detect a stopped pump holds a **0% temporary
basal typed `PUMP_SUSPEND`** open for as long as the pump is stopped. This one now does too, and
none of it has been watched on hardware.

The record expires after **60 minutes** on purpose, so that one orphaned by a crash does not tell
AAPS basal was off for days. That makes re-assertion load-bearing: if nothing re-opens it, a pump
stopped for longer than an hour goes back to accruing phantom basal IOB.

### 6a. Stop the pump on its own buttons

1. Pump running on saline. Confirm `byte0=10` and `suspendRecord=none`.
2. **Stop the pump using its own buttons.**
3. Press **Refresh** on the YPSO tab.

**Confirm all four:**

- The log line reads `mode=STOPPED` and `suspendRecord=open since <time>`.
- `Pump is not delivering (mode 'Stopped'); recorded a 0% basal …` appears.
- An **urgent AAPS notification** says the pump is not delivering.
- AAPS's own records show it: Treatments → Temp Basals has a **0% / 0.00 U/h** entry starting now.
  Look at AAPS, not only at the pump — §4h's TBR cancel passed every pump-side check and AAPS still
  disagreed, and the UI is what caught it.

### 6b. It is re-asserted across polls ✅✅ **PASSED 2026-08-03, twice — on the fix, then on HEAD**

> **The renewal fix works.** 2h33m of continuous stopped-pump coverage, four renewal cycles, **zero
> uncovered time**. Unattended, and not even deliberate — the cartridge ran dry during Phase 5e at
> 07:08 and the pump simply stayed stopped.
>
> ```
> started              mins     expires
> 2026-08-03 07:08:42  37.62 →  07:46:19   ┐
> 2026-08-03 07:46:19  40.07 →  08:26:23   │  each record starts EXACTLY
> 2026-08-03 08:26:23  34.98 →  09:01:22   │  when its predecessor expires
> 2026-08-03 09:01:22  40.00 →  09:41:22   ┘
> 2026-08-03 09:41:22  60.00 →  10:41:22   (still running, not yet truncated)
> ```
>
> `started[n+1] − (started[n] + duration[n])` = **0 at all four transitions.**
>
> **The durations prove the fix is present, not just that the run went well, and the argument is not
> circular.** A `PUMP_SUSPEND` record is *always created* with `duration = 60 min`; a stored duration
> of 37.62 can only mean AAPS **truncated** it when a successor was synced over it 37.62 minutes in.
> Pre-fix the code returned early whenever a record was open, so a successor could only arrive after
> expiry and every stored duration was exactly `3600000`. **~40-minute durations are producible only
> by renewal-before-expiry.** One observation entails both facts.
>
> The denominator guard is met: this procedure requires **≥ 2** renewals or the run never reached what
> it was testing. There are **four**.
>
> Two earlier records the same morning — 06:28:40 (3.62 min) and 06:40:27 (4.35 min) — both ended
> early because the pump restarted after a cartridge change. That is the **close** path, working, for
> a fourth time (6c).

> ⚠⚠ **It passed on the wrong build, and that is worth as much as the pass.** The tested build is
> **3.4.2.4 of 2026-08-02 18:23**. HEAD is **3.4.2.6**, carrying the `SuspendRecordDecision`
> extraction written 2026-08-03 — the same logic, refactored so a test could reach it, pinned by 15
> unit tests, and **never run on hardware**.
>
> That is exactly the shape of the failure this re-run existed to correct. Last time the fix was
> committed and the test ran on a build from before it; this time the test ran on a build from before
> the refactor. "Mechanical and unit-tested" is not this file's standard for hardware behaviour —
> §8.6 records four variants of one bug, every one introduced by a change that looked safe.
>
> | | |
> |---|---|
> | The renewal fix | ✅ verified on hardware |
> | Today's refactor of it | ⏳ not on hardware |
>
> **Re-confirm on HEAD before an insulin trial.** It is cheap: leave a stopped pump for two hours on
> the current build and re-run the query below. That run would also exercise the
> `[ypso-suspend] LAPSED` detector and the minutes-remaining field, **neither of which has ever run
> on hardware** — they were added the same day and this run predates them.

#### ✅✅ [verified] Re-confirmed on HEAD (3.4.2.6) — 2026-08-03, and it survived a reinstall

The caveat above is now closed. HEAD was flashed at **10:12:56** onto the still-stopped pump and left
to poll unattended. The record chain **across the reinstall**:

```
started    mins    expires
09:01:22   40.00 → 09:41:22    ┐  old build
09:41:22   32.14 → 10:13:30    │  ← truncated 34 s after the APK was replaced
10:13:30   37.90 → 10:51:24    │  HEAD
10:51:24   39.98 → 11:31:23    │
11:31:23   60.00 → 12:31:23    ┘  (running)
```

**Zero gap at every transition, including across the reinstall.** The 09:41:22 record had 28 minutes
left when HEAD's first poll found it — under the 30-minute threshold — so it was renewed immediately
rather than left to lapse. The record survives an APK replacement because it lives in the database,
and the refactored code did the right thing on its first hardware poll.

**Three renewals on HEAD, zero `[ypso-suspend]` lines.** The denominator guard is met, so the empty
grep is evidence rather than silence.

**The Hold/Renew boundary was demonstrated on real polls**, which is what the morning's run could not
do because that build lacked the field:

```
11:11:24  suspendRecord=open since 10:51 am (39min left of 60)     ← held, 39 > 30
11:31:23  renewed the 0% basal with 20 min left, new=true          ← renewed, 20 < 30
11:31:23  suspendRecord=open since 11:31 am (59min left of 60)
```

That is `SuspendRecordDecision.decide` returning `Hold` then `Renew` on hardware, matching the unit
tests that pin the same boundary. **So the extraction preserved the behaviour**, which was the only
open question.

⚠ **One honest limit: the `LAPSED` detector has still never fired.** Its absence here proves no lapse
occurred; it does not prove the detector works. That direction is pinned only by
`SuspendRecordDecisionTest`, which covers `lapsedByMs` in all three of its states. A detector that has
never triggered in the field is exactly the kind of thing this file keeps finding, and it is recorded
rather than assumed away — though note that unlike the checks that *could not* fail, this one is a
total function of three values and its inputs are examined by test.

#### 6b-RERUN — the procedure (keep for any future re-confirm)

> **This is the last correctness gate before an insulin trial.** The overnight run below was on a
> **pre-fix build**: it reproduced the defect faithfully and proved nothing about the repair. Read
> "6b-RERUN" immediately after this box; the history is kept because it is what the re-run is being
> judged against.


**Build under test:** anything at or after the commit that added the `[ypso-suspend] LAPSED` line.
Confirm before starting, because a build without it cannot fail this test:

```
grep -c "ypso-suspend" <the apk's sources, or just check the version you installed>
```

**Duration:** at least **3 hours** stopped, unattended. Two expiry cycles is the minimum that can
distinguish "renews" from "renewed once"; three hours gives four renewals and room for one to be
missed and seen. Overnight is better and costs nothing but saline.

**Setup**

1. Pump on saline, off-body, cannula to air. Cartridge with enough volume to sit for the duration.
2. AAPS running, screen off, on charge. Do **not** press Refresh — this test is about the idle poll
   path, and pressing Refresh is what made the original failure look quick to recover from.
3. Note the wall-clock time you stop the pump. Write it down; it is the left edge of the window.
4. **Stop the pump on its own buttons.** Leave it stopped and leave the phone alone.

**Then, at the end, four checks. All four must pass.**

**① The database, and this is the one that decides it.** It does not depend on a log line surviving:

```sql
select timestamp, duration,
       datetime(timestamp/1000,'unixepoch','localtime') as started,
       duration/60000.0 as minutes
from temporaryBasals
where isValid=1 and referenceId is null and type='PUMP_SUSPEND'
order by timestamp;
```

| | Pre-fix (what the overnight run showed) | Fixed (what a pass looks like) |
|---|---|---|
| `duration` of every record but the last | exactly `3600000` — 60.00 min, ran its full course | **≈40 min** — truncated by its successor opening early |
| Interval between consecutive `started` values | ~60 min, sometimes 80 when one lapsed | **≈40 min, never more than ~45** |
| Coverage | 39m47s uncovered in 13h40 | **zero uncovered** |

**A run whose records all read `duration = 3600000` is a run without the fix, whatever else the log
says.** That sentence is the whole reason this test is being repeated.

The arithmetic that adjudicates it: for each consecutive pair, `started[n+1] - (started[n] +
duration[n])` must be **≤ 0**. A positive value is an uncovered window and its size is the hole.

**② `[ypso-suspend] LAPSED` must not appear. Once.**

```
grep "ypso-suspend" AndroidAPS.log
```

This line is new, and it exists because the old test could not fail (see the box below). It is
emitted from `syncSuspendedState` *before* a replacement record is opened, comparing the expiry the
previous poll recorded against the current time. It is the only place in the driver where a hole is
observable at the moment it exists rather than by arithmetic afterwards.

⚠ **An empty grep is only evidence if the run was long enough to contain expiries.** Confirm the
denominator first — `grep -c "renewed the 0% basal"` must be **≥ 2**, or the test did not reach the
thing it is testing and the empty result means nothing. This is exactly the trap 7d and the type-19
echo fell into: a check that passed because its input never arrived.

**③ Read the MIDDLE of the log, not the ends.** A record that lapses partway looks like a pass from
either end — the first line reads `open`, the last line reads `open`, and the hole is between them.

```
grep "\[ypso-state\]" AndroidAPS.log
```

Every line now carries the minutes remaining:

```
[ypso-state] byte0=3 name='Stopped' mode=STOPPED delivering=false \
  suspendRecord=open since 2:30:12 am (41min left of 60) rate=… raw=[03 …]
```

Take the lines from the **middle third of the run** and read the countdown. A held record sawtooths
**60 → 40 → 60 → 40**, and the figure **never approaches zero**. What a lapse looks like is a line
reading a small number of minutes left, or `none`, followed by one reading `60min left of 60` with a
**new** `since` — the record was replaced rather than renewed.

**④ AAPS shows it as a flat gap in the basal record.** Overview → the basal graph across the stopped
window must be **flat at zero for its whole width**, with no steps back up to profile rate. In
Treatments → Temp Basals the 0% entries must **abut**: each one starting at or before the previous
one's end, with no visible gap between the bars. Look at this on the phone, not only in the log —
§4h's cancel passed every pump-side check and the UI is what caught it.

**Then close it.** Restart the pump (6c). The record must end — not expire — and the graph must
return to profile basal at the restart time, not up to 60 minutes later.

**If it fails.** Record the hole width and the two record timestamps either side of it, and stop:
`SUSPEND_TBR_RENEW_BELOW_MINUTES` is the number to move, and moving it is a code change, not a
retry. Do not simply run it again — the failure is phase-dependent and a second run can pass by
luck, which is how the 2-in-13 rate was originally reported as "about half the time" from a
two-sample run.

**What this test cannot reach.** A pump left stopped for over an hour is an alarm condition already
being ignored, and none of the everyday scenarios get there: a site change is 15–30 min, and a
shower or swim goes through AAPS's own Disconnect Pump, which writes `EMULATED_PUMP_SUSPEND` — a
different record type this code never touches. The reason to run it anyway is that *"while the pump
is stopped a 0% record is open"* is either an invariant or it is not, and a documented-but-false
invariant is what somebody relies on later.

#### 6b — the history this re-run is judged against ❌ **FAILED 2026-08-01, pre-fix build**

> **Result over 13h40 unattended, pump stopped: 2 gaps in 13 expiry cycles**, 19m48s and 19m59s,
> 39m47s uncovered in total (4.9%). Eleven cycles renewed within 0–4 seconds.
>
> ```
> missed:   record 21:30:34 → expiry 22:30:34.  Poll at 22:30:33 — ONE second early
> caught:   record 22:50:33 → expiry 23:50:33.  Poll at 23:50:35 — two seconds late
> ```
>
> The record was opened *by* a poll, so expiry fell exactly 60 min later, and AAPS polls every
> ~20 min — **60 is an exact multiple of 20**, so a poll landed within seconds of every expiry and
> drift decided it. Fixed by renewing below 30 minutes remaining, which breaks the phase-lock.
>
> **Severity is lower than it looks**, and lower than the first write-up claimed: a missing record
> makes AAPS *over-state* IOB, which is the conservative direction (§0), and `isSuspended()` holds
> the loop off throughout regardless. In normal use it is nearly unreachable — it needs the pump
> stopped over an hour, and a site change is 15–30 min while shower/swim disconnects go through
> AAPS's own `EMULATED_PUMP_SUSPEND`, a different record type entirely.
>
> **⚠⚠ The failure test below could not fail, and reported zero across a run containing two real
> gaps.** `syncSuspendedState` runs before `logDeliveryState`, so by the time a line is written a
> fresh record always exists — every line reads `suspendRecord=open`, including the pairs either side
> of a twenty-minute hole.
>
> **Polling itself was flawless**: 61 connections, 20-minute cadence, no doze, no drops, over
> 11h42 — total radio time 16m29s, a 2.35% duty cycle.

**⚠ How to measure this — do NOT grep for `none`.** Compare each record's `open since` against the
**previous** record's `open since` plus 60 minutes. Any interval longer than 60 min is an uncovered
window, and it is invisible in the log lines themselves.

```
grep "recorded a 0% basal\|renewed the 0% basal" AndroidAPS.log
```

With the fix in place, renewals should now appear roughly every **40 minutes** (`renewed the 0%
basal with ~20 min left`) and consecutive record starts should never be more than ~40 min apart.

**⚠ Better still, measure it in the database rather than the log** — added 2026-08-02, after the
overnight run above was re-derived from `androidaps.db` and matched the log analysis exactly
(19m48s and 19m59s, same two cycles). The durable signature does not depend on a log line existing:

```sql
select timestamp, duration from temporaryBasals
where isValid=1 and referenceId is null and type='PUMP_SUSPEND' order by timestamp;
```

**Before the fix, every record's `duration` is exactly 60.00 min** — it ran its full course and the
successor opened 0.1–4.2 s *after* it expired, so the record lapsed at every single boundary and
twice lapsed for a full poll. **With the fix, renewal happens at ~40 min, so AAPS truncates the
running record** and durations should read **≈40 min, not 60.00**, with starts ~40 min apart.

A run whose records all read `duration = 3600000` is a run **without** the fix, whatever the log
says. That is a check that can fail, which the `suspendRecord=open` one could not.

**⚠ The overnight run of 2026-08-01→02 was on a pre-fix build and the fix is still unverified.**
`fca883077c` was committed at **08:46 on 2026-08-02**; the pump was restarted at **08:49:20** and ran
for the rest of the session. Every record in that run reads `duration = 60.00 min`. The overnight
data reproduces the defect faithfully and says **nothing** about whether the fix works.

### 6b — the original procedure ⚠ **SUPERSEDED by 6b-RERUN; kept because its failure is instructive**

**Do not run this version.** Its acceptance criterion is the one that reported zero across a run
containing two real gaps. It is here so that the next person to write a check has an example of one
that could not fail, sitting next to the thing that replaced it.

This is the check that the `StateFlow` conflation fix was for: a stopped pump reports identical
bytes poll after poll, and the collector that writes this record used to fire only when the value
*changed*.

1. Leave the pump stopped, AAPS running, for **just over an hour**. Idle polling is roughly every
   20 minutes, so expect three or four `[ypso-state]` lines.
2. Press Refresh a few times during the hour rather than waiting passively — each one is a poll.

**Confirm:**

- Every `[ypso-state]` line through the whole hour says `suspendRecord=open`. **Not one says
  `none`.**
- Around the 60-minute mark a **second** record opens — the first expired and was replaced. In
  Treatments you should see two consecutive 0% entries, not a gap between them.
- Basal IOB does not start climbing again at any point.

A single `suspendRecord=none` line while the pump is stopped is a **failure**, and it is the exact
shape of the bug this fix was for.

⚠ **…and it can never be observed, which is why the above is superseded.** `syncSuspendedState` runs
*before* `logDeliveryState`, so a fresh record always exists by the time a line is written. The
criterion is not merely weak; it is unreachable for every possible input. Use 6b-RERUN.

### 6c. Restart the pump

1. Restart the pump on its own buttons.
2. Refresh.

**Confirm:** `Pump is delivering again (mode 'Running'); closed the 0% suspend record`, the line
reads `suspendRecord=none`, the notification clears, and the 0% entry in Treatments has **ended**
rather than still running.

### 6d. A bolus must not open one

Depends on 5a. With the pump running normally, give a **2 U** bolus from AAPS and watch every
`[ypso-state]` line from before it until two polls after.

**Confirm:** `suspendRecord=none` throughout. If one opens, the delivery-mode byte changed during
the bolus and something is treating an unmapped value as stopped — stop and report it, because it
would mean a spurious suspend record on every bolus, and an app killed mid-bolus would leave one
open for its full hour.

### 6e. A real temporary basal is not mistaken for one

The record is recognised as this driver's own by **type and duration together**. A real TBR has a
different type; the 60-minute duration is the second half of the signature. This checks that a
genuine TBR is never closed by accident.

1. Pump running. Set a **50% TBR for 30 minutes** from AAPS and confirm it is running (byte 10, and
   the ACT tab offering to cancel it).
2. Refresh twice. **Confirm** every line says `suspendRecord=none` and the TBR is untouched — the
   driver must not mistake a real TBR for its own record, and must not close it.
3. Let it run or cancel it normally. Confirm the cancel behaves as Phase 3 says.

**Then the interaction with the least analysis behind it**, which is worth watching precisely
because nobody has reasoned it through: with a **real 50% TBR running**, stop the pump on its own
buttons and Refresh. Record what happens to both records — whether the suspend record opens
alongside the TBR or instead of it, what Treatments shows, and then what restarting the pump leaves
behind. Write down what you see rather than what should happen; this row exists to be observed, not
confirmed.

### 6f. A TBR cancel must not close the suspend record ✅ **PASSED 2026-08-01**

> **Result.** Verified on pump 10074292 immediately after the fix was installed.
>
> ```
> 17:58:01  recorded a 0% basal, new=true        suspendRecord=open since 5:58 pm
> 17:58:23  cancelTempBasal: confirmed=true
>           TBR cancel: the open record is this driver's own 0% suspend record … left alone
> 17:59:54  suspendRecord=open since 5:58 pm     ← same record, 2 min and one cancel later
> 18:00:50  Pump is delivering again … closed the 0% suspend record, new=true
> ```
>
> Six readings, all `open since 5:58 pm` — the timestamp never moved. Before the fix the same
> sequence produced 5:11 → 5:12 inside 90 seconds. The synthetic-id warning is gone too: the cancel
> read a real entry, `seq=160593 type=10`, instead of hunting a millisecond clock reading.
>
> **And the opposite direction still works**, which is what says the fix discriminates rather than
> just refusing: a real 50% TBR for 15 min was set (`seq=160650 v1=50 v2=15`) and cancelled, and
> its record **closed** — `Recorded TBR stop under endPumpId=160650`, under the pump's own sequence,
> with no "left alone" line.
>
> **⚠ The bug was worse than the write-up above says.** The cancel at 17:58:23 was **not issued by
> the tester** — they stopped the pump and pressed Refresh, and AAPS issued `cancelTempBasal` on its
> own 22 seconds later, exactly as it had the day before. AAPS tidies up the temporary basal
> whenever it sees the pump suspended. So this was never a cancel-path edge case reachable only by
> going out of your way: **it fired automatically on every pump stop**, and left AAPS counting basal
> IOB on a stopped pump until the next poll — up to twenty minutes, unattended, with nobody
> pressing anything.

**Originally added after this failed on hardware**, and it remains the check for the fix. With the
pump stopped and a suspend record open, a temporary-basal cancel closed the record — AAPS believed
basal had resumed on a stopped pump for 52 seconds, until the next poll re-opened it.

⚠ **This is the only Phase 6 step with no unit test behind it.** `:pump:ypso` has no test source set
— the service needs Android test doubles and does not have them — so this procedure *is* the
regression test. Run it after any change to `cancelTempBasal` or `syncSuspendedState`.

1. **Stop the pump** on its own buttons. Refresh, and confirm `suspendRecord=open since <time>`.
2. **Cancel the temporary basal from AAPS** — the ACT tab will offer it, because AAPS sees the 0%
   record as a running TBR. That is the whole trap: what looks cancellable is not a TBR at all.
3. Refresh.

**Confirm:**

- The log says `TBR cancel: the open record is this driver's own 0% suspend record … so it is left
  alone`.
- **`suspendRecord=open since <the same time as step 1>`.** The timestamp must not have moved — a
  new time means it was closed and re-opened, which is the bug wearing a disguise.
- No `Recorded TBR stop under endPumpId=…` line.
- No `the entry AAPS expected (seq=…) was not in the log` line. That warning was the driver hunting
  its own synthetic timestamp id in a log of pump sequence numbers.

4. **Restart the pump.** The record must close normally, exactly as in 6c.

**Then check the opposite case still works**, because the fix makes the two paths asymmetric: with
the pump **running**, set a real 50% TBR, cancel it from AAPS, and confirm the AAPS record *does*
close (`Recorded TBR stop under endPumpId=…`). Leaving a real TBR open is the failure in that
direction, and it is the one §4h already cost a session to.

### Phase 6 is passed when

6a–6d and 6f all confirm, 6b shows **no two consecutive record start times more than ~40 minutes
apart**, and 6e's observation is recorded whatever it turned out to be.

⚠ **6b's criterion used to read "an unbroken hour of `suspendRecord=open`" and that was wrong** — it
passed across a 13h40 run containing two twenty-minute gaps, because the record is renewed before the
line is written. A phase is only passed by a check that could have failed.

**Status: all six confirmed**, 6b on 2026-08-03 — see its box for the build caveat, which is a
re-confirm rather than an open question.

⚠ **6b does not, and this line used to say "all six".** It failed, was fixed the next morning, and
the fix has never been exercised — the only long run was on a build committed three minutes before
the pump restarted onto it. Recording that as a pass turned "fixed" into "confirmed" without
anything in between, which is the same substitution the criterion above warns about, made about the
phase rather than inside it. **6b-RERUN is required, and it is not opportunistic**: it is the last
correctness gate before an insulin trial.

---

# Phase 7 — the history-sync gate ⏳

**This is the switch that turns pump history into insulin on board.** Getting it wrong corrupts IOB
silently and persistently rather than visibly, which is why it has been built and left off for
three sessions (`PORTING_NOTES` §8.5). Do it with someone watching and nothing else running.

**Before starting:** run Phases 5 and 6. Do not enable this on a session where anything else is
being changed.

### 7a. First enable takes a baseline and imports nothing ✅ **PASSED 2026-08-02**

> **Predicted before the run, from a history dump taken first: baseline must be 160658.** It was.
>
> ```
> 13:06:35 History sync: starting, watermark=unset (-1)
> 13:06:57 read 50 event(s) seq 160490..160658, 50 alert(s) seq 108154..160625,
>          88 of them mapped — in 21397 ms (213 ms/entry)
> 13:06:57 no watermark yet, so taking sequence 160658 as the baseline and importing nothing
> ```
>
> The watermark was genuinely unset: `ypso_last_synced_sequence` was **absent from the preferences
> file entirely**, checked before the run, so this was a first enable and not a repeat.
>
> **Confirmed against the database, not the log.** Treatments 11 before and after, matching the
> tester's count; nothing created in any table. And the check that could have failed: `seq 160492`
> holds **4.0 U in AAPS against `v1=249` → 2.49 U in the pump's log** (an early abort, recorded
> high). `syncBolusWithPumpId` is an upsert that rewrites the amount, so an import over that range
> would have pulled 4.0 down to 2.49. It still reads 4.0 — nothing was touched.

⚠ **The moment a baseline bug would surface as phantom IOB.** If the watermark is missing, the
driver must record where to start and import *nothing* — otherwise the pump's recent history becomes
treatments carrying IOB, including boluses from before AAPS was installed.

1. Note AAPS's current **IOB** and the number of entries in Treatments. Screenshot both.
2. Confirm the watermark is unset: this is the first enable, and `ypso_last_synced_sequence`
   defaults to `-1`. If this driver has ever had the preference on, it is **not** unset and 7a
   cannot be run without clearing it.
3. Turn on **YpsoSyncHistoryToAaps** in the YpsoPump preferences.
4. Press **Refresh**.

**Confirm:**

```
History sync: no watermark yet, so taking sequence <N> as the baseline and importing nothing.
```

- **No new treatments.** IOB unchanged. If anything appears, turn the preference straight back off
  and record what appeared — that is the failure this step exists to catch.
- `<N>` should be close to the newest sequence in a **Read history** dump taken just before.

### 7b. A bolus given on the pump's own menu reaches AAPS ✅ **PASSED 2026-08-02**

> A 1.5 U bolus on the pump's own menu, 13:17.
>
> ```
> 13:17:49 importing BolusDelivered seq=160661 at 2/08/26 01:16:59 pm
> 13:17:49 imported 1 event(s) above 160658; watermark 160658 -> 160661
> ```
>
> Twenty-one entries were logged as **considered and skipped** below the watermark, and exactly one
> taken — the distinction the old logging could not make.
>
> Database: one row, `id=17 ts=13:16:59.000 1.5 U pumpId=160661 ver=0`. Count 11 → 12. **IOB moved
> −0.04 → 1.47**, a swing of 1.51 for a bolus a minute old — the amount arriving through a consumer
> that cares about it, not a number in a log line.
>
> **Second refresh:** `skipping BolusDelivered seq=160661 — at or below watermark 160661`,
> `imported 0`. The row stayed at **version 0**, so it was not merely left correct, it was never
> written to.

The gap this whole feature exists for: until now a manual bolus was invisible to the loop, so IOB
was under-stated and AAPS would dose on top of insulin already delivered — the stacking direction.

1. Give a **1.5 U** bolus **from the pump's own menu**, on saline. Note the time to the minute.
2. Press **Refresh**.

**Confirm:**

- `History sync: importing BolusDelivered seq=<S>` and `imported 1 event(s) above <watermark>`.
- Treatments shows **one** bolus of **1.5 U**, at the right time — within seconds, not hours. A
  bolus off by twelve hours is a real failure mode here (§5), and the clock correction (§8.6) is
  what defends against it.
- IOB moves by roughly 1.5 U.
- Press Refresh again. It must still be **one** bolus, not two.

### 7c. A bolus from AAPS is not duplicated when the sync re-reads it ✅ **PASSED 2026-08-02 — moved 6.455 s**

> A 1.0 U bolus from AAPS. AAPS keeps every prior version of a row, so the whole lifecycle is on
> record and the move is measurable to the millisecond rather than by eye:
>
> | version | timestamp | amount | pumpId | written |
> |---|---|---|---|---|
> | 0 | 13:26:23.079 | 1.0 U | — | provisional, before sending |
> | 1 | 13:26:27.455 | 1.0 U | 160662 | settled, phone's clock |
> | 2 | **13:26:21.000** | 1.0 U | 160662 | history sync, pump's clock |
>
> **One row throughout** — 0 and 1 are history rows against `id=18`, not separate treatments — and
> the amount never left 1.0 U. Second refresh imported 0.
>
> **⚠ The 6.455 s is not a clock measurement, and the ten-second criterion above is unsound.**
> Decomposed: measured skew **−2347 ms**, and the bolus took **≈4.3 s** to deliver (1.0 U at
> 12 U/min). The pump's entry lines up with the **send**, not the completion — so the delta is
> delivery duration plus skew, and it **scales with bolus size**. A 10 U bolus takes ~50 s, so it
> would move ~52 s and trip this check with a perfectly healthy clock. Judge the clock by the
> `Pump clock is …ms from the phone's` line, which is now logged below the threshold as well as
> above it. Treat "the pump timestamps at the start" as one clean observation, not settled: the
> older `seq 160492` record does not fit it neatly, but that is different code and an unknown skew.

The skip list that used to protect this was **removed** (§8.5): the sync upserts on `pumpId`, and
the pump's sequence is the `pumpId` both at delivery time and at sync time, so the same bolus
resolves to one record by identity rather than by exclusion.

1. Give a **1.0 U** bolus **from AAPS**. Confirm it appears in Treatments as usual.
2. Note its timestamp to the second.
3. Press **Refresh** so the sync re-reads it. Then Refresh once more.

**Confirm:**

- **One** 1.0 U treatment, not two, after both refreshes.
- The amount is unchanged.
- The timestamp may **move slightly**, from the phone's time to the pump's, and that is expected —
  it is the one field the dropped skip list was defending. **Record how far it moved.** More than
  ten seconds means the clock correction is not doing its job, since the driver now resyncs the
  pump's clock whenever drift exceeds 10 s.

### 7d. The 24-entry burst from a profile write ✅ **PASSED 2026-08-02 — 20.4 s per sync**

> **⚠ Step 1 below is wrong as written and would have tested nothing.** The pump logs one type-7
> entry **per changed slot**, not one per slot — so re-writing the profile the pump already holds
> appends **zero** entries. That is exactly what happened at 09:27 when AAPS re-pushed the active
> profile after a reinstall: `writeBasalProfile: confirmed=true` and nothing in the log. Had 7d been
> run as written, it would have passed while producing no burst at all — a fourth check that could
> not fail.
>
> **Run it with a profile that differs in every slot.** Done here with a flat **0.35 U/h** against
> a pump holding `[0.10, 0.15, 0.02, 0.25, 0.30×5, 0.05×15]`:
>
> ```
> 13:32:52 read 50 event(s) seq 160581..160686, 50 alert(s) seq 108154..160625 — in 19940 ms
> 13:32:52 gap check against watermark 160662 — events newest=160686 oldest=160581 -> 0 missed
> 13:32:52 importing BasalRateChanged seq=160663 … 160686        (24 of them)
> 13:32:52 No PumpSync mapping for BasalRateChanged seq=160663, skipped        ×24
> 13:32:52 imported 24 event(s) above 160662; watermark 160662 -> 160686.
>          Took 20407 ms for 100 entr(ies) (read 19940 ms, import 158 ms)
> ```
>
> All four criteria met, and repeated on a second write (flat 0.15 U/h, `seq 160687..160710`).
> Database: bolus, TBR, therapy-event and carb counts all unchanged. The only new rows were
> `profileSwitches` and `effectiveProfileSwitches`, written by the profile activation **before** the
> sync ran — AAPS's own bookkeeping, not the sync's.
>
> **Headroom, measured rather than assumed.** After the burst the event window spans 160581–160686:
> 24 of its 50 slots are new, leaving **26 entries of margin** below the watermark. Two 24-entry
> writes between one pair of polls would just fit; three would not.
>
> **⚠ Elapsed: 20.4 s per sync, not the ~10 s §8.5 predicted** — five syncs measured, 19.9–21.4 s,
> ~200 ms/entry. §8.5 costed one 50-entry window; the driver reads **two**. See §8.5 for what that
> means for SMB-rate looping and the cheap way out.

One basal profile write appends **up to 24 entries** — one per slot that actually changes, and none
at all if the profile written matches the one the pump holds (§5). The read window is 50, so a full
burst should fit, and this is the closest thing to an overflow the driver can produce on demand.

1. Write a basal profile from AAPS (Phase 4's procedure, and put the original back afterwards).
   ⚠ **It must differ from the pump's current profile in every slot** — see the result above. A flat
   rate that appears nowhere in the current profile is the easy way to guarantee that.
2. Press **Refresh**.

**Confirm:**

- No `entr(ies) above <watermark> were never read and are lost` line.
- The watermark advances past all 24.
- No treatments appear from them — basal profile entries map to nothing, deliberately.
- Note the **time the sync takes**. It is roughly 195 ms per entry, so a 50-entry window is about
  10 seconds on every connection. Fine now; far too slow for a loop issuing SMBs every five
  minutes, and this is the measurement that says how close that is (§8.5).

**On the gap warning:** it was inert until this session — measured across the union of the event and
alert logs, where the alert log always reaches back years, so it answered zero for every possible
input. It is now measured per log. That means 7d is the first time it has ever been able to fire,
so a quiet run here is worth something it was not worth before.

### 7e. Leave it running and look again ✅ **PASSED 2026-08-02**

> **14 consecutive idle polls, 13:56 → 17:56**, pump untouched throughout. Every one:
>
> ```
> History sync: imported 0 event(s) above 160710; watermark 160710 -> 160710.
>               Took ~20.5 s for 100 entr(ies)
> ```
>
> Durations 20374–20719 ms across the whole run — no drift, no degradation.
>
> **Database: zero rows created in any table** after 13:42 — boluses, TBRs, therapy events, carbs,
> extended boluses, profile switches. Counts unchanged at 13 / 31 / 10, watermark still `160710` on
> disk. Nothing arrived from Nightscout or the Lotus phone either, both of which have produced this
> exact signature before.
>
> **It also priced the waste.** Those 14 polls spent **4.8 minutes of radio time** reading 1,400
> entries to establish that nothing had happened — the workload of an idle pump, in full. See
> Phase 7f.

Leave the preference on with the pump idle for **an hour or two**, then check Treatments for
anything that appeared without a cause. Phantom IOB is the failure mode, and it is quiet.

**⚠ Two causes that are not the driver**, and both have produced exactly this signature before:
Nightscout enacts inbound commands on the research pump (a 0% TBR for 120 minutes appeared
mid-session once), and the Lotus study phone writes the basal profile the moment it wakes. Check
both before calling anything phantom.

### Phase 7 results — 2026-08-02

| Step | Verdict | The number |
|---|---|---|
| 7a first enable imports nothing | ✅ **PASS** | baseline **160658**, predicted before the run |
| 7b manual bolus arrives once | ✅ **PASS** | 1.5 U at 13:16:59, IOB −0.04 → 1.47 |
| 7c AAPS bolus not duplicated | ✅ **PASS** | timestamp moved **−6.455 s**, of which ~4.3 s is delivery time and 2.35 s is skew |
| 7d 24-entry burst | ✅ **PASS** | 24 imported, 0 to treatments, no gap, **20.4 s** per sync |
| 7e nothing appears unbidden | ✅ **PASS** | 14 idle polls over 4h20, 0 imported, 0 rows created |

**Phase 7 is passed. `YpsoSyncHistoryToAaps` is ON** as of 2026-08-02 (`PORTING_NOTES` §8.5).

Three things the run found that the procedure had wrong, all of the same shape — a check whose
inputs nobody had examined:

1. **7d as written could not fail.** A profile write only logs *changed* slots, so re-writing the
   profile already on the pump appends nothing.
2. **7c's ten-second threshold conflates two things.** The delta is delivery duration plus clock
   skew, and it scales with bolus size.
3. **6b's overnight run was on a pre-fix build**, so the renewal fix remains unverified.

### Phase 7 is passed when

7a imports nothing, 7b's manual bolus arrives exactly once at the right amount and time, 7c stays
at one record with the timestamp moving less than ten seconds **once delivery duration is
subtracted**, 7d is quiet, and 7e finds nothing unexplained.

**If any of them fails, turn `YpsoSyncHistoryToAaps` back off** before doing anything else. It is a
preference precisely so that it can be switched off in one action.

---

# Phase 7f — the cheap probe ✅ **PASSED 2026-08-02**

> **Eight syncs after install: six quiet, two full reads, and the probe was right every time.**
>
> ```
> 18:28:03  newest sequence is 160710, still at the watermark — nothing new,
>           so the window was not read. Probe took 576 ms.
> 19:16:45  read 50 event(s) seq 160662..160714 … importing BolusDelivered seq=160714
>           imported 1 event(s); watermark 160710 -> 160714. Probe 579 ms.
> 19:17:20  newest sequence is 160714, still at the watermark … Probe took 572 ms.
> 19:47:13  imported 24 event(s) above 160714; watermark 160714 -> 160738. Probe 619 ms.
> ```
>
> | | |
> |---|---|
> | Probe cost | **572–621 ms**, eight samples |
> | Quiet sync, before → after | **20 500 ms → ~580 ms**, a **36× cut** |
> | Cost when a read *is* needed | **+580 ms** on top of the full read |
> | Radio across the eight syncs | **47 s**, against 164 s under the old code |
> | Extrapolated idle day (72 polls, 4 with events) | **24.6 min/day → 2.1 min/day** |
>
> `grep -c "read 50 event"` returned **0** across the whole quiet period — the window was genuinely
> not read, not merely read fast. Database unchanged throughout except the one imported bolus
> (`id=21 ver=0 1.0 U pumpId=160714`) and AAPS's own profile-switch rows.
>
> **⚠ The 0.4 s estimate was wrong, and why is worth keeping.** `readHistory` reads the log **count**
> before any entry, so the probe is count + index-write + value-read **per log** — six GATT round
> trips at ~95 ms, not four. That also cross-checks the ~200 ms/entry figure exactly, since an entry
> is two of those round trips. The arithmetic closing is better evidence than the estimate having
> been right would have been.

## Phase 7f — the original procedure

**This is sync logic, so it does not inherit Phase 7's pass.** Commit `5ac789dd8e` changed what the
sync reads: index 0 of each log first (~0.4 s), and the full 100-entry window **only** when the
newest sequence has moved past the watermark. Phase 7 validated the driver *without* it.

Why it exists: 7e spent 4.8 minutes of radio across four idle hours to learn nothing had happened.
At AAPS's 20-minute idle cadence that is merely wasteful; at the 5-minute cadence of a loop issuing
SMBs it delays every command behind a 20-second read.

**Do not run this on the same session as anything else.** Install, then:

### 7f-a. The quiet path skips the window

1. Press **Refresh** with the pump untouched since the last sync.

**Confirm:**

```
History sync: starting, watermark=<W>
History sync: newest sequence is <W>, still at the watermark — nothing new, so the window
              was not read. Probe took <N> ms.
```

- **`<N>` should be under ~1000 ms**, against ~20500 ms before. That number is the whole point.
- **No `read 50 event(s)…` line at all.** Its presence means the probe did not short-circuit.
- No treatments appear, and Treatments count is unchanged.

### 7f-b. A bolus still arrives, through the probe path

The risk is a probe that says "nothing new" when something happened — silently stopping IOB, which
is the stacking direction and the quietest failure this driver has.

1. Give a **1.0 U bolus from the pump's own menu**. Note the time.
2. Press **Refresh**.

**Confirm:**

- The probe does **not** short-circuit: a full `read 50 event(s)…` line appears.
- `importing BolusDelivered seq=<S>`, exactly one, right amount, right time.
- IOB moves by ~1.0 U.
- Press Refresh again: it must now take the quiet path (7f-a's line) and import nothing.

### 7f-c. The 24-entry burst still crosses the probe

1. Write a basal profile differing in **every slot** (see 7d — a flat rate appearing nowhere in the
   current profile).
2. Press **Refresh**.

**Confirm:** full read, 24 `BasalRateChanged` imported, no gap warning, no treatments, watermark
advances past all 24. Then put the profile back.

### What cannot be tested here

**The `SequenceWentBackwards` branch** — a log wrap, a pump swap, or a restored backup. It is not
producible on demand and it is roughly three months away on this pump's log at the current rate of
profile changes. It is pinned by unit tests instead (`HistorySyncDecisionTest`), and its behaviour
is deliberately identical to the old code's; the only change is that it is now logged at **error**
instead of happening in silence.

### Phase 7f is passed when

7f-a skips the window in under a second, 7f-b's bolus still arrives exactly once, and 7f-c's burst
still crosses. **Then, and not before, flip `YpsoSyncHistoryToAaps` to default `true`** — the flip
is a statement that the shipped build is ready, and it should not be made about a build carrying
unverified sync logic.

✅ **All three passed 2026-08-02, and the default was flipped to `true` the same day.**

---

# Phase 8 — the extended bolus ⏳ **NOT STARTED**

**Written 2026-08-03, before any code, which is the rule this file keeps for itself.** Four of the
checks in these notes initially "passed" against a test that could not have failed, and every one of
them was written *after* the thing it was checking. This phase exists on paper first so that its
acceptance criteria were chosen while nobody was under pressure to accept a weaker one.

## Why this exists, and what it is not

**The goal is comfort on large boluses**, and nothing else. The pump delivers at a fixed **~33 U/min**
(measured, §8.6), so a 10 U bolus goes in over 18 seconds. Other pumps' normal speed is 1.5–3 U/min —
Omnipod delivers 0.05 U every 2 s. An extended bolus is the pump's own mechanism for that, and it is
already in the wire format:

```
totalScaled(4B) + durationMinutes(4B) + immediateScaled(4B) + bolusType(1B)
bolusType = 1 for a standard bolus (duration == 0), 2 for extended/multiwave
```

`YpsoBleManager.startBolus` already implements it. What refuses is
`YpsoPumpManagerImpl.deliverExtendedBolus`, one layer above BLE. **So this phase opens a gate; it
does not write a driver.**

**⚠ This is not a loop capability and must not become one.** `isFakingTempsByExtendedBoluses` stays
**false**. Nothing here is for emulating a temporary basal, and the SMB algorithm is not to be handed
a new delivery channel on the strength of a comfort feature.

**Sub-minute durations were considered and rejected.** A 1-minute extended bolus of 10 U is 10 U/min,
still three times faster than the pumps whose speed people call comfortable, so it answers a question
comfort does not ask. AAPS's existing 15-minute duration step gives 0.67 U/min on a 10 U bolus, which
is already gentler than Omnipod. **The granularity is not the problem and does not need changing.**

## Before starting

- **Phase 6b's re-run must have passed.** Adding a delivery capability ahead of it means the last
  correctness gate gets run against a driver that changed underneath it.
- Saline, off-body, cannula to air. Full cartridge — this phase delivers repeatedly.
- The study phone off; NSClient uploading off (§0).
- ⚠ **Do not enable the multiwave/combined bolus in the same change.** It is a separate refusal and
  gets its own gate. One at a time, as always.

## 8a. The queue-blocking question ✅ **PASSED 2026-08-03**

> **The pump does extended boluses, and it enforces a minimum duration.** Run at 14:19 on saline,
> 115 U in the cartridge, driven from `adb`.
>
> ```
> control: standard 0.1U (type=1)            -> accepted
> trying 1.0U over 1min  (type=2)            -> REJECTED
> trying 1.0U over 15min (type=2)            -> ACCEPTED
> t+0s   slow[status=1 seq=160798 injected=0.0  total=1.0] duration=0/15 min
> t+2s   slow[status=1 seq=160798 injected=0.17 total=1.0] duration=0/15 min
> t+30s  slow[status=1 seq=160798 injected=0.17 total=1.0] duration=0/15 min
> cancel -> slow[status=0 injected=0.0 total=0.0] duration=0/0    CONFIRMED by read-back
> ```
>
> **1 minute rejected, 15 accepted.** AAPS's `extendedBolusSettings` 15-minute step was describing
> **the pump**, not the UI — the same figure mylife Lotus offers. The earlier single-shot probe's
> report said *"Extended bolus is not available on this firmware"*; that was an over-claim from one
> observation with three possible explanations, and this is the run that disproves it.
>
> **The echo is exact**: `slowTotal = 1.0` against 1.0 U asked, `totalDuration = 15` against 15 min
> asked, and the pump assigned its own sequence `160798`.
>
> **Delivery is genuinely spread, and it is PULSED rather than continuous.** `slowInjected` went to
> **0.17 U within 2 s and then held there for the remaining 28 s**. 0.167 U is exactly 1/6 of 1 U, so
> a 15-minute bolus appears to be six pulses ~2.5 min apart. At the pump's normal ~33 U/min the whole
> 1 U would have been gone in 1.8 s, so the duration is unambiguously being honoured.
>
> **Cancel works and was confirmed by read-back** — Phase 8d's safety half, exercised here for the
> first time anywhere.

> ⚠⚠ **The queue-blocking answer is "yes it would block", and that decides how the real method must
> be written.** `slowStatus` stayed **1** for the whole 30 s — the pump reports an extended bolus as
> *running* for its duration, not as *programmed*. So `deliverExtendedBolus` must **not** reuse
> `deliverBolusConfirmed`'s wait-for-`Idle` loop, or a 15-minute bolus would hold the AAPS command
> queue for fifteen minutes.
>
> What it must do instead is exactly what this probe did: send, then **confirm by read-back that
> `slowTotal` and `totalDuration` echo what was asked**, and return. That satisfies §0 — success is a
> claim the pump has confirmed — while redefining what is being claimed: *an extended bolus of this
> amount and duration is running*, not *the insulin has been delivered*. Write that distinction down
> at the call site when the method is implemented.

## 8a — the original procedure

**The question:** `deliverBolus` waits for the pump's `BolusStatus` to reach `Idle`, and the AAPS
command queue is blocked behind that call for its whole duration (§8.3). If an extended bolus reports
busy for its full programmed duration, then **a 15-minute extended bolus blocks the queue for 15
minutes** — no status read, no temporary basal, no alert check, nothing. That would be a far worse
regression than the feature is worth, and it is a property of the pump nobody can predict.

**The cheapest possible exposure: 1 U over 1 minute, and it has its own button.**

⚠ **Do not do this by enabling the capability and using AAPS's extended-bolus dialog.** That flag
tells `LoopPlugin`, `TreatmentsActivity`, `ActionsFragment` and `goToZeroTemp` that this pump does
extended boluses — including 8d's automatic `cancelExtended`, which raises an audible delivery-error
alarm while cancel still refuses. All of that would be live before anyone had seen the pump answer a
single `bolusType = 2` command.

Instead there is **EXTENDED BOLUS PROBE (BENCH TEST)** on the YPSO tab. It is confirmation-gated,
its amount and duration are constants in `YpsoService` rather than dialog input, it writes **no**
treatment record, and `isExtendedBolusCapable` stays **false** throughout, so nothing in AAPS's model
of the pump changes. It reaches `YpsoPumpManagerImpl.probeExtendedBolus`; the contract method
`deliverExtendedBolus` stays refused.

1. Pump on saline, off-body, cannula to air. Reservoir reading noted.
2. Press the button and confirm the dialog. It runs for about **two minutes** and blocks the command
   queue for that time — deliberately, because watching *when* the pump stops reporting the bolus is
   the measurement.
3. Read the report it shows, and `grep "\[ypso-ext\]"` in the log for the same lines.

**Every reading prints all twelve `BolusStatusData` fields**, `slow*` included — those have been
decoded since the SDK was vendored and **nothing has ever read them**, so what they contain during an
extended bolus is genuinely unknown. Printing only the decode would answer the question the reader
already thinks they know, which is §8.3's lesson about `byte0` and the payload behind it.

**Four outcomes, and the report names which one it saw:**

| What the report says | What it means |
|---|---|
| *"the pump REFUSED the bolusType=2 command outright"* | The ATT layer rejected it. Extended bolus is unavailable on this firmware. Phase 8 ends here |
| *"accepted but the pump never reported an extended bolus"* | Silently discarded — this pump's documented behaviour for a payload it cannot validate (§4f). The 13-byte layout is wrong for `bolusType = 2` |
| *"the pump did NOT echo what was sent"* | It parsed the payload differently from how this code laid it out. Cancelled automatically. **Do not retry without changing the layout** |
| *"accepted and ran an extended bolus, echoing the amount and duration"* | ✅ Continue — then read the `t+Ns` lines for the two things that decide 8a |

**From the `t+Ns` lines, two separate questions:**

- **Did `slowInjected` climb gradually?** If it jumps to 1.0 immediately the pump ignored the duration
  and delivered at ~33 U/min, and the whole feature is pointless on this firmware.
- **When did it stop reporting the bolus?** At **~60 s** means the pump treats an extended bolus as
  *running* for its duration — so a 15-minute one would hold the AAPS command queue for fifteen
  minutes and `deliverExtendedBolus` must be written to return once programmed rather than reusing
  the standard bolus's wait. Promptly after acceptance means the queue is free and the normal
  pattern transfers.

## 8b. Does the pump actually spread the delivery? — **read the MIDDLE**

**The question:** whether `duration` changes anything, or the pump accepts it and delivers at
33 U/min anyway.

1. Reservoir reading before. **5 U over 15 min.**
2. Press Refresh at roughly **2, 5, 8 and 12 minutes** and record the reservoir each time.
3. Reservoir reading after.

**Confirm:** the reservoir falls **gradually** — roughly 0.33 U/min, so about 1.7 U gone at 5 minutes
and 4 U at 12.

⚠⚠ **This is the check most likely to be written so that it cannot fail, and its failure mode is 6b's
exactly.** Before and after readings alone are *identical* for an extended bolus and for an immediate
one — both end 5 U down. **Only the middle distinguishes them.** A test that reads the ends and
reports a pass has measured that 5 U left the cartridge, which was never in question.

This is the same evidence §8.3 used to prove the bolus probe was live: the reservoir in the payload,
partway through, sitting between the start and end figures.

## 8c. What it logs ⚠ **RUN 2026-08-03 — it lands in the WRONG TABLE**

> **The failure this step existed to catch, caught.** After the 8a probe, the database says:
>
> ```
> extendedBoluses:  (empty)
>
> boluses:
>   14:19:17   0.17   NORMAL   160798    <- the EXTENDED bolus
>   14:19:12   0.10   NORMAL   160797    <- the control, correctly NORMAL
> ```
>
> Sequence 160798 is the one `BolusStatus` reported for the extended bolus, so there is no ambiguity
> about which delivery this is. **It was recorded as a normal bolus.**
>
> The raw entry shows why, and shows that the fix is reachable:
>
> ```
> [history] idx=1 seq=160797 type=2(Bolus Immediate) v1=10 v2=0 v3=0
> [history] idx=0 seq=160798 type=3(Bolus Delayed)   v1=17 v2=0 v3=0
> ```
>
> **The pump gives the extended bolus its own event type — 3, "Bolus Delayed" — distinct from the
> standard bolus's type 2.** `YpsoHistoryMapper` maps both to `BolusDelivered`, which is why it
> reached `syncBolusWithPumpId` and landed in `boluses`. So the driver *can* tell them apart and
> currently does not.
>
> ⚠⚠ **But the entry carries no duration.** `v2` and `v3` are both zero where a standard bolus has
> nothing to put there anyway. `PumpSync.syncExtendedBolusWithPumpId` needs amount **and** duration,
> so **an extended bolus cannot be reconstructed from the pump's history alone.** The duration has to
> come from the driver's own record made at delivery time — the same shape as the bolus settle path,
> where a provisional record is opened before sending and settled against the log afterwards.
>
> That is a design constraint on implementing this properly, and it is worth having found before
> writing the code rather than after.
>
> **Why the wrong table matters, in the direction that bites.** A completed 15-minute extended bolus
> logged as a single `NORMAL` bolus would put all of its insulin at one instant. If the pump writes
> that entry when the bolus *ends* — which is what §4f established for the standard bolus — then for
> the whole 15 minutes AAPS sees **no IOB for insulin that is going in**, and may dose on top of it.
> That is the stacking direction.
>
> **Still unknown, and needs a completed rather than cancelled bolus:** whether a bolus that runs to
> completion logs its duration in `v2`, and whether `v1` is then the programmed or the delivered
> amount. This run was cancelled at 30 s, and `v1=17` was the **delivered** 0.17 U, not the programmed
> 1.0 U — which is the right convention and matches §4f, but says nothing about the completed case.

## 8c — the original procedure

1. After 8b, press **Read history** and record every entry the extended bolus produced — `seq=`,
   `type=`, `v1=`, `v2=`.
2. Query the database, not the log — §5c-ii's lesson, where `insertTherapyEvent` logged nothing on
   its success path and only the database could tell one record type from another:

```sql
select datetime(timestamp/1000,'unixepoch','localtime') ts, amount, duration, pumpId, isValid
from extendedBoluses order by timestamp desc limit 5;

select datetime(timestamp/1000,'unixepoch','localtime') ts, amount, type, pumpId
from boluses order by timestamp desc limit 5;
```

**Confirm:** it lands in **`extendedBoluses`**, not in `boluses`. The two have different IOB decay
models, and an extended bolus recorded as a normal one over-states insulin action early and
under-states it late — a silent, persistent IOB error of the same class as the `pumpId` question
Phase 7 existed to settle.

**Record whichever it is**, because `YpsoHistoryMapper` decodes four bolus types and which one the
pump uses here decides whether the **history sync** re-imports it correctly on the next poll. Then
re-read history a second time and confirm it stays **one** record, as 7c did for the standard bolus.

## 8d. Cancel — **ships in the same change, not after it**

⚠ **The one place this file's "one refusal at a time" rule is deliberately bent, and the reason is
concrete.** `LoopPlugin.goToZeroTemp` — the disconnect-pump and suspend-loop path — does this:

```kotlin
if (pump.pumpDescription.isExtendedBolusCapable && getExtendedBolusActiveAt(now) != null)
    commandQueue.cancelExtended(...)      // on failure: runAlarm(extendedbolusdeliveryerror)
```

Advertise the capability with `cancelExtendedBolus` still refusing, and **every pump disconnect during
an extended bolus raises an audible delivery-error alarm.** Deliver and cancel are a pair, and cancel
is the safety half of it.

1. Start **5 U over 15 min**. Let it run ~5 minutes.
2. Cancel it from AAPS.

**Confirm:** the pump stops delivering (the reservoir stops falling across two further Refreshes);
the partial amount recorded is **the pump's figure, not the programmed 5 U**; and the
`extendedBoluses` record is **ended**, not left running. Same rule as everywhere else — the amount
comes from the pump's log, never from what was asked for.

## 8e. The loop's own cancel path

The thing 8d's warning is about, exercised deliberately rather than discovered.

1. Start **5 U over 15 min**.
2. While it runs, use AAPS's own **Disconnect Pump**.

**Confirm:** `cancelExtended` succeeds, **no audible delivery-error alarm**, and the record closes.
A failure here stays silent until the day somebody disconnects mid-bolus.

## 8f. Byte 0, and the suspend record

**The question:** whether an extended bolus is a distinct delivery mode, and — the harm §8.6 predicts
— whether it opens a spurious `PUMP_SUSPEND` record.

With a 15-minute extended bolus running, Refresh and read the `[ypso-state]` line.

**Confirm:** `byte0=10`, and above all **`suspendRecord=none`**. If byte 0 turns out to be a third
value, `isDeliveringOrNull` returns null, AAPS reports the pump suspended, and the loop is held off
for the whole duration of every extended bolus. That would be the first new byte-0 value this project
has found, and it would change §8.3.

## 8g. With a temporary basal running

Never tried together, and §8.4 item 1 has the standard-bolus version of this open too. A TBR running
while an extended bolus is programmed: does either disturb the other, and do both still read back?

## What cannot be tested here

**A connection lost mid-extended-bolus.** The pump owns the delivery and will finish it whether or not
the phone is there — that is the property §0 relies on — but what AAPS's *record* does in between is
untested, exactly as it is for the standard bolus (§8.4 item 2). `ReconnectManager` is armed for the
standard bolus only, and whether it should cover a 15-minute extended one is a design question this
phase does not answer.

## Phase 8 is passed when

**8a's three timestamps are recorded and the queue was not blocked**; 8b's reservoir fell gradually
**with at least two middle readings, not just the ends**; 8c lands in `extendedBoluses` and stays one
record across a re-read; 8d and 8e both cancel cleanly with no delivery-error alarm; and 8f reads
`suspendRecord=none`.

**Then, and not before**, restore the capability — which for this driver means deleting
`isExtendedBolusCapable = false` from `YpsoPlugin.pumpDescription`, **not** editing
`PumpCapability.YpsomedCapabilities` in shared `core/data` (§9). `YpsoPluginTest` asserts the
withdrawal, so that test is expected to be updated in the same change, and its failing is the
reminder that this is a deliberate act rather than a tidy-up.

---

# Phase 9 — the chunked bolus, for comfort ⏳ **BUILT 2026-08-03, AWAITING HARDWARE**

**Written 2026-08-03, before any code.** The mechanism is not a guess: it was read off mylife Lotus's
own settings screen and then confirmed in the pump's event log, so this phase starts from a worked
example rather than a hypothesis. That is a better position than Phases 1–8 began from, and it is
worth not squandering by skipping the gates.

## What this is, and why it replaced the extended bolus

**The goal is comfort on large boluses.** The pump delivers a commanded bolus at a fixed **~33 U/min**
(§8.6), so 10 U goes in over 18 seconds. Other pumps take minutes.

**"Bolus speed" is not a pump setting. It is a loop in the phone.** Lotus's four options are labelled
with their own implementation:

```
unlimited              33.3 U/min — the pump's native rate, no chunking
12 U/min   =  1 U   per 5 s
 6 U/min   =  0.5 U per 5 s
 3 U/min   =  0.4 U per 8 s
```

Confirmed on 2026-08-03 in the pump's log — a 2 U bolus from Lotus at 3 U/min:

```
seq=160875 type=2(Bolus Immediate) v1=41  -> 0.41 U
seq=160876 type=2(Bolus Immediate) v1=41  -> 0.41 U   +8 s
seq=160877 type=2(Bolus Immediate) v1=41  -> 0.41 U   +8 s
seq=160878 type=2(Bolus Immediate) v1=41  -> 0.41 U   +8 s
seq=160879 type=2(Bolus Immediate) v1=36  -> 0.36 U   +8 s
                                             = 2.00 U exactly
```

Five ordinary standard boluses, the last trimmed so the total lands exactly. **Nothing is written to
the pump.** So this needs no new pump capability, no new wire format, and no new event type — every
chunk is the `type=2` bolus this driver has delivered and confirmed since Phase 2.

**Why not the extended bolus (Phase 8).** It works, but floors at a **15-minute minimum** — 0.67 U/min
for a 10 U dose, far slower than the 12 U/min asked for — and it lands in `boluses` as `NORMAL` with
no duration in its log entry, so it cannot even be recorded correctly without more work. Phase 8's
findings stand on their own; they are just not the answer to this question.

## ⚠ This reverses an argument these notes made first

When the question was first asked, this file argued **against** driver-side chunking on §0 grounds:
*"the pump owns the bolus once it has been told"*, and chunking makes the phone the sequencer. The
objection was over-stated, and three things moved it:

- **Every chunk is a complete, pump-owned, pump-logged bolus.** A phone that dies mid-sequence simply
  stops issuing chunks; the pump finishes the one in flight and no more. That is **under**-delivery,
  and the driver can report exactly what went in because every chunk is in the log — which is what
  §4f's partial-bolus path already does for a cancelled bolus.
- **The manufacturer's own app does this**, on this pump, with these parameters.
- **The history sync already handles the result**, demonstrated without being asked to: all five
  chunks above imported as separate records totalling 2.00 U, while AAPS's own bolus stayed a single
  live row (7c holding under a five-entry burst).

Recorded because a future reader will find both arguments in this repository and should know which
one the evidence settled on, and why.

## Design decisions to make BEFORE writing code

Each of these changes what the tests must check. Decide them, write them down, then build.

| Question | Options | Note |
|---|---|---|
| **Where does the loop live?** | `YpsoPumpManagerImpl.deliverBolusConfirmed`, or a layer above it | Below the mutex means the whole sequence holds `withConnection`, which is what keeps a second command from interleaving mid-sequence |
| **Confirm each chunk, or only the total?** | per-chunk echo (`fastTotal`) then one log read at the end | A silently-dropped chunk must not pass unnoticed. The gap is 5–8 s, so there is time |
| **One AAPS record or N?** | N (what the pump did) vs 1 synthetic (what the user asked for) | N is *accurate* and the sync produces it anyway. 1 would fight the sync on every re-read |
| **What does `success` mean at chunk 7 of 10?** | `success=false`, `bolusDelivered` = sum of confirmed chunks | §0: never report more than the pump confirmed, but record the full amount that went in |
| **Does the preference survive?** | `YpsoBolusSpeed` currently controls nothing and is due for deletion (§8.6) | If chunking lands, it becomes real for the first time — and needs a **fourth** option, 6 U/min, which the current three-way list is missing |
| **Ceiling on duration?** | 10 U at 12 U/min = 50 s of held command queue | Compare: the standard bolus already holds it for ~20 s. A cap on total sequence time is worth considering |

## Before starting

- Saline, off-body, cannula to air. Full cartridge.
- Study phone (Lotus) **off** — it rewrites the basal profile on connect (§4i, confirmed twice).
- NSClient uploading off.
- ⚠ **Phase 8's extended bolus stays refused and unadvertised throughout.** Two delivery changes at
  once is exactly what §0 forbids.

### The build under test — what it does and how to see it

Built 2026-08-03 to the six decisions recorded in `PORTING_NOTES` §8.6, which were settled before any
code was written. The design decisions are **not** open questions for these steps; what is open is
whether the pump and the phone behave the way the design assumes.

**⚠ Out of the box this build delivers exactly as Phases 1–8 did.** `YpsoBolusSpeed` now defaults to
**Pump's own speed (33 U/min)**, which does not chunk at all. Each step below has to set the speed
by hand first: **Preferences → YpsoPump → Bolus speed**. The list offers *33 U/min / 12 U/min /
3 U/min*; **6 U/min is deliberately absent** until 9a–9e pass, and its absence is not a defect.

**Nothing chunks below one chunk.** At 12 U/min a bolus of 1.0 U or less is a single command, and at
3 U/min anything at or below 0.4 U is. So a test bolus must be *bigger than one chunk* or there is no
sequence to observe.

**Reading the result.** Every sequence writes lines prefixed `CHUNKED:` — one when the plan is made,
one per chunk, one summary:

```
adb logcat -v time PUMP:V PUMPBTCOMM:V YpsoPumpManagerImpl:V '*:S' | grep CHUNKED
```

The summary line carries the three things 9a is decided on — **how many chunks were confirmed, the
phone-side start offsets, and the gaps between the pump's own entry timestamps** — because the total
alone cannot tell a working sequence from no sequence. Prefer the *pump entry* gaps: the phone offsets
say when a command left, not when insulin went in.

**⚠ Expect the spacing to be at or a little over the nominal, never under.** The interval is a
minimum gap between chunk starts; if a chunk's confirmation overruns its slot the next goes out late.
5.6 s where 5.0 was asked for is a pass. Under 5.0 would not be.

**One known consequence to watch for rather than be surprised by:** the connection lock is held for
the whole sequence, so nothing else — status read, TBR, alert check — can reach the pump while it
runs. That is deliberate (it is what stops a command interleaving between chunks) and it is what 9a's
step 2 is looking at. It is capped at five minutes.

## 9a. Two chunks — the smallest thing that is a sequence

**The question:** does a sequence work at all, and does the command queue survive it?

1. Bolus **2 U at 12 U/min** — should be 2 chunks of 1 U, 5 s apart.
2. While it runs, watch whether anything else can reach the pump.

**Confirm:**

- The pump logs **exactly two** `type=2` entries, ~5 s apart, summing to **2.00 U**.
- `bolusDelivered` is 2.0 and `success=true`.
- The reservoir falls by 2.0 U.

⚠ **The check that could be written so it cannot fail:** a 2 U bolus delivered *unchunked* also ends
2.00 U down with `success=true`. **Only the log entry count and their timestamps distinguish the two.**
Read them, not the total.

## 9b. Cancel mid-sequence

**The question:** does `stopBolus` stop the *sequence*, or only the chunk in flight?

1. Bolus **6 U at 3 U/min** — ~15 chunks over ~2 minutes.
2. Cancel from AAPS after ~30 s.

**Confirm:** no further `type=2` entries appear after the cancel; `bolusDelivered` equals the sum of
the entries that did land; `success=false`. **A sequence that keeps going after a cancel is the worst
failure this phase can produce** — the user has asked it to stop and insulin is still going in.

## 9c. Connection lost mid-sequence

**The question:** the one the §0 objection was about. Answer it deliberately rather than discovering it.

1. Bolus **6 U at 3 U/min**.
2. Pull the pump out of BLE range after ~30 s.

**Confirm:** delivery **stops** (chunks are issued by the phone, so no phone means no chunks); the
pump's log contains only the chunks that were sent; and once reconnected, AAPS's record equals that
sum and no more. **Under-delivery reported honestly is a pass. Over-reporting is a failure.**

## 9d. The record, and IOB

Query the database, not the log — §5c-ii's lesson:

```sql
select datetime(timestamp/1000,'unixepoch','localtime') ts, amount, pumpId, isValid, referenceId
from boluses order by timestamp desc limit 20;
```

**Confirm:** the live rows (`referenceId is null`) sum to what the pump delivered, with no duplicates
and no missing chunk. Then **re-read history** and confirm the count does not change — the sync must
be idempotent across a chunked bolus exactly as 7c proved it is for a single one.

## 9e. A chunk the pump refuses

**The question:** what happens when chunk N is rejected — the pump's own max bolus, or an alarm
stopping delivery partway.

Reachable by forcing an occlusion mid-sequence, which this pump raises reliably (§8.2).

**Confirm:** the sequence **stops** rather than continuing to hammer a stopped pump; the amount
reported is what the log shows; and the alarm still reaches AAPS as an urgent notification.

## What cannot be tested here

**Whether the chunk parameters are right for comfort.** 1 U per 5 s is Lotus's number, not a measured
optimum, and comfort is not something a saline bench can assess. Take the manufacturer's figures and
do not invent others.

## Results

| Step | Speed to set | Bolus | Result |
|---|---|---|---|
| 9a two chunks | 12 U/min | 2 U | ✅ **PASSED 2026-08-03** |
| 9b cancel mid-sequence | 3 U/min | 6 U | ✅ **PASSED 2026-08-03** |
| 9c connection lost | 3 U/min | 2 U | ✅ **PASSED 2026-08-04** on the re-run after the fix |
| 9d the record and IOB | — | — | ✅ **PASSED 2026-08-03** |
| 9e a refused chunk | 3 U/min | 4 U | ✅ **PASSED 2026-08-03** |

✅ **Phase 9 is PASSED, 2026-08-04.** 9c failed first time, found a real defect, and passed on the
re-run after the fix — which is the phase working rather than the phase being lucky. `YpsoBolusSpeed`
is now a real four-option preference, 6 U/min included.

**Every run was checked against the reservoir, not against the driver's own account of itself:**

| Run | Driver reported | Reservoir | Basal in window | Agrees |
|---|---|---|---|---|
| 9b 19:14:41 | 2.00 U | 102.76 → 100.75 = 2.01 | ~0.004 | ✓ |
| 9c 19:16:20 | 0.40 U | 100.75 → 100.34 = 0.41 | ~0.014 | ✓ |
| 9e 19:19:08 | 3.16 U | 100.34 → 97.17 = 3.17 | ~0.011 | ✓ |

Run on build **3.4.2.6**, installed 17:06:32. (The labels on the speed dropdown were corrected
afterwards; the delivery code is byte-identical, so these results stand for HEAD.)

### ✅ 9a PASSED — 2026-08-03 17:09:32

2 U at 12 U/min. **Two entries, five seconds apart, 2.00 U.**

```
CHUNKED: asked 2.0U at 12.0 U/min -> 2 chunks of [1.0, 1.0] U every 5s
CHUNKED: chunk 1/2 1.0U sent +0.001s, seq=160910 -> 1.0U
CHUNKED: chunk 2/2 1.0U sent +5.001s, seq=160911 -> 1.0U, pump entry +5s
CHUNKED: 2/2 confirmed, 2.0U of 2.0U, phone starts [0.001, 5.001]s,
         pump entries [5]s apart, seqs [160910, 160911]
deliverTreatment done: asked 2.0U, pump delivered 2.0U, success=true
```

**Adjudicated on the count and the spacing, which is the whole point of the step** — the total was
never going to distinguish anything. The spacing quoted is between the **pump's own entry
timestamps**, not the phone's send offsets.

**Step 2 — nothing else reached the pump while it ran.** The 35-second window of the 3 U/min run
below contains 91 log lines and every one is the bolus machinery itself: bolus writes, bolus-status
frames, history reads. No status read, no temporary basal, no alert check, no history sync. The
sequence held `withConnection` exactly as designed.

**The delivery-mode probe fired once per sequence, not once per chunk** — `probeDeliveryModeOnce`
working, and worth confirming because five extra round trips inside an eight-second budget would
have shown up as spacing drift.

#### ✅ And a second run that was not asked for: 2 U at 3 U/min reproduced Lotus exactly

Run at 17:08:18, before 9a. Five entries `160905`–`160909`, **8, 8, 8, 8 seconds apart**, 2.00 U:

```
CHUNKED: asked 2.0U at 3.0 U/min -> 5 chunks of [0.4, 0.4, 0.4, 0.4, 0.4] U every 8s
CHUNKED: 5/5 confirmed, 2.0U of 2.0U, phone starts [0.0, 8.001, 16.002, 24.004, 32.001]s,
         pump entries [8, 8, 8, 8]s apart, seqs [160905 … 160909]
```

Set beside the capture from mylife Lotus on the same pump earlier the same day — five `type=2`
entries eight seconds apart summing to exactly 2.00 U — **the driver produced the same chunk count
at the same cadence for the same total.** The only difference is where the trim lands: Lotus split it
`0.41 0.41 0.41 0.41 0.36`, this splits it `0.40 ×5`, which is the deliberate choice not to scale the
chunk by how long the previous cycle actually took.

**Reservoir: 107.39 → 105.38 across it = 2.01 U**, the extra 0.01 being basal at 0.3 U/h over the
sequence. **9a's own run is confirmed separately: 105.38 → 103.30 = 2.08 U**, the bolus plus 0.07 U
of basal across the fifteen minutes between the two readings. The second reading only arrived at
17:24, after a reinstall brought AAPS back and it reconnected on its own.

**Phone-side scheduling held to the millisecond**: starts at +0.0, +8.001, +16.002, +24.004, +32.001.
Confirmation fits comfortably inside the slot, so the "due at sequence start + k × interval"
scheduling never had to fall behind. The upper-bound property was therefore **not** exercised — a run
where a chunk overruns its slot has not happened yet, and the ceiling has not been reached either.

### ✅ 9d PASSED — the live rows are right, and they survived a re-read

```
17:09:41  1.0  pumpId=160911     17:08:53  0.4  pumpId=160909
17:09:36  1.0  pumpId=160910     17:08:45  0.4  pumpId=160908
                                 17:08:37  0.4  pumpId=160907
                                 17:08:29  0.4  pumpId=160906
                                 17:08:20  0.4  pumpId=160905
```

Queried on the database, not the log. **Run 1: five live rows summing 2.0 U. 9a: two live rows
summing 2.0 U.** No duplicate `pumpId` among live rows, and — the check that matters for the
per-chunk provisional design — **zero live rows with a null `pumpId`**, meaning every provisional
opened before a chunk was settled to that chunk's own pump sequence. The rows carrying a
`referenceId` are the superseded provisionals, which is 7c's finding and not duplication.

#### The re-read, and why it was a stronger test than the one that was specified

The reinstall at 17:23 restarted AAPS, which reconnected on its own and ran a sync with
**`watermark=160904`** — *below every chunk entry*. So the sync did not merely re-offer entries it had
already handled; it saw all seven **for the first time** and imported them:

```
History sync: importing BolusDelivered seq=160905 … 160911   (7 in a row)
History sync: imported 7 event(s) above 160904; watermark 160904 -> 160911
```

Seven imports onto seven existing records is the case that produces fourteen boluses if the identity
scheme is wrong. **It produced seven.** Re-queried afterwards:

```
17:08:18  0.4  160905        17:09:31  1.0  160910
17:08:26  0.4  160906        17:09:36  1.0  160911
17:08:34  0.4  160907
17:08:42  0.4  160908
17:08:50  0.4  160909
```

Same counts, same totals, no duplicate `pumpId`, no live row with a null one. `syncBolusWithPumpId`
upserted, exactly as §8.5 says it does, across a chunked bolus and not merely across a single one.

⚠ **And the timestamps moved** — from the phone's settle time to the pump's own entry time, which is
also §8.5's documented behaviour. That turns out to matter here for a reason nobody planned: the
rows now record **8, 8, 8, 8 seconds and 5 seconds** in AAPS's own database. The cadence is therefore
attested by three independent things — the driver's log line, the pump's entry timestamps, and the
treatment records — rather than only by the driver's account of itself.

**Two incidental confirmations from the same reconnect.** The profile write AAPS makes after a
reinstall (`[0.30 ×24]`, identical to what the pump held) appended **nothing** to the event log — the
watermark went straight to 160911 — which is §8.5's "a profile write of the *same* profile appends
nothing", holding for a fourth time. And the sync cost **22.3 s for 100 entries behind a 634 ms
probe**, which is §8.5's own pricing.

⚠ **Do not change the logic mid-run to make a step pass.** Record the failure, stop, and fix it
deliberately — that is the rule the earlier phases were run under and it is what makes their results
worth anything.

### ✅ 9b PASSED — the cancel stopped the sequence, not just the chunk

6 U at 3 U/min, 15 chunks planned, cancelled from AAPS at ~32 s.

```
CHUNKED: chunk 5/15 0.4U sent +32.002s, seq=160916 -> 0.4U, pump entry +8s — Bolus stopped after 0.4 U of 0.4 U
CHUNKED: 5/15 confirmed, 2.0U of 6.0U, phone starts [0.0, 8.002, 16.001, 24.001, 32.002]s,
         pump entries [8, 8, 8, 8]s apart, seqs [160912 … 160916] — stopped by the user before chunk 6 of 15
deliverBolus: asked 6.0U, pump delivered 2.0U, success=false
```

**No sixth chunk.** The pump's own log confirms it independently of the driver's account: the next
event entry is `160917`, which is the *following test's* first chunk more than a minute later — the
gap check at 19:19:07 reports `events newest=160917`, so nothing was written between the cancel and
then. And the reservoir moved 2.01 U for five chunks of 0.4 U.

**The cancel landed mid-chunk and that chunk still completed** — `Bolus stopped after 0.4 U of 0.4 U`
is chunk 5 delivering in full while the stop flag was already set. That is correct and worth being
explicit about: **a cancel cannot recall a chunk the pump is already delivering**, because the pump
owns it. What it stops is the *sequence*, which is exactly the distinction 9b was written to test.
The chunk therefore counts as confirmed (5/15, 2.0 U) while the sequence reports `success=false`.

### ✅ 9e PASSED — an occlusion stopped the sequence, and AAPS was told

4 U at 3 U/min, occlusion forced during chunk 8 of 10.

```
CHUNKED: chunk 7/10 0.4U sent +48.001s, seq=160925 -> 0.4U, pump entry +8s
CHUNKED: chunk 8/10 0.4U sent +56.0s, seq=160926 -> 0.36U — Pump delivered only 0.36 U of the 0.4 U requested
CHUNKED: 7/10 confirmed, 3.16U of 4.0U, … seqs [160919 … 160926]
PUMP ALERT: Alert: Occlusion (type 105, seq 160962)
Pump alert 105 (Alert: Occlusion), urgent=true
```

All three of 9e's conditions:

- **The sequence stopped rather than hammering a stopped pump.** Two chunks unsent.
- **The amount reported is what the log shows** — 3.16 U = seven whole chunks plus the 0.36 U partial.
  The reservoir says 3.17 U including basal. The partial chunk's insulin is *counted in the total*
  while *not counted as a confirmed chunk*, which is the §0 split working: report only what the pump
  confirmed, record everything that went in.
- **The alarm reached AAPS as urgent**, `urgent=true`, 20 s after the sequence gave up.

**And Phase 6's machinery interoperated with it unprompted**: the pump went to `mode=STOPPED
rate=0.0U/h` and the driver opened the 0% suspend record (`suspendRecord=open … 59min left of 60`)
without anyone asking. A failed chunked bolus leaving a stopped pump does not leave AAPS counting
basal that is not being delivered.

**This is also the first run where confirmation overran its slot.** Chunk 8 was *sent* on time at
+56.0 s but took 13.4 s to confirm, because `findBolusRecord` retried against an entry the pump was
still revising. The sequence absorbed it and reported honestly. The "upper bound, never a floor"
property is therefore no longer untested — it took a failure to exercise it.

### ⚠ 9c PARTIAL — delivery is right; the record keeps a phantom that can never be reconciled

2 U at 3 U/min, pump taken out of range after chunk 1.

```
CHUNKED: chunk 1/5 0.4U sent +0.0s, seq=160917 -> 0.4U
GLB write to 669a0c20-0008-969e-e211-fcbecc3b7bc5 failed (GATT status=133)
Bolus refused: The pump's event log could not be read, so a bolus could not be confirmed — nothing was sent
CHUNKED: 1/5 confirmed, 0.4U of 2.0U … seqs [160917]
deliverBolus: asked 2.0U, pump delivered 0.4U, success=false
BLE disconnected (reason=10)  → reconnect attempts → Failed to connect: reason=-1
```

**Two of 9c's three conditions passed.** Delivery stopped — the reservoir moved **0.41 U**, which is
chunk 1 and basal, so chunk 2 genuinely never went in. The pump's log contains only `160917`.

**The third failed.** *"AAPS's record equals that sum and no more."* It does not:

```
19:16:20   0.4   pumpId=160917     ← real
19:16:28   0.4   pumpId=(null)     ← phantom: the provisional for a chunk that was never sent
```

**0.8 U recorded for 0.4 U delivered.** The *report* was honest (`pump delivered 0.4U,
success=false`), so §0's reporting rule held. The IOB record did not.

#### ⚠⚠ Why this is a defect and not §4g working as intended

§4g says an unconfirmed bolus is left recorded in full because *"deleting it would assert that
nothing was delivered, which is the one thing not known."* **Here it is known.** The failure was in
`newestEventSequence()` — the baseline read, which happens *before* `suspendStartBolus`.

⚠ **And that is established from the BLE traffic, not from the driver's own message.** "Nothing was
sent" is the code's account of itself, which is the class of evidence this file distrusts on
principle. The raw log settles it independently — the entire 9c run contains **one** bolus write:

```
19:16:20.858  Starting bolus: 0.40U (scaled=40), duration=0min, type=1, layout=UPSTREAM
19:16:20.860  bolus: writing 15B to …e18b… as payload+CRC: 28 00 …           ← chunk 1, the only one
19:16:28.513  Provisionally recorded 0.4U under temporaryId=1785741388482    ← chunk 2's provisional
19:16:29.918  readHistoryCount(…cb3b…): 3000                                  ← baseline read begins
19:16:35.441  GLB write to …cc3b… failed (GATT status=133)                    ← and dies, 5.5 s later
19:16:35.442  Bolus refused: … nothing was sent
```

No second `Starting bolus`, no second `bolus: writing`. For comparison the 9e run shows **eight**
writes for its eight attempted chunks. **No bolus command was ever written for chunk 2**, and the
0.4 U in the database corresponds to no insulin that ever existed.

Worth noting the shape of the failure too: the provisional was opened on time at +8.0 s, then the
GLB index write **hung for 5.5 seconds** before GATT gave up with status 133. The slot was consumed
by a dying link, not by slow confirmation.

So this is a case §4g's rule does not actually cover — it was written for *unknown* outcomes, and
this branch has a **known** one. Three things make it worth fixing rather than tolerating:

- **It is permanent.** No pump entry will ever exist for that chunk, so no history sync can ever
  reconcile it. Unlike the transient window §4g describes, this row stays in the database for ever.
- **Chunking multiplies the opportunities.** Every chunk boundary re-runs the baseline read, so a
  fifteen-chunk bolus has fifteen chances to hit it where a single bolus has one.
- **It is silent.** IOB is simply wrong by up to one chunk, and nothing announces it.

⚠ **It fails in the safe direction** — over-stated IOB makes AAPS dose *less* — and the magnitude is
capped at one chunk (0.4 U at 3 U/min, 1.0 U at 12). Nothing was mis-dosed. But "safe direction" is
why it was not noticed, not a reason to keep it.

⚠ **The same defect exists on the unchunked path**, where it is *rarer but larger*: a baseline-read
failure there leaves a provisional at the **full** bolus amount. Chunking made each occurrence
smaller and more frequent, and that is what made it visible.

**Not fixed during the run** — §8.6's rule. Fixed afterwards and re-run.

#### ✅ 9c re-run PASSED — 2026-08-04 05:14

2 U at 3 U/min, pump pulled out of range. **The link died in the same place**, during chunk 4's
baseline read — `readHistoryCount` at 05:14:44.1, GLB write failing with GATT 133 at 05:14:49.3 after
a 5.2 s hang, the same shape as the first run's 5.5 s. That was not guaranteed: the branch left
deliberately unchanged is the one where the *write* dies, and pulling the pump catches whatever is in
flight.

The same failure at the same point, before and after:

| | Pre-fix, 08-03 19:16 | Post-fix, 08-04 05:14 |
|---|---|---|
| `Provisionally recorded` for the failed chunk | present | **absent** |
| `Starting bolus` for the failed chunk | absent | absent |
| Provisionals opened | 2, for 1 sent chunk | **3, for 3 sent chunks** |
| New live row with null `pumpId` | yes | **none** |

```
05:14:49.294  Bolus refused: The pump's event log could not be read … nothing was sent
CHUNKED: 3/5 confirmed, 1.2U of 2.0U … seqs [160975, 160976, 160977]
deliverBolus: asked 2.0U, pump delivered 1.2U, success=false
```

Database: three live rows, `160975`–`160977`, 0.4 U each. **Reservoir 93.50 → 92.28 = 1.22 U**
against 1.20 U claimed plus 0.006 U of basal — agreement inside the pump's own 0.01 U resolution.

⚠ **The control that makes this convincing is the old phantom.** The 0.4 U null-`pumpId` row from
19:16:28 on 08-03 is still in the database, so the query demonstrably finds such rows when they
exist. None was created this time under the same failure.

#### ⚠ [fixed] And the re-run caught the instrument lying

```
CHUNKED: chunk 4/5 0.4U sent +24.0s, seq=null -> 0.0U, pump entry +8s — … nothing was sent
                                                       ^^^^^^^^^^^^^^
```

Chunk 4 produced **no** pump entry, and the line reported a gap of 8 s — stale, carried over from
chunks 2→3. The test asked whether the *list* held two entries rather than whether **this** chunk had
contributed one.

**It did not corrupt any result**: the summary line's `seqs` list, the confirmed count, the database
and the reservoir are all independent of it and all agree. But this is a phase adjudicated on entry
spacing, and a per-chunk line that invents a spacing for a chunk with no entry is precisely the
failure this file keeps recording — a check reading back something other than what it claims to
measure — this time in the instrument rather than in the driver. Fixed: the gap prints only when the
chunk being logged produced an entry and a previous one exists.

### ⚠ Unexplained: sequence numbers that appear in neither log

Recorded as an observation, not an explanation, because guessing at this pump's numbering is what
made upstream's delivery-mode table wrong.

**`160918` appears nowhere** — not imported, not skipped, not in any window dump, in the whole
capture. It sits between chunk 1 of the 9c run (`160917`) and chunk 1 of the 9e run (`160919`).
Likewise the watermark jumped **`160917` → `160962`** while the event log's newest was `160926`, so
**`160927`–`160961` were allocated and never seen either**. The gap check reported `0 missed` both
times, because it compares the oldest entry in a window against the watermark and cannot see holes
*inside* the range it skips over.

**None of it is insulin** — the reservoir accounts for every unit across all three runs to within
0.01 U, so whatever these are, they did not deliver. The consequence is narrower: the watermark has
advanced past them, so if they ever do appear in a window read they will be skipped as "at or below
watermark". §8.8's third, never-read history log (`YpsoPumpUuids.System`) is the obvious suspect and
**has not been checked**, which is why this is written down rather than concluded.

## Phase 9 is passed when

9a shows the **right number of log entries at the right spacing** (not merely the right total); 9b
stops the sequence on cancel; 9c under-delivers honestly on a lost link; 9d's live rows sum correctly
and survive a re-read; and 9e stops on a refused chunk.

**Then, and not before**, `YpsoBolusSpeed` becomes a real preference for the first time — and it needs
its fourth option, **6 U/min**, which the current three-way list is missing (§8.6).

### ✅ All five passed, 2026-08-04 — and the gate has been opened

`YpsoBolusSpeed` now offers all four of Lotus's options with Lotus's parameters, and is a real
preference for the first time since it was introduced.

⚠ **The default is still `unlimited`.** Passing Phase 9 licenses the *option*, not a change to how
the driver behaves for someone who has not chosen one. Nothing chunks unless it is asked to.

**9c is the reason to trust the rest.** It failed on the first attempt, and it failed on the one
condition of the five that no amount of watching a successful run would have exposed — the record
left behind by a chunk that was never sent. Four steps passing on the first attempt is what a phase
looks like when it is not asking hard enough; this one asked.

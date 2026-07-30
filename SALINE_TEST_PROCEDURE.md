# YpsoPump driver — saline test procedure

Hardware procedures for the YpsoPump driver's write path. Companion to `PORTING_NOTES.md`,
which records what the pump has actually been observed to do.

**Phase 1 has passed on hardware. Phases 2–4 have procedures but no code.** They are written up
here so each phase's confirmation check is decided while nobody is under pressure to accept a
weaker one. Their code is deliberately not written yet: each removes a refusal that lets insulin
move, and each gets its own saline test.

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

## Phase 3 — temporary basal (not yet implemented)

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

## Phase 4 — basal profile write (not yet implemented)

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

Still not insulin. What remains is in `PORTING_NOTES` §8: an open-loop soak on saline, deciding
how `sincePosition` filters history entries already seen, and turning
`YpsoSyncHistoryToAaps` on — which is its own gate, because getting it wrong corrupts IOB
silently and persistently rather than visibly.

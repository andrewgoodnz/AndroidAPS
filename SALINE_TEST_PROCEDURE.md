# YpsoPump driver — saline test procedure

Hardware procedures for the YpsoPump driver's write path. Companion to `PORTING_NOTES.md`,
which records what the pump has actually been observed to do.

## ⏳ Everything still to run — read this first

As of 2026-08-04, **every phase from 1 to 9 has run on hardware and passed.** The driver bonds,
authenticates in plaintext, reads everything it needs, and writes the clock, a standard bolus, a
temporary basal, the 24-slot basal profile and a chunked slow bolus. History sync is **on by
default**, and a quiet poll costs ~580 ms rather than 20 s.

✅✅ **6b passed on 2026-08-03, and was re-confirmed the same day on HEAD after a flash** — seven
renewal cycles across two builds, **zero uncovered time**, decided on the database.

✅ **Phase 9 (chunked bolus) passed 2026-08-04.** "Bolus speed" turned out to be a loop in the phone
rather than a pump setting; 9c failed first, found a permanent phantom IOB record, was fixed and
re-run clean. Phase 8's extended bolus works but lands in the wrong table, and is superseded.

⏳ **Phase 10, the interrupted bolus, is written and unrun.** It is items E and F below, and unlike the
phases above it its subject is not a pump capability — it is what AAPS's own database holds after the
phone is taken out of the loop mid-delivery. **The code predicts a specific failure there and Phase 10
says so in advance.**

⚠⚠ **Phase 11 now comes before it, and Phase 11 was not written from the code.** It was written from a
pump that refused a command while insulin was going into a person. Nothing on this page predicted it,
because every phase from 1 to 9 tested capabilities one at a time and this is a *combination* — a
temporary basal replaced while another is running. That is the shape of every expensive finding in
these notes, and it took a real loop to produce it.

⚠⚠ **2026-08-04, later the same day: the driver ran on insulin, on a person, and the trial was ended
by a defect that no bench phase had reached.** The pump **refused a 0% temporary basal while a 400%
one was running** — twice. A zero temp accompanies every SMB in oref1 and is the loop's instrument for
backing off insulin, so this is a loss of control rather than a missing feature. IOB accounting stayed
correct throughout and nothing was dosed blind. The pump is back on saline and the phone is back on
its previous pump. **Phase 11 is now the first thing to run, ahead of Phase 10**, and until it passes
this driver is not fit for closed-loop use with insulin.

**Still to run, most valuable first:**

| # | Test | Why it matters | Where |
|---|---|---|---|
| **0** | ⚠⚠ **Phase 11 — the TBR that could not be replaced** | 📝 **Written 2026-08-04, not yet run. The only item here found by real insulin use rather than by reading.** The pump refused `0%` while `400%` was running; `tbrPercentRange` is `0..500` so the refusal is the pump's, not a guard. Two hypotheses — 0% is never accepted, or no TBR can be replaced while one runs — and a 2x2 that separates them. Cancel (100%) works, so if it is the latter the fix is cancel-then-set | **Phase 11**, §4h |
| A | ~~**6b — the renewal fix**~~ | ✅✅ **CLOSED 2026-08-03, verified twice.** 2h33m on the 08-02 build (4 cycles) and then re-confirmed on **HEAD 3.4.2.6** after a flash (3 cycles), with **zero uncovered time at every transition including across the reinstall**. Adjudicated on `temporaryBasals.duration`. The Hold→Renew boundary was seen on real polls (39 min left held, 20 min left renewed) | **6b**, §8.6 |
| B | ~~**Fix `insertAnnouncement`'s dedupe key**~~ | ✅ **CLOSED 2026-08-04.** The alert path now calls `insertTherapyEventIfNewWithTimestamp(event.timestamp, ANNOUNCEMENT, …)`, so the key is `(type, the pump's entry time)` and re-reading a window is a genuine no-op. It also brings the path under `confirmActivePump(timestamp, …)`, which `now()` had been sailing past. Fixed inside `pump/ypso`, adding no shared-file surface | §8.5 |
| C | ~~**Phase 5c — cartridge out and priming**~~ | ✅ **CLOSED 2026-08-03.** Cartridge out reads `byte0=3` with the reservoir `FF FF FF FF` (−1) — the first sighting of reservoir `-1`, handled correctly. The *priming* row is recorded as **unreachable by this method**: liveness cannot be proved because the reservoir reads −1 throughout, so a mid-prime reading is byte-identical to one taken after it | **Phase 5c**, §8.3 |
| D | ~~**Phase 5e — reservoir empty mid-delivery**~~ | ✅ **CLOSED 2026-08-03.** Reads `byte0=3` with the reservoir a **known `0`** — not the `-1` of a cartridge change, so the pump does distinguish empty from absent. Alert count 147 → 148, `104 Cartridge Empty`, urgent. **Phase 5's table is complete** | **Phase 5e**, §8.3 |
| E | **Reconnect mid-bolus** | ❌⚠ **FAILED 2026-08-09 — the predicted double-count, confirmed.** An 8 U bolus interrupted 5.4 s in left an orphaned provisional (`id=195`, no `pumpId`) and the history sync inserted `id=198` beside it: **two live rows, 16.00 U for one 8 U bolus**. The driver's own `success=false` is correct per §0 and AAPS alarmed; it is the record that fails. Fix deliberately — see Phase 10 results. Original entry: 📝 *Written as Phase 10a/10c, 2026-08-04, not yet run.* Take the link away during a bolus. The pump finishes regardless; what is untested is whether AAPS's *record* settles. ⚠ The code says it does **not** — an unsettled provisional has no `pumpId`, so the history sync cannot find it and inserts a second row. Phase 10 states that prediction and then goes to look | **Phase 10**, §4g |
| F | **Kill AAPS mid-bolus** | ❌⚠ **FAILED 2026-08-09, with one good answer inside it.** ✅ The provisional **survived** `am force-stop` (`id=199`, live, queried with AAPS dead), so `addBolusWithTempId` is durable and the §4g window is genuinely covered — the worst predicted outcome is ruled out. ❌ But nothing reconciles it: **two live rows, 16.00 U**. Same failure as E, so one fix serves both. Original entry: 📝 *Written as Phase 10b/10d, 2026-08-04, not yet run.* Force-stop during delivery. The provisional is a committed row so it should survive; what is untested is whether anything ever reconciles it. This decides whether `PumpSyncStorage` is needed, and Phase 10b records what each outcome would mean **before** the run | **Phase 10**, §4g |
| G | **Change the phone's timezone** | ⏳ **UNTESTED, and going into therapy use untested.** The driver should queue a clock sync and the pump's clock should follow. `canHandleDST()` is false and `timezoneOrDSTChanged` queues the write, so the path exists and has never been exercised end to end. The failure mode if it is wrong is history timestamps an hour out, which §5 has already produced once | §8.6 |
| H | **A bolus while a TBR runs** | ✅ **CLOSED 2026-08-09 — accepted in both directions.** 1.0 U under a running 0% (4.11 s) and under a running 300% (3.93 s); the TBR survived both, mid-delivery and after, and each cost exactly 1.00 U. A 0% temp does **not** make the pump report itself as not delivering, so the alternative refusal mechanism is disproved rather than merely unseen. The history below is kept because the reasoning matters: ⏳ *UNTESTED, and the "low risk" that used to be written here is withdrawn.* The reasoning was that both operations work alone and both are identified by sequence — which is *exactly* the reasoning that would have rated "replace a running TBR" low risk the day before it failed on a person. Nothing on the command × pump-state axis is low risk until it has been run. 📝 **Written as Phase 12a/12b, 2026-08-09, not yet run.** ⚠ It does **not** need SMB — a manual bolus exercises the same axis, so the Nightscout BG source blocking SMB never blocked this | **Phase 12**, combination matrix |
| H2 | **A basal profile write while a TBR runs** | ✅ **CLOSED 2026-08-09 — the write succeeded and both calculations agreed.** All 24 slots went 1.26 → 1.00 under a running 200%, read back and confirmed; the TBR survived with its countdown intact and the pump then reported `rate=2.0U/h`, exactly the new base × 200%. The agreement §4h could only argue for is now observed. Original entry: ⏳ *UNTESTED, identified 2026-08-04.* The delivered rate does change across the write, because the pump applies the percentage to the new base. It should stay *consistent*: the driver records the TBR as a percentage (`isAbsolute = false`), so AAPS applies the same percentage to the same new profile, and `writeBasalProfile` verifies all 24 slots. **Nothing checks for a running TBR before writing, though**, and the agreement is two independent calculations rather than an enforced invariant. Realistic trigger: an exercise profile change landing while the loop holds a TBR. 📝 **Written as Phase 12c, 2026-08-09, not yet run** | **Phase 12**, combination matrix, §4h |
| H3 | ⚠ **6e — stop the pump while a real TBR runs** | ⏳ **Written, unrun, no pass marker.** The procedure calls it "the interaction with the least analysis behind it" and says the row exists to be observed rather than confirmed. After Phase 11, an unanalysed interaction between the suspend record and a running TBR is not a curiosity. 📝 **Restated with explicit confirmations as Phase 12d, 2026-08-09, not yet run** | **Phase 12**, 6e |
| I | **A temp basal across an hour boundary** | ⏳ **UNTESTED.** The same percentage becomes a different absolute rate. Inherent behaviour rather than a defect, but worth seeing once | Phase 3 |
| J | **Restore a profile you care about** | ⏳ **UNTESTED.** A write you cannot reverse is not finished. Matters at the moment of switching pumps, not during steady use | Phase 4 |
| K | **Chunked bolus for comfort** | ✅ **DONE — Phase 9 passed on hardware 2026-08-04.** "Bolus speed" is a loop in the phone, not a pump setting: Lotus sends ordinary `type=2` boluses on a timer — **1 U/5 s = 12 U/min, 0.5 U/5 s = 6, 0.4 U/8 s = 3** — confirmed in the pump's log (five entries, 8 s apart, summing to 2.00 U). Needs no new pump capability. Built to six design decisions written down before the code; 9c failed first, found a permanent phantom IOB record, was fixed and re-run clean | **Phase 9**, §8.6 |
| L | **The extended bolus** | ⚠ Works, 15-min minimum, lands in `boluses` as NORMAL and its log entry carries no duration (8a/8c passed/found 2026-08-03). Superseded as the comfort route by K, which hits 12 U/min where this floors at 0.67 | **Phase 8**, §8.6 |

## ⚠ The combination matrix — what has only ever been tested alone

**Phase 11 exists because Phases 1 to 9 each removed one refusal and tested that capability by
itself.** Set a temporary basal: passed. Cancel one: passed, repeatedly, on several days. *Replace*
one: never tried, and it is the thing that failed on a person. This section lists the rest of that
blind spot, so the next one is found on saline instead.

**The axis is `command × the pump's state when it arrives`, not command × command.** Two writes do not
overlap — the AAPS command queue serialises them and this driver holds the connection for each. What
*does* persist between commands is the **pump's** state: a temporary basal keeps running, the pump
stays stopped, an alarm stays raised. That is where the untested ground is.

| Command ↓ / pump state → | idle, running | **TBR running** | stopped | alarming |
|---|---|---|---|---|
| standard bolus | ✅ Phase 2 | ⏳ **item H** | ⏳ | ⏳ |
| chunked bolus | ✅ Phase 9 | ⏳ | ⏳ | ✅ 9e (occlusion mid-chunk) |
| **set TBR** | ✅ Phase 3 | ❌ **REFUSED — Phase 11** | ⏳ | ⏳ |
| cancel TBR | ✅ Phase 3 (no-op) | ✅ Phase 3 | ✅ 6f | ⏳ |
| **basal profile write** | ✅ Phase 4 | ⚠ ⏳ **see below** | ⏳ | ⏳ |
| clock write | ✅ Phase 1 | ⏳ | ⏳ | ⏳ |
| pump stopped by hand | — | ⚠ ⏳ **6e, unrun** | — | — |

⏳ means nobody has tried it, not that it is expected to work.

**Three of these deserve promoting above "someday", and one of them was already written down and
dismissed:**

1. ⚠ **A basal profile write while a temporary basal is running** — and the first draft of this entry
   overstated it, which is worth leaving visible. Rewriting the 24 slots under a running TBR **does**
   change the delivered rate immediately, because the pump applies the percentage to whatever base it
   now holds. The claim that it does so *invisibly* was wrong: the driver records the TBR with
   `isAbsolute = false` and `rate = percentage`, so **AAPS's own model applies the same percentage to
   the same new profile** and the two stay in agreement. `writeBasalProfile` also confirms all 24
   slots read back and drops its cache on failure, so a partial write cannot quietly leave the two
   sides disagreeing.

   **What remains worth one run** is that no code anywhere checks for a running TBR before writing the
   profile, and the agreement above is a property of two independent pieces of arithmetic rather than
   anything enforced. The realistic trigger is ordinary: a profile percentage change for exercise
   (~4–5× a week per §8.5) landing while the loop happens to hold a temporary basal. Watch the
   effective rate on the pump and the rate AAPS believes, across the write, and confirm they agree.
   Item I is the same phenomenon arriving slowly at an hour boundary and is inherent; this one arrives
   in a second, at a moment AAPS chooses.

2. ⚠ **6e's second half — stop the pump while a real TBR is running.** It is written, it has no pass
   marker, and the procedure describes it in its own words as *"the interaction with the least
   analysis behind it … this row exists to be observed, not confirmed."* Given Phase 11, an unanalysed
   interaction between the suspend record and a running TBR is no longer a curiosity.

3. **Item H — a bolus while a TBR runs — is rated "Low risk, real" and that rating should be
   withdrawn.** It was reached by the same reasoning that would have rated *replace a running TBR* low
   risk: both operations work alone, both are identified by sequence, so the pair looks safe. Phase 11
   is the counter-example. **Nothing on this axis is low risk until it has been run**; the honest
   entry is "untested", with no adjective.

📝 **All three of the above are now written up as Phase 12 (2026-08-09), not yet run.** Item H is 12a
and 12b, H2 is 12c, and 6e's second half is restated with explicit confirmations as 12d.

**What this matrix does not cover** is the pump's state changing *during* a command rather than
before it — an alarm raised mid-bolus, a cartridge pulled mid-write. Phase 9e reached one such case by
occluding mid-chunk. The rest are harder to time by hand and are not claimed here.

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
| **Does a cannula-priming event exist?** | ✅ **YES — reopened and answered 2026-08-05.** This said "no `type=25`, anywhere" for two days. A cartridge top-up with the pump's own **cannula fill** step produced `type=25` at 0.3 U and a real `CANNULA_CHANGE`; **CAGE restarts.** The 2026-08-03 "deliberate cannula prime" that proved otherwise was **30 U — a tubing fill**, so it tested the wrong action (§5c-ii) |
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

> ✅ **ANSWERED 2026-08-05: type 25 exists and CAGE restarts.** The procedure below is kept because it
> is what was run, and because its decision table is the reason a wrong answer stood for two days —
> every row assumed the cannula would be primed, and no row asked *how much*. **The action that
> produces `type=25` is the pump's own cannula-fill step (a few tenths of a unit), not a large prime.**
> Jump to *"REOPENED 2026-08-05"* below for the evidence.

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
| **Does `type=25` exist? (5c-ii)** ✅ **REOPENED AND RE-CLOSED 2026-08-05** | **YES — it exists.** The 2026-08-03 answer below was wrong because it tested the wrong action. See *"reopened"* underneath it | | | | |

#### ⚠⚠ [verified] REOPENED 2026-08-05 — type 25 exists, and the 08-03 test primed the wrong thing

**Everything in the 2026-08-03 entry below is accurate except its conclusion.** A cartridge top-up on
2026-08-05, in which the operator went through the pump's own **cannula fill** step, produced:

```
06:54:20  type=16   rewind             → INSULIN_CHANGE   (pumpId 161526)
06:55:04  type=4    2.0 U prime        → below the tube-change threshold, correctly not recorded
06:55:11  type=25   0.3 U cannula fill → CANNULA_CHANGE   (pumpId 161530)
```

`CANNULA_CHANGE` has exactly one source in `YpsoEventPolicy.consumableEventFor` — `type=25` — so its
presence in `therapyEvents` is proof the event arrived. **CAGE restarts on this pump.**

⚠ **Why the earlier answer was wrong, which is the part worth keeping.** The 08-03 run used a
**30 U "cannula prime"**. Thirty units is a *tubing* fill; a cannula fill is a few tenths. So the test
performed a second line prime, correctly got `type=4`, and concluded from it that the pump cannot
distinguish the two primes. **It never exercised the cannula-fill step at all.** The pump had always
been able to tell them apart — `type=4` tubing, `type=25` cannula — and the missing thing was a button
press, not a capability.

This is the fifth time in these notes a confident negative has turned out to be a statement about the
*inputs* rather than about the pump. The tell was available in the data the whole time: 30 U is
visibly a line volume, and it sat in the table next to a 14.35 U prime labelled "tubing" without
anyone asking why a cannula would need twice as much.

**What this changes:** site changes no longer have to be recorded by hand *provided the operator
completes the cannula-fill step*. ⚠ And the converse — filling a cannula for any other reason, such as
clearing a line after a cartridge top-up, **also restarts CAGE**, so a site age can read younger than
the site really is. That is the pump's semantics, not a decode fault.

#### ❌ The superseded entry — 5c-ii "closed" 2026-08-03, wrong conclusion, kept for its lesson

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

# Phase 8 — the extended bolus ⚠ **8a RAN 2026-08-03; the rest stands unrun, and deliberately so**

> **Status, 2026-08-04.** Only **8a** was run. It answered the question that mattered — the pump
> *does* extended boluses, enforces a 15-minute minimum, and logs them under its own event type
> carrying **no duration**, so one cannot be reconstructed from history alone (§8.6). At a 15-minute
> floor a 10 U dose is 0.67 U/min, far slower than the ~12 U/min this was wanted for, so **Phase 9's
> chunked bolus superseded it as the comfort route** and the remaining steps were never run. The
> extended bolus stays **refused and unadvertised**, and nothing depends on it. This is a decision,
> not an unfinished job — see §8.6.

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

# Phase 9 — the chunked bolus, for comfort ✅ **PASSED 2026-08-04**

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

---

# Phase 10 — the interrupted bolus (items E and F)

**Written 2026-08-04, unrun.** This is the last phase before insulin, and it is the only one whose
subject is not a pump capability. Everything the pump does here is already known and is not in
question: **the pump owns a bolus once it has been told, and finishes it whether or not the phone is
still there** (§4g). What is untested is whether **AAPS's record of that bolus settles correctly**
when the phone is taken out of the loop partway through.

That matters more than it sounds. A driver that mis-records an interrupted bolus is not wrong
occasionally — it is wrong every time the link drops or the phone is killed during a delivery, which
on a phone carried around all day is not a rare event. And IOB that is wrong in the **stacking**
direction is the hypo direction.

## ⚠ The prediction, written before the run

Phase 9c's lesson was that the expensive failures are the ones no successful run can expose, so this
phase states what the code says will happen and then goes to look. **Read this before running, and
then judge the run against the database rather than against this text.**

`openPendingBolus` calls `PumpSync.addBolusWithTempId`, which is a **committed row in the `boluses`
table** — not an in-memory pending list. It carries `temporaryId` set, `pumpId` **null**, and the
amount *requested*. `settlePendingBolus` is what later promotes it: same row, amount corrected to
what the pump gave, `pumpId` filled in with the pump's sequence.

If `settlePendingBolus` never runs, nothing else in AAPS will do it. **The whole app contains no
other consumer of `temporaryId` on a bolus** — a grep finds the interface, the DAO and this driver,
and nothing that reconciles an orphan. And the history sync cannot: `SyncPumpBolusTransaction` looks
the row up with `findByPumpIds(pumpId, …)`, the orphan has no `pumpId`, so the lookup misses and the
sync **inserts a second row** for the same physical bolus.

So the predicted failure, for both E and F, is one bolus and **two live rows**:

| | `amount` | `temporaryId` | `pumpId` | Where it came from |
|---|---|---|---|---|
| the orphaned provisional | what was **requested** | set | **null** | `openPendingBolus`, never settled |
| the history-sync import | what the pump **gave** | null | set | `syncHistoryToAaps` at the next status read |

Both are `isValid=1` with `referenceId` null, so **both count towards IOB**. A 10 U bolus would read
as ~20 U of insulin on board.

This prediction is the reason the phase exists rather than a reason to skip it. It could be wrong in
either direction: the reconnect may cover the window more often than the code suggests, the
force-stop may not leave the row where it is expected, or the double row may turn out to be
short-lived for a reason nobody has thought of. **Hardware decides. Nothing here is a pass until the
database says so.**

## Before starting

- Saline, off-body, cannula to air. Full cartridge, and note the reservoir reading before each run.
- Study phone (Lotus) **off** — it writes the basal profile unprompted on connect (§4i).
- NSClient uploading off.
- **Bolus speed set to `Unlimited — no splitting`**, which is the shipped default and the path the
  main phone will run. 10d repeats one step chunked; the rest are deliberately about the single
  command, because that is what an interrupted therapy bolus will be.
- logcat running in a restart loop **before the pump is touched**, per the instrumentation notes:
  `adb logcat -v time PUMP:V PUMPBTCOMM:V YpsoBleManager:V YpsoPumpManagerImpl:V "*:S"`

### ⚠ This phase does not need SMB, and never did

Recorded 2026-08-09, because the belief that it did is what kept the phase unrun for five days. With
glucose arriving from Nightscout, `isAdvancedFilteringEnabled` is false and AAPS constrains SMB off,
so **the loop cannot originate a bolus on this rig**. That blocks the loop from making one; it does
not block *you*. Every step here — 10a through 10e — is a bolus you press yourself in the AAPS bolus
dialog, and the interruption is applied by hand or by `adb`. The same is true of Phase 12a/12b.

The top open question in this whole document has been runnable the entire time.

### Why a 10 U bolus, and not 2 U

The prompt for item E says a 2 U bolus. At the pump's own ~33.3 U/min a 2 U bolus is **3.6 seconds**
of delivery, and `deliverOneBolus` goes to the log for its answer 3 s after that — so the whole
window a tester has to interrupt is about **6.6 seconds**, most of it spent walking. Three attempts
at the Phase 5c priming reading already missed a window of that size.

**Use 10 U at unlimited speed: ~18 s of delivery and a ~21 s window.** That is long enough to be hit
by hand, and nothing about the mechanism under test changes with the amount — a bigger number makes
the *record* discrepancy easier to read, not the behaviour different. Saline, off-body, so 10 U costs
a reservoir reading and nothing else.

### Two ways to take the link away, and why the scripted one is primary

- **`adb shell svc bluetooth disable`** on the phone. Instant, repeatable, and the tester chooses
  exactly how long the outage lasts by when they re-enable it. It is also *harsher* than walking
  away: with the adapter down, `ReconnectManager`'s attempts cannot succeed at all, so the outage
  length is controlled rather than hoped for.
- **Physical range loss** — carry the pump to the far end of the house, or shut it in a metal tin.
  This is what actually happens in use, so it is worth seeing once (10c), but it cannot be timed to
  a six-second window and RSSI decays rather than cutting.

Run the scripted version first. It is the one that can be repeated identically after a fix.

## 10a. The link dies mid-delivery and comes back — item E

**The question:** the pump finishes the 10 U regardless. Does AAPS end up holding a record of 10 U,
or of 20?

1. Record the reservoir. Pull a **baseline database** (see *The query*, below).
2. Bolus **10 U** from AAPS at unlimited speed.
3. **6 seconds** after the dialog starts moving, `adb shell svc bluetooth disable`.
4. Leave it off for **90 seconds** — comfortably past the ~21 s window and past
   `ReconnectManager`'s first two attempts (5 s, then 10 s), so the confirmation cannot succeed.
5. `adb shell svc bluetooth enable`. Wait for AAPS to reconnect.
6. Tap **Refresh** on the YPSO tab (`adb shell input tap 140 523`) and let one status read complete,
   so `syncHistoryToAaps` runs.
7. Pull the database and run *The query*.

**Confirm — and every one of these is a separate question:**

- **Delivery.** The pump's event log holds **one** `type=2` entry of 10.00 U, and the reservoir has
  fallen by ~10 U. This is the control on everything else: if the pump did not deliver 10 U the run
  says nothing about records.
- **What AAPS reported.** `success=false` is *correct* here and is not a failure of the step — the
  driver could not confirm, and §0 forbids reporting more than the pump confirmed. A `success=true`
  on an unconfirmed bolus **is** a failure.
- **⚠ The row count.** How many live rows (`referenceId is null`) exist for this one bolus, and what
  do they sum to? **One row summing to 10 U is a pass. Two rows summing to 20 U is the predicted
  failure.**
- **IOB.** Read the AAPS home screen. It is the number the loop will act on, and it is the reason
  the row count matters.

## 10b. AAPS is killed mid-delivery — item F

**The question:** the provisional record is written before the pump is told. Does it survive the
process dying, does it keep counting, and does anything ever reconcile it?

This is the step that decides whether `PumpSyncStorage` is needed.

1. Reservoir, baseline database.
2. Bolus **10 U** at unlimited speed.
3. **6 seconds** in: `adb shell am force-stop info.nightscout.androidaps`.
4. Confirm the process is gone (`adb shell pidof info.nightscout.androidaps` returns nothing) and
   that **the pump is still delivering** — the pump's own screen shows the bolus running. That
   observation is the whole premise of §4g and is worth seeing once with your own eyes.
5. Wait for the pump to finish. Restart AAPS.
6. Let it reconnect; tap Refresh; let one status read complete.
7. Pull the database and run *The query*.

**Confirm:**

- **The provisional survived the kill.** A row with `temporaryId` set and `pumpId` null, amount 10.0.
  If it is *absent*, that is a different and worse finding than the predicted one — it would mean the
  window §4g exists to cover is not covered at all, and the insulin is invisible to IOB.
- **It is still counting.** `isValid=1`, `referenceId` null, and it appears in AAPS's treatments.
- **⚠ What the sync then did.** One row or two, and the sum.
- **IOB on the home screen** after the status read.

**What the outcomes mean, decided in advance:**

| Outcome | What it says about `PumpSyncStorage` |
|---|---|
| One row, 10 U | Not needed. The committed row and the sync already converge. Record that and move on |
| Two rows, 20 U | Something must reconcile the orphan. `PumpSyncStorage` is *one* answer — it persists pending entries and settles them after a restart. A narrower one is for the driver to look for an unsettled provisional at the next connection and settle it from the pump's log, or invalidate it. **Decide deliberately; do not reach for the framework because it is there** |
| No provisional row at all | Neither — this is a different defect, in `addBolusWithTempId`'s durability, and it has to be understood before anything is built on top of it |

## 10c. Real range loss, once

Repeat **10a** with the phone's Bluetooth left alone and the *pump* carried out of range — far end of
the house, or a closed metal tin — 6 s into a 10 U bolus, returned after 90 s.

**Why it is separate:** `svc bluetooth disable` tears the adapter down; range loss is a link timing
out with the adapter still up, and the two reach `ReconnectManager` differently. This is the one that
happens in life. If 10a passed and this does not, the difference is the finding.

## 10d. The same interruption on a chunked sequence

Only if 10a and 10b are settled first.

Bolus **6 U at 3 U/min** (~15 chunks over ~2 minutes) and force-stop AAPS 40 s in.

**Why it is worth a step of its own:** a sequence opens **one provisional per chunk**, so whatever
10b finds is multiplied — and the chunk in flight when the process died is the only one that can be
orphaned, while every earlier chunk should already be settled. **Confirm the orphan count is one, not
N.** If earlier chunks are also unsettled, the per-chunk settle is not working and 9d's pass was
measured on an uninterrupted run.

## 10e. The control — and it is not optional

Run an **ordinary, uninterrupted 2 U bolus**, and run the same query.

**Confirm exactly one live row of 2.0 U, carrying both a `temporaryId` and a `pumpId`** — that is
what a settled provisional looks like, and it is what none of the interrupted runs should produce.

This step exists because four checks in `PORTING_NOTES` turned out to be incapable of failing, every
one of them read as a pass, and the pattern was never the logic — it was that nobody looked at what
the *inputs* were. A query that cannot tell one row from two would report a pass for 10a and 10b no
matter what happened. **Run the control on the same build, in the same session, with the same
command.**

## The query

The database is the arbiter and the log cannot be. Pull all three files — `exec-out`, never `shell`,
or the binary is CRLF-mangled on Windows:

```bash
for f in androidaps.db androidaps.db-wal androidaps.db-shm; do
  adb exec-out "run-as info.nightscout.androidaps cat databases/$f" > "$f"
done
```

### ⚠⚠ This needs a DEBUG build on the tablet — a release build cannot be read

`run-as` only works on a **debuggable** package, and `debuggable` comes from the build *type*, not from
the signing key. On a release build the command above fails with:

```
run-as: package not debuggable: info.nightscout.androidaps
```

**Found the hard way on 2026-08-04**, by installing the release APK on the tablet and discovering the
instrumentation had gone. There is no way around it from the host side — not root, not `exec-out`, not a
different `adb` flag; the platform simply refuses. So:

- **The test tablet must carry `:app:assembleFullDebug`** for any phase adjudicated on the database,
  which is Phase 10 and in practice all of them. This is not a preference, it is what makes the arbiter
  readable.
- **The phone should carry the release build** — not debuggable is the right answer for a device doing
  therapy, and no test is adjudicated there.
- The two are signed with different keys anyway (`README_YPSO.md`), so they were never going to be the
  same file.

If a phase has to be run against a release build, the only way to get the database out is AAPS's own
**Export database** from the Maintenance menu, which is a different artefact in a different format and
is not what the SQL above expects. Reinstall the debug build instead.

```sql
select id,
       datetime(timestamp/1000,'unixepoch','localtime') as ts,
       amount, type, pumpId, temporaryId, isValid, referenceId
from boluses
order by timestamp desc
limit 20;
```

**Read it as:** live rows are `referenceId is null` **and** `isValid = 1`. A settled bolus has both
`temporaryId` and `pumpId`. An orphaned provisional has `temporaryId` and **no** `pumpId`. A
sync-only import has `pumpId` and **no** `temporaryId`.

The sum of the live rows in the window is the number to compare against the reservoir. Take the
reservoir reading before and after every run — it is the only measurement in this phase that does
not come from the software under test, and it is what adjudicated all five steps of Phase 9.

## What cannot be tested here

- **A phone that never comes back.** Every step assumes the tester restores the link or restarts the
  app. A phone that is lost, flat or broken leaves the provisional standing for ever, at the
  requested amount, counting towards IOB — which is §4g's intended failure mode and is the safe
  direction. There is nothing to run; it is a property of the design and is recorded rather than
  measured.
- **Whether the reconnect *usually* succeeds.** These steps deliberately force outages long enough
  that it cannot. How often a real drop is short enough to be ridden out is a field question, not a
  bench one.
- **A crash rather than a force-stop.** `am force-stop` is a clean process kill. A native crash or an
  OOM kill could in principle leave the database mid-write; Room's WAL is designed for exactly that
  and this phase does not test it.

## Phase 10 is passed when

10a and 10b each end with **AAPS's live rows summing to what the reservoir says went in** — not
merely with a record existing — and 10e proves the query can tell one row from two on the same
build. 10c agrees with 10a. 10d shows one orphan and not N.

**If the predicted double row appears, that is the phase working.** Record it here in full, fix it
deliberately, and re-run — which is what 9c did, and 9c is the reason the other four Phase 9 results
are worth believing.

## Results

| Step | Bolus | Interruption | Result |
|---|---|---|---|
| 10a link dies and returns | 8 U | `svc bluetooth disable`, 90 s | ❌→✅ **FAILED then FIXED 2026-08-09.** 16.00 U before the fix, **8.00 U in one row** after, same rig |
| 10b AAPS killed | 8 U | `am force-stop` | ❌→✅ **FAILED then FIXED 2026-08-09.** The marker survived the kill and reconciled: **8.00 U in one row** |
| 10c real range loss | 5 U | pump carried away | ✅ **PASSED 2026-08-10** — one live row, 5.00 U, reconciled on the first connection back. `reason=10` / `status 133`, distinct from 10a's `reason=1` / `-100` |
| 10d chunked sequence killed | 2 U @ 3 U/min | `am force-stop` on a chunk write | ❌→✅ **FAILED twice, then FIXED 2026-08-10.** 2.80 U against 2.40 U under the marker design; **4 rows and 1.60 U for 1.60 U delivered** once the sync settles the stranded row instead. Marker deleted |
| 10e control, uninterrupted | 2 U | none | ✅ **PASSED 2026-08-09** — exactly one row, both ids |

---

# Phase 11 — the temporary basal that could not be replaced ⏳

**Written 2026-08-04, unrun. This one is not a gap found by reading the code — it is a defect that
appeared during the driver's first and only period of real insulin use, and it is the reason that
period ended.**

## What happened, and why it stopped the trial

The pump was carrying insulin and looping on a person. AAPS set a 400% temporary basal, then **twice
asked to replace it with 0% and was refused by the pump both times**:

```
13:17:06  setTempBasalAbsolute: 4.8U/h against the pump's 1.2U/h = 400% for 30min
13:17:08  setTempBasalPercent: asked 400% for 30min, confirmed=true
13:17:08  TBR confirmed by read-back: 400% with 30min left, logged as seq=161015 v1=400 v2=30

13:20:46  setTempBasalAbsolute: 0.0U/h against the pump's 1.2U/h = 0% for 60min
13:20:46  setTempBasalPercent: asked 0% for 60min, confirmed=false, The pump refused the temporary basal command
13:22:04  ... and again, identically, 78 seconds later
```

**The refusal is the pump's, not the driver's.** `tbrPercentRange` is `0..500`, so 0 passes the range
guard — a percentage outside it produces a different message naming the range. The failure comes from
`!suspendStartTbr(...)` inside `setTbrConfirmed`, which is the write itself.

⚠ **What the field capture cannot tell us, because of the filter it was taken with.** The live log was
captured with `PUMP:V PUMPBTCOMM:V "*:S"`, which omits `YpsoBleManager` — so the `Starting TBR: 0% for
60min` line and whatever the ATT layer said about it are simply not in the only recording of this
event. `startTbr` returns false both when a write fails **and** when the characteristic is missing, and
those are very different faults. Nothing below should assume which one happened; *Before starting*
fixes the filter so the next capture can say.

**Why this ends a loop trial rather than joining a list.** A zero temporary basal is not an occasional
request in oref1: it accompanies **every SMB**, because the microbolus replaces basal delivery, and it
is the loop's primary instrument for backing off insulin when glucose falls. A pump that will not
accept one has not lost a feature — it has lost the ability to reduce delivery on demand. Through the
live event AAPS's IOB accounting stayed correct (it knew the 400% was running and said so in
`currentTemp`), so nothing was dosed blind; what was lost was **control**, and it could not be
recovered until the temporary basal expired or somebody cancelled it by hand.

⚠ **Cancel is a different code path and it works.** `cancelTbrConfirmed()` writes the pump's "no TBR"
value of 100%, and was observed succeeding repeatedly the same day. That asymmetry is why this phase
can be short: there is a working primitive to build on if the diagnosis lands where it probably lands.

## The two hypotheses, and the 2x2 that separates them

Everything observed fits either of these, because **only one combination was ever exercised**: 0%
requested while a TBR was already running.

1. **H-ZERO — the pump refuses 0% itself**, whatever the current state. Then zero temps are not
   expressible on this pump and the loop needs a different instrument (a pump stop, or a very low
   non-zero percentage). A design question, not a patch.
2. **H-REPLACE — the pump refuses *any* new temporary basal while one is running**, and 0% is
   incidental. Then the fix is small and sits entirely in `setTbrConfirmed`: cancel first, then set.

The 400% at 13:17 was set from `tbr=none` and succeeded. That is the only other data point and it
rules out neither.

| | **11b: 150% → 80% succeeds** | **11b: refused** |
|---|---|---|
| **11a: 0% from idle succeeds** | Neither hypothesis — the failure needs the *combination*; investigate further | **H-REPLACE.** Fix: cancel-then-set |
| **11a: refused** | **H-ZERO.** 0% is not expressible; rethink the instrument | Both true; fix replacement first, then confront 0% |

**Run 11a and 11b before anything else — they are the whole point of the phase.** The rest confirms.

## Before starting

- Saline, off-body, cannula to air. **Insulin out of the cartridge before this phase begins.**
- Note the reservoir before every step. It is the only measurement that does not come from the
  software under test.
- Study phone (Lotus) off. NSClient uploading off.
- Base basal must be a known non-zero rate or the percentage arithmetic is meaningless. Record it —
  every expected rate below is a multiple of it.
- logcat running before the pump is touched, and **with the BLE tags included**:

  ```bash
  adb logcat -v time PUMP:V PUMPBTCOMM:V YpsoBleManager:V YpsoPumpManagerImpl:V "*:S"
  ```

  ⚠ **Not the shorter `PUMP:V PUMPBTCOMM:V` filter.** The field capture used that one, and it is why
  the most useful line about this defect is missing from the only recording of it: `startTbr` logs
  `Starting TBR: n% for Nmin` immediately before the write, and `writeCommandPayload` reports what the
  ATT layer did with it. Without those, `confirmed=false` cannot distinguish **the write being
  rejected** from **the write never being attempted** (`startTbr` also returns false when the
  characteristic is missing, logging `CHAR_TBR_START_STOP not found`).

### ⚠ One thing to settle while you are here: does this pump NAK at all?

§0 states that a successful GATT write proves nothing because **this pump does not NAK a bad write** —
a wrong auth password produced a perfectly successful write that only failed on the next read. That
rule is load-bearing: it is why every write in this driver is confirmed by a read-back.

**A refusal at write time would be the first counter-example.** So for every refusal below, record
which layer produced it:

| What the log shows | What it means |
|---|---|
| `Starting TBR: …` then an ATT/GATT failure | the pump **did** reject a write — new, and §0's rule needs qualifying |
| `Starting TBR: …` then nothing, then `confirmed=false` | the write was accepted and the read-back disagreed — the ordinary pattern |
| no `Starting TBR: …` at all | the driver never wrote; look at the characteristic, not the pump |

This costs nothing to collect and answers a question older than this phase.

## 11a. 0% from idle — the decisive one

**The question:** can this pump express a zero temporary basal at all?

1. Confirm nothing is running: the YPSO tab shows `Temp basal —`, and `[ypso-state]` reads `tbr=none`
   with byte 10 at 100.
2. From AAPS set a temporary basal of **0% for 30 min** (or a 0.0 U/h absolute rate, which the plugin
   converts to 0%).
3. Read the log for `setTempBasalPercent: asked 0% ...`.

**Confirm:**

- **Accepted or refused**, from `confirmed=true/false`. This is the answer the phase exists for.
- If accepted: byte 10 reads **0** and the YPSO tab shows a 0% temporary basal running.
- ⚠ **If accepted, does delivery actually stop?** Leave it 15 minutes and compare the reservoir before
  and after. At a 1.2 U/h base a 0% TBR should consume **nothing**. Anything else means the pump
  accepted the command and did not honour it, which is worse than a refusal because it is silent.
- ⚠⚠ **And does the pump still call itself delivering?** Record `[ypso-state]`'s `delivering=` and
  `suspendRecord=`. If a 0% TBR makes `isDelivering` false, `syncSuspendedState` will open a
  `PUMP_SUSPEND` record **on top of a temporary basal AAPS set deliberately** — two records for one
  state, one of them claiming the pump has stopped. Nothing has ever exercised that interaction, and
  this is the first thing that would.

## 11b. Replace a running temporary basal, non-zero — the other axis

**The question:** does the pump accept *any* replacement while a TBR runs, or must it be cancelled
first?

1. Cancel anything running. Confirm `tbr=none`.
2. Set **150% for 30 min**. Confirm accepted, byte 10 reads 150.
3. **Without cancelling**, set **80% for 30 min**.

**Confirm:**

- Accepted or refused, from `confirmed=`.
- If accepted: byte 10 reads **80** and the effective rate is 0.8 x base. ⚠ Check the *duration* too —
  §4h records that this pump **rewrites the TBR history entry in place** rather than appending, so
  establish whether the replacement restarted the 30 minutes or inherited what was left of the first.
- If refused: the message must be the same `The pump refused the temporary basal command` seen in the
  field. A different message means a different fault, and 11b has not reproduced anything.

## 11c. Reproduce the field failure exactly — the control

Set **400% for 30 min**, and once confirmed running, ask for **0% for 60 min** — the live sequence.
**This must be refused**, or the bench does not reproduce the field and nothing concluded from 11a and
11b can be carried back to the phone.

This step is why the other two mean anything, and it is the same reason Phase 10 carries a control:
four checks in `PORTING_NOTES` turned out to be incapable of failing, and every one read as a pass.

## 11d. Cancel-then-set — ⚠ **the fix is now written; this validates it**

**This step was originally "do it by hand before writing the code", and that rule has been overtaken.**
It existed to avoid a failure that could not be attributed — pump or patch. What removed the ambiguity
was not a manual run but the overnight ledger: **25 refusals and 24 successes with no exception in
either group**, which pins the pump's behaviour far more tightly than one hand-run of three steps
would have. Both halves of the fix were also exercised repeatedly by the loop itself: 16 sets from
idle, 8 cancels, all confirmed by read-back.

**What was written (`YpsoPumpManagerImpl.setTbrConfirmed`, 2026-08-05):** read the status first; if a
temporary basal is running, `cancelTbrLocked()` and require that cancel to confirm; only then issue
the set. If the status cannot be read, or the cancel does not confirm, **the set is not attempted** —
the old rate keeps running and AAPS is told the request failed, which is the honest report.

⚠ **Two things in that patch are not covered by the overnight evidence, and are what this step is now
for:**

1. **Cancel and set back to back inside one connection.** Every observed cancel was followed by a
   *poll*, then a set on a later connection. Nothing has yet cancelled and immediately set within the
   same held session. If the pump needs longer than `TBR_SETTLE_MS` to accept a new TBR after a
   cancel, this is where it shows.
2. **The failure branch.** The set must not be attempted when the cancel fails.

### ⚠⚠ This step cannot be driven from the AAPS UI — and that is the whole point

**The obvious procedure — "set 400%, then without cancelling set 0%" — is impossible by hand.** The
moment a temporary basal is running, AAPS's Actions tab replaces `TEMPBASAL` with `CANCEL n%`. There
is no affordance for setting a second one, which is exactly why nine bench phases never reached this
defect. **A procedure that asks a tester to do it manually repeats the mistake it exists to document.**

**The instrument is the loop.** oref1 calls `setTempBasalAbsolute` directly and bypasses the button,
which is how all 25 refusals were produced in the first place. So the test is to install the fixed
build, let the loop run against live glucose, and wait for the cycle where it wants a different rate
while one is running — historically every 5–20 minutes.

### The procedure

1. Install the build carrying the fix on the test tablet, saline, off-body. Confirm the version in the
   AAPS header matches the commit you think you are testing.
2. Let the closed loop run against real BG data. **You may seed the precondition by hand**: the UI
   *will* let you set a temporary basal from idle (say **150% for 30 min**), and the loop will then
   supply the replacement attempt on its own within a few cycles. That shortens the wait without
   requiring the impossible step.
3. Watch the log for the first replacement attempt — a `Starting TBR: N%` while a different one is
   running.

**Confirm, in one session and in this order:**

```
[ypso-state] … tbr=M% K min                       ← one is running
A M% temporary basal is already running; cancelling it before setting N%
TBR cancel confirmed by read-back: byte 10 is back to 100
TBR confirmed by read-back: N% with … min left    ← the set that used to be refused
setTempBasalPercent: asked N% …, confirmed=true
```

4. **Time the whole sequence.** It happens inside one held connection, and the extra cancel costs a
   round trip; a zero temp that takes a few seconds longer is still a zero temp, but the cost belongs
   in the notes.
5. **Both directions.** Confirm at least one replacement upward (low running, high requested) and one
   downward (high running, 0% requested). The overnight ledger showed the pump refusing both, so the
   fix must be seen to handle both.
6. **The database, not the log.** Confirm `temporaryBasals` holds one live row for the new rate, that
   the old one is closed, and that no phantom rows appeared.

**11d is passed when a replacement succeeds on the first attempt, with the cancel visible in the log
between the request and the confirmation.** If the set is refused *after* a confirmed cancel, that is
a new finding — a settle-time problem, not the occupancy rule — and `TBR_SETTLE_MS` is the first thing
to look at.

### ✅ **PASSED 2026-08-05 — both directions, first attempt each**

The loop supplied both cases within six minutes of each other, exactly as this step predicted it would.

**Downward, 400% → 0%:**

```
06:15:11.049  A 400% temporary basal is already running; cancelling it before setting 0%
06:15:11.052  TBR cancel: writing 16B … payload: 64 00 00 00 9B FF FF FF 00 00 00 00 FF FF FF FF
06:15:22.347  TBR cancel confirmed by read-back: byte 10 is back to 100, but no end entry found
06:15:22.641  Starting TBR: 0% for 60min
06:15:24.590  TBR confirmed by read-back: 0% with 60min left, logged as seq=161510 v1=0 v2=60
06:15:24.656  Recorded TBR 0% under pumpId=161510, new=true
```

**Upward, 0% → 397%:**

```
06:20:30.334  A 0% temporary basal is already running; cancelling it before setting 397%
06:20:33.742  TBR cancel confirmed by read-back: byte 10 is back to 100, logged as seq=161510 type=10
06:20:34.034  Starting TBR: 397% for 30min
06:20:36.033  TBR confirmed by read-back: 397% with 30min left, logged as seq=161511 v1=397 v2=30
06:20:36.090  Recorded TBR 397% under pumpId=161511, new=true
```

**The records are right too**, checked against the database rather than the log — one live row per period,
the old one closed when replaced, and **no row with a null `pumpId`**, so the fix invents no phantoms:

```
(127, 06:15:23,   0.0%,  5.22 min, pumpId 161510)   ← closed when the 397% replaced it
(130, 06:20:34, 397.0%, 30.00 min, pumpId 161511)   ← live
```

⚠ **The cost, and it is not symmetric.** End to end the downward case took **13.6 s** and the upward
one **5.8 s**. The difference is entirely in the cancel's read-back — 11.3 s versus 3.4 s — and the
slow one is also the one that reported *"no end entry found so the stop cannot be recorded"*, i.e. it
was looking for a history entry the pump had not written yet. **A replacement therefore holds the
connection for something like 6–14 seconds**, against ~3 s for a plain set. That is well inside the
loop's five-minute cycle and nothing else can interleave (the whole sequence is under one lock), but
it is worth knowing before anyone tightens a timeout.

**What this does not cover:** the failure branch — a cancel that does not confirm, where the set must
not be attempted. It did not arise, because both cancels confirmed. It stays untested.

⚠ **The fix has no unit test, deliberately.** The decision it makes ("is one running? then cancel
first") is a single condition, and a test asserting that null aborts, running cancels, and idle
proceeds would only restate the code — the shape this file has been caught by four times already. The
part worth testing is the *ordering*, and that needs a fake BLE layer which does not exist. **So the
evidence for this fix is 11d on hardware, and nothing else.**

## 11e. Does a zero temp survive its own duration?

Only if 0% is accepted somewhere above. Set **0% for 30 min** and leave it through at least two status
polls and one history sync.

**Confirm:** byte 10 stays 0, the AAPS record stays open with the right remaining duration, no
`PUMP_SUSPEND` record appears beside it, and the reservoir does not move. Then let it **expire on its
own** and confirm the pump returns to base rate and AAPS closes the record. §8.6's renewal logic was
written for the suspend record and has never been watched across a 0% TBR that AAPS itself set.

## What this phase does not answer

- **Whether 0% is the right instrument even if it works.** If the pump honours 0% but reports itself
  as not delivering, the driver may be better off treating a requested zero temp as something else
  entirely. A design decision for once the facts are in.
- **What other units or firmware do.** Everything here is one pump on V05.02.03.
- **Whether the loop can be trusted meanwhile.** It cannot, for closed-loop use, until a zero temp
  either works or is deliberately replaced by something that does.

## Phase 11 is passed when

11a and 11b have each produced a definite accepted-or-refused answer, 11c has reproduced the field
refusal on the bench, and — if H-REPLACE — 11d has shown cancel-then-set working by hand. A zero
temporary basal that is *accepted* must additionally be shown in 11a to actually stop delivery against
the reservoir, and not to open a competing suspend record.

**Until then this driver is not fit for closed-loop use with insulin**, and that sentence is a finding
from a real trial rather than a precaution.

## Results

| Step | What | Expected | Result |
|---|---|---|---|
| 11a 0% from idle | 0% / 30 min from `tbr=none` | unknown — the question | ✅ **ACCEPTED — 2026-08-04 20:18** |
| 11b replace non-zero | a TBR running → a different one | unknown — the question | ❌ **REFUSED — 2026-08-04 20:49** |
| 11c field control | reproduce the field refusal | **refused** | ✅ **REPRODUCED — same message** |
| 11d cancel-then-set | replace a running TBR directly | accepted, cancel visible in the log | ✅ **PASSED 2026-08-05, both directions** |
| 11e zero temp endures | 0% / 30 min, watched | byte 10 stays 0, reservoir static | ⚠ **ENDED EARLY — see below** |

### ✅❌ **RESOLVED 2026-08-04: it is H-REPLACE. 0% was never special.**

**11a — 0% from idle is accepted, and it genuinely stops delivery.**

```
20:18:05  Starting TBR: 0% for 30min
20:18:05  TBR: writing 16B … payload: 00 00 00 00 FF FF FF FF 1E 00 00 00 E1 FF FF FF
20:18:05  Status: Running, Rate: 0.0U/h, TBR 0% 30min left
20:18:07  TBR confirmed by read-back: 0% with 30min left, logged as seq=161453 v1=0 v2=30
20:18:07  setTempBasalPercent: asked 0% for 30min, confirmed=true
```

Adjudicated against the reservoir, not the read-back: **81.39 U at 20:18, still 81.39 U at 20:37** — 19
minutes with no movement, where 0.3 U/h would have consumed ~0.095 U and the preceding readings fell
~0.08 U per 20 min. Delivery really stops. ⚠ And the interaction nobody had reasoned through is
benign: `delivering=true` and `suspendRecord=none` throughout, so **a 0% TBR does not make the pump
report itself stopped** and no competing `PUMP_SUSPEND` record opens.

**11b — a running temporary basal cannot be replaced, at any percentage.** Produced by the loop
itself, which is the only thing that can reach this path (see below):

```
20:49:54  Status: Running, Rate: 0.45U/h, TBR 150% 23min left
          raw = 0A C4 1F 00 00 02 2D 00 00 00 96 00 00 00 17 00 00 00
                                        ^^byte6=0x2D=45      ^^byte10=0x96=150   ^^byte14=0x17=23min
20:49:55  setTempBasalPercent: asked 400% for 30min, confirmed=false, The pump refused the temporary basal command
20:49:55  CommandTempBasalAbsolute: Result rate: 1.2 durationInMinutes: 30 success: false enacted: false
```

**150% running, 400% requested, refused.** Both non-zero, both mid-range, and the refusal message is
identical to the field's. Taken with 11a this settles the 2×2: **H-ZERO is refuted and H-REPLACE is
confirmed.** The pump will change a running temporary basal to *none* (cancel works) but not to
another value.

#### The overnight tally — 12 out of 12, and 7 out of 7, with no overlap

Left running from 20:18 to 23:52 against real glucose data, the loop produced a clean separation:

| Attempt | Count | Outcome |
|---|---|---|
| set while a TBR was **running** | **12** | **all refused** |
| set from **`tbr=none`** | **7** | **all accepted** — 0%, 150%, 400%, 0%, 0%, 300%, 213% |
| cancel a running TBR | 1 | accepted |

**No attempt in either group went the other way.** Percentages spanned 0–400% and durations 30 and 60
minutes, so the rule is not about magnitude, direction, or length — it is only about whether a
temporary basal is already running. ⚠ That last point needed one more observation to be honest: every
60-minute request had been *refused*, which alone could not distinguish "duration is irrelevant" from
"this pump dislikes 60 minutes". At 03:14:56 the loop set **0% for 60 minutes from idle and it was
accepted** (`v2=60`, countdown correct), so the pump does honour the longer duration and the rule
really is only about occupancy. That is the number to quote when the fix is written, and it is why
`setTbrConfirmed` needs no comparison logic: a single "is one running?" check is sufficient and
complete.

### What the loop can and cannot do — observed over three hours of saline looping

The refusal is narrower than "the loop cannot drive this pump". Watching a real closed loop from
20:18 to 21:52 shows exactly three behaviours, and only one of them fails:

| Loop action | Result | Seen |
|---|---|---|
| set a temporary basal **from `tbr=none`** | ✅ accepted | 0% at 20:18, 150% at 20:42, 400% at 21:19 |
| **cancel** a running temporary basal | ✅ accepted | 400% cancelled at 21:44 with 5 min left |
| **replace** a running one with another | ❌ refused | 5 × 400%-while-150%-ran, 20:49–21:09 |

**So the loop is degraded rather than frozen.** It can still reach any rate it wants, but only by
passing through idle — and it does not know to do that, so when it wants a *different* rate it simply
retries the replacement every loop cycle and is refused every time until the running TBR expires or it
independently decides to cancel. Five consecutive refusals across 20 minutes is what that looks like.

⚠ **And this is the fix, demonstrated by the loop itself without a line of code being written.** Both
halves of cancel-then-set were exercised repeatedly and both work; the only untried part is doing them
back to back inside one connection, which is what 11d asks for. Worth noting too that the cancel
recorded `Recorded TBR stop under endPumpId=161455, new=true` — **the pump's own sequence, not the
`dateUtil.now()` fallback** that §4h flags as wanting rework. In the ordinary cancel path that fallback
is not reached.

### ⚠⚠ Why no bench phase could ever have found this — the UI forbids it

**When a temporary basal is running, AAPS's Actions tab replaces the `TEMPBASAL` button with
`CANCEL 150%`.** There is no "set a different one" affordance at all. Every phase from 1 to 9 was run
by hand through that UI, so **a manual tester physically cannot attempt a replacement.** The path is
reachable only from the loop, which calls `setTempBasalAbsolute` directly and bypasses the button.

That is a stronger statement than "nobody thought of the combination". The combination was
*unreachable by the test method*, and the only instrument that reaches it is a running loop. Which is
why this was found seven minutes after a loop was pointed at the pump, and not in nine phases of
careful manual work.

### ⚠ 11e, unplanned: a 0% TBR ended ~10 minutes early, and nothing cancelled it

The 0% set at 20:18:05 for **30 minutes** read `0% 11min left` at 20:37:24, and by 20:42:36 the pump
reported `tbr=none` with the reservoir moving again (81.39 → 81.36).

⚠ **Be careful which source says what — the two are not independent.** `temporaryBasals` row 78 carries
`duration = 1,245,416 ms = 20.757 min`, and its `dateCreated` is **27 ms after** that computed end.
That is the signature of **AAPS writing "it ended now"**, not of importing the pump's figure: the pump
records durations in whole minutes (`v2=30`), so a value of 20.757 cannot have come from it. The
database therefore records **when AAPS decided the TBR was over**, which is not necessarily when the
pump ended it.

**What stands on the pump's own evidence**, independent of AAPS's bookkeeping: at 20:42:36 the raw
status read `0A C8 1F 00 00 02 1E 00 00 00 **64** …` — byte 10 = `0x64` = 100 = *no temporary basal* —
when a 30-minute TBR started at 20:18:05 should have had ~5.5 minutes left. The reservoir had also
resumed falling. **The pump really did end it early; the precise moment is AAPS's guess.**

⚠ **The path that closed the record at 20:38:50 was not identified, and now cannot be from this run.**
A dump taken while the window was still in the buffer showed no ypso/PUMP lines between 20:37:25 and
20:42:32, so the driver was not connected — but by the time the anomaly was worth chasing, logcat had
rotated (buffer now begins 20:42:04) and the non-pump AAPS activity at 20:38:50 is gone. **Next time
this is suspected, dump logcat immediately rather than after the following poll.**

**Nothing cancelled it.** The driver was disconnected from 20:37:25 to 20:42:32; no command was sent
in that window, and the loop was not running yet.

⚠ **One observation, not a conclusion.** It has not been repeated, the reservoir arithmetic has ~1 min
of slack in it, and no TBR in this session has yet been watched across its *full* programmed duration
uninterrupted. If it is real it matters a great deal — a loop that sets a 60-minute zero temp and gets
20 minutes of one would under-deliver against its own model in the hypo direction. **Watch it
continuously; do not infer it from two samples five minutes apart.**

#### ✅ The control arrived the same evening: a **non-zero** TBR runs its full duration exactly

The 150% set at 20:42:38 for 30 min was polled every ~5 minutes by the loop and tracked perfectly:

| elapsed (wall) | pump says elapsed | drift | reservoir | used | expected @0.45 U/h |
|---|---|---|---|---|---|
| 0.0 | 0 | 0.0 | 81.36 | 0.000 | 0.000 |
| 7.3 | 7 | 0.3 | 81.32 | 0.040 | 0.055 |
| 12.2 | 12 | 0.2 | 81.27 | 0.090 | 0.091 |
| 17.1 | 17 | 0.1 | 81.25 | 0.110 | 0.129 |
| 22.1 | 22 | 0.1 | 81.21 | 0.150 | 0.166 |
| 27.1 | 27 | 0.1 | 81.16 | **0.200** | **0.203** |

**Countdown drift under 0.3 min across 27 minutes, and 0.200 U consumed against 0.203 U expected.** So
the pump honours a non-zero temporary basal for its whole programmed duration *and* delivers exactly
the rate it claims — 0.45 U/h being 150% of the 0.3 U/h base.

**And it ran to term.** The 150% was set at 20:42:38.888 and `temporaryBasals` row 81 records
`duration = 1,800,000 ms = 30.000 min` **exactly** — the requested value, never truncated — with the
pump reading `tbr=none` afterwards at 21:19:46 and the loop then setting a fresh 400% from idle. A
non-zero temporary basal is honoured for its whole programmed duration.

### ✅ [verified] RETRACTED, 22:44 the same night — a 0% temporary basal *does* run its full duration

The loop set its own 0% for 30 minutes at 22:14:43, and this one was watched continuously across the
whole thing — through the ~20.7-minute point where the first one died, and out the other side:

```
22:14:44  tbr=0% 30min   reservoir 80.48
22:24:44  tbr=0% 20min   reservoir 80.48     ← past nothing yet
22:29:39  tbr=0% 15min   reservoir 80.48
22:39:42  tbr=0%  5min   reservoir 80.48     ← 25 min in, well past 20.757, still running
22:44:36  tbr=none       reservoir 80.48     ← expired naturally, on time
```

`temporaryBasals` row 86 records **29.905 min of a 30-minute request** — full duration, the residue
being AAPS noticing at the next poll. And the reservoir did not move by **0.01 U across the entire 30
minutes**, where 0.3 U/h would have consumed 0.15 U.

**So the suspicion is withdrawn.** A zero temporary basal is accepted, suspends delivery completely,
and runs for exactly as long as it was asked to. The cancel-then-set fix may safely assume a zero
temp, once accepted, stays.

⚠ **What that leaves is a genuine one-off, and it should stay on the record as one.** Row 78 really did
end at 20.757 minutes, and row 86 is a direct counter-example under the same conditions in the same
session. So the earlier ending was **not a property of 0% TBRs** — it was something that happened once,
whose cause was never identified and whose logcat window had rotated by the time it mattered. Do not
build anything on it, and do not delete it: if it recurs, this row is the prior.

| | asked | recorded | outcome |
|---|---|---|---|
| 0% (161453) | 30 min | 20.757 min | ⚠ unexplained one-off |
| 150% (161454) | 30 min | 30.000 min | full |
| 400% (161455) | 30 min | 25.052 min | cancelled by the loop |
| **0% (161456)** | 30 min | **29.905 min** | **full — the retraction** |
| **0% (161457)** | 30 min | **~30.08 min** | **full — set 22:44:39, `tbr=none` at 23:14:44** |

**Two zero temps out of two have now run to term**, so the retraction stands on a pair rather than a
single counter-example.

⚠ **And the 30.000 figures are the pump's, not AAPS's — which settles the earlier "whose number is
this" worry.** Row 86 was read twice: at 22:52 it held **29.905 min**, and by 23:31 the same row held
**30.000**. Something revised it *upward* after the fact, which can only be the history sync
reconciling the record to the pump's own entry (`v2=30`, whole minutes). So the sequence is: AAPS
truncates to what it observed at the next poll, then the sync corrects it to what the pump says.

That matters twice over. It makes the full-duration result **pump-sourced and authoritative** rather
than AAPS's reckoning. And it sharpens the anomaly: rows 86 and 88 were both corrected to 30.000,
while **row 78 still reads 20.757 and has never been corrected** — so either the pump agrees that one
ended early, or its entry fell out of the sync's read window before it could be reconciled. Worth
checking against the pump's own log next time somebody has it in front of them.

### ✅ [verified] 2026-08-07 — `duration` is a running estimate, and it is rewritten more than once

The "whose number is this" worry above is now settled harder than it was, and in the direction that
dissolves most of the anomaly. A single row was observed three times across 96 minutes:

| observed at | `duration` | `dateCreated` |
|---|---|---|
| 08:02 | 120.000 min | 07:51:38 |
| 08:21 | *(pump reported 88 min left — 32 min elapsed of 120)* | — |
| 18:18 | **55.220 min** | **08:44:16** |

Same row, `pumpId=161534`, rewritten twice. So `duration` is not a fact about the pump at all until
the record is closed and reconciled — it is **AAPS's estimate as of the last time something touched
it**, and `dateCreated` moves with it. A row read mid-life reports its *programmed* duration; a row
read after the loop replaced it reports *elapsed to the replacement*.

⚠ **Therefore a short `duration` is never, by itself, evidence that a temporary basal ended early.**
Both times this session that a zero temp appeared to stop short — the 20.757 one-off above and a
0%/120 min on 05 Aug that read 30.081 — the reading was taken from a row still being revised. The
second one was chased as far as asking the operator whether they had cancelled it by hand; that
question should not have been asked, because the observation never had the meaning attributed to it.
The 20.757 row stays on the record, but its "unexplained" label should now be read as *probably the
same artefact*, not as a rival explanation with equal standing. It was never reconciled, so it cannot
be promoted past "probably".

The general rule, which cost two false alarms to learn: **read a temporary basal's duration only from
a closed, reconciled row**, and treat anything else as a snapshot of AAPS's bookkeeping.

### ✅✅ [verified] 2026-08-05 → 08-07 — the fix holds over 58 hours of unattended closed loop

The strongest evidence for the cancel-before-set fix is no longer 11d's two hand-run cases. The rig
ran closed loop on saline, off-body, against real Nightscout glucose, from 05 Aug to 17:52 on 07 Aug
with no operator intervention in the driver. From the database:

- **124 live temporary basals**, spanning **32 distinct rates** from 107% to the 400% cap
- **45 replacements** — a running temporary basal cut short with the next one beginning within
  seconds — **every one of them on a real pump sequence id**
- **no period of the loop stuck at a rate it was trying to leave**, which is the symptom the refusal
  produced before the fix and the reason it was caught at all

Set against the pre-fix ledger of **25 refusals and 0 successes** while a temporary basal was
running, this is the result that matters. ⚠ Note the limit precisely: the database records successes,
so it proves 45 replacements worked; it cannot by itself prove zero refusals occurred. The
corroboration is the rate diversity — a refused loop sits pinned at one figure for 15–20 minutes, and
nothing in these two days does.

### ✅ [verified] The empty-cartridge path, exercised for real rather than by procedure

Not planned, and worth having. At 14:03 on 07 Aug the cartridge ran dry under sustained high temps:

```
14:03:00  ANNOUNCEMENT  "Alert: Cartridge Empty"   pumpId=161650
14:18:46  first synthesised 0% temporary basal (pumpId is a timestamp, not a sequence)
16:55:10  INSULIN_CHANGE                            pumpId=161663
17:52     suspend record opened, 60 min; status byte0=3 Stopped, reservoir unknown (no cartridge)
```

The alert decoded and announced, and the driver then represented the stopped pump as a chain of
contiguous zero temporary basals carrying synthesised ids. That chain is the correct shape — AAPS
must show *something* covering the gap — and its ids are distinguishable from pump-sourced ones,
which is what let the two be told apart here. Eight such rows exist in this window and none of them
should be read as loop decisions.

### ⚠⚠ [verified] What it actually costs: 15 minutes of **zero** basal against the loop's intent

The clearest measurement of the night, 22:44–23:14, and the one to quote when deciding whether this
driver may loop. The loop set a 0% zero temp for 30 minutes at 22:44:39. Glucose then began to rise,
and the loop tried to escalate — and could not:

```
22:59:42  tbr=0% 15min left  →  asked 213%  →  REFUSED
23:04:41  tbr=0% 10min left  →  asked 253%  →  REFUSED
23:09:45  tbr=0%  5min left  →  asked 287%  →  REFUSED
23:14:44  tbr=none (expired) →  asked 300%  →  ✅ ACCEPTED, seq=161458
```

**For 15.05 minutes the pump delivered nothing at all while the loop was asking for two to three times
basal.** The reservoir confirms it independently: static at **80.48 U** for the entire half hour, so
this is not a bookkeeping artefact — no insulin moved.

Three things this makes concrete:

1. **The blocked interval is bounded by whatever TBR happens to be running**, not by anything the loop
   controls. AAPS sets temporary basals for 30 minutes, and cannot replace them, so **the worst case is
   up to 30 minutes of the wrong rate** — and when that rate is 0%, it is 30 minutes of no basal at all.
2. **The loop's requests escalate while it is blocked** (213 → 253 → 287 → 300) because the deviation
   it is correcting keeps growing. The correction is not lost, it is *deferred and enlarged*.
3. **It all lands at once.** The instant the 0% expired, 300% was accepted on the first attempt. So the
   pattern is a suspension, followed by a large catch-up — which is the oscillation this defect
   produces, rather than a simple under-delivery.

⚠ **Direction of harm depends on which way it is stuck**, and both have now been seen: stuck at 400%
wanting 0% on insulin (over-delivery, the hypo direction, §4h), and stuck at 0% wanting 300% here
(under-delivery, then a catch-up). **The first is the dangerous one. This one is the one that shows
how long it lasts.**

#### And the dangerous direction, also measured — 02:24 to 02:44, 20 minutes

The same night produced the hypo-side case on saline, which the insulin trial had shown without a
duration. A 107% temporary basal was set from idle at 02:14:54; glucose then fell, and the loop asked
to cut delivery four times:

```
02:24:53  107% running  →  asked  73%  →  REFUSED
02:29:53  107% running  →  asked   0%  →  REFUSED
02:34:50  107% running  →  asked   0%  →  REFUSED
02:39:52  107% running  →  asked   0%  →  REFUSED   (5 min left)
02:44:52  tbr=none — the 107% expired on its own after 29.97 min
02:44:53  asked 0%  →  ✅ ACCEPTED, seq=161470
```

**20.0 minutes during which the loop was asking for zero basal and the pump delivered 0.32 U/h** —
about **0.107 U given against an explicit request for none**. Small in absolute terms on a 0.3 U/h
profile; the point is the mechanism and the duration, both of which scale with the user's basal rate.

**So the block is symmetric and bounded the same way in both directions:** 15 minutes stuck at zero
wanting more, 20 minutes stuck delivering wanting zero, each ending only when the running TBR expired
of its own accord. Neither was escapable by the loop, and in both cases the correct rate was applied
on the first attempt once the pump was idle.

### ⚠ The refusal is not about the value: an identical request is refused too

Three more refusals arrived while that 0% ran, and two of them asked for **0% while 0% was already
running**:

```
22:24:45  asked 0% for 60min  → refused   (0% running, 20 min left)
22:29:41  asked 0% for 60min  → refused   (0% running, 15 min left)
22:39:42  asked 0% for 30min  → refused   (0% running,  5 min left)
```

The last is the sharpest: **0% for 30 minutes refused while 0% for 30 minutes was running** — the same
percentage and the same duration. So this is not "the pump will not change a rate"; it is **"the pump
will not accept any new temporary basal while one is running"**, full stop. That simplifies the fix —
`setTbrConfirmed` does not need to compare anything, it only needs to know whether a TBR is active.

---

# Phase 12 — the command × pump-state axis, the class Phase 11 broke on

**Status: ⏳ WRITTEN 2026-08-09, NOT RUN.**

Phase 11 established something narrower and more useful than "the pump refuses 0%": **this pump
refuses a command because of the state it is in, not because of what the command says.** An identical
request — 0% for 30 min while 0% for 30 min ran — was refused. Nothing about the value mattered.

That finding has a consequence nobody has tested. If the pump gates *temporary basals* on pump state,
the obvious question is what else it gates. Three interactions sit on that same axis and all three are
untested:

| | interaction | item |
|---|---|---|
| 12a/12b | a **bolus** while a temporary basal runs | H |
| 12c | a **basal profile write** while a temporary basal runs | H2 |
| 12d | the pump **stopped by hand** while a temporary basal runs | H3 (6e's second half) |

### ⚠ The driver makes no state check before either command — this is verified, not assumed

- `deliverBolusConfirmed` validates the amount, baselines the event log with `newestEventSequence()`,
  and sends. It never calls `suspendGetSystemStatus()`. It does not know whether a TBR is running.
- `writeBasalProfile` reads the active program, reads the existing 24 slots, writes, and diffs the
  read-back. There is **no check for a running temporary basal anywhere in it.**

This is the *same blind spot* that produced the Phase 11 defect: a command sent into a pump state the
driver never looked at. The fix added a status read before a TBR write and nowhere else.

**So if the pump refuses either of these, the driver will not get a clean refusal — it will get a
confirmation that never arrives, and fail on a timeout.** That distinction matters, and 12a is
designed to record which one happened.

### Why this is not "probably fine"

The withdrawn note on item H used to say the risk was low because both operations work alone and both
are identified by sequence. That is **exactly** the reasoning that would have rated "replace a running
TBR" low risk the day before it failed on a person. Nothing on this axis is low risk until it is run.

## Before starting

- Saline cartridge, **off body**, pump 10074292, debug build on the tablet.
- Reservoir above 30 U — 12a–12c together use under 5 U, but 12d may need a restart.
- Have `[ypso-state]` streaming so `tbr=`, `delivering=` and `suspendRecord=` are visible throughout:

```
adb -s 192.168.1.39:5555 logcat -v time PUMP:V YpsoPumpManagerImpl:V "*:S" | grep -vE "skipping (Alert|Bolus|Tbr|Unknown)"
```

- ⚠ **Phase 10e is the control for 12a and 12b** — a bolus from idle, uninterrupted. If 10e has not
  been run, run it first. An anomaly here needs something to be an anomaly against.

## 12a. A bolus while a **0%** temporary basal runs — item H, the sharper half

**Why 0% first.** If a 0% TBR makes the pump report `delivering=false`, a bolus may be refused on the
grounds that the pump is not delivering — a different mechanism from the Phase 11 one, and one that
testing only the high-rate case would miss entirely.

1. From idle, set **0% for 30 min**. Confirm it took: `tbr=0%`, byte 10 reads 0.
2. Record `[ypso-state]`'s `delivering=` and `suspendRecord=` **now**, before the bolus.
3. Deliver a **1.0 U** bolus from the AAPS bolus dialog. 1 U is enough to confirm and cheap on the
   cartridge; the interruption timing that needs 10 U belongs to Phase 10.
4. Watch for `Bolus 1.0 U requested. Newest event sequence before sending: N`.

**Confirm:**

- **Accepted or refused** — and *how*. A clean rejection and `deliverOneBolus` timing out with no
  confirmation are different findings. Record which, with the elapsed time.
- If accepted: the reservoir falls by 1.0 U, one bolus row appears, `pumpId` set, `temporaryId`
  settled.
- ⚠ **Is the 0% temporary basal still running afterwards?** Check `tbr=` and byte 10. If a bolus
  silently cancels the TBR, every bolus given in a low-glucose state would restore full basal without
  AAPS knowing — the inverse of the Phase 11 harm, and worse because it is silent.
- The TBR's `duration` in the database: unchanged, or rewritten?

## 12b. A bolus while a **high** temporary basal runs — item H

1. From idle, set **300% for 30 min**. Confirm `tbr=300%`.
2. Deliver **1.0 U**.
3. Same confirmations as 12a.

**Additionally:** the reservoir should fall by 1.0 U **plus** the elevated basal for the interval.
Compute the expected figure before reading it. A bolus that lands but suppresses the basal for its
duration is a real under-delivery, and it is only visible against a computed expectation.

### What each outcome means

| outcome | meaning |
|---|---|
| both accepted, TBR survives | ✅ item H closes. The gate is specific to temporary basals |
| refused while a TBR runs | ⚠⚠ **Severe.** Meal boluses fail whenever the loop holds a temp — 84% of the time over 08-05→08-07. Needs the same cancel-before-send treatment as Phase 11, but a bolus cannot simply cancel the TBR without restoring it afterwards. Do not improvise the fix; record it and design it |
| accepted but the TBR is cancelled | ⚠⚠ **Silent.** AAPS goes on showing a TBR the pump no longer has. Worse than a refusal |
| accepted, TBR survives, delivery wrong | ⚠ Under- or over-delivery against the computed expectation. Measure before concluding |

## 12c. A basal profile write while a temporary basal runs — item H2

Realistic trigger: an exercise profile switch landing while the loop holds a temp. This happens
without anyone intending it.

1. From idle, set **200% for 30 min**. Confirm it took, and record the base rate for the current hour.
2. With it running, apply a profile change in AAPS that alters the current hour's basal — a percentage
   change is easiest, and it rewrites all 24 slots.
3. Watch for `BASAL PROGRAM x BEFORE OVERWRITE` and `written and confirmed: all 24 slots`.

**Confirm:**

- **Did the write succeed at all** while a TBR ran, or was it refused?
- ⚠ **Is the temporary basal still running, and at what percentage?** The driver records TBRs as
  percentages (`isAbsolute = false`), so AAPS should apply 200% to the *new* base and the pump should
  do the same. **That agreement is two independent calculations, not an enforced invariant** — this
  step is where it is checked.
- The delivered rate the pump reports against `new base × 200%`. Record both figures.
- ⚠ If the write is refused, note that **nothing retries it**. AAPS would believe the profile is set.
  Check `isThisProfileSet` afterwards.

## 12d. The pump stopped by hand while a temporary basal runs — item H3, 6e's second half

6e is written and has no pass marker; its own text calls this "the interaction with the least analysis
behind it". After Phase 11, an unanalysed interaction between the suspend record and a running
temporary basal is not a curiosity.

1. From idle, set **250% for 60 min**. Confirm it took.
2. **On the pump's own buttons**, stop the pump. Do not touch AAPS.
3. Let the driver poll at least twice.
4. **Restart the pump on its buttons.**

**Confirm:**

- `[ypso-state]`: `mode=STOPPED`, `delivering=false`, and what `suspendRecord=` shows.
- ⚠ **Does a `PUMP_SUSPEND` therapy event open on top of a temporary basal AAPS believes is running?**
  Two records for one state, one of them claiming the pump has stopped.
- What the database holds: is the temporary basal row closed, left open, or left running past the
  stop? Query `temporaryBasals` and `therapyEvents` together.
- After the restart: does the temporary basal resume, and do AAPS and the pump agree about whether it
  is running? Record both sides.
- ⚠ Recall that after any restart the driver reads the pump as suspended until it answers — do not
  mistake that for a finding here.

## 12e. Cancel a temporary basal that superseded another — the regression test for the end-id collision

**Status: ⏳ WRITTEN 2026-08-09, NOT RUN.** Added with the fix, not before it, because the defect was
found on hardware rather than predicted.

**Why this step exists.** On 2026-08-09 a 0% temporary basal at `seq=161723` was cancelled three times
from the AAPS UI. The pump obeyed every time — `tbr=none`, base rate restored — and all three came
back `new=false`. AAPS went on showing a temporary basal the pump did not have.

The cause was a collision between two reasonable rules. This pump rewrites one history entry in place
when a temporary basal stops, keeping its sequence and changing only the type (9 → 10), so the *end*
sequence and the *start* sequence are the same number. AAPS separately closes a superseded temporary
basal by stamping the new one's `pumpId` into the old row's `endId`, and then
`SyncPumpCancelTemporaryBasalIfAnyTransaction` refuses any stop whose `endPumpId` already appears as
some row's `endId`. Together: **a temporary basal that superseded another can never have its own stop
recorded.** Row id=422 already carried `endId=161723`, so every cancel of 161723 was discarded before
it did anything. 53 of 120 real temporary basals over 08-05 → 08-07 met that precondition.

⚠ **This step cannot be replaced by a unit test.** `YpsoService` is a bound service, and a test
asserting that a millisecond clock reading is not `161723` is a check incapable of failing — the exact
pattern that produced four false passes in `PORTING_NOTES`. The hardware sequence below can fail.

### ⚠⚠ This step cannot be driven from the AAPS UI either — the same trap as 11d

The first draft of this procedure said "set 200%, then **without cancelling it** set 0%". That is not
possible by hand: **while a temporary basal runs, the AAPS button reads `CANCEL n%`, not
`TEMPBASAL`.** There is no way to ask for a replacement from the UI, which is precisely why the
Phase 11 defect survived nine bench phases — and 11d already had to be rewritten for this exact
reason. It was written into this procedure a second time anyway.

**Only the loop can set a temporary basal over a running one.** So the loop is the instrument here,
as it was in 11d. What the tester supplies is the starting condition and the cancel; oref1 supplies
the step in the middle.

### The procedure

1. **Closed loop enabled**, pump running, glucose arriving. Confirm the loop is actually issuing
   temporary basals — the log has shown `setTempBasalPercent … confirmed=true` within the last
   ~10 minutes.
2. By hand, set a temporary basal the loop will want to change and that will not expire while you
   wait: **200% for 2 hours** if glucose is flat or falling, **0% for 2 hours** if it is rising. The
   point is to be wrong on purpose, so the loop's next cycle asks for something else.
3. **Wait for the fix to fire.** Watch for the line — it is unambiguous and it names both rates:

```
A 200% temporary basal is already running; cancelling it before setting N% (this pump will not replace one)
```

   This is the step that builds the trap: AAPS closes the 200% row by stamping `endId` = the new
   temporary basal's `pumpId`, which is what used to poison that new one's own cancel. Typically one
   loop cycle, so under ~5 minutes.

4. **Now press `CANCEL n%` in AAPS. Once — not three times.** Three presses is what happened on
   2026-08-09 and it only produced three identical `new=false` lines.

### Confirm

- `cancelTempBasal: confirmed=true`, and `[ypso-state]` reads `tbr=none` with the base rate restored.
- ⚠⚠ **`Recorded TBR stop under endPumpId=…, new=true`.** Before the fix this read `new=false`, and
  that single word is the whole test.
- **The `endPumpId` in that line is a timestamp (~1.8e12), not a pump sequence (~1.6e5).** If a
  sequence number appears there, the fix has been reverted or bypassed.
- **The AAPS UI offers `TEMPBASAL` again, not `CANCEL n%`.** This is the user-visible half and it is
  what prompted the original report.
- **In the database:** the row with `pumpId = B` has a non-null `endId`, its `duration` is the elapsed
  time rather than the programmed 30 min, and **no temporary basal is active**:

```sql
select id, timestamp, rate, duration/60000.0 as min, pumpId, endId
from temporaryBasals where isValid=1 and referenceId is null
order by timestamp desc limit 4;
```

### What a failure means

| outcome | meaning |
|---|---|
| `new=true`, row closed, UI offers TEMPBASAL | ✅ fixed |
| `new=false` again | The end id is still colliding. Check what `endPumpId` was logged before concluding anything else |
| `new=true` but the row stays open | A different defect, in the transaction rather than the id. Do not fold it into this one |


## Phase 12 is passed when

- 12a and 12b have a recorded accept/refuse with timings, and the TBR's survival is confirmed either
  way.
- 12c records whether the write succeeded and whether the two rate calculations agreed.
- 12d records what the database holds across a hand-stop, with no interpretation left implicit.
- **Any refusal is written up before a fix is attempted.** Phase 11's fix was right because the
  refusal was characterised first — 25 refusals against 0 successes, both directions, before a line
  changed.

## Results

| step | date | outcome |
|---|---|---|
| 12a bolus under 0% | 2026-08-09 | ✅ **ACCEPTED**, 4.11 s, TBR survived, exactly 1.00 U |
| 12b bolus under 300% | 2026-08-09 | ✅ **ACCEPTED**, 3.93 s, TBR survived, exactly 1.00 U |
| 12c profile write under 200% | 2026-08-09 | ✅ **SUCCEEDED**, TBR survived, both calculations agreed |
| 12d hand-stop under 250% | 2026-08-09 | ✅ **PASSED** (at 200% — the dialog clamps there). No overlap: the running TBR was closed before the suspend opened |
| 12e cancel after a supersede | 2026-08-09 | ✅ **PASSED**, precondition verified before the press, all four confirmations met |

### ✅ 2026-08-09 — items H and H2 both close, and the gate really is specific to temporary basals

**12a — a 1.0 U bolus under a running 0% temporary basal was accepted.**

```
16:18:03  TBR 0% for 15min set, confirmed, seq=161694
          [ypso-state] delivering=true  suspendRecord=none  rate=0.0U/h  tbr=0%
16:18:26.395  Bolus 1.0 U requested. Newest event sequence before sending: 161694
16:18:26.433  Provisionally recorded 1.0U under temporaryId=1786249106396
16:18:26.933  probe=during-bolus … tbr=0%          ← the TBR is still there mid-delivery
16:18:30.506  Settled temporaryId=1786249106396 under pumpId=161695
              4.11 s request → settled
16:22:39  [ypso-state] tbr=0% 11min  reservoir=88.38   (was 89.38 — exactly 1.00 U)
```

Three things this settles, beyond the accept/refuse question:

1. ⚠ **A 0% temporary basal does not make the pump report itself as not delivering.** `delivering=true`
   and `suspendRecord=none` throughout. The hypothesis that a bolus might be refused *by a different
   mechanism* — the pump treating 0% as stopped — is disproved, not merely unobserved.
2. **The TBR survived the bolus**, both mid-delivery (the probe) and after (11 min left of 15).
   Nothing silently cancelled it.
3. **The 0% was still honoured afterwards**: the reservoir read 88.38 at 16:22:39 and 88.38 at
   16:25:15 — no basal consumed across 2.6 minutes under the zero temp.

**12b — a 1.0 U bolus under a running 300% temporary basal was accepted.** seq=161696 set at
16:25:34 (`rate=3.78U/h`, which is 1.26 × 3); bolus requested 16:25:39.783, settled 16:25:43.717 under
pumpId=161697 — **3.93 s**. The during-bolus probe again read `tbr=300%`. Reservoir 88.38 → 87.38,
exactly 1.00 U.

**12c — a basal profile write under a running 200% temporary basal succeeded, and the two independent
calculations agreed.** This is the H2 answer and it is the sharpest result of the three:

```
16:26:06  TBR 200% for 15min set, seq=161698, rate=2.52U/h   (1.26 × 2)
16:26:32  BASAL PROGRAM A BEFORE OVERWRITE: [1.26 × 24]
16:26:32  BASAL PROGRAM A being written:    [1.00 × 24]
16:26:41  written and confirmed: all 24 slots read back as asked
16:27:12  [ypso-state] rate=2.0U/h  tbr=200% 14min
```

**New base 1.00 × 200% = 2.00 U/h, and the pump reports exactly `rate=2.0U/h`.** The temporary basal
survived a 24-slot rewrite, kept its countdown (14 min of 15), and the percentage was reapplied to the
new base by the pump — matching what AAPS computes from its own percentage record. The agreement that
§4h could only argue for is now observed.

⚠ **The recalculation is the pump's, not the driver's, and that is exactly why the driver's recording
choice is load-bearing.** The pump stores the temporary basal as a percentage and applies it to
whatever base rate it currently holds; nothing in the driver participates. The observed pair is
`2.52 U/h` before the write (200% × 1.26) and `2.00 U/h` after (200% × 1.00).

Because `setTemporaryBasal` records `isAbsolute = false`, AAPS applies **the same percentage to the
same new profile** and lands on the same 2.00 U/h. **Had the driver recorded the TBR as an absolute
rate, AAPS would have gone on believing 2.52 U/h while the pump delivered 2.00** — a silent 20%
over-estimate of delivery, persisting for the rest of the temporary basal, with nothing to reveal it.
The percentage choice was made on reasoning in §4h; this is the first evidence that it is correct.

### What this does and does not license

**Item H closes in both directions and H2 closes.** The Phase 11 gate is specific to temporary basals:
this pump refuses *a new TBR while a TBR runs*, and refuses nothing else that has been tried. The
withdrawn "low risk" rating turned out to be correct — but it is now correct *because it was run*,
which is the only reason worth having.

⚠ **It does not license the general claim that pump state never gates anything else.** 12d is still
unrun, and Phase 10's interrupted-bolus case is untouched. What is now known is three specific
combinations, measured.

### ⚠ A footnote on the fix, which fired again during this session

At 16:27:13, immediately after 12c, the loop asked for 0% while the 200% still ran:

```
16:27:13.680  A 200% temporary basal is already running; cancelling it before setting 0%
16:27:27.486  setTempBasalPercent: asked 0% for 120min, confirmed=true      13.8 s
```

Fourth confirmed firing, and the first outside 11d's scripted conditions. 13.8 s puts it at the top of
the 6–14 s band, consistent with the cancel read-back being the variable part.

### ✅ [verified] 2026-08-09 — 12e passed, and the precondition was confirmed *before* the press

The value of this run is that the trap was verified to exist first. A cancel that succeeds on a row
which was never poisoned proves nothing, and that is the failure mode this phase was written to avoid.

**The precondition, read from the database before anything was pressed:**

```
id=430  0%    pumpId=161726  endId=None     ← live, the row to cancel
id=428  200%  pumpId=161725  endId=161726   ← already consumed 161726 as its end marker
```

`161726` was simultaneously the live temporary basal's `pumpId` and the previous row's `endId`. That
is the exact collision that discarded three cancels at 16:34–16:35. On the old build this press would
have returned `new=false`.

**The run:**

```
17:12:56.573  A 200% temporary basal is already running; cancelling it before setting 0%
17:13:02.282  setTempBasalPercent: asked 0% for 120min, confirmed=true, seq=161726     (5.7 s)
              ← the loop built the trap; only oref1 can, which is why 12e needs it as instrument
17:20:16.057  cancelTempBasal: confirmed=true
17:20:16.110  Recorded TBR stop under endPumpId=1786252816058, new=true
```

**All four confirmations met:**

| confirmation | result |
|---|---|
| `new=true` | ✅ — the word the whole test turns on |
| end id is a timestamp, not a sequence | ✅ `1786252816058`, not `161726` |
| row closed with elapsed duration | ✅ `id=430` 119.00 → **7.23 min**, `endId` set |
| no temporary basal active, pump at base | ✅ `tbr=none`, `rate=1.0U/h`, nothing active in the database |

### ⚠ The 6–14 s band from 11d needs its lower bound widened

The fix has now fired six times. The 17:12:56 firing completed in **5.7 s**, below the 6–14 s band
recorded at 11d. Nothing is wrong — the spread was always the cancel read-back, and a pump that
answers promptly finishes sooner. **The observed band is now 5.7–13.8 s**, and a timing outside it is
worth a look rather than a fault. Recording it so the next person does not treat 5.7 s as an anomaly.

### What 12e does not close

It proves the *end id* no longer collides. It does not prove the record is right in every other
respect — `id=424` from earlier the same afternoon still carries **28.09 min** of 0% for a temporary
basal the pump stopped at ~16:34, because the stop was unrecordable when it happened and was only
closed at 16:55:32 when the fixed build started. That over-count is residue from the defect, not a
live one, and it is in the 08-09 data permanently.

---

# Phase 10 results — 2026-08-09. Items E and F both FAIL, exactly as predicted

Run by Claude over adb while the tester was away, on saline, off-body, pump 10074292. Every bolus was
entered through the AAPS bolus dialog by UI automation, with the dialog contents read back and
verified before each press; every interruption was fired from `adb`; every verdict comes from the
database, not the log.

## ⚠ Deviation from the written procedure: 8 U, not 10 U

`treatmentssafety_maxbolus` is **8.0** on this tablet, so the dialog will not accept 10 U. The
tester's max-bolus safety limit was **not** changed to run a test. 8 U at the pump's ~33.3 U/min is
**14.4 s** of delivery, so an interruption at +6 s still lands mid-delivery with ~8 s left to run,
which is what the step needs. The reason 2 U was rejected — 3.6 s is too short to interrupt — does
not apply at 8 U.

## The headline arithmetic

```
Pump actually delivered:  2 + 8 + 8  =  18.00 U
   reservoir 86.33 -> 67.93 = 18.40, less 0.40 U of basal over the 24 min   ✔
AAPS now records:                        34.00 U
Over-count:                              16.00 U
```

**The pump did exactly what it was told, three times. AAPS's records are wrong by 89%.**

## 10e — the control. ✅ PASSED

```
17:31:38.797  Bolus 2.0 U requested. Newest event sequence before sending: 161726
17:31:38.827  Provisionally recorded 2.0U under temporaryId=1786253498798
17:31:39.335  probe=during-bolus
17:31:44.311  Bolus confirmed from the pump's log: seq=161727 → 2.0 U
17:31:44.362  Settled temporaryId=1786253498798 under pumpId=161727        5.57 s
```

Database: **exactly one live row**, 2.00 U, carrying both `temporaryId` and `pumpId`. The superseded
provisional is `id=194`, `referenceId=193` — versioned away, not left live.

This is what makes the two failures below mean something: **the query can tell one row from two.**

## 10a — the link dies mid-delivery. ❌ FAILED (item E)

Bluetooth killed **5.4 s** after the send, held down 90 s.

```
17:36:45.621  Bolus 8.0 U requested. Newest event sequence before sending: 161727
17:36:45.789  Bolus sent. Expecting ~14s at the pump's 33.3 U/min, giving up after 74s
17:36:52.165  BLE disconnected (reason=1)          ← the cut
17:36:52.172  Link lost; reconnecting in 5s (attempt 1)
17:36:57.198  BLE connect failed with status -100
17:37:07.350  Bolus NOT CONFIRMED: no new bolus entry in the pump's event log.
              The pump reported at least 3.19 U before the log was read.
```

**The driver's own behaviour here is correct and should not be "fixed".** It reported
`success=false` on a delivery it could not confirm, which is what §0 requires, and AAPS raised a
modal alarm — *"Bolus reported an error… Not confirmed: the pump's log…"* — so the user is told
rather than left to notice.

**The record is what fails.** After reconnection and a history sync:

```
id=195  17:36:45  8.00U  pumpId=None    tempId=1786253805622   ← orphaned provisional
id=198  17:36:44  8.00U  pumpId=161728  tempId=None            ← history sync's own insert
                          both isValid=1, referenceId null — sum 16.00 U
```

Precisely the §4g prediction: the unsettled provisional has no `pumpId`, so the history sync cannot
match it, and inserts a second row beside it.

## 10b — AAPS killed mid-delivery. ❌ FAILED (item F), but one good answer inside it

`am force-stop` **5.8 s** after the send; `pidof` empty immediately after.

**✅ The provisional survived the kill.** Queried with AAPS still dead:

```
id=199  v=0  valid=1  ref=None  17:52:48  8.00U  pumpId=None  tempId=1786254768202
```

That rules out the worst of the three predicted outcomes — "no provisional row at all", which would
have been a durability defect in `addBolusWithTempId` and would have had to be understood before
anything else was built. `addBolusWithTempId` commits, and the row keeps counting toward IOB through
a process death. **The window §4g exists to cover is genuinely covered.**

**❌ Nothing then reconciles it.** After restart and sync:

```
id=200  17:52:47  8.00U  pumpId=161729  tempId=None
id=199  17:52:48  8.00U  pumpId=None    tempId=1786254768202
                          sum 16.00 U
```

## What this decides, and what it deliberately does not

Both interruption modes produce the same failure, so the fix does not need to distinguish them.

Per this phase's own outcome table, two rows means **something must reconcile the orphan** — and it
says, in the same breath, *"Decide deliberately; do not reach for the framework because it is there."*
That decision was **not** made here, and this was left unfixed on purpose. The candidates, restated
now that both runs are in:

| candidate | note |
|---|---|
| `PumpSyncStorage` | Persists pending entries and settles them after a restart. The framework answer. Larger surface than the problem |
| Settle from the pump's log at the next connection | Narrower. The driver already knows the `temporaryId` and the amount; the pump's entry is right there — 10a and 10b both had the matching `BolusDelivered` in the very next sync |
| Invalidate the orphan and let the sync's row stand | Simplest. Loses the IOB coverage during the gap, which is the whole reason the provisional is written first |

⚠ **The second looks strongest on this evidence** — in both runs the pump's own entry (`seq=161728`,
`seq=161729`) arrived in the first sync after reconnection, timestamped within a second of the
provisional. But that is an observation, not a decision, and it is the tester's to make.

## ⚠ The tablet's database is now wrong by 16 U

`id=195` and `id=199` are live orphans and `id=198`/`id=200` are their duplicates. Nothing cleans
these up automatically. On saline it costs nothing; the rows should be invalidated in AAPS
Treatments before this database is used for anything that reads IOB.

## What blocked this phase for the whole afternoon

Phase 10a was run once before this and produced an orphan that never reconciled — because history
sync had been wedged since 16:25 by a zero-duration temporary basal (`seq=161696`, 12b's 300%
cancelled after 17 seconds). See the separate finding. **Until that was fixed, 10a could not
distinguish "the orphan is never reconciled" from "the sync never ran"**, and would have been
recorded as the wrong failure.

### ✅ [verified] 2026-08-09, later the same evening — items E and F both FIXED, re-run on hardware

The fix is a persisted marker plus a reconciliation pass that runs **before** the history sync. Both
interruption modes were re-run on the same rig that had produced 16.00 U an hour earlier.

**10a re-run — link cut 5.6 s into an 8 U bolus:**

```
18:20:22.410  Bolus 8.0 U requested. Newest event sequence before sending: 161730
              marker persisted: ypso_pending_bolus = 1786256422412|8.0|161730
18:20:28      svc bluetooth disable                          (90 s down)
18:20:44      the pump's log did not confirm this bolus … the pending marker is left in place
18:25:57.840  Reconciled … temporaryId=1786256422412 settled to 8.0U under pumpId=161731, new=true
18:25:57.843  History sync: starting, watermark=161730       ← reconciliation went first
```

```
id=201  v=2  valid=1  ref=None  18:20:21  8.00U  pumpId=161731  tempId=1786256422412
id=203  v=1  valid=1  ref=201     (superseded)
id=202  v=0  valid=1  ref=201     (superseded)
                       LIVE ROWS: 1     8.00 U
```

⚠ **The timestamp is 18:20:21, not 18:25.** Settling with `now()` would have dragged a bolus
delivered five minutes earlier forward to the moment of reconciliation and put it on the wrong part
of the IOB curve. `temporaryId` *is* the phone clock at the moment of sending, so it is what gets
passed back.

**10b re-run — AAPS force-stopped 6.0 s into an 8 U bolus:**

```
18:30:24.015  Bolus 8.0 U requested. Newest event sequence before sending: 161735
18:30:30      am force-stop  →  pidof empty
              marker after the kill: ypso_pending_bolus = 1786257024016|8.0|161735   ← survived
18:32:02.104  Reconciled … temporaryId=1786257024016 settled to 8.0U under pumpId=161736, new=true
18:32:02.108  History sync: starting, watermark=161731
```

**That surviving marker is the whole reason it is persisted rather than held in memory.** An
in-memory marker is exactly what a `force-stop` destroys, and 10b is the step that destroys it.

**The session's arithmetic, which is the real verdict:**

| bolus | interruption | live rows |
|---|---|---|
| 18:20:21 | Bluetooth killed mid-delivery | 1 — 8.00 U |
| 18:28:56 | none (incidental control) | 1 — 8.00 U |
| 18:30:23 | process killed mid-delivery | 1 — 8.00 U |
| | **pump delivered 24.00 U, AAPS records 24.00 U** | |

Against 16.00 U for a single 8 U bolus, twice, an hour earlier on the same rig.

### What is still true after the fix

- **A bolus the pump has no record of is still left alone**, at the requested amount, marker cleared.
  Deleting it would assert nothing went in, which is the one thing this driver does not know (§0).
- **The driver still reports `success=false` on an unconfirmed bolus and AAPS still alarms.** None of
  that changed, and none of it should: the delivery genuinely was unconfirmed at the time. What
  changed is that the record is corrected at the next connection instead of standing wrong for ever.
- ⚠ **The two pre-fix orphans are still there** — `id=195` and `id=199`, with their duplicates
  `id=198` and `id=200`. They carry no marker, so reconciliation cannot see them and never will.
  They are 16 U of over-count that has to be invalidated by hand in AAPS Treatments.

### The out-of-order case, detected but not repaired

If reconciliation ever runs *after* the sync has imported the matching entry,
`SyncBolusWithTempIdTransaction` would put one `pumpId` on two rows, which is no better than the
failure. That case is detected against the watermark and **refuses to settle**, logging at error
level, rather than making it worse. It cannot arise while reconciliation runs first; it is a guard
against a future reordering, not a live path.

⚠ It is a guard, **not a repair** — the driver cannot invalidate the duplicate, because that needs
`PersistenceLayer` and no real-pump driver in this tree injects it. If the error line ever appears,
the correction is manual.

---

# 12d and 10d — run 2026-08-09 evening

## ✅ 12d PASSED — the suspend record does not overlap a running temporary basal

Tester stopped and restarted the pump on its own buttons; everything else driven over adb.

```
19:07:06  TBR 200% for 60min set, seq=161738          (200%, not 250% — AAPS's dialog clamps there)
          … the loop replaced it twice; seq=161740 (0%/120min) was running at the stop
19:42:30  syncSuspendedState: Pump is not delivering (mode 'Stopped');
          recorded a 0% basal so AAPS stops counting basal IOB, new=true
19:42:30  mode=STOPPED delivering=false suspendRecord=open (59min of 60)  tbr=none
19:43:01  TBR cancel: the open record is this driver's own 0% suspend record … left alone
19:56:12  Pump is delivering again (mode 'Running'); closed the 0% suspend record, new=true
```

```
id=454  19:42:30   0.0%  13.70min  pumpId=1786261350099  endId=1786262171959  PUMP_SUSPEND
id=452  19:38:57   0.0%   3.00min  pumpId=161740         endId=1786261350099  NORMAL
id=448  19:09:03   0.0%  29.94min  pumpId=161739         endId=161740         NORMAL
id=446  19:07:04  200.0%  1.00min  pumpId=161738         endId=161739         NORMAL
                                              active records at any point: 1
```

**The feared outcome did not happen.** `syncSuspendedState` closed the running temporary basal —
stamping the suspend record's own id into `id=452`'s `endId` — *before* opening the suspend. One
state, one record, and the two are distinguishable by `type` (`PUMP_SUSPEND` against `NORMAL`), which
is what the §8.6 guard relies on.

Two supporting observations:

- ⚠ **The pump cancels the temporary basal itself when stopped** — it reports `tbr=none`, not a
  suspended 0%. So the two sides agree for the right reason rather than by luck.
- **The §8.6 asymmetry works on hardware.** At 19:43:01 a cancel arrived and the driver declined it,
  because closing that record would have told AAPS basal had resumed on a stopped pump.

## ❌ 10d FAILED — but not where it was expected to, and the cause is still open

Bolus speed set to 3 U/min for this step and restored to `unlimited` afterwards. 6 U requested,
`am force-stop` at **+40 s**.

### ✅ The half the step was written to check: **exactly one orphan, not N**

```
id=210 ✔161748   id=212 ✔161749   id=214 ✔161750   id=216 ✔161751   id=218 ✔161752
id=220  0.40U  pumpId=None  tempId=1786262516455        ← the chunk in flight, and the only orphan
```

Five chunks settled with their provisionals versioned away; only the one open when the process died
was left. **The per-chunk settle is real**, and 9d's pass was not an artifact of an uninterrupted run.

### ❌ The half that failed: the orphan was never reconciled

```
id=226  20:01:56  0.40U  pumpId=161753  tempId=None            ← the history sync's own insert
id=220  20:01:56  0.40U  pumpId=None    tempId=1786262516455   ← still orphaned
        LIVE rows 7, sum 2.80 U, against 2.40 U actually delivered
```

⚠ **This is the same double-count that Phase 10a and 10b now avoid.** The chunked path does not
inherit the fix.

### What was ruled out, and what is left

The reconciliation logic is **not** the fault. Restoring the marker by hand and forcing a read proved
the function runs, finds the right entry, and behaves correctly:

```
20:09:36  ⚠ Pending bolus temporaryId=1786262516455 matches seq=161753, but history sync has already
          imported that entry (watermark 161753) … Settling now would put one pumpId on two rows,
          so it is NOT done
```

That is the out-of-order guard firing exactly as designed — a branch that had never been exercised
until this run, now confirmed.

**The actual fault is that the marker read blank at the one moment it mattered.** It was on disk at
20:01:57, immediately after the kill:

```
ypso_pending_bolus = 1786262516455|0.4|161752
```

and by the reconciliation at 20:03:33 it was empty. The timing shows the function returned instantly —
8 ms between `correctClockIfDrifted` at 20:03:33.227 and `syncHistoryToAaps` at 20:03:33.235, where a
populated marker would have spent ~20 s in `readHistory`. **Every branch that clears the marker logs,
and none of them logged.** The only silent path is `if (raw.isBlank()) return`.

⚠ **Nothing here explains what cleared it, and this is recorded as an open defect rather than a
guess.** Candidates worth checking in order, none of them verified:

1. Whether `settlePendingBolus` clears the marker on a chunk boundary in a way that races the next
   chunk's `openPendingBolus` — the chunked path writes and clears it once per chunk, ~8 s apart,
   which the single-bolus path never does.
2. Whether the marker's `preferences.put` from a chunk coroutine is flushed before the process dies,
   given it was read back from disk once but may have been re-written since.
3. Whether AAPS's startup `setNewBasalProfile` at 20:03:11 — which this rig does on connect — reaches
   a status read that consumed the marker before the 20:03:33 one.

**Until it is understood, the single-bolus fix should not be assumed to cover chunked delivery.** The
shipped default is `unlimited`, which does not chunk, so the exposure is limited to a user who has
deliberately selected a chunked speed.

## Results

| step | date | outcome |
|---|---|---|
| 12d hand-stop under a running TBR | 2026-08-09 | ✅ **PASSED** — one record, closed then reopened correctly |
| 10d chunked sequence killed | 2026-08-09 | ❌ **FAILED** — one orphan (correct) but never reconciled; 2.80 U recorded against 2.40 U delivered |
| 10c real range loss | 2026-08-10 | ✅ **PASSED** — one live row, reconciled on reconnect |

## ✅ 10c PASSED — 2026-08-10, and range loss really does reach the driver differently

Tester carried the pump out of range mid-bolus; the phone's adapter was left up throughout.

```
08:41:01.934  Bolus 5.0 U requested. Newest event sequence before sending: 161803
08:41:01.988  Provisionally recorded 5.0U under temporaryId=1786308061935
08:41:14.015  Disconnected: reason=10                     ← supervision timeout, not an adapter kill
08:41:18.486  Bolus NOT CONFIRMED … The pump reported at least 2.49 U before the log was read
08:41:18.497  … the pending marker is left in place
08:41:40      BLE connect failed with status 133          ← device not found; out of range
08:41:57      BLE connect failed with status 133
08:45:13.870  Reconciled … temporaryId=1786308061935 settled to 5.0U under pumpId=161804, new=true
```

```
id=227  08:41:02  5.00U  pumpId=161804  tempId=1786308061935   valid=1  ref=None
id=228, id=229    superseded versions under ref=227
                        LIVE ROWS: 1     5.00 U
```

### The difference the step exists to expose is real, and it is visible in two numbers

§10c predicted that range loss reaches `ReconnectManager` differently from `svc bluetooth disable`,
because the adapter stays up and the link times out instead. It does, and the driver's own log
distinguishes them without ambiguity:

| | disconnect reason | reconnect failure |
|---|---|---|
| `svc bluetooth disable` (10a) | `reason=1` | `status -100` |
| carried out of range (10c) | **`reason=10`** | **`status 133`** |

`reason=10` is a supervision timeout and `133` is the GATT "device not found" — the pump is simply not
there to answer. **10a passed and this passed too, so the difference did not become a finding**; but
it is now recorded, because the next person to see `status 133` should know it means distance rather
than a broken adapter.

### And it exercised a branch the scripted runs never did

⚠ **The pump reported at least 2.49 U of the 5.0 U before the log went out of reach**, and the driver
still refused to claim it: `success=false`, provisional left standing at the **requested** 5.0 U, and
the marker held. Reconciliation four minutes later settled it to the pump's own figure — **5.0 U, so
the bolus did complete** after the link died, which is §4g's premise observed once more rather than
argued.

Note the timing: **the reconciliation happened on the first connection after the pump came back**, not
on a timer. Nothing had to be scheduled.

### ⚠⚠ [verified] 2026-08-10 — 10d re-run twice. It is reproducible, and it is **not** about chunking

Both ends of the marker were instrumented first (`Pending marker WRITTEN` / `READ`), purely
additively, so these runs report the cause as well as the frequency.

| run | kill lands | marker on disk after kill | result |
|---|---|---|---|
| 10d #1 (08-09) | on chunk 6's write, **<1 s** after it | present | ❌ 7 rows, 2.80 U, 1 orphan |
| 10d #2 | on chunk 6's write, **<1 s** after it | **absent** | ❌ 7 rows, 2.80 U, 1 orphan |
| 10d #3 | in the gap after a chunk **settled** | absent, *correctly* | ✅ 6 rows, 2.40 U, **0 orphans** |

```
#2   09:05:26.225  Pending marker WRITTEN: '1786309526218|0.4|161813'
     09:05:26      am force-stop                              ← same second
     marker on disk: EMPTY
     09:07:52      Pending marker READ: '' (nothing pending)
     id=246  0.40U  pumpId=161815  tempId=None      ← the sync's insert
     id=240  0.40U  pumpId=None    tempId=…526218   ← the orphan

#3   09:12:46.744  Pending marker WRITTEN: '1786309966740|0.4|161820'
     09:12:50      am force-stop                              ← 3.3 s later, after that chunk settled
     6 chunks, all carrying both ids, 0 orphans
```

### The corrected diagnosis, which overturns what was written yesterday

Yesterday this was recorded as "the chunked path does not inherit the fix". **That framing was wrong.**
Run #3 chunked exactly the same way and came out perfect. The discriminator is not chunking — it is
**how long the marker had between being written and the process dying**:

| run | write → kill | outcome |
|---|---|---|
| 10b single bolus | **6 s** | ✅ marker survived, reconciled |
| 10d #3 | n/a — already settled and cleared | ✅ nothing pending, nothing lost |
| 10d #1 | **<1 s** | ❌ |
| 10d #2 | **<1 s** | ❌ |

**The defect is a durability window on the marker write, and chunking only multiplies the exposure.**
A single bolus writes the marker once; a 15-chunk sequence writes it fifteen times, so it meets the
window fifteen times as often. That is why it surfaced here and not in 10b.

⚠ **This means the reassurance written yesterday — "leave bolus speed at `unlimited` and this cannot
reach you" — is too strong and is withdrawn.** A single bolus whose process dies inside that same
sub-second window loses its marker in exactly the same way. The exposure is narrow, but it is not
zero, and it is not confined to chunked delivery.

### What is still unexplained, and is not being guessed at

Run #1's marker was **on disk** after the kill and yet read blank at reconciliation 90 s later. Run #2's
never reached disk at all. Both failed identically. A lost write explains #2; it does not explain #1,
and nothing here explains how a value present on disk becomes absent to the app that reads it next.
`SharedPreferences` writing via `apply()` — asynchronous, with a `.bak` restore path on a torn write —
is the obvious place to look, but it has **not** been demonstrated and no fix should be written on it.

### The shape of the fix, when it is written

The marker must be durable **before** the pump is told, not eventually. That means a synchronous
commit rather than a fire-and-forget put, and it needs to be established that whatever `Preferences`
does underneath actually offers one. A second, independent option is to stop relying on a single slot
and derive the pending set from the database instead — the orphan is already identifiable as a live
bolus with a `temporaryId` and no `pumpId` — but that needs `PersistenceLayer`, which no real-pump
driver in this tree injects.

**Neither has been chosen.** What is established is the trigger, the reproducibility, and that the
reconciliation logic itself is sound — proved separately when a hand-restored marker made the
out-of-order guard fire correctly.

---

# ✅ [verified] 2026-08-10 — the marker is gone, and the reboot heals

The pending-bolus marker has been **deleted**. The history sync now settles a stranded provisional
from the pump's own entry instead of inserting beside it. Nothing has to survive a crash any more —
the stranded row is a committed SQLite row and was present after every force-stop Phase 10 threw at
it, so the reboot repairs the record rather than duplicating it.

## ⚠ The first hardware run of the new matcher was wrong, and the test is why we know

It settled **yesterday's** stranded chunk against **today's** bolus, leaving two pairs of rows sharing
one `pumpId` — the exact corruption the old design's watermark guard existed to prevent:

```
09:51:14  seq=161824 settled … temporaryId=1786309526218  (2 candidates at this amount; took the oldest)
          pumpId=161824 then on id=240 (09:05:26) AND id=265 (09:49:13)
          pumpId=161825 then on id=267 (09:49:21) AND id=271 (09:49:35)
```

Two mistakes, both in the matcher:

1. **"Take the oldest" is exactly backwards.** Stranded rows left by *earlier* failures are the oldest
   ones present, so "oldest" reliably picks the wrong bolus. Matching is now **nearest in time**,
   within [STRANDED_MATCH_TOLERANCE_MS] — a provisional is written a second or two before the pump
   logs its entry and `correctClockIfDrifted` holds the pump within 10 s of the phone, so a genuine
   pair is seconds apart. The bad match was **44 minutes** apart.
2. **Nothing checked that the pump entry was unclaimed.** If a row already carries that `pumpId` the
   entry is already recorded and there is nothing to settle.

Both guards **fail closed**: no match means the caller inserts, and the worst case is the
double-count this exists to avoid, which is recoverable by hand. A wrong match is not.

## The corrected matcher, run twice on hardware

### Run A — chunked, process killed on a chunk write

The timing that defeated the marker: the kill landed at 10:13:32 with chunk 4's provisional written at
10:13:32.806, **the same second**.

```
10:14:33.727  seq=161833 settled a stranded provisional rather than inserting beside it —
              temporaryId=1786313612807, 0.4U, new=true, 0s apart

id=281  10:13:09  0.40U  pumpId=161830      id=285  10:13:25  0.40U  pumpId=161832
id=283  10:13:17  0.40U  pumpId=161831      id=287  10:13:32  0.40U  pumpId=161833
                    4 rows, 1.60 U — exactly what the pump delivered
```

Entries 161830–161832 were already claimed by their live-settled rows, so the unclaimed check let
them fall through to the ordinary sync. **Only the genuinely stranded chunk triggered a settle**, and
`0s apart` is the matching being exact rather than lucky.

### Run B — single bolus, link cut 2.2 s in

Run at `unlimited`, because **the single-bolus crash path had not been re-verified since the marker
was removed** — 10a, 10b and 10c all passed *with* it, and that was the real gap.

```
10:18:38.126  Bolus 2.0 U requested … 10:18:40 svc bluetooth disable
10:18:49.189  Bolus NOT CONFIRMED … The pump reported at least 0.91 U before the log was read
10:23:55.388  seq=161834 settled a stranded provisional … 2.0U, new=true, 0s apart

id=292  10:18:38  2.00U  pumpId=161834  tempId=1786313918127      1 row, 2.00 U
```

### Before and after, same test, same rig

| | rows | recorded | delivered |
|---|---|---|---|
| chunked kill, **marker design** | 7 | **2.80 U** | 2.40 U |
| chunked kill, **sync settles** | 4 | **1.60 U** | 1.60 U |
| single bolus, link cut | 1 | **2.00 U** | 2.00 U |

**0 orphans and 0 duplicate `pumpId`s anywhere in the database** after both runs.

## What did not change, deliberately

- **The provisional still exists**, written before the pump is told. It is the anti-stacking guard:
  without it AAPS is blind to delivered insulin until the next sync — up to the 20-minute idle poll
  (§8.2) — and with SMBs enabled the loop would re-dose into that gap. Over-counting IOB is the safe
  failure; under-counting is not.
- **`success=false` on an unconfirmed bolus, and the AAPS alarm.** The delivery genuinely was
  unconfirmed at the time. What changed is that the record is repaired at the next sync instead of
  standing wrong.
- **An unmatched entry is still inserted, never dropped** — a bolus the driver cannot explain is
  still a bolus that went in.

## Cost

`PersistenceLayer` is now injected into the driver, which no other real-pump driver in this tree does.
That is the whole architectural price, and it buys the **deletion** of a mechanism rather than the
addition of one: the `PendingBolus` preference, `reconcilePendingBolus`, the per-chunk marker writes
and the SDK `beforeSend` baseline parameter are all gone.

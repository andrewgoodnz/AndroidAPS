# YpsoPump driver for AndroidAPS

An AndroidAPS pump driver for an **unlocked plaintext research YpsoPump**, built on AAPS 3.4.2.4.

It bonds, connects, authenticates in plaintext with no key exchange, reads everything it needs, and
writes the pump clock, a standard bolus, a temporary basal and the 24-slot basal profile. Every one
of those was gated behind its own hardware test on saline, and every one is confirmed by reading the
pump back rather than by the write callback.

**Every test to date has been on saline, off-body, cannula to air.** Nothing here has moved insulin
into a person. What stops it doing so is the cartridge, not the code — see
[Before insulin](#before-insulin).

Three documents, and they have different jobs:

| | |
|---|---|
| **`README_YPSO.md`** (this file) | What the driver is, what it can do, and how to bring one up |
| **`PORTING_NOTES.md`** | What the pump has actually been observed to do, and why the code is shaped this way. The long one |
| **`SALINE_TEST_PROCEDURE.md`** | The hardware procedures, one per capability, each written before its code |

---

## Which pump — and why it cannot talk to a standard one

**This driver only works against an unlocked research pump. It cannot talk to a normal YpsoPump, and
that is a property of what the code contains rather than a setting.**

A production YpsoPump speaks an encrypted protocol whose session keys come from a Proregia key
exchange. vicktor's SDK implements both, in its `crypto/` and `keyexchange/` packages. **Neither was
vendored here.** There is no `PumpCryptor` to leave null, no relay client to accidentally call, and
no way to configure one in. A standard pump will bond and then refuse to answer.

That was a deliberate choice with two consequences worth knowing. It removed gRPC and Google Play
Integrity from the dependency tree — the only third-party library this driver adds is Nordic's BLE
library. And it means the driver's attack surface is genuinely small: it can only ever have
plaintext conversations with a pump that has been unlocked to have them.

**Tested against:** `YpsoPump_10074292`, MAC `EC:2A:F0:01:22:34`, **firmware V05.02.03**. Every
finding in `PORTING_NOTES.md` is from that unit. Other firmware is unknown territory — the wire
envelopes turned out to be a property of each *characteristic* rather than of the firmware, which is
mildly encouraging, but nobody has checked.

---

## What it can do

Everything in this table has been confirmed on hardware, on saline, and the "confirmed by" column is
the evidence rather than the intent. The driver never reports success on the strength of a
successful GATT write — **this pump does not NAK a bad write**, and an entirely wrong auth password
once produced a perfectly successful write that only failed on the next *read*.

| | Capability | Confirmed by |
|---|---|---|
| Read | status, 24 basal rates, clock, history, alert count | parity against the pump's own display |
| Write | pump clock | read-back, the display, and a type 12/13 log entry |
| Write | **standard bolus**, and abort | the event log's type-2 entry and its amount |
| Write | **temporary basal**, set and cancel | status byte 10, plus the effective-rate arithmetic |
| Write | **basal profile**, all 24 slots | all 24 read back, and the pump's display |
| Sync | boluses, TBRs and TBR stops into `PumpSync` under the pump's own sequence number | IOB on the AAPS home screen |
| Sync | **pump history → AAPS treatments**, on by default | a bolus given on the pump's own menu becomes insulin on board |
| Detect | pump stopped, by hand or by alarm → a 0% `PUMP_SUSPEND` record so AAPS stops counting basal IOB | the record, and the flat basal graph |
| Detect | pump alerts → urgent AAPS notification with sound | an occlusion forced on saline, 26 s end to end while connected |
| Correct | the pump's clock, automatically, above 10 s of drift and after any DST or timezone change | read-back |

**Cost:** a quiet status poll costs **~580 ms**. History sync asks the pump one question — "has
anything happened?" — and only reads the 100-entry window when the answer is yes. Without that it
would add ~20 s to every status read, which a manually-driven pump survives and a loop issuing an
SMB every five minutes does not.

---

## Known limitations

These are the things a new user should be told before they find them.

### The pump logs no cannula-priming event, so CAGE cannot restart itself

**There is no `type=25` anywhere in this pump's history.** AAPS's site-change age timer (CAGE) is
driven by a `CANNULA_CHANGE` therapy event, the driver maps type 25 to exactly that, and **the pump
has never once emitted a type 25** — a cartridge change through its own menu produces cartridge and
prime entries and no cannula event. **You must record site changes by hand in AAPS**, exactly as an
MDI user does. This is a property of the pump's log, not a gap in the driver, and no amount of work
here will change it.

The neighbouring events *do* work, which is what makes this one easy to assume is fine: a prime
above 9 U becomes a `TUBE_CHANGE`, and a battery removal becomes a `PUMP_BATTERY_CHANGE`. So IAGE
and battery age fill themselves in, and CAGE silently does not.

### The event log holds 3000 entries — roughly three months

And the arithmetic that gets there is not obvious: **a profile percentage change for exercise
rewrites all 24 slots**, and reverting costs another 24. At ~48 entries an episode, 4–5 times a
week, that is ~200–240 entries a week against a 3000-entry log. **What happens when it wraps has
never been observed**, and it matters: the history sync's watermark assumes the pump's sequence
numbers only ever go up. If they restart at the cap, the driver re-baselines loudly rather than
silently — that path is coded and logged — but nobody has watched it happen.

### The extended and combined bolus are refused

Refused in `YpsoPumpManagerImpl` before any BLE traffic, and **not advertised to AAPS** — the
extended-bolus button and its Treatments tab do not appear. The pump can do both; this driver has
never been asked to, and the standard bolus should soak first. `loadTDDs` is likewise refused and
unadvertised, though the data exists in the pump's `type=23` entries and would not be hard.

### AAPS learns of a pump alarm at the next poll, and that can be 20 minutes

AAPS polls when its command queue has work and this driver disconnects when it is empty, which is
how every AAPS pump driver but Insight behaves. The measured idle cadence is ~20 minutes, and one
observed occlusion took **27 minutes** to reach the phone. Three things bound the harm and none of
them is software: the pump alarms locally and loudly, the user is normally near it, and this is not
a failure mode the phone was ever the primary defence against.

### Two of byte 0's 256 delivery-mode values are identified

`3 = Stopped`, `10 = Running`. Every state reachable on the hardware came back as one of the two —
including during a bolus, during an alarm, and after one — and there is no pause state on this pump
at all. Two situations have still never been read: **cartridge out or priming**, and **reservoir
empty**. An unidentified value makes AAPS treat the pump as suspended, which holds the loop off, and
deliberately writes no therapy record.

### Smaller ones

- **A low reservoir is not a pump alert.** The mylife controller computes it on the phone; nothing
  in the pump's alert log will ever surface it.
- **A temporary basal is a percentage of the pump's own basal program**, so crossing an hour
  boundary into a different programmed rate changes the absolute rate. Inherent to a percent-only
  pump; AAPS re-evaluates each loop iteration, which is what limits it.
- **The pump has no concept of a time zone.** Its clock is local wall-clock seconds from a written
  hour/minute/second. The driver rewrites it on any DST or timezone change, and `canHandleDST()`
  stays false so AAPS's own safeguard is untouched.
- **Firmware displays as `0.2.2.1`** where the pump says V05.02.03. Cosmetic; the decode joins raw
  bytes as decimal.
- **Two controllers can reach one pump.** This driver holds its GATT connection rather than
  releasing it between commands, so do not put it on a phone that also runs a licensed controller
  paired to the same pump.

---

## Bringing one up

For anyone else with an unlocked pump. Allow an evening.

### 0. Before anything — the device and the pump

- **Do not use a phone running another pump controller** against the same pump. This driver holds
  its connection, and can keep a licensed app from reaching the pump to deliver insulin.
- **Do not point it at a pump that is treating someone.** Both a research pump and a standard one
  advertise as `YpsoPump_*` and both show up in the scan; the pairing screen makes you confirm the
  serial for exactly this reason.
- Android **12+** (`minSdk 31`), USB debugging on, Bluetooth scan/connect permission granted.
- **If you share a Nightscout instance with a real loop, turn NSClient uploading off, or point the
  test device at a separate instance.** A saline bolus that reaches a real therapy record is insulin
  the record says was given and the patient never received. Inbound is also live: a 0% TBR once
  arrived on the research pump from another pump's loop through a shared Nightscout, and looked
  exactly like an unexplained pump-side change.

`INSTALL_FAILED_VERIFICATION_FAILURE` on install is not a signing problem — it is on-device install
verification (Play Protect "Verify apps over USB", or Samsung Auto Blocker). Turn it off on the test
device. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` *is* a signature mismatch, needs an uninstall, and an
uninstall **deletes the AAPS database** — export settings *and* database first, and check both
landed, because they are two separate actions.

### 1. Build

```bash
export JAVA_HOME=/c/Program\ Files/Android/Android\ Studio/jbr    # or your own JDK
./gradlew :pump:ypso:sdk:testFullDebugUnitTest      # 65 tests — the wire decodes
./gradlew :pump:ypso:testFullDebugUnitTest          # 35 tests — the plugin and suspend logic
./gradlew :app:assembleFullDebug
```

⚠ **`app/build.gradle.kts` fails the build if any tracked file is modified** and
`Versions.appVersion` contains no `-`. Commit first, or suffix the version (`3.4.2.4-dev`) while
iterating — and revert the suffix before committing. See `PORTING_NOTES.md` §9a for the rest of the
build's sharp edges, including the two-Gradle-daemon lockup that looks like a file permission error.

### 2. Make the tab visible

Config Builder → Pump → YpsoPump → tick the **visibility** checkbox. Pump fragments default to
invisible, so without this the status screen — with Refresh, Read history and Set pump clock — is
simply not there, and it is the screen everything below is done from.

### 3. Bond

Preferences → YpsoPump → **Pair YpsoPump**. Pick your pump, confirm the serial, then confirm the
code on the pump itself.

If nothing appears in the list, lower `YpsoRssiThreshold` before assuming a scan problem. A pump
that is currently *connected* may stop advertising, so if you are re-pairing, forget it in Android's
Bluetooth settings first.

### 4. Connect, and read

Watch the log for `Authenticated (plaintext, no key exchange)` and then `Session ready`.

If it connects but the status read fails, flip the `YpsoLegacyAuthSalt` preference and retry — the
two known drivers use different auth salts. The default is confirmed correct for V05.02.03, so on
that firmware suspect the framing before the salt.

### 5. Parity check — do not skip this

Press Refresh and compare against **the pump's own display**: reservoir, battery, delivery state,
active program, and all 24 basal rates. Then press **Read history** and compare types and timestamps
against the pump's history screen.

This step is not a formality. It is what found the battery decode bug and the delivery-mode bug,
neither of which was visible from the driver's own output — a decode that is wrong produces a
confident number, not an error. Do it again after any change to the decoding.

### 6. Then the saline tests, in order, one at a time

`SALINE_TEST_PROCEDURE.md`, Phase 1 onwards. **On saline, off-body, cannula to air**, with the pump
where you can see its display.

The rule the whole procedure is built on: **remove one refusal at a time, each with its own test,
never in a batch**, and each test names the read-back or history entry that confirms it *before* the
code is written. That ordering matters more than it sounds. Three checks in these notes initially
"passed" against a test that could not have failed for any input, and the ones that caught real
defects were the ones written while nobody was under pressure to accept a weaker answer.

Phase 1 (the clock write) is also the quickest way to prove the write path still works after any
change to the framing code.

---

## Before insulin

Stated plainly, because this is the question the whole project is pointed at.

**One correctness gate is open:**

- **Phase 6b has never been run on the build that fixes it.** The 0% suspend record must be held
  continuously while the pump is stopped. The fix for the renewal gap was committed *after* the only
  long unattended run, so what that run measured was the defect. `SALINE_TEST_PROCEDURE.md`
  **6b-RERUN** is the procedure; it needs a stopped pump and three unattended hours, and it decides
  on the database rather than the log.

**Two things only time can answer:**

- **What the event log does at 3000 entries**, roughly three months out.
- **How the driver behaves across a real DST transition.** The code is there and tested in unit
  tests; no actual transition has happened while it was running.

**And two states never read**, both free at the next cartridge change: byte 0 with the cartridge out
or priming, and with the reservoir empty.

**None of the above is a capability gap.** The driver can do what it needs to do. What is missing is
observation — and the decision to move from saline to insulin is a human one, taken by the person
whose body it is, not a task in a checklist.

---

## Layout

```
pump/ypso/                  the AAPS driver — plugin, service, pairing screen, overview fragment
pump/ypso/sdk/              vicktor's ypso-sdk, vendored, minus crypto/ and keyexchange/
pump/ypso/sdk-common/       vicktor's pump-common, vendored verbatim (the PumpManager contract)
```

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

Outside `pump/ypso/` this branch changes **eight lines in six upstream files** — the Gradle includes,
the app's dependency, Dagger's two component lists and the plugin map, and one version-catalogue
entry. `PORTING_NOTES.md` §9 has the table, the rebase workflow, and the one command that says
whether that surface has grown.

---

## Credit

vicktor's `ypso-sdk` and `pump-common` are the protocol layer this is built on; the wire format work
here is corrections to a vendored copy, not original reverse engineering. Andy Rozman's 2022
YpsoPump driver was the parts donor for the domain enums and the response-translator logic — that
module lived in this tree as `:pump:ypsopump` until it had been fully mined, and is now in git
history. The AAPS drivers this one is shaped on, and reads like: Medtrum for the service and state
holder, DanaRS for pairing, Medtronic for the clock-correction convention, Combo for the
stopped-pump record.

And the corrections that mattered most came from **the person who actually uses the pump** — that
the bolus increment is a menu setting and not a delivery constraint, and that a fourteen-hour
stopped-pump scenario corresponds to nothing anyone does. Both were right, and both were against
what this file's ancestor asserted at the time.

# YpsoPump driver for AndroidAPS

An AndroidAPS pump driver for an **unlocked plaintext research YpsoPump**, built on AAPS 3.4.2.6.

It bonds, connects, authenticates in plaintext with no key exchange, reads everything it needs, and
writes the pump clock, a standard bolus, a temporary basal and the 24-slot basal profile. Every one
of those was gated behind its own hardware test on saline, and every one is confirmed by reading the
pump back rather than by the write callback.

It also delivers a **slow ("chunked") bolus** — a large dose given as several small ones a few
seconds apart, for comfort — which passed its own hardware phase on 2026-08-04. It is **off by
default** and cannot happen until the bolus speed is changed by hand.

**Every *test* to date has been on saline, off-body, cannula to air** — and that is still literally
true, because the one period of real insulin use verified nothing: it was aborted after about ten
minutes by the defect it found (below). Treat this driver as saline-verified and no more.

## ⚠⚠ Not fit for closed-loop use with insulin — read this before anything else

**On 2026-08-04 this driver ran a closed loop on a person, and the trial was ended after about ten
minutes by a defect no bench test had reached.** The pump **refused a 0% temporary basal while a 400%
one was running**, twice. Zero temps are not incidental to oref1 — one accompanies every SMB, and it
is how the loop backs off insulin when glucose falls — so this is the loop losing the ability to
deliver *less* on demand.

Nothing was dosed blind: AAPS's accounting was correct throughout and knew exactly what was running.
The high temp simply ran to its natural end because nothing could replace it, and was cancelled by
hand. The pump went back on saline the same day.

**`SALINE_TEST_PROCEDURE.md` Phase 11 is written to settle it and has not been run.** Two hypotheses
survive — 0% is never accepted, or nothing can be replaced while a temporary basal is running — and
only one combination was ever exercised. **Cancelling to 100% works**, so if it is the replacement
rule the fix is small.

Until Phase 11 passes: **do not close the loop with insulin in this pump.** Manual and open-loop use
are a separate question this finding does not settle.

⚠ **A second gate is open and it is not about the pump.** **Phase 10 — what AAPS's database holds
after a bolus is interrupted** by a dropped link or a killed app — is written and has never been run,
and the code predicts it leaves a bolus counted twice. An interrupted bolus is a record to check by
hand. Read [Before insulin](#before-insulin) for the specifics, not just this paragraph.

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

**There is no `type=25` anywhere in this pump's history**, and it is worse than an omission. AAPS's
site-change age timer (CAGE) is driven by a `CANNULA_CHANGE` therapy event; the driver maps type 25
to exactly that, and the pump has never emitted one.

**A cartridge change on 2026-08-03 with a deliberate 30 U cannula prime settled it:**

```
06:28:06  INSULIN_CHANGE   rewind
06:31:24  TUBE_CHANGE      14.35 U prime (tubing)
06:35:13  TUBE_CHANGE      30 U prime — the cannula prime
06:37:43  INSULIN_CHANGE   second rewind
```

The cannula prime arrived as `type=4`, **the same event as the tubing prime**. So the pump gives no
signal that separates the two, and there is nothing to remap CAGE onto — the only candidate is
indistinguishable from an event that is not a site change.

**You must record site changes by hand in AAPS**, exactly as an MDI user does. This is a property of
the pump's log, not a gap in the driver, and no amount of work here will change it.

The neighbouring timers *do* fill themselves in, which is what makes this one easy to assume is
fine: the rewind becomes an `INSULIN_CHANGE` (IAGE) and a battery removal becomes a
`PUMP_BATTERY_CHANGE` (BAGE). IAGE and battery age work; CAGE silently does not.

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

**The extended bolus works and is still refused, which is now a decision rather than a gap.** Phase
8a ran it on saline (`PORTING_NOTES` §8.6): the pump accepts one, enforces a **15-minute minimum**,
and logs it under its own event type 3 carrying **no duration**, so it cannot be reconstructed from
history alone. It was wanted for comfort on large boluses, and at a 15-minute floor a 10 U dose is
0.67 U/min — far slower than the ~12 U/min asked for. The chunked bolus is the answer to that
question instead, so the extended bolus stays refused and unadvertised with nothing depending on it.

### AAPS learns of a pump alarm at the next poll, and that can be 20 minutes

AAPS polls when its command queue has work and this driver disconnects when it is empty, which is
how every AAPS pump driver but Insight behaves. The measured idle cadence is ~20 minutes, and one
observed occlusion took **27 minutes** to reach the phone. Three things bound the harm and none of
them is software: the pump alarms locally and loudly, the user is normally near it, and this is not
a failure mode the phone was ever the primary defence against.

### Two of byte 0's 256 delivery-mode values are identified

`3 = Stopped`, `10 = Running`, and **every state reachable on this hardware has now been read** —
during a bolus, during an alarm, after one, through a whole cartridge change, and with the cartridge
run dry mid-bolus. There is no pause state on this pump at all. An unidentified value would make AAPS
treat the pump as suspended, which holds the loop off, and deliberately writes no therapy record.

**What separates those states is the reservoir field, not byte 0.** It reads `FF FF FF FF` (−1,
"unknown") while the cartridge is out or being changed, and a known **`0`** when the cartridge has run
empty — so the driver can tell "no cartridge" from "empty cartridge" even though the delivery mode is
`3` for both. Running dry also raises a real pump alert (`104 Cartridge Empty`, urgent); a *low*
reservoir never does.

### "Fallback to SMB. Not enough TDD data." is **not** this driver's missing TDD support

Worth stating plainly because the inference is so natural and so wrong. This driver reports
`supportsTDDs = false` and `loadTDDs` is unimplemented (§8.6), so when Dynamic ISF starts complaining
about TDD the obvious conclusion is that the two are connected. **They are not, and implementing
`loadTDDs` would not fix it.**

`OpenAPSSMBPlugin` gets its TDD from `TddCalculator`, and `TddCalculatorImpl.calculateInterval`
computes it **entirely from AAPS's own database** — boluses from `getBolusesFromTimeToTime`, plus
basal integrated from the active profile and any temp basals, in 5-minute steps. It never reads a
pump-reported TDD record. What the pump could tell us about its own daily totals is irrelevant to
this code path.

The actual requirement is **seven consecutive complete days**:
`tddCalculator.calculate(7, allowMissingDays = false)` returns `null` unless every one of the seven
produced a value, and a day produces `null` if the profile was not active for even one 5-minute step
in it, or if no insulin at all was recorded. Basal alone is enough — a day with no boluses still
totals 24 × the profile rate.

Measured on the phone 2026-08-04, and it shows the shape exactly:

```
tdd1D=37.0   tddLast24H=36.0   tddLast4H=9.3   tddLast8to4H=4.8   tdd7D=null
28/07 null   29/07 null   30/07 null   31/07 null   01/08 28.8   02/08 28.8   03/08 37.0
```

Only `tdd7D` was missing, because the four days before 1 August had no profile coverage in that
database. It resolves itself once seven unbroken days have accumulated — no action, just time — and
any gap in profile coverage restarts the count. Until then AAPS runs the documented SMB fallback,
which is a safety behaviour and not a fault. Turning Dynamic ISF off also removes the fallback, but
that is a therapy decision and nothing to do with the pump.

### ⚠ Every AAPS restart suspends the loop for ~30 s and cancels any running temp basal

**Measured on hardware, 2026-08-04.** This is not a fault to report; it is what the driver does, and
it is documented here because on a looping phone it is visible and looks alarming.

On every app start AAPS logs `SUSPENDED_BY_PUMP — Pump suspended`, suspends the loop, and issues a
`cancelTempBasal` — **even though the pump is running normally.** About 30 seconds later, when the
first status read comes back, it logs `PUMP_RUNNING — Pump running` and resumes. Timed at **30.3 s**
on the test tablet, on a pump that was delivering throughout with a falling reservoir.

The cause is one line, and it is deliberate:

```kotlin
override fun isSuspended(): Boolean = ypsoPump.lastConnection > 0 && !ypsoPump.isDelivering
```

`lastConnection` is persisted in preferences; `isDelivering` is in-memory and starts `false`. So
immediately after a restart both conditions hold, and the driver reports "suspended" until it has
actually read the pump.

**Why it is left this way.** Before the first read the pump's state is genuinely unknown, and the two
possible guesses are not symmetric: guessing "suspended" withholds dosing for half a minute, while
guessing "running" would let the loop dose into a pump that might be stopped. **Medtrum — the driver
this one is shaped on — behaves identically** (its `pumpState` starts at `NONE`, and `NONE < ACTIVE`
reads as suspended). Dana and Diaconn guess the other way.

**What it actually costs you, and the one part that matters:**

- ~30 s of no loop after each restart, self-healing. Harmless.
- A `SUSPENDED_BY_PUMP` / `PUMP_RUNNING` pair in the therapy log on each restart. Noise, but it is
  noise in the record you would use to reconstruct a day.
- ⚠ **A running temporary basal is cancelled** — this is AAPS's `suspendLoop` doing it, not the
  driver. After a phone reboot or an app restart mid-TBR, that TBR is gone and the loop must
  re-establish it on its next iteration. It errs towards less insulin, not more.

If you want the loop up faster after a restart, open the YPSO tab and press **Refresh** — the
suspension ends on the first successful status read, not on a timer.

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
./gradlew :pump:ypso:sdk:testFullDebugUnitTest      # 84 tests — the wire decodes
./gradlew :pump:ypso:testFullDebugUnitTest          # 47 tests — the plugin, suspend and event policy
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

## Putting this build on a phone that loops

The test tablet and the phone someone actually wears are different problems. This section is the
second one. **Everything here is about the app; none of it selects the pump** — see the last step.

### 1. Build the full flavour, from a clean tree

```bash
export JAVA_HOME=/c/Program\ Files/Android/Android\ Studio/jbr
./gradlew --stop; ./gradlew :app:clean; ./gradlew :app:assembleFullRelease
```

**It must be the `full` flavour.** `pumpcontrol` and `aapsclient` are restricted builds — `APS` is
`BuildConfig.FLAVOR == "full"`, so a closed loop is simply not present in the others.

**`Versions.appVersion` must contain no `-`.** Two separate things turn on that:

- At build time `isMaster()` is true, which enforces `allCommitted()` — **the build fails outright if
  any tracked file is modified.** That is a feature here: it means a built APK corresponds to a commit
  you can go back to.
- At run time `Config.isDev()` is false, so `isEngineeringModeOrRelease()` is true and no
  `engineering_mode` marker file is needed. `isDev()` trips on a `-` **or on any letter**, so do not
  put a git hash in it.

Confirm what came out, rather than assuming:

```bash
cat app/build/outputs/apk/full/release/output-metadata.json
```

This build: **`versionName 3.4.2.6`, `versionCode 1500`, applicationId `info.nightscout.androidaps`,
variant `fullRelease`** — no suffix.

### 2. ⚠ Sign it — Gradle does not

**There is no release signing config in this project**, so the output is
`app-full-release-unsigned.apk` and **Android will not install it at all**. This is not a warning that
can be ignored; it is the whole reason step 2 exists. Do not "fix" it by adding a `signingConfigs`
block: `app/build.gradle.kts` is one of the seven shared files this branch touches (§9), and signing
does not need a build change.

⚠⚠ **The two devices are signed with different keys, so one build needs two signatures.** This was
measured on 2026-08-04, not assumed:

| Device | Signed with | Certificate SHA-256 |
|---|---|---|
| **Main phone** (the therapy one) | `aaps_key`, alias `key0` | *needs the keystore password to read* |
| **Test tablet** (`192.168.1.39:5555`) | Android **debug** key, `~/.android/debug.keystore` | `aea08c92…555b7b61` |

The tablet's installed AAPS was pulled and read with `apksigner`: `CN=Android Debug`, matching this
machine's `debug.keystore` exactly — so the tablet has been carrying `assembleFullDebug` builds. **The
main phone uses `aaps_key`.** Sign the same unsigned APK twice rather than building twice; the build is
identical either way, and only the signature differs.

**Build type does not affect looping.** `Config.DEBUG` is declared and then used nowhere in the
codebase, and the loop gates are the `full` flavour plus a version with no `-` (below). A release build
signed with the debug key therefore runs perfectly well on the tablet.

⚠⚠ **But do not put a release build on the test tablet, because it cannot be instrumented.** `run-as`
— which is how every hardware phase pulls `androidaps.db`, the arbiter the log is explicitly not —
works only on a **debuggable** package, and `debuggable` comes from the build *type*, not the signing
key. On a release build it fails with `run-as: package not debuggable`, and nothing on the host side
gets around it. Measured on 2026-08-04 by doing exactly this and losing the instrumentation.

So the split is:

| Device | Build | Why |
|---|---|---|
| **Test tablet** | `:app:assembleFullDebug` | `run-as` works, so the database can be read. This is what makes a phase adjudicable |
| **Main phone** | `:app:assembleFullRelease` | Not debuggable, which is right for a therapy device. Nothing is adjudicated here |

The phone's key is `C:\Users\Andrew\StudioProjects\aaps_key` — **PKCS12, one entry, alias `key0`**,
created 2021-06-04.

⚠ **`apksigner.bat` needs `JAVA_HOME` and will not inherit one from Android Studio.** Without it the
only thing it prints is `ERROR: JAVA_HOME is not set and no 'java' command could be found in your
PATH`, which looks like a broken toolchain and is one missing variable. It is a per-window setting, so
a fresh terminal needs it again.

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # PowerShell: $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
APKSIGNER="/c/Users/Andrew/AppData/Local/Android/Sdk/build-tools/36.1.0/apksigner.bat"
cd app/build/outputs/apk/full/release

# --- the main phone: aaps_key ---
"$APKSIGNER" sign --ks "C:\Users\Andrew\StudioProjects\aaps_key" --ks-key-alias key0 \
    --out app-full-release-PHONE-aapskey.apk app-full-release-unsigned.apk

# --- the test tablet: the debug key (password is Android's public default) ---
"$APKSIGNER" sign --ks "C:\Users\Andrew\.android\debug.keystore" \
    --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
    --out app-full-release-TESTTABLET-debugkey.apk app-full-release-unsigned.apk
```

The first prompts for the keystore password. **Leave it prompting** — do not pass `--ks-pass pass:…`
for `aaps_key`, which would put a real password in your shell history. The debug keystore's password is
literally `android` on every Android install and is not a secret.

Then check each one says the key you expect:

```bash
"$APKSIGNER" verify --print-certs app-full-release-PHONE-aapskey.apk      # -> your own DN
"$APKSIGNER" verify --print-certs app-full-release-TESTTABLET-debugkey.apk # -> CN=Android Debug
```

### 3. ⚠⚠ Check the signature matches the phone BEFORE you install

**A different signing key cannot install over the existing app.** It fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only way through that is an uninstall — **which deletes
the AAPS database**: all treatments, all history, the lot. Check first; the check is two commands.

**The only reliable way to read the installed app's certificate is to pull the installed APK and read
it with the same tool.** Both sides of the comparison then come from `apksigner`, in the same format:

```bash
# 1. the certificate the NEW APK is signed with
"$APKSIGNER" verify --print-certs app-full-release-signed.apk | grep -i "SHA-256"

# 2. the certificate the INSTALLED app is signed with — pull it, don't ask dumpsys
adb shell pm path info.nightscout.androidaps          # -> package:/data/app/~~…/base.apk
adb pull /data/app/~~…/base.apk installed-aaps.apk    # paste the path from above
"$APKSIGNER" verify --print-certs installed-aaps.apk | grep -i "SHA-256"
```

**The two `SHA-256` digests must be identical.** If they are, `adb install -r` will work. If they are
not, stop — reaching for an uninstall here is how the database gets lost.

Two traps at this step, both of which look like answers and are not:

- ⚠ **`adb shell dumpsys package …` cannot do this.** It prints
  `signatures:[a1b2c3d4]` — **truncated `hashCode()` values, not certificate fingerprints.** There is
  nothing there that can be compared against a SHA-256, and the short hex string looks enough like a
  digest to be mistaken for one.
- ⚠ **Use `apksigner verify`, not `keytool -printcert -jarfile`.** `minSdk` is 31, so apksigner signs
  with the **v2/v3 schemes and skips v1 (JAR) signing**, and `keytool -printcert -jarfile` only
  understands v1. On a perfectly good APK it reports *"Not a signed jar file"*, which reads exactly
  like a signing failure.

If `pm path` returns several lines (a split install), `base.apk` is the one that carries the
signature.

⚠ **The package is `info.nightscout.androidaps`, not `app.aaps`.** `app.aaps` is the Gradle
*namespace*; the full flavour's `applicationId` is `info.nightscout.androidaps`, and
`dumpsys package app.aaps` returns nothing at all — which reads as "no AAPS installed" and is
wrong.

**The SHA-256 fingerprints must match.** If they do not, stop and work out which key the phone's
build used. Reaching for an uninstall at this point is how the database gets lost.

### 4. Export first — this is the rollback

There is no undo for an app upgrade, so the rollback is a file.

1. In the **currently installed** AAPS: Maintenance → **Export settings**, then Maintenance →
   **Export database**. These are **two separate actions** and people do one and think they did both.
2. Check both files exist on the phone and note their timestamps.
3. Copy them **off** the phone (`adb pull`). A backup that only exists on the device it is protecting
   is not a backup.

### 5. Install over the top

```bash
adb install -r app-full-release-signed.apk
```

`-r` replaces the app and **keeps its data**. Install the **`PHONE-aapskey`** APK on the phone and the
**`TESTTABLET-debugkey`** one on the tablet; they are the same build and only the signature differs.

Two failures, and they mean opposite things:

- `INSTALL_FAILED_VERIFICATION_FAILURE` — on-device install verification (Play Protect's "Verify apps
  over USB", or Samsung Auto Blocker). **Not** a signing problem. Turn it off and retry.
- ⚠⚠ `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — **a signature mismatch. You have the wrong APK for this
  device.** The correct response is to go back and sign with the other key, **not** to uninstall.
  Android will suggest uninstalling; an uninstall deletes the AAPS database, and this error is the one
  case where the obvious remedy is the destructive one. Nothing has been damaged at this point — the
  install simply did not happen.

### 6. ⚠ Verify the data survived — before going anywhere near the pump

Open AAPS and check, in this order:

- **Profiles** are present and the active one is the one you expect.
- **Treatments** — the last few days of boluses and carbs are there.
- **CGM** is reading, with a current value on the home screen.
- The loop is in the mode you left it in.

If any of those is wrong, restore from step 4 now rather than continuing.

### 7. Only then, and separately, select the pump

**Carrying this build and using the Ypso driver are two different decisions**, and doing them in one
step means a problem cannot be attributed to either. The phone can run this APK while still on
**Virtual Pump** indefinitely — the driver is inert until it is selected.

When the pump switch is the intended change: Config Builder → Pump → **YpsoPump**, tick its
visibility box, then follow *Bringing one up* from step 3 (bond) — including the **parity check**,
which is the step that catches a decode regression, and which nothing else will catch for you.

⚠ Also read *Before insulin* below, in particular the open Phase 10 gate. It describes a record that
can double-count after an interrupted bolus, and it is the one thing on this page that currently asks
the user to check the driver's work by hand.

---

## Pulling a newer AAPS later

This driver lives on `ypso-integration`, branched from `upstream/master`. Bringing it forward:

```bash
git fetch upstream
git log --oneline HEAD..upstream/master | wc -l          # how much has moved

git checkout ypso-integration
git checkout -b ypso-integration-rebase-$(date +%Y%m%d)  # ⚠ always a throwaway branch
git rebase upstream/master
```

**Always rebase on a throwaway branch.** A rebase replays every ypso commit, and those commit
messages are the only place several hardware findings are written down at full length; a rebase that
goes wrong and gets resolved by force is how that record is lost. If the throwaway builds clean,
fast-forward `ypso-integration` onto it. If not, delete it and nothing was risked.

Then, in order, and do not skip any:

```bash
./gradlew :pump:ypso:sdk:testFullDebugUnitTest     # 84 tests
./gradlew :pump:ypso:testFullDebugUnitTest         # 47 tests
./gradlew --stop; ./gradlew :app:clean; ./gradlew :app:assembleFullDebug
```

**A rebase is not a no-op for a pump driver.** `PumpSync`'s contract, the `Pump` interface and the
command queue's behaviour are all upstream's to change and all three are load-bearing here. Re-run the
**parity check** on the test pump afterwards, on saline, before that build goes near a body.
`PORTING_NOTES.md` §9 has the per-file conflict table and the command that says whether the
changed-file surface has grown.

### This driver never talks to Nightscout

**It contains no Nightscout code at all** — no upload call, no NSClient dependency. Everything it
records goes into the local AAPS database through `PumpSync`, and that is the end of the driver's
involvement.

Anything that does reach Nightscout gets there because **the NSClient plugin syncs that local
database**, which is AAPS's behaviour and not this driver's. That distinction matters in one direction
in particular: during the saline phases, NSClient uploading had to be **off**, because a saline bolus
synced to a shared Nightscout instance becomes a therapy record saying insulin was given that nobody
received — and there is nothing in the record to say otherwise. On a phone doing real therapy that
same upload is wanted. **The rule is about which instance and which fluid, not about the driver.**

---

## Before insulin

Stated plainly, because this is the question the whole project is pointed at.

### ✅ The chunked bolus (Phase 9) — passed on hardware, 2026-08-04

The pump delivers a commanded bolus at a fixed ~33 U/min, so 10 U goes in over 18 seconds —
uncomfortable for large doses where other pumps take minutes.

mylife Lotus solves this **entirely in the phone**: its "bolus speed" setting is a chunk size and an
interval, not a pump setting. Confirmed in the pump's own log on 2026-08-03 — a 2 U Lotus bolus at
3 U/min appeared as five ordinary `type=2` boluses 8 s apart, summing to exactly 2.00 U.

```
12 U/min  =  1 U   per 5 s
 6 U/min  =  0.5 U per 5 s
 3 U/min  =  0.4 U per 8 s
```

So this needs **no new pump capability, no new wire format and no new event type** — every chunk is
the standard bolus this driver has delivered and confirmed since Phase 2. Built to six design
decisions written down *before* the code (`PORTING_NOTES` §8.6), and passed all five steps of
`SALINE_TEST_PROCEDURE.md` **Phase 9**: on the bench it reproduced Lotus's own capture, five chunks
eight seconds apart summing to exactly 2.00 U. A cancel stops the sequence, a lost link stops it, an
occlusion stops it, and every run was checked against the reservoir.

⚠ **Nothing chunks unless it is switched on.** *Preferences → YpsoPump → Bolus speed* defaults to
the pump's own ~33 U/min, which is one command and exactly what every hardware phase ran. Three
things are worth knowing before turning it on:

- **The connection is held for the whole sequence**, so nothing else — including the alert check that
  notices an occlusion — reaches the pump while it runs. Capped at five minutes; above that the rate
  steps up to the next option rather than refusing the bolus.
- **A 10 U bolus becomes ten treatment records.** Noisy but accurate: the insulin genuinely went in
  as ten pulses, and it is what the history sync produces from the pump's log anyway.
- **The rate is an upper bound, never a floor.** If a chunk's confirmation overruns its slot the
  sequence runs slower than configured. It will never run faster.

---

### ⚠⚠ The temporary basal that could not be replaced — the one that ended the trial

Covered at the top of this file and in full in `PORTING_NOTES.md` §4h and `SALINE_TEST_PROCEDURE.md`
Phase 11. In one line: **the pump refused `0%` while `400%` was running**, `tbrPercentRange` is
`0..500` so the refusal is the pump's rather than a guard, cancelling to 100% works, and until Phase 11
separates the two possible causes the loop cannot be relied on to reduce delivery.

It is worth saying *why* nothing caught it earlier, because the answer is not carelessness. Phases 1
to 9 each removed one refusal and tested that capability **on its own** — set a temporary basal, cancel
a temporary basal, and both passed repeatedly. The failure lives in the **combination**: replacing one
while another runs. That is the same shape as every expensive finding in these notes, and it is the
argument for Phase 11's 2×2 rather than a single reproduction.

**Everything else about what the pump does is closed.** Phase 6b — the last of those — closed on
2026-08-03, verified twice: 2h33m of stopped pump on one build (four renewal cycles), then
re-confirmed on the current build after a flash (three more), with **zero uncovered time at every
transition, including across the reinstall**. The 0% suspend record is held continuously while the
pump is stopped, which is what stops AAPS counting basal insulin that is not being delivered.

### ⚠⚠ One gate is open, it is about AAPS's own database, and the code predicts it will fail

**Phase 10 — the interrupted bolus — is written and has never been run.** It is the last thing that
could still change a record, and unlike every phase before it its subject is not a pump capability.
The pump's part is not in question: **it owns a bolus once it has been told and finishes it whether or
not the phone is still there.** What has never been tested is whether AAPS's *record* of that bolus
settles when the phone is taken out of the loop partway through — the link dropping (10a/10c) or the
app being killed (10b/10d).

**Read the prediction before trusting the driver here.** `openPendingBolus` writes a committed row
with `temporaryId` set, `pumpId` null and the amount *requested*; `settlePendingBolus` is the only
thing that ever promotes it. Nothing else in AAPS consumes `temporaryId` on a bolus, and the history
sync cannot rescue it — it looks rows up by `pumpId`, the orphan has none, so the sync **inserts a
second row for the same physical insulin**. One bolus, two live rows, both counting:

| | `amount` | `temporaryId` | `pumpId` |
|---|---|---|---|
| the orphaned provisional | what was **requested** | set | **null** |
| the history-sync import | what the pump **gave** | null | set |

A 10 U bolus would then read as ~20 U on board. **IOB wrong in that direction makes AAPS dose less,
which is the safe side; but the same orphan mechanism with a *cancelled* bolus over-states insulin
that was never given, and nothing reconciles it — ever.** Phase 9c already produced exactly one such
permanent phantom row, which is why this is written as a prediction rather than a worry.

Until Phase 10 has been run, treat a bolus that was interrupted — dropped link, killed app, phone
rebooted mid-delivery — as **a record that must be checked by hand** against the pump's own history,
and corrected in AAPS if it double-counts. This is the one thing on this page that asks the user to
do the driver's job for it.

**Two things only time can answer:**

- **What the event log does at 3000 entries**, roughly three months out. That is where a monotonic
  watermark stops being sound.
- **How the driver behaves across a real DST or timezone change.** The path exists and is unit
  tested; no actual transition has happened while it was running.

**Phase 5's delivery-mode table is complete** — cartridge-out and reservoir-empty both closed on
2026-08-03. Only "during priming with fluid moving" is unfilled, and it is recorded as *unreachable by
this method* rather than blank: the reservoir reads `-1` throughout a change, so a mid-prime reading
is byte-identical to one taken after it.

**This paragraph used to say that none of the gaps was a capability gap, and that the driver could do
what it needed to do.** That was written on 2026-08-04, hours before the pump refused a command the
loop depends on. It is left here in corrected form rather than deleted, because the error in it is
instructive: every gap *known at the time* was about records, timing and observation, and the
conclusion drawn was that nothing remained but a human decision. What was actually missing was a test
of two capabilities **together**, and no amount of confidence about the individual ones could stand in
for it.

So: the temporary-basal replacement gap **is** a capability gap, it is open, and it is the one that
decides whether this driver can close a loop at all. The rest — the 3000-entry log wrap, an untested
DST change, Phase 10's interrupted-bolus records — remain observation.

The decision to put insulin in the pump is still a human one, taken by the person whose body it is and
not a task in a checklist. It has now been taken once and reversed the same day, which is the system
working.

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

Outside `pump/ypso/` this branch touches **seven files, 19 added lines and 2 changed** — the Gradle
includes, the app's dependency, Dagger's two component lists and the plugin map, one
version-catalogue entry and one `.gitignore` line. `PORTING_NOTES.md` §9 has the per-file table, the
rebase workflow, and the one command that says whether that surface has grown.

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

# YpsoPump forward-port notes

Porting Andy Rozman's `ypsopump` module (branch `ypsopump_andy_v2`, ~April 2022) onto current
AndroidAPS (3.4.2.4).

**Status after this session: the project builds green with the module included and the plugin
selectable in the pump list. There is no pump communication of any kind in the tree.**

---

## 0. Read this first — safety posture

The only connector wired up is `YpsoPumpDummyConnector`, and it **fails every delivery command**:

| Command | Behaviour |
|---|---|
| `sendBolus` | returns `success=false`, "Command not implemented." |
| `sendTemporaryBasal` | returns `success=false` |
| `cancelTemporaryBasal` | returns `success=false` |
| `sendBasalProfile` | returns `success=false` |
| `connectToPump` | returns `false` |
| all `retrieve*` | return `null` / `success=false` |

**This is a deliberate change from Andy's original.** His `YpsoPumpDummyConnector` returned
`CommandResponse.builder().success(true)` for bolus, TBR and basal-profile writes so he could
exercise the UI, and `YpsoPumpConnectorAbstract` defaulted `connectToPump()` to `true` and
`retrieveFirmwareVersion()` to `VERSION_1_0`. A dummy that reports *successful insulin delivery* is
a hazard as soon as anything above it trusts the result, so those defaults were inverted.

Do not restore the optimistic dummy. Write a real connector instead.

`YpsoPumpConnectionManager.sendFakeCommand()` likewise now returns failure rather than success.

---

## 1. What was and wasn't brought across

### Ported
`YpsopumpPumpPlugin`, `YpsoPumpFragment`, `YpsoPumpConnectionManager`, `YpsoPumpDataConverter`,
the connector seam (`YpsoPumpConnectorInterface` / `Abstract` / `Dummy` / `Base` / `15` / `16`),
`comm/command/**` response + parameter types, `data/**` DTOs, `database/**` (Room), `defs/**`,
`di/**`, `driver/**`, `event/**`, `handlers/**`, `util/**`, and all resources.

### NOT ported (deliberately)

| Dropped | Why |
|---|---|
| `comm/ble/YpsoPumpBLE.kt`, `comm/ble/command/**`, `comm/ble/operations/**` | Andy's own BLE stack. This is real pump communication, which this session explicitly excludes; and per the integration plan it is being replaced wholesale by the vicktor SDK behind a new connector. Still available under `reference/reference/andy-aaps/ypsopump/`. |
| `comm/ble/defs/YpsoGattService`, `YpsoGattCharacteristic`, `GattStatus`, `BLECommOperationResultType` | Describe only the BLE transport that was dropped. |
| `dialog/YpsoPumpBLEConfigActivity.java` | Extended a pump-common base class that no longer exists upstream. `pump:common` now ships a generic `PumpBLEConfigActivity`; wire the module to that instead. |
| `dialog/YpsoPumpHistoryActivity.kt` | Extended pump-common's `PumpHistoryActivity`, which upstream **deleted** (only the layout survives; Medtronic ships its own). |
| `defs/YpsoDriverStatus.kt` | Andy had already marked it `@Deprecated("Deprecated class. Remove.")` and nothing used it. |

`comm/ble/defs/YpsoBasalProfileType`, `YpsoPumpNotificationType` and `YpsoSettingId` **were** kept —
they are plain enums referenced by non-BLE code.

---

## 2. Interface / API deltas fixed

### Package moves (mechanical)

| 2022 | current |
|---|---|
| `info.nightscout.androidaps.plugins.pump.ypsopump.*` | `app.aaps.pump.ypsopump.*` |
| `info.nightscout.androidaps.dependencyInjection` (DI module pkg) | `app.aaps.pump.ypsopump.di` |
| `...data.DetailedBolusInfo` / `...data.PumpEnactResult` | `app.aaps.core.interfaces.pump.*` |
| `...interfaces.{Pump,PumpSync}` | `app.aaps.core.interfaces.pump.*` |
| `...interfaces.Profile` | `app.aaps.core.interfaces.profile.Profile` |
| `...interfaces.PumpDescription` | `app.aaps.core.data.pump.defs.PumpDescription` |
| `...interfaces.ActivePlugin` / `CommandQueue` | `app.aaps.core.interfaces.plugin` / `.queue` |
| `...plugins.bus.RxBus` | `app.aaps.core.interfaces.rx.bus.RxBus` |
| `...events.Event*` , `queue.events.EventQueueChanged`, `overview.events.EventNewNotification` | `app.aaps.core.interfaces.rx.events.*` |
| `...overview.notifications.Notification` | `app.aaps.core.interfaces.notifications.Notification` |
| `...utils.{DateUtil,FabricPrivacy,resources.ResourceHelper,rx.AapsSchedulers}` | `app.aaps.core.interfaces.utils` / `.resources` / `.rx` |
| `...utils.TimeChangeType` | `app.aaps.core.data.pump.defs.TimeChangeType` |
| `...utils.T` | `app.aaps.core.data.time.T` |
| `...utils.alertDialogs.OKDialog` | `app.aaps.core.ui.dialogs.OKDialog` |
| `...utils.WarnColors` | `app.aaps.core.interfaces.pump.WarnColors` |
| `info.nightscout.shared.logging.*` | `app.aaps.core.interfaces.logging.*` |
| `...pump.common.defs.PumpType` | `app.aaps.core.data.pump.defs.PumpType` |
| `...pump.common.defs.PumpDeviceState` | `app.aaps.core.interfaces.pump.defs.PumpDeviceState` |
| `...pump.common.utils.{ByteUtil,ThreadUtil}` | `app.aaps.core.utils.pump.*` |
| `...pump.common.utils.{StringUtil,DateTimeUtil}` | `app.aaps.core.utils.*` |
| `...pump.common.driver.PumpBLESelector[Text]` | `app.aaps.pump.common.driver.ble.*` |
| `...pump.common.ble.BlePreCheck` | `app.aaps.core.interfaces.pump.BlePreCheck` |
| `...activities.NoSplashAppCompatActivity` | `dagger.android.DaggerActivity` |

**Good news:** upstream `pump:common` is a direct descendant of Andy's own `pump-common` — it
absorbed `PumpPluginAbstract`, `PumpStatus`, `PumpDriverConfiguration`, `PumpBLESelector`,
`PumpHistoryDataProvider`, `PumpSyncStorage`, `ProfileUtil`, `TempBasalPair` and even a generalised
`PumpCommandType` (a generalisation of his `YpsoPumpCommandType`) and `PumpDataRefreshType`
(literally still carrying his `Created by andy on 04.07.2022` header). Much of the framework port was
therefore already done for us.

### Preferences: `SP` → `Preferences` (biggest single change)

`info.nightscout.shared.sharedPreferences.SP` is gone. Preferences are now typed key enums
(`app.aaps.core.keys.interfaces.*`) resolved via `Preferences`. `YpsoPumpConst.Prefs` /
`.Statistics` (which used `@StringRes` ints and raw strings) were replaced by:

- `keys/YpsopumpStringPreferenceKey` — Serial, Address, Name, BolusSize
- `keys/YpsopumpBooleanPreferenceKey` — Bonded
- `keys/YpsopumpStringNonKey` — PumpStatusList (internal JSON state)
- `keys/YpsopumpLongNonKey` — FirstPumpStart, LastGoodPumpCommunicationTime, TbrsSet,
  StandardBoluses, SmbBoluses

**Key strings were kept byte-identical to Andy's `strings.xml`** (`pref_ypsopump_serial`, etc.) so an
existing install keeps its stored values. The key classes are registered via `ownPreferences` on the
`PumpPluginAbstract` constructor.

`YpsoPumpConst` itself is now unused by the plugin and can be deleted once the last references go.

### `Pump` interface

| Was | Now |
|---|---|
| `fun lastDataTime(): Long` | `val lastDataTime: Long` |
| `val batteryLevel: Int` | `val batteryLevel: Int?` |
| `PumpEnactResult(injector)` | injected `Provider<PumpEnactResult>` → `pumpEnactResultProvider.get()` |
| `PumpEnactResult.carbsDelivered(...)` | removed — carbs go through `pumpSync.syncCarbsWithTimestamp` |
| `Constraints` | `PluginConstraints` |
| `Constraint.set(aapsLogger, value, reason, from)` | `Constraint.set(value, reason, from)` |
| `PumpSyncEntriesCreator.generateTempId(Any?)` | `generateTempId(objectA: Any)` (non-null) |
| `DetailedBolusInfo.BolusType.*` | `app.aaps.core.data.model.BS.Type.*` |
| `DetailedBolusInfo.EventType.*` | `app.aaps.core.data.model.TE.Type.*` |

### `PumpPluginAbstract`

Upstream absorbed most of Andy's status-refresh scheduling. The following were **removed from the
plugin** because the base class now declares them: `firstRun`, `isRefresh`,
`hasTimeDateOrTimeZoneChanged`, `serviceRunning`, `logPrefix`, `getTimeInFutureFromMinutes`,
`getTimeInMs`, `incrementStatistics` (now takes a `LongNonPreferenceKey`).

Constructor is now fully named-parameter and additionally requires `decimalFormatter`,
`pumpEnactResultProvider` and `pumpDriverConfigurationInternal`; it no longer takes `injector`,
`activePlugin` or `sp`.

Two local renames were needed to avoid clashing with the base class:
- `isInitialized` (private field) → `pumpInitialized` (base has `fun isInitialized()`)
- `doWeHaveAnyStatusNeededRefereshing` → `doWeHaveAnyYpsoStatusNeedingRefresh` (identical JVM
  erasure to the base method: `(Ljava/util/Map;)Z`)
- `statusRefreshMap` → `ypsoStatusRefreshMap` (base keys its own by `PumpDataRefreshType`)

### `ByteUtil` (`app.aaps.core.utils.pump.ByteUtil`)

| Was | Now |
|---|---|
| `toInt(b: Byte)` | gone → `asUINT8(b)` |
| `toInt(b1, b2, flag)` with `Byte`s | gone → `toInt(b1, b2, null, null, flag)` |
| `toInt(b1, b2, b3, flag)` with `Byte`s | gone → `toInt(b1, b2, b3, null, flag)` |
| `getString(ByteArray)` | gone → `String(data)` |
| `getHex(...)` | now returns `String?` |

### Classes that no longer exist upstream, recreated module-locally

| Class | Where it went |
|---|---|
| `PumpUpdateFragmentType` | `app.aaps.pump.ypsopump.defs.PumpUpdateFragmentType` (rewritten — the original used an illegal `final var` + enum-constructor pattern) |
| `EventPumpFragmentValuesChanged` | `app.aaps.pump.ypsopump.event.*` |
| `EventPumpConnectionParametersChanged` | `app.aaps.pump.ypsopump.event.*` |
| `BasalProfileStatus` | `app.aaps.pump.ypsopump.defs.*` (Medtronic likewise keeps its own) |
| `PumpBLESelectorAbstract` | folded into `YpsoPumpBLESelector`, which now implements `PumpBLESelector` directly |
| `ProfileUtil.getProfilesByHourToString` | inlined in `YpsopumpPumpPlugin.isThisProfileSet` |

`EventRefreshButtonState` *does* still exist upstream (`app.aaps.core.interfaces.rx.events`).

### Resources

Added to the module's `strings.xml` (they lived in Andy's core/pump-common, which upstream no longer
ships): `ble_error_*` (7), `pump_settings_error_*` (3), `ypsopump_ago`, and the
`ypsopump_duration_hours` / `ypsopump_duration_days` plurals.

Remapped to core: `not_set_short`, `insulin_unit_shortname`, `unknown` (→ `core.ui`), `minago`
(→ `core.interfaces`), `pump_basebasalrate` → `core.ui/pump_base_basal_rate`, `reservoirvalue` →
`core.ui/reservoir_value`, and the generic BLE scan toasts → `pump.common/ble_config_scan_*`.

### Changes made OUTSIDE the module

Three, all additive — no other pump driver was touched:

1. `core/interfaces/.../notifications/Notification.kt` — added `YPSOPUMP_PUMP_ALARM = 95`.
2. `pump/common/.../defs/PumpDriverState.kt` — restored `Sleeping` and `ErrorCommunicatingWithPump`
   (both existed in Andy's pump-common; upstream trimmed them because no in-tree driver used them,
   and the whole ypsopump state machine is built on them), plus two strings in `pump/common`'s
   `strings.xml`.
3. App wiring: `settings.gradle`, `app/build.gradle.kts`, `di/PluginsListModule.kt` (`@IntKey(175)`),
   `di/AppComponent.kt`, `androidTest/di/TestAppComponent.kt`.

`.gitignore` also gained `/reference` (required before anything could build — see §5).

---

## 3. Deferred / stubbed — the actual TODO list

Everything here compiles and is reachable, but does nothing useful yet.

### 3a. Pump communication (all of it)
`YpsoPumpConnectionManager` used to bypass the connector seam and drive `YpsoPumpBLE` directly for
`determineFirmwareVersion`, `getConfiguration`, `getBasalProfile` and `getTime`. It now routes
**everything** through `YpsoPumpConnectorInterface` — which is what the integration plan wants
anyway ("the connector is the whole seam"). With the dummy connector, all of it reports
"not implemented".

- `getTime()` / `setTime()` return `null` and log. **The connector interface has no clock method** —
  add `retrieveDateTime()` / `sendDateTime()` when writing the vicktor connector.
- `getConfiguration()` and `getBasalProfile()` check `isSuccess` then log "payload mapping not
  implemented". Andy unpacked `YpsoPumpSettingsDto` / `BasalProfileDto` straight off his BLE
  commands; the connector returns `MapDataCommandResponse` / `BasalProfileResponse` instead, and
  **those Java classes have package-private payload fields with no getters**, and the map keys were
  never defined. Add accessors and define the key contract as part of the connector work rather than
  guessing it now.

### 3b. History → PumpSync
Andy's Room DB (`database/**`) was kept and compiles. What is stubbed is the *reading*:

- `YpsoPumpHistoryHandler.readEventsFromPump()`, `readAlarmsFromPump()`, `readLastEventFromPump()`
  all return `null` ("read failed").
- Everything downstream — `preProcessHistory`, `preProcessBasalProfiles`,
  `preProcessConfigurationItems`, `preProcessDeliverySuspendItems`, `addHistoryToDatabase`,
  `sendDataToPumpSync`, `uploadCareportalEventIfFoundInHistory` — is **Andy's original logic, intact
  and compiling**, waiting for events.
- `readLastEventFromPump` is the path that confirms a delivery actually happened. It must be
  reinstated *together with* any delivery support, not after.

**Per §4 of the integration plan, prefer PumpSync over the Room DB.** Replacing the DB was too large
for this session; the recommendation stands — every `PumpHistoryEvent` carries a `position` usable as
a stable `pumpId`, which is what makes reconnect dedup work.

### 3c. UI
- **History screen**: the button now shows a "Not implemented yet." dialog. Upstream deleted
  `PumpHistoryActivity`; ypsopump needs its own (copy Medtronic's `MedtronicHistoryActivity`
  pattern — `pump:common` still ships the `pump_history_activity.xml` layout and
  `PumpHistoryDataProvider`/`PumpHistoryEntry` interfaces, and
  `YpsopumpPumpHistoryDataProvider` is already ported and wired).
- **BLE config screen — will not launch yet.** `YpsoPumpBLEConfigActivity` was dropped;
  `pump:common` ships a generic `PumpBLEConfigActivity` and `YpsoPumpBLESelector` is already ported
  to the current interface and returned by `YpsopumpPumpDriverConfiguration`.
  `res/xml/pref_ypsopump.xml` has been repointed to
  `app.aaps.pump.common.driver.ui.PumpBLEConfigActivity`, **but that is an intent *action* string and
  `PumpBLEConfigActivity` is declared in no `AndroidManifest.xml` anywhere in the tree** (pump:common's
  manifest is empty), so nothing resolves it. Either declare the activity with a matching
  `<intent-filter>`, or switch the preference to an explicit component
  (`android:targetPackage` + `android:targetClass`, as Medtronic does for its own activities) and
  declare it in the ypsopump manifest. Until then the "YpsoPump Configuration" preference does nothing.
- **Bonding is not wired.** `PumpBLESelector.onDeviceSelected` used to register a `BondStateReceiver`
  (also deleted upstream) and call `createBond()`. It now only stores address/name/serial.

### 3d. Other
- `YpsopumpPumpDriverConfiguration.getPumpDriverDatabaseOperation()` throws `NotImplementedError`
  (Medtronic's does too — nothing calls it while the module keeps its own Room DB).
- `driverMode` still defaults to `YpsoDriverMode.Faked`.
- The plugin keeps its own `YpsoPumpStatusRefreshType`-keyed refresh map instead of migrating to the
  base class's `PumpDataRefreshType` scheduler. Worth unifying eventually; not load-bearing.
- Andy's unit tests (`YpsoPumpDataConverterUTest`, `YpsoPumpHistoryHandlerUTest`) were **not**
  ported — they exercise the BLE decode path.
- `schemas/` was copied but the Room schema dir is declared under the old package name
  (`info.nightscout.androidaps.plugins.pump.ypsopump.database.YpsoPumpDatabase/1.json`); the module
  namespace changed, so KSP will emit a new schema path.

---

## 4. Recommended order for the next session

1. **Make the BLE-config screen actually launch.** The preference intent is already repointed at
   `app.aaps.pump.common.driver.ui.PumpBLEConfigActivity`, but that activity is declared in no
   manifest, so the intent resolves to nothing (see §3c for the two options). Without this you
   cannot select a pump at all. Small, and it unblocks everything else.
2. **Restore bonding** in `YpsoPumpBLESelector.onDeviceSelected` (port `BondStateReceiver` into the
   module — it was ~70 lines).
3. **Extend `YpsoPumpConnectorInterface`** before writing any implementation:
   add `retrieveDateTime()`, `sendDateTime()`, and (per plan §5) `sendExtendedBolus` /
   `sendMultiwaveBolus`. Add getters to the `comm/command/response/*.java` payload classes and pin
   down the `MapDataCommandResponse` key names.
4. **Write `YpsoPumpVicktorConnector`** (plan §1–§3) — plaintext, no key. Wire it in
   `YpsoPumpConnectionManager.init` *alongside* the dummy, selected by preference so the dummy stays
   the default until the real one is trusted.
5. **Read-only bring-up** (plan §7 step 3): firmware → config → basal profile → reservoir → battery.
   Cross-check every value against the pump screen. Zero delivery.
6. **History**: implement `readEventsFromPump` via the connector, then decide DB-vs-PumpSync. Do the
   PumpSync `pumpId = position` mapping (plan §4) at this point — retrofitting stable IDs later means
   duplicate IOB.
7. **Then, and only then**, revisit the dummy-connector failure defaults for the real connector, and
   follow plan §7 steps 4–8 (syncDateTime → saline bolus → TBR → profile → soak).

---

## 5. Build environment gotchas

- `app/build.gradle.kts` fails the build on **any** modified tracked file
  (`isMaster() && !allCommitted()`), and `isMaster()` is true whenever `Versions.appVersion` contains
  no `-`. Untracked files are ignored, tracked modifications are not. So you cannot build a dirty
  tree — commit first, or temporarily suffix `Versions.appVersion` (e.g. `3.4.2.4-dev`) to disable
  the guard while iterating. **If you use the suffix trick, revert it before committing.**
- The guard is evaluated when `:app` is *configured*, which happens even for module-only tasks like
  `:pump:ypsopump:compileFullDebugKotlin`.
- Keep Android Studio closed while building from the CLI. Its Gradle daemon holds
  `core/interfaces/build/intermediates/.../classes.jar` open and every build fails with
  `FileSystemException: ... used by another process`.
- `JAVA_HOME` must be set for `gradlew.bat`; the Android Studio JBR
  (`C:\Program Files\Android\Android Studio\jbr`) works.
- `:app:kspFullDebugKotlin` can fail spuriously with
  `ComponentProcessingStep was unable to process 'app.aaps.di.AppComponent' because
  'app.aaps.di.ActivitiesModule_ContributesHistoryBrowseActivity' could not be resolved` after the
  build inputs change underneath a stale incremental state. It is not a real error — the named type
  is unrelated Dagger-generated code. `./gradlew :app:clean` then rebuild fixes it.

package app.aaps.pump.ypsopump.defs

/**
 * Ported from Andy Rozman's `pump-common`. Upstream AAPS keeps this per-driver (see
 * `app.aaps.pump.medtronic.defs.BasalProfileStatus`), so ypsopump carries its own copy.
 */
enum class BasalProfileStatus {
    NotInitialized,
    ProfileChanged,
    ProfileOK
}

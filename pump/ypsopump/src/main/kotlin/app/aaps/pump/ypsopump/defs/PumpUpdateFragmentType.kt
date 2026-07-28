package app.aaps.pump.ypsopump.defs

/**
 * Fragment-update granularity used by [app.aaps.pump.ypsopump.event.EventPumpFragmentValuesChanged].
 *
 * Ported from Andy Rozman's `pump-common` (`PumpUpdateFragmentType`), which upstream AAPS dropped
 * when the generic driver framework was merged. Kept module-local so the ypsopump fragment keeps
 * its original partial-refresh behaviour.
 */
enum class PumpUpdateFragmentType {

    None,
    PumpStatus,
    DriverStatus,
    Queue,
    Bolus,
    TBR,
    ProfileChange,
    TBRCount,
    BolusCount,
    TreatmentValues, // Last Bolus, TBR, Profile Change, TBR Count, Bolus Count
    Full,
    Configuration, // Firmware, Errors
    Battery,
    Reservoir,
    OtherValues, // Battery, Reservoir
    Custom_1,
    Custom_2,
    Custom_3,
    Custom_4,
    Custom_5,
    Custom_6,
    Custom_7,
    Custom_8
    ;

    val children: List<PumpUpdateFragmentType>
        get() = when (this) {
            TreatmentValues -> listOf(Bolus, TBR, TBRCount, BolusCount, ProfileChange)
            OtherValues     -> listOf(Battery, Reservoir)
            else            -> emptyList()
        }

    fun isOptionIncluded(type: PumpUpdateFragmentType): Boolean =
        this == type || children.contains(type)
}

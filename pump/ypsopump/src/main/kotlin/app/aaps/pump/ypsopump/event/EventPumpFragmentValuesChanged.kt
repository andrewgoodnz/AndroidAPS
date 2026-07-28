package app.aaps.pump.ypsopump.event

import app.aaps.core.interfaces.rx.events.Event
import app.aaps.pump.ypsopump.defs.PumpUpdateFragmentType

/**
 * Ported from Andy Rozman's `pump-common`, which upstream AAPS dropped. Kept module-local.
 */
class EventPumpFragmentValuesChanged(val updateType: PumpUpdateFragmentType) : Event()

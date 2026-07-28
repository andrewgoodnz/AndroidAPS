package app.aaps.pump.ypsopump.event

import app.aaps.core.interfaces.rx.events.Event

/**
 * Raised when the configured pump address / bonding state / serial changes.
 *
 * Ported from Andy Rozman's `pump-common`, which upstream AAPS dropped. Kept module-local.
 */
class EventPumpConnectionParametersChanged : Event()

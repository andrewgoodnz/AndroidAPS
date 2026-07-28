package app.aaps.pump.ypsopump.event

import app.aaps.core.interfaces.rx.events.Event
import app.aaps.pump.common.defs.PumpDriverState

class EventPumpStatusChanged(var driverStatus: PumpDriverState) : Event()

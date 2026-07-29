package app.aaps.pump.ypso.ui

import app.aaps.core.interfaces.queue.CustomCommand

/**
 * Queued request to set the pump's clock from the phone's.
 *
 * A custom command rather than the queue's own `UPDATE_TIME`, because `CommandUpdateTime`
 * dispatches on `pump is Medtrum` and so would never reach this driver.
 *
 * User-triggered by design as well. This is the only write the driver makes, and until the write
 * path has been proven on hardware nothing should be issuing it on a schedule or as a side effect
 * of a loop iteration.
 */
class YpsoSyncTimeCommand : CustomCommand {

    override val statusDescription: String = "SET YPSO PUMP CLOCK"
}

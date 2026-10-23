package app.aaps.pump.ypso.ui

import app.aaps.core.interfaces.queue.CustomCommand

/**
 * Queued request to set the pump's clock from the phone's.
 *
 * A custom command rather than the queue's own `UPDATE_TIME`, because `CommandUpdateTime`
 * dispatches on `pump is Medtrum` and so would never reach this driver.
 *
 * ⚠ **No longer only user-triggered**, and this comment used to say it was. The write path has been
 * proven on hardware since, so the driver now queues a clock sync by itself on a timezone or DST
 * change and whenever the pump's clock has drifted past ten seconds (`PORTING_NOTES` §8.6). That is
 * safe for the reason §4e gives and for no other: the write refuses unless it can read the clock
 * first, and reports success only from the read-back.
 */
class YpsoSyncTimeCommand : CustomCommand {

    override val statusDescription: String = "SET YPSO PUMP CLOCK"
}

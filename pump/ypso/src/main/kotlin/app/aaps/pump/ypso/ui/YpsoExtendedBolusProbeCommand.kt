package app.aaps.pump.ypso.ui

import app.aaps.core.interfaces.queue.CustomCommand

/**
 * Queued request for the **Phase 8a extended-bolus probe** — a saline-bench instrument.
 *
 * Deliberately a custom command rather than [app.aaps.core.interfaces.pump.Pump.setExtendedBolus].
 * Going through the `Pump` interface would require telling AAPS the pump is
 * `isExtendedBolusCapable`, and that one flag switches on `LoopPlugin`, `TreatmentsActivity`,
 * `ActionsFragment` and — the trap — `goToZeroTemp`'s automatic `cancelExtended`, which raises an
 * audible delivery-error alarm while `cancelExtendedBolus` still refuses. All of that would be live
 * before anyone had seen the pump answer a single `bolusType = 2` command.
 *
 * This route changes nothing in AAPS's model of the pump. The amount and duration are fixed in
 * [app.aaps.pump.ypso.services.YpsoService.probeExtendedBolus] rather than taken from a dialog, so
 * there is no path by which it delivers something other than the bench dose.
 *
 * ⚠ **It moves fluid.** Saline, off-body, cannula to air — `SALINE_TEST_PROCEDURE.md` Phase 8a.
 */
class YpsoExtendedBolusProbeCommand : CustomCommand {

    override val statusDescription: String = "YPSO EXTENDED BOLUS PROBE (bench test)"
}

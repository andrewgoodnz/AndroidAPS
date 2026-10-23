package app.aaps.pump.ypso.ui

import app.aaps.core.interfaces.queue.CustomCommand

/**
 * Queued request to read the pump's history logs.
 *
 * Going through the command queue means the read happens inside a normal connect/disconnect
 * cycle and cannot collide with another pump command.
 */
class YpsoLoadHistoryCommand : CustomCommand {

    override val statusDescription: String = "LOAD YPSO HISTORY"
}

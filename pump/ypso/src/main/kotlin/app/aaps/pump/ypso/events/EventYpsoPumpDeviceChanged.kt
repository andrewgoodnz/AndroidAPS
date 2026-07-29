package app.aaps.pump.ypso.events

import app.aaps.core.interfaces.rx.events.Event

/**
 * The user picked a different pump in the pairing screen.
 *
 * The service listens for this so a newly paired MAC takes effect immediately, rather than at
 * the next app start.
 */
class EventYpsoPumpDeviceChanged : Event()

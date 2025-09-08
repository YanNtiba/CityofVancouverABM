package org.matsim.contrib.shared_mobility.examples;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;

import java.util.HashMap;
import java.util.Map;

/**
 * An event handler that applies a dynamic fare based on a Pay-Per-Ride (PPR) structure,
 * including an unlock fee and a per-minute rate.
 * This implementation models a "first N minutes free" promotional policy.
 */
public class MobiFreeMinutesFarePolicy_old implements PersonDepartureEventHandler, PersonArrivalEventHandler {

	private final EventsManager events;
	private final String mobiMode;
	private final double promoFreeMinutes;
	private final double overagePerMin;
	private final double unlockFee;

	private final Map<Id<Person>, Double> depTimes = new HashMap<>();

	/**
	 * Constructor for the Pay-Per-Ride fare policy.
	 * Dependencies are automatically provided by MATSim's dependency injection framework (Guice).
	 *
	 * @param events           The MATSim EventsManager to process new money events.
	 * @param mobiMode         The transport mode to which this fare policy applies.
	 * @param promoFreeMinutes The number of promotional free minutes for each trip.
	 * @param overagePerMin    The per-minute rate charged after promotional minutes are used.
	 * @param unlockFee        The fixed fee charged at the start of every trip.
	 */
	@Inject
	public MobiFreeMinutesFarePolicy_old(EventsManager events,
                                         @Named("mobiMode") String mobiMode,
                                         @Named("promoFreeMinutes") double promoFreeMinutes,
                                         @Named("overagePerMin") double overagePerMin,
                                         @Named("unlockFee") double unlockFee) {
		this.events = events;
		this.mobiMode = mobiMode;
		this.promoFreeMinutes = promoFreeMinutes;
		this.overagePerMin = overagePerMin;
		this.unlockFee = unlockFee;
	}

	@Override
	public void handleEvent(PersonDepartureEvent event) {
		// When a person starts a trip on a mobi bike, record their departure time.
		if (mobiMode.equals(event.getLegMode())) {
			Double prev = depTimes.put(event.getPersonId(), event.getTime());
			if (prev != null) {
				// This warning helps debug plans where an agent might start a new trip
				// without properly ending the previous one.
				System.err.println("WARNING: Person " + event.getPersonId() +
					" started a new mobi departure at time " + event.getTime() +
					" without arriving from previous departure at time " + prev +
					". This may indicate an aborted leg or bad plan.");
			}
		}
	}

	@Override
	public void handleEvent(PersonArrivalEvent event) {
		// We only care about arrivals on the mobi mode that we have been tracking.
		if (!mobiMode.equals(event.getLegMode())) return;

		Double t0 = depTimes.remove(event.getPersonId());
		if (t0 == null) return; // Should not happen in a valid simulation

		// --- Pay-Per-Ride Fare Calculation Logic ---
		double durationMinutes = (event.getTime() - t0) / 60.0;

		// Calculate how many minutes are chargeable after subtracting the promotional free time.
		double billedMinutes = Math.max(0.0, durationMinutes - this.promoFreeMinutes);

		// The time-based portion of the cost.
		double variableCost = billedMinutes * this.overagePerMin;

		// The total fare is the fixed unlock fee plus the variable (per-minute) cost.
		double totalFare = this.unlockFee + variableCost;

		// Fire a PersonMoneyEvent if a cost was incurred.
		// The fare is a negative value as it is a cost (disutility) to the agent.
		if (totalFare > 0) {
			events.processEvent(new PersonMoneyEvent(event.getTime(), event.getPersonId(), -totalFare, "mobi_ppr_fare", "mobi_bike_service", null));
		}
	}

	@Override
	public void reset(int iteration) {
		// Clear the map of departure times at the beginning of each new iteration
		// to prevent data from one iteration from affecting the next.
		depTimes.clear();
	}
}

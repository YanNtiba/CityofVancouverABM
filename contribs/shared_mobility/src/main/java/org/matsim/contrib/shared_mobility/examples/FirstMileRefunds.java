package org.matsim.contrib.shared_mobility.examples;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.vehicles.Vehicle;
import org.matsim.api.core.v01.events.PersonMoneyEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class FirstMileRefunds implements PersonEntersVehicleEventHandler {

	private static final class BikeTrip {
		final double startTime;
		final double endTime;
		final double rideSec;
		final double fareCharged;
		boolean refunded = false;
		BikeTrip(double startTime, double endTime, double rideSec, double fareCharged) {
			this.startTime = startTime;
			this.endTime = endTime;
			this.rideSec = rideSec;
			this.fareCharged = fareCharged;
		}
	}

	private final Set<Id<Vehicle>> transitVehicles = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final Map<Id<Person>, BikeTrip> lastBikeTrip = new ConcurrentHashMap<>();

	private final EventsManager events;
	private final double transferWindowSec;
	private final double freeBikeSeconds;     // e.g., 900 for 15 min
	private final double overagePerMin;       // same dial as fare policy
	private final double unlockFee;
	private final boolean firstMileEnabled;
	private final boolean waiveUnlockWhenEligible;  // if first mile should waive unlock when PT is boarded
	private final boolean useDiscountInsteadOfFree;
	private final double discountRatePerMin;

	@Inject
	public FirstMileRefunds(
		EventsManager eventsManager,
		TransitSchedule transitSchedule,
		@Named("transferWindowSec") Double transferWindowSec,
		@Named("freeBikeSeconds")  Double freeBikeSeconds,
		@Named("overagePerMin")    Double overagePerMin,
		@Named("unlockFee")        Double unlockFee,
		@Named("firstMileEnabled") Boolean firstMileEnabled,
		@Named("waiveUnlockWhenEligible") Boolean waiveUnlockWhenEligible,
		@Named("useDiscountInsteadOfFree") Boolean useDiscountInsteadOfFree,
		@Named("discountRatePerMin") Double discountRatePerMin
	) {
		this.events = eventsManager;
		this.transferWindowSec = transferWindowSec != null ? transferWindowSec : 1800.0;
		this.freeBikeSeconds   = freeBikeSeconds   != null ? freeBikeSeconds   :  900.0;
		this.overagePerMin     = overagePerMin     != null ? overagePerMin     :  0.29;
		this.unlockFee         = unlockFee         != null ? unlockFee         :  1.00;
		this.firstMileEnabled  = firstMileEnabled  != null ? firstMileEnabled  : true;
		this.waiveUnlockWhenEligible = waiveUnlockWhenEligible != null ? waiveUnlockWhenEligible : true;
		this.useDiscountInsteadOfFree = useDiscountInsteadOfFree != null ? useDiscountInsteadOfFree : false;
		this.discountRatePerMin = discountRatePerMin != null ? discountRatePerMin : 0.0;

		for (TransitLine line : transitSchedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				for (Departure dep : route.getDepartures().values()) {
					transitVehicles.add(dep.getVehicleId());
				}
			}
		}
	}

	/** Called by the fare policy at bike drop-off. */
	public void recordBikeTrip(Id<Person> pid, double startTime, double endTime, double rideSec, double fareCharged) {
		lastBikeTrip.put(pid, new BikeTrip(startTime, endTime, rideSec, fareCharged));
	}

	/** On PT board, refund the previous bike if within the window and not already refunded. */
	@Override
	public void handleEvent(PersonEntersVehicleEvent event) {
		if (!firstMileEnabled) return;
		if (!transitVehicles.contains(event.getVehicleId())) return;

		Id<Person> pid = event.getPersonId();
		BikeTrip bt = lastBikeTrip.get(pid);
		if (bt == null || bt.refunded) return;

		// check time window from bike end to PT board
		if (event.getTime() - bt.endTime > transferWindowSec) return;

		// compute what would have been free under the policy
		double freeSec = Math.min(freeBikeSeconds, bt.rideSec);
		double refundable;
		if (useDiscountInsteadOfFree) {
			// Refund the difference between full price and discounted price during the free window
			refundable = (freeSec / 60.0) * (overagePerMin - discountRatePerMin);
			if (waiveUnlockWhenEligible) refundable += unlockFee;
		} else {
			// Full free minutes + optional unlock waiver
			refundable = (freeSec / 60.0) * overagePerMin + (waiveUnlockWhenEligible ? unlockFee : 0.0);
		}
		// Cap by what was actually charged
		double refund = Math.min(refundable, bt.fareCharged);
		if (refund <= 0.0) return;

		// emit positive money event as a refund
		events.processEvent(new PersonMoneyEvent(event.getTime(), pid, +refund, "mobi-refund", "Mobi by Rogers", null));
		bt.refunded = true;
	}

	@Override public void reset(int iteration) { lastBikeTrip.clear(); }
}

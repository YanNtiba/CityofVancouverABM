package org.matsim.contrib.shared_mobility.examples;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.vehicles.Vehicle;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class TransitTripCreditManager implements
	PersonLeavesVehicleEventHandler,
	IterationStartsListener {

	private final Set<Id<Vehicle>> transitVehicles = ConcurrentHashMap.newKeySet();
	private final Map<Id<Person>, Deque<Credit>> creditsByPerson = new ConcurrentHashMap<>();

	private final double transferWindowSec;  // e.g. 1800 (30 min)
	private final double freeBikeSeconds;    // e.g. 900  (15 min)

	public static final class Credit {
		final double grantedAtSec;
		final double expiresAtSec;
		boolean consumed = false;
		Credit(double t0, double windowSec) {
			this.grantedAtSec = t0;
			this.expiresAtSec = t0 + windowSec;
		}
	}

	@Inject
	public TransitTripCreditManager(
		@Named("transferWindowSec") Double transferWindowSec,
		@Named("freeBikeSeconds")  Double freeBikeSeconds,
		TransitSchedule transitSchedule
	) {
		this.transferWindowSec = (transferWindowSec != null) ? transferWindowSec : 1800.0;
		this.freeBikeSeconds   = (freeBikeSeconds   != null) ? freeBikeSeconds   :  900.0;

		// Build the definitive PT vehicle set from the schedule
		for (TransitLine line : transitSchedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				for (Departure dep : route.getDepartures().values()) {
					this.transitVehicles.add(dep.getVehicleId());
				}
			}
		}
	}

	public double freeSecondsPerEligibleRide() { return freeBikeSeconds; }

	public boolean isEligible(Id<Person> personId, double nowSec) {
		pruneExpired(personId, nowSec);
		Deque<Credit> q = creditsByPerson.get(personId);
		if (q == null) return false;
		for (Credit c : q) if (!c.consumed && c.expiresAtSec >= nowSec) return true;
		return false;
	}

	public boolean consumeEligibility(Id<Person> personId, double nowSec) {
		pruneExpired(personId, nowSec);
		Deque<Credit> q = creditsByPerson.get(personId);
		if (q == null) return false;
		for (Credit c : q) {
			if (!c.consumed && c.expiresAtSec >= nowSec) {
				c.consumed = true;
				return true;
			}
		}
		return false;
	}

	@Override
	public void handleEvent(PersonLeavesVehicleEvent event) {
		if (!transitVehicles.contains(event.getVehicleId())) return; // not PT
		Id<Person> pid = event.getPersonId();

		// guard against PT drivers if they ever get a leave event
		if (pid != null && pid.toString().startsWith("pt_")) return;

		creditsByPerson
			.computeIfAbsent(pid, k -> new ArrayDeque<>())
			.addLast(new Credit(event.getTime(), transferWindowSec));
	}

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {
		creditsByPerson.clear();
	}

	@Override
	public void reset(int iteration) {
		creditsByPerson.clear();
	}

	private void pruneExpired(Id<Person> personId, double nowSec) {
		Deque<Credit> q = creditsByPerson.get(personId);
		if (q == null) return;
		q.removeIf(c -> c.expiresAtSec < nowSec);
		if (q.isEmpty()) creditsByPerson.remove(personId);
	}
}

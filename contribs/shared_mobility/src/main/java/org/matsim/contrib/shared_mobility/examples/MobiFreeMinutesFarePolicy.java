package org.matsim.contrib.shared_mobility.examples;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.shared_mobility.service.SharingService;
import org.matsim.contrib.shared_mobility.service.SharingVehicle;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.vehicles.Vehicle;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class MobiFreeMinutesFarePolicy
	implements PersonEntersVehicleEventHandler, PersonLeavesVehicleEventHandler {

	private final TransferAudit audit;

	private final double unlockFee;
	private final double overagePerMin;
	private final boolean waiveUnlockWhenEligible;

	private final TransitTripCreditManager creditMgr;
	private final EventsManager eventsManager;

	private final Set<Id<Vehicle>> sharedVehicleIds;

	// per-person rental session state
	private final Map<Id<Person>, Double> pickupTimeSec = new ConcurrentHashMap<>();
	private final Map<Id<Person>, Double> freeSecAssigned = new ConcurrentHashMap<>();
	private final Map<Id<Person>, Id<Vehicle>> currentVeh = new ConcurrentHashMap<>();
	private final FirstMileRefunds firstMileRefunds;

	private final boolean useDiscountInsteadOfFree;
	private final double discountRatePerMin;

	@Inject
	public MobiFreeMinutesFarePolicy(
		@Named("unlockFee") Double unlockFee,
		@Named("overagePerMin") Double overagePerMin,
		@Named("waiveUnlockWhenEligible") Boolean waiveUnlockWhenEligible,
		@Named("useDiscountInsteadOfFree") Boolean useDiscountInsteadOfFree,
		@Named("discountRatePerMin") Double discountRatePerMin,
		TransitTripCreditManager creditMgr,
		SharingService sharingService,
		EventsManager eventsManager,
		TransferAudit audit,
		FirstMileRefunds firstMileRefunds
	) {
		this.unlockFee = (unlockFee != null) ? unlockFee : 1.00;
		this.overagePerMin = (overagePerMin != null) ? overagePerMin : 0.29;
		this.waiveUnlockWhenEligible = (waiveUnlockWhenEligible != null) ? waiveUnlockWhenEligible : true;
		this.useDiscountInsteadOfFree = useDiscountInsteadOfFree != null ? useDiscountInsteadOfFree : false;
		this.discountRatePerMin = discountRatePerMin != null ? discountRatePerMin : 0.0;
		this.creditMgr = creditMgr;
		this.eventsManager = eventsManager;
		this.audit = audit;
		this.firstMileRefunds = firstMileRefunds;

		// Collect the QSim vehicle IDs for the sharing fleet
		this.sharedVehicleIds = new HashSet<>();
		if (sharingService == null) {
			throw new IllegalStateException("SharingService injection is null. Bind a concrete instance in RunScenario_CompassCard.");
		}
		for (SharingVehicle vehicle : sharingService.getVehicles()) {
			this.sharedVehicleIds.add(Id.create(vehicle.getId().toString(), Vehicle.class));
		}
	}

	private boolean isSharedBikeVehicle(Id<Vehicle> vehicleId) {
		return this.sharedVehicleIds.contains(vehicleId);
	}

	// ---- Events ----

	@Override
	public void handleEvent(PersonEntersVehicleEvent event) {
		if (!isSharedBikeVehicle(event.getVehicleId())) return;
		Id<Person> pid = event.getPersonId();

		boolean eligible = creditMgr.consumeEligibility(pid, event.getTime());
		double freeSec = eligible ? creditMgr.freeSecondsPerEligibleRide() : 0.0;

		pickupTimeSec.put(pid, event.getTime());
		freeSecAssigned.put(pid, freeSec);
		currentVeh.put(pid, event.getVehicleId());

		audit.mark(pid, eligible);
	}

	@Override
	public void handleEvent(PersonLeavesVehicleEvent event) {
		if (!isSharedBikeVehicle(event.getVehicleId())) return;
		Id<Person> pid = event.getPersonId();
		if (!currentVeh.containsKey(pid) || !event.getVehicleId().equals(currentVeh.get(pid))) {
			return; // not a session we are tracking
		}
		double t0 = pickupTimeSec.getOrDefault(pid, event.getTime());
		double rideSec = Math.max(0.0, event.getTime() - t0);
		double freeWindowSec = Math.min(freeSecAssigned.getOrDefault(pid, 0.0), rideSec);
		double discountedMin = freeWindowSec / 60.0;
		double unlock = (freeWindowSec > 0.0 && waiveUnlockWhenEligible) ? 0.0 : unlockFee;
		double fare;
		if (freeWindowSec > 0.0 && useDiscountInsteadOfFree) {
			// First X minutes are charged at a discounted per-minute rate
			double discountedPart = discountedMin * discountRatePerMin;
			double overagePart    = Math.max(0.0, (rideSec - freeWindowSec)) / 60.0 * overagePerMin;
			fare = Math.max(0.0, unlock + discountedPart + overagePart);
		} else {
			// First X minutes are free (your current behaviour)
			double billableMin = Math.max(0.0, (rideSec - freeWindowSec)) / 60.0;
			fare = Math.max(0.0, unlock + overagePerMin * billableMin);
		}

		// Always emit a money event, even when fare == 0.0
		eventsManager.processEvent(
			new PersonMoneyEvent(event.getTime(), pid, -fare, "mobi-fare", "Mobi by Rogers", null)
		);

		// Record this bike trip for possible first-mile refund later
		firstMileRefunds.recordBikeTrip(pid, t0, event.getTime(), rideSec, fare);

		// clear session state
		pickupTimeSec.remove(pid);
		freeSecAssigned.remove(pid);
		currentVeh.remove(pid);
	}
}

package org.matsim.contrib.shared_mobility.examples;

import com.google.inject.Inject;
import jakarta.inject.Singleton;
import org.matsim.contrib.shared_mobility.service.SharingService;
import org.matsim.contrib.shared_mobility.service.SharingStation;
import org.matsim.contrib.shared_mobility.service.SharingVehicle;
import org.matsim.core.config.groups.ControllerConfigGroup; // Import the config group class
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Singleton
public class RebalancingListener implements MobsimBeforeSimStepListener, ShutdownListener {

	private final SharingService sharingService;
	private static final double REBALANCE_INTERVAL = 3600.0;
	private static final double TARGET_OCCUPANCY = 0.70;
	private double nextRebalanceTime = REBALANCE_INTERVAL;

	private final FileWriter writer;
	private boolean headerWritten = false;

	// THE FIX IS HERE, IN THE CONSTRUCTOR
	@Inject
	public RebalancingListener(SharingService sharingService, ControllerConfigGroup controlerConfig) { // We ask MATSim to "inject" the controller config
		this.sharingService = sharingService;

		// STEP 1: Get the official output directory from the config file.
		// This will be "C:/Users/yanni/MatsimProjects/exampleproject/scenarios/basecase/BSS_5iter"
		String outputDirectory = controlerConfig.getOutputDirectory();

		// STEP 2: Combine that path with our desired filename.
		Path logFilePath = Paths.get(outputDirectory, "rebalancing_log.csv");

		// STEP 3: Create the file at the correct, full path.
		try {
			// This will now try to create the file at C:/.../BSS_5iter/rebalancing_log.csv
			writer = new FileWriter(logFilePath.toFile(), false);
		} catch (IOException e) {
			// The error message is now more helpful if it fails again
			throw new RuntimeException("Could not create rebalancing log file at: " + logFilePath, e);
		}
	}

	@Override
	public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent event) {
		double now = event.getSimulationTime();
		if (now < nextRebalanceTime - 1e-9) return;
		nextRebalanceTime += REBALANCE_INTERVAL;

		Collection<SharingStation> stations = sharingService.getStations().values();

		Map<SharingStation, Integer> surplus = new LinkedHashMap<>();
		Map<SharingStation, Integer> deficit = new LinkedHashMap<>();

		for (SharingStation station : stations) {
			int capacity = station.getCapacity();
			int currentBikes = station.getVehicles().size();
			int target = (int) Math.round(capacity * TARGET_OCCUPANCY);

			if (currentBikes > target) {
				surplus.put(station, currentBikes - target);
			} else if (currentBikes < target) {
				deficit.put(station, target - currentBikes);
			}
		}

		try {
			if (!headerWritten) {
				writer.write("time,vehicle_id,from_station,to_station\n");
				headerWritten = true;
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to write rebalancing log header", e);
		}

		try {
			for (Map.Entry<SharingStation, Integer> entry : surplus.entrySet()) {
				SharingStation from = entry.getKey();
				int available = entry.getValue();
				Iterator<SharingVehicle> fromVehicles = from.getVehicles().iterator();

				// Bonus: Smarter rebalancing to nearest station
				List<SharingStation> deficitStations = new ArrayList<>(deficit.keySet());
				deficitStations.sort(Comparator.comparingDouble(s ->
					Math.sqrt(Math.pow(s.getLink().getCoord().getX() - from.getLink().getCoord().getX(), 2) +
						Math.pow(s.getLink().getCoord().getY() - from.getLink().getCoord().getY(), 2))));

				while (available-- > 0 && !deficitStations.isEmpty() && fromVehicles.hasNext()) {
					SharingVehicle bike = fromVehicles.next();
					fromVehicles.remove();

					SharingStation to = deficitStations.get(0);
					to.getVehicles().add(bike);

					int remaining = deficit.get(to) - 1;
					if (remaining <= 0) {
						deficit.remove(to);
						deficitStations.remove(0);
					} else {
						deficit.put(to, remaining);
					}

					writer.write(String.format(Locale.US, "%.0f,%s,%s,%s\n", now, bike.getId(), from.getId(), to.getId()));
				}
			}
			writer.flush();
		} catch (IOException e) {
			throw new RuntimeException("Failed to log rebalancing action", e);
		}
	}

	@Override
	public void notifyShutdown(ShutdownEvent event) {
		try {
			writer.close();
		} catch (IOException e) {
			throw new RuntimeException("Failed to close rebalancing log file", e);
		}
	}
}

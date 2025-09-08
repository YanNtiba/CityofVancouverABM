package org.matsim.contrib.shared_mobility.io;

import java.util.*;

public class SharingServiceSpecificationImpl implements SharingServiceSpecification {
	private final Collection<SharingVehicleSpecification> vehicles = new ArrayList<>();
	private final Collection<SharingStationSpecification> stations = new ArrayList<>();

	@Override
	public Collection<SharingVehicleSpecification> getVehicles() {
		return vehicles;
	}

	@Override
	public Collection<SharingStationSpecification> getStations() {
		return stations;
	}

	@Override
	public void addVehicle(SharingVehicleSpecification val) {
		vehicles.add(val);
	}

	@Override
	public void addStation(SharingStationSpecification val) {
		stations.add(val);
	}
}

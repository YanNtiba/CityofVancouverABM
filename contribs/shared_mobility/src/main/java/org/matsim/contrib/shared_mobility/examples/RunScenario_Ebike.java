package org.matsim.contrib.shared_mobility.examples;

import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.shared_mobility.analysis.MissingStationChecker;
import org.matsim.contrib.shared_mobility.io.SharingServiceReader;
import org.matsim.contrib.shared_mobility.io.SharingServiceSpecification;
import org.matsim.contrib.shared_mobility.io.SharingServiceSpecificationImpl;
import org.matsim.contrib.shared_mobility.run.SharingConfigGroup;
import org.matsim.contrib.shared_mobility.run.SharingModule;
import org.matsim.contrib.shared_mobility.run.SharingServiceConfigGroup;
import org.matsim.contrib.shared_mobility.service.SharingService;
import org.matsim.contrib.shared_mobility.service.SharingUtils;
import org.matsim.contrib.shared_mobility.service.StationBasedService;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.population.algorithms.PermissibleModesCalculator;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.simwrapper.SimWrapperModule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Run script for the Full Fleet Electrification scenario.
 * This script assumes that all behavioral parameters for the e-bike mode
 * (e.g., travel disutility) are defined in the provided config file.
 * It is responsible for setting the correct mode for the sharing service
 * and injecting the specific e-bike FARE parameters into the custom fare handler.
 */
public final class RunScenario_Ebike {

	// Best Practice: Use a dedicated config file for each major scenario.
	private static final String CONFIG_PATH = "C:/Users/yanni/MatsimProjects/exampleproject/scenarios/equil/config_ebike.xml";
	private static final String SERVICE_XML = "C:/Users/yanni/MatsimProjects/exampleproject/src/main/resources/output/25_mobi_service.xml";
	private static final double WALK_RADIUS = 500.0;

	// Define the new e-bike mode name for consistency. This must match the mode in the config file.
	private static final String EBIKE_MODE = "sharing:mobi_ebike";

	public static void main(String[] args) {
		// Load the configuration. This file is expected to contain all necessary
		// scoring parameters for the 'sharing:mobi_ebike' mode.
		Config config = ConfigUtils.loadConfig(CONFIG_PATH);

		Scenario scenario = ScenarioUtils.loadScenario(config);
		Network network = scenario.getNetwork();

		try {
			MissingStationChecker.reportMissingLinks(config.network().getInputFile(), SERVICE_XML);
		} catch (Exception e) {
			throw new RuntimeException("Station-link verification failed", e);
		}

		// --- SCENARIO INTERVENTION: Configure the sharing service to use E-BIKES ---
		SharingConfigGroup shareCfg = ConfigUtils.addOrGetModule(config, SharingConfigGroup.class);
		SharingServiceConfigGroup service = new SharingServiceConfigGroup();
		service.setId("mobi_bike"); // The service ID from mobi_service.xml
		service.setServiceScheme(SharingServiceConfigGroup.ServiceScheme.StationBased);

		// CRITICAL: Tell the service to use the e-bike mode. This links the service
		// to the 'sharing:mobi_ebike' vehicleType and scoring parameters.
		service.setMode(EBIKE_MODE);

		service.setServiceInputFile(SERVICE_XML);
		service.setMaximumAccessEgressDistance(WALK_RADIUS);
		shareCfg.addService(service);

		// Ensure the new e-bike mode is available for mode choice
		String sharedMode = SharingUtils.getServiceMode(service);
		List<String> modes = new ArrayList<>(Arrays.asList(config.subtourModeChoice().getModes()));
		if (!modes.contains(sharedMode)) {
			modes.add(sharedMode);
		}
		config.subtourModeChoice().setModes(modes.toArray(String[]::new));

		// Add non-scoring activities for shared mobility
		config.scoring().getOrCreateScoringParameters(null).getOrCreateActivityParams(SharingUtils.PICKUP_ACTIVITY).setScoringThisActivityAtAll(false);
		config.scoring().getOrCreateScoringParameters(null).getOrCreateActivityParams(SharingUtils.DROPOFF_ACTIVITY).setScoringThisActivityAtAll(false);
		config.scoring().getOrCreateScoringParameters(null).getOrCreateActivityParams(SharingUtils.BOOKING_ACTIVITY).setScoringThisActivityAtAll(false);

		Controler controller = new Controler(scenario);
		controller.addOverridingModule(new SharingModule());
		controller.addOverridingModule(new SimWrapperModule());
		controller.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(PermissibleModesCalculator.class).to(ModeChecker.class).asEagerSingleton();
			}
		});

		// --- SCENARIO INTERVENTION: Register the fare policy with E-BIKE FARE parameters ---
		// These are the specific monetary costs which are independent of the behavioral parameters in the config.
		controller.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				// For this scenario, we test the raw effect of e-bikes with their higher cost
				// and no promotional discount.
				double promoFreeMinutes = 0.0;

				// Bind the configuration values for the E-BIKE Pay-Per-Ride model
				bind(String.class).annotatedWith(Names.named("mobiMode")).toInstance(EBIKE_MODE);
				bind(Double.class).annotatedWith(Names.named("unlockFee")).toInstance(1.00); // $1.00 unlock fee
				bind(Double.class).annotatedWith(Names.named("overagePerMin")).toInstance(0.45); // Higher e-bike rate
				bind(Double.class).annotatedWith(Names.named("promoFreeMinutes")).toInstance(promoFreeMinutes);

				// Bind the fare policy class and register it as an event handler
				bind(MobiFreeMinutesFarePolicy_old.class).asEagerSingleton();
				addEventHandlerBinding().to(MobiFreeMinutesFarePolicy_old.class);
			}
		});

		// This module sets up the shared mobility service within the simulation environment (QSim)
		controller.addOverridingQSimModule(new AbstractQSimModule() {
			@Override
			protected void configureQSim() {
				SharingServiceSpecification spec = new SharingServiceSpecificationImpl();
				new SharingServiceReader(spec).readFile(SERVICE_XML);

				Id<SharingService> serviceId = Id.create("mobi_bike", SharingService.class);
				SharingService stationService = new StationBasedService(serviceId, spec, network, WALK_RADIUS);

				bind(SharingService.class).toInstance(stationService);
				bind(new TypeLiteral<Map<String, Integer>>() {})
					.annotatedWith(Names.named("stationCapacities"))
					.toInstance(SharingUtils.loadCapacitiesFrom(SERVICE_XML));

				// Bind the RebalancingListener for dynamic bike rebalancing
				bind(RebalancingListener.class).asEagerSingleton();
				bind(MobsimBeforeSimStepListener.class).to(RebalancingListener.class);
				bind(ShutdownListener.class).to(RebalancingListener.class);
			}
		});

		controller.run();
	}

	private RunScenario_Ebike() {}
}

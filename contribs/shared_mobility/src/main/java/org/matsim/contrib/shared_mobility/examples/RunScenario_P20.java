package org.matsim.contrib.shared_mobility.examples;

import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
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
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
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
 * Run script for Policy Scenario P-10: 10 promotional free minutes on a Pay-Per-Ride fare structure.
 * This script configures and runs the simulation with a custom fare policy that models
 * an unlock fee and a per-minute charge.
 */
public final class RunScenario_P20 {

	private static final String CONFIG_PATH = "C:/Users/yanni/MatsimProjects/exampleproject/scenarios/equil/25config_bikeshare_20min.xml";
	private static final String SERVICE_XML = "C:/Users/yanni/MatsimProjects/exampleproject/src/main/resources/output/25_mobi_service.xml";
	private static final double WALK_RADIUS = 500.0;

	public static void main(String[] args) {
		Config config = ConfigUtils.loadConfig(CONFIG_PATH);

		// --- Configure scoring for the Pay-Per-Ride (PPR) model ---
		ScoringConfigGroup.ModeParams mobiParams = config.scoring().getOrCreateModeParams("sharing:mobi_bike");
		mobiParams.setDailyMonetaryConstant(0.0);
		// as our custom fare policy will handle all costs (unlock fee + per-minute rate).
		mobiParams.setMonetaryDistanceRate(0.0);
		// mobiParams.setMarginalUtilityOfTraveling(0.0);

		Scenario scenario = ScenarioUtils.loadScenario(config);
		Network network = scenario.getNetwork();
		try {
			MissingStationChecker.reportMissingLinks(config.network().getInputFile(), SERVICE_XML);
		} catch (Exception e) {
			throw new RuntimeException("Station-link verification failed", e);
		}
		// Shared mobility configuration (This section remains as it was, defining the service)
		SharingConfigGroup shareCfg = ConfigUtils.addOrGetModule(config, SharingConfigGroup.class);
		SharingServiceConfigGroup service = new SharingServiceConfigGroup();
		service.setId("mobi_bike");
		service.setServiceScheme(SharingServiceConfigGroup.ServiceScheme.StationBased);
		service.setMode(TransportMode.bike);
		service.setServiceInputFile(SERVICE_XML);
		service.setMaximumAccessEgressDistance(WALK_RADIUS);
		shareCfg.addService(service);

		// Add bike mode for subtour mode choice
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

		// Ensure a scoring entry for the base mode exists
		config.scoring().getOrCreateModeParams(TransportMode.bike);

		// Controller settings from your config are used (lastIteration, writeIntervals, etc.)

		Controler controller = new Controler(scenario);
		controller.addOverridingModule(new SharingModule());
		controller.addOverridingModule(new SimWrapperModule());
		controller.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(PermissibleModesCalculator.class).to(ModeChecker.class).asEagerSingleton();
			}
		});

		// --- SCENARIO INTERVENTION: Register the NEW Pay-Per-Ride fare policy handler ---
		controller.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				// This is the "knob" for this specific scenario file: 20 free minutes.
				double promoFreeMinutes = 20.0;

				// Bind the configuration values needed by the MobiFreeMinutesFarePolicy constructor,
				// based on the PPR model (2025 rates).
				bind(String.class).annotatedWith(Names.named("mobiMode")).toInstance("sharing:mobi_bike");
				bind(Double.class).annotatedWith(Names.named("unlockFee")).toInstance(1.00); // $1.00 unlock fee
				bind(Double.class).annotatedWith(Names.named("overagePerMin")).toInstance(0.29); // $0.29 per minute
				bind(Double.class).annotatedWith(Names.named("promoFreeMinutes")).toInstance(promoFreeMinutes);

				// Tell MATSim to create one instance of our fare policy class.
				bind(MobiFreeMinutesFarePolicy_old.class).asEagerSingleton();

				// Register that single instance as an event handler to process fares.
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

	private static void addTeleportedMode(Config cfg, String mode, double speed, double beelineFactor) {
		RoutingConfigGroup.TeleportedModeParams params = new RoutingConfigGroup.TeleportedModeParams(mode);
		params.setTeleportedModeSpeed(speed);
		params.setBeelineDistanceFactor(beelineFactor);
		cfg.routing().addTeleportedModeParams(params);
	}

	private RunScenario_P20() {}
}

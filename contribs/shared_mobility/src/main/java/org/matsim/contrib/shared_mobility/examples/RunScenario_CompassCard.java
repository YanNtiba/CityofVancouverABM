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
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.population.algorithms.PermissibleModesCalculator;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.simwrapper.SimWrapperModule;

import java.util.*;
import java.util.LinkedHashSet;

public final class RunScenario_CompassCard {

	private static final double WALK_RADIUS = 500.0;

	public static void main(String[] args) {
		// === Relative defaults (repo root as working dir) ===
		final String DEFAULT_CONFIG  = RepoPaths.cfg("25config_bikeshare.xml").toString();
		final String DEFAULT_SERVICE = RepoPaths.cfg("25_mobi_service.xml").toString();
		// Optional: a sample public placeholder for plans (user must replace with their own)
		final String SAMPLE_PLANS    = RepoPaths.cfg("sample_population.xml.gz").toString();

		// === Allow CLI overrides: --config --service --plans ===
		java.util.Map<String,String> cli = new java.util.HashMap<>();
		for (int i = 0; i + 1 < args.length; i++) if (args[i].startsWith("--")) cli.put(args[i].substring(2), args[i+1]);

		final String CONFIG_PATH  = cli.getOrDefault("config",  DEFAULT_CONFIG);
		final String SERVICE_XML  = cli.getOrDefault("service", DEFAULT_SERVICE);
		final String PLANS_PATH   = cli.get("plans"); // optional; only applied if present

		// Load config
		Config config = ConfigUtils.loadConfig(CONFIG_PATH);

		// If user passed a private plans file, override what's in the config:
		if (PLANS_PATH != null) config.plans().setInputFile(PLANS_PATH);

		// 2) Declare sharing service CONFIG first (serviceCfg), then use it
		SharingConfigGroup shareCfg = ConfigUtils.addOrGetModule(config, SharingConfigGroup.class);
		SharingServiceConfigGroup serviceCfg = new SharingServiceConfigGroup();
		serviceCfg.setId("mobi_bike");
		serviceCfg.setServiceScheme(SharingServiceConfigGroup.ServiceScheme.StationBased);
		serviceCfg.setMode(TransportMode.bike);
		serviceCfg.setServiceInputFile(SERVICE_XML);
		serviceCfg.setMaximumAccessEgressDistance(WALK_RADIUS);
		shareCfg.addService(serviceCfg);

		// Canonical and module mode names
		final String SHARE_MODE_CFG = "sharing:mobi_bike"; // your canonical name
		final String SHARE_MODE_MOD = org.matsim.contrib.shared_mobility.service.SharingUtils.getServiceMode(serviceCfg); // whatever the module actually registers

		java.util.Set<String> shareModes = new java.util.LinkedHashSet<>();
		shareModes.add(SHARE_MODE_CFG);
		shareModes.add(SHARE_MODE_MOD);

		// Scoring: create ModeParams for both (no distance charge; money comes via PersonMoneyEvent)
		for (String m : shareModes) {
			var p = config.scoring().getOrCreateModeParams(m);
			p.setDailyMonetaryConstant(0.0);
			p.setMonetaryDistanceRate(0.0);
		}

		// SubtourModeChoice: include both so plans can switch either way without error
		{
			var set = new java.util.LinkedHashSet<>(java.util.Arrays.asList(config.subtourModeChoice().getModes()));
			set.addAll(shareModes);
			config.subtourModeChoice().setModes(set.toArray(new String[0]));
		}

		config.scoring().getOrCreateScoringParameters(null)
			.getOrCreateActivityParams(SharingUtils.PICKUP_ACTIVITY).setScoringThisActivityAtAll(false);
		config.scoring().getOrCreateScoringParameters(null)
			.getOrCreateActivityParams(SharingUtils.DROPOFF_ACTIVITY).setScoringThisActivityAtAll(false);
		config.scoring().getOrCreateScoringParameters(null)
			.getOrCreateActivityParams(SharingUtils.BOOKING_ACTIVITY).setScoringThisActivityAtAll(false);
		config.scoring().getOrCreateModeParams(TransportMode.bike);

		// (Optional) preflight: ensure all stations link to network
		try {
			MissingStationChecker.reportMissingLinks(config.network().getInputFile(), SERVICE_XML);
		} catch (Exception e) {
			throw new RuntimeException("Station-link verification failed", e);
		}

		// 4) Build scenario & network BEFORE creating StationBasedService
		Scenario scenario = ScenarioUtils.loadScenario(config);
		Network network = scenario.getNetwork();

		// 5) Build a single SharingService instance (used by policy/logger)
		SharingServiceSpecification spec = new SharingServiceSpecificationImpl();
		new SharingServiceReader(spec).readFile(SERVICE_XML);
		Id<SharingService> serviceId = Id.create(serviceCfg.getId(), SharingService.class);
		final SharingService stationService = new StationBasedService(serviceId, spec, network, WALK_RADIUS);

		// 6) Controller and modules
		Controler controller = new Controler(scenario);
		controller.addOverridingModule(new SharingModule());
		controller.addOverridingModule(new SimWrapperModule());

		// Mode checker (your custom)
		controller.addOverridingModule(new AbstractModule() {
			@Override public void install() {
				bind(PermissibleModesCalculator.class).to(ModeChecker.class).asEagerSingleton();
			}
		});

		// Policy bindings
		controller.addOverridingModule(new AbstractModule() {
			@Override public void install() {
				// dial set
				bind(Double.class).annotatedWith(Names.named("transferWindowSec")).toInstance(1200.0); // 20 min
				bind(Double.class).annotatedWith(Names.named("freeBikeSeconds")).toInstance(0.0);   // 15 min
				bind(Double.class).annotatedWith(Names.named("overagePerMin")).toInstance(0.15);      // match pilot if desired
				bind(Double.class).annotatedWith(Names.named("unlockFee")).toInstance(1.00);
				bind(Boolean.class).annotatedWith(Names.named("waiveUnlockWhenEligible")).toInstance(false);

				// feature toggles
				bind(Boolean.class).annotatedWith(Names.named("firstMileEnabled")).toInstance(true);  // turn on
				// spatial gate if you add it later
				bind(Double.class).annotatedWith(Names.named("spatialGateMeters")).toInstance(0.0);   // 0 = off

				// === Policy dials (add these with your other binds) ===
				bind(Boolean.class).annotatedWith(Names.named("useDiscountInsteadOfFree")).toInstance(false);   // toggle
				bind(Double.class).annotatedWith(Names.named("discountRatePerMin")).toInstance(0.0);

				// register helper
				bind(FirstMileRefunds.class).asEagerSingleton();
				addEventHandlerBinding().to(FirstMileRefunds.class); // listens for PT boards

				// Make the sharing service injectable for fare policy & logger
				bind(SharingService.class).toInstance(stationService);

				// Credit manager
				bind(TransitTripCreditManager.class).asEagerSingleton();
				addEventHandlerBinding().to(TransitTripCreditManager.class);
				addControlerListenerBinding().to(TransitTripCreditManager.class);

				// Fare policy
				bind(MobiFreeMinutesFarePolicy.class).asEagerSingleton();
				addEventHandlerBinding().to(MobiFreeMinutesFarePolicy.class);

				// Logger
				bind(CompassCardLogger.class).asEagerSingleton();
				addEventHandlerBinding().to(CompassCardLogger.class);
				addControlerListenerBinding().to(CompassCardLogger.class);

				// Optional helper
				bind(TransferAudit.class).asEagerSingleton();
			}
		});

		// QSim wiring (no SharingService re-bind here)
		controller.addOverridingQSimModule(new AbstractQSimModule() {
			@Override protected void configureQSim() {
				bind(new TypeLiteral<Map<String, Integer>>() {})
					.annotatedWith(Names.named("stationCapacities"))
					.toInstance(SharingUtils.loadCapacitiesFrom(SERVICE_XML));

				bind(RebalancingListener.class).asEagerSingleton();
				bind(MobsimBeforeSimStepListener.class).to(RebalancingListener.class);
				bind(ShutdownListener.class).to(RebalancingListener.class);
			}
		});

		controller.run();
	}

	private RunScenario_CompassCard() {}
}

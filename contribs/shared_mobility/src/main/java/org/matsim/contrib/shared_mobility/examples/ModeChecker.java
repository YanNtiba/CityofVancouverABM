package org.matsim.contrib.shared_mobility.examples;

import jakarta.inject.Inject;
import org.matsim.api.core.v01.population.*;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.Config;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.algorithms.PermissibleModesCalculator;
import org.matsim.core.population.algorithms.PermissibleModesCalculatorImpl;

import java.util.*;

public final class ModeChecker implements PermissibleModesCalculator {

	private static final String MOBI = "sharing:mobi_bike";
	private final PermissibleModesCalculator delegate;

	@Inject
	public ModeChecker(Config config) {
		this.delegate = new PermissibleModesCalculatorImpl(config);
	}

	@Override
	public Collection<String> getPermissibleModes(Plan plan) {
		Person person = plan.getPerson();

		// --- Normalize attributes so the delegate & our rules behave correctly ---
		normalizeLicense(person);       // ensure String "yes"/"no"
		normalizeCarAvailability(person); // map "car" -> "always"

		// Start from delegate’s set (handles chain-based, considerCarAvailability, etc.)
		Set<String> allowed = new LinkedHashSet<>(delegate.getPermissibleModes(plan));

		// ---- Our rules based on your plans attributes ----
		boolean licenseYes = "yes".equalsIgnoreCase(
			String.valueOf(person.getAttributes().getAttribute("license")));
		String carAvail = String.valueOf(person.getAttributes().getAttribute("carAvail")); // always/sometimes/never

		boolean carOk = licenseYes && !equalsIgnoreCaseAny(carAvail, "never", "no", "false", "null");
		if (!carOk) {
			allowed.remove(TransportMode.car);
		}

		boolean hasBike = toBoolean(person.getAttributes().getAttribute("hasBike"));
		if (!hasBike) {
			allowed.remove(TransportMode.bike);
		}

		// PT policy: allow PT discovery for everyone (good for your scenarios)
		// If you want to restrict to diary users only, uncomment the 3 lines below.
		// boolean usesPT = toBoolean(person.getAttributes().getAttribute("usesTransit"));
		// if (!usesPT) { allowed.remove(TransportMode.pt); }

		// Shared Mobi is available to all (no ownership constraint)
		allowed.add(MOBI);

		// Safety: never forbid a mode already present in the selected plan
		Plan sel = person.getSelectedPlan();
		if (sel != null) {
			for (PlanElement pe : sel.getPlanElements()) {
				if (pe instanceof Leg) {
					String m = ((Leg) pe).getMode();
					if (m != null) allowed.add(m);
				}
			}
		}
		return allowed;
	}

	// ---- helpers ----
	private static void normalizeLicense(Person p) {
		Object lic = p.getAttributes().getAttribute("license");
		if (lic instanceof String) return;
		boolean hasLicense = toBoolean(p.getAttributes().getAttribute("hasLicense"));
		p.getAttributes().putAttribute("license", hasLicense ? "yes" : "no");
	}

	private static void normalizeCarAvailability(Person p) {
		Object carAvailObj = p.getAttributes().getAttribute("carAvail");
		if (carAvailObj == null) return;
		String v = String.valueOf(carAvailObj);
		if ("car".equalsIgnoreCase(v)) {
			// PersonUtils expects "always"/"sometimes"/"never"
			p.getAttributes().putAttribute("carAvail", "always");
		}
	}

	private static boolean toBoolean(Object v) {
		if (v == null) return false;
		if (v instanceof Boolean b) return b;
		if (v instanceof String s) {
			return s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes") || s.equals("1");
		}
		return false;
	}

	private static boolean equalsIgnoreCaseAny(String s, String... vals) {
		if (s == null) return false;
		for (String v : vals) if (s.equalsIgnoreCase(v)) return true;
		return false;
	}
}

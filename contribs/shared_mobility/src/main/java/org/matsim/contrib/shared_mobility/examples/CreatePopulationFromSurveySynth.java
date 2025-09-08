package org.matsim.contrib.shared_mobility.examples;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Diary → MATSim converter that <b>adds a synthetic «home → first‑origin» leg</b>
 * when the first diary record does not start at home. Agents whose diary states
 * “Day start <i>not</i> from home” are still dropped.
 */
public class CreatePopulationFromSurveySynth {

	/*──────────────────────────────────────────────────────────*/
	/* 1 – Look‑up maps & constants                            */
	/*──────────────────────────────────────────────────────────*/
	private static final Map<String, String> ACT = Map.ofEntries(
		Map.entry("day start from home", "home"), Map.entry("to go home", "home"),
		Map.entry("dining/restaurant", "leisure"), Map.entry("recreation/social/entertainment", "leisure"),
		Map.entry("shopping", "leisure"), Map.entry("during work/business trip", "work"),
		Map.entry("to work", "work"), Map.entry("to school", "school"),
		Map.entry("personal business", "other"), Map.entry("to drive someone/pick-up", "other"),
		Map.entry("to volunteer", "other")
	);

	private static final Map<String, String> MODE = Map.ofEntries(
		Map.entry("auto driver", "car"), Map.entry("auto passenger", "car"), Map.entry("other", "car"),
		Map.entry("bike", "bike"), Map.entry("walk", "walk"), Map.entry("transit", "pt")
	);

	/** average speed [m/s] for synthetic travel‑time estimation */
	private static final Map<String, Double> MODE_SPEED = Map.of(
		"car", 11.0,   // ≈40 km/h
		"pt", 8.0,
		"bike", 4.0,
		"walk", 1.4
	);

	/** priority order when several modes are tied for most‑used */
	private static final List<String> MODE_PRIORITY = List.of("car", "pt", "bike", "walk");

	/*──────────────────────────────────────────────────────────*/
	/* 2 – Paths & caps                                         */
	/*──────────────────────────────────────────────────────────*/
	private static final String CSV_INPUT =
		"C:/Users/yanni/OneDrive - Simon Fraser University (1sfu)/Simon Fraser University (1sfu)/Yan/ABM/Baseline Model/baseline-model/data/output/agents_with_all_locations.csv";
	private static final String NETWORK_FILE =
		"C:/Users/yanni/MatsimProjects/exampleproject/src/main/resources/output/bikeshare_prepared/network_bss.xml";
	private static final String OUTPUT_DIR =
		"C:/Users/yanni/Downloads/plane/plans_synth";

	private static final int CHUNK_SIZE = 1_000_000;
	private static final int MAX_BLUEPRINT_USAGE_CAP = 500;
	private static final Map<String, Integer> REV_PERSON_USAGE = new ConcurrentHashMap<>();

	/*──────────────────────────────────────────────────────────*/
	public static void main(String[] args) throws Exception {
		new File(OUTPUT_DIR).mkdirs();

		try (CSVReader in = new CSVReaderBuilder(new FileReader(CSV_INPUT))
			.withCSVParser(new CSVParserBuilder().withSeparator(',').withQuoteChar('"').build())
			.build()) {

			String[] header = in.readNext();
			if (header == null) throw new IllegalStateException("Empty CSV");
			Map<String, Integer> col = new HashMap<>();
			for (int i = 0; i < header.length; i++) col.put(header[i].trim().toLowerCase(), i);

			List<String[]> chunk = new ArrayList<>(CHUNK_SIZE + 32_768);
			String lastSyn = null;
			int part = 1;
			String[] row;
			while ((row = in.readNext()) != null) {
				if (row.length < header.length) continue;
				String syn = text(row, col, "syn_id");

				if (chunk.size() >= CHUNK_SIZE && !Objects.equals(syn, lastSyn)) {
					processChunk(chunk, part++, col);
					chunk.clear();
				}
				chunk.add(row);
				lastSyn = syn;
			}
			if (!chunk.isEmpty()) processChunk(chunk, part, col);
		}
		writeBlueprintUsageCsv();
	}

	/*──────────────────────────────────────────────────────────*/
	private static void processChunk(List<String[]> rows, int part, Map<String, Integer> col) {
		var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new MatsimNetworkReader(scenario.getNetwork()).readFile(NETWORK_FILE);

		CoordinateTransformation tf =
			TransformationFactory.getCoordinateTransformation("EPSG:4326", "EPSG:26910");
		QuadTree<Link> qt = buildQuadTree(scenario.getNetwork());
		PopulationFactory pf = scenario.getPopulation().getFactory();

		Map<String, List<String[]>> byAgent = new LinkedHashMap<>();
		for (String[] r : rows) byAgent.computeIfAbsent(text(r, col, "syn_id"), k -> new ArrayList<>()).add(r);

		int kept = 0, skippedCap = 0, dropped = 0;

		for (var ent : byAgent.entrySet()) {
			List<String[]> trips = ent.getValue().stream()
				.filter(r -> "1".equals(text(r, col, "diaryday")))
				.sorted(Comparator.comparingDouble(r -> num(r, col, "triphour", 99)))
				.collect(Collectors.toList());
			if (trips.isEmpty()) {
				dropped++;
				continue;
			}

			if (text(trips.get(0), col, "prevpurptxt").toLowerCase().contains("not from home")) {
				dropped++;
				continue;
			}

			String blueprint = text(trips.get(0), col, "revpersonid");
			if (!blueprint.isEmpty() && REV_PERSON_USAGE.getOrDefault(blueprint, 0) >= MAX_BLUEPRINT_USAGE_CAP) {
				skippedCap++;
				continue;
			}
			REV_PERSON_USAGE.merge(blueprint, 1, Integer::sum);

			Coord homeWgs = coord(trips.get(0), "home_lat", "home_lon", col);
			if (homeWgs == null) {
				dropped++;
				continue;
			}
			Coord homeUtm = tf.transform(homeWgs);

			Person person = pf.createPerson(Id.createPersonId(ent.getKey()));
			attachAttributes(person, trips.get(0), col); // <-- Calls the new, fixed method
			Plan plan = pf.createPlan();
			boolean planIsValid = true;

			double firstStart = jitteredSecs(trips.get(0), col, "triphour");
			Activity home0 = createActivity(pf, qt, homeWgs, "home", tf);
			home0.setEndTime(firstStart);
			plan.addActivity(home0);

			if (!"home".equals(act(text(trips.get(0), col, "prevpurptxt")))) {
				String synthMode = chooseDominantMode(trips, col);

				Coord firstPrevWgs = getCoordinates(trips.get(0), act(text(trips.get(0), col, "prevpurptxt")), col);
				if (firstPrevWgs == null) {
					dropped++;
					continue;
				}
				double dist = CoordUtils.calcEuclideanDistance(homeUtm, tf.transform(firstPrevWgs));
				double travTime = dist / MODE_SPEED.getOrDefault(synthMode, 8.0);
				double dep = Math.max(0, firstStart - travTime);
				home0.setEndTime(dep);

				plan.addLeg(pf.createLeg(synthMode));

				Activity prevAct = createActivity(pf, qt, firstPrevWgs, act(text(trips.get(0), col, "prevpurptxt")), tf);
				prevAct.setEndTime(firstStart);
				plan.addActivity(prevAct);
			}

			for (int i = 0; i < trips.size(); i++) {
				String[] r = trips.get(i);
				plan.addLeg(pf.createLeg(mode(text(r, col, "modegrouptxt"))));

				String type = act(text(r, col, "currentpurptxt"));
				Coord destWgs = getCoordinates(r, type, col);
				if (destWgs == null) {
					planIsValid = false;
					break;
				}
				Activity destAct = createActivity(pf, qt, destWgs, type, tf);

				if (i + 1 < trips.size()) {
					destAct.setEndTime(jitteredSecs(trips.get(i + 1), col, "triphour"));
				}
				plan.addActivity(destAct);
			}

			if (!planIsValid) {
				dropped++;
				continue;
			}

			getLastActivity(plan).ifPresent(act -> act.setEndTimeUndefined());
			person.addPlan(plan);
			scenario.getPopulation().addPerson(person);
			kept++;
		}
		System.out.printf("✔ Chunk %d done. Kept: %,d, Dropped: %,d, Skipped by cap: %,d%n", part, kept, dropped, skippedCap);
		String file = Paths.get(OUTPUT_DIR, "plans_chunk_" + part + ".xml.gz").toString();
		new PopulationWriter(scenario.getPopulation()).writeV6(file);
	}

	// =================================================================================
	// HELPER METHODS
	// =================================================================================

	private static void writeBlueprintUsageCsv() {
		String csvFile = Paths.get(OUTPUT_DIR, "blueprint_usage.csv").toString();
		try (FileWriter w = new FileWriter(csvFile)) {
			w.append("revPersonID,agent_count\n");
			for (var e : REV_PERSON_USAGE.entrySet()) {
				w.append(e.getKey()).append(',').append(String.valueOf(e.getValue())).append('\n');
			}
			System.out.println("✅ Blueprint usage summary saved: " + csvFile);
		} catch (IOException ex) {
			System.err.println("Error writing usage CSV: " + ex.getMessage());
		}
	}

	// =================================================================================
	//  THE FIX IS HERE: This method now writes MATSim-standard attributes.
	// =================================================================================
	private static void attachAttributes(Person person, String[] row, Map<String, Integer> col) {
		// --- Car Availability and License (MATSim standard format) ---
		// These attributes must have specific names ("license", "carAvail") and
		// String values ("yes"/"no", "car"/"never") for the default MATSim
		// replanning modules to work correctly. This fixes the ClassCastException.
		boolean hasLicense = "1.0".equals(text(row, col, "drvlic"));
		boolean hasCarAccess = "1".equals(text(row, col, "autoavail"));

		// Write "license" as a String ("yes" or "no")
		person.getAttributes().putAttribute("license", hasLicense ? "yes" : "no");

		// Write "carAvail" as a String ("car" or "never") for car availability
		person.getAttributes().putAttribute("carAvail", (hasCarAccess && hasLicense) ? "car" : "never");

		// --- Custom attributes for your own logic ---
		// We keep these custom boolean attributes for easier use in our custom ModeChecker.
		person.getAttributes().putAttribute("hasBike", num(row, col, "bicyclesmax6", 0) > 0);
		person.getAttributes().putAttribute("usesTransit", "1".equals(text(row, col, "transituse")));
	}

	private static Optional<Activity> getLastActivity(Plan plan) {
		List<PlanElement> elements = plan.getPlanElements();
		for (int i = elements.size() - 1; i >= 0; i--) {
			if (elements.get(i) instanceof Activity) {
				return Optional.of((Activity) elements.get(i));
			}
		}
		return Optional.empty();
	}

	private static String chooseDominantMode(List<String[]> trips, Map<String, Integer> col) {
		Map<String, Long> counts = trips.stream()
			.map(r -> mode(text(r, col, "modegrouptxt")))
			.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
		if (counts.isEmpty()) return "car";

		long max = Collections.max(counts.values());
		List<String> topModes = counts.entrySet().stream()
			.filter(e -> e.getValue() == max)
			.map(Map.Entry::getKey)
			.toList();

		if (topModes.size() == 1) return topModes.get(0);
		return MODE_PRIORITY.stream().filter(topModes::contains).findFirst().orElse("car");
	}

	private static Activity createActivity(PopulationFactory pf, QuadTree<Link> qt, Coord wgs, String type, CoordinateTransformation tf) {
		Coord utm = tf.transform(wgs);
		Link link = qt.getClosest(utm.getX(), utm.getY());
		Activity activity = pf.createActivityFromLinkId(type, link.getId());
		activity.setCoord(utm);
		return activity;
	}

	private static String text(String[] r, Map<String, Integer> c, String n) {
		Integer p = c.get(n.toLowerCase());
		return (p == null || p >= r.length) ? "" : r[p].trim();
	}

	private static double num(String[] r, Map<String, Integer> c, String n, double def) {
		try {
			return Double.parseDouble(text(r, c, n));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static String act(String p) {
		return ACT.get(p.toLowerCase());
	}

	private static String mode(String m) {
		return MODE.get(m.toLowerCase());
	}

	private static double jitteredSecs(String[] r, Map<String, Integer> c, String n) {
		double hour = num(r, c, n, 8.0); // Default to 8am if missing
		double jitter = (Math.random() - 0.5) * 300.0; // +/- 2.5 minutes
		return hour * 3600.0 + jitter;
	}

	private static Coord coord(String[] row, String latKey, String lonKey, Map<String, Integer> idx) {
		String lat = text(row, idx, latKey);
		String lon = text(row, idx, lonKey);
		if (lat.isEmpty() || lon.isEmpty()) return null;
		try {
			return CoordUtils.createCoord(Double.parseDouble(lon), Double.parseDouble(lat));
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private static Coord getCoordinates(String[] row, String actType, Map<String, Integer> idx) {
		if (actType == null) return null;
		return switch (actType) {
			case "home" -> coord(row, "home_lat", "home_lon", idx);
			case "work" -> coord(row, "work_lat", "work_lon", idx);
			case "school" -> coord(row, "school_lat", "school_lon", idx);
			case "leisure", "other" -> coord(row, "leis_lat", "leis_lon", idx);
			default -> null;
		};
	}

	private static QuadTree<Link> buildQuadTree(Network net) {
		double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;

		for (Link l : net.getLinks().values()) {
			Coord c = l.getCoord();
			minX = Math.min(minX, c.getX());
			minY = Math.min(minY, c.getY());
			maxX = Math.max(maxX, c.getX());
			maxY = Math.max(maxY, c.getY());
		}
		QuadTree<Link> qt = new QuadTree<>(minX, minY, maxX, maxY);
		for (Link l : net.getLinks().values()) {
			qt.put(l.getCoord().getX(), l.getCoord().getY(), l);
		}
		return qt;
	}
}

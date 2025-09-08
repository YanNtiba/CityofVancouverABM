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

public class CreatePopulationFromSurvey2 {

	/*------------------------------------------------------------------*
	 * 1.  CONSTANTS & LOOK-UP MAPS
	 *------------------------------------------------------------------*/
	private static final Map<String, String> ACT_TYPE_MAP = Map.ofEntries(
		Map.entry("day start from home", "home"), Map.entry("to go home", "home"),
		Map.entry("dining/restaurant", "leisure"), Map.entry("recreation/social/entertainment", "leisure"),
		Map.entry("shopping", "leisure"), Map.entry("during work/business trip", "work"),
		Map.entry("to work", "work"), Map.entry("to school", "school"),
		Map.entry("personal business", "other"), Map.entry("to drive someone/pick-up", "other"),
		Map.entry("to volunteer", "other")
	);

	private static final Map<String, String> MODE_MAP = Map.ofEntries(
		Map.entry("auto driver", "car"), Map.entry("auto passenger", "car"), Map.entry("other", "car"),
		Map.entry("bike", "bike"), Map.entry("walk", "walk"), Map.entry("transit", "pt")
	);

	/*------------------------------------------------------------------*
	 * 2.  FILES & RUNTIME PARAMS
	 *------------------------------------------------------------------*/
	private static final String CSV_INPUT =
		"C:/Users/yanni/OneDrive - Simon Fraser University (1sfu)/Simon Fraser University (1sfu)/Yan/ABM/Baseline Model/baseline-model/data/output/agents_with_all_locations.csv";
	private static final String NETWORK_FILE =
		"C:/Users/yanni/MatsimProjects/exampleproject/src/main/resources/output/bikeshare_prepared/network_bss.xml";
	private static final String OUTPUT_DIR =
		"C:/Users/yanni/Downloads/plane/plans_repaired_v2";

	private static final int CHUNK_SIZE = 1_000_000;
	private static final int MAX_BLUEPRINT_USAGE_CAP = 500;
	private static final Map<String, Integer> revPersonIdUsage = new ConcurrentHashMap<>();

	/*------------------------------------------------------------------*/
	public static void main(String[] args) throws Exception {
		new File(OUTPUT_DIR).mkdirs();

		try (CSVReader reader = new CSVReaderBuilder(new FileReader(CSV_INPUT))
			.withCSVParser(new CSVParserBuilder().withSeparator(',').withQuoteChar('"').build())
			.build()) {

			String[] header = reader.readNext();
			if (header == null) throw new IllegalStateException("Empty CSV file.");

			Map<String, Integer> colIndexes = new HashMap<>();
			for (int i = 0; i < header.length; i++) colIndexes.put(header[i].trim().toLowerCase(), i);

			List<String[]> chunk = new ArrayList<>(CHUNK_SIZE + 50_000);
			String lastAgentId = null;
			int part = 1;
			String[] row;

			while ((row = reader.readNext()) != null) {
				if (row.length < header.length) continue;
				String currentAgentId = text(row, colIndexes, "syn_id");

				if (chunk.size() >= CHUNK_SIZE && !Objects.equals(currentAgentId, lastAgentId)) {
					buildPlansFromChunk(chunk, part++, colIndexes);
					chunk.clear();
				}
				chunk.add(row);
				lastAgentId = currentAgentId;
			}
			if (!chunk.isEmpty()) buildPlansFromChunk(chunk, part, colIndexes);
		}
		generateUsageCsv();
	}

	/*------------------------------------------------------------------*
	 * 3.  CORE CHUNK CONVERTER
	 *------------------------------------------------------------------*/
	private static void buildPlansFromChunk(List<String[]> rows, int part, Map<String, Integer> col) {
		System.out.printf("Processing chunk %d with %,d rows...%n", part, rows.size());

		// --- Scenario & helpers ------------------------------------------------
		var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new MatsimNetworkReader(scenario.getNetwork()).readFile(NETWORK_FILE);
		Population population = scenario.getPopulation();
		PopulationFactory pf = population.getFactory();

		CoordinateTransformation tf =
			TransformationFactory.getCoordinateTransformation("EPSG:4326", "EPSG:26910");
		QuadTree<Link> networkQT = buildQuadTree(scenario.getNetwork());

		// --- Group raw rows by person -----------------------------------------
		Map<String, List<String[]>> agentsData = new LinkedHashMap<>();
		for (String[] r : rows) agentsData.computeIfAbsent(text(r, col, "syn_id"), k -> new ArrayList<>()).add(r);

		int personsKept = 0, personsSkippedByCap = 0, personsTotal = agentsData.size();

		// --- Per-agent conversion ---------------------------------------------
		for (var agentEntry : agentsData.entrySet()) {

			/* ----- retain only diary day 1 rows for that person -------------- */
			List<String[]> agentTrips = agentEntry.getValue().stream()
				.filter(r -> "1".equals(text(r, col, "diaryday")))
				.collect(Collectors.toList());

			if (!isAgentDataValid(agentTrips, col)) continue;

			/* ----- blueprint usage enforcement ------------------------------- */
			String blueprintId = text(agentTrips.get(0), col, "revpersonid");
			if (!blueprintId.isEmpty() &&
				revPersonIdUsage.getOrDefault(blueprintId, 0) >= MAX_BLUEPRINT_USAGE_CAP) {
				personsSkippedByCap++;
				continue;
			}
			revPersonIdUsage.merge(blueprintId, 1, Integer::sum);

			/* ----- sort rows chronologically --------------------------------- */
			agentTrips.sort(Comparator.comparingDouble(r -> num(r, col, "triphour", 99)));

			/* ----- create MATSim person -------------------------------------- */
			Person person = pf.createPerson(Id.createPersonId(agentEntry.getKey()));
			Plan plan = pf.createPlan();

			/* ----- static home location -------------------------------------- */
			String[] firstTrip = agentTrips.get(0);
			Coord homeWgs = coord(firstTrip, "home_lat", "home_lon", col);
			Coord homeUtm = tf.transform(homeWgs);
			Link homeLink = networkQT.getClosest(homeUtm.getX(), homeUtm.getY());

			/* ----- attributes ------------------------------------------------- */
			boolean hasCar  = "1".equals(text(firstTrip, col, "autoavail")) &&
				"1.0".equals(text(firstTrip, col, "drvlic"));
			boolean hasBike = parseDouble(text(firstTrip, col, "bicyclesmax6")) > 0;

			person.getAttributes().putAttribute("hasCar",       hasCar);
			person.getAttributes().putAttribute("hasBike",      hasBike);
			person.getAttributes().putAttribute("hasLicense",   "1.0".equals(text(firstTrip, col, "drvlic")));
			person.getAttributes().putAttribute("usesTransit",  "1".equals(text(firstTrip, col, "transituse")));

			/* ----- 3a. create first activity (synthetic or diary) ------------ */
			if (!"day start from home".equalsIgnoreCase(text(firstTrip, col, "prevpurptxt"))) {
				synthesizeFirstTripFromHome(plan, pf, firstTrip, homeLink, homeUtm,
					agentTrips, col, tf, networkQT);
			} else {
				Activity firstHome = pf.createActivityFromLinkId("home", homeLink.getId());
				firstHome.setCoord(homeUtm);
				firstHome.setEndTime(jitteredSecs(firstTrip, col, "triphour"));
				plan.addActivity(firstHome);
			}

			/* ----- 3b. iterate through diary trips --------------------------- */
			for (int i = 0; i < agentTrips.size(); i++) {
				String[] currentRow   = agentTrips.get(i);
				String   currentAType = act(text(currentRow, col, "currentpurptxt"));

				Activity lastAct = getLastActivity(plan);          // *** fix ***
				if (lastAct == null) continue;

				/* ---- consolidation: identical types -> extend end-time ------ */
				if (currentAType.equals(lastAct.getType())) {
					double newEnd = (i + 1 < agentTrips.size())
						? jitteredSecs(agentTrips.get(i + 1), col, "triphour")
						: jitteredSecs(currentRow, col, "triphour")
						+ num(currentRow, col, "duration", 15) * 60;
					lastAct.setEndTime(newEnd);
				} else {
					/* ---- different type -> add leg + new activity ----------- */
					plan.addLeg(pf.createLeg(mode(text(currentRow, col, "modegrouptxt"))));

					Coord destWgs = getCoordinates(currentRow, currentAType, col);
					Coord destUtm = tf.transform(destWgs);
					Link destLink = networkQT.getClosest(destUtm.getX(), destUtm.getY());

					Activity activity = pf.createActivityFromLinkId(currentAType, destLink.getId());
					activity.setCoord(destUtm);

					double newEnd = (i + 1 < agentTrips.size())
						? jitteredSecs(agentTrips.get(i + 1), col, "triphour")
						: jitteredSecs(currentRow, col, "triphour")
						+ num(currentRow, col, "duration", 15) * 60;
					activity.setEndTime(newEnd);
					plan.addActivity(activity);
				}
			}

			/* ----- 3c. synthesize trip back home if needed ------------------- */
			Activity lastActivity = getLastActivity(plan);        // *** fix ***
			if (lastActivity != null && !"home".equals(lastActivity.getType())) {
				synthesizeFinalTripToHome(plan, pf, lastActivity,
					homeLink, homeUtm, agentTrips, col);
			}

			/* ----- 3d. open-ended final activity ---------------------------- */
			Activity finalAct = getLastActivity(plan);            // *** fix ***
			if (finalAct != null) finalAct.setEndTimeUndefined();

			person.addPlan(plan);
			population.addPerson(person);
			personsKept++;
		}

		/* --- write out ------------------------------------------------------- */
		String outFile = "plans_chunk_" + part + ".xml.gz";
		new PopulationWriter(population).writeV6(Paths.get(OUTPUT_DIR, outFile).toString());
		System.out.printf("✔ Chunk %d complete. Kept %,d of %,d persons (%.1f%%). "
				+ "Skipped %,d by cap. Output: %s%n",
			part, personsKept, personsTotal, 100.0 * personsKept / personsTotal,
			personsSkippedByCap, outFile);
	}

	/*------------------------------------------------------------------*
	 * 4.  SAFETY HELPERS
	 *------------------------------------------------------------------*/
	/** Returns the last element in the plan that is an {@link Activity}, or
	 *  {@code null} if none exists. */
	private static Activity getLastActivity(Plan plan) {
		List<PlanElement> pes = plan.getPlanElements();
		for (int i = pes.size() - 1; i >= 0; i--) {
			PlanElement pe = pes.get(i);
			if (pe instanceof Activity act) return act;
		}
		return null;
	}

	/** Basic diary sanity checks (kept relaxed). */
	private static boolean isAgentDataValid(List<String[]> trips, Map<String, Integer> col) {
		if (trips.isEmpty()) return false;
		if (coord(trips.get(0), "home_lat", "home_lon", col) == null) return false;

		for (String[] r : trips) {
			if (mode(text(r, col, "modegrouptxt")) == null) return false;
			String actType = act(text(r, col, "currentpurptxt"));
			if (getCoordinates(r, actType, col) == null) return false;
		}
		return true;
	}

	/*------------------------------------------------------------------*
	 * 5.  SYNTHETIC TRIP BUILDERS (unchanged)
	 *------------------------------------------------------------------*/
	private static double synthesizeFirstTripFromHome(Plan plan, PopulationFactory pf, String[] firstTrip,
													  Link homeLink, Coord homeUtm, List<String[]> allTrips,
													  Map<String, Integer> col, CoordinateTransformation tf,
													  QuadTree<Link> quadTree) {

		double firstActStart = jitteredSecs(firstTrip, col, "triphour");
		double assumedTravel = 30 * 60;
		double depart        = Math.max(0, firstActStart - assumedTravel);

		Activity firstHome = pf.createActivityFromLinkId("home", homeLink.getId());
		firstHome.setCoord(homeUtm);
		firstHome.setEndTime(depart);
		plan.addActivity(firstHome);

		plan.addLeg(pf.createLeg(determineAssumedMode(allTrips, col)));
		return firstActStart;
	}

	private static void synthesizeFinalTripToHome(Plan plan, PopulationFactory pf,
												  Activity lastAct, Link homeLink, Coord homeUtm,
												  List<String[]> allTrips, Map<String, Integer> col) {

		String[] firstTripRow = allTrips.get(0);
		boolean carUsedAny    = allTrips.stream()
			.anyMatch(r -> "car".equals(mode(text(r, col, "modegrouptxt"))));
		boolean carAvailable  = "1".equals(text(firstTripRow, col, "autoavail"));

		String synMode = (carUsedAny && carAvailable)
			? "car"
			: determineAssumedMode(allTrips, col);

		plan.addLeg(pf.createLeg(synMode));

		Activity home = pf.createActivityFromLinkId("home", homeLink.getId());
		home.setCoord(homeUtm);
		plan.addActivity(home);
	}

	private static String determineAssumedMode(List<String[]> trips, Map<String, Integer> col) {
		Map<String, Long> modeCounts = trips.stream()
			.map(r -> mode(text(r, col, "modegrouptxt")))
			.filter(Objects::nonNull)
			.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

		if (modeCounts.isEmpty()) return "car";

		long max = modeCounts.values().stream().max(Long::compare).orElse(0L);
		List<String> top = modeCounts.entrySet().stream()
			.filter(e -> e.getValue() == max)
			.map(Map.Entry::getKey).toList();

		if (top.size() == 1) return top.get(0);
		if (top.contains("car")) return "car";
		if (top.contains("pt"))  return "pt";
		return top.get(0);
	}

	/*------------------------------------------------------------------*
	 * 6.  OUTPUT: BLUEPRINT USAGE CSV
	 *------------------------------------------------------------------*/
	private static void generateUsageCsv() {
		String csvFile = Paths.get(OUTPUT_DIR, "blueprint_usage.csv").toString();
		System.out.println("\nGenerating blueprint usage summary CSV...");

		try (FileWriter w = new FileWriter(csvFile)) {
			w.append("revPersonID,agent_count\n");
			for (var e : revPersonIdUsage.entrySet())
				w.append(e.getKey()).append(',').append(String.valueOf(e.getValue())).append('\n');
			System.out.println("✅ Blueprint usage summary saved: " + csvFile);
		} catch (IOException ex) {
			System.err.println("Error writing usage CSV: " + ex.getMessage());
		}
	}

	/*------------------------------------------------------------------*
	 * 7.  SMALL UTILS (unchanged)
	 *------------------------------------------------------------------*/
	private static String text(String[] r, Map<String, Integer> c, String n) {
		Integer p = c.get(n.toLowerCase());
		return (p == null || p >= r.length) ? "" : r[p].trim();
	}

	private static double num(String[] r, Map<String, Integer> c, String n, double def) {
		try { return Double.parseDouble(text(r, c, n)); }
		catch (NumberFormatException e) { return def; }
	}

	private static String act(String p) { return ACT_TYPE_MAP.get(p.toLowerCase()); }
	private static String mode(String m) { return MODE_MAP.get(m.toLowerCase()); }

	private static double jitteredSecs(String[] r, Map<String, Integer> c, String n) {
		try {
			double hour = Double.parseDouble(text(r, c, n));
			double jitter = (Math.random() - 0.5) * 300.0;
			return hour * 3600.0 + jitter;
		} catch (Exception e) { return -1; }
	}

	private static Coord coord(String[] row, String latKey, String lonKey, Map<String, Integer> idx) {
		String lat = text(row, idx, latKey), lon = text(row, idx, lonKey);
		if (lat.isEmpty() || lon.isEmpty()) return null;
		try { return CoordUtils.createCoord(Double.parseDouble(lon), Double.parseDouble(lat)); }
		catch (NumberFormatException ex) { return null; }
	}

	private static Coord getCoordinates(String[] row, String act, Map<String, Integer> idx) {
		if (act == null) return null;
		return switch (act) {
			case "home"    -> coord(row, "home_lat", "home_lon", idx);
			case "work"    -> coord(row, "work_lat", "work_lon", idx);
			case "school"  -> coord(row, "school_lat", "school_lon", idx);
			case "leisure", "other" -> coord(row, "leis_lat", "leis_lon", idx);
			default        -> null;
		};
	}

	private static QuadTree<Link> buildQuadTree(Network net) {
		double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY,
			maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;

		for (Link l : net.getLinks().values()) {
			Coord c = l.getCoord();
			minX = Math.min(minX, c.getX()); minY = Math.min(minY, c.getY());
			maxX = Math.max(maxX, c.getX()); maxY = Math.max(maxY, c.getY());
		}
		QuadTree<Link> qt = new QuadTree<>(minX, minY, maxX, maxY);
		for (Link l : net.getLinks().values()) qt.put(l.getCoord().getX(), l.getCoord().getY(), l);
		return qt;
	}

	private static double parseDouble(String s) {
		try { return Double.parseDouble(s); } catch (Exception e) { return 0.0; }
	}
}

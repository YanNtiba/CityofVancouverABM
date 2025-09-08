package org.matsim.contrib.shared_mobility.examples;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import org.matsim.api.core.v01.*;
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
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CreatePopulationFromSurveyStrict {

	/*──────────────────────────────────────────────────────────*/
	/* 1 – Look‑up maps                                         */
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

	/*──────────────────────────────────────────────────────────*/
	/* 2 – Paths & caps                                         */
	/*──────────────────────────────────────────────────────────*/
	private static final String CSV =
		"C:/Users/yanni/OneDrive - Simon Fraser University (1sfu)/Simon Fraser University (1sfu)/Yan/ABM/Baseline Model/baseline-model/data/output/agents_with_all_locations.csv";
	private static final String NET =
		"C:/Users/yanni/MatsimProjects/exampleproject/src/main/resources/output/bikeshare_prepared/network_bss.xml";
	private static final String OUT_DIR =
		"C:/Users/yanni/Downloads/plane/plans_strict";

	private static final int CHUNK_SIZE = 1_000_000;
	private static final int MAX_BLUEPRINT_USAGE_CAP = 500;
	private static final Map<String, Integer> revPersonIdUsage = new ConcurrentHashMap<>();
	private static final File OUTDIR = new File(OUT_DIR);

	/*──────────────────────────────────────────────────────────*/
	public static void main(String[] args) throws Exception {

		OUTDIR.mkdirs();

		try (CSVReader in = new CSVReaderBuilder(new FileReader(CSV))
			.withCSVParser(new CSVParserBuilder().withSeparator(',').withQuoteChar('"').build())
			.build()) {

			String[] header = in.readNext();
			if (header == null) throw new IllegalStateException("Empty CSV");
			Map<String, Integer> col = new HashMap<>();
			for (int i = 0; i < header.length; i++) col.put(header[i].trim().toLowerCase(), i);

			List<String[]> chunk = new ArrayList<>(CHUNK_SIZE + 32000);
			String lastSyn = null;
			int part = 1;

			String[] row;
			while ((row = in.readNext()) != null) {
				if (row.length < header.length) continue;
				String syn = text(row, col, "syn_id");

				if (chunk.size() >= CHUNK_SIZE && !Objects.equals(syn, lastSyn)) {
					buildChunk(chunk, part++, col);
					chunk.clear();
				}
				chunk.add(row);
				lastSyn = syn;
			}
			if (!chunk.isEmpty()) buildChunk(chunk, part, col);
		}
		writeBlueprintUsageCsv();
	}

	/*──────────────────────────────────────────────────────────*/
	private static void buildChunk(List<String[]> rows, int part, Map<String, Integer> col) {

		Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new MatsimNetworkReader(sc.getNetwork()).readFile(NET);

		CoordinateTransformation tf =
			TransformationFactory.getCoordinateTransformation("EPSG:4326", "EPSG:26910");
		QuadTree<Link> qt = buildQuadTree(sc.getNetwork());
		PopulationFactory pf = sc.getPopulation().getFactory();

		Map<String, List<String[]>> byAgent = new LinkedHashMap<>();
		for (String[] r : rows)
			byAgent.computeIfAbsent(text(r, col, "syn_id"), k -> new ArrayList<>()).add(r);

		int kept = 0, skippedByCap = 0;

		for (var ent : byAgent.entrySet()) {

			List<String[]> trips = ent.getValue().stream()
				.filter(r -> "1".equals(text(r, col, "diaryday")))
				.toList();
			if (trips.size() < 2) continue;
			trips = new ArrayList<>(trips);
			trips.sort(Comparator.comparingDouble(r -> num(r, col, "triphour", 99)));

			if (!"home".equals(act(text(trips.get(0), col, "prevpurptxt")))) continue;
			if (!"home".equals(act(text(trips.get(trips.size() - 1), col, "currentpurptxt")))) continue;

			String blueprintId = text(trips.get(0), col, "revpersonid");
			if (!blueprintId.isEmpty() &&
				revPersonIdUsage.getOrDefault(blueprintId, 0) >= MAX_BLUEPRINT_USAGE_CAP) {
				skippedByCap++; continue;
			}
			revPersonIdUsage.merge(blueprintId, 1, Integer::sum);

			Coord homeWgs = coord(trips.get(0), "home_lat", "home_lon", col);
			if (homeWgs == null) continue;
			Coord homeUtm = tf.transform(homeWgs);
			Link homeLink = qt.getClosest(homeUtm.getX(), homeUtm.getY());

			String[] firstRow = trips.get(0);
			Person p = pf.createPerson(Id.createPersonId(ent.getKey()));
			p.getAttributes().putAttribute("hasCar",
				"1".equals(text(firstRow, col, "autoavail")) && "1.0".equals(text(firstRow, col, "drvlic")));
			p.getAttributes().putAttribute("hasBike", parseDouble(text(firstRow, col, "bicyclesmax6")) > 0);
			p.getAttributes().putAttribute("hasLicense", "1.0".equals(text(firstRow, col, "drvlic")));
			p.getAttributes().putAttribute("usesTransit", "1".equals(text(firstRow, col, "transituse")));

			Plan plan = pf.createPlan();

			Activity home0 = pf.createActivityFromLinkId("home", homeLink.getId());
			home0.setCoord(homeUtm);
			home0.setEndTime(jitteredSecs(firstRow, col, "triphour"));
			plan.addActivity(home0);

			boolean ok = true;
			for (String[] r : trips) {

				Activity lastA = (Activity) plan.getPlanElements()
					.get(plan.getPlanElements().size() - 1);

				Leg leg = pf.createLeg(mode(text(r, col, "modegrouptxt")));
				double depSec = lastA.getEndTime().seconds();
				leg.setDepartureTime(depSec);
				plan.addLeg(leg);

				String type = act(text(r, col, "currentpurptxt"));
				Coord destWgs = getCoordinates(r, type, col);
				if (destWgs == null) { ok = false; break; }
				Coord destUtm = tf.transform(destWgs);
				Link destLink = qt.getClosest(destUtm.getX(), destUtm.getY());

				Activity act = pf.createActivityFromLinkId(type, destLink.getId());
				act.setCoord(destUtm);
				double end = jitteredSecs(r, col, "triphour") + 60 * num(r, col, "duration", 0);
				act.setEndTime(end);
				plan.addActivity(act);
			}
			if (!ok) continue;

			((Activity) plan.getPlanElements()
				.get(plan.getPlanElements().size() - 1))
				.setEndTimeUndefined();

			p.addPlan(plan);
			sc.getPopulation().addPerson(p);
			kept++;
		}

		String file = OUT_DIR + "/plans_chunk_" + part + ".xml";
		new PopulationWriter(sc.getPopulation()).write(file);
		System.out.printf("✔ chunk %d → kept %,d (%,d skipped by cap)%n", part, kept, skippedByCap);
	}

	/*──────────────────────────────────────────────────────────*/
	/* 3 – Blueprint usage CSV                                  */
	/*──────────────────────────────────────────────────────────*/
	private static void writeBlueprintUsageCsv() {
		try {
			new File(OUT_DIR).mkdirs();
			try (FileWriter w = new FileWriter(Paths.get(OUT_DIR, "blueprint_usage.csv").toString())) {
				w.append("revPersonID,agent_count\n");
				for (var e : revPersonIdUsage.entrySet())
					w.append(e.getKey()).append(',').append(String.valueOf(e.getValue())).append('\n');
			}
			System.out.println("✅ blueprint_usage.csv written");
		} catch (Exception ex) {
			System.err.println("Failed to write blueprint CSV: " + ex.getMessage());
		}
	}

	/*──────────────────────────────────────────────────────────*/
	/* 4 – Helpers                                              */
	/*──────────────────────────────────────────────────────────*/
	private static String text(String[] r, Map<String, Integer> c, String n) {
		Integer p = c.get(n.toLowerCase()); return (p == null || p >= r.length) ? "" : r[p].trim();
	}
	private static double num(String[] r, Map<String, Integer> c, String n, double d) {
		try { return Double.parseDouble(r[c.get(n.toLowerCase())]); } catch (Exception e) { return d; }
	}
	private static String act(String s) { return ACT.getOrDefault(s.toLowerCase(), "other"); }
	private static String mode(String s) { return MODE.getOrDefault(s.toLowerCase(), "car"); }
	private static double jitteredSecs(String[] r, Map<String, Integer> c, String n) {
		double h = num(r, c, n, 8); double jit = (Math.random() - 0.5) / 12.0; return (h + jit) * 3600.0;
	}
	private static double parseDouble(String s) { try { return Double.parseDouble(s); } catch (Exception e) { return 0; } }

	private static Coord coord(String[] row, String latKey, String lonKey, Map<String, Integer> idx) {
		String lat = text(row, idx, latKey), lon = text(row, idx, lonKey);
		if (lat.isEmpty() || lon.isEmpty()) return null;
		try { return CoordUtils.createCoord(Double.parseDouble(lon), Double.parseDouble(lat)); }
		catch (NumberFormatException e) { return null; }
	}
	private static Coord getCoordinates(String[] row, String act, Map<String, Integer> idx) {
		return switch (act) {
			case "home"   -> coord(row, "home_lat", "home_lon", idx);
			case "work"   -> coord(row, "work_lat", "work_lon", idx);
			case "school" -> coord(row, "school_lat", "school_lon", idx);
			case "leisure", "other" -> coord(row, "leis_lat", "leis_lon", idx);
			default -> null;
		};
	}
	private static QuadTree<Link> buildQuadTree(Network net) {
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		for (Link l : net.getLinks().values()) {
			Coord c = l.getCoord();
			minX = Math.min(minX, c.getX()); minY = Math.min(minY, c.getY());
			maxX = Math.max(maxX, c.getX()); maxY = Math.max(maxY, c.getY());
		}
		QuadTree<Link> q = new QuadTree<>(minX, minY, maxX, maxY);
		for (Link l : net.getLinks().values())
			q.put(l.getCoord().getX(), l.getCoord().getY(), l);
		return q;
	}
}

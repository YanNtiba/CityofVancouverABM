package org.matsim.contrib.shared_mobility.examples;

import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.api.core.v01.Scenario;

import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;

public class MergePlansChunks {

	public static void main(String[] args) {
		// --- CONFIGURATION ---
		// This is the directory where CreatePopulationFromSurvey2 saved its output chunks.
		String inputFolder = "C:/Users/yanni/Downloads/plane/plans_synth";

		// This is the final, single plans file we want to create.
		String outputFile = Paths.get(inputFolder, "plans_combined_repaired.xml.gz").toString();
		// --- END CONFIGURATION ---

		Scenario mergedScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		Population mergedPopulation = mergedScenario.getPopulation();

		File folder = new File(inputFolder);
		// UPDATED: Look for the compressed .xml.gz files now.
		File[] gzFiles = folder.listFiles((dir, name) -> name.startsWith("plans_chunk_") && name.endsWith(".xml.gz"));

		if (gzFiles == null || gzFiles.length == 0) {
			System.err.println("⚠ No .xml.gz chunk files found in directory: " + inputFolder);
			System.err.println("Please ensure 'CreatePopulationFromSurvey2' has been run successfully.");
			return;
		}

		// Sort files to process them in order (e.g., chunk_1, chunk_2, ... chunk_10)
		// This is good practice although not strictly necessary for merging.
		Arrays.sort(gzFiles, Comparator.comparingInt(f ->
			Integer.parseInt(f.getName().replaceAll("[^0-9]", ""))
		));

		System.out.println("Found " + gzFiles.length + " chunk files to merge.");

		for (File gzFile : gzFiles) {
			System.out.println("  -> Merging: " + gzFile.getName());
			Scenario chunkScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
			new PopulationReader(chunkScenario).readFile(gzFile.getAbsolutePath());

			for (Person person : chunkScenario.getPopulation().getPersons().values()) {
				// This check prevents errors if an agent ID somehow spans across two chunk files.
				if (!mergedPopulation.getPersons().containsKey(person.getId())) {
					mergedPopulation.addPerson(person);
				}
			}
		}

		System.out.println("\nWriting combined population file...");
		// UPDATED: Use writeV6 for modern, compressed XML plans files.
		new PopulationWriter(mergedPopulation).writeV6(outputFile);
		System.out.println("✅ Combined population written successfully to: " + outputFile);
		System.out.printf("   Total persons in combined file: %,d%n", mergedPopulation.getPersons().size());
	}
}

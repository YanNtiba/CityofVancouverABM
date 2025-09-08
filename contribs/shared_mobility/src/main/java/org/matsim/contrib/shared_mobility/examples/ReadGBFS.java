package org.matsim.contrib.shared_mobility.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.contrib.shared_mobility.io.*;
import org.matsim.contrib.shared_mobility.service.*;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation;
import org.matsim.core.utils.io.IOUtils;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reads a GBFS feed → writes a MATSim sharing-service XML
 * (station-based, service id = {@code mobi_bike}).
 *
 * Example program arguments (one line):
 *
 * <pre>
 * --url https://vancouver-gbfs.smoove.pro/gbfs/gbfs.json
 * --output-path C:/Users/yanni/MatsimProjects/exampleproject/src/main/resources/output/vancouver_bike_share/mobi_service.xml
 * --network-path C:/Users/yanni/MatsimProjects/exampleproject/src/main/resources/output/bikeshare_prepared/network_bss.xml
 * --network-modes car,bike
 * --crs EPSG:26910
 * --service-id mobi_bike
 * </pre>
 */
public final class ReadGBFS {

	public static void main(String[] args) throws IOException, ConfigurationException {

		/* ── CLI ─────────────────────────────────────────────── */
		CommandLine cmd = new CommandLine.Builder(args)
			.requireOptions("url", "network-path", "crs",
				"network-modes", "output-path", "service-id")
			.build();

		/* ── 0. accept Smoove TLS cert ──────────────────────── */
		InsecureSsl.trustAllHosts();

		/* ── 1. CRS transform (WGS-84 → local) ──────────────── */
		CoordinateTransformation tf = new GeotoolsTransformation(
			"EPSG:4326",                          // GBFS is WGS-84
			cmd.getOptionStrict("crs"));

		/* ── 2. load + mode-filter network ──────────────────── */
		Network full = NetworkUtils.createNetwork();
		new MatsimNetworkReader(full).readFile(cmd.getOptionStrict("network-path"));

		Set<String> modes = Arrays.stream(cmd.getOptionStrict("network-modes")
				.split(","))
			.map(String::trim).collect(Collectors.toSet());

		Network net = NetworkUtils.createNetwork();
		new TransportModeNetworkFilter(full).filter(net, modes);

		/* ── 3. auto-discover feed URLs inside gbfs.json ────── */
		Map<String, URL> feeds = discoverFeeds(new URL(cmd.getOptionStrict("url")));

		/* ── 4. build SharingServiceSpecification ───────────── */
//		Id<SharingService> srvId =
//			Id.create(cmd.getOptionStrict("service-id"), SharingService.class);
		SharingServiceSpecification spec =
			new DefaultSharingServiceSpecification();   // ← no arguments

		Map<Id<SharingStation>, Id<Link>> station2link = new HashMap<>();

		/* station_information.json */
		try (BufferedReader r =
				 IOUtils.getBufferedReader(feeds.get("station_information"))) {
			for (JsonNode s : new ObjectMapper().readTree(r).findPath("stations")) {
				Id<SharingStation> sid =
					Id.create(s.get("station_id").asText(), SharingStation.class);
				int cap = s.get("capacity").asInt();
				Coord proj = tf.transform(new Coord(
					s.get("lon").asDouble(),
					s.get("lat").asDouble()));
				Link link = NetworkUtils.getNearestLink(net, proj);

				spec.addStation(ImmutableSharingStationSpecification.newBuilder()
					.id(sid)
					.capacity(cap)
					.linkId(link.getId())
					.build());
				station2link.put(sid, link.getId());
			}
		}

		/* station_status.json – add one vehicle per available bike */
		try (BufferedReader r =
				 IOUtils.getBufferedReader(feeds.get("station_status"))) {
			int vid = 0;
			for (JsonNode s : new ObjectMapper().readTree(r).findPath("stations")) {
				Id<SharingStation> sid =
					Id.create(s.get("station_id").asText(), SharingStation.class);
				int bikes = s.get("num_bikes_available").asInt();
				for (int i = 0; i < bikes; i++) {
					spec.addVehicle(ImmutableSharingVehicleSpecification.newBuilder()
						.id(Id.create("mobi_" + (vid++), SharingVehicle.class))
						.startStationId(sid)
						.startLinkId(station2link.get(sid))
						.build());
				}
			}
		}

		/* ── 5. write XML ───────────────────────────────────── */
		new SharingServiceWriter(spec).write(cmd.getOptionStrict("output-path"));

		System.out.printf("✅  wrote %s  (%d stations, %d vehicles)%n",
			cmd.getOptionStrict("output-path"),
			spec.getStations().size(),
			spec.getVehicles().size());
	}

	/* ---------- helpers ------------------------------------- */

	private static Map<String, URL> discoverFeeds(URL discovery) throws IOException {
		try (BufferedReader r = IOUtils.getBufferedReader(discovery)) {
			return parseFeeds(r);
		} catch (IOException ex) {
			// retry /en/gbfs.json automatically
			if (!discovery.toString().contains("/en/")) {
				URL fallback = new URL(discovery.toString()
					.replace("/gbfs.json", "/en/gbfs.json"));
				System.out.println("⚠️  " + ex.getMessage() + " – retrying " + fallback);
				try (BufferedReader r = IOUtils.getBufferedReader(fallback)) {
					return parseFeeds(r);
				}
			}
			throw ex;
		}
	}

	private static Map<String, URL> parseFeeds(BufferedReader reader) throws IOException {
		Map<String, URL> feeds = new HashMap<>();
		JsonNode arr = new ObjectMapper().readTree(reader).findPath("feeds");
		if (!arr.isArray()) {
			throw new IllegalStateException("'feeds' node missing in GBFS feed");
		}
		for (JsonNode f : arr) {
			feeds.put(f.get("name").asText(), new URL(f.get("url").asText()));
		}
		return feeds;
	}

	/** Trust every TLS certificate – *only* for public GBFS! */
	private static final class InsecureSsl {
		static void trustAllHosts() {
			try {
				TrustManager[] trustAllCerts = { new X509TrustManager() {
					public X509Certificate[] getAcceptedIssuers() { return null; }
					public void checkClientTrusted(X509Certificate[] c, String a) {}
					public void checkServerTrusted(X509Certificate[] c, String a) {}
				}};
				SSLContext sc = SSLContext.getInstance("SSL");
				sc.init(null, trustAllCerts, new SecureRandom());
				HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
				HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
			} catch (Exception ignored) {}
		}
	}

	private ReadGBFS() {}
}

package org.matsim.contrib.shared_mobility.analysis;

import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.contrib.shared_mobility.io.DefaultSharingServiceSpecification;
import org.matsim.contrib.shared_mobility.io.SharingServiceReader;
import org.matsim.contrib.shared_mobility.io.SharingServiceSpecification;
import org.matsim.core.network.NetworkUtils;

import java.io.File;
import java.net.MalformedURLException;
import java.util.HashSet;
import java.util.Set;

public class MissingStationChecker {

	public static void reportMissingLinks(String netFile, String srvFile) throws MalformedURLException {

		/* ---- read network ---- */
		Network net = NetworkUtils.createNetwork();
		new MatsimNetworkReader(net).readFile(netFile);
		Set<String> linkIds = new HashSet<>();
		net.getLinks().keySet().forEach(id -> linkIds.add(id.toString()));

		/* ---- read sharing service ---- */
		SharingServiceSpecification spec = new DefaultSharingServiceSpecification();
		new SharingServiceReader(spec).readFile(srvFile);   // ← back to String


		/* ---- find offenders ---- */
		spec.getStations().forEach(st -> {
			if (!linkIds.contains(st.getLinkId().toString())) {
				System.err.printf("⚠  station %-5s → missing link %s%n",
					st.getId(), st.getLinkId());
			}
		});
	}
}

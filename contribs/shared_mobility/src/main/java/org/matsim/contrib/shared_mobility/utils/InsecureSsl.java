package org.matsim.contrib.shared_mobility.utils;

import javax.net.ssl.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * ⚠ **Debug-only!**  Disables TLS certificate + host-name checks for the
 * whole JVM.  Use only for public, read-only feeds that ship a
 * mis-configured certificate (as with vancouver-gbfs.smoove.pro).
 */
public final class InsecureSsl {

	/** Call once at start-up (e.g. from ReadGBFS.main) */
	public static void trustAllHosts() {
		try {
			TrustManager[] trustAll = { new X509TrustManager() {
				public void checkClientTrusted(X509Certificate[] c, String a) {}
				public void checkServerTrusted(X509Certificate[] c, String a) {}
				public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
			}};
			SSLContext sc = SSLContext.getInstance("TLS");
			sc.init(null, trustAll, new SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
			HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
		} catch (Exception e) {
			throw new RuntimeException("Failed to disable TLS validation", e);
		}
	}

	private InsecureSsl() {}           // no instances
}

package org.matsim.contrib.shared_mobility.examples;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class RepoPaths {
    private RepoPaths() {}
    /** Repo-relative configs folder */
    public static Path cfg(String first, String... more) { return Paths.get("configs_helpers", first, more); }
}

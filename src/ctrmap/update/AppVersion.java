package ctrmap.update;

import java.io.InputStream;
import java.util.Properties;

/**
 * The running build's version, and the ordering used to decide whether a
 * release on GitHub is newer than it.
 *
 * <p>The version lives in {@code ctrmap/resources/version.properties}, which is
 * packaged into the jar, so a build always knows what it is without depending on
 * its folder name or on git being present.
 *
 * <p>Versions are {@code MAJOR.MINOR.PATCH}, compared numerically field by
 * field, with an optional leading {@code v} and an optional {@code -suffix}
 * ignored. Anything unparsable sorts as "older than everything", so a corrupt
 * or missing version can only ever result in the update being offered - never in
 * a real update being hidden.
 */
public class AppVersion {

	/** Shown when the version resource is missing (a raw source checkout). */
	public static final String UNKNOWN = "0.0.0";

	private static String current;

	/** Why the version is {@link #UNKNOWN}, or null when this build recorded one. */
	private static String unknownBecause;

	/** The running build's version, e.g. {@code "1.2.0"}. Never null. */
	public static synchronized String current() {
		if (current != null) {
			return current;
		}
		current = UNKNOWN;
		//UNKNOWN stays the answer for comparing - the updater then offers any release, which is right
		//for a build that cannot say what it is - but the reason is kept and said (F130): a missing
		//or unreadable version file read the same as a build that had never been stamped
		try (InputStream in = ctrmap.resources.ResourceAccess.getStream("version.properties")) {
			Properties p = new Properties();
			p.load(in);
			String v = p.getProperty("version");
			if (v != null && !v.trim().isEmpty()) {
				current = v.trim();
			} else {
				unknownBecause = "version.properties names no version";
			}
		} catch (java.io.IOException | IllegalStateException ex) {
			unknownBecause = ctrmap.util.Bytes.reason(ex);
		}
		return current;
	}

	/** The version as the user is shown it: with the reason when this build does not know it. */
	public static synchronized String described() {
		String v = current();
		return unknownBecause == null ? v : v + " (this build does not know its version: " + unknownBecause + ")";
	}

	/**
	 * Negative when {@code a} is older than {@code b}, positive when newer, 0
	 * when they are the same release.
	 */
	public static int compare(String a, String b) {
		int[] x = parse(a), y = parse(b);
		for (int i = 0; i < 3; i++) {
			if (x[i] != y[i]) {
				return x[i] < y[i] ? -1 : 1;
			}
		}
		return 0;
	}

	/** True when {@code candidate} is a strictly newer release than what is running. */
	public static boolean isNewerThanCurrent(String candidate) {
		return compare(current(), candidate) < 0;
	}

	/** {@code "v1.2.3-beta"} -&gt; {@code {1,2,3}}; unparsable fields become 0. */
	static int[] parse(String v) {
		int[] out = new int[3];
		if (v == null) {
			return out;
		}
		String s = v.trim();
		if (s.startsWith("v") || s.startsWith("V")) {
			s = s.substring(1);
		}
		int dash = s.indexOf('-');
		if (dash >= 0) {
			s = s.substring(0, dash);
		}
		String[] parts = s.split("\\.");
		for (int i = 0; i < 3 && i < parts.length; i++) {
			try {
				out[i] = Integer.parseInt(parts[i].trim());
			} catch (NumberFormatException ex) {
				out[i] = 0;
			}
		}
		return out;
	}
}

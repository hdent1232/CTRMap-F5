package ctrmap.tests;

/**
 * The one shape of a ratchet's second half (F189): the ceiling IS what is measured, not merely at
 * or above it.
 *
 * <p>A ratchet only bites while it is kept tight. Measured 2026-10-08: 14 of the 20 int ceilings
 * the suites held were only bounded, and the plant ledger's own two carried slack of 15 and 10 -
 * room for that many new defects to land with every suite green. SilentCatchTest printed the same
 * words for its three equality checks, so a plant could not say which one had fired; this names
 * the ceiling. BatteryHygieneTest fails a ceiling that no suite holds tight.
 *
 * <p>Not: a counter, a record or a store - it answers one comparison and words one sentence.
 */
final class Ratchet {

	private Ratchet() {
	}

	/** True when the ceiling is exactly what was measured: no slot of slack, and nothing over it. */
	static boolean tight(int measured, int ceiling) {
		return measured == ceiling;
	}

	/** What a suite prints for the tightness check, naming which ceiling it is. */
	static String said(String ceiling, int measured, int recorded) {
		return "and the " + ceiling + " ceiling is what is measured (measured " + measured + ", recorded "
				+ recorded + ")" + (measured < recorded
				? " - lower it: a slot of slack is a defect the ratchet will not notice" : "");
	}
}

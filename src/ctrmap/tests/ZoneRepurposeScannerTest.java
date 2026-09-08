package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.ZoneRepurposeScanner;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Characterization of {@link ZoneRepurposeScanner}, which reads the whole open
 * game and answers "which base zones look free to reuse". It writes nothing, so
 * its observable output IS the list, and the list is what is pinned here.
 *
 * <p>WHY THIS EXISTS. The class had never been executed by the battery. It is
 * the thing a user consults before overwriting a zone, and every one of its
 * signals is a silent one: a zone wrongly reported as free is a piece of the
 * retail game the user destroys on this program's advice, and a zone wrongly
 * reported as in use is only a missed opportunity, so the two errors are not
 * symmetric and neither of them announces itself.
 *
 * <p>The list is pinned two ways, because either alone would be weak. First an
 * INDEPENDENT ORACLE: this suite parses the zone containers itself - the same
 * bytes, a separate implementation - works out which base zones no warp points
 * at, and requires the scanner's answer to be exactly that set with exactly
 * those record counts and tiers. Second the MEASURED TOTALS against the retail
 * ORAS dump (165 candidates, 2/46/117 by tier) and a digest of the whole list,
 * so a change that moves every entry the same way still fails.
 *
 * Usage: java ctrmap.tests.ZoneRepurposeScannerTest &lt;pristine-dump-root&gt;
 */
public class ZoneRepurposeScannerTest {

	/** Measured on the retail ORAS dump; see the class doc for why they are literal. */
	private static final int EXPECTED_TOTAL = 165;
	private static final int EXPECTED_TIER0 = 2;
	private static final int EXPECTED_TIER1 = 46;
	private static final int EXPECTED_TIER2 = 117;
	private static final String EXPECTED_DIGEST = "7592105f844100be934011bd1bdc3b483102dae4a2c811668c67a5022d889d9f";

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump.getAbsolutePath());
			System.out.println("ALL PASS");
			return;
		}
		ScratchGame.open(dump);

		check(ZoneRepurposeScanner.BASE_ZONES == 536,
				"the base-zone ceiling is " + ZoneRepurposeScanner.BASE_ZONES
				+ ", the index above which zones cannot run field scripts");

		labelsSayWhichTier();

		List<ZoneRepurposeScanner.Candidate> got = ZoneRepurposeScanner.scan();
		shapeOfTheList(got);
		matchesAnIndependentParse(got);
		measuredTotals(got);

		//LAST: this wipes the workspace, so nothing may read the game afterwards
		emptyWithNoGameOpen();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** The words the user is shown for each tier, and for a tier nobody set. */
	private static void labelsSayWhichTier() {
		check("SAFEST (placeholder/empty, unreferenced)".equals(label(0)), "tier 0 reads '" + label(0) + "'");
		check("likely free (empty, no incoming warps - verify)".equals(label(1)), "tier 1 reads '" + label(1) + "'");
		check("in use (has content or incoming warps)".equals(label(2)), "tier 2 reads '" + label(2) + "'");
		check("in use (has content or incoming warps)".equals(label(9)),
				"an unexpected tier falls back to the in-use wording: '" + label(9) + "'");
	}

	private static String label(int tier) {
		ZoneRepurposeScanner.Candidate c = new ZoneRepurposeScanner.Candidate();
		c.tier = tier;
		return c.tierLabel();
	}

	private static void shapeOfTheList(List<ZoneRepurposeScanner.Candidate> got) {
		boolean sorted = true;
		boolean inRange = true;
		boolean noZero = true;
		boolean noDupes = true;
		boolean incomingAlwaysZero = true;
		boolean[] seen = new boolean[ZoneRepurposeScanner.BASE_ZONES];
		int lastTier = -1, lastIndex = -1;
		for (int i = 0; i < got.size(); i++) {
			ZoneRepurposeScanner.Candidate c = got.get(i);
			if (c.tier < lastTier || (c.tier == lastTier && c.index <= lastIndex)) {
				sorted = false;
			}
			lastTier = c.tier;
			lastIndex = c.index;
			if (c.index < 0 || c.index >= ZoneRepurposeScanner.BASE_ZONES) {
				inRange = false;
				continue;
			}
			if (c.index == 0) {
				noZero = false;
			}
			if (seen[c.index]) {
				noDupes = false;
			}
			seen[c.index] = true;
			if (c.incomingWarps != 0) {
				incomingAlwaysZero = false;
			}
		}
		check(sorted, "the list is ordered best tier first, then by zone index");
		check(inRange, "every candidate is a base zone index");
		check(noZero, "zone 0 is never offered (it is a special slot)");
		check(noDupes, "no zone is listed twice");
		check(incomingAlwaysZero, "every candidate reports 0 incoming warps, which is what got it listed");
	}

	/**
	 * The same question answered from the same bytes by a separate parse. A
	 * scanner that miscounts records, misses a warp target or mislabels a tier
	 * disagrees with this and says exactly where.
	 */
	private static void matchesAnIndependentParse(List<ZoneRepurposeScanner.Candidate> got) throws IOException {
		GARC zo = Workspace.getArchive(ArchiveType.ZONE_DATA);
		int base = Math.min(ZoneRepurposeScanner.BASE_ZONES, zo.length);
		int[] npcs = new int[base], warps = new int[base], trig = new int[base], parent = new int[base];
		boolean[] valid = new boolean[base];
		boolean[] targeted = new boolean[base];
		for (int i = 0; i < base; i++) {
			byte[] z = zo.getDecompressedEntry(i);
			if (z == null || z.length < 12 || (z[0] & 0xFF) != 0x5A || (z[1] & 0xFF) != 0x4F) {
				continue;
			}
			valid[i] = true;
			byte[] hdr = subfile(z, 0);
			parent[i] = (hdr != null && hdr.length >= 0x1E) ? (u16(hdr, 0x1C) & 0x3FF) : 0;
			byte[] ent = subfile(z, 1);
			if (ent == null) {
				continue;
			}
			ZoneEntities e = new ZoneEntities(ent);
			npcs[i] = e.npcs.size();
			warps[i] = e.warps.size();
			trig[i] = e.triggers1.size() + e.triggers2.size();
			for (ZoneEntities.Warp w : e.warps) {
				if (w.targetZone >= 0 && w.targetZone < base) {
					targeted[w.targetZone] = true;
				}
			}
		}

		//the location-name table, read with the other text parser in this tree
		File gtFile = Workspace.getWorkspaceFile(ArchiveType.GAMETEXT,
				ctrmap.formats.text.LocationNames.gametextIndex(Workspace.session()));
		GFMessageFile names = new GFMessageFile(readAll(gtFile));

		List<Integer> expectedIndices = new ArrayList<>();
		for (int i = 1; i < base; i++) {
			if (valid[i] && !targeted[i]) {
				expectedIndices.add(i);
			}
		}
		check(got.size() == expectedIndices.size(),
				"the scanner listed " + got.size() + " zones; an independent parse finds "
				+ expectedIndices.size() + " base zones no warp points at");

		boolean[] listed = new boolean[base];
		for (ZoneRepurposeScanner.Candidate c : got) {
			if (c.index >= 0 && c.index < base) {
				listed[c.index] = true;
			}
		}
		int wronglyListed = 0, wronglyOmitted = 0;
		for (int i = 1; i < base; i++) {
			boolean shouldBe = valid[i] && !targeted[i];
			if (listed[i] && !shouldBe) {
				wronglyListed++;
			}
			if (!listed[i] && shouldBe) {
				wronglyOmitted++;
			}
		}
		check(wronglyListed == 0, wronglyListed + " zone(s) were offered that a warp points at (or that do not parse)");
		check(wronglyOmitted == 0, wronglyOmitted + " zone(s) that nothing warps to were left out");

		int badCounts = 0, badTier = 0, badName = 0;
		for (ZoneRepurposeScanner.Candidate c : got) {
			int i = c.index;
			if (i < 0 || i >= base) {
				continue;
			}
			if (c.npcs != npcs[i] || c.warpsOut != warps[i] || c.triggers != trig[i]) {
				badCounts++;
			}
			String expectedName = parent[i] < names.getLineCount() ? names.getLine(parent[i]) : null;
			if (expectedName != null && !expectedName.equals(c.name)) {
				badName++;
			}
			boolean empty = npcs[i] == 0 && warps[i] == 0 && trig[i] == 0;
			String n = c.name;
			//U+FFFD, the replacement character the scanner also looks for: a
			//name line the text decoder could not make sense of. Written as an
			//escape so this file stays plain ASCII and cannot be mangled in transit.
			boolean placeholder = n == null || n.trim().isEmpty()
					|| n.indexOf('?') >= 0 || n.indexOf('\uFFFD') >= 0;
			int expectedTier = (empty && placeholder) ? 0 : (empty ? 1 : 2);
			if (c.tier != expectedTier) {
				badTier++;
			}
		}
		check(badCounts == 0, badCounts + " candidate(s) report record counts the containers do not have");
		check(badName == 0, badName + " candidate(s) carry a name that is not their zone's location line");
		check(badTier == 0, badTier + " candidate(s) are ranked into the wrong tier for their content and name");
	}

	/** The totals for the retail dump, and a digest of every field of every row. */
	private static void measuredTotals(List<ZoneRepurposeScanner.Candidate> got) throws Exception {
		int[] tiers = new int[3];
		StringBuilder all = new StringBuilder();
		for (ZoneRepurposeScanner.Candidate c : got) {
			if (c.tier >= 0 && c.tier < 3) {
				tiers[c.tier]++;
			}
			all.append(c.index).append(':').append(c.tier).append(':').append(c.npcs).append(':')
					.append(c.warpsOut).append(':').append(c.triggers).append(':').append(c.name).append('\n');
		}
		check(got.size() == EXPECTED_TOTAL, "the retail dump yields " + got.size()
				+ " reuse candidates (measured: " + EXPECTED_TOTAL + ")");
		check(tiers[0] == EXPECTED_TIER0 && tiers[1] == EXPECTED_TIER1 && tiers[2] == EXPECTED_TIER2,
				"by tier: " + tiers[0] + " safest, " + tiers[1] + " likely free, " + tiers[2]
				+ " in use (measured: " + EXPECTED_TIER0 + "/" + EXPECTED_TIER1 + "/" + EXPECTED_TIER2 + ")");

		//the two the scanner puts at the top: empty, unwarped, and named '???'
		if (got.size() >= 2) {
			check(got.get(0).index == 473 && got.get(0).tier == 0 && "???".equals(got.get(0).name),
					"the first recommendation is zone " + got.get(0).index + " '" + got.get(0).name + "'");
			check(got.get(1).index == 474 && got.get(1).tier == 0 && "???".equals(got.get(1).name),
					"the second recommendation is zone " + got.get(1).index + " '" + got.get(1).name + "'");
			ZoneRepurposeScanner.Candidate last = got.get(got.size() - 1);
			check(last.index == 529 && last.tier == 2 && last.npcs == 1,
					"the last row is zone " + last.index + ", tier " + last.tier + ", " + last.npcs + " NPC(s)");
		} else {
			check(false, "the list is too short to check its ends (" + got.size() + " rows)");
		}

		MessageDigest md = MessageDigest.getInstance("SHA-256");
		byte[] h = md.digest(all.toString().getBytes("UTF-8"));
		StringBuilder hex = new StringBuilder();
		for (int i = 0; i < h.length; i++) {
			hex.append(String.format("%02x", h[i]));
		}
		check(EXPECTED_DIGEST.equals(hex.toString()),
				"the whole list digests to " + hex + " (measured: " + EXPECTED_DIGEST
				+ "; a mismatch means the ranking, the counts or the names moved)");
	}

	/** No game open is not a crash and not a wrong answer - it is no candidates. */
	private static void emptyWithNoGameOpen() {
		Workspace.reset();
		List<ZoneRepurposeScanner.Candidate> none = ZoneRepurposeScanner.scan();
		check(none != null && none.isEmpty(),
				"with no ZoneData archive open the scan returns an empty list, not null and not a crash");
	}

	private static byte[] subfile(byte[] c, int i) {
		int cnt = (c[2] & 0xFF) | ((c[3] & 0xFF) << 8);
		if (i >= cnt) {
			return null;
		}
		int o0 = u32(c, 4 + i * 4), o1 = u32(c, 4 + (i + 1) * 4);
		if (o0 < 0 || o1 > c.length || o1 < o0) {
			return null;
		}
		byte[] o = new byte[o1 - o0];
		System.arraycopy(c, o0, o, 0, o.length);
		return o;
	}

	private static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	private static int u32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	private static byte[] readAll(File f) throws IOException {
		InputStream in = new FileInputStream(f);
		byte[] b = new byte[in.available()];
		in.read(b);
		in.close();
		return b;
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}

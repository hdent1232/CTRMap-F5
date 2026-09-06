package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.pokedata.PartyParam;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PawnDisassembler;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.PawnSubroutine;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Guards the PokePartyGetParam / PokePartySetParam selector table against the
 * retail corpus and against itself.
 *
 * <p>The table's NAMES come from the ARM code, which this test cannot re-read -
 * code.bin is not part of the workspace. What it can do honestly is hold the
 * table to everything the shipped scripts do prove, and to the internal
 * consistency a transcription error would break:
 *
 * <ol>
 * <li><b>The measurement is still there.</b> The two natives must still be
 *     found the expected number of times in the expected number of zones. Both
 *     ways of getting this wrong - reading a native record's data[0] instead of
 *     data[1], or matching PUSH_C instead of PUSH_P_C - produce a plausible
 *     empty result, so a confident zero must fail loudly rather than pass.</li>
 * <li><b>Argument 1 really is the party slot.</b> Whenever the PUSH nearest the
 *     SYSREQ_N is a literal it must be a legal party slot (0..5), while the
 *     middle PUSH ranges over the selector space. If the argument order were
 *     the other way round the "slot" position would be full of selectors like
 *     8, 13 and 49 and this would fail.</li>
 * <li><b>Every selector retail uses is in range</b> for its switch, and the
 *     histogram of literal selectors matches what was measured.</li>
 * <li><b>Everything retail WRITES is confirmed.</b> Retail only ever writes
 *     ribbons and friendship; both must pass isSafeToWrite.</li>
 * <li><b>The safety gate holds.</b> isSafeToWrite is false for anything not in
 *     the Set table, and nothing in the Set table is nature or ability - the
 *     executable has no path from either native to those setters, and a UI that
 *     believed otherwise would write an unknown byte into a player's save.</li>
 * <li><b>Get and Set agree.</b> A name present in both tables must carry the
 *     same PK6 offset in both. One mistyped offset breaks this.</li>
 * </ol>
 *
 * Usage: java ctrmap.tests.PartyParamTest &lt;path-to-zonedata-a013-garc&gt;
 */
public class PartyParamTest {

	static final long H_GET = 0xE5AB2CFAL;
	static final long H_SET = 0x6EFC380EL;

	/** Measured 2026-09-04 and re-measured here: calls, and zones containing them. */
	static final int EXPECT_GET_CALLS = 160, EXPECT_GET_ZONES = 29;
	static final int EXPECT_SET_CALLS = 13, EXPECT_SET_ZONES = 5;

	/** The literal Get selectors retail passes, selector:count. */
	static final String EXPECT_GET_HISTOGRAM
			= "0:30 1:2 2:22 3:2 4:2 5:2 8:42 10:4 11:1 12:1 13:11 14:1 15:1 29:1 36:1 37:1 38:1 39:1 "
			+ "40:1 44:5 45:2 46:1 47:1 48:3 49:6 51:1 53:6";
	/** The literal Set selectors retail passes. */
	static final String EXPECT_SET_HISTOGRAM = "1006:1 1007:12";

	public static void main(String[] args) throws Exception {
		File garc = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs/a/0/1/3");
		List<String> fails = new ArrayList<>();

		// ---- structural checks, no game data needed -----------------------
		checkTables(fails);

		// ---- corpus checks ------------------------------------------------
		GARC zo = new GARC(garc);
		Map<Long, Scan> scans = new LinkedHashMap<>();
		scans.put(H_GET, new Scan("PokePartyGetParam"));
		scans.put(H_SET, new Scan("PokePartySetParam"));

		int scanned = 0;
		for (int z = 0; z < zo.getEntryCount(); z++) {
			GFLPawnScript scr = zoneScript(zo, z);
			if (scr == null) {
				continue;
			}
			scanned++;
			Map<Integer, Long> slot = new LinkedHashMap<>();
			for (int i = 0; i < scr.natives.size(); i++) {
				int[] nd = scr.natives.get(i).data;
				// the native's name hash lives in data[1]; data[0] is what
				// publics use and copying that resolves everything to nothing
				long hash = (nd.length > 1 ? nd[1] : nd[0]) & 0xFFFFFFFFL;
				if (scans.containsKey(hash)) {
					slot.put(i, hash);
				}
			}
			if (slot.isEmpty()) {
				continue;
			}
			List<PawnInstruction> flat = new ArrayList<>();
			for (PawnSubroutine sub : PawnDisassembler.disassembleScript(scr)) {
				flat.addAll(sub.instructions);
			}
			for (int i = 0; i < flat.size(); i++) {
				PawnInstruction ins = flat.get(i);
				if (ins.getCommand() != PawnInstruction.Commands.SYSREQ_N.ordinal()) {
					continue;
				}
				if (ins.argumentCells == null || ins.argumentCells.length < 2) {
					continue;
				}
				Long hash = slot.get(ins.argumentCells[0]);
				if (hash == null) {
					continue;
				}
				int nargs = ins.argumentCells[1] / 4;
				int[] argv = argsOf(flat, i, nargs);
				if (argv == null) {
					continue;
				}
				scans.get(hash).add(z, argv);
			}
		}

		Scan get = scans.get(H_GET), set = scans.get(H_SET);
		System.out.println("scripts scanned: " + scanned);
		System.out.println(get);
		System.out.println(set);

		// 1. the measurement is still there
		expect(fails, "PokePartyGetParam call count", EXPECT_GET_CALLS, get.calls);
		expect(fails, "PokePartyGetParam zone count", EXPECT_GET_ZONES, get.zones.size());
		expect(fails, "PokePartySetParam call count", EXPECT_SET_CALLS, set.calls);
		expect(fails, "PokePartySetParam zone count", EXPECT_SET_ZONES, set.zones.size());

		// 2. argument 1 is the party slot
		for (Scan sc : new Scan[]{get, set}) {
			for (Map.Entry<Integer, Integer> e : sc.arg1.entrySet()) {
				if (e.getKey() < 0 || e.getKey() > 5) {
					fails.add(sc.name + ": argument 1 literal " + e.getKey()
							+ " is not a party slot (0..5) - the argument order is not what the table assumes");
				}
			}
		}
		if (get.arg1.isEmpty() && set.arg1.isEmpty()) {
			fails.add("no literal argument-1 value anywhere - the slot check proved nothing");
		}

		// 3. selectors in range, and the histogram is what was measured
		for (int sel : get.arg2.keySet()) {
			if (!PartyParam.isGetSelector(sel)) {
				fails.add("Get selector " + sel + " is outside the switch range "
						+ PartyParam.GET_MIN + ".." + PartyParam.GET_MAX);
			}
		}
		for (int sel : set.arg2.keySet()) {
			if (!PartyParam.isSetSelector(sel)) {
				fails.add("Set selector " + sel + " is outside the switch range "
						+ PartyParam.SET_MIN + ".." + PartyParam.SET_MAX);
			}
		}
		expectText(fails, "Get selector histogram", EXPECT_GET_HISTOGRAM, hist(get.arg2));
		expectText(fails, "Set selector histogram", EXPECT_SET_HISTOGRAM, hist(set.arg2));

		// 4. everything retail writes must be confirmed
		for (int sel : set.arg2.keySet()) {
			if (!PartyParam.isSafeToWrite(sel)) {
				fails.add("retail writes Set selector " + sel + " but the table does not confirm it");
			}
		}

		// 5. every Get selector retail reads must be named
		List<Integer> unnamedUsed = new ArrayList<>();
		for (int sel : get.arg2.keySet()) {
			if (PartyParam.get(sel) == null) {
				unnamedUsed.add(sel);
			}
		}
		// 45 is the one selector retail uses that was traced but not named.
		if (!unnamedUsed.equals(java.util.Arrays.asList(45))) {
			fails.add("unnamed Get selectors used by retail changed: expected [45], got " + unnamedUsed);
		}

		for (String f : fails) {
			System.out.println("  FAIL: " + f);
		}
		System.out.println("named Get selectors: " + PartyParam.getTable().size()
				+ "  named Set selectors: " + PartyParam.setTable().size());
		System.out.println(fails.isEmpty() ? "ALL PASS" : "FAILURES PRESENT");
		if (!fails.isEmpty()) {
			System.exit(1);
		}
	}

	/** Table-internal invariants: no game data, no executable, just the table. */
	private static void checkTables(List<String> fails) {
		Set<String> getNames = new HashSet<>(), setNames = new HashSet<>();
		for (PartyParam.Param p : PartyParam.getTable().values()) {
			if (!PartyParam.isGetSelector(p.selector)) {
				fails.add("Get table names selector " + p.selector + ", outside the switch");
			}
			if (!getNames.add(p.name)) {
				fails.add("Get table names " + p.name + " twice");
			}
			if (p.pk6Offset != -1 && (p.pk6Offset < 0 || p.pk6Offset > 0xE7)) {
				fails.add(p.name + ": PK6 offset " + p.pk6Offset + " is outside the 232-byte record");
			}
			if (p.evidence == null || p.evidence.isEmpty()) {
				fails.add(p.name + ": named with no evidence recorded");
			}
		}
		for (PartyParam.Param p : PartyParam.setTable().values()) {
			if (!PartyParam.isSetSelector(p.selector)) {
				fails.add("Set table names selector " + p.selector + ", outside the switch");
			}
			if (!setNames.add(p.name)) {
				fails.add("Set table names " + p.name + " twice");
			}
			if (p.pk6Offset != -1 && (p.pk6Offset < 0 || p.pk6Offset > 0xE7)) {
				fails.add(p.name + ": PK6 offset " + p.pk6Offset + " is outside the 232-byte record");
			}
			// a setter with no confirmed reading must never be writable
			if (p.certainty != PartyParam.Certainty.CONFIRMED && PartyParam.isSafeToWrite(p.selector)) {
				fails.add(p.name + ": unconfirmed but isSafeToWrite says yes");
			}
		}
		// nothing may claim to write nature or ability: the executable has no
		// path from either native to those setters
		for (String forbidden : new String[]{"NATURE", "ABILITY"}) {
			if (PartyParam.setSelectorFor(forbidden) != null) {
				fails.add("the Set table claims to write " + forbidden
						+ ", which no case of PokePartySetParam reaches");
			}
		}
		// the safety gate is closed outside the table
		for (int sel : new int[]{-1, 0, 22, 999, 1020, 65535}) {
			if (PartyParam.isSafeToWrite(sel)) {
				fails.add("isSafeToWrite(" + sel + ") is true but that selector is not a confirmed setter");
			}
		}
		// a name in both tables must describe the same byte
		for (PartyParam.Param s : PartyParam.setTable().values()) {
			for (PartyParam.Param g : PartyParam.getTable().values()) {
				if (g.name.equals(s.name) && g.pk6Offset != s.pk6Offset) {
					fails.add(s.name + ": Get says PK6 0x" + Integer.toHexString(g.pk6Offset)
							+ " but Set says PK6 0x" + Integer.toHexString(s.pk6Offset));
				}
			}
		}
	}

	/**
	 * The literal arguments of a native call, argv[0] = argument 1 (the PUSH
	 * nearest the SYSREQ_N). -1 where the pushed value is not a constant.
	 */
	static int[] argsOf(List<PawnInstruction> flat, int sysreqAt, int nargs) {
		PawnInstruction.Commands[] all = PawnInstruction.Commands.values();
		List<Integer> nearestFirst = new ArrayList<>();
		for (int k = sysreqAt - 1; k >= 0 && nearestFirst.size() < nargs; k--) {
			PawnInstruction p = flat.get(k);
			String op = all[p.getCommand()].name();
			if (!op.startsWith("PUSH")) {
				break;
			}
			boolean isConst = op.endsWith("_C");
			nearestFirst.add(isConst && p.argumentCells != null && p.argumentCells.length > 0
					? p.argumentCells[0] : -1);
		}
		if (nearestFirst.size() < nargs) {
			return null;
		}
		int[] argv = new int[nargs];
		for (int i = 0; i < nargs; i++) {
			argv[i] = nearestFirst.get(i);
		}
		return argv;
	}

	static class Scan {

		final String name;
		int calls;
		final Set<Integer> zones = new TreeSet<>();
		final TreeMap<Integer, Integer> arg1 = new TreeMap<>();
		final TreeMap<Integer, Integer> arg2 = new TreeMap<>();

		Scan(String name) {
			this.name = name;
		}

		void add(int zone, int[] argv) {
			calls++;
			zones.add(zone);
			if (argv.length > 0 && argv[0] != -1) {
				bump(arg1, argv[0]);
			}
			if (argv.length > 1 && argv[1] != -1) {
				bump(arg2, argv[1]);
			}
		}

		@Override
		public String toString() {
			return name + ": " + calls + " call(s) in " + zones.size() + " zone(s)"
					+ "\n    arg1 (party slot) literals: " + hist(arg1)
					+ "\n    arg2 (selector)   literals: " + hist(arg2);
		}
	}

	static void bump(Map<Integer, Integer> m, int k) {
		Integer v = m.get(k);
		m.put(k, v == null ? 1 : v + 1);
	}

	static String hist(TreeMap<Integer, Integer> m) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Integer> e : m.entrySet()) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(e.getKey()).append(':').append(e.getValue());
		}
		return sb.toString();
	}

	static void expect(List<String> fails, String what, int want, int got) {
		if (want != got) {
			fails.add(what + ": expected " + want + ", measured " + got);
		}
	}

	static void expectText(List<String> fails, String what, String want, String got) {
		if (!want.equals(got)) {
			fails.add(what + ":\n      expected " + want + "\n      measured " + got);
		}
	}

	static GFLPawnScript zoneScript(GARC garc, int index) {
		try {
			byte[] zo = garc.getDecompressedEntry(index);
			if (zo == null || zo.length < 4) {
				return null;
			}
			if ((((zo[0] & 0xFF) << 8) | (zo[1] & 0xFF)) != 0x5A4F) {
				return null;
			}
			int count = (zo[2] & 0xFF) | ((zo[3] & 0xFF) << 8);
			if (count < 3 || zo.length < 4 + (count + 1) * 4) {
				return null;
			}
			int start = le(zo, 4 + 2 * 4), end = le(zo, 4 + 3 * 4);
			if (start < 0 || end > zo.length || end <= start) {
				return null;
			}
			return new GFLPawnScript(java.util.Arrays.copyOfRange(zo, start, end));
		} catch (Throwable t) {
			return null;
		}
	}

	static int le(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}

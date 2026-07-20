package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.ZoneScriptAnalyzer;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Headless test for ZoneScriptAnalyzer against the PRISTINE ORAS ZoneData
 * GARC (a/0/1/3, read-only backup). Run with:
 * java -cp build/classes ctrmap.tests.ZoneScriptAnalyzerTest
 *
 * Fixed-point checks on zones 24, 5 and 1 (values verified by manual
 * disassembly), a patchTalkerLine round-trip through serialized bytes, and a
 * full dispatch/wrapper sweep over all 536 ZO zones with zero exceptions
 * allowed.
 */
public class ZoneScriptAnalyzerTest {

	private static final String DEFAULT_GARC_PATH = "C:\\Users\\flami\\Desktop\\Claude\\sessions\\3DS Editor\\RomFS_original_garcs\\a\\0\\1\\3";
	private static final int ZONE_COUNT = 536;

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0] : DEFAULT_GARC_PATH);
		if (!garcFile.exists()) {
			System.out.println("FAIL: pristine ZoneData GARC not found: " + garcFile.getAbsolutePath());
			System.exit(1);
		}
		GARC garc = new GARC(garcFile);

		//zone 24: 10 script IDs, talkers on cases 7 (line 5) and 8 (line 8)
		GFLPawnScript z24 = getZoneScript(garc, 24);
		check(z24 != null, "zone 24 script parsed");
		ZoneScriptAnalyzer.Dispatch d24 = ZoneScriptAnalyzer.findDispatch(z24);
		check(d24 != null, "zone 24 dispatch found");
		check(d24.cases.containsKey(-1), "zone 24 has -1 init case");
		List<Integer> ids24 = ZoneScriptAnalyzer.listScriptIds(z24);
		check(ids24.equals(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)),
				"zone 24 script IDs == [1..10], got " + ids24);
		ZoneScriptAnalyzer.TalkerPattern t7 = ZoneScriptAnalyzer.findTalkerPattern(z24, 7);
		check(t7 != null, "zone 24 case 7 is a simple talker");
		check(t7.msgLine == 5, "zone 24 case 7 msgLine == 5, got " + t7.msgLine);
		ZoneScriptAnalyzer.TalkerPattern t8 = ZoneScriptAnalyzer.findTalkerPattern(z24, 8);
		check(t8 != null, "zone 24 case 8 is a simple talker");
		check(t8.msgLine == 8, "zone 24 case 8 msgLine == 8, got " + t8.msgLine);
		PawnInstruction w24 = ZoneScriptAnalyzer.findMsgWrapper(z24);
		check(w24 != null, "zone 24 msg wrapper found");
		check(t7.wrapperEntry != null && t7.wrapperEntry.pointer == w24.pointer,
				"zone 24 talker calls the found wrapper (0x" + Integer.toHexString(w24.pointer) + ")");

		//zone 5: exactly 3 talkers on cases 2, 4, 6 with lines 0, 1, 2
		GFLPawnScript z5 = getZoneScript(garc, 5);
		check(z5 != null, "zone 5 script parsed");
		check(ZoneScriptAnalyzer.findDispatch(z5) != null, "zone 5 dispatch found");
		List<Integer> talkerKeys5 = new ArrayList<>();
		List<Integer> talkerLines5 = new ArrayList<>();
		for (int id : ZoneScriptAnalyzer.listScriptIds(z5)) {
			ZoneScriptAnalyzer.TalkerPattern tp = ZoneScriptAnalyzer.findTalkerPattern(z5, id);
			if (tp != null) {
				talkerKeys5.add(id);
				talkerLines5.add(tp.msgLine);
			}
		}
		check(talkerKeys5.equals(Arrays.asList(2, 4, 6)),
				"zone 5 talker cases == [2, 4, 6], got " + talkerKeys5);
		check(talkerLines5.equals(Arrays.asList(0, 1, 2)),
				"zone 5 talker msgLines == [0, 1, 2], got " + talkerLines5);
		check(ZoneScriptAnalyzer.findMsgWrapper(z5) != null, "zone 5 msg wrapper found");

		//zone 1: no publics, NPCs use engine-range script IDs - must not crash
		GFLPawnScript z1 = getZoneScript(garc, 1);
		check(z1 != null, "zone 1 script parsed");
		ZoneScriptAnalyzer.Dispatch d1 = ZoneScriptAnalyzer.findDispatch(z1);
		check(d1 != null, "zone 1 dispatch found");
		for (int id : ZoneScriptAnalyzer.listScriptIds(z1)) {
			ZoneScriptAnalyzer.findTalkerPattern(z1, id); //no crash
		}
		ZoneScriptAnalyzer.findMsgWrapper(z1); //no crash

		//patchTalkerLine: in place, then through a serialize/reparse round trip
		check(ZoneScriptAnalyzer.patchTalkerLine(z24, 7, 9), "zone 24 case 7 patched to line 9");
		ZoneScriptAnalyzer.TalkerPattern t7b = ZoneScriptAnalyzer.findTalkerPattern(z24, 7);
		check(t7b != null && t7b.msgLine == 9, "zone 24 case 7 msgLine == 9 after patch");
		GFLPawnScript reparsed = new GFLPawnScript(z24.getScriptBytes());
		reparsed.decompressThis();
		ZoneScriptAnalyzer.TalkerPattern t7c = ZoneScriptAnalyzer.findTalkerPattern(reparsed, 7);
		check(t7c != null && t7c.msgLine == 9, "patched msgLine survives serialization");
		ZoneScriptAnalyzer.TalkerPattern t8c = ZoneScriptAnalyzer.findTalkerPattern(reparsed, 8);
		check(t8c != null && t8c.msgLine == 8, "unpatched talker unchanged after serialization");

		//sweep: all 536 ZO zones, zero exceptions allowed
		int zoZones = 0;
		int skipped = 0;
		int dispatchCount = 0;
		int wrapperCount = 0;
		int talkerCount = 0;
		int scriptIdCount = 0;
		int exceptions = 0;
		for (int i = 0; i < ZONE_COUNT; i++) {
			try {
				GFLPawnScript s = getZoneScript(garc, i);
				if (s == null) {
					skipped++; //not a ZO container (dummy entry)
					continue;
				}
				zoZones++;
				ZoneScriptAnalyzer.Dispatch d = ZoneScriptAnalyzer.findDispatch(s);
				if (d != null) {
					dispatchCount++;
					List<Integer> ids = ZoneScriptAnalyzer.listScriptIds(s);
					scriptIdCount += ids.size();
					for (int id : ids) {
						if (ZoneScriptAnalyzer.findTalkerPattern(s, id) != null) {
							talkerCount++;
						}
					}
				}
				if (ZoneScriptAnalyzer.findMsgWrapper(s) != null) {
					wrapperCount++;
				}
			} catch (Exception ex) {
				exceptions++;
				System.out.println("EXCEPTION at zone " + i + ": " + ex);
				ex.printStackTrace(System.out);
			}
		}
		System.out.println("sweep: " + zoZones + "/" + ZONE_COUNT + " ZO zones (" + skipped + " non-ZO skipped)");
		System.out.println("sweep: dispatch found in " + dispatchCount + " zones");
		System.out.println("sweep: msg wrapper found in " + wrapperCount + " zones");
		System.out.println("sweep: " + scriptIdCount + " script IDs total, " + talkerCount + " simple talkers");
		System.out.println("sweep: " + exceptions + " exceptions");
		check(exceptions == 0, "sweep had zero exceptions");
		check(zoZones + skipped == ZONE_COUNT, "all zone entries visited");
		check(dispatchCount > 0 && wrapperCount > 0, "sweep found dispatches and wrappers");

		System.out.println("PASS");
	}

	/**
	 * Extracts subfile 2 (map script) of ZoneData entry index and parses it.
	 * Returns null when the entry is not a ZO container (magic 0x5A4F).
	 */
	private static GFLPawnScript getZoneScript(GARC garc, int index) {
		byte[] zo = garc.getDecompressedEntry(index);
		if (zo == null || zo.length < 4) {
			return null;
		}
		int magic = ((zo[0] & 0xFF) << 8) | (zo[1] & 0xFF); //big-endian, matches AbstractGamefreakContainer.read2Bytes
		if (magic != 0x5A4F) {
			return null;
		}
		int count = (zo[2] & 0xFF) | ((zo[3] & 0xFF) << 8);
		if (count < 3 || zo.length < 4 + (count + 1) * 4) {
			return null;
		}
		int start = readIntLE(zo, 4 + 2 * 4);
		int end = readIntLE(zo, 4 + 3 * 4);
		if (start < 0 || end > zo.length || end <= start) {
			return null;
		}
		byte[] scr = new byte[end - start];
		System.arraycopy(zo, start, scr, 0, scr.length);
		GFLPawnScript s = new GFLPawnScript(scr);
		s.decompressThis();
		return s;
	}

	private static int readIntLE(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}

	private static void check(boolean cond, String what) {
		if (!cond) {
			System.out.println("FAIL: " + what);
			System.exit(1);
		}
		System.out.println("ok: " + what);
	}
}

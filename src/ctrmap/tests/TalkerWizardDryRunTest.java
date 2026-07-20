package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.TalkerScriptWizard;
import ctrmap.formats.scripts.ZoneScriptAnalyzer;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Headless dry run of the "add talking NPC" wizard over the PRISTINE ORAS
 * ZoneData GARC (read-only backup, nothing is written). Run with:
 * java -cp build/classes ctrmap.tests.TalkerWizardDryRunTest
 *
 * For EVERY ZO zone that has both a script dispatch and a message wrapper,
 * the full clone operation is executed in memory, the result is reserialized
 * with getScriptBytes(), re-parsed with a fresh GFLPawnScript and verified:
 * the new case key resolves to a valid talker with the fake line, all
 * pre-existing case keys still resolve to the same instruction patterns,
 * the publics still point at the same instruction kinds (PROCs) and the main
 * entry point is still valid. Zero exceptions allowed.
 */
public class TalkerWizardDryRunTest {

	private static final String DEFAULT_GARC_PATH = "C:\\Users\\flami\\Desktop\\Claude\\sessions\\3DS Editor\\RomFS_original_garcs\\a\\0\\1\\3";
	private static final int ZONE_COUNT = 536;
	private static final int FAKE_LINE = 1234;
	private static final int OP_PROC = PawnInstruction.Commands.PROC.ordinal();

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0] : DEFAULT_GARC_PATH);
		if (!garcFile.exists()) {
			System.out.println("FAIL: pristine ZoneData GARC not found: " + garcFile.getAbsolutePath());
			System.exit(1);
		}
		GARC garc = new GARC(garcFile);

		int zoZones = 0;
		int skipped = 0;
		int eligible = 0;
		int passed = 0;
		List<String> failures = new ArrayList<>();
		for (int i = 0; i < ZONE_COUNT; i++) {
			GFLPawnScript s;
			try {
				s = getZoneScript(garc, i);
			} catch (Exception ex) {
				failures.add("zone " + i + ": parse exception " + ex);
				continue;
			}
			if (s == null) {
				skipped++; //not a ZO container (dummy entry)
				continue;
			}
			zoZones++;
			try {
				if (ZoneScriptAnalyzer.findDispatch(s) == null || ZoneScriptAnalyzer.findMsgWrapper(s) == null) {
					continue; //not eligible for the wizard
				}
				eligible++;
				String err = dryRunZone(s);
				if (err == null) {
					passed++;
				} else {
					failures.add("zone " + i + ": " + err);
				}
			} catch (Exception ex) {
				failures.add("zone " + i + ": EXCEPTION " + ex);
				ex.printStackTrace(System.out);
			}
		}

		for (String f : failures) {
			System.out.println("FAILURE: " + f);
		}
		System.out.println("dry run: " + zoZones + "/" + ZONE_COUNT + " ZO zones (" + skipped + " non-ZO skipped)");
		System.out.println("dry run: " + eligible + " zones eligible (dispatch + wrapper)");
		System.out.println("dry run: " + passed + "/" + eligible + " zones passed, " + failures.size() + " failures");
		if (failures.isEmpty() && eligible > 0 && passed == eligible) {
			System.out.println("PASS");
		} else {
			System.out.println("FAIL");
			System.exit(1);
		}
	}

	/**
	 * Runs the full clone on one eligible zone script and verifies the
	 * reserialized result. Returns null on success, an error string otherwise.
	 */
	private static String dryRunZone(GFLPawnScript s) {
		//pre-state: every dispatch case key -> [target command, isTalker, msgLine]
		ZoneScriptAnalyzer.Dispatch pre = ZoneScriptAnalyzer.findDispatch(s);
		Map<Integer, int[]> preCases = new LinkedHashMap<>();
		for (Map.Entry<Integer, PawnInstruction> e : pre.cases.entrySet()) {
			int cmd = (e.getValue() == null) ? -1 : e.getValue().getCommand();
			ZoneScriptAnalyzer.TalkerPattern tp = ZoneScriptAnalyzer.findTalkerPattern(s, e.getKey());
			preCases.put(e.getKey(), new int[]{cmd, tp == null ? 0 : 1, tp == null ? -1 : tp.msgLine});
		}
		//pre-state: command each public points at
		int[] prePublics = new int[s.publics.size()];
		for (int i = 0; i < prePublics.length; i++) {
			PawnInstruction target = s.lookupInstructionByPtr(s.publics.get(i).data[0]);
			prePublics[i] = (target == null) ? -1 : target.getCommand();
		}
		PawnInstruction preMain = s.lookupInstructionByPtr(s.mainEntryPoint);
		int preMainCmd = (preMain == null) ? -1 : preMain.getCommand();
		//pre-state: VM heap/stack headroom (allocatedMem - heapStart) - a clone
		//grows the code section, and write() must grow allocatedMem by the same
		//delta instead of silently shrinking the runtime heap/stack
		int preHeadroom = s.allocatedMem - s.heapStart;

		//the full clone operation, in memory
		int newId = TalkerScriptWizard.cloneTalker(s, FAKE_LINE);

		//reserialize and re-parse from scratch
		byte[] bytes = s.getScriptBytes();
		if (bytes == null) {
			return "getScriptBytes() returned null";
		}
		GFLPawnScript fresh = new GFLPawnScript(bytes);
		fresh.decompressThis();

		//allocatedMem headroom preserved after the clone
		int postHeadroom = fresh.allocatedMem - fresh.heapStart;
		if (postHeadroom != preHeadroom) {
			return "VM heap/stack headroom changed: was " + preHeadroom + ", now " + postHeadroom;
		}

		//main entry still valid
		PawnInstruction postMain = fresh.lookupInstructionByPtr(fresh.mainEntryPoint);
		if (postMain == null) {
			return "main entry point 0x" + Integer.toHexString(fresh.mainEntryPoint) + " no longer lands on an instruction";
		}
		if (postMain.getCommand() != preMainCmd) {
			return "main entry instruction changed: was cmd " + preMainCmd + ", now " + postMain.getCommand();
		}
		ZoneScriptAnalyzer.Dispatch post = ZoneScriptAnalyzer.findDispatch(fresh);
		if (post == null) {
			return "dispatch no longer found after clone";
		}
		//new case key present and resolves to a valid talker with the fake line
		if (!post.cases.containsKey(newId)) {
			return "new case key " + newId + " missing from CASETBL";
		}
		ZoneScriptAnalyzer.TalkerPattern ntp = ZoneScriptAnalyzer.findTalkerPattern(fresh, newId);
		if (ntp == null) {
			return "new case key " + newId + " does not resolve to a simple talker";
		}
		if (ntp.msgLine != FAKE_LINE) {
			return "new talker msgLine == " + ntp.msgLine + ", expected " + FAKE_LINE;
		}
		PawnInstruction postWrapper = ZoneScriptAnalyzer.findMsgWrapper(fresh);
		if (postWrapper == null || ntp.wrapperEntry == null || ntp.wrapperEntry.pointer != postWrapper.pointer) {
			return "new talker does not call the zone's message wrapper";
		}
		//all pre-existing case keys still resolve to the same patterns
		if (post.cases.size() != preCases.size() + 1) {
			return "case count " + post.cases.size() + ", expected " + (preCases.size() + 1);
		}
		for (Map.Entry<Integer, int[]> e : preCases.entrySet()) {
			int key = e.getKey();
			int[] info = e.getValue();
			if (!post.cases.containsKey(key)) {
				return "pre-existing case key " + key + " missing after clone";
			}
			PawnInstruction target = post.cases.get(key);
			int cmd = (target == null) ? -1 : target.getCommand();
			if (cmd != info[0]) {
				return "case " + key + " target command changed: was " + info[0] + ", now " + cmd;
			}
			ZoneScriptAnalyzer.TalkerPattern tp = ZoneScriptAnalyzer.findTalkerPattern(fresh, key);
			if ((tp != null ? 1 : 0) != info[1]) {
				return "case " + key + " talker-ness changed (was " + (info[1] == 1) + ")";
			}
			if (tp != null && tp.msgLine != info[2]) {
				return "case " + key + " talker msgLine changed: was " + info[2] + ", now " + tp.msgLine;
			}
		}
		//publics still point at PROC instructions (same command as before)
		if (fresh.publics.size() != prePublics.length) {
			return "publics count changed";
		}
		for (int i = 0; i < prePublics.length; i++) {
			PawnInstruction target = fresh.lookupInstructionByPtr(fresh.publics.get(i).data[0]);
			int cmd = (target == null) ? -1 : target.getCommand();
			if (cmd != prePublics[i]) {
				return "public " + i + " target command changed: was " + prePublics[i] + ", now " + cmd;
			}
			if (prePublics[i] == OP_PROC && cmd != OP_PROC) {
				return "public " + i + " no longer points at a PROC";
			}
		}
		return null;
	}

	/**
	 * Extracts subfile 2 (map script) of ZoneData entry index and parses it.
	 * Returns null when the entry is not a ZO container (magic 0x5A4F,
	 * big-endian on disk - matches AbstractGamefreakContainer.read2Bytes).
	 */
	private static GFLPawnScript getZoneScript(GARC garc, int index) {
		byte[] zo = garc.getDecompressedEntry(index);
		if (zo == null || zo.length < 4) {
			return null;
		}
		int magic = ((zo[0] & 0xFF) << 8) | (zo[1] & 0xFF);
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
}

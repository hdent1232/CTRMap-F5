package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.MsgWrapperInjector;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.PawnPrefixEntry;
import ctrmap.formats.scripts.TalkerScriptWizard;
import ctrmap.formats.scripts.ZoneScriptAnalyzer;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Headless acceptance harness for MsgWrapperInjector over the PRISTINE ORAS
 * ZoneData GARC (read-only backup, nothing is written). Run with:
 * java -cp build/classes ctrmap.tests.MsgWrapperInjectTest
 *
 * For EVERY ZO zone without a message wrapper (the census must be exactly
 * 308 wrapper-less / 228 wrapper zones), the injection is executed in
 * memory, reserialized, re-parsed and verified byte-for-byte: all old code
 * cells bit-identical (shifted by the 11-cell stub when one was inserted),
 * the stub recognized at 0x4 with the yield native, old data cells
 * untouched with exactly the 30 donor buffer cells appended, natives
 * append-only with the old entries byte-identical and no duplicated hashes,
 * publics/mainEntryPoint shifted consistently and still landing on PROCs,
 * headroom preserved, every injected CALL resolving in-block or to 0x4 and
 * the dispatch untouched. An independent DFS walk of the injected closure
 * must match the donor closure positionally: every SYSREQ_N site resolves to
 * the same native name hash + argBytes, every CALL to the same sub ordinal,
 * and the 8 buffer-address rewrites hold exactly base+delta. On top of the injected script the full talker
 * clone is executed and verified with all TalkerWizardDryRunTest assertions,
 * and the new talker must call the injected wrapper. Wrapper-carrying zones
 * must be refused byte-untouched (double-inject guard). Zero failures
 * allowed.
 */
public class MsgWrapperInjectTest {

	private static final String DEFAULT_GARC_PATH = "C:\\Users\\flami\\Desktop\\Claude\\sessions\\3DS Editor\\RomFS_original_garcs\\a\\0\\1\\3";
	private static final int ZONE_COUNT = 536;
	private static final int EXPECTED_WRAPPER_ZONES = 228;
	private static final int EXPECTED_WRAPPERLESS_ZONES = 308;
	private static final int FAKE_LINE = 1234;
	private static final int OP_PROC = PawnInstruction.Commands.PROC.ordinal();
	private static final int OP_CALL = PawnInstruction.Commands.CALL.ordinal();
	private static final int OP_SYSREQ_N = PawnInstruction.Commands.SYSREQ_N.ordinal();

	/**
	 * The 8 buffer-address rewrite sites: {DFS sub ordinal, byte offset in
	 * the sub, byte delta from the buffer base} (measured, matches
	 * MsgWrapperInjector.REWRITE_SITES).
	 */
	private static final int[][] SITES = {
		{2, 0x130, 0}, {2, 0x14C, 0}, {2, 0x178, 12}, {2, 0x194, 12},
		{2, 0x234, 24}, {2, 0x290, 48}, {11, 0xB0, 72}, {11, 0x10C, 96}
	};

	/**
	 * Independent closure model of the donor's message routine, built once in
	 * main() and compared per-site against every injected result.
	 */
	private static Closure donorClosure;
	private static int donorSiteBase;

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0] : DEFAULT_GARC_PATH);
		if (!garcFile.exists()) {
			System.out.println("FAIL: pristine ZoneData GARC not found: " + garcFile.getAbsolutePath());
			System.exit(1);
		}
		GARC garc = new GARC(garcFile);

		//census
		List<Integer> withWrapper = new ArrayList<>();
		List<Integer> withoutWrapper = new ArrayList<>();
		int zoZones = 0;
		for (int i = 0; i < ZONE_COUNT; i++) {
			GFLPawnScript s = getZoneScript(garc, i);
			if (s == null) {
				continue;
			}
			zoZones++;
			if (ZoneScriptAnalyzer.findMsgWrapper(s) != null) {
				withWrapper.add(i);
			} else {
				withoutWrapper.add(i);
			}
		}
		System.out.println("census: " + zoZones + "/" + ZONE_COUNT + " ZO zones, " + withWrapper.size() + " with wrapper, " + withoutWrapper.size() + " without");
		if (zoZones != ZONE_COUNT || withWrapper.size() != EXPECTED_WRAPPER_ZONES || withoutWrapper.size() != EXPECTED_WRAPPERLESS_ZONES) {
			System.out.println("FAIL: census does not match the pristine ORAS ZoneData (expected " + EXPECTED_WRAPPER_ZONES + "/" + EXPECTED_WRAPPERLESS_ZONES + ")");
			System.exit(1);
		}

		//donor: the canonical zone, validated; pickDonor must agree
		GFLPawnScript donor = getZoneScript(garc, MsgWrapperInjector.PREFERRED_DONOR_ZONE);
		try {
			MsgWrapperInjector.validateDonor(donor);
		} catch (RuntimeException ex) {
			System.out.println("FAIL: zone " + MsgWrapperInjector.PREFERRED_DONOR_ZONE + " does not validate as donor: " + ex.getMessage());
			System.exit(1);
		}
		try {
			MsgWrapperInjector.pickDonor(new MsgWrapperInjector.ScriptSource() {
				@Override
				public GFLPawnScript get(int zoneIndex) {
					return getZoneScript(garc, zoneIndex);
				}
			}, ZONE_COUNT);
		} catch (RuntimeException ex) {
			System.out.println("FAIL: pickDonor found no donor: " + ex.getMessage());
			System.exit(1);
		}
		int[] donorBuf = MsgWrapperInjector.getDonorBufferCells(donor);
		if (donorBuf.length != MsgWrapperInjector.BUFFER_CELLS) {
			System.out.println("FAIL: donor buffer is " + donorBuf.length + " cells");
			System.exit(1);
		}
		//independent DFS walk of the donor closure for per-site comparison
		try {
			PawnInstruction donorW = ZoneScriptAnalyzer.findMsgWrapper(donor);
			donorClosure = closureAt(donor, donorW.pointer);
			donorSiteBase = donorClosure.siteBase;
		} catch (RuntimeException ex) {
			System.out.println("FAIL: donor closure walk failed: " + ex.getMessage());
			System.exit(1);
		}

		//injection acceptance over every wrapper-less zone
		int eligible = withoutWrapper.size();
		int passed = 0;
		List<String> failures = new ArrayList<>();
		for (int z : withoutWrapper) {
			try {
				String err = injectAndVerify(garc, z, donor, donorBuf);
				if (err == null) {
					passed++;
				} else {
					failures.add("zone " + z + ": " + err);
				}
			} catch (Exception ex) {
				failures.add("zone " + z + ": EXCEPTION " + ex);
				if (failures.size() <= 3) {
					ex.printStackTrace(System.out);
				}
			}
		}

		//refusal acceptance over every wrapper zone: typed refusal, byte-untouched
		int refusalsPassed = 0;
		for (int z : withWrapper) {
			try {
				GFLPawnScript s = getZoneScript(garc, z);
				byte[] before = s.getScriptBytes();
				boolean refused = false;
				try {
					MsgWrapperInjector.injectMsgWrapper(s, donor);
				} catch (MsgWrapperInjector.InjectionException ex) {
					refused = true;
				}
				if (!refused) {
					failures.add("wrapper zone " + z + ": injection was not refused");
					continue;
				}
				byte[] after = s.getScriptBytes();
				if (!Arrays.equals(before, after)) {
					failures.add("wrapper zone " + z + ": refusal modified the script");
					continue;
				}
				refusalsPassed++;
			} catch (Exception ex) {
				failures.add("wrapper zone " + z + ": EXCEPTION " + ex);
			}
		}

		for (String f : failures) {
			System.out.println("FAILURE: " + f);
		}
		System.out.println("inject: " + eligible + " zones eligible (dispatch, no wrapper)");
		System.out.println("inject: " + passed + "/" + eligible + " zones injected, cloned and verified");
		System.out.println("inject: per-site native identity (hash + argBytes vs donor) verified for every injected closure");
		System.out.println("refuse: " + refusalsPassed + "/" + withWrapper.size() + " wrapper zones refused byte-untouched");
		if (failures.isEmpty() && passed == eligible && refusalsPassed == withWrapper.size()) {
			System.out.println("PASS");
		} else {
			System.out.println("FAIL");
			System.exit(1);
		}
	}

	/**
	 * Injects into one wrapper-less zone, verifies the reserialized result
	 * byte-for-byte, then runs the full talker clone on top and verifies it
	 * with the TalkerWizardDryRunTest assertions. Returns null on success.
	 */
	private static String injectAndVerify(GARC garc, int z, GFLPawnScript donor, int[] donorBuf) {
		GFLPawnScript t = getZoneScript(garc, z);
		boolean needStub = !MsgWrapperInjector.hasStubAt4(t);

		//pre-injection snapshots
		int preCodeCells = (t.dataStart - t.instructionStart) / 4;
		int[] preAll = t.decInstructions.clone();
		int preNatives = t.natives.size();
		List<int[]> preNativeData = clonePrefixData(t.natives);
		List<int[]> prePublicData = clonePrefixData(t.publics);
		List<int[]> prePublicVars = clonePrefixData(t.publicVars);
		List<int[]> preLibraries = clonePrefixData(t.libraries);
		List<int[]> preTags = clonePrefixData(t.tags);
		byte[] preRest = t.rest.clone();
		int preMain = t.mainEntryPoint;
		int preHeadroom = t.allocatedMem - t.heapStart;
		int preDataCells = t.data.size();
		ZoneScriptAnalyzer.Dispatch preD = ZoneScriptAnalyzer.findDispatch(t);
		if (preD == null) {
			return "no dispatch";
		}
		Map<Integer, Integer> preCaseCmds = new LinkedHashMap<>();
		for (Map.Entry<Integer, PawnInstruction> e : preD.cases.entrySet()) {
			preCaseCmds.put(e.getKey(), e.getValue() == null ? -1 : e.getValue().getCommand());
		}

		int wrapperPtr;
		try {
			wrapperPtr = MsgWrapperInjector.injectMsgWrapper(t, donor);
		} catch (MsgWrapperInjector.InjectionException ex) {
			return "refused: " + ex.getMessage();
		}

		PawnInstruction w = ZoneScriptAnalyzer.findMsgWrapper(t);
		if (w == null || w.pointer != wrapperPtr) {
			return "in-memory wrapper not at returned address";
		}

		//reserialize and re-parse from scratch
		byte[] bytes = t.getScriptBytes();
		if (bytes == null) {
			return "getScriptBytes() returned null";
		}
		GFLPawnScript fresh = new GFLPawnScript(bytes);
		fresh.decompressThis();

		int stubShiftCells = needStub ? 11 : 0;
		if (fresh.decInstructions[0] != preAll[0]) {
			return "cell 0 changed";
		}
		if (needStub) {
			if (!MsgWrapperInjector.hasStubAt4(fresh)) {
				return "stub not recognized at 4 after injection";
			}
			int stubNativeIdx = fresh.instructions.get(3).argumentCells[0];
			if (fresh.natives.get(stubNativeIdx).data[1] != MsgWrapperInjector.YIELD_NATIVE_HASH) {
				return "stub native hash wrong";
			}
		}
		//old code cells bit-identical (shifted by the stub for cells >= 1)
		for (int i = 1; i < preCodeCells; i++) {
			if (fresh.decInstructions[i + stubShiftCells] != preAll[i]) {
				return "old code cell " + i + " changed (shift " + stubShiftCells + ")";
			}
		}
		//data: old cells preserved at the same DAT index, buffer appended
		int freshCodeCells = (fresh.dataStart - fresh.instructionStart) / 4;
		if (fresh.data.size() != preDataCells + donorBuf.length) {
			return "data size " + fresh.data.size() + ", expected " + (preDataCells + donorBuf.length);
		}
		for (int i = 0; i < preDataCells; i++) {
			if (fresh.decInstructions[freshCodeCells + i] != preAll[preCodeCells + i]) {
				return "old data cell " + i + " changed";
			}
		}
		for (int i = 0; i < donorBuf.length; i++) {
			if (fresh.decInstructions[freshCodeCells + preDataCells + i] != donorBuf[i]) {
				return "appended buffer cell " + i + " mismatch";
			}
		}
		//natives: append-only, old entries byte-identical, appended entries
		//well-formed {0, hash} with hashes not duplicating any other entry
		if (fresh.natives.size() < preNatives) {
			return "natives shrank";
		}
		for (int i = 0; i < preNatives; i++) {
			if (!Arrays.equals(fresh.natives.get(i).data, preNativeData.get(i))) {
				return "native " + i + " changed";
			}
		}
		for (int i = preNatives; i < fresh.natives.size(); i++) {
			int[] data = fresh.natives.get(i).data;
			if (data.length != 2 || data[0] != 0) {
				return "appended native " + i + " malformed";
			}
			for (int j = 0; j < fresh.natives.size(); j++) {
				if (j != i && fresh.natives.get(j).data[1] == data[1]) {
					return "appended native " + i + " duplicates hash 0x" + Integer.toHexString(data[1]);
				}
			}
		}
		//publics: same count and hashes, addresses shifted by the stub only,
		//each still landing on a PROC
		if (fresh.publics.size() != prePublicData.size()) {
			return "publics count changed";
		}
		for (int i = 0; i < prePublicData.size(); i++) {
			if (fresh.publics.get(i).data[0] != prePublicData.get(i)[0] + stubShiftCells * 4) {
				return "public " + i + " address not shifted consistently";
			}
			if (fresh.publics.get(i).data[1] != prePublicData.get(i)[1]) {
				return "public " + i + " hash changed";
			}
			PawnInstruction pt = fresh.lookupInstructionByPtr(fresh.publics.get(i).data[0]);
			if (pt == null || pt.getCommand() != OP_PROC) {
				return "public " + i + " no longer lands on a PROC";
			}
		}
		//other prefix tables and the rest blob byte-identical
		String prefixErr = comparePrefixData(fresh.publicVars, prePublicVars, "publicVar");
		if (prefixErr == null) {
			prefixErr = comparePrefixData(fresh.libraries, preLibraries, "library");
		}
		if (prefixErr == null) {
			prefixErr = comparePrefixData(fresh.tags, preTags, "tag");
		}
		if (prefixErr != null) {
			return prefixErr;
		}
		if (!Arrays.equals(fresh.rest, preRest)) {
			return "rest blob changed";
		}
		if (fresh.mainEntryPoint != preMain + stubShiftCells * 4) {
			return "mainEntryPoint not shifted consistently";
		}
		if (fresh.allocatedMem - fresh.heapStart != preHeadroom) {
			return "VM heap/stack headroom changed";
		}
		//the injected wrapper is found at the predicted address
		PawnInstruction freshW = ZoneScriptAnalyzer.findMsgWrapper(fresh);
		if (freshW == null || freshW.pointer != wrapperPtr) {
			return "reserialized wrapper not at predicted address";
		}
		//per-site native identity: an independent DFS walk of the injected
		//closure, comparing every instruction positionally against the donor
		//(SYSREQ_N by native name hash + argBytes, CALL by sub ordinal, the 8
		//buffer rewrites by delta from each closure's own base, everything
		//else by raw cell equality) - closes the swapped-natives blind spot
		Closure fc;
		try {
			fc = closureAt(fresh, wrapperPtr);
		} catch (RuntimeException ex) {
			return "injected closure walk failed: " + ex.getMessage();
		}
		int freshBufBase = preDataCells * 4;
		String closureErr = compareClosures(donorClosure, donor, fc, fresh, donorSiteBase, freshBufBase);
		if (closureErr != null) {
			return "injected closure differs from donor: " + closureErr;
		}
		//injected block CALLs: internal or the stub at 0x4, always onto a PROC
		int blockBase = (preCodeCells + stubShiftCells) * 4;
		for (PawnInstruction ins : fresh.instructions) {
			if (ins.pointer >= blockBase && ins.getCommand() == OP_CALL) {
				int target = ins.pointer + ins.argumentCells[0];
				if (target != 4 && target < blockBase) {
					return "block CALL leaves the block to 0x" + Integer.toHexString(target);
				}
				PawnInstruction ti = fresh.lookupInstructionByPtr(target);
				if (ti == null || ti.getCommand() != OP_PROC) {
					return "block CALL target invalid";
				}
			}
		}
		//dispatch untouched (same keys, same target commands)
		ZoneScriptAnalyzer.Dispatch pd = ZoneScriptAnalyzer.findDispatch(fresh);
		if (pd == null || pd.cases.size() != preCaseCmds.size()) {
			return "dispatch changed";
		}
		for (Map.Entry<Integer, Integer> e : preCaseCmds.entrySet()) {
			PawnInstruction ti = pd.cases.get(e.getKey());
			int cmd = ti == null ? -1 : ti.getCommand();
			if (cmd != e.getValue()) {
				return "dispatch case " + e.getKey() + " changed";
			}
		}
		//double-inject guard: the injected script must now be refused
		boolean refused = false;
		try {
			MsgWrapperInjector.injectMsgWrapper(fresh, donor);
		} catch (MsgWrapperInjector.InjectionException ex) {
			refused = true;
		}
		if (!refused) {
			return "double injection was not refused";
		}

		return cloneAndVerify(bytes, wrapperPtr);
	}

	/**
	 * The full talker clone on top of the injected script, verified with the
	 * TalkerWizardDryRunTest assertions plus the injected-wrapper identity.
	 */
	private static String cloneAndVerify(byte[] injectedBytes, int wrapperPtr) {
		GFLPawnScript s2 = new GFLPawnScript(injectedBytes);
		s2.decompressThis();
		//pre-clone state: every dispatch case key -> [target command, isTalker, msgLine]
		ZoneScriptAnalyzer.Dispatch pre = ZoneScriptAnalyzer.findDispatch(s2);
		if (pre == null) {
			return "pre-clone dispatch missing";
		}
		Map<Integer, int[]> preCases = new LinkedHashMap<>();
		for (Map.Entry<Integer, PawnInstruction> e : pre.cases.entrySet()) {
			int cmd = (e.getValue() == null) ? -1 : e.getValue().getCommand();
			ZoneScriptAnalyzer.TalkerPattern tp = ZoneScriptAnalyzer.findTalkerPattern(s2, e.getKey());
			preCases.put(e.getKey(), new int[]{cmd, tp == null ? 0 : 1, tp == null ? -1 : tp.msgLine});
		}
		int[] prePublics = new int[s2.publics.size()];
		for (int i = 0; i < prePublics.length; i++) {
			PawnInstruction target = s2.lookupInstructionByPtr(s2.publics.get(i).data[0]);
			prePublics[i] = (target == null) ? -1 : target.getCommand();
		}
		PawnInstruction preMain = s2.lookupInstructionByPtr(s2.mainEntryPoint);
		int preMainCmd = (preMain == null) ? -1 : preMain.getCommand();
		int preHeadroom = s2.allocatedMem - s2.heapStart;
		PawnInstruction preW = ZoneScriptAnalyzer.findMsgWrapper(s2);
		if (preW == null || preW.pointer != wrapperPtr) {
			return "pre-clone wrapper not at injected address";
		}

		int newId;
		try {
			newId = TalkerScriptWizard.cloneTalker(s2, FAKE_LINE);
		} catch (RuntimeException ex) {
			return "cloneTalker failed on injected script: " + ex.getMessage();
		}

		byte[] bytes2 = s2.getScriptBytes();
		if (bytes2 == null) {
			return "post-clone getScriptBytes() returned null";
		}
		GFLPawnScript s3 = new GFLPawnScript(bytes2);
		s3.decompressThis();

		if (s3.allocatedMem - s3.heapStart != preHeadroom) {
			return "post-clone headroom changed";
		}
		PawnInstruction postMain = s3.lookupInstructionByPtr(s3.mainEntryPoint);
		if (postMain == null || postMain.getCommand() != preMainCmd) {
			return "post-clone main entry invalid";
		}
		ZoneScriptAnalyzer.Dispatch post = ZoneScriptAnalyzer.findDispatch(s3);
		if (post == null) {
			return "post-clone dispatch missing";
		}
		if (!post.cases.containsKey(newId)) {
			return "new case key " + newId + " missing from CASETBL";
		}
		if (post.cases.size() != preCases.size() + 1) {
			return "post-clone case count " + post.cases.size() + ", expected " + (preCases.size() + 1);
		}
		ZoneScriptAnalyzer.TalkerPattern ntp = ZoneScriptAnalyzer.findTalkerPattern(s3, newId);
		if (ntp == null) {
			return "new case key " + newId + " does not resolve to a simple talker";
		}
		if (ntp.msgLine != FAKE_LINE) {
			return "new talker msgLine == " + ntp.msgLine + ", expected " + FAKE_LINE;
		}
		PawnInstruction postW = ZoneScriptAnalyzer.findMsgWrapper(s3);
		if (postW == null || ntp.wrapperEntry == null || ntp.wrapperEntry.pointer != postW.pointer) {
			return "new talker does not call the injected wrapper";
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
				return "case " + key + " target command changed after clone";
			}
			ZoneScriptAnalyzer.TalkerPattern tp = ZoneScriptAnalyzer.findTalkerPattern(s3, key);
			if ((tp != null ? 1 : 0) != info[1]) {
				return "case " + key + " talker-ness changed after clone";
			}
			if (tp != null && tp.msgLine != info[2]) {
				return "case " + key + " talker msgLine changed after clone";
			}
		}
		if (s3.publics.size() != prePublics.length) {
			return "post-clone publics count changed";
		}
		for (int i = 0; i < prePublics.length; i++) {
			PawnInstruction target = s3.lookupInstructionByPtr(s3.publics.get(i).data[0]);
			int cmd = (target == null) ? -1 : target.getCommand();
			if (cmd != prePublics[i]) {
				return "post-clone public " + i + " target command changed";
			}
		}
		if (!MsgWrapperInjector.hasStubAt4(s3)) {
			return "stub at 0x4 lost after cloneTalker";
		}
		return null;
	}

	//============ independent closure model (per-site donor comparison) ============
	private static class Sub {

		int entryPtr;
		int startIdx;
		int endIdx;
	}

	private static class Closure {

		final List<Sub> subs = new ArrayList<>();
		int siteBase; //value at the first rewrite site minus its delta
	}

	/**
	 * DFS preorder walk of the CALL closure rooted at the given PROC,
	 * independent of MsgWrapperInjector's own model.
	 */
	private static Closure closureAt(GFLPawnScript s, int wrapperPtr) {
		Closure c = new Closure();
		dfs(s, wrapperPtr, c, new HashSet<Integer>());
		Sub siteSub = c.subs.get(SITES[0][0]);
		PawnInstruction first = s.lookupInstructionByPtr(siteSub.entryPtr + SITES[0][1]);
		c.siteBase = first.argumentCells[0] - SITES[0][2];
		return c;
	}

	private static void dfs(GFLPawnScript s, int entryPtr, Closure c, Set<Integer> seen) {
		if (!seen.add(entryPtr)) {
			return;
		}
		PawnInstruction entry = s.lookupInstructionByPtr(entryPtr);
		if (entry == null || entry.getCommand() != OP_PROC) {
			throw new IllegalStateException("bad closure entry 0x" + Integer.toHexString(entryPtr));
		}
		Sub sub = new Sub();
		sub.entryPtr = entryPtr;
		sub.startIdx = s.instructions.indexOf(entry);
		int end = sub.startIdx + 1;
		while (end < s.instructions.size() && s.instructions.get(end).getCommand() != OP_PROC) {
			end++;
		}
		sub.endIdx = end;
		c.subs.add(sub);
		for (int i = sub.startIdx; i < end; i++) {
			PawnInstruction ins = s.instructions.get(i);
			if (ins.getCommand() == OP_CALL && ins.argumentCells.length == 1) {
				dfs(s, ins.pointer + ins.argumentCells[0], c, seen);
			}
		}
	}

	/**
	 * Compares two closures for semantic equivalence: same DFS shape, same
	 * opcodes at the same offsets, SYSREQ_N by native name hash + argBytes
	 * (positional correspondence), CALL by sub ordinal, the 8 rewrite sites
	 * by delta from each closure's own buffer base, everything else by raw
	 * cell equality. Returns null when equivalent.
	 */
	private static String compareClosures(Closure a, GFLPawnScript sa, Closure b, GFLPawnScript sb, int baseA, int baseB) {
		if (a.subs.size() != b.subs.size()) {
			return "sub count " + b.subs.size() + " vs " + a.subs.size();
		}
		Map<Integer, Integer> aOrd = new HashMap<>();
		Map<Integer, Integer> bOrd = new HashMap<>();
		for (int i = 0; i < a.subs.size(); i++) {
			aOrd.put(a.subs.get(i).entryPtr, i);
			bOrd.put(b.subs.get(i).entryPtr, i);
		}
		Map<String, Integer> siteDelta = new HashMap<>();
		for (int[] site : SITES) {
			siteDelta.put(site[0] + ":" + site[1], site[2]);
		}
		for (int i = 0; i < a.subs.size(); i++) {
			Sub subA = a.subs.get(i);
			Sub subB = b.subs.get(i);
			if (subA.endIdx - subA.startIdx != subB.endIdx - subB.startIdx) {
				return "sub " + i + " instruction count differs";
			}
			for (int k = 0; k < subA.endIdx - subA.startIdx; k++) {
				PawnInstruction ia = sa.instructions.get(subA.startIdx + k);
				PawnInstruction ib = sb.instructions.get(subB.startIdx + k);
				int offA = ia.pointer - subA.entryPtr;
				int offB = ib.pointer - subB.entryPtr;
				if (offA != offB) {
					return "sub " + i + " ins " + k + " offset differs (0x" + Integer.toHexString(offA) + " vs 0x" + Integer.toHexString(offB) + ")";
				}
				if (ia.getCommand() != ib.getCommand()) {
					return "sub " + i + "+0x" + Integer.toHexString(offA) + " opcode " + ib.getCommand() + " vs " + ia.getCommand();
				}
				int cmd = ia.getCommand();
				Integer delta = siteDelta.get(i + ":" + offA);
				if (cmd == OP_SYSREQ_N) {
					int idxA = ia.argumentCells[0];
					int idxB = ib.argumentCells[0];
					if (idxB < 0 || idxB >= sb.natives.size()) {
						return "sub " + i + "+0x" + Integer.toHexString(offA) + " SYSREQ_N index " + idxB + " out of range";
					}
					int ha = sa.natives.get(idxA).data[1];
					int hb = sb.natives.get(idxB).data[1];
					if (ha != hb) {
						return "sub " + i + "+0x" + Integer.toHexString(offA) + " SYSREQ_N hash 0x" + Integer.toHexString(hb) + " vs 0x" + Integer.toHexString(ha);
					}
					if (ia.argumentCells[1] != ib.argumentCells[1]) {
						return "sub " + i + "+0x" + Integer.toHexString(offA) + " SYSREQ_N argBytes differ";
					}
				} else if (cmd == OP_CALL) {
					Integer oa = aOrd.get(ia.pointer + ia.argumentCells[0]);
					Integer ob = bOrd.get(ib.pointer + ib.argumentCells[0]);
					if (oa == null || ob == null || !oa.equals(ob)) {
						return "sub " + i + "+0x" + Integer.toHexString(offA) + " CALL ordinal " + ob + " vs " + oa;
					}
				} else if (delta != null) {
					if (ia.argumentCells[0] != baseA + delta) {
						return "site " + i + "+0x" + Integer.toHexString(offA) + " donor value " + ia.argumentCells[0] + " != base+" + delta;
					}
					if (ib.argumentCells[0] != baseB + delta) {
						return "site " + i + "+0x" + Integer.toHexString(offA) + " injected value " + ib.argumentCells[0] + " != base+" + delta;
					}
					if ((ia.cellValue & 0xFFFF) != (ib.cellValue & 0xFFFF)) {
						return "site " + i + "+0x" + Integer.toHexString(offA) + " opcode halves differ";
					}
				} else {
					if (!Arrays.equals(ia.getRaw(), ib.getRaw())) {
						return "sub " + i + "+0x" + Integer.toHexString(offA) + " raw cells differ";
					}
				}
			}
		}
		return null;
	}

	private static List<int[]> clonePrefixData(List<PawnPrefixEntry> entries) {
		List<int[]> ret = new ArrayList<>();
		for (PawnPrefixEntry e : entries) {
			ret.add(e.data.clone());
		}
		return ret;
	}

	private static String comparePrefixData(List<PawnPrefixEntry> entries, List<int[]> expected, String what) {
		if (entries.size() != expected.size()) {
			return what + " table size changed";
		}
		for (int i = 0; i < expected.size(); i++) {
			if (!Arrays.equals(entries.get(i).data, expected.get(i))) {
				return what + " " + i + " changed";
			}
		}
		return null;
	}

	/**
	 * Extracts subfile 2 (map script) of ZoneData entry index and parses it;
	 * null when the entry is not a ZO container.
	 */
	private static GFLPawnScript getZoneScript(GARC garc, int index) {
		return MsgWrapperInjector.extractZoneScript(garc.getDecompressedEntry(index));
	}
}

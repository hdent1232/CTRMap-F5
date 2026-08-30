package ctrmap.tests;

import ctrmap.formats.scripts.*;

import ctrmap.formats.garc.GARC;
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
 * Corpus acceptance harness for the SignWrapperInjector prototype over the
 * PRISTINE ORAS ZoneData GARC (read-only backup, nothing is written).
 * Mirrors MsgWrapperInjectTest:
 *
 * For EVERY ZO zone without a sign wrapper (census must be exactly 69 sign /
 * 467 sign-less), the injection is executed in memory, reserialized,
 * re-parsed and verified byte-for-byte: all old code cells bit-identical
 * (shifted by the 11-cell stub when one was inserted), the stub recognized
 * at 0x4 with the yield native, the DATA SEGMENT BYTE-IDENTICAL WITH NOTHING
 * APPENDED (the sign closure has no data references), natives append-only
 * with the old entries byte-identical and no duplicated hashes,
 * publics/mainEntryPoint shifted consistently and still landing on PROCs,
 * headroom preserved, every injected CALL resolving in-block or to 0x4 and
 * the dispatch untouched. An independent DFS walk of the injected closure
 * must match the donor closure positionally (SYSREQ_N by native name hash +
 * argBytes, CALL by sub ordinal, everything else raw-equal - no rewrite-site
 * cases exist). On top of the injected script NpcTemplates.addSignScript is
 * executed and verified (new case resolves via findSignPattern and calls the
 * injected wrapper; pre-existing cases/publics/stub unchanged). Sign-carrying
 * zones must be refused byte-untouched. Additionally, for every zone lacking
 * BOTH wrappers, msg-then-sign and sign-then-msg composition is executed and
 * both routines plus both editor entry points verified. Zero failures
 * allowed.
 */
public class SignWrapperInjectTest {

	private static final String DEFAULT_GARC_PATH = "../RomFS_original_garcs/a/0/1/3";
	private static final int ZONE_COUNT = 536;
	private static final int EXPECTED_SIGN_ZONES = 69;
	private static final int EXPECTED_SIGNLESS_ZONES = 467;
	private static final int EXPECTED_DUAL_LESS_ZONES = 289;
	private static final int FAKE_LINE = 0;
	private static final int FAKE_SIGN_TYPE = 6;
	private static final int FAKE_TALKER_LINE = 1234;
	private static final int OP_PROC = PawnInstruction.Commands.PROC.ordinal();
	private static final int OP_CALL = PawnInstruction.Commands.CALL.ordinal();
	private static final int OP_SYSREQ_N = PawnInstruction.Commands.SYSREQ_N.ordinal();

	private static Closure donorClosure;

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0] : DEFAULT_GARC_PATH);
		if (!garcFile.exists()) {
			System.out.println("FAIL: pristine ZoneData GARC not found: " + garcFile.getAbsolutePath());
			System.exit(1);
		}
		GARC garc = new GARC(garcFile);

		//census
		List<Integer> withSign = new ArrayList<>();
		List<Integer> withoutSign = new ArrayList<>();
		List<Integer> withoutBoth = new ArrayList<>();
		int zoZones = 0;
		int signlessWithDispatch = 0;
		for (int i = 0; i < ZONE_COUNT; i++) {
			GFLPawnScript s = getZoneScript(garc, i);
			if (s == null) {
				continue;
			}
			zoZones++;
			if (ZoneScriptAnalyzer.findSignWrapper(s) != null) {
				withSign.add(i);
			} else {
				withoutSign.add(i);
				if (ZoneScriptAnalyzer.findDispatch(s) != null) {
					signlessWithDispatch++;
				}
				if (ZoneScriptAnalyzer.findMsgWrapper(s) == null) {
					withoutBoth.add(i);
				}
			}
		}
		System.out.println("census: " + zoZones + "/" + ZONE_COUNT + " ZO zones, " + withSign.size() + " with sign wrapper, "
				+ withoutSign.size() + " without (" + signlessWithDispatch + " with dispatch), " + withoutBoth.size() + " lacking both wrappers");
		if (zoZones != ZONE_COUNT || withSign.size() != EXPECTED_SIGN_ZONES || withoutSign.size() != EXPECTED_SIGNLESS_ZONES
				|| signlessWithDispatch != EXPECTED_SIGNLESS_ZONES || withoutBoth.size() != EXPECTED_DUAL_LESS_ZONES) {
			System.out.println("FAIL: census does not match the pristine ORAS ZoneData (expected " + EXPECTED_SIGN_ZONES + "/" + EXPECTED_SIGNLESS_ZONES
					+ ", all sign-less with dispatch, " + EXPECTED_DUAL_LESS_ZONES + " dual-less)");
			System.exit(1);
		}

		//donor: the canonical zone, validated; pickDonor must agree
		GFLPawnScript donor = getZoneScript(garc, SignWrapperInjector.PREFERRED_DONOR_ZONE);
		try {
			SignWrapperInjector.validateDonor(donor);
		} catch (RuntimeException ex) {
			System.out.println("FAIL: zone " + SignWrapperInjector.PREFERRED_DONOR_ZONE + " does not validate as sign donor: " + ex.getMessage());
			System.exit(1);
		}
		//every one of the other 68 sign zones must also validate (donor-fallback identity)
		int donorAlts = 0;
		List<String> failures = new ArrayList<>();
		for (int z : withSign) {
			if (z == SignWrapperInjector.PREFERRED_DONOR_ZONE) {
				continue;
			}
			try {
				SignWrapperInjector.validateDonor(getZoneScript(garc, z));
				donorAlts++;
			} catch (RuntimeException ex) {
				failures.add("sign zone " + z + " does not validate as fallback donor: " + ex.getMessage());
			}
		}
		try {
			SignWrapperInjector.pickDonor(new MsgWrapperInjector.ScriptSource() {
				@Override
				public GFLPawnScript get(int zoneIndex) {
					return getZoneScript(garc, zoneIndex);
				}
			}, ZONE_COUNT);
		} catch (RuntimeException ex) {
			System.out.println("FAIL: pickDonor found no donor: " + ex.getMessage());
			System.exit(1);
		}
		//independent DFS walk of the donor closure for positional comparison
		try {
			PawnInstruction donorW = ZoneScriptAnalyzer.findSignWrapper(donor);
			donorClosure = closureAt(donor, donorW.pointer);
		} catch (RuntimeException ex) {
			System.out.println("FAIL: donor closure walk failed: " + ex.getMessage());
			System.exit(1);
		}
		//also validate the shared msg donor for the composition pass
		try {
			MsgWrapperInjector.validateDonor(donor);
		} catch (RuntimeException ex) {
			System.out.println("FAIL: zone 24 no longer validates as msg donor: " + ex.getMessage());
			System.exit(1);
		}
		int[] donorBuf = MsgWrapperInjector.getDonorBufferCells(donor);

		//injection acceptance over every sign-less zone
		int eligible = withoutSign.size();
		int injectVerified = 0;
		int signScriptPassed = 0;
		for (int z : withoutSign) {
			try {
				String[] result = injectAndVerify(garc, z, donor);
				if (result[0] == null) {
					injectVerified++;
				} else {
					failures.add("zone " + z + " [inject]: " + result[0]);
				}
				if (result[1] == null) {
					signScriptPassed++;
				} else {
					failures.add("zone " + z + " [addSignScript]: " + result[1]);
				}
			} catch (Exception ex) {
				failures.add("zone " + z + ": EXCEPTION " + ex);
				if (failures.size() <= 3) {
					ex.printStackTrace(System.out);
				}
			}
		}

		//refusal acceptance over every sign zone: typed refusal, byte-untouched
		int refusalsPassed = 0;
		for (int z : withSign) {
			try {
				GFLPawnScript s = getZoneScript(garc, z);
				byte[] before = s.getScriptBytes();
				boolean refused = false;
				try {
					SignWrapperInjector.injectSignWrapper(s, donor);
				} catch (SignWrapperInjector.InjectionException ex) {
					refused = true;
				}
				if (!refused) {
					failures.add("sign zone " + z + ": injection was not refused");
					continue;
				}
				byte[] after = s.getScriptBytes();
				if (!Arrays.equals(before, after)) {
					failures.add("sign zone " + z + ": refusal modified the script");
					continue;
				}
				refusalsPassed++;
			} catch (Exception ex) {
				failures.add("sign zone " + z + ": EXCEPTION " + ex);
			}
		}

		//composition acceptance over every zone lacking both wrappers:
		//msg-then-sign and sign-then-msg must both work end-to-end
		int composedMsgFirst = 0;
		int composedSignFirst = 0;
		for (int z : withoutBoth) {
			try {
				String err = composeAndVerify(garc, z, donor, true);
				if (err == null) {
					composedMsgFirst++;
				} else {
					failures.add("zone " + z + " [msg-then-sign]: " + err);
				}
				err = composeAndVerify(garc, z, donor, false);
				if (err == null) {
					composedSignFirst++;
				} else {
					failures.add("zone " + z + " [sign-then-msg]: " + err);
				}
			} catch (Exception ex) {
				failures.add("zone " + z + " [compose]: EXCEPTION " + ex);
			}
		}

		for (String f : failures) {
			System.out.println("FAILURE: " + f);
		}
		System.out.println("donor: zone 24 validated; " + donorAlts + "/" + (withSign.size() - 1) + " other sign zones validate as fallback donors");
		System.out.println("inject: " + eligible + " zones eligible (dispatch, no sign wrapper)");
		System.out.println("inject: " + injectVerified + "/" + eligible + " zones injected + reserialize/reparse/byte-level verified (data segment untouched in every one)");
		System.out.println("inject: per-site native identity (hash + argBytes vs donor) verified for every injected closure");
		System.out.println("editor: " + signScriptPassed + "/" + eligible + " zones passed NpcTemplates.addSignScript(0, 6) on top of the injection");
		System.out.println("refuse: " + refusalsPassed + "/" + withSign.size() + " sign zones refused byte-untouched");
		System.out.println("compose: " + composedMsgFirst + "/" + withoutBoth.size() + " dual-less zones passed msg-then-sign (cloneTalker + addSignScript both verified)");
		System.out.println("compose: " + composedSignFirst + "/" + withoutBoth.size() + " dual-less zones passed sign-then-msg (cloneTalker + addSignScript both verified)");
		if (failures.isEmpty() && injectVerified == eligible && signScriptPassed == eligible
				&& refusalsPassed == withSign.size() && donorAlts == withSign.size() - 1
				&& composedMsgFirst == withoutBoth.size() && composedSignFirst == withoutBoth.size()) {
			System.out.println("PASS");
		} else {
			System.out.println("FAIL");
			System.exit(1);
		}
	}

	/**
	 * Injects into one sign-less zone and verifies the reserialized result
	 * byte-for-byte, then runs NpcTemplates.addSignScript on top and verifies
	 * it. Returns {injectError, addSignError} (null = pass).
	 */
	private static String[] injectAndVerify(GARC garc, int z, GFLPawnScript donor) {
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
			return new String[]{"no dispatch", "skipped"};
		}
		Map<Integer, Integer> preCaseCmds = new LinkedHashMap<>();
		for (Map.Entry<Integer, PawnInstruction> e : preD.cases.entrySet()) {
			preCaseCmds.put(e.getKey(), e.getValue() == null ? -1 : e.getValue().getCommand());
		}

		int wrapperPtr;
		try {
			wrapperPtr = SignWrapperInjector.injectSignWrapper(t, donor);
		} catch (SignWrapperInjector.InjectionException ex) {
			return new String[]{"refused: " + ex.getMessage(), "skipped"};
		}

		PawnInstruction w = ZoneScriptAnalyzer.findSignWrapper(t);
		if (w == null || w.pointer != wrapperPtr) {
			return new String[]{"in-memory wrapper not at returned address", "skipped"};
		}

		//reserialize and re-parse from scratch
		byte[] bytes = t.getScriptBytes();
		if (bytes == null) {
			return new String[]{"getScriptBytes() returned null", "skipped"};
		}
		GFLPawnScript fresh = new GFLPawnScript(bytes);
		fresh.decompressThis();

		int stubShiftCells = needStub ? 11 : 0;
		if (fresh.decInstructions[0] != preAll[0]) {
			return new String[]{"cell 0 changed", "skipped"};
		}
		if (needStub) {
			if (!MsgWrapperInjector.hasStubAt4(fresh)) {
				return new String[]{"stub not recognized at 4 after injection", "skipped"};
			}
			int stubNativeIdx = fresh.instructions.get(3).argumentCells[0];
			if (fresh.natives.get(stubNativeIdx).data[1] != SignWrapperInjector.YIELD_NATIVE_HASH) {
				return new String[]{"stub native hash wrong", "skipped"};
			}
		}
		//old code cells bit-identical (shifted by the stub for cells >= 1)
		for (int i = 1; i < preCodeCells; i++) {
			if (fresh.decInstructions[i + stubShiftCells] != preAll[i]) {
				return new String[]{"old code cell " + i + " changed (shift " + stubShiftCells + ")", "skipped"};
			}
		}
		//data: byte-identical, NOTHING appended (the key sign-specific check)
		int freshCodeCells = (fresh.dataStart - fresh.instructionStart) / 4;
		if (fresh.data.size() != preDataCells) {
			return new String[]{"data size " + fresh.data.size() + ", expected untouched " + preDataCells, "skipped"};
		}
		for (int i = 0; i < preDataCells; i++) {
			if (fresh.decInstructions[freshCodeCells + i] != preAll[preCodeCells + i]) {
				return new String[]{"data cell " + i + " changed", "skipped"};
			}
		}
		//natives: append-only, old entries byte-identical, appended entries
		//well-formed {0, hash} with hashes not duplicating any other entry
		if (fresh.natives.size() < preNatives) {
			return new String[]{"natives shrank", "skipped"};
		}
		for (int i = 0; i < preNatives; i++) {
			if (!Arrays.equals(fresh.natives.get(i).data, preNativeData.get(i))) {
				return new String[]{"native " + i + " changed", "skipped"};
			}
		}
		for (int i = preNatives; i < fresh.natives.size(); i++) {
			int[] data = fresh.natives.get(i).data;
			if (data.length != 2 || data[0] != 0) {
				return new String[]{"appended native " + i + " malformed", "skipped"};
			}
			for (int j = 0; j < fresh.natives.size(); j++) {
				if (j != i && fresh.natives.get(j).data[1] == data[1]) {
					return new String[]{"appended native " + i + " duplicates hash 0x" + Integer.toHexString(data[1]), "skipped"};
				}
			}
		}
		//publics: same count and hashes, addresses shifted by the stub only,
		//each still landing on a PROC
		if (fresh.publics.size() != prePublicData.size()) {
			return new String[]{"publics count changed", "skipped"};
		}
		for (int i = 0; i < prePublicData.size(); i++) {
			if (fresh.publics.get(i).data[0] != prePublicData.get(i)[0] + stubShiftCells * 4) {
				return new String[]{"public " + i + " address not shifted consistently", "skipped"};
			}
			if (fresh.publics.get(i).data[1] != prePublicData.get(i)[1]) {
				return new String[]{"public " + i + " hash changed", "skipped"};
			}
			PawnInstruction pt = fresh.lookupInstructionByPtr(fresh.publics.get(i).data[0]);
			if (pt == null || pt.getCommand() != OP_PROC) {
				return new String[]{"public " + i + " no longer lands on a PROC", "skipped"};
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
			return new String[]{prefixErr, "skipped"};
		}
		if (!Arrays.equals(fresh.rest, preRest)) {
			return new String[]{"rest blob changed", "skipped"};
		}
		if (fresh.mainEntryPoint != preMain + stubShiftCells * 4) {
			return new String[]{"mainEntryPoint not shifted consistently", "skipped"};
		}
		if (fresh.allocatedMem - fresh.heapStart != preHeadroom) {
			return new String[]{"VM heap/stack headroom changed", "skipped"};
		}
		//the injected wrapper is found at the predicted address
		PawnInstruction freshW = ZoneScriptAnalyzer.findSignWrapper(fresh);
		if (freshW == null || freshW.pointer != wrapperPtr) {
			return new String[]{"reserialized wrapper not at predicted address", "skipped"};
		}
		//the injected block must NOT satisfy the msg-wrapper predicate (the
		//measured disjointness that keeps msg injection available afterwards)
		PawnInstruction freshM = ZoneScriptAnalyzer.findMsgWrapper(fresh);
		boolean hadMsg = ZoneScriptAnalyzer.findMsgWrapper(donor) != null; //donor untouched; target pre-state:
		//recompute from the pristine script instead (donor always has one)
		GFLPawnScript pristine = getZoneScript(garc, z);
		boolean preHadMsg = ZoneScriptAnalyzer.findMsgWrapper(pristine) != null;
		if (preHadMsg != (freshM != null)) {
			return new String[]{"msg-wrapper presence changed from " + preHadMsg + " to " + (freshM != null), "skipped"};
		}
		//independent DFS walk of the injected closure, positionally equal to
		//the donor closure (no rewrite-site cases - raw equality elsewhere)
		Closure fc;
		try {
			fc = closureAt(fresh, wrapperPtr);
		} catch (RuntimeException ex) {
			return new String[]{"injected closure walk failed: " + ex.getMessage(), "skipped"};
		}
		String closureErr = compareClosures(donorClosure, donor, fc, fresh);
		if (closureErr != null) {
			return new String[]{"injected closure differs from donor: " + closureErr, "skipped"};
		}
		//injected block CALLs: internal or the stub at 0x4, always onto a PROC
		int blockBase = (preCodeCells + stubShiftCells) * 4;
		for (PawnInstruction ins : fresh.instructions) {
			if (ins.pointer >= blockBase && ins.getCommand() == OP_CALL) {
				int target = ins.pointer + ins.argumentCells[0];
				if (target != 4 && target < blockBase) {
					return new String[]{"block CALL leaves the block to 0x" + Integer.toHexString(target), "skipped"};
				}
				PawnInstruction ti = fresh.lookupInstructionByPtr(target);
				if (ti == null || ti.getCommand() != OP_PROC) {
					return new String[]{"block CALL target invalid", "skipped"};
				}
			}
		}
		//dispatch untouched (same keys, same target commands)
		ZoneScriptAnalyzer.Dispatch pd = ZoneScriptAnalyzer.findDispatch(fresh);
		if (pd == null || pd.cases.size() != preCaseCmds.size()) {
			return new String[]{"dispatch changed", "skipped"};
		}
		for (Map.Entry<Integer, Integer> e : preCaseCmds.entrySet()) {
			PawnInstruction ti = pd.cases.get(e.getKey());
			int cmd = ti == null ? -1 : ti.getCommand();
			if (cmd != e.getValue()) {
				return new String[]{"dispatch case " + e.getKey() + " changed", "skipped"};
			}
		}
		//double-inject guard: the injected script must now be refused
		boolean refused = false;
		try {
			SignWrapperInjector.injectSignWrapper(fresh, donor);
		} catch (SignWrapperInjector.InjectionException ex) {
			refused = true;
		}
		if (!refused) {
			return new String[]{"double injection was not refused", "skipped"};
		}

		return new String[]{null, addSignAndVerify(bytes, wrapperPtr)};
	}

	/**
	 * NpcTemplates.addSignScript on top of the injected script, verified:
	 * the new case resolves via findSignPattern with the requested constants
	 * and calls the injected wrapper; pre-existing dispatch cases, publics,
	 * headroom and the stub survive. Returns null on success.
	 */
	private static String addSignAndVerify(byte[] injectedBytes, int wrapperPtr) {
		GFLPawnScript s2 = new GFLPawnScript(injectedBytes);
		s2.decompressThis();
		ZoneScriptAnalyzer.Dispatch pre = ZoneScriptAnalyzer.findDispatch(s2);
		if (pre == null) {
			return "pre-clone dispatch missing";
		}
		Map<Integer, Integer> preCases = new LinkedHashMap<>();
		for (Map.Entry<Integer, PawnInstruction> e : pre.cases.entrySet()) {
			preCases.put(e.getKey(), e.getValue() == null ? -1 : e.getValue().getCommand());
		}
		int[] prePublics = new int[s2.publics.size()];
		for (int i = 0; i < prePublics.length; i++) {
			PawnInstruction target = s2.lookupInstructionByPtr(s2.publics.get(i).data[0]);
			prePublics[i] = (target == null) ? -1 : target.getCommand();
		}
		PawnInstruction preMain = s2.lookupInstructionByPtr(s2.mainEntryPoint);
		int preMainCmd = (preMain == null) ? -1 : preMain.getCommand();
		int preHeadroom = s2.allocatedMem - s2.heapStart;
		PawnInstruction preW = ZoneScriptAnalyzer.findSignWrapper(s2);
		if (preW == null || preW.pointer != wrapperPtr) {
			return "pre-clone sign wrapper not at injected address";
		}

		int newId;
		try {
			newId = NpcTemplates.addSignScript(s2, FAKE_LINE, FAKE_SIGN_TYPE);
		} catch (RuntimeException ex) {
			return "addSignScript failed on injected script: " + ex.getMessage();
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
		NpcTemplates.SignPattern sp = NpcTemplates.findSignPattern(s3, newId);
		if (sp == null) {
			return "new case key " + newId + " does not resolve to a vanilla-shaped sign";
		}
		if (sp.msgLine != FAKE_LINE || sp.signType != FAKE_SIGN_TYPE) {
			return "new sign constants " + sp.signType + "/" + sp.msgLine + ", expected " + FAKE_SIGN_TYPE + "/" + FAKE_LINE;
		}
		PawnInstruction postW = ZoneScriptAnalyzer.findSignWrapper(s3);
		if (postW == null || sp.wrapperEntry == null || sp.wrapperEntry.pointer != postW.pointer) {
			return "new sign does not call the injected wrapper";
		}
		for (Map.Entry<Integer, Integer> e : preCases.entrySet()) {
			if (!post.cases.containsKey(e.getKey())) {
				return "pre-existing case key " + e.getKey() + " missing after addSignScript";
			}
			PawnInstruction target = post.cases.get(e.getKey());
			int cmd = (target == null) ? -1 : target.getCommand();
			if (cmd != e.getValue()) {
				return "case " + e.getKey() + " target command changed after addSignScript";
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
			return "stub at 0x4 lost after addSignScript";
		}
		return null;
	}

	/**
	 * Composition on a zone lacking BOTH wrappers: inject in the given order,
	 * reserialize/reparse after each step, verify both wrappers are found and
	 * distinct, then run cloneTalker + addSignScript on the result and verify
	 * both editor patterns. Returns null on success.
	 */
	private static String composeAndVerify(GARC garc, int z, GFLPawnScript donor, boolean msgFirst) {
		GFLPawnScript t = getZoneScript(garc, z);
		int msgPtr, signPtr;
		try {
			if (msgFirst) {
				msgPtr = MsgWrapperInjector.injectMsgWrapper(t, donor);
				//reserialize between the two injections (fresh-parse composition)
				t = new GFLPawnScript(t.getScriptBytes());
				t.decompressThis();
				signPtr = SignWrapperInjector.injectSignWrapper(t, donor);
			} else {
				signPtr = SignWrapperInjector.injectSignWrapper(t, donor);
				t = new GFLPawnScript(t.getScriptBytes());
				t.decompressThis();
				msgPtr = MsgWrapperInjector.injectMsgWrapper(t, donor);
			}
		} catch (RuntimeException ex) {
			return "second injection refused/failed: " + ex.getMessage();
		}
		byte[] bytes = t.getScriptBytes();
		if (bytes == null) {
			return "getScriptBytes() returned null";
		}
		GFLPawnScript fresh = new GFLPawnScript(bytes);
		fresh.decompressThis();
		PawnInstruction mw = ZoneScriptAnalyzer.findMsgWrapper(fresh);
		PawnInstruction sw = ZoneScriptAnalyzer.findSignWrapper(fresh);
		if (mw == null) {
			return "msg wrapper not found after composition";
		}
		if (sw == null) {
			return "sign wrapper not found after composition";
		}
		if (mw.pointer == sw.pointer) {
			return "msg and sign predicates resolved to the same sub";
		}
		if (mw.pointer != msgPtr) {
			return "msg wrapper at 0x" + Integer.toHexString(mw.pointer) + ", injected at 0x" + Integer.toHexString(msgPtr);
		}
		if (sw.pointer != signPtr) {
			return "sign wrapper at 0x" + Integer.toHexString(sw.pointer) + ", injected at 0x" + Integer.toHexString(signPtr);
		}
		if (!MsgWrapperInjector.hasStubAt4(fresh)) {
			return "stub at 0x4 missing after composition";
		}
		//no duplicated native hashes after both injections
		for (int i = 0; i < fresh.natives.size(); i++) {
			for (int j = i + 1; j < fresh.natives.size(); j++) {
				if (fresh.natives.get(i).data[1] == fresh.natives.get(j).data[1]) {
					return "native hash 0x" + Integer.toHexString(fresh.natives.get(i).data[1]) + " duplicated after composition";
				}
			}
		}
		//both editor entry points work on the composed script
		int talkerId;
		try {
			talkerId = TalkerScriptWizard.cloneTalker(fresh, FAKE_TALKER_LINE);
		} catch (RuntimeException ex) {
			return "cloneTalker failed on composed script: " + ex.getMessage();
		}
		int signId;
		try {
			signId = NpcTemplates.addSignScript(fresh, FAKE_LINE, FAKE_SIGN_TYPE);
		} catch (RuntimeException ex) {
			return "addSignScript failed on composed script: " + ex.getMessage();
		}
		GFLPawnScript done = new GFLPawnScript(fresh.getScriptBytes());
		done.decompressThis();
		ZoneScriptAnalyzer.TalkerPattern tp = ZoneScriptAnalyzer.findTalkerPattern(done, talkerId);
		if (tp == null || tp.msgLine != FAKE_TALKER_LINE) {
			return "composed talker case does not verify";
		}
		NpcTemplates.SignPattern sp = NpcTemplates.findSignPattern(done, signId);
		if (sp == null || sp.msgLine != FAKE_LINE || sp.signType != FAKE_SIGN_TYPE) {
			return "composed sign case does not verify";
		}
		PawnInstruction dmw = ZoneScriptAnalyzer.findMsgWrapper(done);
		PawnInstruction dsw = ZoneScriptAnalyzer.findSignWrapper(done);
		if (dmw == null || tp.wrapperEntry == null || tp.wrapperEntry.pointer != dmw.pointer) {
			return "composed talker does not call the msg wrapper";
		}
		if (dsw == null || sp.wrapperEntry == null || sp.wrapperEntry.pointer != dsw.pointer) {
			return "composed sign does not call the sign wrapper";
		}
		return null;
	}

	//============ independent closure model ============
	private static class Sub {

		int entryPtr;
		int startIdx;
		int endIdx;
	}

	private static class Closure {

		final List<Sub> subs = new ArrayList<>();
	}

	private static Closure closureAt(GFLPawnScript s, int wrapperPtr) {
		Closure c = new Closure();
		dfs(s, wrapperPtr, c, new HashSet<Integer>());
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
	 * Positional closure comparison: same DFS shape, same opcodes at the same
	 * offsets, SYSREQ_N by native name hash + argBytes, CALL by sub ordinal,
	 * everything else by raw cell equality (the sign closure has no
	 * rewrite-site cases). Returns null when equivalent.
	 */
	private static String compareClosures(Closure a, GFLPawnScript sa, Closure b, GFLPawnScript sb) {
		if (a.subs.size() != b.subs.size()) {
			return "sub count " + b.subs.size() + " vs " + a.subs.size();
		}
		Map<Integer, Integer> aOrd = new HashMap<>();
		Map<Integer, Integer> bOrd = new HashMap<>();
		for (int i = 0; i < a.subs.size(); i++) {
			aOrd.put(a.subs.get(i).entryPtr, i);
			bOrd.put(b.subs.get(i).entryPtr, i);
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
					return "sub " + i + " ins " + k + " offset differs";
				}
				if (ia.getCommand() != ib.getCommand()) {
					return "sub " + i + "+0x" + Integer.toHexString(offA) + " opcode differs";
				}
				int cmd = ia.getCommand();
				if (cmd == OP_SYSREQ_N) {
					int idxA = ia.argumentCells[0];
					int idxB = ib.argumentCells[0];
					if (idxB < 0 || idxB >= sb.natives.size()) {
						return "sub " + i + "+0x" + Integer.toHexString(offA) + " SYSREQ_N index out of range";
					}
					if (sa.natives.get(idxA).data[1] != sb.natives.get(idxB).data[1]) {
						return "sub " + i + "+0x" + Integer.toHexString(offA) + " SYSREQ_N hash differs";
					}
					if (ia.argumentCells[1] != ib.argumentCells[1]) {
						return "sub " + i + "+0x" + Integer.toHexString(offA) + " SYSREQ_N argBytes differ";
					}
				} else if (cmd == OP_CALL) {
					Integer oa = aOrd.get(ia.pointer + ia.argumentCells[0]);
					Integer ob = bOrd.get(ib.pointer + ib.argumentCells[0]);
					if (oa == null || ob == null || !oa.equals(ob)) {
						return "sub " + i + "+0x" + Integer.toHexString(offA) + " CALL ordinal differs";
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

	private static GFLPawnScript getZoneScript(GARC garc, int index) {
		return MsgWrapperInjector.extractZoneScript(garc.getDecompressedEntry(index));
	}
}

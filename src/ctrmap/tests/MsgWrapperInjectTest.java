package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.PawnClosure;
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

	private static final String DEFAULT_GARC_PATH = "../RomFS_original_garcs/a/0/1/3";
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
		//...and it is the zone pickDonor tries FIRST, alone when it validates; a game with no such zone is never asked
		//for it (the 2026-10-15 shared sweep, PawnClosure 599, negate-if, SURVIVED: this asked for a donor and never
		//which). Inverted, the canonical zone was passed over for the first other one that validates, and a game
		//too short to hold it was asked for it
		List<String> failures = new ArrayList<>();
		java.util.List<Integer> asked = new java.util.ArrayList<>();
		PawnClosure.ScriptSource source = zoneIndex -> {
			asked.add(zoneIndex);
			return getZoneScript(garc, zoneIndex);
		};
		try {
			MsgWrapperInjector.pickDonor(source, ZONE_COUNT);
		} catch (RuntimeException ex) {
			System.out.println("FAIL: pickDonor found no donor: " + ex.getMessage());
			System.exit(1);
		}
		if (!asked.equals(java.util.Collections.singletonList(MsgWrapperInjector.PREFERRED_DONOR_ZONE))) {
			failures.add("the donor search asks the canonical zone " + MsgWrapperInjector.PREFERRED_DONOR_ZONE
					+ " first, and alone when it validates: it asked " + asked);
		}
		asked.clear();
		try {
			MsgWrapperInjector.pickDonor(source, MsgWrapperInjector.PREFERRED_DONOR_ZONE);
		} catch (RuntimeException noDonorAmongThem) {
			//the first zones may hold none; what is asked is the point
		}
		if (asked.contains(MsgWrapperInjector.PREFERRED_DONOR_ZONE) || asked.isEmpty()) {
			failures.add("a game of " + MsgWrapperInjector.PREFERRED_DONOR_ZONE + " zones is searched, never asked for zone "
					+ MsgWrapperInjector.PREFERRED_DONOR_ZONE + ": it asked " + asked);
		}
		//and a game none of whose zones serves is refused in words, never answered with no donor (the 2026-10-15
		//shared sweep, PawnClosure 615, swallow-throw, SURVIVED: every game this suite searched held one)
		String noneServes;
		try {
			noneServes = "answered " + MsgWrapperInjector.pickDonor(zoneIndex -> null, ZONE_COUNT);
		} catch (PawnClosure.Refused refused) {
			noneServes = refused.getMessage();
		} catch (RuntimeException other) {
			noneServes = "threw " + other;
		}
		if (!noneServes.startsWith("No zone script validates as a ") || !noneServes.endsWith(" donor.")) {
			failures.add("a game none of whose zones serves is refused in words, naming the routine: " + noneServes);
		}
		//and an injection handed no target script is refused in words before anything is read (the 2026-10-15 shared
		//sweep, PawnClosure 659, drop-throw, SURVIVED: every injection this suite made had a target). Dropped, it was a
		//NullPointerException out of the first thing done to the target
		String noTarget;
		try {
			MsgWrapperInjector.injectMsgWrapper(NpcScriptGateTest.ORAS, null, donor);
			noTarget = "injected";
		} catch (PawnClosure.Refused refused) {
			noTarget = refused.getMessage();
		} catch (RuntimeException other) {
			noTarget = "threw " + other;
		}
		if (!"No target script.".equals(noTarget)) {
			failures.add("an injection handed no target script is refused in words: " + noTarget);
		}
		//and a donor handed in as its own target is refused as that, before anything is changed (the 2026-10-15 shared
		//sweep, PawnClosure 664, drop-throw, SURVIVED: every injection this suite made had two scripts). Dropped, the
		//script was taken on into the injection - refused later for another reason, or changed under its own reading
		String itself;
		try {
			MsgWrapperInjector.injectMsgWrapper(NpcScriptGateTest.ORAS, donor, donor);
			itself = "injected";
		} catch (PawnClosure.Refused refused) {
			itself = refused.getMessage();
		} catch (RuntimeException other) {
			itself = "threw " + other;
		}
		if (!"The donor and the target are the same script.".equals(itself)) {
			failures.add("a donor handed in as its own target is refused as that: " + itself);
		}
		//and a target with no script dispatch is refused as that (the 2026-10-15 shared sweep, PawnClosure 667, drop-throw,
		//SURVIVED: every target this suite handed over was a zone script with its dispatch). A wrapper-less zone whose
		//entry point names no instruction - the first thing findDispatch reads - so it has none; the control asks first
		GFLPawnScript undispatched = getZoneScript(garc, withoutWrapper.get(0));
		undispatched.decompressThis();
		undispatched.mainEntryPoint = Integer.MAX_VALUE;
		String noDispatch;
		if (ZoneScriptAnalyzer.findDispatch(undispatched) != null) {
			noDispatch = "(control failed: the script still has a dispatch)";
		} else {
			try {
				MsgWrapperInjector.injectMsgWrapper(NpcScriptGateTest.ORAS, undispatched, donor);
				noDispatch = "injected";
			} catch (PawnClosure.Refused refused) {
				noDispatch = refused.getMessage();
			} catch (RuntimeException other) {
				noDispatch = "threw " + other;
			}
		}
		if (!"The target script has no script dispatch (main SWITCH/CASETBL).".equals(noDispatch)) {
			failures.add("a target with no script dispatch is refused as that: " + noDispatch);
		}
		//and a target whose prefix entries are not the size every retail script's are is refused, naming the size (the
		//2026-10-15 shared sweep, PawnClosure 674, drop-throw, SURVIVED: every target here was retail's). Dropped, the
		//natives were appended at the size the injector assumes into a table of another
		GFLPawnScript oddPrefix = getZoneScript(garc, withoutWrapper.get(0));
		oddPrefix.decompressThis();
		int retailSize = oddPrefix.defsize;
		oddPrefix.defsize = retailSize + 4;
		String odd;
		try {
			MsgWrapperInjector.injectMsgWrapper(NpcScriptGateTest.ORAS, oddPrefix, donor);
			odd = "injected";
		} catch (PawnClosure.Refused refused) {
			odd = refused.getMessage();
		} catch (RuntimeException other) {
			odd = "threw " + other;
		}
		if (retailSize != 8 || !("Unexpected target prefix entry size " + (retailSize + 4) + ".").equals(odd)) {
			failures.add("a target whose prefix entries are not retail's 8 bytes is refused, naming the size (retail "
					+ retailSize + "): " + odd);
		}
		//and a target one of whose native entries is malformed - fewer than its two cells - is refused naming the entry
		//(the 2026-10-15 shared sweep, PawnClosure 678, drop-throw, SURVIVED: every target's natives were retail's).
		//Dropped, the injector read on past an entry it could not read
		GFLPawnScript cutNative = getZoneScript(garc, withoutWrapper.get(0));
		cutNative.decompressThis();
		String cut;
		if (cutNative.natives.isEmpty()) {
			cut = "(control failed: the script declares no natives)";
		} else {
			cutNative.natives.get(0).data = new int[]{cutNative.natives.get(0).data[0]};
			try {
				MsgWrapperInjector.injectMsgWrapper(NpcScriptGateTest.ORAS, cutNative, donor);
				cut = "injected";
			} catch (PawnClosure.Refused refused) {
				cut = refused.getMessage();
			} catch (RuntimeException other) {
				cut = "threw " + other;
			}
		}
		if (!"Target native entry 0 is malformed.".equals(cut)) {
			failures.add("a target whose native entry is malformed is refused, naming the entry: " + cut);
		}
		//and a target whose stub-shaped sub at 0x4 calls another native than the yield one is refused as that (the
		//2026-10-15 shared sweep, PawnClosure 689, drop-throw, SURVIVED: every stub at 0x4 this suite met was retail's).
		//Dropped, it was taken for the yield stub and the routine's yields called whatever it calls. The first
		//wrapper-less zone that already has the stub, its call pointed at the next native; the control finds one
		GFLPawnScript stubbed = null;
		for (int zone : withoutWrapper) {
			GFLPawnScript s = getZoneScript(garc, zone);
			s.decompressThis();
			if (PawnClosure.hasStubAt4(s) && s.natives.size() >= 2) {
				stubbed = s;
				break;
			}
		}
		String wrongStub;
		if (stubbed == null) {
			wrongStub = "(control failed: no wrapper-less zone has the stub at 0x4)";
		} else {
			PawnInstruction call = stubbed.instructions.get(3);
			call.argumentCells[0] = (call.argumentCells[0] + 1) % stubbed.natives.size();
			try {
				MsgWrapperInjector.injectMsgWrapper(NpcScriptGateTest.ORAS, stubbed, donor);
				wrongStub = "injected";
			} catch (PawnClosure.Refused refused) {
				wrongStub = refused.getMessage();
			} catch (RuntimeException other) {
				wrongStub = "threw " + other;
			}
		}
		if (!"The stub-shaped sub at 0x4 does not call the yield native.".equals(wrongStub)) {
			failures.add("a target whose stub at 0x4 calls another native is refused as that: " + wrongStub);
		}
		//and a transplant that rewrote other than the spec's count of buffer addresses is refused, naming both (the
		//2026-10-15 shared sweep, PawnClosure 809, drop-throw, SURVIVED: no donor that passes the donor checks can
		//reach it). Asked of PawnClosure.requireRewrites with the message wrapper's own spec, through reflection
		String rewrites;
		try {
			java.lang.reflect.Field specField = MsgWrapperInjector.class.getDeclaredField("SPEC");
			specField.setAccessible(true);
			Object spec = specField.get(null);
			java.lang.reflect.Method require = PawnClosure.class.getDeclaredMethod("requireRewrites",
					Class.forName("ctrmap.formats.scripts.PawnClosure$Spec"), int.class);
			require.setAccessible(true);
			StringBuilder heard = new StringBuilder();
			for (int performed : new int[]{8, 7, 9}) {
				try {
					require.invoke(null, spec, performed);
					heard.append(performed).append(" passed; ");
				} catch (java.lang.reflect.InvocationTargetException threw) {
					heard.append(performed).append(" ").append(threw.getCause().getMessage()).append("; ");
				}
			}
			rewrites = heard.toString();
		} catch (ReflectiveOperationException unreached) {
			rewrites = "could not be asked: " + unreached;
		}
		if (!rewrites.equals("8 passed; 7 Expected 8 buffer-address rewrites, performed 7.; 9 Expected 8 buffer-address"
				+ " rewrites, performed 9.; ")) {
			failures.add("a transplant that rewrote other than the spec's 8 buffer addresses is refused, naming both: "
					+ rewrites);
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
		//EVERY WAY A DONOR CAN BE WRONG is refused in its own words (the 2026-10-14 shared sweep: MsgWrapperInjector 148
		//and 156, PawnClosure 357 and 361, each dropped, SURVIVED - every donor this suite hands over is retail's, so
		//each check below was negated and killed, and dropped and never missed). Retail's donor is spoiled one way per
		//check, and each must be refused in that check's words - not the next check's, which is what a drop leaves.
		int target = withoutWrapper.isEmpty() ? -1 : withoutWrapper.get(0);
		GFLPawnScript otherBare = withoutWrapper.size() < 2 ? null : getZoneScript(garc, withoutWrapper.get(1));
		Object[][] spoils = {
			{"a donor whose first rewrite site has not the measured shape is refused, naming the site",
				(Spoil) (s, d) -> site(s, d, 0, at -> at.hasCompressedArgument = false),
				"Donor rewrite site at 0x[0-9a-f]+ does not match the measured shape\\."},
			{"a donor whose second rewrite site disagrees with the buffer the first placed is refused, naming both",
				(Spoil) (s, d) -> site(s, d, 1, at -> at.argumentCells[0] += 4),
				"Donor rewrite site at 0x[0-9a-f]+ holds -?\\d+, expected -?\\d+\\."},
			{"a donor whose rewrite sites agree on a buffer no data region holds is refused, naming the base",
				(Spoil) (s, d) -> everySite(s, d, at -> at.argumentCells[0] += 1),
				"Donor buffer base -?\\d+ is not a readable 30-cell data region\\."},
			{"a donor of another prefix entry size is refused, naming the size",
				(Spoil) (s, d) -> {
					s.defsize = 4;
					return s;
				}, "Unexpected donor prefix entry size 4\\."},
			{"a donor with no message wrapper is refused",
				(Spoil) (s, d) -> otherBare, "The donor script has no message wrapper\\."},
			{"a donor whose closure lost a sub is refused, counting them",
				(Spoil) (s, d) -> retargetOnce(s, d), "Donor closure has \\d+ subs, expected \\d+\\."},
			{"a donor whose yield stub is not where the closure reaches it is refused",
				(Spoil) (s, d) -> swapStubCall(s, d), "Donor closure does not reach the yield stub at 0x4 in canonical order\\."},
			{"a donor whose sub runs past its measured length is refused, naming the sub",
				(Spoil) (s, d) -> lengthen(s, d), "Donor closure sub \\d+ has \\d+ cells, expected \\d+\\."},
			{"a donor with no yield stub at 0x4 is refused",
				(Spoil) (s, d) -> command(s, s.instructions.get(4), PawnInstruction.Commands.ZERO_ALT),
				"The donor has no yield stub at 0x4\\."},
			{"a donor whose stub calls another native is refused",
				(Spoil) (s, d) -> {
					PawnInstruction call = s.instructions.get(3);
					call.argumentCells[0] = (call.argumentCells[0] + 1) % s.natives.size();
					return s.natives.size() < 2 ? null : s;
				}, "The donor stub does not call the yield native\\."},
			{"a donor whose closure content was changed is refused by its fingerprint",
				(Spoil) (s, d) -> constant(s, d), "Donor closure content fingerprint 0x[0-9a-f]+ does not match the vanilla"
						+ " message routine \\(0x[0-9a-f]+\\) - the donor zone's code was modified\\."},
			{"a donor whose closure jumps indirectly is refused, naming the command",
				(Spoil) (s, d) -> first(s, d, ins -> ins.getCommand() == PawnInstruction.Commands.ZERO_PRI.ordinal(),
						ins -> command(s, ins, PawnInstruction.Commands.JUMP_PRI)),
				"Donor closure uses indirect control flow \\(cmd 0x[0-9a-f]+ at 0x[0-9a-f]+\\)\\."},
			{"a donor whose closure calls a native it does not declare is refused",
				(Spoil) (s, d) -> first(s, d, ins -> ins.getCommand() == PawnInstruction.Commands.SYSREQ_N.ordinal(), ins -> {
					ins.argumentCells[0] = s.natives.size();
					return s;
				}), "Donor SYSREQ_N at 0x[0-9a-f]+ has no well-formed natives entry\\."},
			{"a donor whose closure holds a malformed CALL is refused",
				(Spoil) (s, d) -> malformCall(s, d), "Donor CALL at 0x[0-9a-f]+ is malformed\\."},
			{"a donor whose branch leaves its sub is refused",
				(Spoil) (s, d) -> first(s, d, ins -> PawnInstruction.checkJmp(ins), ins -> {
					ins.argumentCells[0] = 0x100000;
					return s;
				}), "Donor branch at 0x[0-9a-f]+ leaves its sub\\."},
		};
		for (Object[] spoil : spoils) {
			String said = spoiledDonor(garc, target, (Spoil) spoil[1]);
			if (!said.matches((String) spoil[2])) {
				failures.add(spoil[0] + ": " + said);
			} else {
				System.out.println("  ok: " + spoil[0] + ": " + said);
			}
		}
		//NOR A TARGET WHOSE DATA THE BUFFER CANNOT FOLLOW (MsgWrapperInjector 189, dropped, SURVIVED the same sweep: every
		//retail target's data ends far below the 16-bit address the rebased buffer is written with)
		String bigSaid = bigTargetRefusal(garc, target);
		if (!bigSaid.matches("The target data segment is too large to rebase the buffer \\(\\d+ bytes\\)\\.")) {
			failures.add("a target whose data segment the buffer cannot follow is refused, naming its size: " + bigSaid);
		} else {
			System.out.println("  ok: a target whose data segment the buffer cannot follow is refused, naming its size: " + bigSaid);
		}
		//neither retail closure holds a SWITCH or a case table, so those are asked of the closure check itself
		Object[][] tables = {
			{"a SWITCH that leaves its sub is refused as a branch", selfContained(garc, PawnInstruction.Commands.SWITCH, 0x100000),
				"Donor branch at 0x[0-9a-f]+ leaves its sub\\."},
			{"a case table whose default leaves its sub is refused", selfContained(garc, PawnInstruction.Commands.CASETBL, 0, 0x100000),
				"Donor CASETBL at 0x[0-9a-f]+ leaves its sub\\."},
			{"a case table whose case leaves its sub is refused", selfContained(garc, PawnInstruction.Commands.CASETBL, 1, -4, 7, 0x100000),
				"Donor CASETBL at 0x[0-9a-f]+ leaves its sub\\."},
			{"control: a case table inside its sub passes", selfContained(garc, PawnInstruction.Commands.CASETBL, 1, -4, 7, -12),
				"it passed"}};
		for (Object[] t : tables) {
			if (!((String) t[1]).matches((String) t[2])) {
				failures.add(t[0] + ": " + t[1]);
			} else {
				System.out.println("  ok: " + t[0] + ": " + t[1]);
			}
		}
		//AND NO DONOR AT ALL (PawnClosure 357, dropped, SURVIVED the same sweep): refused in words, never a null pointer
		String noDonor;
		try {
			MsgWrapperInjector.injectMsgWrapper(NpcScriptGateTest.ORAS,
					getZoneScript(garc, withoutWrapper.isEmpty() ? 0 : withoutWrapper.get(0)), null);
			noDonor = "it injected";
		} catch (PawnClosure.Refused refused) {
			noDonor = refused.getMessage();
		} catch (RuntimeException other) {
			noDonor = "it failed some other way: " + other;
		}
		if (!noDonor.equals("No donor script.")) {
			failures.add("no donor script at all is refused in words: " + noDonor);
		} else {
			System.out.println("  ok: no donor script at all is refused in words: " + noDonor);
		}
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
					MsgWrapperInjector.injectMsgWrapper(NpcScriptGateTest.ORAS, s, donor);
				} catch (PawnClosure.Refused ex) {
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
		boolean needStub = !PawnClosure.hasStubAt4(t);

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
		//the count the NPC form and the painter quote before injecting is what the injection adds, the stub's 8 only
		//when the target lacks it (the 2026-10-15 shared sweep, PawnClosure 511, negate-if, SURVIVED: no suite asked it)
		int counted = MsgWrapperInjector.countInjectedInstructions(t, donor);
		int countedBare = MsgWrapperInjector.countInjectedInstructions(null, donor);
		int preInstructions = t.instructions.size();
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
			wrapperPtr = MsgWrapperInjector.injectMsgWrapper(NpcScriptGateTest.ORAS, t, donor);
		} catch (PawnClosure.Refused ex) {
			return "refused: " + ex.getMessage();
		}
		int added = t.instructions.size() - preInstructions;
		if (added != counted || counted - countedBare != (needStub ? 8 : 0)) {
			return "the count quoted before injecting is not what it added: " + counted + " quoted (" + countedBare
					+ " for no target), " + added + " added, the stub " + (needStub ? "needed" : "there already");
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
			if (!PawnClosure.hasStubAt4(fresh)) {
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
			MsgWrapperInjector.injectMsgWrapper(NpcScriptGateTest.ORAS, fresh, donor);
		} catch (PawnClosure.Refused ex) {
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
			newId = TalkerScriptWizard.cloneTalker(NpcScriptGateTest.ORAS, s2, FAKE_LINE);
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
		if (!PawnClosure.hasStubAt4(s3)) {
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
	 * One way to spoil retail's donor: answers the script to hand over - changed in place, or another one - or null
	 * when the donor holds nothing this spoil can change, which fails the check rather than passing it.
	 */
	interface Spoil {
		GFLPawnScript apply(GFLPawnScript donor, PawnClosure.Donor built) throws Exception;
	}

	/** A change to one instruction, answering the script to hand over or null. */
	interface Change {
		GFLPawnScript apply(PawnInstruction ins) throws Exception;
	}

	/**
	 * What injecting from retail's donor, spoiled by {@code spoil}, said: its refusal, or what it did. The closure is
	 * built from the clean donor first, as the injector builds it, so a spoil can find what it changes.
	 */
	static String spoiledDonor(GARC garc, int targetZone, Spoil spoil) throws Exception {
		if (targetZone < 0) {
			return "no wrapper-less zone to inject into";
		}
		GFLPawnScript donor = getZoneScript(garc, MsgWrapperInjector.PREFERRED_DONOR_ZONE);
		java.lang.reflect.Field specField = MsgWrapperInjector.class.getDeclaredField("SPEC");
		specField.setAccessible(true);
		java.lang.reflect.Method build = PawnClosure.class.getDeclaredMethod("buildDonor",
				Class.forName("ctrmap.formats.scripts.PawnClosure$Spec"), GFLPawnScript.class);
		build.setAccessible(true);
		PawnClosure.Donor d = (PawnClosure.Donor) build.invoke(null, specField.get(null), donor);
		GFLPawnScript handed = spoil.apply(donor, d);
		if (handed == null) {
			return "the donor holds nothing this spoil can change";
		}
		try {
			MsgWrapperInjector.injectMsgWrapper(NpcScriptGateTest.ORAS, getZoneScript(garc, targetZone), handed);
			return "it injected";
		} catch (PawnClosure.Refused refused) {
			return refused.getMessage();
		} catch (RuntimeException other) {
			return "it failed some other way: " + other;
		}
	}

	/**
	 * What injecting retail's routine into a wrapper-less zone said once its data segment was grown, cell by cell,
	 * until the rebased buffer's last address passes what a packed argument holds: its refusal, or what it did.
	 */
	static String bigTargetRefusal(GARC garc, int targetZone) throws Exception {
		if (targetZone < 0) {
			return "no wrapper-less zone to inject into";
		}
		java.lang.reflect.Field delta = MsgWrapperInjector.class.getDeclaredField("MAX_BUFFER_DELTA");
		delta.setAccessible(true);
		int maxDelta = delta.getInt(null);
		GFLPawnScript t = getZoneScript(garc, targetZone);
		t.decompressThis();
		PawnInstruction cell = t.data.isEmpty() ? new PawnInstruction(0, 0, "") : t.data.get(0);
		while (t.data.size() * 4 + maxDelta <= Short.MAX_VALUE) {
			t.data.add(cell);
		}
		try {
			MsgWrapperInjector.injectMsgWrapper(NpcScriptGateTest.ORAS, t, getZoneScript(garc, MsgWrapperInjector.PREFERRED_DONOR_ZONE));
			return "it injected";
		} catch (PawnClosure.Refused refused) {
			return refused.getMessage();
		} catch (RuntimeException other) {
			return "it failed some other way: " + other;
		}
	}

	/** Rewrite site {@code n} of the donor's closure, changed by {@code change}. */
	static GFLPawnScript site(GFLPawnScript s, PawnClosure.Donor d, int n, java.util.function.Consumer<PawnInstruction> change)
			throws Exception {
		java.lang.reflect.Field sitesField = MsgWrapperInjector.class.getDeclaredField("REWRITE_SITES");
		sitesField.setAccessible(true);
		int[] site = ((int[][]) sitesField.get(null))[n];
		change.accept(s.lookupInstructionByPtr(d.subPtrs.get(site[0]) + site[1]));
		return s;
	}

	/** How many cells an instruction takes: one when its operand is packed into it, else one per operand and its own. */
	static int width(PawnInstruction ins) {
		return ins.hasCompressedArgument ? 1 : 1 + ins.argumentCount;
	}

	/** Every rewrite site of the donor's closure, each changed by {@code change}. */
	static GFLPawnScript everySite(GFLPawnScript s, PawnClosure.Donor d, java.util.function.Consumer<PawnInstruction> change)
			throws Exception {
		java.lang.reflect.Field sitesField = MsgWrapperInjector.class.getDeclaredField("REWRITE_SITES");
		sitesField.setAccessible(true);
		for (int n = 0; n < ((int[][]) sitesField.get(null)).length; n++) {
			site(s, d, n, change);
		}
		return s;
	}

	/** {@code ins} made {@code to}, or null when {@code to} takes another number of cells - a swap that moves no cell. */
	static GFLPawnScript command(GFLPawnScript s, PawnInstruction ins, PawnInstruction.Commands to) {
		if (width(new PawnInstruction(0, to.ordinal(), "")) != width(ins)) {
			return null;
		}
		ins.cellValue = (ins.cellValue & ~0x7FFF) | to.ordinal();
		return s;
	}

	/** The first instruction of the closure, outside its yield stub, that {@code which} picks, changed by {@code change}. */
	static GFLPawnScript first(GFLPawnScript s, PawnClosure.Donor d, java.util.function.Predicate<PawnInstruction> which,
			Change change) throws Exception {
		for (int ptr : d.subPtrs) {
			int[] r = d.subRanges.get(ptr);
			for (int i = r[0]; ptr != 4 && i < r[1]; i++) {
				if (which.test(s.instructions.get(i))) {
					return change.apply(s.instructions.get(i));
				}
			}
		}
		return null;
	}

	/** Every well-formed CALL in the closure, in the closure's order. */
	static List<PawnInstruction> calls(GFLPawnScript s, PawnClosure.Donor d) {
		List<PawnInstruction> out = new ArrayList<>();
		for (int ptr : d.subPtrs) {
			int[] r = d.subRanges.get(ptr);
			for (int i = r[0]; i < r[1]; i++) {
				PawnInstruction ins = s.instructions.get(i);
				if (ins.getCommand() == PawnInstruction.Commands.CALL.ordinal() && ins.argumentCells.length == 1) {
					out.add(ins);
				}
			}
		}
		return out;
	}

	/** Where a CALL lands. */
	static int target(PawnInstruction call) {
		return call.pointer + call.argumentCells[0];
	}

	/** How many CALLs in the closure land on {@code ptr}. */
	static int callers(GFLPawnScript s, PawnClosure.Donor d, int ptr) {
		int n = 0;
		for (PawnInstruction c : calls(s, d)) {
			n += target(c) == ptr ? 1 : 0;
		}
		return n;
	}

	/** The closure walked again over the script as it now stands, from the same entry. */
	static PawnClosure.Donor walked(GFLPawnScript s, PawnClosure.Donor d) {
		PawnClosure.Donor again = new PawnClosure.Donor();
		PawnClosure.dfsClosure(s, d.wrapperPtr, again);
		return again;
	}

	/** Do two walks hold the same subs, in the same order, of the same sizes? */
	static boolean sameWalk(PawnClosure.Donor a, PawnClosure.Donor b) {
		if (!a.subPtrs.equals(b.subPtrs)) {
			return false;
		}
		for (int ptr : a.subPtrs) {
			if (a.subCells.get(ptr).length != b.subCells.get(ptr).length) {
				return false;
			}
		}
		return true;
	}

	/** A CALL whose callee nothing else in the closure calls, sent to the yield stub - so the callee leaves the closure. */
	static GFLPawnScript retargetOnce(GFLPawnScript s, PawnClosure.Donor d) {
		for (PawnInstruction c : calls(s, d)) {
			if (target(c) != 4 && callers(s, d, target(c)) == 1) {
				c.argumentCells[0] = 4 - c.pointer;
				return s;
			}
		}
		return null;
	}

	/**
	 * Two CALLs with their targets swapped, the first pair whose closure, walked again, keeps its count and no longer
	 * holds the yield stub where the spec measured it - every pair is tried, so it does not hang on how often the stub
	 * is called (3 times in the message routine).
	 */
	static GFLPawnScript swapStubCall(GFLPawnScript s, PawnClosure.Donor d) throws Exception {
		java.lang.reflect.Field at = MsgWrapperInjector.class.getDeclaredField("STUB_DFS_INDEX");
		at.setAccessible(true);
		int stubAt = at.getInt(null);
		List<PawnInstruction> all = calls(s, d);
		for (int i = 0; i < all.size(); i++) {
			for (int j = i + 1; j < all.size(); j++) {
				PawnInstruction a = all.get(i);
				PawnInstruction b = all.get(j);
				int ta = target(a);
				int tb = target(b);
				if (ta == tb) {
					continue;
				}
				a.argumentCells[0] = tb - a.pointer;
				b.argumentCells[0] = ta - b.pointer;
				PawnClosure.Donor again = walked(s, d);
				if (again.subPtrs.size() == d.subPtrs.size() && again.subPtrs.get(stubAt) != 4) {
					return s;
				}
				a.argumentCells[0] = ta - a.pointer;
				b.argumentCells[0] = tb - b.pointer;
			}
		}
		return null;
	}

	/**
	 * What the closure's own check said of retail's donor with one instruction outside its stub replaced by
	 * {@code made}: its refusal, or that it passed. Asked of requireSelfContained directly, after the walk - neither
	 * retail closure holds a SWITCH or a case table, so no spoiled donor reaches these lines through buildDonor.
	 */
	static String selfContained(GARC garc, PawnInstruction.Commands made, int... operands) throws Exception {
		GFLPawnScript donor = getZoneScript(garc, MsgWrapperInjector.PREFERRED_DONOR_ZONE);
		java.lang.reflect.Field specField = MsgWrapperInjector.class.getDeclaredField("SPEC");
		specField.setAccessible(true);
		java.lang.reflect.Method build = PawnClosure.class.getDeclaredMethod("buildDonor",
				Class.forName("ctrmap.formats.scripts.PawnClosure$Spec"), GFLPawnScript.class);
		build.setAccessible(true);
		PawnClosure.Donor d = (PawnClosure.Donor) build.invoke(null, specField.get(null), donor);
		int[] r = d.subRanges.get(d.subPtrs.get(0));
		PawnInstruction was = donor.instructions.get(r[0] + 1);
		PawnInstruction in = new PawnInstruction(was.pointer, made.ordinal(), "");
		in.argumentCells = operands;
		in.argumentCount = operands.length;
		in.hasCompressedArgument = false;
		donor.instructions.set(r[0] + 1, in);
		java.lang.reflect.Method check = PawnClosure.class.getDeclaredMethod("requireSelfContained", GFLPawnScript.class,
				PawnClosure.Donor.class);
		check.setAccessible(true);
		try {
			check.invoke(null, donor, d);
			return "it passed";
		} catch (java.lang.reflect.InvocationTargetException e) {
			return e.getCause() instanceof PawnClosure.Refused ? e.getCause().getMessage() : "it failed some other way: " + e.getCause();
		}
	}

	/**
	 * The PROC that ends a closure sub made a RETN, where nothing in the closure calls it and what follows it calls
	 * nothing: the sub runs on to the next PROC, longer than measured, and the closure is otherwise the same.
	 */
	static GFLPawnScript lengthen(GFLPawnScript s, PawnClosure.Donor d) {
		for (int ptr : d.subPtrs) {
			int end = d.subRanges.get(ptr)[1];
			if (end >= s.instructions.size() || d.subCells.containsKey(s.instructions.get(end).pointer)) {
				continue;
			}
			boolean callsOut = false;
			for (int i = end + 1; i < s.instructions.size() && s.instructions.get(i).getCommand() != PawnInstruction.Commands.PROC.ordinal(); i++) {
				callsOut |= s.instructions.get(i).getCommand() == PawnInstruction.Commands.CALL.ordinal();
			}
			if (!callsOut) {
				return command(s, s.instructions.get(end), PawnInstruction.Commands.RETN);
			}
		}
		return null;
	}

	/**
	 * One operand in the closure, outside the stub, every rewrite site, every CALL, branch, case table and native
	 * call, changed by one: a cell only the fingerprint reads.
	 */
	static GFLPawnScript constant(GFLPawnScript s, PawnClosure.Donor d) throws Exception {
		java.util.Set<Integer> fixed = new java.util.HashSet<>(java.util.Arrays.asList(
				PawnInstruction.Commands.CALL.ordinal(), PawnInstruction.Commands.CASETBL.ordinal(),
				PawnInstruction.Commands.SWITCH.ordinal(), PawnInstruction.Commands.SYSREQ_N.ordinal(),
				PawnInstruction.Commands.CONST_P_PRI.ordinal(), PawnInstruction.Commands.PUSH_P_C.ordinal()));
		return first(s, d, ins -> ins.argumentCount >= 1 && !fixed.contains(ins.getCommand()) && !PawnInstruction.checkJmp(ins),
				ins -> {
					ins.argumentCells[0] += 1;
					return s;
				});
	}

	/**
	 * A CALL given a second operand, where its callee is reached another way and the closure walks the same without
	 * it - so the one thing wrong is the CALL itself.
	 */
	static GFLPawnScript malformCall(GFLPawnScript s, PawnClosure.Donor d) {
		for (PawnInstruction c : calls(s, d)) {
			if (callers(s, d, target(c)) < 2) {
				continue;
			}
			int[] was = c.argumentCells;
			c.argumentCells = new int[]{was[0], 0};
			if (sameWalk(d, walked(s, d))) {
				return s;
			}
			c.argumentCells = was;
		}
		return null;
	}

	/**
	 * Extracts subfile 2 (map script) of ZoneData entry index and parses it;
	 * null when the entry is not a ZO container.
	 */
	private static GFLPawnScript getZoneScript(GARC garc, int index) {
		return ctrmap.formats.scripts.PawnClosure.extractZoneScript(garc.getDecompressedEntry(index));
	}
}

package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.NpcTemplates;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.ZoneScriptAnalyzer;
import ctrmap.formats.zone.ZoneEntities;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Headless dry run of the sign and item-giver script templates plus the
 * record-only templates (trainer, scripted NPC, sign furniture), over the
 * PRISTINE ORAS ZoneData GARC (read-only backup, nothing is written). Run with:
 * java -cp "build/classes;lib/jogl-all.jar;lib/gluegen-rt.jar" ctrmap.tests.NpcTemplatesTest
 *
 * For every zone whose script carries a sign (or give-item) wrapper, the full
 * template surgery is executed in memory, reserialized with getScriptBytes(),
 * re-parsed from scratch and verified: the new case resolves to the exact
 * template shape with the requested parameters, every pre-existing case still
 * resolves to the same target command, publics still point at PROCs and the
 * main entry is unchanged. The record templates are round-tripped through
 * ZoneEntities.assembleData() and re-parsed to prove the field encodings
 * survive. Zero exceptions allowed.
 */
public class NpcTemplatesTest {

	private static final String DEFAULT_GARC_PATH = "../RomFS_original_garcs/a/0/1/3";
	private static final int ZONE_COUNT = 536;
	private static final int FAKE_LINE = 777;
	private static final int OP_PROC = PawnInstruction.Commands.PROC.ordinal();

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0] : DEFAULT_GARC_PATH);
		if (!garcFile.exists()) {
			System.out.println("FAIL: pristine ZoneData GARC not found: " + garcFile.getAbsolutePath());
			System.exit(1);
		}
		GARC garc = new GARC(garcFile);
		List<String> failures = new ArrayList<>();
		int noSignZone = -1, noGiveZone = -1, giveZone = -1;

		//--- census + sign/giver dry-runs -------------------------------------
		int zoZones = 0, signWrapperZones = 0, giveWrapperZones = 0;
		int vanillaSignsDetected = 0, vanillaGiversDetected = 0;
		int signRun = 0, signOk = 0, giveRun = 0, giveOk = 0, onceRun = 0, onceOk = 0;

		for (int i = 0; i < ZONE_COUNT; i++) {
			GFLPawnScript s = getZoneScript(garc, i);
			if (s == null) {
				continue; //non-ZO dummy
			}
			zoZones++;

			//detector census: do the wrappers exist, and are vanilla template
			//cases re-detected by the pattern matchers?
			ZoneScriptAnalyzer.Dispatch disp = ZoneScriptAnalyzer.findDispatch(s);
			boolean hasSign = ZoneScriptAnalyzer.findSignWrapper(s) != null;
			boolean hasGive = ZoneScriptAnalyzer.findGiveWrapper(s) != null;
			if (hasSign) {
				signWrapperZones++;
			}
			if (hasGive) {
				giveWrapperZones++;
			}
			if (disp != null) {
				for (int key : disp.cases.keySet()) {
					if (key == -1) {
						continue;
					}
					if (NpcTemplates.findSignPattern(s, key) != null) {
						vanillaSignsDetected++;
					}
					if (NpcTemplates.findGiverPattern(s, key) != null) {
						vanillaGiversDetected++;
					}
				}
			}

			if (!hasSign && noSignZone < 0) {
				noSignZone = i;
			}
			if (!hasGive && noGiveZone < 0) {
				noGiveZone = i;
			}
			if (hasGive && giveZone < 0) {
				giveZone = i;
			}
			//sign template dry-run
			if (hasSign) {
				signRun++;
				String err = dryRunSign(getZoneScript(garc, i), NpcTemplates.SIGN_TYPES[0]);
				if (err == null) {
					signOk++;
				} else {
					failures.add("sign zone " + i + ": " + err);
				}
			}
			//giver template dry-run
			if (hasGive) {
				giveRun++;
				String err = dryRunGiver(getZoneScript(garc, i), 4 /*item id*/, 1 /*count*/);
				if (err == null) {
					giveOk++;
				} else {
					failures.add("give zone " + i + ": " + err);
				}
				//and given ONCE (F085): the same template inside retail's one-time guard
				onceRun++;
				String once = dryRunOnceGiver(getZoneScript(garc, i), 4, 1, ONCE_FLAG);
				if (once == null) {
					onceOk++;
				} else {
					failures.add("once-give zone " + i + ": " + once);
				}
			}
		}
		System.out.println("one-time give template: " + onceOk + "/" + onceRun + " zones injected and read back as retail's guard");
		//WHAT A TEMPLATE CANNOT GO INTO IS REFUSED, the script left as it was (the 2026-10-14 shared sweep, NpcTemplates
		//290, dropped, SURVIVED: the dry-runs above ask only zones that hold the routine, and in-range items)
		int itemMax = ctrmap.gamedef.GameProfile.of(ctrmap.gamedef.GameType.ORAS).itemIdMax();
		Object[][] refusals = {
			{"a sign in a zone with no sign routine", noSignZone, (TemplateCall) s -> NpcTemplates.addSignScript(
					NpcScriptGateTest.ORAS, s, FAKE_LINE, NpcTemplates.SIGN_TYPES[0]), "The zone script has no sign display routine"},
			{"an item giver in a zone with no give routine", noGiveZone, (TemplateCall) s -> NpcTemplates.addItemGiverScript(
					NpcScriptGateTest.ORAS, s, 4, 1, -1), "The zone script has no give-item routine"},
			{"item 0", giveZone, (TemplateCall) s -> NpcTemplates.addItemGiverScript(NpcScriptGateTest.ORAS, s, 0, 1, -1),
				"Item ID 0 is out of range 1.." + itemMax + "."},
			{"item " + (itemMax + 1), giveZone, (TemplateCall) s -> NpcTemplates.addItemGiverScript(NpcScriptGateTest.ORAS, s,
					itemMax + 1, 1, -1), "Item ID " + (itemMax + 1) + " is out of range 1.." + itemMax + "."},
			{"a count of 0", giveZone, (TemplateCall) s -> NpcTemplates.addItemGiverScript(NpcScriptGateTest.ORAS, s, 4, 0, -1),
				"Item count 0 is out of range 1..99."},
			{"a count of 100", giveZone, (TemplateCall) s -> NpcTemplates.addItemGiverScript(NpcScriptGateTest.ORAS, s, 4, 100,
					-1), "Item count 100 is out of range 1..99."},
			//nor a once-flag no packed argument holds (PawnEmitter 95, dropped, SURVIVED the same sweep)
			{"a giver once by flag 0", giveZone, (TemplateCall) s -> NpcTemplates.addItemGiverScript(NpcScriptGateTest.ORAS, s,
					4, 1, 0), "Event flag 0 does not fit a packed argument (1..32767)."},
			{"a giver once by flag 32768", giveZone, (TemplateCall) s -> NpcTemplates.addItemGiverScript(NpcScriptGateTest.ORAS,
					s, 4, 1, 32768), "Event flag 32768 does not fit a packed argument (1..32767)."}};
		for (Object[] r : refusals) {
			int zone = (Integer) r[1];
			if (zone < 0) {
				failures.add(r[0] + " is refused: no zone of that kind to ask it of");
				continue;
			}
			GFLPawnScript s = getZoneScript(garc, zone);
			s.decompressThis();
			byte[] before = s.getScriptBytes();
			String said;
			try {
				said = "it added case " + ((TemplateCall) r[2]).add(s);
			} catch (IllegalStateException | IllegalArgumentException refused) {
				said = refused.getMessage();
			} catch (RuntimeException other) {
				said = "it failed some other way: " + other;
			}
			boolean kept = java.util.Arrays.equals(before, s.getScriptBytes());
			if (!said.startsWith((String) r[3]) || !kept) {
				failures.add(r[0] + " is refused, the script left as it was: " + said + (kept ? "" : " (and the script was changed)"));
			} else {
				System.out.println("  ok: " + r[0] + " is refused, the script left as it was: " + said);
			}
		}
		if (onceRun == 0 || onceOk != onceRun) {
			failures.add("the one-time giver was built in " + onceOk + " of " + onceRun + " give-wrapper zones");
		}
		failures.addAll(scriptFlagsReadTheGameAsItStands(garc));

		//--- record templates (trainer / scripted NPC / sign furniture) -------
		String recErr = recordRoundTrips(garc);
		if (recErr != null) {
			failures.add("records: " + recErr);
		}

		for (String f : failures) {
			System.out.println("FAILURE: " + f);
		}
		System.out.println("census: " + zoZones + " ZO zones | sign-wrapper zones: " + signWrapperZones
				+ " | give-wrapper zones: " + giveWrapperZones);
		System.out.println("census: vanilla signs re-detected: " + vanillaSignsDetected
				+ " | vanilla standalone givers re-detected: " + vanillaGiversDetected
				+ " (vanilla embeds item-gives inside dialogue subs, so 0 standalone giver"
				+ " cases is expected; the giver template is validated by the inject+verify"
				+ " round-trip below)");
		System.out.println("sign template: " + signOk + "/" + signRun + " zones injected + verified");
		System.out.println("give template: " + giveOk + "/" + giveRun + " zones injected + verified");
		System.out.println("record templates (trainer/scripted/furniture): " + (recErr == null ? "verified" : "FAILED"));

		//note: vanillaGiversDetected is expected to be 0 - vanilla has no standalone
		//item-giver dispatch cases (gives are embedded in dialogue sequences). The
		//giver template's correctness is proven by the inject+verify round-trip, which
		//itself exercises findGiverPattern on our own output.
		boolean ok = failures.isEmpty()
				&& signRun > 0 && signOk == signRun
				&& giveRun > 0 && giveOk == giveRun
				&& signWrapperZones > 0 && giveWrapperZones > 0
				&& vanillaSignsDetected > 0;
		if (ok) {
			System.out.println("PASS");
		} else {
			System.out.println("FAIL");
			System.exit(1);
		}
	}

	private static String dryRunSign(GFLPawnScript s, int signType) {
		Map<Integer, Integer> preCases = snapshotCases(s);
		int[] prePublics = snapshotPublics(s);
		int preHeadroom = s.allocatedMem - s.heapStart;

		int newId;
		try {
			newId = NpcTemplates.addSignScript(NpcScriptGateTest.ORAS, s, FAKE_LINE, signType);
		} catch (RuntimeException ex) {
			return "addSignScript threw: " + ex.getMessage();
		}

		GFLPawnScript fresh = reparse(s);
		if (fresh == null) {
			return "getScriptBytes() returned null";
		}
		if (fresh.allocatedMem - fresh.heapStart != preHeadroom) {
			return "VM headroom changed";
		}
		NpcTemplates.SignPattern sp = NpcTemplates.findSignPattern(fresh, newId);
		if (sp == null) {
			return "new case " + newId + " does not resolve to a sign pattern";
		}
		if (sp.msgLine != FAKE_LINE) {
			return "new sign msgLine == " + sp.msgLine + ", expected " + FAKE_LINE;
		}
		if (sp.signType != signType) {
			return "new sign type == " + sp.signType + ", expected " + signType;
		}
		return verifyPreserved(fresh, preCases, prePublics, newId);
	}

	/** A flag for the one-time dry runs: the template takes any; ScriptFlags is what chooses (F085). */
	static final int ONCE_FLAG = 3076;

	/**
	 * The one-time giver, injected and read back (F085): its case runs retail's guard - PUSH flag;
	 * FlagGet; JNZ past; the four give pushes; CALL the zone's give wrapper; PUSH flag; FlagSet - and the
	 * JNZ lands on what follows the FlagSet, so a second talk gives nothing. Every other case and public
	 * as it was.
	 */
	private static String dryRunOnceGiver(GFLPawnScript s, int itemId, int count, int flag) {
		Map<Integer, Integer> preCases = snapshotCases(s);
		int[] prePublics = snapshotPublics(s);
		int newId;
		try {
			newId = NpcTemplates.addItemGiverScript(NpcScriptGateTest.ORAS, s, itemId, count, flag);
		} catch (RuntimeException ex) {
			return "addItemGiverScript(once) threw: " + ex.getMessage();
		}
		GFLPawnScript fresh = reparse(s);
		if (fresh == null) {
			return "getScriptBytes() returned null";
		}
		PawnInstruction entry = ZoneScriptAnalyzer.findCaseSubEntry(fresh, newId);
		int at = entry == null ? -1 : fresh.instructions.indexOf(entry);
		if (at < 0 || at + 13 > fresh.instructions.size()) {
			return "new case " + newId + " leads to no subroutine";
		}
		List<PawnInstruction> b = fresh.instructions.subList(at, at + 13);
		PawnInstruction.Commands[] want = {PawnInstruction.Commands.PROC, PawnInstruction.Commands.PUSH_P_C,
			PawnInstruction.Commands.SYSREQ_N, PawnInstruction.Commands.JNZ, PawnInstruction.Commands.PUSH_P_C,
			PawnInstruction.Commands.PUSH_P_C, PawnInstruction.Commands.PUSH_P_C, PawnInstruction.Commands.PUSH_P_C,
			PawnInstruction.Commands.CALL, PawnInstruction.Commands.PUSH_P_C, PawnInstruction.Commands.SYSREQ_N,
			PawnInstruction.Commands.ZERO_PRI, PawnInstruction.Commands.RETN};
		for (int k = 0; k < want.length; k++) {
			if (b.get(k).getCommand() != want[k].ordinal()) {
				return "instruction " + k + " of the one-time giver is " + PawnInstruction.getDisassembly(b.get(k))
						+ ", not " + want[k];
			}
		}
		if (b.get(1).argumentCells[0] != flag || b.get(9).argumentCells[0] != flag) {
			return "the guard pushes flags " + b.get(1).argumentCells[0] + " and " + b.get(9).argumentCells[0] + ", not " + flag;
		}
		if (nativeHash(fresh, b.get(2)) != ctrmap.scripts.GfHash.hashForName("FlagGet")
				|| nativeHash(fresh, b.get(10)) != ctrmap.scripts.GfHash.hashForName("FlagSet")) {
			return "the guard does not ask FlagGet before the give and FlagSet after it";
		}
		if (b.get(4).argumentCells[0] != NpcTemplates.GIVE_MODE || b.get(5).argumentCells[0] != count
				|| b.get(6).argumentCells[0] != itemId || b.get(7).argumentCells[0] != NpcTemplates.GIVE_ARG_BYTES) {
			return "the give inside the guard does not push mode, count " + count + ", item " + itemId + " and the argument bytes";
		}
		if (fresh.lookupInstructionByPtr(b.get(8).pointer + b.get(8).argumentCells[0]) != ZoneScriptAnalyzer.findGiveWrapper(fresh)) {
			return "the give inside the guard does not call the zone's give wrapper";
		}
		if (fresh.lookupInstructionByPtr(b.get(3).pointer + b.get(3).argumentCells[0]) != b.get(11)) {
			return "the guard's JNZ does not land past the FlagSet, so a second talk would give again";
		}
		return verifyPreserved(fresh, preCases, prePublics, newId);
	}

	private static int nativeHash(GFLPawnScript s, PawnInstruction sysreq) {
		int n = sysreq.argumentCells[0];
		return n >= 0 && n < s.natives.size() && s.natives.get(n).data.length >= 2 ? s.natives.get(n).data[1] : 0;
	}

	/**
	 * Which event flags the game's zone scripts name, read as the game holds them (F085). Known answers
	 * MEASURED 2026-10-09 over the retail ZoneData: 536 scripts, 621 distinct flags pushed before a flag
	 * native, the highest 3080, and 72 flag calls whose flag is computed. Then a zone given a one-time giver
	 * on the offered flag, staged as an edit, makes that flag named - the giver after it is refused it.
	 */
	static List<String> scriptFlagsReadTheGameAsItStands(GARC garc) throws Exception {
		System.out.println("--- which flags the scripts use, read as the game holds them (F085)");
		List<String> out = new ArrayList<>();
		FakeGameFiles game = new FakeGameFiles().open(ctrmap.gamedef.ArchiveType.ZONE_DATA, garc);
		ctrmap.ScriptFlags f = ctrmap.ScriptFlags.of(game);
		//AN INDEPENDENT WALK, as the oracle: every zone's script, each call to a flag native, the constant
		//pushed just before it - its own count of scripts and computed calls the control that it walked
		java.util.Set<Integer> hashes = new java.util.HashSet<>();
		for (String n : new String[]{"FlagGet", "FlagSet", "FlagSetReq", "FlagReset"}) {
			hashes.add(ctrmap.scripts.GfHash.hashForName(n));
		}
		java.util.SortedSet<Integer> named = new java.util.TreeSet<>();
		int scripts = 0, computed = 0;
		for (int z = 0; z < 536; z++) {
			GFLPawnScript zs = getZoneScript(garc, z);
			scripts++;
			for (int k = 0; k < zs.instructions.size(); k++) {
				PawnInstruction a = zs.instructions.get(k);
				if (a.getCommand() != PawnInstruction.Commands.SYSREQ_N.ordinal() || a.argumentCells.length == 0
						|| a.argumentCells[0] < 0 || a.argumentCells[0] >= zs.natives.size()
						|| !hashes.contains(zs.natives.get(a.argumentCells[0]).data[1])) {
					continue;
				}
				PawnInstruction b = k > 0 ? zs.instructions.get(k - 1) : null;
				if (b != null && (b.getCommand() == PawnInstruction.Commands.PUSH_C.ordinal()
						|| b.getCommand() == PawnInstruction.Commands.PUSH_P_C.ordinal())) {
					named.add(b.argumentCells[0]);
				} else {
					computed++;
				}
			}
		}
		if (scripts != 536 || named.size() != 621 || computed != 72) {
			out.add("control: the walk measured " + scripts + " scripts, " + named.size() + " flags, " + computed
					+ " computed - not the 536, 621 and 72 measured");
		}
		if (!f.named.equals(named) || f.highest() != 3080) {
			out.add("the flag reader names " + f.named.size() + " flags up to " + f.highest() + ", the walk " + named.size()
					+ " - not the same 621 up to 3080");
		}
		if (f.refusal(105) == null || f.refusal(3081) == null || f.refusal(0) == null) {
			out.add("flag 105 (zone 8's one-time giver's), 3081 (past the highest) or 0 was offered to a new giver");
		}
		int free = f.suggestion();
		if (free < 1 || f.named.contains(free) || f.refusal(free) != null) {
			out.add("the offered flag " + free + " is one a script uses, or is refused");
		}
		System.out.println("  " + scripts + " scripts name " + f.named.size() + " flags up to " + f.highest() + " (" + computed
				+ " computed); offered: " + free);
		//THE GAME AS IT STANDS: an earlier one-time giver's flag is a flag a script uses
		int zone = 8;
		GFLPawnScript s = getZoneScript(garc, zone);
		NpcTemplates.addItemGiverScript(NpcScriptGateTest.ORAS, s, 4, 1, free);
		File staged = game.plant(ctrmap.gamedef.ArchiveType.ZONE_DATA, zone, garc.getDecompressedEntry(zone))
				.staged(ctrmap.gamedef.ArchiveType.ZONE_DATA, zone);
		new ctrmap.formats.containers.ZO(staged, game).storeFile(2, s.getScriptBytes());
		game.edited(staged);
		ctrmap.ScriptFlags after = ctrmap.ScriptFlags.of(game);
		if (!after.named.contains(free) || after.refusal(free) == null) {
			out.add("a zone staged with a one-time giver on flag " + free + " was read without it - the next giver would be offered it again");
		}
		//measured by the 2026-10-12 sweep (ScriptFlags 67, dropped, SURVIVED): nothing asked a zone that holds no
		//script. Dropped, the read went on into the parser with none, and the zone was called unreadable because of
		//a null pointer instead of because there is no script in it
		FakeGameFiles noScript = new FakeGameFiles().open(ctrmap.gamedef.ArchiveType.ZONE_DATA, garc);
		File junk = noScript.plant(ctrmap.gamedef.ArchiveType.ZONE_DATA, 3, new byte[4]).staged(ctrmap.gamedef.ArchiveType.ZONE_DATA, 3);
		noScript.edited(junk);
		String said;
		try {
			ctrmap.ScriptFlags read = ctrmap.ScriptFlags.of(noScript);
			said = read == null ? "it answered no flag reader at all" : "it read " + read.named.size() + " flags";
		} catch (java.io.IOException refused) {
			said = refused.getMessage();
		}
		System.out.println("  a zone staged with no script in it: " + said);
		if (!"zone 3's script could not be read, so which flags it names is unknown".equals(said)) {
			out.add("a zone staged with no script in it was not refused with that reason: " + said);
		}
		//measured by the 2026-10-12 sweep (ScriptFlags 96, dropped, SURVIVED): nothing asked a game whose zone
		//scripts name no flag at all. Dropped, the read answered an empty set, and the first question asked of it -
		//the highest flag - failed on an empty set instead of saying the read found none. The game: the first two
		//retail zones whose scripts name no flag, then the retail master table and encounter pack
		List<byte[]> flagless = new ArrayList<>();
		for (int z = 0; z < 536 && flagless.size() < 2; z++) {
			if (!namesAFlag(getZoneScript(garc, z), hashes)) {
				flagless.add(garc.getDecompressedEntry(z));
			}
		}
		if (flagless.size() < 2) {
			out.add("CONTROL: the retail ZoneData holds " + flagless.size() + " zone(s) whose script names no flag, not 2");
		} else {
			File none = new File(Scratch.dir("ctrmap_flagless_zones"), "zonedata.garc");
			HandedGameTest.writeGarc(none, flagless.get(0), flagless.get(1), garc.getDecompressedEntry(536),
					garc.getDecompressedEntry(537));
			FakeGameFiles noFlags = new FakeGameFiles().open(ctrmap.gamedef.ArchiveType.ZONE_DATA, new GARC(none));
			try {
				ctrmap.ScriptFlags read = ctrmap.ScriptFlags.of(noFlags);
				said = read == null ? "it answered no flag reader at all" : "it read " + read.named.size() + " flags";
			} catch (java.io.IOException refused) {
				said = refused.getMessage();
			}
			System.out.println("  a game whose zone scripts name no flag: " + said);
			if (!"no zone script of 2 names a flag - a read that found none is not a game with none".equals(said)) {
				out.add("a game whose zone scripts name no flag was not refused with that reason: " + said);
			}
		}
		return out;
	}

	/** Whether {@code zs} pushes a constant just before a call to one of the flag natives - the oracle walk's test. */
	private static boolean namesAFlag(GFLPawnScript zs, java.util.Set<Integer> hashes) {
		for (int k = 1; k < zs.instructions.size(); k++) {
			PawnInstruction a = zs.instructions.get(k);
			if (a.getCommand() != PawnInstruction.Commands.SYSREQ_N.ordinal() || a.argumentCells.length == 0
					|| a.argumentCells[0] < 0 || a.argumentCells[0] >= zs.natives.size()
					|| !hashes.contains(zs.natives.get(a.argumentCells[0]).data[1])) {
				continue;
			}
			PawnInstruction b = zs.instructions.get(k - 1);
			if (b.getCommand() == PawnInstruction.Commands.PUSH_C.ordinal()
					|| b.getCommand() == PawnInstruction.Commands.PUSH_P_C.ordinal()) {
				return true;
			}
		}
		return false;
	}

	private static String dryRunGiver(GFLPawnScript s, int itemId, int count) {
		Map<Integer, Integer> preCases = snapshotCases(s);
		int[] prePublics = snapshotPublics(s);
		int preHeadroom = s.allocatedMem - s.heapStart;

		int newId;
		try {
			newId = NpcTemplates.addItemGiverScript(NpcScriptGateTest.ORAS, s, itemId, count, -1);
		} catch (RuntimeException ex) {
			return "addItemGiverScript threw: " + ex.getMessage();
		}

		GFLPawnScript fresh = reparse(s);
		if (fresh == null) {
			return "getScriptBytes() returned null";
		}
		if (fresh.allocatedMem - fresh.heapStart != preHeadroom) {
			return "VM headroom changed";
		}
		NpcTemplates.GiverPattern gp = NpcTemplates.findGiverPattern(fresh, newId);
		if (gp == null) {
			return "new case " + newId + " does not resolve to a giver pattern";
		}
		if (gp.itemId != itemId) {
			return "new giver itemId == " + gp.itemId + ", expected " + itemId;
		}
		if (gp.count != count) {
			return "new giver count == " + gp.count + ", expected " + count;
		}
		return verifyPreserved(fresh, preCases, prePublics, newId);
	}

	/**
	 * Verifies that every pre-existing dispatch case still resolves to the same
	 * target command, the new case is present, publics still point at the same
	 * command kinds, and the main entry is intact.
	 */
	private static String verifyPreserved(GFLPawnScript fresh, Map<Integer, Integer> preCases, int[] prePublics, int newId) {
		ZoneScriptAnalyzer.Dispatch post = ZoneScriptAnalyzer.findDispatch(fresh);
		if (post == null) {
			return "dispatch no longer found";
		}
		if (!post.cases.containsKey(newId)) {
			return "new case key " + newId + " missing from CASETBL";
		}
		if (post.cases.size() != preCases.size() + 1) {
			return "case count " + post.cases.size() + ", expected " + (preCases.size() + 1);
		}
		for (Map.Entry<Integer, Integer> e : preCases.entrySet()) {
			PawnInstruction target = post.cases.get(e.getKey());
			int cmd = (target == null) ? -1 : target.getCommand();
			if (!post.cases.containsKey(e.getKey())) {
				return "pre-existing case key " + e.getKey() + " missing";
			}
			if (cmd != e.getValue()) {
				return "case " + e.getKey() + " target command changed: was " + e.getValue() + ", now " + cmd;
			}
		}
		if (fresh.publics.size() != prePublics.length) {
			return "publics count changed";
		}
		for (int i = 0; i < prePublics.length; i++) {
			PawnInstruction target = fresh.lookupInstructionByPtr(fresh.publics.get(i).data[0]);
			int cmd = (target == null) ? -1 : target.getCommand();
			if (cmd != prePublics[i]) {
				return "public " + i + " target command changed: was " + prePublics[i] + ", now " + cmd;
			}
		}
		return null;
	}

	private static Map<Integer, Integer> snapshotCases(GFLPawnScript s) {
		ZoneScriptAnalyzer.Dispatch pre = ZoneScriptAnalyzer.findDispatch(s);
		Map<Integer, Integer> preCases = new LinkedHashMap<>();
		for (Map.Entry<Integer, PawnInstruction> e : pre.cases.entrySet()) {
			preCases.put(e.getKey(), e.getValue() == null ? -1 : e.getValue().getCommand());
		}
		return preCases;
	}

	private static int[] snapshotPublics(GFLPawnScript s) {
		int[] prePublics = new int[s.publics.size()];
		for (int i = 0; i < prePublics.length; i++) {
			PawnInstruction target = s.lookupInstructionByPtr(s.publics.get(i).data[0]);
			prePublics[i] = (target == null) ? -1 : target.getCommand();
		}
		return prePublics;
	}

	private static GFLPawnScript reparse(GFLPawnScript s) {
		byte[] bytes = s.getScriptBytes();
		if (bytes == null) {
			return null;
		}
		GFLPawnScript fresh = new GFLPawnScript(bytes);
		fresh.decompressThis();
		return fresh;
	}

	/**
	 * Builds the record-only templates, round-trips them through
	 * ZoneEntities.assembleData(), and verifies the decoded fields.
	 */
	private static String recordRoundTrips(GARC garc) {
		ZoneEntities e = getZoneEntities(garc, 24);
		if (e == null) {
			return "could not load zone 24 entities";
		}
		int baseNpc = e.npcs.size();
		int baseProp = e.furniture.size();
		int uid = NpcTemplates.nextFreeUid(e);

		ZoneEntities.NPC trainer = NpcTemplates.makeTrainerNpc(NpcScriptGateTest.ORAS, uid, 7, 100, 5, 1, 30, 40);
		ZoneEntities.NPC pair = NpcTemplates.makeTrainerPairNpc(NpcScriptGateTest.ORAS, uid + 1, 7, 100, 5, 1, 31, 40);
		ZoneEntities.NPC giverNpc = NpcTemplates.makeScriptedNpc(NpcScriptGateTest.ORAS, uid + 2, 50, 42, 10, 12);
		ZoneEntities.Prop sign = NpcTemplates.makeSignFurniture(NpcScriptGateTest.ORAS, 9, 15, 16);

		e.npcs.add(trainer);
		e.npcs.add(pair);
		e.npcs.add(giverNpc);
		e.furniture.add(sign);
		e.modified = true;

		byte[] bytes = e.assembleData();
		if (bytes == null) {
			return "assembleData() returned null";
		}
		ZoneEntities re = new ZoneEntities(bytes);
		if (re.npcs.size() != baseNpc + 3) {
			return "NPC count after round-trip " + re.npcs.size() + ", expected " + (baseNpc + 3);
		}
		if (re.furniture.size() != baseProp + 1) {
			return "furniture count after round-trip " + re.furniture.size() + ", expected " + (baseProp + 1);
		}
		ZoneEntities.NPC rt = re.npcs.get(baseNpc);
		if (rt.script != NpcTemplates.TRAINER_SCRIPT_BASE + 7) {
			return "trainer script == " + rt.script + ", expected " + (NpcTemplates.TRAINER_SCRIPT_BASE + 7);
		}
		if (rt.movePerm2 != NpcTemplates.TRAINER_MOVE_EVENT || rt.sightRange != 5 || rt.model != 100) {
			return "trainer fields not preserved (movePerm2/sightRange/model)";
		}
		ZoneEntities.NPC rp = re.npcs.get(baseNpc + 1);
		if (rp.script != NpcTemplates.TRAINER_PAIR_SCRIPT_BASE + 7 || rp.movePerm2 != NpcTemplates.TRAINER_PAIR_MOVE_EVENT) {
			return "trainer pair fields not preserved";
		}
		ZoneEntities.NPC rg = re.npcs.get(baseNpc + 2);
		if (rg.script != 42 || rg.model != 50 || rg.xTile != 10 || rg.yTile != 12) {
			return "scripted NPC fields not preserved";
		}
		ZoneEntities.Prop rs = re.furniture.get(baseProp);
		if (rs.script != 9 || rs.unknown2 != NpcTemplates.SIGN_FURNITURE_U2 || rs.x != 15 || rs.y != 16 || rs.w != 1 || rs.h != 1) {
			return "sign furniture fields not preserved";
		}

		//out-of-range guards
		try {
			NpcTemplates.makeTrainerNpc(NpcScriptGateTest.ORAS, 0, 0, 0, 0, 0, 0, 0);
			return "makeTrainerNpc accepted trainer id 0";
		} catch (RuntimeException ok) {
			//expected
		}
		return null;
	}

	private static GFLPawnScript getZoneScript(GARC garc, int index) {
		byte[] zo = subfile(garc, index, 2);
		if (zo == null) {
			return null;
		}
		GFLPawnScript s = new GFLPawnScript(zo);
		s.decompressThis();
		return s;
	}

	private static ZoneEntities getZoneEntities(GARC garc, int index) {
		byte[] ent = subfile(garc, index, 1);
		return ent == null ? null : new ZoneEntities(ent);
	}

	/**
	 * Extracts a ZO container subfile from a ZoneData entry, or null when the
	 * entry is not a ZO container.
	 */
	private static byte[] subfile(GARC garc, int index, int sub) {
		byte[] zo = garc.getDecompressedEntry(index);
		if (zo == null || zo.length < 4) {
			return null;
		}
		int magic = ((zo[0] & 0xFF) << 8) | (zo[1] & 0xFF);
		if (magic != 0x5A4F) {
			return null;
		}
		return ctrmap.formats.containers.ContainerBytes.subfile(zo, sub);
	}

	/** One template put into a zone's script, answering the case id it added. */
	interface TemplateCall {
		int add(GFLPawnScript s);
	}
}

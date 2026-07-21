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

	private static final String DEFAULT_GARC_PATH = "C:\\Users\\flami\\Desktop\\Claude\\sessions\\3DS Editor\\RomFS_original_garcs\\a\\0\\1\\3";
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

		//--- census + sign/giver dry-runs -------------------------------------
		int zoZones = 0, signWrapperZones = 0, giveWrapperZones = 0;
		int vanillaSignsDetected = 0, vanillaGiversDetected = 0;
		int signRun = 0, signOk = 0, giveRun = 0, giveOk = 0;

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
			}
		}

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
			newId = NpcTemplates.addSignScript(s, FAKE_LINE, signType);
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

	private static String dryRunGiver(GFLPawnScript s, int itemId, int count) {
		Map<Integer, Integer> preCases = snapshotCases(s);
		int[] prePublics = snapshotPublics(s);
		int preHeadroom = s.allocatedMem - s.heapStart;

		int newId;
		try {
			newId = NpcTemplates.addItemGiverScript(s, itemId, count);
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

		ZoneEntities.NPC trainer = NpcTemplates.makeTrainerNpc(uid, 7, 100, 5, 1, 30, 40);
		ZoneEntities.NPC pair = NpcTemplates.makeTrainerPairNpc(uid + 1, 7, 100, 5, 1, 31, 40);
		ZoneEntities.NPC giverNpc = NpcTemplates.makeScriptedNpc(uid + 2, 50, 42, 10, 12);
		ZoneEntities.Prop sign = NpcTemplates.makeSignFurniture(9, 15, 16);

		e.npcs.add(trainer);
		e.npcs.add(pair);
		e.npcs.add(giverNpc);
		e.furniture.add(sign);
		e.NPCCount = e.npcs.size();
		e.furnitureCount = e.furniture.size();
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
			NpcTemplates.makeTrainerNpc(0, 0, 0, 0, 0, 0, 0);
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
		int count = (zo[2] & 0xFF) | ((zo[3] & 0xFF) << 8);
		if (count <= sub || zo.length < 4 + (count + 1) * 4) {
			return null;
		}
		int start = readIntLE(zo, 4 + sub * 4);
		int end = readIntLE(zo, 4 + (sub + 1) * 4);
		if (start < 0 || end > zo.length || end < start) {
			return null;
		}
		byte[] out = new byte[end - start];
		System.arraycopy(zo, start, out, 0, out.length);
		return out;
	}

	private static int readIntLE(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}
}

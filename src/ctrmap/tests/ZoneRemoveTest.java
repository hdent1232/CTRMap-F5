package ctrmap.tests;

import ctrmap.ZoneRemover;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.garc.GarcRebuilder;
import ctrmap.formats.garc.LZ11;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Validates the shrink-capable GARC writer and zone removal against the
 * pristine ZoneData:
 * <ol>
 * <li>identity rebuild: rewriting all 538 stored entries yields an archive
 *     whose every entry decompresses identically;</li>
 * <li>append simulation: a 4-zone appended layout (zones 536-539 = copies,
 *     master grown to 540 rows at 540, EN at 541) round-trips through the
 *     rebuilder;</li>
 * <li>removal: {@link ZoneRemover#stockLayout} restores the stock layout
 *     with every base zone, the master table and the EN pack content-equal
 *     to pristine;</li>
 * <li>the base-zone warp scan reports no references into added zones on
 *     pristine data;</li>
 * <li>where the scripts send the player - each zone's own and the shared scripts no zone owns -
 *     reaches a zone as a warp does, for the reusable-zone scan and the removal warning alike, and
 *     what no reading names is said (F225).</li>
 * </ol>
 * Usage: java ctrmap.tests.ZoneRemoveTest &lt;path-to-a013-garc&gt; &lt;path-to-the-dump's-a029-garc, the shared scripts&gt;
 */
public class ZoneRemoveTest {

	public static void main(String[] args) throws Exception {
		File pristine = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/1/3");
		File tmpDir = Scratch.dir("zrm");
		File a = new File(tmpDir, "zrm_a013");
		Files.copy(pristine.toPath(), a.toPath(), StandardCopyOption.REPLACE_EXISTING);
		GARC pz = new GARC(pristine);
		int fails = 0;
		fails += check("pristine has 538 entries", pz.length == 538);

		// (1) identity rebuild
		List<byte[]> stored = new ArrayList<>();
		for (int i = 0; i < pz.length; i++) {
			stored.add(pz.getStoredEntry(i));
		}
		File b = new File(tmpDir, "zrm_identity");
		GarcRebuilder.write(a, b, stored);
		GARC rb = new GARC(b);
		fails += check("identity count", rb.length == 538);
		boolean idOk = true;
		for (int i = 0; i < 538; i++) {
			if (!Arrays.equals(rb.getDecompressedEntry(i), pz.getDecompressedEntry(i))) {
				idOk = false;
				System.out.println("  identity mismatch at entry " + i);
				break;
			}
		}
		fails += check("identity content", idOk);

		// (2) simulated 4-zone append
		byte[] master = pz.getDecompressedEntry(536);
		byte[] grown = Arrays.copyOf(master, master.length + 4 * ZoneRemover.MASTER_ROW);
		for (int k = 0; k < 4; k++) {
			System.arraycopy(master, ZoneRemover.MASTER_ROW, grown, master.length + k * ZoneRemover.MASTER_ROW, ZoneRemover.MASTER_ROW);
		}
		List<byte[]> appended = new ArrayList<>();
		for (int i = 0; i < 536; i++) {
			appended.add(pz.getStoredEntry(i));
		}
		for (int k = 0; k < 4; k++) {
			appended.add(pz.getStoredEntry(1)); // 4 added zones = copies of zone 1
		}
		appended.add(pz.isEntryCompressed(536) ? LZ11.compress(grown) : grown);
		appended.add(pz.getStoredEntry(537));
		File c = new File(tmpDir, "zrm_appended");
		GarcRebuilder.write(a, c, appended);
		GARC ap = new GARC(c);
		fails += check("appended count 542", ap.length == 542);
		fails += check("added zone content", Arrays.equals(ap.getDecompressedEntry(538), pz.getDecompressedEntry(1)));
		fails += check("grown master rows", ap.getDecompressedEntry(540).length == 540 * ZoneRemover.MASTER_ROW);

		// (3) removal restores stock content
		int removed = shrink(c);
		fails += check("removed 4", removed == 4);
		GARC rm = new GARC(c);
		fails += check("restored count 538", rm.length == 538);
		boolean restOk = true;
		for (int i = 0; i < 538; i++) {
			if (!Arrays.equals(rm.getDecompressedEntry(i), pz.getDecompressedEntry(i))) {
				restOk = false;
				System.out.println("  restore mismatch at entry " + i);
				break;
			}
		}
		fails += check("restored content == pristine", restOk);
		fails += check("removal on stock is a no-op", shrink(b) == 0);

		// the shared scripts no zone owns send the player to zones too (F225), read from the dump: nothing writes them
		GARC sharedArc = new GARC(new File(args.length > 1 ? args[1] : "../RomFS/000400000011C400"
				+ ctrmap.Workspace.getArchivePath(ctrmap.gamedef.ArchiveType.SHARED_SCRIPTS, ctrmap.gamedef.GameType.ORAS)));
		ctrmap.ZoneRepurposeScanner.SharedReach shared = ctrmap.ZoneRepurposeScanner.sharedReach(sharedArc.length, sharedArc::getDecompressedEntry);
		System.out.println("  " + sharedArc.length + " shared scripts; they name " + new java.util.TreeSet<>(shared.zones).size()
				+ " zones; not counted: " + shared.unread);
		fails += check("control: the shared scripts send the player to some zone (" + shared.zones.size() + ")", !shared.zones.isEmpty());
		//and what no reading names is SAID: each shared script calling a map-change native other than _MapChangeCore
		//(which argument carries the zone is unmeasured), and each whose _MapChangeCore destination is computed
		java.util.Set<Integer> callsOther = new java.util.TreeSet<>(), saidScripts = new java.util.TreeSet<>();
		for (int i = 0; i < sharedArc.length; i++) {
			ctrmap.formats.scripts.GFLPawnScript s = new ctrmap.formats.scripts.GFLPawnScript(sharedArc.getDecompressedEntry(i));
			s.decompressThis();
			for (ctrmap.formats.scripts.PawnInstruction req : s.instructions) {
				if (req.getCommand() == ctrmap.formats.scripts.PawnInstruction.Commands.SYSREQ_N.ordinal() && req.argumentCells[0] >= 0
						&& req.argumentCells[0] < s.natives.size()) {
					int hash = s.natives.get(req.argumentCells[0]).data[1];
					String name = ctrmap.scripts.GfHash.nameForHash(hash);
					if (name != null && name.contains("MapChange") && hash != ctrmap.formats.scripts.ZoneScriptAnalyzer.MAP_CHANGE_CORE_NATIVE_HASH) {
						callsOther.add(i);
					}
				}
			}
		}
		for (String u : shared.unread) {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("^shared script (\\d+): ").matcher(u);
			if (m.find()) {
				saidScripts.add(Integer.parseInt(m.group(1)));
			}
		}
		java.util.Set<Integer> unsaidOther = new java.util.TreeSet<>(callsOther), computedCore = new java.util.TreeSet<>(saidScripts);
		unsaidOther.removeAll(saidScripts);
		computedCore.removeAll(callsOther);
		fails += check("control: some shared script calls a map-change native other than _MapChangeCore " + callsOther, !callsOther.isEmpty());
		fails += check("every shared script calling another map-change native is said (unsaid: " + unsaidOther + ")", unsaidOther.isEmpty());
		fails += check("the shared scripts' computed _MapChangeCore destinations are said: " + shared.unread, !computedCore.isEmpty());

		// (4) pristine base zones reference no added zones - and the only things nothing could check are the shared
		// scripts' destinations computed at run time, each said
		List<String> pristineRefs = ZoneRemover.referencesToAdded(pz.length, pz::getDecompressedEntry, shared);
		boolean dangling = false, unsaid = false;
		for (String r : pristineRefs) {
			dangling |= r.contains("-> added zone");
			unsaid |= !(r.startsWith("shared script ") && r.endsWith("is not checked"));
		}
		fails += check("no dangling references in pristine: " + pristineRefs, !dangling);
		fails += check("in pristine, all the removal could not check is a shared script's computed destination: " + pristineRefs, !unsaid);

		// (5) F225: a zone a script sends the player to is reached, like a warp's target. MEASURED 2026-10-09:
		// _MapChangeCore's first argument is the destination (all 95 retail call sites land in its map). Every
		// zone a retail script names is no reusable candidate - and the control is how many of them NO warp
		// reaches, which the scan offered as safe to overwrite before it read scripts.
		java.util.Set<Integer> scriptTargets = new java.util.TreeSet<>(), warpTargets = new java.util.TreeSet<>();
		for (int i = 0; i < 536; i++) {
			byte[] z = pz.getDecompressedEntry(i);
			scriptTargets.addAll(ctrmap.formats.scripts.ZoneScriptAnalyzer.mapChangesOfZone(z).zones);
			for (ctrmap.formats.zone.ZoneEntities.Warp w : new ctrmap.formats.zone.ZoneEntities(
					ctrmap.formats.containers.ContainerBytes.subfile(z, 1)).warps) {
				warpTargets.add(w.targetZone);
			}
		}
		java.util.Set<Integer> onlyByScript = new java.util.TreeSet<>(scriptTargets);
		onlyByScript.removeAll(warpTargets);
		java.util.Set<Integer> onlyByShared = new java.util.TreeSet<>(shared.zones);
		onlyByShared.removeAll(warpTargets);
		onlyByShared.removeAll(scriptTargets);
		ctrmap.ZoneRepurposeScanner.Scan scan = ctrmap.ZoneRepurposeScanner.scanZones(536, 536, pz::getDecompressedEntry, parentMap -> "", shared);
		java.util.Set<Integer> offered = new java.util.TreeSet<>(), offeredShared = new java.util.TreeSet<>();
		for (ctrmap.ZoneRepurposeScanner.Candidate cand : scan.candidates) {
			offered.add(cand.index);
		}
		offeredShared.addAll(offered);
		offered.retainAll(scriptTargets);
		offeredShared.retainAll(shared.zones);
		System.out.println("  " + scriptTargets.size() + " zones a retail zone script sends the player to; " + onlyByScript.size()
				+ " of them no warp reaches " + onlyByScript);
		System.out.println("  zones only a shared script sends the player to: " + onlyByShared);
		fails += check("control: some zone only a script reaches (" + onlyByScript.size() + ")", !onlyByScript.isEmpty());
		fails += check("no zone a script sends the player to is offered as reusable (offered: " + offered + ")", offered.isEmpty());
		fails += check("no zone a shared script sends the player to is offered as reusable (offered: " + offeredShared + ")", offeredShared.isEmpty());
		//and with a shared script naming the scan's first candidate, it is reached - asked whether or not retail has such a zone
		int first = scan.candidates.isEmpty() ? -1 : scan.candidates.get(0).index;
		fails += check("control: the retail scan offers a candidate", first > 0);
		if (first > 0) {
			ctrmap.ZoneRepurposeScanner.SharedReach naming = ctrmap.ZoneRepurposeScanner.sharedReach(sharedArc.length, sharedArc::getDecompressedEntry);
			naming.zones.add(first);
			naming.from.add(0);
			boolean stillOffered = false;
			for (ctrmap.ZoneRepurposeScanner.Candidate cand : ctrmap.ZoneRepurposeScanner.scanZones(536, 536, pz::getDecompressedEntry, parentMap -> "", naming).candidates) {
				stillOffered |= cand.index == first;
			}
			fails += check("a zone a shared script sends the player to is no candidate (zone " + first + ")", !stillOffered);
		}

		// (6) and a script's map change into an added zone is in the removal warning: zone 6's (to zone 23) re-pointed at 540
		final byte[] sixTo540 = zoneSixsMapChangeTo23(pz, (s, at) -> {
			s.instructions.get(at).argumentCells[0] = 540;
			s.instructions.get(at).updateDisassembly();
		});
		fails += check("control: zone 6's map change to zone 23 was found and re-pointed at 540", sixTo540 != null);
		if (sixTo540 != null) {
			List<String> refs = ZoneRemover.referencesToAdded(pz.length, i -> i == 6 ? sixTo540 : pz.getDecompressedEntry(i), shared);
			fails += check("a script's map change into added zone 540 is in the removal warning: " + refs, refs.contains("zone 6's script -> added zone 540"));
			//asked whether or not the zone's warps read: a zone whose warps did not read skipped its script unsaid
			byte[] ent = ctrmap.formats.containers.ContainerBytes.subfile(sixTo540, 1);
			final byte[] cutWarps = HandedGameTest.container("ZO", ctrmap.formats.containers.ContainerBytes.subfile(sixTo540, 0),
					Arrays.copyOf(ent, 14), ctrmap.formats.containers.ContainerBytes.subfile(sixTo540, 2));
			List<String> cutRefs = ZoneRemover.referencesToAdded(pz.length, i -> i == 6 ? cutWarps : pz.getDecompressedEntry(i), shared);
			boolean warpsSaid = false;
			for (String r : cutRefs) {
				warpsSaid |= r.startsWith("zone 6's warps could not be read");
			}
			fails += check("control: zone 6 with its entities cut short has warps that do not read: " + cutRefs, warpsSaid);
			fails += check("and its script's map change into added zone 540 is still in the warning: " + cutRefs, cutRefs.contains("zone 6's script -> added zone 540"));
			//measured by the 2026-10-12 sweep (ZoneRemover 251, dropped, SURVIVED): no zone with an EMPTY entity block was
			//scanned. Dropped, the empty block went to the entity reader, and the zone's warps were said unreadable for
			//whatever that threw - or read as none and never checked, a warp into a removed zone unsaid
			final byte[] noEntities = HandedGameTest.container("ZO", ctrmap.formats.containers.ContainerBytes.subfile(sixTo540, 0),
					new byte[0], ctrmap.formats.containers.ContainerBytes.subfile(sixTo540, 2));
			List<String> emptyRefs = ZoneRemover.referencesToAdded(pz.length, i -> i == 6 ? noEntities : pz.getDecompressedEntry(i), shared);
			fails += check("a zone whose container has an empty entity block is said, with that reason, never read as no warps: "
					+ emptyRefs, emptyRefs.contains("zone 6's warps could not be read (its container has no whole entity block),"
					+ " so they are not checked"));
		}
		//and a shared script's: the same SharedReach, one more destination, 540
		ctrmap.ZoneRepurposeScanner.SharedReach to540 = ctrmap.ZoneRepurposeScanner.sharedReach(sharedArc.length, sharedArc::getDecompressedEntry);
		to540.zones.add(540);
		to540.from.add(7);
		List<String> sharedRefs = ZoneRemover.referencesToAdded(pz.length, pz::getDecompressedEntry, to540);
		fails += check("a shared script's map change into added zone 540 is in the removal warning: " + sharedRefs, sharedRefs.contains("shared script 7 -> added zone 540"));

		// (7) a destination computed at run time could be any zone, so no reading names it: the same call site's constant
		// swapped for a stack variable of the same size. The scan and the removal both SAY so - and the scan still counts
		// the zone's warps, so what zone 6 leads to by warp is no more offered than it was.
		final byte[] sixComputed = zoneSixsMapChangeTo23(pz, (s, at) -> {
			ctrmap.formats.scripts.PawnInstruction push = s.instructions.get(at);
			ctrmap.formats.scripts.PawnInstruction stack = new ctrmap.formats.scripts.PawnInstruction(push.pointer,
					(push.hasCompressedArgument ? ctrmap.formats.scripts.PawnInstruction.Commands.PUSH_P_S
							: ctrmap.formats.scripts.PawnInstruction.Commands.PUSH_S).ordinal(), "");
			stack.argumentCells[0] = -4;
			stack.updateDisassembly();
			s.instructions.set(at, stack);
		});
		fails += check("control: zone 6's map change to zone 23 was found and made computed", sixComputed != null);
		if (sixComputed != null) {
			//handed NO shared scripts: what zone 6 alone leads to is asked among the zones' warps and scripts, where it is
			//zone 227 - which shared script 78 also reaches, so with them handed nothing would be zone 6's alone
			ctrmap.ZoneRepurposeScanner.SharedReach noShared = new ctrmap.ZoneRepurposeScanner.SharedReach();
			ctrmap.ZoneRepurposeScanner.Scan computedScan = ctrmap.ZoneRepurposeScanner.scanZones(536, 536, i -> i == 6 ? sixComputed : pz.getDecompressedEntry(i), parentMap -> "", noShared);
			fails += check("the scan says zone 6's computed map change was not counted: " + computedScan.unreadable,
					computedScan.unreadable.contains("zone 6: 1 script map change(s) whose destination is computed are not counted"));
			//the zones only zone 6 leads to - by warp, or by its script's named map changes - are what dropping its warps would offer
			java.util.Map<Integer, java.util.Set<Integer>> ledFrom = new java.util.TreeMap<>();
			for (int i = 0; i < 536; i++) {
				byte[] z = pz.getDecompressedEntry(i);
				java.util.List<Integer> to = new java.util.ArrayList<>(ctrmap.formats.scripts.ZoneScriptAnalyzer.mapChangesOfZone(z).zones);
				for (ctrmap.formats.zone.ZoneEntities.Warp w : new ctrmap.formats.zone.ZoneEntities(
						ctrmap.formats.containers.ContainerBytes.subfile(z, 1)).warps) {
					to.add(w.targetZone);
				}
				for (int t : to) {
					ledFrom.computeIfAbsent(t, k -> new java.util.TreeSet<>()).add(i);
				}
			}
			java.util.Set<Integer> onlyFromSix = new java.util.TreeSet<>();
			for (java.util.Map.Entry<Integer, java.util.Set<Integer>> e : ledFrom.entrySet()) {
				if (e.getValue().equals(java.util.Collections.singleton(6)) && e.getKey() != 23) {
					onlyFromSix.add(e.getKey());
				}
			}
			java.util.Set<Integer> nowOffered = new java.util.TreeSet<>();
			for (ctrmap.ZoneRepurposeScanner.Candidate cand : computedScan.candidates) {
				nowOffered.add(cand.index);
			}
			nowOffered.retainAll(onlyFromSix);
			System.out.println("  zones only zone 6 leads to, beside 23: " + onlyFromSix);
			fails += check("control: some zone only zone 6 leads to, beside 23 (" + onlyFromSix.size() + ")", !onlyFromSix.isEmpty());
			fails += check("zone 6's warps still count, so none of what only it leads to is offered: " + nowOffered, nowOffered.isEmpty());
			//and a zone whose script goes somewhere no reading names is itself no candidate: the same zone, put in the
			//slot of the scan's first candidate
			int slot = scan.candidates.isEmpty() ? -1 : scan.candidates.get(0).index;
			fails += check("control: the retail scan offers a candidate", slot > 0);
			if (slot > 0) {
				ctrmap.ZoneRepurposeScanner.Scan inSlot = ctrmap.ZoneRepurposeScanner.scanZones(536, 536, i -> i == slot ? sixComputed : pz.getDecompressedEntry(i), parentMap -> "", shared);
				boolean stillOffered = false;
				for (ctrmap.ZoneRepurposeScanner.Candidate cand : inSlot.candidates) {
					stillOffered |= cand.index == slot;
				}
				fails += check("zone " + slot + ", holding a computed map change, is no candidate", !stillOffered);
			}
			List<String> refs = ZoneRemover.referencesToAdded(pz.length, i -> i == 6 ? sixComputed : pz.getDecompressedEntry(i), shared);
			fails += check("the removal says zone 6's computed map change was not checked: " + refs,
					refs.contains("zone 6: 1 script map change(s) whose destination is computed are not checked"));
		}

		// (8) a script that does not read sends the player who knows where - never "nowhere": zone 6 with its script cut short
		byte[] six = pz.getDecompressedEntry(6);
		byte[] script = ctrmap.formats.containers.ContainerBytes.subfile(six, 2);
		final byte[] sixUnread = HandedGameTest.container("ZO", ctrmap.formats.containers.ContainerBytes.subfile(six, 0),
				ctrmap.formats.containers.ContainerBytes.subfile(six, 1), Arrays.copyOf(script, 16));
		ctrmap.ZoneRepurposeScanner.Scan unreadScan = ctrmap.ZoneRepurposeScanner.scanZones(536, 536, i -> i == 6 ? sixUnread : pz.getDecompressedEntry(i), parentMap -> "", shared);
		boolean scanSaid = false;
		for (String u : unreadScan.unreadable) {
			scanSaid |= u.startsWith("zone 6: its script does not read") && u.endsWith("so the zones it sends the player to are not counted");
		}
		fails += check("the scan says zone 6's script does not read: " + unreadScan.unreadable, scanSaid);
		boolean removalSaid = false;
		for (String r : ZoneRemover.referencesToAdded(pz.length, i -> i == 6 ? sixUnread : pz.getDecompressedEntry(i), shared)) {
			removalSaid |= r.startsWith("zone 6's script could not be read");
		}
		fails += check("the removal says zone 6's script could not be read", removalSaid);
		//and a SHARED script that does not read: shared script 6, cut the same way
		final byte[] sharedCut = Arrays.copyOf(sharedArc.getDecompressedEntry(6), 16);
		ctrmap.ZoneRepurposeScanner.SharedReach cutReach = ctrmap.ZoneRepurposeScanner.sharedReach(sharedArc.length,
				i -> i == 6 ? sharedCut : sharedArc.getDecompressedEntry(i));
		boolean sharedScanSaid = false, sharedRemovalSaid = false;
		for (String u : ctrmap.ZoneRepurposeScanner.scanZones(536, 536, pz::getDecompressedEntry, parentMap -> "", cutReach).unreadable) {
			sharedScanSaid |= u.startsWith("shared script 6: its script does not read") && u.endsWith("so the zones it sends the player to are not counted");
		}
		for (String r : ZoneRemover.referencesToAdded(pz.length, pz::getDecompressedEntry, cutReach)) {
			sharedRemovalSaid |= r.startsWith("shared script 6: its script does not read") && r.endsWith("so where it sends the player is not checked");
		}
		fails += check("the scan says shared script 6 does not read: " + cutReach.unread, sharedScanSaid);
		fails += check("the removal says shared script 6 does not read", sharedRemovalSaid);

		//measured by the 2026-10-12 sweep (ZoneRemover 149, dropped, SURVIVED): no removal was asked of a game whose
		//ZoneData is not open. Dropped, it went on to read a master table that is not there and failed on whatever
		//that threw - the one sentence saying why lost
		ctrmap.WorkspaceSession noZones = new ctrmap.WorkspaceSession(new File(tmpDir, "ws"), new File(tmpDir, "game"),
				ctrmap.gamedef.GameType.ORAS, new java.util.EnumMap<ctrmap.gamedef.ArchiveType, GARC>(ctrmap.gamedef.ArchiveType.class));
		String unopened;
		try {
			ZoneRemover.Removed r = ZoneRemover.remove(noZones);
			unopened = r == null ? "it answered no result at all" : "it removed " + r.zones + " zone(s)";
		} catch (java.io.IOException refused) {
			unopened = refused.getMessage();
		} catch (RuntimeException crashed) {
			unopened = "crashed: " + crashed;
		}
		fails += check("a removal from a game whose ZoneData is not open is refused, saying so: " + unopened,
				"ZoneData is not open, so there are no added zones to remove.".equals(unopened));

		//measured by the 2026-10-12 sweep (ZoneRemover 185, dropped, SURVIVED): no area id was ever given back to a game
		//whose NPC registries are not open. Dropped, the give-back failed on a null pointer, the sentence saying the ids
		//were not given back lost
		java.lang.reflect.Method giveBack = ZoneRemover.class.getDeclaredMethod("emptyRegistries",
				ctrmap.WorkspaceSession.class, List.class);
		giveBack.setAccessible(true);
		String noRegistries;
		try {
			giveBack.invoke(null, noZones, Arrays.asList(229));
			noRegistries = "it answered as if area 229 was given back";
		} catch (java.lang.reflect.InvocationTargetException thrown) {
			noRegistries = thrown.getCause() instanceof java.io.IOException ? thrown.getCause().getMessage()
					: "crashed: " + thrown.getCause();
		}
		fails += check("giving area ids back to a game whose NPC registries are not open is refused, saying so: "
				+ noRegistries, noRegistries.startsWith("The NPC registries are not open, so the area ids"));

		//measured by the 2026-10-12 sweep (ZoneRemover 193, dropped, SURVIVED): no registry the give-back could not read
		//was ever met. Dropped, an unreadable registry went on as nothing - a crash on the next one, or the archive
		//rewritten around an entry nobody read
		File regs = new File(tmpDir, "registries");
		HandedGameTest.writeGarc(regs, "registry 0".getBytes("US-ASCII"), "registry 1".getBytes("US-ASCII"));
		java.util.EnumMap<ctrmap.gamedef.ArchiveType, GARC> withRegs = new java.util.EnumMap<>(ctrmap.gamedef.ArchiveType.class);
		withRegs.put(ctrmap.gamedef.ArchiveType.NPC_REGISTRIES, new GARC(regs));
		ctrmap.WorkspaceSession unreadable = new ctrmap.WorkspaceSession(new File(tmpDir, "ws2"), new File(tmpDir, "game2"),
				ctrmap.gamedef.GameType.ORAS, withRegs);
		boolean gone = regs.delete();   //opened, then gone: no entry of it can be read now
		String unreadSaid;
		try {
			giveBack.invoke(null, unreadable, Arrays.asList(1));
			unreadSaid = "it answered as if area 1 was given back";
		} catch (java.lang.reflect.InvocationTargetException thrown) {
			unreadSaid = thrown.getCause() instanceof java.io.IOException ? thrown.getCause().getMessage()
					: "crashed: " + thrown.getCause();
		}
		fails += check("a registry the give-back cannot read refuses it, naming the registry, and changes nothing: "
				+ unreadSaid, gone && !regs.exists()
				&& "NPC registry 0 could not be read, so no area id was given back and nothing was changed.".equals(unreadSaid));

		System.out.println("rebuild+remove: identity 538, append 542, restore 538, " + fails + " failure(s)");
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The removal's file-level shrink, on a scratch copy: {@link ZoneRemover#stockLayout} written
	 * back. The product does this only inside {@link ZoneRemover#remove}, which also gives the
	 * removed zones' area ids back (F043) and needs a whole workspace; the layout is what this
	 * suite checks.
	 */
	static int shrink(File garc) throws Exception {
		GARC g = new GARC(garc);
		List<byte[]> stock = ZoneRemover.stockLayout(g);
		if (stock == null) {
			return 0;
		}
		GarcRebuilder.write(garc, garc, stock);
		return g.length - stock.size();
	}

	/**
	 * Zone 6 rebuilt with {@code edit} applied to its script at the constant pushed as the destination of its
	 * map change to zone 23 - the push two before the CALL of a wrapper that calls _MapChangeCore - or null
	 * when no such call site is found.
	 */
	static byte[] zoneSixsMapChangeTo23(GARC pz, java.util.function.BiConsumer<ctrmap.formats.scripts.GFLPawnScript, Integer> edit) throws Exception {
		byte[] six = pz.getDecompressedEntry(6);
		ctrmap.formats.scripts.GFLPawnScript s = new ctrmap.formats.scripts.GFLPawnScript(ctrmap.formats.containers.ContainerBytes.subfile(six, 2));
		s.decompressThis();
		for (int k = 2; k < s.instructions.size(); k++) {
			ctrmap.formats.scripts.PawnInstruction call = s.instructions.get(k);
			if (call.getCommand() != ctrmap.formats.scripts.PawnInstruction.Commands.CALL.ordinal()) {
				continue;
			}
			ctrmap.formats.scripts.PawnInstruction target = s.lookupInstructionByPtr(call.pointer + call.argumentCells[0]);
			ctrmap.formats.scripts.PawnInstruction first = s.instructions.get(k - 2);
			if (target != null && first.argumentCells.length > 0 && first.argumentCells[0] == 23 && callsMapChange(s, target)) {
				edit.accept(s, k - 2);
				return HandedGameTest.container("ZO", ctrmap.formats.containers.ContainerBytes.subfile(six, 0),
						ctrmap.formats.containers.ContainerBytes.subfile(six, 1), s.getScriptBytes());
			}
		}
		return null;
	}

	/** Does the subroutine at {@code entry} call _MapChangeCore before its next PROC? */
	static boolean callsMapChange(ctrmap.formats.scripts.GFLPawnScript s, ctrmap.formats.scripts.PawnInstruction entry) {
		for (int k = s.instructions.indexOf(entry) + 1; k < s.instructions.size(); k++) {
			ctrmap.formats.scripts.PawnInstruction a = s.instructions.get(k);
			if (a.getCommand() == ctrmap.formats.scripts.PawnInstruction.Commands.PROC.ordinal()) {
				return false;
			}
			if (a.getCommand() == ctrmap.formats.scripts.PawnInstruction.Commands.SYSREQ_N.ordinal() && a.argumentCells[0] < s.natives.size()
					&& s.natives.get(a.argumentCells[0]).data[1] == ctrmap.formats.scripts.ZoneScriptAnalyzer.MAP_CHANGE_CORE_NATIVE_HASH) {
				return true;
			}
		}
		return false;
	}

	static int check(String what, boolean ok) {
		if (!ok) {
			System.out.println("FAIL: " + what);
			return 1;
		}
		return 0;
	}
}

package ctrmap.tests;

import ctrmap.WorkspaceSession;
import ctrmap.ZoneLinker;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Connect zones is one change: both ends, or neither (F227).
 *
 * <p>WHY THIS SUITE EXISTS. A link wrote zone A's warp and then zone B's, each through its own
 * container and each staged for the next pack as it went. When B could not be written, A stayed
 * written: a one-way door, the outcome ZoneLinker's own javadoc calls worse than none. The two
 * writes now run in one WorkspaceUnit, and B failing puts A back.
 *
 * <p>ORDER: needs no dump. The workspace is a scratch folder holding two extracted zones, which
 * the session hands back without opening any archive.
 *
 * Usage: java ctrmap.tests.ZoneLinkUnitTest
 */
public class ZoneLinkUnitTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		aLinkThatWorksWritesBothEnds();
		aLinkWhoseSecondEndFailsLeavesTheFirstAsItWas();
		aLinkMadeInTheFormIsTakenBack();
		removingAWarpSaysWhichDoorsItBreaks(new File(args.length > 0 ? args[0] : "src"));
		theRemoveQuestionNamesTwentyAndCountsTheRest();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * Remove's question names the first twenty doors it breaks and counts the rest, and says no count when there is no
	 * rest (the 2026-10-15 shared sweep, WarpEditForm 231, negate-if, SURVIVED: every door list a suite made was short).
	 */
	static void theRemoveQuestionNamesTwentyAndCountsTheRest() {
		System.out.println("--- Remove's question names twenty doors and counts the rest");
		java.util.List<String> three = new java.util.ArrayList<>();
		java.util.List<String> many = new java.util.ArrayList<>();
		for (int i = 0; i < 25; i++) {
			many.add("zone " + i + " warp 0 leads to warp 2");
		}
		three.addAll(many.subList(0, 3));
		String few = ctrmap.humaninterface.WarpEditForm.removeQuestion(2, three);
		String lots = ctrmap.humaninterface.WarpEditForm.removeQuestion(2, many);
		check(few.contains("zone 2 warp 0") && !few.contains("more"),
				"three doors are all named, with no count after them");
		check(lots.contains("zone 19 warp 0") && !lots.contains("zone 20 warp 0") && lots.contains("... and 5 more"),
				"twenty-five: the first twenty named, and \"... and 5 more\": " + (lots.contains("more")
				? lots.substring(lots.indexOf("...")) : lots));
	}

	/** One warp, and an NPC whose uid is {@code npcUid} when {@code npcUid} is not negative. */
	static byte[] entities(int npcUid) {
		byte[] script = HandedGameTest.amx();
		int npcs = npcUid < 0 ? 0 : 1;
		byte[] e = new byte[12 + npcs * 0x30 + 0x18 + script.length];
		MapResizeTest.putI32(e, 0, 8 + npcs * 0x30 + 0x18);
		e[5] = (byte) npcs;
		e[6] = 1;
		if (npcs > 0) {
			MapResizeTest.putU16(e, 12, npcUid);
		}
		System.arraycopy(script, 0, e, 12 + npcs * 0x30 + 0x18, script.length);
		return e;
	}

	static WorkspaceSession workspace(String name, byte[] zone0, byte[] zone1) throws Exception {
		File root = Scratch.dir(name);
		File game = new File(root, "game");
		game.mkdirs();
		WorkspaceSession ws = Sessions.bare(new File(root, "ws"), game, GameType.ORAS);
		File zones = ws.getExtractionDirectory(ArchiveType.ZONE_DATA);
		zones.mkdirs();
		byte[] script = HandedGameTest.amx();
		Files.write(new File(zones, "0").toPath(), HandedGameTest.container("ZO", new byte[0x38], zone0, script));
		Files.write(new File(zones, "1").toPath(), HandedGameTest.container("ZO", new byte[0x38], zone1, script));
		return ws;
	}

	static ZoneEntities warpsOf(WorkspaceSession ws, int zone) {
		return new ZoneEntities(new ZO(ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zone), ws).getFile(1));
	}

	static ZoneEntities.Warp door(int zone, int warp) {
		ZoneEntities.Warp w = new ZoneEntities.Warp();
		w.targetZone = zone;
		w.targetWarpId = warp;
		return w;
	}

	/**
	 * Census H.39: removing a warp says first which doors it breaks. Every later warp is renumbered, so a door in
	 * any zone that led to one of them led one warp off from then on, or past the last - and the Warp form's
	 * Remove did it with no word; the user found out in game.
	 */
	static void removingAWarpSaysWhichDoorsItBreaks(File src) throws Exception {
		System.out.println("--- removing a warp says first which doors it breaks (census H.39)");
		java.util.Map<Integer, java.util.List<ZoneEntities.Warp>> zones = new java.util.HashMap<>();
		zones.put(3, Arrays.asList(door(5, 1), door(5, 2), door(5, 0), door(9, 1)));
		zones.put(5, Arrays.asList(door(3, 0), door(5, 1), door(5, 2)));
		ZoneLinker.WarpsOf of = z -> {
			if (z == 4) {
				throw new java.io.IOException("planted: zone 4 does not read");
			}
			return zones.get(z);
		};
		java.util.List<String> breaks = ZoneLinker.inboundAt(5, 1, 7, of);
		check(breaks.contains("zone 3 warp 0 leads to warp 1, the one removed")
				&& breaks.contains("zone 3 warp 1 leads to warp 2, which becomes warp 1")
				&& breaks.contains("zone 5 warp 2 leads to warp 2, which becomes warp 1"),
				"every door into the removed warp or a later one is named, in any zone and in its own: " + breaks);
		check(breaks.size() == 4 && breaks.get(2).startsWith("zone 4: its warps cannot be read"),
				"a door into an earlier warp, another zone or the removed warp itself is not, and a zone that cannot be"
				+ " read is said, never left out: " + breaks);
		check(ZoneLinker.inboundAt(5, 3, 7, z -> z == 4 ? null : zones.get(z)).isEmpty(),
				"control: past the last warp nothing is broken");
		byte[] oneWarp = entities(-1);
		WorkspaceSession ws = workspace("zonelink_inbound", oneWarp, oneWarp);
		ZoneEntities live = warpsOf(ws, 0);
		java.util.List<String> read = ZoneLinker.inboundAt(0, 0, 2, ZoneLinker.warpsNow(ws, 0, live));
		check(read.equals(Arrays.asList("zone 1 warp 0 leads to warp 0, the one removed")),
				"read from the workspace, the other zone's door into it is found, the open zone answering for itself: " + read);
		//measured by the 2026-10-12 sweep (ZoneLinker 104, negated, SURVIVED): the check above gives both zones the same
		//one warp, so which zone answered from the live edit and which from its file could not be told apart. Inverted,
		//the open zone answered from its file - its unsaved edits unseen - and every other zone answered with the open
		//zone's live warps
		ZoneEntities edited = warpsOf(ws, 0);
		edited.warps.get(0).targetZone = 7;     //an edit not yet saved: the file still says zone 0
		edited.warps.get(0).targetWarpId = 3;
		ZoneLinker.WarpsOf now = ZoneLinker.warpsNow(ws, 0, edited);
		java.util.List<ZoneEntities.Warp> open = now.warps(0);
		java.util.List<ZoneEntities.Warp> other = now.warps(1);
		check(open == edited.warps && other != edited.warps && other.get(0).targetZone == 0,
				"the open zone answers with its unsaved edit, and the other zone with its own file, never the open zone's"
				+ " (open leads to zone " + open.get(0).targetZone + ", other to zone " + other.get(0).targetZone + ")");
		//measured by the 2026-10-12 sweep (ZoneLinker 113, dropped, SURVIVED): no zone container without an entity block
		//was ever read. Dropped, the entity reader was handed nothing and failed on a null pointer, and the list of
		//doors a removal breaks said that zone could not be read because of a null pointer
		java.nio.file.Files.write(new File(ws.getExtractionDirectory(ArchiveType.ZONE_DATA), "1").toPath(),
				MapResizeTest.container(0x5a4f, new byte[ctrmap.MasterTable.ROW]));
		String unread;
		try {
			java.util.List<ZoneEntities.Warp> got = now.warps(1);
			unread = got == null ? "it answered no warps and no reason" : "it read " + got.size() + " warp(s)";
		} catch (java.io.IOException refused) {
			unread = refused.getMessage();
		} catch (RuntimeException crashed) {
			unread = "crashed: " + crashed;
		}
		check("its container holds no entity block".equals(unread),
				"a zone whose container holds no entity block is refused with that reason, not a null pointer: " + unread);
		//measured by the 2026-10-12 sweep (ZoneTables 122, dropped, SURVIVED): no zone that is neither in the workspace
		//nor in an archive was ever read. Dropped, it read as nothing, and the doors a removal breaks skipped it in
		//silence - a door from it into the removed warp never asked about. This workspace holds zones 0 and 1 and no
		//archive, so zone 2 is in neither
		java.util.List<String> past = ZoneLinker.inboundAt(0, 0, 3, now);
		check(past.contains("zone 2: its warps cannot be read (zone 2 is neither in the workspace nor readable from the"
				+ " archive), so whether a door there leads here is unknown"),
				"a zone that is in neither the workspace nor the archive is said as unknown, never skipped: " + past);
		String form = SourceSeamTest.stripComments(DialogSeamTest.read(new File(src, "ctrmap/humaninterface/WarpEditForm.java")));
		String remove = CollisionReadTest.body(form, "public void removeEntry()");
		int asked = remove == null ? -1 : remove.indexOf("ZoneLinker.inboundAt(");
		int confirmed = remove == null ? -1 : remove.indexOf("Ui.confirm(");
		int removed = remove == null ? -1 : remove.indexOf("e.warps.remove(");
		check(asked > 0 && confirmed > asked && removed > confirmed,
				"the Warp form's Remove reads what it breaks and asks before it removes (" + asked + ", " + confirmed + ", "
				+ removed + ")");
	}

	static void aLinkThatWorksWritesBothEnds() throws Exception {
		System.out.println("--- control: a link that works writes both ends (F227)");
		WorkspaceSession ws = workspace("ctrmap_link_ok", entities(-1), entities(0));
		String said = ZoneLinker.link(ws, 0, 0, 1, 0, true);
		ZoneEntities.Warp a = warpsOf(ws, 0).warps.get(0);
		ZoneEntities.Warp b = warpsOf(ws, 1).warps.get(0);
		check(a.targetZone == 1 && b.targetZone == 0 && said.contains("lead to each other"),
				"zone 0's warp leads to zone 1 and back (" + a.targetZone + ", " + b.targetZone + ") - the fixture can be written");
	}

	static void aLinkWhoseSecondEndFailsLeavesTheFirstAsItWas() throws Exception {
		System.out.println("--- a link whose second end cannot be written leaves the first as it was (F227)");
		WorkspaceSession ws = workspace("ctrmap_link_half", entities(-1), entities(5));
		File first = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, 0);
		byte[] before = Files.readAllBytes(first.toPath());
		String refused;
		try {
			ZoneLinker.link(ws, 0, 0, 1, 0, true);
			refused = "nothing was refused";
		} catch (Exception ex) {
			refused = String.valueOf(ex.getMessage());
		}
		check(refused.contains("uid"), "control: zone 1's misnumbered NPC refuses to be written: " + refused);
		check(Arrays.equals(before, Files.readAllBytes(first.toPath())),
				"zone 0 is byte for byte as it was - its warp was written first and stayed, a one-way door");
		check(!ws.persistPaths().contains(first.getAbsolutePath()),
				"and it is not staged for the next pack either");
	}

	/**
	 * Connect zones is kept (F209c): its Connect button links both ends as one change of the history it is handed, and
	 * Undo puts both zones back byte for byte and reads the zones again; Redo links them again.
	 */
	static void aLinkMadeInTheFormIsTakenBack() throws Exception {
		System.out.println("--- a link made in the Connect zones form is taken back and put again (F209c)");
		WorkspaceSession ws = workspace("ctrmap_link_kept", entities(-1), entities(0));
		File zero = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, 0);
		File one = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, 1);
		byte[] zeroWas = Files.readAllBytes(zero.toPath());
		byte[] oneWas = Files.readAllBytes(one.toPath());
		ctrmap.LoadedZone zones = new ctrmap.LoadedZone();
		zones.table(new ctrmap.formats.zone.Zone[]{new ctrmap.formats.zone.Zone(new ZO(zero, ws), GameType.ORAS),
			new ctrmap.formats.zone.Zone(new ZO(one, ws), GameType.ORAS)});
		ctrmap.humaninterface.SaveHistory ops = new ctrmap.humaninterface.SaveHistory(
				() -> java.util.Collections.<Object>singletonList(ws));
		int[] reread = {0};
		javax.swing.JComponent form = ctrmap.humaninterface.ZoneLinkDialog.panel(null, ws, zones, () -> { }, ops,
				() -> reread[0]++);
		javax.swing.JButton connect = button(form, "Connect");
		java.util.List<String> said = ctrmap.Ui.record(javax.swing.JOptionPane.OK_OPTION);   //"connect anyway", over the fixture's self-warp
		javax.swing.SwingUtilities.invokeAndWait(connect::doClick);
		ctrmap.Ui.stopRecording();
		check(warpsOf(ws, 0).warps.get(0).targetZone == 1 && warpsOf(ws, 1).warps.get(0).targetZone == 0
				&& ops.history().canUndo(),
				"control: the form's Connect links zone 0 and zone 1, kept in the history it was handed: " + said);
		ops.history().undo();
		check(Arrays.equals(zeroWas, Files.readAllBytes(zero.toPath())) && Arrays.equals(oneWas, Files.readAllBytes(one.toPath()))
				&& reread[0] == 1,
				"Undo puts both zones back byte for byte and reads the zones again (read " + reread[0] + ")");
		ops.history().redo();
		check(warpsOf(ws, 0).warps.get(0).targetZone == 1 && warpsOf(ws, 1).warps.get(0).targetZone == 0 && reread[0] == 2,
				"and Redo links them again (read " + reread[0] + ")");
	}

	/** The button in {@code in} that says {@code text}, or null. */
	static javax.swing.JButton button(java.awt.Container in, String text) {
		for (java.awt.Component c : in.getComponents()) {
			if (c instanceof javax.swing.JButton && text.equals(((javax.swing.JButton) c).getText())) {
				return (javax.swing.JButton) c;
			}
			if (c instanceof java.awt.Container) {
				javax.swing.JButton found = button((java.awt.Container) c, text);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
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

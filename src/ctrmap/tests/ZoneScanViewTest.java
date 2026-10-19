package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.ZoneRemover;
import ctrmap.ZoneRepurposeScanner;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import static ctrmap.formats.containers.ContainerBytes.subfile;

/**
 * The zone-safety scans judge the workspace's zones as they are now, and say which they could not
 * read (F225).
 *
 * <p>WHY THIS SUITE EXISTS. "Find reusable base zones" and "Remove added zones" read ZoneData from
 * the game folder - the game as of the last pack. A warp the user had added since, into a zone, did
 * not count, so that zone was offered as safe to overwrite; a warp added since into an added zone
 * was missing from the dangling-warp warning. A zone whose entities would not read was skipped by
 * the remover, and a zone whose entity block was missing counted as empty - the safest tier.
 *
 * <p>ORDER: needs no dump. The workspace holds hand-made zones.
 *
 * Usage: java ctrmap.tests.ZoneScanViewTest [src root]
 */
public class ZoneScanViewTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		File ws = Scratch.dir("ctrmap_zonescan_ws");
		File game = Scratch.dir("ctrmap_zonescan_game");
		try {
			GARC zo = (GARC) RenderLoopTest.bare(GARC.class);
			zo.length = 6;
			WorkspaceSession s = new WorkspaceSession(ws, game, GameType.ORAS,
					Collections.singletonMap(ArchiveType.ZONE_DATA, zo));
			Workspace.install(s);
			theReusableZoneScanReadsTheWorkspace(s, ws);
			everyZonesDoorsCountAndAnUnreadNameIsNoPlaceholder();
			theRemovalWarningReadsTheWorkspace(s, ws);
			theSharedScriptsAreSaidWhenNotRead(s, ws, game);
			theActionsAskTheWorkspace(root);
			theParentMapHasOneSpelling(root);
		} finally {
			Workspace.reset();
			Scratch.deleteTree(ws);
			Scratch.deleteTree(game);
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** An empty zone, as HandedGameTest builds one. */
	static byte[] empty() {
		return HandedGameTest.zoneBytes();
	}

	/** An empty zone with one warp into {@code target}. */
	static byte[] warpingTo(int target) throws Exception {
		byte[] z = empty();
		ZoneEntities e = new ZoneEntities(subfile(z, 1));
		ZoneEntities.Warp w = new ZoneEntities.Warp();
		w.targetZone = target;
		w.targetWarpId = 0;
		e.warps.add(w);
		return HandedGameTest.container("ZO", subfile(z, 0), e.assembleData(), subfile(z, 2));
	}

	/** A zone whose entity block says it holds a warp and is cut short before it. */
	static byte[] cutShort() throws Exception {
		byte[] z = warpingTo(2);
		byte[] ent = subfile(z, 1);
		return HandedGameTest.container("ZO", subfile(z, 0), java.util.Arrays.copyOf(ent, 14), subfile(z, 2));
	}

	static void stage(File ws, int i, byte[] zone) throws Exception {
		File dir = WorkspaceSession.extractionDirectory(ws, ArchiveType.ZONE_DATA);
		dir.mkdirs();
		Files.write(new File(dir, String.valueOf(i)).toPath(), zone);
	}

	static boolean listed(List<ZoneRepurposeScanner.Candidate> cands, int zone) {
		for (ZoneRepurposeScanner.Candidate c : cands) {
			if (c.index == zone) {
				return true;
			}
		}
		return false;
	}

	static ZoneRepurposeScanner.Candidate candidate(ZoneRepurposeScanner.Scan scan, int zone) {
		for (ZoneRepurposeScanner.Candidate c : scan.candidates) {
			if (c.index == zone) {
				return c;
			}
		}
		return null;
	}

	/**
	 * Census H.57: the reusable-zone scan counts the doors of every zone the game holds, and a name it cannot read
	 * is no placeholder. It counted the base zones' doors alone, so a door from an added zone into a base zone did
	 * not count; and a name lookup that threw answered "", the placeholder's own look, so the zone was offered as
	 * the SAFEST to overwrite.
	 */
	static void everyZonesDoorsCountAndAnUnreadNameIsNoPlaceholder() throws Exception {
		System.out.println("--- a door from an added zone counts, and a name nobody could read is no placeholder (census H.57)");
		byte[][] zones = {empty(), empty(), empty(), empty(), empty(), empty(), warpingTo(3)};
		ZoneRepurposeScanner.SharedReach none = new ZoneRepurposeScanner.SharedReach();
		check(listed(ZoneRepurposeScanner.scanZones(6, 6, i -> zones[i], parentMap -> "", none).candidates, 3),
				"control: asked of the six base zones alone, zone 3 has no door into it");
		check(!listed(ZoneRepurposeScanner.scanZones(6, 7, i -> zones[i], parentMap -> "", none).candidates, 3),
				"asked of every zone the game holds, the added zone 6's door into zone 3 counts");
		ZoneRepurposeScanner.Candidate named = candidate(ZoneRepurposeScanner.scanZones(6, 6, i -> zones[i], parentMap -> "", none), 2);
		check(named != null && named.tier == 0, "control: an empty zone with an empty name is the safest tier");
		ZoneRepurposeScanner.Scan unnamed = ZoneRepurposeScanner.scanZones(6, 6, i -> zones[i], parentMap -> {
			throw new IllegalStateException("planted: the names do not read");
		}, none);
		ZoneRepurposeScanner.Candidate unread = candidate(unnamed, 2);
		check(unread != null && unread.tier != 0, "a zone whose name cannot be read is not the safest tier ("
				+ (unread == null ? "not listed" : "tier " + unread.tier) + ")");
		check(unnamed.unreadable.contains("zone 2: its name cannot be read (planted: the names do not read), so it is not"
				+ " taken for a placeholder"), "and that is said: " + unnamed.unreadable);
	}

	static void theReusableZoneScanReadsTheWorkspace(WorkspaceSession s, File ws) throws Exception {
		System.out.println("--- 'Find reusable zones' reads the workspace's zones, unpacked edits included (F225)");
		byte[][] packed = {empty(), empty(), empty(), empty(), empty(), empty()};
		ZoneRepurposeScanner.Scan asPacked = ZoneRepurposeScanner.scanZones(6, 6, i -> packed[i], parentMap -> "", new ZoneRepurposeScanner.SharedReach());
		check(listed(asPacked.candidates, 3), "control: as last packed, zone 3 has no incoming warp and is a candidate");

		stage(ws, 0, empty());
		stage(ws, 1, warpingTo(3));   //the user's unpacked edit: a warp into zone 3
		stage(ws, 2, empty());
		stage(ws, 3, empty());
		stage(ws, 4, cutShort());
		stage(ws, 5, HandedGameTest.container("ZO", new byte[0x38]));
		ZoneRepurposeScanner.Scan now = ZoneRepurposeScanner.scanZones(s);
		check(!listed(now.candidates, 3), "with a warp added into it since, zone 3 is no candidate - it was offered as safe to overwrite");
		check(listed(now.candidates, 2), "and zone 2, still unreferenced, still is");
		check(!listed(now.candidates, 4) && now.unreadable.toString().contains("zone 4"),
				"a zone whose entities do not read is no candidate, and is named: " + now.unreadable);
		check(!listed(now.candidates, 5) && now.unreadable.toString().contains("zone 5: its container holds no entity block"),
				"nor is one with no entity block - it counted as empty, the safest tier: " + now.unreadable);
	}

	static void theRemovalWarningReadsTheWorkspace(WorkspaceSession s, File ws) throws Exception {
		System.out.println("--- 'Remove added zones' warns from the workspace's zones, and names those it could not read (F225)");
		stage(ws, 2, warpingTo(540));   //an unpacked warp into an added zone
		List<String> refs = ZoneRemover.referencesToAdded(s);
		check(refs.contains("zone 2 warp 0 -> added zone 540"),
				"a warp added since into an added zone is in the warning - it was missing: " + refs);
		boolean saysFour = false;
		boolean saysFive = false;
		for (String r : refs) {
			saysFour |= r.startsWith("zone 4") && r.contains("not checked");
			saysFive |= r.startsWith("zone 5") && r.contains("not checked");
		}
		check(saysFour && saysFive, "and the zones it could not read are named, not skipped: " + refs);
	}

	/**
	 * The scripts no zone owns send the player to zones too (F225). A workspace whose shared-script archive
	 * did not open - this one has only ZoneData - and a game where nobody has measured where it is, are both
	 * SAID, by the scan and by the removal: neither is a game whose shared scripts send the player nowhere.
	 */
	static void theSharedScriptsAreSaidWhenNotRead(WorkspaceSession s, File ws, File game) throws Exception {
		System.out.println("--- shared scripts nothing could read are said, never read as going nowhere (F225)");
		check(s.getArchive(ArchiveType.SHARED_SCRIPTS) == null, "control: this workspace's shared-script archive is not open");
		String unopened = "the shared-script archive: their archive did not open";
		check(ZoneRepurposeScanner.scanZones(s).unreadable.contains(unopened + ", so the zones it sends the player to are not counted"),
				"the scan says the shared scripts were not read: " + ZoneRepurposeScanner.scanZones(s).unreadable);
		check(ZoneRemover.referencesToAdded(s).contains(unopened + ", so where it sends the player is not checked"),
				"and the removal says it: " + ZoneRemover.referencesToAdded(s));
		GARC zo = (GARC) RenderLoopTest.bare(GARC.class);
		zo.length = 6;
		WorkspaceSession xy = new WorkspaceSession(ws, game, GameType.XY, Collections.singletonMap(ArchiveType.ZONE_DATA, zo));
		check(xy.profile().archivePath(ArchiveType.SHARED_SCRIPTS) == null, "control: where X/Y keeps its shared scripts is not measured");
		check(ZoneRepurposeScanner.sharedReach(xy).unread.contains("the shared-script archive: where this game keeps them is not measured"),
				"on X/Y the shared scripts are said unmeasured: " + ZoneRepurposeScanner.sharedReach(xy).unread);
	}

	static void theActionsAskTheWorkspace(File root) throws Exception {
		System.out.println("--- the two actions and the painter's slot search ask the workspace (F225)");
		String mf = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/CtrmapMainframe.java")));
		check(mf.contains("ZoneRemover.referencesToAdded(Workspace.session())") && mf.contains("ZoneRepurposeScanner.scanZones(session)")
				&& mf.contains("new javax.swing.SwingWorker<ZoneRepurposeScanner.Scan, Void>()"),
				"the removal warning and the reusable-zone scan read the session, the scan off the event thread");
		String tp = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/MapPainter.java")));
		check(tp.contains("!edited.contains(new File(zoDir, String.valueOf(c.index)).getAbsolutePath())"),
				"and the painter's interior slots skip zones EDITED this session, not every zone with a file");
	}

	/** The word at 0x1C holds the parent map in its low 10 bits and the OL value above them (lead 55). */
	static void theParentMapHasOneSpelling(File root) throws Exception {
		System.out.println("--- a zone header's parent map is read one way (F225, lead 55)");
		byte[] hdr = new byte[ctrmap.formats.zone.ZoneHeader.SIZE];
		int word = (0x2A << 10) | 0x155;
		hdr[0x1C] = (byte) word;
		hdr[0x1D] = (byte) (word >> 8);
		ctrmap.formats.zone.ZoneHeader parsed = new ctrmap.formats.zone.ZoneHeader(hdr, GameType.ORAS);
		check(ctrmap.formats.zone.ZoneHeader.parentMapOf(hdr) == 0x155 && parsed.parentMap == 0x155,
				"the low 10 bits of 0x" + Integer.toHexString(word) + ", by the reader and by the header's own parse: "
				+ Integer.toHexString(ctrmap.formats.zone.ZoneHeader.parentMapOf(hdr)) + ", " + Integer.toHexString(parsed.parentMap));
		java.util.List<String> spelled = new java.util.ArrayList<>();
		for (File f : CollisionReadTest.javaFiles(new File(root, "ctrmap"), new java.util.ArrayList<>())) {
			if (f.getPath().contains(File.separator + "tests" + File.separator) || f.getName().equals("ZoneHeader.java")) {
				continue;
			}
			if (SourceSeamTest.stripComments(DialogSeamTest.read(f)).matches("(?s).*0x1[cC]\\)\\s*&\\s*0x3[fF]{2}.*")) {
				spelled.add(f.getName());
			}
		}
		check(spelled.isEmpty(), "and nothing else spells the field itself: " + spelled);
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

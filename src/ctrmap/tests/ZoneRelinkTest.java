package ctrmap.tests;

import ctrmap.GeometryForker;
import ctrmap.MasterTable;
import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.WorkspaceOps;
import ctrmap.WorkspaceSession;
import ctrmap.ZoneRelinker;
import ctrmap.ZoneResource;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JOptionPane;

/**
 * Re-link a zone - the feature the owner asked for on 2026-09-11, in their words: "add a feature to re-link/connect
 * zones, if for any reason the user would want to do that (say they accidentally unlink a vanilla zone that they want
 * to relink back to its original other vanilla zones it was linked to)".
 *
 * <p>WHAT WAS BUILT INSTEAD, and why this suite reads the request back: the next day's commit wired a warp in one zone
 * to a warp in another, called it Connect zones, and said it was this request. Every suite was green, because every
 * suite asked whether the warp connector did what its own author meant. This one asks the owner's question: a zone
 * unlinked from the vanilla zones it shipped sharing with goes back to them, header AND master row, kept in Undo.
 *
 * <p>Runs on a scratch copy of the game, with the pristine snapshot the editor takes on first open made by hand from
 * that copy - the snapshot is what "as shipped" is read from.
 *
 * Usage: java ctrmap.tests.ZoneRelinkTest &lt;romfs-root&gt; [&lt;whole-game&gt;]
 */
public class ZoneRelinkTest {

	static int fails = 0;
	/** Fallarbor Town: shares its map matrix with Routes 111-114 in the game as shipped. */
	static final int SHARED_ZONE = 10;
	/** Two zones with different retail areas. */
	static final int[] ZONES = {15, 20};

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		File whole = args.length > 1 ? new File(args[1]) : null;
		if (!dump.isDirectory()) {
			Skip.section("no dump at " + dump);
			System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
			return;
		}
		File[] also = whole == null ? new File[0] : new File[]{whole};
		anUnlinkedZoneGoesBackToWhatItShippedSharing(dump, also);
		aZoneSharesAnotherZonesArea(dump, also);
		aRelinkThatCannotBeDoneWritesNothing(dump, also);
		thePanelsButtonsDoIt(dump, also);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** A fresh scratch game with its pristine snapshot of ZoneData, as the editor takes it on first open. */
	static WorkspaceSession game(File dump, File[] also) throws Exception {
		ScratchGame.open(dump, also);
		WorkspaceSession ws = Workspace.session();
		File live = ws.archiveFile(ArchiveType.ZONE_DATA);
		File snap = new File(WorkspaceSession.originalSnapshotDir(ws.workspaceDir()).getAbsolutePath()
				+ ws.profile().archivePath(ArchiveType.ZONE_DATA));
		snap.getParentFile().mkdirs();
		Files.copy(live.toPath(), snap.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		return ws;
	}

	static ctrmap.humaninterface.SaveHistory ops(WorkspaceSession ws) {
		return new ctrmap.humaninterface.SaveHistory(() -> java.util.Collections.<Object>singletonList(ws));
	}

	static int header(WorkspaceSession ws, int zone, ZoneResource res) throws IOException {
		return res.idIn(Files.readAllBytes(ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zone).toPath()));
	}

	static Map<ZoneResource, Integer> one(ZoneResource res, int id) {
		Map<ZoneResource, Integer> m = new EnumMap<>(ZoneResource.class);
		m.put(res, id);
		return m;
	}

	/** THE OWNER'S CASE: a vanilla zone made independent, re-linked back to the vanilla zones it was linked to. */
	static void anUnlinkedZoneGoesBackToWhatItShippedSharing(File dump, File[] also) throws Exception {
		System.out.println("--- a vanilla zone made independent goes back to the vanilla zones it shipped sharing with");
		WorkspaceSession ws = game(dump, also);
		MasterTable shipped = ZoneRelinker.shipped(ws);
		check(shipped != null, "CONTROL: the game as shipped is read from the pristine snapshot");
		if (shipped == null) {
			return;
		}
		int vanillaMap = shipped.id(ZoneResource.MAP, SHARED_ZONE);
		List<Integer> vanillaSiblings = shipped.sharing(ZoneResource.MAP, SHARED_ZONE);
		check(!vanillaSiblings.isEmpty(), "CONTROL: zone " + SHARED_ZONE + " shipped sharing map " + vanillaMap
				+ " with " + vanillaSiblings);
		GeometryForker.ensurePrivate(SHARED_ZONE);
		int own = MasterTable.read(ws).id(ZoneResource.MAP, SHARED_ZONE);
		check(own != vanillaMap && MasterTable.read(ws).sharing(ZoneResource.MAP, SHARED_ZONE).isEmpty(),
				"CONTROL: made independent, it has map " + own + " of its own - unlinked");

		//AS SHIPPED IS READ AGAIN, AFTER the zone was made independent: the copy read above was taken before it, so it
		//matches today's table as well as the pristine one and could not tell the two apart
		ZoneRelinker.Link map = ZoneRelinker.links(ws, SHARED_ZONE, ZoneRelinker.shipped(ws)).get(ZoneResource.MAP.ordinal());
		check(map.res == ZoneResource.MAP && map.differsFromShipped() && map.shipped == vanillaMap,
				"the zone's links say its map is " + map.now + " now and was " + map.shipped + " as shipped");

		ctrmap.humaninterface.SaveHistory ops = ops(ws);
		int[] reread = {0};
		String said = WorkspaceOps.relink(ws, SHARED_ZONE, one(ZoneResource.MAP, vanillaMap), ops, () -> reread[0]++);
		MasterTable now = MasterTable.read(ws);
		check(header(ws, SHARED_ZONE, ZoneResource.MAP) == vanillaMap && now.id(ZoneResource.MAP, SHARED_ZONE) == vanillaMap,
				"re-linked, its header AND its master row name map " + vanillaMap + " again");
		check(now.sharing(ZoneResource.MAP, SHARED_ZONE).equals(vanillaSiblings),
				"and it shares that map with exactly the zones it shipped sharing with " + vanillaSiblings);
		check(said.contains("used by no zone") && said.contains(String.valueOf(own)),
				"and it says its own map " + own + " is left unused: " + said.replace('\n', ' '));
		ops.history().undo();
		check(header(ws, SHARED_ZONE, ZoneResource.MAP) == own && MasterTable.read(ws).id(ZoneResource.MAP, SHARED_ZONE) == own,
				"Undo puts it back on its own map " + own + ", header and master row");
		//READ AGAIN ON UNDO AND REDO, as every kept operation is: the window reopens the open zone itself right after
		//a re-link (ZoneWrittenElsewhereTest holds that), and the history reads the zones again when it moves
		check(reread[0] == 1, "and Undo reads the zones again (" + reread[0] + ")");
		ops.history().redo();
		check(header(ws, SHARED_ZONE, ZoneResource.MAP) == vanillaMap
				&& MasterTable.read(ws).id(ZoneResource.MAP, SHARED_ZONE) == vanillaMap, "and Redo re-links it");
	}

	/** Sharing another zone's: the zone names what the other names now, in both places. */
	static void aZoneSharesAnotherZonesArea(File dump, File[] also) throws Exception {
		System.out.println("--- a zone shares another zone's area");
		WorkspaceSession ws = game(dump, also);
		int theirs = MasterTable.read(ws).id(ZoneResource.AREA, ZONES[1]);
		check(MasterTable.read(ws).id(ZoneResource.AREA, ZONES[0]) != theirs,
				"CONTROL: zones " + ZONES[0] + " and " + ZONES[1] + " start on different areas");
		WorkspaceOps.relink(ws, ZONES[0], one(ZoneResource.AREA, theirs), ops(ws), () -> { });
		check(header(ws, ZONES[0], ZoneResource.AREA) == theirs && MasterTable.read(ws).id(ZoneResource.AREA, ZONES[0]) == theirs
				&& MasterTable.read(ws).sharing(ZoneResource.AREA, ZONES[0]).contains(ZONES[1]),
				"zone " + ZONES[0] + " names area " + theirs + " in its header and master row, shared with zone " + ZONES[1]);
	}

	/** Refused in words, before anything is written. */
	static void aRelinkThatCannotBeDoneWritesNothing(File dump, File[] also) throws Exception {
		System.out.println("--- a re-link that cannot be done is refused, and writes nothing");
		WorkspaceSession ws = game(dump, also);
		File zo = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, ZONES[0]);
		byte[] zoWas = Files.readAllBytes(zo.toPath());
		byte[] masterWas = MasterTable.read(ws).bytes().clone();
		int areas = ws.reach(ArchiveType.AREA_DATA, ws.getArchive(ArchiveType.AREA_DATA).length);
		Object[][] cases = {
			{ZoneResource.AREA, ctrmap.AreaForker.AD_GLOBAL_TABLE, "per-area table"},
			{ZoneResource.AREA, areas, "There is no area " + areas},
			{ZoneResource.MAP, 70000, "0 to 65535"},
		};
		for (Object[] c : cases) {
			String refused = null;
			try {
				WorkspaceOps.relink(ws, ZONES[0], one((ZoneResource) c[0], (Integer) c[1]), ops(ws), () -> { });
			} catch (IOException ex) {
				refused = ex.getMessage();
			}
			check(refused != null && refused.contains((String) c[2]) && refused.contains("Nothing was changed"),
					c[0] + " " + c[1] + " is refused in words: " + refused);
		}
		check(Arrays.equals(Files.readAllBytes(zo.toPath()), zoWas) && Arrays.equals(MasterTable.read(ws).bytes(), masterWas),
				"and the zone's header and the master table are exactly as they were");
	}

	/** The panel the zone column shows: its way back to as shipped, pressed, re-links; the All button does every one. */
	static void thePanelsButtonsDoIt(File dump, File[] also) throws Exception {
		System.out.println("--- the Re-link zone panel's buttons re-link, through what the window hands it");
		WorkspaceSession ws = game(dump, also);
		MasterTable shipped = ZoneRelinker.shipped(ws);
		int vanillaMap = shipped.id(ZoneResource.MAP, SHARED_ZONE);
		int vanillaArea = shipped.id(ZoneResource.AREA, SHARED_ZONE);
		GeometryForker.ensurePrivate(SHARED_ZONE);
		WorkspaceOps.relink(ws, SHARED_ZONE, one(ZoneResource.AREA, MasterTable.read(ws).id(ZoneResource.AREA, ZONES[1])),
				ops(ws), () -> { });
		check(MasterTable.read(ws).id(ZoneResource.MAP, SHARED_ZONE) != vanillaMap
				&& MasterTable.read(ws).id(ZoneResource.AREA, SHARED_ZONE) != vanillaArea,
				"CONTROL: zone " + SHARED_ZONE + "'s map and area both differ from what it shipped with");

		//ONE PARSED ZONE IN EVERY SLOT, as MatrixEditFormGuardsTest does: the panel asks the table for its count and
		//for names, and a table with a hole is refused (F208)
		ctrmap.LoadedZone zones = new ctrmap.LoadedZone();
		ctrmap.formats.zone.Zone any = new ctrmap.formats.zone.Zone(new ctrmap.formats.containers.ZO(
				ws.getWorkspaceFile(ArchiveType.ZONE_DATA, 0), ws), ctrmap.gamedef.GameType.ORAS);
		ctrmap.formats.zone.Zone[] table = new ctrmap.formats.zone.Zone[MasterTable.read(ws).zones()];
		Arrays.fill(table, any);
		zones.table(table);
		ctrmap.humaninterface.SaveHistory ops = ops(ws);
		java.util.List<Map<ZoneResource, Integer>> asked = new java.util.ArrayList<>();
		javax.swing.JComponent[] panel = new javax.swing.JComponent[1];
		javax.swing.SwingUtilities.invokeAndWait(() -> panel[0] = ctrmap.humaninterface.ZoneRelinkPanel.panel(null, ws, zones,
				(zone, to) -> {
					asked.add(to);
					return WorkspaceOps.relink(ws, zone, to, ops, () -> { });
				}));
		check(panel[0] != null, "CONTROL: the panel is built over the scratch game");
		if (panel[0] == null) {
			return;
		}
		javax.swing.JSpinner zone = first(panel[0], javax.swing.JSpinner.class);
		javax.swing.SwingUtilities.invokeAndWait(() -> zone.setValue(SHARED_ZONE));
		JButton mapBack = backFor(panel[0], ZoneResource.MAP);
		check(mapBack != null && mapBack.isEnabled(), "the map row's '" + ctrmap.humaninterface.ZoneRelinkPanel.BACK
				+ "' is offered");
		List<String> said = Ui.record(JOptionPane.OK_OPTION);
		try {
			if (mapBack != null) {
				javax.swing.SwingUtilities.invokeAndWait(mapBack::doClick);
			}
		} finally {
			Ui.stopRecording();
		}
		check(asked.size() == 1 && asked.get(0).equals(one(ZoneResource.MAP, vanillaMap))
				&& MasterTable.read(ws).id(ZoneResource.MAP, SHARED_ZONE) == vanillaMap,
				"pressed, it re-links the map to " + vanillaMap + ": " + asked + " " + said);
		JButton all = button(panel[0], ctrmap.humaninterface.ZoneRelinkPanel.ALL_BACK);
		check(all != null && all.isEnabled(), "'" + ctrmap.humaninterface.ZoneRelinkPanel.ALL_BACK + "' is offered while the area differs");
		said = Ui.record(JOptionPane.OK_OPTION);
		try {
			if (all != null) {
				javax.swing.SwingUtilities.invokeAndWait(all::doClick);
			}
		} finally {
			Ui.stopRecording();
		}
		check(MasterTable.read(ws).id(ZoneResource.AREA, SHARED_ZONE) == vanillaArea
				&& header(ws, SHARED_ZONE, ZoneResource.AREA) == vanillaArea,
				"and All back to as shipped puts the area back too: " + said);
	}

	static JButton backFor(java.awt.Container in, ZoneResource res) {
		for (java.awt.Component c : in.getComponents()) {
			if (c instanceof JButton && ctrmap.humaninterface.ZoneRelinkPanel.BACK.equals(((JButton) c).getText())
					&& ((JButton) c).getClientProperty(ZoneResource.class) == res) {
				return (JButton) c;
			}
			if (c instanceof java.awt.Container) {
				JButton b = backFor((java.awt.Container) c, res);
				if (b != null) {
					return b;
				}
			}
		}
		return null;
	}

	static JButton button(java.awt.Container in, String text) {
		for (java.awt.Component c : in.getComponents()) {
			if (c instanceof JButton && text.equals(((JButton) c).getText())) {
				return (JButton) c;
			}
			if (c instanceof java.awt.Container) {
				JButton b = button((java.awt.Container) c, text);
				if (b != null) {
					return b;
				}
			}
		}
		return null;
	}

	static <T> T first(java.awt.Container in, Class<T> type) {
		for (java.awt.Component c : in.getComponents()) {
			if (type.isInstance(c)) {
				return type.cast(c);
			}
			if (c instanceof java.awt.Container) {
				T t = first((java.awt.Container) c, type);
				if (t != null) {
					return t;
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

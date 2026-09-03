package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GRCollisionFile;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.npcreg.NPCRegistry;
import ctrmap.formats.zone.Zone;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.humaninterface.NPCEditForm;
import ctrmap.humaninterface.Selector;
import ctrmap.humaninterface.TileMapPanel;
import ctrmap.humaninterface.ZoneLoadingPanel;
import ctrmap.humaninterface.tools.NPCTool;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JSpinner;

/**
 * Drives the real NPC editor form - Save, Remove entry, New entry, the NPC
 * dropdown, the Dialogue note, the map overlay and the drag tool - with no
 * window and no 3D panel, against retail zone 24. NpcEntityGuardsTest proves
 * the record-level helpers; mutation testing showed a form that stopped
 * calling them stayed green. Each check here is one of those holes.
 *
 * <ol>
 * <li>"Remove entry" deleted from a List&lt;H3DModel&gt; kept parallel to the
 *     NPC list, by object. NPCs sharing a MoveModel share the cached
 *     instance, so the FIRST slot with that model went and every later NPC
 *     drew as its neighbour. Zone 24's last NPC shares model 218 with NPCs 4
 *     and 7. The old guard was a regex on the field's name, which a renamed
 *     list with the same bug passed; this one deletes NPC 8 through the form
 *     and asks which model stands on each survivor's tile.</li>
 * <li>Save accepted a script id the zone's dispatch does not define, and the
 *     NPC did nothing in game. The helper was guarded; the form's call sites
 *     - Save, the NPC dropdown (which saves first), the Dialogue note - were
 *     not. Each is driven here, and the refusal must reach the user through
 *     ctrmap.Ui, not just happen.</li>
 * <li>New entry past 255 NPCs wrote a count byte of 0 and made the zone
 *     unloadable; the form refuses and says so.</li>
 * <li>The map overlay boxed the NPC whose uid equals the selection index,
 *     and the drag tool assigned the collision height raw - NaN on the lower
 *     half of a map taller than wide. The box must follow position, and a
 *     drag where the mesh has no answer keeps the altitude.</li>
 * </ol>
 *
 * Usage: java ctrmap.tests.NpcEditFormGuardsTest &lt;pristine dump root&gt;
 */
public class NpcEditFormGuardsTest {

	static int fails = 0;
	/** Nine NPCs, dispatch cases 1..10, NPC 0 on script 7; the last NPC shares model 218 with NPCs 4 and 7. */
	static final int ZONE = 24;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump + " - the form checks need zone 24");
		} else {
			Workspace.game = Workspace.GameType.ORAS;
			Workspace.GAMEDIR_PATH = dump.getAbsolutePath();
			Workspace.valid = true; //the form only looks up its zone inside a workspace
			GARC zo = new GARC(new File(Workspace.GAMEDIR_PATH + Workspace.getArchivePath(Workspace.ArchiveType.ZONE_DATA, Workspace.game)));
			GARC gr = new GARC(new File(Workspace.GAMEDIR_PATH + Workspace.getArchivePath(Workspace.ArchiveType.FIELD_DATA, Workspace.game)));
			removeKeepsModels(zo, gr);
			saveRefusesUndefinedScript(zo);
			saveWithNothingSelected(zo);
			dialogueNoteNamesUndefinedScript(zo);
			newEntryStopsAtTheCeiling(zo);
			overlayFollowsPosition(zo);
			dragKeepsAltitudeOffTheMesh(zo);
		}
		altitudeFromMesh();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * Remove entry on zone 24's last NPC. Afterwards every survivor must be
	 * drawn with the model its record names, on its own tile - the viewport's
	 * only binding is updateH3D placing a model at an NPC's position.
	 */
	static void removeKeepsModels(GARC zo, GARC gr) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		int last = e.npcs.size() - 1;
		ZoneEntities.NPC gone = e.npcs.get(last);
		List<ZoneEntities.NPC> survivors = new ArrayList<>(e.npcs);
		survivors.remove(last);
		int twins = 0;
		for (ZoneEntities.NPC s : survivors) {
			if (s.model == gone.model) {
				twins++;
			}
		}
		check(twins >= 1, "zone " + ZONE + ": the last NPC shares model " + gone.model + " with " + twins + " earlier NPCs - the case a delete by object got wrong");
		NPCRegistry reg = registryFor(e, gr);
		NPCEditForm form = new NPCEditForm();
		form.loadFromEntities(e, reg);
		form.setNPC(last);
		check(form.npc == gone && form.npcIndex == last, "the form is on NPC " + last);

		form.removeEntry();
		check(e.npcs.size() == last && e.NPCCount == last, "Remove dropped one NPC and the count follows");
		check(e.firstMisnumberedNPC() == -1, "and the survivors are numbered by position");
		check(entries(form) == last, "the dropdown lost one item");
		check(form.npc == survivors.get(last - 1) && form.npcIndex == last - 1, "the selection moved to the new last NPC");
		check(e.modified, "the zone is marked modified");
		boolean allOwn = e.npcs.size() == survivors.size();
		for (int i = 0; allOwn && i < survivors.size(); i++) {
			ZoneEntities.NPC s = survivors.get(i);
			for (H3DModel m : reg.models.values()) {
				m.worldLocX = -1f;
				m.worldLocZ = -1f;
			}
			form.updateH3D(i);
			H3DModel own = reg.getModel(s.model);
			allOwn = e.npcs.get(i) == s && own.worldLocX == s.xTile * 18f + 9f && own.worldLocZ == s.yTile * 18f + 9f;
			for (H3DModel m : reg.models.values()) {
				if (m != own && m.worldLocX != -1f) {
					allOwn = false; //another NPC's model moved onto this tile
				}
			}
			if (!allOwn) {
				System.out.println("  NPC " + i + " (model " + s.model + ") is not drawn with its own model");
			}
		}
		check(allOwn, "every survivor is drawn with its own model, on its own tile");
	}

	/**
	 * Save and the NPC dropdown with a script id zone 24 does not define. The
	 * record stays, the user is told, and the dropdown stays on the NPC that
	 * needs fixing; a defined id saves and switches.
	 */
	static void saveRefusesUndefinedScript(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		NPCEditForm form = new NPCEditForm();
		form.loadFromEntities(e, null);
		JSpinner scr = (JSpinner) field(form, "scr");
		JComboBox<?> entryBox = (JComboBox<?>) field(form, "entryBox");
		ZoneEntities.NPC first = e.npcs.get(0);
		check(form.npc == first && first.script == 7, "the form opened on NPC 0, script 7");
		check(form.saveEntry() && !e.modified, "Save with nothing changed succeeds and touches nothing");

		for (int bogus : new int[]{11, 999}) {
			scr.setValue(bogus);
			List<String> said = ctrmap.Ui.record();
			boolean saved;
			try {
				saved = form.saveEntry();
			} finally {
				ctrmap.Ui.stopRecording();
			}
			check(!saved, "Save refuses script " + bogus + " (the dispatch defines 1..10)");
			check(e.npcs.get(0) == first && first.script == 7 && !e.modified, "and the record is untouched");
			check(said.size() == 1 && said.get(0).contains("Script " + bogus + " is not defined"), "and the user is told why: " + said);

			said = ctrmap.Ui.record();
			try {
				form.setNPC(1);
			} finally {
				ctrmap.Ui.stopRecording();
			}
			check(entryBox.getSelectedIndex() == 0 && form.npc == first, "switching NPC with " + bogus + " still in the field stays on NPC 0");
			check(first.script == 7 && !e.modified, "and saves nothing");
			check(!said.isEmpty(), "and says so again");
		}

		scr.setValue(8);
		List<String> said = ctrmap.Ui.record();
		boolean saved;
		try {
			saved = form.saveEntry();
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(saved && e.npcs.get(0).script == 8 && form.npc == e.npcs.get(0) && e.modified, "a defined id saves");
		check(said.isEmpty(), "with nothing to say");
		scr.setValue(9);
		form.setNPC(1);
		check(e.npcs.get(0).script == 9 && entryBox.getSelectedIndex() == 1 && form.npc == e.npcs.get(1), "the dropdown saves a defined id and switches");
	}

	/** A zone with no NPCs: Save has nothing to refuse and must not report a failure. */
	static void saveWithNothingSelected(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		e.npcs.clear();
		e.NPCCount = 0;
		NPCEditForm form = new NPCEditForm();
		form.loadFromEntities(e, null);
		check(form.npc == null, "no NPC is selected in an empty zone");
		check(form.saveEntry() && !e.modified, "Save with no NPC selected succeeds and touches nothing");
	}

	/**
	 * The Dialogue note distinguishes "not defined" from "not a simple
	 * talker": an NPC whose record already holds a dangling id - a zone saved
	 * by an older build - must be named as such when it is shown.
	 */
	static void dialogueNoteNamesUndefinedScript(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		NPCEditForm form = new NPCEditForm();
		form.loadFromEntities(e, null);
		JLabel note = (JLabel) field(form, "dlgStatus");
		check(!note.getText().contains("not defined"), "NPC 0 on script 7: " + note.getText());
		e.npcs.get(0).script = 999;
		form.refresh();
		check(note.getText().equals("Script 999 is not defined in this zone's script."), "NPC 0 on script 999: " + note.getText());
		e.npcs.get(0).script = 7;
		form.refresh();
		check(!note.getText().contains("not defined"), "back on script 7: " + note.getText());
	}

	/** New entry adds an NPC on the given tile and selects it; at 255 it refuses and says so. */
	static void newEntryStopsAtTheCeiling(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		NPCEditForm form = new NPCEditForm();
		form.loadFromEntities(e, null);
		int before = e.npcs.size();
		List<String> said = ctrmap.Ui.record();
		try {
			form.addEntry(new Point(3, 4));
		} finally {
			ctrmap.Ui.stopRecording();
		}
		ZoneEntities.NPC added = e.npcs.get(e.npcs.size() - 1);
		check(e.npcs.size() == before + 1 && e.NPCCount == before + 1, "New entry added an NPC");
		check(added.uid == before && added.xTile == 3 && added.yTile == 4, "numbered by position, on the tile asked for");
		check(form.npc == added && form.npcIndex == before && entries(form) == before + 1, "and selected in the form");
		check(said.isEmpty() && e.modified, "with nothing to say, and the zone marked modified");

		while (e.npcs.size() < ZoneEntities.MAX_PER_KIND) {
			ZoneEntities.NPC filler = new ZoneEntities.NPC();
			filler.uid = e.npcs.size();
			e.npcs.add(filler);
		}
		e.NPCCount = e.npcs.size();
		form.loadFromEntities(e, null);
		said = ctrmap.Ui.record();
		try {
			form.addEntry(new Point(5, 5));
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(e.npcs.size() == ZoneEntities.MAX_PER_KIND && e.NPCCount == ZoneEntities.MAX_PER_KIND, "New entry on a zone with 255 NPCs adds nothing");
		check(said.size() == 1 && said.get(0).startsWith("Zone full: ") && said.get(0).contains("255 NPCs"), "and tells the user why: " + said);
	}

	/**
	 * The overlay's red box sits on the NPC the form is editing - by
	 * position, which is all the form knows. uids off their positions (the
	 * user declined the renumbering offer) are where uid and position differ.
	 */
	static void overlayFollowsPosition(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		e.npcs.clear();
		for (int i = 0; i < 3; i++) {
			ZoneEntities.NPC npc = new ZoneEntities.NPC();
			npc.uid = 4 + i;
			npc.xTile = 1 + 2 * i;
			npc.yTile = 1;
			e.npcs.add(npc);
		}
		e.NPCCount = 3;
		NPCEditForm form = new NPCEditForm();
		form.e = e;
		form.loaded = true;
		form.npcIndex = 1;
		CtrmapMainframe.mNPCEditForm = form;
		BufferedImage img = new BufferedImage(160, 60, BufferedImage.TYPE_INT_RGB);
		Graphics g = img.getGraphics();
		headlessTool().drawOverlay(g, 0, 0, 20.0);
		g.dispose();
		check(img.getRGB(3 * 20, 20) == Color.RED.getRGB(), "the NPC at position 1 (uid 5) wears the red box");
		check(img.getRGB(1 * 20, 20) == Color.BLACK.getRGB() && img.getRGB(5 * 20, 20) == Color.BLACK.getRGB(), "the others are boxed in black");
	}

	/**
	 * Dragging an NPC with the NPC tool onto a row the collision matrix does
	 * not cover keeps its altitude; onto a covered cell takes the mesh's.
	 * The matrix is 2 wide and 4 tall, the shape whose lower half used to
	 * read NaN.
	 */
	static void dragKeepsAltitudeOffTheMesh(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		ZoneEntities.NPC first = e.npcs.get(0);
		first.xTile = 10;
		first.yTile = 100; //inside the 80 x 160 tiles the matrix covers
		first.z3DCoordinate = 12.5f;
		CtrmapMainframe.mTileMapPanel = tallMatrix();
		NPCEditForm form = new NPCEditForm();
		form.loadFromEntities(e, null);
		CtrmapMainframe.mNPCEditForm = form;
		NPCTool tool = headlessTool();
		Selector.hilightTileX = 10;
		Selector.hilightTileY = 100;
		tool.onTileMouseDown(null);
		check(form.npc == first && form.npcIndex == 0, "pressing on NPC 0's tile selects it");

		Selector.hilightTileY = 170; //row 170 of 160: the mesh has no answer
		tool.onTileMouseDragged(null);
		check(first.xTile == 10 && first.yTile == 170, "the drag moved NPC 0 to (10, 170)");
		check(first.z3DCoordinate == 12.5f, "and kept its altitude where the mesh has no answer (was: NaN, into the file)");
		check(((Float) ((JFormattedTextField) field(form, "altitude")).getValue()) == 12.5f, "the Altitude field agrees");

		Selector.hilightTileY = 120; //row 120: a covered cell with no collision file reads 0
		tool.onTileMouseDragged(null);
		check(first.yTile == 120 && first.z3DCoordinate == 0f, "a drag onto the mesh takes the mesh's height");
		check(e.modified, "and the zone is marked modified");
	}

	/** The record's own altitude lookup: the mesh's height inside the matrix, unchanged past its edge. */
	static void altitudeFromMesh() {
		CtrmapMainframe.mTileMapPanel = tallMatrix();
		ZoneEntities.NPC npc = new ZoneEntities.NPC();
		npc.z3DCoordinate = 12.5f;
		npc.setYFromColl(10 * 18f, 100 * 18f);
		check(npc.z3DCoordinate == 0f, "setYFromColl takes the mesh's height inside the matrix");
		npc.z3DCoordinate = 12.5f;
		npc.setYFromColl(10 * 18f, 170 * 18f);
		check(npc.z3DCoordinate == 12.5f, "and keeps the altitude past its edge, where the lookup is NaN");
	}

	/** A map panel over a 2 wide, 4 tall matrix; null cells read height 0, past the edge reads NaN. */
	static TileMapPanel tallMatrix() {
		TileMapPanel map = new TileMapPanel();
		map.colls = new GRCollisionFile[2][4];
		return map;
	}

	/** An NPC tool with no window: the base constructor's tool-UI switch is the only part that needs one. */
	static NPCTool headlessTool() {
		return new NPCTool() {
			@Override
			public void onToolInit() {
			}
		};
	}

	/** Zone index as the editor holds it: a ZoneLoadingPanel with that zone open, in CtrmapMainframe.mZonePnl. */
	static Zone openZone(GARC zo, int index) throws Exception {
		ZoneLoadingPanel pnl = new ZoneLoadingPanel();
		pnl.zones = new Zone[index + 1];
		pnl.zones[index] = new Zone(new ZO(temp(zo.getDecompressedEntry(index))), Workspace.game);
		pnl.zone = pnl.zones[index];
		pnl.zoneIndex = index;
		CtrmapMainframe.mZonePnl = pnl;
		return pnl.zone;
	}

	/**
	 * A registry with an entry and a model for every MoveModel the NPCs use.
	 * The move-model archive is not part of the pristine set, so the meshes
	 * are a map model from the field-data archive, parsed once per uid: what
	 * matters is that each uid has its own instance, as in the editor.
	 */
	static NPCRegistry registryFor(ZoneEntities e, GARC gr) throws Exception {
		byte[] bch = mapModel(gr);
		NPCRegistry reg = new NPCRegistry(temp(new byte[0]));
		for (ZoneEntities.NPC npc : e.npcs) {
			if (reg.entries.containsKey(npc.model)) {
				continue;
			}
			NPCRegistry.NPCRegistryEntry entry = new NPCRegistry.NPCRegistryEntry();
			entry.uid = npc.model;
			entry.model = npc.model;
			reg.entries.put(npc.model, entry);
			reg.models.put(npc.model, new BCHFile(bch).models.get(0));
		}
		return reg;
	}

	/** The first map model in the field-data archive. */
	static byte[] mapModel(GARC gr) throws Exception {
		for (int i = 0; i < gr.length; i++) {
			byte[] raw = gr.getDecompressedEntry(i);
			if (raw == null || raw.length < 2 || (((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF)) != 0x4752) {
				continue;
			}
			GR container = new GR(temp(raw));
			byte[] model = container.len >= 2 ? container.getFile(1) : null;
			if (model != null && BchMapModel.isMapModel(model) && !new BCHFile(model).models.isEmpty()) {
				return model;
			}
		}
		throw new IllegalStateException("no map model in the field-data archive");
	}

	static int entries(NPCEditForm form) throws Exception {
		return ((JComboBox<?>) field(form, "entryBox")).getItemCount();
	}

	static Object field(Object o, String name) throws Exception {
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	static File temp(byte[] bytes) throws Exception {
		File f = File.createTempFile("ctrmap_npcform", ".bin");
		f.deleteOnExit();
		Files.write(f.toPath(), bytes);
		return f;
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

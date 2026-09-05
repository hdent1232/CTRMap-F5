package ctrmap.tests;

import com.jogamp.opengl.GL2;
import ctrmap.CtrmapMainframe;
import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GRCollisionFile;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.RandomAccessBAIS;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.npcreg.NPCRegistry;
import ctrmap.formats.scripts.PawnInstruction;
import ctrmap.formats.scripts.TalkerScriptWizard;
import ctrmap.formats.scripts.ZoneScriptAnalyzer;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.formats.zone.Zone;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.humaninterface.NPCEditForm;
import ctrmap.humaninterface.Selector;
import ctrmap.humaninterface.TileMapPanel;
import ctrmap.humaninterface.ZoneLoadingPanel;
import ctrmap.humaninterface.tools.AbstractTool;
import ctrmap.humaninterface.tools.NPCTool;
import ctrmap.humaninterface.tools.WarpTool;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
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
 * <li>Three of the form's own dialogues were still bare JOptionPanes: the
 *     out-of-order-uid question, the missing-registry-entry question and the
 *     soft-lock warning. A bare dialog is unassertable AND unreachable
 *     headless, and on a desktop it is worse than that - foreign-snapshot
 *     warnings of exactly this shape appeared mid-battery and the run only
 *     finished because somebody was at the machine to click them. Each is
 *     driven here through ctrmap.Ui: the question must be asked, and the
 *     answer must be the one that is acted on.</li>
 * <li>Five mutants the sweep could only record as survivors, because each
 *     lived behind something a suite cannot have: the zone-full refusal
 *     behind the modal "Add NPC / object" chooser, and the four null tests
 *     inside methods handed a live GL context. The chooser is answerable now;
 *     the GL decisions moved out of the GL calls into modelledNPCs() and
 *     ownedModels(), which are asked here directly.</li>
 * <li>Asking the decision was not enough for the outline. {@code boxedNPC(i)}
 *     was asserted, but its one call site - the {@code if} inside renderCM3D -
 *     was not, and inverting that line kept every check above green while the
 *     red box moved to every NPC EXCEPT the one being edited. The outline is
 *     the only thing on screen that says which record the NPC tool acts on, so
 *     a user would drag the boxed neighbour and watch a different NPC move.
 *     The frame is now drawn against a recording GL2 and the outline's world
 *     position is read back out of it.</li>
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
			misnumberedUidsAskFirst(zo);
			missingRegistryEntryAsksFirst(zo);
			softLockWarningReachesTheUser(zo);
			addTemplateStopsAtTheCeiling(zo);
			dialogueNoteTellsABrokenScriptFromAPlainOne(zo);
			aFullRegistryRefusesTheModelAndSaysSo(zo);
			viewportDrawsOnlyModelledNPCs(zo, gr);
			onlyTheEditedNPCIsBoxed(zo);
			theFrameOutlinesTheEditedNPC(zo, gr);
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

	/**
	 * A zone whose NPC uids are not their positions. Opening it must ask
	 * before renumbering, and the question must be answerable without a
	 * screen: as a bare JOptionPane it threw headless and blocked a desktop.
	 */
	static void misnumberedUidsAskFirst(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		e.npcs.get(3).uid = 99;
		check(e.firstMisnumberedNPC() == 3, "zone " + ZONE + " NPC 3 carries uid 99 - the gap an earlier delete left");

		List<String> said = ctrmap.Ui.record(JOptionPane.NO_OPTION);
		try {
			new NPCEditForm().loadFromEntities(e, null);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).contains("Renumber them now?"), "opening the zone asks first: " + said);
		check(e.npcs.get(3).uid == 99 && e.firstMisnumberedNPC() == 3 && !e.modified, "No leaves the uids as they were");

		said = ctrmap.Ui.record(JOptionPane.YES_OPTION);
		try {
			new NPCEditForm().loadFromEntities(e, null);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 1, "it asks again on the next open");
		check(e.firstMisnumberedNPC() == -1 && e.npcs.get(3).uid == 3 && e.modified, "and Yes renumbers them by position");
	}

	/**
	 * An NPC whose MoveModel has no registry entry. The form offers to write
	 * dummy registry data, which is a change to the area's registry - so it
	 * must ask, through ctrmap.Ui, and act on the answer it is given.
	 */
	static void missingRegistryEntryAsksFirst(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		NPCRegistry reg = new NPCRegistry(temp(new byte[0]));
		check(reg.entries.isEmpty(), "an empty registry has no entry for NPC 0's MoveModel");

		NPCEditForm form = new NPCEditForm();
		List<String> said = ctrmap.Ui.record(JOptionPane.NO_OPTION);
		try {
			form.loadFromEntities(zone.entities, reg);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).contains("create the registry entry"), "the form asks before writing registry data: " + said);
		check(reg.entries.isEmpty() && !reg.modified && form.regentry == null, "No writes no registry data");
		check(form.loaded, "and the form is usable afterwards");

		said = ctrmap.Ui.record(); //no answer: the same as closing the question
		try {
			new NPCEditForm().loadFromEntities(zone.entities, reg);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 1 && reg.entries.isEmpty() && !reg.modified, "closing the question writes none either");

		//Yes, against a registry that is already at capacity: the answer is
		//acted on (it gets past the No branch) and the refusal is the reason
		for (int uid = 1000; uid < 1000 + NPCRegistry.MAX_ENTRIES; uid++) {
			NPCRegistry.NPCRegistryEntry filler = new NPCRegistry.NPCRegistryEntry();
			filler.uid = uid;
			filler.model = uid;
			reg.entries.put(uid, filler);
		}
		said = ctrmap.Ui.record(JOptionPane.YES_OPTION);
		try {
			new NPCEditForm().loadFromEntities(zone.entities, reg);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 2 && said.get(1).contains("maximum capacity"), "Yes on a full registry says why it could not: " + said);
		check(reg.entries.size() == NPCRegistry.MAX_ENTRIES && !reg.modified, "and no entry was invented over the cap");
	}

	/**
	 * Saving an NPC onto a talker script whose message line does not exist.
	 * The save goes through - the record is legal - but the user must be told
	 * the NPC will freeze the game, and that warning was a bare JOptionPane.
	 */
	static void softLockWarningReachesTheUser(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		NPCEditForm form = new NPCEditForm();
		form.loadFromEntities(e, null);
		//zone 24's script 8 is a talker on message line 8; a story file of one
		//empty line has no line 8, which is the soft-lock exactly
		withStoryFile(form, zo, zone.header.textID, "");
		((JSpinner) field(form, "scr")).setValue(8);
		List<String> said = ctrmap.Ui.record();
		boolean saved;
		try {
			saved = form.saveEntry();
		} finally {
			ctrmap.Ui.stopRecording();
			Workspace.storytexts = null;
		}
		check(saved && e.npcs.get(0).script == 8, "the save goes through - a missing message is a warning, not a refusal");
		check(said.size() == 1 && said.get(0).contains("soft-lock"), "and the user is warned: " + said);
	}

	/**
	 * "Add NPC / object" on a zone that already holds every record the format
	 * can count. The chooser was a modal JOptionPane, so nothing after it -
	 * including this refusal, the one thing standing between the user and an
	 * unloadable zone - could be reached from a guard at all. Both arms are
	 * driven: an NPC template against 255 NPCs, a Sign against 255 props.
	 */
	static void addTemplateStopsAtTheCeiling(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		NPCEditForm form = new NPCEditForm();
		form.loadFromEntities(e, null);

		List<String> said = ctrmap.Ui.record(); //no answer: the same as cancelling the chooser
		Throwable ran;
		try {
			ran = addTemplate(form);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).contains("What would you like to add?"), "the chooser is asked through Ui: " + said);
		check(e.npcs.size() == 9 && e.furniture.size() == 2, "and cancelling it adds nothing");
		check(ran == null, "and the handler ends there: " + ran);

		while (e.npcs.size() < ZoneEntities.MAX_PER_KIND) {
			ZoneEntities.NPC filler = new ZoneEntities.NPC();
			filler.uid = e.npcs.size();
			e.npcs.add(filler);
		}
		e.NPCCount = e.npcs.size();
		said = ctrmap.Ui.record("Talking NPC");
		try {
			ran = addTemplate(form);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 2 && said.get(1).contains("255 NPCs"), "a full NPC list is refused, and said so: " + said);
		check(e.npcs.size() == ZoneEntities.MAX_PER_KIND, "and nothing was added");
		//the refusal has to END the handler. Telling the user the zone is full
		//and then walking them through the dialogue-entry flow anyway is the
		//half-refusal this ceiling exists to prevent, and it is not visible in
		//the counts above: everything past this point asks its questions
		//through dialogs this suite cannot see.
		check(ran == null, "and the handler stopped where it refused, instead of carrying on into the talker flow: " + ran);

		while (e.furniture.size() < ZoneEntities.MAX_PER_KIND) {
			e.furniture.add(new ZoneEntities.Prop());
		}
		e.furnitureCount = e.furniture.size();
		said = ctrmap.Ui.record("Sign");
		try {
			ran = addTemplate(form);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 2 && said.get(1).contains("255 props"), "a full prop list is refused against the prop count, not the NPC one: " + said);
		check(e.furniture.size() == ZoneEntities.MAX_PER_KIND, "and nothing was added");
		check(ran == null, "and the Sign arm stopped where it refused too: " + ran);
	}

	/**
	 * What the viewport would draw, and whose GL buffers this form owns. The
	 * mutation sweep recorded the null tests inside renderCM3D, uploadBuffers,
	 * deleteGLInstanceBuffers and the click test as survivors, all for the
	 * same reason: each sits in a method handed a live GL context, which no
	 * suite has. The decision itself is plain logic and is asked here.
	 */
	static void viewportDrawsOnlyModelledNPCs(GARC zo, GARC gr) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		NPCEditForm form = new NPCEditForm();
		form.loadFromEntities(e, null);
		check(form.modelledNPCs().length == 0, "with no registry the viewport draws nothing");
		check(form.ownedModels().isEmpty(), "and the form owns no GL buffers to upload or delete");

		NPCRegistry reg = registryFor(e, gr);
		form.loadFromEntities(e, reg);
		check(form.modelledNPCs().length == e.npcs.size(), "every NPC with a registered model is drawn");
		check(reg.models.size() > 1 && form.ownedModels().size() == reg.models.size(), "and the form owns every model in the registry");

		int gone = e.npcs.get(0).model;
		reg.models.remove(gone); //a MoveModel that failed to load
		int expected = 0;
		for (ZoneEntities.NPC npc : e.npcs) {
			if (npc.model != gone) {
				expected++;
			}
		}
		int[] drawn = form.modelledNPCs();
		check(expected < e.npcs.size() && drawn.length == expected, "an NPC whose model is missing is skipped, not drawn as its neighbour");
		boolean ownSlots = true;
		for (int i = 0; i < drawn.length; i++) {
			ownSlots &= drawn[i] < e.npcs.size() && (i == 0 || drawn[i] > drawn[i - 1]) && e.npcs.get(drawn[i]).model != gone;
		}
		check(ownSlots, "and the drawn slots are the survivors' own, in order");
		check(form.ownedModels().size() == reg.models.size(), "the owned buffers follow the registry");

		e.NPCCount = 2;
		check(form.modelledNPCs().length <= 2, "a count shorter than the NPC list draws only what it counts");
	}

	/**
	 * Which NPC the viewport outlines, and under which tool. renderCM3D is
	 * handed a live GL context and cannot be driven from here, so the decision
	 * it makes once per NPC lives in boxedNPC() and is asked directly - the
	 * same treatment modelledNPCs() and ownedModels() already have.
	 *
	 * <p>The outline says "this is the record the NPC tool acts on". Standing
	 * it around the NPCs the user is NOT editing points at the wrong record;
	 * standing it under the Warp or Camera tool promises that a click will
	 * move an NPC when it will not.
	 */
	static void onlyTheEditedNPCIsBoxed(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		NPCEditForm form = new NPCEditForm();
		form.loadFromEntities(e, null);
		form.setNPC(2);
		check(form.npcIndex == 2 && e.npcs.size() > 5, "the form is editing NPC 2 of " + e.npcs.size());

		AbstractTool was = CtrmapMainframe.tool;
		try {
			CtrmapMainframe.tool = headlessTool();
			int boxed = 0;
			boolean onlyMine = true;
			for (int i = 0; i < e.npcs.size(); i++) {
				if (form.boxedNPC(i)) {
					boxed++;
					onlyMine &= i == form.npcIndex;
				}
			}
			check(boxed == 1 && onlyMine, "with the NPC tool in hand exactly one NPC is outlined, and it is the one being edited (outlined: " + boxed + ")");

			form.setNPC(5);
			check(form.boxedNPC(5) && !form.boxedNPC(2), "the outline follows the selection to NPC 5");

			CtrmapMainframe.tool = new WarpTool() {
				@Override
				public void onToolInit() {
				}
			};
			boxed = 0;
			for (int i = 0; i < e.npcs.size(); i++) {
				if (form.boxedNPC(i)) {
					boxed++;
				}
			}
			check(boxed == 0, "under another tool nothing is outlined - a click there does not move an NPC (outlined: " + boxed + ")");

			CtrmapMainframe.tool = null;
			check(!form.boxedNPC(form.npcIndex), "and with no tool at all, nothing is outlined");
		} finally {
			CtrmapMainframe.tool = was;
		}
	}

	/**
	 * The outline as the frame actually draws it, not as the decision behind it
	 * answers. {@link #onlyTheEditedNPCIsBoxed} asks {@code boxedNPC} directly;
	 * the {@code if} that consults it inside renderCM3D is a line of its own,
	 * and with that line inverted every check in this suite still passed while
	 * the red box stood around every NPC except the edited one.
	 *
	 * <p>No GL context exists here, and none is needed: the frame is drawn with
	 * models that record what they were asked to draw instead of drawing it, so
	 * the GL2 handed down is never touched. Every model in the registry is one
	 * of them, so "which NPC was outlined" is answered by position - the same
	 * world position updateH3D puts the model at.
	 */
	static void theFrameOutlinesTheEditedNPC(GARC zo, GARC gr) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		NPCRegistry reg = registryFor(e, gr);
		List<float[]> drawn = new ArrayList<>();
		List<float[]> outlined = new ArrayList<>();
		byte[] bch = mapModel(gr);
		for (Integer uid : new ArrayList<>(reg.models.keySet())) {
			reg.models.put(uid, recordingModel(bch, drawn, outlined));
		}
		NPCEditForm form = new NPCEditForm();
		form.loadFromEntities(e, reg);
		form.setNPC(5);
		AbstractTool was = CtrmapMainframe.tool;
		try {
			CtrmapMainframe.tool = headlessTool();
			check(form.modelledNPCs().length > 2 && form.npcIndex == 5,
					"fixture: the frame draws " + form.modelledNPCs().length + " NPCs and the form is editing NPC 5");
			form.renderCM3D(null);
			ZoneEntities.NPC npc = e.npcs.get(5);
			float wx = npc.xTile * 18f + 9f;
			float wz = npc.yTile * 18f + 9f;
			check(drawn.size() == form.modelledNPCs().length, "the frame drew every modelled NPC (" + drawn.size() + ")");
			check(outlined.size() == 1, "and outlined exactly one of them (" + outlined.size() + ")");
			float[] at = outlined.isEmpty() ? null : outlined.get(0);
			check(at != null && at[0] == wx && at[1] == wz,
					"the outline stands on the NPC being edited, at (" + wx + ", " + wz + ") - drawn at "
					+ (at == null ? "nowhere" : "(" + at[0] + ", " + at[1] + ")"));
		} finally {
			CtrmapMainframe.tool = was;
		}
	}

	/**
	 * A MoveModel that remembers what it was asked to draw. It is parsed from
	 * real BCH bytes the way {@link ctrmap.formats.h3d.BCHFile} parses model 0,
	 * because H3DModel has no constructor that does not parse one; the buffer is
	 * taken from the BCHFile that already relocated it, which is why that field
	 * is read reflectively rather than cloned again from the source bytes.
	 */
	static H3DModel recordingModel(byte[] bch, final List<float[]> drawn, final List<float[]> outlined) throws Exception {
		BCHFile f = new BCHFile(bch);
		Field bufField = BCHFile.class.getDeclaredField("buf");
		bufField.setAccessible(true);
		byte[] buf = (byte[]) bufField.get(f);
		RandomAccessBAIS in = new RandomAccessBAIS(new ByteArrayInputStream(buf));
		in.seek(f.contentHeader.modelsPointerTableOffset);
		return new H3DModel(in, buf, f.header) {
			@Override
			public void render(GL2 gl) {
				drawn.add(new float[]{worldLocX, worldLocZ});
			}

			@Override
			public void renderBox(GL2 gl) {
				outlined.add(new float[]{worldLocX, worldLocZ});
			}
		};
	}

	/**
	 * The "Add NPC / object" button, which the form keeps to itself. Whatever
	 * the handler throws comes back as a value instead of ending the run: a
	 * handler that walks past its own refusal carries on into the talker
	 * flow's remaining bare JOptionPanes and dies headless, and that has to
	 * read as the check below failing - naming what went wrong - rather than
	 * as the suite stopping four checks early with a stack trace.
	 */
	static Throwable addTemplate(NPCEditForm form) throws Exception {
		java.lang.reflect.Method m = form.getClass().getDeclaredMethod("btnAddTalkerActionPerformed", java.awt.event.ActionEvent.class);
		m.setAccessible(true);
		try {
			m.invoke(form, (java.awt.event.ActionEvent) null);
			return null;
		} catch (java.lang.reflect.InvocationTargetException ex) {
			return ex.getCause() == null ? ex : ex.getCause();
		}
	}

	/**
	 * The Dialogue note has to tell a BROKEN script from one that is merely
	 * not a talker, and say which.
	 *
	 * <p>Both live on one line, either side of a ternary, and both mean
	 * something different to whoever is reading the form. "not a simple talker
	 * script" is ordinary - most scripts are not talkers, and there is nothing
	 * to do about it. "is broken: its dispatch case jumps straight into a
	 * subroutine, which freezes the game when that subroutine returns" is the
	 * shape that shipped and hard-froze ORAS, and it reads as an ordinary
	 * advanced script right up until the player dismisses the textbox.
	 *
	 * <p>Deleting the line leaves whatever the note last said - so an NPC on a
	 * game-freezing script shows the previous NPC's dialogue preview, which is
	 * worse than a blank. So both texts are asserted, and the DIFFERENCE
	 * between them: one script, driven through the form twice, clean and then
	 * malformed the way the old case installer malformed it.
	 */
	static void dialogueNoteTellsABrokenScriptFromAPlainOne(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		NPCEditForm form = new NPCEditForm();
		form.loadFromEntities(e, null);
		JLabel note = (JLabel) field(form, "dlgStatus");

		zone.s.decompressThis();
		ZoneScriptAnalyzer.Dispatch d = ZoneScriptAnalyzer.findDispatch(zone.s);
		check(d != null, "zone " + ZONE + " has a script dispatch");
		if (d == null) {
			return;
		}
		//a case this zone really defines, that is not a talker and is not
		//already broken - the ordinary arm of the ternary
		int key = Integer.MIN_VALUE;
		for (Map.Entry<Integer, PawnInstruction> c : d.cases.entrySet()) {
			int k = c.getKey();
			if (k < 0 || k >= TalkerScriptWizard.ENGINE_RESERVED_MIN || c.getValue() == null) {
				continue;
			}
			if (ZoneScriptAnalyzer.findTalkerPattern(zone.s, k) == null
					&& ZoneScriptAnalyzer.describeCaseDefect(zone.s, k) == null) {
				key = k;
				break;
			}
		}
		check(key != Integer.MIN_VALUE, "zone " + ZONE + " defines a case that is neither a talker nor broken");
		if (key == Integer.MIN_VALUE) {
			return;
		}
		e.npcs.get(0).script = key;
		form.refresh();
		check(note.getText().equals("Script " + key + " is not a simple talker script."),
				"a defined, non-talker, undamaged script reads as exactly that: " + note.getText());

		//now the shape that froze the game: the case points straight at a
		//subroutine PROC instead of at its trampoline
		PawnInstruction proc = null;
		for (PawnInstruction ins : zone.s.instructions) {
			if (ins.getCommand() == PawnInstruction.Commands.PROC.ordinal()) {
				proc = ins;
				break;
			}
		}
		check(proc != null, "the zone has a subroutine PROC to point the case at");
		if (proc == null) {
			return;
		}
		int ai = -1;
		for (int k = 0; k < d.caseTbl.argumentCells[0] && ai < 0; k++) {
			int at = 2 + 2 * k;
			if (at + 1 < d.caseTbl.argumentCells.length && d.caseTbl.argumentCells[at] == key) {
				ai = at;
			}
		}
		check(ai > 0, "case " + key + " is in the CASETBL");
		if (ai <= 0) {
			return;
		}
		d.caseTbl.argumentCells[ai + 1] = proc.pointer - (d.caseTbl.pointer + ai * 4) - 4;
		check(ZoneScriptAnalyzer.describeCaseDefect(zone.s, key) != null,
				"the case now has the defect that freezes the game");

		form.refresh();
		String broken = note.getText();
		check(broken.startsWith("Script " + key + " is broken: "),
				"and the note says the script is broken, not merely not a talker: " + broken);
		check(broken.contains("jumps straight into a subroutine"), "naming the defect: " + broken);
		check(broken.contains("freezes the game"), "and what it costs the player: " + broken);
		check(!broken.equals("Script " + key + " is not a simple talker script."),
				"the two cases do not read the same");
	}

	/**
	 * The model picker on an area whose NPC registry is already at its cap.
	 *
	 * <p>{@code registerModel} returns -1 and the pick does nothing. Without
	 * the message that is completely silent: the user clicks a model in the
	 * browser, the dialog closes, and the NPC still wears the model it had -
	 * with no way to tell that from having picked the same one back. The
	 * message is the only thing that says the registry is what stopped them,
	 * and what to do about it.
	 *
	 * <p>It was a bare JOptionPane, so a guard could neither reach it nor read
	 * it; it goes through {@link ctrmap.Ui} now, like the form's other five.
	 * The picker's global pool needs the MoveModels archive, which the pristine
	 * set does not carry, so one pool entry is put there by hand - everything
	 * downstream of it (the filter, the visible list, the selection) is the
	 * picker's own.
	 */
	@SuppressWarnings("unchecked")
	static void aFullRegistryRefusesTheModelAndSaysSo(GARC zo) throws Exception {
		final int GLOBAL_MODEL = 5; //not one of the fillers below, so it is not already registered
		Zone zone = openZone(zo, ZONE);
		NPCRegistry full = new NPCRegistry(temp(new byte[0]));
		for (int uid = 1000; uid < 1000 + NPCRegistry.MAX_ENTRIES; uid++) {
			NPCRegistry.NPCRegistryEntry filler = new NPCRegistry.NPCRegistryEntry();
			filler.uid = uid;
			filler.model = uid;
			full.entries.put(uid, filler);
		}
		NPCEditForm form = new NPCEditForm();
		ctrmap.Ui.record(JOptionPane.NO_OPTION); //the form asks about the NPCs with no entry; not this test's business
		try {
			form.loadFromEntities(zone.entities, full);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(form.reg == full && full.entries.size() == NPCRegistry.MAX_ENTRIES,
				"the form is on a registry that is already full (" + full.entries.size()
				+ " of " + NPCRegistry.MAX_ENTRIES + ")");

		Constructor<?> ctor = Class.forName("ctrmap.humaninterface.NPCEditForm$ModelPicker")
				.getDeclaredConstructor(NPCEditForm.class, int.class);
		ctor.setAccessible(true);
		Object picker = ctor.newInstance(form, -1);
		try {
			((List<int[]>) field(picker, "poolExtra")).add(new int[]{1, GLOBAL_MODEL});
			((List<String>) field(picker, "poolExtraLabels")).add("[+] model " + GLOBAL_MODEL);
			setField(picker, "poolLoaded", true);
			((javax.swing.JCheckBox) field(picker, "showAll")).setSelected(true);
			invoke(picker, "buildEntries");
			invoke(picker, "rebuild");

			List<int[]> visible = (List<int[]>) field(picker, "visibleEntries");
			javax.swing.JList<String> list = (javax.swing.JList<String>) field(picker, "list");
			int global = -1, registered = -1;
			for (int k = 0; k < visible.size(); k++) {
				if (visible.get(k)[0] == 1 && global < 0) {
					global = k;
				}
				if (visible.get(k)[0] == 0 && registered < 0) {
					registered = k;
				}
			}
			check(global >= 0 && registered >= 0,
					"the browser offers both a model this area already has and one it does not");
			if (global < 0 || registered < 0) {
				return;
			}

			list.setSelectedIndex(global);
			List<String> said = ctrmap.Ui.record();
			int uid;
			try {
				uid = (Integer) invoke(picker, "getSelectedUid");
			} finally {
				ctrmap.Ui.stopRecording();
			}
			check(uid < 0, "picking a model the full registry cannot take hands back no uid: " + uid);
			check(said.size() == 1 && said.get(0).contains("registry is full"),
					"and the user is told the registry is what stopped them: " + said);
			check(said.size() == 1 && said.get(0).contains(String.valueOf(NPCRegistry.MAX_ENTRIES)),
					"naming the cap they have hit: " + said);
			check(said.size() == 1 && said.get(0).contains("NPC registry editor"),
					"and what to do about it: " + said);
			check(full.entries.size() == NPCRegistry.MAX_ENTRIES && !full.modified,
					"and nothing was written over the cap");

			//the difference: a model this area already has needs no registration
			//at all, so the same picker must say nothing
			list.setSelectedIndex(registered);
			said = ctrmap.Ui.record();
			try {
				uid = (Integer) invoke(picker, "getSelectedUid");
			} finally {
				ctrmap.Ui.stopRecording();
			}
			check(uid >= 0 && said.isEmpty(),
					"picking a model the area already has hands back uid " + uid + " and says nothing: " + said);
		} finally {
			invoke(form, "disposePreviews"); //the preview runs an animator thread
		}
	}

	/** Calls a private no-argument method. */
	static Object invoke(Object o, String name) throws Exception {
		Method m = o.getClass().getDeclaredMethod(name);
		m.setAccessible(true);
		return m.invoke(o);
	}

	/**
	 * Hands the form a story-text file without a STORYTEXT archive: the
	 * pristine set has none, and the form's own cache is what the soft-lock
	 * check reads once the archive is known to exist.
	 */
	static void withStoryFile(NPCEditForm form, GARC anyGarc, int textID, String... lines) throws Exception {
		Workspace.storytexts = anyGarc; //only ever null-checked on this path
		setField(form, "storyFile", new GFMessageFile(GFMessageFile.write(Arrays.asList(lines))));
		setField(form, "storyFileTextID", textID);
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

	static void setField(Object o, String name, Object value) throws Exception {
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		f.set(o, value);
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

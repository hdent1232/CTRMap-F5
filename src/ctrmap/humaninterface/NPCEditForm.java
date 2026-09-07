package ctrmap.humaninterface;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.glu.gl2.GLUgl2;
import ctrmap.CtrmapMainframe;
import ctrmap.formats.npcreg.NPCRegistry;
import ctrmap.formats.zone.ZoneEntities;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.JFormattedTextField;
import javax.swing.text.NumberFormatter;
import static ctrmap.CtrmapMainframe.*;
import ctrmap.Utils;
import ctrmap.Workspace;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.h3d.model.H3DVertex;
import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.scripts.MsgWrapperInjector;
import ctrmap.formats.scripts.NpcTemplates;
import ctrmap.formats.scripts.SignWrapperInjector;
import ctrmap.formats.scripts.TalkerScriptWizard;
import ctrmap.formats.scripts.ZoneScriptAnalyzer;
import ctrmap.formats.scripts.ZoneScriptEdit;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.formats.vectors.Vec3f;
import ctrmap.formats.zone.NpcMoveCodes;
import ctrmap.formats.zone.Zone;
import ctrmap.humaninterface.tools.NPCTool;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;

/**
 * GUI form for modifying NPC properties.
 */
public class NPCEditForm extends javax.swing.JPanel implements CM3DRenderable {

	/**
	 * Creates new form NPCEditForm
	 */
	public boolean loaded = false;
	public ZoneEntities e;
	public ZoneEntities.NPC npc;
	public NPCRegistry reg;
	public NPCRegistry.NPCRegistryEntry regentry;
	public int npcIndex;
	public DefaultComboBoxModel motionModel = new DefaultComboBoxModel();
	public DefaultComboBoxModel motion2Model = new DefaultComboBoxModel();

	private boolean scrDropdownLoading = false;
	private GFMessageFile storyFile;
	private int storyFileTextID = -1;

	public NPCEditForm() {
		initComponents();
		setIntegerValueClass(new JFormattedTextField[]{x, y, areaW, areaH, mot, mp2, u10, u12, areaSX, areaSY, zl2, zl3, hostZone, originZone, linkedZone, linkID});
		((NumberFormatter) altitude.getFormatter()).setValueClass(Float.class);
		motDropdown.setModel(motionModel);
		mot2Dropdown.setModel(motion2Model);
	}

	public void loadFromEntities(ZoneEntities e, NPCRegistry reg) {
		fillMotionDropdowns();
		this.reg = reg;
		this.e = e;
		loaded = false;
		npc = null;
		regentry = null;
		entryBox.removeAllItems();
		storyFile = null;
		storyFileTextID = -1;
		populateScriptDropdown();
		if (e == null) {
			updateDialogueSection();
			return;
		}
		if (e.firstMisnumberedNPC() != -1 && ctrmap.Ui.confirm(frame,
				"This zone's NPC uids are not their positions (an earlier delete left a gap).\n"
				+ "The game and this editor both number NPCs by position, and the zone\n"
				+ "will not save until they match.\n\n"
				+ "Renumber them now? Scripts that address these NPCs by uid will need updating.",
				"NPC uids out of order", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
			e.renumberNPCs();
			e.modified = true;
		}
		for (int i = 0; i < e.NPCCount; i++) {
			entryBox.addItem(String.valueOf(e.npcs.get(i).uid));
		}
		loaded = true;
		if (entryBox.getItemCount() > 0) {
			showEntry(0);
		} else {
			updateDialogueSection(); //zone with zero NPCs - clear the previous zone's preview
		}
	}

	private void fillMotionDropdowns() {
		motionModel.removeAllElements();
		motion2Model.removeAllElements();
		boolean xy = Workspace.isXY();
		motModelStrArrMerge(NpcMoveCodes.movePerm1Labels(xy));
		mot2ModelStrArrMerge(NpcMoveCodes.movePerm2Labels(xy));
	}

	/**
	 * Which AI-motion row shows movePerm2 code {@code raw}, or -1 when this game
	 * gives it no name. See {@link NpcMoveCodes} for why both directions have to
	 * come off one table.
	 */
	public int getMot2Index(int raw) {
		return NpcMoveCodes.movePerm2Index(raw, Workspace.isXY());
	}

	/** The movePerm2 code AI-motion row {@code index} writes, or -1 off the end. */
	public int getMot2Raw(int index) {
		return NpcMoveCodes.movePerm2Raw(index, Workspace.isXY());
	}

	public void motModelStrArrMerge(String[] strings) {
		for (int i = 0; i < strings.length; i++) {
			motionModel.addElement(strings[i]);
		}
	}

	public void mot2ModelStrArrMerge(String[] strings) {
		for (int i = 0; i < strings.length; i++) {
			motion2Model.addElement(strings[i]);
		}
	}

	public void unload() {
		loaded = false;
		npcIndex = -1;
		reg = null;
		regentry = null;
		e = null;
		npc = null;
		entryBox.setSelectedIndex(-1);
		entryBox.removeAllItems();
		storyFile = null;
		storyFileTextID = -1;
		scrDropdownLoading = true;
		scrDropdown.removeAllItems();
		scrDropdownLoading = false;
		updateDialogueSection();
	}

	public void refresh() {
		entryBox.setSelectedIndex(entryBox.getSelectedIndex());
	}

	public void showEntry(int index) {
		npcIndex = -1;
		if (index == -1) {
			return;
		}
		loaded = false;
		npc = e.npcs.get(index);
		npcIndex = index;
		regentry = null;
		mdl.setValue(npc.model);
		evtFlag.setValue(npc.spawnFlag);
		scr.setValue(npc.script);
		x.setValue(npc.xTile);
		y.setValue(npc.yTile);
		altitude.setValue(npc.z3DCoordinate);
		facedir.setValue(npc.faceDirection);
		range.setValue(npc.sightRange);
		areaW.setValue(npc.areaWidth);
		areaH.setValue(npc.areaHeight);
		mot.setValue(npc.movePerm1);
		if (npc.movePerm1 < motionModel.getSize()) {
			motDropdown.setSelectedIndex(npc.movePerm1);
		} else {
			motDropdown.setSelectedIndex(-1);
		}
		mp2.setValue(npc.movePerm2);
		if (getMot2Index(npc.movePerm2) < motion2Model.getSize()) {
			mot2Dropdown.setSelectedIndex(getMot2Index(npc.movePerm2));
		} else {
			mot2Dropdown.setSelectedIndex(-1);
		}
		hostZone.setValue(npc.multiZoneLinkHostZone);
		originZone.setValue(npc.multiZoneLinkOriginZone);
		linkedZone.setValue(npc.multiZoneLinkTargetZone);
		linkID.setValue(npc.multiZoneLink1Type);
		u10.setValue(npc.u10);
		u12.setValue(npc.u12);
		areaSX.setValue(npc.areaStartX);
		areaSY.setValue(npc.areaStartY);
		zl2.setValue(npc.leashWidth);
		zl3.setValue(npc.leashHeight);
		if (reg != null) {
			regentry = reg.entries.get(npc.model);
			if (regentry == null) {
				int createEntry = ctrmap.Ui.confirm(frame,
						"This NPC's MoveModel properties could not be found\n"
						+ "in this area's MoveModel registry under the model UID.\n\n"
						+ "CTRMap can create dummy registry data for you using the\n"
						+ "most compatible settings for the majority of NPCs.\n\n"
						+ "You may need to change collision properties in NRE.\n\n"
						+ "Do you want to create the registry entry?", "Warning", JOptionPane.YES_NO_OPTION);
				if (createEntry != JOptionPane.YES_OPTION) { //closing the question is not consent to write registry data
					loaded = true;
					return;
				}
				NPCRegistry.NPCRegistryEntry failsafe = new NPCRegistry.NPCRegistryEntry();
				failsafe.uid = npc.model; //in this case, GF sometimes uses different UIDs than models, for it****s and ob****s. It's not required though.
				failsafe.model = npc.model;
				if (reg.entries.size() < 31) {
					reg.entries.put(failsafe.uid, failsafe);
					reg.mapModel(failsafe.uid, failsafe.uid);
					reg.modified = true;
					regentry = failsafe;
				} else {
					ctrmap.Ui.error(this, "The registry's maximum capacity of 31 unique NPCs has been reached.\n"
							+ "Please free up space in the registry editor and try again.", "Could not create registry entry");
				}
			}
		}
		updateH3D(index);
		bindNavi(e.npcs.get(index));
		syncScrDropdown(npc.script);
		updateDialogueSection();
		loaded = true;
	}

	/**
	 * The MoveModel drawn for NPC index, straight from its record. The form
	 * used to keep a List&lt;H3DModel&gt; alongside e.npcs and delete from it by
	 * object; NPCs sharing a model share the cached instance, so a delete
	 * took out the first slot with that model and every later NPC drew as
	 * its neighbour.
	 */
	private H3DModel modelAt(int index) {
		return reg == null ? null : reg.getModel(e.npcs.get(index).model);
	}

	/**
	 * The 3D navi gizmo follows the selected NPC. The guard tests drive this
	 * form with no 3D panel and no window, so both are optional here.
	 */
	private void bindNavi(MapObject o) {
		if (m3DDebugPanel != null) {
			m3DDebugPanel.bindNavi(o);
		}
	}

	private void repaintFrame() {
		if (frame != null) {
			frame.repaint();
		}
	}

	/**
	 * Commits the form to the selected NPC. Returns false, with the reason
	 * shown, when the record would point at a script this zone does not
	 * define - that used to save silently and the NPC did nothing in game.
	 */
	public boolean saveEntry() {
		if (npc == null) {
			return true;
		}
		ZoneEntities.NPC npc2 = new ZoneEntities.NPC();
		npc2.uid = npc.uid;
		npc2.model = (Integer) mdl.getValue();
		npc2.spawnFlag = (Integer) evtFlag.getValue();
		npc2.script = (Integer) scr.getValue();
		npc2.xTile = (Integer) x.getValue();
		npc2.yTile = (Integer) y.getValue();
		npc2.z3DCoordinate = (Float) altitude.getValue();
		npc2.faceDirection = (Integer) facedir.getValue();
		npc2.sightRange = (Integer) range.getValue();
		npc2.areaStartX = (Integer) areaSX.getValue();
		npc2.areaStartY = (Integer) areaSY.getValue();
		npc2.areaWidth = (Integer) areaW.getValue();
		npc2.areaHeight = (Integer) areaH.getValue();
		npc2.movePerm1 = (Integer) mot.getValue();
		npc2.movePerm2 = (Integer) mp2.getValue();
		npc2.u10 = (Integer) u10.getValue();
		npc2.u12 = (Integer) u12.getValue();
		npc2.leashWidth = (Integer) zl2.getValue();
		npc2.leashHeight = (Integer) zl3.getValue();
		npc2.multiZoneLinkHostZone = (Integer) hostZone.getValue();
		npc2.multiZoneLinkOriginZone = (Integer) originZone.getValue();
		npc2.multiZoneLinkTargetZone = (Integer) linkedZone.getValue();
		npc2.multiZoneLink1Type = (Integer) linkID.getValue();

		if (!npc2.equals(npc)) {
			String missing = undefinedScriptProblem(npc2.script);
			if (missing != null) {
				ctrmap.Ui.error(this, missing, "Script not defined");
				return false;
			}
			int idx = e.npcs.indexOf(npc);
			npc = npc2;
			e.npcs.set(idx, npc);
			e.modified = true;
			if (loaded) {
				String problem = talkerMessageProblem(npc2.script);
				if (problem != null) {
					ctrmap.Ui.message(this,
							"Heads up: this NPC runs a talking script, but " + problem + ".\n\n"
							+ "Talking to it in-game will FREEZE the game - there is no message box to close.\n\n"
							+ "Give it text with the \"Edit dialogue\" button, or set its Script to a\n"
							+ "non-talking one.",
							"This NPC will soft-lock the game", JOptionPane.WARNING_MESSAGE);
				}
			}
		}
		return true;
	}

	public boolean saveRegistry(boolean dialog) {
		if (reg == null) {
			return true;
		}
		return reg.store(dialog);
	}

	/**
	 * What "New entry" does: a fresh NPC on tile, selected in the form.
	 * Refuses, and says so, when the zone already holds every NPC the format
	 * can count - the 256th used to be written as a count byte of 0.
	 */
	public void addEntry(Point tile) {
		if (kindFull(e.npcs.size(), "NPCs")) {
			return;
		}
		loaded = false;
		ZoneEntities.NPC newNPC = new ZoneEntities.NPC();
		int newuid = 0;
		for (int i = 0; i < e.npcs.size(); i++) {
			newuid = Math.max(e.npcs.get(i).uid + 1, newuid); //get first free UID but don't pollute free spaces if any
		}
		newNPC.uid = newuid;
		newNPC.model = (npc != null) ? npc.model : 0;
		newNPC.xTile = tile.x;
		newNPC.yTile = tile.y;
		e.npcs.add(newNPC);
		e.NPCCount++;
		entryBox.addItem(String.valueOf(newNPC.uid));
		loaded = true;
		setNPC(entryBox.getItemCount() - 1);
		repaintFrame();
		e.modified = true;
	}

	/**
	 * What "Remove entry" does: drops the selected NPC and renumbers the ones
	 * after it, because the game keeps NPC uids equal to their position.
	 * Asks first when any NPC will move.
	 */
	public void removeEntry() {
		int idx = entryBox.getSelectedIndex();
		if (npc == null || idx == -1 || idx >= e.npcs.size() || e.npcs.get(idx) != npc) {
			return;
		}
		if (idx < e.npcs.size() - 1 && ctrmap.Ui.confirm(frame,
				"The NPCs after this one will be renumbered (uids " + (idx + 1) + ".." + (e.npcs.size() - 1)
				+ " become " + idx + ".." + (e.npcs.size() - 2) + "), because the game keeps NPC uids\n"
				+ "equal to their position. Scripts that address them by uid will need updating.\n\n"
				+ "Remove NPC " + idx + "?",
				"Remove NPC", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
			return;
		}
		loaded = false;
		e.removeNPC(idx);
		entryBox.removeAllItems();
		for (int i = 0; i < e.npcs.size(); i++) {
			entryBox.addItem(String.valueOf(e.npcs.get(i).uid)); //relabelled - the uids after idx moved down
		}
		loaded = true;
		if (e.npcs.isEmpty()) {
			npc = null;
			npcIndex = -1;
			bindNavi(null);
			updateDialogueSection();
		} else {
			entryBox.setSelectedIndex(Math.min(idx, e.npcs.size() - 1));
		}
		repaintFrame();
		e.modified = true;
	}

	/** Tells the user when the zone already holds every record of a kind the format can count. */
	private boolean kindFull(int count, String kind) {
		if (count < ZoneEntities.MAX_PER_KIND) {
			return false;
		}
		ctrmap.Ui.error(this, "This zone already has " + count + " " + kind + ", the most the game can store.\n"
				+ "Remove one before adding another.", "Zone full");
		return true;
	}

	/**
	 * Why scriptId cannot go on an NPC in this zone, or null when it can. An
	 * id with no case in the zone's dispatch is not an advanced script, it is
	 * nothing - and the Dialogue note used to describe both the same way.
	 */
	private String undefinedScriptProblem(int scriptId) {
		Zone zone = getLoadedZone();
		if (zone == null || zone.s == null) {
			return null;
		}
		zone.s.decompressThis();
		if (TalkerScriptWizard.scriptIdExists(zone.s, scriptId)) {
			return null;
		}
		return "Script " + scriptId + " is not defined in this zone's script - its dispatch only has cases\n"
				+ ZoneScriptAnalyzer.listScriptIds(zone.s) + ".\n\n"
				+ "The NPC keeps its previous script. Pick one from the dropdown, or add a\n"
				+ "script with \"Add NPC / object\" first.";
	}

	/**
	 * Returns a plain-language reason why an NPC with this talker script id would
	 * soft-lock the game (its message line is missing or empty - the game opens a
	 * message box the player can never close), or null when the NPC is not a
	 * talker or its message is fine. Same detection as the read-only Dialogue
	 * preview, but surfaced as an active guard when the NPC is saved. Fully
	 * defensive: any lookup failure returns null rather than a false alarm.
	 */
	private String talkerMessageProblem(int scriptId) {
		try {
			Zone zone = getLoadedZone();
			if (zone == null || zone.s == null || scriptId >= TalkerScriptWizard.ENGINE_RESERVED_MIN) {
				return null;
			}
			if (Workspace.getStoryTextGARC() == null) {
				return null;
			}
			zone.s.decompressThis();
			ZoneScriptAnalyzer.TalkerPattern tp = ZoneScriptAnalyzer.findTalkerPattern(zone.s, scriptId);
			if (tp == null) {
				return null; //not a simple talker - nothing to guard against
			}
			GFMessageFile msg = getStoryFile(zone.header.textID);
			if (msg == null) {
				return null;
			}
			if (tp.msgLine < 0 || tp.msgLine >= msg.getLineCount()) {
				return "its message line " + tp.msgLine + " does not exist in story-text file " + zone.header.textID;
			}
			if (msg.getLine(tp.msgLine).trim().isEmpty()) {
				return "its message (story-text file " + zone.header.textID + ", line " + tp.msgLine + ") is empty";
			}
			return null;
		} catch (Exception ex) {
			return null;
		}
	}

	private Zone getLoadedZone() {
		if (!Workspace.isValid() || mZonePnl == null) {
			return null;
		}
		return mZonePnl.zone;
	}

	private void populateScriptDropdown() {
		scrDropdownLoading = true;
		scrDropdown.removeAllItems();
		Zone zone = getLoadedZone();
		if (zone != null && zone.s != null) {
			zone.s.decompressThis();
			for (String item : TalkerScriptWizard.buildScriptIdItems(zone.s)) {
				scrDropdown.addItem(item);
			}
		}
		scrDropdown.setSelectedIndex(-1);
		scrDropdownLoading = false;
	}

	private void syncScrDropdown(int scriptId) {
		scrDropdownLoading = true;
		int match = -1;
		for (int i = 0; i < scrDropdown.getItemCount(); i++) {
			if (parseLeadingInt(scrDropdown.getItemAt(i)) == scriptId) {
				match = i;
				break;
			}
		}
		scrDropdown.setSelectedIndex(match);
		scrDropdownLoading = false;
	}

	private static int parseLeadingInt(String item) {
		try {
			int space = item.indexOf(' ');
			return Integer.parseInt(space == -1 ? item : item.substring(0, space));
		} catch (NumberFormatException ex) {
			return Integer.MIN_VALUE;
		}
	}

	/**
	 * Refreshes the Dialogue section for the selected NPC: a read-only
	 * preview of the STORYTEXT line displayed by the NPC's talker script, or
	 * an explanatory label when the NPC's script is not a simple talker (or
	 * no workspace/zone/storytext is available).
	 */
	private void updateDialogueSection() {
		dlgPreview.setText("");
		btnEditDialogue.setEnabled(false);
		Zone zone = getLoadedZone();
		if (zone == null || zone.s == null) {
			btnAddTalker.setEnabled(false);
			dlgStatus.setText("No zone loaded.");
			return;
		}
		zone.s.decompressThis();
		boolean hasDispatch = ZoneScriptAnalyzer.findDispatch(zone.s) != null;
		boolean hasWrapper = ZoneScriptAnalyzer.findMsgWrapper(zone.s) != null;
		boolean hasStoryText = Workspace.getStoryTextGARC() != null;
		//the button opens a template chooser (Talking NPC / Sign / Item giver /
		//Trainer); each template validates its own needs, so the button only
		//needs a script dispatch (every zone has one). A missing message wrapper
		//no longer disables anything - the talker path offers to inject one.
		btnAddTalker.setEnabled(hasDispatch && e != null);
		if (!hasDispatch) {
			btnAddTalker.setToolTipText("This zone's script has no script dispatch (main SWITCH/CASETBL).");
		} else if (!hasStoryText) {
			btnAddTalker.setToolTipText("Add a trainer here; talking NPCs and signs need the STORYTEXT archive, which was not found.");
		} else if (!hasWrapper) {
			btnAddTalker.setToolTipText("Talking NPCs work here (the game's message routine is injected if needed). Signs and item givers need a zone that already has one.");
		} else {
			btnAddTalker.setToolTipText("Add a talking NPC, sign, item giver, or trainer.");
		}
		if (npc == null) {
			dlgStatus.setText("No NPC selected.");
			return;
		}
		int scriptId = npc.script;
		if (scriptId >= TalkerScriptWizard.ENGINE_RESERVED_MIN) {
			dlgStatus.setText("Script " + scriptId + " is in an engine-reserved range (no local dialogue).");
			return;
		}
		if (!TalkerScriptWizard.scriptIdExists(zone.s, scriptId)) {
			dlgStatus.setText("Script " + scriptId + " is not defined in this zone's script.");
			return;
		}
		ZoneScriptAnalyzer.TalkerPattern tp = ZoneScriptAnalyzer.findTalkerPattern(zone.s, scriptId);
		if (tp == null) {
			//a malformed case is not merely "not a talker" - say what is wrong
			//with it, or it reads as an ordinary advanced script right up until
			//the game freezes on it
			String defect = ZoneScriptAnalyzer.describeCaseDefect(zone.s, scriptId);
			dlgStatus.setText(defect != null
					? "Script " + scriptId + " is broken: " + defect + "."
					: "Script " + scriptId + " is not a simple talker script.");
			return;
		}
		if (Workspace.getStoryTextGARC() == null) {
			dlgStatus.setText("STORYTEXT archive not found in the game directory.");
			return;
		}
		GFMessageFile msg = getStoryFile(zone.header.textID);
		if (msg == null) {
			dlgStatus.setText("Story text file " + zone.header.textID + " could not be read.");
			return;
		}
		if (tp.msgLine < 0 || tp.msgLine >= msg.getLineCount()) {
			dlgStatus.setText("Talker line " + tp.msgLine + " is out of range of story text file " + zone.header.textID + ".");
			return;
		}
		int users = TalkerScriptWizard.countTalkersUsingLine(zone.s, tp.msgLine);
		dlgStatus.setText("Story text " + zone.header.textID + ", line " + tp.msgLine + (users > 1 ? " (shared by " + users + " talkers)" : ""));
		dlgPreview.setText(msg.getLine(tp.msgLine));
		btnEditDialogue.setEnabled(true);
	}

	private GFMessageFile getStoryFile(int textID) {
		if (storyFile != null && storyFileTextID == textID) {
			return storyFile;
		}
		storyFile = null;
		storyFileTextID = -1;
		if (Workspace.getStoryTextGARC() == null) {
			return null;
		}
		File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.STORYTEXT, textID);
		if (f == null || !f.exists()) {
			return null;
		}
		try {
			InputStream in = new FileInputStream(f);
			byte[] b = new byte[in.available()];
			in.read(b);
			in.close();
			storyFile = new GFMessageFile(b);
			storyFileTextID = textID;
		} catch (IOException | RuntimeException ex) {
			storyFile = null;
			storyFileTextID = -1;
		}
		return storyFile;
	}

	/**
	 * Writes the story text file into the workspace and registers it for
	 * packing - the same workspace-file + addPersist flow TextEditor.store()
	 * uses for GAMETEXT. Throws with the reason when it cannot; this is the
	 * {@link ZoneScriptEdit.Store} the Add wizards hand their edit.
	 */
	private void writeStoryFile(int textID, GFMessageFile msg) throws IOException {
		byte[] b;
		try {
			b = msg.write();
		} catch (RuntimeException ex) {
			throw new IOException("the text could not be encoded: " + ex.getMessage(), ex);
		}
		File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.STORYTEXT, textID);
		if (f == null) {
			throw new IOException("story text file " + textID + " could not be extracted from the STORYTEXT archive");
		}
		OutputStream os = new FileOutputStream(f);
		try {
			os.write(b);
			os.flush();
		} finally {
			os.close();
		}
		Workspace.addPersist(f);
	}

	/** {@link #writeStoryFile}, reporting the reason through Ui instead: for the dialogue editor's in-place edit. */
	private boolean storeStoryFile(int textID, GFMessageFile msg) {
		try {
			writeStoryFile(textID, msg);
			return true;
		} catch (IOException ex) {
			ctrmap.Ui.error(this, "Could not store story text file " + textID + ":\n" + ex.getMessage(), "Text save error");
			return false;
		}
	}

	/**
	 * Selects a message-routine donor script from the ZoneData archive via
	 * the workspace-respecting path (a user-modified canonical zone that no
	 * longer validates is skipped in favour of the first zone that does).
	 *
	 * @throws MsgWrapperInjector.InjectionException when no zone validates
	 */
	private GFLPawnScript loadWrapperDonor() {
		final GARC zoneGarc = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		if (zoneGarc == null) {
			throw new MsgWrapperInjector.InjectionException("The ZoneData archive is not loaded.");
		}
		return MsgWrapperInjector.pickDonor(new MsgWrapperInjector.ScriptSource() {
			@Override
			public GFLPawnScript get(int zoneIndex) {
				File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, zoneIndex);
				if (f == null || !f.exists()) {
					return null;
				}
				try {
					InputStream in = new FileInputStream(f);
					byte[] b = new byte[in.available()];
					in.read(b);
					in.close();
					return MsgWrapperInjector.extractZoneScript(b);
				} catch (IOException ex) {
					return null;
				}
			}
		}, zoneGarc.length);
	}

	/**
	 * Selects a SIGN-routine donor script (mirror of {@link #loadWrapperDonor}
	 * for the sign transplant).
	 *
	 * @throws SignWrapperInjector.InjectionException when no zone validates
	 */
	private GFLPawnScript loadSignDonor() {
		final GARC zoneGarc = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		if (zoneGarc == null) {
			throw new SignWrapperInjector.InjectionException("The ZoneData archive is not loaded.");
		}
		return SignWrapperInjector.pickDonor(new MsgWrapperInjector.ScriptSource() {
			@Override
			public GFLPawnScript get(int zoneIndex) {
				File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, zoneIndex);
				if (f == null || !f.exists()) {
					return null;
				}
				try {
					InputStream in = new FileInputStream(f);
					byte[] b = new byte[in.available()];
					in.read(b);
					in.close();
					return MsgWrapperInjector.extractZoneScript(b);
				} catch (IOException ex) {
					return null;
				}
			}
		}, zoneGarc.length);
	}

	/**
	 * Multi-line text typed into a dialog uses real newlines; the message
	 * files store them as the \n escape (same form the TextEditor shows).
	 */
	private static String escapeTypedText(String text) {
		return text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n");
	}

	private void btnEditDialogueActionPerformed(java.awt.event.ActionEvent evt) {
		Zone zone = getLoadedZone();
		if (zone == null || npc == null) {
			return;
		}
		zone.s.decompressThis();
		ZoneScriptAnalyzer.TalkerPattern tp = ZoneScriptAnalyzer.findTalkerPattern(zone.s, npc.script);
		if (tp == null) {
			return;
		}
		GFMessageFile msg = getStoryFile(zone.header.textID);
		if (msg == null || tp.msgLine < 0 || tp.msgLine >= msg.getLineCount()) {
			return;
		}
		DialogueForm form = new DialogueForm(msg.getLine(tp.msgLine));
		if (!showForm(form.panel, "Edit dialogue (story text " + zone.header.textID + ", line " + tp.msgLine + ")")) {
			return;
		}
		DialogueEdit done = editDialogue(zone, npc.script, tp.msgLine, msg, form.text());
		if (done == DialogueEdit.FAILED) {
			return;
		}
		if (done == DialogueEdit.REPOINTED) {
			mZonePnl.store(false); //same path ScriptEditor uses to save the zone script
			mScriptPnl.loadScript(zone.s);
			populateScriptDropdown();
			syncScrDropdown(npc.script);
		}
		updateDialogueSection();
	}

	/** The dialogue editor's form: the line, as it is, to retype. */
	public static final class DialogueForm {

		public final JScrollPane panel;
		private final JTextArea text;

		public DialogueForm(String currentLine) {
			text = textArea(currentLine, 5);
			panel = new JScrollPane(text);
		}

		public String text() {
			return text.getText();
		}
	}

	/** What {@link #editDialogue} did. */
	public enum DialogueEdit {
		/** Nothing changed, and the user was told why. */
		FAILED,
		/** The line was rewritten where it is. */
		IN_PLACE,
		/** The line was shared, so a new one was added and this talker re-pointed at it: the script changed. */
		REPOINTED
	}

	/**
	 * The dialogue editor past its form: refuses text that will not encode
	 * (saying so); rewrites the talker's line in place, or - when other
	 * talkers share it - adds a new line and re-points scriptId's talker at
	 * it; stores the story file, rolling either change back when it cannot.
	 */
	public DialogueEdit editDialogue(Zone zone, int scriptId, int msgLine, GFMessageFile msg, String typed) {
		String text = escapeTypedText(typed);
		try {
			GFMessageFile.write(java.util.Arrays.asList(text)); //validate the bracket/escape syntax before touching anything
		} catch (RuntimeException ex) {
			ctrmap.Ui.error(this, "The text could not be encoded:\n" + ex.getMessage(), "Text encode error");
			return DialogueEdit.FAILED;
		}
		boolean scriptChanged = false;
		String previousLine = null;
		if (TalkerScriptWizard.countTalkersUsingLine(zone.s, msgLine) > 1) {
			//the line is shared with other talkers - add a new line and re-point this talker only
			msg.addLine(text);
			int newLine = msg.getLineCount() - 1;
			if (!ZoneScriptAnalyzer.patchTalkerLine(zone.s, scriptId, newLine)) {
				msg.removeLine(newLine);
				ctrmap.Ui.error(this, "Could not re-point the talker script to line " + newLine + ".", "Script patch error");
				return DialogueEdit.FAILED;
			}
			zone.s.updateRaw();
			scriptChanged = true;
		} else {
			previousLine = msg.getLine(msgLine);
			msg.setLine(msgLine, text);
		}
		if (!storeStoryFile(zone.header.textID, msg)) {
			if (scriptChanged) {
				//roll back the re-point so a later zone save cannot ship a script
				//referencing a line that was never written
				int addedLine = msg.getLineCount() - 1;
				ZoneScriptAnalyzer.patchTalkerLine(zone.s, scriptId, msgLine);
				zone.s.updateRaw();
				msg.removeLine(addedLine);
			} else {
				//roll back the in-place edit so the cache matches disk and a later
				//store of this text file cannot silently commit the failed edit
				msg.setLine(msgLine, previousLine);
			}
			return DialogueEdit.FAILED;
		}
		return scriptChanged ? DialogueEdit.REPOINTED : DialogueEdit.IN_PLACE;
	}

	private void btnAddTalkerActionPerformed(java.awt.event.ActionEvent evt) {
		Zone zone = getLoadedZone();
		if (zone == null || zone.s == null || e == null) {
			ctrmap.Ui.error(this, "No zone is loaded.", "Add talking NPC");
			return;
		}
		zone.s.decompressThis();
		if (ZoneScriptAnalyzer.findDispatch(zone.s) == null) {
			ctrmap.Ui.error(this, "This zone's script has no script dispatch (main SWITCH/CASETBL).", "Add NPC / object");
			return;
		}
		//pick which template to add; "Talking NPC" continues the proven flow below,
		//the others dispatch to their own self-contained handlers
		String[] templates = {"Talking NPC", "Sign", "Item giver", "Trainer", "Battle challenge (own trainers)", "Give BP"};
		Object choice = ctrmap.Ui.input(frame, "What would you like to add?", "Add NPC / object",
				JOptionPane.PLAIN_MESSAGE, templates, templates[0]);
		if (choice == null) {
			return;
		}
		if ("Sign".equals(choice) ? kindFull(e.furniture.size(), "props") : kindFull(e.npcs.size(), "NPCs")) {
			return;
		}
		if ("Sign".equals(choice)) {
			addSignTemplate(zone);
			return;
		}
		if ("Item giver".equals(choice)) {
			addGiverTemplate(zone);
			return;
		}
		if ("Trainer".equals(choice)) {
			addTrainerTemplate(zone);
			return;
		}
		if ("Battle challenge (own trainers)".equals(choice)) {
			addChallengeTemplate(zone);
			return;
		}
		if ("Give BP".equals(choice)) {
			addGiveBpTemplate(zone);
			return;
		}
		boolean injectWrapper = false;
		GFLPawnScript wrapperDonor = null;
		if (ZoneScriptAnalyzer.findMsgWrapper(zone.s) == null) {
			try {
				wrapperDonor = loadWrapperDonor();
			} catch (RuntimeException ex) {
				ctrmap.Ui.error(this, "This zone's script has no message-display routine and no donor zone could provide one:\n" + ex.getMessage(), "Add talking NPC");
				return;
			}
			int insCount = MsgWrapperInjector.countInjectedInstructions(zone.s, wrapperDonor);
			int rslInject = ctrmap.Ui.confirm(frame,
					"This zone's script has no message-display routine.\n"
					+ "Inject one (copied from the game's own code)?\n"
					+ "This adds " + insCount + " instructions (about 2.4 KB) to the zone script.",
					"Add talking NPC", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (rslInject != JOptionPane.OK_OPTION) {
				return;
			}
			injectWrapper = true;
		}
		if (Workspace.getStoryTextGARC() == null) {
			ctrmap.Ui.error(this, "The STORYTEXT archive was not found in the game directory.", "Add talking NPC");
			return;
		}
		GFMessageFile msg = getStoryFile(zone.header.textID);
		if (msg == null) {
			ctrmap.Ui.error(this, "Story text file " + zone.header.textID + " could not be read.", "Add talking NPC");
			return;
		}
		TalkerForm form = new TalkerForm((npc != null) ? npc.model : -1);
		if (!showForm(form.panel, "Add talking NPC")) {
			return;
		}
		if (addTalker(zone, msg, form.text(), form.model(), injectWrapper ? wrapperDonor : null,
				mTileMapPanel.getTileAtViewportCentre()) != null) {
			finishNpcAdd(zone, true);
			repaintFrame();
		}
	}

	/** The talking-NPC form: the dialogue text and the model. */
	public final class TalkerForm {

		public final JPanel panel = stackedForm();
		private final JTextArea text = textArea("", 5);
		private final ModelPicker model;

		public TalkerForm(int defaultModel) {
			model = new ModelPicker(defaultModel);
			addLabeled(panel, "Dialogue text:", new JScrollPane(text));
			addLabeled(panel, "Model (type to search) - preview below:", model);
			panel.add(hint("<html>The NPC is placed at the centre of the current view.<br>Only registered overworld models are listed.</html>"));
		}

		public String text() {
			return text.getText();
		}

		public int model() {
			return model.getSelectedUid();
		}
	}

	/**
	 * The talking-NPC wizard past its form: refuses a missing model or text
	 * that will not encode (saying which), else clones the talker script for
	 * the new story-text line - transplanting the message routine from
	 * wrapperDonor first when one is given - and places the NPC record at
	 * pos. Returns the placed record, or null when nothing was added.
	 */
	public ZoneEntities.NPC addTalker(Zone zone, GFMessageFile msg, String typed, int model, GFLPawnScript wrapperDonor, Point pos) {
		if (model < 0) {
			ctrmap.Ui.error(this, "Select an overworld model first.", "Add talking NPC");
			return null;
		}
		String text = escapeTypedText(typed);
		try {
			GFMessageFile.write(java.util.Arrays.asList(text)); //validate the bracket/escape syntax before touching anything
		} catch (RuntimeException ex) {
			ctrmap.Ui.error(this, "The text could not be encoded:\n" + ex.getMessage(), "Text encode error");
			return null;
		}
		//the whole script edit (injection + talker clone + the new line) lands
		//whole or not at all - see ZoneScriptEdit
		int newId;
		try {
			ZoneScriptEdit edit = new ZoneScriptEdit(zone, "the talker script")
					.withText(msg, zone.header.textID, java.util.Arrays.asList(text));
			if (wrapperDonor != null) {
				edit.injectMsgWrapperFrom(wrapperDonor);
			}
			newId = edit.apply(TalkerScriptWizard::cloneTalker, this::writeStoryFile);
		} catch (ZoneScriptEdit.Refused ex) {
			ctrmap.Ui.error(this, ex.reason(), "Add talking NPC");
			return null;
		}
		ZoneEntities.NPC placed = NpcTemplates.makeScriptedNpc(NpcTemplates.nextFreeUid(e), model, newId, pos.x, pos.y);
		placeNpc(placed);
		return placed;
	}

	/**
	 * Sign template: clones the zone's vanilla sign script for a new STORYTEXT
	 * line and drops an interactable furniture record at the view centre.
	 */
	private void addSignTemplate(Zone zone) {
		//zones without the sign-display routine (467 of 536) get the vanilla one
		//transplanted first - the same proven mechanism as the message routine
		zone.s.decompressThis();
		boolean needsWrapper = ZoneScriptAnalyzer.findSignWrapper(zone.s) == null;
		GFLPawnScript signDonor = null;
		if (needsWrapper) {
			try {
				signDonor = loadSignDonor();
			} catch (RuntimeException ex) {
				ctrmap.Ui.error(this, "This zone's script has no sign-display routine and none could be\ntransplanted: " + ex.getMessage(), "Add sign");
				return;
			}
			int insCount = SignWrapperInjector.countInjectedInstructions(zone.s, signDonor);
			if (ctrmap.Ui.confirm(this,
					"This zone's script has no sign-display routine (467 of 536 vanilla zones lack it).\n"
					+ "Inject the vanilla routine (" + insCount + " instructions) into this zone's script?\n"
					+ "This is the same transplant that makes talking NPCs work everywhere.",
					"Add sign", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
				return;
			}
		}
		if (Workspace.getStoryTextGARC() == null) {
			ctrmap.Ui.error(this, "The STORYTEXT archive was not found in the game directory.", "Add sign");
			return;
		}
		GFMessageFile msg = getStoryFile(zone.header.textID);
		if (msg == null) {
			ctrmap.Ui.error(this, "Story text file " + zone.header.textID + " could not be read.", "Add sign");
			return;
		}
		SignForm form = new SignForm();
		if (!showForm(form.panel, "Add sign")) {
			return;
		}
		if (addSign(zone, msg, form.text(), form.signType(), needsWrapper ? signDonor : null,
				mTileMapPanel.getTileAtViewportCentre())) {
			saveZoneScript(zone);
			repaintFrame();
		}
	}

	/** The sign form: the text and the sign style. */
	public final class SignForm {

		public final JPanel panel = stackedForm();
		private final JTextArea text = textArea("", 5);
		private final javax.swing.JComboBox<String> style = new javax.swing.JComboBox<>(NpcTemplates.SIGN_TYPE_LABELS);

		public SignForm() {
			addLabeled(panel, "Sign text:", new JScrollPane(text));
			addLabeled(panel, "Sign style:", style);
			panel.add(hint("<html>A sign furniture object is placed at the centre of the current view.<br>Edit its exact tile with the Prop tool.</html>"));
		}

		public String text() {
			return text.getText();
		}

		/** The engine's sign type for the chosen style - see {@link NpcTemplates#SIGN_TYPES}. */
		public int signType() {
			return NpcTemplates.SIGN_TYPES[Math.max(0, style.getSelectedIndex())];
		}
	}

	/**
	 * The sign wizard past its form: refuses text that will not encode
	 * (saying so), else adds the sign case for the new story-text line -
	 * transplanting the sign routine from signDonor first when one is given -
	 * and places the sign furniture at pos, telling the user where. Returns
	 * false when nothing was added; the caller saves the script.
	 */
	public boolean addSign(Zone zone, GFMessageFile msg, String typed, final int signType, GFLPawnScript signDonor, Point pos) {
		String text = escapeTypedText(typed);
		try {
			GFMessageFile.write(java.util.Arrays.asList(text));
		} catch (RuntimeException ex) {
			ctrmap.Ui.error(this, "The text could not be encoded:\n" + ex.getMessage(), "Text encode error");
			return false;
		}
		int caseId;
		try {
			ZoneScriptEdit edit = new ZoneScriptEdit(zone, "the sign script")
					.withText(msg, zone.header.textID, java.util.Arrays.asList(text));
			if (signDonor != null) {
				edit.injectSignWrapperFrom(signDonor);
			}
			caseId = edit.apply((work, line) -> NpcTemplates.addSignScript(work, line, signType), this::writeStoryFile);
		} catch (ZoneScriptEdit.Refused ex) {
			ctrmap.Ui.error(this, ex.reason(), "Add sign");
			return false;
		}
		e.furniture.add(NpcTemplates.makeSignFurniture(caseId, pos.x, pos.y));
		e.furnitureCount = e.furniture.size();
		e.modified = true;
		ctrmap.Ui.message(this, "Sign added at tile (" + pos.x + ", " + pos.y + "). Adjust its position with the Prop tool.", "Add sign", JOptionPane.INFORMATION_MESSAGE);
		return true;
	}

	/**
	 * Item-giver template: clones the zone's vanilla give-item script and adds
	 * an NPC that hands over the item on interaction (repeatable).
	 */
	private void addGiverTemplate(Zone zone) {
		if (ZoneScriptAnalyzer.findGiveWrapper(zone.s) == null) {
			ctrmap.Ui.error(this, "This zone's script has no give-item routine (120 of 536 vanilla zones have one).\nPick a zone that already gives an item, or use pk3DS to place items differently.", "Add item giver");
			return;
		}
		GiverForm form = new GiverForm();
		if (!showForm(form.panel, "Add item giver")) {
			return;
		}
		if (addGiver(zone, form.item(), form.count(), form.model(), mTileMapPanel.getTileAtViewportCentre()) != null) {
			finishNpcAdd(zone, true);
		}
	}

	/** The item-giver form: which item, how many, which model. */
	public final class GiverForm {

		public final JPanel panel = stackedForm();
		private final IdChooser item = new IdChooser(loadGameTextNames(NpcTemplates.gametextItemNames()), NpcTemplates.ITEM_ID_MAX, 1);
		private final JSpinner count = new JSpinner(new javax.swing.SpinnerNumberModel(1, 1, 99, 1));
		private final ModelPicker model = new ModelPicker(-1);

		public GiverForm() {
			addLabeled(panel, "Item (type to search):", item);
			addLabeled(panel, "Quantity:", count);
			addLabeled(panel, "NPC model (type to search; preview below):", model);
			panel.add(hint("<html>The NPC is placed at the centre of the view and gives the item<br>each time it is talked to (no one-time flag yet).</html>"));
		}

		public int item() {
			return item.getId();
		}

		public int count() {
			return (Integer) count.getValue();
		}

		public int model() {
			return model.getSelectedUid();
		}
	}

	/**
	 * The item-giver wizard past its form: refuses a missing item or model
	 * (saying which), else adds the give-item case and the NPC record at pos.
	 * Returns the placed record, or null when nothing was added.
	 */
	public ZoneEntities.NPC addGiver(Zone zone, final int itemId, final int count, int model, Point pos) {
		if (itemId < 1) {
			ctrmap.Ui.error(this, "Select an item first.", "Add item giver");
			return null;
		}
		if (model < 0) {
			ctrmap.Ui.error(this, "Select an overworld model first.", "Add item giver");
			return null;
		}
		int caseId;
		try {
			caseId = new ZoneScriptEdit(zone, "the give-item script")
					.apply((work, line) -> NpcTemplates.addItemGiverScript(work, itemId, count), this::writeStoryFile);
		} catch (ZoneScriptEdit.Refused ex) {
			ctrmap.Ui.error(this, ex.reason(), "Add item giver");
			return null;
		}
		ZoneEntities.NPC placed = NpcTemplates.makeScriptedNpc(NpcTemplates.nextFreeUid(e), model, caseId, pos.x, pos.y);
		placeNpc(placed);
		return placed;
	}

	/**
	 * "Battle challenge" template: an NPC that battles the player's OWN trainer
	 * entries (Game Data -> Trainers) through the generic field battle natives -
	 * completely independent of the Battle Maison engine and its shared pools,
	 * so the retail game's opponents stay vanilla. Each talk runs one battle:
	 * the trainer for the current streak (a save-data work variable), BP on a
	 * win, streak reset on a loss. See {@link GauntletScriptWizard}.
	 */
	private void addChallengeTemplate(Zone zone) {
		ChallengeForm form = new ChallengeForm();
		if (!showForm(form.panel, "Add battle challenge")) {
			return;
		}
		if (addChallenge(zone, form.input(), mTileMapPanel.getTileAtViewportCentre()) != null) {
			finishNpcAdd(zone, true);
		}
	}

	/** What the battle-challenge form collects, as values. */
	public static final class ChallengeInput {

		public final java.util.List<Integer> trainerIds = new java.util.ArrayList<>();
		public int bpPerWin = 3;
		public int milestone = 0;
		public int milestoneBonus = 20;
		/** The streak save variable as typed: hex, with or without 0x. */
		public String streakWorkHex = Integer.toHexString(ctrmap.formats.scripts.GauntletScriptWizard.DEFAULT_STREAK_WORK);
		public boolean loseWhiteout = false;
		public String intro = "", win = "", lose = "";
		public int model = -1;
	}

	/** The battle-challenge form: the lineup, the BP rules, the three texts, the streak variable, the model. */
	public final class ChallengeForm {

		public final JPanel panel = stackedForm();
		private final java.util.List<Integer> trainerIds = new java.util.ArrayList<>();
		private final JSpinner bp = new JSpinner(new javax.swing.SpinnerNumberModel(3, 0, 999, 1));
		private final JSpinner milestone = new JSpinner(new javax.swing.SpinnerNumberModel(0, 0, 99, 1));
		private final JSpinner bonus = new JSpinner(new javax.swing.SpinnerNumberModel(20, 0, 9999, 1));
		private final javax.swing.JCheckBox whiteout = new javax.swing.JCheckBox("White out on defeat (engine loss handler)");
		private final JTextArea intro = textArea("", 2);
		private final JTextArea win = textArea("", 2);
		private final JTextArea lose = textArea("", 2);
		private final javax.swing.JTextField workVar = new javax.swing.JTextField(
				Integer.toHexString(ctrmap.formats.scripts.GauntletScriptWizard.DEFAULT_STREAK_WORK), 6);
		private final ModelPicker model = new ModelPicker(-1);

		public ChallengeForm() {
			final IdChooser idChooser = new IdChooser(loadGameTextNames(NpcTemplates.gametextTrainerNames()), NpcTemplates.TRAINER_ID_MAX, 1);
			final javax.swing.DefaultListModel<String> listModel = new javax.swing.DefaultListModel<>();
			final java.util.List<String> trainerNames = loadGameTextNames(NpcTemplates.gametextTrainerNames());
			final javax.swing.JList<String> trainerList = new javax.swing.JList<>(listModel);
			trainerList.setVisibleRowCount(5);
			javax.swing.JButton addBtn = new javax.swing.JButton("Add to lineup");
			javax.swing.JButton removeBtn = new javax.swing.JButton("Remove selected");
			addBtn.addActionListener(ev -> {
				int tid = idChooser.getId();
				if (tid >= 1 && tid <= 949) {
					trainerIds.add(tid);
					listModel.addElement("#" + trainerIds.size() + "  " + tid
							+ (trainerNames != null && tid < trainerNames.size() && trainerNames.get(tid) != null && !trainerNames.get(tid).isEmpty()
							? " " + trainerNames.get(tid) : ""));
				}
			});
			removeBtn.addActionListener(ev -> {
				int i = trainerList.getSelectedIndex();
				if (i >= 0) {
					trainerIds.remove(i);
					listModel.remove(i);
				}
			});
			addLabeled(panel, "Lineup (battle 1, 2, ... - the last repeats until a loss):", idChooser);
			JPanel listBtns = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
			listBtns.add(addBtn);
			listBtns.add(removeBtn);
			listBtns.setAlignmentX(LEFT_ALIGNMENT);
			panel.add(listBtns);
			JScrollPane listScroll = new JScrollPane(trainerList);
			listScroll.setAlignmentX(LEFT_ALIGNMENT);
			panel.add(listScroll);
			addLabeled(panel, "BP per win (0 = none):", bp);
			addLabeled(panel, "Bonus at streak (0 = no bonus):", milestone);
			addLabeled(panel, "Bonus BP:", bonus);
			addLabeled(panel, "Intro text (empty = none):", new JScrollPane(intro));
			addLabeled(panel, "Win text (empty = none):", new JScrollPane(win));
			addLabeled(panel, "Lose text (empty = none):", new JScrollPane(lose));
			addLabeled(panel, "Streak save variable (hex; script-corpus-free default):", workVar);
			whiteout.setAlignmentX(LEFT_ALIGNMENT);
			panel.add(whiteout);
			addLabeled(panel, "NPC model (type to search; preview below):", model);
			panel.add(hint("<html>The lineup takes ANY trainer entry: retail trainers (Youngsters, Ace Trainers...)<br>"
					+ "work AS-IS and are not modified by battling them here; for custom competitors,<br>"
					+ "repurpose a blank-named entry in Game Data -> Trainers (set its class, name and<br>"
					+ "team there - the class gives it the Youngster/Ace Trainer/... battle identity).<br>"
					+ "Fully independent of the retail facilities' shared pools - vanilla stays untouched.<br>"
					+ "Each talk = one battle at the current streak. UNPROVEN IN-GAME - test it first.</html>"));
		}

		public ChallengeInput input() {
			ChallengeInput in = new ChallengeInput();
			in.trainerIds.addAll(trainerIds);
			in.bpPerWin = (Integer) bp.getValue();
			in.milestone = (Integer) milestone.getValue();
			in.milestoneBonus = (Integer) bonus.getValue();
			in.streakWorkHex = workVar.getText();
			in.loseWhiteout = whiteout.isSelected();
			in.intro = intro.getText();
			in.win = win.getText();
			in.lose = lose.getText();
			in.model = model.getSelectedUid();
			return in;
		}
	}

	/**
	 * The battle-challenge wizard past its form: refuses an empty lineup, a
	 * missing model, a streak variable that is not hex, or text that will not
	 * encode (saying which); asks before transplanting the message routine a
	 * zone lacks when there is text to show; then adds the challenge case
	 * (with its lines) and places the NPC record at pos. Returns the placed
	 * record, or null when nothing was added.
	 */
	public ZoneEntities.NPC addChallenge(Zone zone, ChallengeInput in, Point pos) {
		final java.util.List<Integer> trainerIds = in.trainerIds;
		if (trainerIds.isEmpty()) {
			ctrmap.Ui.error(this, "Add at least one trainer to the lineup.", "Add battle challenge");
			return null;
		}
		int model = in.model;
		if (model < 0) {
			ctrmap.Ui.error(this, "Select an overworld model first.", "Add battle challenge");
			return null;
		}
		int workVar;
		try {
			workVar = Integer.parseInt(in.streakWorkHex.trim().replace("0x", ""), 16);
		} catch (NumberFormatException ex) {
			ctrmap.Ui.error(this, "The streak variable must be a hex number (e.g. 4020).", "Add battle challenge");
			return null;
		}
		String introText = escapeTypedText(in.intro);
		String winText = escapeTypedText(in.win);
		String loseText = escapeTypedText(in.lose);
		boolean needText = !introText.isEmpty() || !winText.isEmpty() || !loseText.isEmpty();
		java.util.List<String> newLines = new java.util.ArrayList<>();
		GFMessageFile msg = null;
		if (needText) {
			for (String t : new String[]{introText, winText, loseText}) {
				if (!t.isEmpty()) {
					try {
						GFMessageFile.write(java.util.Arrays.asList(t)); //validate before touching anything
					} catch (RuntimeException ex) {
						ctrmap.Ui.error(this, "A text could not be encoded:\n" + ex.getMessage(), "Text encode error");
						return null;
					}
				}
			}
			if (Workspace.getStoryTextGARC() == null) {
				ctrmap.Ui.error(this, "The STORYTEXT archive was not found in the game directory.", "Add battle challenge");
				return null;
			}
			msg = getStoryFile(zone.header.textID);
			if (msg == null) {
				ctrmap.Ui.error(this, "Story text file " + zone.header.textID + " could not be read.", "Add battle challenge");
				return null;
			}
		}
		//a zone without the message routine gets it transplanted as part of the
		//edit below - but the user is asked first, because it grows the script
		GFLPawnScript wrapperDonor = null;
		if (needText && ZoneScriptAnalyzer.findMsgWrapper(zone.s) == null) {
			try {
				wrapperDonor = loadWrapperDonor();
			} catch (RuntimeException ex) {
				ctrmap.Ui.error(this, "This zone's script has no message-display routine and no donor zone could provide one:\n" + ex.getMessage(), "Add battle challenge");
				return null;
			}
			int insCount = MsgWrapperInjector.countInjectedInstructions(zone.s, wrapperDonor);
			if (ctrmap.Ui.confirm(frame,
					"This zone's script has no message-display routine.\n"
					+ "Inject one (copied from the game's own code)?\n"
					+ "This adds " + insCount + " instructions (about 2.4 KB) to the zone script.",
					"Add battle challenge", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
				return null;
			}
		}
		final ctrmap.formats.scripts.GauntletScriptWizard.Config cfg = new ctrmap.formats.scripts.GauntletScriptWizard.Config();
		cfg.trainerIds = new int[trainerIds.size()];
		for (int i = 0; i < trainerIds.size(); i++) {
			cfg.trainerIds[i] = trainerIds.get(i);
		}
		cfg.bpPerWin = in.bpPerWin;
		cfg.milestone = in.milestone;
		cfg.milestoneBonus = in.milestoneBonus;
		cfg.streakWorkId = workVar;
		cfg.loseWhiteout = in.loseWhiteout;
		ZoneScriptEdit edit = new ZoneScriptEdit(zone, "the challenge script");
		if (wrapperDonor != null) {
			edit.injectMsgWrapperFrom(wrapperDonor);
		}
		int nextLine = msg != null ? msg.getLineCount() : 0;
		if (!introText.isEmpty()) {
			cfg.introLine = nextLine++;
			newLines.add(introText);
		}
		if (!winText.isEmpty()) {
			cfg.winLine = nextLine++;
			newLines.add(winText);
		}
		if (!loseText.isEmpty()) {
			cfg.loseLine = nextLine++;
			newLines.add(loseText);
		}
		if (msg != null && !newLines.isEmpty()) {
			edit.withText(msg, zone.header.textID, newLines);
		}
		int caseId;
		try {
			caseId = edit.apply((work, line) -> ctrmap.formats.scripts.GauntletScriptWizard.addChallengeScript(work, cfg),
					this::writeStoryFile);
		} catch (ZoneScriptEdit.Refused ex) {
			ctrmap.Ui.error(this, ex.reason(), "Add battle challenge");
			return null;
		}
		ZoneEntities.NPC placed = NpcTemplates.makeScriptedNpc(NpcTemplates.nextFreeUid(e), model, caseId, pos.x, pos.y);
		placeNpc(placed);
		return placed;
	}

	/**
	 * "Give BP" template: an NPC that, when talked to, adds N Battle Points to
	 * the player using the engine's own PlayerGetBP/PlayerSetBP natives (the
	 * exact BP-grant frame the Battle Maison lobby uses). Works in any zone with
	 * a script dispatch - no wrapper routine needed. Useful for facility rewards
	 * and for testing BP-driven shops/facilities.
	 */
	private void addGiveBpTemplate(Zone zone) {
		GiveBpForm form = new GiveBpForm();
		if (!showForm(form.panel, "Add Give BP")) {
			return;
		}
		if (addGiveBp(zone, form.amount(), form.model(), mTileMapPanel.getTileAtViewportCentre()) != null) {
			finishNpcAdd(zone, true);
		}
	}

	/** The Give-BP form: how many points, and which model. */
	public final class GiveBpForm {

		public final JPanel panel = stackedForm();
		private final JSpinner amount = new JSpinner(new javax.swing.SpinnerNumberModel(20, 1, 9999, 1));
		private final ModelPicker model = new ModelPicker(-1);

		public GiveBpForm() {
			addLabeled(panel, "Battle Points to give:", amount);
			addLabeled(panel, "NPC model (type to search; preview below):", model);
			panel.add(hint("<html>The NPC adds this many BP each time it is talked to (no one-time flag yet;<br>the game caps total BP at 9999). Uses the engine's own BP natives.</html>"));
		}

		public int amount() {
			return (Integer) amount.getValue();
		}

		public int model() {
			return model.getSelectedUid();
		}
	}

	/**
	 * The Give-BP wizard past its form: refuses a missing model (saying so),
	 * else adds the script case and the NPC record at pos. Returns the placed
	 * record, or null when nothing was added.
	 */
	public ZoneEntities.NPC addGiveBp(Zone zone, final int amount, int model, Point pos) {
		if (model < 0) {
			ctrmap.Ui.error(this, "Select an overworld model first.", "Add Give BP");
			return null;
		}
		int caseId;
		try {
			caseId = new ZoneScriptEdit(zone, "the Give BP script")
					.apply((work, line) -> ctrmap.formats.scripts.FacilityScriptWizard.addGiveBpScript(work, amount),
							this::writeStoryFile);
		} catch (ZoneScriptEdit.Refused ex) {
			ctrmap.Ui.error(this, ex.reason(), "Add Give BP");
			return null;
		}
		ZoneEntities.NPC placed = NpcTemplates.makeScriptedNpc(NpcTemplates.nextFreeUid(e), model, caseId, pos.x, pos.y);
		placeNpc(placed);
		return placed;
	}

	/**
	 * Trainer template: a pure NPC record (script = 3000 + trainer ID), with an
	 * optional double-battle partner. No script surgery.
	 */
	private void addTrainerTemplate(Zone zone) {
		TrainerForm form = new TrainerForm();
		if (!showForm(form.panel, "Add trainer")) {
			return;
		}
		if (addTrainer(zone, form.trainer(), form.model(), form.sight(), form.facing(), form.pair(),
				mTileMapPanel.getTileAtViewportCentre()) > 0) {
			finishNpcAdd(zone, false);
			repaintFrame();
		}
	}

	/** The trainer form: which trainer, model, sight range, facing, and whether a double-battle partner comes too. */
	public final class TrainerForm {

		public final JPanel panel = stackedForm();
		private final IdChooser trainer = new IdChooser(loadGameTextNames(NpcTemplates.gametextTrainerNames()), NpcTemplates.TRAINER_ID_MAX, 1);
		private final ModelPicker model = new ModelPicker(-1);
		private final JSpinner sight = new JSpinner(new javax.swing.SpinnerNumberModel(0, 0, 8, 1));
		private final javax.swing.JComboBox<String> facing = new javax.swing.JComboBox<>(new String[]{"Down", "Up", "Left", "Right"});
		private final javax.swing.JCheckBox pair = new javax.swing.JCheckBox("Add double-battle partner (script 5000 + ID) beside it");

		public TrainerForm() {
			addLabeled(panel, "Trainer (type to search; edit party/class in pk3DS):", trainer);
			addLabeled(panel, "NPC model (type to search; preview below):", model);
			addLabeled(panel, "Sight range (0 = battle on talk only):", sight);
			addLabeled(panel, "Facing:", facing);
			pair.setAlignmentX(LEFT_ALIGNMENT);
			panel.add(pair);
			panel.add(hint("<html>Places the overworld trainer NPC only. The battle exists only if<br>trainer data slot ID is valid (set it in pk3DS).</html>"));
		}

		public int trainer() {
			return trainer.getId();
		}

		public int model() {
			return model.getSelectedUid();
		}

		public int sight() {
			return (Integer) sight.getValue();
		}

		/** 0=down 1=up 2=left 3=right. */
		public int facing() {
			return Math.max(0, facing.getSelectedIndex());
		}

		public boolean pair() {
			return pair.isSelected();
		}
	}

	/**
	 * The trainer wizard past its form: refuses a missing trainer or model
	 * (saying so), else places the trainer record at pos - and its
	 * double-battle partner one tile right when asked. Returns how many
	 * records were placed, 0 when refused; the caller saves the zone.
	 */
	public int addTrainer(Zone zone, int tid, int model, int sight, int face, boolean pair, Point pos) {
		if (tid < 1) {
			ctrmap.Ui.error(this, "Select a trainer first.", "Add trainer");
			return 0;
		}
		if (model < 0) {
			ctrmap.Ui.error(this, "Select an overworld model first.", "Add trainer");
			return 0;
		}
		int placed = 0;
		try {
			placeNpc(NpcTemplates.makeTrainerNpc(NpcTemplates.nextFreeUid(e), tid, model, sight, face, pos.x, pos.y));
			placed++;
			if (pair) {
				placeNpc(NpcTemplates.makeTrainerPairNpc(NpcTemplates.nextFreeUid(e), tid, model, sight, face, pos.x + 1, pos.y));
				placed++;
			}
		} catch (RuntimeException ex) {
			ctrmap.Ui.error(this, "Could not add the trainer:\n" + ex.getMessage(), "Add trainer");
		}
		return placed;
	}

	/**
	 * Adds an NPC record to the loaded zone and shows it in this form - the
	 * part of every Add that needs no window, so the {@code add*} methods can
	 * do it and a suite can watch the record land.
	 */
	private void placeNpc(ZoneEntities.NPC newNPC) {
		loaded = false;
		e.npcs.add(newNPC);
		e.NPCCount = e.npcs.size();
		entryBox.addItem(String.valueOf(newNPC.uid));
		loaded = true;
		e.modified = true;
		populateScriptDropdown();
		setNPC(entryBox.getItemCount() - 1);
	}

	/**
	 * Saves what an Add placed, through the main window's panels. When
	 * saveScript is true the zone script was changed and is persisted too.
	 */
	private void finishNpcAdd(Zone zone, boolean saveScript) {
		if (saveScript) {
			saveZoneScript(zone);
		} else {
			mZonePnl.store(false);
		}
	}

	private void saveZoneScript(Zone zone) {
		mZonePnl.store(false); //same path ScriptEditor uses to save the zone script
		mScriptPnl.loadScript(zone.s);
	}

	/**
	 * Loads a GameText name list (item names, trainer names) for the item/trainer
	 * pickers. ORAS-only - the file indices differ on X/Y, so this returns null
	 * there and the pickers fall back to a numeric spinner.
	 */
	private java.util.List<String> loadGameTextNames(int fileIndex) {
		if (!Workspace.isOA() || Workspace.getArchive(Workspace.ArchiveType.GAMETEXT) == null) {
			return null;
		}
		try {
			byte[] raw = Workspace.getArchive(Workspace.ArchiveType.GAMETEXT).getDecompressedEntry(fileIndex);
			return raw == null ? null : GFMessageFile.getStrings(raw);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/**
	 * A searchable ID picker: a filter field over a "id: name" list when names
	 * are available, or a plain numeric spinner otherwise. getId() returns the
	 * chosen 1-based ID, or -1 when the filtered list has no selection.
	 */
	private static class IdChooser extends JPanel {

		private javax.swing.JList<String> list;
		private final java.util.List<Integer> ids = new java.util.ArrayList<>();
		private JSpinner spinner;

		IdChooser(java.util.List<String> names, int maxId, int defaultId) {
			setLayout(new java.awt.BorderLayout());
			if (names == null || names.size() <= 1) {
				spinner = new JSpinner(new javax.swing.SpinnerNumberModel(defaultId, 1, maxId, 1));
				add(spinner, java.awt.BorderLayout.CENTER);
				return;
			}
			final java.util.List<String> entries = new java.util.ArrayList<>();
			final java.util.List<Integer> baseIds = new java.util.ArrayList<>();
			for (int i = 1; i <= maxId && i < names.size(); i++) {
				String nm = names.get(i);
				if (nm == null || nm.isEmpty() || nm.equals("-")) {
					continue;
				}
				entries.add(i + ": " + nm);
				baseIds.add(i);
			}
			final javax.swing.DefaultListModel<String> model = new javax.swing.DefaultListModel<>();
			list = new javax.swing.JList<>(model);
			list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
			final javax.swing.JTextField filter = new javax.swing.JTextField();
			final Runnable rebuild = new Runnable() {
				@Override
				public void run() {
					String f = filter.getText().toLowerCase();
					model.clear();
					ids.clear();
					for (int k = 0; k < entries.size(); k++) {
						if (f.isEmpty() || entries.get(k).toLowerCase().contains(f)) {
							model.addElement(entries.get(k));
							ids.add(baseIds.get(k));
						}
					}
					if (!model.isEmpty() && list.getSelectedIndex() < 0) {
						list.setSelectedIndex(0);
					}
				}
			};
			filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
				@Override public void insertUpdate(javax.swing.event.DocumentEvent e) { rebuild.run(); }
				@Override public void removeUpdate(javax.swing.event.DocumentEvent e) { rebuild.run(); }
				@Override public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuild.run(); }
			});
			rebuild.run();
			for (int k = 0; k < ids.size(); k++) {
				if (ids.get(k) == defaultId) {
					list.setSelectedIndex(k);
					break;
				}
			}
			add(filter, java.awt.BorderLayout.NORTH);
			JScrollPane sc = new JScrollPane(list);
			sc.setPreferredSize(new java.awt.Dimension(280, 150));
			add(sc, java.awt.BorderLayout.CENTER);
		}

		int getId() {
			if (spinner != null) {
				return (Integer) spinner.getValue();
			}
			int sel = list.getSelectedIndex();
			return (sel >= 0 && sel < ids.size()) ? ids.get(sel) : -1;
		}
	}

	private void addLabeled(JPanel panel, String label, java.awt.Component field) {
		JLabel l = new JLabel(label);
		l.setAlignmentX(LEFT_ALIGNMENT);
		panel.add(l);
		if (field instanceof javax.swing.JComponent) {
			((javax.swing.JComponent) field).setAlignmentX(LEFT_ALIGNMENT);
		}
		panel.add(field);
	}

	private final java.util.List<CustomH3DPreview> activePreviews = new java.util.ArrayList<>();

	/**
	 * Wraps a MoveModel-UID spinner with a live 3D preview of the model, so the
	 * user sees the NPC they are placing and can flip through UIDs. Uses a fresh
	 * model instance (not the shared registry cache) so the preview cannot
	 * disturb the main viewport's rendering.
	 */
	private JPanel modelWithPreview(final JSpinner modelSpinner) {
		JPanel wrap = new JPanel(new java.awt.BorderLayout());
		wrap.setAlignmentX(LEFT_ALIGNMENT);
		wrap.add(modelSpinner, java.awt.BorderLayout.NORTH);
		final CustomH3DPreview preview = new CustomH3DPreview();
		preview.setPreferredSize(new java.awt.Dimension(200, 200));
		wrap.add(preview, java.awt.BorderLayout.CENTER);
		final Runnable reload = new Runnable() {
			@Override
			public void run() {
				preview.loadModel(reg == null ? null : reg.loadFreshModel((Integer) modelSpinner.getValue()));
			}
		};
		modelSpinner.addChangeListener(new javax.swing.event.ChangeListener() {
			@Override
			public void stateChanged(javax.swing.event.ChangeEvent e) {
				reload.run();
			}
		});
		reload.run();
		activePreviews.add(preview);
		return wrap;
	}

	/**
	 * Stops the render loops of any preview widgets from a just-closed dialog.
	 * Public because a form's model picker starts its preview's animator the
	 * moment it is built, and that thread is not a daemon: a suite that builds
	 * a form without showing it must call this, or its JVM never exits.
	 */
	public void disposePreviews() {
		for (CustomH3DPreview p : activePreviews) {
			p.stop();
		}
		activePreviews.clear();
	}

	/**
	 * Shows a wizard's form and waits for OK. The ONE place this form opens a
	 * dialog with a live component in it, which is why it is not a
	 * {@link ctrmap.Ui} call: the seam carries a String on purpose, and a
	 * recording of "JPanel[...]" would assert nothing. What a suite can do
	 * instead is everything around this call - build the same form
	 * ({@code new TalkerForm(...)} and the others), read its defaults, and
	 * hand its values to the {@code add*} method the handler hands them to.
	 * DialogSeamTest counts this call, and only this one, for this file.
	 */
	private boolean showForm(javax.swing.JComponent body, String title) {
		int rsl = JOptionPane.showConfirmDialog(frame, body, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		disposePreviews();
		return rsl == JOptionPane.OK_OPTION;
	}

	/** A form's widgets stacked top to bottom, each behind its label. */
	private JPanel stackedForm() {
		JPanel panel = new JPanel();
		panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
		return panel;
	}

	private static JLabel hint(String html) {
		JLabel hint = new JLabel(html);
		hint.setAlignmentX(LEFT_ALIGNMENT);
		return hint;
	}

	private static JTextArea textArea(String text, int rows) {
		JTextArea ta = new JTextArea(text, rows, 40);
		ta.setLineWrap(true);
		ta.setWrapStyleWord(true);
		return ta;
	}

	/**
	 * A searchable NPC-model picker with a live 3D preview. Lists ONLY the
	 * registered overworld models (from the NPC registry) as "UID: name" - so
	 * empty/unregistered UIDs never clutter the browser - and previews the
	 * selection. Names come from each model's embedded BCH name.
	 */
	private class ModelPicker extends JPanel {

		private final javax.swing.JList<String> list = new javax.swing.JList<>();
		// each entry is {kind, value}: kind 0 = registered UID, kind 1 = global MoveModels index
		private final List<int[]> visibleEntries = new ArrayList<>();
		private final List<int[]> allEntries = new ArrayList<>();
		private final List<String> allLabels = new ArrayList<>();
		private final List<int[]> registered = new ArrayList<>();
		private final List<String> registeredLabels = new ArrayList<>();
		private final List<int[]> poolExtra = new ArrayList<>();
		private final List<String> poolExtraLabels = new ArrayList<>();
		private boolean poolLoaded = false;
		private final CustomH3DPreview preview = new CustomH3DPreview();
		private final javax.swing.JTextField filter = new javax.swing.JTextField();
		private final javax.swing.JCheckBox showAll = new javax.swing.JCheckBox("Browse ALL game NPC models (adds the one you pick to this area)");
		private final javax.swing.DefaultListModel<String> listModel = new javax.swing.DefaultListModel<>();

		ModelPicker(int defaultUid) {
			setLayout(new java.awt.BorderLayout());
			if (reg != null) {
				List<Integer> keys = new ArrayList<>(reg.entries.keySet());
				Collections.sort(keys);
				for (int uid : keys) {
					H3DModel m = reg.getModel(uid);
					String nm = (m != null && m.name != null) ? m.name.trim() : "";
					registered.add(new int[]{0, uid});
					registeredLabels.add(uid + (nm.isEmpty() ? "" : ": " + nm));
				}
			}
			list.setModel(listModel);
			list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
			filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
				@Override public void insertUpdate(javax.swing.event.DocumentEvent e) { rebuild(); }
				@Override public void removeUpdate(javax.swing.event.DocumentEvent e) { rebuild(); }
				@Override public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuild(); }
			});
			showAll.addActionListener((java.awt.event.ActionEvent e) -> {
				if (showAll.isSelected() && !poolLoaded) {
					setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
					try {
						loadFullPool();
					} finally {
						setCursor(java.awt.Cursor.getDefaultCursor());
					}
				}
				buildEntries();
				rebuild();
			});
			list.addListSelectionListener((javax.swing.event.ListSelectionEvent e) -> {
				if (!e.getValueIsAdjusting()) {
					updatePreview();
				}
			});
			buildEntries();
			rebuild();
			for (int k = 0; k < visibleEntries.size(); k++) {
				int[] en = visibleEntries.get(k);
				if (en[0] == 0 && en[1] == defaultUid) {
					list.setSelectedIndex(k);
					break;
				}
			}
			JScrollPane listScroll = new JScrollPane(list);
			listScroll.setPreferredSize(new java.awt.Dimension(300, 120));
			preview.setPreferredSize(new java.awt.Dimension(300, 190));
			javax.swing.JPanel top = new javax.swing.JPanel(new java.awt.BorderLayout());
			top.add(filter, java.awt.BorderLayout.NORTH);
			top.add(showAll, java.awt.BorderLayout.SOUTH);
			add(top, java.awt.BorderLayout.NORTH);
			add(listScroll, java.awt.BorderLayout.CENTER);
			add(preview, java.awt.BorderLayout.SOUTH);
			activePreviews.add(preview);
			updatePreview();
		}

		private void loadFullPool() {
			poolExtra.clear();
			poolExtraLabels.clear();
			java.util.Set<Integer> already = new java.util.HashSet<>();
			if (reg != null) {
				for (NPCRegistry.NPCRegistryEntry en : reg.entries.values()) {
					already.add(en.model);
				}
			}
			int n = ctrmap.formats.npcreg.MoveModelPool.size();
			for (int i = 0; i < n; i++) {
				if (already.contains(i)) {
					continue; //already offered via the registered list
				}
				String nm = ctrmap.formats.npcreg.MoveModelPool.name(i);
				poolExtra.add(new int[]{1, i});
				poolExtraLabels.add("[+] model " + i + (nm == null || nm.isEmpty() ? "" : ": " + nm));
			}
			poolLoaded = true;
		}

		private void buildEntries() {
			allEntries.clear();
			allLabels.clear();
			allEntries.addAll(registered);
			allLabels.addAll(registeredLabels);
			if (showAll.isSelected()) {
				allEntries.addAll(poolExtra);
				allLabels.addAll(poolExtraLabels);
			}
		}

		private void rebuild() {
			String f = filter.getText().toLowerCase();
			listModel.clear();
			visibleEntries.clear();
			for (int k = 0; k < allEntries.size(); k++) {
				if (f.isEmpty() || allLabels.get(k).toLowerCase().contains(f)) {
					listModel.addElement(allLabels.get(k));
					visibleEntries.add(allEntries.get(k));
				}
			}
			if (!listModel.isEmpty() && list.getSelectedIndex() < 0) {
				list.setSelectedIndex(0);
			}
		}

		/**
		 * The chosen UID. If the user picked a global model that is not yet in this
		 * area, it is registered on demand (and the new UID returned). Returns -1
		 * when nothing valid is selected or the area's registry is full.
		 */
		int getSelectedUid() {
			int s = list.getSelectedIndex();
			if (s < 0 || s >= visibleEntries.size()) {
				return -1;
			}
			int[] en = visibleEntries.get(s);
			if (en[0] == 0) {
				return en[1]; //already a registered UID
			}
			int uid = (reg != null) ? reg.registerModel(en[1]) : -1;
			if (uid < 0) {
				//through Ui, not JOptionPane: this is the only thing that
				//distinguishes "the model you picked is now on the NPC" from
				//"nothing happened", and a bare dialog is neither reachable nor
				//observable from a guard
				ctrmap.Ui.error(this,
						"This area's NPC registry is full (max " + NPCRegistry.MAX_ENTRIES + " unique models).\n"
						+ "Remove an unused model in the NPC registry editor and try again.",
						"Registry full");
			}
			return uid;
		}

		boolean hasModels() {
			return !registered.isEmpty();
		}

		private void updatePreview() {
			int s = list.getSelectedIndex();
			if (s < 0 || s >= visibleEntries.size()) {
				preview.loadModel(null);
				return;
			}
			int[] en = visibleEntries.get(s);
			H3DModel m = (en[0] == 0)
					? ((reg != null) ? reg.loadFreshModel(en[1]) : null)
					: NPCRegistry.loadFreshModelByIndex(en[1]);
			preview.loadModel(m);
		}
	}

	private void scrDropdownActionPerformed(java.awt.event.ActionEvent evt) {
		if (loaded && !scrDropdownLoading && scrDropdown.getSelectedIndex() != -1) {
			int id = parseLeadingInt((String) scrDropdown.getSelectedItem());
			if (id != Integer.MIN_VALUE) {
				scr.setValue(id);
			}
		}
	}

	public void setNPC(int num) {
		if (loaded && saveEntry()) { //a refused save keeps the user on the NPC that needs fixing
			entryBox.setSelectedIndex(num);
		}
	}

	public void commitAndSwitch(int switchNum) {
		btnSave.requestFocus(); //first focus something else so that the listener is called
		FocusAdapter adapter = new FocusAdapter() {
			@Override
			public void focusGained(FocusEvent e) {
				entryBox.removeFocusListener(this);
				setNPC(switchNum);
			}
		};
		entryBox.addFocusListener(adapter);
		entryBox.requestFocus();
	}

	public void setIntegerValueClass(JFormattedTextField[] fields) {
		for (int i = 0; i < fields.length; i++) {
			((NumberFormatter) fields[i].getFormatter()).setValueClass(Integer.class);
		}
	}

	public void updateH3D(int index) {
		H3DModel m = modelAt(index);
		if (m == null) {
			return;
		}
		ZoneEntities.NPC n = e.npcs.get(index);
		m.worldLocX = n.xTile * 18f + 9f; //720 (chunk size) / 40 (tile width per chunk)
		m.worldLocZ = n.yTile * 18f + 9f;
		m.worldLocY = n.z3DCoordinate;
		m.rotationY = get3DOrientation(n.faceDirection);
	}

	public float get3DOrientation(int faceDirection) {
		switch (faceDirection) {
			case 0:
				return 180f;
			case 1:
				return 0f;
			case 2:
				return 270f;
			case 3:
				return 90f;
			default:
				return 0f;
		}
	}

	/**
	 * The NPC slots this form has a model for, in order. The viewport draws
	 * exactly these and the click test walks exactly these, so the two cannot
	 * disagree and land a click on an NPC that was never drawn; with no
	 * registry, or no zone, there is nothing to draw at all.
	 *
	 * <p>It is a method of its own because both callers hold a live GL context
	 * and no test can. The GL calls stay untested; which NPCs they apply to
	 * does not have to be, and as an inline condition it was a mutation
	 * survivor in all three of them.
	 */
	public int[] modelledNPCs() {
		if (reg == null || e == null) {
			return new int[0];
		}
		//a count past the end of the list is a corrupt zone, not a reason to throw mid-frame
		int[] hits = new int[Math.min(e.NPCCount, e.npcs.size())];
		int n = 0;
		for (int i = 0; i < hits.length; i++) {
			if (modelAt(i) != null) {
				hits[n++] = i;
			}
		}
		return Arrays.copyOf(hits, n);
	}

	/**
	 * The models whose GL buffers this form owns - the registry's, or none
	 * without a registry. Same reason as {@link #modelledNPCs()}: uploading
	 * and deleting differ only in the GL call made on each, so the decision
	 * they share is the only part a guard can hold.
	 */
	public Collection<H3DModel> ownedModels() {
		if (reg == null) {
			return Collections.emptyList();
		}
		return reg.models.values();
	}

	/**
	 * Whether the viewport outlines the NPC in slot i: the one this form is
	 * editing, and only while the NPC tool is the tool in hand. The outline
	 * means "this is the record the NPC tool acts on", so it must not stand
	 * around any other NPC, and must not stand at all under a tool that would
	 * do something else with a click.
	 *
	 * <p>A method of its own for the same reason as {@link #modelledNPCs()}:
	 * its only caller is handed a live GL context and no test has one. The
	 * renderBox call stays untested; which NPC it applies to no longer is.
	 */
	public boolean boxedNPC(int i) {
		return i == npcIndex && CtrmapMainframe.tool instanceof NPCTool;
	}

	@Override
	public void renderCM3D(GL2 gl) {
		for (int i : modelledNPCs()) {
			updateH3D(i);
			H3DModel m = modelAt(i);
			m.render(gl);
			if (boxedNPC(i)) {
				m.renderBox(gl);
			}
		}
	}

	@Override
	public void uploadBuffers(GL2 gl) {
		for (H3DModel m : ownedModels()) {
			m.uploadAllBOs(gl);
		}
	}

	@Override
	public void deleteGLInstanceBuffers(GL2 gl) {
		for (H3DModel m : ownedModels()) {
			m.destroyAllBOs(gl);
		}
	}

	@Override
	public void renderOverlayCM3D(GL2 gl) {
	}

	/**
	 * This method is called from within the constructor to initialize the form.
	 * WARNING: Do NOT modify this code. The content of this method is always
	 * regenerated by the Form Editor.
	 */
	@SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        mdlLabel = new javax.swing.JLabel();
        evtFlagLabel = new javax.swing.JLabel();
        mdl = new javax.swing.JSpinner();
        evtFlag = new javax.swing.JSpinner();
        scrLabel = new javax.swing.JLabel();
        scr = new javax.swing.JSpinner();
        headerSep = new javax.swing.JSeparator();
        worldLabel = new javax.swing.JLabel();
        xLabel = new javax.swing.JLabel();
        altitude = new javax.swing.JFormattedTextField();
        yLabel = new javax.swing.JLabel();
        y = new javax.swing.JFormattedTextField();
        altitudeLabel = new javax.swing.JLabel();
        x = new javax.swing.JFormattedTextField();
        worldLocNote = new javax.swing.JLabel();
        areaWLabel = new javax.swing.JLabel();
        areaW = new javax.swing.JFormattedTextField();
        areaHLabel = new javax.swing.JLabel();
        areaH = new javax.swing.JFormattedTextField();
        facedirLabel = new javax.swing.JLabel();
        facedir = new javax.swing.JSpinner();
        rangeLabel = new javax.swing.JLabel();
        range = new javax.swing.JSpinner();
        aiLabel = new javax.swing.JLabel();
        motLabel = new javax.swing.JLabel();
        mot = new javax.swing.JFormattedTextField();
        aiSep = new javax.swing.JSeparator();
        horizonLabel = new javax.swing.JLabel();
        worldSep = new javax.swing.JSeparator();
        mp2Label = new javax.swing.JLabel();
        mp2 = new javax.swing.JFormattedTextField();
        u10Label = new javax.swing.JLabel();
        u10 = new javax.swing.JFormattedTextField();
        areaSX = new javax.swing.JFormattedTextField();
        u12Label = new javax.swing.JLabel();
        u12 = new javax.swing.JFormattedTextField();
        u14Label = new javax.swing.JLabel();
        zl2 = new javax.swing.JFormattedTextField();
        u16Label = new javax.swing.JLabel();
        areaSY = new javax.swing.JFormattedTextField();
        zl2Label = new javax.swing.JLabel();
        zl3Label = new javax.swing.JLabel();
        zl3 = new javax.swing.JFormattedTextField();
        entryBox = new javax.swing.JComboBox<>();
        btnNewEntry = new javax.swing.JButton();
        btnRemoveEntry = new javax.swing.JButton();
        btnSave = new javax.swing.JButton();
        btnRegEdit = new javax.swing.JButton();
        zlLabel = new javax.swing.JLabel();
        hostZoneLabel = new javax.swing.JLabel();
        originZoneLabel = new javax.swing.JLabel();
        hostZone = new javax.swing.JFormattedTextField();
        linkIDLabel = new javax.swing.JLabel();
        linkID = new javax.swing.JFormattedTextField();
        originZone = new javax.swing.JFormattedTextField();
        linkedZoneLabel = new javax.swing.JLabel();
        linkedZone = new javax.swing.JFormattedTextField();
        zlSep = new javax.swing.JSeparator();
        motDropdown = new javax.swing.JComboBox<>();
        mot2Dropdown = new javax.swing.JComboBox<>();
        scrDropdown = new javax.swing.JComboBox<>();
        dlgSep = new javax.swing.JSeparator();
        dlgLabel = new javax.swing.JLabel();
        dlgStatus = new javax.swing.JLabel();
        dlgScrollPane = new javax.swing.JScrollPane();
        dlgPreview = new javax.swing.JTextArea();
        btnEditDialogue = new javax.swing.JButton();
        btnAddTalker = new javax.swing.JButton();

        mdlLabel.setText("Model");

        evtFlagLabel.setText("Spawn condition event flag");

        mdl.setModel(new javax.swing.SpinnerNumberModel(0, null, 65535, 1));
        mdl.setMinimumSize(new java.awt.Dimension(50, 20));
        mdl.setPreferredSize(new java.awt.Dimension(50, 20));
        mdl.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                mdlStateChanged(evt);
            }
        });

        evtFlag.setModel(new javax.swing.SpinnerNumberModel(0, null, 65535, 1));
        evtFlag.setMinimumSize(new java.awt.Dimension(50, 20));
        evtFlag.setPreferredSize(new java.awt.Dimension(50, 20));

        scrLabel.setText("Script");

        scr.setMinimumSize(new java.awt.Dimension(50, 20));
        scr.setPreferredSize(new java.awt.Dimension(50, 20));

        worldLabel.setText("World properties:");

        xLabel.setText("X");

        altitude.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#,##0.00"))));

        yLabel.setText("Y");

        y.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        altitudeLabel.setText("Altitude");

        x.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        worldLocNote.setForeground(new java.awt.Color(102, 102, 102));
        worldLocNote.setText("<html>\nNote: X and Y coordinates are specified in grid tiles while the altitude is<br/> a float that you can either take from the collision mesh at the NPC's postition<br/>or go crazy. Altitude represents the 3D world Y position.\n</html>");

        areaWLabel.setText("Move area width");

        areaW.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        areaHLabel.setText("Move area height");

        areaH.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        facedirLabel.setText("Default face direction");

        facedir.setMinimumSize(new java.awt.Dimension(50, 20));
        facedir.setPreferredSize(new java.awt.Dimension(50, 20));

        rangeLabel.setText("Sight range");

        range.setMinimumSize(new java.awt.Dimension(50, 20));
        range.setPreferredSize(new java.awt.Dimension(50, 20));

        aiLabel.setText("AI properties:");

        motLabel.setText("Motion AI");

        mot.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
        mot.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                motActionPerformed(evt);
            }
        });

        horizonLabel.setText("There's much to do and many unknowns on the horizon:");

        mp2Label.setText("Move events");

        mp2.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
        mp2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mp2ActionPerformed(evt);
            }
        });

        u10Label.setText("U10");

        u10.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        areaSX.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
        areaSX.setMinimumSize(new java.awt.Dimension(100, 20));

        u12Label.setText("U12");

        u12.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        u14Label.setText("Move area rel. X");

        zl2.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        u16Label.setText("Move area rel. Y");
        u16Label.setMaximumSize(new java.awt.Dimension(84, 14));
        u16Label.setMinimumSize(new java.awt.Dimension(84, 14));
        u16Label.setPreferredSize(new java.awt.Dimension(84, 14));

        areaSY.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
        areaSY.setMinimumSize(new java.awt.Dimension(100, 20));

        zl2Label.setText("ZL2");

        zl3Label.setText("ZL3");

        zl3.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        entryBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                entryBoxActionPerformed(evt);
            }
        });

        btnNewEntry.setText("New entry");
        btnNewEntry.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnNewEntryActionPerformed(evt);
            }
        });

        btnRemoveEntry.setText("Remove entry");
        btnRemoveEntry.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRemoveEntryActionPerformed(evt);
            }
        });

        btnSave.setText("Save");
        btnSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSaveActionPerformed(evt);
            }
        });

        btnRegEdit.setForeground(new java.awt.Color(255, 51, 51));
        btnRegEdit.setText("[DANGER] Edit registry data");
        btnRegEdit.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRegEditActionPerformed(evt);
            }
        });

        zlLabel.setText("Zone linking:");

        hostZoneLabel.setText("Host zone");

        originZoneLabel.setText("Origin zone");

        hostZone.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
        hostZone.setMinimumSize(new java.awt.Dimension(100, 20));

        linkIDLabel.setText("Link ID");

        linkID.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
        linkID.setMinimumSize(new java.awt.Dimension(100, 20));
        linkID.setPreferredSize(new java.awt.Dimension(100, 20));

        originZone.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
        originZone.setMinimumSize(new java.awt.Dimension(100, 20));

        linkedZoneLabel.setText("Linked zone");

        linkedZone.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));
        linkedZone.setMinimumSize(new java.awt.Dimension(100, 20));

        motDropdown.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                motDropdownActionPerformed(evt);
            }
        });

        mot2Dropdown.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mot2DropdownActionPerformed(evt);
            }
        });

        scrDropdown.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                scrDropdownActionPerformed(evt);
            }
        });

        dlgLabel.setText("Dialogue:");

        dlgStatus.setForeground(new java.awt.Color(102, 102, 102));
        dlgStatus.setText("No zone loaded.");

        dlgPreview.setEditable(false);
        dlgPreview.setLineWrap(true);
        dlgPreview.setWrapStyleWord(true);
        dlgPreview.setRows(3);
        dlgScrollPane.setViewportView(dlgPreview);

        btnEditDialogue.setText("Edit dialogue...");
        btnEditDialogue.setEnabled(false);
        btnEditDialogue.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnEditDialogueActionPerformed(evt);
            }
        });

        btnAddTalker.setText("Add NPC / object...");
        btnAddTalker.setEnabled(false);
        btnAddTalker.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnAddTalkerActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(zl2Label)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(zl2, javax.swing.GroupLayout.PREFERRED_SIZE, 100, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(zl3Label)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(zl3, javax.swing.GroupLayout.PREFERRED_SIZE, 100, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(u10Label)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(u10, javax.swing.GroupLayout.PREFERRED_SIZE, 100, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(u12Label)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(u12, javax.swing.GroupLayout.PREFERRED_SIZE, 100, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(headerSep)
                            .addComponent(btnRegEdit, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(dlgSep)
                            .addComponent(dlgScrollPane)
                            .addComponent(btnEditDialogue, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(btnAddTalker, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addGroup(layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(dlgLabel)
                                    .addComponent(dlgStatus))
                                .addGap(0, 0, Short.MAX_VALUE))
                            .addComponent(btnSave, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(btnRemoveEntry, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(btnNewEntry, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(worldSep)
                            .addComponent(entryBox, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(xLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(x)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(yLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(y)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(altitudeLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(altitude))
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(mdlLabel)
                                .addGap(3, 3, 3)
                                .addComponent(mdl, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(evtFlagLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(evtFlag, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(scrLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(scr, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(scrDropdown, javax.swing.GroupLayout.PREFERRED_SIZE, 110, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(facedirLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(facedir, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(rangeLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(range, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                            .addComponent(worldLocNote, javax.swing.GroupLayout.DEFAULT_SIZE, 387, Short.MAX_VALUE)
                            .addComponent(zlSep)
                            .addComponent(aiSep)
                            .addGroup(layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                                    .addGroup(javax.swing.GroupLayout.Alignment.LEADING, layout.createSequentialGroup()
                                        .addComponent(zlLabel)
                                        .addGap(0, 0, Short.MAX_VALUE))
                                    .addGroup(javax.swing.GroupLayout.Alignment.LEADING, layout.createSequentialGroup()
                                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                            .addComponent(originZoneLabel)
                                            .addComponent(hostZoneLabel))
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                            .addComponent(linkID, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                            .addComponent(hostZone, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                            .addComponent(originZone, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(linkedZoneLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(linkedZone, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                            .addGroup(layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                                    .addComponent(aiLabel, javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(worldLabel, javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(linkIDLabel, javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(horizonLabel, javax.swing.GroupLayout.Alignment.LEADING))
                                .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                                        .addComponent(areaWLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                        .addComponent(u14Label, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                                    .addComponent(motLabel)
                                    .addComponent(mp2Label))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(areaW)
                                    .addComponent(areaSX, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                    .addComponent(mot)
                                    .addComponent(mp2))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addGroup(layout.createSequentialGroup()
                                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                            .addComponent(areaHLabel)
                                            .addComponent(u16Label, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                            .addComponent(areaH)
                                            .addComponent(areaSY, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                                    .addComponent(motDropdown, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                    .addComponent(mot2Dropdown, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))))
                        .addContainerGap())))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(entryBox, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(mdlLabel)
                    .addComponent(evtFlagLabel)
                    .addComponent(mdl, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(evtFlag, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(scrLabel)
                    .addComponent(scr, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(scrDropdown, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(headerSep, javax.swing.GroupLayout.PREFERRED_SIZE, 2, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(worldLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(xLabel)
                    .addComponent(altitude, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(yLabel)
                    .addComponent(y, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(altitudeLabel)
                    .addComponent(x, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(worldLocNote, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(facedirLabel)
                    .addComponent(facedir, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(rangeLabel)
                    .addComponent(range, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(4, 4, 4)
                .addComponent(worldSep, javax.swing.GroupLayout.PREFERRED_SIZE, 2, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(aiLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(u14Label)
                    .addComponent(areaSX, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(u16Label, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(areaSY, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(areaWLabel)
                    .addComponent(areaHLabel)
                    .addComponent(areaH, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(areaW, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(motLabel)
                    .addComponent(mot, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(motDropdown, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(mp2Label)
                    .addComponent(mp2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(mot2Dropdown, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(aiSep, javax.swing.GroupLayout.PREFERRED_SIZE, 2, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(zlLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(hostZoneLabel)
                    .addComponent(hostZone, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(originZoneLabel)
                    .addComponent(originZone, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(linkedZoneLabel)
                    .addComponent(linkedZone, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(linkIDLabel)
                    .addComponent(linkID, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(zlSep, javax.swing.GroupLayout.PREFERRED_SIZE, 2, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(horizonLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(u10Label)
                    .addComponent(u10, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(u12Label)
                    .addComponent(u12, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(zl2Label)
                    .addComponent(zl2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(zl3Label)
                    .addComponent(zl3, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnNewEntry)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnRemoveEntry)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnSave)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnRegEdit)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(dlgSep, javax.swing.GroupLayout.PREFERRED_SIZE, 2, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(dlgLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(dlgStatus)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(dlgScrollPane, javax.swing.GroupLayout.PREFERRED_SIZE, 60, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnEditDialogue)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnAddTalker)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
    }// </editor-fold>//GEN-END:initComponents

    private void entryBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_entryBoxActionPerformed
		if (loaded && entryBox.getSelectedIndex() != -1) {
			showEntry(entryBox.getSelectedIndex());
			repaintFrame();
		}
    }//GEN-LAST:event_entryBoxActionPerformed

    private void btnRegEditActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRegEditActionPerformed
		if (npc == null || regentry == null) {
			return;
		}
		NPCRegistryEditor regedit = new NPCRegistryEditor();
		regedit.loadRegistry(reg);
		regedit.setEntry(npc.model);
    }//GEN-LAST:event_btnRegEditActionPerformed

    private void btnNewEntryActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnNewEntryActionPerformed
		addEntry(mTileMapPanel.getTileAtViewportCentre());
    }//GEN-LAST:event_btnNewEntryActionPerformed

    private void btnSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSaveActionPerformed
		saveEntry();
		updateDialogueSection(); //the NPC's script may have just been re-assigned
		repaintFrame();
    }//GEN-LAST:event_btnSaveActionPerformed

    private void btnRemoveEntryActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRemoveEntryActionPerformed
		removeEntry();
    }//GEN-LAST:event_btnRemoveEntryActionPerformed

    private void mdlStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_mdlStateChanged
		setNPC(entryBox.getSelectedIndex());
    }//GEN-LAST:event_mdlStateChanged

    private void motActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_motActionPerformed
		if (loaded && (Integer) mot.getValue() < motDropdown.getItemCount()) {
			motDropdown.setSelectedIndex((Integer) mot.getValue());
		}
    }//GEN-LAST:event_motActionPerformed

    private void motDropdownActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_motDropdownActionPerformed
		if (loaded && motDropdown.getSelectedIndex() != -1) {
			mot.setValue(motDropdown.getSelectedIndex());
		}
    }//GEN-LAST:event_motDropdownActionPerformed

    private void mot2DropdownActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_mot2DropdownActionPerformed
		if (loaded && mot2Dropdown.getSelectedIndex() != -1) {
			mp2.setValue(getMot2Raw(mot2Dropdown.getSelectedIndex()));
		}
    }//GEN-LAST:event_mot2DropdownActionPerformed

    private void mp2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_mp2ActionPerformed
		if (loaded && (Integer) mp2.getValue() < mot2Dropdown.getItemCount()) {
			mot2Dropdown.setSelectedIndex(getMot2Index((Integer) mp2.getValue()));
		}
    }//GEN-LAST:event_mp2ActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnAddTalker;
    private javax.swing.JButton btnEditDialogue;
    private javax.swing.JLabel dlgLabel;
    private javax.swing.JTextArea dlgPreview;
    private javax.swing.JScrollPane dlgScrollPane;
    private javax.swing.JSeparator dlgSep;
    private javax.swing.JLabel dlgStatus;
    private javax.swing.JComboBox<String> scrDropdown;
    private javax.swing.JLabel aiLabel;
    private javax.swing.JSeparator aiSep;
    private javax.swing.JFormattedTextField altitude;
    private javax.swing.JLabel altitudeLabel;
    private javax.swing.JFormattedTextField areaH;
    private javax.swing.JLabel areaHLabel;
    private javax.swing.JFormattedTextField areaSX;
    private javax.swing.JFormattedTextField areaSY;
    private javax.swing.JFormattedTextField areaW;
    private javax.swing.JLabel areaWLabel;
    private javax.swing.JButton btnNewEntry;
    private javax.swing.JButton btnRegEdit;
    private javax.swing.JButton btnRemoveEntry;
    private javax.swing.JButton btnSave;
    private javax.swing.JComboBox<String> entryBox;
    private javax.swing.JSpinner evtFlag;
    private javax.swing.JLabel evtFlagLabel;
    private javax.swing.JSpinner facedir;
    private javax.swing.JLabel facedirLabel;
    private javax.swing.JSeparator headerSep;
    private javax.swing.JLabel horizonLabel;
    private javax.swing.JFormattedTextField hostZone;
    private javax.swing.JLabel hostZoneLabel;
    private javax.swing.JFormattedTextField linkID;
    private javax.swing.JLabel linkIDLabel;
    private javax.swing.JFormattedTextField linkedZone;
    private javax.swing.JLabel linkedZoneLabel;
    private javax.swing.JSpinner mdl;
    private javax.swing.JLabel mdlLabel;
    private javax.swing.JFormattedTextField mot;
    private javax.swing.JComboBox<String> mot2Dropdown;
    private javax.swing.JComboBox<String> motDropdown;
    private javax.swing.JLabel motLabel;
    private javax.swing.JFormattedTextField mp2;
    private javax.swing.JLabel mp2Label;
    private javax.swing.JFormattedTextField originZone;
    private javax.swing.JLabel originZoneLabel;
    private javax.swing.JSpinner range;
    private javax.swing.JLabel rangeLabel;
    private javax.swing.JSpinner scr;
    private javax.swing.JLabel scrLabel;
    private javax.swing.JFormattedTextField u10;
    private javax.swing.JLabel u10Label;
    private javax.swing.JFormattedTextField u12;
    private javax.swing.JLabel u12Label;
    private javax.swing.JLabel u14Label;
    private javax.swing.JLabel u16Label;
    private javax.swing.JLabel worldLabel;
    private javax.swing.JLabel worldLocNote;
    private javax.swing.JSeparator worldSep;
    private javax.swing.JFormattedTextField x;
    private javax.swing.JLabel xLabel;
    private javax.swing.JFormattedTextField y;
    private javax.swing.JLabel yLabel;
    private javax.swing.JFormattedTextField zl2;
    private javax.swing.JLabel zl2Label;
    private javax.swing.JFormattedTextField zl3;
    private javax.swing.JLabel zl3Label;
    private javax.swing.JLabel zlLabel;
    private javax.swing.JSeparator zlSep;
    // End of variables declaration//GEN-END:variables

	@Override
	public void doSelectionLoop(MouseEvent evt, Component parent, float[] mvMatrix, float[] projMatrix, int[] view, Vec3f cameraVec) {
		if (!(CtrmapMainframe.tool instanceof NPCTool)){
			return;
		}
		double closestDist = Float.MAX_VALUE;
		int closestIdx = -1;
		GLUgl2 glu = new GLUgl2();
		for (int i : modelledNPCs()) {
			H3DModel m = modelAt(i);
			ZoneEntities.NPC testNpc = e.npcs.get(i);
			float[][] box = m.boxVectors;
			if (Utils.isBoxSelected(box, evt, parent, new Vec3f(testNpc.getX(), testNpc.getY(), testNpc.getZ()), new Vec3f(1f, 1f, 1f), new Vec3f(0f, get3DOrientation(testNpc.faceDirection), 0f), mvMatrix, projMatrix, view)) {
				boolean allow = false;
				for (int mesh = 0; mesh < m.meshes.size(); mesh++) {
					for (int vertex = 0; vertex < m.meshes.get(mesh).vertices.size(); vertex++) {
						H3DVertex v = m.meshes.get(mesh).vertices.get(vertex);
						float[] test = new float[3];
						glu.gluProject(v.position.x + testNpc.getX(), v.position.y + testNpc.getY(), v.position.z + testNpc.getZ(), mvMatrix, 0, projMatrix, 0, view, 0, test, 0);
						if (test[0] > 0 && test[0] < parent.getWidth() && test[1] > 0 && test[1] < parent.getHeight()) {
							allow = true;
							break;
						}
					}
					if (allow) {
						break;
					}
				}
				if (!allow) {
					continue;
				}
				Vec3f dummyCenterVector = new Vec3f(testNpc.getX(), testNpc.getY(), testNpc.getZ());
				double dist = Utils.getDistanceFromVector(dummyCenterVector, cameraVec);
				if (Math.abs(dist) < closestDist && i != npcIndex) {
					closestDist = Math.abs(dist);
					closestIdx = i;
				}
			}
		}
		if (closestIdx != -1) {
			setNPC(closestIdx);
		}
	}
}

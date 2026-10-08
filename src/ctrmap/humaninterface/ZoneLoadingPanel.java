package ctrmap.humaninterface;

import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.ZoneAppender;
import ctrmap.ZoneCloner;
import ctrmap.ZoneTables;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.text.LocationNames;
import ctrmap.formats.zone.Zone;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.text.NumberFormatter;

/**
 * Top-level loader for all in the Pokemon world, should really stop being
 * debug.
 */
public class ZoneLoadingPanel extends javax.swing.JPanel implements ZoneSaver, ZoneList {

	/**
	 * The zone table, the open zone and its index, owned by {@link LoadedZone}
	 * and handed to this panel; the three used to be public fields here, read
	 * by fourteen classes through the window's static. Every write the panel
	 * makes goes through it, named for what happened.
	 */
	private final LoadedZone loadedZone;
	private boolean loaded = false;

	/** Which tool the editor is holding, handed in: this class only asks. */
	private final ctrmap.humaninterface.tools.ToolSelection tools;

	/** The editors that hold unsaved work, handed in: this class flushes them, it does not own them. */
	private final OpenEditors openEditors;

	/** The editors that show the open zone, handed in: this panel says which zone, not how to show it. */
	private final ZoneEditors zoneEditors;

	/** The 3D gizmo, handed in: a zone switch lets go of whatever it was over. */
	private final Navigator navi;

	/**
	 * What shows the map a zone load has just opened, and what redraws the 3D view in the zone's own
	 * atmosphere. HANDED, not calls up into the window (F007): the window builds this tab and says what
	 * to ask; a suite hands nothing-doers.
	 */
	private final Runnable showMap;
	private final Runnable showAtmosphere;

	/** Creates the form over the zone owner it is handed. */
	public ZoneLoadingPanel(LoadedZone loadedZone, ctrmap.humaninterface.tools.ToolSelection tools, OpenEditors openEditors, ZoneEditors zoneEditors, Navigator navi,
			Runnable showMap, Runnable showAtmosphere, UndoHistory history) {
		if (history == null) {
			throw new IllegalArgumentException("the Zone tab must be handed the zone's history its properties are"
					+ " a document of");
		}
		//the header boxes, while a zone is shown, are one of the zone's documents (F209): watched, not recorded
		history.watch(() -> loaded && loadedZone.isOpen()
				? java.util.Collections.<UndoHistory.Document<?>>singletonList(
						new WidgetDocument(loadedZone.open(), "the zone's properties", headerWidgets()))
				: java.util.Collections.<UndoHistory.Document<?>>emptyList());
		if (navi == null) {
			throw new IllegalArgumentException("the Zone tab must be handed a Navigator - it lets the gizmo go");
		}
		this.navi = navi;
		if (openEditors == null || zoneEditors == null) {
			throw new IllegalArgumentException("the Zone tab must be handed the editors it saves and shows"
				+ " - openEditors=" + openEditors + ", zoneEditors=" + zoneEditors);
		}
		this.openEditors = openEditors;
		this.zoneEditors = zoneEditors;
		this.tools = tools;
		if (loadedZone == null) {
			throw new IllegalArgumentException("ZoneLoadingPanel must be handed a LoadedZone");
		}
		this.loadedZone = loadedZone;
		if (showMap == null || showAtmosphere == null) {
			throw new IllegalArgumentException("the Zone tab must be handed what shows a loaded map and its"
				+ " atmosphere - showMap=" + showMap + ", showAtmosphere=" + showAtmosphere);
		}
		this.showMap = showMap;
		this.showAtmosphere = showAtmosphere;
		initComponents();
		zoneList.setToolTipText(LOAD_HINT);
		btnCloneZone.setToolTipText("Copy the currently loaded zone over another existing zone slot.");
		btnAddZone.setToolTipText("Add new zones and lift ORAS's " + ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES + "-zone limit; also generates the required code.ips patch. ORAS only - test in Azahar first.");
		Forms.numberClass(Integer.class, new JFormattedTextField[]{cam1, cam2, camFlags, unknownFlags, battleBG, ad, bgmSpring,
			matrix, textFile, script, move, parentMap, x1, y1, z1, x2, y2, z2});
		//RAW BYTES ARE SAID TO BE RAW, AND FOLDED AWAY (F221): the flag word's number and bit 15 of 0x20
		//sat among the named fields under pk3DS's names for them, with nothing to say what they were
		unknownTitleLabel.setText("More header fields (from pk3DS):");
		unknownFlag.setText("Unnamed bit 15 of 0x20");
		Forms.unknownField(uFlabel, "Raw flag bits", "The flag word at 0x28 as one number - its named bits are the"
				+ " ghosting, breath, dowsing, 3D, special-walking and flash boxes, written from them; the rest are"
				+ " unnamed - and bit 15 of the word at 0x20", unknownFlags, unknownFlag);
		String music = "<html>The header holds four music slots, one per season: Spring, Summer, Autumn and Winter."
				+ "<br>This box shows Spring's. A change is written to every slot that held the same track,"
				+ "<br>and a slot set to a different one is left as it is.</html>";
		BGMTitleLabel.setToolTipText(music);
		bgmSpring.setToolTipText(music);
	}

	public void loadZone(Zone z) {
		try {
			loaded = false;
			Zone previous = loadedZone.open();
			if (previous != null) {
				previous.header.freeArchives();
			}
			//z is open at whatever index is selected: the list worker records
			//the index it picked right after this call, and a zone opened from
			//a file has no slot to record
			loadedZone.open(loadedZone.index(), z);

			showHeader(z.header);

			loaded = true;
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Fills the boxes from a header and remembers what each showed (F115): what opening a zone shows,
	 * and what a discarded header goes back to (F106).
	 */
	private void showHeader(ctrmap.formats.zone.ZoneHeader h) {
		isParentMap.setSelected(h.OLvalue == 1);
		shownParent = isParentMap.isSelected();
		cam1.setValue(h.camera1);
		cam2.setValue(h.camera2);
		camFlags.setValue(h.cameraFlags);
		x1.setValue(h.X);
		x2.setValue(h.X2);
		unknownFlags.setValue(h.unknownFlags);
		coldbreath.setSelected(h.enableBreathFX);
		ghosting.setSelected(h.enableGhosting);
		dowsing.setSelected(h.enableDowsingMachine);
		enable3d.setSelected(h.enable3D);
		unknownFlag.setSelected(h.unknownFlag);
		ad.setValue(h.areadataID);
		battleBG.setValue(h.battleBG);
		bgmSpring.setValue(h.BGMSpring);
		run.setSelected(h.enableRunning);
		skate.setSelected(h.enableRollerSkates);
		cycling.setSelected(h.enableCycling);
		escrope.setSelected(h.enableEscapeRope);
		fly.setSelected(h.enableFlyFrom);
		skybox.setSelected(h.enableSkybox);
		bgmCyclingEnable.setSelected(h.enableCyclingBGM);
		shownTransition = Combos.show(mapTransition, h.mapChange);
		matrix.setValue(h.mapmatrixID);
		move.setValue(h.mapMove);
		parentMap.setValue(h.parentMap);
		script.setValue(h.script);
		textFile.setValue(h.textID);
		shownTownMap = Combos.show(tmg, h.townMapGroup);
		shownType = Combos.show(type, getTypeIndex(h.mapType));
		shownWeather = Combos.show(weather, getWeatherIndex(h.weather));
		y1.setValue(h.Y);
		z1.setValue(h.Z);
		y2.setValue(h.Y2);
		z2.setValue(h.Z2);

		specWalk.setSelected(h.enableSpecialWalking);
		flash.setSelected(h.enableFlashableDarkness);
		//WHICH FLAGS THIS GAME HAS is its profile's answer (F207), not "is it ORAS"
		ctrmap.gamedef.GameProfile.ZoneFlagLayout l = h.flagLayout();
		coldbreath.setEnabled(l != null);
		ghosting.setEnabled(l != null);
		dowsing.setEnabled(l != null);
		enable3d.setEnabled(l != null);
		flash.setEnabled(l != null && l.flashableDarkness >= 0);
		specWalk.setEnabled(l != null && l.specialWalkingOff >= 0);
	}

	/** Programmatically opens a zone, as if picked from the dropdown. */
	public void selectZone(int index) {
		if (index >= 0 && index < zoneList.getItemCount()) {
			zoneList.setSelectedIndex(index);
		}
	}

	/**
	 * How many zones are actually in the dropdown.
	 *
	 * <p>The honest test of whether a workspace loaded. {@code Workspace.isValid()}
	 * is true before the archives are read, so it reports that the paths looked
	 * right, not that anything came of them; this reports what the user can see.
	 */
	public int getLoadedZoneCount() {
		return zoneList == null ? 0 : zoneList.getItemCount();
	}

	@Override
	public void rebuild(Runnable then) {
		loadEverything(then);
	}

	@Override
	public void open(int index) {
		selectZone(index);
	}

	public void loadEverything() {
		loadEverything(null);
	}

	/**
	 * Rebuilds the zone list from the current ZoneData archive on a background
	 * worker. onDone (if given) runs on the EDT after the list is populated -
	 * use it when follow-up UI (e.g. selecting a freshly appended zone) must see
	 * the reloaded list rather than the stale one.
	 */
	public void loadEverything(final Runnable onDone) {
		LoadingDialog progress = LoadingDialog.makeDialog("Loading Zone data");
		//READ ON THE WORKER, SHOWN ON THE EVENT THREAD (F116): the worker filled zoneList and tmg,
		//set the type and weather models and enabled a button - four Swing components, their
		//listeners firing - while the event thread painted the progress dialog
		loaded = false;
		Zone previous = loadedZone.open();
		if (previous != null) {
			previous.header.freeArchives();
		}
		loadedZone.release();
		SwingWorker<Listed, Void> worker = new SwingWorker<Listed, Void>() {
			@Override
			protected void done() {
				progress.close();
				Listed listed;
				try {
					listed = get(); //without this, anything thrown below vanishes and the
					//zone list is just silently empty with no error anywhere
				} catch (Exception ex) {
					Logger.getLogger(ZoneLoadingPanel.class.getName()).log(Level.SEVERE, "loading zones", ex);
					//...and the log alone IS silent to anyone not watching a
					//console. The zone count now comes from the profile and can
					//refuse in words for a game nobody has measured; that
					//sentence is worth nothing if it only reaches stderr.
					ctrmap.Ui.error(ZoneLoadingPanel.this, ctrmap.Ui.reason(ex), "Loading Zone data");
					listed = null;
				}
				if (listed != null) {
					showListed(listed);
				}
				if (onDone != null) {
					onDone.run();
				}
			}

			@Override
			protected Listed doInBackground() throws Exception {
				//WAS "length - (isXY() ? 1 : 2)": two answers for four games,
				//and the "else" arm handed every unmeasured game ORAS's 2 - a
				//zone count two short, with an ordinary zone read as the master
				//table. ZoneTables asks the open game and refuses in words.
				int totalZones = ZoneTables.zoneCount(Workspace.getArchive(ArchiveType.ZONE_DATA));
				if (totalZones <= 0) {
					//a truncated or non-ZoneData archive: without this the array
					//size goes negative and the real problem is never reported
					throw new IllegalStateException("The ZoneData archive holds no zones ("
							+ Workspace.getArchive(ArchiveType.ZONE_DATA).length
							+ " entries). The game folder is probably incomplete or damaged.");
				}
				Listed listed = new Listed(totalZones); //last file is not a ZO; filled slot by slot below
				for (int i = 0; i < totalZones; i++) {
					ZO zo = new ZO(Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, i), Workspace.session());
					listed.zones[i] = new Zone(zo, Workspace.game());
					listed.names.add(LocationNames.getLocName(ctrmap.Workspace.session(), listed.zones[i].header.parentMap) + " - " + i);
					progress.setBarPercent((int) ((float) i / totalZones * 100));
				}
				return listed;
			}
		};
		worker.execute();
		progress.showDialog();
	}

	/** The zones a load read, and the name each is listed under - built on the worker, shown on the EDT. */
	private static final class Listed {

		final Zone[] zones;
		final java.util.List<String> names = new java.util.ArrayList<>();

		Listed(int count) {
			zones = new Zone[count];
		}
	}

	/** Puts what a load read into the boxes - on the event thread, where every Swing component lives. */
	private void showListed(Listed listed) {
		loadedZone.table(listed.zones);
		zoneList.setSelectedIndex(-1);
		zoneList.removeAllItems();
		tmg.setSelectedIndex(-1);
		tmg.removeAllItems();
		for (String name : listed.names) {
			zoneList.addItem(name);
			tmg.addItem(name);
		}
		//The button follows the same capability the action behind it
		//refuses on. It used to be disabled for XY and enabled for
		//everything else, so Sun/Moon and Ultra Sun/Ultra Moon got a
		//live "Add zones" button that opens a dialog which cannot
		//finish - the gate said "not XY", the appender means "ORAS".
		if (!Workspace.profile().supports(GameProfile.Feature.ZONE_APPEND)) {
			btnAddZone.setEnabled(false);
			btnAddZone.setToolTipText("Adding new zones is not available for "
					+ Workspace.profile().displayName()
					+ " - it needs that game's zone-table limit found in its executable.");
		} else {
			btnAddZone.setEnabled(true);
			btnAddZone.setToolTipText("Add new zones and lift ORAS's " + ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES + "-zone limit; also generates the required code.ips patch. ORAS only - test in Azahar first.");
		}
		if (Workspace.game() == GameType.XY) {
			type.setModel(new DefaultComboBoxModel<>(new String[]{
				"Small generic",
				"Outside generic",
				"Grass gym",
				"Inside generic",
				"Anistar Sundial",
				"Lumiose Boulevard",
				"??? Route 6/19",
				"Lumiose Plazas"
			}));
			weather.setModel(new DefaultComboBoxModel<>(new String[]{
				"None",
				"Default 1",
				"Default 2",
				"Default 3",
				"Default 4",
				"Default 5",
				"Default 6",
				"Default 7",
				"Default 8",
				"Default 9",
				"Default 10",
				"Default 11",
				"Default 12",
				"Default 13",
				"Default 14",
				"Clear (perpetual)",
				"Clear (debris storms)",
				"Clear (battle sandstorms)",
				"Clear (snowstorms)",
				"Snow (perpetual)",
				"Snow/Clear/Overcast",
				"Snow/Overcast",
				"Starry/Clear/Snowstorm",
				"Starry/Cloudless",
				"Starry/Clear"
			}));
		} else {
			type.setModel(new DefaultComboBoxModel<>(new String[]{
				"Default map",
				"Single zone map"}));
			weather.setModel(new DefaultComboBoxModel<>(new String[]{
				"Clear",
				"Rain",
				"Thunderstorm",
				"Foggy",
				"Volcanic ash",
				"Sandstorm",
				"Dark",
				"Primordial rain",
				"Primordial drought",
				"Clear (No skybox)"
			}));
		}
		loaded = true;
	}

	@Override
	public boolean save(boolean askFirst) {
		return store(askFirst);
	}

	/**
	 * Every box store() writes into the header, in the order it reads them: what the zone's properties
	 * are while unsaved (F209). ZoneHeaderTest holds this list to store()'s body, so a box added there
	 * and not here fails by name rather than going without undo.
	 */
	javax.swing.JComponent[] headerWidgets() {
		return new javax.swing.JComponent[]{type, move, ad, matrix, textFile, script, tmg, mapTransition, parentMap, isParentMap, weather, battleBG, bgmSpring, bgmCyclingEnable, run, skate, cycling, escrope, fly, dowsing, coldbreath, ghosting, specWalk, flash, enable3d, skybox, cam1, cam2, camFlags, unknownFlags, unknownFlag, x1, y1, z1, x2, y2, z2};
	}

	public boolean store(boolean dialog) {
		Zone zone = loadedZone.open();
		if (zone == null) {
			return true;
		}
		if (!loaded) {
			//THE FORM NEVER FINISHED SHOWING THIS ZONE, SO IT HAS NOTHING TO WRITE.
			//loadZone() sets "loaded" false, writes forty-odd widgets out of the header,
			//and sets it true as its LAST line - all inside one try whose catch prints a
			//stack trace and stops there. Four of those writes are
			//JComboBox.setSelectedIndex fed straight from the header, and an index the
			//model does not have is an IllegalArgumentException: the ORAS map-type box
			//holds two entries while getTypeIndex can answer 2 or 4..7, the ORAS weather
			//box holds ten against a five-bit header field, and the transition box holds
			//eight against another five-bit field. When one throws, every field AFTER it
			//still holds the PREVIOUS zone's value - and this method had no "loaded" test
			//at all, so it copied that zone's boundaries, and for an early throw its
			//matrix, script, text and parent-map ids too, into THIS zone's header on the
			//next File > Save, zone switch or window close. Cross-zone header corruption
			//whose only trace was a stack trace on stderr: a town's walkable bounds end
			//up on a different map and the player meets it three towns later.
			//
			//Nothing is written and the user is told. It answers TRUE rather than false
			//on purpose. A refusal here would also stop OpenEditors.saveAll, and the
			//window's close handler only exits when that answers true - and this state
			//cannot be cleared from the zone dropdown, because the same false "loaded"
			//makes the dropdown's listener a no-op. Refusing would leave the editor with
			//no way out at all, which is a worse failure than the one being fixed.
			ctrmap.Ui.error(this, "The zone was NOT saved: its properties never finished"
					+ " loading, so this form is still showing part of another zone and writing"
					+ " it back would overwrite this one's header.\n\nReload the workspace"
					+ " (Options > Workspace settings) to try again.", "Save zone");
			return true;
		}
		int zoneIndex = loadedZone.index();
		//A BOX'S FIELD IS WRITTEN ONLY WHEN THE BOX WAS CHANGED (F115): written back from every box,
		//an ORAS map type 1 was saved as 3, unknown types as 0xFF, unknown XY weathers as 29 and the
		//6-bit OL value as 0 or 1 - on every save, edited or not
		if (type.getSelectedIndex() != shownType) {
			zone.header.mapType = getTypeRaw(type.getSelectedIndex());
		}
		zone.header.mapMove = (Integer) move.getValue();
		zone.header.areadataID = (Integer) ad.getValue();
		zone.header.mapmatrixID = (Integer) matrix.getValue();
		zone.header.textID = (Integer) textFile.getValue();
		zone.header.script = (Integer) script.getValue();

		if (tmg.getSelectedIndex() != shownTownMap) {
			zone.header.townMapGroup = tmg.getSelectedIndex();
		}
		if (mapTransition.getSelectedIndex() != shownTransition) {
			zone.header.mapChange = mapTransition.getSelectedIndex();
		}
		zone.header.parentMap = (Integer) parentMap.getValue();
		if (isParentMap.isSelected() != shownParent) {
			zone.header.OLvalue = isParentMap.isSelected() ? 1 : 0;
		}

		if (weather.getSelectedIndex() != shownWeather) {
			zone.header.weather = getWeatherRaw(weather.getSelectedIndex());
		}
		zone.header.battleBG = (Integer) battleBG.getValue();
		zone.header.setMusic((Integer) bgmSpring.getValue());
		zone.header.enableCyclingBGM = bgmCyclingEnable.isSelected();

		zone.header.enableRunning = run.isSelected();
		zone.header.enableRollerSkates = skate.isSelected();
		zone.header.enableCycling = cycling.isSelected();

		zone.header.enableEscapeRope = escrope.isSelected();
		zone.header.enableFlyFrom = fly.isSelected();
		zone.header.enableDowsingMachine = dowsing.isSelected();

		zone.header.enableBreathFX = coldbreath.isSelected();
		zone.header.enableGhosting = ghosting.isSelected();
		zone.header.enableSpecialWalking = specWalk.isSelected();
		//shown, and editable on ORAS, and never written back: the edit was dropped (F207)
		zone.header.enableFlashableDarkness = flash.isSelected();
		zone.header.enable3D = enable3d.isSelected();

		zone.header.enableSkybox = skybox.isSelected();

		zone.header.camera1 = (Integer) cam1.getValue();
		zone.header.camera2 = (Integer) cam2.getValue();
		zone.header.cameraFlags = (Integer) camFlags.getValue();
		zone.header.unknownFlags = (Integer) unknownFlags.getValue();
		zone.header.unknownFlag = unknownFlag.isSelected();
		zone.header.calculateFlags();

		zone.header.X = (Integer) x1.getValue();
		zone.header.Y = (Integer) y1.getValue();
		zone.header.Z = (Integer) z1.getValue();

		zone.header.X2 = (Integer) x2.getValue();
		zone.header.Y2 = (Integer) y2.getValue();
		zone.header.Z2 = (Integer) z2.getValue();

		//commit what the editors are holding before the zone is written. This
		//named two forms by name and threw away the boolean one of them
		//returned; the warp form was not named at all
		if (!zoneEditors.commit()) {
			return false;
		}
		boolean stored;
		try {
			stored = storeZone(dialog);
		} catch (IllegalStateException ex) {
			//a record that refuses to serialise - a warp with no destination -
			//used to leave here as an uncaught exception on stderr, and the
			//caller carried on as if the zone had been saved
			ctrmap.Ui.error(this, "Zone " + zoneIndex + " was not saved.\n" + ctrmap.util.Bytes.reason(ex), "Save zone");
			return false;
		}
		if (stored) {
			//A ZONE WITH NO ROW IN THE TABLE MUST NOT BE WRITTEN INTO SOMEBODY ELSE'S.
			//A zone opened from a loose .zo file is open at index -1, which is what
			//LoadedZone.open(int, Zone) documents for a zone that came from a file. The
			//write below was "seek to index * 0x38 and put this header there", and at -1
			//that is a NEGATIVE seek, which RandomAccessFile refuses with an IOException
			//- so the catch fired and the user was told. That worked, and it worked by
			//accident: skipBytes(-56) silently does nothing, so the read above it
			//compared this file against slot 0, and the only thing standing between a
			//loose zone and zone 0's row was that one call throwing. Anyone "fixing"
			//the negative seek - clamping it, or seeking before skipping - would have
			//landed the header in slot 0 with no warning at all.
			//
			//So the case is now decided here, where it can be read, and the report says
			//what actually happened rather than naming "zone -1".
			if (zoneIndex < 0) {
				ctrmap.Ui.error(this, "This zone was opened from a file rather than from the"
					+ " zone list, so it has no row of its own and the master zone-header table"
					+ " was NOT updated. The file itself was saved.\n\nTo change a header the"
					+ " game loads, open the zone from the Zone tab's list instead.", "Save zone");
				loadZone(zone);
				return true;
			}
			try {
				//THROUGH THE ONE READER (F013). This opened the table with a RandomAccessFile and
				//wrote the header at index * 0x38 whatever the file held: a short or stale table
				//was written into or grown to fit, the read before it ignored how many bytes came
				//back, a header longer than a row ran into the next zone's, and the write went
				//around the open unit of work. The table is now read by the one policy, the row
				//replaced only when it is one row long, and written through the unit.
				ctrmap.MasterTable table = ctrmap.MasterTable.read(Workspace.session());
				if (table.setRow(zoneIndex, zone.file.getFile(0))) {
					table.write();
				}
			} catch (IOException | RuntimeException ex) {
				Logger.getLogger(ZoneLoadingPanel.class.getName()).log(Level.SEVERE, null, ex);
				//the zone file itself IS written by here, so this is a half
				//save: the game reads its headers from the master table and
				//would keep loading the old one. It used to go to the log only.
				ctrmap.Ui.error(this, "Zone " + zoneIndex + " was saved, but the master zone-header"
						+ " table was NOT updated, so the game will keep loading the old header.\n\n"
						+ ctrmap.Ui.reason(ex), "Save zone");
			}
			loadZone(zone);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Asks, when a dialog is wanted, whether to keep the zone header and
	 * whether to keep the entity data, and hands the decisions to the zone.
	 * The questions used to be asked by {@link Zone} itself, from inside the
	 * format layer, where no suite could answer them and no caller could
	 * decide for it; this panel owns the window, so this panel asks, through
	 * the one seam ({@link Utils#askToKeep}, which holds the rule that a closed
	 * or unanswered question is a cancel), and passes the decisions down.
	 *
	 * @return true when the zone was written (whatever was kept), false when
	 * the user cancelled and nothing was written
	 * @throws IllegalStateException from the zone when the entities will not
	 * serialise; nothing is written then, and the caller reports it
	 */
	private boolean storeZone(boolean dialog) {
		Zone zone = loadedZone.open();
		EnumSet<Zone.Part> changed = zone.changed();
		EnumSet<Zone.Part> keep = EnumSet.noneOf(Zone.Part.class);
		if (changed.contains(Zone.Part.HEADER)) {
			//NO PUTS THE HEADER AND ITS BOXES BACK (F106): it did nothing, and the boxes and the header
			//went on holding the edit for the next save to write
			switch (ctrmap.Ui.askToKeep(dialog, "Zone header", () -> {
				zone.discardHeader();
				showHeader(zone.header);
			})) {
				case SAVE:
					keep.add(Zone.Part.HEADER);
					break;
				//cancel stops the save rather than quietly dropping this piece of it
				case CANCEL:
					return false;
				default:
					break;
			}
		}
		if (changed.contains(Zone.Part.ENTITIES)) {
			//NO PUTS THE ENTITIES BACK AND SHOWS THEM (F106): it forgot the change and left the edited
			//records in the forms; only the editors that show entities are asked, because showing the
			//whole zone again would reset editors whose own questions have not been asked yet
			switch (ctrmap.Ui.askToKeep(dialog, "Entity data", () -> {
				zone.discardEntities();
				zoneEditors.showEntities(zone);
				navi.follow(null);   //the record it was over went with the edit
			})) {
				case SAVE:
					keep.add(Zone.Part.ENTITIES);
					break;
				case CANCEL:
					return false;
				default:
					break;
			}
		}
		zone.store(keep);
		return true;
	}

	/** What each box showed when the zone loaded: a field is written back only when its box moved from it (F115). */
	private int shownType = -1, shownWeather = -1, shownTransition = -1, shownTownMap = -1;
	private boolean shownParent;

	public int getWeatherRaw(int index) {
		if (Workspace.game() == GameType.ORAS) {
			return index;
		} else {
			switch (index) {
				case 0:
					return 29;
				case 1:
				case 2:
				case 3:
				case 4:
				case 5:
					return index - 1;
				case 6:
				case 7:
				case 8:
					return index;
				case 9:
				case 10:
					return index + 1;
				case 11:
				case 12:
					return index + 5;
				case 13:
				case 14:
					return index + 6;
				case 15:
					return 23;
				case 16:
					return 9;
				case 17:
					return 5;
				case 18:
					return 14;
				case 19:
					return 18;
				case 20:
					return 13;
				case 21:
					return 15;
				case 22:
					return 12;
				case 23:
					return 21;
				case 24:
					return 22;
			}
		}
		return 29;
	}

	public int getWeatherIndex(int raw) {
		if (Workspace.game() == GameType.ORAS) {
			return raw;
		} else {
			if (raw < 5) {
				return raw + 1;
			} else {
				switch (raw) {
					case 29:
						return 0;
					case 5:
						return 17;
					case 6:
					case 7:
					case 8:
						return raw;
					case 9:
						return 16;
					case 10:
						return 9;
					case 11:
						return 10;
					case 12:
						return 22;
					case 13:
						return 20;
					case 14:
						return 18;
					case 15:
						return 21;
					case 16:
						return 11;
					case 17:
						return 12;
					case 18:
						return 19;
					case 19:
						return 13;
					case 20:
						return 14;
					case 21:
						return 23;
					case 22:
						return 24;
					case 23:
						return 15;
				}
			}
		}
		return -1;
	}

	public int getTypeIndex(int raw) {
		switch (raw) {
			case 0:
				return 0;
			case 1:
				return 1;
			case 2:
				return 2;
			case 3:
				if (Workspace.game() == GameType.XY) {
					return 3;
				} else {
					return 1;
				}
			case 6:
				return 4;
			case 7:
				return 5;
			case 8:
				return 6;
			case 9:
				return 7;
			default:
				return -1;
		}
	}

	public int getTypeRaw(int index) {
		switch (index) {
			case 0:
				return 0;
			case 1:
				if (Workspace.game() == GameType.XY) {
					return 1;
				} else {
					return 3;
				}
			case 2:
				return 2;
			case 3:
				return 3;
			case 4:
				return 6;
			case 5:
				return 7;
			case 6:
				return 8;
			case 7:
				return 9;
			default:
				return -1;
		}
	}

	/**
	 * This method is called from within the constructor to initialize the form.
	 * WARNING: Do NOT modify this code. The content of this method is always
	 * regenerated by the Form Editor.
	 */
	@SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        camFlags = new javax.swing.JFormattedTextField();
        btnSave = new javax.swing.JButton();
        cam1label = new javax.swing.JLabel();
        cam2label = new javax.swing.JLabel();
        camFlagsLabel = new javax.swing.JLabel();
        x1Label = new javax.swing.JLabel();
        x2Label = new javax.swing.JLabel();
        x2 = new javax.swing.JFormattedTextField();
        cam1 = new javax.swing.JFormattedTextField();
        cam2 = new javax.swing.JFormattedTextField();
        x1 = new javax.swing.JFormattedTextField();
        uFlabel = new javax.swing.JLabel();
        unknownFlags = new javax.swing.JFormattedTextField();
        unknownFlag = new javax.swing.JCheckBox();
        zoneList = new javax.swing.JComboBox<>();
        btnCloneZone = new javax.swing.JButton();
        btnAddZone = new javax.swing.JButton();
        loadZoneLabel = new javax.swing.JLabel();
        loaderSeparator = new javax.swing.JSeparator();
        typeLabel = new javax.swing.JLabel();
        type = new javax.swing.JComboBox<>();
        moveLabel = new javax.swing.JLabel();
        adLabel = new javax.swing.JLabel();
        move = new javax.swing.JFormattedTextField();
        matrix = new javax.swing.JFormattedTextField();
        matrixLabel = new javax.swing.JLabel();
        ad = new javax.swing.JFormattedTextField();
        textLabel = new javax.swing.JLabel();
        textFile = new javax.swing.JFormattedTextField();
        scriptLabel = new javax.swing.JLabel();
        script = new javax.swing.JFormattedTextField();
        tmgLabel = new javax.swing.JLabel();
        engDataSep = new javax.swing.JSeparator();
        BGMTitleLabel = new javax.swing.JLabel();
        bgmSpring = new javax.swing.JFormattedTextField();
        worldPropLabel = new javax.swing.JLabel();
        engDataLabel = new javax.swing.JLabel();
        parentMapLabel = new javax.swing.JLabel();
        parentMap = new javax.swing.JFormattedTextField();
        weatherLabel = new javax.swing.JLabel();
        battleBGLabel = new javax.swing.JLabel();
        battleBG = new javax.swing.JFormattedTextField();
        mapChangeLabel = new javax.swing.JLabel();
        plrFlagsTitleLabel = new javax.swing.JLabel();
        run = new javax.swing.JCheckBox();
        skate = new javax.swing.JCheckBox();
        cycling = new javax.swing.JCheckBox();
        escrope = new javax.swing.JCheckBox();
        fly = new javax.swing.JCheckBox();
        skybox = new javax.swing.JCheckBox();
        bgmCyclingEnable = new javax.swing.JCheckBox();
        unknownSep = new javax.swing.JSeparator();
        unknownTitleLabel = new javax.swing.JLabel();
        y1Label = new javax.swing.JLabel();
        y1 = new javax.swing.JFormattedTextField();
        z1Label = new javax.swing.JLabel();
        z1 = new javax.swing.JFormattedTextField();
        y2 = new javax.swing.JFormattedTextField();
        y2Label = new javax.swing.JLabel();
        z2 = new javax.swing.JFormattedTextField();
        z2Label = new javax.swing.JLabel();
        mainSep = new javax.swing.JSeparator();
        plrFlagsSep = new javax.swing.JSeparator();
        isParentMap = new javax.swing.JCheckBox();
        spawnPointLabel = new javax.swing.JLabel();
        weather = new javax.swing.JComboBox<>();
        coldbreath = new javax.swing.JCheckBox();
        ghosting = new javax.swing.JCheckBox();
        dowsing = new javax.swing.JCheckBox();
        specWalk = new javax.swing.JCheckBox();
        tmg = new javax.swing.JComboBox<>();
        flash = new javax.swing.JCheckBox();
        mapTransition = new javax.swing.JComboBox<>();
        enable3d = new javax.swing.JCheckBox();

        camFlags.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        btnSave.setText("Save");
        btnSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSaveActionPerformed(evt);
            }
        });

        cam1label.setText("Camera1");

        cam2label.setText("Camera2");

        camFlagsLabel.setText("CameraFlags");

        x1Label.setText("X1");

        x2Label.setText("X");

        x2.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        cam1.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        cam2.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        x1.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        uFlabel.setText("UnknownFlags");

        unknownFlags.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        unknownFlag.setText("UnknownFlag");

        zoneList.setMaximumRowCount(20);
        zoneList.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                zoneListActionPerformed(evt);
            }
        });

        btnCloneZone.setText("Clone zone...");
        btnCloneZone.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCloneZoneActionPerformed(evt);
            }
        });

        btnAddZone.setText("Add zones (lift limit)...");
        btnAddZone.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnAddZoneActionPerformed(evt);
            }
        });

        loadZoneLabel.setText("Load Zone");

        typeLabel.setText("Type:");

        moveLabel.setText("Move:");

        adLabel.setText("Area data:");

        move.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        matrix.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        matrixLabel.setText("Map matrix:");

        ad.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        textLabel.setText("Text file:");

        textFile.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        scriptLabel.setText("Script file:");

        script.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        tmgLabel.setText("Town map group:");

        engDataSep.setOrientation(javax.swing.SwingConstants.VERTICAL);

        BGMTitleLabel.setText("BGM:");

        bgmSpring.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        worldPropLabel.setText("World properties:");

        engDataLabel.setText("Engine data:");

        parentMapLabel.setText("Parent map:");

        parentMap.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        weatherLabel.setText("Default weather:");

        battleBGLabel.setText("Battle background pack:");

        battleBG.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        mapChangeLabel.setText("MapChange:");

        plrFlagsTitleLabel.setText("Player flags:");

        run.setText("Allow running");

        skate.setText("Allow Rollerblades");

        cycling.setText("Allow Cycling");

        escrope.setText("Allow use of Escape Rope");

        fly.setText("Allow flying from this zone");

        skybox.setText("Draw Skybox (OmegaAlpha only)");

        bgmCyclingEnable.setText("Enable Cycling BGM");
        bgmCyclingEnable.setMargin(new java.awt.Insets(2, 0, 2, 2));

        unknownTitleLabel.setText("Unknown properties (From PK3DS):");

        y1Label.setText("Y1");

        y1.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        z1Label.setText("Z1");

        z1.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        y2.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        y2Label.setText("Y");

        z2.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0"))));

        z2Label.setText("Z");

        isParentMap.setText("Is parent map");
        isParentMap.setMargin(new java.awt.Insets(2, 0, 2, 2));

        spawnPointLabel.setText("OA Default spawn point (3D world float coordinate as short):             (XY role unknown)");

        weather.setMaximumRowCount(25);

        coldbreath.setText("Cold breath FX");

        ghosting.setText("Ghosting blur shader");

        dowsing.setText("Allow Dowsing machine");

        specWalk.setText("Special walking animation (XY)");

        flash.setText("Flash-able darkness (OA)");

        mapTransition.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "In - Fade/Opening rhombus; Out - Fade/Pokemon logo", "Directional swipe", "In - Closing circle/Fade; Out - Fade/Opening circle", "In - Pokemon logo/Fade; Out - Fade/Pokemon logo", "In - Closing rhombus/Fade; Out - Fade/Opening rhombus", "In - Swipe/Fade; Out - Fade/Swipe", "In - Closing circle/Fade; Out - White fade", "All fade" }));

        enable3d.setText("Enable 3D");

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(loaderSeparator, javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(plrFlagsSep)
                    .addComponent(unknownSep)
                    .addComponent(mainSep)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(run, javax.swing.GroupLayout.PREFERRED_SIZE, 108, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addComponent(skate, javax.swing.GroupLayout.PREFERRED_SIZE, 130, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addComponent(cycling, javax.swing.GroupLayout.PREFERRED_SIZE, 107, javax.swing.GroupLayout.PREFERRED_SIZE))
                                .addGap(45, 45, 45)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(escrope, javax.swing.GroupLayout.PREFERRED_SIZE, 168, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addComponent(fly, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addComponent(dowsing))
                                .addGap(45, 45, 45)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(coldbreath)
                                    .addGroup(layout.createSequentialGroup()
                                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                            .addComponent(specWalk)
                                            .addComponent(ghosting))
                                        .addGap(45, 45, 45)
                                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                            .addComponent(enable3d)
                                            .addComponent(flash)))))
                            .addGroup(layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                                        .addComponent(engDataLabel)
                                        .addGroup(layout.createSequentialGroup()
                                            .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                                .addComponent(moveLabel)
                                                .addComponent(typeLabel))
                                            .addGap(31, 31, 31)
                                            .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                                .addComponent(move, javax.swing.GroupLayout.PREFERRED_SIZE, 70, javax.swing.GroupLayout.PREFERRED_SIZE)
                                                .addComponent(type, javax.swing.GroupLayout.PREFERRED_SIZE, 110, javax.swing.GroupLayout.PREFERRED_SIZE)))
                                        .addGroup(layout.createSequentialGroup()
                                            .addComponent(adLabel)
                                            .addGap(9, 9, 9)
                                            .addComponent(ad)
                                            .addGap(40, 40, 40)))
                                    .addComponent(scriptLabel)
                                    .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                                        .addGroup(javax.swing.GroupLayout.Alignment.LEADING, layout.createSequentialGroup()
                                            .addComponent(matrixLabel)
                                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                            .addComponent(matrix, javax.swing.GroupLayout.PREFERRED_SIZE, 70, javax.swing.GroupLayout.PREFERRED_SIZE))
                                        .addGroup(layout.createSequentialGroup()
                                            .addComponent(textLabel)
                                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                            .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                                                .addComponent(script, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, 70, Short.MAX_VALUE)
                                                .addComponent(textFile, javax.swing.GroupLayout.Alignment.TRAILING)))))
                                .addGap(18, 18, 18)
                                .addComponent(engDataSep, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addGap(18, 18, 18)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(worldPropLabel)
                                    .addGroup(layout.createSequentialGroup()
                                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                            .addComponent(isParentMap)
                                            .addGroup(layout.createSequentialGroup()
                                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                                    .addComponent(tmgLabel)
                                                    .addComponent(mapChangeLabel)
                                                    .addComponent(parentMapLabel))
                                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                                                    .addComponent(parentMap, javax.swing.GroupLayout.PREFERRED_SIZE, 50, javax.swing.GroupLayout.PREFERRED_SIZE)
                                                    .addComponent(tmg, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                                    .addComponent(mapTransition, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))))
                                        .addGap(26, 26, 26)
                                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                            .addComponent(battleBGLabel)
                                            .addComponent(weatherLabel)
                                            .addComponent(BGMTitleLabel)
                                            .addComponent(bgmCyclingEnable))
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                            .addComponent(weather, javax.swing.GroupLayout.PREFERRED_SIZE, 127, javax.swing.GroupLayout.PREFERRED_SIZE)
                                            .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                                                .addComponent(bgmSpring, javax.swing.GroupLayout.Alignment.LEADING)
                                                .addComponent(battleBG, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.PREFERRED_SIZE, 50, javax.swing.GroupLayout.PREFERRED_SIZE))))))
                            .addComponent(plrFlagsTitleLabel)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(loadZoneLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(zoneList, javax.swing.GroupLayout.PREFERRED_SIZE, 350, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(btnCloneZone)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(btnAddZone))
                            .addComponent(skybox)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(x2Label)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(x2, javax.swing.GroupLayout.PREFERRED_SIZE, 79, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(y2Label)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(y2, javax.swing.GroupLayout.PREFERRED_SIZE, 79, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(z2Label)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(z2, javax.swing.GroupLayout.PREFERRED_SIZE, 79, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addComponent(spawnPointLabel)
                            .addComponent(unknownTitleLabel)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(x1Label)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(x1, javax.swing.GroupLayout.PREFERRED_SIZE, 79, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(y1Label)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(y1, javax.swing.GroupLayout.PREFERRED_SIZE, 79, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(z1Label)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                                .addComponent(z1, javax.swing.GroupLayout.PREFERRED_SIZE, 79, javax.swing.GroupLayout.PREFERRED_SIZE))
                            .addComponent(btnSave, javax.swing.GroupLayout.PREFERRED_SIZE, 82, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addGroup(layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(camFlagsLabel)
                                    .addComponent(cam2label)
                                    .addComponent(cam1label)
                                    .addComponent(uFlabel))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                                    .addComponent(camFlags, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.PREFERRED_SIZE, 79, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                                        .addComponent(cam1, javax.swing.GroupLayout.PREFERRED_SIZE, 79, javax.swing.GroupLayout.PREFERRED_SIZE)
                                        .addComponent(cam2, javax.swing.GroupLayout.PREFERRED_SIZE, 79, javax.swing.GroupLayout.PREFERRED_SIZE))
                                    .addComponent(unknownFlags, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.PREFERRED_SIZE, 79, javax.swing.GroupLayout.PREFERRED_SIZE)))
                            .addComponent(unknownFlag))
                        .addGap(0, 0, Short.MAX_VALUE)))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(zoneList, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(loadZoneLabel)
                    .addComponent(btnCloneZone)
                    .addComponent(btnAddZone))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(loaderSeparator, javax.swing.GroupLayout.PREFERRED_SIZE, 2, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                        .addComponent(worldPropLabel)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(tmgLabel)
                            .addComponent(weatherLabel)
                            .addComponent(weather, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(tmg, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(mapChangeLabel)
                            .addComponent(battleBGLabel)
                            .addComponent(battleBG, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(mapTransition, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(parentMapLabel)
                            .addComponent(parentMap, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(BGMTitleLabel)
                            .addComponent(bgmSpring, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(bgmCyclingEnable)
                            .addComponent(isParentMap))
                        .addGap(62, 62, 62))
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(engDataLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                                    .addComponent(typeLabel)
                                    .addComponent(type, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                                    .addComponent(moveLabel)
                                    .addComponent(move, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                                    .addComponent(adLabel)
                                    .addComponent(ad, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                                    .addComponent(matrix, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addComponent(matrixLabel))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                                    .addComponent(textFile, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addComponent(textLabel))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                                    .addComponent(scriptLabel)
                                    .addComponent(script, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                            .addComponent(engDataSep, javax.swing.GroupLayout.PREFERRED_SIZE, 170, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)))
                .addComponent(plrFlagsSep, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(plrFlagsTitleLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(run)
                    .addComponent(escrope)
                    .addComponent(coldbreath))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(skate)
                    .addComponent(fly)
                    .addComponent(ghosting)
                    .addComponent(enable3d))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(cycling)
                    .addComponent(dowsing)
                    .addComponent(specWalk)
                    .addComponent(flash))
                .addGap(18, 18, 18)
                .addComponent(skybox)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(spawnPointLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(x2Label)
                    .addComponent(x2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(y2Label)
                    .addComponent(y2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(z2Label)
                    .addComponent(z2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(unknownSep, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(unknownTitleLabel)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(cam1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(cam1label))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(cam2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(cam2label))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(camFlags, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(camFlagsLabel))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(unknownFlags, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(uFlabel))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(unknownFlag)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(x1Label)
                    .addComponent(x1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(y1Label)
                    .addComponent(y1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(z1Label)
                    .addComponent(z1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(mainSep, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnSave)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
    }// </editor-fold>//GEN-END:initComponents

    private void btnSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSaveActionPerformed
		store(false);
    }//GEN-LAST:event_btnSaveActionPerformed

    private void zoneListActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_zoneListActionPerformed
		if (browser != null) {
			//the browser follows what is open, so the row highlighted is the zone loaded
			browser.select(zoneList.getSelectedIndex());
		}
		if (zoneList.getSelectedIndex() != -1 && loaded) {
			if (openEditors.saveAll(true)) {
				LoadingDialog progress = LoadingDialog.makeDialog("Loading zone");
				//READ HERE, ON THE EVENT THREAD, ONCE: the worker used to ask the dropdown
				//twice from its own thread, and a user arrowing on meanwhile opened one zone
				//into the slot of another
				final int opening = zoneList.getSelectedIndex();
				SwingWorker<Zone, Void> worker = new SwingWorker<Zone, Void>() {
					@Override
					protected void done() {
						//THE SHOW HALF, ON THE EVENT THREAD (F220). Handing the zone to the
						//property form and all nine editors used to happen in doInBackground:
						//some two hundred widget writes off the event thread, a nested modal
						//progress dialog shown from the worker, and two questions asked from it.
						//The dialog closes AFTER the editors have the zone, so whoever waits on
						//it - a headless suite included - sees the zone open, not half of it.
						Throwable failed = null;
						try {
							Zone z = get(); //without this, anything thrown in the read vanishes and a
							//zone that failed to load looks loaded, the editors half on the last one
							if (z != loadedZone.at(opening)) {
								loadedZone.replace(opening, z); //the table holds what the file holds (F234)
							}
							loadZone(z);
							loadedZone.open(opening, z);
							zoneEditors.show(z);
							navi.follow(null);   //the record it was over belongs to the zone being closed
						} catch (java.util.concurrent.ExecutionException ex) {
							failed = ex.getCause() != null ? ex.getCause() : ex;
						} catch (InterruptedException | RuntimeException ex) {
							failed = ex;
						} finally {
							progress.close();
						}
						if (failed != null) {
							Throwable cause = failed;
							Logger.getLogger(ZoneLoadingPanel.class.getName()).log(Level.SEVERE, "loading zone", cause);
							unloadZone();
							//through Ui: the progress dialog closes on failure
							//exactly as on success and the dropdown has already
							//moved, so this sentence is all that separates a
							//zone that would not open from one that is empty
							ctrmap.Ui.error(ZoneLoadingPanel.this, "The zone did not load:\n" + cause
									+ "\n\nPick a zone from the list to try again.", "Load zone");
							return;
						}
						//show the map that was just loaded instead of leaving the user on the property form
						showMap.run();
						//forking is the safe default: a shared map means edits here
						//would silently change other zones too
						offerForkIfShared();
						//whatever tool is held may need to re-seed onto the NEW zone. This
						//used to name the painter and reach into the window for its form,
						//which meant the zone switch knew which tools exist and which of
						//them care
						tools.zoneChanged();
						//show this zone's own atmosphere in the 3D view
						showAtmosphere.run();
					}

					@Override
					protected Zone doInBackground() {
						//THE READ HALF (F220): files and parsing only, returning the zone. Nothing
						//here may touch a widget - EdtReachTest follows every call it makes.
						//
						//The camera form is NOT stored here. It was, and it was the
						//second store of the same form: openEditors.saveAll(true), the
						//guard this worker runs inside, has mCamEditForm.store as its
						//FIRST entry, so the camera was already written - on the event
						//thread, with its refusal honoured by that if. This copy threw
						//the boolean away, so a user who answered Cancel to "keep camera
						//data?" would have had the next zone loaded over the top anyway,
						//and it asked from doInBackground - a modal dialog off the event
						//thread. It never fired only because the first store leaves the
						//record unmodified, which is luck, not a design.
						//A SLOT ANOTHER WRITER CHANGED IS READ AGAIN (F234): the table read every
						//zone once, and Empty zone, Rename, Connect zones, a door wired from another
						//map and an interior clone write a zone file through containers of their
						//own - so opening it showed the old warps, and its first save cut the link off
						//READ FROM THE OWNER, NOT CAPTURED: a worker holding the zone in a field of its
						//own is a second holder of a zone, which LoadedZoneTest refuses (F208)
						Zone slot = loadedZone.at(opening);
						Zone z = slot.changedOnDisk().isEmpty() ? slot : slot.reread();
						progress.setBarPercent(20);
						z.header.fetchArchives(Workspace.session());
						progress.setBarPercent(50);
						z.s.decompressThis();
						progress.setBarPercent(100);
						return z;
					}
				};
				worker.execute();
				progress.showDialog();
			}
		}
    }//GEN-LAST:event_zoneListActionPerformed

	/**
	 * Opens the open zone again when another writer has changed its file (F234) - Empty zone,
	 * Rename and Connect zones write zones through containers of their own, and the editors went
	 * on showing, and saving, what they held before. It goes the way picking the zone in the
	 * dropdown goes: what the editors hold is saved first, asked as a zone switch asks (a part
	 * edited here AND changed on disk is refused, never written over), and the zone is then read
	 * again from its file. Nothing happens when the open zone's file is as it was read.
	 */
	public void reopenIfChangedOnDisk() {
		Zone open = loadedZone.open();
		if (open == null || loadedZone.index() < 0 || zoneList.getSelectedIndex() != loadedZone.index()
				|| open.changedOnDisk().isEmpty()) {
			return;
		}
		zoneListActionPerformed(null);
	}

	/**
	 * Back to "no zone open", for a load that failed partway. The editors that
	 * had already switched would otherwise sit on a zone store() never writes,
	 * and the ones that had not would still hold the previous zone.
	 *
	 * <p>PUBLIC for the OTHER way a zone stops being open, which had no way to
	 * say so: {@link ctrmap.CtrmapMainframe#onWorkspaceOpened} is handed null
	 * when a workspace fails to open, and the zone that was open belonged to the
	 * game that has just gone. The state wanted there is this one exactly -
	 * nothing open, no index, no editor showing it - so the window asks for it
	 * rather than a second, shorter version of it being written beside it, which
	 * is the failure {@link ZoneEditors} exists to end. It was private only
	 * because a load that failed partway was the one way in.
	 */
	public void unloadZone() {
		loadedZone.close();
		zoneEditors.clear();
		zoneList.setSelectedIndex(-1);
	}

	/**
	 * Whether this game can be given a zone its own private copy of a map.
	 *
	 * <p>Three sites here asked {@code Workspace.isOA()} instead - the offer
	 * after loading a shared zone, the tick box in the clone dialog, and the
	 * flag that decides whether a clone forks. A fork appends to AreaData, the
	 * NPC registry, FieldData and the MapMatrix and repoints the zone in two
	 * places, and every one of those offsets was measured on ORAS - which is
	 * what {@link GameProfile.Feature#AREA_FORK} records. Asking for the
	 * capability rather than for the game means the day someone measures those
	 * offsets on another game, this panel follows the profile instead of
	 * needing three more edits, and {@code AreaForker} / {@code GeometryForker}
	 * - which already refuse on the same flag - cannot disagree with the UI in
	 * front of them.
	 */
	private static boolean canFork() {
		return Workspace.isValid() && Workspace.profile().supports(GameProfile.Feature.AREA_FORK);
	}

	/**
	 * Hands out buttons the Zone Loader tab can put in the browser's row - and they are NOT
	 * the ones this form built.
	 *
	 * <p>YOU CANNOT MOVE A COMPONENT OUT OF A GroupLayout. This returned the form's own
	 * {@code btnCloneZone} and {@code btnAddZone} for the tab to re-parent, which works
	 * exactly once: adding a component to another container removes it from this one, and the
	 * first time this panel is LAID OUT, GroupLayout puts every component named in its groups
	 * back. The generated layout names both of them twice, in the horizontal group and the
	 * vertical one.
	 *
	 * <p>It took four wrong explanations to find that, because every check said it worked. The
	 * tab really does move them; a diagnostic written from inside the tab builder saw them
	 * moved, because that runs BEFORE the first layout; and the headless suite built the panel
	 * without ever laying it out. The owner's window laid out and pulled them home, and they
	 * had to say so four times.
	 *
	 * <p>So the form keeps its two, hidden - GroupLayout honours visibility by default, so a
	 * hidden component takes no space and the corner closes up - and the fields are REPOINTED
	 * at the copies. Everything that already enables, disables or re-tooltips
	 * {@code btnAddZone} goes on working, and touches the button the user can actually see.
	 */
	private javax.swing.JButton exported(javax.swing.JButton original) {
		javax.swing.JButton copy = new javax.swing.JButton(original.getText());
		copy.setToolTipText(original.getToolTipText());
		copy.setEnabled(original.isEnabled());
		copy.setMnemonic(original.getMnemonic());
		for (java.awt.event.ActionListener listener : original.getActionListeners()) {
			copy.addActionListener(listener);
			//...AND TAKEN OFF THE ORIGINAL, so this is a MOVE and not a copy. A hidden button
			//that still fires the action is a second control for one job - the exact thing the
			//owner reported - kept alive where nobody can see it to reason about.
			original.removeActionListener(listener);
		}
		original.setVisible(false);
		original.setEnabled(false);
		return copy;
	}

	/**
	 * The two buttons that MAKE a zone, in the order they should be offered.
	 *
	 * <p>They were laid out in this form's top-left corner, which is where NetBeans put them
	 * years ago and where they stayed when the zone browser was added to the right-hand side.
	 * The owner reported the result: one job - pick a zone, copy a zone, add zones - split
	 * across opposite corners of the tab, and the button that moved was one nobody asked to
	 * move. The tab takes these and puts them in the browser's own action row; handing them
	 * over rather than letting the tab dig them out keeps the panel in charge of what it will
	 * give up, and means a reader of this class can see that they leave.
	 *
	 * <p>They are NOT copies. Adding a Swing component to another container moves it, so there
	 * is still exactly one "Clone zone..." button in the window.
	 */
	public javax.swing.JButton[] zoneCreationButtons() {
		if (!handedOver) {
			handedOver = true;
			//REPOINT THE FIELDS at the copies. Everything else in this class that touches
			//btnAddZone - the game-type check that disables it, and its tooltip - then drives
			//the button the user can see, instead of a hidden one behind the layout.
			btnCloneZone = exported(btnCloneZone);
			btnAddZone = exported(btnAddZone);
		}
		return new javax.swing.JButton[]{btnCloneZone, btnAddZone};
	}

	/** Handed over once; a second call must not make a third pair. */
	private boolean handedOver;

	/** The zone browser beside this panel, handed in when the tab is built. */
	private ZoneBrowserPane browser;

	/** What the dropdown does. It loads, immediately - which is why the browser exists. */
	private static final String LOAD_HINT
		= "Picking a map here OPENS it straight away. To look through the zones first,"
		+ " use Browse zones on the right: arrow through the list and each one is drawn"
		+ " before you commit to it.";

	/**
	 * Gives this panel the zone browser that sits beside it in the Zone Loader tab.
	 *
	 * <p>The browser mirrors this dropdown's rows and hands a chosen zone straight
	 * back to it, so there is one list of zones and one way to open one. Looking is
	 * the browser's job and it opens nothing; this dropdown opens things and does not
	 * pretend to be a way of looking.
	 */
	public void useBrowser(ZoneBrowserPane pane) {
		this.browser = pane;
		if (pane == null) {
			return;
		}
		pane.onLoad(new ZoneBrowserPane.Loader() {
			@Override
			public void load(int zoneIndex) {
				//out through the dropdown: the one path that knows how to open a zone
				if (zoneIndex >= 0 && zoneIndex < zoneList.getItemCount()) {
					zoneList.setSelectedIndex(zoneIndex);
				}
			}
		});
		mirrorZonesToBrowser();
		zoneList.getModel().addListDataListener(new javax.swing.event.ListDataListener() {
			@Override
			public void intervalAdded(javax.swing.event.ListDataEvent e) {
				mirrorZonesToBrowser();
			}

			@Override
			public void intervalRemoved(javax.swing.event.ListDataEvent e) {
				mirrorZonesToBrowser();
			}

			@Override
			public void contentsChanged(javax.swing.event.ListDataEvent e) {
				mirrorZonesToBrowser();
			}
		});
		//THE DROPDOWN GOES OUT OF SIGHT. There is one way to pick a zone now - the list
		//beside this panel, where looking is free and opening is a button. A dropdown that
		//ALSO picks zones is a second, worse way to do the same job: it opens whatever you
		//arrow onto, which is the complaint the browser exists to answer, and leaving both
		//is the duplication this project keeps being cleared of. It is still the MECHANISM
		//- the browser hands a chosen zone back through it, the one path that knows how to
		//open one, and the rest of this panel reads it to know what is loaded.
		zoneList.setVisible(false);
		loadZoneLabel.setVisible(false);
		zoneList.setToolTipText(LOAD_HINT);
	}

	/** Keeps the browser showing the same zones the dropdown does. */
	private void mirrorZonesToBrowser() {
		if (browser == null) {
			return;
		}
		java.util.List<String> rows = new java.util.ArrayList<>();
		for (int i = 0; i < zoneList.getItemCount(); i++) {
			rows.add(String.valueOf(zoneList.getItemAt(i)));
		}
		browser.setZones(rows);
	}

	/**
	 * Hands the browser the game to read maps out of, when a workspace opens.
	 *
	 * <p>WHY IT IS HANDED IN. WorkspaceSessionTest holds a falling ceiling on how
	 * many production classes reach the open-workspace statics; a preview that
	 * fetched the session itself would raise it.
	 */
	public void attachZonePreview(ctrmap.WorkspaceSession ws) {
		if (browser == null) {
			return;
		}
		browser.use(ws);
		mirrorZonesToBrowser();
		browser.select(zoneList.getSelectedIndex());
	}

	/** The browser beside this panel, for the window that built the tab and for tests. */
	public ZoneBrowserPane browser() {
		return browser;
	}

	/**
	 * Declined fork offers, remembered across sessions (asking again on every zone load reads as
	 * nagging) - IN THE WORKSPACE FOLDER, per game folder, through {@link ctrmap.ForkDeclines}
	 * (F253). They were machine-wide Preferences under the hash of the workspace path, so pointing
	 * the workspace at another game silenced the offer on that game's zones at the same indices.
	 * Read on every ask, never cached: a repoint, a clean or another workspace cannot leave a stale
	 * set behind.
	 */
	private java.util.Set<Integer> forkDeclined() {
		try {
			return ctrmap.ForkDeclines.read(Workspace.WORKSPACE_PATH == null ? null : new java.io.File(Workspace.WORKSPACE_PATH),
					Workspace.GAMEDIR_PATH);
		} catch (java.io.IOException ex) {
			//COULD NOT READ IS NOT NONE DECLINED: the offer is asked again - the safe direction, one
			//question the user can answer - and the reason is said once per panel, like a refused save
			if (!forkDeclineReadReported) {
				forkDeclineReadReported = true;
				ctrmap.Ui.error(this, "The zones you declined a private map for could not be read:\n" + ctrmap.Ui.reason(ex)
						+ "\n\nThose offers will be asked again. Nothing in the game or in the workspace is affected.",
						"Shared map");
			}
			return new java.util.TreeSet<>();
		}
	}

	/** Set once this panel has said its declines could not be read. */
	private boolean forkDeclineReadReported = false;

	/** Forgets a remembered decline - call whenever a slot's OCCUPANT changes
	 *  (clone-into-slot, facility setup): the new zone deserves a fresh offer. */
	@Override
	public void clearForkDecline(int zoneIndex) {
		java.util.Set<Integer> declined = forkDeclined();
		if (declined.remove(zoneIndex)) {
			saveForkDeclined(declined);
		}
	}

	/** Forgets remembered declines for every zone at or above the index (the
	 *  added-zone removal path). */
	public void clearForkDeclinesFrom(int minIndex) {
		java.util.Set<Integer> declined = forkDeclined();
		if (declined.removeIf(z -> z >= minIndex)) {
			saveForkDeclined(declined);
		}
	}

	/** Set once this panel has told the user that its declines are not being
	 *  remembered, so a store that keeps refusing reports once instead of
	 *  turning one nag into another. */
	private boolean forkDeclineSaveReported = false;

	private void saveForkDeclined(java.util.Set<Integer> declined) {
		try {
			ctrmap.ForkDeclines.write(Workspace.WORKSPACE_PATH == null ? null : new java.io.File(Workspace.WORKSPACE_PATH),
					Workspace.GAMEDIR_PATH, declined);
		} catch (java.io.IOException ex) {
			//REMEMBERING THE DECLINE IS THE WHOLE FEATURE, so a write that failed costs exactly
			//what the memory exists to prevent: the offer comes back on every zone load, with
			//nothing anywhere to say why. What refuses it now is the workspace folder - not
			//writable, gone, its file unreadable. Said ONCE per panel: a report on every decline
			//would be the nagging again. The READ side says its own failure once too (forkDeclined).
			if (!forkDeclineSaveReported) {
				forkDeclineSaveReported = true;
				ctrmap.Ui.error(this,
						"The zones you declined a private map for could not be saved:\n"
						+ ctrmap.Ui.reason(ex)
						+ "\n\nThose offers will come back next time this workspace is opened.\n"
						+ "Nothing in the game or in the workspace is affected.",
						"Shared map");
			}
		}
	}

	/**
	 * Forking is the DEFAULT: when the freshly loaded zone shares its map with
	 * other zones (same map matrix - e.g. a town and its story-event copies, or
	 * a clone and its source), offer to give it a private copy immediately, so
	 * the user never unknowingly edits someone else's map. Zones created by
	 * today's tools are private from birth; this prompt is the migration net
	 * for zones from before auto-fork and for retail zones. Declines are
	 * remembered per zone, per workspace.
	 */
	private void offerForkIfShared() {
		Zone zone = loadedZone.open();
		int zoneIndex = loadedZone.index();
		if (zone == null || zoneIndex < 0 || !canFork() || forkDeclined().contains(zoneIndex) || loadedZone.count() == 0) {
			return;
		}
		int mm = zone.header.mapmatrixID;
		int baseZones = ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES;
		java.util.List<Integer> sharerIdx = new java.util.ArrayList<>();
		for (int i = 0; i < loadedZone.count(); i++) {
			Zone other = loadedZone.at(i);
			if (i != zoneIndex && other.header != null && other.header.mapmatrixID == mm) {   //never a null slot (F208)
				sharerIdx.add(i);
			}
		}
		int sharers = sharerIdx.size();
		if (sharers == 0) {
			return;
		}
		//name the sharers so "N other zones" is concrete; added zones (the
		//spare padding slots of an old append) are labeled for what they are
		StringBuilder who = new StringBuilder();
		for (int k = 0; k < sharerIdx.size() && k < 6; k++) {
			int i = sharerIdx.get(k);
			who.append("  - zone ").append(i).append(" (").append(LocationNames.getLocName(ctrmap.Workspace.session(), loadedZone.at(i).header.parentMap))
					.append(i >= baseZones ? ", added zone" : "").append(")\n");
		}
		if (sharerIdx.size() > 6) {
			who.append("  - and ").append(sharerIdx.size() - 6).append(" more\n");
		}
		//NO NOTE ABOUT PADDING SLOTS, because a padding slot cannot get this far any
		//more. There was one for a while: the spares an append rounds the count up
		//with kept the donor's map until 1.0.1, so opening one asked this question
		//about a zone the editor had created itself, and the note explained why.
		//PaddingZoneRepair.repairOnOpen now gives those spares their own map
		//before the zone list is even built, so by the time anyone can open one there
		//is nothing to explain. What reaches this question is what always should
		//have: a retail zone that genuinely shares its map with its story copies or
		//its neighbouring routes, which is not a defect and needs no history.
		int rsl = ctrmap.Ui.confirm(this,
				"This zone SHARES its map with " + sharers + " other zone(s):\n"
				+ who
				+ "\nEditing the map here would change those zones too.\n"
				+ "Give this zone its OWN private map now? (Recommended.\n"
				+ "Pure data - packs the workspace when done.)",
				"Shared map", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (rsl != JOptionPane.YES_OPTION) {
			java.util.Set<Integer> declined = forkDeclined();
			declined.add(zoneIndex);
			saveForkDeclined(declined);
			return;
		}
		final int idx = zoneIndex;
		try {
			ctrmap.GeometryForker.ForkResult r = ctrmap.GeometryForker.ensurePrivate(idx);
			Workspace.packWorkspace(new Runnable() {
				@Override
				public void run() {
					loadEverything(new Runnable() {
						@Override
						public void run() {
							selectZone(idx);
							ctrmap.Ui.message(ZoneLoadingPanel.this,
									"Zone " + idx + " now has its own private map (regions "
									+ java.util.Arrays.toString(r.newRegions) + ").\nEdits here no longer affect any other zone."
									+ (r.otherZones.length == 0 ? ""
											: "\n\nThe map also carries ground belonging to zone(s) "
											+ java.util.Arrays.toString(r.otherZones)
											+ ".\nYour copy keeps their labels, so walking there still reports them,"
											+ "\nbut edits you make to that ground now show only in this zone."),
									"Shared map", JOptionPane.INFORMATION_MESSAGE);
						}
					});
				}
			});
		} catch (Exception ex) {
			ctrmap.Ui.error(this, "Fork failed:\n" + ctrmap.util.Bytes.reason(ex), "Shared map");
		}
	}

	/**
	 * "Clone zone into existing slot" - safe variant, both slots must already
	 * exist (no GARC appending). See ZoneCloner for the byte-level contract.
	 */
	private void btnCloneZoneActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCloneZoneActionPerformed
		Zone zone = loadedZone.open();
		int zoneIndex = loadedZone.index();
		if (loadedZone.count() == 0 || zone == null || zoneIndex == -1) {
			ctrmap.Ui.error(this, "Load the source zone from the dropdown first.", "Clone zone");
			return;
		}
		//the cloner reads the last-SAVED workspace bytes, so flush any pending
		//on-screen edits first (same store chain as switching zones) - abort on cancel
		if (!openEditors.saveAll(true)) {
			return;
		}
		int srcIndex = zoneIndex;
		String[] names = new String[loadedZone.count()];
		for (int i = 0; i < names.length; i++) {
			names[i] = LocationNames.getLocName(ctrmap.Workspace.session(), loadedZone.at(i).header.parentMap) + " - " + i;
		}
		JComboBox<String> dstPicker = new JComboBox<>(names);
		dstPicker.setMaximumRowCount(20);
		javax.swing.JCheckBox forkChk = new javax.swing.JCheckBox(
				"Give this zone its own private map (edit it without changing the source)", true);
		forkChk.setEnabled(canFork());
		if (!canFork()) {
			//a disabled tick with no reason beside it reads as a bug in the
			//editor; say which game is loaded and what is missing for it
			forkChk.setText("Give this zone its own private map - not available for "
					+ Workspace.profile().displayName());
			forkChk.setSelected(false);
		}
		Object[] form = {
			"Source (currently loaded): " + names[srcIndex],
			"Destination (will be overwritten):",
			dstPicker,
			" ",
			forkChk
		};
		if (JOptionPane.showConfirmDialog(this, form, "Clone zone", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
			return;
		}
		int dstIndex = dstPicker.getSelectedIndex();
		if (dstIndex == srcIndex) {
			ctrmap.Ui.error(this, "The source and destination zones are the same.", "Clone zone");
			return;
		}
		final boolean doFork = forkChk.isSelected() && canFork();
		int confirm = ctrmap.Ui.confirm(this,
				"Zone " + dstIndex + " (" + names[dstIndex] + ") will be completely replaced by a copy of zone "
				+ srcIndex + " (" + names[srcIndex] + "):\n"
				+ "header, NPCs, warps, triggers, scripts.\n"
				+ "Its wild encounters are NOT changed.\n"
				+ (doFork ? "It will also get its OWN private map (independent geometry).\n" : "")
				+ "\nThis is reversible only by restoring a backup.",
				"Confirm zone clone", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (confirm != JOptionPane.OK_OPTION) {
			return;
		}
		try {
			ZoneCloner.cloneIntoSlot(srcIndex, dstIndex);
			clearForkDecline(dstIndex); //the slot holds a NEW zone now
			if (doFork) {
				//give the clone its own map so editing it won't change the source (same as Add zones)
				ctrmap.GeometryForker.ensurePrivate(dstIndex);
			}
		} catch (Exception ex) {
			Logger.getLogger(ZoneLoadingPanel.class.getName()).log(Level.SEVERE, null, ex);
			ctrmap.Ui.error(this, "Could not clone the zone:\n" + ctrmap.util.Bytes.reason(ex), "Clone zone");
			return;
		}
		final int dst = dstIndex;
		if (doFork) {
			//the fork appended FieldData/MapMatrix entries - pack so they apply, then reload + open
			Workspace.packWorkspace(new Runnable() {
				@Override
				public void run() {
					loadEverything(new Runnable() {
						@Override
						public void run() {
							if (dst < zoneList.getItemCount()) {
								zoneList.setSelectedIndex(dst);
							}
						}
					});
				}
			});
		} else {
			//reload the zone list from the workspace and open the freshly written destination
			loadEverything(); //modal - blocks until the worker is done
			zoneList.setSelectedIndex(dstIndex);
		}
	}//GEN-LAST:event_btnCloneZoneActionPerformed

	/**
	 * EXPERIMENTAL "append a brand-new zone slot" - ORAS only. See
	 * ZoneAppender for the byte-level contract. The append only stages the
	 * three workspace files, so the workspace is packed and fully reloaded
	 * immediately afterwards (GARC.length is constructor-only and one append
	 * is allowed per pack cycle).
	 */
	private void btnAddZoneActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnAddZoneActionPerformed
		if (!Workspace.isValid()) {
			ctrmap.Ui.error(this, "Load a workspace first (Options > Workspace settings).", "Add new zones");
			return;
		}
		if (!Workspace.profile().supports(GameProfile.Feature.ZONE_APPEND)) {
			//"ORAS-only in v1" described the EDITOR's history, not this user's
			//game, and said the same thing to X/Y, Sun/Moon and Ultra Sun/Moon
			//alike. ZoneAppender refuses again in the same words; this one is
			//here so the button does not open a dialog that cannot finish.
			ctrmap.Ui.error(this, "Adding new zones is not available for "
					+ Workspace.profile().displayName() + "."
					+ "\n\nZones past the ones a game ships need that game's zone-table limit"
					+ " found in its executable and raised by a code patch. Both were measured"
					+ " for Omega Ruby / Alpha Sapphire only, so CTRMap refuses here rather"
					+ " than writing a zone this game would never load.", "Add new zones");
			return;
		}
		if (loadedZone.count() == 0) {
			ctrmap.Ui.error(this, "Load a workspace first.", "Add new zones");
			return;
		}
		//the appender reads the last-SAVED workspace bytes, so flush any pending
		//on-screen edits first (same store chain as switching zones) - abort on cancel
		if (!openEditors.saveAll(true)) {
			return;
		}
		int baseCount = loadedZone.count();
		String[] names = new String[baseCount];
		for (int i = 0; i < baseCount; i++) {
			names[i] = LocationNames.getLocName(ctrmap.Workspace.session(), loadedZone.at(i).header.parentMap) + " - " + i;
		}
		JComboBox<String> srcPicker = new JComboBox<>(names);
		srcPicker.setMaximumRowCount(20);
		int zoneIndex = loadedZone.index();
		if (zoneIndex >= 0 && zoneIndex < names.length) {
			srcPicker.setSelectedIndex(zoneIndex);
		}
		javax.swing.JSpinner countSpin = new javax.swing.JSpinner(
				new javax.swing.SpinnerNumberModel(1, 1, ctrmap.formats.codepatch.ZoneLimitPatch.MAX_NEW_ZONES, 1));
		Object[] form = {
			"Lift ORAS's " + ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES + "-zone limit and add new zones for your fan game.",
			" ",
			"How many new zones to add?",
			countSpin,
			" ",
			"Copy each new zone's contents from:",
			srcPicker,
			" ",
			"New zones are added after the last one; nothing existing is overwritten.",
			"Each new zone automatically gets its OWN private map copy, so editing its",
			"geometry will not change the zone you copied it from.",
			"A matching code patch (code.ips) is generated - the game needs BOTH the",
			"new ZoneData and that patch installed, or it will not boot."
		};
		if (JOptionPane.showConfirmDialog(this, form, "Add new zones (lift zone limit)", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
			return;
		}
		int realZones = (Integer) countSpin.getValue();
		int srcIndex = srcPicker.getSelectedIndex();
		int m;
		try {
			//FROM THE ZONES THERE ARE NOW, a previous append's included (F039)
			m = ctrmap.formats.codepatch.ZoneLimitPatch.masterIndexFrom(baseCount, realZones);
		} catch (IllegalArgumentException tooMany) {
			ctrmap.Ui.error(this, "Cannot add " + realZones + " zone(s): " + ctrmap.util.Bytes.reason(tooMany) + ".",
					"Add new zones");
			return;
		}
		int spares = m - baseCount - realZones;
		String replaces = baseCount > ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES
				? "This game already has added zones, so the zone-limit patch saved now REPLACES the\n"
				+ "one in your code.ips - the old one names a zone table this game no longer has.\n"
				+ "Shop and item-icon changes already in that code.ips are kept.\n\n"
				: "";
		//strong warning, Cancel is the default option
		Object[] options = {"Continue", "Cancel"};
		int confirm = ctrmap.Ui.option(this,
				"Adding " + realZones + " new zone(s): indices " + baseCount + ".." + (baseCount + realZones - 1) + ".\n\n"
				+ "The game's zone table must stay 4-aligned, so the total is rounded up to " + m + "\n"
				+ "(" + realZones + " real + " + spares + " spare, never-visited slot(s)).\n\n"
				+ "FIRST-OF-ITS-KIND: no fan hack has ever added an ORAS zone. The archive and the\n"
				+ "code patch are byte-verified here, but the in-game boot is unproven - test in\n"
				+ "Azahar first and keep a RomFS backup.\n\n"
				+ replaces
				+ "You MUST install the generated code.ips or the game will black-screen:\n"
				+ "  Azahar: right-click the game -> Open Mods Location -> exefs\\code.ips\n"
				+ "  3DS Luma: sdmc:/luma/titles/000400000011C400/code.ips (enable Game Patching)\n\n"
				+ ctrmap.ZoneAppender.sharedWarning(srcIndex)
				+ "\n\nContinue?",
				"Add new zones (lift zone limit)",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
				options, options[1]);
		if (confirm != 0) {
			return;
		}
		//THE EXECUTABLE FIRST, BEFORE ANYTHING IS STAGED (F046). The patch is built against the
		//user's own code.bin and refused for any other build - and an append whose code patch
		//cannot be made is a game that black-screens, so nothing is appended without one.
		java.io.File codeFile = CodeBinFile.pick(this, "The zone-limit patch is built against it.",
				new java.io.File(Workspace.GAMEDIR_PATH));
		if (codeFile == null) {
			return;
		}
		final byte[] code;
		try {
			code = java.nio.file.Files.readAllBytes(codeFile.toPath());
			ctrmap.formats.codepatch.ZoneLimitPatch.verify(code);
		} catch (java.io.IOException | IllegalStateException notPatchable) {
			CodeBinFile.forget();
			ctrmap.Ui.error(this, "No zones were added: the zone-limit patch cannot be made for "
					+ codeFile.getName() + ".\n\n" + ctrmap.util.Bytes.reason(notPatchable), "Add new zones");
			return;
		}
		CodeBinFile.remember(codeFile);
		ctrmap.ZoneAppender.AppendResult res;
		try {
			//THE USER HAS JUST BEEN SHOWN WHAT THESE ZONES WILL SHARE, in the dialog
			//above, and pressed OK. The appender refuses the form that does not say so,
			//which is what stops a zone being created shared by accident from anywhere.
			res = ctrmap.ZoneAppender.appendZones(realZones, srcIndex, true);
		} catch (Exception ex) {
			Logger.getLogger(ZoneLoadingPanel.class.getName()).log(Level.SEVERE, null, ex);
			ctrmap.Ui.error(this, "Could not add the zones:\n" + ctrmap.util.Bytes.reason(ex), "Add new zones");
			return;
		}
		//generate + save the paired code patch (same N drives both sides)
		boolean savedIps = false;
		try {
			byte[] ips = ctrmap.formats.codepatch.ZoneLimitPatch.buildIPS(res.patchZones, code);
			javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
			fc.setDialogTitle("Save the zone-limit code patch (name it code.ips)");
			fc.setSelectedFile(new java.io.File("code.ips"));
			if (ctrmap.Ui.choose(this, fc, true) != null) {
				//into the code.ips already there, its shop and icon patches kept (F152)
				ctrmap.formats.codepatch.CodeIps.save(fc.getSelectedFile(), ips,
						ctrmap.formats.codepatch.ZoneLimitPatch.owned());
				savedIps = true;
			}
		} catch (Exception ex) {
			Logger.getLogger(ZoneLoadingPanel.class.getName()).log(Level.SEVERE, null, ex);
			ctrmap.Ui.message(this, "The zones were staged, but the code.ips could not be saved:\n" + ctrmap.util.Bytes.reason(ex)
					+ "\n\nThe game will not boot without it - regenerate it before testing.", "Add new zones", JOptionPane.WARNING_MESSAGE);
		}
		//the append changed the GARC layout. packWorkspace and loadEverything are
		//BOTH async (SwingWorkers); chain: pack -> reload -> select + confirm.
		final int firstNew = res.firstNewZone;
		final int lastReal = res.firstNewZone + res.realZones - 1;
		final int total = res.newZoneCount;
		final boolean ipsSaved = savedIps;
		Workspace.packWorkspace(new Runnable() {
			@Override
			public void run() {
				loadEverything(new Runnable() {
					@Override
					public void run() {
						if (firstNew < zoneList.getItemCount()) {
							zoneList.setSelectedIndex(firstNew);
						}
						ctrmap.Ui.message(ZoneLoadingPanel.this,
								"Added zones " + firstNew + ".." + lastReal + " (archive now holds " + total + " zone slots).\n\n"
								+ "Each new zone got its OWN private map (its own FieldData region + matrix),\n"
								+ "so editing its geometry won't affect the zone you copied it from.\n\n"
								+ (ipsSaved
										? "code.ips saved - install it (Azahar mods exefs, or Luma titles folder) before booting.\n\n"
										: "code.ips was NOT saved - the game will black-screen until you generate and install it.\n\n")
								+ "New zones aren't reachable until you point a warp or script at them (edit a\n"
								+ "warp in another zone to target zone " + firstNew + "). Wild encounters start empty.",
								"Add new zones", JOptionPane.INFORMATION_MESSAGE);
					}
				});
			}
		});
	}//GEN-LAST:event_btnAddZoneActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel BGMTitleLabel;
    private javax.swing.JFormattedTextField ad;
    private javax.swing.JLabel adLabel;
    private javax.swing.JFormattedTextField battleBG;
    private javax.swing.JLabel battleBGLabel;
    private javax.swing.JCheckBox bgmCyclingEnable;
    private javax.swing.JFormattedTextField bgmSpring;
    private javax.swing.JButton btnAddZone;
    private javax.swing.JButton btnCloneZone;
    private javax.swing.JButton btnSave;
    private javax.swing.JFormattedTextField cam1;
    private javax.swing.JLabel cam1label;
    private javax.swing.JFormattedTextField cam2;
    private javax.swing.JLabel cam2label;
    private javax.swing.JFormattedTextField camFlags;
    private javax.swing.JLabel camFlagsLabel;
    private javax.swing.JCheckBox coldbreath;
    private javax.swing.JCheckBox cycling;
    private javax.swing.JCheckBox dowsing;
    private javax.swing.JCheckBox enable3d;
    private javax.swing.JLabel engDataLabel;
    private javax.swing.JSeparator engDataSep;
    private javax.swing.JCheckBox escrope;
    private javax.swing.JCheckBox flash;
    private javax.swing.JCheckBox fly;
    private javax.swing.JCheckBox ghosting;
    private javax.swing.JCheckBox isParentMap;
    private javax.swing.JLabel loadZoneLabel;
    private javax.swing.JSeparator loaderSeparator;
    private javax.swing.JSeparator mainSep;
    private javax.swing.JLabel mapChangeLabel;
    private javax.swing.JComboBox<String> mapTransition;
    private javax.swing.JFormattedTextField matrix;
    private javax.swing.JLabel matrixLabel;
    private javax.swing.JFormattedTextField move;
    private javax.swing.JLabel moveLabel;
    private javax.swing.JFormattedTextField parentMap;
    private javax.swing.JLabel parentMapLabel;
    private javax.swing.JSeparator plrFlagsSep;
    private javax.swing.JLabel plrFlagsTitleLabel;
    private javax.swing.JCheckBox run;
    private javax.swing.JFormattedTextField script;
    private javax.swing.JLabel scriptLabel;
    private javax.swing.JCheckBox skate;
    private javax.swing.JCheckBox skybox;
    private javax.swing.JLabel spawnPointLabel;
    private javax.swing.JCheckBox specWalk;
    private javax.swing.JFormattedTextField textFile;
    private javax.swing.JLabel textLabel;
    private javax.swing.JComboBox<String> tmg;
    private javax.swing.JLabel tmgLabel;
    private javax.swing.JComboBox<String> type;
    private javax.swing.JLabel typeLabel;
    private javax.swing.JLabel uFlabel;
    private javax.swing.JCheckBox unknownFlag;
    private javax.swing.JFormattedTextField unknownFlags;
    private javax.swing.JSeparator unknownSep;
    private javax.swing.JLabel unknownTitleLabel;
    private javax.swing.JComboBox<String> weather;
    private javax.swing.JLabel weatherLabel;
    private javax.swing.JLabel worldPropLabel;
    private javax.swing.JFormattedTextField x1;
    private javax.swing.JLabel x1Label;
    private javax.swing.JFormattedTextField x2;
    private javax.swing.JLabel x2Label;
    private javax.swing.JFormattedTextField y1;
    private javax.swing.JLabel y1Label;
    private javax.swing.JFormattedTextField y2;
    private javax.swing.JLabel y2Label;
    private javax.swing.JFormattedTextField z1;
    private javax.swing.JLabel z1Label;
    private javax.swing.JFormattedTextField z2;
    private javax.swing.JLabel z2Label;
    private javax.swing.JComboBox<String> zoneList;
    // End of variables declaration//GEN-END:variables
}

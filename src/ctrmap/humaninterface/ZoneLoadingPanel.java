package ctrmap.humaninterface;

import static ctrmap.CtrmapMainframe.*;
import ctrmap.LoadedZone;
import ctrmap.Utils;
import ctrmap.Workspace;
import ctrmap.ZoneAppender;
import ctrmap.ZoneCloner;
import ctrmap.ZoneTables;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.cameradata.CameraDataFile;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.formats.propdata.ADPropRegistry;
import ctrmap.formats.text.LocationNames;
import ctrmap.formats.zone.Zone;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
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
public class ZoneLoadingPanel extends javax.swing.JPanel {

	/**
	 * The zone table, the open zone and its index, owned by {@link LoadedZone}
	 * and handed to this panel; the three used to be public fields here, read
	 * by fourteen classes through the window's static. Every write the panel
	 * makes goes through it, named for what happened.
	 */
	private final LoadedZone loadedZone;
	private boolean loaded = false;

	/** Creates the form over the zone owner it is handed. */
	/** Which tool the editor is holding, handed in: this class only asks. */
	private final ctrmap.humaninterface.tools.ToolSelection tools;

	public ZoneLoadingPanel(LoadedZone loadedZone, ctrmap.humaninterface.tools.ToolSelection tools) {
		this.tools = tools;
		if (loadedZone == null) {
			throw new IllegalArgumentException("ZoneLoadingPanel must be handed a LoadedZone");
		}
		this.loadedZone = loadedZone;
		initComponents();
		zoneList.setToolTipText("Select a map here to open it - this is the normal way to load a zone.");
		btnCloneZone.setToolTipText("Copy the currently loaded zone over another existing zone slot.");
		btnAddZone.setToolTipText("Add new zones and lift ORAS's 536-zone limit; also generates the required code.ips patch. ORAS only - test in Azahar first.");
		setIntValueClass(new JFormattedTextField[]{cam1, cam2, camFlags, unknownFlags, battleBG, ad, bgmSpring,
			matrix, textFile, script, move, parentMap, x1, y1, z1, x2, y2, z2});
	}

	public void loadZone(Zone z) {
		try {
			loaded = false;
			Zone previous = loadedZone.open();
			if (previous != null) {
				previous.header.freeArchives();
				System.gc();
			}
			//z is open at whatever index is selected: the list worker records
			//the index it picked right after this call, and a zone opened from
			//a file has no slot to record
			loadedZone.open(loadedZone.index(), z);

			isParentMap.setSelected(z.header.OLvalue == 1);
			cam1.setValue(z.header.camera1);
			cam2.setValue(z.header.camera2);
			camFlags.setValue(z.header.cameraFlags);
			x1.setValue(z.header.X);
			x2.setValue(z.header.X2);
			unknownFlags.setValue(z.header.unknownFlags);
			coldbreath.setSelected(z.header.enableBreathFX);
			ghosting.setSelected(z.header.enableGhosting);
			dowsing.setSelected(z.header.enableDowsingMachine);
			enable3d.setSelected(z.header.enable3D);
			unknownFlag.setSelected(z.header.unknownFlag);
			ad.setValue(z.header.areadataID);
			battleBG.setValue(z.header.battleBG);
			bgmSpring.setValue(z.header.BGMSpring);
			run.setSelected(z.header.enableRunning);
			skate.setSelected(z.header.enableRollerSkates);
			cycling.setSelected(z.header.enableCycling);
			escrope.setSelected(z.header.enableEscapeRope);
			fly.setSelected(z.header.enableFlyFrom);
			skybox.setSelected(z.header.enableSkybox);
			bgmCyclingEnable.setSelected(z.header.enableCyclingBGM);
			mapTransition.setSelectedIndex(z.header.mapChange);
			matrix.setValue(z.header.mapmatrixID);
			move.setValue(z.header.mapMove);
			parentMap.setValue(z.header.parentMap);
			script.setValue(z.header.script);
			textFile.setValue(z.header.textID);
			tmg.setSelectedIndex(z.header.townMapGroup);
			type.setSelectedIndex(getTypeIndex(z.header.mapType));
			weather.setSelectedIndex(getWeatherIndex(z.header.weather));
			y1.setValue(z.header.Y);
			z1.setValue(z.header.Z);
			y2.setValue(z.header.Y2);
			z2.setValue(z.header.Z2);

			specWalk.setSelected(z.header.enableSpecialWalking);
			flash.setSelected(z.header.enableFlashableDarkness);
			flash.setEnabled(Workspace.game() == GameType.ORAS);
			specWalk.setEnabled(Workspace.game() == GameType.XY);

			loaded = true;
		} catch (Exception e) {
			e.printStackTrace();
		}
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
		SwingWorker worker = new SwingWorker() {
			@Override
			protected void done() {
				progress.close();
				try {
					get(); //without this, anything thrown below vanishes and the
					//zone list is just silently empty with no error anywhere
				} catch (Exception ex) {
					Logger.getLogger(ZoneLoadingPanel.class.getName()).log(Level.SEVERE, "loading zones", ex);
					//...and the log alone IS silent to anyone not watching a
					//console. The zone count now comes from the profile and can
					//refuse in words for a game nobody has measured; that
					//sentence is worth nothing if it only reaches stderr.
					ctrmap.Ui.error(ZoneLoadingPanel.this, ctrmap.Ui.reason(ex), "Loading Zone data");
				}
				if (onDone != null) {
					onDone.run();
				}
			}

			@Override
			protected Object doInBackground() throws Exception {
				loaded = false;
				Zone previous = loadedZone.open();
				if (previous != null) {
					previous.header.freeArchives();
				}
				System.gc();
				loadedZone.release();
				zoneList.setSelectedIndex(-1);
				zoneList.removeAllItems();
				tmg.setSelectedIndex(-1);
				tmg.removeAllItems();
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
					btnAddZone.setToolTipText("Add new zones and lift ORAS's 536-zone limit; also generates the required code.ips patch. ORAS only - test in Azahar first.");
				}
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
				loadedZone.table(new Zone[totalZones]); //last file is not a ZO; filled slot by slot below
				for (int i = 0; i < totalZones; i++) {
					ZO zo = new ZO(Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, i), Workspace.session());
					Zone read = new Zone(zo, Workspace.game());
					loadedZone.replace(i, read);
					//unknown flags 1024 == 8192 ???, 4096, 16384 always 0, >> 20 lumi warp zone?,
					String name = LocationNames.getLocName(read.header.parentMap) + " - " + i;
					if (read.s.publics.size() > 3) {
						System.out.println(i + "/" + name);
					}
					/*for (int j = 0; j < zones[i].entities.NPCCount; j++){
						ZoneEntities.NPC npc = zones[i].entities.npcs.get(j);
						if (npc.movePerm2 != 1 && npc.movePerm2 != 0 && npc.movePerm2 != 4 && npc.movePerm2 != 9){
							System.out.println(name + "/NPC " + j + " - " + npc.movePerm2);
						}
					}*/
					zoneList.addItem(name);
					tmg.addItem(name);
					progress.setBarPercent((int) ((float) i / totalZones * 100));
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
				return null;
			}
		};
		worker.execute();
		progress.showDialog();
	}

	public boolean store(boolean dialog) {
		Zone zone = loadedZone.open();
		if (zone == null) {
			return true;
		}
		int zoneIndex = loadedZone.index();
		zone.header.mapType = getTypeRaw(type.getSelectedIndex());
		zone.header.mapMove = (Integer) move.getValue();
		zone.header.areadataID = (Integer) ad.getValue();
		zone.header.mapmatrixID = (Integer) matrix.getValue();
		zone.header.textID = (Integer) textFile.getValue();
		zone.header.script = (Integer) script.getValue();

		zone.header.townMapGroup = (Integer) tmg.getSelectedIndex();
		zone.header.mapChange = mapTransition.getSelectedIndex();
		zone.header.parentMap = (Integer) parentMap.getValue();
		zone.header.OLvalue = isParentMap.isSelected() ? 1 : 0;

		zone.header.weather = getWeatherRaw(weather.getSelectedIndex());
		zone.header.battleBG = (Integer) battleBG.getValue();
		zone.header.BGMSpring = (Integer) bgmSpring.getValue();
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
		zone.header.enable3D = enable3d.isSelected();

		zone.header.enableSkybox = skybox.isSelected();

		zone.header.camera1 = (Integer) cam1.getValue();
		zone.header.camera2 = (Integer) cam2.getValue();
		zone.header.cameraFlags = (Integer) camFlags.getValue();
		zone.header.unknownFlags = (Integer) unknownFlags.getValue();
		zone.header.unknownFlag = unknownFlag.isSelected();
		zone.header.unknownFlags = (Integer) unknownFlags.getValue();
		zone.header.calculateFlags();

		zone.header.X = (Integer) x1.getValue();
		zone.header.Y = (Integer) y1.getValue();
		zone.header.Z = (Integer) z1.getValue();

		zone.header.X2 = (Integer) x2.getValue();
		zone.header.Y2 = (Integer) y2.getValue();
		zone.header.Z2 = (Integer) z2.getValue();

		mNPCEditForm.saveEntry();
		mTriggerEditForm.saveEntry();
		boolean stored;
		try {
			stored = storeZone(dialog);
		} catch (IllegalStateException ex) {
			//a record that refuses to serialise - a warp with no destination -
			//used to leave here as an uncaught exception on stderr, and the
			//caller carried on as if the zone had been saved
			ctrmap.Ui.error(this, "Zone " + zoneIndex + " was not saved.\n" + ex.getMessage(), "Save zone");
			return false;
		}
		if (stored) {
			try {
				//save to master table - the entry just past the last zone.
				//WAS "length - (isXY() ? 1 : 2)", the same two-answers-for-four-
				//games arithmetic as the zone count: on a game whose tail nobody
				//has counted this wrote a zone header 0x38 bytes at a time into
				//an ORDINARY ZONE.
				File master = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA,
						ZoneTables.masterIndex(Workspace.getArchive(ArchiveType.ZONE_DATA)));
				RandomAccessFile dos = new RandomAccessFile(master, "rw");
				dos.skipBytes(zoneIndex * 0x38);
				byte[] test = new byte[0x38];
				dos.read(test);
				byte[] replace = zone.file.getFile(0);
				if (!Arrays.equals(replace, test)) {
					dos.seek(zoneIndex * 0x38);
					dos.write(replace);
					Workspace.addPersist(master);
				}
				dos.close();
			} catch (IOException ex) {
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
			switch (Utils.askToKeep(dialog, "Zone header")) {
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
			switch (Utils.askToKeep(dialog, "Entity data")) {
				case SAVE:
					keep.add(Zone.Part.ENTITIES);
					break;
				case DISCARD:
					zone.discardEntities();
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

	public void setIntValueClass(JFormattedTextField[] fields) {
		for (int i = 0; i < fields.length; i++) {
			((NumberFormatter) fields[i].getFormatter()).setValueClass(Integer.class);
		}
	}

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
		if (zoneList.getSelectedIndex() != -1 && loaded) {
			if (mCamEditForm.store(true) && mTileMapPanel.saveTileMap(true) && mMtxEditForm.store(true) && mPropEditForm.store(true) && mNPCEditForm.saveRegistry(true) && store(true)) {
				LoadingDialog progress = LoadingDialog.makeDialog("Loading zone");
				SwingWorker worker = new SwingWorker() {
					@Override
					protected void done() {
						progress.close();
						try {
							get(); //without this, anything thrown below vanishes and a zone
							//that failed to load looks loaded, the editors half on the last one
						} catch (Exception ex) {
							Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
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
						ctrmap.CtrmapMainframe.showWorldEditor();
						//forking is the safe default: a shared map means edits here
						//would silently change other zones too
						offerForkIfShared();
						//an active Map Builder must re-seed onto the NEW zone -
						//otherwise its panel keeps showing (and guarding against)
						//the previously painted zone
						if (tools.holding(ctrmap.humaninterface.tools.PaintTool.class)) {
							mPaintForm.activate();
						}
						//show this zone's own atmosphere in the 3D view
						ctrmap.CtrmapMainframe.refreshSceneFog();
					}

					@Override
					protected Object doInBackground() {
						mCamEditForm.store(true);
						progress.setBarPercent(20);
						Zone z = loadedZone.at(zoneList.getSelectedIndex());
						loadZone(z);
						loadedZone.open(zoneList.getSelectedIndex(), z);
						progress.setBarPercent(50);
						z.header.fetchArchives(Workspace.session());
						z.s.decompressThis();
						progress.setBarPercent(100);
						mTileMapPanel.loadMatrix(new MapMatrix(z.header.mapmatrix, Workspace.session()), new ADPropRegistry(z.header.areadata, z.header.propTextures, Workspace.session()), z.header.worldTextures, z.header.propTextures);
						mMtxPanel.loadMatrix(mTileMapPanel.mm);
						mCamEditForm.loadDataFile(new CameraDataFile(z.header.areadata));
						mNPCEditForm.loadFromEntities(z.entities, z.header.npcreg);
						mWarpEditForm.loadFromEntities(z.entities);
						mTriggerEditForm.loadFromEntities(z.entities);
						mScriptPnl.loadScript(z.s);
						m3DDebugPanel.bindNavi(null);
						System.gc();
						m3DDebugPanel.reload = true;
						mTileMapPanel.update = true;
						return null;
					}
				};
				worker.execute();
				progress.showDialog();
			}
		}
    }//GEN-LAST:event_zoneListActionPerformed

	/**
	 * Back to "no zone open", for a load that failed partway. The editors that
	 * had already switched would otherwise sit on a zone store() never writes,
	 * and the ones that had not would still hold the previous zone.
	 */
	private void unloadZone() {
		loadedZone.close();
		mNPCEditForm.loadFromEntities(null, null);
		mWarpEditForm.loadFromEntities(null);
		mTriggerEditForm.loadFromEntities(null);
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

	private java.util.Set<Integer> forkDeclined = null;
	private String forkDeclinedWs = null;

	/** Declined fork offers, remembered PER WORKSPACE across sessions (asking
	 *  again on every zone load reads as nagging). Reloads when the active
	 *  workspace changes so one workspace's declines never leak into another. */
	private java.util.Set<Integer> forkDeclined() {
		if (forkDeclined == null || !java.util.Objects.equals(forkDeclinedWs, Workspace.WORKSPACE_PATH)) {
			forkDeclined = new java.util.HashSet<>();
			forkDeclinedWs = Workspace.WORKSPACE_PATH;
			try {
				String csv = java.util.prefs.Preferences.userRoot().node("ctrmap.ZoneLoadingPanel")
						.get("FORK_DECLINED_" + Workspace.WORKSPACE_PATH.hashCode(), "");
				for (String tok : csv.split(",")) {
					if (!tok.trim().isEmpty()) {
						forkDeclined.add(Integer.parseInt(tok.trim()));
					}
				}
			} catch (Exception ignore) {
			}
		}
		return forkDeclined;
	}

	/** Forgets a remembered decline - call whenever a slot's OCCUPANT changes
	 *  (clone-into-slot, facility setup): the new zone deserves a fresh offer. */
	public void clearForkDecline(int zoneIndex) {
		if (forkDeclined().remove(zoneIndex)) {
			saveForkDeclined();
		}
	}

	/** Forgets remembered declines for every zone at or above the index (the
	 *  added-zone removal path). */
	public void clearForkDeclinesFrom(int minIndex) {
		boolean changed = forkDeclined().removeIf(z -> z >= minIndex);
		if (changed) {
			saveForkDeclined();
		}
	}

	private void saveForkDeclined() {
		try {
			StringBuilder sb = new StringBuilder();
			for (int z : forkDeclined()) {
				if (sb.length() > 0) {
					sb.append(',');
				}
				sb.append(z);
			}
			java.util.prefs.Preferences.userRoot().node("ctrmap.ZoneLoadingPanel")
					.put("FORK_DECLINED_" + Workspace.WORKSPACE_PATH.hashCode(), sb.toString());
		} catch (Exception ignore) {
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
			if (i != zoneIndex && other != null && other.header != null && other.header.mapmatrixID == mm) {
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
			who.append("  - zone ").append(i).append(" (").append(LocationNames.getLocName(loadedZone.at(i).header.parentMap))
					.append(i >= baseZones ? ", added zone" : "").append(")\n");
		}
		if (sharerIdx.size() > 6) {
			who.append("  - and ").append(sharerIdx.size() - 6).append(" more\n");
		}
		String legacyNote = zoneIndex >= baseZones
				? "\nThis zone was added before the editor forked new zones automatically\n"
				+ "- newly added zones now get their own map from the start.\n"
				: "";
		int rsl = ctrmap.Ui.confirm(this,
				"This zone SHARES its map with " + sharers + " other zone(s):\n"
				+ who
				+ legacyNote
				+ "\nEditing the map here would change those zones too.\n"
				+ "Give this zone its OWN private map now? (Recommended.\n"
				+ "Pure data - packs the workspace when done.)",
				"Shared map", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (rsl != JOptionPane.YES_OPTION) {
			forkDeclined().add(zoneIndex);
			saveForkDeclined();
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
			ctrmap.Ui.error(this, "Fork failed:\n" + ex.getMessage(), "Shared map");
		}
	}

	/**
	 * "Clone zone into existing slot" - safe variant, both slots must already
	 * exist (no GARC appending). See ZoneCloner for the byte-level contract.
	 */
	private void btnCloneZoneActionPerformed(java.awt.event.ActionEvent evt) {
		Zone zone = loadedZone.open();
		int zoneIndex = loadedZone.index();
		if (loadedZone.count() == 0 || zone == null || zoneIndex == -1) {
			ctrmap.Ui.error(this, "Load the source zone from the dropdown first.", "Clone zone");
			return;
		}
		//the cloner reads the last-SAVED workspace bytes, so flush any pending
		//on-screen edits first (same store chain as switching zones) - abort on cancel
		if (!(mCamEditForm.store(true) && mTileMapPanel.saveTileMap(true) && mMtxEditForm.store(true) && mPropEditForm.store(true) && mNPCEditForm.saveRegistry(true) && store(true))) {
			return;
		}
		int srcIndex = zoneIndex;
		String[] names = new String[loadedZone.count()];
		for (int i = 0; i < names.length; i++) {
			names[i] = LocationNames.getLocName(loadedZone.at(i).header.parentMap) + " - " + i;
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
			ctrmap.Ui.error(this, "Could not clone the zone:\n" + ex.getMessage(), "Clone zone");
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
	}

	/**
	 * EXPERIMENTAL "append a brand-new zone slot" - ORAS only. See
	 * ZoneAppender for the byte-level contract. The append only stages the
	 * three workspace files, so the workspace is packed and fully reloaded
	 * immediately afterwards (GARC.length is constructor-only and one append
	 * is allowed per pack cycle).
	 */
	private void btnAddZoneActionPerformed(java.awt.event.ActionEvent evt) {
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
		if (!(mCamEditForm.store(true) && mTileMapPanel.saveTileMap(true) && mMtxEditForm.store(true) && mPropEditForm.store(true) && mNPCEditForm.saveRegistry(true) && store(true))) {
			return;
		}
		int baseCount = loadedZone.count();
		String[] names = new String[baseCount];
		for (int i = 0; i < baseCount; i++) {
			names[i] = LocationNames.getLocName(loadedZone.at(i).header.parentMap) + " - " + i;
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
			"Lift ORAS's 536-zone limit and add new zones for your fan game.",
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
		int m = ctrmap.formats.codepatch.ZoneLimitPatch.masterIndex(realZones);
		int spares = m - baseCount - realZones;
		//strong warning, Cancel is the default option
		Object[] options = {"Continue", "Cancel"};
		int confirm = ctrmap.Ui.option(this,
				"Adding " + realZones + " new zone(s): indices " + baseCount + ".." + (baseCount + realZones - 1) + ".\n\n"
				+ "The game's zone table must stay 4-aligned, so the total is rounded up to " + m + "\n"
				+ "(" + realZones + " real + " + spares + " spare, never-visited slot(s)).\n\n"
				+ "FIRST-OF-ITS-KIND: no fan hack has ever added an ORAS zone. The archive and the\n"
				+ "code patch are byte-verified here, but the in-game boot is unproven - test in\n"
				+ "Azahar first and keep a RomFS backup.\n\n"
				+ "You MUST install the generated code.ips or the game will black-screen:\n"
				+ "  Azahar: right-click the game -> Open Mods Location -> exefs\\code.ips\n"
				+ "  3DS Luma: sdmc:/luma/titles/000400000011C400/code.ips (enable Game Patching)\n\n"
				+ "Continue?",
				"Add new zones (lift zone limit)",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
				options, options[1]);
		if (confirm != 0) {
			return;
		}
		ctrmap.ZoneAppender.AppendResult res;
		try {
			res = ctrmap.ZoneAppender.appendZones(realZones, srcIndex);
		} catch (Exception ex) {
			Logger.getLogger(ZoneLoadingPanel.class.getName()).log(Level.SEVERE, null, ex);
			ctrmap.Ui.error(this, "Could not add the zones:\n" + ex.getMessage(), "Add new zones");
			return;
		}
		//generate + save the paired code patch (same N drives both sides)
		boolean savedIps = false;
		try {
			byte[] ips = ctrmap.formats.codepatch.ZoneLimitPatch.buildIPS(realZones);
			javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
			fc.setDialogTitle("Save the zone-limit code patch (name it code.ips)");
			fc.setSelectedFile(new java.io.File("code.ips"));
			if (fc.showSaveDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
				java.io.FileOutputStream fos = new java.io.FileOutputStream(fc.getSelectedFile());
				fos.write(ips);
				fos.close();
				savedIps = true;
			}
		} catch (Exception ex) {
			Logger.getLogger(ZoneLoadingPanel.class.getName()).log(Level.SEVERE, null, ex);
			ctrmap.Ui.message(this, "The zones were staged, but the code.ips could not be saved:\n" + ex.getMessage()
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
	}

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

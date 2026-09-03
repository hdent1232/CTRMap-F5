package ctrmap;

import static ctrmap.CtrmapMainframe.*;
import ctrmap.formats.tilemap.EditorTileset;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.LocationNames;
import ctrmap.humaninterface.LoadingDialog;
import ctrmap.resources.ResourceAccess;
import java.awt.Component;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;

/**
 * The core of CTRMap filesystem access which seamlessly takes care of everything the editor wants from the game.
 */
public class Workspace {

	static Preferences prefs;
	public static String WORKSPACE_PATH;
	public static String GAMEDIR_PATH;
	public static String ESPICA_PATH;
	public static boolean TILESET_DEFAULT;
	public static String TILESET_PATH;

	public static File areadata;
	public static File fielddata;
	public static File mapmatrix;
	public static File gametext;
	public static File storytext;
	public static File zonedata;
	public static File buildingmodels;
	public static File npcregistries;
	public static File movemodels;
	public static File temp;

	public static File persist_config;
	public static ArrayList<String> persist_paths = new ArrayList<>();

	public static GARC ad;
	public static GARC gr;
	public static GARC mm;
	public static GARC texts;
	public static GARC storytexts;
	public static GARC zo;
	public static GARC bm;
	public static GARC npcreg;
	public static GARC npcmm;
	//trainer archives (ORAS-only; opened with compression sniffing DISABLED -
	//their entries are always raw and may legitimately start with 0x11)
	public static GARC trdata;
	public static GARC trclass;
	public static GARC trpoke;
	private static File trainerdataFile, trainerclassFile, trainerpokeFile;
	//Battle Maison opponent data (ORAS-only; sniff off - 16B set records may start 0x11)
	public static GARC maisonSetA, maisonListA, maisonSetB, maisonListB, maisonSetC;

	public static String[] musicNames;
	
	public static GameType game;
	public static boolean valid = false;

	public static void loadWorkspace() {
		prefs = Preferences.userRoot().node(Workspace.class.getName());
		WORKSPACE_PATH = prefs.get("WORKSPACE_PATH", "");
		GAMEDIR_PATH = prefs.get("GAMEDIR_PATH", "");
		ESPICA_PATH = prefs.get("ESPICA_PATH", "");
		TILESET_DEFAULT = prefs.getBoolean("TILESET_DEFAULT", true);
		TILESET_PATH = prefs.get("TILESET_PATH", "");
	}
	
	public static void createWorkspace(String wspath, String gamepath, String espicapath, boolean tilesetDefault, String customTilesetPath){
		WORKSPACE_PATH = wspath;
		GAMEDIR_PATH = gamepath;
		ESPICA_PATH = espicapath;
		TILESET_DEFAULT = tilesetDefault;
		TILESET_PATH = customTilesetPath;
	}

	public static EditorTileset getTileset() {
		if (!TILESET_DEFAULT) {
			if (TILESET_PATH != null) {
				File f = new File(TILESET_PATH);
				if (f.exists()) {
					EditorTileset ts = new EditorTileset(f);
					if (ts.tiles != null) { //if yes, the loader failed, wrong magic most likely
						return ts;
					}
				}
			}
		} else {
			return new EditorTileset(ResourceAccess.getStream("DefaultTileset.mets"));
		}
		Utils.showErrorMessage("Invalid tileset", "The tileset is corrupt. Restoring defaults.");
		TILESET_DEFAULT = true;
		return new EditorTileset(ResourceAccess.getStream("DefaultTileset.mets"));
	}

	/**
	 * True when CTRMap has never been pointed at a game. Deliberately tests the
	 * SETTINGS rather than the folders: a user whose dump lives on a drive that
	 * happens to be unplugged has still set CTRMap up, and must not be dragged
	 * back through first-run setup because of it.
	 */
	public static boolean isConfigured() {
		return GAMEDIR_PATH != null && !GAMEDIR_PATH.trim().isEmpty()
				&& WORKSPACE_PATH != null && !WORKSPACE_PATH.trim().isEmpty();
	}

	public static void validate(Component parent) {
		validate(parent, true);
	}

	/**
	 * @param showErrors when false, a failed validation returns quietly instead
	 * of throwing a list of missing archive names at the user. The setup wizard
	 * reports problems in its own words, and a brand-new user must never meet
	 * the raw list before they have had a chance to do anything.
	 */
	public static void validate(Component parent, boolean showErrors) {
		ArrayList<String> errors = new ArrayList<>();
		if (WORKSPACE_PATH == null) {
			errors.add("Workspace path not set");
		} else {
			File ws = new File(WORKSPACE_PATH);
			if (!ws.exists()) {
				errors.add("Workspace path not found");
			} else {
				//every directory getExtractionDirectory() can hand out, not just the
				//map ones. getWorkspaceFile opens a FileOutputStream without mkdirs,
				//so a missing directory here does not throw - it logs at SEVERE and
				//returns a File that was never written, which is how the trainer and
				//Maison editors used to fail silently on a brand-new workspace.
				Utils.mkDirsIfNotContains(ws, WORKSPACE_SUBDIRS);
				temp = new File(ws + "/temp");
				persist_config = new File(WORKSPACE_PATH + "/ctrmap_persist.txt");
				persist_paths.clear();
				if (persist_config.exists()) {
					try {
						Scanner scanner = new Scanner(persist_config);
						scanner.useDelimiter("\n"); //for better crossplatformness, force the Linux endline everywhere
						while (scanner.hasNextLine()) {
							persist_paths.add(WORKSPACE_PATH + scanner.nextLine());
						}
						scanner.close();
					} catch (IOException ex) {
						Logger.getLogger(Workspace.class.getName()).log(Level.SEVERE, null, ex);
					}
				}
			}
		}
		if (GAMEDIR_PATH == null) {
			errors.add("Game directory path not set");
		} else {
			File basepath = new File(GAMEDIR_PATH);
			if (!basepath.exists()) {
				errors.add("Game directory path not found");
			} else {
				//detect the game by each profile's probe file (gamedef)
				ctrmap.gamedef.GameProfile detected = ctrmap.gamedef.GameProfile.detect(basepath);
				if (detected == null) {
					errors.add("Could not detect game version");
				} else if (!detected.supports(ctrmap.gamedef.GameProfile.Feature.H3D_MAPS)) {
					errors.add(detected.displayName() + " detected - this game is not supported yet");
				} else {
					game = detected.type();
				}
				if (game != null) {
					//check needed archives
					areadata = new File(basepath + getArchivePath(ArchiveType.AREA_DATA, game));
					fielddata = new File(basepath + getArchivePath(ArchiveType.FIELD_DATA, game));
					mapmatrix = new File(basepath + getArchivePath(ArchiveType.MAP_MATRIX, game));
					gametext = new File(basepath + getArchivePath(ArchiveType.GAMETEXT, game));
					storytext = new File(basepath + getArchivePath(ArchiveType.STORYTEXT, game)); //no existence check - lazy-loaded on demand via getStoryTextGARC()
					zonedata = new File(basepath + getArchivePath(ArchiveType.ZONE_DATA, game));
					buildingmodels = new File(basepath + getArchivePath(ArchiveType.BUILDING_MODELS, game));
					npcregistries = new File(basepath + getArchivePath(ArchiveType.NPC_REGISTRIES, game));
					movemodels = new File(basepath + getArchivePath(ArchiveType.MOVE_MODELS, game));
//					musicNames = BCSArStringLoader.getStrings(new File(basepath + getArchivePath(ArchiveType.SOUND_BCSAR, game)));
					if (!areadata.exists()) {
						errors.add("AreaData GARC not found");
					}
					if (!fielddata.exists()) {
						errors.add("FieldData GARC not found");
					}
					if (!mapmatrix.exists()) {
						errors.add("MapMatrix GARC not found");
					}
					if (!gametext.exists()) {
						errors.add("GameText GARC not found");
					}
					if (!zonedata.exists()) {
						errors.add("ZoneData GARC not found");
					}
					if (!buildingmodels.exists()) {
						errors.add("BuildingModels GARC not found");
					}
					if (!npcregistries.exists()) {
						errors.add("NPCRegistries GARC not found");
					}
					if (!movemodels.exists()) {
						errors.add("MoveModels GARC not found");
					}
				}
			}
		}
		if (errors.isEmpty()) {
			valid = true;
			loadArchives();
			LocationNames.loadFromGarc();
			CtrmapMainframe.mBuilder.loadGARCs();
			CtrmapMainframe.mZonePnl.loadEverything();
			CtrmapMainframe.mTextEditor.loadGarc();
			CtrmapMainframe.showZoneLoadingHint();
		} else {
			valid = false;
			if (!showErrors) {
				return;
			}
			StringBuilder sb = new StringBuilder();
			for (String s : errors) {
				sb.append(s);
				sb.append("\n");
			}
			sb.append("\nRun Options > Setup wizard to point CTRMap at your game,\n");
			sb.append("then open a map from the zone dropdown in the \"Zone Loader\" tab.");
			JOptionPane.showMessageDialog(parent, sb.toString(), "Setup Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	public static void addPersist(File f) {
		if (!persist_paths.contains(f.getAbsolutePath())) {
			persist_paths.add(f.getAbsolutePath());
		}
	}

	/**
	 * RomFS-relative path of an archive for a game, or null when that game
	 * lacks it (or its location is not yet verified). The per-game tables live
	 * in {@link ctrmap.gamedef.GameProfile} and its subclasses - the single
	 * home for game-specific constants.
	 */
	public static String getArchivePath(ArchiveType archiveType, GameType gameType) {
		return ctrmap.gamedef.GameProfile.of(gameType).archivePath(archiveType);
	}

	/** The active game's profile (paths, text indices, feature gates). */
	public static ctrmap.gamedef.GameProfile profile() {
		return ctrmap.gamedef.GameProfile.of(game);
	}

	public static void cleanAll() {
		multiClean(true);
		persist_paths.clear();
		saveWorkspace();
	}

	public static void cleanAndReload() {
		mTileMapPanel.unload();
		mCollEditPanel.unload();
		mCamEditForm.unload();
		mNPCEditForm.unload();
		mPropEditForm.unload();
		cleanAll();
	}

	public static void cleanUnchanged() {
		multiClean(false);
	}

	/**
	 * Every per-archive extraction directory, in one place. {@link #validate}
	 * creates all of them and {@link #multiClean} empties all of them; when the
	 * two lists drifted apart, the directories only the second one knew about
	 * were never created and the editors that wrote into them failed silently.
	 * {@code _original_garcs} is deliberately absent - it is the pristine backup
	 * and must survive cleaning.
	 */
	public static final String[] WORKSPACE_SUBDIRS = {
		"areadata", "fielddata", "mapmatrix", "gametext", "storytext", "zonedata",
		"buildingmodels", "npcregistries", "movemodels",
		"trdata", "trclass", "trpoke",
		"maison_setA", "maison_listA", "maison_setB", "maison_listB", "maison_setC",
		"temp"
	};

	private static void multiClean(boolean deletePersistent) {
		for (String dir : WORKSPACE_SUBDIRS) {
			cleanDirectory(WORKSPACE_PATH + "/" + dir, "temp".equals(dir) || deletePersistent);
		}
	}

	public static void cleanDirectory(String dir, boolean deletePersistent) {
		File[] files = new File(dir).listFiles();
		if (files == null || files.length == 0) {
			return;
		}
		for (int i = 0; i < files.length; i++) {
			if (!deletePersistent) {
				if (persist_paths.contains(files[i].getAbsolutePath())) {
					continue;
				}
			}
			files[i].delete();
		}
	}

	/** Opens an ORAS-only GARC with compression sniffing off, or null if absent. */
	private static GARC optionalGarc(ArchiveType type) {
		String rel = getArchivePath(type, game);
		if (rel == null) {
			return null;
		}
		File f = new File(GAMEDIR_PATH + rel);
		return f.exists() ? new GARC(f, false) : null;
	}

	public static GARC getArchive(ArchiveType type) {
		switch (type) {
			case AREA_DATA:
				return ad;
			case TRAINER_DATA:
				return trdata;
			case TRAINER_CLASS:
				return trclass;
			case TRAINER_POKE:
				return trpoke;
			case MAISON_SET_POOL_A:
				return maisonSetA;
			case MAISON_CLASS_LIST_A:
				return maisonListA;
			case MAISON_SET_POOL_B:
				return maisonSetB;
			case MAISON_CLASS_LIST_B:
				return maisonListB;
			case MAISON_SET_POOL_C:
				return maisonSetC;
			case FIELD_DATA:
				return gr;
			case MAP_MATRIX:
				return mm;
			case GAMETEXT:
				return texts;
			case STORYTEXT:
				return getStoryTextGARC();
			case ZONE_DATA:
				return zo;
			case BUILDING_MODELS:
				return bm;
			case NPC_REGISTRIES:
				return npcreg;
			case MOVE_MODELS:
				return npcmm;
		}
		return null;
	}

	public static File getExtractionDirectory(ArchiveType type) {
		StringBuilder sb = new StringBuilder(WORKSPACE_PATH + "/");
		switch (type) {
			case AREA_DATA:
				sb.append("areadata");
				break;
			case FIELD_DATA:
				sb.append("fielddata");
				break;
			case TRAINER_DATA:
				sb.append("trdata");
				break;
			case TRAINER_CLASS:
				sb.append("trclass");
				break;
			case TRAINER_POKE:
				sb.append("trpoke");
				break;
			case MAISON_SET_POOL_A:
				sb.append("maison_setA");
				break;
			case MAISON_CLASS_LIST_A:
				sb.append("maison_listA");
				break;
			case MAISON_SET_POOL_B:
				sb.append("maison_setB");
				break;
			case MAISON_CLASS_LIST_B:
				sb.append("maison_listB");
				break;
			case MAISON_SET_POOL_C:
				sb.append("maison_setC");
				break;
			case MAP_MATRIX:
				sb.append("mapmatrix");
				break;
			case GAMETEXT:
				sb.append("gametext");
				break;
			case STORYTEXT:
				sb.append("storytext");
				break;
			case ZONE_DATA:
				sb.append("zonedata");
				break;
			case BUILDING_MODELS:
				sb.append("buildingmodels");
				break;
			case NPC_REGISTRIES:
				sb.append("npcregistries");
				break;
			case MOVE_MODELS:
				sb.append("movemodels");
				break;
			default:
				return null;
		}
		sb.append("/");
		return new File(sb.toString());
	}

	/**
	 * Lazily loads the STORYTEXT GARC (dialogue text; one GFMessageFile per
	 * ZoneHeader.textID). Unlike GAMETEXT it is not loaded at startup because
	 * of its size (637 files on ORAS).
	 *
	 * Archive paths follow pk3DS's GARCReference tables: storytext base GARC
	 * 079 (ORAS) / 080 (XY) plus the language offset (2 = English), giving
	 * /a/0/8/1 for ORAS and /a/0/8/2 for XY; other languages are adjacent
	 * (e.g. ORAS /a/0/7/9 JP-kana, /a/0/8/0 JP-kanji).
	 *
	 * @return the storytext GARC, or null if the workspace is not validated
	 * or the archive file does not exist
	 */
	public static GARC getStoryTextGARC() {
		if (storytexts == null && storytext != null && storytext.exists()) {
			storytexts = new GARC(storytext);
		}
		return storytexts;
	}

	public static void loadArchives() {
		if (valid) {
			storytexts = null; //enforce lazy re-load from the current game dir
			ad = new GARC(areadata);
			gr = new GARC(fielddata);
			mm = new GARC(mapmatrix);
			texts = new GARC(gametext);
			zo = new GARC(zonedata);
			bm = new GARC(buildingmodels);
			npcreg = new GARC(npcregistries);
			npcmm = new GARC(movemodels);
			//trainer archives (ORAS-only paths; optional - older partial dumps lack them)
			String trdPath = getArchivePath(ArchiveType.TRAINER_DATA, game);
			if (trdPath != null) {
				trainerdataFile = new File(GAMEDIR_PATH + trdPath);
				trainerclassFile = new File(GAMEDIR_PATH + getArchivePath(ArchiveType.TRAINER_CLASS, game));
				trainerpokeFile = new File(GAMEDIR_PATH + getArchivePath(ArchiveType.TRAINER_POKE, game));
				trdata = trainerdataFile.exists() ? new GARC(trainerdataFile, false) : null;
				trclass = trainerclassFile.exists() ? new GARC(trainerclassFile, false) : null;
				trpoke = trainerpokeFile.exists() ? new GARC(trainerpokeFile, false) : null;
			} else {
				trdata = trclass = trpoke = null;
			}
			//Battle Maison opponent GARCs (ORAS-only; optional)
			maisonSetA = optionalGarc(ArchiveType.MAISON_SET_POOL_A);
			maisonListA = optionalGarc(ArchiveType.MAISON_CLASS_LIST_A);
			maisonSetB = optionalGarc(ArchiveType.MAISON_SET_POOL_B);
			maisonListB = optionalGarc(ArchiveType.MAISON_CLASS_LIST_B);
			maisonSetC = optionalGarc(ArchiveType.MAISON_SET_POOL_C);
			snapshotOriginals();
		}
	}

	/** Directory holding the one-time pristine copy of the moddable archives,
	 *  used by {@link ModDeployer} to ship only what actually changed. */
	public static File originalSnapshotDir() {
		return new File(WORKSPACE_PATH + "/_original_garcs");
	}

	/** Records which game folder a pristine snapshot was taken from. */
	public static File originalSnapshotStamp() {
		return new File(originalSnapshotDir(), "taken-from.txt");
	}

	/**
	 * The game folder a workspace's pristine snapshot was taken from, or null
	 * when there is no snapshot or it predates stamping.
	 */
	public static String snapshotSourcePath() {
		File stamp = originalSnapshotStamp();
		if (!stamp.isFile()) {
			return null;
		}
		try (Scanner sc = new Scanner(stamp, "UTF-8")) {
			while (sc.hasNextLine()) {
				String line = sc.nextLine().trim();
				if (line.startsWith("gamedir=")) {
					return line.substring("gamedir=".length()).trim();
				}
			}
		} catch (IOException ex) {
			//unreadable stamp is treated as absent
		}
		return null;
	}

	/**
	 * True when this workspace already holds a pristine snapshot of a DIFFERENT
	 * game folder than the one configured.
	 *
	 * <p>This matters more than it looks. The snapshot is what "ship only what I
	 * changed" diffs against, and what the building palette, the atmosphere
	 * picker and the Maison guard read pristine data from. It is copied once and
	 * never refreshed, and cleaning a workspace does not remove it - so pointing
	 * an existing workspace at a second dump silently keeps the first dump's
	 * backup forever, and every one of those consumers is quietly wrong from
	 * then on with nothing to indicate it.
	 */
	public static boolean snapshotIsForeign() {
		return snapshotIsForeign(GAMEDIR_PATH);
	}

	/**
	 * The same question about a folder the workspace is about to be pointed at,
	 * so the settings dialog can ask BEFORE it repoints anything.
	 */
	public static boolean snapshotIsForeign(String gameDir) {
		String taken = snapshotSourcePath();
		if (taken == null || gameDir == null) {
			return false;
		}
		try {
			return !new File(taken).getCanonicalPath()
					.equalsIgnoreCase(new File(gameDir).getCanonicalPath());
		} catch (IOException ex) {
			return !taken.equalsIgnoreCase(gameDir);
		}
	}

	/** Deletes the pristine snapshot so the next load re-takes it from the current game folder. */
	public static void discardSnapshot() {
		File snap = originalSnapshotDir();
		deleteTree(snap);
	}

	private static void deleteTree(File f) {
		if (f == null || !f.exists()) {
			return;
		}
		File[] kids = f.listFiles();
		if (kids != null) {
			for (File k : kids) {
				deleteTree(k);
			}
		}
		f.delete();
	}

	/**
	 * One-time pristine snapshot of the moddable RomFS archives. Copies each to
	 * _original_garcs/&lt;archive path&gt; only when it is not already there, so it
	 * captures the state at the first load and is never overwritten (later loads,
	 * including post-pack reloads, are no-ops). Fully guarded: a snapshot failure
	 * must never break loading a workspace.
	 *
	 * <p>Stamps which game folder it copied from, so {@link #snapshotIsForeign}
	 * can catch a workspace that was later pointed at a different dump. A
	 * snapshot taken before stamping existed has no stamp; it is left alone and
	 * stamped in place rather than re-taken, because re-taking it against
	 * already-edited archives would bake the edits in as "pristine".
	 */
	/**
	 * Archives the snapshot is supposed to hold but does not.
	 *
	 * <p>Non-empty means the snapshot is PARTIAL and must not be trusted as a
	 * record of the retail game: the missing archives were never captured, and
	 * by the time anyone noticed, the live copies had been edited.
	 */
	public static java.util.List<String> snapshotMissingArchives() {
		java.util.List<String> missing = new java.util.ArrayList<>();
		if (GAMEDIR_PATH == null || WORKSPACE_PATH == null || game == null) {
			return missing;
		}
		File snap = originalSnapshotDir();
		for (ArchiveType t : ModDeployer.MODDABLE) {
			String rel = getArchivePath(t, game);
			if (rel == null) {
				continue;
			}
			if (new File(GAMEDIR_PATH + rel).exists()
					&& !new File(snap.getAbsolutePath() + rel).exists()) {
				missing.add(rel);
			}
		}
		return missing;
	}

	/**
	 * Copies the retail archives aside, once, so edits can be diffed against
	 * what the game shipped.
	 *
	 * <p>Capture happens ONLY while the snapshot is still being established -
	 * that is, before the stamp exists. Once stamped, a missing archive is left
	 * missing and reported, never filled in.
	 *
	 * <p>That restriction is the whole point. This used to copy any archive it
	 * found absent, on every load, which sounds harmless and is not: the
	 * snapshot is built archive by archive, so one added to the moddable list
	 * later - or one lost from the folder - was captured from a game the user
	 * had already been editing for weeks, and recorded as pristine. Six of the
	 * archives in the author's own workspace were contaminated exactly this
	 * way, and the stamp still said the snapshot was legitimate, so donors were
	 * cut from edited maps and the edits compounded.
	 *
	 * <p>Leaving the gap open is the safe failure. Consumers refuse to work
	 * from an absent snapshot ({@code BuildingCatalog.pristineRegion}); none of
	 * them can detect a present-but-wrong one.
	 */
	public static java.util.List<String> snapshotOriginals() {
		java.util.List<String> refused = new java.util.ArrayList<>();
		try {
			if (GAMEDIR_PATH == null || WORKSPACE_PATH == null || game == null) {
				return refused;
			}
			File snap = originalSnapshotDir();
			boolean established = originalSnapshotStamp().isFile();
			//A backup of one game folder says nothing about another. Repointing
			//a workspace at a second dump keeps the first one's backup - the
			//clean deliberately spares it - so Deploy diffs the new game
			//against the old one and ships archives nobody touched, and every
			//donor the palette cuts comes from the wrong game. The check for
			//this was written, with a javadoc describing exactly this failure,
			//and never called from anywhere.
			if (established && snapshotIsForeign()) {
				Ui.error(frame, "This workspace holds a pristine backup of a different game folder:\n  "
						+ snapshotSourcePath()
						+ "\n\nCTRMap compares your edits against that backup to work out what you"
						+ "\nchanged, and cuts donor buildings out of it, so both are now wrong for"
						+ "\n  " + GAMEDIR_PATH
						+ "\n\nDelete the backup folder\n  " + snap
						+ "\nand reload, against an unmodified game, to take a new one.",
						"Backup belongs to another game");
			}
			for (ArchiveType t : ModDeployer.MODDABLE) {
				String rel = getArchivePath(t, game);
				if (rel == null) {
					continue;
				}
				File live = new File(GAMEDIR_PATH + rel);
				File dst = new File(snap.getAbsolutePath() + rel);
				if (live.exists() && !dst.exists()) {
					if (established) {
						//the game has been in use since this snapshot was taken;
						//whatever is in the live archive now is not evidence of
						//what shipped
						refused.add(rel);
						continue;
					}
					if (dst.getParentFile() != null) {
						dst.getParentFile().mkdirs();
					}
					java.nio.file.Files.copy(live.toPath(), dst.toPath());
				}
			}
			if (!refused.isEmpty()) {
				System.err.println("Workspace: the pristine snapshot in " + snap
						+ " is missing " + refused.size() + " archive(s): " + refused
						+ "\n  They will NOT be captured from the live game, which may already be"
						+ " edited. Delete the snapshot folder and reload against an unmodified"
						+ " game to retake it whole.");
			}
			if (snap.isDirectory() && !originalSnapshotStamp().isFile()) {
				try (java.io.PrintWriter pw = new java.io.PrintWriter(
						originalSnapshotStamp(), "UTF-8")) {
					pw.println("# The game folder this pristine backup was copied from.");
					pw.println("# CTRMap compares your edits against it to ship only what changed.");
					pw.println("gamedir=" + new File(GAMEDIR_PATH).getAbsolutePath());
					pw.println("game=" + game);
				}
			}
		} catch (Exception ex) {
			System.err.println("Original-archive snapshot failed (non-fatal): " + ex);
		}
		return refused;
	}
	
	public static void reloadGARC(ArchiveType arc){
		switch (arc){
			case AREA_DATA:
				ad = new GARC(ad.file);
			case BUILDING_MODELS:
				bm = new GARC(bm.file);
			case FIELD_DATA:
				gr = new GARC(gr.file);
			case GAMETEXT:
				texts = new GARC(texts.file);
			case MAP_MATRIX:
				mm = new GARC(mm.file);
			case MOVE_MODELS:
				npcmm = new GARC(npcmm.file);
			case NPC_REGISTRIES:
				npcreg = new GARC(npcreg.file);
			case ZONE_DATA:
				zo = new GARC(zo.file);
			case STORYTEXT:
				if (storytexts != null) {
					storytexts = new GARC(storytexts.file);
				}
			case TRAINER_DATA:
			case TRAINER_CLASS:
			case TRAINER_POKE:
				//sniffing stays disabled - trainer entries are raw and may start with 0x11
				if (trdata != null) {
					trdata = new GARC(trdata.file, false);
				}
				if (trclass != null) {
					trclass = new GARC(trclass.file, false);
				}
				if (trpoke != null) {
					trpoke = new GARC(trpoke.file, false);
				}
			case MAISON_SET_POOL_A:
			case MAISON_CLASS_LIST_A:
			case MAISON_SET_POOL_B:
			case MAISON_CLASS_LIST_B:
			case MAISON_SET_POOL_C:
				if (maisonSetA != null) {
					maisonSetA = new GARC(maisonSetA.file, false);
				}
				if (maisonListA != null) {
					maisonListA = new GARC(maisonListA.file, false);
				}
				if (maisonSetB != null) {
					maisonSetB = new GARC(maisonSetB.file, false);
				}
				if (maisonListB != null) {
					maisonListB = new GARC(maisonListB.file, false);
				}
				if (maisonSetC != null) {
					maisonSetC = new GARC(maisonSetC.file, false);
				}
		}
	}

	public static File getWorkspaceFile(ArchiveType arc, int fileNum) {
		File wsFile;
		wsFile = new File(getExtractionDirectory(arc).getAbsolutePath() + "/" + fileNum);
		if (!wsFile.exists() && getArchive(arc).length > fileNum) {
			try {
				OutputStream os = new FileOutputStream(wsFile);
				byte[] b = getArchive(arc).getDecompressedEntry(fileNum);
				if (b == null) {
					os.close();
					return null;
				}
				os.write(b);
				os.flush();
				os.close();
			} catch (IOException ex) {
				Logger.getLogger(Workspace.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
		return wsFile;
	}

	public static void packWorkspace() {
		packWorkspace(null);
	}

	/** Progress sink for {@link #packArchives}: the dialog in the app, nothing in a headless test. */
	public interface PackProgress {

		void at(int percent, String what);
	}

	/**
	 * Packs every edited archive back into the game directory, in one fixed
	 * order, and reloads them. Synchronous and headless: the app runs it on a
	 * worker and the tests run it directly, so the two cannot pack differently.
	 *
	 * <p>Throws on the first archive that cannot be rewritten - the emulator
	 * or a virus scanner holding it open is the usual cause. Nothing is lost:
	 * the edits stay staged in the workspace and marked pending, so the next
	 * pack carries them. The failure used to be swallowed inside the archive
	 * writer, and the progress bar filled while the game kept the old map.
	 *
	 * <p>Returns everything the pack found wrong, as sentences meant for the
	 * user: a cross-archive reference that will stop a zone loading, an archive
	 * a second program is writing to, a pristine backup with holes in it. All
	 * three used to be printed to stderr, and the shipped build has no console,
	 * so a pack that could see the game was broken still ended in the success
	 * dialog alone.
	 */
	public static java.util.List<String> packArchives(PackProgress progress) throws IOException {
		//leftovers from a pack that threw are not this pack's news
		GARC.drainPackWarnings();
		progress.at(0, "Packing - fielddata");
		//a pending geometry fork appends private region copies (see GeometryForker)
		gr.packDirectory(getExtractionDirectory(ArchiveType.FIELD_DATA), GeometryForker.consumePendingFieldOverrides());
		progress.at(30, "Packing - areadata");
		//a pending area fork appends a private area copy (see AreaForker)
		ad.packDirectory(getExtractionDirectory(ArchiveType.AREA_DATA), AreaForker.consumePendingAreaOverrides());
		progress.at(60, "Packing - zonedata");
		//a pending zone append needs its compression overrides exactly once (see ZoneAppender)
		zo.packDirectory(getExtractionDirectory(ArchiveType.ZONE_DATA), ZoneAppender.consumePendingZoneDataOverrides());
		progress.at(65, "Packing - mapmatrix");
		//a pending geometry fork appends a rewired matrix (see GeometryForker)
		mm.packDirectory(getExtractionDirectory(ArchiveType.MAP_MATRIX), GeometryForker.consumePendingMatrixOverrides());
		progress.at(70, "Packing - buildingmodels");
		bm.packDirectory(getExtractionDirectory(ArchiveType.BUILDING_MODELS));
		progress.at(90, "Packing - npcregistries");
		//an area fork appends the matching registry entry (indexed by area id)
		npcreg.packDirectory(getExtractionDirectory(ArchiveType.NPC_REGISTRIES), AreaForker.consumePendingNpcRegOverrides());
		progress.at(95, "Packing - trainers");
		//trainer archives: pack only when actually edited (rewriting them
		//without edits would still be byte-faithful, but skip the churn)
		if (trdata != null && hasPersistedFiles(getExtractionDirectory(ArchiveType.TRAINER_DATA))) {
			trdata.packDirectory(getExtractionDirectory(ArchiveType.TRAINER_DATA));
		}
		if (trpoke != null && hasPersistedFiles(getExtractionDirectory(ArchiveType.TRAINER_POKE))) {
			trpoke.packDirectory(getExtractionDirectory(ArchiveType.TRAINER_POKE));
		}
		//Battle Maison opponent pools/lists (edited-only)
		for (ArchiveType mt : new ArchiveType[]{ArchiveType.MAISON_SET_POOL_A, ArchiveType.MAISON_CLASS_LIST_A,
			ArchiveType.MAISON_SET_POOL_B, ArchiveType.MAISON_CLASS_LIST_B, ArchiveType.MAISON_SET_POOL_C}) {
			GARC mg = getArchive(mt);
			if (mg != null && hasPersistedFiles(getExtractionDirectory(mt))) {
				mg.packDirectory(getExtractionDirectory(mt));
			}
		}
		progress.at(95, "Packing - gametext");
		//packDirectory rewrites the GARC in the game directory even with zero persisted files - only pack when text was actually edited
		if (hasPersistedFiles(getExtractionDirectory(ArchiveType.GAMETEXT))) {
			texts.packDirectory(getExtractionDirectory(ArchiveType.GAMETEXT));
		}
		progress.at(97, "Packing - storytext");
		//storytext is lazy-loaded and huge - only pack when dialogue was actually edited
		GARC storyGarc = getStoryTextGARC();
		if (storyGarc != null && hasPersistedFiles(getExtractionDirectory(ArchiveType.STORYTEXT))) {
			storyGarc.packDirectory(getExtractionDirectory(ArchiveType.STORYTEXT));
			reloadGARC(ArchiveType.STORYTEXT);
		}
		progress.at(100, "Done, updating GARCs");
		//the GARC indices may have changed and as such we need to reload them
		reloadGARC(ArchiveType.AREA_DATA);
		reloadGARC(ArchiveType.FIELD_DATA);
		reloadGARC(ArchiveType.ZONE_DATA);
		reloadGARC(ArchiveType.MAP_MATRIX);
		reloadGARC(ArchiveType.BUILDING_MODELS);
		reloadGARC(ArchiveType.NPC_REGISTRIES);
		reloadGARC(ArchiveType.GAMETEXT);
		//the session prop database was built from the pre-pack GARCs;
		//drop it so the next palette use rebuilds from the fresh archives
		ctrmap.formats.propdata.PropDatabase.invalidate();
		//The archives now on disk are what the game will load. Cross-
		//archive references are by bare index and nothing else checks
		//them, so an operation that grew one archive and not another
		//leaves a dangling index that only shows up much later, as a
		//zone that will not load. Check here, while the edit that
		//caused it is still the last thing that happened.
		java.util.List<String> warnings = new ArrayList<>();
		warnings.addAll(WorkspaceIntegrity.report("packing the workspace"));
		warnings.addAll(GARC.drainPackWarnings());
		java.util.List<String> missing = snapshotMissingArchives();
		if (!missing.isEmpty()) {
			warnings.add("the pristine backup in " + originalSnapshotDir() + " is missing "
					+ missing.size() + " archive(s): " + missing + ". They will NOT be captured"
					+ " from the game as it is now, which may already be edited - so Deploy"
					+ " cannot tell what you changed in them, and donor buildings cut from them"
					+ " are unavailable. Delete the backup folder and reload against an"
					+ " unmodified game to retake it whole.");
		}
		return warnings;
	}

	/**
	 * Puts what a pack found wrong in front of the user, through the dialog
	 * they already get when the pack finishes.
	 *
	 * <p>Separate from the pack itself on purpose: the pack runs on a worker
	 * with an application-modal progress dialog on screen, and a message shown
	 * from there would sit behind it, unreachable. This runs on the EDT once
	 * that dialog has closed.
	 */
	public static void reportPackWarnings(Component parent, java.util.List<String> warnings) {
		if (warnings == null || warnings.isEmpty()) {
			return;
		}
		StringBuilder sb = new StringBuilder("The workspace was packed, but the archives it wrote"
				+ " do not add up:\n");
		for (String w : warnings) {
			sb.append('\n').append(wrap(w));
		}
		sb.append("\nFix this before deploying - the game may not load.");
		Ui.error(parent, sb.toString(), "Pack workspace");
	}

	/** One warning, broken at spaces so the dialog does not grow off the screen. */
	private static String wrap(String s) {
		StringBuilder out = new StringBuilder("  - ");
		int lineStart = 0;
		for (String word : s.split(" ")) {
			if (out.length() - lineStart + word.length() > 92) {
				out.append("\n    ");
				lineStart = out.length();
			}
			out.append(word).append(' ');
		}
		return out.append('\n').toString();
	}

	/**
	 * Packs the workspace back into the game archives on a background worker.
	 * Because the pack is asynchronous (and only finishes reloading the GARCs -
	 * updating their entry counts - when the worker completes), callers that
	 * need the fresh archives (e.g. after a zone append changes the entry count)
	 * MUST pass onDone rather than running follow-up work on the calling thread.
	 * onDone runs on the EDT after packing and the GARC reloads are complete.
	 */
	public static void packWorkspace(final Runnable onDone) {
		if (valid) {
			LoadingDialog progress = LoadingDialog.makeDialog("Packing");
			final java.util.List<String> warnings = new ArrayList<>();
			SwingWorker worker = new SwingWorker() {
				@Override
				protected void done() {
					progress.close();
					try {
						get(); //without this, a pack that threw half-way closes the dialog and
						//onDone deploys or reloads as if every archive had been written
					} catch (Exception ex) {
						Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
						Logger.getLogger(Workspace.class.getName()).log(Level.SEVERE, "packing the workspace", cause);
						JOptionPane.showMessageDialog(CtrmapMainframe.frame, "The workspace was not packed:\n" + cause
								+ "\n\nThe game archives may be partly written. Fix the cause and pack again before deploying.", "Pack workspace", JOptionPane.ERROR_MESSAGE);
						return;
					}
					if (onDone != null) {
						onDone.run();
					}
				}

				@Override
				protected Object doInBackground() throws Exception {
					//a failure here must reach done() and the user, not the log
					warnings.addAll(packArchives((percent, what) -> {
						progress.setBarPercent(percent);
						progress.setDescription(what);
					}));
					return null;
				}
			};
			worker.execute();
			progress.showDialog();
			//showDialog returns on the EDT once done() has closed the progress
			//dialog, so this is the first moment anything else can be seen
			reportPackWarnings(CtrmapMainframe.frame, warnings);
		}
	}
	
	private static boolean hasPersistedFiles(File dir) {
		File[] files = dir.listFiles();
		if (files == null) {
			return false;
		}
		for (File f : files) {
			if (persist_paths.contains(f.getAbsolutePath())) {
				return true;
			}
		}
		return false;
	}

	public static String getMusicName(int id){
		return musicNames[id - 65536];
	}

	public static enum GameType {
		XY,
		ORAS,
		/** Sun/Moon (Gen 7) - detected/served via gamedef profiles; not yet supported. */
		SM,
		/** Ultra Sun/Ultra Moon (Gen 7) - not yet supported. */
		USUM
	}
	
	public static boolean isOA(){
		return game == GameType.ORAS;
	}
	
	public static boolean isOADemo(){
		return new File(GAMEDIR_PATH + ctrmap.gamedef.OrasProfile.DEMO_PROBE).exists();
	}
	
	public static boolean isXY(){
		return game == GameType.XY;
	}

	public static enum ArchiveType {
		AREA_DATA,
		FIELD_DATA,
		TRAINER_DATA,
		TRAINER_CLASS,
		TRAINER_POKE,
		MAISON_SET_POOL_A,
		MAISON_CLASS_LIST_A,
		MAISON_SET_POOL_B,
		MAISON_CLASS_LIST_B,
		MAISON_SET_POOL_C,
		MAP_MATRIX,
		GAMETEXT,
		STORYTEXT,
		ZONE_DATA,
		BUILDING_MODELS,
		NPC_REGISTRIES,
		MOVE_MODELS,
		/** Species base stats/types/abilities (read-only reference data). */
		PERSONAL,
		/** Move type/category/power mini-container (read-only reference data). */
		MOVE_DATA,
		SOUND_BCSAR
	}

	public static void prefsPutNonNull(String key, String value){
		if (value != null && key != null){
			prefs.put(key, value);
		}
	}
	
	public static void saveWorkspace() {
		if (prefs == null) {
			return; //loadWorkspace() never ran: a headless tool, not the app
		}
		prefsPutNonNull("WORKSPACE_PATH", WORKSPACE_PATH);
		prefsPutNonNull("GAMEDIR_PATH", GAMEDIR_PATH);
		prefsPutNonNull("ESPICA_PATH", ESPICA_PATH);
		prefs.putBoolean("TILESET_DEFAULT", TILESET_DEFAULT);
		prefsPutNonNull("TILESET_PATH", TILESET_PATH);
		try {
			prefs.flush(); //settings survive a crash, not just a clean exit
		} catch (java.util.prefs.BackingStoreException ex) {
			Logger.getLogger(Workspace.class.getName()).log(Level.SEVERE, null, ex);
		}
		if (persist_config == null) {
			return; //no workspace has validated yet, so there is no file to write
		}
		try {
			persist_config.delete();
			persist_config.createNewFile();
			BufferedWriter writer = new BufferedWriter(new FileWriter(persist_config));
			for (String line : persist_paths) {
				writer.write(line.replace(WORKSPACE_PATH, "") + "\n");  //write the paths relative to wsdir to allow moving workspace to other machines
			}
			writer.close();
		} catch (IOException ex) {
			Logger.getLogger(Workspace.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
}

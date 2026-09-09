package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.LoadedZone;
import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.containers.MM;
import ctrmap.formats.gfcollision.GRCollisionFile;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.formats.tilemap.Tilemap;
import ctrmap.gamedef.ArchiveType;
import ctrmap.humaninterface.TileMapPanel;
import java.awt.Dimension;
import java.awt.Point;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * The map view's non-painting half: what a save writes, what a cancelled save
 * refuses to lose, what unloading clears, and where the viewport thinks it is.
 *
 * <p>WHY THIS SUITE EXISTS. {@link TileMapPanel} is the editor's map, and
 * almost everything measured about it so far has been how it REPORTS failure -
 * {@code DataSafetyGuardsTest} drives {@code awaitLoad}, {@code refreshFailed}
 * and {@code regionSaveFailed}, and {@code NpcEntityGuardsTest} pins the static
 * height lookup's bounds. Deliberately not repeated here.
 *
 * <p>What was never measured is the decision that runs before any of those: the
 * save chain. Every zone switch, every clone and every append begins
 * {@code mCamEditForm.store(true) && mTileMapPanel.saveTileMap(true) && ...},
 * so this one boolean is what stands between "the user said cancel" and "the
 * editor moved on and their tiles are gone". Three of its arms write, clear a
 * flag, or do neither, and each is one line:
 *
 * <ul>
 * <li>YES writes the assembled tilemap into the region container and clears the
 *     modified flag. Drop the write and the flag still clears, so nothing ever
 *     offers to save it again - the edit is gone with the dialog having said it
 *     was kept.</li>
 * <li>NO clears the flag WITHOUT writing, which is the user discarding on
 *     purpose. Make it write and "no" silently means yes.</li>
 * <li>Anything else returns false, and false is what stops the zone switch. Get
 *     that backwards and cancel means proceed.</li>
 * </ul>
 *
 * <p>The matrix save has the same three arms over a grid, plus the one asymmetry
 * pinned below and not fixed: called with {@code dialog} false it writes
 * without asking, while the single-region save called the same way writes
 * nothing at all and leaves the region modified. Both are characterized as they
 * behave.
 *
 * <p>Painting, scaling and the 3D view are out of scope and skipped loudly:
 * {@code renderTileMap} and {@code loadTileMap} both call
 * {@code GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()},
 * which has no answer without a display. Their GUARDS are reachable and are
 * checked; what is behind them is not, and says so.
 *
 * Usage: java ctrmap.tests.TileMapPanelStateTest &lt;pristine dump root&gt;
 */
public class TileMapPanelStateTest {

	/** The 3D scene the map view shares: a recorder, so what it was told can be read. */
	static final RecordingScene SCENE = new RecordingScene();
	/** The tool this suite holds: its own, so another suite may hold another. */
	static final ctrmap.humaninterface.tools.ToolSelection TOOLS = new ctrmap.humaninterface.tools.ToolSelection();


	/** Mauville, whose map matrix is its own in the retail game. */
	private static final int ZONE = 15;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");

		//needs no game: pure geometry over arrays the suite builds itself
		thetwoWaysOfPointingTheCameraStayTwo();
		theViewportCentreIsWhereTheScrollBarsSay();
		aRegionIsPickedByTileNumber();
		theHeightLookupReadsThePanelsOwnCollisions();
		scalingRefusesWhatItCannotDraw();
		aStaleReloadIsIgnoredRatherThanWritten();

		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump
					+ " - the save-path checks need real region containers");
		} else {
			ScratchGame.open(dump);
			aSingleRegionSaveAsksAndActsOnTheAnswer();
			aCancelledSaveStopsTheLoadThatAskedForIt();
			aMatrixSaveAsksOnceAndWritesEveryRegion();
			aMatrixSaveThatIsRefusedWritesNothing();
			unloadingLeavesNothingOfTheOldMap();
		}

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ---- the save chain ----------------------------------------------------

	/**
	 * One region open, one tile edited, and the three answers the user can give.
	 *
	 * <p>The bytes are the assertion, because they are the only thing that
	 * distinguishes the three. All three close the dialog, all three let the
	 * editor carry on or not, and only the container on disk says which of them
	 * actually happened.
	 *
	 * <p>Also pinned, and NOT fixed: called with {@code dialog} false - which is
	 * how {@code saveMatrix} is reached from the same method, and how a
	 * scripted save would come in - the single-region path writes NOTHING and
	 * leaves the region marked modified, while still returning true. The
	 * multi-region path called the same way writes everything. See
	 * suspected_defects.
	 */
	static void aSingleRegionSaveAsksAndActsOnTheAnswer() throws Exception {
		System.out.println("--- one region: what each answer to the save prompt writes");
		TileMapPanel panel = single(153);
		byte[] pristine = panel.mainGR.getFile(0);

		//nothing modified: no question, no write
		List<String> said = Ui.record();
		boolean ok;
		try {
			ok = panel.saveTileMap(true);
		} finally {
			Ui.stopRecording();
		}
		check(ok && said.isEmpty(), "an unmodified region is saved without asking anything: " + said);
		check(Arrays.equals(panel.mainGR.getFile(0), pristine), "and without writing");

		//not loaded at all: still no question
		panel.loaded = false;
		said = Ui.record();
		try {
			ok = panel.saveTileMap(true);
		} finally {
			Ui.stopRecording();
		}
		check(ok && said.isEmpty(), "a panel with no map open saves without asking: " + said);
		panel.loaded = true;

		//edited, and the user keeps it
		byte[] edited = editATile(panel);
		said = Ui.record(JOptionPane.YES_OPTION);
		try {
			ok = panel.saveTileMap(true);
		} finally {
			Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).equals(
				"Save changes: Tilemap has been modified. Do you want to keep the changes?"),
				"an edited region asks whether to keep it, naming what changed: " + said);
		check(ok, "and keeping it lets the editor carry on");
		check(Arrays.equals(panel.mainGR.getFile(0), edited),
				"and the edited tilemap really is in the container");
		check(!panel.tilemaps[0][0].modified,
				"and the region is no longer marked modified, so it is not offered again");

		//edited, and the user discards it
		panel = single(154);
		pristine = panel.mainGR.getFile(0);
		editATile(panel);
		said = Ui.record(JOptionPane.NO_OPTION);
		try {
			ok = panel.saveTileMap(true);
		} finally {
			Ui.stopRecording();
		}
		check(ok, "discarding also lets the editor carry on");
		check(Arrays.equals(panel.mainGR.getFile(0), pristine),
				"but nothing was written - \"no\" does not quietly mean yes");
		check(!panel.tilemaps[0][0].modified, "and the discard is remembered, so it is not asked again");

		//edited, and the user cancels: the editor must NOT carry on
		panel = single(155);
		pristine = panel.mainGR.getFile(0);
		editATile(panel);
		said = Ui.record(JOptionPane.CANCEL_OPTION);
		try {
			ok = panel.saveTileMap(true);
		} finally {
			Ui.stopRecording();
		}
		check(!ok, "cancelling refuses, which is what stops the zone switch that asked");
		check(Arrays.equals(panel.mainGR.getFile(0), pristine), "with nothing written");
		check(panel.tilemaps[0][0].modified, "and the edit still marked modified, so it can still be saved");

		//nobody there at all reads as cancel, never as consent
		said = Ui.record();
		try {
			ok = panel.saveTileMap(true);
		} finally {
			Ui.stopRecording();
		}
		check(!ok && panel.tilemaps[0][0].modified,
				"a closed dialog is treated as cancel, not as permission to discard");

		//RETARGETED. This read "PINNED AS IS: dialog=false writes nothing here,
		//unlike the matrix path" - the asymmetry was seen, written down and
		//left standing. It was not an asymmetry, it was data loss: File > Save
		//is openEditors.saveAll(FALSE), so every tile edit made to a loose GR
		//map was reported saved and thrown away, and the matrix path a few
		//lines up never had the problem, so nobody with a zone open could see
		//it. The flag means "may I ask", not "may I write".
		ok = panel.saveTileMap(false);
		check(ok, "asked not to prompt, the single-region save returns true");
		check(!Arrays.equals(panel.mainGR.getFile(0), pristine),
				"AND WRITES: a flush that asks nobody still has to save");
		check(!panel.tilemaps[0][0].modified, "with nothing left pending afterwards");
	}

	/**
	 * A load that could not save first must not load.
	 *
	 * <p>{@code loadTileMap} opens with the save chain, so a cancelled save has
	 * to stop it: carrying on replaces the tilemap the user just refused to
	 * discard with a different region's, and the edit is gone with no dialog
	 * having said so.
	 *
	 * <p>Only the refusal is driven. Past it the method asks the local graphics
	 * environment for a screen device, which has no answer here.
	 */
	static void aCancelledSaveStopsTheLoadThatAskedForIt() throws Exception {
		System.out.println("--- a load whose pending save was cancelled does not load");
		TileMapPanel panel = single(156);
		Tilemap before = panel.tilemaps[0][0];
		GR wasOpen = panel.mainGR;
		editATile(panel);

		GR other = new GR(Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, 157), Workspace.session());
		List<String> said = Ui.record(JOptionPane.CANCEL_OPTION);
		try {
			panel.loadTileMap(other);
		} finally {
			Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).startsWith("Save changes: Tilemap"),
				"the load asks about the unsaved edit first: " + said);
		check(panel.mainGR == wasOpen && panel.tilemaps[0][0] == before,
				"and a cancelled answer leaves the map that was open exactly where it was");
		check(panel.tilemaps[0][0].modified, "with the edit still unsaved and still offered");
		System.out.println("  skip: the loading half of loadTileMap needs a screen"
				+ " (GraphicsEnvironment.getDefaultScreenDevice)");
	}

	/**
	 * A multi-region map: one edited cell, and the whole grid written.
	 *
	 * <p>The save asks ONCE, at the first modified cell it finds, and then
	 * writes every populated cell. That is worth pinning both ways round: ask
	 * per cell and a nine-region map asks nine times, and write only the
	 * modified cell and the flag-clearing loop still clears all nine, so the
	 * other eight are never offered again.
	 *
	 * <p>The bytes assert it - each cell's container is compared against the
	 * tilemap the panel would assemble for it.
	 */
	static void aMatrixSaveAsksOnceAndWritesEveryRegion() throws Exception {
		System.out.println("--- a matrix save asks once and writes every populated cell");
		TileMapPanel panel = multiCell();
		if (panel == null) {
			System.out.println("  skip: no retail map matrix of 2 to 8 populated cells was found");
			return;
		}
		int cells = populated(panel);
		check(cells >= 2, "a real multi-region map: " + panel.mm.width + "x" + panel.mm.height
				+ " with " + cells + " populated cell(s), so \"once\" is a claim about a grid");

		//nothing modified: no question, no write
		List<String> said = Ui.record();
		boolean ok;
		try {
			ok = panel.saveTileMap(true);
		} finally {
			Ui.stopRecording();
		}
		check(ok && said.isEmpty(), "an unmodified matrix is saved without asking: " + said);

		editATile(panel);
		byte[][][] wanted = new byte[panel.mm.width][panel.mm.height][];
		for (int i = 0; i < panel.mm.height; i++) {
			for (int j = 0; j < panel.mm.width; j++) {
				wanted[j][i] = panel.mm.regions.get(j, i) == null ? null : panel.tilemaps[j][i].assembleTilemap();
			}
		}
		said = Ui.record(JOptionPane.YES_OPTION);
		try {
			ok = panel.saveTileMap(true);
		} finally {
			Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).equals(
				"Save changes: Region data has been modified. Do you want to keep the changes?"),
				"an edited matrix asks ONCE, naming region data rather than one tilemap: " + said);
		check(ok, "and keeping it lets the editor carry on");
		int written = 0;
		boolean allWritten = true;
		for (int i = 0; i < panel.mm.height; i++) {
			for (int j = 0; j < panel.mm.width; j++) {
				if (wanted[j][i] == null) {
					continue;
				}
				written++;
				allWritten &= Arrays.equals(panel.mm.regions.get(j, i).getFile(0), wanted[j][i]);
			}
		}
		check(written == cells && allWritten,
				"and EVERY populated cell holds its own assembled tilemap, not just the edited one ("
				+ written + " of " + cells + " cell(s))");
		boolean anyLeftModified = false;
		for (int i = 0; i < panel.mm.height; i++) {
			for (int j = 0; j < panel.mm.width; j++) {
				anyLeftModified |= panel.mm.regions.get(j, i) != null && panel.tilemaps[j][i].modified;
			}
		}
		check(!anyLeftModified, "with nothing still marked modified");
	}

	/**
	 * The same map, refused.
	 *
	 * <p>Discarding must clear the flags without writing, and cancelling must
	 * write nothing AND keep the flags, because the flags are what the editor
	 * still knows about a map that is not on disk. This is the arm that stops a
	 * zone switch, so its return value is asserted with the bytes.
	 */
	static void aMatrixSaveThatIsRefusedWritesNothing() throws Exception {
		System.out.println("--- a refused matrix save keeps the map and the editor where they were");
		TileMapPanel panel = multi();
		byte[] pristine = panel.mm.regions.get(0, 0).getFile(0);
		editATile(panel);

		List<String> said = Ui.record(JOptionPane.CANCEL_OPTION);
		boolean ok;
		try {
			ok = panel.saveTileMap(true);
		} finally {
			Ui.stopRecording();
		}
		check(!ok, "cancelling a matrix save refuses, which stops the zone switch");
		check(Arrays.equals(panel.mm.regions.get(0, 0).getFile(0), pristine), "with nothing written");
		check(panel.tilemaps[0][0].modified, "and the edit still marked modified");

		said = Ui.record(JOptionPane.NO_OPTION);
		try {
			ok = panel.saveTileMap(true);
		} finally {
			Ui.stopRecording();
		}
		check(ok, "discarding lets the editor carry on");
		check(Arrays.equals(panel.mm.regions.get(0, 0).getFile(0), pristine),
				"and still writes nothing - a discard is not a save");
		check(!panel.tilemaps[0][0].modified, "but is remembered, so it is not asked again");

		//a matrix with no cells is not a reason to stop anything
		panel.mm = null;
		check(panel.saveMatrix(true), "a panel with no matrix at all saves trivially");
	}

	/**
	 * Unloading has to leave the placeholder, not half of a map.
	 *
	 * <p>Every field the renderers and the entity editors index into is cleared
	 * here, and the reason each one matters is the same: a stale array outlives
	 * the zone it came from, so the next read is either the previous map's data
	 * shown as the current one, or an index into an array that is now the wrong
	 * shape. {@code mode} goes back to SINGLE for the same reason - left on
	 * MULTI, the next save walks a null matrix.
	 */
	static void unloadingLeavesNothingOfTheOldMap() throws Exception {
		System.out.println("--- unload leaves nothing of the map that was open");
		TileMapPanel panel = multi();
		check(panel.loaded && panel.mm != null && panel.tilemaps != null
				&& panel.models != null && panel.colls != null,
				"the panel really is holding a map before the unload");
		check(panel.mode == TileMapPanel.ViewportMode.MULTI, "in multi-region mode");

		panel.unload();
		check(!panel.loaded, "unloaded: nothing claims to be open");
		check(panel.mm == null, "the map matrix is dropped");
		check(panel.tilemaps == null, "the tilemaps are dropped");
		check(panel.models == null, "the models are dropped");
		check(panel.tallgrass == null, "the tall grass is dropped");
		check(panel.colls == null, "the collisions are dropped, so no height read finds the old map");
		check(panel.mode == TileMapPanel.ViewportMode.SINGLE,
				"and the mode is back to single, so the next save does not walk a null matrix");
		check(panel.getRegionForTile(0, 0) == null,
				"and a region lookup answers null rather than throwing");
		check(panel.saveTileMap(true), "and saving an unloaded panel is trivially fine");
	}

	// ---- lookups and geometry ----------------------------------------------

	/**
	 * Which region a tile belongs to, by tile number.
	 *
	 * <p>40 tiles to a region, so the arithmetic is integer division and the
	 * cell is [x/40][y/40] - x indexes the FIRST dimension. The tools that
	 * place things (the geometry editor, the Map Builder) ask this to find the
	 * container to write into, so a transposed answer edits the wrong region of
	 * a map that is not square, silently, because both regions are valid.
	 */
	static void aRegionIsPickedByTileNumber() {
		System.out.println("--- a tile number picks its region");
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS));
		check(panel.getRegionForTile(0, 0) == null,
				"with no map open the lookup answers null rather than throwing");

		Tilemap[][] grid = new Tilemap[3][2]; //3 regions wide, 2 tall
		panel.tilemaps = grid;
		check(panel.getRegionForTile(0, 0) == grid[0][0], "tile (0,0) is the top-left region");
		check(panel.getRegionForTile(39, 39) == grid[0][0], "the last tile of that region is still in it");
		check(panel.getRegionForTile(40, 0) == grid[1][0], "tile (40,0) is one region EAST, not south");
		check(panel.getRegionForTile(0, 40) == grid[0][1], "tile (0,40) is one region SOUTH");
		check(panel.getRegionForTile(119, 79) == grid[2][1], "the far corner is the last region");
	}

	/**
	 * The panel's own height lookup reads the panel's own collisions.
	 *
	 * <p>The bounds arithmetic is pinned by NpcEntityGuardsTest against the
	 * static form. What is pinned here is the one line that binds the instance
	 * to it: the NPC tool drags an entity, asks the panel for the ground height
	 * and writes the answer into the file, so an instance method reading
	 * anything but this panel's {@code colls} gives an altitude belonging to
	 * another map.
	 */
	static void theHeightLookupReadsThePanelsOwnCollisions() {
		System.out.println("--- the panel's height lookup reads the panel's own collisions");
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS));
		panel.colls = new GRCollisionFile[2][4]; //2 wide, 4 tall, all cells empty
		check(panel.getHeightAtWorldLoc(100f, 3 * 720f + 10f) == 0f,
				"a position inside this panel's 2x4 matrix reads its empty cell as height 0");
		check(Float.isNaN(panel.getHeightAtWorldLoc(2 * 720f, 100f)),
				"and one past its width is NaN, which is the caller's cue to leave the altitude alone");
		panel.colls = new GRCollisionFile[1][1];
		check(Float.isNaN(panel.getHeightAtWorldLoc(100f, 3 * 720f + 10f)),
				"and the same position over a 1x1 matrix is now out of range,"
				+ " so the answer really came from this panel's collisions");
	}

	/**
	 * Where the viewport's centre is, in raw pixels, world units and tiles.
	 *
	 * <p>This is where the NPC, warp and trigger tools put a NEW entity: "Add"
	 * places it at the centre of what the user is looking at. Wrong, and the
	 * object lands off screen in a corner of the map, which reads as the button
	 * having done nothing at all - the record is created, so the user presses
	 * Add again.
	 *
	 * <p>Three conversions of one number, each with its own scale: raw pixels,
	 * then world units at 720 per 400 pixels divided by the zoom, then tiles at
	 * the image's own pixels-per-tile. They are asserted against the arithmetic
	 * spelled out rather than against constants, so the check says which
	 * conversion moved if one of them does.
	 */
	/**
	 * Opening a map points the 3D camera at it - and the two ways of doing that
	 * are NOT the same call.
	 *
	 * <p>WHY THIS IS WORTH A SECTION. Loading a single region and loading a
	 * matrix both aim the camera at the new map with four almost-identical
	 * assignments - except the matrix path also zeroes the yaw and the
	 * single-region path does not. Orbit the 3D view, then open a loose GR map
	 * file, and the new map comes up at the angle you left the last one at.
	 *
	 * <p>That asymmetry was five field writes on a JOGL panel reached through the
	 * main window, so nothing could see it and nothing did. It is two named
	 * methods now, and this asserts they stay two: behind a boolean the
	 * difference reads as an oversight and the next person removes it.
	 */
	static void thetwoWaysOfPointingTheCameraStayTwo() {
		System.out.println("--- the two ways of pointing the 3D camera are not the same call");
		SCENE.reset();
		SCENE.frameSingleRegion();
		SCENE.frameMatrix(4, 3);
		check(SCENE.framings.size() == 2 && !SCENE.framings.get(0).equals(SCENE.framings.get(1)),
			"they are two distinct calls, not one with an argument: " + SCENE.framings);
		check(SCENE.framings.get(1).equals("matrix 4x3"),
			"and the matrix one carries the size it is framing: " + SCENE.framings.get(1));
		SCENE.reset();
	}

	static void theViewportCentreIsWhereTheScrollBarsSay() {
		System.out.println("--- the viewport centre, in pixels, world units and tiles");
		//the scroll pane is HANDED to the panel now rather than planted on the
		//window, so it has to be built before the panel and given to it - which is
		//also the only way the two can be the same object
		JPanel view = new JPanel();
		view.setPreferredSize(new Dimension(2000, 2000));
		JScrollPane sp = new JScrollPane(view);
		sp.getViewport().setSize(300, 200);
		sp.getViewport().setViewPosition(new Point(640, 480));
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, sp,
			new ctrmap.humaninterface.CollEditPanel(TOOLS));

		panel.height = 2;                       //2 regions tall
		panel.tilemapScale = 0.5d;
		panel.tilemapScaledImage = new java.awt.image.BufferedImage(
				400, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);

		Point pos = sp.getViewport().getViewPosition();
		int imgStartX = (panel.getWidth() - 400) / 2;
		int imgStartY = (panel.getHeight() - 400) / 2;
		int rawX = pos.x - imgStartX + 300 / 2;
		int rawY = pos.y - imgStartY + 200 / 2;
		check(sp.getViewport().getViewPosition().equals(new Point(640, 480)),
				"the scroll position the checks are built on really took (" + pos.x + "," + pos.y + ")");

		Point raw = panel.getRawAtViewportCentre();
		check(raw.equals(new Point(rawX, rawY)),
				"the raw centre is the scroll position plus half the viewport, less the image's"
				+ " offset inside the panel: expected (" + rawX + "," + rawY + "), got " + raw);

		Point world = panel.getWorldLocAtViewportCentre();
		Point wantWorld = new Point((int) Math.round(raw.x / 400d * 720d / panel.tilemapScale),
				(int) Math.round(raw.y / 400d * 720d / panel.tilemapScale));
		check(world.equals(wantWorld),
				"the world position is that in 720-per-region units, divided by the zoom: expected "
				+ wantWorld + ", got " + world);

		Point tile = panel.getTileAtViewportCentre();
		double perRegion = 400 / (double) panel.height;
		Point wantTile = new Point((int) Math.round(raw.x / perRegion), (int) Math.round(raw.y / perRegion));
		check(tile.equals(wantTile),
				"and the tile is the raw centre over the image's own pixels-per-tile: expected "
				+ wantTile + ", got " + tile);

		//the zoom really is in the world conversion and not in the tile one
		panel.tilemapScale = 1.0d;
		check(!panel.getWorldLocAtViewportCentre().equals(world),
				"changing the zoom moves the world position");
		check(panel.getTileAtViewportCentre().equals(tile),
				"and leaves the tile position alone, because that one reads the scaled image");
	}

	/**
	 * Scaling refuses what it cannot draw, rather than trying.
	 *
	 * <p>Both scaling paths are guarded by {@code loaded && scale > 0.05 &&
	 * scale <= 1}, and the guard is the only thing between them and a
	 * {@code createCompatibleImage} of a negative or absurd size. The bounds
	 * are asserted at the edges because that is where an inverted or shifted
	 * comparison shows: 1.0 is allowed and anything above it is not.
	 *
	 * <p>What the guard protects is not reachable here - the drawing behind it
	 * needs a screen - so only the refusals are driven, and the zoom is the
	 * observable: a refused scale must not have been remembered.
	 */
	static void scalingRefusesWhatItCannotDraw() {
		System.out.println("--- scaling refuses a zoom it cannot draw, and does not remember it");
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS));
		panel.tilemapScale = 0.25d;

		panel.loaded = false;
		panel.scaleImage(0.5d);
		check(panel.tilemapScale == 0.25d, "with no map open a scale request is ignored");
		panel.perfScale(0.5d, 0, 0);
		check(panel.tilemapScale == 0.25d, "and so is a per-region one");

		panel.loaded = true;
		panel.scaleImage(0.05d);
		check(panel.tilemapScale == 0.25d, "a zoom at the lower bound is refused (0.05 is not > 0.05)");
		panel.scaleImage(0d);
		check(panel.tilemapScale == 0.25d, "and so is zero");
		panel.scaleImage(-1d);
		check(panel.tilemapScale == 0.25d, "and a negative one");
		panel.scaleImage(1.5d);
		check(panel.tilemapScale == 0.25d, "and anything past 1, which is full size");
		panel.perfScale(2d, 0, 0);
		check(panel.tilemapScale == 0.25d, "the per-region path holds the same bounds");
		System.out.println("  skip: an ACCEPTED scale renders through"
				+ " GraphicsEnvironment.getDefaultScreenDevice, which needs a display");
	}

	/**
	 * A stale live-refresh must be dropped, not written somewhere else.
	 *
	 * <p>The Map Builder regenerates a region on a worker and hands the result
	 * back here; by the time it arrives the user may have loaded another zone,
	 * so the scene it was computed against is gone or a different shape.
	 * Without the bounds test the write lands in whatever array is there now,
	 * which is another map's region.
	 *
	 * <p>Only the refusals are driven: accepting one ends in {@code
	 * makeAllBOs}, which builds GL buffer objects.
	 */
	static void aStaleReloadIsIgnoredRatherThanWritten() {
		System.out.println("--- a stale region refresh is dropped rather than written into another map");
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS));
		byte[] notEvenAModel = new byte[]{'B', 'C', 'H'};

		panel.models = null;
		panel.reloadRegionModel(0, 0, notEvenAModel);
		check(panel.models == null, "a refresh arriving after the scene was torn down is dropped");

		ctrmap.formats.h3d.BCHFile[][] models = new ctrmap.formats.h3d.BCHFile[2][2];
		panel.models = models;
		panel.reloadRegionModel(-1, 0, notEvenAModel);
		panel.reloadRegionModel(0, -1, notEvenAModel);
		panel.reloadRegionModel(2, 0, notEvenAModel);
		panel.reloadRegionModel(0, 2, notEvenAModel);
		boolean untouched = true;
		for (int x = 0; x < 2; x++) {
			for (int y = 0; y < 2; y++) {
				untouched &= models[x][y] == null;
			}
		}
		check(untouched, "and a cell outside the scene it was computed for writes nothing, on either axis");
		System.out.println("  skip: an ACCEPTED region refresh ends in makeAllBOs, which needs a GL context");
	}

	// ---- plumbing ----------------------------------------------------------

	/** A panel holding one region, the way loadTileMap leaves it. */
	static TileMapPanel single(int regionId) throws Exception {
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS));
		GR gr = new GR(Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, regionId), Workspace.session());
		panel.mode = TileMapPanel.ViewportMode.SINGLE;
		panel.mainGR = gr;
		panel.tilemaps = new Tilemap[1][1];
		panel.tilemaps[0][0] = new Tilemap(gr);
		panel.width = 40;
		panel.height = 40;
		panel.loaded = true;
		return panel;
	}

	/** A panel holding the whole of zone 15's map, the way loadMatrix leaves it. */
	static TileMapPanel multi() throws Exception {
		ctrmap.formats.zone.Zone z = new ctrmap.formats.zone.Zone(
				new ctrmap.formats.containers.ZO(temp(Workspace.getArchive(ArchiveType.ZONE_DATA).getDecompressedEntry(ZONE)), Workspace.session()),
				Workspace.game());
		File mmFile = Workspace.getWorkspaceFile(ArchiveType.MAP_MATRIX, z.header.mapmatrixID);
		return over(new MapMatrix(new MM(mmFile, Workspace.session()), Workspace.session()));
	}

	/** A panel over a parsed map matrix, the way loadMatrix leaves one. */
	static TileMapPanel over(MapMatrix mm) throws Exception {
		TileMapPanel panel = new TileMapPanel(new LoadedZone(), TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS));
		panel.mm = mm;
		panel.mode = TileMapPanel.ViewportMode.MULTI;
		panel.tilemaps = new Tilemap[mm.width][mm.height];
		panel.models = new ctrmap.formats.h3d.BCHFile[mm.width][mm.height];
		panel.tallgrass = new ctrmap.formats.h3d.BCHFile[mm.width][mm.height];
		panel.colls = new GRCollisionFile[mm.width][mm.height];
		for (int i = 0; i < mm.height; i++) {
			for (int j = 0; j < mm.width; j++) {
				if (mm.regions.get(j, i) != null) {
					panel.tilemaps[j][i] = new Tilemap(mm.regions.get(j, i));
				}
			}
		}
		panel.width = mm.width * 40;
		panel.height = mm.height * 40;
		panel.loaded = true;
		return panel;
	}

	/**
	 * The first retail map matrix with between 2 and 8 populated cells, so
	 * "asks once and writes every cell" is a claim about a real grid rather
	 * than about a single region. Bounded above because every populated cell
	 * opens a container and builds a tilemap.
	 */
	static TileMapPanel multiCell() throws Exception {
		int matrices = Workspace.getArchive(ArchiveType.MAP_MATRIX).length;
		for (int id = 0; id < matrices; id++) {
			File f = Workspace.getWorkspaceFile(ArchiveType.MAP_MATRIX, id);
			if (f == null || !f.isFile()) {
				continue;
			}
			MapMatrix mm = new MapMatrix(new MM(f, Workspace.session()), Workspace.session());
			if (mm.width * mm.height < 2 || mm.width * mm.height > 8) {
				continue;
			}
			TileMapPanel panel = over(mm);
			if (populated(panel) >= 2) {
				return panel;
			}
		}
		return null;
	}

	static int populated(TileMapPanel panel) {
		int n = 0;
		for (int i = 0; i < panel.mm.height; i++) {
			for (int j = 0; j < panel.mm.width; j++) {
				if (panel.mm.regions.get(j, i) != null) {
					n++;
				}
			}
		}
		return n;
	}

	/**
	 * Edits one tile of the panel's first region and returns the bytes a save
	 * must then write, so the assertion is against what the editor holds rather
	 * than against a literal.
	 */
	static byte[] editATile(TileMapPanel panel) {
		Tilemap tm = panel.tilemaps[0][0];
		byte[] was = tm.getTileData(3, 4);
		byte[] now = Arrays.copyOf(was, was.length);
		now[0] = (byte) (now[0] ^ 0x10); //a different tile, whatever it was
		tm.setTileData(3, 4, now);
		tm.modified = true;
		return tm.assembleTilemap();
	}

	static File temp(byte[] bytes) throws Exception {
		File f = File.createTempFile("ctrmap_tilemap", ".bin");
		f.deleteOnExit();
		java.nio.file.Files.write(f.toPath(), bytes);
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

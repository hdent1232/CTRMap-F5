package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BchTexturePack;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.formats.propdata.PropDatabase;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainCatalog;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
import ctrmap.formats.zone.Zone;
import ctrmap.humaninterface.TilePainterForm;
import ctrmap.humaninterface.ZoneLoadingPanel;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Apply in the Map Builder must be all-or-nothing. Each of these was a live
 * defect, and each left the workspace holding something the next Pack
 * Workspace would ship while the dialog said "Apply failed" or "Painted map
 * applied".
 *
 * <ol>
 * <li>The painted regions were written BEFORE the texture carry, and the carry
 *     refuses a shared area. "Apply failed: Area 43 is also used by zones 72,
 *     73" therefore left region 272 in the workspace with a 3,200-triangle sand
 *     floor whose material names a texture the area does not hold - white in
 *     game, and the prop code calls that condition a hardlock. 418 retail zones
 *     share their area and 362 of those have no sand material to start with, so
 *     painting sand on most of the game did this.</li>
 * <li>A placed building's door prop was registered - textures imported,
 *     registry rewritten - straight into the zone's area before any check, so
 *     one click on Littleroot grew area 8 by poke_gym_door01 and poke_gym_mado
 *     behind zones 7, 23 and 25, and then refused the sand.</li>
 * <li>An in-Apply geometry fork left the zone's own cell unresolvable (the
 *     loaded header still named the old matrix), so a composite Apply on a
 *     shared matrix skipped every region and reported "Painted map applied"
 *     with nothing painted.</li>
 * </ol>
 *
 * Runs headless in a throwaway workspace over the pristine dump. The only
 * dialog on the path is the shared-area prompt; a headless JVM makes it throw,
 * which is a refusal like any other and must have written nothing.
 *
 * Usage: java ctrmap.tests.PaintApplyGuardsTest &lt;pristine-dump-root&gt; [src]
 */
public class PaintApplyGuardsTest {

	static final int DIM = PaintedRegionBuilder.DIM;

	static int fails = 0;
	/** What the last {@link #apply} told the user, or null when it was stopped. */
	static String report;
	static GARC ad, gr, zo;
	static TilePalette[][] grid = new TilePalette[DIM][DIM];
	static boolean[][] touched = new boolean[DIM][DIM];
	static int[][] height = new int[DIM][DIM];
	static int[][] ramp = new int[DIM][DIM];

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!new File(dump, "a/0/3/9").isFile()) {
			System.out.println("  skip: no pristine dump at " + dump);
			System.out.println("ALL PASS");
			return;
		}
		System.setProperty("java.awt.headless", "true");
		openWorkspace(dump);

		refusedCarryWritesNothing();
		doorPropWaitsForTheAreaDecision();
		failedCarryLeavesRegionsAlone();
		appliedMapCarriesItsTextures();
		retryStillAsksForTheTextures();
		sharedMatrixIsPaintedAfterTheFork();
		paintedCliffsCarryTheirTexture();
		anExtraThatFailsDoesNotUnsayTheApply();
		everyAreaImportAsksTheSharedQuestion(new File(args.length > 1 ? args[1] : "src"));
		sameNameDifferentPixelsIsNotSilent();
		applyCountsTilesThatTookANeighboursGround();
		aRegionThatCannotBeWrittenStopsTheApply();
		aDoorPropTheAreaHasNotRegisteredIsAnAreaWrite();
		//these two fork zone 74's area, so nothing above may run after them
		cancellingTheSharedAreaQuestionWritesNothing();
		acceptingTheForkPaintsTheZone();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * Zone 74 (area 43, shared with zones 72 and 73; its own single-region
	 * matrix). Painting sand needs the SAND donor's texture carried into the
	 * area, which is refused for a shared one - and the refusal must find the
	 * region still untouched.
	 */
	static void refusedCarryWritesNothing() throws Exception {
		open(74);
		paintSand();
		List<String> before = new ArrayList<>(Workspace.persist_paths);
		Exception stop = apply(74, new ArrayList<TilePainterForm.Placed>());
		check(stop != null, "Apply on a shared-area zone does not report success (stopped by: " + stop + ")");
		check(newlyPersisted(before).isEmpty(), "nothing was persisted by the refused Apply: " + newlyPersisted(before));
		check(pristine(Workspace.ArchiveType.FIELD_DATA, gr, 272), "region 272 is byte-identical to the archive");
		check(pristine(Workspace.ArchiveType.AREA_DATA, ad, 43), "area 43 is byte-identical to the archive");
	}

	/**
	 * Zone 6 (Littleroot, area 8 shared with zones 7, 23 and 25) with a Gym
	 * placed: its door prop needs two textures and a registry entry in the area.
	 * Those must wait for the same decision as the terrain carry.
	 */
	static void doorPropWaitsForTheAreaDecision() throws Exception {
		open(6);
		paintSand();
		BuildingCatalog.Entry gym = null;
		for (BuildingCatalog.Entry e : BuildingCatalog.entries()) {
			if ("Gym".equals(e.name)) {
				gym = e;
			}
		}
		if (gym == null) {
			System.out.println("  skip: no Gym in the building catalogue");
			return;
		}
		List<TilePainterForm.Placed> placed = new ArrayList<>();
		placed.add(new TilePainterForm.Placed(gym, 20, 20));
		List<String> before = new ArrayList<>(Workspace.persist_paths);
		Exception stop = apply(6, placed);
		check(stop != null, "Apply with a door prop on a shared-area zone does not report success (stopped by: " + stop + ")");
		check(newlyPersisted(before).isEmpty(), "nothing was persisted by the refused Apply: " + newlyPersisted(before));
		check(pristine(Workspace.ArchiveType.AREA_DATA, ad, 8), "shared area 8 is byte-identical to the archive (no door textures, no registry entry)");
	}

	/**
	 * Zone 15 (Mauville mall, private area 21, private matrix): the area is
	 * nobody else's, so no prompt - but the SAND donor area is swapped for one
	 * that lacks the texture, so the carry itself fails. The region must still
	 * be untouched and the failure must name the texture.
	 */
	static void failedCarryLeavesRegionsAlone() throws Exception {
		open(15);
		paintSand();
		TerrainCatalog.Donor sand = TerrainCatalog.donors().get(TilePalette.SAND);
		List<String> needed = TerrainCatalog.ensureMaterial(new GR(Workspace.getWorkspaceFile(
				Workspace.ArchiveType.FIELD_DATA, 153)).getFile(1), TilePalette.SAND).texturesNeeded;
		check(sand != null && !needed.isEmpty(), "the SAND brush imports a donor material that needs textures: " + needed);
		int lacking = areaLacking(needed);
		File donorFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.AREA_DATA, sand.donorArea);
		Files.write(donorFile.toPath(), ad.getDecompressedEntry(lacking));
		List<String> before = new ArrayList<>(Workspace.persist_paths);
		Exception stop = apply(15, new ArrayList<TilePainterForm.Placed>());
		donorFile.delete();
		check(stop != null && stop.getMessage() != null && stop.getMessage().contains(needed.get(0)),
				"a carry that cannot find its texture stops the Apply and names it (stopped by: " + stop + ")");
		check(newlyPersisted(before).isEmpty(), "nothing was persisted by the failed Apply: " + newlyPersisted(before));
		check(pristine(Workspace.ArchiveType.FIELD_DATA, gr, 153), "region 153 is byte-identical to the archive");
		check(pristine(Workspace.ArchiveType.AREA_DATA, ad, 21), "area 21 is byte-identical to the archive");
	}

	/** The same Apply with the real donor: the map lands AND its textures do. */
	static void appliedMapCarriesItsTextures() throws Exception {
		open(15);
		paintSand();
		//what the pristine map will need, read before anything paints it
		List<String> needed = TerrainCatalog.ensureMaterial(PropDatabase.getSubfile(
				gr.getDecompressedEntry(153), 1), TilePalette.SAND).texturesNeeded;
		forget(Workspace.ArchiveType.FIELD_DATA, 153);
		List<String> before = new ArrayList<>(Workspace.persist_paths);
		Exception stop = apply(15, new ArrayList<TilePainterForm.Placed>());
		check(stop == null, "Apply on a private zone succeeds (stopped by: " + stop + ")");
		File region = Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, 153);
		check(newlyPersisted(before).contains(region.getAbsolutePath()), "region 153 was written");
		check(sandTriangles(new GR(region).getFile(1)) > 0, "and it carries the painted sand floor");
		File area = Workspace.getWorkspaceFile(Workspace.ArchiveType.AREA_DATA, 21);
		check(newlyPersisted(before).contains(area.getAbsolutePath()), "area 21 was written");
		check(!needed.isEmpty() && areaTextures(Files.readAllBytes(area.toPath())).containsAll(needed),
				"and it now holds the sand texture(s) " + needed);
	}

	/**
	 * The retry after a refused carry, which is what the user does next. Zone
	 * 15's region now carries the SAND brush's imported material from the apply
	 * above, and its area is put back the way a REFUSED carry leaves it -
	 * without the texture. Applying again must notice and carry it: reporting
	 * "Painted map applied" over the same white floor, with no message at all
	 * this time, is how the user found out in the emulator instead.
	 */
	static void retryStillAsksForTheTextures() throws Exception {
		byte[] painted = new GR(Workspace.getWorkspaceFile(
				Workspace.ArchiveType.FIELD_DATA, 153)).getFile(1);
		List<String> needed = TerrainCatalog.ensureMaterial(PropDatabase.getSubfile(
				gr.getDecompressedEntry(153), 1), TilePalette.SAND).texturesNeeded;
		TerrainCatalog.ImportResult again = TerrainCatalog.ensureMaterial(painted, TilePalette.SAND);
		check(!again.injected && again.texturesNeeded.equals(needed),
				"a map that already carries the brush's material still reports its textures "
				+ again.texturesNeeded + " (the donor's are " + needed + ")");
		forget(Workspace.ArchiveType.AREA_DATA, 21);
		open(15);
		paintSand();
		Exception stop = apply(15, new ArrayList<TilePainterForm.Placed>());
		check(stop == null, "the retry applies (stopped by: " + stop + ")");
		File area = Workspace.getWorkspaceFile(Workspace.ArchiveType.AREA_DATA, 21);
		check(!needed.isEmpty() && areaTextures(Files.readAllBytes(area.toPath())).containsAll(needed),
				"and it carries the sand texture(s) " + needed + " the refused carry never delivered");
	}

	/**
	 * Zone 0 shares matrix 0 with 44 other zones but owns area 2. Apply forks
	 * the geometry itself here, and the paint must land in the private copy -
	 * not in the shared original, and not nowhere.
	 */
	static void sharedMatrixIsPaintedAfterTheFork() throws Exception {
		open(0);
		paintSand();
		List<String> before = new ArrayList<>(Workspace.persist_paths);
		Exception stop = apply(0, new ArrayList<TilePainterForm.Placed>());
		check(stop == null, "Apply on a shared-matrix zone succeeds (stopped by: " + stop + ")");
		check(pristine(Workspace.ArchiveType.FIELD_DATA, gr, 0), "the shared region 0 is byte-identical to the archive");
		File painted = null;
		String fd = Workspace.getExtractionDirectory(Workspace.ArchiveType.FIELD_DATA).getAbsolutePath();
		for (String p : newlyPersisted(before)) {
			if (p.startsWith(fd) && sandTriangles(new GR(new File(p)).getFile(1)) > 0) {
				painted = new File(p);
			}
		}
		check(painted != null, "the zone's private copy of the region carries the painted sand floor");
	}

	/**
	 * The shared-area rule lived inside carryToArea alone, so the door-prop and
	 * prop-editor imports walked straight past it: placing any building with a
	 * door - 400 of ~535 zones are on a shared area - imported its textures and
	 * rewrote the prop registry of an area fifteen other maps could be using,
	 * with no prompt and no line in the result dialog. Every production import
	 * into an area now goes through BchTexturePack.importIntoArea, which asks
	 * the question first; the raw pack-level importTextures is for the format
	 * tests, which have no area at all.
	 */
	static void everyAreaImportAsksTheSharedQuestion(File src) throws Exception {
		int shared = -1, own = -1;
		for (int z = 0; z < zo.length - 2 && (shared < 0 || own < 0); z++) {
			int area = ctrmap.AreaForker.currentArea(z);
			boolean many = BchTexturePack.zonesUsingArea(area, z) != null;
			shared = many && shared < 0 ? z : shared;
			own = !many && own < 0 ? z : own;
		}
		check(shared >= 0 && refusal(shared) != null && refusal(shared).getMessage().contains("also used by"),
				"an area other zones use is refused, and the refusal names them (" + refusal(shared) + ")");
		check(own >= 0 && refusal(own) == null, "a zone's own private area is not refused");

		File root = new File(src, "ctrmap");
		if (!root.isDirectory()) {
			System.out.println("  skip: no source at " + root);
			return;
		}
		List<String> raw = new ArrayList<>();
		rawImports(root, raw);
		check(raw.isEmpty(), "no editor path imports textures into an area without asking: " + raw);
	}

	/**
	 * Past the commit point the map IS on disk, so an extra that cannot run -
	 * the door-warp wiring, the sign wiring - is a note on a successful Apply,
	 * never an "Apply failed" over a map that was applied. The warp step used
	 * to throw straight out of applyToZone, and PaintForm then put the preview
	 * back and told the user nothing had happened. Here the warp dialog itself
	 * cannot open (headless), which is exactly the shape of that failure.
	 */
	static void anExtraThatFailsDoesNotUnsayTheApply() throws Exception {
		BuildingCatalog.Entry gym = null;
		for (BuildingCatalog.Entry e : BuildingCatalog.entries()) {
			if ("Gym".equals(e.name)) {
				gym = e;
			}
		}
		if (gym == null) {
			System.out.println("  skip: no Gym in the building catalogue");
			return;
		}
		forget(Workspace.ArchiveType.FIELD_DATA, 153);
		forget(Workspace.ArchiveType.AREA_DATA, 21);
		open(15);
		paintSand();
		List<TilePainterForm.Placed> placed = new ArrayList<>();
		placed.add(new TilePainterForm.Placed(gym, 20, 20));
		Exception stop = apply(15, placed);
		check(stop == null, "an Apply whose door wiring cannot run still reports success (stopped by: " + stop + ")");
		File region = Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, 153);
		check(sandTriangles(new GR(region).getFile(1)) > 0, "and the map it wrote is on disk");
	}

	/**
	 * A painted slope's cliff faces are a catalogue import like any brush, but
	 * PaintedRegionBuilder makes them INSIDE the build, where Apply could not
	 * see what they needed - and 227 of the game's 228 areas do not hold the
	 * cliff donor's texture. Every map painted with a height change therefore
	 * shipped a material pointing at a texture its area does not have, which is
	 * the condition this editor's own prop code calls a hardlock on area load.
	 */
	static void paintedCliffsCarryTheirTexture() throws Exception {
		List<String> needed = TerrainCatalog.donorTextures(TerrainCatalog.cliffDonor());
		check(!needed.isEmpty(), "the generated cliff faces need the donor's texture(s) " + needed);
		forget(Workspace.ArchiveType.FIELD_DATA, 153);
		forget(Workspace.ArchiveType.AREA_DATA, 21);
		open(15);
		paintSand();
		//a raised plateau, so the build really has cliff faces to generate
		for (int y = 16; y < 24; y++) {
			for (int x = 16; x < 24; x++) {
				height[y][x] = 1;
			}
		}
		Exception stop = apply(15, new ArrayList<TilePainterForm.Placed>());
		for (int[] row : height) {
			Arrays.fill(row, 0);
		}
		check(stop == null, "a map with a slope applies (stopped by: " + stop + ")");
		File area = Workspace.getWorkspaceFile(Workspace.ArchiveType.AREA_DATA, 21);
		check(!needed.isEmpty() && areaTextures(Files.readAllBytes(area.toPath())).containsAll(needed),
				"and its area now holds the cliff texture(s) " + needed);
	}

	/**
	 * 358 of the game's 2,274 texture names carry different pixels in different
	 * areas, and the import treated presence as a matter of NAME alone: a brush
	 * or a building whose donor texture shares a name with one the target area
	 * already holds was quietly drawn in the target's version. PATH's
	 * chip_soil_a has four versions and 22 areas hold one that differs from the
	 * palette's donor - mean RGB 227,202,118 against 208,174,99, a visibly
	 * different soil. The paint came out a different shade from the swatch and
	 * from the same brush on the next map, and the carry said "(textures
	 * already present)". Asking for a name the target holds differently is now
	 * refused, and a carry that meets one says so in the Apply dialog.
	 */
	static void sameNameDifferentPixelsIsNotSilent() throws Exception {
		String name = null;
		int holder = -1, other = -1;
		java.util.Map<String, int[]> first = new java.util.LinkedHashMap<>(); //name -> {area, contentKey}
		for (int area = 0; area < ad.length && name == null; area++) {
			byte[] c = ad.getDecompressedEntry(area);
			if (c == null || c.length < 8 || c[0] != 'A' || c[1] != 'D') {
				continue;
			}
			for (int sub : new int[]{11, 1}) {
				byte[] pk = PropDatabase.getSubfile(c, sub);
				if (pk == null || !BchTexturePack.isTexturePack(pk)) {
					continue;
				}
				for (BchTexturePack.Texture t : BchTexturePack.parse(pk)) {
					int key = Arrays.hashCode(t.data) * 31 + t.format * 7 + t.dimParam;
					int[] seen = first.get(t.name);
					if (seen == null) {
						first.put(t.name, new int[]{area, key});
					} else if (seen[1] != key && seen[0] != area && privateAreaZone(seen[0]) >= 0) {
						name = t.name;
						holder = seen[0];
						other = area;
						break;
					}
				}
			}
		}
		check(name != null, "the dump has a same-name different-pixel texture (\"" + name
				+ "\" in areas " + holder + " and " + other + ")");
		if (name == null) {
			return;
		}
		List<String> one = new ArrayList<>();
		one.add(name);
		Exception stop = null;
		try {
			BchTexturePack.importTextures(areaPack(holder), areaPack(other), one);
		} catch (Exception ex) {
			stop = ex;
		}
		check(stop != null && stop.getMessage() != null && stop.getMessage().contains(name),
				"importing a name the target already holds with different pixels is refused: " + stop);
		int zone = privateAreaZone(holder);
		BchTexturePack.Carry carry = BchTexturePack.planCarry(other, holder, one,
				areaPack(holder), PropDatabase.getSubfile(ad.getDecompressedEntry(holder), 1), zone);
		check(carry.note.contains(name) && carry.note.contains(String.valueOf(other)),
				"a carry that meets one reports it, naming the texture and both areas: " + carry.note.trim());
	}

	/** A zone whose area is exactly this one and nobody else's, or -1. */
	static int privateAreaZone(int area) throws Exception {
		for (int z = 0; z < zo.length - 2; z++) {
			if (ctrmap.AreaForker.currentArea(z) == area && BchTexturePack.zonesUsingArea(area, z) == null) {
				return z;
			}
		}
		return -1;
	}

	/** An area's world texture pack, or its prop pack when it has no world one. */
	static byte[] areaPack(int area) {
		byte[] c = ad.getDecompressedEntry(area);
		byte[] p11 = PropDatabase.getSubfile(c, 11);
		return BchTexturePack.isTexturePack(p11) ? p11 : PropDatabase.getSubfile(c, 1);
	}

	/** What assertNotShared does to the zone's area, or null when it allows it. */
	static Exception refusal(int zoneIndex) throws Exception {
		try {
			BchTexturePack.assertNotShared(ctrmap.AreaForker.currentArea(zoneIndex), zoneIndex);
			return null;
		} catch (Exception ex) {
			return ex;
		}
	}

	/** Production files calling the pack-level import instead of importIntoArea. */
	static void rawImports(File dir, List<String> hits) throws Exception {
		File[] files = dir.listFiles();
		if (files == null) {
			return;
		}
		for (File f : files) {
			if (f.isDirectory()) {
				if (!f.getName().equals("tests")) {
					rawImports(f, hits);
				}
				continue;
			}
			if (!f.getName().endsWith(".java") || f.getName().equals("BchTexturePack.java")) {
				continue;
			}
			String text = SourceSeamTest.stripComments(new String(
					Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
			for (String call : new String[]{"importTextures(", "importTexture("}) {
				int at = text.indexOf(call);
				if (at >= 0) {
					hits.add(f.getName() + ":" + text.substring(0, at).split("\n", -1).length);
				}
			}
		}
	}

	/**
	 * 44% of retail tiles carry no collision sample under their centre, and a
	 * painted one takes its ground from the nearest walkable neighbour rather
	 * than sinking to the region's floor. Apply never said how many did: the
	 * seed-time label counts them when the painter opens, but the "Painted map
	 * applied" dialog - the one account of what the edit actually did - was
	 * silent, so a patch standing on ground borrowed from beside it looked
	 * exactly like one the map itself gave a height to. Zone 17 (area 22, its
	 * own; region 154) has 39 such tiles under the guards' 12x12 sand patch.
	 */
	static void applyCountsTilesThatTookANeighboursGround() throws Exception {
		forget(Workspace.ArchiveType.FIELD_DATA, 154);
		forget(Workspace.ArchiveType.AREA_DATA, 22);
		open(17);
		paintSand();
		GR region = new GR(Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, 154));
		int borrowed = PaintedRegionBuilder.borrowedGroundTiles(region.getFile(2), region.getFile(0), touched);
		check(borrowed > 0, "fixture: the painted tiles include " + borrowed + " with no ground of their own");
		Exception stop = apply(17, new ArrayList<TilePainterForm.Placed>());
		check(stop == null, "the Apply went through (stopped by: " + stop + ")");
		check(report != null && report.contains(borrowed + " painted tile(s) had no ground under them"),
				"and says the " + borrowed + " tiles took a neighbour's ground: " + report);
	}

	/**
	 * The last thing Apply does is write the regions, and it checks that each
	 * one landed. A workspace file the editor cannot open for writing - the
	 * region is read-only, or something else has it open - makes storeFile
	 * return false, and without the refusal that follows, Apply carries on:
	 * the area is committed with this map's new textures, "Painted map applied"
	 * is reported, and the map itself is still the old one. That is the exact
	 * shape of finding 0, arrived at from the other end.
	 */
	static void aRegionThatCannotBeWrittenStopsTheApply() throws Exception {
		forget(Workspace.ArchiveType.FIELD_DATA, 153);
		forget(Workspace.ArchiveType.AREA_DATA, 21);
		open(15);
		paintSand();
		//getWorkspaceFile extracts it from the archive, so there is a file to lock
		File region = Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, 153);
		byte[] was = Files.readAllBytes(region.toPath());
		check(region.setWritable(false) && !region.canWrite(), "fixture: region 153's workspace file is read-only");
		Exception stop;
		try {
			stop = apply(15, new ArrayList<TilePainterForm.Placed>());
		} finally {
			region.setWritable(true);
		}
		check(stop != null && String.valueOf(stop.getMessage()).contains("could not write region 153"),
				"a region the workspace cannot write stops the Apply and names it (stopped by: " + stop + ")");
		check(Arrays.equals(Files.readAllBytes(region.toPath()), was), "the region is exactly as it was");
		check(pristine(Workspace.ArchiveType.AREA_DATA, ad, 21),
				"and its area was not committed either - no textures for a map that was never written");
	}

	/**
	 * The other half of the "does this Apply have to write the area?" question,
	 * which is what decides whether the user is asked to fork a shared one: a
	 * placed building whose door prop the area has not registered.
	 *
	 * <p>Driven directly, because the end-to-end path cannot separate it. The
	 * texture half is true for practically every area in the game - the cliff
	 * faces every painted slope generates come from area 69 and only zone 125
	 * sits on that area, privately - so a shared area always needs a texture
	 * write too, and an area that needs none is by definition one nobody
	 * shares, where the question is not asked either way. The prop half is
	 * still the whole reason a door was ever registered behind fifteen other
	 * maps' backs, so it is asserted where it can be seen: with every texture
	 * already in place (the state a re-apply is in - see
	 * {@link #retryStillAsksForTheTextures()}), the answer must turn on the
	 * door prop alone.
	 */
	static void aDoorPropTheAreaHasNotRegisteredIsAnAreaWrite() throws Exception {
		PropDatabase db = PropDatabase.get();
		BuildingCatalog.Entry door = null;
		int model = -1;
		for (BuildingCatalog.Entry e : BuildingCatalog.entries()) {
			if (e.doorProp == null || "-".equals(e.doorProp)) {
				continue;
			}
			for (PropDatabase.PropModel m : db.models) {
				if (e.doorProp.equals(m.name) && door == null) {
					door = e;
					model = m.modelIndex;
				}
			}
		}
		check(door != null, "fixture: a catalogue building whose door prop is in the dump ("
				+ (door == null ? "none" : door.name + ", prop " + door.doorProp + " = model " + model) + ")");
		if (door == null) {
			return;
		}
		int registered = -1, unregistered = -1;
		for (int area = 0; area < ad.length && (registered < 0 || unregistered < 0); area++) {
			byte[] c = ad.getDecompressedEntry(area);
			if (c == null || c.length < 8 || c[0] != 'A' || c[1] != 'D') {
				continue;
			}
			boolean has = registersProp(area, model);
			registered = has && registered < 0 ? area : registered;
			unregistered = !has && unregistered < 0 ? area : unregistered;
		}
		check(registered >= 0 && unregistered >= 0, "fixture: area " + registered + " registers model "
				+ model + " and area " + unregistered + " does not");
		if (registered < 0 || unregistered < 0) {
			return;
		}
		//nothing left to carry: every texture this paint needs is already there
		java.util.Map<Integer, Set<String>> carried = new java.util.LinkedHashMap<>();
		List<TilePainterForm.Placed> none = new ArrayList<>();
		List<TilePainterForm.Placed> one = new ArrayList<>();
		one.add(new TilePainterForm.Placed(door, 20, 20));
		check(!needsAreaWrite(unregistered, none, carried),
				"an Apply that places nothing and carries nothing does not have to write the area");
		check(needsAreaWrite(unregistered, one, carried),
				"placing \"" + door.name + "\" on area " + unregistered + ", which has not registered its door,"
				+ " does have to write the area (so a shared one is offered the fork first)");
		check(!needsAreaWrite(registered, one, carried),
				"and placing it on area " + registered + ", which already registers that door, does not");
	}

	/** needsAreaWrite, reporting a throw as the failed check it is. */
	static boolean needsAreaWrite(int area, List<TilePainterForm.Placed> placed,
			java.util.Map<Integer, Set<String>> texNeeds) {
		try {
			return TilePainterForm.needsAreaWrite(area, placed, texNeeds);
		} catch (Exception ex) {
			check(false, "needsAreaWrite(" + area + ", " + placed.size() + " placed) threw " + ex);
			return false;
		}
	}

	/** True when an area's prop registry already carries a model index. */
	static boolean registersProp(int area, int model) throws Exception {
		ctrmap.formats.propdata.ADPropRegistry reg = new ctrmap.formats.propdata.ADPropRegistry(
				new ctrmap.formats.containers.AD(Workspace.getWorkspaceFile(
						Workspace.ArchiveType.AREA_DATA, area)), null, false);
		for (ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry e : reg.entries.values()) {
			if (e.model == model) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Zone 74's area is zones 72 and 73's too. Apply asks before it writes one,
	 * and Cancel means nothing happens - the answer the dialog gives when
	 * nobody is there to click, which is also what a headless battery gets.
	 * With the refusal that follows the cancel deleted, the Apply carried on
	 * into area -1 and the user was told the map had been painted.
	 */
	static void cancellingTheSharedAreaQuestionWritesNothing() throws Exception {
		open(74);
		paintSand();
		List<String> before = new ArrayList<>(Workspace.persist_paths);
		List<String> said = ctrmap.Ui.record("Cancel");
		Exception stop;
		try {
			stop = apply(74, new ArrayList<TilePainterForm.Placed>());
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said(said, "SHARES its area"), "painting a zone whose area other zones use asks first: " + said);
		check(stop != null && String.valueOf(stop.getMessage()).contains("Apply cancelled - nothing was changed."),
				"cancelling that question stops the Apply and says so (stopped by: " + stop + ")");
		check(newlyPersisted(before).isEmpty(), "nothing was written by the cancelled Apply: " + newlyPersisted(before));
		check(pristine(Workspace.ArchiveType.FIELD_DATA, gr, 272), "region 272 is byte-identical to the archive");
		check(pristine(Workspace.ArchiveType.AREA_DATA, ad, 43), "area 43 is byte-identical to the archive");
	}

	/** The other answer: the fork is taken, and the paint lands in the copy. */
	static void acceptingTheForkPaintsTheZone() throws Exception {
		open(74);
		paintSand();
		List<String> said = ctrmap.Ui.record("Give this zone its own area");
		Exception stop;
		try {
			stop = apply(74, new ArrayList<TilePainterForm.Placed>());
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said(said, "SHARES its area"), "the same question is asked: " + said);
		check(stop == null, "accepting the fork applies the paint (stopped by: " + stop + ")");
		check(pristine(Workspace.ArchiveType.AREA_DATA, ad, 43),
				"and zones 72 and 73's area 43 is still byte-identical to the archive");
		int now = ctrmap.AreaForker.currentArea(74);
		check(now != 43, "zone 74 now has an area of its own (" + now + ")");
		check(sandTriangles(new GR(Workspace.getWorkspaceFile(
				Workspace.ArchiveType.FIELD_DATA, 272)).getFile(1)) > 0, "and its map carries the painted sand floor");
	}

	/** True when one of the messages the program gave contains the text. */
	static boolean said(List<String> said, String text) {
		for (String s : said) {
			if (s.contains(text)) {
				return true;
			}
		}
		return false;
	}

	//--- fixtures -------------------------------------------------------------

	static void openWorkspace(File dump) throws Exception {
		Workspace.game = Workspace.GameType.ORAS;
		Workspace.GAMEDIR_PATH = dump.getAbsolutePath();
		File ws = Scratch.dir("ctrmap_paint_apply_guards");
		Workspace.WORKSPACE_PATH = ws.getAbsolutePath();
		ctrmap.Utils.mkDirsIfNotContains(ws, Workspace.WORKSPACE_SUBDIRS);
		Workspace.temp = new File(ws, "temp");
		Workspace.persist_paths.clear();
		//the brush donors are cut through the workspace's pristine snapshot;
		//the dump IS pristine, so link it into place (copy when linking fails)
		File snap = new File(Workspace.originalSnapshotDir().getAbsolutePath()
				+ Workspace.getArchivePath(Workspace.ArchiveType.FIELD_DATA, Workspace.game));
		if (!snap.isFile()) {
			snap.getParentFile().mkdirs();
			try {
				Files.createLink(snap.toPath(), new File(dump, "a/0/3/9").toPath());
			} catch (Exception cannotLink) {
				Files.copy(new File(dump, "a/0/3/9").toPath(), snap.toPath());
			}
		}
		ad = Workspace.ad = archive(dump, Workspace.ArchiveType.AREA_DATA);
		gr = Workspace.gr = archive(dump, Workspace.ArchiveType.FIELD_DATA);
		zo = Workspace.zo = archive(dump, Workspace.ArchiveType.ZONE_DATA);
		Workspace.mm = archive(dump, Workspace.ArchiveType.MAP_MATRIX);
		Workspace.bm = archive(dump, Workspace.ArchiveType.BUILDING_MODELS);
		Workspace.npcreg = archive(dump, Workspace.ArchiveType.NPC_REGISTRIES);
		//the prop database builds only behind a validated workspace, and a
		//validated workspace packs after Apply (a Swing worker) - build it
		//once, then leave valid off so a successful Apply stops at the pack
		Workspace.valid = true;
		if (PropDatabase.get() == null) {
			throw new IllegalStateException("prop database did not build");
		}
		Workspace.valid = false;
		CtrmapMainframe.mZonePnl = new ZoneLoadingPanel();
		for (int[] row : ramp) {
			Arrays.fill(row, PaintedRegionBuilder.NO_RAMP);
		}
	}

	static GARC archive(File dump, Workspace.ArchiveType type) throws Exception {
		return new GARC(new File(dump.getAbsolutePath() + Workspace.getArchivePath(type, Workspace.game)));
	}

	/** Loads a zone into the panel the painter reads, as the editor would. */
	static void open(int zoneIndex) throws Exception {
		CtrmapMainframe.mZonePnl.zone = new Zone(new ZO(Workspace.getWorkspaceFile(
				Workspace.ArchiveType.ZONE_DATA, zoneIndex)), Workspace.game);
		CtrmapMainframe.mZonePnl.zoneIndex = zoneIndex;
	}

	/** A 12x12 sand patch in the middle of an otherwise grass, untouched map. */
	static void paintSand() {
		for (int y = 0; y < DIM; y++) {
			for (int x = 0; x < DIM; x++) {
				boolean in = x >= 14 && x < 26 && y >= 14 && y < 26;
				grid[y][x] = in ? TilePalette.SAND : TilePalette.GRASS;
				touched[y][x] = in;
			}
		}
	}

	/** One click of Apply: the exception that stopped it, or null. */
	static Exception apply(int zoneIndex, List<TilePainterForm.Placed> placed) {
		try {
			report = TilePainterForm.applyToZone(zoneIndex, grid, height, ramp, TerrainLighting.daytime(), false, placed, touched);
			return null;
		} catch (Exception ex) {
			report = null;
			return ex;
		}
	}

	static List<String> newlyPersisted(List<String> before) {
		List<String> now = new ArrayList<>(Workspace.persist_paths);
		now.removeAll(before);
		return now;
	}

	/** Drops the workspace's copy of an entry so the next read is the archive's. */
	static void forget(Workspace.ArchiveType type, int index) {
		File f = new File(Workspace.getExtractionDirectory(type), String.valueOf(index));
		Workspace.persist_paths.remove(f.getAbsolutePath());
		f.delete();
	}

	/** True when the workspace holds no copy of the entry, or an identical one. */
	static boolean pristine(Workspace.ArchiveType type, GARC garc, int index) throws Exception {
		File f = new File(Workspace.getExtractionDirectory(type), String.valueOf(index));
		return !f.exists() || Arrays.equals(Files.readAllBytes(f.toPath()), garc.getDecompressedEntry(index));
	}

	/** Triangles drawn by the SAND brush's imported material, 0 when it has none. */
	static int sandTriangles(byte[] model) {
		BchMapModel m = new BchMapModel(model);
		String inject = TerrainCatalog.donors().get(TilePalette.SAND).injectName;
		for (int i = 0; i < m.meshCount; i++) {
			if (inject.equals(m.getMaterialName(m.getMeshMaterialIndex(i)))) {
				return m.getTriangles(i).length / 3;
			}
		}
		return 0;
	}

	/** Names in an area container's texture packs (files 11 and 1). */
	static Set<String> areaTextures(byte[] container) {
		Set<String> names = new TreeSet<>();
		for (int sub : new int[]{11, 1}) {
			byte[] p = PropDatabase.getSubfile(container, sub);
			if (p != null && BchTexturePack.isTexturePack(p)) {
				for (BchTexturePack.Texture t : BchTexturePack.parse(p)) {
					names.add(t.name);
				}
			}
		}
		return names;
	}

	/** The first retail area whose packs hold none of the names. */
	static int areaLacking(List<String> names) {
		for (int area = 0; area < ad.length; area++) {
			byte[] c = ad.getDecompressedEntry(area);
			if (c != null && c.length > 8 && c[0] == 'A' && c[1] == 'D') {
				Set<String> have = areaTextures(c);
				boolean any = false;
				for (String n : names) {
					any |= have.contains(n);
				}
				if (!any) {
					return area;
				}
			}
		}
		throw new IllegalStateException("every area holds " + names);
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

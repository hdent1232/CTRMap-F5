package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.formats.h3d.GeoBoxOps;
import ctrmap.formats.h3d.MapPrefab;
import ctrmap.formats.h3d.RegionFactory;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
import ctrmap.formats.tilemap.Tilemap;
import ctrmap.humaninterface.BuildingPaletteDialog;
import ctrmap.humaninterface.TilePainterForm;
import ctrmap.tools.BuildingHarvester;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The ways placing a building lied about what it did. Every one of these was a
 * live defect on the Map Builder's palette path, and every one ended in
 * "Painted map applied" with nothing else said.
 *
 * <ol>
 * <li>A building that lost pieces was written as a fragment. Region 490 is
 *     skinned throughout, so of "Battle Resort structure 32" only the three
 *     pieces whose material the target already carried could land: a few
 *     triangles of sea foam, plus the full 12x13 impassable footprint - an
 *     invisible wall. Placement now refuses the whole building and says how much
 *     of it could not be placed.</li>
 * <li>The donor's movement tuples were copied over the user's paint wholesale.
 *     "Route 110 fence 2" turned 366 painted path tiles into surf water; a
 *     Rustboro tree planted two jump-down ledges. Only the donor's solid tiles
 *     (and the door tile of a wired building) may replace the paint now, and
 *     Apply reports what it kept. The first fix read the 0xD4 tuple as a door
 *     and kept it as paint: every retail 0xD4 tile is an impassable piece of
 *     furniture (Littleroot's bookshelf row), so a placed room had 16 shelves
 *     the player walked through, and the dialog called them doors.</li>
 * <li>Collision that crossed the cut box was dropped, so bridges and stairs
 *     arrived with no walkable surface ("Shoal Cave bridge 6": 0 of 20 crossing
 *     triangles kept) and the player walked through the deck at ground height.
 *     Crossing triangles are clipped to the box instead.</li>
 * <li>baseY was the lowest ground under the box rather than the structure's own
 *     footing. A Battle Resort lamp standing on a cliff at 153 carried baseY 0
 *     and stamped 153 units into the air; Fortree's treehouses did the same.</li>
 * <li>Satellite absorption grew a component's box without re-running the
 *     terrain guard, so a 22x17 slab of Cycling Road (sea plane, deck and all)
 *     was offered as a fence. The box was fixed; what rides inside a box was
 *     not: "Littleroot Town lamp" is a furnished room - four floors, a baked
 *     shadow, 27 textures - and "Route 110 fence 2" brings a sea plane and a
 *     road deck 240 units up, and the palette's manifest gave numbers only, so
 *     the user could not tell a scene from a lamp except by inference, and
 *     Apply repeated none of it.</li>
 * <li>"Copy selection as prefab" discarded every face crossing the selection
 *     edge without counting it: Route 101's 10..19 box loses 135 faces that
 *     lie across it and two materials entirely, and the dialog listed only what
 *     survived.
 *     The first count saw only faces with a corner inside the box, so a ground
 *     quad lying across it with every corner outside was dropped uncounted and
 *     its material vanished unnamed (region 7's chip_kusa_a); and a selection
 *     with no face fully inside - most single-tile selections - returned
 *     nothing at all, and the status bar called it empty.</li>
 * </ol>
 *
 * Usage: java ctrmap.tests.PlacementGuardsTest &lt;path-to-a039-garc&gt; [sampleStep]
 */
public class PlacementGuardsTest {

	static final int DIM = PaintedRegionBuilder.DIM;

	static int fails = 0;
	static GARC garc;
	static File scratch;
	static byte[] tileset;   //region 1's model - the painter's tileset donor
	static final Map<Integer, GR> regions = new HashMap<>();
	static final Map<Integer, List<BuildingHarvester.Comp>> detected = new HashMap<>();

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs/a/0/3/9");
		int step = args.length > 1 ? Integer.parseInt(args[1]) : 20;
		if (!garcFile.isFile()) {
			System.out.println("  skip: no FieldData GARC at " + garcFile);
			System.out.println("ALL PASS");
			return;
		}
		//the palette cuts its donors from the workspace's pristine snapshot -
		//point a throwaway workspace's snapshot at the dump
		Workspace.game = Workspace.GameType.ORAS;
		Workspace.GAMEDIR_PATH = garcFile.getParentFile().getParentFile().getParentFile().getParentFile().getAbsolutePath();
		Workspace.WORKSPACE_PATH = Scratch.dir("ctrmap_placement_guards").getAbsolutePath();
		Workspace.temp = new File(Workspace.WORKSPACE_PATH, "temp");
		Workspace.temp.mkdirs();
		File snap = new File(Workspace.originalSnapshotDir().getAbsolutePath()
				+ Workspace.getArchivePath(Workspace.ArchiveType.FIELD_DATA, Workspace.game));
		if (!snap.isFile()) {
			snap.getParentFile().mkdirs();
			try {
				java.nio.file.Files.createLink(snap.toPath(), garcFile.toPath());
			} catch (Exception cannotLink) {
				java.nio.file.Files.copy(garcFile.toPath(), snap.toPath());
			}
		}
		garc = new GARC(garcFile);
		scratch = new File(Workspace.temp, "guards");
		scratch.mkdirs();
		tileset = region(1).getFile(1);

		partialStampRefused();
		paintKeptUnderDonorBehaviour();
		collisionCrossingTheBoxIsKept();
		facesCrossingTheBoxAreCounted();
		cutLossSurvivesSaveAndReload();
		passengersAreNamed();
		baseYIsTheFooting(step);
		catalogueBoxesAreAssets();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A building that cannot be placed whole is refused, not written in part. */
	static void partialStampRefused() throws Exception {
		//region 490 is skinned throughout: on painted path only the three pieces
		//whose material the tileset already carries can land, the other 33 cannot
		BuildingCatalog.Entry e = entry("skinned donor", 490, 101, 8, 9, 19, 21, 0);
		RegionFactory.BlankContent bc = paintedPath();
		byte[] model = bc.model, coll = bc.collision, tiles = bc.tilemap.clone();
		try {
			TilePainterForm.stampPlaced(bc, at(e, 5, 5), new int[DIM][DIM], null);
			check(false, "a building that lost 33 of 36 pieces was applied as if it were whole");
		} catch (IllegalStateException ex) {
			check(ex.getMessage().contains("of 36"), "the partial stamp is refused and says how much was lost: " + ex.getMessage());
		}
		check(bc.model == model && bc.collision == coll && Arrays.equals(bc.tilemap, tiles),
				"a refused building leaves the map untouched");
	}

	/** Only the donor's solid tiles may replace the user's paint; everything else is reported as kept. */
	static void paintKeptUnderDonorBehaviour() throws Exception {
		//a Cycling Road slab (366 surf tiles in its footprint) and a Littleroot
		//interior (16 bookshelf tiles on the 0xD4 object code, 14 more on its
		//siblings, 2 walkable) over a map painted entirely as path
		BuildingCatalog.Entry[] donors = {
			entry("cycling road slab", 186, 28, 0, 1, 21, 17, -9),
			entry("littleroot interior", 510, 114, 6, 6, 22, 22, 0)
		};
		for (BuildingCatalog.Entry e : donors) {
			RegionFactory.BlankContent bc = paintedPath();
			byte[] before = bc.tilemap.clone();
			MapPrefab p = BuildingPaletteDialog.cachedPrefab(e);
			String note = TilePainterForm.stampPlaced(bc, at(e, 5, 5), new int[DIM][DIM], null);
			int walls = 0, objects = 0, solidLost = 0;
			Map<String, Integer> leaked = new TreeMap<>();
			for (int y = 0; y < DIM; y++) {
				for (int x = 0; x < DIM; x++) {
					if (!Arrays.equals(tile(before, x, y), tile(bc.tilemap, x, y))) {
						String b = behaviour(tile(bc.tilemap, x, y));
						if ("wall".equals(b)) {
							walls++;
						} else if ("object".equals(b)) {
							objects++;
						} else {
							leaked.merge(b, 1, Integer::sum);
						}
					}
				}
			}
			//what is solid in the donor - plain walls and its furniture codes
			//alike - must be solid on the map; the room's prefab carries only two
			//collision triangles, so nothing else stops the player
			for (int y = 0; y < p.tilesH; y++) {
				for (int x = 0; x < p.tilesW; x++) {
					String donor = behaviour(p.tiles[x][y]);
					if (("wall".equals(donor) || "object".equals(donor)) && (tile(bc.tilemap, 5 + x, 5 + y)[0] & 1) == 0) {
						solidLost++;
					}
				}
			}
			check(walls > 0, e.name + ": the footprint's walls block the paint (" + walls + " tiles)");
			check(leaked.isEmpty(), e.name + ": no painted tile changed behaviour under the footprint " + leaked);
			check(solidLost == 0, e.name + ": every tile solid in the donor is solid on the map (" + solidLost + " came out walkable)");
			check(note.contains("kept as painted"), e.name + ": Apply reports the donor tiles it withheld: " + note.trim());
			if (objects > 0) {
				check(note.contains(objects + " of them"), e.name + ": Apply says how many blocked tiles carry the donor's object codes ("
						+ objects + "): " + note.trim());
			}
		}
		//the Geometry tool's explicit "also update movement tiles" copies every
		//tuple; its status line used to say "+N tiles" and nothing about what
		//kinds - the same silence, one tool over
		MapPrefab room = BuildingPaletteDialog.cachedPrefab(donors[1]);
		Map<String, Integer> kinds = new TreeMap<>();
		for (int y = 0; y < room.tilesH; y++) {
			for (int x = 0; x < room.tilesW; x++) {
				kinds.merge(behaviour(room.tiles[x][y]), 1, Integer::sum);
			}
		}
		MapPrefab.StampResult r = new MapPrefab.StampResult();
		room.stampTiles(r, new Tilemap(region(510)), 5, 5);
		check(r.tilesStamped == room.tilesW * room.tilesH && kinds.equals(r.tilesWritten),
				"a verbatim tile copy accounts for every tuple by behaviour: wrote " + r.tilesWritten + ", the donor holds " + kinds);
	}

	/** Collision crossing the box is clipped to it, keeps the donor's heights, and reaches the map. */
	static void collisionCrossingTheBoxIsKept() throws Exception {
		//Rustboro Gym's box: ten collision triangles overlap it, none lies fully inside
		GR gr = region(22);
		GeoBoxOps.Box box = GeoBoxOps.Box.ofTiles(14, 23, 22, 28);
		List<float[]> crossing = new ArrayList<>();
		for (int cs : MapPrefab.collSubfiles(gr)) {
			byte[] cb = cs < gr.len ? gr.getFile(cs) : null;
			if (cb == null || !GfColl.isColl(cb)) {
				continue;
			}
			for (float[] t : new GfColl(cb).uniqueTris) {
				float mnX = Math.min(t[0], Math.min(t[3], t[6])), mxX = Math.max(t[0], Math.max(t[3], t[6]));
				float mnZ = Math.min(t[2], Math.min(t[5], t[8])), mxZ = Math.max(t[2], Math.max(t[5], t[8]));
				if (mxX > box.minX && mnX < box.maxX && mxZ > box.minZ && mnZ < box.maxZ) {
					crossing.add(t);
				}
			}
		}
		MapPrefab p = MapPrefab.extract(gr, 14, 23, 22, 28, "gym");
		check(!crossing.isEmpty() && p != null, "fixture: the Gym box is crossed by collision (" + crossing.size() + " triangles)");
		check(p != null && !p.collTris.isEmpty(), "collision crossing the box is clipped in, not dropped: "
				+ (p == null ? 0 : p.collTris.size()) + " triangles carried from " + crossing.size() + " crossing");
		int outside = 0, offSurface = 0;
		if (p != null) {
			for (float[] t : p.collTris) {
				for (int v = 0; v < 3; v++) {
					float x = t[v * 3] + box.minX, y = t[v * 3 + 1], z = t[v * 3 + 2] + box.minZ;
					if (x < box.minX - 0.01f || x > box.maxX + 0.01f || z < box.minZ - 0.01f || z > box.maxZ + 0.01f) {
						outside++;
					}
					if (!onASurface(x, y, z, crossing)) {
						offSurface++;
					}
				}
			}
		}
		check(outside == 0, "clipped collision stays inside the box (" + outside + " corners outside)");
		check(offSurface == 0, "clipped collision keeps the donor's heights: every corner lies on an original triangle's plane ("
				+ offSurface + " do not)");
		//end to end: a bridge and a flight of stairs add walkable surface through the placement path
		BuildingCatalog.Entry[] raised = {
			entry("Shoal Cave bridge", 377, 72, 6, 23, 16, 24, -52),
			entry("Sealed Chamber stairs", 415, 78, 6, 19, 11, 22, 0)
		};
		for (BuildingCatalog.Entry e : raised) {
			RegionFactory.BlankContent bc = paintedPath();
			int before = new GfColl(bc.collision).uniqueTris.size();
			String note = TilePainterForm.stampPlaced(bc, at(e, 5, 5), new int[DIM][DIM], null);
			int added = new GfColl(bc.collision).uniqueTris.size() - before;
			check(added > 0, e.name + ": placement adds walkable collision (+" + added + ")");
			check(note.contains(added + " collision"), e.name + ": Apply reports the collision it added: " + note.trim());
		}
	}

	/** True when (x,y,z) lies on the plane of one of the triangles. */
	static boolean onASurface(float x, float y, float z, List<float[]> tris) {
		for (float[] t : tris) {
			double ux = t[3] - t[0], uy = t[4] - t[1], uz = t[5] - t[2];
			double vx = t[6] - t[0], vy = t[7] - t[1], vz = t[8] - t[2];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len < 1e-6) {
				continue;
			}
			double d = (nx * (x - t[0]) + ny * (y - t[1]) + nz * (z - t[2])) / len;
			if (Math.abs(d) < 0.05) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A copy counts every face it left out and names the materials that
	 * vanished with them. A face is lost when its footprint overlaps the box
	 * and it was not taken - whether or not a corner of it lies inside.
	 */
	static void facesCrossingTheBoxAreCounted() throws Exception {
		GR gr = region(1);
		Map<String, int[]> perMat = cutTally(gr, GeoBoxOps.Box.ofTiles(10, 10, 19, 19));
		int straddling = 0;
		TreeSet<String> lost = new TreeSet<>();
		for (Map.Entry<String, int[]> en : perMat.entrySet()) {
			straddling += en.getValue()[1];
			if (en.getValue()[0] == 0 && en.getValue()[1] > 0) {
				lost.add(en.getKey());
			}
		}
		MapPrefab p = MapPrefab.extract(gr, 10, 10, 19, 19, "route 101");
		check(straddling > 0 && !lost.isEmpty() && p != null,
				"fixture: Route 101's 10..19 box is crossed by " + straddling + " faces and loses " + lost);
		check(p != null && p.facesDropped == straddling, "the copy counts the faces it left out: reports "
				+ (p == null ? 0 : p.facesDropped) + ", the box loses " + straddling);
		check(p != null && new TreeSet<>(p.materialsLost).equals(lost), "the copy names the materials that vanished: "
				+ (p == null ? null : p.materialsLost) + " vs " + lost);
		String report = p == null ? "" : p.cutReport();
		check(report.contains(straddling + " face(s)") && report.contains(lost.toString()),
				"the copy dialog's report carries the count and the names: " + report.replace('\n', ' '));
		//a ground quad lying across the box with every corner outside: the
		//only faces of chip_kusa_a in region 7's 30,2..38,12 box are of that kind
		GR gr7 = region(7);
		Map<String, int[]> perMat7 = cutTally(gr7, GeoBoxOps.Box.ofTiles(30, 2, 38, 12));
		int[] kusa = perMat7.get("chip_kusa_a");
		MapPrefab p7 = MapPrefab.extract(gr7, 30, 2, 38, 12, "grass edge");
		check(kusa != null && kusa[0] == 0 && kusa[1] > 0, "fixture: chip_kusa_a lies across region 7's box with no corner inside ("
				+ (kusa == null ? "absent" : kusa[1] + " faces") + ")");
		check(p7 != null && p7.materialsLost.contains("chip_kusa_a"), "a material whose faces overlap the box with no corner inside is named as lost: "
				+ (p7 == null ? null : p7.materialsLost));
		//one tile of region 686: 60 faces cross it, none lies inside. The copy
		//used to answer null and the status bar said there was nothing to copy.
		int crossing = 0;
		for (int[] kd : cutTally(region(686), GeoBoxOps.Box.ofTiles(20, 20, 20, 20)).values()) {
			crossing += kd[1];
		}
		check(crossing > 0, "fixture: region 686's tile (20,20) is crossed by " + crossing + " faces");
		try {
			MapPrefab p686 = MapPrefab.extract(region(686), 20, 20, 20, 20, "one tile");
			check(false, "a selection with faces across it but none inside is refused, not answered with "
					+ (p686 == null ? "null" : p686.pieces.size() + " piece(s)"));
		} catch (IllegalStateException ex) {
			check(ex.getMessage().contains(crossing + " face(s)") && ex.getMessage().contains("none lies inside"),
					"the refusal counts the crossing faces and says why: " + ex.getMessage());
		}
	}

	/**
	 * What a cut left behind is part of the prefab, not of the moment it was
	 * made. Both counts were computed at cut time and written nowhere, so a
	 * prefab saved to a .ctrprefab file and loaded in the next session reported
	 * a clean cut - Route 101's 10..19 box loses 135 faces and two materials
	 * entirely, and the reloaded prefab said none - and the copy dialog's
	 * warning could never be shown again. The block is appended after
	 * everything an older build reads, and only when there is loss to record,
	 * so a file this build writes is byte-for-byte the file the old one wrote
	 * plus a trailer, and a file the old one wrote still loads.
	 */
	static void cutLossSurvivesSaveAndReload() throws Exception {
		GR gr = region(1);
		MapPrefab p = MapPrefab.extract(gr, 10, 10, 19, 19, "route 101");
		check(p != null && p.facesDropped > 0 && !p.materialsLost.isEmpty(),
				"fixture: the cut loses " + (p == null ? 0 : p.facesDropped) + " face(s) and the materials "
				+ (p == null ? null : p.materialsLost));
		if (p == null) {
			return;
		}
		File pf = new File(scratch, "loss.ctrprefab");
		p.save(pf);
		MapPrefab back = MapPrefab.load(pf);
		check(back.facesDropped == p.facesDropped, "the reloaded prefab still counts the faces the cut left out: "
				+ back.facesDropped + " vs " + p.facesDropped);
		check(back.materialsLost.equals(p.materialsLost), "and still names the materials that vanished: "
				+ back.materialsLost + " vs " + p.materialsLost);
		check(back.cutReport().equals(p.cutReport()),
				"so the copy dialog's warning can be shown again: " + back.cutReport().replace('\n', ' '));

		//exactly what the old writer produced: the same cut with nothing to
		//record. The loss block is the only difference between the two files.
		MapPrefab bare = MapPrefab.extract(gr, 10, 10, 19, 19, "route 101");
		bare.facesDropped = 0;
		bare.materialsLost.clear();
		File old = new File(scratch, "loss_v2.ctrprefab");
		bare.save(old);
		byte[] oldBytes = java.nio.file.Files.readAllBytes(old.toPath());
		byte[] newBytes = java.nio.file.Files.readAllBytes(pf.toPath());
		check(newBytes.length > oldBytes.length
				&& Arrays.equals(oldBytes, Arrays.copyOf(newBytes, oldBytes.length)),
				"the loss block is appended, so a build without it reads the file it always did ("
				+ oldBytes.length + " of " + newBytes.length + " bytes unchanged)");
		check(new java.io.DataInputStream(new java.io.ByteArrayInputStream(newBytes, 4, 4)).readInt() == MapPrefab.VERSION
				&& MapPrefab.VERSION == 2,
				"and the version is unchanged, so an older build does not refuse the file outright");
		MapPrefab backOld = MapPrefab.load(old);
		check(backOld.pieces.size() == p.pieces.size() && backOld.collTris.size() == p.collTris.size(),
				"an old-format prefab still loads whole: " + backOld.pieces.size() + " piece(s), "
				+ backOld.collTris.size() + " collision tri(s)");
		check(backOld.facesDropped == 0 && backOld.materialsLost.isEmpty(),
				"and reports no loss, because it records none");
	}

	/**
	 * This test's own reading of a cut: per material, {faces taken whole,
	 * faces lost}. A face is lost when its XZ footprint overlaps the box - by
	 * a real area, not a corner resting on or a hair past the box edge - and
	 * not all three corners lie inside it.
	 */
	static Map<String, int[]> cutTally(GR gr, GeoBoxOps.Box box) {
		BchMapModel m = new BchMapModel(gr.getFile(1));
		Area rect = new Area(new Rectangle2D.Float(box.minX, box.minZ, box.maxX - box.minX, box.maxZ - box.minZ));
		Map<String, int[]> perMat = new TreeMap<>();
		for (BchMapModel.MeshGeom g : m.geometry()) {
			if (!g.posOk) {
				continue;
			}
			float[][] pos = m.getVertexPositions(g.meshIndex);
			int[] tris = m.getTriangles(g.meshIndex);
			int[] kd = perMat.computeIfAbsent(m.getMaterialName(m.getMeshMaterialIndex(g.meshIndex)), k -> new int[2]);
			for (int t = 0; t + 2 < tris.length; t += 3) {
				int in = 0;
				for (int c = 0; c < 3; c++) {
					if (box.contains(pos[tris[t + c]])) {
						in++;
					}
				}
				if (in == 3) {
					kd[0]++;
					continue;
				}
				Path2D.Float tri = new Path2D.Float();
				tri.moveTo(pos[tris[t]][0], pos[tris[t]][2]);
				tri.lineTo(pos[tris[t + 1]][0], pos[tris[t + 1]][2]);
				tri.lineTo(pos[tris[t + 2]][0], pos[tris[t + 2]][2]);
				tri.closePath();
				Area overlap = new Area(tri);
				overlap.intersect(rect);
				if (areaOf(overlap) > 0.01) {
					kd[1]++;
				}
			}
		}
		return perMat;
	}

	/** The area a polygonal shape encloses (Area keeps zero-width slivers, so isEmpty is not enough). */
	static double areaOf(Area shape) {
		double area2 = 0, sx = 0, sy = 0, px = 0, py = 0;
		double[] c = new double[6];
		for (PathIterator it = shape.getPathIterator(null, 0.01); !it.isDone(); it.next()) {
			int seg = it.currentSegment(c);
			if (seg == PathIterator.SEG_MOVETO) {
				sx = px = c[0];
				sy = py = c[1];
			} else if (seg == PathIterator.SEG_LINETO) {
				area2 += px * c[1] - c[0] * py;
				px = c[0];
				py = c[1];
			} else if (seg == PathIterator.SEG_CLOSE) {
				area2 += px * sy - sx * py;
				px = sx;
				py = sy;
			}
		}
		return Math.abs(area2) / 2;
	}

	/**
	 * What rides along inside a cut is named where the user decides - the
	 * palette's manifest before placing, Apply's account after - by material
	 * class, not counted into a triangle total; and the counts the manifest
	 * does give are the prefab's own.
	 */
	static void passengersAreNamed() throws Exception {
		BuildingCatalog.Entry room = entry("Littleroot Town lamp", 510, 114, 6, 6, 22, 22, 0);
		BuildingCatalog.Entry fence = entry("Route 110 fence 2", 185, 28, 15, 11, 19, 18, -9);
		MapPrefab p = BuildingPaletteDialog.cachedPrefab(room);
		check(p != null && p.pieces.size() > 20, "fixture: the \"lamp\" is a room of " + (p == null ? 0 : p.pieces.size()) + " pieces");
		if (p == null) {
			return;
		}
		//the manifest's numbers, recounted from the pieces themselves
		int tris = 0;
		float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
		for (MapPrefab.Piece piece : p.pieces) {
			tris += piece.triangles.length / 3;
			for (int v = 0; v * piece.stride < piece.vertexBytes.length; v++) {
				int o = v * piece.stride + piece.posOffset + 4;
				float y = Float.intBitsToFloat((piece.vertexBytes[o] & 0xFF) | ((piece.vertexBytes[o + 1] & 0xFF) << 8)
						| ((piece.vertexBytes[o + 2] & 0xFF) << 16) | ((piece.vertexBytes[o + 3] & 0xFF) << 24));
				lo = Math.min(lo, y);
				hi = Math.max(hi, y);
			}
		}
		check(p.triangleCount() == tris, "the manifest's triangle count is the pieces' own: " + p.triangleCount() + " vs " + tris);
		check(p.heightSpan()[0] == lo && p.heightSpan()[1] == hi, "the manifest's height span is the pieces' own: "
				+ Arrays.toString(p.heightSpan()) + " vs " + lo + ".." + hi);
		RegionFactory.BlankContent base = paintedPath();
		String manifest = BuildingPaletteDialog.manifest(p, p.stampGeometry(base.model, 5, 5, 0), room);
		check(manifest.contains(tris + " triangles") && manifest.contains(Math.round(hi) + " above ground"),
				"the palette's manifest carries the count and the span: " + manifest);
		check(manifest.contains("rides along") && manifest.contains("shadow") && manifest.contains("floor"),
				"the palette names the room's passengers - its shadow decal and floors - by class: " + manifest);
		MapPrefab pf = BuildingPaletteDialog.cachedPrefab(fence);
		String fenceManifest = pf == null ? "" : BuildingPaletteDialog.manifest(pf, pf.stampGeometry(base.model, 5, 5, 9), fence);
		check(fenceManifest.contains("sea/water"), "the palette names the fence's sea plane: " + fenceManifest);
		//Apply's own account repeats it
		String note = TilePainterForm.stampPlaced(paintedPath(), at(room, 5, 5), new int[DIM][DIM], null);
		check(note.contains(tris + " triangles") && note.contains("rides along") && note.contains("shadow"),
				"Apply's account of a placed building repeats the count and names the passengers: " + note.trim());
		//and the confirmation before it lists the same manifest
		String summary = TilePainterForm.placedSummary(at(room, 5, 5));
		check(summary.contains(room.name) && summary.contains(tris + " triangles") && summary.contains("rides along"),
				"Apply's confirmation lists each building's manifest: " + summary);
		//the palette's checkbox: the passengers stay behind, and the account says so
		int riders = p.passengers().size();
		check(riders > 0 && p.withoutPassengers().pieces.size() == p.pieces.size() - riders,
				"leaving the passengers behind drops exactly them (" + riders + " of " + p.pieces.size() + " pieces)");
		java.util.List<TilePainterForm.Placed> alone = new ArrayList<>();
		alone.add(new TilePainterForm.Placed(room, 5, 5, false));
		String left = TilePainterForm.stampPlaced(paintedPath(), alone, new int[DIM][DIM], null);
		check(left.contains((p.pieces.size() - riders) + " piece(s)") && left.contains("left behind") && left.contains("shadow"),
				"a building placed without its passengers stamps the structure alone and says what stayed: " + left.trim());
	}

	/**
	 * An entry's baseY lies within the structure's own height - a ground level
	 * it stands on, never the bottom of a drop beside it. Stamped at
	 * dy = ground - baseY, a structure whose baseY sits more than half a step
	 * below its lowest face floats by the difference; one whose baseY is above
	 * its top is buried whole.
	 */
	static void baseYIsTheFooting(int step) throws Exception {
		//the cliff-top lamp, the treehouse sign and their kin, pinned by donor box
		//so a renamed catalogue still checks them - plus a sample of everything
		int[][] pinned = {{484, 14, 11, 22, 19}, {154, 14, 6, 28, 15}, {755, 20, 5, 24, 8}, {739, 15, 9, 23, 18}, {475, 21, 20, 36, 28}};
		List<BuildingCatalog.Entry> probe = new ArrayList<>();
		for (int[] b : pinned) {
			BuildingCatalog.Entry hit = null;
			for (BuildingCatalog.Entry e : BuildingCatalog.entries()) {
				if (e.auto && e.donorRegion == b[0] && e.tx0 == b[1] && e.ty0 == b[2] && e.tx1 == b[3] && e.ty1 == b[4]) {
					hit = e;
				}
			}
			check(hit != null, "fixture: the catalogue still offers region " + b[0] + " box " + b[1] + "," + b[2] + ".." + b[3] + "," + b[4]);
			if (hit != null) {
				probe.add(hit);
			}
		}
		int seen = 0;
		for (BuildingCatalog.Entry e : BuildingCatalog.entries()) {
			if (e.auto && (seen++ % step) == 0 && !probe.contains(e)) {
				probe.add(e);
			}
		}
		int checked = 0, off = 0, unmatched = 0;
		String worst = null;
		float worstOff = 0;
		for (BuildingCatalog.Entry e : probe) {
			BuildingHarvester.Comp c = component(e);
			if (c == null) {
				unmatched++;
				continue;
			}
			checked++;
			if (e.baseY < c.minY - 9 || e.baseY > c.maxY) {
				off++;
				float d = e.baseY < c.minY ? c.minY - e.baseY : e.baseY - c.maxY;
				if (d > worstOff) {
					worstOff = d;
					worst = "\"" + e.name + "\" baseY " + e.baseY + " against faces " + Math.round(c.minY) + ".." + Math.round(c.maxY)
							+ (e.baseY < c.minY ? " - would float " + Math.round(d) + " units above the ground" : " - would be buried");
				}
			}
		}
		check(unmatched == 0, "every probed entry's box is a component the harvester detects today (" + unmatched + " are not - stale catalogue?)");
		check(off == 0, "baseY is a level the structure stands on for all " + checked + " probed entries (" + off + " are not"
				+ (worst == null ? "" : "; worst " + worst) + ")");
	}

	/** Nothing wider than an asset is offered, and the harvester no longer grows one past the guard. */
	static void catalogueBoxesAreAssets() throws Exception {
		int wide = 0;
		String example = null;
		for (BuildingCatalog.Entry e : BuildingCatalog.entries()) {
			if (e.auto && (e.tilesW() > BuildingHarvester.TERRAIN_TILES || e.tilesH() > BuildingHarvester.TERRAIN_TILES)) {
				wide++;
				example = "\"" + e.name + "\" " + e.tilesW() + "x" + e.tilesH();
			}
		}
		check(wide == 0, "no catalogue entry is wider than an asset (" + wide + " over " + BuildingHarvester.TERRAIN_TILES
				+ " tiles" + (example == null ? "" : ", e.g. " + example) + ")");
		int grown = 0;
		for (BuildingHarvester.Comp c : components(186)) {
			if (!c.terrain && (c.tilesW() > BuildingHarvester.TERRAIN_TILES || c.tilesH() > BuildingHarvester.TERRAIN_TILES)) {
				grown++;
			}
		}
		check(grown == 0, "satellite absorption re-runs the terrain guard (Cycling Road's region grows " + grown + " oversized component(s))");
	}

	// ---- fixtures -----------------------------------------------------------

	static GR region(int id) throws Exception {
		GR gr = regions.get(id);
		if (gr == null) {
			File f = new File(scratch, "r" + id);
			try (FileOutputStream fo = new FileOutputStream(f)) {
				fo.write(garc.getDecompressedEntry(id));
			}
			gr = new GR(f);
			regions.put(id, gr);
		}
		return gr;
	}

	static List<BuildingHarvester.Comp> components(int region) throws Exception {
		List<BuildingHarvester.Comp> comps = detected.get(region);
		if (comps == null) {
			GR gr = region(region);
			comps = BuildingHarvester.detect(gr.getFile(1), gr.len > 2 ? gr.getFile(2) : null);
			detected.put(region, comps);
		}
		return comps;
	}

	/** The harvester's component whose box is the entry's, or null. */
	static BuildingHarvester.Comp component(BuildingCatalog.Entry e) throws Exception {
		for (BuildingHarvester.Comp c : components(e.donorRegion)) {
			if (!c.terrain && c.tx0 == e.tx0 && c.ty0 == e.ty0 && c.tx1 == e.tx1 && c.ty1 == e.ty1) {
				return c;
			}
		}
		return null;
	}

	static BuildingCatalog.Entry entry(String name, int region, int area, int tx0, int ty0, int tx1, int ty1, int baseY) {
		BuildingCatalog.Entry e = new BuildingCatalog.Entry();
		e.kind = "A_STRUCT";
		e.name = name;
		e.donorRegion = region;
		e.donorArea = area;
		e.tx0 = tx0;
		e.ty0 = ty0;
		e.tx1 = tx1;
		e.ty1 = ty1;
		e.baseY = baseY;
		e.auto = true;
		return e;
	}

	static List<TilePainterForm.Placed> at(BuildingCatalog.Entry e, int tx, int ty) {
		List<TilePainterForm.Placed> placed = new ArrayList<>();
		placed.add(new TilePainterForm.Placed(e, tx, ty));
		return placed;
	}

	/** A flat 40x40 map painted entirely as path - the user's own work. */
	static RegionFactory.BlankContent paintedPath() {
		TilePalette[][] grid = new TilePalette[DIM][DIM];
		for (TilePalette[] row : grid) {
			Arrays.fill(row, TilePalette.PATH);
		}
		return PaintedRegionBuilder.build(tileset, grid, new int[DIM][DIM], null, TerrainLighting.daytime(), false);
	}

	static byte[] tile(byte[] tilemap, int x, int y) {
		int o = 4 + (y * DIM + x) * 4;
		return new byte[]{tilemap[o], tilemap[o + 1], tilemap[o + 2], tilemap[o + 3]};
	}

	/** This test's own reading of a movement tuple (behaviour is byte 3; bit 0 of byte 0 blocks;
	 *  a blocked tile whose code is above 0x01 is one of the game's furniture/object tiles). */
	static String behaviour(byte[] t) {
		int b0 = t[0] & 0xFF, b2 = t[2] & 0xFF, b3 = t[3] & 0xFF;
		if (b2 == 0x18 && b3 == 0x3D) {
			return "encounter";
		}
		if (b2 == 0x1A && b3 == 0x3D) {
			return "surf";
		}
		if (b3 == 0x40) {
			return "waterfall";
		}
		if (b3 >= 0x69 && b3 <= 0x6C) {
			return "stairs";
		}
		if (b3 >= 0x72 && b3 <= 0x75) {
			return "ledge";
		}
		if ((b0 & 1) == 0) {
			return "walkable";
		}
		return b3 <= 0x01 ? "wall" : "object";
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

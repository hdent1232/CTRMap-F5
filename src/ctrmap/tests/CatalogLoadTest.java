package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.tools.BuildingHarvester;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The building catalog reads every row it can and says which it could not; its donor archive is
 * opened once while it is unchanged (F067).
 *
 * <p>WHY THIS SUITE EXISTS. One malformed number ended a catalog file where it stood, and the
 * only trace was a line on stderr, which the shipped program's user never sees - so the palette
 * could lose most of its 3,583 entries without a word. And every cut from the pristine snapshot,
 * one per placement and preview, opened the archive again from scratch.
 *
 * <p>ORDER: needs no dump. BuildingCatalogTest is the one that cuts every entry from it.
 *
 * Usage: java ctrmap.tests.CatalogLoadTest
 */
public class CatalogLoadTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		System.out.println("--- the shipped catalog reads every row whose box is a box, and names the rest (F067, F129)");
		//the inverted rows are counted here from the files themselves, not taken from the loader
		List<String> inverted = new ArrayList<>();
		int rows = 0;
		for (String res : new String[]{"ctrmap/resources/oras_buildings.tsv", "ctrmap/resources/oras_buildings_auto.tsv"}) {
			String text = new String(java.nio.file.Files.readAllBytes(new File("src/" + res).toPath()), StandardCharsets.UTF_8);
			int lineNo = 0;
			for (String line : text.split("\n", -1)) {
				lineNo++;
				String[] f = line.trim().split("\t");
				if (line.trim().isEmpty() || line.trim().startsWith("#") || f.length < 14) {
					continue;
				}
				rows++;
				if (Integer.parseInt(f[6]) < Integer.parseInt(f[4]) || Integer.parseInt(f[7]) < Integer.parseInt(f[5])) {
					inverted.add(res + " line " + lineNo);
				}
			}
		}
		List<BuildingCatalog.Entry> all = BuildingCatalog.entries();
		List<String> said = BuildingCatalog.loadProblems();
		List<String> saidWhere = new ArrayList<>();
		for (String s : said) {
			saidWhere.add(s.substring(0, s.indexOf(':')));
		}
		check(rows == 48 + 3535, "control: the two shipped files hold 3,583 rows (48 curated, 3,535 harvested): " + rows);
		check(all.size() == rows - inverted.size() && saidWhere.equals(inverted),
				"every row reads but the " + inverted.size() + " whose box ends before it starts, and those are named by"
				+ " line: " + all.size() + " read, said " + saidWhere);
		boolean boxes = true;
		for (BuildingCatalog.Entry e : all) {
			try {
				boxes &= e.tilesW() >= 1 && e.tilesH() >= 1;
			} catch (IllegalArgumentException invertedEntry) {
				boxes = false;
			}
		}
		check(boxes, "so every entry offered is at least one tile each way");

		System.out.println("--- a box is one width wherever it is measured, and never inverted (F129)");
		String box = "house\tInverted\t1\t2\t5\t3\t4\t3\t0\t-1\t-1\t-\t-1\t-1\n"
				+ "house\tFlat\t1\t2\t5\t3\t5\t3\t0\t-1\t-1\t-\t-1\t-1\n";
		List<BuildingCatalog.Entry> boxed = new ArrayList<>();
		List<String> boxProblems = new ArrayList<>();
		BuildingCatalog.parse(new ByteArrayInputStream(box.getBytes(StandardCharsets.UTF_8)), "box.tsv", true, boxed, boxProblems);
		check(boxed.size() == 1 && boxed.get(0).name.equals("Flat") && boxed.get(0).tilesW() == 1
				&& boxProblems.size() == 1 && boxProblems.get(0).startsWith("box.tsv line 1") && boxProblems.get(0).contains("Inverted"),
				"a row whose box ends before it starts is refused by its line, and a one-tile box reads: " + boxProblems);
		String refused = null;
		try {
			ctrmap.formats.h3d.MapPrefab.span(5, 4);
		} catch (IllegalArgumentException ex) {
			refused = ex.getMessage();
		}
		check(ctrmap.formats.h3d.MapPrefab.span(5, 5) == 1 && ctrmap.formats.h3d.MapPrefab.span(2, 4) == 3 && refused != null,
				"the one width counts both ends and refuses an end before the start: " + refused);
		String cut = null;
		try {
			ctrmap.formats.h3d.MapPrefab.extract(null, 5, 3, 4, 3, "inverted");
		} catch (RuntimeException ex) {
			cut = String.valueOf(ex.getMessage());
		}
		check(cut != null && cut.contains("before its start"), "and a cut of an inverted box is refused, not turned round: " + cut);
		ctrmap.tools.BuildingHarvester.Comp flat = new ctrmap.tools.BuildingHarvester.Comp();
		flat.minX = flat.maxX = -360f + 5 * 18f;   //no thickness, lying on the line between tiles 4 and 5
		flat.minZ = -360f + 3 * 18f;
		flat.maxZ = -360f + 4 * 18f;
		flat.computeTiles();
		check(flat.tx1 >= flat.tx0 && flat.tilesW() == 1, "and the harvester never writes one: a flat piece on a tile line is "
				+ flat.tx0 + ".." + flat.tx1);

		System.out.println("--- a row that does not read is said by its line, and the rows after it still read (F067)");
		String tsv = "# comment\n"
				+ "house\tA\t1\t2\t0\t0\t3\t3\t0\t-1\t-1\t-\t-1\t-1\n"
				+ "house\tB\t1\tx2\t0\t0\t3\t3\t0\t-1\t-1\t-\t-1\t-1\n"
				+ "house\tC\t1\t2\t0\n"
				+ "house\tD\t1\t2\t0\t0\t3\t3\t0\t-1\t-1\t-\t-1\t-1\tTown\tmany\n"
				+ "house\tE\t5\t6\t0\t0\t3\t3\t0\t-1\t-1\t-\t-1\t-1\n";
		List<BuildingCatalog.Entry> into = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		BuildingCatalog.parse(new ByteArrayInputStream(tsv.getBytes(StandardCharsets.UTF_8)), "test.tsv", true, into, problems);
		List<String> names = new ArrayList<>();
		for (BuildingCatalog.Entry e : into) {
			names.add(e.name);
		}
		check(names.toString().equals("[A, D, E]") && into.get(2).donorRegion == 5 && into.get(1).retailCount == 1,
				"the rows that read are kept, including those after a bad one: " + names);
		check(problems.size() == 3 && problems.get(0).startsWith("test.tsv line 3") && problems.get(0).contains("x2")
				&& problems.get(1).startsWith("test.tsv line 4") && problems.get(2).startsWith("test.tsv line 5"),
				"and each it could not read is named by its line: " + problems);

		System.out.println("--- the interior suite checks every interior the catalog links, read from it (F153)");
		java.util.TreeSet<Integer> linked = new java.util.TreeSet<>();
		for (BuildingCatalog.Entry e : all) {
			if (e.interiorZone >= 0) {
				linked.add(e.interiorZone);
			}
		}
		int[] checked = InteriorWirerTest.interiors();
		java.util.TreeSet<Integer> checkedSet = new java.util.TreeSet<>();
		for (int z : checked) {
			checkedSet.add(z);
		}
		check(linked.size() >= InteriorWirerTest.AT_LEAST && checkedSet.equals(linked) && checked.length == linked.size(),
				"InteriorWirerTest checks all " + linked.size() + " interiors the catalog links, not a copied 15: " + checked.length);

		System.out.println("--- the donor archive is opened once while it is unchanged (F067)");
		File dir = Scratch.dir("ctrmap_catalogsnap");
		File garc = new File(dir, "fielddata");
		HandedGameTest.writeGarc(garc, "region zero".getBytes(StandardCharsets.US_ASCII));
		GARC first = BuildingCatalog.snapshotHandle(garc);
		check(first == BuildingCatalog.snapshotHandle(garc), "a second cut from the same snapshot uses the handle the first opened");
		HandedGameTest.writeGarc(garc, "region zero".getBytes(StandardCharsets.US_ASCII), "region one".getBytes(StandardCharsets.US_ASCII));
		GARC again = BuildingCatalog.snapshotHandle(garc);
		check(again != first && again.length == 2, "and a snapshot rewritten since is opened anew: " + again.length + " entries");

		theTablesTheBuildShipsReadWholeOrSaySo();
		aSmallThingIsFiledByItsBox(all);
		aSearchFindsWhatAnEntryIs(all);
		thePaletteListsTheCatalogUnderItsPlaces(all);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/**
	 * The terrain donor and ground-material tables read whole or are refused by their line, and a
	 * read that fails part way is said rather than taken for the end of the file (F211).
	 *
	 * <p>Three readers swallowed what they could not read. The brush table dropped a bad row - a
	 * brush that could then be given to no map, silently - and the cliff and lava rows skipped a
	 * short one and answered "no such row". The ground-material table answered an empty set with a
	 * line on stderr, which is "no material is a sprite": the Route 102 black hole, back without a
	 * word. And all three, with the building catalog, read through a Scanner, which keeps a failed
	 * read to itself and simply runs out of lines.
	 */
	static void theTablesTheBuildShipsReadWholeOrSaySo() throws Exception {
		System.out.println("--- the tables the build ships read whole or say which line they could not (F211)");
		//the override is asked FIRST: donors() caches the first table it reads whole
		File bad = new File(Scratch.dir("ctrmap_terrain_rows"), "terrain.tsv");
		java.nio.file.Files.write(bad.toPath(), ("# header\nGRASS\t1\t2\t3\tmat\tctr_kusa\nSAND\t1\tx\t3\tmat\tctr_suna\n")
				.getBytes(StandardCharsets.UTF_8));
		System.setProperty("terrain", bad.getAbsolutePath());
		String terrainSaid = null;
		try {
			terrainSaid = "read " + ctrmap.formats.tilemap.TerrainCatalog.donors().keySet();
		} catch (IllegalStateException ex) {
			terrainSaid = ex.getMessage();
		} finally {
			System.clearProperty("terrain");
		}
		check(terrainSaid.contains("line 3") && terrainSaid.contains("SAND"),
				"a brush row that does not read refuses the table, naming its line - it is not dropped: " + terrainSaid);

		//the shipped table, counted here from the file itself
		int brushRows = 0, otherRows = 0;
		for (String line : new String(java.nio.file.Files.readAllBytes(new File("src/ctrmap/resources/oras_terrain.tsv").toPath()),
				StandardCharsets.UTF_8).split("\n")) {
			String l = line.trim();
			if (l.isEmpty() || l.startsWith("#")) {
				continue;
			}
			if (l.startsWith("CLIFF\t") || l.startsWith("LAVA_CHURN\t")) {
				otherRows++;
			} else {
				brushRows++;
			}
		}
		check(brushRows == 20 && otherRows == 1, "control: the shipped terrain table holds 20 brush rows and the CLIFF row: "
				+ brushRows + ", " + otherRows);
		check(ctrmap.formats.tilemap.TerrainCatalog.donors().size() == brushRows
				&& ctrmap.formats.tilemap.TerrainCatalog.cliffDonor() != null,
				"and every one of them reads: " + ctrmap.formats.tilemap.TerrainCatalog.donors().size() + " brushes and the cliff");

		//one table for the run (F093): the brushes, the cliff and the churn were three caches, each
		//filled by its own read when first asked, so a table swapped after the brushes were read gave
		//the map its churn from another design. The shipped table has no LAVA_CHURN row; another that
		//has one, set now, must not be the one the churn is answered from.
		File other = new File(Scratch.dir("ctrmap_terrain_rows"), "other.tsv");
		java.nio.file.Files.write(other.toPath(), ("GRASS\t1\t2\t3\tmat\tctr_kusa\nLAVA_CHURN\t7\t8\t9\tmagma\tctr_churn\n")
				.getBytes(StandardCharsets.UTF_8));
		System.setProperty("terrain", other.getAbsolutePath());
		ctrmap.formats.tilemap.TerrainCatalog.Donor churn;
		try {
			churn = ctrmap.formats.tilemap.TerrainCatalog.churnDonor();
		} finally {
			System.clearProperty("terrain");
		}
		check(churn == null, "the churn row is the table's the brushes came from, read once for the run - not another"
				+ " table's set later: " + (churn == null ? "none" : churn.injectName));

		//the ground-material table: a bad row is refused by its line
		String groundSaid;
		try {
			groundSaid = "read " + ctrmap.formats.tilemap.PaintedMaterials.readSpriteTable(new ByteArrayInputStream(
					"chip_a\t1\t2\t100\nchip_b\t1\t2\tmostly\n".getBytes(StandardCharsets.UTF_8)));
		} catch (java.io.IOException ex) {
			groundSaid = ex.getMessage();
		}
		check(groundSaid.startsWith("line 2"), "a ground-material row whose opacity is not a number is refused by its line,"
				+ " not skipped as if it were opaque: " + groundSaid);
		int sprites = 0, groundRows = 0;
		for (String line : new String(java.nio.file.Files.readAllBytes(
				new File("src/ctrmap/resources/oras_ground_materials.tsv").toPath()), StandardCharsets.UTF_8).split("\n")) {
			String[] f = line.trim().split("\t");
			if (line.trim().isEmpty() || line.trim().startsWith("#")) {
				continue;
			}
			groundRows++;
			sprites += Double.parseDouble(f[3]) < 95.0 ? 1 : 0;
		}
		check(groundRows == 788 && sprites == 150, "control: the shipped ground-material table holds 788 materials, 150 of"
				+ " them below 95% opaque: " + groundRows + ", " + sprites);
		check(ctrmap.formats.tilemap.PaintedMaterials.spriteMaterials().size() == sprites,
				"and the painter reads every one of them as a sprite: " + ctrmap.formats.tilemap.PaintedMaterials.spriteMaterials().size());

		//a read that fails part way is said, by every reader of a table
		String groundCut;
		try {
			groundCut = "read " + ctrmap.formats.tilemap.PaintedMaterials.readSpriteTable(failsAfter("chip_a\t1\t2\t40\n"));
		} catch (java.io.IOException ex) {
			groundCut = ex.getMessage();
		}
		check(groundCut.equals("the disk went away"), "a ground-material table whose read fails part way says so: " + groundCut);
		List<BuildingCatalog.Entry> cutInto = new ArrayList<>();
		List<String> cutProblems = new ArrayList<>();
		BuildingCatalog.parse(failsAfter("house\tA\t1\t2\t0\t0\t3\t3\t0\t-1\t-1\t-\t-1\t-1\n"), "cut.tsv", true, cutInto, cutProblems);
		check(cutInto.size() == 1 && cutProblems.size() == 1 && cutProblems.get(0).startsWith("cut.tsv could not be read past line 1"),
				"and a building catalog whose read fails part way keeps what it read and says where it stopped: " + cutProblems);
	}

	/**
	 * A lamp, a sign or a piece of decor whose box is a room's is filed as a structure and loses the
	 * small thing's word from its name - in the catalog the release ships, and in what the harvester
	 * writes next (F251).
	 *
	 * <p>The harvester's vote counts triangles and cannot see size: twelve triangles of lamp glass
	 * outvoting the unrecognised rest filed seven 11x9 Sootopolis City rooms as lamps, and a search for
	 * "lamp" offered rooms.
	 */
	static void aSmallThingIsFiledByItsBox(List<BuildingCatalog.Entry> all) throws Exception {
		System.out.println("--- a lamp, sign or decor the size of a room is filed and named as a structure (F251)");
		check(BuildingCatalog.kindFor("A_LAMP", 11, 9).equals(BuildingCatalog.STRUCTURE)
				&& BuildingCatalog.kindFor("A_SIGN", 8, 5).equals(BuildingCatalog.STRUCTURE)
				&& BuildingCatalog.kindFor("A_DECOR", 5, 8).equals(BuildingCatalog.STRUCTURE)
				&& BuildingCatalog.kindFor("A_SIGN", 13, 3).equals("A_SIGN") && BuildingCatalog.kindFor("A_LAMP", 2, 2).equals("A_LAMP")
				&& BuildingCatalog.kindFor("A_BUILDING", 11, 9).equals("A_BUILDING") && BuildingCatalog.kindFor("SIGN", 11, 9).equals("SIGN"),
				"the rule's known answers: a lamp, sign or decor of " + BuildingCatalog.ROOM_TILES + " tiles or more is a"
				+ " structure, of 39 it is itself, and no other kind - nor a curated one - is refiled");

		//the misfiled rows, counted here from the shipped file itself, boxes counted at both ends
		java.util.Set<String> misfiled = new java.util.HashSet<>();
		String text = new String(java.nio.file.Files.readAllBytes(new File("src/ctrmap/resources/oras_buildings_auto.tsv").toPath()),
				StandardCharsets.UTF_8);
		int carryMaterial = 0, harvested = 0;
		for (String line : text.split("\n")) {
			String[] f = line.trim().split("\t");
			if (line.trim().isEmpty() || line.trim().startsWith("#") || f.length < 14) {
				continue;
			}
			harvested++;
			carryMaterial += f.length >= 18 ? 1 : 0;
			int w = Integer.parseInt(f[6]) - Integer.parseInt(f[4]) + 1, h = Integer.parseInt(f[7]) - Integer.parseInt(f[5]) + 1;
			if ((f[0].equals("A_LAMP") || f[0].equals("A_SIGN") || f[0].equals("A_DECOR")) && w * h >= 40) {
				misfiled.add(f[2] + ":" + f[4] + "," + f[5] + "," + f[6] + "," + f[7]);
			}
		}
		//a table harvested before the rule files 15 rooms as lamps and signs; one harvested since, which
		//carries the material column, must file none - the harvester asks the rule the catalog reads by
		if (carryMaterial == 0) {
			check(misfiled.size() == 15, "control: the shipped harvested table, written before F251, files 15 room-sized"
					+ " cuts as lamps or signs: " + misfiled.size());
		} else {
			check(carryMaterial == harvested && misfiled.isEmpty(), "the shipped harvested table, written since F251, gives"
					+ " every row its material and files no room-sized cut as a lamp, sign or decor: " + carryMaterial + " of "
					+ harvested + " with a material, " + misfiled.size() + " misfiled");
		}
		int refiled = 0;
		List<String> wrong = new ArrayList<>();
		java.util.Set<String> autoNames = new java.util.HashSet<>();
		int autoCount = 0;
		for (BuildingCatalog.Entry e : all) {
			boolean small = e.kind.equals("A_LAMP") || e.kind.equals("A_SIGN") || e.kind.equals("A_DECOR");
			if (small && e.tilesW() * e.tilesH() >= BuildingCatalog.ROOM_TILES) {
				wrong.add("\"" + e.name + "\" is still " + e.kind + " at " + e.tilesW() + "x" + e.tilesH());
			}
			if (misfiled.contains(e.donorRegion + ":" + e.tx0 + "," + e.ty0 + "," + e.tx1 + "," + e.ty1)) {
				refiled++;
				String rest = e.name.startsWith(e.location + " ") ? e.name.substring(e.location.length() + 1) : e.name;
				if (!e.kind.equals(BuildingCatalog.STRUCTURE) || !rest.matches("structure( \\d+)?")) {
					wrong.add("\"" + e.name + "\" (" + e.kind + ") was refiled but not named a structure of its place");
				}
			}
			if (e.auto) {
				autoCount++;
				autoNames.add(e.name);
			}
		}
		check(refiled == misfiled.size() && wrong.isEmpty(), "and the catalog offers each of them as a structure of its place, named so, and"
				+ " no lamp, sign or decor of a room's size: " + refiled + " refiled, " + wrong);
		check(autoNames.size() == autoCount, "and no harvested name is given twice by the renaming: " + autoNames.size()
				+ " names for " + autoCount + " entries");

		//the renaming numbers after every name already taken, wherever in the file it is
		String tsv = "A_STRUCT\tTown structure\t1\t2\t0\t0\t1\t1\t0\t-1\t-1\t-\t-1\t-1\tTown\t1\taa\n"
				+ "A_LAMP\tTown lamp\t1\t2\t0\t0\t10\t8\t0\t-1\t-1\t-\t-1\t-1\tTown\t1\tbb\n"
				+ "A_LAMP\tTown lamp 2\t1\t2\t0\t0\t1\t1\t0\t-1\t-1\t-\t-1\t-1\tTown\t1\tcc\n"
				+ "A_STRUCT\tTown structure 2\t1\t2\t0\t0\t1\t1\t0\t-1\t-1\t-\t-1\t-1\tTown\t1\tdd\n";
		List<BuildingCatalog.Entry> into = new ArrayList<>();
		BuildingCatalog.parse(new ByteArrayInputStream(tsv.getBytes(StandardCharsets.UTF_8)), "rename.tsv", true, into,
				new ArrayList<>());
		check(into.size() == 4 && into.get(1).name.equals("Town structure 3") && into.get(1).kind.equals(BuildingCatalog.STRUCTURE)
				&& into.get(2).name.equals("Town lamp 2") && into.get(2).kind.equals("A_LAMP"),
				"a refiled row takes the first structure number no row of the file holds, before or after it, and a lamp"
				+ " of a lamp's size keeps its name: \"" + (into.size() > 1 ? into.get(1).name : "?") + "\"");

		//the harvester files and names what it writes by the same rule
		BuildingHarvester.Comp room = comp(0, 0, 11, 9, "lamp_glass", 12);
		BuildingHarvester.Comp lamp = comp(0, 0, 2, 2, "lamp_glass", 12);
		check(room.tilesW() == 11 && room.tilesH() == 9 && room.hint().equals("lamp") && lamp.tilesW() * lamp.tilesH() == 4,
				"control: the harvester reads an 11x9 and a 2x2 component, both of lamp glass");
		check(room.category().equals(BuildingCatalog.STRUCTURE) && room.noun().equals("structure")
				&& lamp.category().equals("A_LAMP") && lamp.noun().equals("lamp"),
				"and files the room as a structure named so, the lamp as a lamp: " + room.category() + " \"" + room.noun()
				+ "\", " + lamp.category() + " \"" + lamp.noun() + "\"");
		BuildingHarvester.Comp mixed = comp(0, 0, 3, 3, "lamp_glass", 12);
		add(mixed, 0, 0, 3, 3, "chip_kabe_a", 20);
		BuildingHarvester.Comp tied = comp(0, 0, 3, 3, "b_mat", 5);
		add(tied, 0, 0, 3, 3, "a_mat", 5);
		check(mixed.dominantMaterial().equals("chip_kabe_a") && tied.dominantMaterial().equals("a_mat")
				&& new BuildingHarvester.Comp().dominantMaterial().isEmpty(),
				"and names the material owning the most of a cut, a tie to the first by name: " + mixed.dominantMaterial()
				+ ", " + tied.dominantMaterial());

		//and the column it writes is read
		String withMaterial = "A_STRUCT\tTown structure (yane_roof01)\t1\t2\t0\t0\t1\t1\t0\t-1\t-1\t-\t-1\t-1\tTown\t1\taa\tyane_roof01\n"
				+ "A_STRUCT\tTown structure\t1\t2\t0\t0\t1\t1\t0\t-1\t-1\t-\t-1\t-1\tTown\t1\tbb\n";
		List<BuildingCatalog.Entry> read = new ArrayList<>();
		BuildingCatalog.parse(new ByteArrayInputStream(withMaterial.getBytes(StandardCharsets.UTF_8)), "material.tsv", true, read,
				new ArrayList<>());
		check(read.size() == 2 && read.get(0).material.equals("yane_roof01") && read.get(1).material.isEmpty(),
				"a harvested table's material column is read, and a table harvested before it reads as none");
	}

	/** A harvester component of {@code faces} triangles of one material over a tile box. */
	static BuildingHarvester.Comp comp(int tx, int ty, int w, int h, String material, int faces) {
		BuildingHarvester.Comp c = new BuildingHarvester.Comp();
		add(c, tx, ty, w, h, material, faces);
		return c;
	}

	static void add(BuildingHarvester.Comp c, int tx, int ty, int w, int h, String material, int faces) {
		float x0 = -360f + (tx + 0.5f) * 18f, z0 = -360f + (ty + 0.5f) * 18f;
		float x1 = -360f + (tx + w - 0.5f) * 18f, z1 = -360f + (ty + h - 0.5f) * 18f;
		for (int i = 0; i < faces; i++) {
			c.addFace(new float[]{x0, 10, z0, x1, 10, z0, x1, 10, z1}, material);
		}
		c.computeTiles();
	}

	/**
	 * A search finds an entry by any of its words - name, kind, place or material - and by its size, and
	 * every word asked must be found (F251). It matched the whole search as one string against the name or
	 * kind, and the harvested names say only where a cut came from and which bucket it fell in.
	 */
	static void aSearchFindsWhatAnEntryIs(List<BuildingCatalog.Entry> all) {
		System.out.println("--- a search finds an entry by its words, its place, its material and its size (F251)");
		BuildingCatalog.Entry league = new BuildingCatalog.Entry();
		league.kind = "A_LAMP";
		league.name = "Pokémon League lamp";
		league.location = "Pokémon League";
		league.material = "lamp_glass";
		BuildingCatalog.Entry wide = new BuildingCatalog.Entry();
		wide.kind = "A_ROCK";
		wide.name = "Route 101 rock";
		wide.tx1 = 10;
		wide.ty1 = 10;
		check(BuildingCatalog.matchesQuery(league, "") && BuildingCatalog.matchesQuery(league, "pokemon league")
				&& BuildingCatalog.matchesQuery(league, "LAMP glass") && BuildingCatalog.matchesQuery(league, "1x1")
				&& !BuildingCatalog.matchesQuery(league, "lamp rock") && !BuildingCatalog.matchesQuery(league, "1x2")
				&& BuildingCatalog.matchesQuery(wide, "11x11 rock") && !BuildingCatalog.matchesQuery(wide, "1x1")
				&& BuildingCatalog.matchesQuery(wide, "a_rock"),
				"the rule's known answers: case and accents ignored, every word must be found, the material is read, and"
				+ " a size names the box exactly - \"1x1\" is not an 11x11");
		int lamps = 0, rooms = 0, sootopolis = 0;
		boolean placed = true;
		for (BuildingCatalog.Entry e : all) {
			if (BuildingCatalog.matchesQuery(e, "lamp")) {
				lamps++;
				rooms += e.tilesW() * e.tilesH() >= BuildingCatalog.ROOM_TILES ? 1 : 0;
			}
			if (BuildingCatalog.matchesQuery(e, "sootopolis 11x9")) {
				sootopolis++;
				placed &= e.location.equals("Sootopolis City") && e.tilesW() == 11 && e.tilesH() == 9;
			}
		}
		check(lamps > 0 && rooms == 0, "and over the catalog the release ships, \"lamp\" finds lamps and no room: " + lamps
				+ " found, " + rooms + " of a room's size");
		check(sootopolis >= 7 && placed, "and \"sootopolis 11x9\" finds Sootopolis City's 11x9 cuts and nothing else: "
				+ sootopolis);
	}

	/**
	 * The palette lists the catalog under one heading per place, the curated entries first and open, every
	 * place closed until clicked or searched (F251) - it was one flat list of 3,583, a house reached by
	 * arrowing through 2,130 "structure N" rows. And the dialog builds its list from exactly these.
	 */
	static void thePaletteListsTheCatalogUnderItsPlaces(List<BuildingCatalog.Entry> all) throws Exception {
		System.out.println("--- the palette lists the catalog under its places, the curated first and open (F251)");
		java.util.TreeSet<String> places = new java.util.TreeSet<>();
		int curated = 0;
		for (BuildingCatalog.Entry e : all) {
			if (e.auto) {
				places.add(e.location.isEmpty() ? "Unknown place" : e.location);
			} else {
				curated++;
			}
		}
		check(curated == 48 && places.size() > 50, "control: the catalog holds the 48 curated entries and harvested ones from "
				+ places.size() + " places");
		java.util.Set<String> none = new java.util.HashSet<>();
		List<Object> closed = ctrmap.humaninterface.BuildingPaletteDialog.rows(all, none, false);
		List<String> headings = new ArrayList<>();
		int headed = 0, entries = 0;
		for (Object row : closed) {
			if (row instanceof ctrmap.humaninterface.BuildingPaletteDialog.Heading) {
				ctrmap.humaninterface.BuildingPaletteDialog.Heading h = (ctrmap.humaninterface.BuildingPaletteDialog.Heading) row;
				headings.add(h.title);
				headed += h.count;
			} else {
				entries++;
			}
		}
		List<String> expected = new ArrayList<>();
		expected.add(ctrmap.humaninterface.BuildingPaletteDialog.CURATED);
		expected.addAll(places);
		check(headings.equals(expected) && headed == all.size(), "one heading for the curated entries, then one per place in"
				+ " order, and every entry under one of them: " + headings.size() + " headings over " + headed + " entries");
		check(entries == curated && closed.get(1) instanceof BuildingCatalog.Entry && !((BuildingCatalog.Entry) closed.get(1)).auto,
				"and only the curated heading starts open, its entries straight under it: " + entries + " entries listed");
		//the place with the most entries, so a heading that opened onto a few would show
		java.util.Map<String, Integer> perPlace = new java.util.HashMap<>();
		for (BuildingCatalog.Entry e : all) {
			if (e.auto) {
				perPlace.merge(e.location, 1, Integer::sum);
			}
		}
		String place = java.util.Collections.max(perPlace.entrySet(), java.util.Map.Entry.comparingByValue()).getKey();
		List<Object> one = ctrmap.humaninterface.BuildingPaletteDialog.rows(all, new java.util.HashSet<>(java.util.Arrays.asList(place,
				ctrmap.humaninterface.BuildingPaletteDialog.CURATED)), false);
		int under = 0;
		boolean ofThePlace = true;
		for (Object row : one) {
			if (row instanceof BuildingCatalog.Entry) {
				under++;
				ofThePlace &= place.equals(((BuildingCatalog.Entry) row).location);
			}
		}
		int ofPlace = 0;
		for (BuildingCatalog.Entry e : all) {
			ofPlace += e.auto && e.location.equals(place) ? 1 : 0;
		}
		check(under == ofPlace && ofThePlace, "a clicked place opens with its entries under it, and a clicked curated heading"
				+ " closes: " + under + " listed of " + place + "'s " + ofPlace);
		List<Object> open = ctrmap.humaninterface.BuildingPaletteDialog.rows(all, none, true);
		check(open.size() == all.size() + headings.size(), "and a search opens every heading: " + open.size() + " rows");

		List<BuildingCatalog.Entry> lights = ctrmap.humaninterface.BuildingPaletteDialog.shown(all, "", "Lamps & lights", null);
		List<BuildingCatalog.Entry> asked = ctrmap.humaninterface.BuildingPaletteDialog.shown(all, "sootopolis 11x9", null, null);
		boolean allLamps = !lights.isEmpty();
		for (BuildingCatalog.Entry e : lights) {
			allLamps &= e.kind.equals("A_LAMP");
		}
		check(allLamps && asked.size() >= 7, "the dialog's filter narrows by type and answers the catalog's search: "
				+ lights.size() + " lamps, " + asked.size() + " for \"sootopolis 11x9\"");

		//the dialog's list is these two, at its one call site
		String dialog = SourceSeamTest.stripComments(new String(java.nio.file.Files.readAllBytes(
				new File("src/ctrmap/humaninterface/BuildingPaletteDialog.java").toPath()), StandardCharsets.UTF_8));
		int calls = dialog.split("rows\\(shown\\(all, ", -1).length - 1;
		check(calls == 1 && !dialog.contains(".name.toLowerCase().contains("), "and the palette dialog builds its list from"
				+ " rows(shown(...)), with no search of its own: " + calls + " call(s)");
	}

	/** {@code text}, then a read that fails - a disk that went away part way through a file. */
	static java.io.InputStream failsAfter(String text) {
		final byte[] b = text.getBytes(StandardCharsets.UTF_8);
		return new java.io.InputStream() {
			int at = 0;

			@Override
			public int read() throws java.io.IOException {
				if (at < b.length) {
					return b[at++] & 0xFF;
				}
				throw new java.io.IOException("the disk went away");
			}
		};
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

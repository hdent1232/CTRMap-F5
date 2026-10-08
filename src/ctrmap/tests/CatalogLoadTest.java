package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BuildingCatalog;
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

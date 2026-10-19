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
		//and so are the rows whose box an earlier row of either file already holds (curated first, as loaded)
		java.util.Map<String, String> boxAt = new java.util.HashMap<>();
		List<String> sameBox = new ArrayList<>();
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
				String at = boxAt.putIfAbsent(f[2] + " " + f[4] + " " + f[5] + " " + f[6] + " " + f[7], res + " line " + lineNo);
				if (at != null) {
					sameBox.add(res + " line " + lineNo + " (" + f[1] + ") cuts what " + at + " does");
				}
			}
		}
		List<BuildingCatalog.Entry> all;
		List<String> said;
		try {
			all = BuildingCatalog.entries();
			said = BuildingCatalog.loadProblems();
		} catch (IllegalArgumentException offered) {
			//A ROW THE CATALOG SHOULD HAVE REFUSED, OFFERED: the load measures every box it keeps, so an inverted row
			//let through throws there - which ended this suite with no check saying what it was watching
			check(false, "the catalog keeps a row whose box ends before it starts out of what it offers - loading it"
					+ " threw instead: " + ctrmap.util.Bytes.reason(offered));
			System.out.println("FAILURES PRESENT (" + fails + ")");
			System.exit(1);
			return;
		}
		List<String> saidWhere = new ArrayList<>();
		for (String s : said) {
			saidWhere.add(s.substring(0, s.indexOf(':')));
		}
		//regenerated 2026-10-09 by the fixed harvester (F129, F251): 3,536 cuts verified, 16 of them not written
		//because the curated table (15) or an earlier row (1) already offered their box
		check(rows == 48 + 3520, "control: the two shipped files hold 3,568 rows (48 curated, 3,520 harvested): " + rows);
		check(sameBox.isEmpty(), "no row cuts a box an earlier row already offers, so the palette never lists one"
				+ " cut twice: " + sameBox.size() + " " + (sameBox.size() > 3 ? sameBox.subList(0, 3) + "..." : sameBox));
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
		try {
			BuildingCatalog.parse(new ByteArrayInputStream(box.getBytes(StandardCharsets.UTF_8)), "box.tsv", true, boxed, boxProblems);
		} catch (IllegalArgumentException offered) {
			//THE SAME WORDS AS THE LOAD ABOVE, for the same defect: the shipped files hold no inverted row, so it is this
			//row that an inverted box let through reaches - and its measure threw, ending the suite with nothing said
			//(an-inverted-catalog-row-is-offered, NOT PROVEN 2026-10-14)
			check(false, "the catalog keeps a row whose box ends before it starts out of what it offers - reading box.tsv"
					+ " threw instead: " + ctrmap.util.Bytes.reason(offered));
		}
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

		System.out.println("--- a table a harvester writes ends its lines as the index holds them (LF)");
		File written = new File(Scratch.dir("ctrmap_tablewriter"), "t.tsv");
		try (java.io.PrintWriter w = ctrmap.tools.TableWriter.open(written.getPath())) {
			w.println("# head");
			w.println("a\tb");
		}
		File plain = new File(written.getParentFile(), "plain.tsv");
		try (java.io.PrintWriter w = new java.io.PrintWriter(plain.getPath(), "UTF-8")) {
			w.println("a");
		}
		//CONTROL: a plain PrintWriter writes this platform's separator, which on Windows is the CRLF this replaced
		check(new String(java.nio.file.Files.readAllBytes(plain.toPath()), StandardCharsets.UTF_8).equals("a" + System.lineSeparator()),
				"control: a plain PrintWriter ends a line with this platform's separator");
		check(new String(java.nio.file.Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8).equals("# head\na\tb\n"),
				"and the harvesters' writer ends every line with LF, on this platform too");

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
		aCatalogFileMissingFromTheBuildIsSaid();
		theCatalogsSkippedRowsAreSaidOnce();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/**
	 * The rows the building catalog skipped are said once a session, and nothing is said when there are none (F067;
	 * the 2026-10-14 shared sweep, BuildingPaletteDialog 234, negate-if, SURVIVED: the shipped catalog reads whole, so
	 * no suite had a row to say). The catalog's loaded state is set by reflection, and put back after.
	 */
	static void theCatalogsSkippedRowsAreSaidOnce() throws Exception {
		System.out.println("--- the rows the building catalog skipped are said once, and none is never said (F067)");
		java.lang.reflect.Field entries = BuildingCatalog.class.getDeclaredField("entries");
		java.lang.reflect.Field problems = BuildingCatalog.class.getDeclaredField("problems");
		java.lang.reflect.Field said = ctrmap.humaninterface.BuildingPaletteDialog.class.getDeclaredField("catalogProblemsSaid");
		entries.setAccessible(true);
		problems.setAccessible(true);
		said.setAccessible(true);
		Object keptEntries = entries.get(null), keptProblems = problems.get(null), keptSaid = said.get(null);
		List<String> heard = ctrmap.Ui.record();
		try {
			entries.set(null, new ArrayList<BuildingCatalog.Entry>());
			problems.set(null, new ArrayList<String>());
			said.set(null, false);
			ctrmap.humaninterface.BuildingPaletteDialog.sayCatalogProblems(null);
			check(heard.isEmpty(), "CONTROL: a catalog that read whole says nothing: " + heard);
			problems.set(null, java.util.Arrays.asList("oras_buildings.tsv line 3: not a number"));
			ctrmap.humaninterface.BuildingPaletteDialog.sayCatalogProblems(null);
			ctrmap.humaninterface.BuildingPaletteDialog.sayCatalogProblems(null);
			check(heard.size() == 1 && heard.get(0).contains("skipped 1 row(s)") && heard.get(0).contains("line 3: not a number"),
					"a catalog that skipped a row says so once, naming it, and not again: " + heard);
		} finally {
			ctrmap.Ui.stopRecording();
			entries.set(null, keptEntries);
			problems.set(null, keptProblems);
			said.set(null, keptSaid);
		}
	}

	/**
	 * A catalog file missing from the build is said in the catalog's problems, never loaded as a catalog with nothing
	 * in it.
	 *
	 * <p>Measured by the 2026-10-14 shared sweep (BuildingCatalog 137, drop-report, SURVIVED): no catalog file had ever
	 * been missing. Dropped, the catalog opened without that file's buildings and said nothing. Asked through
	 * reflection: the loader is the catalog's own, and the build always ships both files.
	 */
	static void aCatalogFileMissingFromTheBuildIsSaid() throws Exception {
		System.out.println("--- a catalog file missing from the build is said, never loaded as an empty one");
		java.lang.reflect.Method load = BuildingCatalog.class.getDeclaredMethod("load", String.class, boolean.class,
				List.class, List.class);
		load.setAccessible(true);
		List<BuildingCatalog.Entry> shipped = new ArrayList<>();
		List<String> shippedSaid = new ArrayList<>();
		load.invoke(null, "ctrmap/resources/oras_buildings.tsv", false, shipped, shippedSaid);
		check(!shipped.isEmpty() && shippedSaid.isEmpty(), "control: the shipped catalog file loads " + shipped.size()
				+ " entries and says nothing: " + shippedSaid);
		List<BuildingCatalog.Entry> none = new ArrayList<>();
		List<String> said = new ArrayList<>();
		load.invoke(null, "ctrmap/resources/no_such_catalog.tsv", true, none, said);
		check(none.isEmpty() && said.equals(java.util.Collections.singletonList(
				"ctrmap/resources/no_such_catalog.tsv is missing from this build")),
				"a catalog file missing from the build is said, naming it: " + said);

		//AND ONE THAT OPENED AND THEN FAILED (BuildingCatalog 143, drop-report, SURVIVED the same day): a close that
		//fails is said too, naming the file
		java.lang.reflect.Method readAll = BuildingCatalog.class.getDeclaredMethod("readAll", java.io.InputStream.class,
				String.class, boolean.class, List.class, List.class);
		readAll.setAccessible(true);
		List<String> closedSaid = new ArrayList<>();
		readAll.invoke(null, new ByteArrayInputStream(new byte[0]), "fine.tsv", true, new ArrayList<BuildingCatalog.Entry>(),
				closedSaid);
		check(closedSaid.isEmpty(), "control: an empty catalog file that closes says nothing: " + closedSaid);
		List<String> failedSaid = new ArrayList<>();
		readAll.invoke(null, new ByteArrayInputStream(new byte[0]) {
			@Override
			public void close() throws java.io.IOException {
				throw new java.io.IOException("planted: the handle would not close");
			}
		}, "closing.tsv", true, new ArrayList<BuildingCatalog.Entry>(), failedSaid);
		check(failedSaid.equals(java.util.Collections.singletonList(
				"closing.tsv could not be read: java.io.IOException: planted: the handle would not close")),
				"a catalog file whose read fails at its close is said, naming it: " + failedSaid);
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
		//and each other way a row is wrong, by its own words (the 2026-10-14 shared sweep, TerrainCatalog 258, dropped,
		//SURVIVED: the one bad table asked held a bad number, so a short row read past its end and said nothing of it)
		String[][] rowsWrong = {
			{"a row of 5 fields", "GRASS\t1\t2\t3\tmat\n", "line 1 (GRASS): 5 fields; a row has 6, or 10 with the cliff bands"},
			{"a name given twice", "GRASS\t1\t2\t3\tmat\tctr_kusa\nGRASS\t1\t2\t3\tmat\tctr_kusa\n",
				"line 2 (GRASS): the name is given twice"},
			{"a brush this build has none of", "NOT_A_BRUSH\t1\t2\t3\tmat\tx\n", "line 1 (NOT_A_BRUSH): "}};
		for (String[] w : rowsWrong) {
			java.nio.file.Files.write(bad.toPath(), w[1].getBytes(StandardCharsets.UTF_8));
			System.setProperty("terrain", bad.getAbsolutePath());
			String said;
			try {
				said = "read " + ctrmap.formats.tilemap.TerrainCatalog.donors().keySet();
			} catch (IllegalStateException ex) {
				said = ex.getMessage();
			} finally {
				System.clearProperty("terrain");
			}
			check(said.contains(w[2]), w[0] + " refuses the table, naming its line and why: " + said);
		}

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
		//and a row of another width (the 2026-10-14 shared sweep, PaintedMaterials 306, dropped, SURVIVED: every row asked
		//had its four fields, so a three-field row read field 3 past its end and said nothing of the line)
		String groundShort;
		try {
			groundShort = "read " + ctrmap.formats.tilemap.PaintedMaterials.readSpriteTable(new ByteArrayInputStream(
					"chip_a\t1\t2\t100\nchip_b\t1\t2\n".getBytes(StandardCharsets.UTF_8)));
		} catch (java.io.IOException | RuntimeException ex) {
			groundShort = ex.getMessage();
		}
		check("line 2: 3 fields, and a row has 4".equals(groundShort),
				"a ground-material row of 3 fields is refused by its line, naming its width: " + groundShort);
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
		//a table that cannot be opened, or is missing from the build, is refused in words - never an empty set, which
		//is "no material is a sprite" and the black hole back (the 2026-10-14 shared sweep, PaintedMaterials 281,
		//dropped, SURVIVED: the shipped table always opens)
		String groundHead = "The ground-material table could not be read, so the painter cannot tell a sprite atlas from a"
				+ " floor (painting with one draws a black hole): ";
		Object[][] unread = {
			{"a table whose open fails", (ctrmap.formats.tilemap.PaintedMaterials.TableSource) () -> {
				throw new java.io.IOException("the disk went away");
			}, "the disk went away"},
			{"a table missing from the build", (ctrmap.formats.tilemap.PaintedMaterials.TableSource)
				() -> ctrmap.resources.ResourceAccess.getStream("no_such_table.tsv"),
				"ctrmap/resources/no_such_table.tsv is missing from this build"}};
		for (Object[] u : unread) {
			String said;
			try {
				said = "read " + ctrmap.formats.tilemap.PaintedMaterials.spriteMaterials(
						(ctrmap.formats.tilemap.PaintedMaterials.TableSource) u[1]);
			} catch (IllegalStateException ex) {
				said = ex.getMessage();
			}
			check(said.equals(groundHead + u[2]), u[0] + " is refused, saying why: " + said);
		}

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
		//what each row says (the 2026-10-14 shared sweep, BuildingPaletteDialog 270, negate-if, SURVIVED: the list's
		//renderer lived in the modal palette); every answer read, a throw too
		List<String> misSaid = new ArrayList<>();
		for (Object row : closed) {
			String text;
			try {
				text = ctrmap.humaninterface.BuildingPaletteDialog.rowText(row);
			} catch (RuntimeException threw) {
				text = "threw " + threw;
			}
			boolean right = row instanceof ctrmap.humaninterface.BuildingPaletteDialog.Heading
					? text.startsWith("<html><b>") && text.contains(ctrmap.Ui.html(((ctrmap.humaninterface.BuildingPaletteDialog.Heading) row).title))
					: text.startsWith("<html>&nbsp;&nbsp;") && text.contains(ctrmap.Ui.html(((BuildingCatalog.Entry) row).name))
						&& text.contains(" <small>(" + ((BuildingCatalog.Entry) row).tilesW() + "x" + ((BuildingCatalog.Entry) row).tilesH());
			if (!right && misSaid.size() < 3) {
				misSaid.add(text);
			}
		}
		check(misSaid.isEmpty() && closed.size() > curated, "each of the " + closed.size() + " rows says itself: a heading in bold"
				+ " with its place, an entry indented with its name and size - not: " + misSaid);
		//which row a rebuild selects (the 2026-10-14 shared sweep, BuildingPaletteDialog 294, negate-if, SURVIVED)
		String lastPlace = headings.get(headings.size() - 1);
		int lastAt = -1;
		for (int i = 0; i < closed.size(); i++) {
			if (closed.get(i) instanceof ctrmap.humaninterface.BuildingPaletteDialog.Heading
					&& ((ctrmap.humaninterface.BuildingPaletteDialog.Heading) closed.get(i)).title.equals(lastPlace)) {
				lastAt = i;
			}
		}
		int first = ctrmap.humaninterface.BuildingPaletteDialog.selectionAfter(closed, null);
		int kept = ctrmap.humaninterface.BuildingPaletteDialog.selectionAfter(closed, lastPlace);
		int absent = ctrmap.humaninterface.BuildingPaletteDialog.selectionAfter(closed, "no such place");
		//what a selection previews (the 2026-10-14 shared sweep, BuildingPaletteDialog 362, negate-if, SURVIVED)
		Object heading = closed.get(0), entry = closed.get(1);
		String previews;
		try {   //every answer read, a throw too
			previews = (ctrmap.humaninterface.BuildingPaletteDialog.previewed(entry) == entry) + ", "
					+ (ctrmap.humaninterface.BuildingPaletteDialog.previewed(heading) == null) + ", "
					+ (ctrmap.humaninterface.BuildingPaletteDialog.previewed(null) == null);
		} catch (RuntimeException threw) {
			previews = "threw " + threw;
		}
		check(previews.equals("true, true, true"),
				"a selected entry is previewed, and a heading or nothing previews nothing, the last preview staying up: " + previews);
		//a preview shown where there is no 3D view - OpenGL could not start - says its note and draws nothing, with a
		//model or without (the 2026-10-14 shared sweep, BuildingPaletteDialog 406, negate-if, SURVIVED)
		javax.swing.JLabel info = new javax.swing.JLabel(" ");
		ctrmap.humaninterface.BuildingPaletteDialog.Preview withModel = new ctrmap.humaninterface.BuildingPaletteDialog.Preview();
		withModel.model = new byte[]{1};
		withModel.note = "  a hut";
		ctrmap.humaninterface.BuildingPaletteDialog.Preview noModel = new ctrmap.humaninterface.BuildingPaletteDialog.Preview();
		noModel.note = "  Could not cut this piece";
		String showSaid;
		try {
			ctrmap.humaninterface.BuildingPaletteDialog.show(withModel, null, info);
			String shownFirst = info.getText();
			ctrmap.humaninterface.BuildingPaletteDialog.show(noModel, null, info);
			showSaid = shownFirst + " / " + info.getText();
		} catch (RuntimeException threw) {
			showSaid = "threw " + threw;
		}
		check(showSaid.equals("<html>  a hut</html> / <html>  Could not cut this piece</html>"),
				"a preview shown with no 3D view says its note and draws nothing, with a model or without: " + showSaid);
		//Place on the map with a heading chosen keeps the palette open; with a building, it is the pick (the 2026-10-14
		//shared sweep, BuildingPaletteDialog 421, negate-if, SURVIVED) - every answer read, a throw too
		String placed;
		try {
			ctrmap.humaninterface.BuildingPaletteDialog.Pick p = ctrmap.humaninterface.BuildingPaletteDialog.pickOf(entry, true);
			placed = ctrmap.humaninterface.BuildingPaletteDialog.staysOpen(heading) + ", "
					+ ctrmap.humaninterface.BuildingPaletteDialog.staysOpen(entry) + ", "
					+ (p != null && p.entry == entry && p.passengers) + ", "
					+ (ctrmap.humaninterface.BuildingPaletteDialog.pickOf(null, true) == null);
		} catch (RuntimeException threw) {
			placed = "threw " + threw;
		}
		check(placed.equals("true, false, true, true"), "Place on the map keeps the palette open for a heading and picks a"
				+ " building, with its passengers when asked: " + placed);
		//and what the palette DOES with those answers (the 2026-10-16 assembly, BuildingPaletteDialog 469 and 388,
		//negate-if, SURVIVED: the answers were asked and the ifs obeying them were left in the modal palette): a heading
		//placed leaves it open, answering nothing; a building is the answer and closes it; rebuilt rows select the row
		//selectionAfter names, and none when it names none
		ctrmap.humaninterface.BuildingPaletteDialog.Pick[] answered = {null};
		int[] closes = {0};
		String acted;
		try {
			ctrmap.humaninterface.BuildingPaletteDialog.placeChosen(heading, true, answered, () -> closes[0]++);
			acted = (answered[0] == null) + "/" + closes[0];
			ctrmap.humaninterface.BuildingPaletteDialog.placeChosen(entry, false, answered, () -> closes[0]++);
			acted += ", " + (answered[0] != null && answered[0].entry == entry && !answered[0].passengers) + "/" + closes[0];
		} catch (RuntimeException threw) {
			acted = "threw " + threw;
		}
		check(acted.equals("true/0, true/1"), "Place on the map with a heading leaves the palette open answering nothing,"
				+ " and with a building answers it and closes: " + acted);
		javax.swing.DefaultListModel<Object> rowModel = new javax.swing.DefaultListModel<>();
		javax.swing.JList<Object> rowList = new javax.swing.JList<>(rowModel);
		String selected;
		try {
			ctrmap.humaninterface.BuildingPaletteDialog.fill(rowModel, rowList, closed, lastPlace);
			selected = rowList.getSelectedIndex() + "/" + rowModel.size();
			ctrmap.humaninterface.BuildingPaletteDialog.fill(rowModel, rowList, closed, null);
			selected += ", " + rowList.getSelectedIndex();
			ctrmap.humaninterface.BuildingPaletteDialog.fill(rowModel, rowList, new ArrayList<Object>(), null);
			selected += ", " + rowList.getSelectedIndex() + "/" + rowModel.size();
		} catch (RuntimeException threw) {
			selected = "threw " + threw;
		}
		check(selected.equals(kept + "/" + closed.size() + ", " + first + ", -1/0"), "rebuilt rows select the heading"
				+ " kept, else the first building, and nothing in no rows (expected " + kept + "/" + closed.size() + ", "
				+ first + ", -1/0): " + selected);
		//what a click on a row does (the 2026-10-14 shared sweep, BuildingPaletteDialog 434, negate-if, SURVIVED)
		String clicks;
		try {
			clicks = ctrmap.humaninterface.BuildingPaletteDialog.onClick(heading, 1) + ", "
					+ ctrmap.humaninterface.BuildingPaletteDialog.onClick(heading, 2) + ", "
					+ ctrmap.humaninterface.BuildingPaletteDialog.onClick(entry, 2) + ", "
					+ ctrmap.humaninterface.BuildingPaletteDialog.onClick(entry, 1);
		} catch (RuntimeException threw) {
			clicks = "threw " + threw;
		}
		check(clicks.equals("TOGGLE, NONE, CHOOSE, NONE"), "a heading clicked once opens or closes and a double click's"
				+ " second press does nothing; a building double-clicked is chosen, clicked once is not: " + clicks);
		//a heading clicked, and clicked again (the 2026-10-14 shared sweep, BuildingPaletteDialog 306, negate-if, SURVIVED)
		java.util.Set<String> clicked = new java.util.HashSet<>();
		ctrmap.humaninterface.BuildingPaletteDialog.flip(clicked, lastPlace);
		boolean once = clicked.equals(java.util.Collections.singleton(lastPlace));
		ctrmap.humaninterface.BuildingPaletteDialog.flip(clicked, lastPlace);
		check(once && clicked.isEmpty(), "a heading clicked turns from the default, and clicked again turns back: once "
				+ once + ", twice " + clicked);
		check(first == 1 && kept == lastAt && lastAt > 1 && absent == -1, "a rebuild selects the first entry (row " + first
				+ "), or the heading just clicked (" + lastPlace + " at row " + kept + " of " + lastAt + "), and nothing for a"
				+ " heading not there (" + absent + ")");
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

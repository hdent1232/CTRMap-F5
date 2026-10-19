package ctrmap.tests;

import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.formats.containers.GR;
import ctrmap.formats.zone.Zone;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.PaintForm;
import ctrmap.humaninterface.PaintForm.OnActivate;
import ctrmap.humaninterface.PaintForm.Underneath;
import java.io.File;
import java.nio.file.Files;

/**
 * Re-activating the map painter keeps the user's painting unless the region really changed - and
 * then asks - and "could not read" is never "changed" or "no region" (F247).
 *
 * <p>WHY THIS SUITE EXISTS. Every activation re-read the region the painter seeded and, on ANY
 * exception, answered "it changed": a transient read failure - an emulator or a virus scanner
 * holding the file - re-seeded, which put every grid cell back to grass and emptied the undo and
 * redo stacks, without a word. The region-cell lookup under it answered "no region" for a matrix it
 * could not read, and the seed's own read of the donor region was swallowed into "no donor".
 *
 * <p>ORDER: needs no dump. The workspace holds a hand-made matrix and region; the form itself (a GL
 * preview) cannot be built here, so its decisions are asked directly and its source read for where
 * it asks them.
 *
 * Usage: java ctrmap.tests.PainterReactivateTest [src root]
 */
public class PainterReactivateTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		File ws = Scratch.dir("ctrmap_painter_ws");
		File game = Scratch.dir("ctrmap_painter_game");
		try {
			Workspace.install(new WorkspaceSession(ws, game, GameType.ORAS, null));
			theRegionUnderneathIsReadThreeWays(ws);
			theCellIsTheZonesOwnAndNothingIsPaintedWithNoZone(ws);
			whatActivationDoes();
			theFormAsksThese(root);
		} finally {
			Workspace.reset();
			Scratch.deleteTree(ws);
			Scratch.deleteTree(game);
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static File entry(File ws, ArchiveType t, int i) {
		File dir = WorkspaceSession.extractionDirectory(ws, t);
		dir.mkdirs();
		return new File(dir, String.valueOf(i));
	}

	static Underneath underneath(LoadedZone lz, byte[] seeded, StringBuilder why) {
		return PaintForm.underneath(lz, seeded, new byte[1][], why);
	}

	static void theRegionUnderneathIsReadThreeWays(File ws) throws Exception {
		System.out.println("--- the region under the painting is SAME, CHANGED or UNKNOWN - never a guess (F247)");
		Zone z = LoadedZoneTest.blank();   //matrix 0, at (0, 0)
		LoadedZone lz = new LoadedZone();
		lz.table(new Zone[]{z});
		lz.open(0, z);
		File matrix = entry(ws, ArchiveType.MAP_MATRIX, 0);
		File region = entry(ws, ArchiveType.FIELD_DATA, 3);
		Files.write(matrix.toPath(), DiscardRevertsTest.matrix(3));
		Files.write(region.toPath(), MapResizeTest.container(0x4752, new byte[]{9}, new byte[]{1, 2, 3, 4}));
		int[] cell = ctrmap.MapPainter.firstRegionCell(lz);
		check(cell != null && cell[0] == 3, "control: the zone's map cell is region 3");
		byte[] seeded = new GR(region, Workspace.session()).getFile(1);
		StringBuilder why = new StringBuilder();
		check(underneath(lz, seeded, why) == Underneath.SAME, "a region nobody touched is SAME");

		Files.write(region.toPath(), MapResizeTest.container(0x4752, new byte[]{9}, new byte[]{5, 6, 7, 8}));
		check(underneath(lz, seeded, why) == Underneath.CHANGED, "one another editor rewrote is CHANGED");

		Files.delete(region.toPath());
		why.setLength(0);
		check(underneath(lz, seeded, why) == Underneath.UNKNOWN && why.toString().contains("could not be read"),
				"one that cannot be read is UNKNOWN, and says so - it was 'changed', and the painting went: " + why);

		Files.write(region.toPath(), MapResizeTest.container(0x4752, new byte[]{9}, new byte[]{1, 2, 3, 4}));
		Files.delete(matrix.toPath());
		why.setLength(0);
		check(underneath(lz, seeded, why) == Underneath.UNKNOWN && why.toString().contains("could not be read"),
				"so is a map whose matrix cannot be read: " + why);
		String said = "";
		try {
			ctrmap.MapPainter.firstRegionCell(lz);
		} catch (IllegalStateException refused) {
			said = refused.getMessage();
		}
		check(said.contains("could not be read"), "and the cell lookup says it could not read, not 'no region': " + said);

		Files.write(matrix.toPath(), DiscardRevertsTest.matrix(0xFFFF));
		check(ctrmap.MapPainter.firstRegionCell(lz) == null, "a matrix holding no region at all is still 'no region'");
		why.setLength(0);
		check(underneath(lz, seeded, why) == Underneath.UNKNOWN && why.toString().contains("no region"),
				"which the painter cannot check against either: " + why);
		why.setLength(0);
		check(underneath(lz, null, why) == Underneath.UNKNOWN && why.toString().contains("no map model"),
				"nor a painting that started with no model to compare: " + why);
	}

	/**
	 * The cell painted is the zone's OWN - the one its header's position names - and the painter refuses, in
	 * words, to work with no zone or nothing to ask.
	 *
	 * <p>Measured by the 2026-10-12 sweep (MapPainter 920, negated; 905 and 471, dropped and swallowed - all
	 * SURVIVED): nothing asked any of them. With 920 negated the painter took a shared matrix's FIRST occupied
	 * cell for every zone on it - the neighbour's ground, and this zone's warps put in the neighbour's
	 * territory, the very defect the own-cell rule fixed; with 905 or 471 gone, no zone, or no zone and no host,
	 * crashed in no one's words or answered as though there were one.
	 */
	static void theCellIsTheZonesOwnAndNothingIsPaintedWithNoZone(File ws) throws Exception {
		System.out.println("--- the cell painted is the zone's own, and no zone is refused in words");
		Zone z = LoadedZoneTest.blank();   //matrix 0
		z.header.X = 720;                   //the second cell across: 720 world units a cell
		z.header.Y = 0;
		LoadedZone lz = new LoadedZone();
		lz.table(new Zone[]{z});
		lz.open(0, z);
		Files.write(entry(ws, ArchiveType.MAP_MATRIX, 0).toPath(), DiscardRevertsTest.matrix(3, 5));
		int[] cell = ctrmap.MapPainter.firstRegionCell(lz);
		check(cell != null && cell[0] == 5 && cell[1] == 1 && cell[2] == 0,
				"a zone placed in the matrix's second cell paints that cell's region 5, not the first cell's 3: "
						+ (cell == null ? "none" : java.util.Arrays.toString(cell)));
		String said = "";
		try {
			said = "answered " + java.util.Arrays.toString(ctrmap.MapPainter.firstRegionCell(new LoadedZone()));
		} catch (IllegalStateException refused) {
			said = refused.getMessage();
		} catch (RuntimeException crash) {
			said = "crashed: " + crash;
		}
		check(said.contains("no zone is open"), "with no zone open there is no cell, said in words: " + said);
		try {
			said = "answered " + ctrmap.MapPainter.applyToZone(null, 0, null, null, null, null, false, null, null, null);
		} catch (IllegalArgumentException refused) {
			said = refused.getMessage();
		} catch (Exception crash) {
			said = "crashed: " + crash;
		}
		check(said.contains("must be handed the LoadedZone and a Host"),
				"an apply handed no zone and nothing to ask is refused in words: " + said);
	}

	static void whatActivationDoes() {
		System.out.println("--- what re-activating on the same zone does with the painting (F247)");
		check(PaintForm.onActivate(Underneath.UNKNOWN, true) == OnActivate.KEEP,
				"a region it cannot check keeps the painting - it was discarded with its undo history");
		check(PaintForm.onActivate(Underneath.SAME, true) == OnActivate.KEEP, "an unchanged one keeps it");
		check(PaintForm.onActivate(Underneath.CHANGED, true) == OnActivate.ASK,
				"a changed one with painting on it asks before discarding anything");
		check(PaintForm.onActivate(Underneath.CHANGED, false) == OnActivate.RESEED,
				"and a changed one with nothing painted starts over, as there is nothing to lose");
		check(PaintForm.onActivate(Underneath.UNKNOWN, false) == OnActivate.KEEP
				&& PaintForm.onActivate(Underneath.SAME, false) == OnActivate.KEEP, "the rest keep");
	}

	static void theFormAsksThese(File root) throws Exception {
		System.out.println("--- the painter asks these when it is activated, and says what it could not read (F247)");
		String src = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/humaninterface/PaintForm.java")));
		String check = PropEditFormGuardsTest.body(src, "private void checkUnderneath() {");
		//the ban on the old regionChangedUnderneath went with the method: a literal nothing in the tree
		//holds can never be found, so BatteryHygieneTest counts it as a check that cannot fail
		check(src.contains("checkUnderneath();")
				&& check.contains("underneath(loadedZone, originalModel, now, why)") && check.contains("onActivate(u, painted())"),
				"activation on the same zone goes through underneath and onActivate");
		String seed = PropEditFormGuardsTest.body(src, "void seed() {");
		int reasons = seed.split("ctrmap\\.Ui\\.reason\\(unread\\)", -1).length - 1;
		check(!seed.contains("catch (Exception ignore)") && reasons == 3,   //its map, a region's terrain (census M.4), the region
				"and the seed says when its map, its region's terrain or its region could not be read, with the reason, instead of swallowing it ("
				+ reasons + " of 3)");
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

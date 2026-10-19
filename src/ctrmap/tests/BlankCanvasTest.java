package ctrmap.tests;

import ctrmap.GeometryForker;
import ctrmap.MasterTable;
import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.ZoneResource;
import ctrmap.formats.containers.GR;
import ctrmap.formats.h3d.RegionFactory;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Blank map canvas says which regions it emptied and which it could not, and leaves nothing behind
 * when it cannot do the job (F072, F227).
 *
 * <p>WHY THIS SUITE EXISTS. The action forked the zone's map, called the region factory as a bare
 * statement for each region, and then told the user "Zone N now has a private blank canvas" -
 * whether a region had been emptied, left as it was because its model could not be rebuilt, or
 * half-written when a write failed part-way. None of it was one unit of work, so a failure left a
 * fork and some blanked regions behind it.
 *
 * <p>ORDER: needs no dump and no display; writes only under {@link Scratch}. A region this suite
 * can build by hand has no map model, so the half it proves is the refusal and the report; a
 * region actually emptied is the owner's in-app test.
 *
 * Usage: java ctrmap.tests.BlankCanvasTest
 */
public class BlankCanvasTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			aRegionWithNoModelIsLeftAndSaidSo();
			aMapNothingCouldBeEmptiedOnLeavesNothing();
			theReportNamesEveryRegion();
			theActionRunsTheOneUnitAndSaysWhatItDid();
			theAnsweringBlankIsUsedOnlyWhereAHalfCannotHurt(new File(args.length > 0 ? args[0] : "src"));
		} finally {
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/**
	 * Census H.59: inside a unit a region is blanked by the form that throws when a write fails part-way, so the
	 * unit puts the half back and the reason is said. The answering form - false for both "cannot rebuild" and
	 * "half-written", the reason thrown away - was called by Repair shared maps, which then called the half-written
	 * region "kept", and by Resize map, which said only that it "could not be blanked". It is left to the one
	 * caller that cannot be undone and need not be: the append's never-visited spares.
	 */
	static void theAnsweringBlankIsUsedOnlyWhereAHalfCannotHurt(File src) throws Exception {
		System.out.println("--- only the append's spares are blanked by the form that drops the reason (census H.59)");
		java.util.List<String> callers = new java.util.ArrayList<>();
		java.util.List<File> files = new java.util.ArrayList<>();
		java.util.ArrayDeque<File> walk = new java.util.ArrayDeque<>(java.util.Collections.singleton(new File(src, "ctrmap")));
		while (!walk.isEmpty()) {
			File f = walk.pop();
			File[] in = f.listFiles();
			if (in != null) {
				for (File c : in) {
					if (c.isDirectory() && !c.getName().equals("tests")) {
						walk.push(c);
					} else if (c.getName().endsWith(".java")) {
						files.add(c);
					}
				}
			}
		}
		String spares = null;
		for (File f : files) {
			String text = SourceSeamTest.stripComments(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			if (f.getName().equals("GeometryForker.java")) {
				spares = CollisionReadTest.body(text, "public static void forkAppendedZones(");
			}
			int at = -1;
			while ((at = text.indexOf("RegionFactory.blankRegionFiles(", at + 1)) >= 0) {
				boolean inSpares = spares != null && f.getName().equals("GeometryForker.java")
						&& text.indexOf(spares) <= at && at < text.indexOf(spares) + spares.length();
				if (!inSpares) {
					callers.add(f.getName() + " at " + at);
				}
			}
		}
		check(files.size() > 200 && spares != null && spares.contains("RegionFactory.blankRegionFiles("),
				"control: the walk reads " + files.size() + " production files and finds the append's spares calling it");
		check(callers.isEmpty(), "no other caller blanks a region by the form that drops the reason: " + callers);
	}

	static void aRegionWithNoModelIsLeftAndSaidSo() throws Exception {
		System.out.println("--- a region with no model to rebuild is left as it is, and answered for");
		WorkspaceSession ws = MapResizeTest.game("blank_region");
		File region = ws.getWorkspaceFile(ArchiveType.FIELD_DATA, 0);
		byte[] before = Files.readAllBytes(region.toPath());
		check(!RegionFactory.blankOrThrow(new GR(region, ws), -1), "blankOrThrow answers false for it");
		check(!RegionFactory.blankRegionFiles(new GR(region, ws), -1), "and so does blankRegionFiles");
		check(Arrays.equals(Files.readAllBytes(region.toPath()), before), "and neither wrote a byte of it");
	}

	static void aMapNothingCouldBeEmptiedOnLeavesNothing() throws Exception {
		System.out.println("--- a map none of whose regions can be emptied is refused, and its fork goes with it (F227)");
		WorkspaceSession ws = MapResizeTest.game("blank_nothing");
		MasterTable table = MasterTable.read(ws);
		check(table.id(ZoneResource.MAP, 0) == 0 && table.id(ZoneResource.MAP, 1) == 0,
				"control: zones 0 and 1 share map 0, so a canvas for zone 0 forks it first");
		File fd = ws.getExtractionDirectory(ArchiveType.FIELD_DATA);
		File mm = ws.getExtractionDirectory(ArchiveType.MAP_MATRIX);
		List<String> persisted = new ArrayList<>(ws.persistPaths());
		String refused = null;
		try {
			GeometryForker.blankCanvas(0, -1);
		} catch (java.io.IOException | RuntimeException ex) {
			refused = ctrmap.util.Bytes.reason(ex);
		}
		check(refused != null && refused.contains("nothing was changed") && refused.contains("private copy"),
				"a map none of whose regions can be emptied is refused, in words: " + refused);
		check(!new File(fd, "2").exists() && !new File(mm, "2").exists(),
				"and the private copy it made is gone: no region 2, no matrix 2");
		check(MasterTable.read(ws).id(ZoneResource.MAP, 0) == 0, "zone 0 is still on map 0");
		check(ws.persistPaths().equals(persisted), "and the list of edits is as it was: " + ws.persistPaths());
	}

	static void theReportNamesEveryRegion() {
		System.out.println("--- what the user is told names every region (F072)");
		String some = new GeometryForker.Canvas(7, new int[]{40, 41}, new int[]{42}).report();
		check(some.contains("[40, 41]") && some.contains("[42]") && some.contains("could NOT be emptied"),
				"the report names the region it could not empty, apart from the ones it did: " + some.replace("\n", " | "));
		String all = new GeometryForker.Canvas(7, new int[]{40, 41, 42}, new int[0]).report();
		check(all.contains("[40, 41, 42]") && !all.contains("could NOT"),
				"and when it emptied them all, it says only that");
	}

	static void theActionRunsTheOneUnitAndSaysWhatItDid() throws Exception {
		System.out.println("--- the menu action runs that unit and reports what it did");
		String src = new String(Files.readAllBytes(new File("src/ctrmap/CtrmapMainframe.java").toPath()),
				StandardCharsets.UTF_8);
		String action = CollisionReadTest.body(src, "private void blankCanvasAction()");
		check(action != null && action.contains("ctrmap.Ui.form(") && action.indexOf("private static void", 1) < 0,
				"control: the action's body is found, whole and alone");
		//THROUGH THE KEPT SEAM (F209c): the window calls WorkspaceOps.blankCanvas, which runs the one unit and keeps it
		String ops = new String(Files.readAllBytes(new File("src/ctrmap/WorkspaceOps.java").toPath()), StandardCharsets.UTF_8);
		String seam = CollisionReadTest.body(ops, "public static GeometryForker.Canvas blankCanvas(");
		check(action != null && action.contains("WorkspaceOps.blankCanvas(") && seam != null
				&& seam.contains("GeometryForker.blankCanvas(") && !action.contains("blankRegionFiles(")
				&& !action.contains("ensurePrivate(") && !seam.contains("blankRegionFiles(") && !seam.contains("ensurePrivate("),
				"the action runs blankCanvas, not a loop of its own");
		check(action != null && action.contains("canvas.report()"), "the action tells the user what blankCanvas did");
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

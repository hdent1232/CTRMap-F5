package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.garc.GarcRebuilder;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.formats.h3d.MapPrefab;
import ctrmap.formats.tilemap.PaintedGeometry;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.tools.BuildingHarvester;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static ctrmap.formats.containers.ContainerBytes.subfile;

/**
 * The harvester's own decisions, driven on real regions.
 *
 * <p>WHY THIS IS NOT {@link BuildingCatalogTest}. That suite validates the
 * shipped TSV - 3,535 rows of metadata, committed data that no longer changes
 * when the harvester does. These are the functions that PRODUCE it, and every
 * one of them could be broken with the whole battery still green, because
 * nothing runs them: the naming rule that decides what a cut is called, and the
 * footing that decides how high off the ground it stamps. A break here is not
 * noticed until somebody re-harvests, and then it is noticed as 3,535 wrong
 * rows.
 *
 * <p>WHAT IS ASSERTED.
 * <ul>
 * <li>Which keyword family a material name belongs to, against names taken from
 *     the dump - a roof is a building, a gake is a rock, and a ground chip is
 *     nothing at all. The family table's order is its tie-break order, so this
 *     is checked by hand rather than by asking the same loop twice.</li>
 * <li>A cut is named after what DOMINATES it. The live defect: the rule asked
 *     its families in a fixed order and took the first that matched anything,
 *     so twelve triangles of lamp glass named a 4,733-triangle furnished room
 *     "Littleroot Town lamp", and 127 catalogue entries were filed under a
 *     family another family outweighed. The anchor for that case is found in
 *     the dump, not assumed: a component holding both a lamp-family and a
 *     building-family material, where building owns more of it.</li>
 * <li>...and only when the winning family beats the biggest single material no
 *     family recognises, otherwise the cut is named for its size.</li>
 * <li>Only WHOLE cuts are catalogued. The live defect: the gate accepted a cut
 *     that landed at least ONE piece, so fifteen cuts of skinned regions got in
 *     on the strength of the grass base's sea foam and placed three of their
 *     thirty-six pieces under a full-size invisible wall. That is not a
 *     function's answer but a decision the sweep makes about each candidate,
 *     so the harvester is RUN - on a dump cut down to the regions swept above -
 *     and every row it emits has to place whole.</li>
 * <li>A structure's baseY is its own footing. The live defect: the LOWEST
 *     ground under the whole box gave a cliff-top lamp standing at 153 a baseY
 *     of 0 - the sea at the foot of the cliff - and the palette stamped it 153
 *     units into the air. So no component may be filed with a footing outside
 *     its own body: no lower than half a step below its lowest face, no higher
 *     than its top.</li>
 * </ul>
 *
 * Usage: java ctrmap.tests.HarvesterGuardsTest &lt;romfs-root&gt; [regions]
 */
public class HarvesterGuardsTest {

	/** Donor regions to sweep; every catalogue cut came out of one of these. */
	private static final int DEFAULT_REGIONS = 8;

	static int fails = 0;

	/** name -> the family hint it must belong to, or null for "nothing recognises it". */
	private static final String[][] FAMILY_CASES = {
		{"yane_roof01", "building"},
		{"chip_kabe_a", "building"},
		{"mado_glass", "building"},
		{"d112r0103_gake2", "rock"},
		{"chip_iwa_01", "rock"},
		{"lamp_glass", "lamp"},
		{"kanban_a", "sign"},
		{"platan_leaf", "tree"},
		{"kaidan_stone", "stairs"},
		{"chip_ground01", null},
		{"chip_sea_b", null},
	};

	/**
	 * Every program run with too few arguments hands its refusal to whatever started it: its usage line, and exit
	 * status 1 (the 2026-10-15 shared sweep: BuildingHarvester 59 and TerrainDonorHarvester 44, negate-if, SURVIVED -
	 * suites call what answers a status, and no suite may call a main - BatteryHygieneTest - so main's half was never
	 * asked). Inverted, a refused run ended 0, or ran on into its first argument, and one given its arguments was
	 * stopped as refused. THE PROGRAMS ARE FOUND, NOT LISTED: every class under src/ctrmap, suites aside, with a main
	 * and a "Usage: java" line naming itself; each is asked in a child JVM with no arguments, as UiOutputTest asks
	 * its own child.
	 */
	static void aRefusedHarvestEndsItsProcessRefused() throws Exception {
		System.out.println("--- every program given no arguments ends with status 1, saying how to run it");
		List<String> programs = new ArrayList<>();
		try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(new File("src/ctrmap").toPath())) {
			for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) walk::iterator) {
				String rel = new File("src").toPath().relativize(f).toString().replace(File.separatorChar, '/');
				if (!rel.endsWith(".java") || rel.startsWith("ctrmap/tests/")) {
					continue;
				}
				String name = rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
				String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
				if (text.contains("public static void main(String[] args)") && text.contains("\"Usage: java " + name + " ")) {
					programs.add(name);
				}
			}
		}
		java.util.Collections.sort(programs);
		check(programs.contains(BuildingHarvester.class.getName()) && programs.size() >= 3,
				"control: the walk finds the programs with a usage line, the harvester among them: " + programs);
		File java = new File(System.getProperty("java.home"), "bin/java.exe");
		if (!java.isFile()) {
			java = new File(System.getProperty("java.home"), "bin/java");
		}
		for (String program : programs) {
			ProcessBuilder pb = new ProcessBuilder(java.getPath(), "-cp", System.getProperty("java.class.path"), program);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			String said = CommitGuardTest.drain(p).trim();
			int code = p.waitFor();
			check(code == 1 && said.startsWith("Usage: java " + program + " "), program + " given no arguments ends with"
					+ " status 1 and its usage: status " + code + ", said " + (said.length() > 80 ? said.substring(0, 80) : said));
		}
	}

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		File garcFile = new File(dump.getAbsolutePath()
				+ Workspace.getArchivePath(ArchiveType.FIELD_DATA, GameType.ORAS));
		aRefusedHarvestEndsItsProcessRefused();
		if (!garcFile.isFile()) {
			Skip.suite("no pristine FieldData archive under " + dump + " (pass the romfs root as args[0])");
			return;
		}
		int wanted = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_REGIONS;

		materialFamiliesAreRead();
		aThrowIsNotARefusal();

		GARC gr = new GARC(garcFile);
		Set<Integer> regions = donorRegions(wanted);
		List<BuildingHarvester.Comp> comps = sweep(gr, regions);
		check(comps.size() > 20, "the sweep found " + comps.size() + " structures to judge");
		aCutIsNamedAfterWhatDominatesIt(comps);
		anUnrecognisedCutIsNamedForItsSize(comps);
		aStructureStandsOnItsOwnFooting(comps);
		onlyWholeCutsAreCatalogued(dump, gr, regions);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * A candidate that threw is not one the gate refused (census M.142). One counter took both, and a regression in
	 * the cut or the stamp showed up as a smaller catalogue in the run that decides what ships. The verify loop runs
	 * the whole harvest, so its catch is read.
	 */
	static void aThrowIsNotARefusal() throws Exception {
		System.out.println("--- the harvester counts a throw apart from a refusal, and names it (census M.142)");
		String src = SourceSeamTest.stripComments(new String(java.nio.file.Files.readAllBytes(
				new File("src/ctrmap/tools/BuildingHarvester.java").toPath()), java.nio.charset.StandardCharsets.UTF_8));
		int verify = src.indexOf("for (Map.Entry<Long, Cand> en : bySig.entrySet()) {");
		int report = src.indexOf("said.println(\"verified \"", verify);   //the run prints to the stream it is handed
		String loop = verify >= 0 && report > verify ? src.substring(verify, report) : "";
		int caught = loop.lastIndexOf("} catch (Exception ex) {");
		check(caught > 0 && loop.indexOf("threw.add(", caught) > caught && loop.indexOf("dropped++", caught) < 0,
				"a candidate whose cut or stamp threw is counted as a throw, with its reason, not as one the gate refused");
		check(src.indexOf("threw.size()", report) > report, "and the run's summary says how many threw");
		//census L.11: the cuts went to a fixed temp folder keyed by region index alone, never cleared, so a run
		//against a second dump cut from the first dump's bytes
		check(src.contains("File tmpDir = java.nio.file.Files.createTempDirectory(\"ctrmap_harvest\").toFile();")
				&& src.contains("removeRunFolder(tmpDir);"),
				"each harvest cuts into a folder of its own, made fresh and removed at the end");
		File run = java.nio.file.Files.createTempDirectory("ctrmap_harvest_probe").toFile();
		java.nio.file.Files.write(new File(run, "reg_7").toPath(), new byte[]{1});
		new File(run, "ws").mkdirs();
		java.nio.file.Files.write(new File(run, "ws/x").toPath(), new byte[]{2});
		java.lang.reflect.Method remove = BuildingHarvester.class.getDeclaredMethod("removeRunFolder", File.class);
		remove.setAccessible(true);
		remove.invoke(null, run);
		check(!run.exists(), "and the removal takes the folder and what is in it, nested folders too");
		//and a cut folder that cannot be removed is said, so the next run is not trusted to be clean (the 2026-10-15
		//shared sweep, BuildingHarvester 282, drop-throw, SURVIVED: every folder a suite removed went). A file held
		//open in a read-only folder - what stops a delete on Windows and on POSIX - with a probe as the control
		File stuck = java.nio.file.Files.createTempDirectory("ctrmap_harvest_stuck").toFile();
		File probe = java.nio.file.Files.createTempDirectory("ctrmap_harvest_stuck_probe").toFile();
		java.util.List<java.io.RandomAccessFile> held = new ArrayList<>();
		for (File dir : new File[]{stuck, probe}) {
			java.nio.file.Files.write(new File(dir, "reg_7").toPath(), new byte[]{1});
			held.add(new java.io.RandomAccessFile(new File(dir, "reg_7"), "rw"));
			dir.setWritable(false);
		}
		String said;
		try {
			if (ctrmap.util.Bytes.deleteTree(probe)) {
				Skip.section("a file held open in a read-only folder is deleted here, so a cut folder that cannot be"
						+ " removed cannot be made");
				said = null;
			} else {
				try {
					remove.invoke(null, stuck);
					said = "removed, it said";
				} catch (java.lang.reflect.InvocationTargetException threw) {
					said = String.valueOf(threw.getCause().getMessage());
				}
			}
		} finally {
			for (java.io.RandomAccessFile f : held) {
				f.close();
			}
			for (File dir : new File[]{stuck, probe}) {
				dir.setWritable(true);
			}
			ctrmap.util.Bytes.deleteTree(stuck);
			ctrmap.util.Bytes.deleteTree(probe);
		}
		if (said != null) {
			check(said.startsWith("the harvest's cut folder could not be removed"),
					"a cut folder that cannot be removed is said, naming it: " + said);
		}
	}

	/** The keyword table read by hand, so a broken reading of it cannot agree with itself. */
	static void materialFamiliesAreRead() {
		List<String> wrong = new ArrayList<>();
		for (String[] c : FAMILY_CASES) {
			String[] got = BuildingHarvester.familyOf(c[0]);
			String hint = got == null ? null : got[0];
			if (c[1] == null ? hint != null : !c[1].equals(hint)) {
				wrong.add("\"" + c[0] + "\" reads as " + hint + ", not " + c[1]);
			}
		}
		check(wrong.isEmpty(), FAMILY_CASES.length + " material names are placed in the right family"
				+ (wrong.isEmpty() ? "" : " - " + wrong));
	}

	/**
	 * The naming rule, twice over: the anchor case the fix was written for, and
	 * then every component of the sweep against the rule stated in prose - the
	 * family owning the most triangles, only when it beats the biggest single
	 * material nothing recognises, ties to the earlier family, otherwise size.
	 */
	static void aCutIsNamedAfterWhatDominatesIt(List<BuildingHarvester.Comp> comps) {
		BuildingHarvester.Comp anchor = null;
		int lampTris = 0, buildingTris = 0;
		for (BuildingHarvester.Comp c : comps) {
			int lamp = 0, building = 0;
			for (Map.Entry<String, Integer> m : c.materialTriangles().entrySet()) {
				String f = familyHint(m.getKey());
				if ("lamp".equals(f)) {
					lamp += m.getValue();
				} else if ("building".equals(f)) {
					building += m.getValue();
				}
			}
			if (lamp > 0 && building > lamp && (anchor == null || building > buildingTris)) {
				anchor = c;
				lampTris = lamp;
				buildingTris = building;
			}
		}
		if (anchor == null) {
			check(false, "no swept component mixes a lamp material into a building");
		} else {
			check("building".equals(anchor.hint()), "a " + buildingTris + "-triangle building holding "
					+ lampTris + " triangles of lamp is called a building, not a lamp - it is called \""
					+ anchor.hint() + "\"");
		}

		List<String> wrong = new ArrayList<>();
		int named = 0;
		for (BuildingHarvester.Comp c : comps) {
			String want = expectedHint(c);
			if (!want.equals(c.hint())) {
				if (wrong.size() < 3) {
					wrong.add(describe(c) + " is called \"" + c.hint() + "\", but " + want + " dominates it");
				}
			}
			if (!"decor".equals(c.hint()) && !"structure".equals(c.hint())) {
				named++;
			}
		}
		check(named > 0, named + " of the swept components are named after a keyword family at all"
				+ " (if none are, the rule below proves nothing)");
		check(wrong.isEmpty(), "every swept component is named after the family that owns the most of it"
				+ (wrong.isEmpty() ? "" : " - " + wrong));
	}

	/**
	 * The extra clause: a family that does NOT outweigh the biggest single
	 * unrecognised material must not name the cut. Stated as its own check
	 * because the sweep above would still pass if both the rule and this
	 * reading of it lost the clause together.
	 */
	static void anUnrecognisedCutIsNamedForItsSize(List<BuildingHarvester.Comp> comps) {
		BuildingHarvester.Comp anchor = null;
		int famTris = 0, unnamedTris = 0;
		for (BuildingHarvester.Comp c : comps) {
			Map<String, Integer> perFamily = new HashMap<>();
			int unnamed = 0;
			for (Map.Entry<String, Integer> m : c.materialTriangles().entrySet()) {
				String f = familyHint(m.getKey());
				if (f == null) {
					unnamed = Math.max(unnamed, m.getValue());
				} else {
					perFamily.put(f, perFamily.containsKey(f) ? perFamily.get(f) + m.getValue() : m.getValue());
				}
			}
			int best = 0;
			for (int v : perFamily.values()) {
				best = Math.max(best, v);
			}
			if (best > 0 && best <= unnamed && unnamed > unnamedTris) {
				anchor = c;
				famTris = best;
				unnamedTris = unnamed;
			}
		}
		if (anchor == null) {
			check(false, "every swept component's best family outweighs its biggest unknown material");
			return;
		}
		String hint = anchor.hint();
		check("decor".equals(hint) || "structure".equals(hint),
				"a cut whose best family owns " + famTris + " triangles against " + unnamedTris
				+ " of a material nothing recognises is named for its size, not for the family - it is called \""
				+ hint + "\"");
	}

	/**
	 * Nothing may be filed standing in mid-air or buried: the footing has to be
	 * a height the structure itself reaches.
	 */
	static void aStructureStandsOnItsOwnFooting(List<BuildingHarvester.Comp> comps) {
		List<String> wrong = new ArrayList<>();
		for (BuildingHarvester.Comp c : comps) {
			if (c.baseY < c.minY - 9.5f || c.baseY > c.maxY + 0.5f) {
				if (wrong.size() < 3) {
					wrong.add(describe(c) + " stands from " + c.minY + " to " + c.maxY
							+ " but is footed at " + c.baseY);
				}
			}
		}
		check(wrong.isEmpty(), "every swept structure's footing is a height it actually reaches"
				+ (wrong.isEmpty() ? "" : " - " + wrong));
	}

	/**
	 * Runs the harvester for real and requires every row it emits to place
	 * whole.
	 *
	 * <p>The gate that decides this is a statement inside the sweep, not a
	 * function anything can call, so the only honest way to measure it is to
	 * sweep. The dump is cut down first: a FieldData archive holding only the
	 * regions swept above (plus region 1, the harvester's own grass base),
	 * every other slot a four-byte stub the sweep skips as "not a map model".
	 * The zone, matrix and gametext archives are the real ones, so the regions
	 * still resolve to the areas and place names they really have. Nothing
	 * under the dump is opened for writing.
	 */
	static void onlyWholeCutsAreCatalogued(File dump, GARC gr, Set<Integer> regions) throws Exception {
		onlyWholeCutsAreCatalogued0(dump, gr, regions);
		theTerrainHarvestSaysWhatItSkipped(dump, gr);
	}

	/**
	 * The terrain harvest says once what it skipped, or that it skipped nothing (the 2026-10-15 shared sweep,
	 * TerrainDonorHarvester 147, drop-report, SURVIVED: no suite ran it - it could only end its process, until it
	 * answered a status as BuildingHarvester's does). Dropped, a donor table chosen without some regions said so
	 * nowhere. Over a cut-down dump of the first 24 regions and the ones it injects a donor into to test it, read
	 * from the program so the two cannot drift.
	 */
	static void theTerrainHarvestSaysWhatItSkipped(File dump, GARC gr) throws Exception {
		System.out.println("--- the terrain harvest says once what it skipped, or that it skipped nothing");
		java.lang.reflect.Field probes = ctrmap.tools.TerrainDonorHarvester.class.getDeclaredField("PROBE_TARGETS");
		probes.setAccessible(true);
		Set<Integer> keep = new LinkedHashSet<>();
		for (int r = 0; r < 24; r++) {
			keep.add(r);
		}
		for (int r : (int[]) probes.get(null)) {
			keep.add(r);
		}
		File romfs = cutDownDump(dump, gr, keep, "ctrmap_terrain_dump");
		java.io.ByteArrayOutputStream heard = new java.io.ByteArrayOutputStream();
		int status = ctrmap.tools.TerrainDonorHarvester.run(new String[]{romfs.getAbsolutePath(),
			Scratch.file("terrain_tsv").getAbsolutePath()}, new java.io.PrintStream(heard, true, "UTF-8"));
		String said = heard.toString("UTF-8");
		System.out.print(said);
		check(status == 0 && skipReports(said) == 1, "the terrain harvest ran and says once what it skipped, or that it"
				+ " skipped nothing: status " + status + ", " + skipReports(said) + " report(s)");
		//and what it scanned, each brush's donor or that it has none, and what it wrote (the 2026-10-16 assembly,
		//TerrainDonorHarvester 162, 207, 210 and 226, drop-report, SURVIVED: a print to a stream it was handed was not
		//read as a report until then, and nothing read these lines). This cut-down dump leaves ICE with no donor
		List<String> lines = Arrays.asList(said.split("\r?\n"));
		int brushes = ctrmap.formats.tilemap.TilePalette.values().length - 1;   //every brush but VOID
		long chosen = lines.stream().filter(l -> l.startsWith("  ") && l.contains(" <- region ")).count();
		long without = lines.stream().filter(l -> l.startsWith("  ") && l.endsWith(": NO usable donor")).count();
		long scanned = lines.stream().filter(l -> l.matches("scanned \\d+ regions")).count();
		long wrote = lines.stream().filter(l -> l.startsWith("wrote " + chosen + " donors -> ")).count();
		check(scanned == 1 && without > 0 && chosen + without == brushes && wrote == 1,
				"the terrain harvest says what it scanned, each of its " + brushes + " brushes' donor or that it has none,"
				+ " and what it wrote: " + scanned + " scanned line(s), " + chosen + " donor(s), " + without
				+ " without, " + wrote + " wrote line(s)");
	}

	/** How many lines of a harvest's output say what it skipped: "skipped nothing", or "SKIPPED n - ...". */
	static int skipReports(String printed) {
		int reports = 0;
		for (String line : printed.split("\r?\n")) {
			reports += line.equals("skipped nothing") || line.startsWith("SKIPPED ") ? 1 : 0;
		}
		return reports;
	}

	/**
	 * A dump to harvest from, made under the scratch folder {@code name}: the real zone, matrix and gametext archives,
	 * and a FieldData archive holding the regions in {@code keep} as stored, every other slot up to the last of them a
	 * four-byte stub. Nothing under {@code dump} is opened for writing.
	 */
	static File cutDownDump(File dump, GARC gr, Set<Integer> keep, String name) throws Exception {
		File romfs = Scratch.dir(name);
		for (ArchiveType t : new ArchiveType[]{
			ArchiveType.ZONE_DATA, ArchiveType.MAP_MATRIX, ArchiveType.GAMETEXT}) {
			String rel = Workspace.getArchivePath(t, GameType.ORAS);
			File dst = new File(romfs.getAbsolutePath() + rel);
			dst.getParentFile().mkdirs();
			Files.copy(new File(dump.getAbsolutePath() + rel).toPath(), dst.toPath(),
					StandardCopyOption.REPLACE_EXISTING);
		}
		int n = 0;
		for (int r : keep) {
			n = Math.max(n, r + 1);
		}
		List<byte[]> stored = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			stored.add(keep.contains(i) ? gr.getStoredEntry(i) : new byte[]{0, 0, 0, 0});
		}
		File field = new File(romfs.getAbsolutePath()
				+ Workspace.getArchivePath(ArchiveType.FIELD_DATA, GameType.ORAS));
		field.getParentFile().mkdirs();
		GarcRebuilder.write(gr.file, field, stored);
		return romfs;
	}

	/** See {@link #onlyWholeCutsAreCatalogued}: the building harvest's half. */
	static void onlyWholeCutsAreCatalogued0(File dump, GARC gr, Set<Integer> regions) throws Exception {
		Set<Integer> keep = new LinkedHashSet<>(regions);
		keep.add(1);                       //the base the harvester stamps candidates onto
		//...and a contiguous block of the dump's own first regions, catalogued
		//or not: the catalogue's donor regions are by construction regions
		//whose cuts PASSED, and a candidate pool made only of those is a pool
		//chosen by the very gate under test.
		for (int r = 0; r < 24; r++) {
			keep.add(r);
		}
		File romfs = cutDownDump(dump, gr, keep, "ctrmap_harvest_dump");

		File tsv = Scratch.file("harvest_tsv");
		//what the run prints is handed to a stream of this suite's and shown after: it says what it skipped, or that
		//it skipped nothing (the 2026-10-15 shared sweep, BuildingHarvester 166, drop-report, SURVIVED: nothing read
		//what the run printed). Dropped, a catalog harvested without some regions said so nowhere
		java.io.ByteArrayOutputStream heard = new java.io.ByteArrayOutputStream();
		java.io.PrintStream said = new java.io.PrintStream(heard, true, "UTF-8");
		//EVERY 100TH CANDIDATE, not the 500th a full dump uses: the cut-down dump holds a few hundred, and the progress line
		//was a line no suite reached (the 2026-10-17 sweep, BuildingHarvester 264, dropped, a HOLE)
		int status = BuildingHarvester.run(new String[]{romfs.getAbsolutePath(), tsv.getAbsolutePath()}, said, 100);
		System.out.print(heard.toString("UTF-8"));
		check(status == 0, "the harvester ran on a dump and an output it was handed: status " + status);
		if (status != 0) {
			return;
		}
		check(skipReports(heard.toString("UTF-8")) == 1, "the harvest says once what it skipped, or that it skipped nothing ("
				+ skipReports(heard.toString("UTF-8")) + " report(s))");

		List<String> rows = new ArrayList<>();
		for (String line : Files.readAllLines(tsv.toPath(), StandardCharsets.UTF_8)) {
			if (!line.startsWith("#") && !line.trim().isEmpty()) {
				rows.add(line);
			}
		}
		check(!rows.isEmpty(), "the harvester catalogued " + rows.size() + " cuts out of "
				+ keep.size() + " regions");
		//and what it swept and what it wrote, no progress line under 500 candidates, and the table says what its rows
		//passed (the 2026-10-16 assembly, BuildingHarvester 172, 263, 276 and 282, SURVIVED: a print to a stream it was
		//handed was not read as a report until then). Inverted, 263 printed progress for every candidate but each 500th
		List<String> printed = Arrays.asList(heard.toString("UTF-8").split("\r?\n"));
		long swept = printed.stream().filter(l -> l.startsWith("sweep: ") && l.contains(" regions, ")).count();
		long progress = printed.stream().filter(l -> l.startsWith("verify: ")).count();
		long wrote = printed.stream().filter(l -> l.startsWith("wrote " + rows.size() + " entries -> ")).count();
		boolean header = Files.readAllLines(tsv.toPath(), StandardCharsets.UTF_8).stream()
				.anyMatch(l -> l.startsWith("# Every row passed the cut->stamp->validate gate."));
		//how many candidates it verified, from its own closing line: one progress line for each whole hundred
		long candidates = -1;
		for (String l : printed) {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("^verified (\\d+) entries \\((\\d+) refused by the gate, (\\d+)"
					+ " threw").matcher(l);
			if (m.find()) {
				candidates = Long.parseLong(m.group(1)) + Long.parseLong(m.group(2)) + Long.parseLong(m.group(3));
			}
		}
		check(candidates >= 100, "control: the harvest verified " + candidates + " candidates - enough for a progress line at"
				+ " every 100th to be asked");
		check(swept == 1 && progress == candidates / 100 && wrote == 1 && header, "the building harvest says what it swept"
				+ " and what it wrote, its progress at every 100th of " + candidates + " candidates, and its table says what"
				+ " its rows passed: " + swept + " sweep line(s), " + progress + " progress line(s), " + wrote
				+ " wrote line(s), header " + (header ? "written" : "missing"));
		//and offers no box the curated catalog already offers, wired with its doors (the 2026-10-15 shared sweep,
		//BuildingHarvester 192, negate-if, SURVIVED: nothing compared a harvest's boxes with the curated ones).
		//Inverted, the boxes held back were the auto table's own, and the curated ones came back unwired
		Set<String> curated = new java.util.HashSet<>();
		int curatedHere = 0;
		for (BuildingCatalog.Entry e : BuildingCatalog.entries()) {
			if (!e.auto) {
				curated.add(BuildingCatalog.boxKey(e.donorRegion, e.tx0, e.ty0, e.tx1, e.ty1));
				curatedHere += keep.contains(e.donorRegion) ? 1 : 0;
			}
		}
		List<String> again = new ArrayList<>();
		for (String row : rows) {
			String[] f = row.split("\t");
			String box = BuildingCatalog.boxKey(Integer.parseInt(f[2]), Integer.parseInt(f[4]), Integer.parseInt(f[5]),
					Integer.parseInt(f[6]), Integer.parseInt(f[7]));
			if (curated.contains(box)) {
				again.add(f[1] + " " + box);
			}
		}
		check(curatedHere > 0, "control: the harvested regions hold curated buildings (" + curatedHere + ")");
		check(again.isEmpty(), "no harvested row offers a box the curated catalog already offers: " + again);

		int dim = PaintedGeometry.DIM;
		TilePalette[][] grid = new TilePalette[dim][dim];
		for (TilePalette[] row : grid) {
			Arrays.fill(row, TilePalette.GRASS);
		}
		byte[] base = PaintedRegionBuilder.build(subfile(gr.getDecompressedEntry(1), 1), grid, null, null,
				TerrainLighting.daytime(), false).model;
		File rf = Scratch.file("harvest_row_region");
		List<String> broken = new ArrayList<>();
		//a scratch game for the region containers to report to: nothing here opens a workspace
		FakeGameFiles game = new FakeGameFiles();
		for (String row : rows) {
			String[] f = row.split("\t");
			String name = f[1];
			int region = Integer.parseInt(f[2]);
			int tx0 = Integer.parseInt(f[4]), ty0 = Integer.parseInt(f[5]);
			int tx1 = Integer.parseInt(f[6]), ty1 = Integer.parseInt(f[7]);
			int baseY = Integer.parseInt(f[8]);
			try (FileOutputStream fo = new FileOutputStream(rf)) {
				fo.write(gr.getDecompressedEntry(region));
			}
			String why = null;
			MapPrefab p = MapPrefab.extract(new GR(rf, game), tx0, ty0, tx1, ty1, "check");
			if (p == null || p.pieces.isEmpty()) {
				why = "no geometry in the box";
			} else {
				MapPrefab.StampResult sr = p.stampGeometry(base, 3, 3, -baseY);
				if (!sr.missingMaterials.isEmpty()) {
					why = sr.missingMaterials.size() + " of " + p.pieces.size()
							+ " pieces cannot be placed: " + sr.missingMaterials;
				}
				for (MapPrefab.Piece pc : p.pieces) {
					if (pc.skinned) {
						why = "piece " + pc.material + " is skinned - it lands only where a map"
								+ " already carries that material";
						break;
					}
				}
			}
			if (why != null && broken.size() < 3) {
				broken.add("\"" + name + "\" (region " + region + " " + tx0 + "," + ty0 + ".." + tx1 + "," + ty1
						+ "): " + why);
			}
		}
		check(broken.isEmpty(), "every cut the harvester catalogued places whole on a painted map"
				+ (broken.isEmpty() ? "" : " - " + broken));
	}

	/** The first donor regions the shipped catalogue cuts from - regions with things in them. */
	static Set<Integer> donorRegions(int wanted) {
		Set<Integer> regions = new LinkedHashSet<>();
		for (BuildingCatalog.Entry e : BuildingCatalog.entries()) {
			if (regions.size() >= wanted) {
				break;
			}
			regions.add(e.donorRegion);
		}
		return regions;
	}

	/** Non-terrain components of those regions. */
	static List<BuildingHarvester.Comp> sweep(GARC gr, Set<Integer> regions) {
		List<BuildingHarvester.Comp> out = new ArrayList<>();
		for (int r : regions) {
			byte[] rc = gr.getDecompressedEntry(r);
			byte[] model = subfile(rc, 1), coll = subfile(rc, 2);
			if (model == null || !BchMapModel.isMapModel(model)) {
				continue;
			}
			for (BuildingHarvester.Comp c : BuildingHarvester.detect(model, coll)) {
				if (!c.terrain) {
					out.add(c);
				}
			}
		}
		System.out.println("  swept " + regions + " -> " + out.size() + " structures");
		return out;
	}

	/**
	 * What the cut asks to be called, worked out here from its raw material
	 * tally: the family owning the most triangles when it beats the biggest
	 * single material no family recognises, otherwise its size.
	 */
	static String expectedHint(BuildingHarvester.Comp c) {
		Map<String, Integer> perFamily = new HashMap<>();
		int biggestUnnamed = 0;
		for (Map.Entry<String, Integer> m : c.materialTriangles().entrySet()) {
			String f = familyHint(m.getKey());
			if (f == null) {
				biggestUnnamed = Math.max(biggestUnnamed, m.getValue());
			} else {
				perFamily.put(f, perFamily.containsKey(f) ? perFamily.get(f) + m.getValue() : m.getValue());
			}
		}
		String best = null;
		int bestTris = 0;
		for (String[] family : BuildingHarvester.HINT_FAMILIES) {   //ties keep the earlier family
			int tris = perFamily.containsKey(family[0]) ? perFamily.get(family[0]) : 0;
			if (tris > bestTris) {
				bestTris = tris;
				best = family[0];
			}
		}
		if (best != null && bestTris > biggestUnnamed) {
			return best;
		}
		return c.tilesW() <= 2 && c.tilesH() <= 2 ? "decor" : "structure";
	}

	/** This suite's own reading of the keyword table, checked by FAMILY_CASES. */
	static String familyHint(String material) {
		String ml = material.toLowerCase();
		for (String[] family : BuildingHarvester.HINT_FAMILIES) {
			for (int k = 1; k < family.length; k++) {
				if (ml.contains(family[k])) {
					return family[0];
				}
			}
		}
		return null;
	}

	static String describe(BuildingHarvester.Comp c) {
		return "the " + c.tilesW() + "x" + c.tilesH() + " cut at tile " + c.tx0 + "," + c.ty0
				+ " (" + c.materialTriangles() + ")";
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

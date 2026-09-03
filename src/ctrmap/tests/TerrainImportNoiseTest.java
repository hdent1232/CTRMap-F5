package ctrmap.tests;

import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TerrainCatalog;
import ctrmap.formats.tilemap.TerrainLighting;
import ctrmap.formats.tilemap.TilePalette;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The terrain import must be silent when it is merely too early, and loud when
 * it really fails.
 *
 * <p>THE DEFECT. Every single painted-region build printed
 * "TerrainCatalog: cliff import failed: java.lang.IllegalArgumentException: no
 * profile for null" to stderr. The cliff material is cut from the pristine
 * snapshot, whose path is resolved through the open workspace's game profile -
 * so a build made before any workspace exists could only ever throw, and the
 * catch swallowed it into a line nobody reads. It cost twice: nine lines of
 * noise per run of PaintedRegionTest alone, and - because the noise was
 * constant - no way for anyone to notice the ONE build where the import really
 * did fail and the map silently kept the wrong rock.
 *
 * <p>THE GUARD. Half one runs a painted-region build the way
 * {@link PaintedRegionTest} does, in a CHILD JVM so the parent can read its
 * stderr for real (this repository forbids System.setErr - see
 * {@link SourceSeamTest}), and fails on any byte of it. Half two satisfies the
 * precondition, breaks the import for real, and fails unless the user is told
 * through {@link Ui}.
 *
 * Usage: java ctrmap.tests.TerrainImportNoiseTest &lt;path-to-a039-garc&gt;
 */
public class TerrainImportNoiseTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		//child mode: build regions and say nothing else, so the parent can
		//measure this process's stderr instead of hijacking its own
		if (args.length > 1 && "--build".equals(args[1])) {
			buildRegions(new File(args[0]));
			return;
		}
		if (args.length == 0 || !new File(args[0]).isFile()) {
			System.out.println("  skip: no pristine FieldData GARC (pass a/0/3/9 as args[0])");
			System.out.println("ALL PASS");
			return;
		}
		buildIsQuiet(new File(args[0]));
		realFailureReachesTheUser();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A build with no workspace open must not write to stderr at all. */
	static void buildIsQuiet(File garc) throws Exception {
		File java = new File(new File(System.getProperty("java.home"), "bin"),
				System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
		if (!java.isFile()) {
			System.out.println("  skip: no java binary at " + java);
			return;
		}
		final Process p = new ProcessBuilder(java.getAbsolutePath(), "-cp",
				System.getProperty("java.class.path"), TerrainImportNoiseTest.class.getName(),
				garc.getAbsolutePath(), "--build").start();
		final List<String> out = new ArrayList<>();
		Thread drain = new Thread(new Runnable() {
			@Override
			public void run() {
				drain(p.getInputStream(), out);
			}
		});
		drain.start();
		List<String> err = new ArrayList<>();
		drain(p.getErrorStream(), err);
		drain.join();
		p.waitFor();
		check(out.contains("built"), "the child JVM really built two painted regions; it said " + out);
		check(err.isEmpty(), "a painted-region build with no workspace open writes nothing to stderr; it said " + err);
	}

	/**
	 * With the precondition met, a genuinely broken import must reach the user.
	 * The snapshot is present (so the catalog is allowed to try) and the model
	 * handed in is not a model, which is what the catch was always for.
	 */
	static void realFailureReachesTheUser() throws Exception {
		String prevPath = Workspace.WORKSPACE_PATH;
		Workspace.GameType prevGame = Workspace.game;
		List<String> said = new ArrayList<>();
		try {
			Workspace.WORKSPACE_PATH = Scratch.dir("ctrmap_terrain_noise").getAbsolutePath();
			Workspace.game = Workspace.GameType.ORAS;
			File snap = new File(Workspace.originalSnapshotDir().getAbsolutePath()
					+ Workspace.getArchivePath(Workspace.ArchiveType.FIELD_DATA, Workspace.game));
			snap.getParentFile().mkdirs();
			try (FileOutputStream fo = new FileOutputStream(snap)) {
				fo.write(new byte[]{'G', 'A', 'R', 'C'});
			}
			said = Ui.record();
			TerrainCatalog.ensureCliffMaterial(new byte[]{'B'});
		} finally {
			Ui.stopRecording();
			Workspace.WORKSPACE_PATH = prevPath;
			Workspace.game = prevGame;
		}
		check(!said.isEmpty(), "a real cliff-import failure is reported through Ui; it said " + said);
	}

	/** Two builds: flat, then a plateau, which is what raises cliff faces. */
	static void buildRegions(File garcFile) throws Exception {
		GARC g = new GARC(garcFile);
		byte[] donor = PaintedRegionTest.sub(g.getDecompressedEntry(1), 1);
		if (donor == null || !BchMapModel.isMapModel(donor)) {
			System.out.println("no donor");
			return;
		}
		int dim = PaintedRegionBuilder.DIM;
		PaintedRegionBuilder.build(donor, PaintedRegionTest.grid(TilePalette.GRASS),
				new int[dim][dim], TerrainLighting.daytime());
		PaintedRegionBuilder.build(donor, PaintedRegionTest.grid(TilePalette.GRASS),
				PaintedRegionTest.plateau(), TerrainLighting.daytime());
		System.out.println("built");
	}

	static void drain(InputStream in, List<String> into) {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				into.add(line);
			}
		} catch (Exception ignore) {
		}
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

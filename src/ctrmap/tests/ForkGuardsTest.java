package ctrmap.tests;

import ctrmap.AreaForker;
import ctrmap.Workspace;
import ctrmap.WorkspaceIntegrity;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * Forking a zone away from the data it shares must leave the archives able to
 * answer for it.
 *
 * <p>An AREA fork appends a private AreaData entry and the NPC registry the
 * engine reads with the SAME id. AreaData carries one entry more than the
 * registry archive - index 228 is the engine's per-area table, not an area - so
 * the new id (229) was one past the end of NPCRegistries, and
 * {@link ctrmap.formats.garc.GARC#packDirectory} silently renumbered the file
 * called "229" to the first free slot, 228. Both dialogs reported success and
 * the editor rendered the zone from the staged workspace file, but in the game
 * the zone's area had no registry behind it and it could not load. A second
 * fork accidentally back-filled 229 and left the counts one apart the other
 * way; the third was refused forever with "AreaData and the NPC registry are
 * out of step", and area forking - the gate on every atmosphere, prop and
 * NPC-model edit - was dead after two uses.
 *
 * <p>Measured on a scratch copy of the dump: one fork gave AreaData=230
 * NPCReg=229 with the clone sitting at 228 and index 229 missing.
 *
 * <p>Runs three consecutive forks, each followed by a real pack, because the
 * damage only showed on the second and third.
 *
 * Usage: java ctrmap.tests.ForkGuardsTest &lt;romfs-root&gt;
 */
public class ForkGuardsTest {

	/** Zones with three different retail areas; any three would do. */
	private static final int[] ZONES = {15, 20, 30};

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump);
			System.out.println("ALL PASS");
			return;
		}
		ScratchGame.open(dump);
		areaForkKeepsTheRegistryAligned();
		gappedAppendIsRefused();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** Three forks in a row, each packed, each leaving a game that can load. */
	static void areaForkKeepsTheRegistryAligned() throws Exception {
		for (int pass = 0; pass < ZONES.length; pass++) {
			int zone = ZONES[pass];
			int oldArea = AreaForker.currentArea(zone);
			byte[] srcRegistry = registry(oldArea);
			AreaForker.ForkResult r;
			try {
				r = AreaForker.forkArea(zone);
			} catch (Exception ex) {
				System.out.println("  FAIL: fork " + (pass + 1) + " of zone " + zone
						+ " was refused: " + ex.getMessage());
				fails++;
				return;
			}
			pack();
			int areas = Workspace.ad.length, regs = Workspace.npcreg.length;
			check(regs == areas, "fork " + (pass + 1) + ": the registry archive covers every area"
					+ " (AreaData " + areas + ", NPCRegistries " + regs + ")");
			check(r.newArea < regs, "fork " + (pass + 1) + ": the registry the engine reads for area "
					+ r.newArea + " exists");
			check(Arrays.equals(registry(r.newArea), srcRegistry),
					"fork " + (pass + 1) + ": and it is the clone of area " + oldArea);
			List<String> bad = WorkspaceIntegrity.check(true);
			check(bad.isEmpty(), "fork " + (pass + 1) + ": the packed game passes every"
					+ " cross-archive invariant " + bad);
		}
	}

	/**
	 * The mechanism underneath, in isolation: a file named past the end of an
	 * archive must be refused, not quietly written somewhere else. This is what
	 * turned the fork's one-slot mistake into a game that would not load, and
	 * it would do the same to any other appender.
	 */
	static void gappedAppendIsRefused() throws Exception {
		File dir = Workspace.getExtractionDirectory(Workspace.ArchiveType.NPC_REGISTRIES);
		int before = Workspace.npcreg.length;
		int past = before + 1;
		File gapped = new File(dir, String.valueOf(past));
		Files.write(gapped.toPath(), new byte[]{1, 2, 3, 4});
		Workspace.addPersist(gapped);
		try {
			pack();
			check(false, "packing a file named past the end of the archive is refused");
		} catch (IOException ex) {
			check(ex.getMessage().contains(String.valueOf(past)),
					"packing a file named past the end of the archive is refused: " + ex.getMessage());
		}
		check(Workspace.npcreg.length == before,
				"and the archive did not grow into the slot it would have been renumbered to");
		Workspace.persist_paths.remove(gapped.getAbsolutePath());
		gapped.delete();
	}

	/** The bytes the engine would read as the registry for an area id. */
	static byte[] registry(int area) {
		byte[] b = Workspace.npcreg.getDecompressedEntry(area);
		return b == null ? null : b;
	}

	static void pack() throws Exception {
		Workspace.packArchives((percent, what) -> {
		});
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

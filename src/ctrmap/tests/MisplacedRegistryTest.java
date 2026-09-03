package ctrmap.tests;

import ctrmap.AreaForker;
import ctrmap.Ui;
import ctrmap.Workspace;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * A workspace an OLD build already damaged has to be told about.
 *
 * <p>The pre-fix area fork wrote the new area's NPC registry one slot early.
 * {@link ctrmap.formats.garc.GARC#packDirectory} renumbered a file named past
 * the tail to the first free slot, so the clone of area 21 that should have
 * landed at index 229 landed at 228 - the slot belonging to AreaData's per-area
 * TABLE, which is not an area and which no zone can ever name. A later fork
 * then back-filled 229 with a different area's clone, leaving AreaData and
 * NPCRegistries the same length, every index in range, and zone 15 reading the
 * registry of somebody else's area.
 *
 * <p>{@link ctrmap.WorkspaceIntegrity} could not see any of it. It checks that
 * the ids a zone uses are IN RANGE, and they are: 229 exists. The archives add
 * up perfectly and the game loads the wrong NPCs, which is the failure mode the
 * whole cross-archive check was written to stop.
 *
 * <p>Fixing the fork (done) stops NEW damage. It does nothing for the
 * workspaces already in this shape, and the fork is the gate on every
 * atmosphere, prop and NPC-model edit, so those workspaces are the ones people
 * are still using.
 *
 * <p>Builds that exact shape on a scratch copy: a healthy fork, then the
 * registry moved back one slot with another area's registry put in its place.
 * Asserts the pack says so through {@link Ui} - a bare dialog is neither
 * reachable nor observable in a headless suite - and that a healthy workspace,
 * forked and packed, stays silent.
 *
 * Usage: java ctrmap.tests.MisplacedRegistryTest &lt;romfs-root&gt;
 */
public class MisplacedRegistryTest {

	/** The zone forked here; any zone with a shared area would do. */
	private static final int ZONE = 15;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump);
			System.out.println("ALL PASS");
			return;
		}
		ScratchGame.open(dump);

		check(pack().isEmpty(), "an untouched workspace tells the user nothing");

		AreaForker.ForkResult fork = AreaForker.forkArea(ZONE);
		check(pack().isEmpty(), "and a workspace with a healthy area fork stays silent too"
				+ " (zone " + ZONE + ": area " + fork.oldArea + " -> " + fork.newArea + ")");

		//the damage: the clone one slot early, another area's registry in its
		//place. Counts stay equal and every index stays in range - exactly what
		//the old fork left behind.
		byte[] clone = Workspace.npcreg.getDecompressedEntry(fork.newArea);
		check(clone != null && clone.length > 0, "the forked area's registry is not empty ("
				+ (clone == null ? -1 : clone.length) + " bytes), so moving it is visible");
		int impostor = otherAreaWithADifferentRegistry(clone);
		check(impostor >= 0, "some other area has a registry of its own to stand in for it");
		byte[] impostorReg = Workspace.npcreg.getDecompressedEntry(impostor);

		write(AreaForker.AD_GLOBAL_TABLE, clone);
		write(fork.newArea, impostorReg);

		String said = pack().toString();
		System.out.println("  the user is shown: " + said);
		check(said.contains(String.valueOf(AreaForker.AD_GLOBAL_TABLE)),
				"the pack names the index holding a registry nothing can read");
		check(said.contains(String.valueOf(fork.newArea)),
				"and the forked area whose zone now reads somebody else's");
		check(said.toLowerCase().contains("re-fork") || said.toLowerCase().contains("by hand"),
				"and says what to do about it");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A retail area whose registry is non-empty and is not the forked clone. */
	static int otherAreaWithADifferentRegistry(byte[] clone) {
		for (int a = 0; a < AreaForker.AD_GLOBAL_TABLE; a++) {
			byte[] r = Workspace.npcreg.getDecompressedEntry(a);
			if (r != null && r.length > 0 && !java.util.Arrays.equals(r, clone)) {
				return a;
			}
		}
		return -1;
	}

	/** Stages a registry entry in the workspace so the next pack writes it. */
	static void write(int index, byte[] bytes) throws Exception {
		File f = Workspace.getWorkspaceFile(Workspace.ArchiveType.NPC_REGISTRIES, index);
		if (f == null) {
			f = new File(Workspace.getExtractionDirectory(Workspace.ArchiveType.NPC_REGISTRIES),
					String.valueOf(index));
		}
		Files.write(f.toPath(), bytes);
		Workspace.addPersist(f);
	}

	/** Packs and reports the way the app does, returning what the user was told. */
	static List<String> pack() throws Exception {
		List<String> said = Ui.record();
		try {
			Workspace.reportPackWarnings(null, Workspace.packArchives((percent, what) -> {
			}));
		} finally {
			Ui.stopRecording();
		}
		return said;
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

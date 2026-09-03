package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.formats.garc.GARC;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * A pack that refuses to run must leave the archive object describing the
 * archive on disk.
 *
 * <p>{@link GARC#packDirectory} walks the staged files in index order, taking
 * each new tail entry into its own entry table as it goes, and only then
 * discovers a file named past the end of the archive and throws "Cannot pack N
 * ... Fill indices X..Y first". By that point the table it is holding is one or
 * more entries longer than the file it came from, and nothing puts it back:
 * {@code parse(file)} runs at the END of a SUCCESSFUL pack, and the throw
 * jumps over it. The instance stays in that state for the rest of the session.
 *
 * <p>The cost is the next pack, which is silent. Packing writes a FATO/FATB
 * from the in-memory table, so the extra entry is written out as a real entry;
 * its bytes are copied from a provisional offset that was never in the file, so
 * the archive gains an entry of garbage, and every archive that indexes this
 * one by number is now one out. Measured on a scratch copy of the dump: a
 * refused pack of NPCRegistries left 229 entries in memory against 228 on disk,
 * and the very next pack - staging nothing at all - grew the archive to 229.
 *
 * <p>The verifier for the gapped-append refusal named this at the time: "call
 * parse(file) before rethrowing".
 *
 * Usage: java ctrmap.tests.PackRollbackTest &lt;romfs-root&gt;
 */
public class PackRollbackTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump);
			System.out.println("ALL PASS");
			return;
		}
		ScratchGame.open(dump);
		refusedPackLeavesTheTableAsTheFileHasIt();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * Stages one legal tail append and one file two past the end. The refusal
	 * has to come AFTER the legal one has already been taken into the table -
	 * that is the whole defect, and a lone gapped file never reaches it.
	 */
	static void refusedPackLeavesTheTableAsTheFileHasIt() throws Exception {
		File dir = Workspace.getExtractionDirectory(Workspace.ArchiveType.NPC_REGISTRIES);
		int before = Workspace.npcreg.length;
		File tail = new File(dir, String.valueOf(before));
		File gapped = new File(dir, String.valueOf(before + 2));
		Files.write(tail.toPath(), new byte[]{1, 2, 3, 4});
		Files.write(gapped.toPath(), new byte[]{5, 6, 7, 8});
		Workspace.addPersist(tail);
		Workspace.addPersist(gapped);
		File archive = Workspace.npcreg.file;
		try {
			pack();
			check(false, "a pack that would leave a gap in the archive is refused");
		} catch (IOException ex) {
			check(ex.getMessage().contains(String.valueOf(before + 2)),
					"a pack that would leave a gap in the archive is refused: " + ex.getMessage());
		}

		GARC fresh = new GARC(archive);
		check(Workspace.npcreg.getEntryCount() == fresh.getEntryCount(),
				"the refused pack left the entry table as the file has it (" + Workspace.npcreg.getEntryCount()
				+ " entries in memory, " + fresh.getEntryCount() + " in " + archive.getName() + ")");
		check(Workspace.npcreg.length == fresh.length,
				"and the entry count with it (" + Workspace.npcreg.length + " vs " + fresh.length + ")");
		int common = Math.min(Workspace.npcreg.getEntryCount(), fresh.getEntryCount());
		int differing = 0;
		for (int i = 0; i < common; i++) {
			if (Workspace.npcreg.getEntryStoredLength(i) != fresh.getEntryStoredLength(i)
					|| Workspace.npcreg.isEntryCompressed(i) != fresh.isEntryCompressed(i)) {
				differing++;
			}
		}
		check(differing == 0, "and every entry it describes matches a fresh read of the file");

		//the cost: with nothing staged at all, the next pack still writes the
		//table it is holding, so the archive grows by an entry of garbage
		Workspace.persist_paths.remove(tail.getAbsolutePath());
		Workspace.persist_paths.remove(gapped.getAbsolutePath());
		tail.delete();
		gapped.delete();
		pack();
		check(Workspace.npcreg.length == before,
				"and a later pack that stages nothing does not grow the archive by the entry the"
				+ " refused one had taken in (" + before + " -> " + Workspace.npcreg.length + ")");
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

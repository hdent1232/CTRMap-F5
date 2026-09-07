package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * A pack writes the archives the user edited, and leaves the others where they
 * are.
 *
 * <p>{@link Workspace#packArchives} decides archive by archive, and for the
 * optional ones the decision is a single {@code hasPersistedFiles} call. The
 * trainer, Maison and storytext decisions all sit behind a null check as well,
 * so a suite trips over them the moment they are wrong. GameText does not: it
 * is always loaded, and the whole decision is
 * {@code if (hasPersistedFiles(getExtractionDirectory(GAMETEXT)))} - one
 * condition with nothing behind it.
 *
 * <p>Measured: with that condition inverted the battery stayed green through
 * PackReport, PackRollback, Integrity, ForkGuards and MisplacedRegistry, and
 * the two things it does are both silent. Every dialogue edit staged in the
 * workspace is dropped - the user edits a sign, packs, and the game shows the
 * old text with no warning anywhere - and the untouched GameText archive is
 * rewritten on every pack instead, which is the churn the check was added to
 * stop (a 12 MB archive rewritten each time, and a fresh chance for the
 * emulator to be holding it open).
 *
 * <p>Both halves are asserted here because inverting the condition swaps them:
 * a guard on only one of them would still pass in the other direction.
 *
 * Usage: java ctrmap.tests.PackScopeTest &lt;romfs-root&gt;
 */
public class PackScopeTest {

	/** A time no pack could produce, so "was it rewritten" has one answer. */
	private static final long LONG_AGO = 1000000000000L;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump);
			System.out.println("ALL PASS");
			return;
		}
		ScratchGame.open(dump);
		File archive = Workspace.session().archiveFile(ArchiveType.GAMETEXT);

		//1. nothing staged for GameText: the archive must be the same file afterwards
		long lengthBefore = archive.length();
		//A pack that leaves the archive alone still reloads it at the end, so
		//the GARC and the file agree on this timestamp afterwards and the next
		//pack has nothing to call stale.
		archive.setLastModified(LONG_AGO);
		pack();
		check(archive.lastModified() == LONG_AGO && archive.length() == lengthBefore,
				"a pack with no text edits does not rewrite the GameText archive"
				+ " (was " + LONG_AGO + "/" + lengthBefore + ", now "
				+ archive.lastModified() + "/" + archive.length() + ")");

		//2. one entry edited: those bytes must be what the game now loads
		int entry = Workspace.getArchive(ArchiveType.GAMETEXT).length - 1;
		File staged = Workspace.getWorkspaceFile(ArchiveType.GAMETEXT, entry);
		byte[] edited = Files.readAllBytes(staged.toPath());
		check(edited.length > 0, "GameText entry " + entry + " extracted (" + edited.length + " bytes)");
		//the last byte of the string data - a different character, same layout,
		//so nothing that parses this file afterwards sees anything malformed
		edited[edited.length - 1] ^= 0x01;
		Files.write(staged.toPath(), edited);
		Workspace.addPersist(staged);
		pack();

		byte[] inGame = new GARC(archive).getDecompressedEntry(entry);
		check(inGame != null && Arrays.equals(edited, inGame),
				"an edited GameText entry is what the packed archive holds");

		//3. the two decisions a pack makes before it writes a byte, each alone
		whatAPackTakes();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The two decisions a pack makes before it writes a byte, each on its
	 * own, now that {@link GARC#packDirectory} is built from them.
	 *
	 * <p>{@link GARC#filesToPack} IS "a pack writes what was edited": the
	 * extraction directory holds every entry ever opened, and only the ones
	 * the workspace lists as edited go back in - in entry order, not the
	 * directory's string order, where "10" comes before "2".
	 * {@link GARC#storedCompressed}: an explicit override wins for any slot,
	 * an existing slot keeps its flag, an appended slot inherits the last
	 * entry's - the rule a zone insert-shift and a plain tail append both
	 * rely on.
	 */
	static void whatAPackTakes() throws Exception {
		File dir = Scratch.dir("ctrmap_pack_take");
		for (String n : new String[]{"10", "2", "3"}) {
			Files.write(new File(dir, n).toPath(), new byte[]{1});
		}
		Workspace.addPersist(new File(dir, "10"));
		Workspace.addPersist(new File(dir, "2"));
		java.util.List<File> take = GARC.filesToPack(dir, Workspace.session()::isPersisted);
		StringBuilder names = new StringBuilder();
		for (File f : take) {
			names.append(f.getName()).append(' ');
		}
		check(take.size() == 2 && take.get(0).getName().equals("2") && take.get(1).getName().equals("10"),
				"a pack takes only the files the workspace lists as edited, in entry order: ["
				+ names.toString().trim() + "]");

		//Asked on two archives whose tails are stored differently - field data
		//ends compressed, zone data ends raw (measured) - so a rule that answers
		//"always compressed" or "always raw" cannot pass as "inherits". Proving
		//this section by breaking found exactly that: on zone data alone,
		//"return false" and "inherit" agree.
		GARC gr = Workspace.getArchive(ArchiveType.FIELD_DATA), zo = Workspace.getArchive(ArchiveType.ZONE_DATA);
		int grLast = gr.getEntryCount() - 1, zoLast = zo.getEntryCount() - 1;
		check(gr.isEntryCompressed(grLast) && !zo.isEntryCompressed(zoLast),
				"the fixtures disagree about their tails (field data ends compressed, zone data raw)"
				+ " - so the rule is asked both ways");
		check(zo.storedCompressed(null, 0) == zo.isEntryCompressed(0)
				&& zo.storedCompressed(null, zoLast) == zo.isEntryCompressed(zoLast)
				&& gr.storedCompressed(null, 0) == gr.isEntryCompressed(0)
				&& gr.storedCompressed(null, grLast) == gr.isEntryCompressed(grLast),
				"an existing slot keeps the way it is stored");
		check(gr.storedCompressed(null, grLast + 1) && gr.storedCompressed(null, grLast + 7)
				&& !zo.storedCompressed(null, zoLast + 1) && !zo.storedCompressed(null, zoLast + 7),
				"an appended slot inherits the last entry's: compressed after field data, raw after zone data");
		check(!gr.storedCompressed(Boolean.FALSE, 0) && gr.storedCompressed(Boolean.TRUE, grLast + 1)
				&& zo.storedCompressed(Boolean.TRUE, 0) && !zo.storedCompressed(Boolean.FALSE, zoLast + 1),
				"and an explicit override wins for any slot, either way");
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

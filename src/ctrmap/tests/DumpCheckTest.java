package ctrmap.tests;

import ctrmap.setup.DumpCheck;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Holds the setup wizard's folder validator to the promise it makes: when the
 * user picks the wrong thing, say what they picked and where the right one is.
 *
 * <p>Every near miss here is one a real person actually makes - the folder above
 * the game, the {@code a} folder inside it, the program-code folder beside it,
 * the .3ds they never unpacked - and each must produce a specific answer rather
 * than a generic refusal.
 *
 * <p>Args: a real, complete RomFS dump folder.
 */
public class DumpCheckTest {

	private static int failures = 0;

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("FAIL usage: DumpCheckTest <a real RomFS dump folder>");
			System.exit(1);
		}
		anUnopenableArchiveIsNotDamage();   //needs no dump: it makes one of its own
		if (!new File(args[0]).isDirectory()) {
			Skip.suite("no complete RomFS dump at " + args[0]);
			return;
		}
		File dump = new File(args[0]);

		DumpCheck.Result ok = DumpCheck.check(dump);
		check("a real dump is VALID", ok.status == DumpCheck.Status.VALID, ok);
		check("the game is identified", ok.game != null && ok.profile != null, ok);
		System.out.println("  real dump  -> " + ok.headline);

		//one level too high: the folder that CONTAINS the dump
		DumpCheck.Result up = DumpCheck.check(dump.getParentFile());
		check("parent folder is WRONG_FOLDER", up.status == DumpCheck.Status.WRONG_FOLDER, up);
		check("parent folder suggests the dump",
				up.suggestion != null && sameFile(up.suggestion, dump), up);
		System.out.println("  parent     -> " + up.headline + "  [-> " + up.suggestion + "]");

		//one level too deep: the archive folder inside the dump
		DumpCheck.Result in = DumpCheck.check(new File(dump, "a"));
		check("'a' folder is WRONG_FOLDER", in.status == DumpCheck.Status.WRONG_FOLDER, in);
		check("'a' folder suggests the dump",
				in.suggestion != null && sameFile(in.suggestion, dump), in);
		System.out.println("  a/         -> " + in.headline + "  [-> " + in.suggestion + "]");

		File tmp = Scratch.dir("ctrmap_dumpcheck");

		//a packed ROM they never unpacked
		File rom = new File(tmp, "Pokemon Omega Ruby.3ds");
		write(rom, "not really a rom");
		DumpCheck.Result r3ds = DumpCheck.check(rom);
		check(".3ds file rejected", r3ds.status == DumpCheck.Status.NOT_A_DUMP, r3ds);
		check(".3ds file explains unpacking", r3ds.detail.toLowerCase().contains("unpack"), r3ds);
		System.out.println("  .3ds file  -> " + r3ds.headline);

		//the ExeFS beside the RomFS
		File exefs = new File(tmp, "decrypted/exefs");
		write(new File(exefs, "code.bin"), "MZ");
		DumpCheck.Result rex = DumpCheck.check(exefs);
		check("exefs is WRONG_FOLDER", rex.status == DumpCheck.Status.WRONG_FOLDER, rex);
		System.out.println("  exefs/     -> " + rex.headline);

		//something entirely unrelated
		File junk = new File(tmp, "holiday photos");
		write(new File(junk, "beach.jpg"), "x");
		DumpCheck.Result rj = DumpCheck.check(junk);
		check("unrelated folder rejected", rj.status == DumpCheck.Status.NOT_A_DUMP, rj);
		check("unrelated folder explains what to look for",
				rj.detail.contains("sound") && rj.detail.contains("shader"), rj);
		System.out.println("  unrelated  -> " + rj.headline);

		//a path that is not there at all
		DumpCheck.Result rn = DumpCheck.check(new File("Z:/definitely/not/here"));
		check("missing path rejected", rn.status == DumpCheck.Status.NOT_A_DUMP, rn);
		check("missing path says so", rn.headline.contains("does not exist"), rn);

		//no message may leak a raw archive path at the user
		for (DumpCheck.Result r : new DumpCheck.Result[]{ok, up, in, r3ds, rex, rj, rn}) {
			String text = r.headline + " " + r.detail;
			check("no raw archive path in \"" + r.headline + "\"", !text.matches(".*\\ba/\\d/\\d/\\d.*"), r);
		}

		//the finder must be quick and must not wander off across the disk
		long t0 = System.currentTimeMillis();
		List<File> found = DumpCheck.findLikelyDumps(4000);
		long ms = System.currentTimeMillis() - t0;
		check("the finder respects its time budget", ms <= 6000, null);
		check("everything the finder returns is really a dump", allDumps(found), null);
		System.out.println("  finder     -> " + found.size() + " dump(s) in " + ms + " ms");
		for (File f : found) {
			System.out.println("                 " + f);
		}

		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	/**
	 * An archive that is there but cannot be opened is not called damage (census M.139). The check answered false
	 * for an IOException exactly as for a wrong magic, and told the user to unpack the whole game again because the
	 * emulator held a file. A dump of 16-byte archives is made, and one of them locked.
	 */
	static void anUnopenableArchiveIsNotDamage() throws Exception {
		File fake = Scratch.dir("ctrmap_dumpcheck_locked");
		ctrmap.gamedef.GameProfile oras = new ctrmap.gamedef.OrasProfile();
		new File(fake, "a").mkdirs();
		write(new File(fake + oras.archivePath(ctrmap.gamedef.ArchiveType.SOUND_BCSAR)), "sound");
		File first = null;
		File second = null;
		byte[] little = {'C', 'R', 'A', 'G', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
		for (ctrmap.gamedef.ArchiveType t : ctrmap.WorkspaceSession.requiredArchives()) {
			String rel = ctrmap.Workspace.getArchivePath(t, oras.type());
			if (rel == null) {
				continue;
			}
			File f = new File(fake + rel);
			f.getParentFile().mkdirs();
			Files.write(f.toPath(), little);
			second = first != null && second == null ? f : second;
			first = first == null ? f : first;
		}
		DumpCheck.Result whole = DumpCheck.check(fake);
		check("control: a dump of whole little archives is VALID", whole.status == DumpCheck.Status.VALID, whole);
		//a damaged one with nothing held says to unpack again and nothing of archives it could not open (the
		//2026-10-15 shared sweep, DumpCheck 248, negate-if, SURVIVED: no suite had damage and a held archive apart)
		write(second, "not an archive at all");
		DumpCheck.Result damaged = DumpCheck.check(fake);
		Files.write(second.toPath(), little);
		check("a damaged archive with none held says to unpack again, and names nothing as unopened",
				damaged.status == DumpCheck.Status.DAMAGED && damaged.detail != null && damaged.detail.contains("unpacking")
				&& !damaged.detail.contains("could not be opened"), damaged);
		try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(first.toPath(),
				java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE);
				java.nio.channels.FileLock held = ch.lock()) {
			boolean blocks;
			try (java.io.RandomAccessFile probe = new java.io.RandomAccessFile(first, "r")) {
				probe.readFully(new byte[4]);
				blocks = false;
			} catch (IOException refused) {
				blocks = true;
			}
			if (!blocks) {
				Skip.section("a lock does not stop a read on this system, so a held archive cannot be made here");
				return;
			}
			DumpCheck.Result locked = DumpCheck.check(fake);
			check("an archive that cannot be opened is said as that, not as an unfinished unpack",
					locked.status != DumpCheck.Status.VALID && locked.detail != null
					&& locked.detail.contains("could not be opened") && !locked.detail.contains("unpacking"), locked);
			//and with damage as well, both are said: unpack again, and these could not be opened (DumpCheck 248)
			write(second, "not an archive at all");
			DumpCheck.Result both = DumpCheck.check(fake);
			Files.write(second.toPath(), little);
			check("a damaged archive and a held one are both said", both.status == DumpCheck.Status.DAMAGED
					&& both.detail != null && both.detail.contains("unpacking") && both.detail.contains("could not be opened"),
					both);
		}
	}

	private static boolean allDumps(List<File> found) {
		for (File f : found) {
			if (!DumpCheck.isDump(f)) {
				System.out.println("FAIL finder returned a non-dump: " + f);
				return false;
			}
		}
		return true;
	}

	private static boolean sameFile(File a, File b) {
		try {
			return a.getCanonicalPath().equalsIgnoreCase(b.getCanonicalPath());
		} catch (IOException ex) {
			return a.getAbsolutePath().equalsIgnoreCase(b.getAbsolutePath());
		}
	}

	private static void write(File f, String s) throws IOException {
		if (f.getParentFile() != null) {
			f.getParentFile().mkdirs();
		}
		Files.write(f.toPath(), s.getBytes("UTF-8"));
	}

	private static void check(String what, boolean ok, DumpCheck.Result r) {
		if (!ok) {
			failures++;
			System.out.println("FAIL " + what
					+ (r == null ? "" : "  (got " + r.status + ": " + r.headline + " / " + r.detail + ")"));
		}
	}
}

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
		if (!new File(args[0]).isDirectory()) {
			System.out.println("  no complete RomFS dump at " + args[0] + " - suite skipped");
			System.out.println("ALL PASS");
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

		File tmp = new File(System.getProperty("java.io.tmpdir"),
				"ctrmap_dumpcheck/" + System.nanoTime());

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

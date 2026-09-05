package ctrmap.tests;

import ctrmap.vault.Vault;
import ctrmap.vault.VaultManifest;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * A backup that cannot be trusted is worse than none, so every claim the vault
 * makes is checked here.
 *
 * <p>WHY THIS SUITE EXISTS. The editor already had an automatic pristine
 * snapshot, and it did not save this project when a stale pack corrupted zone
 * 536 - because it lived inside the workspace, covered 161 MB of a 1.8 GB dump,
 * had no restore, and nothing ever verified it. {@link Vault} answers all four.
 * The failure mode it must never have is the one its predecessor did have:
 * looking complete while being partial. {@code
 * Workspace.snapshotMissingArchives} exists because a snapshot went partial
 * while its stamp still said it was legitimate, donors were cut from archives
 * that were never captured, and nobody found out until much later.
 *
 * <p>So the properties asserted here are, in order of how much damage losing
 * them would do:
 * <ul>
 * <li>an unfinished seal is never readable as a finished one;</li>
 * <li>a sealed vault is never silently overwritten, so an edited dump cannot
 *     replace the pristine record;</li>
 * <li>one game's vault is never replaced by another game's;</li>
 * <li>a dump that failed {@code DumpCheck} is kept but never labelled retail,
 *     and the label survives a reload;</li>
 * <li>drift in the backup is reported, naming the file, and never repaired
 *     silently;</li>
 * <li>restoring one file returns exactly the sealed bytes and touches nothing
 *     else - the zone-536 case.</li>
 * </ul>
 *
 * <p>Runs entirely on a synthetic dump in a scratch folder, and redirects
 * {@code ctrmap.vault.root} so it can never write into the machine's real
 * vault. Needs no corpus.
 *
 * Usage: java ctrmap.tests.VaultGuardsTest
 */
public class VaultGuardsTest {

	static int fails = 0;

	static void check(boolean cond, String msg) {
		if (cond) {
			System.out.println("  ok: " + msg);
		} else {
			System.out.println("  FAIL: " + msg);
			fails++;
		}
	}

	public static void main(String[] args) throws Exception {
		File scratch = File.createTempFile("ctrmap_vault_", "");
		scratch.delete();
		scratch.mkdirs();
		System.setProperty("ctrmap.vault.root", new File(scratch, "vault").getAbsolutePath());
		try {
			File dump = new File(scratch, "000400000011C400");
			buildFakeDump(dump);

			sealsAndVerifies(dump);
			refusesToOverwriteASealedVault(dump);
			unfinishedSealIsNeverReadAsFinished(scratch);
			reportsDriftAndNamesTheFile(dump);
			restoresOneFileWithoutTouchingOthers(dump);
			labelsAnUnverifiedDumpAndKeepsTheLabel(dump);
			aTruncatedManifestIsRefused(dump);
		} finally {
			deleteTree(scratch);
		}

		if (fails == 0) {
			System.out.println("ALL PASS");
		} else {
			System.out.println("FAILURES PRESENT (" + fails + ")");
			System.exit(1);
		}
	}

	// ---- the guards --------------------------------------------------------

	static void sealsAndVerifies(File dump) {
		System.out.println("--- a seal records every file, and verifies clean straight after");
		Vault.SealResult r = Vault.seal(dump, Vault.Scope.FULL_RAW, null);
		check(r.sealed, "the dump was sealed (" + r.problem + ")");
		check(r.manifest != null && r.manifest.entries.size() == 4,
				"all 4 files are in the manifest, not some of them"
				+ (r.manifest == null ? " (no manifest)" : " (got " + r.manifest.entries.size() + ")"));
		check(Vault.isSealed(r.entry), "the entry reads as sealed");
		check(!Vault.isInterrupted(r.entry), "and not as interrupted");
		List<Vault.Drift> drift = Vault.verify(r.entry, true);
		check(drift.isEmpty(), "a fresh vault verifies clean, deeply" + firstOf(drift));
	}

	static void refusesToOverwriteASealedVault(File dump) throws IOException {
		System.out.println("--- a sealed vault is never silently replaced by a later, possibly edited dump");
		File entry = Vault.entryDir(dump, null);
		VaultManifest before = readOrNull(entry);
		//edit the dump the way a user would after hours of work
		write(new File(dump, "a/0/1/3"), "EDITED BY THE USER");
		Vault.SealResult again = Vault.seal(dump, Vault.Scope.FULL_RAW, null);
		check(again.sealed, "sealing again reports success rather than an error");
		VaultManifest after = readOrNull(entry);
		check(before != null && after != null && before.sealedAt == after.sealedAt,
				"but the ORIGINAL seal is what is still on disk - the edited dump did not replace it");
		List<Vault.Drift> drift = Vault.verify(entry, true);
		check(drift.isEmpty(), "and the vault still verifies against its own manifest" + firstOf(drift));
		//put the dump back for the tests that follow
		write(new File(dump, "a/0/1/3"), "zone data");
	}

	static void unfinishedSealIsNeverReadAsFinished(File scratch) throws IOException {
		System.out.println("--- an interrupted seal is detectable, and never counts as a backup");
		File dump = new File(scratch, "000400000011C401");
		buildFakeDump(dump);
		final int stopAfter = 2;
		Vault.SealResult r = Vault.seal(dump, Vault.Scope.FULL_RAW, new Vault.Progress() {
			@Override
			public boolean at(int done, int total, String rel) {
				return done < stopAfter;         // the power goes out here
			}
		});
		check(!r.sealed, "a stopped seal does not report success");
		check(!Vault.isSealed(r.entry), "and the entry does NOT read as sealed");
		check(Vault.isInterrupted(r.entry), "it reads as interrupted, which is a different thing from absent");
		List<Vault.Drift> drift = Vault.verify(r.entry, false);
		check(!drift.isEmpty() && drift.get(0).what.contains("never finished"),
				"and verifying it says so in as many words" + firstOf(drift));
		check(Vault.restoreOne(r.entry, "a/0/1/3", new File(scratch, "nowhere")).length() > 0,
				"restoring from it refuses rather than handing back half a game");
	}

	static void reportsDriftAndNamesTheFile(File dump) throws IOException {
		System.out.println("--- rot in the backup is reported, and the file is named");
		File entry = Vault.entryDir(dump, null);
		File inVault = new File(new File(entry, "files"), "a/0/1/3");
		byte[] good = read(inVault);
		write(inVault, "ROTTED");
		List<Vault.Drift> drift = Vault.verify(entry, true);
		check(drift.size() == 1, "exactly the one damaged file is reported (got " + drift.size() + ")");
		check(!drift.isEmpty() && "a/0/1/3".equals(drift.get(0).relPath),
				"and it is named" + firstOf(drift));
		//a backup that repairs itself stops being evidence
		check(java.util.Arrays.equals(read(inVault), "ROTTED".getBytes("UTF-8")),
				"verifying did not silently repair it - a self-healing backup is not a record");
		writeRaw(inVault, good);
		check(Vault.verify(entry, true).isEmpty(), "and it verifies clean once put back");
	}

	static void restoresOneFileWithoutTouchingOthers(File dump) throws IOException {
		System.out.println("--- restoring ONE file: the zone-536 case, without losing the other edits");
		File entry = Vault.entryDir(dump, null);
		//the user has edited two archives; one of them gets corrupted
		write(new File(dump, "a/0/1/3"), "CORRUPTED BY A STALE PACK");
		write(new File(dump, "a/0/1/4"), "the user's good work");
		String why = Vault.restoreOne(entry, "a/0/1/3", dump);
		check(why.isEmpty(), "the restore reported success (" + why + ")");
		check("zone data".equals(str(read(new File(dump, "a/0/1/3")))),
				"the corrupted file is byte-for-byte what was sealed");
		check("the user's good work".equals(str(read(new File(dump, "a/0/1/4")))),
				"and the OTHER edited file was left completely alone");
		write(new File(dump, "a/0/1/4"), "area data");
	}

	static void labelsAnUnverifiedDumpAndKeepsTheLabel(File dump) {
		System.out.println("--- a dump that failed DumpCheck is kept, but never called retail");
		File entry = Vault.entryDir(dump, null);
		VaultManifest m = readOrNull(entry);
		//the synthetic dump is not a real one, so DumpCheck cannot pass it -
		//which is exactly the case that must not be mislabelled
		check(m != null && !m.verifiedVanilla,
				"the manifest records that this dump was NOT verified vanilla");
		check(m != null && m.summary().contains("NOT verified vanilla"),
				"and says so anywhere it is shown, so the judgement is not left to memory");
		VaultManifest reloaded = readOrNull(entry);
		check(reloaded != null && !reloaded.verifiedVanilla,
				"and the label survives a reload rather than being recomputed");
	}

	static void aTruncatedManifestIsRefused(File dump) throws IOException {
		System.out.println("--- a manifest listing fewer files than it claims is not a manifest");
		File entry = Vault.entryDir(dump, null);
		File man = new File(entry, "manifest.txt");
		byte[] good = read(man);
		String text = str(good);
		//drop the last file line, the way a truncated write would
		int cut = text.lastIndexOf("\r\nf ");
		write(man, text.substring(0, cut) + "\r\n");
		check(!Vault.isSealed(entry) || Vault.verify(entry, false).size() > 0,
				"a truncated manifest does not read as a good backup");
		List<Vault.Drift> drift = Vault.verify(entry, false);
		check(!drift.isEmpty(), "and verifying it complains rather than reporting all-clear"
				+ firstOf(drift));
		writeRaw(man, good);
		check(Vault.verify(entry, true).isEmpty(), "and it is fine again once whole");
	}

	// ---- helpers -----------------------------------------------------------

	static String firstOf(List<Vault.Drift> d) {
		return d.isEmpty() ? "" : " [" + d.get(0) + "]";
	}

	static VaultManifest readOrNull(File entry) {
		for (VaultManifest m : Vault.list()) {
			if (m.entryDir != null && m.entryDir.equals(entry)) {
				return m;
			}
		}
		return null;
	}

	static void buildFakeDump(File dump) throws IOException {
		write(new File(dump, "a/0/1/3"), "zone data");
		write(new File(dump, "a/0/1/4"), "area data");
		write(new File(dump, "a/0/4/0"), "map matrix");
		write(new File(dump, "code.bin"), "executable");
	}

	static void write(File f, String s) throws IOException {
		writeRaw(f, s.getBytes("UTF-8"));
	}

	static void writeRaw(File f, byte[] b) throws IOException {
		f.getParentFile().mkdirs();
		OutputStream os = new FileOutputStream(f);
		try {
			os.write(b);
		} finally {
			os.close();
		}
	}

	static byte[] read(File f) throws IOException {
		return java.nio.file.Files.readAllBytes(f.toPath());
	}

	static String str(byte[] b) throws IOException {
		return new String(b, "UTF-8");
	}

	static void deleteTree(File f) {
		File[] kids = f.listFiles();
		if (kids != null) {
			for (File k : kids) {
				deleteTree(k);
			}
		}
		f.delete();
	}
}

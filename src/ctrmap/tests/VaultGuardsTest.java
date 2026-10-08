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
			flowAsksAndNeverAssumesConsent(scratch);
			aVersionPairIsNeverOneBackup(scratch);
			aDamagedBackupRestoresNothing(scratch);
			theGameAsItShippedIsNeverTheLiveGame(scratch);
			aRestoreRereadsTheOpenGame(scratch);
		} finally {
			Scratch.deleteTree(scratch);
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
		File entry = Vault.entryFor(dump);
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
		File entry = Vault.entryFor(dump);
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
		File entry = Vault.entryFor(dump);
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
		File entry = Vault.entryFor(dump);
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
		File entry = Vault.entryFor(dump);
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

	static void flowAsksAndNeverAssumesConsent(File scratch) throws IOException {
		System.out.println("--- the user-facing flow: a closed dialog is never consent");
		File dump = new File(scratch, "000400000011C402");
		buildFakeDump(dump);
		File entry = ctrmap.vault.Vault.entryFor(dump);

		//1. offered, and declined by closing the dialog - nothing is written
		java.util.List<String> said = ctrmap.Ui.record();          // no answers => CLOSED/null
		try {
			ctrmap.vault.VaultUi.offer(null, dump, 4);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(!ctrmap.vault.Vault.isSealed(entry),
				"closing the size question backs nothing up - silence is not a yes");
		check(!said.isEmpty() && said.get(0).contains("pristine copy"),
				"and the user was actually asked" + (said.isEmpty() ? " (nothing was said)" : ""));

		//2. offered and accepted - it seals, and says where
		said = ctrmap.Ui.record(ctrmap.vault.VaultUi.label(ctrmap.vault.Vault.Scope.FULL_RAW, 4));
		try {
			ctrmap.vault.VaultUi.offer(null, dump, 4);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(ctrmap.vault.Vault.isSealed(entry), "picking a size does back the game up");
		check(lastOf(said).contains("Backup kept") || lastOf(said).contains("has been kept"),
				"and the user is told it worked [" + lastOf(said) + "]");

		//3. a whole-game restore must never proceed on a dismissed dialog: it
		//   would silently discard every edit since the backup was taken
		write(new File(dump, "a/0/1/4"), "hours of the user's work");
		said = ctrmap.Ui.record();                                  // closed
		boolean did;
		try {
			did = ctrmap.vault.VaultUi.restoreEverything(null, entry, dump, null);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(!did, "a dismissed restore prompt does NOT restore");
		check("hours of the user's work".equals(str(read(new File(dump, "a/0/1/4")))),
				"so the user's later edits are still there");
		check(!said.isEmpty() && said.get(0).contains("EVERY CHANGE"),
				"and the prompt said plainly what it would cost before asking");

		//4. and confirming it really does put everything back
		said = ctrmap.Ui.record(Integer.valueOf(javax.swing.JOptionPane.YES_OPTION));
		try {
			did = ctrmap.vault.VaultUi.restoreEverything(null, entry, dump, null);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(did, "confirming it restores");
		check("area data".equals(str(read(new File(dump, "a/0/1/4")))),
				"and the file is what was sealed");
	}

	/**
	 * Two versions of a pair - Omega Ruby and Alpha Sapphire - in folders not named by their title
	 * (F121). Named by game, they share one entry; the second used to be told it was backed up, by
	 * the first's backup, and a restore wrote the first one's files into it.
	 */
	static void aVersionPairIsNeverOneBackup(File scratch) throws IOException {
		System.out.println("--- two dumps the vault cannot tell apart share no backup (F121)");
		File first = new File(new File(scratch, "omega"), "romfs");
		File second = new File(new File(scratch, "alpha"), "romfs");
		buildFakeDump(first);
		buildFakeDump(second);
		write(new File(second, "a/0/1/3"), "the other version's zone data");
		check(Vault.titleIdOf(first) == null && Vault.entryFor(first).equals(Vault.entryFor(second)),
				"control: neither says its title, so both are named by game, alike");
		Vault.SealResult a = Vault.seal(first, Vault.Scope.FULL_RAW, null);
		check(a.sealed, "the first is backed up (" + a.problem + ")");
		Vault.SealResult b = Vault.seal(second, Vault.Scope.FULL_RAW, null);
		check(!b.sealed && b.problem.contains("cannot tell"),
				"the second is NOT called backed up by the first's backup: " + b.problem);
		VaultManifest kept = readOrNull(a.entry);
		check(Vault.verify(a.entry, true).isEmpty() && kept != null && first.getAbsolutePath().equals(kept.takenFrom),
				"and the first's backup is left as it was");
		java.util.List<String> said = ctrmap.Ui.record();
		try {
			ctrmap.vault.VaultUi.offer(null, second, 4);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).contains("NOT been backed up"),
				"the setup offer says so, where it said nothing: " + said);

		String why = Vault.restoreOne(a.entry, "a/0/1/3", second);
		check(why.startsWith("Nothing was restored") && "the other version's zone data".equals(str(read(new File(second, "a/0/1/3")))),
				"restoring the first's backup into the second writes nothing: " + why);
		List<String> all = Vault.restoreAll(a.entry, second, null);
		check(all.size() == 1 && all.get(0).startsWith("Nothing was restored"),
				"nor does restoring all of it, refused once before any file: " + all.size() + " line(s)");
		write(new File(first, "a/0/1/3"), "edited");
		check(Vault.restoreOne(a.entry, "a/0/1/3", first).isEmpty() && "zone data".equals(str(read(new File(first, "a/0/1/3")))),
				"while the folder it was taken from is restored from it");

		System.out.println("--- a title id is read where the dump tools leave it (F121)");
		File named = new File(new File(scratch, "000400000011C500"), "romfs");
		check("000400000011C500".equals(Vault.titleIdOf(named)), "a romfs inside a folder named by its title is that title");
		File cart = new File(scratch, "cart");
		byte[] exheader = new byte[0x400];
		long id = 0x000400000011C500L;
		for (int i = 0; i < 8; i++) {
			exheader[0x200 + i] = (byte) (id >>> (8 * i));
		}
		writeRaw(new File(cart, "exheader.bin"), exheader);
		check("000400000011C500".equals(Vault.titleIdOf(new File(cart, "romfs"))),
				"and so is a romfs beside an ExHeader naming it: " + Vault.titleIdOf(new File(cart, "romfs")));
		exheader[0x204] = 0x77;
		writeRaw(new File(cart, "exheader.bin"), exheader);
		check(Vault.titleIdOf(new File(cart, "romfs")) == null, "an ExHeader naming no application's title names none");

		File pairedWith = new File(new File(scratch, "0004000000055D00"), "romfs");
		buildFakeDump(named);
		buildFakeDump(pairedWith);
		Vault.SealResult c = Vault.seal(named, Vault.Scope.FULL_RAW, null);
		Vault.SealResult d = Vault.seal(pairedWith, Vault.Scope.FULL_RAW, null);
		check(c.sealed && d.sealed && !c.entry.equals(d.entry), "two dumps that say their titles are two backups");
	}

	/**
	 * A backup that has rotted (F140): the restore copied its bytes over the game file first and
	 * checked them after, so it destroyed the file it was asked to repair; and nothing checked the
	 * backup before a restore, or at all.
	 */
	static void aDamagedBackupRestoresNothing(File scratch) throws IOException {
		System.out.println("--- a damaged backup restores nothing, and leaves the file it was asked to repair (F140)");
		File dump = new File(scratch, "000400000011C403");
		buildFakeDump(dump);
		Vault.SealResult r = Vault.seal(dump, Vault.Scope.FULL_RAW, null);
		check(r.sealed, "control: the dump is backed up (" + r.problem + ")");
		File game = new File(dump, "a/0/1/3");
		write(game, "the user's file");
		//the backup rots: the same size, other bytes, so only the digest can tell
		write(new File(new File(r.entry, "files"), "a/0/1/3"), "zone dat@");
		String why = Vault.restoreOne(r.entry, "a/0/1/3", dump);
		check(why.startsWith("Nothing was restored") && "the user's file".equals(str(read(game))),
				"a restore from it is refused, and the user's file is as it was: " + why + " / " + str(read(game)));
		check(!new File(dump, "a/0/1/3.restoring").exists(), "and nothing is left beside it");

		java.util.List<String> said = ctrmap.Ui.record(Integer.valueOf(javax.swing.JOptionPane.YES_OPTION));
		boolean did;
		try {
			did = ctrmap.vault.VaultUi.restoreOne(null, r.entry, "a/0/1/3", dump, null);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(!did && said.size() == 1 && said.get(0).contains("has changed since it was taken"),
				"the window checks the backup first, and says it has changed: " + said);
		said = ctrmap.Ui.record(Integer.valueOf(javax.swing.JOptionPane.YES_OPTION));
		try {
			did = ctrmap.vault.VaultUi.restoreEverything(null, r.entry, dump, null);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(!did && said.size() == 1 && said.get(0).contains("has changed since it was taken") && "the user's file".equals(str(read(game))),
				"and so does a whole restore, before it asks anything: " + said);

		said = ctrmap.Ui.record();
		java.util.List<Vault.Drift> drift;
		try {
			drift = ctrmap.vault.VaultUi.checkOnOpen(null, dump);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(drift.size() == 1 && said.size() == 1 && said.get(0).contains("has changed since it was taken"),
				"opening the game says the backup has changed, before it is needed: " + said);
		write(new File(new File(r.entry, "files"), "a/0/1/3"), "zone data");
		said = ctrmap.Ui.record();
		try {
			drift = ctrmap.vault.VaultUi.checkOnOpen(null, dump);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(drift.isEmpty() && said.isEmpty(), "and says nothing of a backup that is whole: " + said);
		check(Vault.restoreOne(r.entry, "a/0/1/3", dump).isEmpty() && "zone data".equals(str(read(game))),
				"from which a restore lands");
		write(game, "edited after");
		String slashed = Vault.restoreOne(r.entry, "/a/0/1/3", dump);
		String windows = Vault.restoreOne(r.entry, "a\\0\\1\\3", dump);
		check(slashed.isEmpty() && windows.isEmpty() && "zone data".equals(str(read(game))),
				"and a path with a leading slash, or with Windows separators, is the same file (F155): " + slashed + windows);

		String mf = new String(java.nio.file.Files.readAllBytes(new File("src/ctrmap/CtrmapMainframe.java").toPath()),
				java.nio.charset.StandardCharsets.UTF_8);
		String open = CollisionReadTest.body(SourceSeamTest.stripComments(mf), "public static int onWorkspaceOpened(");
		check(open != null && open.contains("VaultUi.checkOnOpen("), "and the window checks it whenever a game opens");
	}

	/**
	 * The game as it shipped comes from the snapshot or the sealed backup, VERIFIED - never from the
	 * live folder (F232).
	 *
	 * <p>Three readers built "snapshot + path" by hand, and two of them, finding no snapshot, read the
	 * user's edited game instead: the donor textures stamped into a new building then came from the
	 * user's own edits. Driven through the one pristine reader and through the building palette's
	 * donor textures, which were one of the two.
	 */
	static void theGameAsItShippedIsNeverTheLiveGame(File scratch) throws Exception {
		System.out.println("--- the game as it shipped is the snapshot's or the backup's, never the live game (F232)");
		File dump = new File(scratch, "000400000011C405");
		buildFakeDump(dump);
		File workspace = new File(scratch, "ws-pristine");
		workspace.mkdirs();
		ctrmap.WorkspaceSession session = Sessions.bare(workspace, dump, ctrmap.gamedef.GameType.ORAS);
		try {
			check(session.pristineArchive(ctrmap.gamedef.ArchiveType.AREA_DATA) == null,
					"with no snapshot and no backup there is no untouched copy - not the live file");
			String refused = null;
			try {
				ctrmap.humaninterface.BuildingPaletteDialog.donorTextures(205);
			} catch (Exception said) {   //any answer is read: one that parsed the live file fails below
				refused = said.getMessage();
			}
			check(refused != null && refused.contains("no untouched copy"),
					"and the palette's donor textures refuse in words rather than reading the edited game: " + refused);

			Vault.SealResult r = Vault.seal(dump, Vault.Scope.FULL_RAW, null);
			check(r.sealed, "control: the dump is backed up (" + r.problem + ")");
			write(new File(dump, "a/0/1/4"), "edited areas");   //the user edits the live game after the seal
			File copy = session.pristineArchive(ctrmap.gamedef.ArchiveType.AREA_DATA);
			check(copy != null && "area data".equals(str(read(copy))),
					"with a sealed backup, the untouched copy is the backup's sealed bytes, not the live edit: "
					+ (copy == null ? "none" : str(read(copy))));

			write(new File(new File(r.entry, "files"), "a/0/1/4"), "area dat@");   //the backup rots
			ctrmap.WorkspaceSession fresh = Sessions.bare(new File(scratch, "ws-pristine-2"), dump,
					ctrmap.gamedef.GameType.ORAS);
			new File(scratch, "ws-pristine-2").mkdirs();
			String damaged = null;
			try {
				fresh.pristineArchive(ctrmap.gamedef.ArchiveType.AREA_DATA);
			} catch (IOException said) {
				damaged = said.getMessage();
			}
			check(damaged != null && damaged.contains("does not match what was sealed"),
					"and a backup that no longer matches what was sealed is refused, not read as absent: " + damaged);
		} finally {
			ctrmap.Workspace.install(null);
		}
	}

	/**
	 * A restore writes the game folder under the open session (F218). It passed no reload, so the
	 * session went on reading the old entry tables over the restored files, and every answer it had
	 * kept from them. Now a restore into the open game's folder re-reads its archives and empties
	 * what it kept, as a pack does; a restore into another folder leaves the open game alone.
	 */
	static void aRestoreRereadsTheOpenGame(File scratch) throws Exception {
		System.out.println("--- a restore into the open game re-reads it and empties what it kept (F218)");
		File dump = new File(scratch, "000400000011C406");
		buildFakeDump(dump);
		Vault.SealResult r = Vault.seal(dump, Vault.Scope.FULL_RAW, null);
		check(r.sealed, "control: the dump is backed up (" + r.problem + ")");
		File ws = new File(scratch, "ws-restore");
		ws.mkdirs();
		File elsewhere = new File(scratch, "another-game");
		elsewhere.mkdirs();
		ctrmap.WorkspaceSession other = new ctrmap.WorkspaceSession(new File(scratch, "ws-other"), elsewhere,
				ctrmap.gamedef.GameType.ORAS, null);
		ctrmap.WorkspaceSession session = Sessions.bare(ws, dump, ctrmap.gamedef.GameType.ORAS);
		ctrmap.formats.GameCache.Key<String> kept = new ctrmap.formats.GameCache.Key<>("an answer read from the game");
		try {
			session.cache().get(kept, () -> "read before the restore");
			other.cache().get(kept, () -> "read from another game");
			write(new File(dump, "a/0/1/3"), "edited");
			java.util.List<String> said = ctrmap.Ui.record(Integer.valueOf(javax.swing.JOptionPane.YES_OPTION));
			boolean did;
			try {
				did = ctrmap.vault.VaultUi.restoreOne(null, r.entry, "a/0/1/3", dump, other);
			} finally {
				ctrmap.Ui.stopRecording();
			}
			check(did && other.cache().holds(kept),
					"control: a restore handed a session over ANOTHER folder lands, and leaves that game's answers: " + said);
			write(new File(dump, "a/0/1/3"), "edited again");
			said = ctrmap.Ui.record(Integer.valueOf(javax.swing.JOptionPane.YES_OPTION));
			try {
				did = ctrmap.vault.VaultUi.restoreOne(null, r.entry, "a/0/1/3", dump, session);
			} finally {
				ctrmap.Ui.stopRecording();
			}
			check(did && !session.cache().holds(kept),
					"a restore of one archive into the open game empties what that game kept: " + said);

			session.cache().get(kept, () -> "read before the whole restore");
			write(new File(dump, "a/0/1/4"), "edited");
			said = ctrmap.Ui.record(Integer.valueOf(javax.swing.JOptionPane.YES_OPTION));
			try {
				did = ctrmap.vault.VaultUi.restoreEverything(null, r.entry, dump, session);
			} finally {
				ctrmap.Ui.stopRecording();
			}
			check(did && !session.cache().holds(kept), "and so does a restore of the whole game: " + said);

			String mf = new String(java.nio.file.Files.readAllBytes(new File("src/ctrmap/CtrmapMainframe.java").toPath()),
					java.nio.charset.StandardCharsets.UTF_8);
			String action = CollisionReadTest.body(SourceSeamTest.stripComments(mf), "private static void restoreFromVaultAction(");
			check(action != null && action.contains("VaultUi.restoreOne(frame, entry, String.valueOf(rel).trim(), dump, Workspace.session())")
					&& action.contains("VaultUi.restoreEverything(frame, entry, dump, Workspace.session())"),
					"and the window hands both restores the session open over that folder");
		} finally {
			ctrmap.Workspace.install(null);
		}
	}

	static String lastOf(java.util.List<String> xs) {
		return xs.isEmpty() ? "" : xs.get(xs.size() - 1);
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
}

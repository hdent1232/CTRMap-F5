package ctrmap.tests;

import ctrmap.ModDeployer;
import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * Characterization of {@link ModDeployer}, which copies the archives the user
 * actually changed into an emulator's LayeredFS mod folder and switches that
 * folder on and off.
 *
 * <p>WHY THIS EXISTS. Deploy is the last step before the game is played, and
 * every way it can be wrong is quiet. Ship an archive nobody edited and the
 * player gets CTRMap's LZ11 in place of Nintendo's for no reason; ship none of
 * them and the edits simply are not there, with a dialog saying the deploy
 * succeeded. The decision is a content diff against the pristine snapshot, and
 * before this file the only line of the class the battery had ever run was a
 * constant.
 *
 * <p>Everything happens inside scratch space: a throwaway copy of the dump
 * ({@link ScratchGame}) and a mod root under the temp folder. The owner's game
 * folder, workspace and real Azahar mods directory are never written to - the
 * one thing asked of the real environment is the SHAPE of the Azahar path, and
 * that is a string comparison.
 *
 * <p>What is pinned:
 * <ul>
 * <li>A pack recompresses every archive, so the container bytes of all eight
 * change; deploy must still ship only the one whose DECOMPRESSED contents moved.
 * That is the whole point of the class and it is asserted by bytes.</li>
 * <li>With nothing changed, deploy creates no romfs folder at all.</li>
 * <li>Parking is a move to a sibling folder and is reversible; nothing is
 * deleted, and a move that would overwrite something refuses.</li>
 * <li>{@link ModDeployer#garcContentsEqual} calls two UNREADABLE files equal -
 * see the suspected-defect note on {@link #contentDiffIsByDecompressedBytes}.</li>
 * </ul>
 *
 * Usage: java ctrmap.tests.ModDeployerTest &lt;pristine-dump-root&gt;
 */
public class ModDeployerTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		//needs no dump: the game is two archives written by hand
		anArchiveBackToRetailIsTakenOutOfTheMod();
		whereTheModGoes();
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			Skip.section("no dump at " + dump.getAbsolutePath());
			System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
			System.exit(fails == 0 ? 0 : 1);
		}

		whatCountsAsWritable();

		ScratchGame.open(dump);
		List<String> refused = Workspace.snapshotOriginals();
		check(refused.isEmpty(), "the pristine snapshot was taken whole (refused: " + refused + ")");

		File modRoot = new File(Scratch.dir("ctrmap_moddeploy"), "mods" + File.separator + "000400000011C400");
		deployShipsNothingWhenNothingChanged(modRoot);
		deployShipsOnlyTheEditedArchive(modRoot);
		parkingIsAReversibleMove(modRoot);
		isDeployedNeedsADirectoryWithFilesInIt(modRoot);
		contentDiffIsByDecompressedBytes();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The two archive lists are a contract with {@link Workspace#snapshotOriginals}
	 * (which refuses to complete a snapshot for a game already in use), so their
	 * membership is not free to drift.
	 */
	private static void whatCountsAsWritable() {
		check(ModDeployer.MODDABLE.length == 16,
				"16 archives are deployed by content diff, got " + ModDeployer.MODDABLE.length);
		check(ModDeployer.MODDABLE[0] == ArchiveType.ZONE_DATA
				&& ModDeployer.MODDABLE[1] == ArchiveType.GAMETEXT
				&& ModDeployer.MODDABLE[2] == ArchiveType.STORYTEXT,
				"the diffed list starts ZoneData, GameText, StoryText");
		check(ModDeployer.MODDABLE_IN_PLACE.length == 1
				&& ModDeployer.MODDABLE_IN_PLACE[0] == ArchiveType.ITEM_DATA,
				"ItemData is the only archive written in place, and is deployed off its own baseline");
		List<ArchiveType> all = ModDeployer.allWritableArchives();
		check(all.size() == 17, "a whole backup must cover " + all.size() + " archives");
		check(all.subList(0, 16).equals(Arrays.asList(ModDeployer.MODDABLE))
				&& all.get(16) == ArchiveType.ITEM_DATA,
				"the whole-backup list is the diffed list followed by the in-place one");
		List<ArchiveType> again = ModDeployer.allWritableArchives();
		again.clear();
		check(ModDeployer.allWritableArchives().size() == 17,
				"the list handed out is a copy; clearing it does not empty the next one");
	}

	/**
	 * F021: deploy only ever added. An archive shipped once and since put back to retail was
	 * reported "unchanged, skipped" while the modified copy shipped last time stayed in the mod,
	 * and the game kept loading it. Now that copy is taken out of the load path - moved aside,
	 * never deleted - and said.
	 */
	private static void anArchiveBackToRetailIsTakenOutOfTheMod() throws Exception {
		System.out.println("--- an archive back to retail is taken out of the mod, and kept beside it");
		Workspace.reset();
		File ws = Scratch.dir("ctrmap_deploy_retail_ws");
		File game = Scratch.dir("ctrmap_deploy_retail_game");
		try {
			ctrmap.tests.Sessions.bare(ws, game, GameType.ORAS);
			Workspace.GAMEDIR_PATH = game.getAbsolutePath();
			String rel = Workspace.getArchivePath(ArchiveType.ZONE_DATA, Workspace.game());
			File live = new File(Workspace.GAMEDIR_PATH + rel);
			File orig = new File(Workspace.originalSnapshotDir().getAbsolutePath() + rel);
			live.getParentFile().mkdirs();
			orig.getParentFile().mkdirs();
			HandedGameTest.writeGarc(live, "zone0".getBytes("US-ASCII"), "master".getBytes("US-ASCII"));
			HandedGameTest.writeGarc(orig, "zone0".getBytes("US-ASCII"), "master".getBytes("US-ASCII"));
			File modRoot = new File(Scratch.dir("ctrmap_deploy_retail_mods"), "mods" + File.separator + "000400000011C400");
			File shipped = new File(new File(modRoot, "romfs").getAbsolutePath() + rel);
			shipped.getParentFile().mkdirs();
			byte[] old = "an edited zone archive an earlier deploy shipped".getBytes("US-ASCII");
			Files.write(shipped.toPath(), old);

			ModDeployer.Result r = ModDeployer.deploy(modRoot, null);
			check(r.deployed.isEmpty() && r.unchanged == 1, "the archive matches retail, so nothing is shipped ("
					+ r.deployed + ", " + r.unchanged + " unchanged)");
			check(!shipped.exists(), "and the copy an earlier deploy left in the mod is no longer in the load path");
			check(r.removed.size() == 1 && r.removed.get(0).startsWith(rel), "and the result says it was taken out " + r.removed);
			File kept = new File(new File(ModDeployer.removedRoot(modRoot), "romfs").getAbsolutePath() + rel);
			check(kept.isFile() && Arrays.equals(Files.readAllBytes(kept.toPath()), old),
					"and it was moved aside whole, not deleted: " + kept);
			check(!kept.getAbsolutePath().startsWith(modRoot.getAbsolutePath() + File.separator),
					"outside the mod folder the emulator loads");

			ModDeployer.Result again = ModDeployer.deploy(modRoot, null);
			check(again.removed.isEmpty() && again.skipped.isEmpty(), "a second deploy has nothing left to take out " + again.removed);

			Files.write(shipped.toPath(), "a second stale copy".getBytes("US-ASCII"));
			ModDeployer.deploy(modRoot, null);
			check(kept.isFile() && Arrays.equals(Files.readAllBytes(kept.toPath()), old)
					&& new File(kept.getPath() + ".1").isFile(),
					"and a second copy set aside never overwrites the first");

			HandedGameTest.writeGarc(live, "zone0 edited".getBytes("US-ASCII"), "master".getBytes("US-ASCII"));
			ModDeployer.Result edited = ModDeployer.deploy(modRoot, null);
			check(edited.deployed.equals(Arrays.asList(rel)) && edited.removed.isEmpty() && shipped.isFile(),
					"an archive that differs from retail is still shipped, and nothing is taken out " + edited.deployed);
		} finally {
			Workspace.reset();
			Workspace.GAMEDIR_PATH = null;
		}
	}

	/** Nothing edited: nothing copied, and not even a romfs folder made. */
	private static void deployShipsNothingWhenNothingChanged(File modRoot) {
		ModDeployer.Result r = ModDeployer.deploy(modRoot, null);
		check(r.deployed.isEmpty(), "an untouched game deploys nothing (" + r.deployed + ")");
		check(r.unchanged == 8, r.unchanged + " archives were found unchanged (the 8 the scratch game holds)");
		check(r.skipped.isEmpty(), "nothing was skipped (" + r.skipped + ")");
		check(!r.codeIpsDeployed, "no code patch was asked for, so none was written");
		check(r.modRoot == modRoot, "the result names the folder it deployed to");
		check(!new File(modRoot, "romfs").exists(),
				"deploying nothing leaves no romfs folder behind");
	}

	/**
	 * One GameText entry edited, then a pack - which rewrites the container bytes
	 * of all eight archives. Only the edited one may be shipped, and the shipped
	 * file must be the live archive byte for byte.
	 */
	private static void deployShipsOnlyTheEditedArchive(File modRoot) throws Exception {
		int entry = Workspace.getArchive(ArchiveType.GAMETEXT).length - 1;
		File staged = Workspace.getWorkspaceFile(ArchiveType.GAMETEXT, entry);
		byte[] edited = Files.readAllBytes(staged.toPath());
		//the last byte of the string data: a different character, same layout, so
		//nothing downstream sees anything malformed
		edited[edited.length - 1] ^= 0x01;
		Files.write(staged.toPath(), edited);
		Workspace.addPersist(staged);

		File[] live = new File[]{Workspace.session().archiveFile(ArchiveType.ZONE_DATA), Workspace.session().archiveFile(ArchiveType.GAMETEXT), Workspace.session().archiveFile(ArchiveType.FIELD_DATA),
			Workspace.session().archiveFile(ArchiveType.MAP_MATRIX), Workspace.session().archiveFile(ArchiveType.AREA_DATA), Workspace.session().archiveFile(ArchiveType.BUILDING_MODELS),
			Workspace.session().archiveFile(ArchiveType.NPC_REGISTRIES), Workspace.session().archiveFile(ArchiveType.MOVE_MODELS)};
		//the GARC header and its entry table sit at the front of the file, so a
		//repack with different compression moves these bytes. Read rather than
		//stat: faking a timestamp to detect a rewrite trips the archive's own
		//staleness guard and fills the battery log with its warning.
		byte[][] headBefore = new byte[live.length][];
		for (int i = 0; i < live.length; i++) {
			headBefore[i] = head(live[i]);
		}
		Workspace.packArchives(new Workspace.PackProgress() {
			@Override
			public void at(int percent, String what) {
			}
		});
		int rewritten = 0;
		for (int i = 0; i < live.length; i++) {
			if (!Arrays.equals(headBefore[i], head(live[i]))) {
				rewritten++;
			}
		}
		check(rewritten > 1, rewritten + " of the 8 archive containers were rewritten by the pack"
				+ " - which is why deploy diffs decompressed contents and not files");

		String itemRel = anEditedItemArchive();

		File ips = new File(Scratch.dir("ctrmap_ips"), "code.ips");
		Files.write(ips.toPath(), "PATCHEOF".getBytes("UTF-8"));
		ModDeployer.Result r = ModDeployer.deploy(modRoot, ips);

		String rel = Workspace.getArchivePath(ArchiveType.GAMETEXT, Workspace.game());
		check(r.deployed.size() == 2 && rel.equals(r.deployed.get(0)) && itemRel.equals(r.deployed.get(1)),
				"only the edited archives were shipped, the in-place one last: " + r.deployed);
		File shippedItems = new File(new File(modRoot, "romfs").getAbsolutePath() + itemRel);
		check(shippedItems.isFile() && Arrays.equals(Files.readAllBytes(shippedItems.toPath()),
				Files.readAllBytes(new File(Workspace.GAMEDIR_PATH + itemRel).toPath())),
				"the in-place archive was shipped from the live game, byte for byte");
		check(r.unchanged == 7, r.unchanged + " archives were recognised as unchanged despite being repacked");
		check(r.skipped.isEmpty(), "nothing was skipped (" + r.skipped + ")");

		File shipped = new File(new File(modRoot, "romfs").getAbsolutePath() + rel);
		check(shipped.isFile(), "the archive landed at romfs" + rel.replace('/', File.separatorChar));
		check(Arrays.equals(Files.readAllBytes(shipped.toPath()),
				Files.readAllBytes(Workspace.session().archiveFile(ArchiveType.GAMETEXT).toPath())),
				"the shipped archive is the live archive, byte for byte");
		for (int i = 0; i < live.length; i++) {
			if (live[i] == Workspace.session().archiveFile(ArchiveType.GAMETEXT)) {
				continue;
			}
			String other = relOf(live[i]);
			if (other != null) {
				check(!new File(new File(modRoot, "romfs").getAbsolutePath() + other).exists(),
						"an unedited archive was not shipped (" + other + ")");
			}
		}

		File ipsOut = new File(new File(modRoot, "exefs"), "code.ips");
		check(r.codeIpsDeployed && ipsOut.isFile() && ipsOut.length() == 8,
				"the code patch was copied to exefs/code.ips (" + ipsOut.length() + " bytes)");

		//A patch file that is not there is not an error and is not reported as
		//done. Asked with the game folder pointed at an empty directory, so no
		//archive exists to compare and the deploy costs nothing: the whole point
		//of this call is the code-patch branch.
		//The deployer follows the OPEN SESSION, not the path setting: repointing
		//only the setting left the session on the scratch game, whose edited item
		//archive then shipped. The empty folder must be the session as well.
		String heldPath = Workspace.GAMEDIR_PATH;
		WorkspaceSession heldSession = Workspace.session();
		try {
			File noGame = Scratch.dir("ctrmap_nogame");
			Workspace.GAMEDIR_PATH = noGame.getAbsolutePath();
			Sessions.bare(heldSession.workspaceDir(), noGame, GameType.ORAS);
			ModDeployer.Result r2 = ModDeployer.deploy(modRoot, new File(ips.getParentFile(), "absent.ips"));
			check(!r2.codeIpsDeployed, "a code patch that does not exist is passed over quietly");
			check(r2.deployed.isEmpty() && r2.unchanged == 0 && r2.skipped.isEmpty(),
					"a game folder with no archives in it deploys nothing and reports nothing skipped");
		} finally {
			Workspace.GAMEDIR_PATH = heldPath;
			Workspace.install(heldSession);
		}
	}

	/**
	 * Stands up an item archive the editor has "written in place", and returns
	 * its RomFS-relative path.
	 *
	 * <p>ItemData is deliberately NOT part of the pristine snapshot's contract,
	 * so deploy diffs it against the pre-edit copy the editor takes on its first
	 * write instead. Nothing here parses the archive - the whole decision is a
	 * byte compare of two files - so a few bytes standing in for one is enough to
	 * drive the branch, and the branch is otherwise unreachable from a scratch
	 * game that has never opened the item editor.
	 *
	 * <p>The baseline's file NAME is ItemTable's own private constant, repeated
	 * here on purpose: what is being pinned is that deploy and the item editor
	 * agree about where that copy lives.
	 */
	private static String anEditedItemArchive() throws IOException {
		String rel = Workspace.getArchivePath(ArchiveType.ITEM_DATA, Workspace.game());
		check(rel != null, "ORAS has an ItemData archive at " + rel);
		File live = new File(Workspace.GAMEDIR_PATH + rel);
		live.getParentFile().mkdirs();
		File baseline = new File(ctrmap.formats.pokedata.ItemTable.baselineDir(Workspace.session()), "itemdata.garc");
		baseline.getParentFile().mkdirs();

		byte[] pristine = new byte[]{'C', 'R', 'A', 'G', 0, 1, 2, 3};
		Files.write(live.toPath(), pristine);
		Files.write(baseline.toPath(), pristine);
		check(!ctrmap.formats.pokedata.ItemTable.changedSinceBaseline(Workspace.session()),
				"an item archive matching its pre-edit copy counts as unedited");

		byte[] edited = new byte[]{'C', 'R', 'A', 'G', 0, 1, 2, 9};
		Files.write(live.toPath(), edited);
		check(ctrmap.formats.pokedata.ItemTable.changedSinceBaseline(Workspace.session()),
				"an item archive that differs from its pre-edit copy counts as edited");
		return rel;
	}

	/** The first 64 KiB of a file: enough of a GARC to cover its header and entry table. */
	private static byte[] head(File f) throws IOException {
		java.io.InputStream in = new java.io.FileInputStream(f);
		try {
			byte[] b = new byte[65536];
			int got = 0;
			while (got < b.length) {
				int n = in.read(b, got, b.length - got);
				if (n < 0) {
					break;
				}
				got += n;
			}
			return Arrays.copyOf(b, got);
		} finally {
			in.close();
		}
	}

	private static String relOf(File live) {
		for (ArchiveType t : ModDeployer.MODDABLE) {
			String rel = Workspace.getArchivePath(t, Workspace.game());
			if (rel != null && new File(Workspace.GAMEDIR_PATH + rel).equals(live)) {
				return rel;
			}
		}
		return null;
	}

	/** Off is a move out of the load path, and on is the move back. Nothing is deleted. */
	private static void parkingIsAReversibleMove(File modRoot) throws IOException {
		check(ModDeployer.parkedRoot(modRoot).equals(
				new File(new File(modRoot.getParentFile().getParentFile(), "mods_disabled"), modRoot.getName())),
				"a folder under mods/ parks in a mods_disabled/ sibling: " + ModDeployer.parkedRoot(modRoot));
		File odd = new File(Scratch.dir("ctrmap_oddroot"), "somewhere" + File.separator + "id");
		check(ModDeployer.parkedRoot(odd).equals(new File(odd.getParentFile(), "id__off")),
				"a folder anywhere else parks beside itself with __off: " + ModDeployer.parkedRoot(odd));

		check(ModDeployer.isDeployed(modRoot) && !ModDeployer.isParked(modRoot),
				"before parking the mod is deployed and not parked");
		File shipped = new File(new File(modRoot, "romfs").getAbsolutePath()
				+ Workspace.getArchivePath(ArchiveType.GAMETEXT, Workspace.game()));
		long shippedSize = shipped.length();

		File parked = ModDeployer.disable(modRoot);
		check(parked.equals(ModDeployer.parkedRoot(modRoot)) && parked.isDirectory(),
				"disable moved the mod to " + parked);
		check(!modRoot.exists(), "the mod folder is gone from the emulator's load path");
		check(!ModDeployer.isDeployed(modRoot) && ModDeployer.isParked(modRoot),
				"a parked mod reports parked, not deployed");
		File parkedShipped = new File(new File(parked, "romfs").getAbsolutePath()
				+ Workspace.getArchivePath(ArchiveType.GAMETEXT, Workspace.game()));
		check(parkedShipped.isFile() && parkedShipped.length() == shippedSize,
				"the parked copy still holds the deployed archive (" + parkedShipped.length() + " bytes)");

		File back = ModDeployer.enable(modRoot);
		check(back.equals(modRoot) && modRoot.isDirectory() && !parked.exists(),
				"enable moved it back to " + back);
		check(ModDeployer.isDeployed(modRoot) && !ModDeployer.isParked(modRoot),
				"the restored mod reports deployed again");
		check(shipped.isFile() && shipped.length() == shippedSize,
				"the deployed archive came back with it");

		try {
			ModDeployer.disable(new File(modRoot, "no_such_child"));
			check(false, "disabling something that is not there refuses");
		} catch (IOException ex) {
			check(ex.getMessage().startsWith("not there: "),
					"disabling something that is not there refuses: " + ex.getMessage());
		}
		ModDeployer.disable(modRoot);
		modRoot.mkdirs();
		new File(modRoot, "romfs").mkdirs();
		try {
			ModDeployer.disable(modRoot);
			check(false, "a park that would overwrite an existing parked copy refuses");
		} catch (IOException ex) {
			check(ex.getMessage().startsWith("already exists, refusing to overwrite: "),
					"a park that would overwrite an existing parked copy refuses: " + ex.getMessage());
		}
		check(ModDeployer.parkedRoot(modRoot).isDirectory() && modRoot.isDirectory(),
				"the refusal left both folders where they were");
	}

	private static void isDeployedNeedsADirectoryWithFilesInIt(File modRoot) throws IOException {
		check(!ModDeployer.isDeployed(null), "no folder at all is not deployed");
		check(!ModDeployer.isDeployed(new File(modRoot, "never_made")), "a missing folder is not deployed");
		check(!ModDeployer.isDeployed(Scratch.dir("ctrmap_emptymod")), "an empty folder is not deployed");
		File file = new File(Scratch.dir("ctrmap_notadir"), "plain");
		Files.write(file.toPath(), new byte[]{1});
		check(!ModDeployer.isDeployed(file), "a plain file is not a deployed mod");
		check(!ModDeployer.isParked(null), "a null mod root is not parked either");
	}

	/**
	 * The diff is over decompressed entries, so the same archive equals itself and
	 * two different archives do not.
	 *
	 * <p>FIXED, AND IN TWO PLACES, because an archive can be unreadable in two ways.
	 * {@link ctrmap.formats.garc.GARC} used to log a parse failure and hand back an archive of
	 * length 0, so two unreadable files had the same entries and were reported "unchanged" -
	 * the deploy shipping nothing for them without a word. A file it can read NOTHING from now
	 * refuses from the constructor; a file it can read PART of still opens, because the entries
	 * before the cut are worth having, and {@code garcContentsEqual} asks whether either side
	 * was cut. Both halves are asserted below; the first plant proved only the first.
	 */
	private static void contentDiffIsByDecompressedBytes() throws IOException {
		File a = Workspace.session().archiveFile(ArchiveType.MAP_MATRIX);
		File b = Workspace.session().archiveFile(ArchiveType.AREA_DATA);
		check(ModDeployer.garcContentsEqual(a, a), "an archive equals itself");
		check(!ModDeployer.garcContentsEqual(a, b), "two different archives are not equal");
		File tmp = Scratch.dir("ctrmap_notgarc");
		File junk1 = new File(tmp, "junk1");
		File junk2 = new File(tmp, "junk2");
		Files.write(junk1.toPath(), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
		Files.write(junk2.toPath(), new byte[]{9, 9, 9, 9});
		//RETARGETED, and the defect it pinned is gone. It recorded that two files which are
		//not archives at all compared EQUAL - pinned, not endorsed - because a half-parsed
		//GARC reported zero entries and two of those matched. Equal here means "already
		//shipped, skip it", so an archive the deployer could not read was precisely the one
		//it decided not to deploy.
		//AND THE HALF THAT IS NOT ABOUT THROWING. The case above is answered by the
		//constructor refusing a file it could read NOTHING from. A file it could read PART of
		//is the other shape, and it does not throw at all - deliberately, because the entries
		//before the cut are whole and worth reading. Two archives cut at the same point have
		//matching readable entries and null past the cut, and Arrays.equals(null, null) is
		//true - so they compare identical, and identical HERE means "already shipped, skip
		//it". The archive the deployer could not fully read would be precisely the one it
		//decided not to ship. Measured by planting: with the truncation refusal removed, the
		//non-archive case above still passed on the constructor's throw, so this assertion is
		//the only thing standing between that guard and being decoration.
		File whole = a;
		if (whole != null && whole.isFile()) {
			byte[] all = Files.readAllBytes(whole.toPath());
			File cutA = new File(tmp, "cutA.garc");
			File cutB = new File(tmp, "cutB.garc");
			Files.write(cutA.toPath(), java.util.Arrays.copyOf(all, all.length / 2));
			Files.write(cutB.toPath(), java.util.Arrays.copyOf(all, all.length / 2));
			check(!ModDeployer.garcContentsEqual(cutA, cutB),
					"two archives cut at the SAME point are not 'already shipped' - what was"
					+ " never read cannot be evidence that it matches");
			check(ModDeployer.garcContentsEqual(whole, whole),
					"...while an archive that reads whole still compares equal to itself,"
					+ " or this refuses everything and deploys the world");
		} else {
			Skip.section("no readable archive to cut - the"
					+ " truncated-vs-truncated case needs a real one");
		}

		check(!ModDeployer.garcContentsEqual(junk1, junk2),
				"two files that are not archives at all are NOT equal - a file that cannot be"
				+ " read is not evidence that it matches anything");
		check(!ModDeployer.garcContentsEqual(a, junk1),
				"a real archive and an unreadable one compare unequal, so the unreadable one gets shipped");
	}

	/**
	 * The folder a mod is deployed into, and the title it is named for: the one the dump says, or a
	 * question - never the version pair's first, which sent an Alpha Sapphire or Y mod into Omega
	 * Ruby's or X's folder, where it never loads (F238). Needs no dump: the folders are made here.
	 */
	private static void whereTheModGoes() throws IOException {
		System.out.println("--- the title a mod is named for is the dump's own (F238)");
		String appdata = System.getenv("APPDATA");
		if (appdata == null) {
			Skip.section("no APPDATA on this machine, so the Azahar path cannot be checked");
		} else {
			File root = ModDeployer.azaharModRoot("0004000000123456");
			check(root != null && root.equals(new File(appdata + File.separator + "Azahar"
					+ File.separator + "load" + File.separator + "mods" + File.separator + "0004000000123456")),
					"the Azahar mod folder for a title is " + root);
		}

		String heldPath = Workspace.GAMEDIR_PATH;
		WorkspaceSession heldSession = Workspace.session();
		File base = Scratch.dir("ctrmap_titles");
		try {
			File named = new File(base, "abcdef0123456789");
			File plain = new File(base, "RomFS");
			File inTitle = new File(new File(base, "000400000011C500"), "romfs");
			File besideExheader = new File(new File(base, "my as dump"), "romfs");
			for (File d : new File[]{named, plain, inTitle, besideExheader}) {
				d.mkdirs();
			}
			byte[] exheader = new byte[0x800];
			long alphaSapphire = 0x000400000011C500L;
			for (int i = 0; i < 8; i++) {
				exheader[0x200 + i] = (byte) (alphaSapphire >>> (8 * i));
			}
			Files.write(new File(besideExheader.getParentFile(), "exheader.bin").toPath(), exheader);

			Sessions.bare(base, plain, GameType.ORAS);
			Workspace.GAMEDIR_PATH = named.getPath();
			check("ABCDEF0123456789".equals(ModDeployer.guessTitleId()),
					"control: a RomFS folder named like a title id is used as one, upper-cased: " + ModDeployer.guessTitleId());
			Workspace.GAMEDIR_PATH = inTitle.getPath();
			check("000400000011C500".equals(ModDeployer.guessTitleId()),
					"a romfs inside a folder named by Alpha Sapphire's id is Alpha Sapphire: " + ModDeployer.guessTitleId());
			Workspace.GAMEDIR_PATH = besideExheader.getPath();
			check("000400000011C500".equals(ModDeployer.guessTitleId()),
					"and so is one whose ExHeader says so, in a folder named anything: " + ModDeployer.guessTitleId());
			Workspace.GAMEDIR_PATH = plain.getPath();
			check(ModDeployer.guessTitleId() == null,
					"a dump that does not say is not Omega Ruby's - it was 000400000011C400 for every one: " + ModDeployer.guessTitleId());
			String[] asked = ModDeployer.titleChoices(null);
			check(asked.length == 3 && asked[0].equals(ModDeployer.PICK_VERSION)
					&& "000400000011C400".equals(ModDeployer.titleIdIn(asked[1])) && "000400000011C500".equals(ModDeployer.titleIdIn(asked[2])),
					"so Deploy asks, offering both versions with neither picked: " + Arrays.toString(asked));
			check(ModDeployer.titleIdIn(asked[0]) == null && ModDeployer.azaharModRoot(ModDeployer.titleIdIn(asked[0])) == null,
					"and the question itself names no folder");
			check(Arrays.equals(ModDeployer.titleChoices("000400000011C500"), new String[]{"000400000011C500"}),
					"while a title the dump said is the only choice");
			Sessions.bare(base, plain, GameType.XY);
			asked = ModDeployer.titleChoices(ModDeployer.guessTitleId());
			check(ModDeployer.guessTitleId() == null && asked.length == 3 && asked[2].startsWith("0004000000055E00"),
					"an X/Y dump that does not say is asked about too, Y offered: " + Arrays.toString(asked));
			Workspace.GAMEDIR_PATH = null;
			check(ModDeployer.guessTitleId() == null, "and with no game folder at all it names no title");
			String window = new String(Files.readAllBytes(new File("src/ctrmap/CtrmapMainframe.java").toPath()), "UTF-8");
			check(window.contains("new javax.swing.JComboBox<>(ModDeployer.titleChoices(titleId))")
					&& !window.contains("new javax.swing.JTextField(titleId)"),
					"the Deploy dialog offers those choices, not a box pre-filled with one id");
		} finally {
			Workspace.GAMEDIR_PATH = heldPath;
			Workspace.install(heldSession);
			Scratch.deleteTree(base);
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

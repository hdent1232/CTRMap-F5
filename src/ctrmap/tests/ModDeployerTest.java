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
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump.getAbsolutePath());
			System.out.println("ALL PASS");
			return;
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
		whereTheModGoes();

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
		String heldPath = Workspace.GAMEDIR_PATH;
		try {
			Workspace.GAMEDIR_PATH = Scratch.dir("ctrmap_nogame").getAbsolutePath();
			ModDeployer.Result r2 = ModDeployer.deploy(modRoot, new File(ips.getParentFile(), "absent.ips"));
			check(!r2.codeIpsDeployed, "a code patch that does not exist is passed over quietly");
			check(r2.deployed.isEmpty() && r2.unchanged == 0 && r2.skipped.isEmpty(),
					"a game folder with no archives in it deploys nothing and reports nothing skipped");
		} finally {
			Workspace.GAMEDIR_PATH = heldPath;
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
		File baseline = new File(ctrmap.formats.pokedata.ItemTable.baselineDir(), "itemdata.garc");
		baseline.getParentFile().mkdirs();

		byte[] pristine = new byte[]{'C', 'R', 'A', 'G', 0, 1, 2, 3};
		Files.write(live.toPath(), pristine);
		Files.write(baseline.toPath(), pristine);
		check(!ctrmap.formats.pokedata.ItemTable.changedSinceBaseline(),
				"an item archive matching its pre-edit copy counts as unedited");

		byte[] edited = new byte[]{'C', 'R', 'A', 'G', 0, 1, 2, 9};
		Files.write(live.toPath(), edited);
		check(ctrmap.formats.pokedata.ItemTable.changedSinceBaseline(),
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
	 * <p>SUSPECTED DEFECT, PINNED NOT FIXED: two files that are not GARCs at all
	 * compare EQUAL. {@link ctrmap.formats.garc.GARC} logs the parse failure and
	 * hands back an archive of length 0, and two empty archives have the same
	 * entries, so an unreadable live archive and an unreadable snapshot are
	 * reported "unchanged" and the deploy ships nothing for them without a word.
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
		check(ModDeployer.garcContentsEqual(junk1, junk2),
				"two files that are not archives at all compare EQUAL (pinned, not endorsed)");
		check(!ModDeployer.garcContentsEqual(a, junk1),
				"a real archive and an unreadable one compare unequal, so the unreadable one gets shipped");
	}

	/** The folder a mod is deployed into, and the title it is named for. */
	private static void whereTheModGoes() {
		String appdata = System.getenv("APPDATA");
		if (appdata == null) {
			System.out.println("  skip: no APPDATA on this machine, so the Azahar path cannot be checked");
		} else {
			File root = ModDeployer.azaharModRoot("0004000000123456");
			check(root != null && root.equals(new File(appdata + File.separator + "Azahar"
					+ File.separator + "load" + File.separator + "mods" + File.separator + "0004000000123456")),
					"the Azahar mod folder for a title is " + root);
		}

		String heldPath = Workspace.GAMEDIR_PATH;
		//the open game used to be a static this test assigned; it is a session
		//now, so "the open game is XY" is said by installing an XY session over
		//the same folders and putting the scratch one back afterwards
		WorkspaceSession heldSession = Workspace.session();
		try {
			Workspace.GAMEDIR_PATH = "C:" + File.separator + "dumps" + File.separator + "abcdef0123456789";
			check("ABCDEF0123456789".equals(ModDeployer.guessTitleId()),
					"a RomFS folder named like a title id is used as one, upper-cased: "
					+ ModDeployer.guessTitleId());
			Workspace.GAMEDIR_PATH = "C:" + File.separator + "dumps" + File.separator + "my oras dump";
			Sessions.bare(heldSession.workspaceDir(), heldSession.gameDir(), GameType.ORAS);
			check("000400000011C400".equals(ModDeployer.guessTitleId()),
					"a folder named anything else falls back to the stock Omega Ruby id: "
					+ ModDeployer.guessTitleId());
			Sessions.bare(heldSession.workspaceDir(), heldSession.gameDir(), GameType.XY);
			check("0004000000055D00".equals(ModDeployer.guessTitleId()),
					"and to the stock X id for XY: " + ModDeployer.guessTitleId());
			Workspace.GAMEDIR_PATH = null;
			check("0004000000055D00".equals(ModDeployer.guessTitleId()),
					"with no game folder at all it still names a title: " + ModDeployer.guessTitleId());
		} finally {
			Workspace.GAMEDIR_PATH = heldPath;
			Workspace.install(heldSession);
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

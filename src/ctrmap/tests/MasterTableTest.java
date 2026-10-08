package ctrmap.tests;

import ctrmap.MasterTable;
import ctrmap.Workspace;
import ctrmap.ZoneResource;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * The master zone-header table is read, asked and written in one place, by one policy (F013).
 *
 * <p>WHY THIS SUITE EXISTS. Reading the table was written out eleven times with four answers to
 * what to do when it was the wrong size: two sharer counts answered "1 sharer" when nothing right
 * was left, two repair passes refused, one helper had no check at all and became the refusal
 * "null", the texture guard took any multiple of a row as the table and answered "nobody" when it
 * could not read one, and the zone save wrote its row at index * 0x38 into whatever file was there.
 * {@link MasterTable#choose} is the one policy and this suite pins every branch of it; the
 * questions asked of the table ({@code using}, {@code sharing}) and the writes into it
 * ({@code set}, {@code setRow}) are pinned the same way.
 *
 * <p>ORDER: needs no dump and no display. The game is a {@link FakeGameFiles} under {@link Scratch},
 * its zone archive built by {@link HandedGameTest#zoneGame}.
 *
 * Usage: java ctrmap.tests.MasterTableTest
 */
public class MasterTableTest {

	static int fails = 0;

	static final int ROW = MasterTable.ROW;

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			thePolicyKeepsEveryEditAndGuessesNothing();
			aDetachedTableIsWholeRowsAndIsNeverWritten();
			theSharersNeverIncludeTheZoneAsked();
			repointingMovesOneIdInOneRow();
			aRowIsReplacedWholeOrNotAtAll();
			theGameIsReadByThePolicyAndWrittenToIt();
			noNewCodeIndexesTheRowsByHand(new File(args.length > 0 ? args[0] : "src"));
			noCodeSpellsTheZoneArchivesTail(new File(args.length > 0 ? args[0] : "src"));
		} finally {
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------------------ 1. the policy
	static void thePolicyKeepsEveryEditAndGuessesNothing() throws Exception {
		System.out.println("--- which bytes are the table: the workspace's, the archive's, or a refusal");
		byte[] right = rows(7, 7, 9, 7);
		byte[] archive = rows(1, 2, 3, 4);
		check(MasterTable.choose(right, false, archive, 4) == right,
				"a right-sized workspace copy is the table");
		check(MasterTable.choose(right, true, archive, 4) == right,
				"and so is a right-sized staged edit");

		String staged = refusal(rows(9, 9, 9, 9, 9), true, archive, 4);
		check(staged != null && staged.contains("it is an edit"),
				"a staged edit of the wrong size is refused, never replaced with the archive's copy: " + staged);
		check(refusal(null, true, archive, 4) != null,
				"and so is a staged edit that holds nothing");

		byte[] fromArchive = MasterTable.choose(rows(9, 9, 9, 9, 9), false, archive, 4);
		check(Arrays.equals(fromArchive, archive),
				"a stale extraction of the wrong size, which is no edit, gives way to the archive's copy");
		check(Arrays.equals(MasterTable.choose(null, false, archive, 4), archive),
				"and so does no workspace file at all");

		String neither = refusal(new byte[ROW * 3 + 5], false, rows(1, 2, 3), 4);
		check(neither != null && neither.contains("Neither"),
				"neither copy the right size is refused, not read as some other count of zones: " + neither);
		check(refusal(new byte[0], false, null, 4) != null,
				"and an archive with no table at all is refused too");
	}

	// ------------------------------------------------------------ 2. detached rows
	static void aDetachedTableIsWholeRowsAndIsNeverWritten() throws Exception {
		System.out.println("--- a table read from elsewhere is whole rows, and has nowhere to be written");
		check(detachRefused(null, 0), "missing rows are refused");
		check(detachRefused(new byte[7], 0), "a runt is refused");
		check(detachRefused(new byte[ROW * 3 + 5], 3), "a trailing partial row is refused, not read past");
		check(detachRefused(rows(1, 2, 3), 4), "rows that are not the count asked for are refused");
		MasterTable t = MasterTable.detached(rows(1, 2, 3), 3);
		check(t.zones() == 3, "whole rows are a table of that many zones (" + t.zones() + ")");
		try {
			t.write();
			check(false, "a detached table refuses to be written, in words (it wrote)");
		} catch (IOException said) {
			check(said.getMessage().contains("nowhere to be written"),
					"a detached table refuses to be written, in words: " + said.getMessage());
		} catch (RuntimeException crash) {
			check(false, "a detached table refuses to be written, in words (it threw " + crash + ")");
		}
	}

	// ------------------------------------------------------------ 3. who shares
	static void theSharersNeverIncludeTheZoneAsked() throws Exception {
		System.out.println("--- who shares a resource: every other zone naming it, never the zone asked");
		MasterTable t = MasterTable.detached(rows(7, 7, 9, 7, 3), 5);
		check(t.sharing(ZoneResource.AREA, 0).equals(Arrays.asList(1, 3)),
				"zone 0 shares area 7 with zones 1 and 3 " + t.sharing(ZoneResource.AREA, 0));
		check(t.sharing(ZoneResource.AREA, 2).isEmpty(),
				"zone 2's area is its own " + t.sharing(ZoneResource.AREA, 2));
		int selfNamed = 0;
		for (int z = 0; z < t.zones(); z++) {
			if (t.sharing(ZoneResource.AREA, z).contains(z)) {
				selfNamed++;
			}
		}
		check(selfNamed == 0, "the sharers never name the zone asked about (" + selfNamed + " of " + t.zones() + " did)");
		check(t.using(ZoneResource.AREA, 7, -1).equals(Arrays.asList(0, 1, 3)),
				"leaving out nobody, every zone on area 7 is named " + t.using(ZoneResource.AREA, 7, -1));
		check(t.using(ZoneResource.AREA, 7, 1).equals(Arrays.asList(0, 3)),
				"leaving out the editing zone names the others " + t.using(ZoneResource.AREA, 7, 1));
		check(t.using(ZoneResource.AREA, 42, -1).isEmpty(), "an id nobody uses is used by nobody");
		check(t.sharing(ZoneResource.MAP, 0).equals(Arrays.asList(1, 2, 3, 4)),
				"the map is asked at its own offset, where every row here holds 0 "
				+ t.sharing(ZoneResource.MAP, 0));
		try {
			t.sharing(ZoneResource.AREA, 5);
			check(false, "a zone past the table has no row (it was answered)");
		} catch (IndexOutOfBoundsException said) {
			check(said.getMessage().contains("no row"), "a zone past the table has no row: " + said.getMessage());
		}
	}

	// ------------------------------------------------------------ 4. repointing
	static void repointingMovesOneIdInOneRow() throws Exception {
		System.out.println("--- repointing a zone moves one id in one row");
		byte[] before = rows(7, 7, 9, 7);
		MasterTable t = MasterTable.detached(before.clone(), 4);
		t.set(ZoneResource.AREA, 2, 0x1234);
		check(t.id(ZoneResource.AREA, 2) == 0x1234, "the id reads back, both bytes (" + Integer.toHexString(t.id(ZoneResource.AREA, 2)) + ")");
		int at = 2 * ROW + ZoneResource.AREA.headerOffset;
		check((t.bytes()[at] & 0xFF) == 0x34 && (t.bytes()[at + 1] & 0xFF) == 0x12,
				"little-endian, at the area's offset in row 2");
		int changed = 0;
		for (int i = 0; i < before.length; i++) {
			if (before[i] != t.bytes()[i]) {
				changed++;
			}
		}
		check(changed == 2, "and no other byte of the table moved (" + changed + " changed)");
		t.set(ZoneResource.AREA, 2, 7);
		check(t.sharing(ZoneResource.AREA, 0).equals(Arrays.asList(1, 2, 3)),
				"pointed at area 7, zone 2 is one of its sharers " + t.sharing(ZoneResource.AREA, 0));
	}

	// ------------------------------------------------------------ 5. a whole row
	static void aRowIsReplacedWholeOrNotAtAll() throws Exception {
		System.out.println("--- a zone's header replaces its row whole, or not at all");
		MasterTable t = MasterTable.detached(rows(7, 7, 9, 7), 4);
		byte[] same = t.row(1);
		check(!t.setRow(1, same), "the same header changes nothing, and says so");
		byte[] header = new byte[ROW];
		Arrays.fill(header, (byte) 0x5A);
		byte[] next = t.row(2);
		check(t.setRow(1, header), "a different header changes the row, and says so");
		check(Arrays.equals(t.row(1), header), "the row is that header");
		check(Arrays.equals(t.row(2), next), "and the next zone's row is untouched");
		for (int len : new int[]{ROW + 8, ROW - 1}) {
			byte[] before = t.bytes().clone();
			try {
				t.setRow(0, new byte[len]);
				check(false, "a " + len + "-byte header is refused (it was taken)");
			} catch (IllegalArgumentException said) {
				check(said.getMessage().contains("bytes, not"), "a " + len + "-byte header is refused: " + said.getMessage());
			} catch (RuntimeException crash) {
				check(false, "a " + len + "-byte header is refused in words (it threw " + crash + ")");
			}
			check(Arrays.equals(t.bytes(), before), "and the table is exactly as it was");
		}
	}

	// ------------------------------------------------------------ 6. a game
	static void theGameIsReadByThePolicyAndWrittenToIt() throws Exception {
		System.out.println("--- a game's table is read by the policy and written back to that game");
		try {
			MasterTable.read(null);
			check(false, "no game is refused (it was read)");
		} catch (IOException said) {
			check(said.getMessage().contains("No workspace"), "no game is refused: " + said.getMessage());
		}
		try {
			MasterTable.read(new FakeGameFiles());
			check(false, "a game with no zone archive open is refused (it was read)");
		} catch (IOException said) {
			check(said.getMessage().contains("ZoneData"), "a game with no zone archive open is refused, naming it: "
					+ said.getMessage());
		}

		FakeGameFiles game = HandedGameTest.zoneGame(7, 7, 9, 7);
		MasterTable t = MasterTable.read(game);
		check(t.zones() == 4, "the game's four zones each have a row (" + t.zones() + ")");
		check(t.sharing(ZoneResource.AREA, 0).equals(Arrays.asList(1, 3)), "and the archive's rows are what is read");
		check(game.edited().isEmpty(), "reading reports no edit");

		t.set(ZoneResource.AREA, 2, 7);
		t.write();
		File staged = game.staged(ArchiveType.ZONE_DATA, 4);
		check(Arrays.equals(Files.readAllBytes(staged.toPath()), rows(7, 7, 7, 7)), "writing puts the rows in the game's file");
		check(game.edited().equals(java.util.Collections.singletonList(staged.getAbsoluteFile())),
				"and reports that file, and only it, as edited " + game.edited());
		check(MasterTable.read(game).sharing(ZoneResource.AREA, 0).equals(Arrays.asList(1, 2, 3)),
				"and the next read sees the edit, not the archive's copy");

		Files.write(staged.toPath(), rows(9, 9, 9, 9, 9));
		try {
			MasterTable.read(game);
			check(false, "an edit of the wrong size is refused (it was read)");
		} catch (IOException said) {
			check(said.getMessage().contains("it is an edit"), "an edit of the wrong size is refused: " + said.getMessage());
		}
		//F029: the encounter pack is the entry after the master table, on a game that keeps one there
		check(ctrmap.ZoneTables.encounterPackIndex(game.archive(ArchiveType.ZONE_DATA), game.profile()) == 5,
				"ORAS's encounter pack is the entry after its master table (entry 5 of 6)");
		try {
			ctrmap.ZoneTables.encounterPackIndex(game.archive(ArchiveType.ZONE_DATA),
					ctrmap.gamedef.GameProfile.of(ctrmap.gamedef.GameType.XY));
			check(false, "a game whose zone archive ends with the master table has no pack index (it answered one)");
		} catch (IOException said) {
			check(said.getMessage().contains("no encounter pack"),
					"a game whose zone archive ends with the master table is refused a pack index: " + said.getMessage());
		}
		FakeGameFiles stale = HandedGameTest.zoneGame(7, 7, 9, 7).plant(ArchiveType.ZONE_DATA, 4, rows(9, 9, 9, 9, 9));
		check(MasterTable.read(stale).sharing(ZoneResource.AREA, 0).equals(Arrays.asList(1, 3)),
				"a stale extraction of the wrong size, never reported as edited, is read from the archive");
	}

	// ------------------------------------------------------------ 7. the class

	/**
	 * Lines outside {@link MasterTable} that index master-table rows by hand: "zone times the row
	 * size, plus an offset". The readers F013 found - and the four the first pass missed, found
	 * only by widening the search - all had that shape, and so did the three forkers that repointed
	 * rows in bytes they were handed and the rename's place-name field, which now go through it.
	 * What remains is the row primitive {@code ZoneResource.idInMasterRow} that this class asks.
	 * Measured 2026-10-07: 6, then 1 once those moved. It may fall and may not rise.
	 */
	static final int HAND_INDEXED_ROW_CEILING = 1;

	static void noNewCodeIndexesTheRowsByHand(File src) throws Exception {
		System.out.println("--- no new code indexes the master table's rows by hand");
		java.util.regex.Pattern rowMath = java.util.regex.Pattern.compile(
				"\\*\\s*[\\w.]*\\b(MASTER_ROW|ZONE_HEADER_SIZE|ROW)\\b\\s*\\+|\\*\\s*0x38\\s*\\+");
		check(rowMath.matcher("return u16(master, zoneIndex * GeometryForker.MASTER_ROW + headerOffset);").find()
				&& rowMath.matcher("int rowOff = z * 0x38 + 4;").find()
				&& !rowMath.matcher("int h = meshesPtr + i * 0x38;").find(),
				"the rule's known answers: a qualified or literal row size times a zone, plus an offset, counts;"
				+ " a stride with no offset after it does not");
		java.util.List<String> found = new java.util.ArrayList<>();
		java.util.List<File> files = DialogSeamTest.javaSources(new File(src, "ctrmap"));
		for (File f : files) {
			if (f.getParentFile().getName().equals("tests") || f.getName().equals("MasterTable.java")) {
				continue;
			}
			int n = 0;
			for (String line : new String(Files.readAllBytes(f.toPath()), "UTF-8").split("\n")) {
				n++;
				String t = line.trim();
				int comment = t.indexOf("//");
				String code = comment < 0 ? t : t.substring(0, comment);
				if (!t.startsWith("*") && !t.startsWith("/*") && rowMath.matcher(code).find()) {
					found.add(f.getName() + ":" + n);
				}
			}
		}
		check(found.contains("ZoneResource.java:83") || found.toString().contains("ZoneResource.java"),
				"and the scan finds the row primitive in ZoneResource, so it is reading the tree (" + files.size()
				+ " files)");
		check(found.size() <= HAND_INDEXED_ROW_CEILING, found.size() + " line(s) index master rows by hand, against a"
				+ " ceiling of " + HAND_INDEXED_ROW_CEILING + " - read the table through MasterTable instead: " + found);
		check(Ratchet.tight(found.size(), HAND_INDEXED_ROW_CEILING), Ratchet.said("hand-indexed-row", found.size(),
				HAND_INDEXED_ROW_CEILING));
	}

	/**
	 * F029: where the zone archive ends is ZoneTables' answer, from the game's profile. ORAS ends
	 * with the master table and then the encounter pack; a game ending with the master table alone
	 * reads "length - 2" as one zone short and "length - 1" as the master table. Six places spelled
	 * it as a number after the rest of the tree had been moved off it. Refused outright: an archive
	 * bound from {@code ZONE_DATA} minus a literal, anywhere but ZoneTables.
	 */
	static void noCodeSpellsTheZoneArchivesTail(File src) throws Exception {
		System.out.println("--- no code spells the zone archive's tail as a number (F029)");
		check(!tailLiterals("GARC zoG = pristineOrLive(ArchiveType.ZONE_DATA);\nint n = zoG.length - 2;\n").isEmpty()
				&& !tailLiterals("final GARC zo = ws.getArchive(ArchiveType.ZONE_DATA);\nint en = zo.length - 1;\n").isEmpty()
				&& tailLiterals("GARC np = ws.getArchive(ArchiveType.NPC_REGISTRIES);\nint last = np.length - 1;\n").isEmpty(),
				"the rule's known answers: a ZoneData archive minus a literal counts; another archive's last index"
				+ " does not");
		java.util.List<String> found = new java.util.ArrayList<>();
		for (File f : DialogSeamTest.javaSources(new File(src, "ctrmap"))) {
			if (f.getParentFile().getName().equals("tests") || f.getName().equals("ZoneTables.java")) {
				continue;
			}
			for (String line : tailLiterals(new String(Files.readAllBytes(f.toPath()), "UTF-8"))) {
				found.add(f.getName() + ": " + line);
			}
		}
		check(found.isEmpty(), "no ZoneData archive's length minus a literal outside ZoneTables - ask"
				+ " ZoneTables.zoneCount / masterIndex / encounterPackIndex: " + found);
	}

	/** The lines of {@code text} taking a literal off the length of an archive bound from ZONE_DATA. */
	static java.util.List<String> tailLiterals(String text) {
		java.util.Set<String> zoneArchives = new java.util.HashSet<>();
		java.util.regex.Matcher decl = java.util.regex.Pattern.compile("\\bGARC\\s+(\\w+)\\s*=[^;]*ZONE_DATA").matcher(text);
		while (decl.find()) {
			zoneArchives.add(decl.group(1));
		}
		java.util.List<String> out = new java.util.ArrayList<>();
		java.util.regex.Pattern minus = java.util.regex.Pattern.compile("\\b(\\w+)\\.length\\s*-\\s*\\d+\\b");
		for (String line : text.split("\n")) {
			String code = line.split("//")[0];
			java.util.regex.Matcher m = minus.matcher(code);
			while (m.find()) {
				if (zoneArchives.contains(m.group(1)) && !code.trim().startsWith("*")) {
					out.add(line.trim());
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------ helpers

	/** A table with one row per zone, each naming only an area id. */
	static byte[] rows(int... areas) {
		byte[] m = new byte[areas.length * ROW];
		for (int z = 0; z < areas.length; z++) {
			int at = z * ROW + ZoneResource.AREA.headerOffset;
			m[at] = (byte) areas[z];
			m[at + 1] = (byte) (areas[z] >> 8);
		}
		return m;
	}

	static String refusal(byte[] workspace, boolean staged, byte[] archive, int zones) {
		try {
			byte[] chose = MasterTable.choose(workspace, staged, archive, zones);
			System.out.println("    (it chose " + chose.length + " bytes instead of refusing)");
			return null;
		} catch (IOException said) {
			return said.getMessage();
		}
	}

	static boolean detachRefused(byte[] rows, int zones) {
		try {
			MasterTable.detached(rows, zones);
			return false;
		} catch (IOException said) {
			return said.getMessage().contains("bytes, not");
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

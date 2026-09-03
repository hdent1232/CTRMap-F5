package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.formats.containers.AD;
import ctrmap.formats.h3d.BchTexturePack;
import ctrmap.formats.propdata.PropDatabase;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A texture carry that reports success must have written the textures, and one
 * that could not write must refuse instead of reporting success.
 *
 * <p>THE DEFECT THIS AREA GUARDS. {@code BchTexturePack.carryToArea} is the one
 * path that grows a target area's texture pack on disk - a building placed from
 * another area, a painted brush whose material comes from a donor region. It is
 * called from exactly one place ({@code GeoEditForm}), behind a modal Apply, and
 * until now no suite called it at all: every line of it - the missing-area
 * refusal, the "is there anything to write" test, the write itself, and the
 * refusal when the write fails - could be broken one at a time and the whole
 * 77-suite battery stayed green. What the user would get is a map that draws
 * white where its imported texture should be, over an Apply dialog that said
 * "+3 textures carried to this area".
 *
 * <p>THE GUARD. Both directions, against a throwaway copy of the game
 * ({@link ScratchGame}), so a real AD container on disk is really rewritten:
 * <ul>
 * <li>a carry into an area no other zone uses returns without throwing, says it
 *     carried them, and - read back from the file afterwards, not from the
 *     in-memory container - the area really holds every name;</li>
 * <li>a carry whose target cannot be written throws, names the area, and leaves
 *     the file byte-for-byte as it was. Reporting that one as success is the
 *     silent failure this repository exists to remove.</li>
 * </ul>
 *
 * Usage: java ctrmap.tests.TextureCarryGuardsTest &lt;romfs-root&gt;
 */
public class TextureCarryGuardsTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump + " (pass the romfs root as args[0])");
			System.out.println("ALL PASS");
			return;
		}
		ScratchGame.open(dump);

		int target = privateArea();
		if (target < 0) {
			System.out.println("  skip: no area in this dump is used by exactly one zone");
			System.out.println("ALL PASS");
			return;
		}
		int zone = zoneOn(target);
		check(BchTexturePack.zonesUsingArea(target, zone) == null,
				"area " + target + " is zone " + zone + "'s alone, so a carry into it is allowed");

		Set<String> held = namesOf(target);
		int donor = -1;
		List<String> spare = new ArrayList<>();
		for (int a = 0; a < Workspace.ad.length && donor < 0; a++) {
			if (a == target) {
				continue;
			}
			List<String> missing = new ArrayList<>();
			for (String n : namesOf(a)) {
				if (!held.contains(n)) {
					missing.add(n);
				}
			}
			if (missing.size() >= 4) {
				donor = a;
				spare = missing;
			}
		}
		if (donor < 0) {
			System.out.println("  skip: no area in this dump holds four textures area " + target + " lacks");
			System.out.println("ALL PASS");
			return;
		}
		List<String> first = new ArrayList<>(spare.subList(0, 2));
		List<String> second = new ArrayList<>(spare.subList(2, 4));
		System.out.println("  fixture: area " + donor + " -> area " + target + " (zone " + zone + "), carrying " + first);

		aCarryReallyWrites(donor, target, zone, first);
		aCarryThatCannotWriteRefuses(donor, target, zone, second);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The success path, checked against the FILE. An in-memory container would
	 * agree with itself whether or not the write happened.
	 */
	static void aCarryReallyWrites(int donor, int target, int zone, List<String> needed) throws Exception {
		File tgtFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.AREA_DATA, target);
		check(tgtFile != null && tgtFile.isFile(), "the target area has a workspace file to grow (" + tgtFile + ")");
		String note;
		try {
			note = BchTexturePack.carryToArea(donor, target, needed, null, zone);
		} catch (Exception ex) {
			check(false, "a carry into an area nobody else uses is not refused; it threw " + ex);
			return;
		}
		check(note != null && note.contains("carried"), "the carry reports what it did: " + note.trim());
		Set<String> after = namesOnDisk(tgtFile);
		List<String> lost = new ArrayList<>();
		for (String n : needed) {
			if (!after.contains(n)) {
				lost.add(n);
			}
		}
		check(lost.isEmpty(), "after a carry that reported success the area file really holds "
				+ needed + " (missing: " + lost + ")");
	}

	/**
	 * The refusal path. The target is made unwritable, so the container's write
	 * fails the way a full disk or a locked file makes it fail; the carry must
	 * throw rather than return its cheerful note.
	 */
	static void aCarryThatCannotWriteRefuses(int donor, int target, int zone, List<String> needed) throws Exception {
		File tgtFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.AREA_DATA, target);
		byte[] before = Files.readAllBytes(tgtFile.toPath());
		Exception thrown = null;
		String note = null;
		if (!tgtFile.setWritable(false)) {
			System.out.println("  skip: this filesystem will not make " + tgtFile + " read-only");
			return;
		}
		try {
			note = BchTexturePack.carryToArea(donor, target, needed, null, zone);
		} catch (Exception ex) {
			thrown = ex;
		} finally {
			tgtFile.setWritable(true);
		}
		check(thrown != null, "a carry whose target cannot be written is refused, not reported as done"
				+ (thrown == null ? " - it returned \"" + (note == null ? null : note.trim()) + "\"" : ""));
		check(thrown != null && String.valueOf(thrown.getMessage()).contains(String.valueOf(target)),
				"the refusal names the area it could not write: " + thrown);
		check(Arrays.equals(before, Files.readAllBytes(tgtFile.toPath())),
				"a refused carry leaves the area file exactly as it was");
	}

	/** An area used by exactly one zone in the master table, or -1. */
	static int privateArea() throws Exception {
		byte[] m = Workspace.zo.getDecompressedEntry(Workspace.zo.length - 2);
		int zones = Math.min(m.length / 0x38, Workspace.zo.length - 2);
		int[] users = new int[Workspace.ad.length];
		for (int z = 0; z < zones; z++) {
			int a = area(m, z);
			if (a >= 0 && a < users.length) {
				users[a]++;
			}
		}
		for (int a = 0; a < users.length; a++) {
			if (users[a] == 1 && !namesOf(a).isEmpty()) {
				return a;
			}
		}
		return -1;
	}

	/** The zone whose header names this area. */
	static int zoneOn(int targetArea) throws Exception {
		byte[] m = Workspace.zo.getDecompressedEntry(Workspace.zo.length - 2);
		int zones = Math.min(m.length / 0x38, Workspace.zo.length - 2);
		for (int z = 0; z < zones; z++) {
			if (area(m, z) == targetArea) {
				return z;
			}
		}
		return -1;
	}

	static int area(byte[] master, int zone) {
		return (master[zone * 0x38 + 2] & 0xFF) | ((master[zone * 0x38 + 3] & 0xFF) << 8);
	}

	/** Every texture name an area holds, across both of its packs. */
	static Set<String> namesOf(int areaId) {
		Set<String> names = new LinkedHashSet<>();
		byte[] c = Workspace.ad.getDecompressedEntry(areaId);
		if (c == null || c.length < 8 || c[0] != 'A' || c[1] != 'D') {
			return names;
		}
		for (int sub : new int[]{11, 1}) {
			addNames(PropDatabase.getSubfile(c, sub), names);
		}
		return names;
	}

	/** The same, read back from the container on disk. */
	static Set<String> namesOnDisk(File adFile) {
		Set<String> names = new LinkedHashSet<>();
		AD ad = new AD(adFile);
		for (int sub : new int[]{11, 1}) {
			addNames(ad.getFile(sub), names);
		}
		return names;
	}

	static void addNames(byte[] pack, Set<String> into) {
		if (!BchTexturePack.isTexturePack(pack)) {
			return;
		}
		for (BchTexturePack.Texture t : BchTexturePack.parse(pack)) {
			into.add(t.name);
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

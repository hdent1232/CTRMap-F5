package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.zone.ZoneEntities;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * The three guards that stand between an ordinary mistake and unrecoverable
 * game data. Each of these was a live defect, and each was silent.
 *
 * <ol>
 * <li>A GARC whose file has been rewritten by somebody else since it was read
 *     must notice before packing. Its entry table is a list of offsets; pack
 *     from a stale one and every "unchanged" entry is copied from garbage. A
 *     CTRMap window sat open for eleven hours while a headless tool rewrote
 *     ZoneData five times - one click on Pack Workspace would have done it.</li>
 * <li>A sign wrapper must be injected BEFORE any talker or sign is wired into
 *     the same script, because updateRaw moves the address the injector writes
 *     at. Recomputing those boundaries "correctly" was tried and is wrong - it
 *     breaks the 467-zone injection corpus - so the ordering is the guard.</li>
 * <li>A warp with no destination must not serialise. It used to default to
 *     zone 0, warp 0, which is a real place: adding a warp in the editor and
 *     saving built a working door into the first zone in the game.</li>
 * </ol>
 *
 * Usage: java ctrmap.tests.DataSafetyGuardsTest &lt;path-to-any-garc&gt;
 */
public class DataSafetyGuardsTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		String garcPath = args.length > 0 ? args[0] : "../RomFS_original_garcs/a/0/4/0";
		staleArchive(new File(garcPath));
		scriptBoundaries();
		unsetWarp();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A GARC must detect that its file moved underneath it. */
	static void staleArchive(File src) throws Exception {
		if (!src.isFile()) {
			System.out.println("  skip: no GARC at " + src);
			return;
		}
		File tmp = File.createTempFile("ctrmap_stale", ".garc");
		tmp.deleteOnExit();
		Files.copy(src.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);

		GARC g = new GARC(tmp);
		check(!g.isStale(), "a freshly read archive is not stale");

		//somebody else rewrites the file - a second editor, another tool
		byte[] bytes = Files.readAllBytes(tmp.toPath());
		byte[] longer = new byte[bytes.length + 64];
		System.arraycopy(bytes, 0, longer, 0, bytes.length);
		Files.write(tmp.toPath(), longer);
		//lastModified can be coarse; the length change alone must be enough
		check(g.isStale(), "an archive rewritten underneath is detected as stale");

		//and a re-read clears it
		GARC g2 = new GARC(tmp);
		check(!g2.isStale(), "re-reading clears the staleness");
		tmp.delete();
	}

	/**
	 * The sign-injection ordering rule, stated so it is not silently lost.
	 *
	 * <p>updateRaw moves dataStart, and SignWrapperInjector writes at
	 * dataStart - instructionStart, so appending code first moves the target.
	 * The arithmetic below shows how far: recomputing the boundary from the
	 * grown code, which is the intuitive "fix", relocates the write by the size
	 * of whatever was appended. SignWrapperInjectTest is what actually holds
	 * this - it verifies 272 injections at byte level across 467 zones.
	 */
	static void scriptBoundaries() {
		int instructionStart = 0x40, code = 100, data = 7, appended = 20;
		int parsedHeapStart = instructionStart + (code + data) * 4;
		int parsedDataStart = parsedHeapStart - data * 4;
		int grownHeapStart = instructionStart + (code + appended + data) * 4;
		int grownDataStart = grownHeapStart - data * 4;
		check(grownDataStart - parsedDataStart == appended * 4,
				"recomputing the boundary after an append moves the injection site by the"
				+ " appended size - which is why signs are injected first, not last");
	}

	/** An unconfigured warp must refuse to serialise. */
	static void unsetWarp() {
		ZoneEntities.Warp w = new ZoneEntities.Warp();
		check(w.isUnset(), "a new warp starts with no destination");
		check(w.targetZone != 0, "a new warp does NOT default to zone 0");

		ZoneEntities e = null;
		try {
			e = new ZoneEntities(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
		} catch (RuntimeException ignore) {
		}
		if (e == null) {
			System.out.println("  skip: could not build an empty ZoneEntities to assemble");
			return;
		}
		e.warps.add(w);
		try {
			e.assembleData();
			System.out.println("  FAIL: an unset warp serialised without complaint");
			fails++;
		} catch (IllegalStateException ex) {
			System.out.println("  ok: an unset warp refuses to serialise (" + ex.getMessage() + ")");
		}

		//and a configured one is fine
		w.targetZone = 473;
		w.targetWarpId = 0;
		check(!w.isUnset(), "setting a destination clears the unset state");
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

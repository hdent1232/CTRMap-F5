package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.GameProfile;
import java.io.IOException;

/**
 * Where the tables at the END of the zone and area archives are, for the game
 * that is actually open - and a refusal, in words, when nobody has measured
 * that for this game.
 *
 * <p>WHY THIS EXISTS. Both archives keep some number of entries past the last
 * real record: ZoneData ends with the master zone-header table and (on ORAS)
 * the "EN" encounter pack, AreaData ends with the engine's global per-area
 * table. Nine places in the editor worked out how many by writing
 * {@code archive.length - (Workspace.isXY() ? 1 : 2)}. That expression has two
 * answers for four games. Sun/Moon answers false to {@code isXY()}, so it took
 * ORAS's 2 - a zone count two short, a master table read from an ordinary
 * zone, and every downstream index off by the difference, with nothing printed
 * and nothing to notice. The number was never anyone's decision; it was the
 * shape of a boolean.
 *
 * <p>The count now comes from the open game's {@link GameProfile}, which
 * answers -1 for "nobody has measured this". Every method here turns that -1
 * into an IOException naming the game and the number that is missing, because
 * the alternative - carrying on with another game's constant - is the defect.
 * A refusal a user can read is worse than working and better than wrong.
 */
public final class ZoneTables {

	private ZoneTables() {
	}

	/**
	 * How many entries at the end of ZoneData are tables rather than zones.
	 *
	 * @throws IOException when the open game has no measured value, or no
	 * workspace is open at all
	 */
	public static int zoneTrailing() throws IOException {
		return zoneTrailing(requireProfile());
	}

	/**
	 * The same for a game handed in rather than looked up, so a caller that
	 * was given a {@link ctrmap.WorkspaceSession} asks about THAT session's
	 * game instead of about whichever one happens to be installed globally -
	 * and still gets the one refusal text rather than a second copy of it.
	 *
	 * @throws IOException when that game has no measured value
	 */
	public static int zoneTrailing(GameProfile p) throws IOException {
		int n = p.zoneDataTrailingEntries();
		if (n < 0) {
			throw new IOException("CTRMap has not measured how the ZoneData archive of "
					+ p.displayName() + " ends - how many of its last entries are tables"
					+ " rather than zones - so it cannot say how many zones this game has,"
					+ " or where its master zone-header table is."
					+ "\n\nThat number is deliberately absent rather than guessed: taking"
					+ " another game's would read an ordinary zone as the master table and"
					+ " report a zone count that is silently wrong."
					+ "\n\nMeasure it against a " + p.displayName() + " dump and fill in"
					+ " zoneDataTrailingEntries() in that game's profile.");
		}
		return n;
	}

	/**
	 * How many zones the open game has: every ZoneData entry that is not one of
	 * the tables at the end.
	 */
	public static int zoneCount(GARC zoneData) throws IOException {
		requireArchive(zoneData, "ZoneData");
		return zoneData.length - zoneTrailing();
	}

	/** The same, for a game handed in rather than looked up. */
	public static int zoneCount(GARC zoneData, GameProfile p) throws IOException {
		requireArchive(zoneData, "ZoneData");
		return zoneData.length - zoneTrailing(p);
	}

	/**
	 * GARC index of the "EN" wild-encounter pack: the entry after the master table, on a game
	 * that keeps one there (ORAS ends master, then EN). Three places wrote it {@code length - 1},
	 * which on a game ending with the master table alone IS the master table (F029).
	 *
	 * @throws IOException when the game keeps no pack after its master table, or nobody measured
	 * how its ZoneData ends
	 */
	public static int encounterPackIndex(GARC zoneData, GameProfile p) throws IOException {
		int zones = zoneCount(zoneData, p);
		if (zoneTrailing(p) < 2) {
			throw new IOException(p.displayName() + "'s ZoneData ends with its master zone-header table;"
					+ " it keeps no encounter pack after it.");
		}
		return zones + 1;
	}

	/** One zone's container bytes by index, from wherever a caller reads zones (F225). */
	public interface ZoneBytes {

		byte[] zone(int index) throws IOException;
	}

	/**
	 * Zone {@code i} as the workspace holds it now, extracting nothing: its extracted copy - the
	 * user's unpacked edits included - when the workspace holds one, else the archive's entry
	 * (F225). The archive alone is the game as of the last pack: the zone-safety scans read it, so
	 * a warp added since then into a zone did not count, and a zone filled since read as empty.
	 *
	 * @throws IOException when neither can be read
	 */
	public static byte[] zoneNow(WorkspaceSession s, int i) throws IOException {
		java.io.File f = new java.io.File(s.getExtractionDirectory(ctrmap.gamedef.ArchiveType.ZONE_DATA), String.valueOf(i));
		if (f.isFile()) {
			return java.nio.file.Files.readAllBytes(f.toPath());
		}
		GARC zo = s.getArchive(ctrmap.gamedef.ArchiveType.ZONE_DATA);
		byte[] b = zo == null ? null : zo.getDecompressedEntry(i);
		if (b == null) {
			throw new IOException("zone " + i + " is neither in the workspace nor readable from the archive");
		}
		return b;
	}

	/**
	 * GARC index of the master zone-header table - the first entry past the
	 * last zone, and the runtime-authoritative copy of every zone header.
	 */
	public static int masterIndex(GARC zoneData) throws IOException {
		return zoneCount(zoneData);
	}

	/**
	 * The AreaData index of the engine's global per-area table: the profile's measured index
	 * (F235). It was "the first entry past the last area" - length minus the trailing tables -
	 * and an area fork appends AFTER the table, so after one fork that named the forked area as
	 * the table and counted the table as an area.
	 *
	 * @throws IOException naming the game when nobody measured where its table is, or when this
	 * archive is too short to hold it
	 */
	public static int areaTableIndex(GARC areaData) throws IOException {
		requireArchive(areaData, "AreaData");
		GameProfile p = requireProfile();
		int t = p.areaTableIndex();
		if (t < 0) {
			throw new IOException("CTRMap has not measured where the AreaData archive of "
					+ p.displayName() + " keeps the engine's per-area table, so it cannot say"
					+ " which of its entries are areas."
					+ "\n\nMeasure it against a " + p.displayName() + " dump and fill in"
					+ " areaTableIndex() in that game's profile.");
		}
		if (t >= areaData.length) {
			throw new IOException("This AreaData archive holds " + areaData.length + " entries, so it has no"
					+ " entry " + t + " for " + p.displayName() + "'s per-area table - it is not that game's archive.");
		}
		return t;
	}

	/**
	 * Every AreaData entry that IS an area, in order: those before the per-area table and those
	 * an area fork appended after it (F235).
	 */
	public static int[] areaIds(GARC areaData) throws IOException {
		int t = areaTableIndex(areaData);
		int[] ids = new int[areaData.length - 1];
		for (int i = 0, k = 0; i < areaData.length; i++) {
			if (i != t) {
				ids[k++] = i;
			}
		}
		return ids;
	}

	/** How many areas the open game has - the forked ones included (F235). */
	public static int areaCount(GARC areaData) throws IOException {
		return areaIds(areaData).length;
	}

	private static GameProfile requireProfile() throws IOException {
		if (!Workspace.isValid()) {
			throw new IOException("No workspace is loaded, so there is no game to ask.");
		}
		return Workspace.profile();
	}

	private static void requireArchive(GARC g, String name) throws IOException {
		if (g == null) {
			throw new IOException("No workspace is loaded (" + name + " archive unavailable).");
		}
	}
}

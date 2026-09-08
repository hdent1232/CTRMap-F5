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
	 * GARC index of the master zone-header table - the first entry past the
	 * last zone, and the runtime-authoritative copy of every zone header.
	 */
	public static int masterIndex(GARC zoneData) throws IOException {
		return zoneCount(zoneData);
	}

	/**
	 * How many areas the open game has: every AreaData entry that is not one of
	 * the tables at the end (on both measured games, the engine's global
	 * per-area table, which is not an area).
	 */
	public static int areaCount(GARC areaData) throws IOException {
		requireArchive(areaData, "AreaData");
		GameProfile p = requireProfile();
		int n = p.areaDataTrailingEntries();
		if (n < 0) {
			throw new IOException("CTRMap has not measured how the AreaData archive of "
					+ p.displayName() + " ends - how many of its last entries are tables"
					+ " rather than areas - so it cannot say how many areas this game has."
					+ "\n\nMeasure it against a " + p.displayName() + " dump and fill in"
					+ " areaDataTrailingEntries() in that game's profile.");
		}
		return areaData.length - n;
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

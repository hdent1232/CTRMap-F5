package ctrmap.tests;

import ctrmap.formats.containers.ContainerBytes;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.formats.zone.ZoneFootprint;
import ctrmap.formats.zone.ZoneHeader;
import ctrmap.gamedef.GameType;
import java.io.File;

/**
 * Which part of a shared map a zone actually occupies.
 *
 * <p>THE DEFECT THIS EXISTS FOR, reported with three screenshots: Route 132, Route 133 and
 * Route 134 are zones 61, 62 and 63, all of them "area 19, map 12", and the Zone Loader's
 * preview drew the same picture for all three. It was not a rendering bug - they really are
 * one matrix - but nothing told the user which third of that route they were about to open.
 *
 * <p>Driven against the retail dump because the answer is a fact about the game's data, not
 * about arithmetic. The header carries no rectangle: {@code PX/PX2/PY/PY2} is zero on every
 * zone sampled and {@code X2/Y2/Z2} merely repeats the spawn point. What separates them is
 * where their NPCs, props, triggers, warps and spawn sit - in TWO different coordinate spaces,
 * which is the part that is easy to get wrong and impossible to see once it is wrong.
 */
public class ZoneFootprintTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : ".");
		File zoneData = new File(dump, "a/0/1/3".replace('/', File.separatorChar));
		if (!zoneData.isFile()) {
			System.out.println("  skip: no ZoneData at " + zoneData
					+ " - this suite reads the real dump, because the defect is in the data");
			System.out.println("ALL PASS");
			return;
		}
		GARC zones = new GARC(zoneData);

		threeZonesSharingOneMatrixAreThreeDifferentPlaces(zones);
		theTileSpaceIsConverted(zones);
		aZoneThatOwnsItsMapCoversIt(zones);
		nothingToGoOnIsNullRatherThanTheOrigin(zones);
		aFootprintIsKeptInsideItsMatrix(zones);

		System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
		if (fails != 0) {
			System.exit(1);
		}
	}

	/** The reported case, asserted as the thing the user actually needs: they differ. */
	static void threeZonesSharingOneMatrixAreThreeDifferentPlaces(GARC zones) throws Exception {
		System.out.println("--- Routes 132, 133 and 134 share one matrix and are three places");
		ZoneFootprint a = footprint(zones, 61);
		ZoneFootprint b = footprint(zones, 62);
		ZoneFootprint c = footprint(zones, 63);
		check(a != null && b != null && c != null, "all three zones locate themselves");
		if (a == null || b == null || c == null) {
			return;
		}
		check(header(zones, 61).mapmatrixID == header(zones, 62).mapmatrixID
				&& header(zones, 62).mapmatrixID == header(zones, 63).mapmatrixID,
				"...and they really do share one map matrix, or this suite is about nothing");
		check(!a.describeCells().equals(b.describeCells())
				&& !b.describeCells().equals(c.describeCells())
				&& !a.describeCells().equals(c.describeCells()),
				"no two of them describe the same cells: " + a.describeCells() + " / "
						+ b.describeCells() + " / " + c.describeCells());
		//AND THEY DO NOT OVERLAP, which is the stronger claim: three names for one place would
		//also be "different" if the boxes merely differed by a cell at the edges.
		check(a.minCellX() > b.maxCellX(), "61 lies entirely east of 62");
		check(b.minCellX() > c.maxCellX(), "62 lies entirely east of 63");
		//...and in the order the route runs, so the preview pans one way as the list is
		//arrowed down rather than jumping about.
		check(a.maxCellX() > b.maxCellX() && b.maxCellX() > c.maxCellX(),
				"and they march in index order along the route");
	}

	/**
	 * NPCs, props and triggers are in TILES; the spawn and warps are in WORLD units.
	 *
	 * <p>Zone 62 is the case that proves it: it has no warps at all, its spawn is 5553 world,
	 * and its thirteen NPCs sit at tiles 217..303. Without the 18-unit conversion those NPCs
	 * would read as world coordinates and drag the box back to cell 0 - so asserting where
	 * zone 62 lands IS the assertion that both spaces were handled, and a preview that framed
	 * cell 0 for it would be pointing at the far end of the route.
	 */
	static void theTileSpaceIsConverted(GARC zones) throws Exception {
		System.out.println("--- tile coordinates are converted, world coordinates are not");
		ZoneFootprint f = footprint(zones, 62);
		check(f != null, "zone 62 locates itself");
		if (f == null) {
			return;
		}
		check(f.minCellX() >= 3,
				"zone 62's NPCs are read as tiles, so its box does not collapse to cell 0: "
						+ f.describeCells());
		ZoneHeader h = header(zones, 62);
		int spawnCell = h.X / ZoneFootprint.WORLD_PER_CELL;
		check(spawnCell >= f.minCellX() && spawnCell <= f.maxCellX(),
				"and the box contains the spawn point's own cell (" + spawnCell + ")");
		check(ZoneFootprint.WORLD_PER_CELL == 720 && ZoneFootprint.WORLD_PER_TILE == 18,
				"the constants are the ones GeoBoxOps documents");
	}

	/** Without this, a footprint that always returned one cell would pass everything above. */
	static void aZoneThatOwnsItsMapCoversIt(GARC zones) throws Exception {
		System.out.println("--- a zone that owns its whole map covers its whole map");
		ZoneFootprint f = footprint(zones, 0);
		check(f != null, "zone 0 locates itself");
		if (f == null) {
			return;
		}
		check(f.cellsAcross() > 3 && f.cellsDown() > 3,
				"zone 0 spans many cells, not one: " + f.describeCells());
	}

	/** An unreadable position is UNKNOWN, and the caller frames the whole map instead. */
	static void nothingToGoOnIsNullRatherThanTheOrigin(GARC zones) throws Exception {
		System.out.println("--- a zone nothing locates answers null, not cell (0,0)");
		check(ZoneFootprint.of(null, null) == null, "no header at all is null");

		//A REAL ZONE FOR THE REAL BRANCH. The first version of this asserted only the line
		//above, which returns from the header==null guard several lines earlier - so the
		//branch that matters, a header that IS there and locates nothing, had nothing
		//covering it, and a plant that put such a zone at cell (0,0) SURVIVED. Scanning all
		//538 ZoneData entries found exactly one: 537, spawn (0,0), in the tail the profile
		//already documents as not being zones. Cell (0,0) is a real cell - Littleroot is in
		//it - so answering with it would aim the preview at the top-left corner of the map
		//and look exactly like an answer.
		ZoneHeader bare = new ZoneHeader(new byte[1024], GameType.ORAS);
		check(bare.X == 0 && bare.Y == 0,
				"a header of zeroes has no position of its own, or this case is not that case");
		check(ZoneFootprint.of(bare, null) == null,
				"a header whose spawn is (0,0) and which has nothing else locates NOTHING,"
				+ " rather than claiming the corner of the map");
		//...and it is not a hypothetical shape: scanning all 538 ZoneData entries found
		//exactly one real entry like it, 537, in the tail the game profile already documents
		//as not being zones. It is read here as zeroes rather than out of the archive so the
		//case does not depend on which entries happen to be padding in one dump.

		ZoneHeader h = header(zones, 61);
		ZoneFootprint spawnOnly = ZoneFootprint.of(h, null);
		check(spawnOnly != null, "...while a header with a spawn point alone still locates it");
		check(spawnOnly != null && spawnOnly.from == 1,
				"and says it was located by exactly one thing");
	}

	static void aFootprintIsKeptInsideItsMatrix(GARC zones) throws Exception {
		System.out.println("--- a footprint is clamped to the matrix it is drawn on");
		ZoneFootprint f = footprint(zones, 61);
		check(f != null, "zone 61 locates itself");
		if (f == null) {
			return;
		}
		ZoneFootprint tiny = f.clampedTo(1, 1);
		check(tiny.minCellX() == 0 && tiny.maxCellX() == 0
				&& tiny.minCellY() == 0 && tiny.maxCellY() == 0,
				"clamped into a one-cell matrix it is that one cell: " + tiny.describeCells());
		check(f.clampedTo(64, 64) == f,
				"and a box that already fits is returned unchanged, not rebuilt");
		check(f.clampedTo(0, 0) == f,
				"a matrix of no size cannot clamp anything, so nothing is claimed");
	}

	static ZoneFootprint footprint(GARC zones, int index) throws Exception {
		byte[] c = zones.getDecompressedEntry(index);
		return ZoneFootprint.of(new ZoneHeader(ContainerBytes.subfile(c, 0), GameType.ORAS),
				new ZoneEntities(ContainerBytes.subfile(c, 1)));
	}

	static ZoneHeader header(GARC zones, int index) throws Exception {
		return new ZoneHeader(ContainerBytes.subfile(zones.getDecompressedEntry(index), 0),
				GameType.ORAS);
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}

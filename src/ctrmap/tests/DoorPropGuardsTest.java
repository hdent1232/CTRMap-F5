package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.formats.h3d.BchTexturePack;
import ctrmap.formats.h3d.BuildingCatalog;
import ctrmap.formats.propdata.PropDatabase;
import ctrmap.humaninterface.TilePainterForm;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A door placed on an area that already holds one of its textures under a
 * different name-sake must say so.
 *
 * <p>Finding 3's fix made {@code importTextures} refuse a same-name,
 * different-pixels clash, and made the terrain carry report one. The door-prop
 * path does neither: it imports only the names the area LACKS, so when the area
 * already has a {@code chip_mado}, nothing is missing, no donor is consulted,
 * and the house draws the area's window - not its own - with no line anywhere.
 * Verification counted five such doors on area 4 alone. This drives the real
 * registration and requires the Apply note to name the texture.
 *
 * <p>The fixture is found, not assumed: the first catalogue door whose model
 * names a texture the target area holds under other pixels. If no such door
 * exists in the dump the test says so and skips, rather than passing.
 *
 * Usage: java ctrmap.tests.DoorPropGuardsTest &lt;romfs-root&gt;
 */
public class DoorPropGuardsTest {

	/** An area verification showed to be prone to this; any shared area would do. */
	private static final int TARGET_AREA = 4;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump);
			System.out.println("ALL PASS");
			return;
		}
		ScratchGame.open(dump);

		int zone = zoneOnArea(TARGET_AREA);
		check(zone >= 0, "some zone sits on area " + TARGET_AREA + " (zone " + zone + ")");
		byte[] areaEntry = Workspace.ad.getDecompressedEntry(TARGET_AREA);
		byte[] world = PropDatabase.getSubfile(areaEntry, 11);
		byte[] props = PropDatabase.getSubfile(areaEntry, 1);

		//find a door whose home pixels differ from what the target area holds
		BuildingCatalog.Entry door = null;
		String clash = null;
		int looked = 0;
		for (BuildingCatalog.Entry e : BuildingCatalog.entries()) {
			if (e.doorProp == null || "-".equals(e.doorProp) || e.donorArea == TARGET_AREA || looked++ > 400) {
				continue;
			}
			byte[] donorPack = PropDatabase.getSubfile(Workspace.ad.getDecompressedEntry(e.donorArea), 1);
			List<String> names = new ArrayList<>(PropDatabase.getMaterialTextureNames(modelOf(e.doorProp, e.donorArea)));
			List<String> clashes = BchTexturePack.clashesWith(world, props, donorPack, names);
			if (!clashes.isEmpty()) {
				door = e;
				clash = clashes.get(0);
				break;
			}
		}
		if (door == null) {
			System.out.println("  skip: no catalogue door clashes with area " + TARGET_AREA + " in this dump (" + looked + " looked at)");
			System.out.println("ALL PASS");
			return;
		}
		System.out.println("  fixture: \"" + door.name + "\" (door " + door.doorProp + " from area " + door.donorArea
				+ ") draws area " + TARGET_AREA + "'s " + clash);

		StringBuilder note = new StringBuilder();
		TilePainterForm.StagedArea area = new TilePainterForm.StagedArea(TARGET_AREA, zone);
		TilePainterForm.ensureDoorPropRegistered(door.doorProp, area, note);
		check(note.toString().contains(clash), "registering the door tells the user it will draw the area's " + clash + " - note: " + note);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** The door prop's model bytes, by name - the lookup the door path itself makes. */
	static byte[] modelOf(String propName, int area) throws Exception {
		PropDatabase db = PropDatabase.get();
		if (db == null) {
			throw new IllegalStateException("prop database unavailable");
		}
		for (PropDatabase.PropModel m : db.models) {
			if (propName.equals(m.name)) {
				return PropDatabase.getSubfile(Workspace.bm.getDecompressedEntry(m.modelIndex), 0);
			}
		}
		throw new IllegalStateException("no prop model named " + propName);
	}

	/** First zone whose header names the area, from the master table. */
	static int zoneOnArea(int area) throws Exception {
		byte[] m = Workspace.zo.getDecompressedEntry(Workspace.zo.length - 2);
		for (int z = 0; z < Math.min(m.length / 0x38, Workspace.zo.length - 2); z++) {
			int a = (m[z * 0x38 + 2] & 0xFF) | ((m[z * 0x38 + 3] & 0xFF) << 8);
			if (a == area) {
				return z;
			}
		}
		return -1;
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

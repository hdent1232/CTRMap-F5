package ctrmap.tests;

import ctrmap.InteriorWirer;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.zone.ZoneEntities;
import java.io.File;
import java.util.List;
import static ctrmap.formats.containers.ContainerBytes.subfile;

/**
 * Validates the interior round-trip wiring against the pristine dump, for
 * every interior the building catalog links:
 * <ul>
 * <li>the exit-warp heuristic (target area != interior's area) finds at least
 *     one exit, and every found exit is a REAL door: its retail target zone
 *     warps back into this interior (the measured retail invariant);</li>
 * <li>same-area warps (stairs to another floor) are never classified as
 *     exits;</li>
 * <li>rewiring the exits and reassembling preserves every other entity
 *     byte-for-byte (only the exit warps' target fields change).</li>
 * </ul>
 * Usage: java ctrmap.tests.InteriorWirerTest &lt;path-to-a013-garc&gt;
 */
public class InteriorWirerTest {

	static GARC zo;

	/**
	 * Every interior the building catalog links, read from the catalog itself (F153). It was a list
	 * copied by hand - 15 of what are now 39 - and the javadoc above went on saying "every".
	 */
	static int[] interiors() {
		java.util.TreeSet<Integer> zones = new java.util.TreeSet<>();
		for (ctrmap.formats.h3d.BuildingCatalog.Entry e : ctrmap.formats.h3d.BuildingCatalog.entries()) {
			if (e.interiorZone >= 0) {
				zones.add(e.interiorZone);
			}
		}
		int[] out = new int[zones.size()];
		int i = 0;
		for (int z : zones) {
			out[i++] = z;
		}
		return out;
	}

	/** The floor under it: a catalog read that came back empty must not check nothing and pass. */
	static final int AT_LEAST = 39;

	static int checkFails = 0;

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		checkFails += ok ? 0 : 1;
	}

	public static void main(String[] args) throws Exception {
		int[] interiors = interiors();
		if (interiors.length < AT_LEAST) {
			System.out.println("FAIL: the catalog links " + interiors.length + " interiors, fewer than the " + AT_LEAST
					+ " it did - a read that lost them would check nothing and pass");
			System.exit(1);
		}
		theWorkspaceReaderRefusesAZoneItHoldsNoFileFor();
		aBuildingsFloorsAreFoundAndRewired();
		everyFloorIsClonedAndItsStairsLeadToTheClones();
		aFloorThatWillNotReadBackOrBeWrittenIsRefusedAndPutBack();
		File garc = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs/a/0/1/3");
		if (!garc.isFile()) {
			Skip.section("no ZoneData archive at " + garc + " - the retail interiors were NOT checked");
			System.out.println(checkFails == 0 ? "ALL PASS" : "FAILURES PRESENT");
			System.exit(checkFails == 0 ? 0 : 1);
		}
		zo = new GARC(garc);
		int fails = checkFails, singles = 0, multis = 0, multiFloor = 0;
		for (int zone : interiors) {
			try {
				ZoneEntities ent = entities(zone);
				int area = area(zone);
				//EVERY FLOOR (F015): the floors found are closed - no floor's warp leads into the
				//building's area anywhere but another of its floors - and rewired, none is left retail
				List<Integer> floors = InteriorWirer.floors(zone, RETAIL);
				java.util.Map<Integer, Integer> cloneOf = new java.util.LinkedHashMap<>();
				for (int f : floors) {
					cloneOf.put(f, 3000 + cloneOf.size());
				}
				for (int f : floors) {
					ZoneEntities floor = entities(f);
					int left = InteriorWirer.rewire(floor, area(f), cloneOf, 500, 7, f == zone, InteriorWirerTest::area);
					for (ZoneEntities.Warp w : floor.warps) {
						if (floors.contains(w.targetZone)) {
							throw new IllegalStateException("floor " + f + " still has a warp into retail floor " + w.targetZone);
						}
					}
					if (left != 0) {
						throw new IllegalStateException("floor " + f + " has " + left + " warp(s) left on a retail target");
					}
				}
				multiFloor += floors.size() > 1 ? 1 : 0;
				List<Integer> exits = InteriorWirer.exitWarps(ent, area, InteriorWirerTest::area);
				if (exits.isEmpty()) {
					throw new IllegalStateException("no exit warps found");
				}
				// every exit must be a real door: its target zone warps back here
				for (int i : exits) {
					int tz = ent.warps.get(i).targetZone;
					boolean back = false;
					for (ZoneEntities.Warp w : entities(tz).warps) {
						if (w.targetZone == zone) {
							back = true;
						}
					}
					if (!back) {
						throw new IllegalStateException("exit " + i + " target zone " + tz + " has no warp back");
					}
				}
				// stairs (same-area targets) never classified as exits
				for (int i = 0; i < ent.warps.size(); i++) {
					if (!exits.contains(i) && area(ent.warps.get(i).targetZone) != area) {
						throw new IllegalStateException("warp " + i + " leads outside but was not classified as exit");
					}
				}
				if (ent.warps.size() == 1 && (exits.size() != 1 || exits.get(0) != 0)) {
					throw new IllegalStateException("single-warp interior must exit via warp 0");
				}

				// rewire + reassemble: only the exit targets change
				ZoneEntities mod = entities(zone);
				for (int i : exits) {
					mod.warps.get(i).targetZone = 500;
					mod.warps.get(i).targetWarpId = 7;
				}
				mod.modified = true;
				ZoneEntities re = new ZoneEntities(mod.assembleData());
				if (re.warps.size() != ent.warps.size() || re.npcs.size() != ent.npcs.size()
						|| re.furniture.size() != ent.furniture.size()
						|| re.triggers1.size() != ent.triggers1.size() || re.triggers2.size() != ent.triggers2.size()) {
					throw new IllegalStateException("entity counts changed on rewire");
				}
				for (int i = 0; i < re.warps.size(); i++) {
					ZoneEntities.Warp a = re.warps.get(i), b = ent.warps.get(i);
					boolean isExit = exits.contains(i);
					if (a.targetZone != (isExit ? 500 : b.targetZone)
							|| a.targetWarpId != (isExit ? 7 : b.targetWarpId)
							|| a.x != b.x || a.y != b.y || a.z != b.z || a.w != b.w || a.h != b.h
							|| a.faceDirection != b.faceDirection || a.transitionType != b.transitionType) {
						throw new IllegalStateException("warp " + i + " corrupted by rewire");
					}
				}
				if (ent.warps.size() == 1) {
					singles++;
				} else {
					multis++;
				}
				System.out.println("  ok: interior " + zone + " (" + ent.warps.size() + " warps, exits " + exits + ")");
			} catch (Exception ex) {
				System.out.println("FAIL interior " + zone + ": " + ex.getMessage());
				fails++;
			}
		}
		System.out.println("interiors: " + interiors.length + " (" + singles + " single-warp, " + multis + " multi, "
				+ multiFloor + " of more than one floor), " + fails + " failure(s)");
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static ZoneEntities entities(int zone) {
		return new ZoneEntities(subfile(zo.getDecompressedEntry(zone), 1));
	}

	/** The retail dump's zones, as the wiring reads them. */
	static final InteriorWirer.Zones RETAIL = new InteriorWirer.Zones() {
		@Override
		public ZoneEntities entities(int zone) {
			return InteriorWirerTest.entities(zone);
		}

		@Override
		public int area(int zone) {
			return InteriorWirerTest.area(zone);
		}
	};

	/** A building's zones as a table: each zone's area and the zones its warps lead to. */
	static InteriorWirer.Zones zones(java.util.Map<Integer, Integer> areas, java.util.Map<Integer, int[]> warps) {
		return new InteriorWirer.Zones() {
			@Override
			public ZoneEntities entities(int zone) throws java.io.IOException {
				if (!warps.containsKey(zone)) {
					throw new java.io.IOException("zone " + zone + " is not in this building");
				}
				return withWarps(warps.get(zone));
			}

			@Override
			public int area(int zone) {
				return areas.getOrDefault(zone, -1);
			}
		};
	}

	/** Entities holding one warp to each zone given, the warp id the warp's own index plus ten. */
	static ZoneEntities withWarps(int... targets) {
		ZoneEntities e = EditorBench.emptyEntities();
		for (int t : targets) {
			ZoneEntities.Warp w = new ZoneEntities.Warp();
			w.targetZone = t;
			w.targetWarpId = 10 + e.warps.size();
			e.warps.add(w);
		}
		return e;
	}

	/**
	 * The workspace's zone reader refuses a zone it holds no file for, in its own words.
	 *
	 * <p>Measured by the 2026-10-12 sweep (InteriorWirer 172, dropped and swallowed, SURVIVED): nothing asked the
	 * production reader what it does for a zone with no file - dropped, a null file reached the zone reader;
	 * swallowed, the floors walk was handed no entities at all. cloneAndWire reads a zone's area from the same file
	 * first, so no public path reaches it today; the reader is asked directly, with no workspace loaded.
	 */
	static void theWorkspaceReaderRefusesAZoneItHoldsNoFileFor() {
		System.out.println("--- the workspace's zone reader refuses a zone it holds no file for, by name");
		String said;
		try {
			java.lang.reflect.Method reader = InteriorWirer.class.getDeclaredMethod("workspace");
			reader.setAccessible(true);
			InteriorWirer.Zones zones = (InteriorWirer.Zones) reader.invoke(null);
			said = "answered " + zones.entities(99999);
		} catch (java.io.IOException ex) {
			said = ex.getMessage();
		} catch (Exception ex) {
			said = "threw " + ex;
		}
		check(said != null && said.contains("zone 99999 is not in the workspace"),
				"a zone with no file is refused by name, not read as nothing: " + said);
	}

	/**
	 * The floors are what the door's floor reaches by stairs, and each clone's warps lead to the door or
	 * to the cloned floors (F015). Only the door's floor was cloned: a two-floor house's stairs led to the
	 * retail upstairs, shared with the original house.
	 */
	static void aBuildingsFloorsAreFoundAndRewired() throws Exception {
		System.out.println("--- a building's floors are found by its stairs, and each clone leads to the clones (F015)");
		java.util.Map<Integer, Integer> areas = new java.util.HashMap<>();
		java.util.Map<Integer, int[]> warps = new java.util.HashMap<>();
		areas.put(1, 2);                  //the town outside
		areas.put(10, 5);                 //the ground floor: the door out, stairs up, a warp nothing can read
		warps.put(10, new int[]{1, 11, 99});
		areas.put(11, 5);                 //the middle floor: down, up
		warps.put(11, new int[]{10, 12});
		areas.put(12, 5);                 //the top floor: down, and a back door outside
		warps.put(12, new int[]{11, 1});
		areas.put(20, 5);                 //another building in the same area, reached by no stair of this one
		warps.put(20, new int[]{1});
		InteriorWirer.Zones building = zones(areas, warps);
		check(InteriorWirer.floors(10, building).equals(java.util.Arrays.asList(10, 11, 12))
				&& InteriorWirer.floors(11, building).equals(java.util.Arrays.asList(11, 10, 12)),
				"the floors are the door's floor and every zone its stairs reach, in the order reached - not the other"
				+ " building in the area, nor a target nothing can read: " + InteriorWirer.floors(10, building));
		String refused = null;
		areas.remove(10);
		try {
			InteriorWirer.floors(10, building);
		} catch (java.io.IOException ex) {
			refused = ex.getMessage();
		}
		areas.put(10, 5);
		check(refused != null && refused.contains("stairs cannot be told"), "and a door floor whose area cannot be read is"
				+ " refused, not taken for one floor: " + refused);

		java.util.Map<Integer, Integer> cloneOf = new java.util.LinkedHashMap<>();
		cloneOf.put(10, 300);
		cloneOf.put(11, 301);
		cloneOf.put(12, 302);
		ZoneEntities ground = withWarps(warps.get(10));
		int groundLeft = InteriorWirer.rewire(ground, 5, cloneOf, 7, 3, true, z -> areas.getOrDefault(z, -1));
		check(ground.warps.get(0).targetZone == 7 && ground.warps.get(0).targetWarpId == 3
				&& ground.warps.get(1).targetZone == 301 && ground.warps.get(1).targetWarpId == 11
				&& ground.warps.get(2).targetZone == 99 && groundLeft == 1,
				"the ground floor's door leads to the placed door, its stairs to the cloned floor - same warp id - and the"
				+ " warp nothing can read is left and counted: " + groundLeft);
		ZoneEntities middle = withWarps(warps.get(11));
		int middleLeft = InteriorWirer.rewire(middle, 5, cloneOf, 7, 3, false, z -> areas.getOrDefault(z, -1));
		check(middle.warps.get(0).targetZone == 300 && middle.warps.get(1).targetZone == 302 && middleLeft == 0,
				"an upper floor with no door keeps both its stairs, on the clones - its warp 0 is not taken for the door");
		ZoneEntities top = withWarps(warps.get(12));
		InteriorWirer.rewire(top, 5, cloneOf, 7, 3, false, z -> areas.getOrDefault(z, -1));
		check(top.warps.get(0).targetZone == 301 && top.warps.get(1).targetZone == 7 && top.warps.get(1).targetWarpId == 3,
				"and an upper floor's own way outside leads to the placed door too");
	}

	/** A zone container of this area holding these entities, as MapResizeTest's game reads one. */
	static byte[] zoneOf(int area, ZoneEntities entities) {
		byte[] header = new byte[ctrmap.MasterTable.ROW];
		header[2] = (byte) area;
		header[3] = (byte) (area >> 8);
		return MapResizeTest.container(0x5a4f, header, entities.assembleData(), HandedGameTest.amx());
	}

	static ZoneEntities read(ctrmap.WorkspaceSession ws, int zone) throws Exception {
		return new ZoneEntities(subfile(MapResizeTest.bytes(ws, ctrmap.gamedef.ArchiveType.ZONE_DATA, zone), 1));
	}

	/**
	 * A cloned floor whose area will not read back, and one whose rewired entities will not be written, are each
	 * refused in words naming the zone, and every slot is put back as it was (the 2026-10-16 assembly, InteriorWirer
	 * 224 and 237, dropped and swallowed, SURVIVED: no suite had a clone fail after it was written). The write fails
	 * on an NPC whose uid is not its place, which assembleData refuses; it left as a bare IllegalStateException
	 * naming no zone. The read-back cannot fail in a game - a donor whose area will not read is no floor - so the
	 * reader is handed in, one that cannot read the ground floor's clone.
	 */
	static void aFloorThatWillNotReadBackOrBeWrittenIsRefusedAndPutBack() throws Exception {
		System.out.println("--- a clone that will not read back or be written is refused, naming its zone, and put back");
		ZoneEntities numbered = withWarps(1);
		numbered.npcs.add(new ZoneEntities.NPC());
		byte[] entities = numbered.assembleData();
		entities[12] = 7;   //the NPC's uid, first in its record after the 12-byte header: 7, at place 0
		byte[] header = new byte[ctrmap.MasterTable.ROW];
		header[2] = 5;
		byte[][] misnumbered = {zoneOf(1, withWarps()), zoneOf(5, withWarps(0, 2)),
			MapResizeTest.container(0x5a4f, header, entities, HandedGameTest.amx()),
			zoneOf(9, withWarps()), zoneOf(9, withWarps()), zoneOf(9, withWarps())};
		String[] refused = new String[2];
		boolean[] putBack = new boolean[2];
		for (int k = 0; k < 2; k++) {
			byte[][] zones = k == 0 ? misnumbered : new byte[][]{zoneOf(1, withWarps()), zoneOf(5, withWarps(0, 2)),
				zoneOf(5, withWarps(1)), zoneOf(9, withWarps()), zoneOf(9, withWarps()), zoneOf(9, withWarps())};
			ctrmap.WorkspaceSession ws = MapResizeTest.game("interior_refused_" + k, zones, new int[]{0, 0, 0, 0, 0, 0},
					new byte[][]{MapResizeTest.matrix(0)}, MapResizeTest.region("region zero"));
			byte[] three = MapResizeTest.bytes(ws, ctrmap.gamedef.ArchiveType.ZONE_DATA, 3);
			byte[] four = MapResizeTest.bytes(ws, ctrmap.gamedef.ArchiveType.ZONE_DATA, 4);
			java.util.List<Integer> slots = new java.util.ArrayList<>(java.util.Arrays.asList(3, 4, 5));
			boolean unreadable = k == 1;
			java.util.function.IntUnaryOperator areaOf = zone -> unreadable && zone == 3 ? -1 : InteriorWirer.zoneArea(zone);
			java.lang.reflect.Method wire = InteriorWirer.class.getDeclaredMethod("cloneAndWire", int.class, int.class,
					int.class, List.class, java.util.function.IntUnaryOperator.class);
			wire.setAccessible(true);
			try {
				wire.invoke(null, 0, 4, 1, slots, areaOf);
				refused[k] = "nothing refused";
			} catch (java.lang.reflect.InvocationTargetException ex) {
				Throwable t = ex.getCause();
				refused[k] = t.getClass().getSimpleName() + ": " + t.getMessage();
			}
			putBack[k] = java.util.Arrays.equals(three, MapResizeTest.bytes(ws, ctrmap.gamedef.ArchiveType.ZONE_DATA, 3))
					&& java.util.Arrays.equals(four, MapResizeTest.bytes(ws, ctrmap.gamedef.ArchiveType.ZONE_DATA, 4));
		}
		check(refused[0].startsWith("IOException: Could not write the rewired interior (zone 4)") && refused[0].contains("uid 7")
				&& putBack[0], "a rewired floor that will not be written is refused naming its zone and why, and both"
				+ " clones are put back: " + refused[0] + (putBack[0] ? "" : " - a slot was left written"));
		check(refused[1].startsWith("IOException: the cloned floor (zone 3)'s area could not be read back") && putBack[1],
				"a clone whose area will not read back is refused naming its zone, and both clones are put back: "
				+ refused[1] + (putBack[1] ? "" : " - a slot was left written"));
	}

	/**
	 * Placing a two-floor house clones both floors into free base slots and wires them to each other and to
	 * the door, leaving the retail house as it was; a building with more floors than slots is refused with
	 * no slot taken (F015). Driven through the workspace, as the Map Builder's Apply drives it.
	 */
	static void everyFloorIsClonedAndItsStairsLeadToTheClones() throws Exception {
		System.out.println("--- placing a two-floor house clones both floors, wired to each other and the door (F015)");
		byte[][] zones = {
			zoneOf(1, withWarps()),          //0: the town the house is placed in
			zoneOf(5, withWarps(0, 2)),      //1: the retail ground floor: the door out, the stairs up
			zoneOf(5, withWarps(1)),         //2: the retail upstairs
			zoneOf(9, withWarps()),          //3, 4, 5: free base slots
			zoneOf(9, withWarps()),
			zoneOf(9, withWarps())};
		ctrmap.WorkspaceSession ws = MapResizeTest.game("interior_floors", zones, new int[]{0, 0, 0, 0, 0, 0},
				new byte[][]{MapResizeTest.matrix(0)}, MapResizeTest.region("region zero"));
		java.util.List<Integer> slots = new java.util.ArrayList<>(java.util.Arrays.asList(3, 4, 5));
		InteriorWirer.Wired wired = InteriorWirer.cloneAndWire(0, 4, 1, slots);
		ZoneEntities ground = read(ws, 3), upstairs = read(ws, 4);
		check(wired.cloneOf.toString().equals("{1=3, 2=4}") && wired.entry() == 3 && wired.retailLinks == 0
				&& slots.equals(java.util.Collections.singletonList(5)),
				"both floors are cloned into the first free slots, the ground floor first, and the slots used are taken: "
				+ wired.cloneOf + ", left " + slots);
		check(ground.warps.get(0).targetZone == 0 && ground.warps.get(0).targetWarpId == 4
				&& ground.warps.get(1).targetZone == 4 && upstairs.warps.get(0).targetZone == 3,
				"the clone's door leads to the placed door, its stairs to the cloned upstairs, and back down to the clone");
		check(read(ws, 1).warps.get(1).targetZone == 2 && read(ws, 2).warps.get(0).targetZone == 1,
				"and the retail house's floors still lead to each other, untouched");
		String refused = null;
		byte[] before = MapResizeTest.bytes(ws, ctrmap.gamedef.ArchiveType.ZONE_DATA, 5);
		try {
			InteriorWirer.cloneAndWire(0, 4, 1, slots);
		} catch (Exception ex) {   //any failure is caught and read: only the refusal in words passes
			refused = String.valueOf(ex.getMessage());
		}
		check(refused != null && refused.contains("2 floors") && slots.equals(java.util.Collections.singletonList(5))
				&& java.util.Arrays.equals(before, MapResizeTest.bytes(ws, ctrmap.gamedef.ArchiveType.ZONE_DATA, 5)),
				"a building with more floors than free slots is refused, no slot taken and none written: " + refused);
		thePainterPointsTheDoorAtTheClone(zones);
	}

	/**
	 * The Map Builder's Apply hands each placed door to {@code MapPainter.privateInterior}: the door is
	 * pointed at the clone of the floor it enters, every slot the floors took is reported, and a building
	 * that cannot be cloned keeps its retail interior and says why (F015).
	 */
	static void thePainterPointsTheDoorAtTheClone(byte[][] zones) throws Exception {
		MapResizeTest.game("interior_floors_painter", zones, new int[]{0, 0, 0, 0, 0, 0},
				new byte[][]{MapResizeTest.matrix(0)}, MapResizeTest.region("region zero"));
		ZoneEntities exterior = withWarps(1);   //the door the painter added, on the retail ground floor
		java.util.List<Integer> slots = new java.util.ArrayList<>(java.util.Arrays.asList(3, 4, 5));
		java.util.List<Integer> taken = new java.util.ArrayList<>();
		String line = ctrmap.MapPainter.privateInterior(exterior, 0, "House", 0, 1, slots, taken::add);
		check(exterior.warps.get(0).targetZone == 3 && exterior.warps.get(0).targetWarpId == 0
				&& taken.equals(java.util.Arrays.asList(3, 4)) && line.contains("its floors zones [3, 4]"),
				"the painter points the door at the cloned ground floor, reports both floors' slots taken, and says so: "
				+ line.trim() + ", taken " + taken);
		ZoneEntities second = withWarps(1);
		String failed = ctrmap.MapPainter.privateInterior(second, 0, "House", 0, 1, slots, taken::add);
		check(second.warps.get(0).targetZone == 1 && taken.size() == 2 && failed.contains("2 floors")
				&& failed.contains("linked retail instead"),
				"and a house it cannot clone keeps its retail interior, nothing reported taken, and the reason said: "
				+ failed.trim());
		slots.clear();
		String none = ctrmap.MapPainter.privateInterior(second, 0, "House", 0, 1, slots, taken::add);
		check(none.contains("No free base zone left") && second.warps.get(0).targetZone == 1,
				"and with no free slot at all the door stays retail and the line says so");

		//and Apply's door wiring is that step, at its one call site - the step above guards nothing if the
		//painter clones by another path, or by none
		String painter = SourceSeamTest.stripComments(new String(java.nio.file.Files.readAllBytes(
				new File("src/ctrmap/MapPainter.java").toPath()), java.nio.charset.StandardCharsets.UTF_8));
		String wiring = CollisionReadTest.body(painter, "static int wireDoorWarps(");
		int clones = painter.split("InteriorWirer\\.cloneAndWire\\(", -1).length - 1;
		check(wiring != null && wiring.contains("privateInterior(") && !wiring.contains("cloneAndWire(") && clones == 1,
				"and the painter's door wiring gives each door its interior through that step, the one place it clones: "
				+ clones + " clone call(s)");
	}

	static int area(int zone) {
		byte[] hdr = subfile(zo.getDecompressedEntry(zone), 0);
		return hdr == null ? -1 : (hdr[2] & 0xFF) | ((hdr[3] & 0xFF) << 8);
	}
}

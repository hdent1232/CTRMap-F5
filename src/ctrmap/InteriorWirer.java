package ctrmap;

import ctrmap.formats.containers.ZO;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static ctrmap.util.LittleEndian.u16;

/**
 * Completes the "place a working building" story: clones a retail interior -
 * every floor of it - into free base slots (index &lt; 536 so scripts keep
 * working) and wires the ROUND TRIP - the cloned interior's exit warp(s) point
 * back at the new exterior door, so walking in AND out works, and its stairs
 * lead to the cloned floors.
 *
 * <p>Exit-warp identification (measured rule): an interior's warp leads
 * OUTSIDE iff its target zone belongs to a DIFFERENT area than the interior
 * itself - stairs to another floor stay within the building's area, the door
 * (and e.g. Lavaridge's hot-spring back exits) target the outdoor town's
 * area. If no warp of the floor the door enters matches (defensive), its warp
 * 0 is treated as the exit (retail interiors' entry warp is always 0 and
 * single-warp rooms use it both ways).
 *
 * <p>EVERY FLOOR, NOT THE GROUND FLOOR (F015). Only the door's floor was
 * cloned, so a two-floor house's stairs led to the RETAIL upstairs - shared with
 * the original house, so editing the placed house's upstairs edited the retail
 * one's, and climbing them came back down into the retail ground floor. The
 * floors are the zones reached from the door's floor by warps into its own area,
 * followed transitively; each is cloned, and a warp between them is pointed at
 * the clone. A building with more floors than there are free slots is refused,
 * not half-cloned.
 */
public class InteriorWirer {

	/** The zones an interior's warps lead to, as the caller can read them. */
	public interface Zones {

		/** The zone's entities; thrown when they cannot be read. */
		ZoneEntities entities(int zone) throws IOException;

		/** The zone's areadataID, or -1 when it cannot be read. */
		int area(int zone);
	}

	/** What a placed building's interior became. */
	public static final class Wired {

		/** Each retail floor and the slot it was cloned into, the door's floor first. */
		public final Map<Integer, Integer> cloneOf;
		/** Warps of the clones left on a retail target: a target or area nothing could read. */
		public final int retailLinks;

		Wired(Map<Integer, Integer> cloneOf, int retailLinks) {
			this.cloneOf = Collections.unmodifiableMap(cloneOf);
			this.retailLinks = retailLinks;
		}

		/** The slot the door now leads into. */
		public int entry() {
			return cloneOf.values().iterator().next();
		}
	}

	/** The warp indexes in an interior that lead outside its own area. Only
	 *  warps whose target zone AND area both read cleanly are classified (a
	 *  sentinel/unreadable target must never cause e.g. stairs to be rewired);
	 *  when nothing classifies, warp 0 is the exit (retail single-warp rooms
	 *  use their entry warp both ways). */
	public static List<Integer> exitWarps(ZoneEntities interior, int interiorArea, java.util.function.IntUnaryOperator areaOfZone) {
		List<Integer> out = leadingOut(interior, interiorArea, areaOfZone);
		if (out.isEmpty() && !interior.warps.isEmpty()) {
			out.add(0);
		}
		return out;
	}

	/** The warps that read cleanly as leading into another area, with no fallback. */
	private static List<Integer> leadingOut(ZoneEntities interior, int interiorArea, java.util.function.IntUnaryOperator areaOfZone) {
		List<Integer> out = new ArrayList<>();
		if (interiorArea >= 0) {
			for (int i = 0; i < interior.warps.size(); i++) {
				int tz = interior.warps.get(i).targetZone;
				if (!isZone(tz)) {
					continue;
				}
				int ta = areaOfZone.applyAsInt(tz);
				if (ta >= 0 && ta != interiorArea) {
					out.add(i);
				}
			}
		}
		return out;
	}

	private static boolean isZone(int target) {
		return target >= 0 && target < 0x8000;
	}

	/**
	 * The interior's floors: the door's floor, then every zone reached from it by a warp into its own
	 * area - the stairs - followed transitively, in the order first reached. A target whose area cannot
	 * be read is not taken for a floor. The door floor's own area unread is refused: with it, stairs
	 * cannot be told from the door, and answering "one floor" would be a guess that reads as a fact.
	 */
	public static List<Integer> floors(int doorFloor, Zones zones) throws IOException {
		List<Integer> out = new ArrayList<>();
		out.add(doorFloor);
		int area = zones.area(doorFloor);
		if (area < 0) {
			throw new IOException("zone " + doorFloor + "'s area could not be read, so its stairs cannot be told from"
					+ " its door");
		}
		for (int i = 0; i < out.size(); i++) {
			for (ZoneEntities.Warp w : zones.entities(out.get(i)).warps) {
				int tz = w.targetZone;
				if (isZone(tz) && !out.contains(tz) && zones.area(tz) == area) {
					out.add(tz);
				}
			}
		}
		return out;
	}

	/**
	 * Points one cloned floor's warps where the placed building needs them: a warp out of the area at
	 * the exterior door, a warp to another floor at that floor's clone. Answers how many it left on a
	 * retail target. {@code doorFloor} says this is the floor the door enters, the only one whose warp
	 * 0 is taken as the exit when nothing reads as one - another floor's warp 0 is its stairs.
	 */
	public static int rewire(ZoneEntities clone, int area, Map<Integer, Integer> cloneOf, int exteriorZone,
			int extWarpIdx, boolean doorFloor, java.util.function.IntUnaryOperator areaOfZone) {
		List<Integer> exits = doorFloor ? exitWarps(clone, area, areaOfZone) : leadingOut(clone, area, areaOfZone);
		int retail = 0;
		for (int i = 0; i < clone.warps.size(); i++) {
			ZoneEntities.Warp w = clone.warps.get(i);
			if (exits.contains(i)) {
				w.targetZone = exteriorZone;
				w.targetWarpId = extWarpIdx;
			} else if (cloneOf.containsKey(w.targetZone)) {
				w.targetZone = cloneOf.get(w.targetZone);
			} else {
				retail++;
			}
		}
		return retail;
	}

	/** areadataID (zone header u16@2) of a zone, or -1. */
	public static int zoneArea(int zoneIndex) {
		try {
			File zf = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex);
			byte[] hdr = new ZO(zf, Workspace.session()).getFile(0);
			return u16(hdr, 2);
		} catch (Exception ex) {
			return -1;
		}
	}

	/** The workspace's zones, as the wiring reads them. */
	static Zones workspace() {
		return new Zones() {
			@Override
			public ZoneEntities entities(int zone) throws IOException {
				File zf = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zone);
				if (zf == null) {
					throw new IOException("zone " + zone + " is not in the workspace");
				}
				return new ZoneEntities(new ZO(zf, Workspace.session()).getFile(1));
			}

			@Override
			public int area(int zone) {
				return zoneArea(zone);
			}
		};
	}

	/**
	 * Clones every floor of {@code interiorDonor} into the first of {@code freeSlots} - taking each
	 * slot it uses out of the list, before it is written - and points every exit warp of the clones at
	 * ({@code exteriorZone}, {@code extWarpIdx}), the door warp the caller is adding to the exterior map,
	 * and every stair at the cloned floor. Refused, with no slot taken, when the building has more floors
	 * than there are slots.
	 */
	public static Wired cloneAndWire(int exteriorZone, int extWarpIdx, int interiorDonor, List<Integer> freeSlots)
			throws IOException {
		return cloneAndWire(exteriorZone, extWarpIdx, interiorDonor, freeSlots, InteriorWirer::zoneArea);
	}

	/**
	 * {@link #cloneAndWire(int, int, int, List)} with the reader of a zone's area handed in, as {@link #rewire} takes
	 * it. A clone's area is read back from the clone, and no game reaches the refusal of one that will not read: a
	 * donor whose area cannot be read is no floor at all (the 2026-10-16 assembly, InteriorWirer 224, SURVIVED). A
	 * suite hands a reader that cannot read the clone back, to ask that the refusal comes and puts everything back.
	 */
	static Wired cloneAndWire(int exteriorZone, int extWarpIdx, int interiorDonor, List<Integer> freeSlots,
			java.util.function.IntUnaryOperator areaOf) throws IOException {
		//ONE UNIT (F227): the clone was written and persisted, and only then were its exits read,
		//rewired and written - so a failure after the clone left an interior whose exits led into
		//the retail exterior. The clones' own units join this one; a failure now puts them all back.
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Wire an interior", Workspace.session())) {
			Wired wired = cloneAndWireIn(exteriorZone, extWarpIdx, interiorDonor, freeSlots, areaOf);
			unit.commit();
			return wired;
		}
	}

	private static Wired cloneAndWireIn(int exteriorZone, int extWarpIdx, int interiorDonor, List<Integer> freeSlots,
			java.util.function.IntUnaryOperator areaOf) throws IOException {
		List<Integer> floors = floors(interiorDonor, workspace());
		if (floors.size() > freeSlots.size()) {
			throw new IOException("its interior is " + floors.size() + " floors (zones " + floors + ") and "
					+ freeSlots.size() + " free base zone(s) are left to clone them into");
		}
		Map<Integer, Integer> cloneOf = new LinkedHashMap<>();
		for (int floor : floors) {
			cloneOf.put(floor, freeSlots.remove(0)); //taken on attempt: a slot is never offered half-written
		}
		for (Map.Entry<Integer, Integer> c : cloneOf.entrySet()) {
			ZoneCloner.cloneIntoSlot(c.getKey(), c.getValue());
		}
		int retail = 0;
		for (Map.Entry<Integer, Integer> c : cloneOf.entrySet()) {
			int slot = c.getValue();
			ZO zo = new ZO(Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, slot), Workspace.session());
			ZoneEntities ent = new ZoneEntities(zo.getFile(1));
			int area = areaOf.applyAsInt(slot);
			if (area < 0) {
				throw new IOException("the cloned floor (zone " + slot + ")'s area could not be read back, so its stairs"
						+ " cannot be told from its door");
			}
			retail += rewire(ent, area, cloneOf, exteriorZone, extWarpIdx, c.getKey() == interiorDonor, areaOf);
			ent.modified = true;
			//ASSEMBLED INSIDE THE REFUSAL (2026-10-16): assembleData never answers null - it throws, unwrapped, out of
			//a method that refuses in IOExceptions - so the null check here was dead and a rewired floor that would not
			//assemble (an NPC whose uid is not its place) left as a bare IllegalStateException naming no zone
			try {
				zo.storeFile(1, ent.assembleData());
			} catch (RuntimeException notWritten) {
				throw new IOException("Could not write the rewired interior (zone " + slot + "): "
					+ ctrmap.util.Bytes.reason(notWritten), notWritten);
			}
		}
		return new Wired(cloneOf, retail);
	}
}

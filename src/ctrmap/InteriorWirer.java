package ctrmap;

import ctrmap.formats.containers.ZO;
import ctrmap.formats.zone.ZoneEntities;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Completes the "place a working building" story: clones a retail interior
 * zone into a free base slot (index &lt; 536 so scripts keep working) and wires
 * the ROUND TRIP - the cloned interior's exit warp(s) point back at the new
 * exterior door, so walking in AND out works.
 *
 * <p>Exit-warp identification (measured rule): an interior's warp leads
 * OUTSIDE iff its target zone belongs to a DIFFERENT area than the interior
 * itself - stairs to another floor stay within the building's area, the door
 * (and e.g. Lavaridge's hot-spring back exits) target the outdoor town's
 * area. If no warp matches (defensive), warp 0 is treated as the exit (retail
 * interiors' entry warp is always 0 and single-warp rooms use it both ways).
 *
 * <p>Multi-warp interiors (2-floor houses) get their DOOR rewired; any
 * further floors still link to the retail upstairs - callers should surface
 * that (the return trip through the door itself is still correct).
 */
public class InteriorWirer {

	/** The warp indexes in an interior that lead outside its own area. Only
	 *  warps whose target zone AND area both read cleanly are classified (a
	 *  sentinel/unreadable target must never cause e.g. stairs to be rewired);
	 *  when nothing classifies, warp 0 is the exit (retail single-warp rooms
	 *  use their entry warp both ways). */
	public static List<Integer> exitWarps(ZoneEntities interior, int interiorArea, java.util.function.IntUnaryOperator areaOfZone) {
		List<Integer> out = new ArrayList<>();
		if (interiorArea >= 0) {
			for (int i = 0; i < interior.warps.size(); i++) {
				int tz = interior.warps.get(i).targetZone;
				if (tz < 0 || tz >= 0x8000) {
					continue;
				}
				int ta = areaOfZone.applyAsInt(tz);
				if (ta >= 0 && ta != interiorArea) {
					out.add(i);
				}
			}
		}
		if (out.isEmpty() && !interior.warps.isEmpty()) {
			out.add(0);
		}
		return out;
	}

	/** areadataID (zone header u16@2) of a zone, or -1. */
	public static int zoneArea(int zoneIndex) {
		try {
			File zf = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, zoneIndex);
			byte[] hdr = new ZO(zf).getFile(0);
			return (hdr[2] & 0xFF) | ((hdr[3] & 0xFF) << 8);
		} catch (Exception ex) {
			return -1;
		}
	}

	/**
	 * Clones {@code interiorDonor} into base slot {@code targetSlot} and points
	 * every exit warp of the clone at ({@code exteriorZone}, {@code extWarpIdx})
	 * - the door warp the caller is adding to the exterior map. Returns the
	 * number of the clone's warps left pointing at retail (0 for single-floor
	 * interiors; stairs of multi-floor houses stay retail-linked).
	 */
	public static int cloneAndWire(int exteriorZone, int extWarpIdx, int interiorDonor, int targetSlot) throws IOException {
		ZoneCloner.cloneIntoSlot(interiorDonor, targetSlot);
		File zf = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, targetSlot);
		ZO zo = new ZO(zf);
		ZoneEntities ent = new ZoneEntities(zo.getFile(1));
		int interiorArea = zoneArea(targetSlot);
		List<Integer> exits = exitWarps(ent, interiorArea, InteriorWirer::zoneArea);
		for (int i : exits) {
			ZoneEntities.Warp w = ent.warps.get(i);
			w.targetZone = exteriorZone;
			w.targetWarpId = extWarpIdx;
		}
		ent.modified = true;
		byte[] assembled = ent.assembleData();
		if (assembled == null || !zo.storeFile(1, assembled)) {
			throw new IOException("Could not write the rewired interior (zone " + targetSlot + ").");
		}
		return ent.warps.size() - exits.size();
	}
}

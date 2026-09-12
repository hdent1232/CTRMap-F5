package ctrmap;

import ctrmap.formats.containers.ZO;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Connects two zones through a warp, both ways.
 *
 * <p>WHY IT EXISTS. A warp carries the zone it leads to and WHICH WARP of that
 * zone it arrives at, and both halves have to agree or the trip only works one
 * way - you walk through a door and cannot walk back. Every existing path that
 * gets this right gets it right for one situation: {@link InteriorWirer} wires a
 * cloned interior to the door that leads into it, and the warp form lets one
 * side be retyped by hand. Wiring two arbitrary zones together was a thing a
 * user did with two forms, two zone loads and no check that the halves matched.
 *
 * <p>IT REFUSES RATHER THAN WIRING SOMETHING BROKEN. A warp index that does not
 * exist, a zone out of range, a link from a warp to itself: each is refused with
 * the reason, before anything is written. A half-written link is worse than no
 * link, because the door exists and swallows the player.
 *
 * <p>IT SAYS WHAT IT WILL BREAK. Repointing a warp that already leads somewhere
 * abandons whatever was on the other end - and the warp there still points BACK
 * at this one, so the old partner becomes a one-way door into a zone that no
 * longer expects it. {@link #wouldBreak} is that list, asked before the user
 * decides rather than discovered afterwards in game.
 */
public final class ZoneLinker {

	private ZoneLinker() {
	}

	/** A zone's entities and the file they came from, so a caller can write them back. */
	private static final class Loaded {

		final ZO zo;
		final ZoneEntities ent;

		Loaded(ZO zo, ZoneEntities ent) {
			this.zo = zo;
			this.ent = ent;
		}
	}

	private static Loaded load(WorkspaceSession ws, int zone) throws IOException {
		File zf = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zone);
		if (zf == null || !zf.isFile()) {
			throw new IOException("Zone " + zone + " could not be read out of the workspace.");
		}
		ZO zo = new ZO(zf, ws);
		return new Loaded(zo, new ZoneEntities(zo.getFile(1)));
	}

	/**
	 * What connecting these two warps would abandon.
	 *
	 * <p>Empty means nothing is lost. Each entry is a sentence naming a warp that
	 * currently leads somewhere else, and where it leads, so the user is deciding
	 * with the facts rather than finding out by walking into it.
	 */
	public static List<String> wouldBreak(WorkspaceSession ws, int zoneA, int warpA,
			int zoneB, int warpB) throws IOException {
		List<String> out = new ArrayList<>();
		int[][] sides = {{zoneA, warpA, zoneB, warpB}, {zoneB, warpB, zoneA, warpA}};
		for (int[] side : sides) {
			Loaded l = load(ws, side[0]);
			if (side[1] < 0 || side[1] >= l.ent.warps.size()) {
				continue; //the range check refuses this separately; nothing to abandon
			}
			ZoneEntities.Warp w = l.ent.warps.get(side[1]);
			if (w.targetZone == ZoneEntities.Warp.NO_TARGET || w.targetZone < 0) {
				continue;
			}
			if (w.targetZone == side[2] && w.targetWarpId == side[3]) {
				continue; //already exactly this link
			}
			out.add("zone " + side[0] + " warp " + side[1] + " currently leads to zone "
					+ w.targetZone + " warp " + w.targetWarpId
					+ ", and that warp is left pointing back here");
		}
		return out;
	}

	/**
	 * Wires {@code zoneA}'s warp to {@code zoneB}'s warp, and back again.
	 *
	 * @param twoWay false wires only A to B, for a one-way drop the user meant
	 * @return what to tell the user
	 */
	public static String link(WorkspaceSession ws, int zoneA, int warpA, int zoneB, int warpB,
			boolean twoWay) throws IOException {
		if (ws == null) {
			throw new IOException("No workspace is loaded.");
		}
		if (zoneA == zoneB && warpA == warpB) {
			throw new IOException("That is the same warp on both ends - a door cannot lead to"
					+ " itself.");
		}
		Loaded a = load(ws, zoneA);
		Loaded b = load(ws, zoneB);
		if (warpA < 0 || warpA >= a.ent.warps.size()) {
			throw new IOException("Zone " + zoneA + " has " + a.ent.warps.size()
					+ " warp(s), so there is no warp " + warpA + " to connect.");
		}
		if (warpB < 0 || warpB >= b.ent.warps.size()) {
			throw new IOException("Zone " + zoneB + " has " + b.ent.warps.size()
					+ " warp(s), so there is no warp " + warpB + " to connect.");
		}
		a.ent.warps.get(warpA).targetZone = zoneB;
		a.ent.warps.get(warpA).targetWarpId = warpB;
		a.ent.modified = true;
		write(a, zoneA, ws);
		if (twoWay) {
			//RELOADED, not the copy taken above: A and B are the same zone when
			//somebody links two doors inside one building, and writing the stale
			//copy would throw the first half of the link away.
			Loaded back = zoneA == zoneB ? load(ws, zoneB) : b;
			back.ent.warps.get(warpB).targetZone = zoneA;
			back.ent.warps.get(warpB).targetWarpId = warpA;
			back.ent.modified = true;
			write(back, zoneB, ws);
		}
		return "Zone " + zoneA + " warp " + warpA + (twoWay ? " and " : " now leads to ")
				+ (twoWay ? "zone " + zoneB + " warp " + warpB + " now lead to each other."
						: "zone " + zoneB + " warp " + warpB + ".")
				+ "\nPack the workspace, then deploy, to walk it.";
	}

	private static void write(Loaded l, int zone, WorkspaceSession ws) throws IOException {
		byte[] assembled = l.ent.assembleData();
		if (assembled == null || !l.zo.storeFile(1, assembled)) {
			throw new IOException("Zone " + zone + "'s warps could not be written back.");
		}
		ws.addPersist(ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zone));
	}
}

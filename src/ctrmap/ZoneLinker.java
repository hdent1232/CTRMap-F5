package ctrmap;

import ctrmap.formats.zone.ZoneEntities;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The doors that lead to a warp - what removing a warp breaks, asked by the Warp form before it removes one.
 *
 * <p>A warp carries the zone it leads to and WHICH WARP of that zone it arrives at, so a door in any zone can
 * depend on a warp in this one. Removing a warp renumbers every warp after it, and every door that led to one of
 * them then leads one warp off - which the user found out in game until the form asked first (census H.39).
 *
 * <p>LINKING TWO WARPS WAS ALSO HERE, as the editor's "Connect zones through a warp", built in place of the owner's
 * request to re-link zones and removed at their word on 2026-10-23; what still links warps - the in-game test
 * world's builder - keeps it beside itself, in ctrmap.tests.WarpLink.
 */
public final class ZoneLinker {

	private ZoneLinker() {
	}

	/** A zone's warps as they are now; null for an index that holds no zone. */
	public interface WarpsOf {

		List<ZoneEntities.Warp> warps(int zone) throws IOException;
	}

	/**
	 * Every door that leads to warp {@code fromWarp} of {@code zone} or to one after it, each said - what removing
	 * that warp breaks (census H.39). A removal renumbers every warp after it, so a door in any zone that led to
	 * one of them leads one warp off from then on, and one that led to the removed warp leads to the next or past
	 * the last; the Warp form's Remove did it with no word, and the user found out in game. A zone whose warps
	 * cannot be read is in the list as unknown, never left out.
	 */
	public static List<String> inboundAt(int zone, int fromWarp, int zoneCount, WarpsOf warpsOf) {
		List<String> out = new ArrayList<>();
		for (int z = 0; z < zoneCount; z++) {
			List<ZoneEntities.Warp> warps;
			try {
				warps = warpsOf.warps(z);
			} catch (IOException | RuntimeException unread) {
				out.add("zone " + z + ": its warps cannot be read (" + Ui.reason(unread)
						+ "), so whether a door there leads here is unknown");
				continue;
			}
			if (warps == null) {
				continue;
			}
			for (int w = 0; w < warps.size(); w++) {
				ZoneEntities.Warp door = warps.get(w);
				if (door.targetZone != zone || door.targetWarpId < fromWarp || (z == zone && w == fromWarp)) {
					continue;
				}
				out.add("zone " + z + " warp " + w + " leads to warp " + door.targetWarpId
						+ (door.targetWarpId == fromWarp ? ", the one removed" : ", which becomes warp " + (door.targetWarpId - 1)));
			}
		}
		return out;
	}

	/**
	 * The workspace's zones' warps as they are now - unpacked edits included, extracting nothing - with the open
	 * zone's {@code live} entities standing for zone {@code open}, whose unsaved edits the files do not hold.
	 */
	public static WarpsOf warpsNow(WorkspaceSession ws, int open, ZoneEntities live) {
		return z -> {
			if (z == open) {
				return live.warps;
			}
			byte[] c = ZoneTables.zoneNow(ws, z);
			if (c == null || c.length < 12 || (c[0] & 0xFF) != 0x5A || (c[1] & 0xFF) != 0x4F) {
				return null;
			}
			byte[] ent = ctrmap.formats.containers.ContainerBytes.subfile(c, 1);
			if (ent == null) {
				throw new IOException("its container holds no entity block");
			}
			return new ZoneEntities(ent).warps;
		};
	}
}

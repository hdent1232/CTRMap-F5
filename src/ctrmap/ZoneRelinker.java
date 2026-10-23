package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Re-link a zone: point it back at a map, area, story text or script it shares with other zones - the one
 * the game shipped with, or another zone's.
 *
 * <p>ASKED FOR ON 2026-09-11, in the owner's words: "add a feature to re-link/connect zones, if for any
 * reason the user would want to do that (say they accidentally unlink a vanilla zone that they want to
 * relink back to its original other vanilla zones it was linked to)". Linked means SHARING: zones that
 * name the same map, area or text are linked, and making a zone independent unlinks it. What was built
 * the next day under this request's name wired warps between zones instead, and claimed the request; this
 * is the request.
 *
 * <p>TWO u16 WRITES PER RESOURCE, and both or neither: the zone's own header in its ZO container and its
 * row of the master zone-header table name the same ids, and the game reads the master. So the two are
 * written in the caller's one unit, and a relink that cannot write the master puts the header back.
 *
 * <p>NOTHING IS DELETED. The private copy a zone leaves - a forked map's matrix and regions, a forked
 * area - stays in the game, used by no zone, and the report says so: packing only ever adds entries, and
 * a zone can be re-linked back to that copy later.
 *
 * <p>Not: GeometryForker.repointMasterRow - that writes the master row alone, for a fork that has already
 * written the header; a relink that wrote one without the other is the half-state the game cannot read.
 * Not: ZoneLinker - that wires a warp in one zone to a warp in another, which is not sharing anything.
 */
public final class ZoneRelinker {

	private ZoneRelinker() {
	}

	/** What one of a zone's resources is now, and was in the game as shipped. */
	public static final class Link {

		public final ZoneResource res;
		/** The id the zone names now. */
		public final int now;
		/** The other zones naming that id now. */
		public final List<Integer> sharingNow;
		/** The id the zone named as shipped, or -1 when the shipped game has no such zone, or none can be read. */
		public final int shipped;
		/** The other zones that named {@link #shipped} as shipped. */
		public final List<Integer> sharingShipped;

		Link(ZoneResource res, int now, List<Integer> sharingNow, int shipped, List<Integer> sharingShipped) {
			this.res = res;
			this.now = now;
			this.sharingNow = sharingNow;
			this.shipped = shipped;
			this.sharingShipped = sharingShipped;
		}

		/** Whether the zone names something other than what it shipped with. */
		public boolean differsFromShipped() {
			return shipped >= 0 && shipped != now;
		}
	}

	/**
	 * Every resource a zone's header names, now and as shipped.
	 *
	 * @param shipped the master table as the game shipped, or null when it cannot be read
	 */
	public static List<Link> links(WorkspaceSession ws, int zone, MasterTable shipped) throws IOException {
		MasterTable now = MasterTable.read(ws);
		if (zone < 0 || zone >= now.zones()) {
			throw new IOException("There is no zone " + zone + " (the game has " + now.zones() + ").");
		}
		boolean wasShipped = shipped != null && zone < shipped.zones();
		List<Link> out = new ArrayList<>();
		for (ZoneResource res : ZoneResource.values()) {
			out.add(new Link(res, now.id(res, zone), now.sharing(res, zone), wasShipped ? shipped.id(res, zone) : -1,
					wasShipped ? shipped.sharing(res, zone) : Collections.<Integer>emptyList()));
		}
		return out;
	}

	/**
	 * The master zone-header table as the game shipped: through {@link WorkspaceSession#pristineArchive} - the
	 * workspace's pristine snapshot, else the sealed backup's verified copy - and null when there is neither.
	 */
	public static MasterTable shipped(WorkspaceSession ws) throws IOException {
		File snap = ws.pristineArchive(ArchiveType.ZONE_DATA);
		if (snap == null) {
			return null;
		}
		GARC g = new GARC(snap);
		int zones = ZoneTables.zoneCount(g, ws.profile());
		return MasterTable.detached(g.getDecompressedEntry(zones), zones);
	}

	/**
	 * Why pointing {@code zone}'s {@code res} at {@code id} cannot be done, or null when it can. Asked before
	 * anything is written, so a refused relink writes nothing.
	 */
	public static String refusal(WorkspaceSession ws, MasterTable now, int zone, ZoneResource res, int id) {
		if (zone < 0 || zone >= now.zones()) {
			return "There is no zone " + zone + " (the game has " + now.zones() + ").";
		}
		if (id < 0 || id > 0xFFFF) {
			return "A " + res.label + " id is a number from 0 to 65535, not " + id + ".";
		}
		if (res == ZoneResource.AREA && id == AreaForker.AD_GLOBAL_TABLE) {
			return "Area " + id + " is the engine's per-area table, not an area a zone can use.";
		}
		if (res.archive != null) {
			GARC g = ws.getArchive(res.archive);
			if (g == null) {
				return "The " + res.label + " archive is not open, so " + res.label + " " + id + " cannot be checked.";
			}
			//WHERE IT REACHES, staged entries counted: a fork not yet packed is a real entry to point at
			int reach = ws.reach(res.archive, g.length);
			if (id >= reach) {
				return "There is no " + res.label + " " + id + ": the game holds " + reach + " (0 to " + (reach - 1) + ").";
			}
		}
		return null;
	}

	/**
	 * Points {@code zone} at the ids in {@code to}, header and master row together, refusing in words before
	 * any write when one cannot be done. Writes inside the caller's unit.
	 *
	 * @return what changed and what was left unused, in sentences
	 */
	public static String relink(WorkspaceSession ws, int zone, Map<ZoneResource, Integer> to) throws IOException {
		MasterTable master = MasterTable.read(ws);
		for (Map.Entry<ZoneResource, Integer> e : to.entrySet()) {
			String no = refusal(ws, master, zone, e.getKey(), e.getValue());
			if (no != null) {
				throw new IOException(no + "\n\nNothing was changed.");
			}
		}
		File zoFile = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zone);
		if (zoFile == null || !zoFile.isFile()) {
			throw new IOException("Zone " + zone + " could not be read out of the workspace, so nothing was changed.");
		}
		byte[] zo = Files.readAllBytes(zoFile.toPath());
		Map<ZoneResource, Integer> was = new EnumMap<>(ZoneResource.class);
		for (Map.Entry<ZoneResource, Integer> e : to.entrySet()) {
			ZoneResource res = e.getKey();
			int before = master.id(res, zone);
			if (before == e.getValue() && res.idIn(zo) == e.getValue()) {
				continue;
			}
			was.put(res, before);
			res.setIn(zo, e.getValue());
			master.set(res, zone, e.getValue());
		}
		if (was.isEmpty()) {
			return "Zone " + zone + " already names those - nothing was changed.";
		}
		WorkspaceUnit.write(zoFile, zo);
		ws.addPersist(zoFile);
		master.write();
		StringBuilder said = new StringBuilder();
		for (Map.Entry<ZoneResource, Integer> e : was.entrySet()) {
			ZoneResource res = e.getKey();
			List<Integer> with = master.sharing(res, zone);
			said.append("Zone ").append(zone).append("'s ").append(res.label).append(": ").append(e.getValue())
					.append(" -> ").append(to.get(res)).append(with.isEmpty() ? ", its own" : ", shared with zone(s) " + with)
					.append(".\n");
			if (master.using(res, e.getValue(), -1).isEmpty()) {
				said.append("  ").append(capital(res.label)).append(" ").append(e.getValue())
						.append(" is now used by no zone. It stays in the game, unused, and re-link can point a zone back at it.\n");
			}
		}
		return said.toString().trim();
	}

	private static String capital(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}

package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.LocationNames;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static ctrmap.formats.LittleEndian.u16;
import static ctrmap.formats.containers.ContainerBytes.subfile;

/**
 * Finds base zones (index &lt; 536) that are candidates to REPURPOSE for custom
 * content. This matters because appended zones (&gt;=536) cannot run field scripts
 * (a hardcoded engine limit - see oras-codebin-re), so interactive custom areas
 * (talking NPCs, signs, triggers) must live in a base slot.
 *
 * <p>Signals used (none is a guarantee - the game also connects some zones by
 * walking/adjacency and by story scripts, which this cannot see, so the caller
 * must VERIFY a pick in-game): a zone with NO incoming warps, empty of placed
 * content, and a placeholder/blank/duplicate name is the safest. A named, empty
 * zone with no incoming warps is often a dungeon interior reached on foot - usable
 * only if you are deliberately replacing that area.
 */
public class ZoneRepurposeScanner {

	/** Zones below this index run field scripts normally (the base game's zones). */
	public static final int BASE_ZONES = ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES;

	public static class Candidate {
		public int index;
		public String name;
		public int npcs;
		public int warpsOut;
		public int triggers;
		public int incomingWarps;
		public int tier; // 0 = safest (placeholder, empty, unreferenced), 1 = likely, 2 = has some use

		public String tierLabel() {
			switch (tier) {
				case 0: return "SAFEST (placeholder/empty, unreferenced)";
				case 1: return "likely free (empty, no incoming warps - verify)";
				default: return "in use (has content or incoming warps)";
			}
		}
	}

	/** What a scan found: the candidates best-first, and the base zones it could not read (F225). */
	public static final class Scan {

		public final List<Candidate> candidates = new ArrayList<>();
		/** "zone N: why" - none of these is a candidate, and none of their warps is counted. */
		public final List<String> unreadable = new ArrayList<>();
	}

	/** The open workspace's candidates, best-first; see {@link #scanZones}. */
	public static List<Candidate> scan() {
		return scanZones(Workspace.isValid() ? Workspace.session() : null).candidates;
	}

	/** The workspace's base zones as they are now - unpacked edits included (F225). */
	public static Scan scanZones(WorkspaceSession s) {
		GARC zo = s == null ? null : s.getArchive(ArchiveType.ZONE_DATA);
		if (zo == null) {
			return new Scan();
		}
		return scanZones(Math.min(BASE_ZONES, zo.length), i -> ZoneTables.zoneNow(s, i),
				parentMap -> LocationNames.getLocName(s, parentMap));
	}

	/**
	 * Scans base zones 0..base-1 and returns candidates sorted best-first (only zones with no
	 * incoming warps are returned; the rest are considered in-use).
	 *
	 * <p>A zone that cannot be read is NOT empty (F225): it was read from the archive as of the last
	 * pack, a zone whose entity block was missing counted as having none - the safest tier - and a
	 * zone whose entities would not read went unsaid. It is listed in {@link Scan#unreadable}, is no
	 * candidate, and its warps, which nobody could count, are said to be uncounted.
	 */
	public static Scan scanZones(int base, ZoneTables.ZoneBytes zones) {
		return scanZones(base, zones, parentMap -> "");
	}

	/** As above, naming each candidate by its parent map through {@code names} - the handed game's location names. */
	public static Scan scanZones(int base, ZoneTables.ZoneBytes zones, java.util.function.IntFunction<String> names) {
		Scan scan = new Scan();
		int[] npcs = new int[base], warps = new int[base], trig = new int[base], parent = new int[base], incoming = new int[base];
		boolean[] valid = new boolean[base];
		for (int i = 0; i < base; i++) {
			byte[] z;
			try {
				z = zones.zone(i);
			} catch (java.io.IOException | RuntimeException unread) {
				scan.unreadable.add("zone " + i + ": " + Ui.reason(unread));
				continue;
			}
			if (z == null || z.length < 12 || (z[0] & 0xFF) != 0x5A || (z[1] & 0xFF) != 0x4F) {
				continue;
			}
			byte[] hdr = subfile(z, 0);
			parent[i] = (hdr != null && hdr.length >= 0x1E) ? ctrmap.formats.zone.ZoneHeader.parentMapOf(hdr) : 0;
			byte[] ent = subfile(z, 1);
			if (ent == null) {
				scan.unreadable.add("zone " + i + ": its container holds no entity block");
				continue;
			}
			ZoneEntities e;
			try {
				e = new ZoneEntities(ent);
			} catch (RuntimeException unread) {
				scan.unreadable.add("zone " + i + ": its entities do not read - " + Ui.reason(unread));
				continue;
			}
			valid[i] = true;
			npcs[i] = e.npcs.size();
			warps[i] = e.warps.size();
			trig[i] = e.triggers1.size() + e.triggers2.size();
			for (ZoneEntities.Warp w : e.warps) {
				if (w.targetZone >= 0 && w.targetZone < base) {
					incoming[w.targetZone]++;
				}
			}
		}

		List<Candidate> out = scan.candidates;
		for (int i = 1; i < base; i++) { // skip 0 (usually a special/system slot)
			if (!valid[i] || incoming[i] > 0) {
				continue;
			}
			Candidate c = new Candidate();
			c.index = i;
			c.name = safeName(names, parent[i]);
			c.npcs = npcs[i];
			c.warpsOut = warps[i];
			c.triggers = trig[i];
			c.incomingWarps = 0;
			boolean empty = npcs[i] == 0 && warps[i] == 0 && trig[i] == 0;
			boolean placeholder = c.name == null || c.name.trim().isEmpty() || c.name.contains("?") || c.name.contains("�");
			c.tier = (empty && placeholder) ? 0 : (empty ? 1 : 2);
			out.add(c);
		}
		out.sort(Comparator.comparingInt((Candidate c) -> c.tier).thenComparingInt(c -> c.index));
		return scan;
	}

	private static String safeName(java.util.function.IntFunction<String> names, int parentMap) {
		try {
			String n = names.apply(parentMap);
			return n == null ? "" : n;
		} catch (RuntimeException ex) {
			return "";
		}
	}
}

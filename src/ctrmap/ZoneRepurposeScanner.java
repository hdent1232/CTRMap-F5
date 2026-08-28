package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.LocationNames;
import ctrmap.formats.zone.ZoneEntities;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

	/**
	 * Scans all base zones and returns candidates sorted best-first (only zones
	 * with no incoming warps are returned; the rest are considered in-use).
	 */
	public static List<Candidate> scan() {
		GARC zo = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		if (zo == null) {
			return new ArrayList<>();
		}
		int base = Math.min(BASE_ZONES, zo.length);
		int[] npcs = new int[base], warps = new int[base], trig = new int[base], parent = new int[base], incoming = new int[base];
		boolean[] valid = new boolean[base];
		for (int i = 0; i < base; i++) {
			byte[] z = zo.getDecompressedEntry(i);
			if (z == null || z.length < 12 || (z[0] & 0xFF) != 0x5A || (z[1] & 0xFF) != 0x4F) {
				continue;
			}
			valid[i] = true;
			byte[] hdr = subfile(z, 0);
			parent[i] = (hdr != null && hdr.length >= 0x1E) ? (u16(hdr, 0x1C) & 0x3FF) : 0;
			byte[] ent = subfile(z, 1);
			if (ent == null) {
				continue;
			}
			ZoneEntities e = new ZoneEntities(ent);
			npcs[i] = e.npcs.size();
			warps[i] = e.warps.size();
			trig[i] = e.triggers1.size() + e.triggers2.size();
			for (ZoneEntities.Warp w : e.warps) {
				if (w.targetZone >= 0 && w.targetZone < base) {
					incoming[w.targetZone]++;
				}
			}
		}

		List<Candidate> out = new ArrayList<>();
		for (int i = 1; i < base; i++) { // skip 0 (usually a special/system slot)
			if (!valid[i] || incoming[i] > 0) {
				continue;
			}
			Candidate c = new Candidate();
			c.index = i;
			c.name = safeName(parent[i]);
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
		return out;
	}

	private static String safeName(int parentMap) {
		try {
			if (LocationNames.textfile == null) {
				LocationNames.loadFromGarc();
			}
			String n = LocationNames.getLocName(parentMap);
			return n == null ? "" : n;
		} catch (RuntimeException ex) {
			return "";
		}
	}

	private static byte[] subfile(byte[] c, int i) {
		int cnt = (c[2] & 0xFF) | ((c[3] & 0xFF) << 8);
		if (i >= cnt) {
			return null;
		}
		int o0 = u32(c, 4 + i * 4), o1 = u32(c, 4 + (i + 1) * 4);
		if (o0 < 0 || o1 > c.length || o1 < o0) {
			return null;
		}
		byte[] o = new byte[o1 - o0];
		System.arraycopy(c, o0, o, 0, o.length);
		return o;
	}

	private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
	private static int u32(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24); }
}

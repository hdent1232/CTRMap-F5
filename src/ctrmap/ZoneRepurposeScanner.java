package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.LocationNames;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static ctrmap.formats.containers.ContainerBytes.subfile;

/**
 * Finds base zones (index &lt; 536) that are candidates to REPURPOSE for custom
 * content. This matters because appended zones (&gt;=536) cannot run field scripts
 * (a hardcoded engine limit - see oras-codebin-re), so interactive custom areas
 * (talking NPCs, signs, triggers) must live in a base slot.
 *
 * <p>Signals used (none is a guarantee - the game also connects some zones by
 * walking/adjacency, which this cannot see, so the caller must VERIFY a pick
 * in-game): a zone with NO incoming warps and no zone script sending the player
 * to it (F225), empty of placed content, and a placeholder/blank/duplicate name is
 * the safest. A named, empty zone with nothing leading in is often a dungeon
 * interior reached on foot - usable only if you are deliberately replacing that area.
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

	/**
	 * What the reusable-zone list says (F225) - its text, apart from the window's scrolling dialog, which no
	 * suite can read: the unreadable zones it names were asserted by nothing (the 2026-10-12 sweep).
	 */
	public static String report(Scan scan) {
		java.util.List<Candidate> cands = scan.candidates;
		int t0 = 0, t1 = 0;
		for (Candidate c : cands) {
			if (c.tier == 0) {
				t0++;
			} else if (c.tier == 1) {
				t1++;
			}
		}
		StringBuilder sb = new StringBuilder();
		sb.append("Base zones (index < " + ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES + ") CAN run scripts - build interactive custom areas\n");
		sb.append("(talking NPCs, signs, triggers) here. Appended zones (" + ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES + "+) can't run scripts.\n\n");
		sb.append(t0).append(" SAFEST (placeholder/blank name, empty, no incoming warps),  ")
		  .append(t1).append(" likely-free.\n\n");
		if (!scan.unreadable.isEmpty()) {
			sb.append(scan.unreadable.size()).append(" zone(s) or script(s) could not be read or counted - an unreadable zone is\n")
					.append("not listed, and a warp or script map change from any of them into a zone below is NOT counted:\n");
			for (int i = 0; i < Math.min(5, scan.unreadable.size()); i++) {
				sb.append("  ").append(scan.unreadable.get(i)).append('\n');
			}
			if (scan.unreadable.size() > 5) {
				sb.append("  ...and ").append(scan.unreadable.size() - 5).append(" more\n");
			}
			sb.append('\n');
		}
		sb.append("A zone a script sends the player to - its own zone's script or a shared one - counts as reached, like a warp.\n");
		sb.append("VERIFY a pick in-game first: some empty, unreferenced zones are dungeon\n");
		sb.append("interiors reached on foot, and named ones are real areas you'd be replacing.\n\n");
		sb.append(String.format("%-5s %-26s %-4s %-5s %-4s %s%n", "zone", "name", "NPCs", "warp", "trg", "status"));
		sb.append("---------------------------------------------------------------------------\n");
		for (Candidate c : cands) {
			String nm = c.name.length() > 25 ? c.name.substring(0, 25) : c.name;
			sb.append(String.format("%-5d %-26s %-4d %-5d %-4d %s%n", c.index, nm, c.npcs, c.warpsOut, c.triggers, c.tierLabel()));
		}
		sb.append("\nWorkflow: pick a zone -> Empty zone -> Clone your template map into it\n");
		sb.append("(keep 'own map' checked) -> Rename zone -> add your NPCs. Scripts will work.");
		return sb.toString();
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
		int base = Math.min(BASE_ZONES, zo.length);
		int count;
		try {
			count = Math.max(base, ZoneTables.zoneCount(zo));
		} catch (java.io.IOException unread) {
			count = zo.length;   //every entry is asked; one that is no zone is passed over as such
		}
		return scanZones(base, count, i -> ZoneTables.zoneNow(s, i),
				parentMap -> LocationNames.getLocName(s, parentMap), sharedReach(s));
	}

	/**
	 * Where the scripts no zone owns send the player (F225), and what of them could not be read.
	 *
	 * <p>MEASURED 2026-10-10 on the ORAS dump: the shared-script archive holds 85 scripts, every one reads,
	 * and 15 of them send the player to 37 zones through {@code _MapChangeCore}, one more to a zone it
	 * computes. A scan that read only the zones' own scripts offered a zone only they reach as safe to
	 * overwrite.
	 */
	public static final class SharedReach {

		/** The zones a shared script names as a destination, one per call that names one. */
		public final List<Integer> zones = new ArrayList<>();
		/** Beside each of {@link #zones}, the shared script that names it. */
		public final List<Integer> from = new ArrayList<>();
		/** "shared script N: why" - each a destination nobody could count, never "no script goes there". */
		public final List<String> unread = new ArrayList<>();
	}

	/**
	 * The workspace's shared scripts. A game whose shared-script archive is not known, or one that did not
	 * open, is said in {@link SharedReach#unread} - it is not a game whose shared scripts go nowhere.
	 */
	public static SharedReach sharedReach(WorkspaceSession s) {
		GARC g = s == null ? null : s.getArchive(ArchiveType.SHARED_SCRIPTS);
		if (g == null) {
			SharedReach r = new SharedReach();
			r.unread.add("the shared-script archive: " + (s == null || s.profile().archivePath(ArchiveType.SHARED_SCRIPTS) == null
					? "where this game keeps them is not measured" : "their archive did not open"));
			return r;
		}
		return sharedReach(g.length, g::getDecompressedEntry);
	}

	/** Where shared scripts 0..count-1 send the player, each read through {@code scripts}. */
	public static SharedReach sharedReach(int count, ZoneTables.ZoneBytes scripts) {
		SharedReach r = new SharedReach();
		for (int i = 0; i < count; i++) {
			ctrmap.formats.scripts.ZoneScriptAnalyzer.MapChanges mc;
			try {
				mc = ctrmap.formats.scripts.ZoneScriptAnalyzer.mapChangesOfScript(scripts.zone(i));
			} catch (java.io.IOException | RuntimeException unread) {
				r.unread.add("shared script " + i + ": " + Ui.reason(unread));
				continue;
			}
			for (int to : mc.zones) {
				r.zones.add(to);
				r.from.add(i);
			}
			if (mc.computed > 0) {
				r.unread.add("shared script " + i + ": " + mc.computed + " map change(s) whose destination is computed");
			}
		}
		return r;
	}

	/**
	 * Scans base zones 0..base-1 and returns candidates sorted best-first (only zones with no
	 * incoming warps are returned; the rest are considered in-use).
	 *
	 * <p>A zone that cannot be read is NOT empty (F225): it was read from the archive as of the last
	 * pack, a zone whose entity block was missing counted as having none - the safest tier - and a
	 * zone whose entities would not read went unsaid. It is listed in {@link Scan#unreadable}, is no
	 * candidate, and its warps, which nobody could count, are said to be uncounted.
	 *
	 * <p>{@code shared} is where the scripts no zone owns send the player - no overload leaves it out, so no
	 * caller can scan as though there were none.
	 *
	 * <p>Each candidate is named by its parent map through {@code names} - the handed game's location names.
	 *
	 * <p>For a game holding {@code count} zones: the candidates are still base zones, and the warps and
	 * script map changes of EVERY zone are counted into them (census H.57) - a door from an added zone, past the
	 * base, into a base zone did not count, and that zone was offered as safe to overwrite.
	 */
	public static Scan scanZones(int base, int count, ZoneTables.ZoneBytes zones, java.util.function.IntFunction<String> names,
			SharedReach shared) {
		Scan scan = new Scan();
		count = Math.max(base, count);
		int[] npcs = new int[count], warps = new int[count], trig = new int[count], parent = new int[count], incoming = new int[count];
		boolean[] valid = new boolean[count];
		for (int i = 0; i < count; i++) {
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
			//AND WHERE ITS SCRIPT SENDS THE PLAYER (F225): a zone reached only by a script's map change had no
			//incoming warp, so it was offered as safe to overwrite
			ctrmap.formats.scripts.ZoneScriptAnalyzer.MapChanges mc;
			try {
				mc = ctrmap.formats.scripts.ZoneScriptAnalyzer.mapChangesOfZone(z);
			} catch (RuntimeException unread) {
				scan.unreadable.add("zone " + i + ": " + Ui.reason(unread) + ", so the zones it sends the player to are not counted");
				continue;
			}
			//a destination computed at run time could be any zone: said, and this zone is no candidate - but
			//its warps and the destinations its script does name still count
			if (mc.computed > 0) {
				scan.unreadable.add("zone " + i + ": " + mc.computed + " script map change(s) whose destination is computed are not counted");
			}
			valid[i] = mc.computed == 0;
			npcs[i] = e.npcs.size();
			warps[i] = e.warps.size();
			trig[i] = e.triggers1.size() + e.triggers2.size();
			for (ZoneEntities.Warp w : e.warps) {
				if (w.targetZone >= 0 && w.targetZone < base) {
					incoming[w.targetZone]++;
				}
			}
			for (int to : mc.zones) {
				if (to >= 0 && to < base) {
					incoming[to]++;
				}
			}
		}

		//AND WHERE THE SHARED SCRIPTS SEND THE PLAYER (F225): no zone owns them, so no zone above read them
		for (int to : shared.zones) {
			if (to >= 0 && to < base) {
				incoming[to]++;
			}
		}
		for (String u : shared.unread) {
			scan.unreadable.add(u + ", so the zones it sends the player to are not counted");
		}

		List<Candidate> out = scan.candidates;
		for (int i = 1; i < base; i++) { // skip 0 (usually a special/system slot)
			if (!valid[i] || incoming[i] > 0) {
				continue;
			}
			Candidate c = new Candidate();
			c.index = i;
			String name = readName(names, parent[i], i, scan);
			c.name = name == null ? "(its name could not be read)" : name;
			c.npcs = npcs[i];
			c.warpsOut = warps[i];
			c.triggers = trig[i];
			c.incomingWarps = 0;
			boolean empty = npcs[i] == 0 && warps[i] == 0 && trig[i] == 0;
			//A NAME NOBODY COULD READ IS NOT A PLACEHOLDER (census H.57): it answered "", the placeholder's own
			//look, and the zone was offered as the SAFEST to overwrite
			boolean placeholder = name != null && (name.trim().isEmpty() || name.contains("?") || name.contains("�"));
			c.tier = (empty && placeholder) ? 0 : (empty ? 1 : 2);
			out.add(c);
		}
		out.sort(Comparator.comparingInt((Candidate c) -> c.tier).thenComparingInt(c -> c.index));
		return scan;
	}

	/** Zone {@code zone}'s name, "" when it has none; null - and said - when it cannot be read. */
	private static String readName(java.util.function.IntFunction<String> names, int parentMap, int zone, Scan scan) {
		try {
			String n = names.apply(parentMap);
			return n == null ? "" : n;
		} catch (RuntimeException unread) {
			scan.unreadable.add("zone " + zone + ": its name cannot be read (" + Ui.reason(unread)
					+ "), so it is not taken for a placeholder");
			return null;
		}
	}
}

package ctrmap.tests;

import ctrmap.WorkspaceSession;
import ctrmap.WorkspaceUnit;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import ctrmap.humaninterface.SaveHistory;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires a warp in one zone to a warp in another, both ways - for the in-game test world's builder, which wires its
 * doors with it, and for the suites that hold it to both ends or neither (F227).
 *
 * <p>WHY IT LIVES WITH THE SUITES. The editor offered this as "Connect zones through a warp", built on 2026-09-12 in
 * place of the owner's request to RE-LINK zones (share a map or area again) and claimed as that request; the owner
 * had it removed on 2026-10-23. What is left uses it is TestWorldBuilder, so it moved out of the shipped program to
 * sit beside that, rather than staying in it reached only by suites.
 *
 * <p>IT REFUSES RATHER THAN WIRING SOMETHING BROKEN, and says what it would abandon: a warp that already leads
 * somewhere leaves its old partner pointing back at a door that has moved on.
 *
 * <p>Not: ctrmap.ZoneLinker - that keeps only what the program uses, the doors a removed warp breaks; nor
 * ctrmap.ZoneRelinker, which re-links what zones SHARE and touches no warp.
 */
final class WarpLink {

	private WarpLink() {
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
	 * What connecting these two warps would abandon: empty means nothing is lost, and each entry names a warp that
	 * currently leads somewhere else, and where.
	 */
	static List<String> wouldBreak(WorkspaceSession ws, int zoneA, int warpA, int zoneB, int warpB) throws IOException {
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
	 * @param twoWay false wires only A to B, a one-way drop
	 * @return what was done, in a sentence
	 */
	static String link(WorkspaceSession ws, int zoneA, int warpA, int zoneB, int warpB, boolean twoWay) throws IOException {
		if (ws == null) {
			throw new IOException("No workspace is loaded.");
		}
		if (zoneA == zoneB && warpA == warpB) {
			throw new IOException("That is the same warp on both ends - a door cannot lead to itself.");
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
		//ONE UNIT (F227): zone A was written and then zone B, so a failure on B left a one-way
		//door - worse than none. Now B failing puts A back.
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Link warps", ws)) {
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
			unit.commit();
		}
		return "Zone " + zoneA + " warp " + warpA + (twoWay ? " and " : " now leads to ")
				+ (twoWay ? "zone " + zoneB + " warp " + warpB + " now lead to each other."
						: "zone " + zoneB + " warp " + warpB + ".")
				+ "\nPack the workspace, then deploy, to walk it.";
	}

	/** {@link #link}, kept as one change of {@code ops}'s history, the zones read again on Undo and Redo (F209c). */
	static String linkKept(WorkspaceSession ws, int za, int wa, int zb, int wb, boolean both, SaveHistory ops,
			Runnable reread) throws IOException {
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Link warps in zone " + za + " and zone " + zb, ws)) {
			String said = link(ws, za, wa, zb, wb, both);
			WorkspaceUnit.inMemory(reread, reread);
			ops.keep(unit.commitKeeping());
			return said;
		}
	}

	private static void write(Loaded l, int zone, WorkspaceSession ws) throws IOException {
		byte[] assembled = l.ent.assembleData();
		if (assembled == null) {
			throw new IOException("Zone " + zone + "'s warps could not be rebuilt.");
		}
		try {
			l.zo.storeFile(1, assembled);
		} catch (RuntimeException notWritten) {
			throw new IOException("Zone " + zone + "'s warps could not be written back: "
				+ ctrmap.util.Bytes.reason(notWritten), notWritten);
		}
		ws.addPersist(ws.getWorkspaceFile(ArchiveType.ZONE_DATA, zone));
	}
}

package ctrmap;

import ctrmap.gamedef.ArchiveType;
import ctrmap.humaninterface.SaveHistory;
import java.io.File;
import java.io.IOException;

/**
 * The workspace's own operations, each run as one change and kept so it can be taken back and put again (F209c).
 *
 * <p>Measured on 2026-10-09, the window's zone and area actions - making a zone independent, renaming and
 * emptying one, the blank canvas, setting up a facility, resizing a map, importing a map model or an OBJ,
 * removing the added zones and taking their patch out of code.ips - wrote the workspace through units that
 * committed and kept nothing: 11 of them, and nothing could take one back. Each runs here inside one unit the
 * operation's own units join, registers how the window reads the workspace again when it is put back or put
 * again, and hands the history what the unit kept. The history is the session's, not the open zone's: most of
 * these change which zones there are, or which one is open.
 *
 * <p>Not: {@link WorkspaceUnit} - that makes a change whole and, asked, keeps it; this is what each operation
 * hands it and what the window is told when it moves. Nor the operations themselves - ZoneIndependence,
 * ZoneManager and the rest decide what to write, and say nothing about history.
 */
public final class WorkspaceOps {

	private WorkspaceOps() {
	}

	/** Gives zone {@code zone} its own map and/or area, kept. */
	public static ZoneIndependence independence(WorkspaceSession ws, int zone, boolean map, boolean area, SaveHistory ops,
			Runnable reread) throws IOException {
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Make zone " + zone + " independent", ws)) {
			ZoneIndependence done = ZoneIndependence.make(ws, zone, map, area);
			WorkspaceUnit.inMemory(reread, reread);
			ops.keep(unit.commitKeeping());
			return done;
		}
	}

	/** Renames zone {@code idx}, on every zone sharing its name line when {@code everyZone}, kept. */
	public static ZoneManager.RenameResult rename(WorkspaceSession ws, int idx, String name, boolean everyZone,
			SaveHistory ops, Runnable reread) throws Exception {
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Rename zone " + idx, ws)) {
			ZoneManager.RenameResult r = everyZone ? ZoneManager.renameZone(ws, idx, name, true)
					: ZoneManager.renameZone(ws, idx, name);
			WorkspaceUnit.inMemory(reread, reread);
			ops.keep(unit.commitKeeping());
			return r;
		}
	}

	/** Takes every placed object out of zone {@code idx}, kept. */
	public static int clearZone(WorkspaceSession ws, int idx, SaveHistory ops, Runnable reread) throws Exception {
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Empty zone " + idx, ws)) {
			int removed = ZoneManager.clearZone(ws, idx);
			WorkspaceUnit.inMemory(reread, reread);
			ops.keep(unit.commitKeeping());
			return removed;
		}
	}

	/** Paints zone {@code zone} a blank canvas of mesh {@code groundMesh}, its own map forked first, kept. */
	public static GeometryForker.Canvas blankCanvas(WorkspaceSession ws, int zone, int groundMesh, SaveHistory ops,
			Runnable reread) throws Exception {
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Blank map canvas of zone " + zone, ws)) {
			GeometryForker.Canvas canvas = GeometryForker.blankCanvas(zone, groundMesh);
			WorkspaceUnit.inMemory(reread, reread);
			ops.keep(unit.commitKeeping());
			return canvas;
		}
	}

	/** Clones zone {@code src} into slot {@code dst} and gives the copy its own map, kept. */
	public static void cloneWithOwnMap(WorkspaceSession ws, int src, int dst, SaveHistory ops, Runnable reread)
			throws Exception {
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Clone zone " + src + " into " + dst, ws)) {
			ZoneCloner.cloneIntoSlot(src, dst);
			GeometryForker.ensurePrivate(dst);
			WorkspaceUnit.inMemory(reread, reread);
			ops.keep(unit.commitKeeping());
		}
	}

	/** Clones zone {@code src} into slot {@code dst}, kept. */
	public static void clone(WorkspaceSession ws, int src, int dst, SaveHistory ops, Runnable reread) throws IOException {
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Clone zone " + src + " into " + dst, ws)) {
			ZoneCloner.cloneIntoSlot(src, dst);
			WorkspaceUnit.inMemory(reread, reread);
			ops.keep(unit.commitKeeping());
		}
	}

	/** Gives zone {@code zone} its own private map, kept. */
	public static GeometryForker.ForkResult forkMap(WorkspaceSession ws, int zone, SaveHistory ops, Runnable reread)
			throws IOException {
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Give zone " + zone + " its own map", ws)) {
			GeometryForker.ForkResult r = GeometryForker.ensurePrivate(zone);
			WorkspaceUnit.inMemory(reread, reread);
			ops.keep(unit.commitKeeping());
			return r;
		}
	}

	/** Wires warp {@code wa} of zone {@code za} to warp {@code wb} of zone {@code zb}, and back when {@code both}, kept. */
	public static String link(WorkspaceSession ws, int za, int wa, int zb, int wb, boolean both, SaveHistory ops,
			Runnable reread) throws IOException {
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Connect zone " + za + " and zone " + zb, ws)) {
			String said = ZoneLinker.link(ws, za, wa, zb, wb, both);
			WorkspaceUnit.inMemory(reread, reread);
			ops.keep(unit.commitKeeping());
			return said;
		}
	}

	/** Resizes zone {@code zone}'s map to {@code w} x {@code h} regions, kept. */
	public static MapResizer.ResizeResult resize(WorkspaceSession ws, int zone, int w, int h, SaveHistory ops,
			Runnable reread) throws Exception {
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Resize the map of zone " + zone, ws)) {
			MapResizer.ResizeResult r = MapResizer.resize(ws, zone, w, h);
			WorkspaceUnit.inMemory(reread, reread);
			ops.keep(unit.commitKeeping());
			return r;
		}
	}

	/** Writes {@code model} as the map model of the region container {@code grFile} holds, kept. */
	public static void storeMapModel(WorkspaceSession ws, String name, File grFile, ctrmap.formats.containers.GR gr,
			byte[] model, SaveHistory ops, Runnable reread) {
		try (WorkspaceUnit unit = WorkspaceUnit.begin(name, ws)) {
			gr.storeFile(1, model);
			ws.addPersist(grFile);
			WorkspaceUnit.inMemory(reread, reread);
			ops.keep(unit.commitKeeping());
		}
	}

	/**
	 * Removes the added zones and gives their area ids back, kept: the zone archive and the NPC registries are
	 * copied before they are rewritten, and put back they are read again before the window is.
	 */
	public static ZoneRemover.Removed removeAddedZones(WorkspaceSession ws, SaveHistory ops, Runnable reread)
			throws IOException {
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Remove the added zones", ws)) {
			ZoneRemover.Removed done = ZoneRemover.remove(ws);
			Runnable again = () -> {
				ws.reloadGARC(ArchiveType.ZONE_DATA);
				ws.reloadGARC(ArchiveType.NPC_REGISTRIES);
				reread.run();
			};
			WorkspaceUnit.inMemory(again, again);
			ops.keep(unit.commitKeeping());
			return done;
		}
	}

	/** Takes the zone-limit patch out of the code.ips at {@code ips}, the others kept, kept. */
	public static int stripZoneLimit(WorkspaceSession ws, File ips, SaveHistory ops) throws IOException {
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Take the zone-limit patch out of " + ips.getName(), ws)) {
			int left = ctrmap.formats.codepatch.CodeIps.strip(ips, ctrmap.formats.codepatch.ZoneLimitPatch.owned(),
					WorkspaceUnit::touching);
			ops.keep(unit.commitKeeping());
			return left;
		}
	}
}

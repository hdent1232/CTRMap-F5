package ctrmap.humaninterface;

import ctrmap.AreaForker;
import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.formats.zone.Zone;
import java.awt.Component;
import java.io.IOException;
import javax.swing.JOptionPane;

/**
 * The shared "make this zone's area private first" gate. Atmosphere, water
 * animations, props and NPC models all live in the zone's AREA, which 77% of
 * retail zones share with other zones - so every edit to one of them asks to
 * fork the area first, exactly as loading a zone offers to fork a shared MAP.
 *
 * <p>Fork-at-the-edit rather than fork-at-zone-load: a shared area is only a
 * problem when something is about to be written, and prompting on every zone
 * load would nag constantly.
 *
 * <p>The question goes through {@link ctrmap.Ui} rather than straight to
 * JOptionPane. It is the last thing standing between a paint and 400-odd maps
 * it was never meant to touch, and while it was a bare option dialog nothing on
 * the far side of it could be tested at all: a headless suite got a
 * HeadlessException instead of an answer, so "the user cancelled and nothing
 * was written" was unreachable code, and deleting the refusal it leads to left
 * the whole battery green. Ui also answers "nobody is there" with CLOSED, which
 * is why a null pick below means cancel and never consent.
 */
public class AreaForkPrompt {

	/**
	 * Returns what the caller should edit, or null when they cancelled.
	 *
	 * <p>{@link AreaForker.ForkResult#newArea} is the area id to write into:
	 * the zone's own area when it is already private, a freshly forked private
	 * copy when the user accepts, the shared id when they decline to fork.
	 * {@link AreaForker.ForkResult#forked} says whether this call appended an
	 * area - the caller hands the same result to {@link #packIfForked} after
	 * applying its edit, so the new area lands in the archive. Null means the
	 * user cancelled, or nobody was there to answer, and nothing was written.
	 *
	 * <p>The result is handed back rather than remembered: this used to leave
	 * "did I fork?" in a static that {@link #packIfForked} read later, and a
	 * caller that packed on its own (the tile painter does) left it set, so the
	 * next unrelated caller's pack-if-forked packed for a fork it never made.
	 * Its sibling {@link GeometryForker#ensurePrivate} returns the same shape.
	 *
	 * <p>{@code loaded} is the zone owner the caller was handed: the sharers
	 * are named from its table.
	 *
	 * <p>THE AREA IS THE ONE THE ZONE'S FILE NAMES (F255). It was handed in - the open zone's header, in memory -
	 * while the sharers were read from the master table, so a zone whose open copy still said the area it had been
	 * forked away from was answered "private" and handed that old, shared area to edit. And a fork no longer
	 * repoints the open zone in memory: setting its header's id left its area container, the map view's prop
	 * registry and the camera form on the old area, and edits went on into it. The open zone is read again instead
	 * - by the caller once the fork is packed ({@code packIfForked(fork, reread)}), and by Undo and Redo of the fork.
	 *
	 * <p>KEPT (F209c): a fork asked for outside any change is a change of its own, handed to {@code ops}; one asked
	 * for inside a change - the painter's Apply - is part of that change, and that change keeps it.
	 */
	public static AreaForker.ForkResult ensurePrivate(LoadedZone loaded, Component parent, int zoneIndex, String whatEdit,
			SaveHistory ops, Runnable reread) {
		if (loaded == null) {
			throw new IllegalArgumentException("AreaForkPrompt.ensurePrivate must be handed the LoadedZone");
		}
		if (ops == null || reread == null) {
			throw new IllegalArgumentException("an area fork must be handed the history that keeps it and what reads the"
					+ " zone again");
		}
		if (!Workspace.isValid()) {
			ctrmap.Ui.error(parent, "Load a workspace first (Options > Workspace settings).", "Shared area");
			return null;
		}
		int currentArea;
		try {
			currentArea = AreaForker.currentArea(zoneIndex);
		} catch (IOException | RuntimeException unread) {
			ctrmap.Ui.error(parent, "Could not read which area zone " + zoneIndex + " is on:\n" + ctrmap.Ui.reason(unread)
					+ "\n\nNothing was changed.", "Shared area");
			return null;
		}
		//Ask whether THIS GAME can fork an area, not whether it is ORAS. The
		//prompt is an offer, so an unsupported game must not be offered it:
		//accepting would run AreaForker, whose four archive offsets and global
		//per-area table rewrite were all measured on ORAS, and which refuses
		//outright for anything else. Offering a button that can only produce a
		//refusal is worse than not offering it.
		if (!Workspace.profile().supports(ctrmap.gamedef.GameProfile.Feature.AREA_FORK)) {
			return unforked(zoneIndex, currentArea);
		}
		int sharers;
		try {
			sharers = AreaForker.areaSharers(zoneIndex);
		} catch (Exception ex) {
			//ASKED, NOT WAVED THROUGH (F211, census M.121): this answered "not shared" and let the
			//edit proceed - the one guard between an edit and 400-odd maps, open whenever it could
			//not look. Uncertainty is a question; a closed dialog is Cancel, as everywhere here
			String[] unsure = {"Edit this area anyway", "Cancel"};
			Object pick = ctrmap.Ui.input(parent,
					"Could not check whether this zone's area is shared with other zones:\n"
					+ ctrmap.Ui.reason(ex) + "\n\n"
					+ "If it is shared, " + whatEdit + " here would change those zones too.",
					"Shared area", JOptionPane.WARNING_MESSAGE, unsure, unsure[1]);
			return unsure[0].equals(pick) ? unforked(zoneIndex, currentArea) : null;
		}
		if (sharers == 0) {
			return unforked(zoneIndex, currentArea); //already this zone's own area
		}
		String[] opts = {"Give this zone its own area", "Edit the shared area anyway", "Cancel"};
		Object pick = ctrmap.Ui.input(parent,
				"This zone SHARES its area with " + sharers + " other zone(s)" + namedSharers(loaded, zoneIndex, currentArea) + ".\n"
				+ "An area holds the atmosphere (fog/lighting), water animations, prop\n"
				+ "registry and NPC models - so " + whatEdit + " here would change those zones too.\n\n"
				+ "Give this zone its OWN private area first? (Recommended. Pure data -\n"
				+ "the copy starts identical, so nothing looks different until you edit it.)",
				"Shared area", JOptionPane.QUESTION_MESSAGE, opts, opts[0]);
		if (pick == null || opts[2].equals(pick)) {
			return null; //cancelled, or nobody there to answer
		}
		if (opts[1].equals(pick)) {
			return unforked(zoneIndex, currentArea); //deliberate game-wide edit
		}
		try {
			return fork(zoneIndex, ops, reread);
		} catch (Exception ex) {
			ctrmap.Ui.error(parent,
					"Could not give this zone its own area:\n" + ctrmap.Ui.reason(ex)
					+ "\n\nThe edit was not applied.",
					"Shared area");
			return null;
		}
	}

	/**
	 * Gives zone {@code zoneIndex} its own area: a change of its own kept in {@code ops}, Undo and Redo reading the
	 * zone again - or, asked inside a change, part of that one.
	 */
	private static AreaForker.ForkResult fork(int zoneIndex, SaveHistory ops, Runnable reread) throws IOException {
		boolean partOfAnother = ctrmap.WorkspaceUnit.current() != null;
		try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Give zone " + zoneIndex + " its own area",
				Workspace.session())) {
			AreaForker.ForkResult r = AreaForker.forkArea(zoneIndex);
			if (partOfAnother) {
				unit.commit();   //joined: the change it is part of keeps it
				return r;
			}
			ctrmap.WorkspaceUnit.inMemory(reread, reread);
			ops.keep(unit.commitKeeping());
			return r;
		}
	}

	/** "Edit this area as it is": the id the zone's file names, nothing appended. */
	private static AreaForker.ForkResult unforked(int zoneIndex, int area) {
		AreaForker.ForkResult r = new AreaForker.ForkResult();
		r.zoneIndex = zoneIndex;
		r.oldArea = area;
		r.newArea = area;
		r.forked = false;
		return r;
	}

	/**
	 * Packs when the given {@link #ensurePrivate} result forked, so the new
	 * area lands in the archive; otherwise just runs onDone. A caller that
	 * packs on its own anyway (the tile painter's Apply) need not call this.
	 */
	public static void packIfForked(AreaForker.ForkResult fork, Runnable onDone) {
		if (fork != null && fork.forked) {
			Workspace.packWorkspace(onDone);
		} else if (onDone != null) {
			onDone.run();
		}
	}

	/** " (Route 110, Route 111 and 3 more)" - concrete beats a bare count. */
	private static String namedSharers(LoadedZone loaded, int zoneIndex, int area) {
		try {
			if (loaded.count() == 0) {
				return "";
			}
			StringBuilder sb = new StringBuilder();
			int shown = 0, extra = 0;
			for (int i = 0; i < loaded.count(); i++) {
				Zone other = loaded.at(i);
				if (i == zoneIndex || other.header == null   //a slot always holds a zone (F208)
						|| other.header.areadataID != area) {
					continue;
				}
				if (shown < 4) {
					if (shown > 0) {
						sb.append(", ");
					}
					sb.append(ctrmap.formats.text.LocationNames.getLocName(ctrmap.Workspace.session(), loaded.at(i).header.parentMap));
					shown++;
				} else {
					extra++;
				}
			}
			if (shown == 0) {
				return "";
			}
			return " (" + sb + (extra > 0 ? " and " + extra + " more" : "") + ")";
		} catch (Exception ex) {
			return "";
		}
	}
}

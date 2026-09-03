package ctrmap.humaninterface;

import ctrmap.AreaForker;
import ctrmap.Workspace;
import java.awt.Component;
import javax.swing.JOptionPane;

import static ctrmap.CtrmapMainframe.*;

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

	/** True when the last {@link #ensurePrivate} call actually appended an area
	 *  (the caller should pack the workspace after applying its edit). */
	public static boolean lastForked = false;

	/**
	 * Returns the area id the caller should edit: the zone's own area when it
	 * is already private, a freshly forked private copy when the user accepts,
	 * the shared id when they decline, or -1 when they cancel.
	 */
	public static int ensurePrivate(Component parent, int zoneIndex, int currentArea, String whatEdit) {
		lastForked = false;
		if (!Workspace.isOA() || zoneIndex < 0) {
			return currentArea;
		}
		int sharers;
		try {
			sharers = AreaForker.areaSharers(zoneIndex);
		} catch (Exception ex) {
			return currentArea; //cannot tell - let the edit proceed as before
		}
		if (sharers == 0) {
			return currentArea; //already this zone's own area
		}
		String[] opts = {"Give this zone its own area", "Edit the shared area anyway", "Cancel"};
		Object pick = ctrmap.Ui.input(parent,
				"This zone SHARES its area with " + sharers + " other zone(s)" + namedSharers(zoneIndex, currentArea) + ".\n"
				+ "An area holds the atmosphere (fog/lighting), water animations, prop\n"
				+ "registry and NPC models - so " + whatEdit + " here would change those zones too.\n\n"
				+ "Give this zone its OWN private area first? (Recommended. Pure data -\n"
				+ "the copy starts identical, so nothing looks different until you edit it.)",
				"Shared area", JOptionPane.QUESTION_MESSAGE, opts, opts[0]);
		if (pick == null || opts[2].equals(pick)) {
			return -1; //cancelled, or nobody there to answer
		}
		if (opts[1].equals(pick)) {
			return currentArea; //deliberate game-wide edit
		}
		try {
			AreaForker.ForkResult r = AreaForker.forkArea(zoneIndex);
			lastForked = r.forked;
			//keep the loaded zone's live header coherent with what we just wrote
			if (mZonePnl != null && mZonePnl.zone != null && mZonePnl.zone.header != null
					&& mZonePnl.zoneIndex == zoneIndex) {
				mZonePnl.zone.header.areadataID = r.newArea;
			}
			return r.newArea;
		} catch (Exception ex) {
			ctrmap.Ui.error(parent,
					"Could not give this zone its own area:\n" + ex.getMessage()
					+ "\n\nThe edit was not applied.",
					"Shared area");
			return -1;
		}
	}

	/** Packs when the last ensurePrivate forked, so the new area lands in the
	 *  archive; a no-op otherwise. */
	public static void packIfForked(Runnable onDone) {
		if (lastForked) {
			lastForked = false;
			Workspace.packWorkspace(onDone);
		} else if (onDone != null) {
			onDone.run();
		}
	}

	/** " (Route 110, Route 111 and 3 more)" - concrete beats a bare count. */
	private static String namedSharers(int zoneIndex, int area) {
		try {
			if (mZonePnl == null || mZonePnl.zones == null) {
				return "";
			}
			StringBuilder sb = new StringBuilder();
			int shown = 0, extra = 0;
			for (int i = 0; i < mZonePnl.zones.length; i++) {
				if (i == zoneIndex || mZonePnl.zones[i] == null || mZonePnl.zones[i].header == null
						|| mZonePnl.zones[i].header.areadataID != area) {
					continue;
				}
				if (shown < 4) {
					if (shown > 0) {
						sb.append(", ");
					}
					sb.append(ctrmap.formats.text.LocationNames.getLocName(mZonePnl.zones[i].header.parentMap));
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

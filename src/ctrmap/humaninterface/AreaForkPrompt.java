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
	 */
	public static AreaForker.ForkResult ensurePrivate(Component parent, int zoneIndex, int currentArea, String whatEdit) {
		//Ask whether THIS GAME can fork an area, not whether it is ORAS. The
		//prompt is an offer, so an unsupported game must not be offered it:
		//accepting would run AreaForker, whose four archive offsets and global
		//per-area table rewrite were all measured on ORAS, and which refuses
		//outright for anything else. Offering a button that can only produce a
		//refusal is worse than not offering it.
		if (!Workspace.isValid()
				|| !Workspace.profile().supports(ctrmap.gamedef.GameProfile.Feature.AREA_FORK)
				|| zoneIndex < 0) {
			return unforked(zoneIndex, currentArea);
		}
		int sharers;
		try {
			sharers = AreaForker.areaSharers(zoneIndex);
		} catch (Exception ex) {
			return unforked(zoneIndex, currentArea); //cannot tell - let the edit proceed as before
		}
		if (sharers == 0) {
			return unforked(zoneIndex, currentArea); //already this zone's own area
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
			return null; //cancelled, or nobody there to answer
		}
		if (opts[1].equals(pick)) {
			return unforked(zoneIndex, currentArea); //deliberate game-wide edit
		}
		try {
			AreaForker.ForkResult r = AreaForker.forkArea(zoneIndex);
			//keep the loaded zone's live header coherent with what we just wrote
			if (mZonePnl != null && mZonePnl.zone != null && mZonePnl.zone.header != null
					&& mZonePnl.zoneIndex == zoneIndex) {
				mZonePnl.zone.header.areadataID = r.newArea;
			}
			return r;
		} catch (Exception ex) {
			ctrmap.Ui.error(parent,
					"Could not give this zone its own area:\n" + ex.getMessage()
					+ "\n\nThe edit was not applied.",
					"Shared area");
			return null;
		}
	}

	/** "Edit this area as it is": the id the caller came in with, nothing appended. */
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

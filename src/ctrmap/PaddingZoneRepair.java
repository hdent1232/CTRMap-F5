package ctrmap;

import java.awt.Component;
import javax.swing.JOptionPane;

/**
 * Gives any appended zone that still shares a donor's map its own, once, when a
 * workspace opens.
 *
 * <p>WHO THIS IS FOR. Adding zones rounds the count up to a multiple of four,
 * and versions up to 1.0.1 forked only the zones the user ASKED for - the
 * padding spares kept pointing at the donor's map. Opening a spare therefore
 * showed the donor's city, and painting it rewrote the donor and every sibling
 * spare. {@link GeometryForker#forkAppendedZones} fixes that for zones created
 * from now on; nothing the appender does can reach a workspace that already has
 * them, and this is the part that can.
 *
 * <p>WHY THIS IS ITS OWN CLASS AND NOT A METHOD OF THE WINDOW. It was one, and a
 * plant proved the guard worthless: the reachability check asks the BYTECODE
 * which classes name the repair, and a private wrapper inside
 * {@code CtrmapMainframe} kept naming it after the call in
 * {@code onWorkspaceOpened} was deleted. So the check passed with the repair
 * unreachable - the precise defect it was written to catch. Out here, the
 * window's only reason to mention this class is the one call, and deleting that
 * call is a red suite.
 *
 * <p>IT IS HANDED ITS PARENT rather than reaching for the window's, so the
 * reporting is the window's business and this is not.
 */
public final class PaddingZoneRepair {

	private PaddingZoneRepair() {
	}

	/**
	 * Repairs, packs and says what happened. Safe to call on every workspace
	 * open: a workspace with nothing to repair does nothing and says nothing.
	 *
	 * <p>IT ASKS NOTHING. The repair can only add - the donor's regions and
	 * matrix are copied, never written, so nothing that existed before stops
	 * existing. A question whose No is remembered would leave the zone
	 * rendering the donor's city for the life of the workspace, which is the
	 * defect rather than the fix.
	 *
	 * <p>IT REPORTS AFTER THE PACK, not before: staged files are not archives
	 * until one runs, and "repaired three zones" said over a pack that then
	 * failed is the same lie in a friendlier voice.
	 *
	 * <p>IT IS HANDED THE PACK rather than reaching for the workspace global. Not
	 * style: {@code WorkspaceSessionTest} holds a falling ceiling on how many
	 * production files reach those statics, and a new class that reached them
	 * would raise it - which that suite refuses, correctly. Handing it in also
	 * means a guard can watch this decide and report without packing anything.
	 *
	 * @param parent what the dialogs belong to, or null for none
	 * @param packer how to pack and then run the callback - the window hands in
	 *        {@code Workspace::packWorkspace}
	 */
	public static void repairOnOpen(Component parent, java.util.function.Consumer<Runnable> packer) {
		final GeometryForker.RepairReport r = GeometryForker.repairSharedAppendedZones(
				ctrmap.formats.codepatch.ZoneLimitPatch.BASE_ZONES);
		if (!r.refusedBecause.isEmpty()) {
			//only worth a word when there was something to repair - a workspace with
			//no appended zones refuses nothing and must not greet the user with a
			//dialog about a feature they have never used
			if (!r.shared.isEmpty()) {
				Ui.error(parent, "Zones added by an earlier version still share another zone's map:"
						+ "\n  " + r.shared
						+ "\n\nCTRMap could not give them their own just now: " + r.refusedBecause
						+ "\n\nEditing the map in one of those would change the zone it shares with,"
						+ " and every other zone on that map.",
						"Zones sharing a map");
			}
			return;
		}
		if (!r.changedAnything()) {
			return;
		}
		packer.accept(new Runnable() {
			@Override
			public void run() {
				Ui.message(parent, report(r), "Zones repaired", JOptionPane.INFORMATION_MESSAGE);
			}
		});
	}

	/**
	 * What the user is told, as a RETURN VALUE so a suite can read it - the same
	 * reason {@code CtrmapMainframe.forkGeometryReport} is one. A sentence built
	 * inside a dialog call is a sentence no test has ever seen.
	 */
	public static String report(GeometryForker.RepairReport r) {
		boolean one = r.forked.size() == 1;
		StringBuilder sb = new StringBuilder();
		sb.append(one ? "Zone " : "Zones ").append(r.forked).append(one ? " was" : " were")
				.append(" added by an earlier version as PADDING. Adding zones rounds\n"
						+ "the count up to a multiple of four, and that version gave only the zones\n"
						+ "you asked for their own map - the spares kept the donor's. Editing one\n"
						+ "would have rewritten the zone it was padded out of.\n\n");
		sb.append(one ? "It now has" : "They now have").append(" a map of ")
				.append(one ? "its" : "their").append(" own");
		if (r.blanked.size() == r.forked.size()) {
			sb.append(", and ").append(one ? "it is" : "they are").append(" empty - which is what an"
					+ "\nunused slot should be. Clone a zone into one, or build on it with the Map"
					+ "\nBuilder");
		} else if (!r.blanked.isEmpty()) {
			sb.append(", and ").append(r.blanked).append(" ").append(r.blanked.size() == 1 ? "is" : "are")
					.append(" empty");
		}
		sb.append(".\n");
		for (String kept : r.kept) {
			sb.append("\n").append(kept);
		}
		return sb.toString();
	}
}

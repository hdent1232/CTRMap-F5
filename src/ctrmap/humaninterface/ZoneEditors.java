package ctrmap.humaninterface;

import ctrmap.formats.zone.Zone;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every editor that shows the open zone, and the one place that says what
 * "show this zone" and "show nothing" mean.
 *
 * <p>WHY THIS EXISTS. The Zone tab did it itself, by name, for seven editors:
 * it built the map matrix and the prop registry, handed them to the map view,
 * handed the map view's matrix to the matrix panel, handed the area data to the
 * camera form, handed the entities to the NPC, warp and trigger forms, handed
 * the script to the script editor, and released the 3D navigator - all through
 * the main window's statics. Clearing was the same list again, shorter and in a
 * different place, so a form added to one and forgotten in the other would be
 * left showing a zone that is no longer open. That is not a theoretical worry:
 * the "loaded" flags those forms keep are what the save path trusts.
 *
 * <p>So the Zone tab now says "show this one" and "show nothing", and this is
 * the list. The window builds it, because the window is what has the editors
 * and the open game they load from.
 *
 * <p>ORDER MATTERS and is the list's, deliberately: the matrix panel is handed
 * the map view's matrix, so the map view has to have loaded first. That
 * dependency used to be two adjacent lines in a worker thread and is now the
 * order of two entries.
 */
public class ZoneEditors {

	/** One editor's view of the open zone. */
	public interface ZoneView {

		/** Show this zone. */
		void show(Zone zone);

		/** Show nothing: no zone is open. */
		void clear();

		/**
		 * Push what this editor is holding into the zone, before the zone is
		 * written. Answer false, having said why, when the record cannot be
		 * committed - the save then stops rather than writing without it.
		 *
		 * <p>Most editors hold nothing of the kind and answer true in a line
		 * that says so, the same way {@link #clear()} does. The point is that
		 * they are all ASKED: the zone save used to name two of the three forms
		 * that DO hold a part-typed record, and the third was simply absent.
		 */
		boolean commit();

		/**
		 * Show the zone's entities again: a discard has put them back to what the file holds
		 * (F106). An editor that shows none says so in a line, as {@link #clear()} does - showing the
		 * whole zone again instead would reload the map and reset editors whose own unsaved edits
		 * have not been asked about yet.
		 */
		void showEntities(Zone zone);
	}

	private final List<ZoneView> views;

	public ZoneEditors(List<ZoneView> views) {
		if (views == null || views.isEmpty()) {
			throw new IllegalArgumentException("the zone editor set cannot be empty - name the editors that show a zone");
		}
		this.views = Collections.unmodifiableList(new ArrayList<>(views));
	}

	/** Hands the zone to every editor that shows one, in order. */
	public void show(Zone zone) {
		for (ZoneView v : views) {
			v.show(zone);
		}
	}

	/**
	 * Tells every editor that no zone is open.
	 *
	 * <p>Every one of them is ASKED, which is the point. The Zone tab's clear
	 * named three of the seven, and the four it did not name were not a
	 * decision anyone had written down - they were an absence. Asking all seven
	 * puts the asymmetry in the list, where four entries say in a line that
	 * clearing them is a no-op today and why. Nothing about what actually
	 * happens changes with this commit; what changes is that it can be read.
	 */
	public void clear() {
		for (ZoneView v : views) {
			v.clear();
		}
	}

	/**
	 * Commits what every editor is holding, stopping at the first refusal.
	 *
	 * <p>WHY A REFUSAL STOPS THE SAVE. The zone save called two of these forms
	 * by name and threw away the boolean one of them returned - three lines
	 * above another refusal it honoured. The NPC form answers false, having
	 * already told the user "Script not defined", when a record would point at a
	 * script the zone does not define; the zone was then written anyway. The
	 * user saw a warning and a saved zone and had no way to tell which had won.
	 */
	public boolean commit() {
		for (ZoneView v : views) {
			if (!v.commit()) {
				return false;
			}
		}
		return true;
	}

	/** Hands the zone's entities, put back by a discard, to every editor, in order (F106). */
	public void showEntities(Zone zone) {
		for (ZoneView v : views) {
			v.showEntities(zone);
		}
	}

	/** How many editors show the zone; the guard checks the count it was built with. */
	@ctrmap.util.ForSuites("SEAM: a suite counts the editors the set was built with, to see none is skipped when a zone is shown")
	public int size() {
		return views.size();
	}
}

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

	/** How many editors show the zone; the guard checks the count it was built with. */
	public int size() {
		return views.size();
	}
}

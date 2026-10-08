package ctrmap;

/**
 * Gives a zone its own map and/or its own area, as one unit, and says what that did (F007: it lived in the
 * main window, which has no business holding a workspace operation with no dialog in it - the window asks
 * the user and shows the report; this does the work and writes the report).
 *
 * <p>Not: {@link GeometryForker} or {@link AreaForker} - each forks one half, and this is the two as ONE
 * unit, which neither can be without reaching into the other; their reports moved onto their results.
 */
public final class ZoneIndependence {

	/** The action's name: its dialogs' title and the name of the unit it runs in. */
	public static final String TITLE = "Make zone independent";

	public final int zoneIndex;
	public final GeometryForker.ForkResult map;
	public final AreaForker.ForkResult area;

	public ZoneIndependence(int zoneIndex, GeometryForker.ForkResult map, AreaForker.ForkResult area) {
		this.zoneIndex = zoneIndex;
		this.map = map;
		this.area = area;
	}

	/**
	 * Gives zone {@code zoneIndex} its own map and/or its own area, each only when it still shares
	 * one, as ONE unit: a failure in the second puts the first back. Either part is null when it was
	 * not asked for.
	 */
	public static ZoneIndependence make(int zoneIndex, boolean map, boolean area) throws java.io.IOException {
		try (WorkspaceUnit unit = WorkspaceUnit.begin(TITLE, Workspace.session())) {
			GeometryForker.ForkResult m = map ? GeometryForker.ensurePrivate(zoneIndex) : null;
			AreaForker.ForkResult a = area ? AreaForker.forkIfShared(zoneIndex) : null;
			unit.commit();
			return new ZoneIndependence(zoneIndex, m, a);
		}
	}

	/** Both reports, in the order the work was done. */
	public String report() {
		StringBuilder sb = new StringBuilder();
		if (map != null) {
			sb.append(map.report(zoneIndex));
		}
		if (area != null) {
			sb.append(sb.length() > 0 ? "\n\n" : "").append(area.report(zoneIndex));
		}
		return sb.length() > 0 ? sb.toString() : "Nothing was asked for, so nothing was changed.";
	}
}

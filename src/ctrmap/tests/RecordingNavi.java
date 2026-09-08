package ctrmap.tests;

import ctrmap.humaninterface.MapObject;
import ctrmap.humaninterface.Navigator;
import java.util.ArrayList;
import java.util.List;

/**
 * The navigator a suite hands a form: it writes down what the gizmo was told.
 *
 * <p>Before {@link Navigator} existed, "the gizmo follows the selected record"
 * could not be asserted at all. It was a reach into the main window for a JOGL
 * panel, so a headless suite either got a null and a private null check it
 * could not see through, or - in the prop editor's case - an exception inside a
 * catch-all that left the form inert while every assertion about it still
 * passed.
 */
public class RecordingNavi implements Navigator {

	/** Every record the gizmo was told to follow, in order; null means "let go". */
	public final List<MapObject> followed = new ArrayList<>();

	/** How many times it was asked to re-read the record it is following. */
	public int resyncs = 0;

	@Override
	public void follow(MapObject o) {
		followed.add(o);
	}

	@Override
	public void resync() {
		resyncs++;
	}

	/** What it is following now, or null if it was let go or never told. */
	public MapObject following() {
		return followed.isEmpty() ? null : followed.get(followed.size() - 1);
	}

	/** Forgets everything, so one suite section does not read another's. */
	public void reset() {
		followed.clear();
		resyncs = 0;
	}
}

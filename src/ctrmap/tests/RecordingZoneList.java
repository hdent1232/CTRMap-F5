package ctrmap.tests;

import ctrmap.humaninterface.ZoneList;
import java.util.ArrayList;
import java.util.List;

/**
 * The zone list a suite hands the painter: what it was asked to do, in order.
 *
 * <p>The rebuild's callback is run IMMEDIATELY. In the application it runs on a
 * worker when the list is ready; here there is no worker, and a suite that
 * silently dropped the callback would be asserting against a step that never
 * happened - which is the sort of thing a double is most likely to get wrong.
 */
public class RecordingZoneList implements ZoneList {

	/** Every call, in order: "rebuild", "open N", "clearForkDecline N". */
	public final List<String> calls = new ArrayList<>();

	@Override
	public void rebuild(Runnable then) {
		calls.add("rebuild");
		if (then != null) {
			then.run();
		}
	}

	@Override
	public void open(int index) {
		calls.add("open " + index);
	}

	@Override
	public void clearForkDecline(int index) {
		calls.add("clearForkDecline " + index);
	}

	/** Forgets everything, so one suite section does not read another's. */
	public void reset() {
		calls.clear();
	}
}

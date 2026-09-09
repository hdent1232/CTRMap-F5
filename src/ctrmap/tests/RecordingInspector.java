package ctrmap.tests;

import ctrmap.humaninterface.TileInspector;
import java.util.ArrayList;
import java.util.List;

/**
 * The tile inspector a suite hands the cursor: what it was told, in order.
 *
 * <p>The lock/show/lock sequence the cursor runs when a tile is picked could
 * not be asserted before - the inspector was reached through the main window,
 * so a headless suite got a null and the cursor threw. The ORDER is what this
 * records, because it is the part that matters: showing before unlocking leaves
 * the previous tile on screen.
 */
public class RecordingInspector implements TileInspector {

	/** Every call, in order: "show x,y[+scroll]" or "lock true/false". */
	public final List<String> calls = new ArrayList<>();

	@Override
	public void showTile(int x, int y, boolean scroll) {
		calls.add("show " + x + "," + y + (scroll ? "+scroll" : ""));
	}

	@Override
	public void lockTile(boolean locked) {
		calls.add("lock " + locked);
	}

	/** Forgets everything, so one suite section does not read another's. */
	public void reset() {
		calls.clear();
	}
}

package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.formats.zone.Zone;
import ctrmap.humaninterface.ZoneEditors;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The zone views a suite hands the Zone tab: it records "show" and "clear",
 * and forwards the clear to the entity forms the suite built.
 *
 * <p>The Zone tab used to name seven editors through the main window's statics
 * to show a zone, and three of them to clear one. A suite could see the effect
 * - the NPC form's {@code loaded} flag going false - but not the event, so
 * "the Zone tab told the editors" was only ever asserted through whichever
 * form the suite happened to have built.
 *
 * <p>What it forwards is exactly what the Zone tab's unload did, so the
 * suites that assert on those flags keep asserting the same thing.
 */
class ZoneEditorsSpy extends ZoneEditors {

	/** Every call, as "show" or "clear", in order. */
	final List<String> calls = new ArrayList<>();

	ZoneEditorsSpy() {
		super(Arrays.<ZoneEditors.ZoneView>asList(new ZoneView() {
			@Override
			public void show(Zone zone) {
			}

			@Override
			public void clear() {
			}
		}));
	}

	@Override
	public void show(Zone zone) {
		calls.add("show");
	}

	@Override
	public void clear() {
		calls.add("clear");
		if (CtrmapMainframe.mNPCEditForm != null) {
			CtrmapMainframe.mNPCEditForm.loadFromEntities(null, null);
		}
		if (CtrmapMainframe.mWarpEditForm != null) {
			CtrmapMainframe.mWarpEditForm.loadFromEntities(null);
		}
		if (CtrmapMainframe.mTriggerEditForm != null) {
			CtrmapMainframe.mTriggerEditForm.loadFromEntities(null);
		}
	}

	/** The last thing the Zone tab asked for, or null if it asked for nothing. */
	String last() {
		return calls.isEmpty() ? null : calls.get(calls.size() - 1);
	}
}

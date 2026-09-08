package ctrmap.tests;

import ctrmap.humaninterface.OpenEditors;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The editor set a suite hands a panel: it records the flush instead of doing
 * it, and can be told to refuse.
 *
 * <p>"Save everything, and stop if one refuses" was a chain of {@code &&} over
 * the main window's statics, written out five times. A suite could only reach
 * it by building every form the chain names; what it could not do was ask
 * whether the flush happened at all, or make one editor refuse and check that
 * the reload after it did not run.
 */
final class RecordingEditors extends OpenEditors {

	/** Every call, as the value of askFirst. */
	final List<Boolean> flushes = new ArrayList<>();

	/** When false, the flush refuses, as an editor whose question was closed does. */
	boolean allow = true;

	RecordingEditors() {
		super(Arrays.<OpenEditors.Editable>asList(ask -> true));
	}

	@Override
	public boolean saveAll(boolean askFirst) {
		flushes.add(askFirst);
		return allow;
	}

	/** How many times anything asked for a flush. */
	int count() {
		return flushes.size();
	}
}

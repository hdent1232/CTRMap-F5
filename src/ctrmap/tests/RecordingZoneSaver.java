package ctrmap.tests;

import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.humaninterface.ScriptView;
import ctrmap.humaninterface.ZoneSaver;
import java.util.ArrayList;
import java.util.List;

/**
 * The zone saver and the script view a suite hands a form: what it was asked
 * for, and an answer the section chooses.
 *
 * <p>Both were reaches into the main window for a whole panel to use one method
 * on it, so "the NPC form writes the zone after repointing a dialogue" could
 * only be asserted by building a Zone tab and a script editor. More to the
 * point, the form DISCARDED the saver's answer, and nothing could notice: a zone
 * that refused to save was followed by re-reading the script from a zone that
 * had not been written.
 */
public class RecordingZoneSaver implements ZoneSaver, ScriptView {

	/** Every save, as the flag it was passed. */
	public final List<Boolean> saves = new ArrayList<>();

	/** Every script the view was told to show. */
	public final List<GFLPawnScript> shown = new ArrayList<>();

	/** What the next save answers. False stands for a zone that would not write. */
	public boolean writes = true;

	@Override
	public boolean save(boolean askFirst) {
		saves.add(askFirst);
		return writes;
	}

	@Override
	public void showScript(GFLPawnScript script) {
		shown.add(script);
	}

	/** Forgets everything, so one suite section does not read another's. */
	public void reset() {
		saves.clear();
		shown.clear();
		writes = true;
	}
}

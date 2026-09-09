package ctrmap.humaninterface;

/**
 * Writing the open zone back, as the things that ask for it see it.
 *
 * <p>WHY THIS EXISTS. Four call sites in two forms said
 * {@code mZonePnl.store(false)} - the NPC editor after repointing a dialogue,
 * after adding an NPC, and after saving a zone script, and the script editor's
 * own Save button. Every one of them reached through the main window for the
 * Zone TAB, a JPanel with a dropdown and forty widgets, to use one method on it.
 *
 * <p>None of those callers wants the Zone tab. They want the zone written.
 *
 * <p>WHAT THE FLAG MEANS is worth stating because every one of those four passes
 * false: {@code false} is "do not ask the user anything". The zone is being
 * written as a side effect of something the user already asked for, so a dialog
 * here would be a second question about a decision already made.
 *
 * <p>AND THE ANSWER MATTERS. This returns false when the zone could not be
 * written - a warp with no destination is the usual reason - having already told
 * the user why. All four callers used to discard it, and the two that go on to
 * reload the script were reloading it from a zone that had not been saved.
 */
public interface ZoneSaver {

	/**
	 * Writes the open zone back.
	 *
	 * @param askFirst whether the editors may put a question to the user before
	 *        writing. Every caller in the program passes false.
	 * @return false when the zone was not written, with the reason already
	 *         shown. A caller that goes on to reload from the zone must stop.
	 */
	boolean save(boolean askFirst);
}

package ctrmap.humaninterface;

import ctrmap.formats.scripts.GFLPawnScript;

/**
 * The script editor, as the NPC form sees it: somewhere to put a zone's script.
 *
 * <p>The NPC form reached through the main window for the whole script editor -
 * a panel with an assembler, a disassembler and a text area - to call one
 * method on it, twice, both times immediately after saving the zone: the script
 * on screen belongs to the zone that was just rewritten, so it has to be read
 * again.
 */
public interface ScriptView {

	/** Show this script, because the one on screen is out of date. */
	void showScript(GFLPawnScript script);
}

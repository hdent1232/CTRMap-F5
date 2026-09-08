package ctrmap.humaninterface.tools;

import ctrmap.humaninterface.TileMapPanel;
import javax.swing.JComponent;

/**
 * The editor a tool works inside: the four things every tool needs from it,
 * and nothing else.
 *
 * <p>WHY THIS EXISTS. Every tool in this package began with
 * {@code import static ctrmap.CtrmapMainframe.*} and read the main window's
 * statics directly - the map view, the side panel, the JFrame, the form it
 * drives. So a tool could not be built at all until nine forms, three split
 * panes, three master panels and a scroll pane existed, which is why the test
 * bench that stands in for the window exists and why seven of the eight
 * classes it serves were at zero coverage: not because they are hard to
 * assert, but because nothing could build one.
 *
 * <p>Handed one of these and its own form instead, a tool is an ordinary
 * object. The application's implementation is the window; a suite's can count
 * what it was asked for, which is how "the tool put its form on the side
 * panel" and "the tool asked for a redraw" became things a test can say.
 */
public interface ToolHost {

	/**
	 * Puts this tool's form on the editor's side panel, which is what a tool
	 * does as it starts: the alternative is the user looking at the previous
	 * tool's form while their clicks go somewhere else.
	 */
	void showToolUi(JComponent form);

	/** The editor's views are out of date and should be drawn again. */
	void redraw();

	/**
	 * Lets go of the 3D navigator. Every tool switch does this, so a gizmo
	 * bound by the NPC editor is not left standing over a prop.
	 */
	void releaseNavi();

	/**
	 * The map view: the tools draw their overlays on it and hit-test against
	 * its scale and image. Handed as itself rather than as a narrower
	 * interface because the tools that use it use a dozen of its members
	 * between them, and naming a capability per member would be a longer way
	 * of writing the same class.
	 */
	TileMapPanel map();
}

package ctrmap.tests;

import ctrmap.humaninterface.TileMapPanel;
import ctrmap.humaninterface.tools.ToolHost;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;

/**
 * The editor a tool works inside, for a suite: it records what it was asked
 * for instead of doing it.
 *
 * <p>Every tool used to reach the main window's statics for the side panel,
 * the map view and the JFrame, and {@code AbstractTool}'s constructor called
 * {@code onToolInit} - so building a tool at all needed nine forms, three
 * split panes and a window, and a headless run could not have one. That is why
 * the checks about what a tool does as it starts were gathered behind a "no
 * display" skip, and why seven of the eight classes the bench serves had been
 * at zero coverage.
 *
 * <p>Handed one of these, a tool is an ordinary object and "it put its form on
 * the side panel", "it asked for a redraw" and "it let go of the navigator"
 * are three counters.
 */
class RecordingHost implements ToolHost {

	/** Every form this host was asked to show, in order. */
	final List<Component> shown = new ArrayList<Component>();
	int redraws;
	int naviReleases;

	/** The map view handed back to the tools; null when the suite needs none. */
	TileMapPanel map;

	/** Also does the real thing, for a suite that has a window and wants both. */
	boolean alsoReally;

	RecordingHost() {
	}

	RecordingHost(TileMapPanel map) {
		this.map = map;
	}

	@Override
	public void showToolUi(JComponent form) {
		shown.add(form);
		if (alsoReally) {
			ctrmap.CtrmapMainframe.switchToolUI(form);
		}
	}

	@Override
	public void redraw() {
		redraws++;
	}

	@Override
	public void releaseNavi() {
		naviReleases++;
	}

	@Override
	public TileMapPanel map() {
		return map;
	}

	/** The last form this host was asked to show, or null if it never was. */
	Component lastShown() {
		return shown.isEmpty() ? null : shown.get(shown.size() - 1);
	}
}

package ctrmap.humaninterface;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.function.BooleanSupplier;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;

/**
 * A part of the window whose normal view can be swapped for a tool - and that always has a way
 * back to that view, which is the only reason this class exists.
 *
 * <p>PAID FOR IN 1.0.4. The Zone Loader's right-hand column swapped the zone list for Connect zones,
 * and the way back was a button in a {@code JToolBar}. On Windows a toolbar button is drawn flat,
 * with no border until the mouse is over it, so "Back to the zone list" read as the tool's caption.
 * Nothing in the Zone menu or the zone actions bar went back to the list either. The owner pressed a
 * zone action during a slow load, and when the window caught up the zone list was gone with nothing
 * on screen that looked like a way out - in a release, where that list is the only way to open a
 * zone. The Extras tab had the same flat button. Each of the three places that swapped a view did it
 * with its own removeAll() and its own idea of a way back, so nothing could say they all had one.
 *
 * <p>So the swap and the way back are one thing here. Every tool shown in a host gets a plain,
 * bordered "Back to ..." button above it, and Esc anywhere inside it does the same. A host that holds
 * an edit it will not let go of is ASKED first ({@code mayLeave}), as the Extras tab asked before.
 * The window owns no removeAll() of its own any more, and ToolHostTest refuses one coming back.
 *
 * <p>Not: SaveHistory - that puts Undo and Redo around one area and never swaps it for another; and
 * not a CardLayout - a deck of cards has no way back unless each caller draws one, which is how three
 * callers ended up with three, one of them missing and two of them flat.
 */
public final class ToolHost extends JPanel {

	/** What every way back says, before the name of where it goes. */
	public static final String BACK = "Back to ";

	/** The Esc binding's name in the tool box's input and action maps. */
	public static final String ESC_ACTION = "ctrmap.toolhost.back";

	private final String homeName;
	private final JComponent home;
	private final boolean scrollTools;
	private JComponent open;
	private BooleanSupplier mayLeave;
	private JButton back;

	/**
	 * @param homeName where the way back goes, as it reads after "Back to " - "the zone list"
	 * @param home the normal view, shown when no tool is
	 * @param scrollTools whether a tool is put in a scroll pane: a tall form needs one, and an editor
	 *        that scrolls its own tables must not get a second
	 */
	public ToolHost(String homeName, JComponent home, boolean scrollTools) {
		super(new BorderLayout());
		if (homeName == null || home == null) {
			throw new IllegalArgumentException("a tool host needs a home to go back to, and a name for it");
		}
		this.homeName = homeName;
		this.home = home;
		this.scrollTools = scrollTools;
		add(home, BorderLayout.CENTER);
	}

	/**
	 * Shows a tool in place of the home view, under its title, with the way back above it.
	 *
	 * <p>A tool already open is asked first, as leaving it for home would ask: replacing it is
	 * leaving it. A null tool means it refused and has said why, and nothing changes.
	 *
	 * @param menu the tool's own menu bar, kept beside the way back, or null
	 * @param mayLeave asked before the tool is left - false keeps it open; null never refuses
	 * @return whether the tool is now shown
	 */
	public boolean show(String title, JComponent tool, JMenuBar menu, BooleanSupplier mayLeave) {
		if (tool == null || !leave()) {
			return false;
		}
		JPanel box = new JPanel(new BorderLayout());
		box.setBorder(BorderFactory.createTitledBorder(title));
		JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		JButton b = new JButton(backLabel());
		b.setToolTipText("Leave " + title + " and go back to " + homeName + ". Esc does the same.");
		b.addActionListener(e -> goHome());
		top.add(b);
		if (menu != null) {
			top.add(menu);
		}
		box.add(top, BorderLayout.NORTH);
		box.add(scrollTools ? new JScrollPane(tool) : tool, BorderLayout.CENTER);
		box.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ESC_ACTION);
		box.getActionMap().put(ESC_ACTION, new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				goHome();
			}
		});
		open = tool;
		this.mayLeave = mayLeave;
		back = b;
		swapTo(box);
		return true;
	}

	/**
	 * Back to the home view, if the open tool lets go.
	 *
	 * @return whether the home view is shown now - false only when the tool refused
	 */
	public boolean goHome() {
		if (open == null) {
			return true;
		}
		if (!leave()) {
			return false;
		}
		swapTo(home);
		return true;
	}

	/** Asks the open tool whether it may be left, and forgets it when it may. True with none open. */
	private boolean leave() {
		if (open == null) {
			return true;
		}
		if (mayLeave != null && !mayLeave.getAsBoolean()) {
			return false;
		}
		open = null;
		mayLeave = null;
		back = null;
		return true;
	}

	private void swapTo(JComponent view) {
		removeAll();
		add(view, BorderLayout.CENTER);
		revalidate();
		repaint();
	}

	/** Whether the home view is the one shown. */
	public boolean atHome() {
		return open == null;
	}

	/** The home view. */
	public JComponent home() {
		return home;
	}

	/** The tool on show, or null at home. */
	public JComponent tool() {
		return open;
	}

	/** The way back while a tool is shown, or null at home - for suites, which press it. */
	public JButton backButton() {
		return back;
	}

	/** What the way back says. */
	public String backLabel() {
		return BACK + homeName;
	}
}

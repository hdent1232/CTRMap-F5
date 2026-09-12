package ctrmap.humaninterface;

import java.awt.Container;
import javax.swing.JPanel;

/**
 * What a NetBeans-generated window needs in order to live in a tab instead.
 *
 * <p>Not: {@link javax.swing.JPanel} - it is one, and that is the point; what a
 * plain JPanel cannot do is answer the six calls a generated
 * {@code initComponents} makes on a JFrame. Those blocks must never be
 * hand-edited (the designer regenerates them), so the calls are answered from
 * underneath rather than removed: {@code getContentPane}, {@code setTitle},
 * {@code pack}, {@code setResizable}, {@code setLocationByPlatform} and
 * {@code setDefaultCloseOperation}.
 *
 * <p>WHY IT EXISTS. Four editors in this application are windows for no better
 * reason than that they were drawn in the form designer, which produces a
 * JFrame: the prop registry, the NPC registry, the tile database writer and
 * workspace settings. The owner's standing rule is that a feature lives in the
 * part of the UI it belongs to, and each of these is a feature. Converting them
 * by hand would have meant editing generated code four times; one superclass
 * converts them without touching a line of it.
 *
 * <p>THE CLOSE HANDLER IS A METHOD, NOT A WINDOW EVENT. These classes did their
 * saving in {@code windowClosing}, and a panel has no such event - a WindowEvent
 * cannot even be constructed without a Window to be the source of. So the bodies
 * move to {@link #closeRequested()}, which the host calls when the user leaves,
 * and which answers whether leaving is allowed: a save that was refused must not
 * be silently discarded, which is what a dropped close handler would do.
 */
public class FormPanel extends JPanel {

	private String title = "";
	private boolean resizable = true;
	private int closeOperation = javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE;

	/** What generated code adds its widgets to. Itself. */
	public Container getContentPane() {
		return this;
	}

	/** The window title, kept as the caption the host shows over the pane. */
	public void setTitle(String t) {
		this.title = t == null ? "" : t;
	}

	public String getTitle() {
		return title;
	}

	/** A window packs to its preferred size; a pane simply takes it. */
	public void pack() {
		setSize(getPreferredSize());
	}

	public void setResizable(boolean b) {
		this.resizable = b;
	}

	public boolean isResizable() {
		return resizable;
	}

	/** Meaningless in a pane, and answered so the generated call compiles. */
	public void setLocationByPlatform(boolean b) {
	}

	public void setDefaultCloseOperation(int op) {
		this.closeOperation = op;
	}

	public int getDefaultCloseOperation() {
		return closeOperation;
	}

	/**
	 * Asked before the user leaves this pane. False keeps them here.
	 *
	 * <p>The default is to let them go. A subclass that used to save in
	 * {@code windowClosing} overrides this with the same body, and answers false
	 * when the save was refused - which is what {@code DO_NOTHING_ON_CLOSE} meant
	 * on the window it used to be.
	 */
	public boolean closeRequested() {
		return true;
	}

	/**
	 * A generated frame can carry a menu bar. A pane cannot, so it is KEPT rather than
	 * dropped and the host puts it above the pane - dropping it would take the tile
	 * database writer's entire File menu with it, silently.
	 */
	public void setJMenuBar(javax.swing.JMenuBar bar) {
		this.menuBar = bar;
	}

	public javax.swing.JMenuBar getJMenuBar() {
		return menuBar;
	}

	private javax.swing.JMenuBar menuBar;

	/** A window disposes; a pane is simply left. Kept so generated code compiles. */
	public void dispose() {
	}
}

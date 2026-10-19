package ctrmap.humaninterface;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * The saves made in one area of the window, each kept so it can be taken back and put again (F209c): what a
 * save changed on disk, before and after, in a history the area owns, with Undo and Redo under it - and
 * Ctrl+Z inside the area takes back the last save made there.
 *
 * <p>A save keeps itself by running as one {@link ctrmap.WorkspaceUnit} and handing {@link #keep} what the
 * unit's commit kept. What the save changed in memory joins the same edit through
 * {@link ctrmap.WorkspaceUnit#inMemory}, so an editor shows again what its files hold once they are put back.
 *
 * <p>The Extras panel's mass edits were the first saves kept this way, with the history and its buttons built
 * into that panel; measured on 2026-10-08, 12 more editors saved with no history at all.
 *
 * <p>Not: {@link UndoHistory} - the model every history is; this is one use of it, for whole saves, with the
 * buttons that use needs. Nor {@link ctrmap.WorkspaceUnit} - that writes all or nothing and, committed, keeps
 * nothing unless asked.
 */
public final class SaveHistory {

	private final UndoHistory history;
	/** The client property an area carries to say which documents its editor edits in memory. */
	private static final String DOCUMENTS = SaveHistory.class.getName() + ".documents";
	/** The documents of the area last put under the buttons - the editor being shown. */
	private Supplier<List<UndoHistory.Document<?>>> shown = Collections::emptyList;
	/** Made when a panel is first put under them: a history nobody shows - a suite's - has no Swing in it. */
	private JButton undo;
	private JButton redo;

	/** A history of the saves made to whatever {@code scope} answers: another workspace opened starts it again. */
	public SaveHistory(Supplier<List<Object>> scope) {
		history = new UndoHistory(scope, () -> shown.get());
	}

	/**
	 * Says that {@code area}'s editor edits {@code documents} in memory: the history it is shown under watches
	 * them while it is the one shown, around every gesture, so an edit not yet saved is taken back too.
	 */
	public static JComponent watching(JComponent area, Supplier<List<UndoHistory.Document<?>>> documents) {
		area.putClientProperty(DOCUMENTS, documents);
		return area;
	}

	/** The documents {@code area} says its editor edits now, or none. */
	@SuppressWarnings("unchecked")
	public static List<UndoHistory.Document<?>> documentsOf(JComponent area) {
		Object d = area.getClientProperty(DOCUMENTS);
		return d instanceof Supplier ? ((Supplier<List<UndoHistory.Document<?>>>) d).get()
				: Collections.<UndoHistory.Document<?>>emptyList();
	}

	/** One save, kept: its own edit, standing alone. */
	public void keep(ctrmap.WorkspaceUnit.Kept kept) {
		history.add(kept);
		history.settle();
	}

	/**
	 * {@code area} with Undo and Redo under it, the whole owned by this history so Ctrl+Z in it comes here.
	 * The buttons are this history's: shown under one area at a time, the area last asked for.
	 */
	public JComponent around(JComponent area) {
		if (undo == null) {
			undo = new JButton("Undo");
			redo = new JButton("Redo");
			undo.addActionListener(e -> history.act(undo, true));
			redo.addActionListener(e -> history.act(redo, false));
			history.addListener(this::show);
			show();
		}
		history.settle();   //what the last area's editor holds is noted before its documents leave
		shown = () -> documentsOf(area);
		history.settle();   //and the new one's as they are now: opening an editor is no edit
		JPanel box = new JPanel(new BorderLayout());
		box.add(area, BorderLayout.CENTER);
		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
		bar.add(undo);
		bar.add(redo);
		box.add(bar, BorderLayout.SOUTH);
		history.own(box);
		return box;
	}

	/** The history itself: where the window's Ctrl+Z and a suite undo through. */
	public UndoHistory history() {
		return history;
	}

	/** Undo and Redo as the history stands: enabled when there is something, and saying what. */
	private void show() {
		undo.setEnabled(history.canUndo());
		undo.setToolTipText(history.canUndo() ? "Put back what \"" + history.undoName() + "\" changed." : null);
		redo.setEnabled(history.canRedo());
		redo.setToolTipText(history.canRedo() ? "Put \"" + history.redoName() + "\" again." : null);
	}
}

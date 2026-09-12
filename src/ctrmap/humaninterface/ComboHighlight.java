package ctrmap.humaninterface;

import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.plaf.basic.BasicComboPopup;

/**
 * Which row of an open dropdown is under the cursor, for anyone who wants to
 * show something about it before it is chosen.
 *
 * <p>WHY THIS EXISTS. Choosing a zone in the Zone Loader dropdown LOADS it - its
 * entities, its script, its editors, its undo history. So "show me that one
 * first" has to answer about the row being arrowed through, not the row that has
 * been selected, and Swing only offers that through the popup's list.
 *
 * <p>WHAT IT IS NOT. It was a window: a floating bubble that drew the map beside
 * the open popup. That broke the rule this project has had from the start - a
 * feature lives in the UI it belongs to, not in a window of its own - and it was
 * invisible to the owner twice running. The highlight itself is still worth
 * having, so what survived is only the hook: it reports an index, and the Zone
 * Loader's own preview panel draws it, in the tab, where it can be seen.
 *
 * <p>THE FRAGILE PART is reaching the list at all: a combo box's popup is a
 * {@link BasicComboPopup} and its list hangs off the UI delegate's accessible
 * child. A look and feel that does not use one has no list to listen to, so
 * {@link #onHighlight} answers whether it managed it. Nothing depends on it
 * working - the pane still follows the SELECTED zone - so a false here costs the
 * highlight, not the preview.
 */
public final class ComboHighlight {

	private ComboHighlight() {
	}

	/** Told the index of the row currently under the cursor in an open popup. */
	public interface Listener {

		void highlighted(int index);
	}

	/**
	 * Reports the highlighted row of {@code combo} while its popup is open.
	 *
	 * @return false when this look and feel exposes no popup list
	 */
	public static boolean onHighlight(final JComboBox<?> combo, final Listener told) {
		final JList<?> list = popupList(combo);
		if (list == null || told == null) {
			return false;
		}
		list.addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(ListSelectionEvent e) {
				if (!combo.isPopupVisible()) {
					return;
				}
				int idx = list.getSelectedIndex();
				if (idx >= 0) {
					told.highlighted(idx);
				}
			}
		});
		return true;
	}

	/**
	 * The list inside a combo box's popup, or null when this look and feel does
	 * not use {@link BasicComboPopup}.
	 */
	public static JList<?> popupList(JComboBox<?> combo) {
		try {
			javax.accessibility.Accessible a = combo.getUI().getAccessibleChild(combo, 0);
			if (a instanceof BasicComboPopup) {
				return ((BasicComboPopup) a).getList();
			}
		} catch (RuntimeException noPopup) {
			//a look and feel that does not expose one; the caller says so
		}
		return null;
	}
}

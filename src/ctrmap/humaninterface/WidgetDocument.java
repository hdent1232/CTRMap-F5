package ctrmap.humaninterface;

import java.util.Arrays;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.text.JTextComponent;

/**
 * What a form's widgets hold, as a document of an undo history (F209) - for a form that writes them into
 * its record only when it is saved, so that until then an edit lives in the widgets alone: the Zone tab's
 * header boxes are written on save, and only those the user changed (F115).
 *
 * <p>A spinner and a formatted field by value, a text field by its text, a tick box or toggle by whether
 * it is ticked, a drop-down by the row picked. Anything else - a plain button, a list - is refused when
 * the document is made, by name: it would otherwise be left out without a word.
 *
 * <p>Not: {@link FlatCopy} - it copies a record's fields, and this form's record is not changed until it
 * is saved; a document over the record would see nothing until then, and the save itself as the edit.
 */
public final class WidgetDocument implements UndoHistory.Document<Object[]> {

	private final Object subject;
	private final String what;
	private final JComponent[] widgets;

	/**
	 * @param subject what the widgets are showing - the zone, the record - so the same widgets showing
	 *        another one are another document
	 * @param what the edit's name, for the Undo button
	 */
	public WidgetDocument(Object subject, String what, JComponent... widgets) {
		if (subject == null || what == null || widgets == null) {
			throw new IllegalArgumentException("a widget document needs what it shows, its name and its widgets");
		}
		for (JComponent w : widgets) {
			if (!(w instanceof JSpinner || w instanceof JTextComponent || w instanceof JToggleButton || w instanceof JComboBox)) {
				throw new IllegalArgumentException("a " + (w == null ? "null" : w.getClass().getSimpleName()) + " holds no value"
						+ " an undo could put back" + (w == null ? "" : " (" + w.getName() + ")"));
			}
		}
		this.subject = subject;
		this.what = what;
		this.widgets = widgets.clone();
	}

	@Override
	public Object subject() {
		return subject;
	}

	@Override
	public Object[] take() {
		Object[] out = new Object[widgets.length];
		for (int i = 0; i < widgets.length; i++) {
			out[i] = valueOf(widgets[i]);
		}
		return out;
	}

	@Override
	public void put(Object[] state) {
		for (int i = 0; i < widgets.length; i++) {
			show(widgets[i], state[i]);
		}
	}

	@Override
	public boolean same(Object[] a, Object[] b) {
		return Arrays.equals(a, b);
	}

	@Override
	public String what() {
		return what;
	}

	private static Object valueOf(JComponent w) {
		if (w instanceof JSpinner) {
			return ((JSpinner) w).getValue();
		}
		if (w instanceof JFormattedTextField) {
			return ((JFormattedTextField) w).getValue();
		}
		if (w instanceof JTextComponent) {
			return ((JTextComponent) w).getText();
		}
		if (w instanceof JComboBox) {
			return ((JComboBox<?>) w).getSelectedIndex();
		}
		return ((AbstractButton) w).isSelected();
	}

	private static void show(JComponent w, Object v) {
		if (w instanceof JSpinner) {
			((JSpinner) w).setValue(v);
		} else if (w instanceof JFormattedTextField) {
			((JFormattedTextField) w).setValue(v);
		} else if (w instanceof JTextComponent) {
			((JTextComponent) w).setText((String) v);
		} else if (w instanceof JComboBox) {
			((JComboBox<?>) w).setSelectedIndex((Integer) v);
		} else {
			((AbstractButton) w).setSelected((Boolean) v);
		}
	}
}

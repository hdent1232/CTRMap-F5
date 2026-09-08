package ctrmap.humaninterface;

import ctrmap.resources.ResourceAccess;
import java.awt.Component;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.JScrollPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.ImageIcon;
import javax.swing.JFormattedTextField;
import javax.swing.JRadioButton;
import javax.swing.text.BadLocationException;

/**
 * Swing helpers the editor's forms share: reading a number a person typed, and
 * building a button drawn from resource images.
 *
 * <p>These were part of {@code ctrmap.Utils}, next to byte helpers the format
 * layer uses and the 3D picking maths. Nothing about them is general: they need
 * Swing, and they belong with the forms that use them.
 */
public final class Forms {

	private Forms() {
	}

	/**
	 * The number in a formatted field, as a float - 0 for anything that is not
	 * one, including an empty field and a lone minus sign, and taking a comma
	 * for a decimal point because that is what a European keyboard types.
	 */
	public static float getFloatFromDocument(JFormattedTextField docOwner) {
		try {
			String val = docOwner.getDocument().getText(0, docOwner.getDocument().getLength()).replace(',', '.');
			if (val.length() > 0 && !val.equals("-")) {
				return Float.valueOf(val);
			} else {
				return 0f;
			}
		} catch (BadLocationException | NumberFormatException ex) {
			return 0f;
		}
	}

	/** An icon from the resources jar. */
	public static ImageIcon getImageIconFromResource(String respath) {
		return new ImageIcon(ResourceAccess.getByteArray(respath));
	}

	/** A radio button drawn as three images: stale, rollover, active. */
	public static JRadioButton createGraphicalButton(String prefix) {
		JRadioButton ret = new JRadioButton(getImageIconFromResource(prefix + "_stale.png"));
		ret.setRolloverIcon(getImageIconFromResource(prefix + "_rollover.png"));
		ret.setPressedIcon(getImageIconFromResource(prefix + "_active.png"));
		ret.setSelectedIcon(getImageIconFromResource(prefix + "_active.png"));
		ret.setRolloverEnabled(true);
		return ret;
	}

	/**
	 * A label above a field, both left-aligned, appended to a stacked form.
	 * Twenty-two of the NPC editor's wizard forms build their rows with this;
	 * it was an instance method of that class, which is one of the reasons its
	 * seven wizard forms could not leave it.
	 */
	public static void addLabeled(JPanel panel, String label, Component field) {
		JLabel l = new JLabel(label);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(l);
		if (field instanceof JComponent) {
			((JComponent) field).setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		panel.add(field);
	}

	/** An empty panel that stacks what is added to it, top to bottom. */
	public static JPanel stackedForm() {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		return panel;
	}

	/** A grey hint line under a field, in HTML. */
	public static JLabel hint(String html) {
		JLabel hint = new JLabel(html);
		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		return hint;
	}

	public static JTextArea textArea(String text, int rows) {
		JTextArea ta = new JTextArea(text, rows, 40);
		ta.setLineWrap(true);
		ta.setWrapStyleWord(true);
		return ta;
	}}

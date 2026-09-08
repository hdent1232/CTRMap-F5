package ctrmap.humaninterface;

import ctrmap.resources.ResourceAccess;
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
}

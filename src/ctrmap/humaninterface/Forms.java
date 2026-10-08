package ctrmap.humaninterface;

import ctrmap.resources.ResourceAccess;
import java.awt.Component;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.ImageIcon;
import javax.swing.JFormattedTextField;
import javax.swing.JRadioButton;
import javax.swing.text.BadLocationException;
import javax.swing.text.NumberFormatter;

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
	 * A value typed into a table cell was refused: Swing's own refusal signal - the one a
	 * formatted field gives - while the cell keeps its old value where the user sees it was
	 * not taken. The trainer, Maison, encounter and class-list tables caught the bad number
	 * and did nothing at all (F211), so a typo looked like a value the editor ignored.
	 */
	public static void refuseTyped(Component where) {
		javax.swing.UIManager.getLookAndFeel().provideErrorFeedback(where);
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

	/**
	 * Bounds a spinner on both sides, keeping its value (clamped into them). Called where a form is
	 * built, after initComponents - never inside it, which a designer save rewrites (F124, F125). A
	 * spinner with an open side took whatever was typed: -1 in an unsigned 16-bit field was saved
	 * as 0xFFFF and reloaded as 65535, and its form's code never said it could not be.
	 */
	public static void bound(javax.swing.JSpinner s, int min, int max) {
		Object v = s.getValue();
		int now = v instanceof Number ? ((Number) v).intValue() : min;
		s.setModel(new javax.swing.SpinnerNumberModel(Math.max(min, Math.min(max, now)), min, max, 1));
	}

	/**
	 * A formatted field that shows and reads a number in hex, refusing typed text that is not hex or
	 * is past its bounds (F125). The one copy: the tile form and the tile database writer each kept a
	 * verbatim twin, and neither had bounds, so 1FF typed into a tile byte was committed and cut to
	 * FF by the listener, -1 to FF, with nothing said.
	 */
	public static class HexFormatterFactory extends javax.swing.text.DefaultFormatterFactory {

		private static final long serialVersionUID = 6795960205384589732L;

		private final long min;
		private final long max;

		/** A hex field with no bounds but an int's. */
		public HexFormatterFactory() {
			this(Integer.MIN_VALUE, Integer.MAX_VALUE);
		}

		/** A hex field that refuses typed text outside {@code min}..{@code max}. */
		public HexFormatterFactory(long min, long max) {
			this.min = min;
			this.max = max;
		}

		@Override
		public JFormattedTextField.AbstractFormatter getDefaultFormatter() {
			return new JFormattedTextField.AbstractFormatter() {
				private static final long serialVersionUID = 6795960205384589733L;

				@Override
				public Object stringToValue(String text) throws java.text.ParseException {
					long v;
					try {
						v = Long.parseLong(text.trim(), 16);
					} catch (NumberFormatException nfe) {
						throw new java.text.ParseException(text, 0);
					}
					if (v < min || v > max) {
						throw new java.text.ParseException(text + " is outside " + Long.toHexString(min).toUpperCase()
								+ ".." + Long.toHexString(max).toUpperCase(), 0);
					}
					return (int) v;
				}

				@Override
				public String valueToString(Object value) {
					return value == null ? "" : Integer.toHexString((Integer) value).toUpperCase();
				}
			};
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
	}

	/**
	 * Makes each field hold a {@code cls} - Integer, Float or Short - so what it answers is the box
	 * its saver casts to. Every form wrote this loop out for itself and chose by hand which field got
	 * which class, which is how the prop registry's ea14 went without one (F095); one place now (F113).
	 */
	public static void numberClass(Class<? extends Number> cls, JFormattedTextField... fields) {
		for (JFormattedTextField f : fields) {
			((NumberFormatter) f.getFormatter()).setValueClass(cls);
		}
	}

	/**
	 * Fields nobody has worked out the meaning of, shown as that (F221). pk3DS's 'UnknownFlag', the NPC
	 * form's 'U10' and the trigger form's 'Unknown 2' sat among the named fields as more things to
	 * edit, with nothing to say they were raw bytes. This captions {@code label} with where the bytes
	 * are, folds {@code fields} behind it - a click on the label shows or hides them - and gives label
	 * and fields one tooltip: {@code what}, and that the bytes are written back as the file holds them.
	 * Folding changes no value. MainframeShapeTest refuses a label captioned as raw bytes that is not
	 * handed here or captioned again.
	 */
	public static void unknownField(JLabel label, String caption, String what, JComponent... fields) {
		if (fields.length == 0) {
			throw new IllegalArgumentException("'" + caption + "' must be handed the fields it folds");
		}
		String tip = "<html>" + what + ".<br>Nobody has worked out what these bytes mean. They are written back exactly"
				+ " as the file holds them<br>unless you change them here.</html>";
		label.setToolTipText(tip);
		for (JComponent f : fields) {
			f.setToolTipText(tip);
			f.setVisible(false);
		}
		label.setText("[+] " + caption);
		label.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		label.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				boolean show = !fields[0].isVisible();
				for (JComponent f : fields) {
					f.setVisible(show);
				}
				label.setText((show ? "[-] " : "[+] ") + caption);
				if (label.getParent() != null) {
					label.getParent().revalidate();
					label.getParent().repaint();
				}
			}
		});
	}
}

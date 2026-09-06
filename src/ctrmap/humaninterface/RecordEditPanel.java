package ctrmap.humaninterface;

import ctrmap.formats.recordschema.RecordField;
import ctrmap.formats.recordschema.RecordSchema;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * One form, generated from a {@link RecordSchema}, for any fixed-stride record.
 *
 * <p>The point is that the NEXT structure somebody reverse-engineers gets this
 * form for free. Nothing here knows what an item is: it walks the schema's
 * fields, draws a checkbox for a single bit, a spinner bounded by the field's
 * own width for a number, and a labelled dropdown where the numbers mean
 * something nobody wrote down. A field marked not-editable draws disabled and
 * says why in its tooltip, because a value worth showing is not always a value
 * worth letting somebody change.
 *
 * <p>Undo and redo are over the RECORD, not over the controls. A form of forty
 * fields where each widget remembers its own history is a form where undo does
 * something different depending on which box has focus; a stack of whole record
 * snapshots always means "put it back the way it was".
 */
public class RecordEditPanel extends JPanel {

	/**
	 * Where a dropdown's text comes from.
	 *
	 * <p>Kept out of this class because the labels for an effect id are DERIVED
	 * from the data being edited - "77 - Mystic Water, Sea Incense, Wave
	 * Incense" - and the derivation belongs with the data, not with the widget.
	 */
	public interface Labels {

		/** Text for one value of one field. */
		String label(RecordField field, int value);

		/** Values worth offering, ascending. Never empty. */
		List<Integer> choices(RecordField field);
	}

	/** Told after every change the user makes, with the record as it now stands. */
	public interface Listener {

		void recordChanged(byte[] record);
	}

	private final RecordSchema schema;
	private final Labels labels;
	private final Map<RecordField, JComponent> controls = new LinkedHashMap<>();
	private final List<byte[]> undo = new ArrayList<>();
	private final List<byte[]> redo = new ArrayList<>();
	private byte[] record;
	private boolean loading;
	private Listener listener;

	public RecordEditPanel(RecordSchema schema, Labels labels) {
		this.schema = schema;
		this.labels = labels;
		this.record = new byte[schema.stride()];
		setLayout(new BorderLayout());
		JPanel body = new JPanel();
		body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
		for (String group : schema.groups()) {
			body.add(buildGroup(group));
			body.add(Box.createVerticalStrut(8));
		}
		List<Integer> unmapped = schema.unmappedBytes();
		if (!unmapped.isEmpty()) {
			//An undescribed byte is a hole in what anyone knows about the
			//format. Saying so is better than a form that quietly looks
			//complete: a user who edits every control here has still not
			//touched these.
			StringBuilder sb = new StringBuilder("<html><small>Bytes this editor cannot name: ");
			for (int i = 0; i < unmapped.size(); i++) {
				sb.append(i > 0 ? ", " : "").append("0x")
						.append(Integer.toHexString(unmapped.get(i)));
			}
			sb.append(". They are left exactly as they were.</small></html>");
			JLabel l = new JLabel(sb.toString());
			l.setForeground(Color.DARK_GRAY);
			l.setAlignmentX(0f);
			body.add(l);
		}
		add(body, BorderLayout.NORTH);
	}

	public void setListener(Listener l) {
		this.listener = l;
	}

	private JPanel buildGroup(String group) {
		JPanel p = new JPanel(new GridBagLayout());
		p.setBorder(BorderFactory.createTitledBorder(group));
		p.setAlignmentX(0f);
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(2, 4, 2, 4);
		c.anchor = GridBagConstraints.WEST;
		int row = 0;
		for (RecordField f : schema.fieldsIn(group)) {
			c.gridx = 0;
			c.gridy = row;
			c.weightx = 0;
			c.fill = GridBagConstraints.NONE;
			JLabel name = new JLabel(f.name() + ":");
			name.setToolTipText(tooltip(f));
			p.add(name, c);

			c.gridx = 1;
			c.weightx = 1;
			c.fill = GridBagConstraints.HORIZONTAL;
			JComponent ctl = control(f);
			ctl.setToolTipText(tooltip(f));
			//named after its field so a guard can find the control for a field
			//and drive it. The control-to-record wiring is where a GENERATED
			//form is most likely to be wrong - one widget writing another
			//field's bits looks perfectly normal on screen - and a test that
			//cannot reach the widgets can only check the parts that were
			//already easy to check.
			ctl.setName(f.key());
			ctl.setEnabled(f.editable());
			controls.put(f, ctl);
			p.add(ctl, c);
			row++;
		}
		return p;
	}

	private String tooltip(RecordField f) {
		StringBuilder sb = new StringBuilder("<html>");
		if (!f.help().isEmpty()) {
			sb.append(f.help()).append("<br><br>");
		}
		sb.append("<small>byte 0x").append(Integer.toHexString(f.byteOffset()));
		if (f.bitCount() % 8 != 0 || f.bitOffset() != 0) {
			sb.append(", bit ").append(f.bitOffset()).append(" +").append(f.bitCount());
		} else {
			sb.append(", ").append(f.signed() ? "signed " : "").append(f.bitCount()).append(" bits");
		}
		sb.append(" &middot; ").append(f.min()).append("..").append(f.max());
		if (!f.editable()) {
			sb.append("<br>Shown, not editable.");
		}
		sb.append("</small></html>");
		return sb.toString();
	}

	private JComponent control(final RecordField f) {
		switch (f.kind()) {
			case FLAG: {
				final JCheckBox box = new JCheckBox();
				box.addActionListener(e -> apply(f, box.isSelected() ? 1 : 0));
				return box;
			}
			case EFFECT: {
				final List<Integer> choices = labels.choices(f);
				String[] text = new String[choices.size()];
				for (int i = 0; i < choices.size(); i++) {
					text[i] = labels.label(f, choices.get(i));
				}
				final JComboBox<String> box = new JComboBox<>(text);
				box.addActionListener(e -> {
					int i = box.getSelectedIndex();
					if (i >= 0 && i < choices.size()) {
						apply(f, choices.get(i));
					}
				});
				box.setPreferredSize(new Dimension(340, box.getPreferredSize().height));
				return box;
			}
			default: {
				final JSpinner spin = new JSpinner(new SpinnerNumberModel(0, f.min(), f.max(), 1));
				spin.addChangeListener(e -> apply(f, ((Number) spin.getValue()).intValue()));
				return spin;
			}
		}
	}

	private void apply(RecordField f, int value) {
		if (loading || !f.editable()) {
			return;
		}
		if (f.get(record) == value) {
			return;
		}
		undo.add(record.clone());
		redo.clear();
		f.set(record, value);
		if (listener != null) {
			listener.recordChanged(record());
		}
	}

	/** Loads a record. Clears the history: undoing into a different item's bytes is nonsense. */
	public void setRecord(byte[] rec) {
		if (rec == null || rec.length != schema.stride()) {
			rec = new byte[schema.stride()];
		}
		record = rec.clone();
		undo.clear();
		redo.clear();
		showRecord();
	}

	private void showRecord() {
		loading = true;
		try {
			for (Map.Entry<RecordField, JComponent> e : controls.entrySet()) {
				RecordField f = e.getKey();
				int v = f.get(record);
				Component c = e.getValue();
				if (c instanceof JCheckBox) {
					((JCheckBox) c).setSelected(v != 0);
				} else if (c instanceof JComboBox) {
					List<Integer> choices = labels.choices(f);
					int idx = choices.indexOf(v);
					//a value outside the offered list still has to be shown as
					//itself rather than snapped to the nearest thing in the box
					((JComboBox<?>) c).setSelectedIndex(idx >= 0 ? idx : -1);
				} else if (c instanceof JSpinner) {
					((JSpinner) c).setValue(v);
				}
			}
		} finally {
			loading = false;
		}
	}

	public byte[] record() {
		return record.clone();
	}

	public boolean canUndo() {
		return !undo.isEmpty();
	}

	public boolean canRedo() {
		return !redo.isEmpty();
	}

	public void undo() {
		if (undo.isEmpty()) {
			return;
		}
		redo.add(record.clone());
		record = undo.remove(undo.size() - 1);
		showRecord();
		if (listener != null) {
			listener.recordChanged(record());
		}
	}

	public void redo() {
		if (redo.isEmpty()) {
			return;
		}
		undo.add(record.clone());
		record = redo.remove(redo.size() - 1);
		showRecord();
		if (listener != null) {
			listener.recordChanged(record());
		}
	}
}

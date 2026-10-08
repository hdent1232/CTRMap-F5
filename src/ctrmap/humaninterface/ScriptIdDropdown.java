package ctrmap.humaninterface;

import ctrmap.formats.scripts.TalkerScriptWizard;
import ctrmap.formats.zone.Zone;
import javax.swing.JComboBox;

/**
 * A zone's script ids in a dropdown beside the number field that holds one (F113). The NPC form and
 * the trigger form each filled, matched and read theirs with a copy of the same three methods and a
 * loading flag of their own.
 *
 * <p>Filling and showing set the box from code, and the box's action listener fires for those too, so
 * {@link #picked} answers nothing while either runs: only a choice the user made reaches the field.
 *
 * <p>Not: {@link Combos} - there a box's row IS the stored value; here the stored value is the number
 * in the field beside the box, the rows are whatever ids the zone's script defines, and an item is
 * matched to an id by the number it starts with.
 */
public final class ScriptIdDropdown {

	private final JComboBox<String> box;
	private boolean setting;

	public ScriptIdDropdown(JComboBox<String> box) {
		this.box = box;
	}

	/**
	 * Lists the zone's local script ids, a simple talker marked; none with no zone or no script. Ends
	 * selecting nothing, so a caller that wants a selection shows one after.
	 */
	public void fill(Zone zone) {
		setting = true;
		try {
			box.removeAllItems();
			if (zone != null && zone.s != null) {
				zone.s.decompressThis();
				for (String item : TalkerScriptWizard.buildScriptIdItems(zone.s)) {
					box.addItem(item);
				}
			}
			box.setSelectedIndex(-1);
		} finally {
			setting = false;
		}
	}

	/** Selects the item for {@code scriptId}, or nothing when the zone does not define it. */
	public void show(int scriptId) {
		setting = true;
		try {
			int match = -1;
			for (int i = 0; i < box.getItemCount(); i++) {
				if (ctrmap.util.Text.parseLeadingInt(box.getItemAt(i)) == scriptId) {
					match = i;
					break;
				}
			}
			box.setSelectedIndex(match);
		} finally {
			setting = false;
		}
	}

	/**
	 * The id the user just chose, or -1 while {@link #fill} or {@link #show} is setting the box, when
	 * nothing is selected, or when the item names no id.
	 */
	public int picked() {
		if (setting || box.getSelectedIndex() == -1) {
			return -1;
		}
		return ctrmap.util.Text.parseLeadingInt((String) box.getSelectedItem());
	}
}

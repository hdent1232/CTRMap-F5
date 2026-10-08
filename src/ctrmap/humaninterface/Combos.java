package ctrmap.humaninterface;

import javax.swing.JComboBox;

/**
 * Showing a value from a file in a combo box, and writing it back (F126, F115).
 *
 * <p>ONE PLACE FOR BOTH HALVES. Forms selected a box's row straight from a raw file value, and a
 * value past the rows the box holds threw IllegalArgumentException out of the fill - leaving every
 * field after it showing the previous record, which the next save wrote into this one. Five sites
 * did it with no bound, one had a bound written out by hand. And the save wrote the box's selection
 * back whatever it was, so a value the box could not show was replaced by "nothing selected". Here
 * a value the box cannot hold shows as no selection, and the write keeps the value as read unless
 * the user moved the box.
 *
 * <p>Not: {@link ctrmap.Ui} - it is how a person is told something; this is how a stored value is
 * shown in a box and given back unchanged. Nor {@code NpcMoveCodes}/{@code WarpTransitions} - they
 * name the codes of one field each; this is the bound every box needs whatever it names.
 */
public final class Combos {

	private Combos() {
	}

	/**
	 * Selects {@code index} when {@code box} holds it and nothing when it does not, and answers what
	 * is selected - which is what {@link #keep} compares against at save.
	 */
	public static int show(JComboBox<?> box, int index) {
		box.setSelectedIndex(index >= 0 && index < box.getItemCount() ? index : -1);
		return box.getSelectedIndex();
	}

	/**
	 * What a box writes back: {@code raw}, the value as read, unless the user moved the box from
	 * {@code shown}; then the row it was moved to.
	 */
	public static int keep(JComboBox<?> box, int shown, int raw) {
		return box.getSelectedIndex() == shown ? raw : box.getSelectedIndex();
	}
}

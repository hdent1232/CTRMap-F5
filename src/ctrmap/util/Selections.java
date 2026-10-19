package ctrmap.util;

/**
 * A list's selection, handled once per selection. THE ONE LIST-SELECTION LISTENER (census L.42): a mouse click
 * sends an adjusting event and then a settled one, and eight of the thirteen listeners in this tree did their
 * whole work on both - the raw browser decoded every model of a container twice per click.
 *
 * <p>Not: ctrmap.Ui - the dialog seam sits in package ctrmap, and the pick-a-record package reaching it joined
 * the program's package cycle (PackageCycleTest, 7 to 8); this package is a leaf every form can reach.
 */
public final class Selections {

	private Selections() {
	}

	/** Runs {@code onSelect} once per selection of {@code list}: never on the adjusting event a click sends first. */
	public static void onSettled(javax.swing.JList<?> list, Runnable onSelect) {
		list.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				onSelect.run();
			}
		});
	}
}

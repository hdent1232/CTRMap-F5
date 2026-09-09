package ctrmap.humaninterface;

/**
 * The list of zones, as the things that change it see it: rebuild it, open one,
 * and forget that a slot was declined for forking.
 *
 * <p>WHY THIS EXISTS. The tile painter and the setup wizard reached through the
 * main window for the Zone TAB - a JPanel with forty widgets - to do three
 * things between them, none of which is about the panel. After packing a
 * workspace the painter rebuilds the list and lands the user on the zone it just
 * wrote; after forking a shared map it forgets the decline, because that slot
 * holds a different zone now; and the wizard asks how many zones the game it has
 * just set up turned out to have.
 *
 * <p>The count is deliberately NOT here. It is the loaded zone table's, and
 * {@link ctrmap.LoadedZone} already owns it - reading it off a dropdown's item
 * count was asking the widget that happens to display it.
 */
public interface ZoneList {

	/**
	 * Rebuilds the list from the archives, then runs {@code then} when it is
	 * ready.
	 *
	 * <p>Asynchronous: the rebuild reads every zone header, so it runs on a
	 * worker and the callback is how anything sequences after it. A caller that
	 * did its next step inline would run it against the old list.
	 */
	void rebuild(Runnable then);

	/** Opens the zone in this slot, as though the user had picked it. */
	void open(int index);

	/**
	 * Forgets that the user declined to fork this slot.
	 *
	 * <p>Called when the slot has been given a different zone: the decline was
	 * about the zone that used to be there, and keeping it would silently skip
	 * the fork offer for a map that has never been asked about.
	 */
	void clearForkDecline(int index);
}

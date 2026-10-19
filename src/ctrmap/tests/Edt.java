package ctrmap.tests;

import javax.swing.SwingUtilities;

/**
 * A suite's call made where the application makes it: on the event thread.
 *
 * <p>F220 refuses a progress dialog made off the event thread - a modal dialog shown from another
 * thread was a second event loop on the wrong one - and a suite that drove a Save or a zone load
 * from {@code main} met that refusal instead of the behaviour it was asking about (measured
 * 2026-10-09: TileMapPanelState and ZoneLoadingState both stopped there). The work a dialog waits
 * for still runs on its worker, and the suite still waits here for the answer.
 */
final class Edt {

	private Edt() {
	}

	/** One call that answers something. */
	interface Call<T> {
		T call() throws Exception;
	}

	/** One call that answers nothing. */
	interface Act {
		void run() throws Exception;
	}

	/** {@code what}, made on the event thread; what it answered, or what it threw. */
	static <T> T call(Call<T> what) throws Exception {
		if (SwingUtilities.isEventDispatchThread()) {
			return what.call();
		}
		Object[] answer = new Object[1];
		Exception[] thrown = new Exception[1];
		SwingUtilities.invokeAndWait(() -> {
			try {
				answer[0] = what.call();
			} catch (Exception ex) {
				thrown[0] = ex;
			}
		});
		if (thrown[0] != null) {
			throw thrown[0];
		}
		@SuppressWarnings("unchecked")
		T made = (T) answer[0];
		return made;
	}

	/** {@code what}, made on the event thread. */
	static void run(Act what) throws Exception {
		call(() -> {
			what.run();
			return null;
		});
	}
}

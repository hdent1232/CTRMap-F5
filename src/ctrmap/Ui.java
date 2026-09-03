package ctrmap;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JOptionPane;

/**
 * The one way this program tells the user something went wrong.
 *
 * <p>WHY THIS EXISTS. Every fix in the silent-failures work ends the same way:
 * a path that used to fail quietly now says so. Mutation testing then asked the
 * obvious question - if that sentence were deleted, would anything notice? - and
 * the answer was no, every time. The dialog announcing "Zone 12 was not saved"
 * could be removed and the whole battery still passed, because a
 * {@code JOptionPane.showMessageDialog} call is not observable from a test. It
 * is not that those guards were weak; telling the user was unassertable by
 * construction, across 280 call sites.
 *
 * <p>Worse than unassertable: a headless suite cannot reach those lines at all,
 * because JOptionPane throws HeadlessException with no display. So the one thing
 * every fix depends on was the one thing no test could ever see.
 *
 * <p>Routing a message through here makes "the user was told" a fact a test can
 * assert, and makes the path runnable without a screen. Only the paths a guard
 * needs to see have been moved over - a blanket migration of all 280 would be
 * churn without a reader.
 */
public final class Ui {

	/** Where a message goes. Null means a real dialog. */
	public interface Sink {

		void message(Component parent, String text, String title, int type);
	}

	private static Sink sink;

	private Ui() {
	}

	/** Tells the user something, through a dialog or through a test's sink. */
	public static void message(Component parent, String text, String title, int type) {
		if (sink != null) {
			sink.message(parent, text, title, type);
			return;
		}
		JOptionPane.showMessageDialog(parent, text, title, type);
	}

	/** An error, which is what nearly every one of these is. */
	public static void error(Component parent, String text, String title) {
		message(parent, text, title, JOptionPane.ERROR_MESSAGE);
	}

	/**
	 * Collects what the program says instead of showing it, for the length of a
	 * test. Returns the live list; call {@link #stopRecording()} afterwards.
	 */
	public static List<String> record() {
		final List<String> said = new ArrayList<>();
		sink = (parent, text, title, type) -> said.add(title + ": " + text);
		return said;
	}

	/** Back to real dialogs. */
	public static void stopRecording() {
		sink = null;
	}
}

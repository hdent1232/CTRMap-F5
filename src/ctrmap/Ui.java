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
	private static boolean dialogsEnabled;

	private Ui() {
	}

	/**
	 * Lets this program open real dialogs. ONLY the application calls it.
	 *
	 * <p>Dialogs are off until something asks for them, rather than on until
	 * something suppresses them, because the failure is silent and one-sided: a
	 * suite that reaches a message path it did not wrap in {@link #record()}
	 * puts a modal window on the developer's screen and blocks until somebody
	 * clicks it. Several appeared during a battery run - a foreign-snapshot
	 * warning from three suites that build a scratch game - and the battery only
	 * finished because the owner happened to be at the machine to dismiss them.
	 * Unattended, it would have waited forever, and a run that never ends
	 * reports nothing at all.
	 *
	 * <p>With dialogs off the message still goes somewhere it can be read, so a
	 * suite that trips one is visible in the log rather than lost.
	 */
	public static void enableDialogs() {
		dialogsEnabled = true;
	}

	/** Tells the user something, through a dialog or through a test's sink. */
	public static void message(Component parent, String text, String title, int type) {
		if (sink != null) {
			sink.message(parent, text, title, type);
			return;
		}
		if (!dialogsEnabled) {
			System.out.println("[Ui] " + title + ": " + text.replace("\n", " | "));
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

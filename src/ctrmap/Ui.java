package ctrmap;

import java.awt.Component;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import javax.swing.JOptionPane;

/**
 * The one way this program tells the user something went wrong, and the one way
 * it asks them a question.
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
 * <p>The same is true of the questions - a modal confirm or chooser is not just
 * unassertable, it is a wall: everything after it is unreachable from a test,
 * and mutants living behind one survive by default. {@link #confirm} and
 * {@link #input} give an answer a test can supply, so the branch on the far
 * side becomes ordinary code.
 *
 * <p>Routing a message through here makes "the user was told" a fact a test can
 * assert, and makes the path runnable without a screen. Every call site now
 * does, bar the twenty-two whose dialog body is a live Swing form the user
 * fills in - the seam carries a String on purpose, and recording
 * "javax.swing.JPanel[...]" would be an assertion about nothing.
 * DialogSeamTest holds that list, with a reason against each entry and a
 * ceiling, so the tree cannot drift back.
 *
 * <p>Two things follow from being the only way out. The first is that this
 * class must never say nothing: a blank dialog is the silent failure it was
 * built to remove, so a null or empty text is replaced rather than shown or
 * dereferenced. The second is that a question nobody answers must mean "do
 * nothing" - see {@link #confirm} and {@link #option}.
 */
public final class Ui {

	/** Where a message goes and where an answer comes from. Null means real dialogs. */
	public interface Sink {

		void message(Component parent, String text, String title, int type);

		int confirm(Component parent, String text, String title, int optionType);

		int option(Component parent, String text, String title, Object[] options);

		Object input(Component parent, String text, String title, int type, Object[] options, Object initial);
	}

	private static Sink sink;
	private static boolean dialogsEnabled;
	/**
	 * The window a dialog belongs to when its caller does not name one.
	 *
	 * <p>A dialog has to be parented to something: unparented, it can open
	 * behind the editor, and a modal one that opens behind is a program that
	 * has stopped responding as far as the user can tell. Every caller
	 * therefore passed {@code CtrmapMainframe.frame} - eighty-seven of them,
	 * eighteen outside the window itself, each reaching into the main window's
	 * statics for the one window this program has ever had, and each unable to
	 * be pointed at another.
	 *
	 * <p>So the seam holds it. The application sets it once, when its window
	 * exists; a caller that owns its own window (a dialog opening a second
	 * dialog, a panel parenting to itself) still names that one, which is
	 * better than this and is why the parented overloads remain. It is null
	 * until set, which is exactly what those call sites passed before the
	 * window was built and what a headless suite passes now.
	 */
	private static Component parent;

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

	/**
	 * What a report says when whatever it was handed said nothing.
	 *
	 * <p>A guard that reports nothing is a silent failure with extra steps -
	 * the whole reason this class exists - and a blank dialog is exactly that:
	 * the user sees a title and no reason. The common way to get one is
	 * {@code ex.getMessage()}, which is null for a whole family of exceptions
	 * (NullPointerException among them), and a null went further still: the
	 * dialogs-off path called {@code text.replace} on it, so the report of a
	 * failure died reporting it. Substituting here means the seam is incapable
	 * of saying nothing, whichever of the three paths the message takes.
	 *
	 * <p>It is a floor, not a fix. A call site that can only produce this
	 * should name the exception itself - {@link #reason} does - and
	 * DialogSeamTest refuses a report whose whole text is a bare getMessage()
	 * for that reason.
	 */
	static final String NOTHING_SAID = "(no details were given)";

	private static String saying(String text) {
		return text == null || text.trim().isEmpty() ? NOTHING_SAID : text;
	}

	/**
	 * Hands the seam the window dialogs belong to. ONLY the application calls
	 * it, once, with the window it has just built.
	 */
	public static void parent(Component window) {
		parent = window;
	}

	/**
	 * That window, for the twenty-two dialogs whose body is a live Swing form
	 * the seam cannot carry (DialogSeamTest names every one). They need a
	 * parent like any other dialog; what they must not need is the main
	 * window's statics.
	 */
	public static Component parent() {
		return parent;
	}

	/** Tells the user something, parented to the window the application set. */
	public static void message(String text, String title, int type) {
		message(parent, text, title, type);
	}

	/** An error, parented to the window the application set. */
	public static void error(String text, String title) {
		error(parent, text, title);
	}

	/** A question, parented to the window the application set. */
	public static int confirm(String text, String title, int optionType) {
		return confirm(parent, text, title, optionType);
	}

	/** The same, with the icon the caller names. */
	public static int confirm(String text, String title, int optionType, int messageType) {
		return confirm(parent, text, title, optionType, messageType);
	}

	/** Named buttons, parented to the window the application set. */
	public static int option(String text, String title, int optionType, int messageType,
			Object[] options, Object initial) {
		return option(parent, text, title, optionType, messageType, options, initial);
	}

	/** A choice, parented to the window the application set. */
	public static Object input(String text, String title, int type, Object[] options, Object initial) {
		return input(parent, text, title, type, options, initial);
	}

	/** Tells the user something, through a dialog or through a test's sink. */
	public static void message(Component parent, String text, String title, int type) {
		text = saying(text);
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
	 * What a report says about {@code ex}: its message when it gave one, and
	 * otherwise the exception itself - "java.lang.NullPointerException" tells
	 * the reader which line to go and look at, "null" tells them nothing.
	 *
	 * <p>Every report that quotes an exception goes through here. The same
	 * conditional used to be written out at each call site, and a site that
	 * forgot it showed "null"; a message that is present but blank is treated
	 * the same as a missing one, because a blank is the silent failure too.
	 */
	public static String reason(Throwable ex) {
		String m = ex.getMessage();
		return m == null || m.trim().isEmpty() ? ex.toString() : m;
	}

	/**
	 * Asks the user a yes/no or ok/cancel question and returns their answer, as
	 * one of JOptionPane's option constants.
	 *
	 * <p>With no display and no sink the answer is CLOSED_OPTION - the same as
	 * closing the dialog - so every caller must treat "closed" as "do nothing",
	 * never as consent. That is why the call sites test for the option they act
	 * on rather than the one they skip.
	 */
	public static int confirm(Component parent, String text, String title, int optionType) {
		return confirm(parent, text, title, optionType, JOptionPane.WARNING_MESSAGE);
	}

	/**
	 * The same question, drawn with the icon the caller asks for.
	 *
	 * <p>The four-argument form above keeps WARNING_MESSAGE rather than
	 * JOptionPane's own QUESTION_MESSAGE default, because every caller it had
	 * before this overload existed is asking about something destructive. A
	 * migrated call site must therefore name its icon: passing a raw
	 * {@code showConfirmDialog}'s implicit QUESTION_MESSAGE through the
	 * four-argument form would silently repaint it as a warning, which is a
	 * behaviour change dressed up as a refactor.
	 */
	public static int confirm(Component parent, String text, String title, int optionType, int messageType) {
		text = saying(text);
		if (sink != null) {
			return sink.confirm(parent, text, title, optionType);
		}
		if (!dialogsEnabled) {
			System.out.println("[Ui] " + title + "? " + text.replace("\n", " | "));
			return JOptionPane.CLOSED_OPTION;
		}
		return JOptionPane.showConfirmDialog(parent, text, title, optionType, messageType);
	}

	/**
	 * Asks the user to press one of several named buttons and returns which,
	 * as an index into options - or CLOSED_OPTION (-1) when they closed the
	 * dialog, which is also the nobody-is-there answer.
	 *
	 * <p>Every caller must therefore treat a negative index as "do nothing".
	 * They already do: each one guards its action with {@code != 0} or
	 * {@code < 0} rather than with "not the cancel button", so an unanswered
	 * question cannot be mistaken for the destructive choice.
	 */
	public static int option(Component parent, String text, String title, int optionType, int messageType,
			Object[] options, Object initial) {
		text = saying(text);
		if (sink != null) {
			return sink.option(parent, text, title, options);
		}
		if (!dialogsEnabled) {
			System.out.println("[Ui] " + title + "? " + text.replace("\n", " | "));
			return JOptionPane.CLOSED_OPTION;
		}
		return JOptionPane.showOptionDialog(parent, text, title, optionType, messageType, null, options, initial);
	}

	/**
	 * Asks the user to pick one of options and returns it, or null when they
	 * cancel - which is also what nobody-is-there answers.
	 */
	public static Object input(Component parent, String text, String title, int type, Object[] options, Object initial) {
		text = saying(text);
		if (sink != null) {
			return sink.input(parent, text, title, type, options, initial);
		}
		if (!dialogsEnabled) {
			System.out.println("[Ui] " + title + "? " + text.replace("\n", " | "));
			return null;
		}
		return JOptionPane.showInputDialog(parent, text, title, type, null, options, initial);
	}

	/**
	 * Collects what the program says instead of showing it, for the length of a
	 * test, and answers the questions it asks with the given answers in order:
	 * an Integer option constant for {@link #confirm}, an Integer button index
	 * for {@link #option}, the chosen object for {@link #input}. Running out
	 * means the user closed the dialog. Returns the live list of what was said;
	 * call {@link #stopRecording()} afterwards.
	 */
	public static List<String> record(Object... answers) {
		final List<String> said = new ArrayList<>();
		final Queue<Object> queue = new LinkedList<>(Arrays.asList(answers));
		sink = new Sink() {
			@Override
			public void message(Component parent, String text, String title, int type) {
				said.add(title + ": " + text);
			}

			@Override
			public int confirm(Component parent, String text, String title, int optionType) {
				said.add(title + ": " + text);
				Object answer = queue.poll();
				return answer instanceof Integer ? (Integer) answer : JOptionPane.CLOSED_OPTION;
			}

			@Override
			public int option(Component parent, String text, String title, Object[] options) {
				said.add(title + ": " + text);
				Object answer = queue.poll();
				return answer instanceof Integer ? (Integer) answer : JOptionPane.CLOSED_OPTION;
			}

			@Override
			public Object input(Component parent, String text, String title, int type, Object[] options, Object initial) {
				said.add(title + ": " + text);
				return queue.poll();
			}
		};
		return said;
	}

	/**
	 * Sends what this program says to a sink of the caller's own. For a test
	 * that has to see more than the text - {@link #record} keeps what was
	 * said and throws away which window it was said over, which is the one
	 * thing the parented and unparented forms differ by.
	 */
	public static void into(Sink custom) {
		sink = custom;
	}

	/** The user's answer to "keep the changes to X?" - see {@link #askToKeep}. */
	public enum Keep {
		SAVE, DISCARD, CANCEL
	}

	/**
	 * Asks whether the changes to {@code changeSubject} should be kept - or,
	 * when the caller wants no dialog, answers SAVE without asking.
	 *
	 * <p>WHY THIS EXISTS. Eleven store() methods asked this question, and each
	 * one switched on JOptionPane's integer answer by hand, which meant each
	 * one had to remember on its own that a CLOSED dialog is a cancel: the X
	 * button is not consent, and a headless caller - which gets CLOSED by
	 * definition - must never find its file written. One of them forgot
	 * (NPCRegistry.store, the case DialogSeamTest proves), its switch ran off
	 * the end, and closing the dialog saved the registry. The rule is written
	 * once, here, and a caller that cannot see JOptionPane cannot get it wrong.
	 *
	 * <p>It lived on {@code ctrmap.Utils} until the package split, which had
	 * put the one question this program asks about unsaved work in the same
	 * class as the byte helpers the format layer reads with.
	 */
	public static Keep askToKeep(boolean dialog, String changeSubject) {
		if (!dialog) {
			return Keep.SAVE;
		}
		switch (confirm(changeSubject + " has been modified. Do you want to keep the changes?", "Save changes",
				JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)) {
			case JOptionPane.YES_OPTION:
				return Keep.SAVE;
			case JOptionPane.NO_OPTION:
				return Keep.DISCARD;
			//closing the dialog is cancel, never "write it anyway"
			case JOptionPane.CLOSED_OPTION:
			case JOptionPane.CANCEL_OPTION:
			default:
				return Keep.CANCEL;
		}
	}

	/**
	 * Explains that no workspace is loaded when a File menu open action is
	 * triggered. Returns true if the user still wants to open a loose file.
	 */
	public static boolean confirmOpenWithoutWorkspace(String action) {
		return confirm(
				"No workspace is loaded, so \"" + action + "\" can only open a single loose file.\n\n"
				+ "To edit a game, first set the RomFS (game directory) and workspace paths in\n"
				+ "Options > Workspace settings. CTRMap then unpacks the game's GARC archives into\n"
				+ "the workspace, and you pick a map from the zone dropdown in the \"Zone Loader\" tab.\n\n"
				+ "Continue anyway to open a loose file?",
				"No workspace loaded", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
	}

	/** Back to real dialogs. */
	public static void stopRecording() {
		sink = null;
	}
}

package ctrmap.humaninterface;

import com.jogamp.opengl.GLProfile;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

/**
 * Whether OpenGL can start in this program, asked once, and what stands where a 3D view cannot (F077).
 *
 * <p>2D-ONLY WHEN GL CANNOT START. Every 3D surface - the world view, the collision view, the zone preview,
 * the model and region previews - asked GLProfile for a profile as it was built, and on a machine whose
 * OpenGL natives do not load that is an UnsatisfiedLinkError out of the constructor: the main window did
 * not open at all, and the 2D map editor, the forms and the Game Data tab went with it. Measured on Linux
 * amd64 with the bundled lib/ (no linux natives): the error comes from the map panel's own constructor,
 * which keeps a GL overlay. Every surface is now built only where {@link #available} says GL can start,
 * and stands as a {@link #placeholder} saying so where it cannot.
 *
 * <p>ASKED ONCE: a JVM whose natives failed to load cannot load them later - JOGL's platform class is
 * dead after a failed initialiser - so the first answer is the answer for the run.
 *
 * <p>Not: {@link ShownLoop} - that decides when a surface that exists draws; this decides whether one
 * can exist at all, and has to answer before any is built.
 */
public final class GlSupport {

	private GlSupport() {
	}

	/** Why OpenGL cannot start, or null when it can; null too before anything has asked. */
	private static String unavailable;
	private static boolean asked;

	/** JOGL's switch for probing OpenGL ES at start-up. Nothing here asks for ES: every surface asks for desktop GL2. */
	static final String NO_ES = "jogl.disable.opengles";

	/** Why OpenGL cannot start in this program, or null when it can. */
	public static synchronized String unavailable() {
		if (!asked) {
			asked = true;
			//OPENGL ES IS NOT PROBED ON WINDOWS (2026-10-24). JOGL 2.6.0, which came with 1.0.4 for its macOS and
			//Linux natives, probes ES as well as desktop GL on its first question, and that probe held the window
			//off the screen: measured through the real window on copies of the owner's workspace and game, 3 launches
			//each, interleaved, OpenGL start-up 1650 ms and the window at 3832 ms - with this switch 750 ms and
			//2674 ms, where 1.0.3 (JOGL 2.3.2) showed its window at 2579. Read once, in GLProfile's initialiser, so
			//it is set here, before the first question. Windows only, because only there was it measured; a value
			//set by whoever started the program is theirs.
			if (System.getProperty("os.name", "").startsWith("Windows") && System.getProperty(NO_ES) == null) {
				System.setProperty(NO_ES, "true");
			}
			try {
				GLProfile.get(GLProfile.GL2);
			} catch (LinkageError | RuntimeException cannot) {
				//the natives did not load (UnsatisfiedLinkError, and NoClassDefFoundError after it), or they
				//loaded and the driver offers no GL2 profile (GLException)
				unavailable = cannot.getClass().getSimpleName() + ": " + ctrmap.Ui.reason(cannot);
			}
		}
		return unavailable;
	}

	/** Whether a 3D surface can be built. */
	public static boolean available() {
		return unavailable() == null;
	}

	/*
	 * NOT ASKED BESIDE THE EVENT THREAD (measured 2026-10-24). Started on a thread of its own while the event thread
	 * built the window, the probe deadlocked 12 runs of 12: JOGL's JAWTUtil initialiser invokes and waits on the event
	 * thread, which was waiting on this class's lock. Doing the native-window part on the event thread first avoided it
	 * on Windows, but whether JOGL needs the event thread later on Linux or macOS cannot be measured here - and a
	 * deadlock there is the editor hanging at launch. So it is asked where it always was: on the event thread.
	 */

	/** What stands where {@code what} would be drawn, saying why it is not and that the rest works. */
	public static JComponent placeholder(String what) {
		JLabel said = new JLabel("<html><center>" + ctrmap.Ui.html(what) + " is not shown: OpenGL could not start"
				+ " on this machine.<br><small>" + ctrmap.Ui.html(String.valueOf(unavailable())) + "</small><br>"
				+ "Everything that does not draw in 3D works without it.</center></html>", SwingConstants.CENTER);
		said.setName("gl-unavailable");
		return said;
	}
}

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

	/** Why OpenGL cannot start in this program, or null when it can. */
	public static synchronized String unavailable() {
		if (!asked) {
			asked = true;
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

	/** What stands where {@code what} would be drawn, saying why it is not and that the rest works. */
	public static JComponent placeholder(String what) {
		JLabel said = new JLabel("<html><center>" + ctrmap.Ui.html(what) + " is not shown: OpenGL could not start"
				+ " on this machine.<br><small>" + ctrmap.Ui.html(String.valueOf(unavailable())) + "</small><br>"
				+ "Everything that does not draw in 3D works without it.</center></html>", SwingConstants.CENTER);
		said.setName("gl-unavailable");
		return said;
	}
}

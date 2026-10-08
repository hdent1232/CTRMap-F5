package ctrmap.humaninterface;

import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLContext;
import java.util.function.Supplier;

/**
 * The one offscreen GL drawable a panel draws into, replaced whole when the panel changes size.
 *
 * <p>Not: {@link com.jogamp.opengl.GLOffscreenAutoDrawable} - that is the drawable; this owns which
 * one is current and ends the one it replaces. The World Editor made a new offscreen drawable on
 * every componentResized and dropped the old one with its context and framebuffer still alive, so
 * dragging the window's edge made one per step of the drag, none ever released (F110).
 */
public final class OffscreenSlot {

	private GLAutoDrawable current;

	/** The drawable in use, or null before the first {@link #replace}. */
	public GLAutoDrawable current() {
		return current;
	}

	/**
	 * Ends the drawable in use - its context released if it is current here, then destroyed - and
	 * makes {@code make}'s the one in use.
	 */
	public GLAutoDrawable replace(Supplier<GLAutoDrawable> make) {
		end();
		current = make.get();
		return current;
	}

	/** Ends the drawable in use, if there is one; the slot is empty afterwards. */
	public void end() {
		GLAutoDrawable old = current;
		current = null;
		if (old == null) {
			return;
		}
		GLContext context = old.getContext();
		if (context != null && context.isCurrent()) {
			context.release();
		}
		old.destroy();
	}
}

package ctrmap.humaninterface;

import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.util.FPSAnimator;
import java.awt.Component;
import java.awt.event.HierarchyEvent;

/**
 * A render loop that runs only while its panel can be seen.
 *
 * <p>Not: {@link FPSAnimator} - that is the loop; this decides when it runs, which every panel
 * that started one in its constructor left to nobody (F098, F099). The legacy collision viewer
 * drew 300 frames a second from application start whether or not its tab was ever opened, and
 * each model preview, map preview and the 3D view ran their own from the moment they were built -
 * the registry editors' and the prop form's never stopped at all, and the prop form's kept a suite's
 * JVM alive after it had finished.
 *
 * <p>So a panel asks for its loop here, and the loop follows the panel: started when it is shown,
 * stopped when it is hidden or taken out of its window, and stopped for good by {@link #end}.
 */
public final class ShownLoop {

	/** What runs: an FPSAnimator in the program, a stand-in in a suite. */
	public interface Loop {

		void start();

		void stop();

		boolean isStarted();
	}

	private final Component panel;
	private final Loop loop;
	private boolean ended;

	/** A loop of {@code fps} frames a second over {@code panel}, running while it is showing. */
	public static ShownLoop of(GLAutoDrawable panel, int fps) {
		final FPSAnimator animator = new FPSAnimator(panel, fps, true);
		return new ShownLoop((Component) panel, new Loop() {
			@Override
			public void start() {
				animator.start();
			}

			@Override
			public void stop() {
				animator.stop();
			}

			@Override
			public boolean isStarted() {
				return animator.isStarted();
			}
		});
	}

	/** {@code loop}, following {@code panel}: running exactly while the panel is showing. */
	public ShownLoop(Component panel, Loop loop) {
		this.panel = panel;
		this.loop = loop;
		panel.addHierarchyListener(e -> {
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
				follow();
			}
		});
		follow();
	}

	/** Starts the loop when the panel is showing and stops it when not; nothing once ended. */
	void follow() {
		if (!ended && panel.isShowing()) {
			if (!loop.isStarted()) {
				loop.start();
			}
		} else if (loop.isStarted()) {
			loop.stop();
		}
	}

	/** Stops the loop for good: the panel is done with it, shown or not. */
	public void end() {
		ended = true;
		follow();
	}

	/** Whether the loop is running now. */
	@ctrmap.util.ForSuites("SEAM: a suite asks whether the render loop runs, to see it stops when its view is hidden")
	public boolean running() {
		return loop.isStarted();
	}
}

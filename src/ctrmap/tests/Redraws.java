package ctrmap.tests;

import ctrmap.humaninterface.Redraw;

/**
 * A redraw a suite can read back.
 *
 * <p>{@code CtrmapMainframe.frame.repaint()} was unassertable twice over: a
 * JFrame cannot be built without a display, so a headless suite could not reach
 * the line at all, and even where it could, a repaint leaves no trace a test can
 * ask about. Every form that changed something the user is looking at ended in
 * one of those calls, so "and the editor was told to draw it again" was a
 * property nothing could hold the code to.
 *
 * <p>Handed one of these instead, a form's request is a number.
 */
final class Redraws implements Redraw {

	int asked;

	@Override
	public void all() {
		asked++;
	}

	/** A mark to compare against, so a check can say "since this point". */
	int mark() {
		return asked;
	}

	/** True when a redraw was asked for since {@code mark}. */
	boolean askedSince(int mark) {
		return asked > mark;
	}
}

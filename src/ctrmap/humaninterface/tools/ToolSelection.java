package ctrmap.humaninterface.tools;

import java.util.function.Supplier;

/**
 * Which editing tool the World Editor is holding, and the one place a
 * different one is picked up.
 *
 * <p>WHY THIS EXISTS. The held tool was {@code CtrmapMainframe.tool}, a public
 * mutable static of the main window: nine classes read it and one - the
 * tilemap's mouse router - assigned it from ten places. A test could set it,
 * because anything could; what a test could not do was hold two, so "the NPC
 * form shows a marker while the NPC tool is up" had to be asserted by writing
 * to the window's field and hoping nothing else had.
 *
 * <p>The switch has an invariant, and it lived in the router: the tool being
 * put down is told to shut down BEFORE the next one is built, because a tool's
 * constructor runs {@code onToolInit} and puts its own form in the editor's
 * pane, which the outgoing tool's shutdown would otherwise undo. That is why
 * {@link #switchTo} takes a supplier rather than a tool: handing it
 * {@code new EditTool()} would build the incoming tool first and reverse the
 * order that matters.
 */
public final class ToolSelection {

	private AbstractTool held;

	/** The tool the editor is holding, or null before the first is picked up. */
	public AbstractTool current() {
		return held;
	}

	/** True when the tool being held is one of {@code kind}. */
	public boolean holding(Class<? extends AbstractTool> kind) {
		return kind.isInstance(held);
	}

	/**
	 * Puts down what is held - telling it so - and picks up what the supplier
	 * builds, in that order, then starts it. See the class comment for why the
	 * order is the contract and not an implementation detail; a tool's setup
	 * runs in {@link AbstractTool#start}, after it has been built and handed
	 * its form, and this is the one place that calls it.
	 */
	public void switchTo(Supplier<AbstractTool> next) {
		if (held != null) {
			held.onToolShutdown();
		}
		held = next.get();
		if (held != null) {
			held.start();
		}
	}

	/**
	 * Tells whatever is held that the open zone changed underneath it.
	 *
	 * <p>Nothing is picked up or put down: the tool stays. See
	 * {@link AbstractTool#onZoneChanged} for why the tool decides what this
	 * means rather than the caller deciding for it.
	 */
	public void zoneChanged() {
		if (held != null) {
			held.onZoneChanged();
		}
	}

	/**
	 * Puts down what is held without picking anything up, and WITHOUT telling
	 * it: this is "there is no editor any more", not a tool switch. The
	 * application never does it; a suite does, to assert what a class does
	 * when no tool is up.
	 */
	public void drop() {
		held = null;
	}
}

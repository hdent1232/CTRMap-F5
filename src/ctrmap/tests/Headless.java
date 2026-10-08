package ctrmap.tests;

import java.awt.GraphicsEnvironment;

/**
 * The one way a suite runs without a screen, and the proof that it does.
 *
 * <p>A SWITCH SET AFTER AWT HAS DECIDED IS NO SWITCH. AWT reads {@code java.awt.headless} once, the
 * first time anything asks, and a component built before that - a panel in a static field, built
 * when the class loads, before {@code main} - asks. DataSafetyGuardsTest set the switch as the
 * "FIRST statement" of main, under a comment explaining why it had to be first, while a static panel
 * three dozen lines above had already settled the answer: measured, a JPanel built before the
 * property is set leaves isHeadless() false, and the same run without it true. Its three progress
 * dialog checks ran against a screen they had been written to avoid, and when OpenGL stopped killing
 * the suite before them (F077) the first of them was refused for making a dialog off the event thread.
 *
 * <p>So this sets the switch and then asks AWT whether it took, and a suite whose switch came too late
 * stops there, saying so - instead of running its dialog checks against a screen. Ten suites each set
 * it by hand and none asked; GlUnavailableTest refuses any class but this one that names the property.
 *
 * <p>Not: a {@code -Djava.awt.headless=true} on the runner - test.ps1 passes no flags, a suite run by
 * hand has whatever its caller gave it, and a suite must not depend on how it was started.
 */
final class Headless {

	private Headless() {
	}

	/** Runs AWT without a screen from here on, or refuses: the answer was settled before this was asked. */
	static void require() {
		System.setProperty("java.awt.headless", "true");
		if (!GraphicsEnvironment.isHeadless()) {
			throw new IllegalStateException("the headless switch came too late: AWT had already decided there is"
					+ " a screen, so something built a component before this ran - call Headless.require() from a"
					+ " static block at the top of the class, before any field that builds one");
		}
	}
}

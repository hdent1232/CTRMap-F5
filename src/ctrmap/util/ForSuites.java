package ctrmap.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A production method that only the suites call, with the decision in words (F204).
 *
 * <p>EveryMethodIsReachedTest walks what the program calls from its own roots. A method only a suite
 * reaches is a path the suites test and the program never takes - 132 of them on 2026-10-10, held to a
 * count nobody had decided on, and among them two the program had written a second time beside the
 * tested one (the prop form's missing textures, the encounter dialog's read), so the tested copy was
 * never the one that ran. A method that is not the program's must say what it is instead, and the
 * reason starts with which of three it is:
 *
 * <ul>
 * <li>{@code SEAM:} it lets a suite drive or watch the program - a recording sink, a count, a handle
 *     swapped for one that misbehaves - and says what the suite does with it;</li>
 * <li>{@code ORACLE:} a second reading of what the program reads or writes, kept apart from the
 *     program's own so a suite can hold one against the other, and says which;</li>
 * <li>{@code CAPABILITY:} a format ability proven against retail by a suite that no part of the editor
 *     offers yet, and says what would offer it.</li>
 * </ul>
 *
 * <p>Not: a shorter way to call something the program calls. That is deleted, and its suites call the
 * program's form - otherwise they test a door the program does not use. EveryMethodIsReachedTest refuses
 * a suite-only method with no mark, a mark on a method the program reaches, and a reason that does not
 * say which of the three it is.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface ForSuites {

	/** "SEAM: ", "ORACLE: " or "CAPABILITY: ", then why. */
	String value();
}

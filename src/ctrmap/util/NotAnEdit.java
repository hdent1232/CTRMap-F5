package ctrmap.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A method that writes a file and changes nothing the user made, with the reason in words (F209d).
 *
 * <p>The undo rule finds the program's editors by what their methods reach: a raw file write, or the mark an
 * edit leaves. Some writes are the workspace's own plumbing - an entry of the dump extracted into the
 * workspace unchanged the first time it is asked for - and that one, reached from nearly every form, held 6
 * classes open on 2026-10-08: both registry editors, the zone preview and three drag tools, every edit of
 * their own already taken by a history, and one of the tools no editor at all. A method carrying this is
 * where the rule stops following: nothing it writes makes its callers editors.
 *
 * <p>It may cut only a raw write. A method that marks an edit - {@code edited()}, {@code addPersist}, a
 * {@code storeFile}, a {@code modified} flag - is an edit by definition and refused; so is one in a class the
 * user drives, where the edit is made; and the reason must be a reason, not a word. UndoCoverageTest asks all
 * three, and that each one cuts a write.
 *
 * <p>Not: {@link ctrmap.humaninterface.UndoHistory.Place} - a Place is a file a history writes and can put
 * back; this marks a write no history should hold, because it changes nothing to put back.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface NotAnEdit {

	/** Why what this method writes is nothing the user would take back. */
	String value();
}

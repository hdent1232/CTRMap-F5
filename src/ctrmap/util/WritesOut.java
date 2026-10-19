package ctrmap.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A method that writes to disk the state of documents its own class's history watches, saying which (F209d).
 *
 * <p>A form whose edits a history reads from documents - the zone's header boxes, its tiles, its geometry -
 * keeps them where they are made, in memory, around each gesture; Undo puts the document back and the form
 * shows it. Its store then writes whatever the documents hold. Judged per entry, each such store read as an
 * edit no history keeps (6 forms, 2026-10-08), though what it writes is exactly what the history holds.
 *
 * <p>UndoCoverageTest stops at a method carrying this, and refuses it in a class whose history watches no
 * document - there is nothing it could be writing out - and with a description that is a word.
 *
 * <p>Not: {@link NotAnEdit} - that says a write changes nothing the user made; this one writes what the user
 * made, and says where the history that took it is.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface WritesOut {

	/** Which documents it writes out, and whose history watches them. */
	String value();
}

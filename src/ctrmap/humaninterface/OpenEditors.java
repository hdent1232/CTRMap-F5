package ctrmap.humaninterface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every editor that holds unsaved work, in the order they are written, and the
 * one place that says so.
 *
 * <p>WHY THIS EXISTS. "Write out whatever the editors are holding, and stop if
 * the user refuses" was spelled out five times, as a chain of {@code &&} over
 * the main window's statics:
 *
 * <pre>
 * mCamEditForm.store(true) &amp;&amp; mTileMapPanel.saveTileMap(true) &amp;&amp; mMtxEditForm.store(true)
 *     &amp;&amp; mPropEditForm.store(true) &amp;&amp; mNPCEditForm.saveRegistry(true) &amp;&amp; ...
 * </pre>
 *
 * <p>The five copies did not agree. The window's close handler wrote the text
 * editor; File &gt; Save wrote the text editor AND the collision editor; the
 * three flushes the Zone tab runs before it reloads the zone list, and the one
 * the map painter runs before it applies, wrote neither. So whether an unsaved
 * collision mesh survived cloning a zone depended on which of five lines you
 * happened to reach - and nothing anywhere said what the set of editors was.
 * The two orders disagreed as well: File &gt; Save wrote the tilemap first, the
 * chains wrote the camera first.
 *
 * <p>One list, one order, named here. What that changes is stated in the commit
 * rather than smuggled: the flush before a reload now writes the collision and
 * text editors too, which is what "save what is open" was always supposed to
 * mean, and File &gt; Save writes in the chains' order.
 *
 * <p>Each editor is a lambda over the form the window built, so no form has to
 * implement anything and the set stays readable as a list.
 *
 * <p>Not final: a suite hands a panel a set that records the flush instead of
 * doing it, which is how "it flushed before it reloaded" and "a refusal stops
 * the reload" became things a test can ask.
 */
public class OpenEditors {

	/** One editor's save. Answers false only when the user was asked and refused. */
	public interface Editable {

		boolean save(boolean askFirst);
	}

	private final List<Editable> editors;

	public OpenEditors(List<Editable> editors) {
		if (editors == null || editors.isEmpty()) {
			throw new IllegalArgumentException("the editor set cannot be empty - name the editors that hold work");
		}
		this.editors = Collections.unmodifiableList(new ArrayList<>(editors));
	}

	/**
	 * Writes what every editor holds, in order, and STOPS at the first one
	 * that refuses - which is the whole point of the {@code &&} chain this
	 * replaces: an editor that could not save must not be followed by a
	 * reload that throws its work away.
	 *
	 * @param askFirst whether an editor with changes may ask the user
	 * @return false as soon as one refuses; true when every one wrote
	 */
	public boolean saveAll(boolean askFirst) {
		for (Editable e : editors) {
			if (!e.save(askFirst)) {
				return false;
			}
		}
		return true;
	}

	/** How many editors are in the set; the guard checks the count it was built with. */
	public int size() {
		return editors.size();
	}
}

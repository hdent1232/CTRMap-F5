package ctrmap.humaninterface;

/**
 * The 3D navigator gizmo: which record it stands over, and when to re-read it.
 *
 * <p>WHY THIS EXISTS. "The gizmo follows this record" was written out five
 * times through the main window's statics, and the five did not agree about
 * the guard. The NPC form wrapped it in a private helper with a null check and
 * a javadoc saying why - "the guard tests drive this form with no 3D panel and
 * no window". The window's own {@code ToolHost.releaseNavi} was the same
 * guarded reach written again. The prop editor's two reaches had no guard at
 * all, and one of them reached through the panel into its public
 * {@code navi} field; the Zone tab's had none either, on a worker thread.
 *
 * <p>Two of five guarded, three did not, and what the three cost was not
 * hypothetical: the prop editor's unguarded reach threw inside a catch-all and
 * left the whole form permanently inert, responding to nothing and saying
 * nothing.
 *
 * <p>So the guard lives in ONE place now - the window's implementation - and
 * every caller is handed this instead. {@code follow(null)} is how you let go,
 * so "bind" and "release" stopped being two verbs.
 */
public interface Navigator {

	/**
	 * The gizmo now stands over this record; {@code null} lets it go.
	 *
	 * <p>Called when the selected NPC or prop changes, when the last one is
	 * removed, when a zone is opened, and on every tool switch - a gizmo left
	 * bound by the NPC editor must not be standing over a prop.
	 */
	void follow(MapObject o);

	/**
	 * The record it is following has moved; re-read its position from it.
	 *
	 * <p>Unconditional at the call site on purpose: the navigator itself
	 * returns immediately when it is following nothing, and hoisting that test
	 * up here would put the same condition in two places again.
	 */
	void resync();
}

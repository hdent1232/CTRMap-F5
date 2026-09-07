package ctrmap.gamedef;

/**
 * The games this editor knows how to speak about.
 *
 * <p>Lives here rather than on {@code Workspace} because it is the gamedef
 * seam's vocabulary, not the open workspace's: a profile, an archive path
 * table or a test fixture names a game without there being a workspace at all.
 * While this enum was nested in {@code Workspace}, every one of the 41 files
 * that merely wanted to say "ORAS" had to reach into the global to say it.
 */
public enum GameType {
	XY,
	ORAS,
	/** Sun/Moon (Gen 7) - detected/served via gamedef profiles; not yet supported. */
	SM,
	/** Ultra Sun/Ultra Moon (Gen 7) - not yet supported. */
	USUM
}

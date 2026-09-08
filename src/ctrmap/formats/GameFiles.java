package ctrmap.formats;

import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import java.io.File;

/**
 * What a format class needs from whoever opened the game: stated here, by the
 * format layer, and handed in by the caller.
 *
 * <p>This is the format layer's side of a seam, not a copy of the
 * application's workspace API. Every class under {@code ctrmap.formats} that
 * read or wrote an extracted file used to fetch the open game from the
 * application's global session holder itself. Two costs, both measured: none
 * of those classes could be exercised without a live workspace installed in
 * the global, because each resolved one on its own; and a write made while no
 * workspace was open was recorded nowhere and said nothing, so the edit sat in
 * the extracted file and the next pack never picked it up. A class that
 * DECLARES what it needs, as a parameter of this type, is handed the open game
 * by the application and a scratch stand-in ({@code ctrmap.tests.FakeGameFiles})
 * by a suite, and cannot tell the difference. That is what makes it testable,
 * and what makes "handed a different game" possible at all.
 *
 * <p>{@code ctrmap.WorkspaceSession} implements this by delegation. The static
 * facade in front of it deliberately does not: a facade satisfying the contract
 * would let a caller keep the global and still look migrated, and
 * {@code ctrmap.tests.GameFilesSeamTest} refuses that.
 *
 * <p>Nothing here invents a value. Where the game lacks something - an archive
 * nobody has located for it, a snapshot that was never taken - the answer is
 * null in the sense {@link GameProfile} documents: absence, to be refused in
 * words, never a guess filled in from another game.
 *
 * <p>The methods are named for what a caller wants, and there are exactly as
 * many as the format classes use. Prefer the narrowest thing that works: a
 * class that only ever writes one file should be handed that file, one that
 * reads one archive should be handed its {@link GARC}, one that needs the
 * per-game answers should be handed the {@link GameProfile}, and this whole
 * contract only where a class genuinely needs several of them.
 */
public interface GameFiles {

	/**
	 * The workspace's extracted copy of one archive entry, extracted first when
	 * the workspace does not hold it yet; null when this game lacks the archive,
	 * nothing extracts it, or the entry cannot be read.
	 *
	 * <p>For an index the archive does not hold YET, the path where that entry
	 * would be staged is returned, not yet existing, because that is how a new
	 * entry is appended: the builder writes the file and the next pack adds it.
	 */
	File staged(ArchiveType type, int entry);

	/** Records that an extracted file was changed and must be packed back into its archive. */
	void edited(File f);

	/** A directory this operation may write temporary files into; nothing in it survives cleaning the workspace. */
	File scratch();

	/** The opened archive, or null when the game lacks it or nothing opened it. */
	GARC archive(ArchiveType type);

	/** Where this game keeps an archive on disk, opened or not; null when the game lacks it or its location is unverified. */
	File archiveFile(ArchiveType type);

	/** The per-game answers: archive paths, text indices, feature gates, measured counts. */
	GameProfile profile();

	/** Which edition of the game the dump is; a demo keeps some tables in other entries than retail. */
	GameProfile.Variant variant();

	/** The directory holding the untouched copy of the game's archives, for a class that diffs against what shipped; null when no snapshot was taken. */
	File pristine();

	/**
	 * A directory, named by the class asking, for files that must outlive
	 * every cleaning of the workspace; null when this game has no workspace
	 * to keep them in. Not created here: the class creates it the first time
	 * it has something to keep, so asking costs nothing and leaves nothing.
	 *
	 * <p>It is not {@link #scratch()}, and must not be: scratch is emptied by
	 * every clean, and so is every extraction directory, and what goes here
	 * is exactly what a clean must leave alone - the item editor's copy of
	 * its archive as it was before the first in-place write, which "revert to
	 * retail" reads and the deployer diffs against. A copy kept in a
	 * directory the next clean empties is no copy.
	 *
	 * <p>It is not {@link #pristine()} either. That snapshot has a lifecycle
	 * of its own: taken once when the workspace opens, stamped, never
	 * completed afterwards, discarded whole when the workspace is repointed
	 * at another game. The item baseline is taken at a different moment
	 * (the first write), under its own rule about never being retaken, and
	 * every existing workspace already holds it beside the snapshot, not
	 * inside it; moving it under the snapshot would orphan those copies, and
	 * an orphaned copy with its "already edited" mark gone is a baseline
	 * recaptured from an edited game - the contamination the mark exists to
	 * prevent. One directory per lifecycle, so neither can quietly delete
	 * the other's evidence.
	 *
	 * @param name one path segment; the implementation refuses a name that
	 * belongs to something cleaning empties
	 */
	File durable(String name);
}

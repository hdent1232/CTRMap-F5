package ctrmap.tests;

import ctrmap.formats.GameFiles;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;

/**
 * A game a suite hands to a format class: scratch space, a map of archives,
 * and a record of every file the class reported as edited.
 *
 * <p>This is what proves a migration is real. A class that is HANDED its
 * {@link GameFiles} can be handed this one instead of the open workspace,
 * with no dump on disk and no workspace open at all, and either works or
 * shows that it still reaches for the global. Building it needs nothing:
 * the default profile is ORAS's, taken from {@link GameProfile#of} rather
 * than probed from a folder, and every file lives under one
 * {@link Scratch} directory that is gone when the JVM exits.
 *
 * <p>Deliberately NOT installed anywhere. {@link Sessions} installs what it
 * builds because the code under test still reads the facade; this must not,
 * because the question it exists to answer is whether a class works WITHOUT
 * the facade. It imports nothing from the application's session holder and
 * never will - a fake that touched the global would pass a class that reads
 * the global, which is the one result it must not produce.
 *
 * <p>Set it up with the fluent methods, then read back what the class under
 * test did: {@link #edited()} is the list of files it reported writing,
 * in order, each once.
 */
final class FakeGameFiles implements GameFiles {

	private final File root;
	private final File game;
	private final File scratch;
	private final EnumMap<ArchiveType, GARC> archives = new EnumMap<>(ArchiveType.class);
	private final List<File> edited = new ArrayList<>();
	private GameProfile profile = GameProfile.of(GameType.ORAS);
	private GameProfile.Variant variant = GameProfile.Variant.RETAIL;
	private File pristine;

	/** An empty ORAS-shaped game under a fresh scratch directory. */
	FakeGameFiles() throws IOException {
		root = Scratch.dir("ctrmap_fake_game");
		game = new File(root, "game");
		scratch = new File(root, "temp");
		scratch.mkdirs();
	}

	/** The directory everything of this game lives under. */
	File root() {
		return root;
	}

	/** Another game's answers, so a class can be shown refusing what that game lacks. */
	FakeGameFiles profile(GameProfile p) {
		profile = p;
		return this;
	}

	/** Another edition of the game. */
	FakeGameFiles variant(GameProfile.Variant v) {
		variant = v;
		return this;
	}

	/** An opened archive for {@link #staged} to extract from and {@link #archive} to hand out. */
	FakeGameFiles open(ArchiveType type, GARC handle) {
		archives.put(type, handle);
		return this;
	}

	/** Stages one entry's bytes as though it had been extracted, with no archive behind it. */
	FakeGameFiles plant(ArchiveType type, int entry, byte[] bytes) throws IOException {
		File f = stagedPath(type, entry);
		f.getParentFile().mkdirs();
		Files.write(f.toPath(), bytes);
		return this;
	}

	/** Puts a file at this game's on-disk location for an archive, where {@link #archiveFile} points. */
	FakeGameFiles plantArchive(ArchiveType type, byte[] bytes) throws IOException {
		File f = archiveFile(type);
		if (f == null) {
			throw new IllegalArgumentException(profile.displayName() + " has no location for " + type);
		}
		f.getParentFile().mkdirs();
		Files.write(f.toPath(), bytes);
		return this;
	}

	/** Gives this game a pristine snapshot directory, empty; {@link #pristine} answers it from then on. */
	FakeGameFiles withPristine() {
		pristine = new File(root, "pristine");
		pristine.mkdirs();
		return this;
	}

	/** Every file the class under test reported as edited, in order, each once. */
	List<File> edited() {
		return Collections.unmodifiableList(edited);
	}

	private File stagedPath(ArchiveType type, int entry) {
		return new File(new File(root, type.name().toLowerCase()), String.valueOf(entry));
	}

	// ------------------------------------------------------------ the contract

	@Override
	public File staged(ArchiveType type, int entry) {
		if (profile.archivePath(type) == null) {
			return null;
		}
		File f = stagedPath(type, entry);
		//the real workspace creates every extraction directory when it is
		//validated, so a path handed out here must be one a container can
		//write into as well
		f.getParentFile().mkdirs();
		if (!f.exists()) {
			GARC g = archives.get(type);
			if (g != null && entry < g.length) {
				byte[] b = g.getDecompressedEntry(entry);
				if (b == null) {
					return null;
				}
				try {
					Files.write(f.toPath(), b);
				} catch (IOException ex) {
					return null;
				}
			}
		}
		return f;
	}

	@Override
	public void edited(File f) {
		File abs = f.getAbsoluteFile();
		if (!edited.contains(abs)) {
			edited.add(abs);
		}
	}

	@Override
	public File scratch() {
		return scratch;
	}

	@Override
	public GARC archive(ArchiveType type) {
		return archives.get(type);
	}

	@Override
	public File archiveFile(ArchiveType type) {
		String rel = profile.archivePath(type);
		return rel == null ? null : new File(game.getPath() + rel);
	}

	@Override
	public GameProfile profile() {
		return profile;
	}

	@Override
	public GameProfile.Variant variant() {
		return variant;
	}

	@Override
	public File pristine() {
		return pristine;
	}

	@Override
	public String toString() {
		return "FakeGameFiles[" + profile.displayName() + " under " + root + "]";
	}
}

package ctrmap.humaninterface;

import java.awt.Component;
import java.io.File;
import java.util.prefs.Preferences;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

/**
 * The user's decompressed code.bin: remembered once picked, and asked for when it is not.
 *
 * <p>ONE PLACE TO ASK (F046). The shop editor asked for it and remembered it under a name of its
 * own, the item-icon editor asked every time, and the zone-limit patch never asked at all - it
 * built its patch blind - while the shop editor's prompt told the user "It's the same file the
 * zone-limit patch uses". Every editor that patches the executable asks here, so it is.
 *
 * <p>Not: ShopEditDialog's own prompt - this IS that prompt, moved out of the one dialog that had
 * it, because the other two could not reach it there.
 */
public final class CodeBinFile {

	private static final String KEY = "CODEBIN_PATH";
	/** Where the shop editor kept it before this existed - read, so nobody is asked again for it. */
	private static final String SHOP_KEY = "SHOP_CODEBIN_PATH";

	private CodeBinFile() {
	}

	private static Preferences prefs() {
		return Preferences.userRoot().node(CodeBinFile.class.getName());
	}

	private static Preferences shopPrefs() {
		return Preferences.userRoot().node(ShopEditDialog.class.getName());
	}

	/**
	 * The preference key a code.bin is remembered under for the game in {@code gameDir} - ONE PER GAME FOLDER
	 * (census M.31). One file was remembered for every game, so a user who had edited one dump's shops and opened
	 * another workspace was handed the first dump's executable silently, and saved a code.ips diffed against it
	 * into the other game.
	 */
	public static String keyFor(String gameDir) {
		String game = gameDir == null ? "" : new File(gameDir).getAbsolutePath().toLowerCase(java.util.Locale.ROOT);
		return KEY + "." + Integer.toHexString(game.hashCode());
	}

	/**
	 * The code.bin remembered for the game in {@code gameDir}, read through {@code lookup}, if it still exists;
	 * else null. A code.bin remembered before this, for every game at once, is not read: which game it was for is
	 * unknown, and unknown is not this one.
	 */
	public static File rememberedFor(String gameDir, java.util.function.UnaryOperator<String> lookup) {
		String path = lookup.apply(keyFor(gameDir));
		return path != null && !path.isEmpty() && new File(path).isFile() ? new File(path) : null;
	}

	/** The code.bin remembered for the open game folder if it still exists, else null. */
	public static File remembered() {
		return rememberedFor(ctrmap.Workspace.GAMEDIR_PATH, key -> prefs().get(key, ""));
	}

	/**
	 * The remembered code.bin, or the one the user picks now; null when they cancel.
	 *
	 * @param why what this editor does with it, said before the file chooser opens
	 * @param near where the chooser starts when nothing is remembered - the caller's game folder
	 */
	public static File pick(Component parent, String why, File near) {
		File known = remembered();
		if (known != null) {
			return known;
		}
		ctrmap.Ui.message(parent, "Pick your DECOMPRESSED code.bin - the game's executable. " + why
				+ "\nThe shop editor, the item icons and the zone-limit patch all use this same file.",
				"code.bin", JOptionPane.INFORMATION_MESSAGE);
		return choose(parent, near);
	}

	/**
	 * Always asks - for a button whose whole meaning is "choose a file" - starting at the
	 * remembered one. Null when the user cancels.
	 */
	public static File choose(Component parent, File near) {
		File known = remembered();
		JFileChooser fc = new JFileChooser(known != null ? known.getParentFile() : near);
		if (known != null) {
			fc.setSelectedFile(known);
		}
		fc.setDialogTitle("Pick the decompressed code.bin");
		if (ctrmap.Ui.choose(parent, fc, false) == null) {
			return null;
		}
		return fc.getSelectedFile();
	}

	/** Remembers a code.bin that was read and accepted, for the open game folder. */
	public static void remember(File f) {
		prefs().put(keyFor(ctrmap.Workspace.GAMEDIR_PATH), f.getAbsolutePath());
	}

	/** Forgets a code.bin that turned out not to be one this program can patch, and the game-less ones before it. */
	public static void forget() {
		prefs().remove(keyFor(ctrmap.Workspace.GAMEDIR_PATH));
		prefs().remove(KEY);
		shopPrefs().remove(SHOP_KEY);
	}
}

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

	/** The remembered code.bin if it still exists, else null. */
	public static File remembered() {
		for (String path : new String[]{prefs().get(KEY, ""), shopPrefs().get(SHOP_KEY, "")}) {
			if (!path.isEmpty() && new File(path).isFile()) {
				return new File(path);
			}
		}
		return null;
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

	/** Remembers a code.bin that was read and accepted. */
	public static void remember(File f) {
		prefs().put(KEY, f.getAbsolutePath());
	}

	/** Forgets a code.bin that turned out not to be one this program can patch. */
	public static void forget() {
		prefs().remove(KEY);
		shopPrefs().remove(SHOP_KEY);
	}
}

package ctrmap.vault;

import ctrmap.Ui;
import java.awt.Component;
import java.io.File;
import java.util.List;
import javax.swing.JOptionPane;

/**
 * The vault as the user meets it: one question at setup, and one way back.
 *
 * <p>Everything here asks and answers through {@link Ui}, which is what makes
 * it testable at all - a modal {@code JOptionPane} is not observable from a
 * suite and cannot even run headless, and every decision below is one this
 * project has learned to guard rather than hope about. It also means the whole
 * flow can be driven by {@code Ui.record(answers...)} without a screen.
 *
 * <p>WHY THE SIZE QUESTION IS ASKED AND NOT DECIDED. A backup of a game dump is
 * between 167 MB and 1.87 GB depending on how much of it is kept, and which of
 * those is right depends on the user's disk and on whether they care about
 * damage that arrives from outside this editor. Guessing on their behalf would
 * either waste 1.7 GB or quietly fail to protect most of the game. The numbers
 * quoted are measured (see {@link Vault.Scope}), not estimated - an earlier
 * draft of this feature guessed that compression would halve the dump, and
 * measuring showed it saves an eighth, which changed which option is the
 * default.
 */
public final class VaultUi {

	private VaultUi() {
	}

	/** What the user picked, or null when they closed the dialog. */
	public static Vault.Scope askScope(Component parent, long dumpBytes) {
		String[] options = {
			label(Vault.Scope.FULL_RAW, dumpBytes),
			label(Vault.Scope.FULL_COMPRESSED, dumpBytes),
			label(Vault.Scope.MODDABLE, dumpBytes),
			"Don't back up"
		};
		Object picked = Ui.input(parent,
				"CTRMap can keep a pristine copy of this game, so a bad edit or a corrupted"
				+ "\narchive never means dumping your cartridge again."
				+ "\n\nIt is kept outside your workspace, so losing the workspace does not lose"
				+ "\nthe backup, and it can put back a single file as well as the whole game."
				+ "\n\nHow much of it should be kept?",
				"Keep a pristine copy of this game?",
				JOptionPane.QUESTION_MESSAGE, options, options[0]);
		if (picked == null) {
			return null;
		}
		String s = String.valueOf(picked);
		for (Vault.Scope sc : Vault.Scope.values()) {
			if (s.equals(label(sc, dumpBytes))) {
				return sc;
			}
		}
		return null;                       // "Don't back up", or anything unrecognised
	}

	/** The option text, with the real size this dump would take. */
	public static String label(Vault.Scope scope, long dumpBytes) {
		switch (scope) {
			case FULL_RAW:
				return "Everything (" + gb(dumpBytes) + ") - recommended";
			case FULL_COMPRESSED:
				//0.862 measured on a retail ORAS dump; see Vault.Scope
				return "Everything, compressed (about " + gb((long) (dumpBytes * 0.862)) + ")";
			case MODDABLE:
				return "Only what CTRMap can change (about " + gb(moddableGuess(dumpBytes)) + ")";
			default:
				return scope.name();
		}
	}

	//measured: 167 MB of a 1.87 GB ORAS dump. Scaled rather than hardcoded so
	//another game does not get quoted ORAS's number, but it is still an
	//approximation and the label says "about".
	private static long moddableGuess(long dumpBytes) {
		return Math.max(1, (long) (dumpBytes * 0.089));
	}

	static String gb(long bytes) {
		if (bytes >= 1000L * 1000 * 1000) {
			return String.format("%.2f GB", bytes / 1e9);
		}
		return String.format("%d MB", Math.max(1, bytes / (1000 * 1000)));
	}

	/**
	 * Offers to vault a dump and does it, reporting either way.
	 *
	 * <p>Never throws and never blocks setup: a workspace whose backup failed is
	 * still a usable workspace, and refusing to continue would be a worse
	 * outcome than continuing without a backup - as long as the user is TOLD,
	 * which is the part that has historically been missed here.
	 */
	public static Vault.SealResult offer(Component parent, File dumpRoot, long dumpBytes) {
		File entry = Vault.entryFor(dumpRoot);
		if (Vault.isSealed(entry)) {
			VaultManifest existing = VaultManifest.read(entry);
			String why = existing == null ? "The backup there cannot be read." : Vault.mismatch(existing, dumpRoot);
			if (why.isEmpty()) {
				return null;               // this dump's backup is there; say nothing, ask nothing
			}
			//SAID, NOT SKIPPED (F121): silence here told the second of a version pair it was backed up
			Ui.error(parent, "This game folder has NOT been backed up.\n\nThe vault holds a backup under"
					+ " the same name, and it may not be this dump's:\n" + why, "Backup not kept");
			return null;
		}
		if (Vault.isInterrupted(entry)) {
			Ui.message(parent,
					"A previous backup of this game was interrupted and is not usable."
					+ "\n\nIt will be replaced by a fresh one.",
					"Unfinished backup found", JOptionPane.WARNING_MESSAGE);
		}
		Vault.Scope scope = askScope(parent, dumpBytes);
		if (scope == null) {
			return null;                   // they said no, which is their business
		}
		Vault.SealResult r = Vault.seal(dumpRoot, scope, null);
		if (r.sealed) {
			String caveat = r.manifest != null && !r.manifest.verifiedVanilla
					? "\n\nNote: this dump did not pass the dump check, so the copy is kept but is"
					+ "\nNOT marked as an unmodified game. It is labelled that way wherever it appears."
					: "";
			Ui.message(parent,
					"A pristine copy of " + (r.manifest != null ? r.manifest.displayName : "this game")
					+ " has been kept.\n\n" + r.filesWritten + " file(s), " + gb(r.bytesWritten)
					+ "\nIn: " + r.entry
					+ "\n\nYou can restore the whole game, or a single archive, from Workspace settings."
					+ caveat,
					"Backup kept", JOptionPane.INFORMATION_MESSAGE);
		} else {
			Ui.error(parent,
					"The pristine copy could NOT be kept.\n\n" + r.problem
					+ "\n\nYou can carry on working - but if this game folder is damaged later,"
					+ "\nyou will need to dump it from your console again.",
					"Backup not kept");
		}
		return r;
	}

	/**
	 * Puts the whole game back, after saying plainly what that costs.
	 *
	 * <p>Restoring everything discards every edit made to the dump since it was
	 * sealed, which is exactly why {@link Vault#restoreOne} exists and why this
	 * says so before doing anything. Returns true when the game was restored.
	 *
	 * @param open the session open now, or null; when it is over {@code targetRoot}, its archives
	 * are re-read after the restore (F218)
	 */
	public static boolean restoreEverything(Component parent, File entry, File targetRoot,
			ctrmap.WorkspaceSession open) {
		VaultManifest man = read(entry);
		if (man == null) {
			Ui.error(parent, Vault.isInterrupted(entry)
					? "The backup for this game was never finished, so it cannot be restored from."
					: "There is no backup of this game to restore from.",
					"Nothing to restore");
			return false;
		}
		if (saidDamaged(parent, entry)) {
			return false;
		}
		int r = Ui.confirm(parent,
				"This puts every file back as it was when the backup was taken:"
				+ "\n  " + man.displayName + ", " + man.entries.size() + " file(s)"
				+ "\n\nEVERY CHANGE you have made to the game folder since then will be lost."
				+ "\nEdits kept in your workspace are not affected."
				+ "\n\nIf only one archive is damaged, restore just that one instead - it keeps"
				+ "\nthe rest of your work."
				+ "\n\nRestore the whole game?",
				"Restore the whole game?", JOptionPane.YES_NO_OPTION);
		//CLOSED_OPTION is what a headless run and a dismissed dialog both give,
		//and it must mean "do nothing" - never consent to overwriting a game
		if (r != JOptionPane.YES_OPTION) {
			return false;
		}
		List<String> bad = Vault.restoreAll(entry, targetRoot, null);
		rewritten(open, targetRoot);   //whole or part, files were put back under the open game
		if (bad.isEmpty()) {
			Ui.message(parent, "The game folder has been put back as it was when the backup was taken.",
					"Restored", JOptionPane.INFORMATION_MESSAGE);
			return true;
		}
		Ui.error(parent, "The restore did not finish. " + bad.size() + " file(s) could not be put back:\n\n"
				+ join(bad, 8) + "\n\nThe game folder is now part old and part new, which is worse than"
				+ "\neither - do not play it until this is sorted out.",
				"Restore incomplete");
		return false;
	}

	/** Puts one file back, reporting either way. Returns true when it landed. */
	public static boolean restoreOne(Component parent, File entry, String relPath, File targetRoot,
			ctrmap.WorkspaceSession open) {
		relPath = Vault.key(relPath);   //said as the backup keys it, whatever was typed (F155)
		if (saidDamaged(parent, entry)) {
			return false;
		}
		String why = Vault.restoreOne(entry, relPath, targetRoot);
		if (why.isEmpty()) {
			rewritten(open, targetRoot);
			Ui.message(parent, relPath + " has been put back as it was when the backup was taken."
					+ "\n\nNothing else in the game folder was touched.",
					"Restored " + relPath, JOptionPane.INFORMATION_MESSAGE);
			return true;
		}
		Ui.error(parent, why, "Could not restore " + relPath);
		return false;
	}

	/**
	 * Files were put back into {@code targetRoot}: when that is the open game's folder, the game's
	 * archive handles are re-read and everything kept from them goes (F218). A restore wrote the game
	 * folder under the open session and passed no reload, so the session kept reading the old entry
	 * tables over the restored files, and every answer it had kept from them.
	 */
	static void rewritten(ctrmap.WorkspaceSession open, File targetRoot) {
		if (open != null && open.gameDir() != null
				&& open.gameDir().getAbsoluteFile().equals(targetRoot.getAbsoluteFile())) {
			open.archivesRewritten();
		}
	}

	/**
	 * Checks the backup against its own record before anything is restored from it, and says so when
	 * it has changed (F140): rot was found only by the restore, after it had written. The cheap
	 * check - sizes, and digests where those disagree - so a gigabyte is not read to ask.
	 *
	 * @return true when it was damaged and the user was told; nothing is restored then
	 */
	static boolean saidDamaged(Component parent, File entry) {
		List<Vault.Drift> drift = Vault.verify(entry, false);
		if (drift.isEmpty()) {
			return false;
		}
		Ui.error(parent, "The backup has changed since it was taken, so nothing was restored from it:\n\n"
				+ join(drift, 8) + "\n\nRe-run the setup wizard against an unmodified dump to take a fresh one.",
				"Backup damaged");
		return true;
	}

	/**
	 * Checks this game's backup when its workspace opens, and says when it has changed (F140) -
	 * found then, rather than at the moment of need, as the vault plan asked. A backup that is not
	 * this dump's (F121) is left alone: it is checked when it is someone's.
	 *
	 * @return what changed; empty when nothing did, or there is no backup of this dump
	 */
	public static List<Vault.Drift> checkOnOpen(Component parent, File gameDir) {
		File entry = Vault.entryFor(gameDir);
		VaultManifest man = Vault.isSealed(entry) ? VaultManifest.read(entry) : null;
		if (man == null || !Vault.mismatch(man, gameDir).isEmpty()) {
			return java.util.Collections.emptyList();
		}
		List<Vault.Drift> drift = Vault.verify(entry, false);
		if (!drift.isEmpty()) {
			Ui.message(parent, "The pristine backup of this game has changed since it was taken:\n\n" + join(drift, 8)
					+ "\n\nIt cannot be trusted to restore from. Re-run the setup wizard against an unmodified"
					+ " dump to take a fresh one.", "Backup changed", JOptionPane.WARNING_MESSAGE);
		}
		return drift;
	}

	static VaultManifest read(File entry) {
		for (VaultManifest m : Vault.list()) {
			if (m.entryDir != null && m.entryDir.equals(entry)) {
				return m;
			}
		}
		return null;
	}

	static String join(List<?> xs, int max) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < xs.size() && i < max; i++) {
			sb.append("  ").append(xs.get(i)).append('\n');
		}
		if (xs.size() > max) {
			sb.append("  ...and ").append(xs.size() - max).append(" more\n");
		}
		return sb.toString();
	}
}

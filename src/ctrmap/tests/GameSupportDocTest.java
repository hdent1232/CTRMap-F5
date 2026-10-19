package ctrmap.tests;

import ctrmap.gamedef.GameProfile;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the docs say works on each game is what the game profiles switch on (F150).
 *
 * <p>WHY THIS SUITE EXISTS. The README and the quickstart advertised X and Y beside Omega Ruby and
 * Alpha Sapphire, while the X/Y profile switches on one feature - map viewing and geometry editing
 * - and refuses every other, its text indices and most archive paths unmeasured. So the README holds
 * a table of what works on which game, written from {@link GameProfile#supports} and checked here
 * against it, and a doc line that names a game the profiles only partly support has to say so.
 *
 * <p>ORDER: needs no dump. {@code --write} rewrites the README's table from the profiles - the one
 * way it is meant to change - and then checks it like any other run.
 *
 * Usage: java ctrmap.tests.GameSupportDocTest [--write]
 */
public class GameSupportDocTest {

	static int fails = 0;

	static final String OPEN = "<!-- support-table: written from GameProfile.supports by GameSupportDocTest - do not edit by hand -->";
	static final String CLOSE = "<!-- /support-table -->";

	/** The table, from every game's profile and every feature's label. */
	static String table() {
		StringBuilder sb = new StringBuilder("| |");
		for (GameType g : GameType.values()) {
			sb.append(' ').append(GameProfile.of(g).displayName()).append(" |");
		}
		sb.append("\n|---|");
		for (int i = 0; i < GameType.values().length; i++) {
			sb.append("---|");
		}
		sb.append('\n');
		for (GameProfile.Feature f : GameProfile.Feature.values()) {
			sb.append("| ").append(f.label()).append(" |");
			for (GameType g : GameType.values()) {
				sb.append(GameProfile.of(g).supports(f) ? " yes |" : " no |");
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	/** The ways a doc names a game by its display name: "X / Y" is also "X/Y" and "X and Y". */
	static List<String> spellings(GameProfile p) {
		String n = p.displayName();
		return Arrays.asList(n, n.replace(" / ", "/"), n.replace(" / ", " and "));
	}

	/** True when a game supports every feature: naming it needs no qualifying. */
	static boolean everything(GameProfile p) {
		for (GameProfile.Feature f : GameProfile.Feature.values()) {
			if (!p.supports(f)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Headline lines - a heading, or a line that opens in bold - that name a partly supported game
	 * without saying it is partly supported, by file:line. Headlines are where a doc advertises (the
	 * README's bold first line and the quickstart's title were the two); in running text "X/Y" is as
	 * often a pair of coordinates.
	 */
	static List<String> unqualified(String name, String text) {
		List<String> out = new ArrayList<>();
		String[] lines = text.split("\n", -1);
		boolean inTable = false;
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (line.contains(OPEN)) {
				inTable = true;
			}
			if (line.contains(CLOSE)) {
				inTable = false;
				continue;
			}
			if (inTable || !(line.startsWith("#") || line.startsWith("**"))) {
				continue;
			}
			//a claim may run over two lines, the second saying "only"
			String around = line + (i + 1 < lines.length ? " " + lines[i + 1] : "");
			for (GameType g : GameType.values()) {
				GameProfile p = GameProfile.of(g);
				if (everything(p)) {
					continue;
				}
				for (String s : spellings(p)) {
					if (line.contains(s) && !around.contains("only") && !around.contains("refuse") && !around.contains("not supported")) {
						out.add(name + ":" + (i + 1) + " names " + p.displayName());
						break;
					}
				}
			}
		}
		return out;
	}

	/**
	 * A doc's text as this suite reads it. LINE ENDINGS ARE NOT CONTENT: git's autocrlf hands a file to a Windows
	 * checkout with CRLF, and read byte for byte the README's table never equalled the one the profiles give -
	 * measured 2026-10-09, red on a README whose table lines each carried a carriage return and read identically.
	 */
	static String read(File doc) throws java.io.IOException {
		return new String(Files.readAllBytes(doc.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
	}

	public static void main(String[] args) throws Exception {
		File readme = new File("README.md");
		String text = read(readme);
		int open = text.indexOf(OPEN);
		int close = text.indexOf(CLOSE);
		if (args.length > 0 && args[0].equals("--write") && open >= 0 && close > open) {
			text = text.substring(0, open + OPEN.length()) + "\n" + table() + text.substring(close);
			Files.write(readme.toPath(), text.getBytes(StandardCharsets.UTF_8));
			System.out.println("  wrote the table into " + readme.getAbsolutePath());
			open = text.indexOf(OPEN);
			close = text.indexOf(CLOSE);
		}

		System.out.println("--- the README's table is what the profiles switch on (F150)");
		check(GameProfile.of(GameType.ORAS).supports(GameProfile.Feature.AREA_FORK)
				&& !GameProfile.of(GameType.XY).supports(GameProfile.Feature.AREA_FORK) && table().contains(" yes | no |"),
				"control: the table tells a game that has a feature from one that does not");
		File crlf = new File(Scratch.dir("ctrmap_doc_crlf"), "README.md");
		Files.write(crlf.toPath(), text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
		check(read(crlf).equals(text),
				"control: a checkout that ends the README's lines with CRLF reads as the same text");
		String shown = open >= 0 && close > open ? text.substring(open + OPEN.length(), close).trim() : null;
		check(shown != null && shown.equals(table().trim()), "the README holds the table the profiles give"
				+ (shown == null ? " - it has no table at all" : shown.equals(table().trim()) ? ""
				: "; run with --write to write it from them. Wanted:\n" + table()));

		System.out.println("--- a doc that names a partly supported game says so (F150)");
		check(unqualified("old", "**A world editor for the 3DS games - Omega Ruby, Alpha Sapphire, X and Y.**\n\nmore\n").size() == 1
				&& unqualified("new", "Omega Ruby and Alpha Sapphire, with X and Y for map viewing only.\n").isEmpty(),
				"control: the README's old claim is seen, and a qualified one is not");
		List<String> found = new ArrayList<>();
		for (String doc : new String[]{"README.md", "QUICKSTART.md"}) {
			found.addAll(unqualified(doc, read(new File(doc))));
		}
		check(found.isEmpty(), "README and QUICKSTART name no partly supported game without saying it: " + found);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}

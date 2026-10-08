package ctrmap.tests;

import ctrmap.formats.containers.ZO;
import ctrmap.formats.scripts.NpcTemplates;
import ctrmap.formats.scripts.ZoneScriptEdit;
import ctrmap.formats.zone.Zone;
import ctrmap.gamedef.GameProfile;
import ctrmap.gamedef.GameType;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scripted-NPC templates write only into a game they were measured on, and the limits they
 * check are the profile's one answer (F085).
 *
 * <p>WHY THIS SUITE EXISTS. Talkers, signs, item and Battle Points givers and battle challenges
 * reproduce call sites, argument layouts and native hashes measured on Omega Ruby / Alpha
 * Sapphire's zone scripts, and their own class said "callers should gate these templates to ORAS
 * workspaces". No caller did, and the game profile had no feature to ask. The trainer bound was
 * written as 949 in four places and the item bound as 775 in the templates, beside a profile that
 * is where such numbers live.
 *
 * <p>ORDER: needs no dump. The zone is HandedGameTest's minimal one, written to a scratch file.
 *
 * Usage: java ctrmap.tests.NpcScriptGateTest [src root]
 */
public class NpcScriptGateTest {

	static int fails = 0;

	/** The answer the suites hand the templates: Omega Ruby / Alpha Sapphire, the game they were measured on. */
	public static final NpcTemplates.Measured ORAS = NpcTemplates.measuredOn(GameType.ORAS);

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		theTemplatesWriteOnlyWhereTheyWereMeasured();
		everyTemplateWriterIsHandedTheAnswer(root);
		theLimitsAreTheProfilesOneAnswer(root);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static Zone zone(String name, GameType game) throws Exception {
		File f = new File(Scratch.dir("ctrmap_npcgate_" + name), "0");
		Files.write(f.toPath(), HandedGameTest.zoneBytes());
		return new Zone(new ZO(f, new ContainerWriteSafetyTest.Files2()), game);
	}

	/** Applies an edit whose surgery only says that it ran; answers what was refused, or "applied". */
	static String apply(Zone z, boolean[] ran) {
		try {
			new ZoneScriptEdit(z, "the talker script").apply((measured, work, line) -> {
				ran[0] = true;
				return 7;
			}, (id, msg) -> {
			});
			return "applied";
		} catch (ZoneScriptEdit.Refused refused) {
			return refused.reason();
		}
	}

	static void theTemplatesWriteOnlyWhereTheyWereMeasured() throws Exception {
		System.out.println("--- a scripted-NPC template writes only into a game it was measured on (F085)");
		boolean[] ran = {false};
		Zone oras = zone("oras", GameType.ORAS);
		check(apply(oras, ran).equals("applied") && ran[0],
				"control: on Omega Ruby / Alpha Sapphire the edit runs its surgery and lands");
		ran[0] = false;
		Zone xy = zone("xy", GameType.XY);
		byte[] before = xy.s.getScriptBytes();
		String said = apply(xy, ran);
		check(said.contains("measured on") && said.contains(GameProfile.of(GameType.XY).displayName()) && !ran[0]
				&& Arrays.equals(before, xy.s.getScriptBytes()),
				"on X/Y it is refused in words, before any surgery, the script untouched: " + said);
		ran[0] = false;
		Zone none = zone("none", null);
		said = apply(none, ran);
		check(said.contains("no game") && !ran[0], "and a zone read for no game is refused, not guessed at: " + said);
		List<String> wrong = new ArrayList<>();
		for (GameType g : GameType.values()) {
			if (GameProfile.of(g).supports(GameProfile.Feature.NPC_SCRIPTS) != (g == GameType.ORAS)) {
				wrong.add(g.toString());
			}
		}
		check(wrong.isEmpty(), "and only the game the templates were measured on says it supports them" + (wrong.isEmpty() ? "" : " - not " + wrong));
	}

	static final String PKG = "ctrmap/formats/scripts/";
	static final String MEASURED = "L" + PKG + "NpcTemplates$Measured;";

	/**
	 * A template that writes - reaches the script's re-layout or builds an entity record - must be
	 * handed the answer and ask it, or ask the question itself. Read from the compiled classes that
	 * are running, so a writer added tomorrow is in scope without anyone listing it.
	 */
	static void everyTemplateWriterIsHandedTheAnswer(File root) throws Exception {
		System.out.println("--- every public template writer takes the measured answer and asks it (F085)");
		check(fails(() -> NpcTemplates.measuredOn(GameType.XY)).contains(GameProfile.of(GameType.XY).displayName())
				&& fails(() -> NpcTemplates.measuredOn(null)).contains("no game"),
				"the gate refuses X/Y and no game in words");
		check(fails(() -> NpcTemplates.makeTrainerNpc(null, 0, 7, 0, 0, 0, 0, 0)).contains("without asking"),
				"control: a template handed no answer refuses rather than writing");
		File classes = new File(NpcTemplates.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		File dir = new File(classes, PKG);
		if (!dir.isDirectory()) {
			check(false, "the running classes are a directory the package can be read from - " + dir + " is not;"
					+ " a template writer cannot be looked for there, which is not the same as there being none");
			return;
		}
		java.util.Map<String, Bytecode.Cls> all = Bytecode.read(dir);
		//the read is the tree's: every source file of the package has its class among those read
		List<String> unread = new ArrayList<>();
		File[] sources = new File(root, PKG).listFiles((d, n) -> n.endsWith(".java"));
		for (File f : sources == null ? new File[0] : sources) {
			String cls = PKG + f.getName().substring(0, f.getName().length() - 5);
			if (!all.containsKey(cls)) {
				unread.add(cls);
			}
		}
		check(sources != null && sources.length > 10 && unread.isEmpty(), "the classes read are the package's "
				+ (sources == null ? "?" : sources.length) + " sources" + (unread.isEmpty() ? "" : " - not " + unread));
		List<String> writers = new ArrayList<>();
		List<String> ungated = new ArrayList<>();
		for (Bytecode.Cls c : all.values()) {
			if (c.name.equals(PKG + "GFLPawnScript")) {
				continue; //the format itself: its own serializer re-lays itself out
			}
			for (Bytecode.Method m : c.methods.values()) {
				boolean visible = (m.access & 0x0001) != 0 && (m.access & 0x1040) == 0;
				if (!visible || !writes(m, all)) {
					continue;
				}
				String name = c.name.substring(PKG.length()) + "." + m.name;
				writers.add(name);
				boolean handed = m.desc.substring(0, m.desc.indexOf(')')).contains(MEASURED);
				boolean asks = calls(m, "asked") || calls(m, "measuredOn");
				boolean gate = calls(m, "measuredOn");
				if (!(handed && asks) && !gate) {
					ungated.add(name + (handed ? " (handed the answer, never asks it)" : " (not handed the answer)"));
				}
			}
		}
		List<String> known = Arrays.asList("TalkerScriptWizard.installCase", "NpcTemplates.makeTrainerNpc",
				"NpcTemplates.makeSignFurniture", "MsgWrapperInjector.injectMsgWrapper", "ZoneScriptEdit.apply");
		check(writers.containsAll(known) && !writers.contains("NpcTemplates.findSignPattern"),
				"control: the walk finds the writers it must (" + known + ") and not a reader - found " + writers.size()
				+ ": " + writers);
		check(ungated.isEmpty(), "every one of the " + writers.size() + " is handed the answer and asks it, or asks"
				+ " the question itself" + (ungated.isEmpty() ? "" : " - not " + ungated));
		//the question is about the zone's game: asked with a game named in the code, the answer is
		//yes for every zone, which is the gate removed while still standing
		java.util.Map<String, Bytecode.Cls> tree = Bytecode.read(new File(classes, "ctrmap"));
		List<String> askers = new ArrayList<>();
		List<String> named = new ArrayList<>();
		for (Bytecode.Cls c : tree.values()) {
			if (c.name.startsWith("ctrmap/tests/")) {
				continue;
			}
			for (Bytecode.Method m : c.methods.values()) {
				if (!calls(m, "measuredOn")) {
					continue;
				}
				askers.add(c.name.substring(c.name.lastIndexOf('/') + 1) + "." + m.name);
				for (Bytecode.Insn i : m.code) {
					if (i.op == Bytecode.GETSTATIC && i.owner.equals("ctrmap/gamedef/GameType")) {
						named.add(c.name.substring(c.name.lastIndexOf('/') + 1) + "." + m.name + " names " + i.name);
					}
				}
			}
		}
		check(askers.contains("ZoneScriptEdit.apply") && askers.contains("TilePainterForm.wireSigns")
				&& askers.contains("NPCEditForm.addTrainer"),
				"control: the production askers are found - " + askers.size() + ": " + askers);
		check(named.isEmpty(), "and every one asks about the zone's game, never a game named in the code"
				+ (named.isEmpty() ? "" : " - not " + named));
	}

	/** True when m, or anything in the package it calls or makes a lambda of, re-lays a script out or builds an entity record. */
	static boolean writes(Bytecode.Method m, java.util.Map<String, Bytecode.Cls> all) {
		java.util.Set<Bytecode.Method> seen = new java.util.HashSet<>();
		java.util.ArrayDeque<Bytecode.Method> todo = new java.util.ArrayDeque<>();
		todo.add(m);
		while (!todo.isEmpty()) {
			Bytecode.Method at = todo.poll();
			if (!seen.add(at)) {
				continue;
			}
			for (Bytecode.Insn i : at.code) {
				if (i.op == Bytecode.NEW && (i.owner.equals("ctrmap/formats/zone/ZoneEntities$NPC")
						|| i.owner.equals("ctrmap/formats/zone/ZoneEntities$Prop"))) {
					return true;
				}
				if (i.isInvoke() && i.owner.equals(PKG + "GFLPawnScript") && i.name.equals("updateRaw")) {
					return true;
				}
				String[] target = i.isInvoke() ? new String[]{i.owner, i.name, i.desc} : i.lambda;
				Bytecode.Cls owner = target == null ? null : all.get(target[0]);
				Bytecode.Method callee = owner == null ? null : owner.methods.get(target[1] + target[2]);
				if (callee != null && !owner.name.equals(PKG + "GFLPawnScript")) {
					todo.add(callee);
				}
			}
		}
		return false;
	}

	/** True when m calls NpcTemplates.{name} itself. */
	static boolean calls(Bytecode.Method m, String name) {
		for (Bytecode.Insn i : m.code) {
			if (i.isInvoke() && i.owner.equals(PKG + "NpcTemplates") && i.name.equals(name)) {
				return true;
			}
		}
		return false;
	}

	/** The message a call refuses with; "" when it did not refuse. */
	static String fails(Runnable r) {
		try {
			r.run();
			return "";
		} catch (RuntimeException ex) {
			return String.valueOf(ex.getMessage());
		}
	}

	/** A trainer or item bound written as a number in a template, form or wizard. */
	static final Pattern SPELLED = Pattern.compile("\\b(949|775)\\b");

	static void theLimitsAreTheProfilesOneAnswer(File root) throws Exception {
		System.out.println("--- the trainer and item bounds are the profile's one answer (F085)");
		GameProfile oras = GameProfile.of(GameType.ORAS);
		check(NpcTemplates.TRAINER_ID_MAX == oras.trainerIdMax() && NpcTemplates.ITEM_ID_MAX == oras.itemIdMax()
				&& oras.trainerIdMax() > 0 && oras.itemIdMax() > 0,
				"the templates' bounds are the ORAS profile's (" + NpcTemplates.TRAINER_ID_MAX + ", " + NpcTemplates.ITEM_ID_MAX + ")");
		List<String> spelled = new ArrayList<>();
		int read = 0;
		for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			String p = f.getPath().replace('\\', '/');
			if (p.contains("/ctrmap/tests/") || p.contains("/ctrmap/gamedef/")) {
				continue;
			}
			read++;
			String code = SourceSeamTest.stripComments(DialogSeamTest.read(f));
			Matcher m = SPELLED.matcher(code);
			while (m.find()) {
				spelled.add(f.getName() + ": " + m.group());
			}
		}
		check(read > 200 && spelled.isEmpty(), "no production source outside the profiles spells either number (" + read
				+ " read)" + (spelled.isEmpty() ? "" : " - " + spelled));
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

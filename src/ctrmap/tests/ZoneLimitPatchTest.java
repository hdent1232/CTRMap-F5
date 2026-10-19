package ctrmap.tests;

import ctrmap.formats.codepatch.ZoneLimitPatch;
import ctrmap.formats.codepatch.ZoneLimitPatch.Patch;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * Validates the auto-scaling zone-limit patch: the master-index math, the
 * generated patch values/IPS, and - if a decompressed ORAS code.bin is present -
 * that every reverse-engineered stock word matches the real executable and the
 * patched bytes are exactly what we expect.
 *
 * Run: java -cp "build/classes;..." ctrmap.tests.ZoneLimitPatchTest [code.bin]
 */
public class ZoneLimitPatchTest {

	private static final String DEFAULT_CODE = "../code.bin";
	private static int fails = 0;

	public static void main(String[] args) throws Exception {
		// 1. master-index rounding (next multiple of 4 >= 536+N)
		checkEq(540, ZoneLimitPatch.masterIndex(1), "N=1 -> 540");
		checkEq(540, ZoneLimitPatch.masterIndex(2), "N=2 -> 540");
		checkEq(540, ZoneLimitPatch.masterIndex(4), "N=4 -> 540");
		checkEq(544, ZoneLimitPatch.masterIndex(5), "N=5 -> 544");
		checkEq(544, ZoneLimitPatch.masterIndex(8), "N=8 -> 544");
		checkEq(548, ZoneLimitPatch.masterIndex(9), "N=9 -> 548");
		checkEq(3, ZoneLimitPatch.spareZones(1), "N=1 spares=3");
		checkEq(0, ZoneLimitPatch.spareZones(4), "N=4 spares=0");
		checkEq(0, ZoneLimitPatch.spareZones(8), "N=8 spares=0");

		// 1b. growing a game a previous append grew (F039): counted from the stock base, the
		// previous append's spares included, so a second append is the same patch for a larger N
		checkEq(540, ZoneLimitPatch.masterIndexFrom(536, 1), "from stock, +1 -> 540");
		checkEq(544, ZoneLimitPatch.masterIndexFrom(540, 1), "from 540 (an append before), +1 -> 544");
		checkEq(544, ZoneLimitPatch.masterIndexFrom(540, 4), "from 540, +4 -> 544");
		checkEq(552, ZoneLimitPatch.masterIndexFrom(544, 5), "from 544, +5 -> 552");
		int grown = ZoneLimitPatch.masterIndexFrom(540, 1);
		checkEq(grown, ZoneLimitPatch.masterIndex(grown - ZoneLimitPatch.BASE_ZONES),
				"the patch for N = M - 536 names that same master index");
		String fewer = null;
		try {
			ZoneLimitPatch.masterIndexFrom(535, 1);
		} catch (IllegalArgumentException refused) {
			fewer = refused.getMessage();
		}
		check(fewer != null && fewer.contains("fewer than the stock"),
				"a game with fewer than the stock zones is refused, in words: " + fewer);
		String past = null;
		try {
			ZoneLimitPatch.masterIndexFrom(ZoneLimitPatch.BASE_ZONES + ZoneLimitPatch.MAX_NEW_ZONES - 4, 5);
		} catch (IllegalArgumentException refused) {
			past = refused.getMessage();
		}
		check(past != null && past.contains("at most"), "and a total past the limit is refused: " + past);

		// 2. patch values for N=1 (M=540): master idx 540, size 540*0x38=0x7620, EN 541, bound 540
		List<Patch> p = ZoneLimitPatch.patches(1);
		checkEq(5, p.size(), "5 patch sites");
		checkBytes(p.get(0), 0x012bc0, new byte[]{(byte) 0x87, 0x1F, (byte) 0xA0, (byte) 0xE3}, "master idx -> mov r1,#0x21C(540)");
		checkBytes(p.get(1), 0x2d9774, new byte[]{(byte) 0x87, 0x0F, 0x55, (byte) 0xE3}, "bound A -> cmp r5,#0x21C");
		checkBytes(p.get(2), 0x2d99f8, new byte[]{(byte) 0x87, 0x0F, 0x56, (byte) 0xE3}, "bound B -> cmp r6,#0x21C");
		checkBytes(p.get(3), 0x012c0c, new byte[]{0x20, 0x76, 0x00, 0x00}, "master size -> 0x7620 (540*0x38)");
		checkBytes(p.get(4), 0x00eae0, new byte[]{0x1D, 0x02, 0x00, 0x00}, "EN idx -> 541");

		// 3. IPS well-formed: 'PATCH' + 5*(3+2+4) + 'EOF' - built against an executable holding
		// the stock words, which is the only kind it may be built against now (F046)
		byte[] stock = stockCode();
		byte[] ips = ZoneLimitPatch.buildIPS(1, stock);
		check("PATCH".equals(new String(ips, 0, 5, "ASCII")), "IPS starts with PATCH");
		check("EOF".equals(new String(ips, ips.length - 3, 3, "ASCII")), "IPS ends with EOF");
		checkEq(5 + 5 * 9 + 3, ips.length, "IPS length = 53");

		// 3b. any other build is refused, naming the site - never patched blind (F046). One site
		// differing is what another build's executable looks like from here.
		final byte[] other = stock.clone();
		other[0x2d9774] ^= 0x01;
		String refused = refusal(() -> ZoneLimitPatch.buildIPS(1, other));
		check(refused != null && refused.contains("0x2D9774") && refused.contains("Alpha Sapphire"),
				"a code.bin that differs at one site is refused, naming the site and the edition: " + refused);
		check(refusal(() -> ZoneLimitPatch.applyToCode(1, other)) != null, "and applyToCode refuses it too");
		String tooShort = refusal(() -> ZoneLimitPatch.buildIPS(1, new byte[0x1000]));
		check(tooShort != null && tooShort.contains("too short"),
				"a file too short to hold the sites is refused, not read past its end: " + tooShort);

		// 3c. the dialog that hands the patch out verifies the user's executable BEFORE it stages
		// a single zone, and builds the patch from that same file
		File panel = new File("src/ctrmap/humaninterface/ZoneLoadingPanel.java");
		if (!panel.isFile()) {
			fail("the zone dialog's source is there to read: " + panel.getAbsolutePath());
		} else {
			String body = SourceSeamTest.stripComments(new String(Files.readAllBytes(panel.toPath()),
					java.nio.charset.StandardCharsets.UTF_8));
			int verified = body.indexOf("ZoneLimitPatch.verify(code)");
			int appended = body.indexOf("ZoneAppender.appendZones(realZones");
			int built = body.indexOf("ZoneLimitPatch.buildIPS(res.patchZones, code)");
			check(verified >= 0 && appended > verified && built > appended,
					"the dialog verifies code.bin, then appends, then builds the patch from it ("
					+ verified + ", " + appended + ", " + built + ")");
			//census M.46: the title the patch installs under is ZoneLimitPatch's one constant, never a copy of it
			check(!body.contains(ZoneLimitPatch.TITLE_ID) && body.contains("ZoneLimitPatch.TITLE_ID"),
					"the Add zones instruction names the patch's title through ZoneLimitPatch.TITLE_ID, not a copy");
			int saidWhere = body.split("MAKE_IT_AGAIN", -1).length - 1;
			check(saidWhere >= 3 && !body.contains("regenerate it before testing"),
					"an append whose patch was not saved says where to make it again, both ways it can happen ("
					+ saidWhere + " mentions)");
		}

		// 3d. MADE AGAIN for a game that already has its zones (census H.2): Add zones was the only producer and
		// runs once per stock base, so a patch lost to a cancelled save or a failed write could never be made again
		int slots = ZoneLimitPatch.masterIndex(1);
		check(Arrays.equals(ZoneLimitPatch.forZoneCount(slots, stock),
				ZoneLimitPatch.buildIPS(slots - ZoneLimitPatch.BASE_ZONES, stock)),
				"the patch made again for a game holding " + slots + " slots is the one Add zones saved for it");
		String none = refusal(() -> ZoneLimitPatch.forZoneCount(ZoneLimitPatch.BASE_ZONES, stock));
		check(none != null && none.contains("no added zones"), "a stock game is refused, saying it needs none: " + none);
		String odd = refusal(() -> ZoneLimitPatch.forZoneCount(ZoneLimitPatch.BASE_ZONES + 1, stock));
		check(odd != null && odd.contains("no append leaves"), "a count no append leaves is refused: " + odd);
		File frame = new File("src/ctrmap/CtrmapMainframe.java");
		String deploy = frame.isFile() ? CollisionReadTest.body(SourceSeamTest.stripComments(new String(
				Files.readAllBytes(frame.toPath()), java.nio.charset.StandardCharsets.UTF_8)), "private void deployModAction()") : null;
		check(deploy != null && deploy.contains("mZonePnl.makeZoneLimitPatch()"),
				"and the deploy dialog, which asks for the patch, makes it for the zones the game holds");

		// 3e. SAVED WHERE THE USER PICKS, AND NOWHERE ON A CANCEL (the 2026-10-15 shared sweep, ZoneLoadingPanel 1543,
		// negate-if, SURVIVED: no suite answered the save's chooser). Inverted, a file picked saved nothing and a cancel
		// wrote code.ips into whatever folder the program runs in. The save is asked of a panel made without its
		// constructor - it reads nothing of the panel but the parent it hands the chooser - with a patch of no records.
		aZoneLimitPatchIsSavedWhereThePickedAndNowhereOnCancel();
		aZoneLimitPatchIsMadeOnlyWithAWorkspace();

		// 4. against the real code.bin: stock words must all match (verifier throws otherwise)
		File codeFile = new File(args.length > 0 ? args[0] : DEFAULT_CODE);
		if (codeFile.exists()) {
			byte[] code = Files.readAllBytes(codeFile.toPath());
			try {
				byte[] patched = ZoneLimitPatch.applyToCode(1, code);
				check(true, "applyToCode(N=1) verified all 5 stock words against real code.bin");
				// spot-check the patched words landed correctly
				checkEq(0x87, patched[0x012bc0] & 0xFF, "patched master-idx low byte = 0x87");
				checkEq(0x76, patched[0x012c0d] & 0xFF, "patched master-size high byte = 0x76");
				checkEq(0x1D, patched[0x00eae0] & 0xFF, "patched EN-idx low byte = 0x1D");
				// surgical: every changed byte lies inside one of the five 4-byte windows
				int diff = 0;
				for (int i = 0; i < code.length; i++) {
					if (code[i] != patched[i]) {
						diff++;
						boolean inWindow = false;
						for (Patch pp : ZoneLimitPatch.patches(1)) {
							if (i >= pp.fileOffset && i < pp.fileOffset + 4) {
								inWindow = true;
								break;
							}
						}
						check(inWindow, "changed byte 0x" + Integer.toHexString(i) + " is inside a patch window");
					}
				}
				checkEq(6, diff, "N=1 changes exactly 6 bytes (the immediates/low bytes of the 5 sites)");
			} catch (IllegalStateException ex) {
				fails++;
				System.out.println("FAILURE: real code.bin stock-word mismatch: " + ex.getMessage());
			}
		} else {
			Skip.section("code.bin not found - the real-binary verification did not run");
		}

		System.out.println();
		if (fails == 0) {
			System.out.println("PASS");
		} else {
			System.out.println("FAIL (" + fails + ")");
			System.exit(1);
		}
	}

	/** An executable holding the stock word at every site and nothing anywhere else. */
	private static byte[] stockCode() {
		byte[] code = new byte[0x2E0000];
		for (Patch p : ZoneLimitPatch.patches(1)) {
			System.arraycopy(p.original, 0, code, p.fileOffset, 4);
		}
		return code;
	}

	private interface Act {
		void run();
	}

	/** The refusal an act threw, or null when it did not refuse; any other exception is named. */
	private static String refusal(Act act) {
		try {
			act.run();
			return null;
		} catch (IllegalStateException refused) {
			return refused.getMessage();
		} catch (RuntimeException threw) {
			return "threw instead of refusing: " + threw;
		}
	}

	private static void checkBytes(Patch p, int expOff, byte[] expBytes, String what) {
		if (p.fileOffset != expOff) {
			fail(what + " (offset 0x" + Integer.toHexString(p.fileOffset) + " != 0x" + Integer.toHexString(expOff) + ")");
			return;
		}
		for (int i = 0; i < 4; i++) {
			if (p.patched[i] != expBytes[i]) {
				fail(what + " (byte " + i + " = " + (p.patched[i] & 0xFF) + " != " + (expBytes[i] & 0xFF) + ")");
				return;
			}
		}
	}

	/** See section 3e of {@link #main}. */
	static void aZoneLimitPatchIsSavedWhereThePickedAndNowhereOnCancel() throws Exception {
		java.lang.reflect.Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		theUnsafe.setAccessible(true);
		Object unsafe = theUnsafe.get(null);
		Object panel = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe,
				ctrmap.humaninterface.ZoneLoadingPanel.class);
		java.lang.reflect.Method save = ctrmap.humaninterface.ZoneLoadingPanel.class.getDeclaredMethod("saveZoneLimitIps",
				byte[].class);
		save.setAccessible(true);
		byte[] none = ctrmap.formats.codepatch.Ips.write(new java.util.ArrayList<>());
		File picked = new File(Scratch.dir("ctrmap_zonelimit_save"), "code.ips");
		Object answered;
		ctrmap.Ui.record(picked);
		try {
			answered = save.invoke(panel, (Object) none);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(picked.equals(answered) && picked.isFile(), "the zone-limit patch is saved where the user picked: answered "
				+ answered + ", " + (picked.isFile() ? "written" : "not written"));
		File here = new File("code.ips").getAbsoluteFile();
		if (here.exists()) {
			Skip.section("a code.ips is already in " + here.getParent() + ", so a cancel that wrote one could not be told");
			return;
		}
		ctrmap.Ui.record();
		try {
			answered = save.invoke(panel, (Object) none);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		boolean wrote = here.exists();
		if (wrote) {
			java.nio.file.Files.delete(here.toPath());   //this check's own: it was not there before the call
		}
		check(answered == null && !wrote, "a cancelled save answers no file and writes none: answered " + answered + ", "
				+ (wrote ? "wrote " + here : "wrote nothing"));
	}

	/**
	 * Make zone-limit patch with no workspace says to load one, and with ZoneData open goes on to ask for code.bin (the
	 * 2026-10-15 shared sweep, ZoneLoadingPanel 1558, negate-if, SURVIVED: no suite pressed it). Inverted, a game with
	 * its zones open was told to load a workspace, and one with none went on to count zones it has not got. The game
	 * folder is a fresh scratch one, so no code.bin is remembered for it, the chooser is cancelled, and nothing is
	 * remembered or forgotten in the user's settings.
	 */
	static void aZoneLimitPatchIsMadeOnlyWithAWorkspace() throws Exception {
		java.lang.reflect.Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		theUnsafe.setAccessible(true);
		Object unsafe = theUnsafe.get(null);
		ctrmap.humaninterface.ZoneLoadingPanel panel = (ctrmap.humaninterface.ZoneLoadingPanel) unsafe.getClass()
				.getMethod("allocateInstance", Class.class).invoke(unsafe, ctrmap.humaninterface.ZoneLoadingPanel.class);
		ctrmap.WorkspaceSession was = ctrmap.Workspace.session();
		String gameDir = ctrmap.Workspace.GAMEDIR_PATH;
		List<String> none;
		List<String> open;
		File answeredNone;
		File answeredOpen;
		try {
			ctrmap.Workspace.GAMEDIR_PATH = Scratch.dir("ctrmap_zonelimit_game").getAbsolutePath();
			ctrmap.Workspace.install(null);
			none = ctrmap.Ui.record();
			try {
				answeredNone = panel.makeZoneLimitPatch();
			} finally {
				ctrmap.Ui.stopRecording();
			}
			MapResizeTest.game("zonelimit");
			open = ctrmap.Ui.record();
			try {
				answeredOpen = panel.makeZoneLimitPatch();
			} finally {
				ctrmap.Ui.stopRecording();
			}
		} finally {
			ctrmap.Workspace.GAMEDIR_PATH = gameDir;
			ctrmap.Workspace.install(was);
		}
		check(answeredNone == null && none.contains("Make zone-limit patch: Load a workspace first."),
				"with no workspace, Make zone-limit patch says to load one: " + none);
		check(answeredOpen == null && !open.toString().contains("Load a workspace first.")
				&& open.toString().contains("Pick your DECOMPRESSED code.bin"),
				"and with ZoneData open it goes on to ask for code.bin: " + open);
	}

	private static void checkEq(int exp, int got, String what) {
		if (exp != got) {
			fail(what + " (got " + got + ", expected " + exp + ")");
		}
	}

	private static void check(boolean cond, String what) {
		if (!cond) {
			fail(what);
		}
	}

	private static void fail(String what) {
		System.out.println("FAILURE: " + what);
		fails++;
	}
}

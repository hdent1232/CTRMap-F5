package ctrmap.tests;

import ctrmap.formats.codepatch.CodeIps;
import ctrmap.formats.codepatch.ItemIconTable;
import ctrmap.formats.codepatch.ShopData;
import ctrmap.formats.codepatch.ZoneLimitPatch;
import ctrmap.formats.codepatch.Ips;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One code.ips carries the zone-limit, shop and item-icon patches, and saving any one of them keeps
 * the other two (F152).
 *
 * <p>WHY THIS SUITE EXISTS. The three editors that write code.ips each had their own policy. The
 * shop editor asked "merge?" and returned only on CANCEL, so closing the question overwrote the
 * file with shops alone; the zone-limit writer never asked and always overwrote; the icon editor
 * alone refused a closed dialog. Closing a dialog, or adding zones after editing shops, deleted the
 * patch the game needs to boot. Removing the added zones told the user to delete the file, shops
 * and icons with it. And a plain merge of a new save over the old file kept every byte the new save
 * no longer writes, so an edit put back to the game's own was never put back in the file.
 *
 * <p>ORDER: needs no dump. The executable is built here: the shop anchor, the icon table and the
 * zone-limit sites each where the retail build has them.
 *
 * Usage: java ctrmap.tests.CodeIpsTest
 */
public class CodeIpsTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Headless.require();
		File root = new File(args.length > 0 ? args[0] : "src");
		byte[] code = executable();
		File dir = Files.createTempDirectory("codeips").toFile();
		try {
			eachPatchOwnsWhatItWrites(code);
			threePatchesShareOneFile(code, new File(dir, "code.ips"));
			anEditPutBackIsPutBackInTheFile(code, new File(dir, "revert.ips"));
			aRecordAcrossTheEdgeIsSplit();
			aFileThatIsNotAPatchIsLeftAlone(new File(dir, "other.ips"));
			removingTheZonesKeepsTheRest(code, new File(dir, "removed.ips"));
			theEditorsSaveButtons(code, dir, new File(dir, "editors.ips"), root);
			everyWriterGoesThroughTheOne(root);
		} finally {
			for (File f : dir.listFiles()) {
				f.delete();
			}
			dir.delete();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** The icon test's stand-in executable, with the shop table and the zone-limit sites added. */
	static byte[] executable() {
		byte[] code = ItemIconPatchTest.synthesize();
		byte[] shop = ShopDataTest.madeCode(ShopData.FILE_OFFSET);
		System.arraycopy(shop, 0x400000, code, 0x400000, shop.length - 0x400000);
		for (ZoneLimitPatch.Patch p : ZoneLimitPatch.patches(1)) {
			System.arraycopy(p.original, 0, code, p.fileOffset, 4);
		}
		return code;
	}

	static void eachPatchOwnsWhatItWrites(byte[] code) {
		System.out.println("--- each patch owns exactly the bytes it writes");
		ShopData.verify(code);
		ItemIconTable.verify(code);
		ZoneLimitPatch.verify(code);
		check(ShopData.totalItems() == 215 && span(ShopData.owned()) == 430,
				"control: the shop table is 215 items of 2 bytes, 430 bytes owned");
		check(span(ItemIconTable.owned()) == 3104 && ItemIconTable.owned()[0][1] == ItemIconTable.NEXT_TABLE,
				"the icon table is 776 words, 3104 bytes, ending where the next table starts");
		List<int[]> zone = Ips.parse(ZoneLimitPatch.buildIPS(4, code));
		int written = 0;
		boolean inside = true;
		for (int[] rec : zone) {
			written += rec[1];
			for (int k = 0; k < rec[1]; k++) {
				inside &= owns(ZoneLimitPatch.owned(), rec[0] + k);
			}
		}
		check(inside && written == span(ZoneLimitPatch.owned()) && written == 20,
				"the zone-limit patch writes 20 bytes, every one inside the 5 words it owns, and owns no other");
		check(!overlap(ShopData.owned(), ItemIconTable.owned()) && !overlap(ShopData.owned(), ZoneLimitPatch.owned())
				&& !overlap(ItemIconTable.owned(), ZoneLimitPatch.owned()),
				"and no two patches own the same byte");
	}

	static void threePatchesShareOneFile(byte[] code, File out) throws Exception {
		System.out.println("--- the zone-limit, shop and icon patches saved into one file, in that order (F152)");
		byte[] zoneIps = ZoneLimitPatch.buildIPS(4, code);
		check(CodeIps.save(out, zoneIps, ZoneLimitPatch.owned()).isEmpty()
				&& Arrays.equals(Files.readAllBytes(out.toPath()), zoneIps),
				"with no file there, the save is the patch itself");
		int[][] shops = edited(ShopData.read(code), 18, new int[]{50, 51, 52, 53});
		String note = CodeIps.save(out, Ips.diff(code, ShopData.write(code, shops)), ShopData.owned());
		check(note.contains("kept"), "a save into a file already there says the patches in it were kept: '" + note + "'");
		int[] icons = ItemIconTable.read(code);
		int[] iconsEdited = icons.clone();
		iconsEdited[50] = icons[2];
		CodeIps.save(out, ItemIconTable.diffIPS(code, iconsEdited), ItemIconTable.owned());

		byte[] all = Ips.apply(code, Files.readAllBytes(out.toPath()));
		byte[] zoneOnly = ZoneLimitPatch.applyToCode(4, code);
		boolean zoneKept = true;
		for (int[] r : ZoneLimitPatch.owned()) {
			zoneKept &= Arrays.equals(Arrays.copyOfRange(all, r[0], r[1]), Arrays.copyOfRange(zoneOnly, r[0], r[1]));
		}
		check(zoneKept, "the zone-limit patch the game needs to boot survives the shop and icon saves");
		check(Arrays.equals(ShopData.read(all)[18], shops[18]), "the shop edit survives the icon save");
		check(ItemIconTable.read(all)[50] == icons[2] && ItemIconTable.read(all)[51] == icons[51],
				"the icon edit is there, and an icon nobody edited is not touched");

		CodeIps.save(out, ZoneLimitPatch.buildIPS(8, code), ZoneLimitPatch.owned());
		byte[] again = Ips.apply(code, Files.readAllBytes(out.toPath()));
		check(Arrays.equals(Arrays.copyOfRange(again, 0x012bc0, 0x012bc4),
				Arrays.copyOfRange(ZoneLimitPatch.applyToCode(8, code), 0x012bc0, 0x012bc4))
				&& Arrays.equals(ShopData.read(again)[18], shops[18]) && ItemIconTable.read(again)[50] == icons[2],
				"adding more zones replaces the zone-limit patch and keeps the shops and icons (it overwrote them)");
	}

	static void anEditPutBackIsPutBackInTheFile(byte[] code, File out) throws Exception {
		System.out.println("--- a shop slot put back to the game's own is put back in code.ips too (F152)");
		int[][] stock = ShopData.read(code);
		int[][] first = edited(stock, 18, new int[]{50, 51, 52, 53});
		CodeIps.save(out, Ips.diff(code, ShopData.write(code, first)), ShopData.owned());
		int[][] second = edited(stock, 18, new int[]{50, stock[18][1], stock[18][2], stock[18][3]});
		byte[] secondIps = Ips.diff(code, ShopData.write(code, second));

		byte[] plain = Ips.apply(code, Ips.merge(Files.readAllBytes(out.toPath()), secondIps));
		check(ShopData.read(plain)[18][1] == 51,
				"control: merging the new save over the old keeps the slot the new save no longer writes");
		CodeIps.save(out, secondIps, ShopData.owned());
		byte[] saved = Ips.apply(code, Files.readAllBytes(out.toPath()));
		check(Arrays.equals(ShopData.read(saved)[18], second[18]),
				"the save puts the 3 slots back: " + Arrays.toString(ShopData.read(saved)[18]));

		CodeIps.save(out, Ips.diff(code, ShopData.write(code, stock)), ShopData.owned());
		check(Ips.parse(Files.readAllBytes(out.toPath())).isEmpty(),
				"and every list put back takes the shop table out of the file - a save with nothing changed is still a save");
	}

	static void aRecordAcrossTheEdgeIsSplit() {
		System.out.println("--- a record across the edge of what a save owns keeps its outside bytes");
		int at = ShopData.FILE_OFFSET;
		int end = ShopData.owned()[0][1];
		byte[] existing = ips(new int[]{at - 2, 6, 1, 2, 3, 4, 5, 6}, new int[]{end - 2, 4, 7, 8, 9, 10});
		List<int[]> left = Ips.parse(CodeIps.merged(existing, empty(), ShopData.owned()));
		check(left.size() == 2 && left.get(0)[0] == at - 2 && left.get(0)[1] == 2 && left.get(0)[2] == 1 && left.get(0)[3] == 2
				&& left.get(1)[0] == end && left.get(1)[1] == 2 && left.get(1)[2] == 9 && left.get(1)[3] == 10,
				"the 2 bytes before the table and the 2 after it stay, the 6 inside go: " + describe(left));
	}

	static void aFileThatIsNotAPatchIsLeftAlone(File out) throws Exception {
		System.out.println("--- a code.ips that is not a patch is neither merged into nor replaced");
		byte[] text = "not a patch".getBytes("UTF-8");
		Files.write(out.toPath(), text);
		refused("a file that is not IPS", out, "not an IPS patch");
		check(Arrays.equals(Files.readAllBytes(out.toPath()), text), "and the file is as it was");
		byte[] cut = ips(new int[]{0x10, 2, 1, 2});
		cut[8] = 0x40;   //the record's length now says 0x4002 bytes, running past the end; PATCH and EOF stay
		Files.write(out.toPath(), cut);
		refused("a record running past the end", out, "could not be read record by record");
		check(Arrays.equals(Files.readAllBytes(out.toPath()), cut), "and that file is as it was too");
	}

	static void removingTheZonesKeepsTheRest(byte[] code, File out) throws Exception {
		System.out.println("--- removing the added zones takes the zone-limit patch out and keeps the rest (F152)");
		CodeIps.save(out, ZoneLimitPatch.buildIPS(4, code), ZoneLimitPatch.owned());
		int[][] shops = edited(ShopData.read(code), 18, new int[]{50, 51, 52, 53});
		CodeIps.save(out, Ips.diff(code, ShopData.write(code, shops)), ShopData.owned());
		int left = CodeIps.strip(out, ZoneLimitPatch.owned());
		byte[] after = Ips.apply(code, Files.readAllBytes(out.toPath()));
		check(Arrays.equals(Arrays.copyOfRange(after, 0x012bc0, 0x012bc4), Arrays.copyOfRange(code, 0x012bc0, 0x012bc4))
				&& Arrays.equals(ShopData.read(after)[18], shops[18]) && left > 0,
				"the zone-limit sites are stock again, the shop edit stays, " + left + " patched byte(s) left");
		check(CodeIps.strip(out, ShopData.owned()) == 0 && Ips.parse(Files.readAllBytes(out.toPath())).isEmpty(),
				"and with the shops taken out too, the file patches nothing");
	}

	/**
	 * The shop and icon editors' Save buttons, pressed headless: the chooser is answered through the
	 * Ui seam, so what is tested is the code the button runs, not the writer it should call.
	 */
	static void theEditorsSaveButtons(byte[] code, File dir, File out, File root) throws Exception {
		System.out.println("--- the shop and icon editors' Save buttons keep the zone-limit patch (F152)");
		File codeFile = new File(dir, "code.bin");   //only its folder is read: where the chooser starts
		CodeIps.save(out, ZoneLimitPatch.buildIPS(4, code), ZoneLimitPatch.owned());
		int[][] stock = ShopData.read(code);
		int[][] shops = edited(stock, 18, new int[]{50, 51, 52, 53});

		List<String> said = ctrmap.Ui.record(out);
		try {
			ctrmap.humaninterface.ShopEditDialog.savePatch(null, codeFile, code, shops);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		byte[] after = Ips.apply(code, Files.readAllBytes(out.toPath()));
		check(zoneKept(code, after) && Arrays.equals(ShopData.read(after)[18], shops[18]),
				"the shop editor's Save writes the shops into the code.ips there and keeps its zone-limit patch: " + said);

		byte[] before = Files.readAllBytes(out.toPath());
		said = ctrmap.Ui.record();   //nothing answers the chooser: it was closed
		try {
			ctrmap.humaninterface.ShopEditDialog.savePatch(null, codeFile, code, stock);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(Arrays.equals(Files.readAllBytes(out.toPath()), before), "closing the chooser writes nothing: " + said);

		said = ctrmap.Ui.record(out);
		try {
			ctrmap.humaninterface.ShopEditDialog.savePatch(null, codeFile, code, stock);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		after = Ips.apply(code, Files.readAllBytes(out.toPath()));
		check(Arrays.deepEquals(ShopData.read(after), stock) && zoneKept(code, after),
				"every list put back and saved takes the shops out of the file, zone patch kept - it said there was"
				+ " nothing to save and left the old edit in: " + said);

		int[] icons = ItemIconTable.read(code);
		int[] iconsEdited = icons.clone();
		iconsEdited[50] = icons[2];
		said = ctrmap.Ui.record(out);
		try {
			ctrmap.humaninterface.ItemEditDialog.saveIconPatch(null, codeFile, code, iconsEdited);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		after = Ips.apply(code, Files.readAllBytes(out.toPath()));
		check(ItemIconTable.read(after)[50] == icons[2] && zoneKept(code, after),
				"the icon editor's Save writes the icon and keeps the zone-limit patch: " + said);
		said = ctrmap.Ui.record(out);
		try {
			ctrmap.humaninterface.ItemEditDialog.saveIconPatch(null, codeFile, code, icons);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		after = Ips.apply(code, Files.readAllBytes(out.toPath()));
		check(Arrays.equals(ItemIconTable.read(after), icons) && zoneKept(code, after),
				"every icon put back and saved takes the icons out of the file too: " + said);

		String mf = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/CtrmapMainframe.java")));
		//BY ITS BRACES, not by where the next "private static void" starts: that marker stopped existing
		//when the window became an object (F007), and the action read as 0 characters
		String found = CollisionReadTest.body(mf, "private void removeAddedZonesAction()");
		String body = found == null ? "" : found;
		check(body.contains("stripZoneLimit();") && !body.toLowerCase().contains("delete the deployed code.ips"),
				"removing the added zones takes the zone-limit patch out of code.ips, and no longer says to delete"
				+ " the file (" + body.length() + " chars of the action read)");
	}

	static boolean zoneKept(byte[] code, byte[] patched) {
		byte[] zoneOnly = ZoneLimitPatch.applyToCode(4, code);
		for (int[] r : ZoneLimitPatch.owned()) {
			if (!Arrays.equals(Arrays.copyOfRange(patched, r[0], r[1]), Arrays.copyOfRange(zoneOnly, r[0], r[1]))) {
				return false;
			}
		}
		return true;
	}

	/** A write of a code patch, its bytes named ips, anywhere but the one writer. */
	static final Pattern RAW_IPS_WRITE = Pattern.compile(
			"(?:Files\\.write|replaceFile|FileOutputStream)\\s*\\([^;]*\\bips\\b");

	/**
	 * The IPS markers, spelled by hand: as strings, as the EOF word, or as their bytes one by one -
	 * as characters, or as the numbers the cheapest way past a check of characters would use.
	 */
	static final Pattern IPS_SPELLED = Pattern.compile(
			"\"PATCH\"|\"EOF\"|0x454F46|'P'.{0,80}'A'.{0,80}'T'.{0,80}'C'.{0,80}'H'"
			+ "|0x50\\W{1,80}0x41\\W{1,80}0x54\\W{1,80}0x43\\W{1,80}0x48"
			+ "|\\b80\\W{1,80}65\\W{1,80}84\\W{1,80}67\\W{1,80}72\\b", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

	static void everyWriterGoesThroughTheOne(File root) throws Exception {
		System.out.println("--- every code.ips writer goes through CodeIps (F152)");
		check(RAW_IPS_WRITE.matcher("Files.write(out.toPath(), ips);").find()
				&& RAW_IPS_WRITE.matcher("ctrmap.util.Bytes.replaceFile(fc.getSelectedFile(), ips);").find()
				&& !RAW_IPS_WRITE.matcher("String note = CodeIps.save(out, ips, ShopData.owned());").find(),
				"control: the shop and zone writers' old raw writes are seen, a save through CodeIps is not");
		List<String> raw = new ArrayList<>();
		List<String> merges = new ArrayList<>();
		for (File f : CollisionReadTest.javaFiles(new File(root, "ctrmap"), new ArrayList<>())) {
			if (f.getPath().contains(File.separator + "tests" + File.separator) || f.getName().equals("CodeIps.java")) {
				continue;
			}
			String src = SourceSeamTest.stripComments(DialogSeamTest.read(f));
			if (RAW_IPS_WRITE.matcher(src).find()) {
				raw.add(f.getName());
			}
			if (src.contains("Ips.merge(")) {
				merges.add(f.getName());
			}
		}
		check(raw.isEmpty(), "nothing writes a code patch's bytes itself: " + raw);
		check(merges.isEmpty(), "and nothing merges one but CodeIps: " + merges);
		//ONE PLACE SPELLS THE FORMAT (F047). Three writers wrote PATCH, the records and EOF by hand
		//and a fourth kept an empty patch as eight typed bytes; every one goes through Ips now.
		check(IPS_SPELLED.matcher("writeAscii(b, \"PATCH\");").find()
				&& IPS_SPELLED.matcher("b.write('P');\n\t\tb.write('A');\n\t\tb.write('T');\n\t\tb.write('C');\n\t\tb.write('H');").find()
				&& IPS_SPELLED.matcher("{'P', 'A', 'T', 'C', 'H', 'E', 'O', 'F'}").find()
				&& IPS_SPELLED.matcher("{0x50, 0x41, 0x54, 0x43, 0x48, 0x45, 0x4F, 0x46}").find()
				&& IPS_SPELLED.matcher("new byte[]{80, 65, 84, 67, 72}").find()
				&& !IPS_SPELLED.matcher("return Ips.write(records);").find(),
				"control: the old hand-written PATCH/EOF spellings are seen, a write through Ips is not");
		List<String> spelled = new ArrayList<>();
		for (File f : CollisionReadTest.javaFiles(new File(root, "ctrmap"), new ArrayList<>())) {
			if (f.getPath().contains(File.separator + "tests" + File.separator) || f.getName().equals("Ips.java")) {
				continue;
			}
			if (IPS_SPELLED.matcher(SourceSeamTest.stripComments(DialogSeamTest.read(f))).find()) {
				spelled.add(f.getName());
			}
		}
		check(spelled.isEmpty(), "nothing spells the IPS format but Ips: " + spelled);
		String[][] writers = {
			{"humaninterface/ShopEditDialog.java", "save", "ShopData"},
			{"humaninterface/ItemEditDialog.java", "save", "ItemIconTable"},
			{"humaninterface/ZoneLoadingPanel.java", "save", "ZoneLimitPatch"},
			{"CtrmapMainframe.java", "strip", "ZoneLimitPatch"}};
		for (String[] w : writers) {
			String src = SourceSeamTest.stripComments(DialogSeamTest.read(new File(root, "ctrmap/" + w[0])));
			check(Pattern.compile("CodeIps\\." + w[1] + "\\([^;]*\\b" + w[2] + "\\.owned\\(\\)\\)").matcher(src).find(),
					w[0] + " goes through CodeIps." + w[1] + " with the ranges " + w[2] + " owns");
		}
	}

	// ------------------------------------------------------------ helpers

	static int[][] edited(int[][] shops, int which, int[] list) {
		int[][] out = new int[shops.length][];
		for (int i = 0; i < shops.length; i++) {
			out[i] = shops[i].clone();
		}
		out[which] = list;
		return out;
	}

	static byte[] ips(int[]... records) {
		java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
		b.write('P');
		b.write('A');
		b.write('T');
		b.write('C');
		b.write('H');
		for (int[] rec : records) {
			b.write((rec[0] >> 16) & 0xFF);
			b.write((rec[0] >> 8) & 0xFF);
			b.write(rec[0] & 0xFF);
			b.write((rec[1] >> 8) & 0xFF);
			b.write(rec[1] & 0xFF);
			for (int k = 0; k < rec[1]; k++) {
				b.write(rec[2 + k]);
			}
		}
		b.write('E');
		b.write('O');
		b.write('F');
		return b.toByteArray();
	}

	static byte[] empty() {
		return ips();
	}

	static int span(int[][] ranges) {
		int n = 0;
		for (int[] r : ranges) {
			n += r[1] - r[0];
		}
		return n;
	}

	static boolean owns(int[][] ranges, int offset) {
		for (int[] r : ranges) {
			if (offset >= r[0] && offset < r[1]) {
				return true;
			}
		}
		return false;
	}

	static boolean overlap(int[][] a, int[][] b) {
		for (int[] x : a) {
			for (int[] y : b) {
				if (x[0] < y[1] && y[0] < x[1]) {
					return true;
				}
			}
		}
		return false;
	}

	static String describe(List<int[]> records) {
		StringBuilder s = new StringBuilder();
		for (int[] r : records) {
			s.append(String.format("[0x%X x%d] ", r[0], r[1]));
		}
		return s.toString().trim();
	}

	static void refused(String what, File out, String why) {
		try {
			CodeIps.save(out, empty(), ShopData.owned());
			check(false, what + " is refused (it was saved into)");
		} catch (Exception said) {
			String m = String.valueOf(said.getMessage());
			check(m.contains(why), what + " is refused in words: " + m);
		}
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

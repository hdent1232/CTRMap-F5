package ctrmap.tests;

import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.humaninterface.Combos;
import ctrmap.humaninterface.WarpEditForm;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JComboBox;

/**
 * A stored value a combo box cannot show neither throws out of the form nor comes back changed
 * (F126).
 *
 * <p>WHY THIS SUITE EXISTS. Five forms selected a box's row straight from a raw file value. A value
 * past the rows threw out of the fill after the form had already switched to the new record, so the
 * fields below it still showed the previous one - and the warp form's save, which the zone save
 * calls unprompted, built the record from those widgets and wrote the previous warp's direction,
 * position and facing into this one.
 *
 * <p>ORDER: needs no dump and no display; the warp form is the real one, over a made zone.
 *
 * Usage: java ctrmap.tests.ComboRawTest
 */
public class ComboRawTest {

	static int fails = 0;
	static final Pattern RAW = Pattern.compile("setSelectedIndex\\(\\s*[a-z]\\w*(\\.\\w+)+\\s*\\)");

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			run();
		} finally {
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static void run() throws Exception {
		System.out.println("--- a box shows what it can and keeps what it cannot (F126)");
		JComboBox<String> box = new JComboBox<>(new String[]{"a", "b", "c"});
		check(Combos.show(box, 2) == 2 && Combos.show(box, 3) == -1 && Combos.show(box, -4) == -1,
				"a row the box holds is shown; past it or negative, nothing is, and nothing throws");
		check(Combos.keep(box, -1, 7) == 7, "a box left as it was shown writes back the value as read");
		box.setSelectedIndex(1);
		check(Combos.keep(box, -1, 7) == 1, "and one the user moved writes back the row it was moved to");

		System.out.println("--- the warp form shows and saves a warp its boxes cannot hold (F126)");
		MapResizeTest.game("warpbox");
		byte[] amx = HandedGameTest.amx();
		byte[] bytes = new byte[12 + amx.length];
		MapResizeTest.putI32(bytes, 0, 8);
		System.arraycopy(amx, 0, bytes, 12, amx.length);
		ZoneEntities e = new ZoneEntities(bytes);
		ZoneEntities.Warp odd = new ZoneEntities.Warp();
		odd.directionality = 5;
		odd.coordinateType = 3;
		odd.faceDirection = 7;
		odd.x = 9;
		odd.y = 27;
		e.warps.add(odd);
		e.warpCount = 1;
		WarpEditForm form = new WarpEditForm(new LoadedZone(), DataSafetyGuardsTest.REDRAW, DataSafetyGuardsTest.CENTRE);
		Throwable thrown = null;
		try {
			form.loadFromEntities(e);
		} catch (Throwable t) {
			thrown = t;
		}
		check(thrown == null, "a warp with direction 5, position type 3 and facing 7 shows without throwing: " + thrown);
		form.saveEntry();
		ZoneEntities.Warp back = e.warps.get(0);
		check(!e.modified && back.directionality == 5 && back.coordinateType == 3 && back.faceDirection == 7,
				"and the save the zone save makes unprompted writes nothing new: direction " + back.directionality
				+ ", position type " + back.coordinateType + ", facing " + back.faceDirection + ", modified " + e.modified);

		System.out.println("--- no form selects a box from a raw value but through the bound (F126)");
		check(RAW.matcher("warpType.setSelectedIndex(warp.directionality);").find(), "control: the scan matches a raw selection");
		List<String> raw = new ArrayList<>();
		int scanned = 0;
		for (File f : CollisionReadTest.javaFiles(new File("src/ctrmap"), new ArrayList<>())) {
			if (f.getPath().contains(File.separator + "tests" + File.separator)) {
				continue;
			}
			scanned++;
			Matcher m = RAW.matcher(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			while (m.find()) {
				//the facility dialogs put back the index they set themselves, never a file's value
				if (!m.group().endsWith("model.poolIndex)") && !m.group().endsWith("model.tableIndex)")) {
					raw.add(f.getName() + ": " + m.group());
				}
			}
		}
		check(raw.isEmpty() && scanned > 100, "every selection from a stored value goes through Combos.show (" + raw
				+ " in " + scanned + " files)");
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

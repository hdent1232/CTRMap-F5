package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.tilemap.TilePalette;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import static ctrmap.formats.containers.ContainerBytes.subfile;
import ctrmap.formats.tilemap.PaintedMaterials;

/**
 * A ground brush must never resolve to a cliff face.
 *
 * <p>The brushes match materials by substring, and ROCK's hints include
 * {@code gake} - Japanese for CLIFF. So on ordinary outdoor routes "rock"
 * matched the map's own cliff: {@code chip_gake_sea} on Route 101,
 * {@code r105_chip_rock_c} on Route 103. Painting rock ground laid vertical
 * cliff art flat across the floor.
 *
 * <p>It read like a bad row in {@code oras_terrain.tsv}, and that row WAS bad,
 * but fixing it would have changed nothing here: {@code ensureMaterial} returns
 * early whenever the map already has a matching material, so on most regions
 * the donor table is never consulted at all. The defect was in resolution, not
 * in the table.
 *
 * <p>Sweeps every region in FieldData and measures, for each ground brush, the
 * flatness of the mesh the painter actually picks. Cliff brushes are exempt -
 * a cliff is supposed to be vertical.
 *
 * Usage: java ctrmap.tests.GroundResolveTest &lt;path-to-a039-garc&gt;
 */
public class GroundResolveTest {

	/** Brushes that paint a floor the player stands on. */
	static final TilePalette[] GROUND = {
		TilePalette.GRASS, TilePalette.TALL_GRASS, TilePalette.PATH, TilePalette.SAND,
		TilePalette.ROCK, TilePalette.CAVE, TilePalette.DEEP_SAND, TilePalette.INDOOR,
		TilePalette.WALKWAY,
	};

	/** Below this, the picked mesh is a wall rather than a surface. */
	static final double MIN_FLAT = 0.5;

	/** MEASURED 2026-10-09 over the retail ORAS FIELD_DATA (a/0/3/9): every entry is a map model. */
	static final int RETAIL_REGIONS = 857;

	/** MEASURED the same run: the ground brushes resolve to a native mesh 2278 times across it. */
	static final int RETAIL_RESOLVED = 2278;

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		GARC gr = new GARC(garcFile);
		int regions = 0, checked = 0, bad = 0;
		//A REGION THAT COULD NOT BE READ IS NOT ONE WITH NOTHING WRONG (F250): these were skipped
		//silently, and the only verdict was "bad == 0" - so a sweep that read nothing passed
		List<String> unread = new ArrayList<>();
		String worst = null;
		double worstFlat = 1.0;

		for (int r = 0; r < gr.length; r++) {
			byte[] model;
			try {
				model = subfile(gr.getDecompressedEntry(r), 1);
			} catch (RuntimeException unreadable) {
				unread.add(r + " (" + unreadable + ")");
				continue;
			}
			if (model == null || !BchMapModel.isMapModel(model)) {
				continue;
			}
			regions++;
			BchMapModel m;
			try {
				m = new BchMapModel(model);
			} catch (RuntimeException unparsable) {
				unread.add(r + " (" + unparsable + ")");
				continue;
			}
			for (TilePalette t : GROUND) {
				int mesh = PaintedMaterials.resolvedGroundMesh(m, t);
				if (mesh < 0) {
					continue; //no native match; the brush imports a donor instead
				}
				double flat = PaintedMaterials.meshFlatness(m, mesh);
				if (flat < 0) {
					continue; //no measurable surface (an empty imported placeholder)
				}
				checked++;
				if (flat < MIN_FLAT) {
					bad++;
					if (flat < worstFlat) {
						worstFlat = flat;
						worst = "region " + r + " brush " + t.name() + " -> "
								+ m.getMaterialName(m.getMeshMaterialIndex(mesh))
								+ " (flat " + String.format("%.2f", flat) + ")";
					}
				}
			}
		}

		System.out.println("regions swept " + regions + ", brush resolutions checked " + checked);
		System.out.println("ground brushes resolving to a wall: " + bad);
		if (worst != null) {
			System.out.println("  worst: " + worst);
		}
		//AND SOMETHING WAS EXAMINED: a regression that stops resolving any ground mesh gives
		//checked 0, bad 0. On the retail ORAS corpus the floor is what a corpus run MEASURED
		//(2026-10-09: all 857 regions are map models, 2278 ground resolutions) - a regression that
		//resolves fewer reads as fewer, not as clean; any other archive keeps "something at all"
		if (regions == 0 || checked == 0) {
			System.out.println("  FAIL: the sweep examined " + regions + " region(s) and " + checked
					+ " brush resolution(s) - a sweep that looked at nothing has found nothing wrong only by not looking");
			bad++;
		} else if (gr.length == RETAIL_REGIONS && (regions != RETAIL_REGIONS || checked < RETAIL_RESOLVED)) {
			System.out.println("  FAIL: the retail corpus measured " + RETAIL_REGIONS + " map regions and "
					+ RETAIL_RESOLVED + " ground resolutions; this sweep read " + regions + " and " + checked
					+ " - fewer is a resolution that stopped finding ground, not a cleaner map");
			bad++;
		} else if (gr.length != RETAIL_REGIONS) {
			System.out.println("  note: " + gr.length + " entries is not the retail ORAS corpus (" + RETAIL_REGIONS
					+ "), so only the something-was-examined floor applies");
		}
		if (!unread.isEmpty()) {
			System.out.println("  FAIL: " + unread.size() + " region(s) could not be read, so what they resolve is unknown: "
					+ unread.subList(0, Math.min(5, unread.size())));
			bad++;
		}
		System.out.println(bad == 0 ? "ALL PASS" : "FAILURES PRESENT (" + bad + ")");
		if (bad > 0) {
			System.exit(1);
		}
	}
}

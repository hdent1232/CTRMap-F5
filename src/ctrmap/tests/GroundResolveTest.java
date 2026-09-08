package ctrmap.tests;

import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import ctrmap.formats.tilemap.TilePalette;
import java.io.File;
import java.util.Arrays;
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

	public static void main(String[] args) throws Exception {
		File garcFile = new File(args.length > 0 ? args[0]
				: "../RomFS_original_garcs/a/0/3/9");
		GARC gr = new GARC(garcFile);
		int regions = 0, checked = 0, bad = 0;
		String worst = null;
		double worstFlat = 1.0;

		for (int r = 0; r < gr.length; r++) {
			byte[] model;
			try {
				model = subfile(gr.getDecompressedEntry(r), 1);
			} catch (RuntimeException ignore) {
				continue;
			}
			if (model == null || !BchMapModel.isMapModel(model)) {
				continue;
			}
			regions++;
			BchMapModel m;
			try {
				m = new BchMapModel(model);
			} catch (RuntimeException ignore) {
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
		System.out.println(bad == 0 ? "ALL PASS" : "FAILURES PRESENT (" + bad + ")");
		if (bad > 0) {
			System.exit(1);
		}
	}
}

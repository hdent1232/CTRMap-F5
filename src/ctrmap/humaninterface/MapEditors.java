package ctrmap.humaninterface;

import ctrmap.formats.containers.GR;
import ctrmap.formats.h3d.texturing.H3DTexture;
import ctrmap.formats.propdata.ADPropRegistry;
import ctrmap.formats.propdata.GRPropData;
import java.util.List;

/**
 * The editors the map view tells about the map it has just loaded.
 *
 * <p>WHY THIS IS A SETTER AND NOT A CONSTRUCTOR ARGUMENT. The map view is built
 * BEFORE the prop and NPC editors - they are handed the map view, which is the
 * direction that matters and which the previous commit finished. Neither order
 * lets both be constructor arguments, so the map view is TOLD about the editors
 * once, after both exist. See {@link MatrixTools} for the same decision on the
 * matrix panel, taken for the same reason.
 *
 * <p>Null until then, and the map view says what it does meanwhile rather than
 * throwing: nothing loads a map in that window, and if anything ever did, the
 * map would still be drawn - only the editors beside it would not be told.
 */
public interface MapEditors {

	/** A matrix is open: these are its props, with the registry and textures to draw them. */
	void showProps(GRPropData props, ADPropRegistry reg, List<H3DTexture> textures);

	/** A single region is open on its own, with no registry and no shared textures. */
	void showLooseProps(GR region);

	/**
	 * No entities: a loose map has replaced whatever zone was open.
	 *
	 * <p>The same clear {@link ZoneEditors} does when a zone closes. This one
	 * fires on the other path - File &gt; Open GR Mapfile - and it is the last
	 * strand of the cycle this window's ratchet used to name as its reason for
	 * existing.
	 */
	void clearEntities();
}

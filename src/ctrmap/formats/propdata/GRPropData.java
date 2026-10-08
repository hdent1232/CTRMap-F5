
package ctrmap.formats.propdata;

import ctrmap.util.LittleEndianDataInputStream;
import ctrmap.util.LittleEndianDataOutputStream;
import ctrmap.formats.containers.GR;
import ctrmap.formats.mapmatrix.MapMatrix;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Info about the placement of world props, stored in the fourth section (after collision) in GR files.
 * Note that the prop models have to be registered in the corresponding AreaData's first section as well
 * as have their required textures available in the location's AD BCH file (TODO - injecting textures).
 */
public class GRPropData {
	public ArrayList<GRProp> props = new ArrayList<>();
	public boolean modified = false;
	public GR f;
	/**
	 * Why some of these props could not be read, or null when all of them were.
	 *
	 * <p>A READ THAT FAILED IS NOT AN EMPTY LIST. A region whose prop section would not read used
	 * to come back with no props and a log line; the editor showed nothing there, and the next
	 * save wrote that empty list over every prop the region held. A save refuses while this is
	 * set, and says which region.
	 */
	public String unreadable;
	public GRPropData(GR gr){
		f = gr;
		try (LittleEndianDataInputStream dis = new LittleEndianDataInputStream(new ByteArrayInputStream(f.getFile(3)))) {
			int length = dis.readInt();
			for (int i = 0; i < length; i++){
				props.add(new GRProp(dis));
			}
		} catch (IOException | RuntimeException ex) {
			props.clear();
			unreadable = "the props of " + (gr.getOriginFile() == null ? "a region" : gr.getOriginFile().getName())
					+ " could not be read (" + ctrmap.util.Bytes.reason(ex) + ")";
		}
	}
	public GRPropData(){}

	/**
	 * Every region's props in one list, region by region: what the prop form edits for a matrix,
	 * read on load by the map view and again by a discard (F106), so in one place.
	 */
	public static GRPropData combined(MapMatrix mm) {
		GRPropData comb = new GRPropData();
		for (int i = 0; i < mm.height; i++) {
			for (int j = 0; j < mm.width; j++) {
				if (mm.regions.get(j, i) != null) {
					comb.absorb(new GRPropData(mm.regions.get(j, i)));
				}
			}
		}
		return comb;
	}

	/** Takes in a region's props, and its unreadability with them. */
	public void absorb(GRPropData region) {
		props.addAll(region.props);
		if (region.unreadable != null) {
			unreadable = unreadable == null ? region.unreadable : unreadable + "; " + region.unreadable;
		}
	}

	/** @throws IOException when the props were not all read, so saving would delete the rest */
	public void write() throws IOException {
		if (f == null) return;
		refuseUnread();
		f.storeFile(3, assemblePropData());
	}

	private void refuseUnread() throws IOException {
		if (unreadable != null) {
			throw new IOException(unreadable + ", so saving would have written over the props it holds"
					+ " with the ones that were read. Nothing was saved.");
		}
	}
	/** The side of one matrix cell, in the units a prop's position is stored in. */
	static final int CELL = 720;

	/**
	 * Writes every prop into the region of the cell it stands in - or, when that cell holds no
	 * region or lies off the grid, into the populated cell NEAREST to it.
	 *
	 * <p>NEVER DROPS A PROP (F083). The fallback search guarded its inner loop with an unbraced
	 * {@code if (y < 0 || y >= mm.height)}, so it ran only for rows OFF the grid, where reading a
	 * cell throws: a prop in an empty cell was left out of every region's list and the save
	 * reported nothing, and one standing off the grid's edge threw on the EDT instead.
	 *
	 * <p>Every region's bytes are built before the first is stored, so a prop list that cannot be
	 * assembled stores nothing; the stores themselves are put back together by a
	 * {@link ctrmap.WorkspaceUnit} the caller opens.
	 *
	 * @throws IOException when there are props and the matrix has no region to hold any of them -
	 *         nothing is written then, and the message says so
	 */
	public void write(MapMatrix mm) throws IOException {
		refuseUnread();
		GRPropData[][] newData = new GRPropData[mm.width][mm.height];
		boolean anyRegion = false;
		for (int i = 0; i < mm.height; i++){
			for (int j = 0; j < mm.width; j++){
				if (mm.regions.get(j, i) != null){
					newData[j][i] = new GRPropData();
					anyRegion = true;
				}
			}
		}
		if (!anyRegion && !props.isEmpty()) {
			throw new IOException("This map has no region to hold its " + props.size() + " prop(s), so"
					+ " nothing was saved - a prop is never left out of the file without a word.");
		}
		for (GRProp prop : props) {
			int[] cell = cellFor(prop.x, prop.z, newData);
			newData[cell[0]][cell[1]].props.add(prop);
		}
		byte[][][] data = new byte[mm.width][mm.height][];
		for (int i = 0; i < mm.height; i++){
			for (int j = 0; j < mm.width; j++){
				if (newData[j][i] != null){
					data[j][i] = newData[j][i].assemblePropData();
				}
			}
		}
		for (int i = 0; i < mm.height; i++){
			for (int j = 0; j < mm.width; j++){
				if (data[j][i] != null){
					mm.regions.get(j, i).storeFile(3, data[j][i]);
				}
			}
		}
	}

	/**
	 * The populated cell a prop at ({@code x}, {@code z}) is written into: the one it stands in,
	 * when that holds a region, else the populated cell whose centre is nearest to it - by real
	 * distance, the first in row order on a tie. {@code populated[x][y]} is non-null for a cell
	 * with a region, and at least one must be.
	 */
	static int[] cellFor(float x, float z, Object[][] populated) {
		int cx = (int) Math.floor(x / CELL);
		int cz = (int) Math.floor(z / CELL);
		int width = populated.length;
		int height = width == 0 ? 0 : populated[0].length;
		if (cx >= 0 && cx < width && cz >= 0 && cz < height && populated[cx][cz] != null) {
			return new int[]{cx, cz};
		}
		int[] best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int y = 0; y < height; y++) {
			for (int c = 0; c < width; c++) {
				if (populated[c][y] == null) {
					continue;
				}
				double dx = (c + 0.5) * CELL - x;
				double dz = (y + 0.5) * CELL - z;
				double distance = dx * dx + dz * dz;
				if (distance < bestDistance) {
					bestDistance = distance;
					best = new int[]{c, y};
				}
			}
		}
		if (best == null) {
			throw new IllegalArgumentException("no populated cell to put a prop in");
		}
		return best;
	}
	public byte[] assemblePropData(){
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			LittleEndianDataOutputStream dos = new LittleEndianDataOutputStream(baos);
			dos.writeInt(props.size());
			for (int i = 0; i < props.size(); i++){
				props.get(i).write(dos);
			}
			dos.close();
			return baos.toByteArray();
		} catch (IOException ex) {
			//NOT NULL: every caller stores what this returns, and a null subfile is a store that
			//fails somewhere further from here with less to say
			throw new IllegalStateException("the prop list could not be assembled: "
					+ ctrmap.util.Bytes.reason(ex), ex);
		}
	}
}

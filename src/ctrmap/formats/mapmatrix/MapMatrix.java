/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ctrmap.formats.mapmatrix;

import ctrmap.util.LittleEndianDataInputStream;
import ctrmap.util.LittleEndianDataOutputStream;
import ctrmap.formats.GameFiles;
import ctrmap.formats.containers.GR;
import ctrmap.formats.containers.MM;
import ctrmap.gamedef.ArchiveType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static ctrmap.util.LittleEndian.u16;
import static ctrmap.util.LittleEndian.i32;

public class MapMatrix {

	public MM file;
	public short hasLOD;
	public short unknown;
	public short width;
	public short height;
	public List<MatrixCameraBoundaries> cambounds = new ArrayList<>();
	public ResizeableMatrix<GR> regions;
	public ResizeableMatrix<Short> ids;
	public ResizeableMatrix<Short> zones;
	public ResizeableMatrix<Short> LOD;

	private int boundaryEntries;
	/**
	 * The camera section as it was read or last written, or null for a matrix built here (F160). The
	 * counted boundaries are written over a copy of it, so what follows them survives a save: retail
	 * matrix 1 holds 324 bytes in this section and 64 of them were written back. What the rest is has
	 * not been measured - that needs the dump - and a byte not understood is carried, not dropped.
	 */
	private byte[] camSection;
	/** The game the regions were opened from, for a discard that opens one again (F106); null for the grid only. */
	private GameFiles files;

	/**
	 * Reads a map matrix, opening each populated cell's region container
	 * through {@code files}.
	 *
	 * <p>Being handed {@code files} is what makes this class testable. It used
	 * to ask the application's global whether a workspace was open and fetch
	 * each region's extracted file from it, so a matrix could not be parsed
	 * with its regions in a test without a live workspace installed in the
	 * global first, and could not be pointed at a different game at all.
	 * Handed its GameFiles it opens each region from whatever it was given,
	 * and the GR it opens reports its own writes there too.
	 *
	 * @param files where the regions are staged, and who hears when one is
	 * written. Null reads the GRID ONLY, every region left null: what the
	 * builder's blank matrix and a suite reading a matrix straight out of the
	 * dump want, and what "no workspace open" used to mean here - except that
	 * now it is the caller's stated choice, not a global's state.
	 *
	 * @throws IllegalStateException naming the matrix and the reason, when it cannot be read whole
	 * (F080): the whole parse sat in a catch that logged, so a short or damaged subfile left the
	 * grid half filled - or null - and the camera bounds cut short, and {@link #write} then saved
	 * that back over the map
	 */
	public MapMatrix(MM f, GameFiles files) {
		file = f;
		this.files = files;
		try {
			LittleEndianDataInputStream dis = new LittleEndianDataInputStream(new ByteArrayInputStream(f.getFile(0)));
			hasLOD = dis.readShort();
			unknown = dis.readShort();
			width = dis.readShort();
			height = dis.readShort();
			ids = new ResizeableMatrix<>(width, height, (short) -1);
			regions = new ResizeableMatrix<>(width, height, null);
			zones = new ResizeableMatrix<>(width * 4, height * 4, (short) -1); //every region has 4x4 segments where zones can be switched freely (almost)
			LOD = new ResizeableMatrix<>(width, height, (short) -1); //LOD is their indexes in AD, need to figure out how they are determined
			for (int i = 0; i < height; i++) {
				for (int j = 0; j < width; j++) {
					ids.set(j, i, dis.readShort());
					if (files != null && ids.get(j, i) != -1) {
						regions.set(j, i, new GR(files.staged(ArchiveType.FIELD_DATA, ids.get(j, i)), files));
					}
				}
			}
			if (hasLOD == 1) { // for some reason, the LOD flag triggers both the zone switch and LOD
				for (int i = 0; i < height * 4; i++) {
					for (int j = 0; j < width * 4; j++) {
						zones.set(j, i, dis.readShort());
					}
				}
				for (int i = 0; i < height; i++) {
					for (int j = 0; j < width; j++) {
						LOD.set(j, i, dis.readShort());
					}
				}
			}
			//should be at EOSection now
			dis.close();
			camSection = f.getFile(1).clone();
			dis = new LittleEndianDataInputStream(new ByteArrayInputStream(camSection));
			boundaryEntries = dis.readInt();
			for (int i = 0; i < boundaryEntries; i++) {
				cambounds.add(new MatrixCameraBoundaries(dis));
			}
			dis.close();
		} catch (IOException | RuntimeException ex) {
			throw new IllegalStateException("The map matrix " + (f == null ? "(none)" : f.getOriginFile().getName())
					+ " could not be read, so it was not opened: " + (ex instanceof java.io.EOFException
					? "it ends before everything it declares has been read" : ctrmap.util.Bytes.reason(ex)), ex);
		}
	}

	public MapMatrix(MM f, int width, int height, int hasLOD) {
		file = f;
		this.width = (short) width;
		this.height = (short) height;
		this.hasLOD = (short) hasLOD;
		unknown = 0;
		ids = new ResizeableMatrix<>(width, height, (short) -1);
		regions = new ResizeableMatrix<>(width, height, null);
		zones = new ResizeableMatrix<>(width * 4, height * 4, (short) -1); //every region has 4x4 segments where zones can be switched freely (almost)
		LOD = new ResizeableMatrix<>(width, height, (short) -1);
		MatrixCameraBoundaries mcb = new MatrixCameraBoundaries();
		mcb.isRepeal = 0;
		mcb.north = -20;
		mcb.south = 740;
		mcb.west = -20;
		mcb.east = 740;
		//built and then dropped (F080), so the builder's new map shipped with no camera boundary
		cambounds.add(mcb);
	}

	/**
	 * WHICH REGIONS A GIVEN ZONE OWNS, read out of the matrix's own zone grid.
	 *
	 * <p>WHY THIS EXISTS. A matrix is not one zone's map. 25 retail matrices are named
	 * by more than one zone and 139 zones sit on a shared one, so "the map of zone N" is
	 * not the matrix - it is the cells of the matrix the game assigns to N. Asking the
	 * matrix for its first filled cell instead gives whatever zone owns the top-left
	 * corner: measured over the retail dump, 39 of the 61 zones whose matrix carries an
	 * ownership grid were answered with a region belonging to a DIFFERENT zone. Mossdeep
	 * City was shown Route 125.
	 *
	 * <p>THE GRID. A matrix with {@code hasLOD == 1} stores, after its region ids, a grid
	 * at four times the resolution naming the zone index that owns each quarter-cell.
	 * This project already trusts it: MatrixEditForm shows it to the user as the cell's
	 * zone. Measured over all 22 retail matrices that carry one, the distinct values in
	 * the grid are exactly the set of zones whose headers name that matrix.
	 *
	 * <p>WHAT A MATRIX WITHOUT A GRID MEANS. No grid means no sharing to resolve, and
	 * every populated cell is answered - which is the right answer for the 477 zones that
	 * own their matrix outright.
	 *
	 * @param mmContainer the raw MM container bytes
	 * @param zoneIndex the zone asking
	 * @return {region id, column, row} per owned cell, in reading order; empty if none
	 */
	public static int[][] regionsOwnedBy(byte[] mmContainer, int zoneIndex) {
		java.util.List<int[]> out = new java.util.ArrayList<>();
		if (mmContainer == null || mmContainer.length < 8) {
			return new int[0][];
		}
		int sub0 = i32(mmContainer, 4);
		if (sub0 < 0 || sub0 + 8 > mmContainer.length) {
			return new int[0][];
		}
		int lod = u16(mmContainer, sub0);
		int w = u16(mmContainer, sub0 + 4);
		int h = u16(mmContainer, sub0 + 6);
		int ids = sub0 + 8;
		int grid = ids + w * h * 2;                 //the zone grid, when there is one
		for (int row = 0; row < h; row++) {
			for (int col = 0; col < w; col++) {
				int at = ids + (row * w + col) * 2;
				if (at + 1 >= mmContainer.length) {
					return out.toArray(new int[out.size()][]);
				}
				int id = u16(mmContainer, at);
				if (id == 0xFFFF) {
					continue;
				}
				if (lod != 1 || !ownedByAnother(mmContainer, grid, w, h, col, row, zoneIndex)) {
					out.add(new int[]{id, col, row});
				}
			}
		}
		return out.toArray(new int[out.size()][]);
	}

	/**
	 * Whether this cell belongs to some zone other than the one asking.
	 *
	 * <p>A cell holds 4x4 quarter-cells and they need not agree - a border cell is
	 * shared deliberately, which is how the game hands over between two maps. So the
	 * question is not "do all sixteen say N" but "does any of them", and a cell whose
	 * grid says nothing at all (all empty) is kept rather than thrown away: an unmarked
	 * cell of a matrix only this zone names is still this zone's.
	 */
	private static boolean ownedByAnother(byte[] mm, int grid, int w, int h,
		int col, int row, int zoneIndex) {
		boolean sawSomebody = false;
		for (int sy = row * 4; sy < row * 4 + 4; sy++) {
			for (int sx = col * 4; sx < col * 4 + 4; sx++) {
				int at = grid + (sy * w * 4 + sx) * 2;
				if (at + 1 >= mm.length) {
					return false;                       //truncated: do not claim to know
				}
				int owner = u16(mm, at);
				if (owner == 0xFFFF) {
					continue;
				}
				if (owner == zoneIndex) {
					return false;
				}
				sawSomebody = true;
			}
		}
		return sawSomebody;
	}
	/**
	 * The first region ZONE {@code zoneIndex} OWNS in this matrix - by its ownership grid, where the matrix
	 * carries one - else the matrix's first filled cell; -1 when it names no region. The one byte-level answer
	 * to "which region is this zone's" (census M.54): the first filled cell alone is a neighbour's on the
	 * retail zones that share a matrix, and the atmosphere preview and the resizer's template took it.
	 */
	public static int ownRegionId(byte[] mmContainer, int zoneIndex) {
		int[][] mine = regionsOwnedBy(mmContainer, zoneIndex);
		return mine.length > 0 ? mine[0][0] : firstRegionId(mmContainer);
	}

	/**
	 * The first FieldData region a map matrix uses, or -1.
	 *
	 * <p>This is what "the map of the loaded zone" means to the tools that work
	 * on one region at a time - Export/Import OBJ default to it, and Blank map
	 * canvas probes it for the zone's own materials. Getting it wrong is not a
	 * cosmetic default: Import OBJ writes the mesh into whatever region the
	 * spinner is showing, so a wrong answer here drops the user's edited model
	 * on top of somebody else's town.
	 *
	 * <p>Takes the whole .mm container rather than a parsed MapMatrix because
	 * the callers have a file and want one number; building a MapMatrix would
	 * open a GR per populated cell against the live workspace. Cells read
	 * 0xFFFF where the zone puts no region, so the first cell that is not
	 * 0xFFFF is the map's top-left corner in reading order.
	 *
	 * @param mmContainer the raw bytes of the map matrix file (header + subfiles)
	 * @return the region id, or -1 if the file is unreadable or wholly empty
	 */
	public static int firstRegionId(byte[] mmContainer) {
		//THROUGH THE GRID (F248): this parsed the grid by hand beside the Grid that exists to read it,
		//and answered from a grid cut short as though the cells it reached were the map
		Grid grid;
		try {
			grid = Grid.of(mmContainer);
		} catch (IllegalArgumentException unreadable) {
			return -1;
		}
		int k = grid.firstFilledCell();
		return k < 0 ? -1 : grid.id(k);
	}

	public byte[] assembleData() {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			LittleEndianDataOutputStream dos = new LittleEndianDataOutputStream(out);
			dos.writeShort(hasLOD);
			dos.writeShort(unknown);
			dos.writeShort(width);
			dos.writeShort(height);
			for (int i = 0; i < height; i++) {
				for (int j = 0; j < width; j++) {
					dos.writeShort(ids.get(j, i));
				}
			}
			if (hasLOD == 1) {
				for (int i = 0; i < height * 4; i++) {
					for (int j = 0; j < width * 4; j++) {
						dos.writeShort(zones.get(j, i));
					}
				}
				for (int i = 0; i < height; i++) {
					for (int j = 0; j < width; j++) {
						dos.writeShort(LOD.get(j, i));
					}
				}
			}
			//pad to 4
			while (dos.size() % 4 != 0) {
				dos.write(0);
			}
			dos.close();
			return out.toByteArray();
		} catch (IOException ex) {
			//never the bytes it was read from: those are not the matrix being saved (F080)
			throw new IllegalStateException("the map matrix grid could not be assembled: " + ctrmap.util.Bytes.reason(ex), ex);
		}
	}

	public byte[] assembleCamData() {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			LittleEndianDataOutputStream dos = new LittleEndianDataOutputStream(out);
			boundaryEntries = cambounds.size();
			dos.writeInt(boundaryEntries);
			for (int i = 0; i < boundaryEntries; i++) {
				cambounds.get(i).write(dos);
			}
			dos.close();
			byte[] written = out.toByteArray();
			if (camSection == null || camSection.length <= written.length) {
				return written;
			}
			byte[] kept = camSection.clone();
			System.arraycopy(written, 0, kept, 0, written.length);
			return kept;
		} catch (IOException ex) {
			throw new IllegalStateException("the map matrix camera bounds could not be assembled: "
					+ ctrmap.util.Bytes.reason(ex), ex);
		}
	}

	public void write() {
		file.storeFile(0, assembleData());
		camSection = assembleCamData();
		file.storeFile(1, camSection);
	}

	/**
	 * Puts the grid, the zone layer, the LOD and the camera bounds back to what the file holds, IN
	 * PLACE (F106): the map view, the matrix editor and the prop form all hold this one matrix, so a
	 * new one would be put back for one of them only. A cell whose region is still open keeps it; one
	 * the edit had dropped, or that names another region now, is opened again from its game.
	 *
	 * @throws IllegalStateException when the file no longer reads; nothing here is changed then
	 */
	public void discard() {
		MapMatrix saved = new MapMatrix(file, null);
		ResizeableMatrix<GR> back = new ResizeableMatrix<>(saved.width, saved.height, null);
		for (int i = 0; i < saved.height; i++) {
			for (int j = 0; j < saved.width; j++) {
				short id = saved.ids.get(j, i);
				if (id == -1 || files == null) {
					continue;
				}
				java.io.File want = files.staged(ArchiveType.FIELD_DATA, id);
				GR open = j < regions.getWidth() && i < regions.getHeight() ? regions.get(j, i) : null;
				back.set(j, i, open != null && want != null && want.equals(open.getOriginFile()) ? open : new GR(want, files));
			}
		}
		hasLOD = saved.hasLOD;
		unknown = saved.unknown;
		width = saved.width;
		height = saved.height;
		ids = saved.ids;
		zones = saved.zones;
		LOD = saved.LOD;
		regions = back;
		cambounds.clear();
		cambounds.addAll(saved.cambounds);
		boundaryEntries = saved.boundaryEntries;
		camSection = saved.camSection;
	}

	/**
	 * The region grid of a raw matrix container - width, height, each cell's region id, and where
	 * the zone-switch layer starts - read in ONE place (F118). It was parsed by hand wherever it was
	 * wanted: "sub0 + 8 + k * 2" fourteen times in eight files, some checking that the grid fits its
	 * subfile and some not.
	 */
	public static final class Grid {

		public final int width;
		public final int height;
		/** A zone-switch layer, (width*4)x(height*4) u16 zone ids, follows the region ids. */
		public final boolean hasZoneLayer;
		private final byte[] mm;
		private final int ids;

		private Grid(byte[] mm, int sub0) {
			this.mm = mm;
			this.ids = sub0 + 8;
			this.width = u16(mm, sub0 + 4);
			this.height = u16(mm, sub0 + 6);
			this.hasZoneLayer = u16(mm, sub0) == 1;
		}

		/**
		 * The grid of {@code mmContainer}.
		 *
		 * @throws IllegalArgumentException naming what does not fit
		 */
		public static Grid of(byte[] mmContainer) {
			if (mmContainer == null || mmContainer.length < 12) {
				throw new IllegalArgumentException("Map matrix container too short.");
			}
			int sub0 = i32(mmContainer, 4);
			if (sub0 < 0 || sub0 + 8 > mmContainer.length) {
				throw new IllegalArgumentException("Map matrix grid subfile out of range.");
			}
			Grid g = new Grid(mmContainer, sub0);
			long cells = (long) g.width * g.height;
			if (g.width <= 0 || g.height <= 0 || cells > 4096 || g.ids + cells * 2 > mmContainer.length) {
				throw new IllegalArgumentException("Map matrix grid " + g.width + "x" + g.height + " does not fit its subfile.");
			}
			return g;
		}

		public int cells() {
			return width * height;
		}

		/**
		 * The first cell, in reading order, that holds a region - the map's top-left corner - or -1
		 * when every cell is empty. One answer to "which region does this map start with" (F248): it
		 * was four loops, two of them parsing the grid by hand.
		 */
		public int firstFilledCell() {
			for (int k = 0; k < cells(); k++) {
				if (id(k) != 0xFFFF) {
					return k;
				}
			}
			return -1;
		}

		/** Cell {@code k}'s region id, in reading order; 0xFFFF for an empty cell. */
		public int id(int k) {
			return u16(mm, idOffset(k));
		}

		public int id(int col, int row) {
			return id(row * width + col);
		}

		/** Where cell {@code k}'s id sits in the container: for a writer rewriting ids in a copy of it. */
		public int idOffset(int k) {
			return ids + k * 2;
		}

		/** Where the zone-switch layer starts in the container, or -1 when there is none. */
		public int zoneLayerOffset() {
			return hasZoneLayer ? ids + cells() * 2 : -1;
		}
	}

	public static class ResizeableMatrix<T> {

		public ArrayList<ArrayList<T>> list = new ArrayList<>();
		private T defaultValue;

		public ResizeableMatrix(int width, int height, T defaultValue) {
			this.defaultValue = defaultValue;
			ensureSize(list, width, false);
			for (int i = 0; i < width; i++) {
				ensureSize(list.get(i), height, true);
			}
		}

		public int getWidth() {
			return list.size();
		}

		public int getHeight() {
			if (list.size() > 0) {
				return list.get(0).size();
			} else {
				return 0;
			}
		}

		private void ensureSize(ArrayList l, int size, boolean isDefault) {
			for (int i = l.size(); i < size; i++) {
				l.add((isDefault) ? defaultValue : new ArrayList<T>());
			}
		}

		public void set(int x, int y, T value) {
			list.get(x).set(y, value);
		}

		public T get(int x, int y) {
			return list.get(x).get(y);
		}

		public void addColumn() {
			addColumns(1);
		}

		public void addColumns(int count) {
			int origHeight = (list.size() > 0) ? list.get(0).size() : 0;
			for (int i = 0; i < count; i++) {
				list.add(new ArrayList<>());
				ensureSize(list.get(list.size() - 1), origHeight, true);
			}
		}

		public void addRow() {
			addRows(1);
		}

		public void addRows(int count) {
			int origHeight = (list.size() > 0) ? list.get(0).size() : 0;
			for (int i = 0; i < list.size(); i++) {
				ensureSize(list.get(i), origHeight + count, true);
			}
		}

		public void removeColumn() {
			removeColumns(1);
		}

		public void removeColumns(int count) {
			if (count < 0 || count > list.size()) {
				throw new IllegalArgumentException("cannot remove " + count + " column(s) of " + list.size());
			}
			for (int i = 0; i < count; i++) {
				list.remove(list.size() - 1);
			}
		}

		public void removeRow() {
			removeRows(1);
		}

		public void removeRows(int count) {
			if (count < 0 || count > getHeight()) {
				throw new IllegalArgumentException("cannot remove " + count + " row(s) of " + getHeight());
			}
			for (int i = 0; i < list.size(); i++) {
				for (int j = 0; j < count; j++) {
					list.get(i).remove(list.get(i).size() - 1);
				}
			}
		}
	}
}

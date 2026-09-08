package ctrmap.formats.containers;

import ctrmap.Utils;
import ctrmap.formats.GameFiles;
import java.io.File;

/**
 * MapMatrix or MoveModel container class.
 * 
 * Contents/MapMatrix:
 * 0 - Matrix + Zone switches + LOD matrix
 * 1 - Camera repulsors
 * 
 * Contents/MoveModel:
 * 0 - Model
 * 1 - Pack of GfMotion animations
 */
public class MM extends AbstractGamefreakContainer{
	public static final int MM_MAP_MATRIX = 0;
	public static final int MM_MOVE_MODEL = 1;
	
	public int type;
	
	/** Opens a matrix or move-model container that reports its writes to {@code files}. */
	public MM(File f, GameFiles files) {
		super(f, files);
		type = sniffType();
	}

	/** Creates an empty container of {@code len} slots and the given type that reports its writes to {@code files}. */
	public MM(File f, int len, int type, GameFiles files) {
		super(f, len, files);
		this.type = type;
	}

	/** Transitional: see {@link AbstractGamefreakContainer}. */
	public MM(File f) {
		super(f);
		type = sniffType();
	}

	/** Transitional: see {@link AbstractGamefreakContainer}. */
	public MM(File f, int len, int type){
		super(f, len);
		this.type = type;
	}

	/** A move model's first subfile is a BCH model; a map matrix's is not. */
	private int sniffType() {
		if (Utils.checkBCHMagic(getFile(0))){
			return MM_MOVE_MODEL;
		}
		return MM_MAP_MATRIX;
	}
	
	@Override
	public short getHeader() {
		return 0x4D4D;
	}	

	@Override
	public ContentType getDefaultContentType(int index) {
		if (type == MM_MAP_MATRIX){
			if (index == 0){
				return ContentType.MAPMATRIX;
			}
			else if (index == 1){
				return ContentType.CAMERA_DATA_MM_EXTRA;
			}
		}
		else {
			if (index == 0){
				return ContentType.H3D_MODEL;
			}
			else {
				return ContentType.GF_MOTION;
			}
		}
		return ContentType.UNKNOWN;
	}

	@Override
	public boolean getIsPadded() {
		if (type == MM_MAP_MATRIX){
			return false;
		}
		else {
			return true;
		}
	}
}

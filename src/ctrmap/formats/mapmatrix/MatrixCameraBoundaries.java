/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ctrmap.formats.mapmatrix;

import ctrmap.util.LittleEndianDataInputStream;
import ctrmap.util.LittleEndianDataOutputStream;
import java.io.IOException;

public class MatrixCameraBoundaries {
	public int isRepeal;
	public float north;
	public float south;
	public float west;
	public float east;
	/** Reads one boundary; a stream that ends inside it throws (F080) rather than leaving zeros. */
	public MatrixCameraBoundaries(LittleEndianDataInputStream dis) throws IOException {
		north = dis.readFloat();
		south = dis.readFloat();
		west = dis.readFloat();
		east = dis.readFloat();
		isRepeal = dis.readInt();
	}
	
	public MatrixCameraBoundaries(){
		isRepeal = 1;
		north = 0f;
		south = 100f;
		west = 0f;
		east = 100f;
	}
	
	public void write(LittleEndianDataOutputStream dos) throws IOException{
		dos.writeFloat(north);
		dos.writeFloat(south);
		dos.writeFloat(west);
		dos.writeFloat(east);
		dos.writeInt(isRepeal);
	}
}

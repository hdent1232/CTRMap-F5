package ctrmap.formats.cameradata;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import ctrmap.LittleEndianDataInputStream;
import ctrmap.LittleEndianDataOutputStream;
import ctrmap.formats.containers.AD;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

/**
 * Parser and accessor for AD-3 Camera data.
 */
public class CameraDataFile {
	private AD file;
	
	public int numEntries;
	public ArrayList<CameraData> camData;
	
	public boolean modified = false;
	
	/**
	 * Reads the area's camera table, or refuses to be one.
	 *
	 * <p>IT USED TO CATCH THE PARSE FAILURE, print the stack trace to a console the user
	 * does not have, and finish: {@code numEntries} left holding the count the bytes
	 * CLAIMED, {@code camData} holding however many were read before they ran out - or
	 * null, when the failure came before the list existed. Nothing downstream could tell
	 * the difference, and there is a writer on the other side: {@link #write} puts
	 * {@code camData.size()} entries back over the area's real table, so a file this
	 * parser could not read became a file holding the cameras it happened to manage and
	 * no record of the rest. The editor would have reported a save.
	 *
	 * <p>The count is checked before anything is allocated, because it comes from the
	 * file: four bytes of something else read as a camera count is a number in the
	 * millions, and the loop would ask for that many before failing.
	 */
	public CameraDataFile(AD ad) {
		file = ad;
		byte[] input = ad.getFile(3);
		if (input == null) {
			throw new IllegalStateException("the area holds no camera table (subfile 3 is empty): "
				+ name());
		}
		try {
			LittleEndianDataInputStream dis = new LittleEndianDataInputStream(new ByteArrayInputStream(input));
			numEntries = dis.readInt();
			if (numEntries < 0 || numEntries > input.length) {
				throw new IOException("it declares " + numEntries + " cameras in " + input.length
					+ " bytes, which cannot be right");
			}
			camData = new ArrayList<>();
			for (int i = 0; i < numEntries; i++) {
				camData.add(new CameraData(dis));
			}
			dis.close();
		} catch (IOException notATable) {
			//REFUSED, not logged: see the javadoc. The message names the area file, because
			//"could not read the cameras" with nothing after it is what the console used to
			//get, on a machine whose user never sees one.
			throw new IllegalStateException("the camera table in " + name() + " could not be read"
				+ " (read " + (camData == null ? 0 : camData.size()) + " of the " + numEntries
				+ " it declares): " + ctrmap.util.Bytes.reason(notATable), notATable);
		}
	}

	/** The area file this table came out of, for a message. */
	private String name() {
		return file == null || file.getOriginFile() == null ? "an unnamed area"
			: file.getOriginFile().getName();
	}
	public void write(){
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			LittleEndianDataOutputStream dos = new LittleEndianDataOutputStream(out);
			dos.writeInt(camData.size());
			for (CameraData d : camData){
				d.write(dos);
			}
			file.storeFile(3, out.toByteArray());
		} catch (IOException cannotAssemble) {
			//THE SAVE EITHER HAPPENS OR SAYS IT DID NOT. This logged and returned, one
			//statement away from the editor telling the user the cameras were saved. The
			//stream is a ByteArrayOutputStream, so the only failures that reach here are a
			//record refusing to serialise - which is exactly what must not be swallowed.
			throw new IllegalStateException("the camera table for " + name() + " could not be"
				+ " assembled, so nothing was written: " + ctrmap.util.Bytes.reason(cannotAssemble),
				cannotAssemble);
		}
	}
}

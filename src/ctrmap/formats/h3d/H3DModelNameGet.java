
package ctrmap.formats.h3d;

import ctrmap.util.LittleEndian;

/**
 * Reads the name of a BCH container's first model, by following the content header's model
 * pointer table to that model's name offset in the string table, based on VisualBasic Ohana3DS
 * source. Ohana3DS-Rebirth was also used for docs, but it relocates the offsets by the reloc
 * table, which reading one name does not need.
 *
 * <p>EVERY OFFSET IS CHECKED AGAINST THE FILE (F069). This read the name with a loop that stopped
 * only at a NUL - and at the end of the stream read() answers -1, which is not 0, so a name, or a
 * pointer, running off the end appended U+FFFF until the program ran out of memory. That is an
 * Error, which the callers' catch blocks do not catch, and the offsets it followed were not
 * checked at all. Anything that does not lead to a whole name inside the file is refused, in
 * words.
 */
public class H3DModelNameGet {

	/**
	 * The first model's name.
	 *
	 * @throws IllegalStateException naming what is wrong, when {@code bchFile} is not a BCH or its
	 * pointers do not lead to a whole name inside it
	 */
	public static String H3DModelNameGet(byte[] bchFile) {
		BchSections s;
		try {
			s = BchSections.read(bchFile); //the one header reader, and the one refusal of what is not a BCH (F066)
		} catch (IllegalArgumentException notWhole) {
			throw new IllegalStateException("A model's name cannot be read from this (" + (bchFile == null ? "absent"
					: bchFile.length + " byte(s)") + "): " + ctrmap.util.Bytes.reason(notWhole), notWhole);
		}
		int contentHeaderOffset = s.contentsAddr;
		int stringTableOffset = s.stringsAddr;
		int modelsPointerTableOffset = at(bchFile, contentHeaderOffset, "the content header") + contentHeaderOffset; //relocate
		int model0offset = at(bchFile, modelsPointerTableOffset, "the model pointer table") + contentHeaderOffset; //relocate again
		int stroff = at(bchFile, model0offset + 0x84, "the first model's name field");
		int name = stroff + stringTableOffset;
		try {
			return StringUtils.cString(bchFile, name);
		} catch (IllegalArgumentException runsOff) {
			throw new IllegalStateException("A model's name cannot be read: " + ctrmap.util.Bytes.reason(runsOff), runsOff);
		}
	}

	/** The word at {@code offset}, refused in words when it is not inside the file. */
	private static int at(byte[] b, int offset, String what) {
		if (offset < 0 || offset > b.length - 4) {
			throw new IllegalStateException("A model's name cannot be read: " + what + " is at byte " + offset
					+ ", outside the " + b.length + "-byte BCH.");
		}
		return LittleEndian.i32(b, offset);
	}
}

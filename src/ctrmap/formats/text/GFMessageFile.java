package ctrmap.formats.text;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Gen 6/7 GameFreak text file codec (read AND write), ported from pk3DS' TextFile.cs.
 * The bracket/escape syntax matches pk3DS exactly so that text round-trips losslessly:
 * parse(write(x)) equals x (modulo trim) and write(parse(b)) equals b for any b write() produced.
 *
 * Note: unlike the legacy read-only TextFile, this codec does NOT remap the private use area
 * characters by default so that files re-encode byte-identically. Pass remapChars = true to
 * the overloads to translate 0xE07F/0xE08D/0xE08E/0xE08F to their Unicode equivalents.
 */
public class GFMessageFile {

	private static final int KEY_BASE = 0x7C89;
	private static final int KEY_ADVANCE = 0x2983;
	private static final int KEY_VARIABLE = 0x0010;
	private static final int KEY_TERMINATOR = 0x0000;
	private static final int KEY_TEXTRETURN = 0xBE00;
	private static final int KEY_TEXTCLEAR = 0xBE01;
	private static final int KEY_TEXTWAIT = 0xBE02;
	private static final int KEY_TEXTNULL = 0xBDFF;

	//variable code table, identical for XY/ORAS/SM in pk3DS (TextVariableCode.cs)
	private static final int[] VAR_CODES = {
		0xFF00, 0x0100, 0x0101, 0x0102, 0x0103, 0x0105, 0x0106, 0x0107,
		0x0108, 0x0109, 0x010A, 0x010B, 0x010D, 0x0110, 0x0127, 0x0134,
		0x013E, 0x0189, 0x018A, 0x018B, 0x018E, 0x018F, 0x0190, 0x0191,
		0x019C, 0x01A1, 0x0192, 0x0193, 0x0195, 0x0199, 0x019A, 0x019B,
		0x019F, 0x1000, 0x1001, 0x1100, 0x1101, 0x1302, 0x1303, 0x0200,
		0x0201, 0x0202, 0x0203, 0x0204, 0x0205, 0x0206, 0x0207, 0x0208
	};
	private static final String[] VAR_NAMES = {
		"COLOR", "TRNAME", "PKNAME", "PKNICK", "TYPE", "LOCATION", "ABILITY", "MOVE",
		"ITEM1", "ITEM2", "sTRBAG", "BOX", "EVSTAT", "OPOWER", "RIBBON", "MIINAME",
		"WEATHER", "TRNICK", "1stchrTR", "SHOUTOUT", "BERRY", "REMFEEL", "REMQUAL", "WEBSITE",
		"CHOICECOS", "GSYNCID", "PRVIDSAY", "BTLTEST", "GENLOC", "CHOICEFOOD", "HOTELITEM", "TAXISTOP",
		"MAISTITLE", "ITEMPLUR0", "ITEMPLUR1", "GENDBR", "NUMBRNCH", "iCOLOR2", "iCOLOR3", "NUM1",
		"NUM2", "NUM3", "NUM4", "NUM5", "NUM6", "NUM7", "NUM8", "NUM9"
	};

	private final List<String> lines;
	private final List<Integer> lineExtras;
	private final List<Integer> linePads;
	private final boolean instanceRemapChars;

	/**
	 * Opens an existing message file for editing. Unlike the static methods, an instance retains the
	 * per-line extra field and any zero padding stored after a line's terminator, so re-writing an
	 * unmodified file reproduces the original bytes exactly.
	 */
	public GFMessageFile(byte[] data) {
		this(data, false);
	}

	public GFMessageFile(byte[] data, boolean remapChars) {
		instanceRemapChars = remapChars;
		lines = parse(data, remapChars);
		lineExtras = readLineExtras(data);
		linePads = readLinePads(data, lines, remapChars);
	}

	public int getLineCount() {
		return lines.size();
	}

	public String getLine(int index) {
		return lines.get(index);
	}

	public List<String> getLines() {
		return new ArrayList<>(lines);
	}

	public void setLine(int index, String text) {
		lines.set(index, text);
	}

	public void addLine(String text) {
		lines.add(text);
		lineExtras.add(0);
		linePads.add(0);
	}

	public void removeLine(int index) {
		lines.remove(index);
		lineExtras.remove(index);
		linePads.remove(index);
	}

	/**
	 * Replaces all lines. Per-line extra values are kept for indices that still exist.
	 */
	public void setLines(List<String> newLines) {
		lines.clear();
		lines.addAll(newLines);
		resize(lineExtras, lines.size());
		resize(linePads, lines.size());
	}

	private static void resize(List<Integer> list, int size) {
		while (list.size() > size) {
			list.remove(list.size() - 1);
		}
		while (list.size() < size) {
			list.add(0);
		}
	}

	public byte[] write() {
		return write(lines, instanceRemapChars, toArray(lineExtras), toArray(linePads));
	}

	private static int[] toArray(List<Integer> list) {
		int[] out = new int[list.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = list.get(i);
		}
		return out;
	}

	private static List<Integer> readLineExtras(byte[] data) {
		int lineCount = readU16(data, 0x02);
		int sdo = (int) readU32(data, 0x0C);
		List<Integer> extras = new ArrayList<>(lineCount);
		for (int i = 0; i < lineCount; i++) {
			extras.add(readU16(data, sdo + 10 + i * 8));
		}
		return extras;
	}

	/**
	 * Number of zero u16s stored after each line's terminator. Some files (e.g. fixed-slot name
	 * tables) pad every line out to a constant size; the game stops at the terminator, but keeping
	 * the padding means an untouched file re-writes to identical bytes.
	 */
	private static List<Integer> readLinePads(byte[] data, List<String> parsedLines, boolean remapChars) {
		int lineCount = readU16(data, 0x02);
		int sdo = (int) readU32(data, 0x0C);
		List<Integer> pads = new ArrayList<>(lineCount);
		for (int i = 0; i < lineCount; i++) {
			int length = readU16(data, sdo + 8 + i * 8);
			int pad = 0;
			try {
				//the canonical encoding ends at the terminator; anything the file declares beyond
				//that is stored padding. Deriving it this way keeps it exactly symmetric with write().
				int canonical = getLineData(parsedLines.get(i), remapChars).length / 2;
				pad = Math.max(0, length - canonical);
			} catch (RuntimeException ex) {
				pad = 0;
			}
			pads.add(pad);
		}
		return pads;
	}

	public static List<String> parse(byte[] data) {
		return parse(data, false);
	}

	public static List<String> parse(byte[] data, boolean remapChars) {
		if (data == null || data.length < 0x14) {
			throw new IllegalArgumentException("Invalid Text File");
		}
		int textSections = readU16(data, 0x00);
		int lineCount = readU16(data, 0x02);
		long totalLength = readU32(data, 0x04);
		long initialKey = readU32(data, 0x08);
		long sectionDataOffset = readU32(data, 0x0C);
		if (initialKey != 0) {
			throw new IllegalArgumentException("Invalid initial key! Not 0?");
		}
		if (sectionDataOffset + totalLength != data.length || textSections != 1) {
			throw new IllegalArgumentException("Invalid Text File");
		}
		int sdo = (int) sectionDataOffset;
		long sectionLength = readU32(data, sdo);
		if (sectionLength != totalLength) {
			throw new IllegalArgumentException("Section size and overall size do not match.");
		}
		List<String> lines = new ArrayList<>();
		int key = KEY_BASE;
		for (int i = 0; i < lineCount; i++) {
			int offset = readS32(data, sdo + 4 + i * 8) + sdo;
			int length = readU16(data, sdo + 8 + i * 8); //u16 read, the two bytes after it are always 0
			if (offset < sdo || offset + length * 2 > data.length) {
				throw new IllegalArgumentException("Line data out of bounds");
			}
			byte[] enc = Arrays.copyOfRange(data, offset, offset + length * 2);
			byte[] dec = cryptLineData(enc, key);
			lines.add(getLineString(dec, remapChars));
			key = (key + KEY_ADVANCE) & 0xFFFF;
		}
		return lines;
	}

	public static List<String> getStrings(byte[] data) {
		return getStrings(data, false);
	}

	public static List<String> getStrings(byte[] data, boolean remapChars) {
		try {
			return parse(data, remapChars);
		} catch (Exception ex) {
			return new ArrayList<>();
		}
	}

	public static byte[] write(List<String> lines) {
		return write(lines, false);
	}

	/**
	 * Writes a message file with no per-line metadata. Prefer the instance API when editing an
	 * existing file - it preserves the per-line extra field that the games do use (see lineExtras).
	 */
	public static byte[] write(List<String> lines, boolean remapChars) {
		return write(lines, remapChars, null, null);
	}

	private static byte[] write(List<String> lines, boolean remapChars, int[] lineExtras, int[] linePads) {
		int n = (lines == null) ? 0 : lines.size();
		if (n > 0xFFFF) {
			throw new IllegalArgumentException("Too many lines: " + n);
		}
		int key = KEY_BASE;
		byte[][] enc = new byte[n][];
		int[] lengths = new int[n]; //u16 line lengths WITHOUT the alignment padding, as the games store them
		int bytesUsed = 0;
		for (int i = 0; i < n; i++) {
			//text is written verbatim - pk3DS trims each line here, which destroys meaningful
			//trailing spaces present in the real game files
			String text = (lines.get(i) == null) ? "" : lines.get(i);
			byte[] dec = getLineData(text, remapChars);
			int pad = (linePads == null || i >= linePads.length) ? 0 : linePads[i];
			if (pad > 0) {
				dec = Arrays.copyOf(dec, dec.length + pad * 2); //zero u16s stored after the terminator
			}
			enc[i] = cryptLineData(dec, key);
			lengths[i] = enc[i].length / 2;
			if (lengths[i] > 0xFFFF) {
				throw new IllegalArgumentException("Line " + i + " too long to store (u16 length field): " + lengths[i] + " code units");
			}
			if (enc[i].length % 4 == 2) {
				enc[i] = Arrays.copyOf(enc[i], enc[i].length + 2); //data is padded to 4 bytes, but the padding is NOT counted in the length field
			}
			bytesUsed += enc[i].length;
			key = (key + KEY_ADVANCE) & 0xFFFF;
		}
		int total = 0x10 + 4 + 8 * n + bytesUsed;
		byte[] out = new byte[total];
		writeU16(out, 0x00, 1); //textSections
		writeU16(out, 0x02, n); //lineCount
		writeS32(out, 0x04, total - 0x10); //totalLength
		writeS32(out, 0x08, 0); //initialKey
		writeS32(out, 0x0C, 0x10); //sectionDataOffset
		writeS32(out, 0x10, total - 0x10); //sectionLength
		int rel = 4 + 8 * n;
		for (int i = 0; i < n; i++) {
			writeS32(out, 0x10 + 4 + i * 8, rel);
			writeU16(out, 0x10 + 8 + i * 8, lengths[i]);
			writeU16(out, 0x10 + 10 + i * 8, (lineExtras == null || i >= lineExtras.length) ? 0 : lineExtras[i]);
			System.arraycopy(enc[i], 0, out, 0x10 + rel, enc[i].length);
			rel += enc[i].length;
		}
		return out;
	}

	private static byte[] cryptLineData(byte[] data, int key) {
		byte[] result = new byte[data.length];
		for (int i = 0; i < result.length; i += 2) {
			int v = (data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8);
			v ^= key;
			result[i] = (byte) (v & 0xFF);
			result[i + 1] = (byte) ((v >>> 8) & 0xFF);
			key = ((key << 3) | (key >>> 13)) & 0xFFFF; //16-bit ROL3, key is always kept 16-bit clean
		}
		return result;
	}

	private static String getLineString(byte[] data, boolean remapChars) {
		StringBuilder s = new StringBuilder();
		int[] i = new int[]{0};
		while (i[0] + 1 < data.length) {
			int val = readU16(data, i[0]);
			if (val == KEY_TERMINATOR) {
				break;
			}
			i[0] += 2;
			switch (val) {
				case KEY_VARIABLE:
					s.append(getVariableString(data, i));
					break;
				case '\n':
					s.append("\\n");
					break;
				case '\\':
					s.append("\\\\");
					break;
				case '[':
					s.append("\\[");
					break;
				default:
					s.append((char) tryUnmapChar(val, remapChars));
					break;
			}
		}
		return s.toString();
	}

	private static String getVariableString(byte[] data, int[] i) {
		StringBuilder s = new StringBuilder();
		int count = readU16(data, i[0]);
		i[0] += 2;
		int variable = readU16(data, i[0]);
		i[0] += 2;
		switch (variable) {
			case KEY_TEXTRETURN: //wait for button, then scroll
				return "\\r";
			case KEY_TEXTCLEAR: //wait for button, then clear
				return "\\c";
			case KEY_TEXTWAIT: {
				int time = readU16(data, i[0]);
				i[0] += 2;
				return "[WAIT " + time + "]";
			}
			case KEY_TEXTNULL: {
				int line = readU16(data, i[0]);
				i[0] += 2;
				return "[~ " + line + "]";
			}
		}
		String varName = getVariableName(variable);
		if (varName == null) {
			varName = String.format("%04X", variable);
		}
		s.append("[VAR ").append(varName);
		if (count > 1) {
			s.append('(');
			while (count > 1) {
				int arg = readU16(data, i[0]);
				i[0] += 2;
				s.append(String.format("%04X", arg));
				if (--count == 1) {
					break;
				}
				s.append(',');
			}
			s.append(')');
		}
		s.append(']');
		return s.toString();
	}

	private static byte[] getLineData(String line, boolean remapChars) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int i = 0;
		while (i < line.length()) {
			int val = line.charAt(i++); //char is already an unsigned 16-bit value
			val = tryRemapChar(val, remapChars);
			switch (val) {
				case '[': {
					int bracket = line.indexOf(']', i);
					if (bracket < 0) {
						throw new IllegalArgumentException("Variable text is not capped properly: " + line);
					}
					String varText = line.substring(i, bracket);
					for (int v : getVariableValues(varText)) {
						writeU16(out, v);
					}
					i += 1 + varText.length();
					break;
				}
				case '\\': {
					if (i >= line.length()) {
						throw new IllegalArgumentException("Invalid terminated line: \\");
					}
					for (int v : getEscapeValues(line.charAt(i++))) {
						writeU16(out, v);
					}
					break;
				}
				default:
					if (val == KEY_TERMINATOR || val == KEY_VARIABLE) {
						//raw occurrences of these collide with codec control values and would make the line unreadable
						throw new IllegalArgumentException("Line contains reserved raw character 0x" + Integer.toHexString(val) + ": " + line);
					}
					writeU16(out, val);
					break;
			}
		}
		writeU16(out, KEY_TERMINATOR); //cap the line off
		return out.toByteArray();
	}

	private static int[] getEscapeValues(char esc) {
		switch (esc) {
			case 'n':
				return new int[]{'\n'};
			case '\\':
				return new int[]{'\\'};
			case '[':
				return new int[]{'['};
			case 'r':
				return new int[]{KEY_VARIABLE, 1, KEY_TEXTRETURN};
			case 'c':
				return new int[]{KEY_VARIABLE, 1, KEY_TEXTCLEAR};
			default:
				throw new IllegalArgumentException("Invalid terminated line: \\" + esc);
		}
	}

	private static List<Integer> getVariableValues(String variable) {
		String[] split = variable.split(" ", -1);
		if (split.length < 2) {
			throw new IllegalArgumentException("Incorrectly formatted variable text: " + variable);
		}
		List<Integer> vals = new ArrayList<>();
		vals.add(KEY_VARIABLE);
		switch (split[0]) {
			//the count is 1 (the code itself) + the number of arguments, so these one-argument
			//codes store 2 - verified against real ORAS text, pk3DS writes 1 here and does not match
			case "~": //blank text line marker
				vals.add(2);
				vals.add(KEY_TEXTNULL);
				vals.add(parseU16Dec(split[1]));
				break;
			case "WAIT": //dramatic pause
				vals.add(2);
				vals.add(KEY_TEXTWAIT);
				vals.add(parseU16Dec(split[1]));
				break;
			case "VAR": //text variable
				vals.addAll(getVariableParameters(split[1]));
				break;
			default:
				throw new IllegalArgumentException("Unknown variable method type: " + variable);
		}
		return vals;
	}

	private static List<Integer> getVariableParameters(String text) {
		List<Integer> vals = new ArrayList<>();
		int bracket = text.indexOf('(');
		if (bracket < 0) {
			vals.add(1);
			vals.add(getVariableNumber(text));
		} else {
			String name = text.substring(0, bracket);
			String[] args = text.substring(bracket + 1, text.length() - 1).split(",", -1);
			vals.add((1 + args.length) & 0xFFFF);
			vals.add(getVariableNumber(name));
			for (String arg : args) {
				vals.add(parseU16Hex(arg));
			}
		}
		return vals;
	}

	private static int getVariableNumber(String name) {
		Integer code = getVariableCode(name);
		if (code != null) {
			return code;
		}
		try {
			return parseU16Hex(name);
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException("Variable parse error: " + name);
		}
	}

	public static String getVariableName(int code) {
		for (int i = 0; i < VAR_CODES.length; i++) {
			if (VAR_CODES[i] == code) {
				return VAR_NAMES[i];
			}
		}
		return null;
	}

	public static Integer getVariableCode(String name) {
		for (int i = 0; i < VAR_NAMES.length; i++) {
			if (VAR_NAMES[i].equals(name)) {
				return VAR_CODES[i];
			}
		}
		return null;
	}

	private static int tryRemapChar(int val, boolean remapChars) {
		if (!remapChars) {
			return val;
		}
		switch (val) {
			case 0x202F:
				return 0xE07F; //nbsp
			case 0x2026:
				return 0xE08D; //ellipsis
			case 0x2642:
				return 0xE08E; //male
			case 0x2640:
				return 0xE08F; //female
			default:
				return val;
		}
	}

	private static int tryUnmapChar(int val, boolean remapChars) {
		if (!remapChars) {
			return val;
		}
		switch (val) {
			case 0xE07F:
				return 0x202F;
			case 0xE08D:
				return 0x2026;
			case 0xE08E:
				return 0x2642;
			case 0xE08F:
				return 0x2640;
			default:
				return val;
		}
	}

	private static int parseU16Dec(String s) {
		int v = Integer.parseInt(s);
		if (v < 0 || v > 0xFFFF) {
			throw new NumberFormatException("Value out of u16 range: " + s);
		}
		return v;
	}

	private static int parseU16Hex(String s) {
		int v = Integer.parseInt(s, 16);
		if (v < 0 || v > 0xFFFF) {
			throw new NumberFormatException("Value out of u16 range: " + s);
		}
		return v;
	}

	private static int readU16(byte[] data, int off) {
		return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
	}

	private static int readS32(byte[] data, int off) {
		return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8) | ((data[off + 2] & 0xFF) << 16) | ((data[off + 3] & 0xFF) << 24);
	}

	private static long readU32(byte[] data, int off) {
		return readS32(data, off) & 0xFFFFFFFFL;
	}

	private static void writeU16(byte[] data, int off, int val) {
		data[off] = (byte) (val & 0xFF);
		data[off + 1] = (byte) ((val >>> 8) & 0xFF);
	}

	private static void writeS32(byte[] data, int off, int val) {
		data[off] = (byte) (val & 0xFF);
		data[off + 1] = (byte) ((val >>> 8) & 0xFF);
		data[off + 2] = (byte) ((val >>> 16) & 0xFF);
		data[off + 3] = (byte) ((val >>> 24) & 0xFF);
	}

	private static void writeU16(ByteArrayOutputStream out, int val) {
		out.write(val & 0xFF);
		out.write((val >>> 8) & 0xFF);
	}
}

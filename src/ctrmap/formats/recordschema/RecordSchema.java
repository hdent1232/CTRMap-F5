package ctrmap.formats.recordschema;

import ctrmap.gamedef.ArchiveType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One fixed-stride record array in an archive, one record per entry, described
 * as data instead of as a class.
 *
 * <p>WHY THIS EXISTS. Many structures the ORAS investigations turned up have the
 * same shape - N records of a fixed size, indexed by position: items are 776 x
 * 0x24 in a GARC, species 826 x 0x50 in another. Describing such a discovery
 * means the next one is a few lines in {@link SchemaRegistry} and inherits an
 * editor that already works. How far that went, and why it stopped at the item
 * record, is docs/items-plan.md's "Where the registry stopped" (F252): the
 * other record editors' records are not this shape, or need what the generic
 * form does not have.
 *
 * <p>WHAT A SCHEMA DELIBERATELY DOES NOT PROMISE. It says where the bytes are
 * and what they are called. It does NOT say what the engine will do with them.
 * That distinction is the whole point for items: the hold-effect byte selects
 * one of 183 behaviours that already exist in code, and no schema, editor or
 * patch can add a 184th - some behaviour is not in the record at all but keyed
 * on the item id at scattered code sites.
 *
 * <p>Only archive data. A table in the executable was described here too - the
 * item icons - and nothing rendered it: the item editor's icon panel reads
 * {@link ctrmap.formats.codepatch.ItemIconTable}, which refuses a build whose
 * stock bytes do not match. That description, and the tier it alone used,
 * went in F252.
 */
public final class RecordSchema {

	private final String name;
	private final String about;
	private final ArchiveType archive;
	private final int recordCount;
	private final int stride;
	private final List<RecordField> fields;

	private RecordSchema(String name, String about, ArchiveType archive, int recordCount, int stride,
			List<RecordField> fields) {
		this.name = name;
		this.about = about;
		this.archive = archive;
		this.recordCount = recordCount;
		this.stride = stride;
		this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
		Set<String> keys = new LinkedHashSet<>();
		for (RecordField f : fields) {
			if (!keys.add(f.key())) {
				throw new IllegalArgumentException(name + ": two fields answer to \"" + f.key()
						+ "\". Names may repeat between groups - an item has an Attack stat stage"
						+ " and an Attack EV - but nothing can ADDRESS a field whose key is shared,"
						+ " so a control would be wired to whichever came first.");
			}
			if (f.endByte() > stride) {
				throw new IllegalArgumentException(name + ": field " + f
						+ " runs past the end of a " + stride + "-byte record. A field that"
						+ " overruns its record reads the NEXT record's bytes and still looks"
						+ " like data.");
			}
		}
	}

	/**
	 * A schema over records stored one per entry in a GARC.
	 *
	 * <p>{@code recordCount} is -1 when the archive itself decides - the item
	 * table is 776 entries in this dump and a caller must read the archive
	 * rather than trust the number, because a count written here is a claim
	 * about somebody else's file.
	 */
	public static RecordSchema archive(String name, String about, ArchiveType archive,
			int recordCount, int stride, List<RecordField> fields) {
		if (archive == null) {
			throw new IllegalArgumentException(name + ": a schema describes records in an archive, and names none");
		}
		return new RecordSchema(name, about, archive, recordCount, stride, fields);
	}

	@ctrmap.util.ForSuites("SEAM: a suite reads a schema's name back, to see the registry hands the schema it was asked for")
	public String name() {
		return name;
	}

	/** One paragraph saying what this is and, more importantly, what it is not. */
	@ctrmap.util.ForSuites("CAPABILITY: the schema's paragraph of what its records are and are not; the record editor would show it above its fields, and does not yet")
	public String about() {
		return about;
	}

	/** The archive holding the records, one per entry. */
	@ctrmap.util.ForSuites("SEAM: a suite reads which archive a schema describes, to hold the schema to the records it reads")
	public ArchiveType archive() {
		return archive;
	}

	public int stride() {
		return stride;
	}

	@ctrmap.util.ForSuites("SEAM: a suite reads the schema's fields flat, to hold each to the record bytes; the editor lays them out by group")
	public List<RecordField> fields() {
		return fields;
	}

	/** The field groups in the order they were declared, for laying out a form. */
	public List<String> groups() {
		Set<String> seen = new LinkedHashSet<>();
		for (RecordField f : fields) {
			seen.add(f.group());
		}
		return new ArrayList<>(seen);
	}

	public List<RecordField> fieldsIn(String group) {
		List<RecordField> out = new ArrayList<>();
		for (RecordField f : fields) {
			if (f.group().equals(group)) {
				out.add(f);
			}
		}
		return out;
	}

	/**
	 * Bytes of the record no field describes.
	 *
	 * <p>Reported rather than hidden. An unmapped byte is a hole in the
	 * understanding of the format, and a form that silently omits it lets a user
	 * believe they are looking at the whole record. Two of the item record's
	 * bytes (0x22 and 0x23) are zero in all 776 retail records and nobody knows
	 * what they are for; that is a fact worth showing.
	 */
	public List<Integer> unmappedBytes() {
		boolean[] covered = new boolean[stride];
		for (RecordField f : fields) {
			for (int i = f.byteOffset(); i < f.endByte(); i++) {
				covered[i] = true;
			}
		}
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < stride; i++) {
			if (!covered[i]) {
				out.add(i);
			}
		}
		return out;
	}

	@Override
	public String toString() {
		return name + " [" + archive + "] " + (recordCount < 0 ? "?" : String.valueOf(recordCount))
				+ " x " + stride + " bytes, " + fields.size() + " field(s)";
	}
}

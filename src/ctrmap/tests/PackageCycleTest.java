package ctrmap.tests;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * No format package sits in a dependency cycle with the program above it (F214).
 *
 * <p>Measured from the bytecode on 2026-10-06, 32 of the 37 production packages
 * were one strongly connected component: every package but five depended,
 * transitively, on every other, so no byte format could be read, tested or
 * reasoned about without the window, the workspace and the widgets on the
 * classpath. The format layer reached up through four things - the two byte
 * streams and their helpers living in the root package beside the window,
 * {@code MapObject} declared in the widget package, the texture pack's
 * shared-area question asking the application's master-table reader, and JOGL
 * rendering inside the format classes. The first three are gone: the streams
 * and {@code LittleEndian} live in {@code ctrmap.util}, {@code MapObject} in
 * {@code ctrmap.formats}, and the texture pack asks the game it is handed.
 *
 * <p>Two rules over the package graph, computed here and not listed. A
 * strongly connected component that holds a {@code ctrmap.formats} package
 * holds nothing else - formats may lean on each other, never on what uses
 * them. And the largest component is the size recorded: a new edge that grows
 * a cycle, anywhere, is a number that moved.
 *
 * <p>And a third, one level down (F090): the format classes in a CLASS cycle with each other are
 * exactly the ones recorded, and the list may only shrink. The painter's build was split into seven
 * stage files whose stages took their frame and their quad type from the build that called them, so
 * the split was one cycle of nine classes however many files it was in - and no package rule sees a
 * cycle inside one package.
 *
 * <p>An edge is any class a production class names: a call, a field, a
 * {@code new}, a cast, an {@code implements}, a type in a signature
 * ({@link ClassFileScanner.ClassFile#refs} and
 * {@link ClassFileScanner.ClassFile#classes}) - an interface implemented with
 * no member touched is an edge, which is how {@code MapObject} held the format
 * layer to the widgets.
 *
 * <p>Not: {@link SourceSeamTest#noFormatClassReachesAboveItself} - that names
 * what a format class may not touch (the window, the widgets, the dialogs, the
 * global) and is blind to everything else above it; the master-table call that
 * closed the cycle named none of those. This measures the graph those edges
 * make.
 *
 * Usage: java ctrmap.tests.PackageCycleTest [classes-root]
 */
public class PackageCycleTest {

	/**
	 * The largest strongly connected component of production packages, measured by this suite
	 * on 2026-10-08 (F214) once the format layer stopped reaching up: the application's own
	 * packages, which call each other both ways. It was 32 of 37, then 8, then 7 once the bundled
	 * resources stopped reaching into the Workspace (F130). Lower it when it falls; never raise it.
	 */
	private static final int LARGEST_CYCLE = 7;

	/**
	 * The fourth way up, NOT closed by F214: format classes that draw themselves with OpenGL, or do
	 * their matrix maths with its library, so a byte format cannot be loaded without JOGL on the
	 * classpath. Measured 2026-10-08; the list may only shrink. Moving the drawing out means moving
	 * the GL buffers each mesh keeps, which nothing in this environment can draw to check.
	 */
	private static final String[] DRAWING_FORMATS = {
		"ctrmap/formats/CMVD",
		"ctrmap/formats/Triangle",
		"ctrmap/formats/gfcollision/GRCollisionFile",
		"ctrmap/formats/gfcollision/GRCollisionMesh",
		"ctrmap/formats/h3d/BCHFile",
		"ctrmap/formats/h3d/model/BCHModelHeader",
		"ctrmap/formats/h3d/model/H3DModel",
		"ctrmap/formats/h3d/model/H3DSkeleton",
		"ctrmap/formats/h3d/texturing/H3DMaterial",
	};

	/**
	 * The format classes in a class cycle with another format class, measured by this suite on
	 * 2026-10-08 (F090); the list may only shrink. The painter's stages and the build that called
	 * them were one cycle of nine until the frame and the types they share moved into a leaf; what
	 * is left is listed by cycle, with what is known about each.
	 */
	private static final String[] CYCLING_FORMAT_CLASSES = {
		//a cycle of 4, measured on 2026-10-08 and not examined: F090 was the painter's
		"H3DMaterial", "H3DTexture", "PICACommandReader", "TextureCodec",
		//a cycle of 2, measured on 2026-10-08 and not examined: F090 was the painter's
		"H3DModel", "OhanaMeshUtils",
		//the painter's material resolution and the terrain catalogue, examined under F090: the catalogue
		//measures a donor's real geometry with the resolver and asks it whether a map already has a brush's
		//material; the resolver asks the catalogue for the cliff donor, and for the scale of a material it
		//imported with its geometry blanked. Breaking it means the catalogue recording that scale in the
		//model it writes - a change to the game data, which nothing here can check.
		"PaintedMaterials", "TerrainCatalog",
		//a cycle of 5, measured on 2026-10-08 and not examined: F090 was the painter's
		"GFLPawnScript", "PawnAssembly", "PawnDisassembler", "PawnInstruction", "PawnSubroutine",
		//a cycle of 2, measured on 2026-10-08 and not examined: F090 was the painter's
		"NpcTemplates", "TalkerScriptWizard",
	};

	private static final String FORMATS = "ctrmap/formats";

	static int fails = 0;

	static void check(boolean cond, String what) {
		System.out.println((cond ? "  ok: " : "  FAIL: ") + what);
		if (!cond) {
			fails++;
		}
	}

	public static void main(String[] args) throws Exception {
		File classes = new File(args.length > 0 ? args[0] : "build/classes");
		System.out.println("--- no format package is in a cycle with the program above it (F214)");
		if (!new File(classes, "ctrmap").isDirectory()) {
			check(false, "there is a compiled program to read - none at " + classes.getAbsolutePath());
			finish();
			return;
		}
		theComponentsAreTheOnesAGraphHas();

		Graph g = new Graph(ClassFileScanner.application(classes));
		Set<String> onDisk = packagesOnDisk(new File(classes, "ctrmap"), "ctrmap", new TreeSet<String>());
		check(g.packages.equals(onDisk), "the graph holds every package that holds a production class ("
				+ g.packages.size() + " read, " + onDisk.size() + " on disk"
				+ (g.packages.equals(onDisk) ? ")" : "; differs by " + difference(g.packages, onDisk) + ")"));
		check(g.edges.getOrDefault("ctrmap/formats/garc", Collections.<String>emptySet()).contains("ctrmap/util"),
				"control: the archive reader's edge to the byte streams it reads with is seen");

		List<List<String>> cycles = components(g.edges, g.packages);
		int largest = 0;
		List<String> mixed = new ArrayList<>();
		for (List<String> c : cycles) {
			if (c.size() < 2) {
				continue;
			}
			largest = Math.max(largest, c.size());
			System.out.println("  cycle of " + c.size() + ": " + c);
			List<String> formats = new ArrayList<>();
			List<String> others = new ArrayList<>();
			for (String p : c) {
				(isFormat(p) ? formats : others).add(p);
			}
			if (!formats.isEmpty() && !others.isEmpty()) {
				for (String f : formats) {
					for (String o : others) {
						Set<String> why = g.why.get(f + " " + o);
						if (why != null) {
							mixed.add(f + " -> " + o + " " + why);
						}
					}
				}
			}
		}
		check(mixed.isEmpty(), "no package cycle holds both a format package and one above it"
				+ (mixed.isEmpty() ? "" : " - these edges put the format layer in one: " + mixed + ". A format"
				+ " class is handed what it needs; ask the GameFiles it was given, or move the type it"
				+ " names down"));
		check(largest <= LARGEST_CYCLE, "no package cycle is larger than the " + LARGEST_CYCLE
				+ " recorded (the largest is " + largest + ")");
		check(largest == LARGEST_CYCLE, "and the recorded size is the measured one - lower it when it falls"
				+ " (measured " + largest + ", recorded " + LARGEST_CYCLE + ")");

		Set<String> drawing = new TreeSet<>();
		for (ClassFileScanner.ClassFile cf : ClassFileScanner.application(classes)) {
			if (!isFormat(Graph.pkg(cf.name))) {
				continue;
			}
			Set<String> named = new TreeSet<>(cf.classes);
			for (ClassFileScanner.Ref r : cf.refs) {
				named.add(r.owner);
			}
			for (String c : named) {
				if (c.startsWith("com/jogamp/")) {
					drawing.add(cf.topLevel());
				}
			}
		}
		Set<String> recorded = new TreeSet<>(java.util.Arrays.asList(DRAWING_FORMATS));
		check(drawing.containsAll(recorded) && recorded.containsAll(drawing), "the format classes that name an"
				+ " OpenGL type are exactly the " + recorded.size() + " recorded"
				+ (drawing.equals(recorded) ? "" : " - measured " + drawing + ". One more is a byte format that"
				+ " cannot be read without OpenGL on the classpath; one fewer is a line to delete here"));

		List<List<String>> classCycles = formatClassCycles(ClassFileScanner.application(classes));
		Set<String> inCycles = new TreeSet<>();
		for (List<String> c : classCycles) {
			System.out.println("  class cycle of " + c.size() + ": " + c);
			for (String n : c) {
				inCycles.add(n.substring(n.lastIndexOf('/') + 1));
			}
		}
		Set<String> cycling = new TreeSet<>(java.util.Arrays.asList(CYCLING_FORMAT_CLASSES));
		check(inCycles.equals(cycling), "the format classes in a class cycle are exactly the " + cycling.size()
				+ " recorded" + (inCycles.equals(cycling) ? "" : " - measured " + inCycles + ". One more is a"
				+ " format class and its caller that cannot be read apart - a split whose pieces still reach"
				+ " back into it is one; one fewer is a name to delete here"));
		theEngineNamesNoWindow(ClassFileScanner.application(classes));
		finish();
	}

	/** What a class may name only if it is the UI: a window, a widget, a dialog, or the Ui seam that opens one. */
	static boolean isWindow(String className) {
		return className.startsWith("javax/swing/") || className.startsWith("java/awt/")
				|| className.startsWith("ctrmap/humaninterface/") || className.equals("ctrmap/Ui");
	}

	/**
	 * Every "class -> window class" edge of the classes {@code which} picks - each one's edges to itself
	 * and its nested classes aside - and how many classes were read.
	 */
	static Set<String> windowEdges(List<ClassFileScanner.ClassFile> program,
			java.util.function.Predicate<ClassFileScanner.ClassFile> which, int[] read) {
		Set<String> out = new TreeSet<>();
		for (ClassFileScanner.ClassFile cf : program) {
			if (!which.test(cf)) {
				continue;
			}
			read[0]++;
			Set<String> named = new TreeSet<>(cf.classes);
			for (ClassFileScanner.Ref r : cf.refs) {
				named.add(r.owner);
			}
			for (String c : named) {
				//itself and its nested classes aside, and nothing else: the engine shares the ctrmap
				//package with the Ui seam, so a package-wide exclusion would have read Ui as its own
				if (isWindow(c) && !c.split("\\$")[0].equals(cf.topLevel())) {
					out.add(cf.topLevel() + " -> " + c);
				}
			}
		}
		return out;
	}

	/**
	 * The map-building engine names no window (F248). It was the painter's form - in the UI package,
	 * reaching into the building palette dialog for its prefabs, the area prompt for its forks and a
	 * Swing dialog of its own for sign text - so it could be reached, or tested, only through the UI.
	 * What it asks a person goes through its Host now, and this holds it there.
	 */
	static void theEngineNamesNoWindow(List<ClassFileScanner.ClassFile> program) {
		System.out.println("--- the map-building engine names no window (F248)");
		int[] seen = {0};
		Set<String> ui = windowEdges(program, cf -> Graph.pkg(cf.name).equals("ctrmap/humaninterface"), seen);
		boolean swingSeen = false;
		for (String e : ui) {
			swingSeen |= e.startsWith("ctrmap/humaninterface/PaintForm -> javax/swing/");
		}
		check(swingSeen, "control: the same scan sees the Map Builder panel's own Swing edges");
		int[] read = {0};
		//the class and its nested ones: it lives in ctrmap beside its peers, where a package of its own
		//joined the application's package cycle (largest 7 -> 8, measured by this suite)
		Set<String> reached = windowEdges(program, cf -> cf.topLevel().equals("ctrmap/MapPainter"), read);
		check(read[0] > 1, "control: the engine's class and its nested ones are read (" + read[0] + ")");
		check(reached.isEmpty(), "no class of the engine names a window, a widget, a dialog or the Ui seam"
				+ (reached.isEmpty() ? "" : " - " + reached + ". Ask the Host what a person must answer"));
	}

	/**
	 * The class cycles inside the format layer (F090): top-level class to top-level class, a nested
	 * type folded into the class that declares it, edges as {@link Graph} reads them. A split's
	 * pieces that still reach their driver - the painter's stages took their frame and their quad
	 * from the class that called them - are one component however many files they are in.
	 */
	static List<List<String>> formatClassCycles(List<ClassFileScanner.ClassFile> program) {
		Set<String> nodes = new TreeSet<>();
		for (ClassFileScanner.ClassFile cf : program) {
			if (isFormat(Graph.pkg(cf.name))) {
				nodes.add(cf.topLevel());
			}
		}
		Map<String, Set<String>> edges = new TreeMap<>();
		for (ClassFileScanner.ClassFile cf : program) {
			String from = cf.topLevel();
			if (!nodes.contains(from)) {
				continue;
			}
			Set<String> named = new TreeSet<>(cf.classes);
			for (ClassFileScanner.Ref r : cf.refs) {
				named.add(r.owner);
			}
			for (String c : named) {
				String to = c.split("\\$")[0];
				if (nodes.contains(to) && !to.equals(from)) {
					Set<String> out = edges.get(from);
					if (out == null) {
						out = new TreeSet<>();
						edges.put(from, out);
					}
					out.add(to);
				}
			}
		}
		List<List<String>> out = new ArrayList<>();
		for (List<String> c : components(edges, nodes)) {
			if (c.size() > 1) {
				out.add(c);
			}
		}
		return out;
	}

	private static void finish() {
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	private static boolean isFormat(String pkg) {
		return pkg.equals(FORMATS) || pkg.startsWith(FORMATS + "/");
	}

	/** Known answers for the component finder before it is believed about the program. */
	static void theComponentsAreTheOnesAGraphHas() {
		Map<String, Set<String>> e = new HashMap<>();
		e.put("a", new TreeSet<>(java.util.Arrays.asList("b")));
		e.put("b", new TreeSet<>(java.util.Arrays.asList("a", "c")));
		e.put("c", new TreeSet<>(java.util.Arrays.asList("d")));
		e.put("d", new TreeSet<>(java.util.Arrays.asList("c", "e")));
		Set<String> all = new TreeSet<>(java.util.Arrays.asList("a", "b", "c", "d", "e", "f"));
		List<List<String>> cs = components(e, all);
		Set<String> got = new TreeSet<>();
		for (List<String> c : cs) {
			got.add(c.toString());
		}
		check(got.equals(new TreeSet<>(java.util.Arrays.asList("[a, b]", "[c, d]", "[e]", "[f]"))),
				"control: a graph with two two-cycles, a sink and a loner has exactly those components (" + got + ")");
	}

	/** Every package under {@code dir} that holds a class file, tests excluded - from the tree, not a list. */
	private static Set<String> packagesOnDisk(File dir, String name, Set<String> out) {
		File[] kids = dir.listFiles();
		if (kids == null || name.equals("ctrmap/tests")) {
			return out;
		}
		for (File k : kids) {
			if (k.isDirectory()) {
				packagesOnDisk(k, name + "/" + k.getName(), out);
			} else if (k.getName().endsWith(".class")) {
				out.add(name);
			}
		}
		return out;
	}

	private static Set<String> difference(Set<String> a, Set<String> b) {
		Set<String> d = new TreeSet<>(a);
		d.addAll(b);
		Set<String> both = new TreeSet<>(a);
		both.retainAll(b);
		d.removeAll(both);
		return d;
	}

	/** Production packages and the class-level edges between them. */
	static final class Graph {
		final Set<String> packages = new TreeSet<>();
		final Map<String, Set<String>> edges = new TreeMap<>();
		/** "from to" -> the class pairs behind that package edge. */
		final Map<String, Set<String>> why = new LinkedHashMap<>();

		Graph(List<ClassFileScanner.ClassFile> program) {
			for (ClassFileScanner.ClassFile cf : program) {
				packages.add(pkg(cf.name));
			}
			for (ClassFileScanner.ClassFile cf : program) {
				Set<String> named = new TreeSet<>(cf.classes);
				for (ClassFileScanner.Ref r : cf.refs) {
					named.add(r.owner);
				}
				String from = pkg(cf.name);
				for (String c : named) {
					String to = pkg(c);
					if (!packages.contains(to) || to.equals(from)) {
						continue;
					}
					Set<String> out = edges.get(from);
					if (out == null) {
						out = new TreeSet<>();
						edges.put(from, out);
					}
					out.add(to);
					Set<String> w = why.get(from + " " + to);
					if (w == null) {
						w = new TreeSet<>();
						why.put(from + " " + to, w);
					}
					w.add(simple(cf.topLevel()) + ">" + simple(c.split("\\$")[0]));
				}
			}
		}

		private static String pkg(String internalName) {
			int slash = internalName.lastIndexOf('/');
			return slash < 0 ? "" : internalName.substring(0, slash);
		}

		private static String simple(String internalName) {
			return internalName.substring(internalName.lastIndexOf('/') + 1);
		}
	}

	/** Tarjan's strongly connected components, iteratively; each sorted, nodes with no edge included. */
	static List<List<String>> components(Map<String, Set<String>> edges, Set<String> nodes) {
		Map<String, Integer> index = new HashMap<>();
		Map<String, Integer> low = new HashMap<>();
		List<String> stack = new ArrayList<>();
		Set<String> onStack = new java.util.HashSet<>();
		List<List<String>> out = new ArrayList<>();
		int[] counter = {0};
		for (String root : nodes) {
			if (index.containsKey(root)) {
				continue;
			}
			//explicit call stack: (node, iterator over its successors)
			List<Object[]> calls = new ArrayList<>();
			index.put(root, counter[0]);
			low.put(root, counter[0]++);
			stack.add(root);
			onStack.add(root);
			calls.add(new Object[]{root, successors(edges, root).iterator()});
			while (!calls.isEmpty()) {
				Object[] top = calls.get(calls.size() - 1);
				String v = (String) top[0];
				@SuppressWarnings("unchecked")
				java.util.Iterator<String> it = (java.util.Iterator<String>) top[1];
				if (it.hasNext()) {
					String w = it.next();
					if (!index.containsKey(w)) {
						index.put(w, counter[0]);
						low.put(w, counter[0]++);
						stack.add(w);
						onStack.add(w);
						calls.add(new Object[]{w, successors(edges, w).iterator()});
					} else if (onStack.contains(w)) {
						low.put(v, Math.min(low.get(v), index.get(w)));
					}
					continue;
				}
				calls.remove(calls.size() - 1);
				if (!calls.isEmpty()) {
					String parent = (String) calls.get(calls.size() - 1)[0];
					low.put(parent, Math.min(low.get(parent), low.get(v)));
				}
				if (low.get(v).equals(index.get(v))) {
					List<String> c = new ArrayList<>();
					String w;
					do {
						w = stack.remove(stack.size() - 1);
						onStack.remove(w);
						c.add(w);
					} while (!w.equals(v));
					Collections.sort(c);
					out.add(c);
				}
			}
		}
		return out;
	}

	private static Set<String> successors(Map<String, Set<String>> edges, String v) {
		Set<String> s = edges.get(v);
		return s == null ? Collections.<String>emptySet() : s;
	}
}

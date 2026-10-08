package ctrmap.formats.h3d;

import java.util.ArrayList;
import java.util.List;

/**
 * The GameFreak name tree (a Patricia trie over the names' bits, LSB first within each byte) that
 * BCH dictionaries and the area animation file both index their entries by - the one builder
 * (F066).
 *
 * <p>WHY THERE IS ONE. BchTexturePack, BchModelAppender and WorldAnim each carried their own copy
 * and each was claimed byte-exact against retail on its own. Three copies of one algorithm is a
 * fix that lands on one and not the others. Before they were merged they were measured against
 * each other: 20,000 random name sets, a quarter of them holding names that differ only in the
 * lowest bit, built identical trees in all three ({@code ctrmap.tests.PatriciaTest} pins the
 * merged one to the trees the BCH builder made).
 *
 * <p>Not: BchMapModel - that reads a model's dictionaries; this only builds the tree a
 * dictionary is indexed by, for the writers.
 */
public final class Patricia {

	private Patricia() {
	}

	/** One tree node: the root first, then one per name, in the order the names were given. */
	public static final class Node {

		/** The bit this node tests; the root's is 0xFFFFFFFF. */
		public long refBit;
		/** Child index when the bit is clear / set; a link to a node with a higher bit is an up-link. */
		public int left, right;
		/** The name this node holds; null for the root. */
		public String name;
	}

	/** Bit {@code bit} of {@code name}: byte {@code bit / 8}, bit {@code bit % 8}; past the end, 0. */
	public static boolean bit(String name, long bit) {
		int pos = (int) (bit >>> 3);
		if (name != null && pos < name.length()) {
			return ((name.charAt(pos) >> (int) (bit & 7)) & 1) != 0;
		}
		return false;
	}

	private static int traverse(String name, List<Node> nodes, Node[] parentOut, long bit) {
		Node parent = nodes.get(0);
		int out = parent.left;
		Node child = nodes.get(out);
		while (parent.refBit > child.refBit && child.refBit > bit) {
			out = bit(name, child.refBit) ? child.right : child.left;
			parent = child;
			child = nodes.get(out);
		}
		parentOut[0] = parent;
		return out;
	}

	/**
	 * The tree for {@code names}, inserted in the order given (the order decides the bytes).
	 *
	 * @throws IllegalArgumentException when a name is given twice: a tree cannot tell two equal
	 * names apart, and a lookup would find one of them
	 */
	public static List<Node> build(List<String> names) {
		List<Node> nodes = new ArrayList<>();
		Node root = new Node();
		root.refBit = 0xFFFFFFFFL;
		nodes.add(root);
		int maxLen = 0;
		for (String t : names) {
			maxLen = Math.max(maxLen, t.length());
		}
		Node[] parentOut = new Node[1];
		for (String t : names) {
			long b = (maxLen << 3) - 1;
			int index = traverse(t, nodes, parentOut, 0);
			while (bit(nodes.get(index).name, b) == bit(t, b)) {
				if (--b < 0) {
					throw new IllegalArgumentException("The name \"" + t + "\" is given twice; a name tree cannot hold"
							+ " two of one name.");
				}
			}
			Node n = new Node();
			n.name = t;
			n.refBit = b;
			if (bit(t, b)) {
				n.left = traverse(t, nodes, parentOut, b);
				n.right = nodes.size();
			} else {
				n.left = nodes.size();
				n.right = traverse(t, nodes, parentOut, b);
			}
			Node parent = parentOut[0];
			if (bit(t, parent.refBit)) {
				parent.right = nodes.size();
			} else {
				parent.left = nodes.size();
			}
			nodes.add(n);
		}
		return nodes;
	}
}

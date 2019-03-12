/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package tracing.annotation;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import tracing.Path;
import tracing.Tree;
import tracing.util.PointInImage;
import tracing.util.SNTPoint;

/**
 * The Class AllenUtils.
 *
 * @author tferr
 */
public class AllenUtils {

	/** The version of the Common Coordinate Framework currently used by SNT */
	public static final int VERSION = 3;
	private final static PointInImage BRAIN_BARYCENTRE = new PointInImage(5687.5435f, 3849.6099f, 6595.3813f);


	private static JSONArray areaList;

	private AllenUtils() {
	}

	private static JSONObject getJSONfile(final String resourcePath) {
		final ClassLoader classloader = Thread.currentThread().getContextClassLoader();
		final InputStream is = classloader.getResourceAsStream(resourcePath);
		final JSONTokener tokener = new JSONTokener(is);
		final JSONObject json = new JSONObject(tokener);
		try {
			is.close();
		} catch (final IOException ignored) {// do nothing

		}
		return json;
	}

	protected static JSONArray getBrainAreasList() {
		if (areaList == null) {
			final JSONObject json = getJSONfile("ml/brainAreas.json");
			areaList = json.getJSONObject("data").getJSONArray("brainAreas");
		}
		return areaList;
	}

	/**
	 * Initialize compartments.
	 *
	 * @param path the path
	 */
	public static void initializeCompartments(final Path path) {
		areaList = getBrainAreasList();
		for (int node = 0; node < path.size(); node++) {
			for (int n = 0; n < areaList.length(); n++) {
				final AllenCompartment label = (AllenCompartment) path.getNodeLabel(node);
				final JSONObject area = (JSONObject) areaList.get(n);
				final UUID id = UUID.fromString(area.getString("id"));
				if (label.getUUID().equals(id)) {
					path.setNodeLabel(new AllenCompartment(area, id), n);
					break;
				}
			}
		}
	}

	/**
	 * Initialize compartments.
	 *
	 * @param tree the tree
	 */
	public static void initializeCompartments(final Tree tree) {
		tree.list().parallelStream().forEach(p -> initializeCompartments(p));
	}

	/**
	 * Checks the which hemisphere a reconstruction
	 *
	 * @param point the point
	 * @return true, if is left hemisphere, false otherwise
	 */
	public static boolean isLeftHemisphere(final SNTPoint point) {
		return point.getX() <= BRAIN_BARYCENTRE.x;
	}

	/**
	 * Returns the spatial centroid of the Allen CCF.
	 *
	 * @return the SNT point defining the center of the ARA
	 */
	public static SNTPoint brainBarycentre() {
		return BRAIN_BARYCENTRE;
	}

	/**
	 * Retrieves the Allen CCF hierarchical tree data.
	 * 
	 * @return the Allen CCF tree data model
	 */
	public static DefaultTreeModel getTreeModel() {
		return new AllenTreeModel().getTreeModel();
	}

	/**
	 * Gets the Allen CCF as a flat (non-hierarchical) collection of ontologies.
	 *
	 * @return the "flattened" ontogenies
	 */
	public static Collection<AllenCompartment> getOntogenies() {
		return new AllenTreeModel().getOntogenies();
	}

	private static class AllenTreeModel {

		private static final int ROOT_ID = 997;
		private final JSONArray areaList;
		private DefaultMutableTreeNode root;

		private AllenTreeModel() {
			areaList = getBrainAreasList();
		}

		private AllenCompartment getAreaListCompartment(final int idx) {
			final JSONObject area = (JSONObject) areaList.get(idx);
			final AllenCompartment ac = new AllenCompartment(UUID.fromString(area.getString("id")));
			return ac;
		}

		private Collection<AllenCompartment> getOntogenies() {
			final Collection<AllenCompartment> list = new ArrayList<>(areaList.length());
			for (int n = 0; n < areaList.length(); n++) {
				list.add(getAreaListCompartment(n));
			}
			return list;
		}

		private DefaultTreeModel getTreeModel() {
			final TreeSet<AllenCompartment> all = new TreeSet<>((ac1, ac2)->ac1.getStructureIdPath().compareTo(ac2.getStructureIdPath()));
			final Map<Integer, AllenCompartment> idsMap = new HashMap<>();
			final Set<Integer> visitedIds = new HashSet<>();
			root = new DefaultMutableTreeNode();
			for (int n = 0; n < areaList.length(); n++) {
				final AllenCompartment ac = getAreaListCompartment(n);
				if (ac.id() == ROOT_ID) {
					root.setUserObject(ac);
					visitedIds.add(ac.id());
				} else {
					idsMap.put(ac.id(), ac);
				}
				all.add(ac);
			}

			for (final AllenCompartment ac : all) {
				final String path = ac.getStructureIdPath();
				DefaultMutableTreeNode node = root;
				for (final String structureID : path.split("/")) {
					if (structureID.isEmpty())
						continue;
					final int id = Integer.parseInt(structureID);
					if (visitedIds.contains(id))
						continue;
					final AllenCompartment c = idsMap.get(id);
					final AllenCompartment pc = idsMap.get(c.getParentStructureId());
					final DefaultMutableTreeNode parentNode = getParentNode(pc);
					if (parentNode!=null) {
						node = parentNode;
					}
					node.add(new DefaultMutableTreeNode(c));
					visitedIds.add(id);
				}
			}
			assert(visitedIds.size()==idsMap.size()+1);
			return new DefaultTreeModel(root);
		}

		private DefaultMutableTreeNode getParentNode(final AllenCompartment parentStructure) {
			@SuppressWarnings("unchecked")
			final Enumeration<DefaultMutableTreeNode> en = root.depthFirstEnumeration();
			while (en.hasMoreElements()) {
				DefaultMutableTreeNode node = en.nextElement();
				final AllenCompartment structure = (AllenCompartment)node.getUserObject();
				if (structure.equals(parentStructure)) {
					return node;
				}
			}
			return null;
		}
	}

	/* IDE Debug method */
	public static void main(final String[] args) {
		final javax.swing.JTree tree = new javax.swing.JTree(getTreeModel());
		final javax.swing.JScrollPane treeView = new javax.swing.JScrollPane(tree);
		final javax.swing.JFrame f = new javax.swing.JFrame();
		f.add(treeView);
		f.pack();
		f.setVisible(true);
	}

}

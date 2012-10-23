/*
 *  Copyright 2012 Peter Karich
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing.util;

import com.graphhopper.coll.MySortedCollection;
import com.graphhopper.routing.DijkstraBidirectionRef;
import com.graphhopper.routing.DijkstraSimple;
import com.graphhopper.routing.Path;
import com.graphhopper.routing.Path4CH;
import com.graphhopper.routing.PathBidirRef;
import com.graphhopper.routing.RoutingAlgorithm;
import com.graphhopper.storage.EdgeEntry;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.LevelGraph;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeSkipIterator;
import com.graphhopper.util.GraphUtility;
import com.graphhopper.util.StopWatch;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class prepares the graph for a bidirectional algorithm supporting contraction hierarchies
 * ie. an algorithm returned by createAlgo.
 *
 * There are several description of contraction hierarchies available. This following is one of the
 * more detailed: http://web.cs.du.edu/~sturtevant/papers/highlevelpathfinding.pdf
 *
 * @author Peter Karich
 */
public class PrepareContractionHierarchies implements AlgorithmPreparation {

    private Logger logger = LoggerFactory.getLogger(getClass());
    private WeightCalculation weightCalc;
    private LevelGraph g;
    // the most important nodes comes last
    private MySortedCollection sortedNodes;
    private WeightedNode refs[];
    // shortcut is one direction, speed is only involved while recalculating the endNode weights - see prepareEdges
    private static int scOneDir = CarStreetType.flags(0, false);
    private static int scBothDir = CarStreetType.flags(0, true);
    private Map<Long, Shortcut> shortcuts = new HashMap<Long, Shortcut>();
    private EdgeLevelFilterCH edgeFilter;
    private OneToManyDijkstraCH algo;

    public PrepareContractionHierarchies(LevelGraph g) {
        this.g = g;
        sortedNodes = new MySortedCollection(g.getNodes());
        refs = new WeightedNode[g.getNodes()];
        weightCalc = ShortestCalc.DEFAULT;
        edgeFilter = new EdgeLevelFilterCH(g);
    }

    public PrepareContractionHierarchies setWeightCalculation(WeightCalculation weightCalc) {
        this.weightCalc = weightCalc;
        return this;
    }

    @Override
    public void doWork() {
        // TODO integrate PrepareRoutingShortcuts -> so avoid all nodes with negative level in the other methods        
        // in PrepareShortcuts level 0 and -1 is already used move that to level 1 and 2 so that level 0 stays as uncontracted
        if (!prepareEdges())
            return;
        if (!prepareNodes())
            return;
        contractNodes();
    }

    boolean prepareEdges() {
        // in CH the flags will be ignored (calculating the new flags for the shortcuts is impossible)
        // also several shortcuts would be necessary with the different modes (e.g. fastest and shortest)
        // so calculate the weight and store this as distance, then use only distance instead of getWeight
        EdgeSkipIterator iter = g.getAllEdges();
        int c = 0;
        while (iter.next()) {
            c++;
            iter.distance(weightCalc.getWeight(iter));
            iter.originalEdges(1);
        }
        return c > 0;
    }

    boolean prepareNodes() {
        int len = g.getNodes();

        // minor idea: 1. sort nodes randomly and 2. pre-init with endNode degree
        for (int node = 0; node < len; node++) {
            refs[node] = new WeightedNode(node, 0);
        }

        for (int node = 0; node < len; node++) {
            WeightedNode wn = refs[node];
            wn.priority = calculatePriority(node);
            sortedNodes.insert(wn.node, wn.priority);
        }

        if (sortedNodes.isEmpty())
            return false;

        return true;
    }

    void contractNodes() {
        int level = 1;
        int newShortcuts = 0;
        final int updateSize = Math.max(10, sortedNodes.size() / 10);
        int counter = 0;
        int updateCounter = 0;
        StopWatch sw = new StopWatch();
        // no update all => 600k shortcuts and 3min
        while (!sortedNodes.isEmpty()) {
            if (counter % updateSize == 0) {
                // periodically update priorities of ALL nodes            
                if (updateCounter > 0 && updateCounter % 2 == 0) {
                    int len = g.getNodes();
                    sw.start();
                    // TODO avoid to traverse all nodes -> via a new sortedNodes.iterator()
                    for (int node = 0; node < len; node++) {
                        WeightedNode wNode = refs[node];
                        if (g.getLevel(node) != 0)
                            continue;
                        int old = wNode.priority;
                        wNode.priority = calculatePriority(node);
                        sortedNodes.update(node, old, wNode.priority);
                    }
                    sw.stop();
                }
                updateCounter++;
                logger.info(counter + ", nodes: " + sortedNodes.size() + ", shortcuts:" + newShortcuts + ", updateAllTime:" + sw.getSeconds() + ", " + updateCounter);
            }

            counter++;
            WeightedNode wn = refs[sortedNodes.pollKey()];

            // update priority of current endNode via simulating 'addShortcuts'
            wn.priority = calculatePriority(wn.node);
            if (!sortedNodes.isEmpty() && wn.priority > sortedNodes.peekValue()) {
                // endNode got more important => insert as new value and contract it later
                sortedNodes.insert(wn.node, wn.priority);
                continue;
            }

            // contract!            
            newShortcuts += addShortcuts(wn.node);
            g.setLevel(wn.node, level);
            level++;

            // recompute priority of uncontracted neighbors
            EdgeIterator iter = g.getEdges(wn.node);
            while (iter.next()) {
                if (g.getLevel(iter.node()) != 0)
                    // already contracted no update necessary
                    continue;

                int nn = iter.node();
                WeightedNode neighborWn = refs[nn];
                int tmpOld = neighborWn.priority;
                neighborWn.priority = calculatePriority(nn);
                if (neighborWn.priority != tmpOld) {
                    sortedNodes.update(nn, tmpOld, neighborWn.priority);
                }
            }
        }
        logger.info("new shortcuts " + newShortcuts);
        // System.out.println("new shortcuts " + newShortcuts);
    }

    /**
     * Calculates the priority of endNode v without changing the graph. Warning: the calculated
     * priority must NOT depend on priority(v) and therefor findShortcuts should also not depend on
     * the priority(v). Otherwise updating the priority before contracting in contractNodes() could
     * lead to a slowishor even endless loop.
     */
    int calculatePriority(int v) {
        // set of shortcuts that would be added if endNode v would be contracted next.
        Collection<Shortcut> tmpShortcuts = findShortcuts(v);
        // from shortcuts we can compute the edgeDifference
        // |shortcuts(v)| − |{(u, v) | v uncontracted}| − |{(v, w) | v uncontracted}|        
        // meanDegree is used instead of outDegree+inDegree as if one endNode is in both directions
        // only one bucket memory is used. Additionally one shortcut could also stand for two directions.
        int degree = GraphUtility.count(g.getEdges(v));
        int edgeDifference = tmpShortcuts.size() - degree;

        // every endNode has an 'original endNode' number associated. initially it is r=1
        // when a new shortcut is introduced then r of the associated edges is summed up:
        // r(u,w)=r(u,v)+r(v,w) now we can define
        // originalEdges = σ(v) := sum_{ (u,w) ∈ shortcuts(v) } of r(u, w)
        int originalEdges = 0;
        for (Shortcut sc : tmpShortcuts) {
            originalEdges += sc.originalEdges;
        }

        // number of already contracted neighbors of v
        int contractedNeighbors = 0;
        EdgeSkipIterator iter = g.getEdges(v);
        while (iter.next()) {
            if (iter.skippedNode() >= 0)
                contractedNeighbors++;
        }

        // according to the paper do a simple linear combination of the properties to get the priority
        return 2 * edgeDifference + 4 * originalEdges + contractedNeighbors;
    }

    Map<Long, Shortcut> getShortcuts() {
        return shortcuts;
    }

    static class EdgeLevelFilterCH extends EdgeLevelFilter {

        int skipNode;

        public EdgeLevelFilterCH(LevelGraph g) {
            super(g);
        }

        public EdgeLevelFilterCH setSkipNode(int skipNode) {
            this.skipNode = skipNode;
            return this;
        }

        @Override public boolean accept() {
            // ignore if it is skipNode or a endNode already contracted
            return skipNode != node() && graph.getLevel(node()) == 0;
        }
    }

    /**
     * Finds shortcuts, does not change the underlying graph.
     */
    Collection<Shortcut> findShortcuts(int v) {
        // we can use distance instead of weight, see prepareEdges where distance is overwritten by weight!
        List<NodeCH> goalNodes = new ArrayList<NodeCH>();
        shortcuts.clear();
        EdgeSkipIterator iter1 = g.getIncoming(v);
        // TODO PERFORMANCE collect outgoing nodes (goalnodes) only once and just skip u
        while (iter1.next()) {
            int u = iter1.node();
            int lu = g.getLevel(u);
            if (lu != 0)
                continue;

            double v_u_weight = iter1.distance();

            // one-to-many shortest path
            goalNodes.clear();
            EdgeSkipIterator iter2 = g.getOutgoing(v);
            double maxWeight = 0;
            while (iter2.next()) {
                int w = iter2.node();
                int lw = g.getLevel(w);
                if (w == u || lw != 0)
                    continue;

                NodeCH n = new NodeCH();
                n.endNode = w;
                n.originalEdges = iter2.originalEdges();
                n.distance = v_u_weight + iter2.distance();
                goalNodes.add(n);

                if (maxWeight < n.distance)
                    maxWeight = n.distance;
            }

            if (goalNodes.isEmpty())
                continue;

            // TODO instead of a weight-limit we could use a hop-limit 
            // and successively increasing it when mean-degree of graph increases
            algo = new OneToManyDijkstraCH(g).setFilter(edgeFilter.setSkipNode(v));
            algo.setLimit(maxWeight).calcPath(u, goalNodes);
            internalFindShortcuts(goalNodes, u, iter1.originalEdges());
        }
        return shortcuts.values();
    }

    void internalFindShortcuts(List<NodeCH> goalNodes, int u, int uOrigEdge) {
        for (NodeCH n : goalNodes) {
            if (n.entry != null) {
                Path p = algo.extractPath(n.entry);
                if (p != null && p.weight() <= n.distance) {
                    // FOUND witness path => do not add shortcut
                    continue;
                }
            }

            // FOUND shortcut but be sure that it is the only shortcut in the collection 
            // and also in the graph for u->w. If existing => update it                
            // Hint: shortcuts are always one-way due to distinct level of every endNode but we don't
            // know yet the levels so we need to determine the correct direction or if both directions
            long edgeId = (long) u * refs.length + n.endNode;
            Shortcut sc = shortcuts.get(edgeId);
            if (sc == null) {
                sc = shortcuts.get((long) n.endNode * refs.length + u);
            } else if (shortcuts.containsKey((long) n.endNode * refs.length + u))
                throw new IllegalStateException("duplicate edge should be overwritten: " + u + "->" + n.endNode);

            if (sc == null || sc.distance != n.distance) {
                sc = new Shortcut(u, n.endNode, n.distance);
                shortcuts.put(edgeId, sc);
                sc.originalEdges = uOrigEdge + n.originalEdges;
            } else {
                // the shortcut already exists in the current collection (different direction)
                // but has identical length so change the flags!
                sc.flags = scBothDir;
            }
        }
    }

    /**
     * Introduces the necessary shortcuts for endNode v in the graph.
     */
    int addShortcuts(int v) {
        Collection<Shortcut> foundShortcuts = findShortcuts(v);
//        System.out.println("contract:" + refs[v] + ", scs:" + shortcuts);
        int newShorts = 0;
        for (Shortcut sc : foundShortcuts) {
            boolean updatedInGraph = false;
            EdgeSkipIterator iter = g.getOutgoing(sc.from);
            while (iter.next()) {
                if (iter.skippedNode() >= 0
                        && iter.node() == sc.to
                        && CarStreetType.canBeOverwritten(iter.flags(), sc.flags)
                        && iter.distance() > sc.distance) {
                    iter.flags(sc.flags);
                    iter.skippedNode(v);
                    iter.distance(sc.distance);
                    iter.originalEdges(sc.originalEdges);
                    updatedInGraph = true;
                    break;
                }
            }

            if (!updatedInGraph) {
                iter = g.shortcut(sc.from, sc.to, sc.distance, sc.flags, v);
                iter.originalEdges(sc.originalEdges);
                newShorts++;
            }
        }
        return newShorts;
    }

    @Override
    public DijkstraBidirectionRef createAlgo() {
        DijkstraBidirectionRef dijkstra = new DijkstraBidirectionRef(g) {
            @Override public boolean checkFinishCondition() {
                // changed finish condition for CH
                if (currFrom == null)
                    return currTo.weight >= shortest.weight();
                else if (currTo == null)
                    return currFrom.weight >= shortest.weight();
                return currFrom.weight >= shortest.weight() && currTo.weight >= shortest.weight();
            }

            @Override public RoutingAlgorithm setType(WeightCalculation wc) {
                // ignore changing of type -> TODO throw exception instead?
                return this;
            }

            @Override protected PathBidirRef createPath() {
                // CH changes the distance in prepareEdges to the weight
                // now we need to transform it back to the real distance
                WeightCalculation wc = new WeightCalculation() {
                    @Override
                    public double getWeight(EdgeIterator iter) {
                        return weightCalc.revert(iter.distance(), iter.flags());
                    }

                    @Override public String toString() {
                        return "INVERSE";
                    }

                    @Override public double apply(double currDistToGoal) {
                        throw new UnsupportedOperationException();
                    }

                    @Override public double apply(double currDistToGoal, int flags) {
                        throw new UnsupportedOperationException();
                    }

                    @Override public double revert(double weight, int flags) {
                        throw new UnsupportedOperationException("Not supported yet.");
                    }
                };
                return new Path4CH(graph, wc);
            }

            @Override public String toString() {
                return "DijkstraCH";
            }
        };
        dijkstra.setEdgeFilter(new EdgeLevelFilter(g));
        return dijkstra;
    }

    static class OneToManyDijkstraCH extends DijkstraSimple {

        EdgeLevelFilter filter;
        double limit;
        Collection<NodeCH> goals;

        public OneToManyDijkstraCH(Graph graph) {
            super(graph);
            setType(ShortestCalc.DEFAULT);
        }

        public OneToManyDijkstraCH setFilter(EdgeLevelFilter filter) {
            this.filter = filter;
            return this;
        }

        @Override
        protected final EdgeIterator getNeighbors(int neighborNode) {
            return filter.doFilter(super.getNeighbors(neighborNode));
        }

        OneToManyDijkstraCH setLimit(double weight) {
            limit = weight;
            return this;
        }

        @Override public OneToManyDijkstraCH clear() {
            super.clear();
            return this;
        }

        @Override public Path calcPath(int from, int to) {
            throw new IllegalArgumentException("call the other calcPath instead");
        }

        Path calcPath(int from, Collection<NodeCH> goals) {
            this.goals = goals;
            return super.calcPath(from, -1);
        }

        @Override public boolean finished(EdgeEntry curr, int _ignoreTo) {
            if (curr.weight > limit)
                return true;

            int found = 0;
            for (NodeCH n : goals) {
                if (n.endNode == curr.endNode) {
                    n.entry = curr;
                    found++;
                } else if (n.entry != null) {
                    found++;
                }
            }
            return found == goals.size();
        }
    }

    private static class WeightedNode {

        int node;
        int priority;

        public WeightedNode(int node, int priority) {
            this.node = node;
            this.priority = priority;
        }

        @Override public String toString() {
            return node + " (" + priority + ")";
        }
    }

    static class Shortcut {

        int from;
        int to;
        double distance;
        int originalEdges;
        int flags = scOneDir;

        public Shortcut(int from, int to, double dist) {
            this.from = from;
            this.to = to;
            this.distance = dist;
        }

        @Override public String toString() {
            return from + "->" + to + ", dist:" + distance;
        }
    }

    static class NodeCH {

        int endNode;
        int originalEdges;
        EdgeEntry entry;
        double distance;

        @Override public String toString() {
            return "" + endNode;
        }
    }
}

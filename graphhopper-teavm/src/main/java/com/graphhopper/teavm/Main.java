package com.graphhopper.teavm;

import org.teavm.jso.JS;
import org.teavm.jso.JSArray;
import org.teavm.jso.JSObject;
import com.graphhopper.routing.DijkstraBidirection;
import com.graphhopper.routing.Path;
import com.graphhopper.routing.util.*;
import com.graphhopper.storage.DataAccess;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.index.LocationIndexTree;
import com.graphhopper.util.PointList;

/**
 *
 * @author Alexey Andreev <konsoletyper@gmail.com>
 */
public class Main {
    public static void main(String[] args) {
        InMemoryDirectory directory = new InMemoryDirectory();
        readAll(directory);
        EncodingManager encodingManager = new EncodingManager(new BikeFlagEncoder());
        GraphHopperStorage graph = new GraphHopperStorage(directory, encodingManager, false);
        graph.loadExisting();
        FlagEncoder encoder = encodingManager.getSingle();

        Weighting weighting = new FastestWeighting(encoder);
        DijkstraBidirection algo = new DijkstraBidirection(graph, encoder, weighting);

        LocationIndexTree locationIndex = new LocationIndexTree(graph, directory);
        locationIndex.prepareIndex();
        int fromNode = locationIndex.findID(55.762523, 37.408784);
        int toNode = locationIndex.findID(55.784806, 37.708047);

        Path path = algo.calcPath(fromNode, toNode);
        PointList points = path.calcPoints();
        for (int i = 0; i < points.size(); ++i) {
            System.out.println(points.getLat(i) + "; " + points.getLon(i));
        }
        System.out.println("Distance: " + path.getDistance());
    }

    private static void readAll(InMemoryDirectory directory) {
        JSObject global = JS.getGlobal();
        @SuppressWarnings("unchecked")
        JSArray<DataEntry> data = (JSArray<DataEntry>)JS.get(global, JS.wrap("graphhopperData"));
        for (int i = 0; i < data.getLength(); ++i) {
            DataEntry entry = data.get(i);
            DataAccess file = directory.find(entry.getName());
            file.setSegmentSize(entry.getSegmentSize());
            file.create(entry.getLength());
            int pos = 0;
            for (int j = 0; j < entry.getData().getLength(); ++j) {
                byte[] bytes = Base64.decode(JS.unwrapString(entry.getData().get(j)));
                file.setBytes(pos, bytes, bytes.length);
                pos += bytes.length;
            }
            byte[] header = Base64.decode(entry.getHeader());
            for (int j = 0; j < 80; j += 4) {
                int val = (header[j]) | (header[j + 1] << 8) | (header[j + 2] << 16) | (header[j + 3] << 24);
                file.setHeader(j, val);
            }
        }
    }
}
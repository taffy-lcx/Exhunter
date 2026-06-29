package org.ExperimentExecutor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.List;
import java.util.logging.Logger;

/**
 * Reads a JSON list of ExampleData where methodResult was produced by an external
 * tool (e.g. Seeker baseline) but the AST-derived result* fields are missing,
 * runs Main.analyzeExperimentDiff on each entry to fill in those fields, and
 * writes the result back. This lets external baselines be evaluated with the
 * exact same MethodDiffAnalyzer pipeline as the main method, so metrics are
 * comparable.
 *
 * Usage:
 *   java -cp target/classes:target/dependency/* \
 *        org.ExperimentExecutor.RefillFromExternal &lt;input.json&gt; &lt;output.json&gt;
 *
 * input.json and output.json may be the same path (in-place refill).
 *
 * Note: must be invoked with cwd = java-scanner/ so that ExampleHandler/Main
 * can locate config.properties (they read it via relative FileInputStream).
 * Despite the dependency on ExampleHandler's loader, this entry point does NOT
 * touch EXPERIMENT_SOURCE / EXPERIMENT_TARGET / experiment_detail caches —
 * it only invokes the pure analyzeExperimentDiff function.
 */
public class RefillFromExternal {
    private static final Logger LOGGER = Logger.getLogger("MAIN");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: RefillFromExternal <input.json> <output.json>");
            System.exit(2);
        }
        String inPath = args[0];
        String outPath = args[1];

        Gson reader = new Gson();
        Type listType = new TypeToken<List<ExampleHandler.ExampleData>>() {}.getType();
        List<ExampleHandler.ExampleData> data;
        try (FileReader fr = new FileReader(inPath)) {
            data = reader.fromJson(fr, listType);
        }
        LOGGER.info("loaded " + data.size() + " entries from " + inPath);

        int filled = 0, skipped = 0;
        for (int i = 0; i < data.size(); i++) {
            ExampleHandler.ExampleData entry = data.get(i);
            if (entry.getMethodBefore() == null || entry.getMethodResult() == null) {
                skipped++;
                continue;
            }
            try {
                Main.analyzeExperimentDiff(entry);
                filled++;
            } catch (Throwable t) {
                LOGGER.warning("analyzeExperimentDiff failed at index " + i
                        + " (" + entry.getMethodName() + "): " + t.getMessage());
                skipped++;
            }
            if ((i + 1) % 100 == 0) {
                LOGGER.info("processed " + (i + 1) + "/" + data.size());
            }
        }

        Gson writer = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
        try (Writer w = new FileWriter(outPath)) {
            writer.toJson(data, w);
        }
        LOGGER.info("wrote " + data.size() + " entries to " + outPath
                + "  (filled=" + filled + ", skipped=" + skipped + ")");
    }
}

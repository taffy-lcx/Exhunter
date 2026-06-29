package org.ExperimentExecutor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.LLMAdvisers.Advisers;
import org.callTreeGenerator.*;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.jdkAnalyzer.ProjectParser;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.logging.*;

 
















public class BuildStaticIntermediate {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    private static String EXPERIMENT_MARK;
    private static boolean UNCAUGHT_BY_LLM = false;
    private static int STATIC_CANDIDATE_CAP = 100;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    
    private static final java.util.concurrent.ExecutorService TRIAGE_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(3);

    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream(System.getenv("CONFIG_FILE") != null ? System.getenv("CONFIG_FILE") : "config.properties")) {
            prop.load(fis);
            EXPERIMENT_MARK = prop.getProperty("EXPERIMENT_MARK");
            UNCAUGHT_BY_LLM = "llm".equalsIgnoreCase(prop.getProperty("UNCAUGHT_SOURCE", "static").trim());
            try {
                STATIC_CANDIDATE_CAP = Integer.parseInt(prop.getProperty("STATIC_CANDIDATE_CAP", "100").trim());
            } catch (NumberFormatException ignore) {
                STATIC_CANDIDATE_CAP = 100;
            }
        } catch (IOException e) {
            throw new RuntimeException("config load failed: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws IOException {
        loggerInit();
        LOGGER.info("BuildStaticIntermediate start (mark=" + EXPERIMENT_MARK + ")");

        
        new java.io.File("../data/" + EXPERIMENT_MARK).mkdirs();
        String outPath = args.length > 0 ? args[0] : "../data/" + EXPERIMENT_MARK + "/intermediate.json";
        boolean resume = !(args.length > 1 && "fresh".equalsIgnoreCase(args[1]));

        
        overrideAdvisersThreshold(-1.0);

        List<IntermediateRecord> existing = new ArrayList<>();
        Set<String> doneKeys = new HashSet<>();
        java.io.File outFile = new java.io.File(outPath);
        if (resume && outFile.exists() && outFile.length() > 4) {
            try (java.io.FileReader reader = new java.io.FileReader(outFile)) {
                java.lang.reflect.Type tp = new com.google.gson.reflect.TypeToken<List<IntermediateRecord>>(){}.getType();
                existing = GSON.fromJson(reader, tp);
                if (existing == null) existing = new ArrayList<>();
                for (IntermediateRecord r : existing) {
                    if (r != null && r.example != null) doneKeys.add(sampleKey(r.example));
                }
                LOGGER.info("resume: loaded " + existing.size() + " records from " + outPath);
            } catch (Exception e) {
                LOGGER.warning("resume parse failed, starting fresh: " + e.getMessage());
                existing = new ArrayList<>();
                doneKeys.clear();
            }
        }

        
        List<ExampleHandler.ExampleData> allInput = ExampleHandler.getRawExampleData();
        if (!ExampleHandler.REPO_FILTER.isEmpty()) {
            LOGGER.info("REPO_FILTER active: " + ExampleHandler.REPO_FILTER);
            allInput = allInput.stream()
                    .filter(d -> ExampleHandler.REPO_FILTER.contains(d.getRepo_id()))
                    .collect(java.util.stream.Collectors.toList());
        }
        int idx = 0, total = allInput.size();
        LOGGER.info("processing " + total + " samples after filter");
        for (ExampleHandler.ExampleData data : allInput) {
            idx++;
            String key = sampleKey(data);
            if (doneKeys.contains(key)) {
                continue;
            }
            LOGGER.info("[" + idx + "/" + total + "] " + data.getRepo_id() + " / " + data.getMethodName());
            IntermediateRecord rec;
            try {
                rec = processSingleExample(data);
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "analysis crashed on " + data.getMethodName(), t);
                rec = new IntermediateRecord();
                rec.example = new ExampleHandler.ExampleData(data);
                rec.example.setLabel(-1);
                rec.staticError = "analysis crashed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            if (rec == null) {
                rec = new IntermediateRecord();
                rec.example = new ExampleHandler.ExampleData(data);
                rec.example.setLabel(-2);
                rec.staticError = "repo unavailable / null result";
            }
            existing.add(rec);
            doneKeys.add(key);
            
            if (idx % 25 == 0 || idx == total) {
                writeAll(existing, outPath);
                LOGGER.info("checkpoint saved: " + existing.size() + " records → " + outPath);
            }
        }
        writeAll(existing, outPath);
        TRIAGE_POOL.shutdown();   
        LOGGER.info("BuildStaticIntermediate done. " + existing.size() + " records → " + outPath);
    }

    private static String sampleKey(ExampleHandler.ExampleData d) {
        return d.getRepo_id() + "|" + (d.getPatch() == null ? "" : d.getPatch().hashCode()) + "|" + d.getMethodName();
    }

    
    private static final String PHASE_TIMING_PATH = "../data/" + EXPERIMENT_MARK + "/phase_timing.jsonl";
    static synchronized void appendPhaseTiming(String sampleKey, String phase, long ms) {
        String line = "{\"sample_key\":\"" + sampleKey.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"phase\":\"" + phase + "\",\"ms\":" + ms + "}\n";
        try (java.io.FileWriter fw = new java.io.FileWriter(PHASE_TIMING_PATH, true)) {
            fw.write(line);
        } catch (IOException e) {
            LOGGER.warning("phase timing write failed: " + e.getMessage());
        }
    }

    private static void writeAll(List<IntermediateRecord> records, String path) throws IOException {
        try (FileWriter w = new FileWriter(path)) {
            GSON.toJson(records, w);
        }
    }

     
    private static IntermediateRecord processSingleExample(ExampleHandler.ExampleData data) throws IOException {
        String repoID = data.getRepo_id();
        String commitHash = RepoHandler.getCommitHashFromPatch(data.getPatch());
        long tCheckoutStart = System.currentTimeMillis();   
        LOGGER.info("start project generation on example: " + data.getMethodName());
        List<String> generatedFiles = ProjectHandler.generateCommitParentHistoryFiles(
                RepoHandler.getRepository(repoID), commitHash);
        if (generatedFiles == null) {
            LOGGER.warning("repo / checkout failed for " + repoID + "/" + commitHash);
            IntermediateRecord rec = new IntermediateRecord();
            rec.example = new ExampleHandler.ExampleData(data);
            rec.example.setLabel(-2);
            rec.staticError = "repo unavailable / checkout failed";
            return rec;
        }
        
        
        appendPhaseTiming(sampleKey(data), "checkout", System.currentTimeMillis() - tCheckoutStart);
        long tStaticStart = System.currentTimeMillis();

        LOGGER.info("start parse on example: " + data.getMethodName());
        ProjectParser projectParser = new ProjectParser(ProjectHandler.TMP_PROJECT_PATH);
        ExceptionCharacteristicManager exceptionCharacteristicManager = new ExceptionCharacteristicManager();
        TreeGenerator treeGenerator = new TreeGenerator(projectParser, exceptionCharacteristicManager);
        IMethodBinding targetMethod = treeGenerator.getMethodBindingByFileAndName(data.getFile_path(), data.getMethodName());

        if (targetMethod == null) {
            IntermediateRecord rec = new IntermediateRecord();
            rec.example = new ExampleHandler.ExampleData(data);
            rec.example.setLabel(-1);
            rec.staticError = "method binding not found: " + data.getMethodName();
            return rec;
        }

        LOGGER.info("start analyze: " + targetMethod.getName());
        treeGenerator.methodBinding2graph(targetMethod);
        DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph = treeGenerator.getGraph();
        MethodTreeNode root = treeGenerator.getRoot();
        ExampleHandler.ExampleData example = new ExampleHandler.ExampleData(data);
        if (example.getLabel() == 0) {
            example.setMethodBefore(root.getCode());
            example.setMethodAfter(root.getCode());
        }

        LOGGER.info("start find exception: " + targetMethod.getName());
        GraphNodeTraversal graphNodeTraversal = new GraphNodeTraversal(exceptionCharacteristicManager);
        List<UncaughtExceptionInfo> exceptionResults = graphNodeTraversal.getSuspiciousThrows(graph, root, UNCAUGHT_BY_LLM);

        Integer layer = 10;
        while (STATIC_CANDIDATE_CAP > 0 && exceptionResults.size() > STATIC_CANDIDATE_CAP && layer > 3) {
            final Integer finalLayer = layer;
            exceptionResults.removeIf(info -> info.getNodeRoute().size() - 1 > finalLayer);
            layer--;
        }
        long staticMs = System.currentTimeMillis() - tStaticStart;   

        
        
        Advisers advisers = new Advisers(example);
        LOGGER.info("start method-triage on " + exceptionResults.size() + " candidates");
        long tTriageStart = System.currentTimeMillis();
        
        List<Advisers.DiscoveredException> discovered =
                advisers.handleMethodTriage(root, exceptionResults, EXPERIMENT_MARK);
        long triageMs = System.currentTimeMillis() - tTriageStart;   
        appendPhaseTiming(sampleKey(data), "callpath", staticMs);
        appendPhaseTiming(sampleKey(data), "triage", triageMs);

        
        IntermediateRecord rec = new IntermediateRecord();
        rec.example = example;
        rec.rootMethodCode = root.getCode();
        for (UncaughtExceptionInfo uei : exceptionResults) {
            IntermediateRecord.Candidate c = extractCandidate(uei);
            if (c != null) rec.candidates.add(c);
        }
        
        for (Advisers.DiscoveredException de : discovered) {
            IntermediateRecord.Candidate c = new IntermediateRecord.Candidate();
            c.kind = "implicit";
            c.simpleName = root.getSimpleName();
            c.routeDepth = 0;
            c.callPathSerialized = "";
            c.hasCallPath = false;
            c.description = de.reason;
            c.apiExceptionScores = new ArrayList<>();
            c.apiExceptionScores.add(new IntermediateRecord.ApiExceptionScore(de.type, de.score, null));
            rec.candidates.add(c);
        }

        // ── ground truth fingerprint
        try {
            org.ASTAnalyzer.ReanalyzeOutput.enrichExampleData(rec.example);
        } catch (Throwable t) {
            LOGGER.warning("enrichExampleData failed: " + t.getMessage());
        }

        LOGGER.info("finish " + data.getMethodName() + ", " + rec.candidates.size() + " candidates");
        return rec;
    }

     
    private static IntermediateRecord.Candidate extractCandidate(UncaughtExceptionInfo uei) {
        if (uei == null) return null;
        IntermediateRecord.Candidate c = new IntermediateRecord.Candidate();
        c.hash = uei.getHash();
        c.simpleName = uei.getMethodTreeNode().getSimpleName();
        c.routeDepth = uei.getNodeRoute().size() - 1;
        c.parentSimpleName = c.routeDepth >= 1 ? uei.getNodeRoute().get(1).getSimpleName() : null;
        c.description = uei.hasDescription() ? uei.getDescription() : null;

        if (uei instanceof UncaughtExceptionInfoAPI) {
            c.kind = "api";
            c.callPathSerialized = c.routeDepth > 0
                    ? Advisers.serializeCallPath(uei.getNodeRoute(), true) : "";
            c.hasCallPath = c.routeDepth > 0 && !c.callPathSerialized.isEmpty();
            
            java.util.Map<String, String> condMap = null;
            if (uei.getMethodTreeNode() instanceof org.callTreeGenerator.ArchivedMethodTreeNode) {
                org.callTreeGenerator.ArchivedMethodTreeNode amt =
                        (org.callTreeGenerator.ArchivedMethodTreeNode) uei.getMethodTreeNode();
                condMap = amt.getExceptions();
                String jd = amt.getJavaDoc();
                if (jd != null && jd.length() > 800) jd = jd.substring(0, 800) + " ...[truncated]";
                c.javadoc = jd;
            }
            
            List<String> ordered = new ArrayList<>(uei.getUncaughtExceptions());
            Collections.sort(ordered);
            Map<String, Double> rawScores = uei.getCheckedExceptionScores();
            c.apiExceptionScores = new ArrayList<>();
            for (String name : ordered) {
                Double s = rawScores == null ? null : rawScores.get(name);
                String cond = null;
                if (condMap != null) {
                    cond = condMap.get(name);
                    if (cond == null) {
                        String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
                        cond = condMap.get(simple);
                    }
                    if (cond != null && cond.length() > 200) cond = cond.substring(0, 200) + "...";
                }
                c.apiExceptionScores.add(new IntermediateRecord.ApiExceptionScore(
                        name, s == null ? 0.0 : s, cond));
            }
        } else if (uei instanceof UncaughtExceptionInfoThrow) {
            c.kind = "throw";
            UncaughtExceptionInfoThrow t = (UncaughtExceptionInfoThrow) uei;
            c.callPathSerialized = c.routeDepth > 0
                    ? Advisers.serializeCallPath(uei.getNodeRoute(), false) : "";
            c.hasCallPath = c.routeDepth > 0 && !c.callPathSerialized.isEmpty();
            Map<ThrowExceptionInfo, Double> rawScores = t.getThrowScores();
            c.throwStatements = new ArrayList<>();
            for (ThrowExceptionInfo info : t.getUncaughtThrowExceptionInfo()) {
                IntermediateRecord.ThrowStatementInfo si = new IntermediateRecord.ThrowStatementInfo();
                si.text = info.toString();
                si.exceptionType = info.getExceptionQualifiedName();
                Double s = rawScores == null ? null : rawScores.get(info);
                si.score = s == null ? 0.0 : s;
                si.description = t.getThrowDescription(info);
                c.throwStatements.add(si);
            }
        } else {
            return null;
        }
        return c;
    }

     
    private static void overrideAdvisersThreshold(double v) {
        try {
            for (String fname : new String[]{"API_THRESHOLD", "THROW_THRESHOLD"}) {
                Field f = Advisers.class.getDeclaredField(fname);
                f.setAccessible(true);
                f.setDouble(null, v);
            }
            LOGGER.info("Advisers thresholds overridden to " + v + " (Stage 1: keep all raw scores)");
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("failed to override Advisers thresholds: " + e.getMessage());
        }
    }

    private static void loggerInit() throws IOException {
        LOGGER.setLevel(Level.ALL);
        Handler fh = new FileHandler("BuildStaticIntermediate_" + EXPERIMENT_MARK + ".log", true);
        fh.setLevel(Level.ALL);
        fh.setFormatter(new SimpleFormatter());
        LOGGER.addHandler(fh);
    }
}

package org.ExperimentExecutor;

import com.google.gson.Gson;
import org.LLMAdvisers.Advisers;
import org.LLMAdvisers.LLMApiCaller;
import org.LLMAdvisers.LLMResponse;
import org.callTreeGenerator.*;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.jdkAnalyzer.ProjectParser;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.*;
import java.util.stream.Collectors;

public class Main {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    private static String EXPERIMENT_MARK;
    
    
    
    private static boolean TRIAGE_EMPTY_FALLBACK;
    
    
    
    
    
    private static boolean TRIAGE_EMPTY_USE_BASELINE;
    
    
    private static int STATIC_CANDIDATE_CAP = 100;
    
    
    private static boolean UNCAUGHT_BY_LLM = false;
    private static Gson gson = new Gson();
    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream(System.getenv("CONFIG_FILE") != null ? System.getenv("CONFIG_FILE") : "config.properties")) {
            prop.load(fis);
            EXPERIMENT_MARK = prop.getProperty("EXPERIMENT_MARK");
            UNCAUGHT_BY_LLM = "llm".equalsIgnoreCase(
                    prop.getProperty("UNCAUGHT_SOURCE", "static").trim());
            TRIAGE_EMPTY_FALLBACK = Boolean.parseBoolean(
                    prop.getProperty("TRIAGE_EMPTY_FALLBACK", "false").trim());
            TRIAGE_EMPTY_USE_BASELINE = Boolean.parseBoolean(
                    prop.getProperty("TRIAGE_EMPTY_USE_BASELINE", "true").trim());
            try {
                STATIC_CANDIDATE_CAP = Integer.parseInt(
                        prop.getProperty("STATIC_CANDIDATE_CAP", "100").trim());
            } catch (NumberFormatException nfe) {
                STATIC_CANDIDATE_CAP = 100;
            }
        } catch (IOException e) {
            LOGGER.severe("config loading failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    public static void main(String[] args) throws IOException {
        loggerInit();
        LOGGER.info("experiment process start.");
        if (!ExampleHandler.REPO_FILTER.isEmpty()) {
            LOGGER.info("REPO_FILTER active, only processing: " + ExampleHandler.REPO_FILTER);
        }
        if (ExampleHandler.SAMPLE_LIMIT > 0) {
            LOGGER.info("SAMPLE_LIMIT=" + ExampleHandler.SAMPLE_LIMIT);
        }
        ExampleHandler.ExampleData processingData = ExampleHandler.getNextData(false);
        while (processingData != null) {
            List<ExampleHandler.ExampleData> results = null;
            boolean analysisCrashed = false;
            try {
                results = processSingleExample(processingData);
            } catch (Throwable t) {
                analysisCrashed = true;
                LOGGER.log(Level.SEVERE,
                        "analysis crashed on sample " + processingData.getMethodName()
                                + " in " + processingData.getRepo_id() + "; marking label=-1 and continuing",
                        t);
            }
            if (results == null || results.isEmpty()) {
                // -1 = analysis-side failure (JDT / LLM / our bug); -2 = repo unavailable
                // (clone or checkout failed, typically network). Rerun -2 entries after
                // fixing connectivity; -1 entries are method-level issues.
                int label = analysisCrashed ? -1 : -2;
                LOGGER.warning("no usable result for " + processingData.getMethodName()
                        + " in " + processingData.getRepo_id() + "; marking label=" + label);
                ExampleHandler.ExampleData failed = new ExampleHandler.ExampleData(processingData);
                failed.setLabel(label);
                results = List.of(failed);
            }
            ExampleHandler.addResults(results);
            ExampleHandler.writeResultData();
            processingData = ExampleHandler.getNextData(false);
        }
        LOGGER.info("finished.");
    }

    private static void loggerInit() throws IOException{
        
        LOGGER.setLevel(Level.ALL);
        
        Handler fileHandler = new FileHandler("ExperimentExecutor_Main_" + EXPERIMENT_MARK + ".log", true);
        fileHandler.setLevel(Level.ALL); 
        
        fileHandler.setFormatter(new SimpleFormatter());
        
        LOGGER.addHandler(fileHandler);
        LOGGER.setLevel(Level.ALL); 
    }

     




    private static List<ExampleHandler.ExampleData> processSingleExample(ExampleHandler.ExampleData data) throws IOException {
        String repoID = data.getRepo_id();
        String commitHash = RepoHandler.getCommitHashFromPatch(data.getPatch());
        String method_name = data.getMethod_name();
        
        LOGGER.info("start project generation on example: " + data.getMethodName());
//        String name = repoID.replaceAll("/", "_") + commitHash.substring(0, 7) + method_name;
        List<String> generatedFiles = ProjectHandler.generateCommitParentHistoryFiles(
                RepoHandler.getRepository(repoID),
                commitHash);
        if (generatedFiles == null) {
            LOGGER.warning("data with repo_id: " + repoID +" and commitHash: " + commitHash + "and name: " + data.getMethodName() + " process failed");
            return null;
        }

        
        LOGGER.info("start parse on example: " + data.getMethodName());
        ProjectParser projectParser = new ProjectParser(ProjectHandler.TMP_PROJECT_PATH);
        ExceptionCharacteristicManager exceptionCharacteristicManager = new ExceptionCharacteristicManager();

        
        List<TreeGenerator> treeGenerators = new ArrayList<>(); 
        List<IMethodBinding> targetMethods = new ArrayList<>(); 
        treeGenerators.add(new TreeGenerator(projectParser, exceptionCharacteristicManager));
        targetMethods.add(treeGenerators.get(0).getMethodBindingByFileAndName(data.getFile_path(), data.getMethodName()));
        List<ExampleHandler.ExampleData> exampleData = new ArrayList<>(List.of(new ExampleHandler.ExampleData(data))); 

        
        LOGGER.info("start experiment on example: " + data.getMethodName());
        List<ExampleHandler.ExampleData> results = new ArrayList<>();
        for (int i = 0; i < targetMethods.size(); i++) {
            if (treeGenerators.get(i) == null || targetMethods.get(i) == null || exampleData.get(i) == null) {
                ExampleHandler.ExampleData data1 = new ExampleHandler.ExampleData(exampleData.get(i));
                LOGGER.warning("experiment failed on example: " + data.getMethodName());
                data1.setLabel(-1);
                results.add(data1);
                continue;
            }
            ExampleHandler.ExampleData result = singleExperiment(treeGenerators.get(i), targetMethods.get(i), exampleData.get(i), exceptionCharacteristicManager);
            results.add(result);
        }
        List<ExampleHandler.ExampleData> processedResults = new ArrayList<>();
        for (ExampleHandler.ExampleData result: results) {
            if (result == null) continue;
            processedResults.add(analyzeExperimentDiff(result));
        }
        LOGGER.info("finish example: " + data.getMethodName());
        return processedResults;
    }

     




    private static List<ExampleHandler.ExampleData> processExample(ExampleHandler.ExampleData data) {
        String repoID = data.getRepo_id();
        String commitHash = RepoHandler.getCommitHashFromPatch(data.getPatch());
        
        LOGGER.info("start project generation on example: " + data.getMethodName());
        List<String> generatedFiles = ProjectHandler.generateCommitParentHistoryFiles(
                RepoHandler.getRepository(repoID),
                commitHash);
        if (generatedFiles == null) {
            LOGGER.warning("data with repo_id: " + repoID +" and commitHash: " + commitHash + "and name: " + data.getMethodName() + " process failed");
            return null;
        }

        
        LOGGER.info("start parse on example: " + data.getMethodName());
        ProjectParser projectParser = new ProjectParser(ProjectHandler.TMP_PROJECT_PATH);
        ExceptionCharacteristicManager exceptionCharacteristicManager = new ExceptionCharacteristicManager();

        
        List<TreeGenerator> treeGenerators = new ArrayList<>(); 
        List<IMethodBinding> targetMethods = new ArrayList<>(); 
        for (int i = 0; i < 3; i++) {
            treeGenerators.add(new TreeGenerator(projectParser, exceptionCharacteristicManager));
        }
        targetMethods.add(treeGenerators.get(0).getMethodBindingByFileAndName(data.getFile_path(), data.getMethodName()));
        List<IMethodBinding> negativeExamples = treeGenerators.get(0).getNegativeMethods();
        List<ExampleHandler.ExampleData> exampleData = new ArrayList<>(List.of(new ExampleHandler.ExampleData(data))); 
        exampleData.get(0).setLabel(1);
        if (!(negativeExamples == null || negativeExamples.isEmpty())) {
            Collections.shuffle(negativeExamples);
            if (!negativeExamples.isEmpty()) {
                targetMethods.add(negativeExamples.get(0));
                exampleData.add(new ExampleHandler.ExampleData(data, targetMethods.get(1)));
                if (negativeExamples.size() > 1) {
                    targetMethods.add(negativeExamples.get(1));
                    exampleData.add(new ExampleHandler.ExampleData(data, targetMethods.get(2)));
                }
            }
        }

        
        LOGGER.info("start experiment on example: " + data.getMethodName());
        List<ExampleHandler.ExampleData> results = new ArrayList<>();
        for (int i = 0; i < targetMethods.size(); i++) {
            if (treeGenerators.get(i) == null || targetMethods.get(i) == null || exampleData.get(i) == null) {
                ExampleHandler.ExampleData data1 = new ExampleHandler.ExampleData(exampleData.get(i));
                data1.setLabel(-1);
                results.add(data1);
                continue;
            }
            ExampleHandler.ExampleData result = singleExperiment(treeGenerators.get(i), targetMethods.get(i), exampleData.get(i), exceptionCharacteristicManager);
            results.add(result);
        }
        List<ExampleHandler.ExampleData> processedResults = new ArrayList<>();
        for (ExampleHandler.ExampleData result: results) {
            if (result == null) continue;
            processedResults.add(analyzeExperimentDiff(result));
        }
        LOGGER.info("finish example: " + data.getMethodName());
        return processedResults;
    }
    private static ExampleHandler.ExampleData singleExperiment(TreeGenerator treeGenerator, IMethodBinding targetMethod, ExampleHandler.ExampleData data, ExceptionCharacteristicManager exceptionCharacteristicManager) {
        
        LOGGER.info("start analyze： " + targetMethod.getName());
        treeGenerator.methodBinding2graph(targetMethod);
        DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph = treeGenerator.getGraph();
        MethodTreeNode root = treeGenerator.getRoot();
        if (data.getLabel() == 0) {
            data.setMethodBefore(root.getCode());
            data.setMethodAfter(root.getCode());
        }

        
        LOGGER.info("start find exception: " + targetMethod.getName());
        GraphNodeTraversal graphNodeTraversal = new GraphNodeTraversal(exceptionCharacteristicManager);
        List<UncaughtExceptionInfo> exceptionResults = graphNodeTraversal.getSuspiciousThrows(graph, root, UNCAUGHT_BY_LLM);
        Integer layer = 10;
        while(STATIC_CANDIDATE_CAP > 0 && exceptionResults.size() > STATIC_CANDIDATE_CAP && layer > 3) {
            Integer finalLayer = layer;
            exceptionResults.removeIf(info -> info.getNodeRoute().size() - 1 > finalLayer);
            layer --;
        }
        LOGGER.fine("root method:\n" + root.getCode());
        StringBuilder sb = new StringBuilder();
        sb.append("found uncaught exceptions:\n");
        for (UncaughtExceptionInfo exceptionResult : exceptionResults) {
            sb.append(exceptionResult);
        }
        LOGGER.fine(sb.toString());

        
        Advisers advisers = new Advisers(data);
        LOGGER.info("start LLM call on method judgment");
        List<UncaughtExceptionInfo> checkedExceptionResults = exceptionResults.parallelStream()
                .peek(uncaughtExceptionInfo -> {
                    advisers.handleUncaughtExceptionInfo(uncaughtExceptionInfo, EXPERIMENT_MARK);
                })
                .filter(UncaughtExceptionInfo::isContainUncaughtExceptions)
                .collect(Collectors.toList());
        if (checkedExceptionResults.isEmpty()) {
            if (TRIAGE_EMPTY_FALLBACK) {
                
                LOGGER.info("triage left no candidates; TRIAGE_EMPTY_FALLBACK=true, falling back to full set: " + targetMethod.getName());
                checkedExceptionResults = exceptionResults;
            } else if (TRIAGE_EMPTY_USE_BASELINE) {
                
                
                
                LOGGER.info("triage left no candidates; falling back to LLM-baseline (TRIAGE_EMPTY_USE_BASELINE=true): " + targetMethod.getName());
                
            } else {
                
                
                
                
                LOGGER.info("triage left no candidates; skipping main question (TRIAGE_EMPTY_FALLBACK=false, TRIAGE_EMPTY_USE_BASELINE=false): " + targetMethod.getName());
                data.setMethodResult(root.getCode());
                data.setNeedLLM("no");
                LOGGER.info("finish analyze： " + targetMethod.getName());
                return data;
            }
        }

        
        LOGGER.info("start LLM call on main question");
        Map<String, String> mainQuestionResult = advisers.handleMainQuestion(root, checkedExceptionResults, EXPERIMENT_MARK);
        if (mainQuestionResult == null) {
            
            
            data.setLabel(0);
            data.setNeedLLM("error");
            data.setMethodResult(root.getCode());
            LOGGER.warning("LLM main question abort after retries, marking needLLM=error: " + root.getSimpleName());
        } else {
            
            
            String rawResult = mainQuestionResult.get("result");
            String need = mainQuestionResult.getOrDefault("need", "").trim().toLowerCase();
            data.setNeedLLM(need);
            if (rawResult == null || rawResult.trim().isEmpty()) {
                if ("no".equals(need)) {
                    
                    data.setMethodResult(root.getCode());
                    LOGGER.info("LLM <need>=no, reusing methodBefore: " + root.getSimpleName());
                } else {
                    
                    data.setMethodResult(root.getCode());
                    LOGGER.warning("LLM <result> empty without <need>=no (need='" + need + "'), reusing methodBefore: " + root.getSimpleName());
                }
            } else {
                data.setMethodResult(LLMResponse.stripCode(rawResult));
            }
        }

        LOGGER.info("finish analyze： " + targetMethod.getName());
        return data;
    }
    public static ExampleHandler.ExampleData analyzeExperimentDiff(ExampleHandler.ExampleData data) {
        // Repair-quality evaluation is fingerprint-based. MethodDiffAnalyzer is a
        // legacy line-matching path and can miss valid repairs after formatting /
        // reordering, so we skip it here. enrichExampleData fills the raw try-body
        // fingerprint fields plus the three catch-type fields (exceptionTypesAll /
        // resultExceptionTypesAll / beforeExceptionTypes), so the emitted JSON is
        // directly consumable by the eval scripts without an offline ReanalyzeOutput pass.
        org.ASTAnalyzer.ReanalyzeOutput.enrichExampleData(data);
        // Compatibility only: changed no longer means "line-diff found a try".
        // Formal detection uses needLLM/nativeDetected, and formal repair metrics
        // use resultRawTryBodyFps/resultExceptionTypesAll.
        data.setChanged(Objects.equals(data.getMethodBefore(), data.getMethodResult()) ? 0 : 1);
        return data;
    }

    private static void mainContent() {
        
        Map<String, String> repo = new HashMap<>();
        repo.put("repo_id", "apache/beam");
        repo.put("patch", "<Patch - https://github.com/apache/beam/commit/857eccedc5544ed920e9d445d18b1de74843488d>");
        repo.put("file_path", "sdks/java/core/src/main/java/org/apache/beam/sdk/metrics/Lineage.java");
        repo.put("methodName", "query(MetricResults,Type)");

        
        LOGGER.info("start project generation");
        ProjectHandler.generateCommitParentHistoryFiles(
                RepoHandler.getRepository(repo.get("repo_id")),
                RepoHandler.getCommitHashFromPatch(repo.get("patch")));

        
        LOGGER.info("start parse");
        ProjectParser projectParser = new ProjectParser(ProjectHandler.TMP_PROJECT_PATH);
        ExceptionCharacteristicManager exceptionCharacteristicManager = new ExceptionCharacteristicManager();
        TreeGenerator treeGenerator = new TreeGenerator(projectParser, exceptionCharacteristicManager);
//        IMethodBinding targetMethod = treeGenerator.getMethodBindingByQualifiedName(methodQualifiedName);
        IMethodBinding targetMethod = treeGenerator.getMethodBindingByFileAndName(repo.get("file_path"), repo.get("methodName"));

        
        LOGGER.info("start analyze");
        treeGenerator.methodBinding2graph(targetMethod);
        DefaultDirectedGraph<MethodTreeNode, MethodCallEdge> graph = treeGenerator.getGraph();
        MethodTreeNode root = treeGenerator.getRoot();

        
        LOGGER.info("start find exception");
        GraphNodeTraversal graphNodeTraversal = new GraphNodeTraversal(exceptionCharacteristicManager);
        List<UncaughtExceptionInfo> exceptionResults = graphNodeTraversal.getSuspiciousThrows(graph, root);
        LOGGER.fine("root method:\n" + root.getCode());
        StringBuilder sb = new StringBuilder();
        sb.append("found uncaught exceptions:\n");
        for (UncaughtExceptionInfo exceptionResult : exceptionResults) {
            sb.append(exceptionResult);
        }
        LOGGER.fine(sb.toString());

        
        LOGGER.info("start LLM call on method judgment");
        List<UncaughtExceptionInfo> checkedExceptionResults = exceptionResults.parallelStream()
                .peek(uncaughtExceptionInfo -> {
                    Advisers.handleUncaughtExceptionInfo(uncaughtExceptionInfo);
                })
                .filter(UncaughtExceptionInfo::isContainUncaughtExceptions)
                .collect(Collectors.toList());
        if (checkedExceptionResults.isEmpty()) {
            checkedExceptionResults = exceptionResults;
        }

        
        LOGGER.info("start LLM call on main question");
        Map<String, String> mainQuestionResult = Advisers.handleMainQuestion(root, checkedExceptionResults);
        System.out.println(mainQuestionResult);
    }

    private static void test() {
        String result = RepoHandler.getRepository("apache/beam");
        System.out.println(result);
    }
}

package org.ExperimentExecutor;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.callTreeGenerator.ExceptionCharacteristicManager;
import org.callTreeGenerator.Util;
import org.eclipse.jdt.core.dom.IMethodBinding;

import java.io.*;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Logger;

 


public class ExampleHandler {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    public static String EXAMPLE_REPO_PATH;
    public static String EXAMPLE_RESULT_PATH;
    public static String EXPERIMENT_DATA_PATH;
    public static Set<String> REPO_FILTER = Collections.emptySet();
    public static int SAMPLE_LIMIT = 0;
    private static List<ExampleData> rawExampleData;
    private static List<ExampleData> rawResultData;
    private static Map<String, List<ExampleData>> exampleData = new HashMap<>(); 
    private static Map<String, Set<String>> experimentDataFiles = new HashMap<>(); 

    public static final boolean LOAD_FINAL_RESULT = false;
    // Used only by LLMExperiment baseline (when LOAD_FINAL_RESULT=true). Points to the same
    // sample set the main pipeline reads, so the LLM-only baseline runs on identical inputs.
    // Relative to java-scanner/ working directory (cwd at launch).
    public static final String EXPERIMENT_FINAL_RESULT_PATH = "../data/1594_input_data.json";
    public static final String LLM_BASELINE_OUTPUT_PATH = "../data/E0502_llm_baseline_output.json";
    private static List<ExampleData> rawFinalResultData;
    private static List<ExampleData> rawLLMResultData;


    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream(System.getenv("CONFIG_FILE") != null ? System.getenv("CONFIG_FILE") : "config.properties")) {
            prop.load(fis);
            EXAMPLE_REPO_PATH = prop.getProperty("EXPERIMENT_SOURCE");
            EXAMPLE_RESULT_PATH = prop.getProperty("EXPERIMENT_TARGET");
            EXPERIMENT_DATA_PATH = prop.getProperty("EXPERIMENT_INTERMEDIATE_PROCESS_DATA_PATH");
            String repoFilter = prop.getProperty("REPO_FILTER", "").trim();
            if (!repoFilter.isEmpty()) {
                REPO_FILTER = new HashSet<>(Arrays.asList(repoFilter.split("\\s*,\\s*")));
            }
            try {
                SAMPLE_LIMIT = Integer.parseInt(prop.getProperty("SAMPLE_LIMIT", "0").trim());
            } catch (NumberFormatException nfe) {
                SAMPLE_LIMIT = 0;
            }
        } catch (IOException e) {
            LOGGER.severe("config loading failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
        
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(EXAMPLE_REPO_PATH)) {
            Type type = new TypeToken<List<ExampleData>>() {}.getType();
            rawExampleData = gson.fromJson(reader, type);
            LOGGER.info("loaded examples from: " + EXAMPLE_REPO_PATH);
        } catch (IOException e) {
            LOGGER.severe("example load error: " + e.getMessage());
            throw new RuntimeException(e);
        }
        for (ExampleData methodData: rawExampleData) {
            if (exampleData.containsKey(methodData.getRepo_id())) {
                exampleData.get(methodData.getRepo_id()).add(methodData);
            } else {
                exampleData.put(methodData.getRepo_id(), new ArrayList<>(List.of(methodData)));
            }
        }
        File file = new File(EXAMPLE_RESULT_PATH);
        if (!file.exists()) {
            LOGGER.warning("Target file does not exist: " + EXAMPLE_RESULT_PATH + ", creating new empty JSON file");
            try {
                
                file.getParentFile().mkdirs();
                
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write("[]");
                }
                LOGGER.info("Created new empty JSON file: " + EXAMPLE_RESULT_PATH);
            } catch (IOException e) {
                LOGGER.severe("Failed to create new JSON file: " + e.getMessage());
                throw new RuntimeException(e);
            }
            
            rawResultData = new ArrayList<ExampleData>();
        } else {
            
            try (FileReader reader = new FileReader(EXAMPLE_RESULT_PATH)) {
                Type type = new TypeToken<List<ExampleData>>() {}.getType();
                rawResultData = gson.fromJson(reader, type);
                LOGGER.info("Loaded examples from: " + EXAMPLE_RESULT_PATH);
            } catch (IOException e) {
                LOGGER.severe("Example load error: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }
        updateExperimentDataRepo();
        if (LOAD_FINAL_RESULT) {
            try (FileReader reader = new FileReader(EXPERIMENT_FINAL_RESULT_PATH)) {
                Type type = new TypeToken<List<ExampleData>>() {}.getType();
                rawFinalResultData = gson.fromJson(reader, type);
                LOGGER.info("loaded examples from: " + EXPERIMENT_FINAL_RESULT_PATH);
            } catch (IOException e) {
                LOGGER.severe("example load error: " + e.getMessage());
                throw new RuntimeException(e);
            }

            try (FileReader reader = new FileReader(LLM_BASELINE_OUTPUT_PATH)) {
                Type type = new TypeToken<List<ExampleData>>() {}.getType();
                rawLLMResultData = gson.fromJson(reader, type);
                LOGGER.info("loaded examples from: " + LLM_BASELINE_OUTPUT_PATH);
            } catch (IOException e) {
                LOGGER.severe("example load error: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }
    }

    public static List<ExampleData> getRawFinalResultData() {
        return rawFinalResultData;
    }

     
    public static List<ExampleData> getRawExampleData() {
        return rawExampleData;
    }

     
    public static List<ExampleData> getResultDataIfAvailable() {
        return rawResultData == null ? java.util.Collections.emptyList() : rawResultData;
    }

    public static List<ExampleData> getRawLLMResultData() {
        return rawLLMResultData;
    }

    public static void addRawLLMResultData(ExampleData data) {
        rawLLMResultData.add(data);
    }

     



    public static ExampleData getNextData(boolean withFilter) {
        if (SAMPLE_LIMIT > 0 && rawResultData != null && rawResultData.size() >= SAMPLE_LIMIT) {
            LOGGER.info("SAMPLE_LIMIT=" + SAMPLE_LIMIT + " reached, stopping.");
            return null;
        }
        if (!withFilter) {
            for (ExampleData exampleData1: rawExampleData) {
                if (dataInResult(exampleData1)) continue;
                if (!REPO_FILTER.isEmpty() && !REPO_FILTER.contains(exampleData1.getRepo_id())) continue;
                return exampleData1;
            }
            return null;
        }
        for (ExampleData exampleData1: rawExampleData) {
            if (dataInResult(exampleData1)) continue;
            if (!REPO_FILTER.isEmpty() && !REPO_FILTER.contains(exampleData1.getRepo_id())) continue;

            
            if (exampleData1.getRepo_id().equals("junit-team/junit5") ||
            exampleData1.getRepo_id().equals("OpenLiberty/open-liberty") ||
            exampleData1.getRepo_id().equals("opensourceBIM/BIMserver") ||
            exampleData1.getRepo_id().equals("openjdk/jdk")){
                continue;
            }

            List<String> exceptionTypes = exampleData1.getExceptionTypes();
            boolean runtimeFlag = false;
            for (String exception: exceptionTypes) {
                if (ExceptionCharacteristicManager.isArchivedRuntime(exception)) {
                    runtimeFlag = true;
                    break;
                }
            }
            if (!runtimeFlag) continue;
            return exampleData1;
        }
        return null;
    }

    public static ExampleData getDataByKeys(String targetRepoId, String targetPatch, String targetMethodName) {
        for (ExampleData resultData : rawResultData) {
            if (targetRepoId.equals(resultData.getRepo_id()) &&
                    targetPatch.equals(resultData.getPatch()) &&
                    targetMethodName.equals(resultData.getMethodName())) {
                return resultData;
            }
        }
        return null;
    }

     




    public static boolean dataInResult(ExampleData data) {
        if (data == null || rawResultData == null || rawResultData.isEmpty()) {
            return false;
        }

        String targetRepoId = data.getRepo_id();
        String targetPatch = data.getPatch();
        String targetMethodName = data.getMethodName();

        
        if (targetRepoId == null || targetPatch == null || targetMethodName == null) {
            return false;
        }

        for (ExampleData resultData : rawResultData) {
            if (targetRepoId.equals(resultData.getRepo_id()) &&
                    targetPatch.equals(resultData.getPatch()) &&
                    targetMethodName.equals(resultData.getMethodName())) {
                return true;
            }
        }

        return false;
    }

    public static boolean dataInLLMResult(ExampleData data) {
        if (data == null || rawLLMResultData == null || rawLLMResultData.isEmpty()) {
            return false;
        }

        String targetRepoId = data.getRepo_id();
        String targetPatch = data.getPatch();
        String targetMethodName = data.getMethodName();

        
        if (targetRepoId == null || targetPatch == null || targetMethodName == null) {
            return false;
        }

        for (ExampleData resultData : rawLLMResultData) {
            if (targetRepoId.equals(resultData.getRepo_id()) &&
                    targetPatch.equals(resultData.getPatch()) &&
                    targetMethodName.equals(resultData.getMethodName())) {
                return true;
            }
        }

        return false;
    }

     


    private static void updateExperimentDataRepo() {
        File rootDir = new File(EXPERIMENT_DATA_PATH);
        if (!rootDir.exists() || !rootDir.isDirectory()) {
            LOGGER.severe("Invalid EXPERIMENT_DATA_PATH: " + EXPERIMENT_DATA_PATH);
            throw new IllegalArgumentException("Invalid root directory path: " + EXPERIMENT_DATA_PATH);
        }

        
        for (File firstLevelDir : Objects.requireNonNull(rootDir.listFiles(File::isDirectory))) {
            String firstLevelKey = firstLevelDir.getName();
            if (!experimentDataFiles.containsKey(firstLevelKey)) {
                experimentDataFiles.put(firstLevelKey, new HashSet<>());
            }
            
            for (File file : Objects.requireNonNull(firstLevelDir.listFiles(File::isDirectory))) {
                String fileName = file.getName();
                experimentDataFiles.get(firstLevelKey).add(fileName);
            }
        }
    }

     



    public static void writeResultData() throws IOException {
        
        File outputFile = new File(EXAMPLE_RESULT_PATH);
        Gson gson = new GsonBuilder()
                .setPrettyPrinting()  
                .disableHtmlEscaping() 
                .create();
        Path filePath = outputFile.toPath();
        Path tempPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        Path backupPath = filePath.resolveSibling(filePath.getFileName() + ".bak");

        try {
            
            try (Writer writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
                gson.toJson(rawResultData, writer);
            }

            
            if (Files.exists(filePath)) {
                Files.move(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }

            
            Files.move(tempPath, filePath, StandardCopyOption.ATOMIC_MOVE);

            
            Files.deleteIfExists(backupPath);

        } catch (Exception e) {
            
            if (Files.exists(backupPath)) {
                try {
                    Files.move(backupPath, filePath, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception restoreEx) {
                    e.addSuppressed(restoreEx);
                }
            }
            throw new IOException("Failed to safely write JSON file", e);

        } finally {
            
            Files.deleteIfExists(tempPath);
        }
    }

    public static void writeData2File(List<ExampleHandler.ExampleData> resultData, String path) throws IOException {
        
        File outputFile = new File(path);
        Gson gson = new GsonBuilder()
                .setPrettyPrinting()  
                .disableHtmlEscaping() 
                .create();
        Path filePath = outputFile.toPath();
        Path tempPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        Path backupPath = filePath.resolveSibling(filePath.getFileName() + ".bak");

        try {
            
            try (Writer writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
                gson.toJson(resultData, writer);
            }

            
            if (Files.exists(filePath)) {
                Files.move(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }

            
            Files.move(tempPath, filePath, StandardCopyOption.ATOMIC_MOVE);

            
            Files.deleteIfExists(backupPath);

        } catch (Exception e) {
            
            if (Files.exists(backupPath)) {
                try {
                    Files.move(backupPath, filePath, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception restoreEx) {
                    e.addSuppressed(restoreEx);
                }
            }
            throw new IOException("Failed to safely write JSON file", e);

        } finally {
            
            Files.deleteIfExists(tempPath);
        }
    }

//    public static boolean dataInExperimentResult(ExampleData data){
//        updateExperimentDataRepo();
//        if (!experimentDataFiles.containsKey(data.getRepo_id().replaceAll("/", "_"))) {
//            return false;
//        }
//        return experimentDataFiles.get(data.getRepo_id().replaceAll("/", "_"))
//                .contains(data.getQualifiedName());
//    }

    public static boolean isFileExists(String absolutePath) {
        Path path = Paths.get(absolutePath);
        return Files.exists(path) && Files.isRegularFile(path);
    }

    public static String getDataRecord(ExampleData data, String name) {
        String filePath = data.getDataPath() + File.separator + name + ".json";
        if (! isFileExists(filePath)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(filePath));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warning("failed read data record: " + filePath);
        }
        return null;
    }

    public static List<String> disassemblyJsonListString(String string) {
        
        JsonArray jsonArray = JsonParser.parseString(string).getAsJsonArray();

        List<String> jsonObjectStrings = new ArrayList<>();

        
        for (int i = 0; i < jsonArray.size(); i++) {
            JsonObject jsonObject = jsonArray.get(i).getAsJsonObject();
            
            jsonObjectStrings.add(jsonObject.toString());
        }

        return jsonObjectStrings;
    }

    public static boolean writeDataRecord(ExampleData data, String content, String name) {
        String filePath = data.getDataPath() + File.separator + name + ".json";
        try {
            Path path = Paths.get(filePath);
            
            Files.createDirectories(path.getParent());
            Files.write(path,
                    content.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            LOGGER.warning("failed save data: " + filePath + ", reason: " + e.getMessage());
        }
        return false;
    }

    public static String md5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));

            BigInteger number = new BigInteger(1, hashBytes);
            StringBuilder hexString = new StringBuilder(number.toString(16));

            while (hexString.length() < 32) {
                hexString.insert(0, '0');
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm is unavailable", e);
        }
    }

    public static Map<String, List<ExampleData>> getExampleData() {
        return exampleData;
    }

    public static Map<String, Set<String>> getExperimentDataFiles() {
        return experimentDataFiles;
    }

    public static void addResults(List<ExampleData> results) {
        if (results == null) return;
        rawResultData.addAll(results);
    }

    public static class ExampleData {
        private int afterTargetStartLine;
        private int beforeTargetEndLine;
        private List<Integer> afterTargetNoNestingLines;
        private String methodBefore;
        private List<String> exceptionTypes;
        private List<Integer> beforeTargetNoNestingLines;
        private int afterTargetEndLine;
        private List<String> catchBlocks;
        private String methodName;
        private String methodAfter;
        private int beforeTargetStartLine;
        private String repo_id;
        private String patch;
        private String file_path;
        private String method_name;
        private String methodResult; 
        private String needLLM; 
        private int label; 
        private int changed;
        private int resultAfterTargetStartLine;
        private int resultAfterTargetEndLine;
        private int resultBeforeTargetStartLine;
        private int resultBeforeTargetEndLine;
        private List<Integer> resultBeforeTargetNoNestingLines;
        private List<Integer> resultAfterTargetNoNestingLines;
        
        private List<Integer> resultCoveredLines;
        
        
        
        
        private List<String> afterTargetTryBodyFps;
        private List<String> resultRawTryBodyFps;
        
        
        private List<String> exceptionTypesAll;
        private List<String> resultExceptionTypesAll;
        private List<String> beforeExceptionTypes;
        private List<String> resultExceptionTypes;
        private List<String> resultCatchBlocks;

        public ExampleData(ExampleData other) {
            
            this.afterTargetStartLine = other.afterTargetStartLine;
            this.beforeTargetEndLine = other.beforeTargetEndLine;
            this.afterTargetEndLine = other.afterTargetEndLine;
            this.beforeTargetStartLine = other.beforeTargetStartLine;
            this.label = other.label;

            
            this.methodBefore = other.methodBefore;
            this.methodName = other.methodName;
            this.methodAfter = other.methodAfter;
            this.repo_id = other.repo_id;
            this.patch = other.patch;
            this.file_path = other.file_path;
            this.method_name = other.method_name;
            this.methodResult = other.methodResult;

            
            this.afterTargetNoNestingLines = other.afterTargetNoNestingLines != null ?
                    new ArrayList<>(other.afterTargetNoNestingLines) : null;
            this.exceptionTypes = other.exceptionTypes != null ?
                    new ArrayList<>(other.exceptionTypes) : null;
            this.beforeTargetNoNestingLines = other.beforeTargetNoNestingLines != null ?
                    new ArrayList<>(other.beforeTargetNoNestingLines) : null;
            this.catchBlocks = other.catchBlocks != null ?
                    new ArrayList<>(other.catchBlocks) : null;
        }

        public ExampleData(ExampleData other, IMethodBinding binding) {
            this.label = 0;
            this.afterTargetStartLine = 0;
            this.beforeTargetEndLine = 0;
            this.afterTargetEndLine = 0;
            this.beforeTargetStartLine = 0;
            this.methodBefore = "";
            this.methodName = Util.generateMethodName(binding);
            this.methodAfter = "";
            this.repo_id = other.repo_id;
            this.patch = other.patch;
            this.file_path = other.file_path;
            this.method_name = binding.getName();
            this.methodResult = "";
            this.afterTargetNoNestingLines = new ArrayList<>();
            this.exceptionTypes = new ArrayList<>();
            this.beforeTargetNoNestingLines = new ArrayList<>();
            this.catchBlocks = new ArrayList<>();
        }

        public int getAfterTargetStartLine() {
            return afterTargetStartLine;
        }

        public int getBeforeTargetEndLine() {
            return beforeTargetEndLine;
        }

        public List<Integer> getAfterTargetNoNestingLines() {
            return afterTargetNoNestingLines;
        }

        public String getMethodBefore() {
            return methodBefore;
        }

        public List<String> getExceptionTypes() {
            return exceptionTypes;
        }

        public List<Integer> getBeforeTargetNoNestingLines() {
            return beforeTargetNoNestingLines;
        }

        public int getAfterTargetEndLine() {
            return afterTargetEndLine;
        }

        public List<String> getCatchBlocks() {
            return catchBlocks;
        }

        public String getMethodName() {
            return methodName;
        }

        public String getMethodAfter() {
            return methodAfter;
        }

        public int getBeforeTargetStartLine() {
            return beforeTargetStartLine;
        }

        public String getRepo_id() {
            return repo_id;
        }

        public String getPatch() {
            return patch;
        }

        public String getFile_path() {
            return file_path;
        }

        public String getMethod_name() {
            return method_name;
        }

        public void setMethodBefore(String methodBefore) {
            this.methodBefore = methodBefore;
        }

        public void setMethodAfter(String methodAfter) {
            this.methodAfter = methodAfter;
        }

        public int getChanged() {
            return changed;
        }

        public int getLabel() {
            return label;
        }

        public void setChanged(int changed) {
            this.changed = changed;
            if (changed == 0) {
                resultAfterTargetNoNestingLines = new ArrayList<>();
                resultBeforeTargetNoNestingLines = new ArrayList<>();
                resultCatchBlocks = new ArrayList<>();
                resultExceptionTypes = new ArrayList<>();
                resultAfterTargetEndLine = 0;
                resultAfterTargetStartLine = 0;
                resultBeforeTargetEndLine = 0;
                resultBeforeTargetStartLine = 0;
            }
        }

        public int getResultAfterTargetStartLine() {
            return resultAfterTargetStartLine;
        }

        public void setResultAfterTargetStartLine(int resultAfterTargetStartLine) {
            this.resultAfterTargetStartLine = resultAfterTargetStartLine;
        }

        public int getResultAfterTargetEndLine() {
            return resultAfterTargetEndLine;
        }

        public void setResultAfterTargetEndLine(int resultAfterTargetEndLine) {
            this.resultAfterTargetEndLine = resultAfterTargetEndLine;
        }

        public int getResultBeforeTargetStartLine() {
            return resultBeforeTargetStartLine;
        }

        public void setResultBeforeTargetStartLine(int resultBeforeTargetStartLine) {
            this.resultBeforeTargetStartLine = resultBeforeTargetStartLine;
        }

        public int getResultBeforeTargetEndLine() {
            return resultBeforeTargetEndLine;
        }

        public void setResultBeforeTargetEndLine(int resultBeforeTargetEndLine) {
            this.resultBeforeTargetEndLine = resultBeforeTargetEndLine;
        }

        public List<Integer> getResultBeforeTargetNoNestingLines() {
            return resultBeforeTargetNoNestingLines;
        }

        public List<Integer> getResultCoveredLines() {
            return resultCoveredLines;
        }

        public void setResultCoveredLines(List<Integer> resultCoveredLines) {
            this.resultCoveredLines = resultCoveredLines;
        }

        public List<String> getAfterTargetTryBodyFps() { return afterTargetTryBodyFps; }
        public void setAfterTargetTryBodyFps(List<String> v) { this.afterTargetTryBodyFps = v; }
        public List<String> getResultRawTryBodyFps() { return resultRawTryBodyFps; }
        public void setResultRawTryBodyFps(List<String> v) { this.resultRawTryBodyFps = v; }
        public List<String> getExceptionTypesAll() { return exceptionTypesAll; }
        public void setExceptionTypesAll(List<String> v) { this.exceptionTypesAll = v; }
        public List<String> getResultExceptionTypesAll() { return resultExceptionTypesAll; }
        public void setResultExceptionTypesAll(List<String> v) { this.resultExceptionTypesAll = v; }
        public List<String> getBeforeExceptionTypes() { return beforeExceptionTypes; }
        public void setBeforeExceptionTypes(List<String> v) { this.beforeExceptionTypes = v; }

        public void setResultBeforeTargetNoNestingLines(List<Integer> resultBeforeTargetNoNestingLines) {
            this.resultBeforeTargetNoNestingLines = resultBeforeTargetNoNestingLines;
        }

        public List<Integer> getResultAfterTargetNoNestingLines() {
            return resultAfterTargetNoNestingLines;
        }

        public void setResultAfterTargetNoNestingLines(List<Integer> resultAfterTargetNoNestingLines) {
            this.resultAfterTargetNoNestingLines = resultAfterTargetNoNestingLines;
        }

        public List<String> getResultExceptionTypes() {
            return resultExceptionTypes;
        }

        public void setResultExceptionTypes(List<String> resultExceptionTypes) {
            this.resultExceptionTypes = resultExceptionTypes;
        }

        public List<String> getResultCatchBlocks() {
            return resultCatchBlocks;
        }

        public void setResultCatchBlocks(List<String> resultCatchBlocks) {
            this.resultCatchBlocks = resultCatchBlocks;
        }

        public void setMethodResult(String methodResult) {
            this.methodResult = methodResult;
        }

        public void setNeedLLM(String needLLM) {
            this.needLLM = needLLM;
        }

        public String getNeedLLM() {
            return needLLM == null ? "" : needLLM;
        }

        public String getMethodResult() {
            String input = getRawMethodResult();
            
            boolean startsWithBlock = input.startsWith("```\n");
            boolean startsWithJavaBlock = input.startsWith("```java\n");

            
            boolean endsWithBlock = input.endsWith("\n```");

            String processed = input;

            
            if ((startsWithBlock || startsWithJavaBlock) && endsWithBlock) {
                if (startsWithJavaBlock) {
                    processed = processed.substring(7); 
                } else {
                    processed = processed.substring(4); 
                }
                processed = processed.substring(0, processed.length() - 3); 
            }

            
            if (!processed.endsWith("\n")) {
                processed += "\n";
            }
            return processed;
        }

        public String getRawMethodResult() {
            return Objects.requireNonNullElse(methodResult, "");
        }

        public void setLabel(int label) {
            this.label = label;
        }

        public String getQualifiedName() {
            return new StringBuilder()
                    .append(repo_id.replaceAll("/", "_"))
                    .append("+")
                    .append(RepoHandler.getCommitHashFromPatch(patch), 0, 7)
                    .append("+")
                    .append(method_name)
                    .toString();
        }

        public String getDataPath() {
            return new StringBuilder()
                    .append(EXPERIMENT_DATA_PATH)
                    .append(File.separator)
                    .append(getRepo_id().replaceAll("/", "_"))
                    .append(File.separator)
                    .append(getQualifiedName())
                    .toString();
        }

        @Override
        public String toString() {
            return "ExampleData{" +
                    "afterTargetStartLine=" + afterTargetStartLine +
                    ", beforeTargetEndLine=" + beforeTargetEndLine +
                    ", afterTargetNoNestingLines=" + afterTargetNoNestingLines +
                    ", methodBefore='" + methodBefore + '\'' +
                    ", exceptionTypes=" + exceptionTypes +
                    ", beforeTargetNoNestingLines=" + beforeTargetNoNestingLines +
                    ", afterTargetEndLine=" + afterTargetEndLine +
                    ", catchBlocks=" + catchBlocks +
                    ", methodName='" + methodName + '\'' +
                    ", methodAfter='" + methodAfter + '\'' +
                    ", beforeTargetStartLine=" + beforeTargetStartLine +
                    ", repo_id='" + repo_id + '\'' +
                    ", patch='" + patch + '\'' +
                    ", file_path='" + file_path + '\'' +
                    ", method_name='" + method_name + '\'' +
                    '}';
        }
    }
}

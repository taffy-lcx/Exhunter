package org.ExperimentExecutor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.LLMAdvisers.Advisers;
import org.LLMAdvisers.LLMResponse;

import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.logging.*;

 

















public class RunMainQuestionFromIntermediate {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    private static String EXPERIMENT_MARK;
    private static boolean TRIAGE_EMPTY_USE_BASELINE;
    private static double API_THRESHOLD;
    private static double THROW_THRESHOLD;
    
    
    private static boolean SHOW_TRIAGE_SCORES = false;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            prop.load(fis);
            EXPERIMENT_MARK = prop.getProperty("EXPERIMENT_MARK");
            TRIAGE_EMPTY_USE_BASELINE = Boolean.parseBoolean(
                    prop.getProperty("TRIAGE_EMPTY_USE_BASELINE", "true").trim());
            API_THRESHOLD = Double.parseDouble(prop.getProperty("TRIAGE_API_THRESHOLD", "0.3").trim());
            String tt = prop.getProperty("TRIAGE_THROW_THRESHOLD", "").trim();
            THROW_THRESHOLD = tt.isEmpty() ? API_THRESHOLD : Double.parseDouble(tt);
            SHOW_TRIAGE_SCORES = Boolean.parseBoolean(
                    prop.getProperty("MAIN_SHOW_TRIAGE_SCORES", "false").trim());
        } catch (IOException e) {
            throw new RuntimeException("config load failed: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws IOException {
        loggerInit();
        String inPath = args.length > 0 ? args[0] : "../data/intermediate_static_1594.json";
        LOGGER.info("RunMainQuestionFromIntermediate start (mark=" + EXPERIMENT_MARK
                + ", api_T=" + API_THRESHOLD + ", throw_T=" + THROW_THRESHOLD + ")");
        LOGGER.info("input: " + inPath);

        List<IntermediateRecord> records;
        try (FileReader r = new FileReader(inPath)) {
            Type tp = new TypeToken<List<IntermediateRecord>>(){}.getType();
            records = GSON.fromJson(r, tp);
        }
        if (records == null || records.isEmpty()) {
            throw new RuntimeException("empty / unparseable intermediate at " + inPath);
        }
        LOGGER.info("loaded " + records.size() + " intermediate records");

        
        Set<String> done = new HashSet<>();
        if (ExampleHandler.getRawExampleData() != null) {
            for (ExampleHandler.ExampleData d : org.ExperimentExecutor.ExampleHandler.getResultDataIfAvailable()) {
                done.add(sampleKey(d));
            }
        }
        LOGGER.info("output already has " + done.size() + " samples, will skip them");

        int idx = 0, processed = 0, skipped = 0;
        for (IntermediateRecord rec : records) {
            idx++;
            ExampleHandler.ExampleData ex = rec.example;
            if (ex == null) continue;
            String key = sampleKey(ex);
            if (done.contains(key)) { skipped++; continue; }

            LOGGER.info("[" + idx + "/" + records.size() + "] " + ex.getRepo_id() + " / " + ex.getMethodName());

            if (rec.staticError != null && !rec.staticError.isEmpty()) {
                
                if (ex.getLabel() == 0) ex.setLabel(-1);
                ExampleHandler.addResults(List.of(ex));
                ExampleHandler.writeResultData();
                processed++;
                continue;
            }

            try {
                runOne(rec);
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "Stage 2 crashed on " + ex.getMethodName(), t);
                ex.setLabel(-1);
                ex.setNeedLLM("error");
                ex.setMethodResult(rec.rootMethodCode != null ? rec.rootMethodCode : ex.getMethodBefore());
            }
            ExampleHandler.addResults(List.of(ex));
            ExampleHandler.writeResultData();
            processed++;
        }
        LOGGER.info("done. processed=" + processed + " skipped=" + skipped);
    }

     
    private static void runOne(IntermediateRecord rec) {
        ExampleHandler.ExampleData ex = rec.example;
        String methodCode = rec.rootMethodCode != null ? rec.rootMethodCode : ex.getMethodBefore();

        
        List<String> blocks = new ArrayList<>();
        for (IntermediateRecord.Candidate c : rec.candidates) {
            String body = formatBlockForCandidate(c);
            if (body != null) blocks.add(body);
        }

        if (blocks.isEmpty() && !TRIAGE_EMPTY_USE_BASELINE) {
            
            ex.setMethodResult(methodCode);
            ex.setNeedLLM("no");
            org.ASTAnalyzer.ReanalyzeOutput.enrichExampleData(ex);
            return;
        }

        String prompt = Advisers.constructQuestionPromptFromBlocks(methodCode, blocks);

        Advisers advisers = new Advisers(ex);
        String simpleName = extractSimpleName(ex.getMethodName());
        long tMainStart = System.currentTimeMillis();
        Map<String, String> mainQuestionResult = advisers.handleMainQuestionWithPrompt(prompt, simpleName, EXPERIMENT_MARK);
        long mainQMs = System.currentTimeMillis() - tMainStart;   
        appendPhaseTiming(sampleKey(ex), "main_q", mainQMs);

        if (mainQuestionResult == null) {
            ex.setLabel(0);
            ex.setNeedLLM("error");
            ex.setMethodResult(methodCode);
            LOGGER.warning("main question abort, needLLM=error: " + simpleName);
        } else {
            String rawResult = mainQuestionResult.get("result");
            String need = mainQuestionResult.getOrDefault("need", "").trim().toLowerCase();
            ex.setNeedLLM(need);
            if (rawResult == null || rawResult.trim().isEmpty()) {
                ex.setMethodResult(methodCode);
            } else {
                ex.setMethodResult(LLMResponse.stripCode(rawResult));
            }
        }

        org.ASTAnalyzer.ReanalyzeOutput.enrichExampleData(ex);
        
        ex.setChanged(java.util.Objects.equals(ex.getMethodBefore(), ex.getMethodResult()) ? 0 : 1);
    }

     
    private static String formatBlockForCandidate(IntermediateRecord.Candidate c) {
        if ("api".equals(c.kind)) {
            
            List<String> kept = new ArrayList<>();
            double maxScore = 0.0;
            if (c.apiExceptionScores != null) {
                for (IntermediateRecord.ApiExceptionScore es : c.apiExceptionScores) {
                    if (es.score >= API_THRESHOLD) {
                        
                        kept.add(SHOW_TRIAGE_SCORES
                                ? es.name + " (score " + String.format("%.2f", es.score) + ")"
                                : es.name);
                    }
                    if (es.score > maxScore) maxScore = es.score;
                }
            }
            if (kept.isEmpty()) return null;  
            String exceptionsString = String.join(", ", kept);
            return Advisers.formatApiBlockBodyRaw(
                    c.parentSimpleName, c.routeDepth, c.simpleName,
                    exceptionsString, c.callPathSerialized, c.description);
        } else if ("throw".equals(c.kind)) {
            List<String> keptTexts = new ArrayList<>();
            String firstDesc = null;
            if (c.throwStatements != null) {
                for (IntermediateRecord.ThrowStatementInfo si : c.throwStatements) {
                    if (si.score >= THROW_THRESHOLD) {
                        keptTexts.add(SHOW_TRIAGE_SCORES
                                ? si.text + " (score " + String.format("%.2f", si.score) + ")"
                                : si.text);
                        if (firstDesc == null && si.description != null && !si.description.isEmpty()) {
                            firstDesc = si.description;
                        }
                    }
                }
            }
            if (keptTexts.isEmpty()) return null;
            String throwsListStr = Advisers.listToStringStyle(keptTexts);
            
            
            return Advisers.formatThrowBlockBodyRaw(
                    c.parentSimpleName, c.routeDepth, c.simpleName,
                    throwsListStr, c.callPathSerialized, null);
        }
        return null;
    }

     
    private static String extractSimpleName(String methodNameWithSig) {
        if (methodNameWithSig == null) return "";
        int p = methodNameWithSig.indexOf('(');
        return p < 0 ? methodNameWithSig : methodNameWithSig.substring(0, p);
    }

    private static String sampleKey(ExampleHandler.ExampleData d) {
        return d.getRepo_id() + "|" + (d.getPatch() == null ? "" : d.getPatch().hashCode()) + "|" + d.getMethodName();
    }

    
    private static final String PHASE_TIMING_PATH = "../data/phase_timing_" + EXPERIMENT_MARK + ".jsonl";
    static synchronized void appendPhaseTiming(String sampleKey, String phase, long ms) {
        String line = "{\"sample_key\":\"" + sampleKey.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"phase\":\"" + phase + "\",\"ms\":" + ms + "}\n";
        try (java.io.FileWriter fw = new java.io.FileWriter(PHASE_TIMING_PATH, true)) {
            fw.write(line);
        } catch (IOException e) {
            LOGGER.warning("phase timing write failed: " + e.getMessage());
        }
    }

    private static void loggerInit() throws IOException {
        LOGGER.setLevel(Level.ALL);
        Handler fh = new FileHandler("RunMainQuestionFromIntermediate_" + EXPERIMENT_MARK + ".log", true);
        fh.setLevel(Level.ALL);
        fh.setFormatter(new SimpleFormatter());
        LOGGER.addHandler(fh);
    }
}

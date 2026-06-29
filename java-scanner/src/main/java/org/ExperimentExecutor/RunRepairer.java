package org.ExperimentExecutor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.LLMAdvisers.Advisers;
import org.LLMAdvisers.LLMResponse;

import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.logging.*;

 





public class RunRepairer {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    private static String EXPERIMENT_MARK;
    private static double API_THRESHOLD;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream(System.getenv("CONFIG_FILE") != null ? System.getenv("CONFIG_FILE") : "config.properties")) {
            prop.load(fis);
            EXPERIMENT_MARK = prop.getProperty("EXPERIMENT_MARK");
            API_THRESHOLD = Double.parseDouble(prop.getProperty("TRIAGE_API_THRESHOLD", "0.2").trim());
        } catch (IOException e) {
            throw new RuntimeException("config load failed: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws IOException {
        loggerInit();
        String dir = "../data/" + EXPERIMENT_MARK;
        new java.io.File(dir).mkdirs();
        String tstr = RunAnalyzer.threshStr(API_THRESHOLD);
        String inPath = args.length > 0 ? args[0] : dir + "/analyzed_" + tstr + ".json";
        
        ExampleHandler.EXAMPLE_RESULT_PATH = dir + "/output_" + tstr + ".json";
        LOGGER.info("RunRepairer start (mark=" + EXPERIMENT_MARK + ") in=" + inPath + " out=" + ExampleHandler.EXAMPLE_RESULT_PATH);

        List<AnalyzedRecord> recs;
        try (FileReader r = new FileReader(inPath)) {
            Type tp = new TypeToken<List<AnalyzedRecord>>(){}.getType();
            recs = GSON.fromJson(r, tp);
        }
        if (recs == null || recs.isEmpty()) throw new RuntimeException("empty analyzed: " + inPath);
        LOGGER.info("loaded " + recs.size() + " analyzed records");

        Set<String> done = new HashSet<>();
        for (ExampleHandler.ExampleData d : ExampleHandler.getResultDataIfAvailable()) done.add(sampleKey(d));
        LOGGER.info("output already has " + done.size() + " samples, skip them");

        int idx = 0, processed = 0;
        for (AnalyzedRecord ar : recs) {
            idx++;
            if (ar.example == null) continue;
            if (done.contains(sampleKey(ar.example))) continue;
            LOGGER.info("[" + idx + "/" + recs.size() + "] " + ar.example.getRepo_id() + " / " + ar.example.getMethodName());
            try {
                repairOne(ar);
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "repairer crashed: " + ar.example.getMethodName(), t);
                ar.example.setNeedLLM("error");
                ar.example.setMethodResult(ar.methodCode != null ? ar.methodCode : ar.example.getMethodBefore());
            }
            ExampleHandler.addResults(List.of(ar.example));
            ExampleHandler.writeResultData();
            processed++;
        }
        LOGGER.info("RunRepairer done. processed=" + processed);
    }

    private static void repairOne(AnalyzedRecord ar) {
        ExampleHandler.ExampleData ex = ar.example;
        String methodCode = ar.methodCode != null ? ar.methodCode : ex.getMethodBefore();

        if (ar.staticError != null && !ar.staticError.isEmpty()) {
            if (ex.getLabel() == 0) ex.setLabel(-1);
            return;  
        }

        String need = (ar.need == null ? "no" : ar.need.trim().toLowerCase());
        if (!need.equals("yes")) {
            
            ex.setNeedLLM("no");
            ex.setMethodResult(methodCode);
            org.ASTAnalyzer.ReanalyzeOutput.enrichExampleData(ex);
            ex.setChanged(0);
            return;
        }

        
        String prompt = Advisers.getRepairerQuestion()
                + "\n<code snippet>\n```\n" + methodCode + "\n```\n<end>\n\n"
                + "<repair plan>\n" + (ar.plan == null ? "" : ar.plan) + "\n<end>\n";
        String simpleName = extractSimpleName(ex.getMethodName());
        Advisers advisers = new Advisers(ex);
        String raw = advisers.cachedLLMCall("repairer", "repairer",
                Advisers.getRepairerSystem(), prompt, simpleName, EXPERIMENT_MARK);

        ex.setNeedLLM("yes");
        String code = null;
        if (raw != null) {
            try {
                String body = LLMResponse.stripJsonFence(raw);
                JsonElement el = JsonParser.parseString(body);
                if (el.isJsonObject() && el.getAsJsonObject().has("result")) {
                    JsonObject o = el.getAsJsonObject();
                    if (!o.get("result").isJsonNull()) code = o.get("result").getAsString();
                }
            } catch (RuntimeException e) {
                code = LLMResponse.extractField(raw, "result");
            }
        }
        if (code == null || LLMResponse.stripCode(code).trim().isEmpty()) {
            
            LOGGER.warning("repairer empty result, reuse methodBefore: " + simpleName);
            ex.setMethodResult(methodCode);
        } else {
            ex.setMethodResult(LLMResponse.stripCode(code));
        }
        org.ASTAnalyzer.ReanalyzeOutput.enrichExampleData(ex);
        ex.setChanged(java.util.Objects.equals(ex.getMethodBefore(), ex.getMethodResult()) ? 0 : 1);
    }

    private static String extractSimpleName(String mn) {
        if (mn == null) return "";
        int p = mn.indexOf('(');
        return p < 0 ? mn : mn.substring(0, p);
    }

    private static String sampleKey(ExampleHandler.ExampleData d) {
        return d.getRepo_id() + "|" + (d.getPatch() == null ? "" : d.getPatch().hashCode()) + "|" + d.getMethodName();
    }

    private static void loggerInit() throws IOException {
        LOGGER.setLevel(Level.ALL);
        Handler fh = new FileHandler("RunRepairer_" + EXPERIMENT_MARK + ".log", true);
        fh.setLevel(Level.ALL); fh.setFormatter(new SimpleFormatter());
        LOGGER.addHandler(fh);
    }
}

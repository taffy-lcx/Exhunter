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
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.logging.*;

 










public class RunAnalyzer {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    private static String EXPERIMENT_MARK;
    private static String ANALYZER_CACHE_PREFIX;
    private static double API_THRESHOLD, THROW_THRESHOLD;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream(System.getenv("CONFIG_FILE") != null ? System.getenv("CONFIG_FILE") : "config.properties")) {
            prop.load(fis);
            EXPERIMENT_MARK = prop.getProperty("EXPERIMENT_MARK");
            ANALYZER_CACHE_PREFIX = prop.getProperty("ANALYZER_CACHE_PREFIX", "analyzer").trim();
            API_THRESHOLD = Double.parseDouble(prop.getProperty("TRIAGE_API_THRESHOLD", "0.2").trim());
            String tt = prop.getProperty("TRIAGE_THROW_THRESHOLD", "").trim();
            THROW_THRESHOLD = tt.isEmpty() ? API_THRESHOLD : Double.parseDouble(tt);
        } catch (IOException e) {
            throw new RuntimeException("config load failed: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws IOException {
        loggerInit();
        String dir = "../data/" + EXPERIMENT_MARK;
        new java.io.File(dir).mkdirs();
        String tstr = threshStr(API_THRESHOLD);
        String inPath = args.length > 0 ? args[0] : dir + "/intermediate.json";
        String outPath = args.length > 1 ? args[1] : dir + "/analyzed_" + tstr + ".json";
        LOGGER.info("RunAnalyzer start (mark=" + EXPERIMENT_MARK + ", api_T=" + API_THRESHOLD
                + ", cache_prefix=" + ANALYZER_CACHE_PREFIX + ")");
        LOGGER.info("in=" + inPath + " out=" + outPath);

        List<IntermediateRecord> records;
        try (FileReader r = new FileReader(inPath)) {
            Type tp = new TypeToken<List<IntermediateRecord>>(){}.getType();
            records = GSON.fromJson(r, tp);
        }
        if (records == null || records.isEmpty()) throw new RuntimeException("empty intermediate: " + inPath);
        LOGGER.info("loaded " + records.size() + " intermediate records");

        
        List<AnalyzedRecord> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        java.io.File of = new java.io.File(outPath);
        if (of.exists() && of.length() > 4) {
            try (FileReader r = new FileReader(of)) {
                Type tp = new TypeToken<List<AnalyzedRecord>>(){}.getType();
                List<AnalyzedRecord> prev = GSON.fromJson(r, tp);
                if (prev != null) {
                    out.addAll(prev);
                    for (AnalyzedRecord ar : prev) if (ar.example != null) done.add(sampleKey(ar.example));
                }
                LOGGER.info("resume: " + out.size() + " already analyzed");
            } catch (Exception e) { LOGGER.warning("resume parse failed: " + e.getMessage()); }
        }

        int idx = 0, processed = 0;
        for (IntermediateRecord rec : records) {
            idx++;
            if (rec.example == null) continue;
            String key = sampleKey(rec.example);
            if (done.contains(key)) continue;
            LOGGER.info("[" + idx + "/" + records.size() + "] " + rec.example.getRepo_id() + " / " + rec.example.getMethodName());
            AnalyzedRecord ar;
            try {
                ar = analyzeOne(rec);
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "analyzer crashed: " + rec.example.getMethodName(), t);
                ar = new AnalyzedRecord();
                ar.example = rec.example;
                ar.methodCode = rec.rootMethodCode;
                ar.need = "no";
                ar.plan = "";
                ar.staticError = "analyzer crashed: " + t.getMessage();
            }
            out.add(ar);
            processed++;
            if (processed % 25 == 0) writeAll(out, outPath);
        }
        writeAll(out, outPath);
        LOGGER.info("RunAnalyzer done. " + out.size() + " records → " + outPath);
    }

     
    static String threshStr(double t) {
        return (t == (long) t) ? String.valueOf((long) t) : String.valueOf(t);
    }

    private static AnalyzedRecord analyzeOne(IntermediateRecord rec) {
        AnalyzedRecord ar = new AnalyzedRecord();
        ar.example = rec.example;
        ar.methodCode = rec.rootMethodCode != null ? rec.rootMethodCode : rec.example.getMethodBefore();

        if (rec.staticError != null && !rec.staticError.isEmpty()) {
            ar.need = "no"; ar.plan = ""; ar.staticError = rec.staticError;
            ar.survivingCandidates = 0;
            return ar;
        }

        
        List<IntermediateRecord.Candidate> survivors = new ArrayList<>();
        for (IntermediateRecord.Candidate c : rec.candidates) {
            if (hasSurvivingException(c)) survivors.add(c);
        }
        ar.survivingCandidates = survivors.size();

        
        
        
        if (survivors.isEmpty()) {
            ar.need = "no"; ar.plan = "";
            return ar;
        }

        
        String userPrompt = buildAnalyzerPrompt(ar.methodCode, survivors);
        String simpleName = extractSimpleName(rec.example.getMethodName());
        Advisers advisers = new Advisers(rec.example);
        String raw = advisers.cachedLLMCall("analyzer", ANALYZER_CACHE_PREFIX,
                Advisers.getAnalyzerSystem(), userPrompt, simpleName, EXPERIMENT_MARK);

        if (raw == null) {  
            ar.need = "yes"; ar.plan = ""; ar.analysis = "analyzer LLM failed";
            return ar;
        }
        
        parseAnalyzerResponse(ar, raw);
        return ar;
    }

    private static boolean hasSurvivingException(IntermediateRecord.Candidate c) {
        
        if (c.apiExceptionScores != null) {
            for (IntermediateRecord.ApiExceptionScore es : c.apiExceptionScores)
                if (es.score >= API_THRESHOLD) return true;
        }
        if (c.throwStatements != null) {
            for (IntermediateRecord.ThrowStatementInfo s : c.throwStatements)
                if (s.score >= THROW_THRESHOLD) return true;
        }
        return false;
    }

     
    static String buildAnalyzerPrompt(String methodCode, List<IntermediateRecord.Candidate> survivors) {
        StringBuilder sb = new StringBuilder();
        sb.append(Advisers.getAnalyzerQuestion());
        sb.append("\n<code snippet>\n```\n").append(methodCode).append("\n```\n<end>\n\n");
        sb.append("<candidates>\n");
        int ci = 1;
        for (IntermediateRecord.Candidate c : survivors) {
            String where = (c.routeDepth <= 1)
                    ? "called directly in the target method"
                    : ("reached " + c.routeDepth + " call-levels deep (via " + c.parentSimpleName + ", in method " + c.simpleName + ")");
            if ("api".equals(c.kind)) {
                sb.append("Candidate ").append(ci).append(": API call `").append(c.simpleName)
                  .append("` (").append(where).append(").\n");
                if (c.javadoc != null && !c.javadoc.trim().isEmpty())
                    sb.append("  api javadoc: ").append(c.javadoc.replaceAll("\\s+", " ").trim()).append("\n");
                sb.append("  documented runtime exceptions [triage unprotected-score]:\n");
                for (IntermediateRecord.ApiExceptionScore es : c.apiExceptionScores) {
                    if (es.score < API_THRESHOLD) continue;
                    sb.append("    - ").append(es.name).append(" [score ").append(String.format("%.2f", es.score)).append("]");
                    if (es.condition != null && !es.condition.trim().isEmpty())
                        sb.append(" — throw condition: ").append(es.condition.trim());
                    sb.append("\n");
                }
            } else if ("throw".equals(c.kind)) {
                sb.append("Candidate ").append(ci).append(": throw statement(s) (").append(where).append(").\n");
                for (IntermediateRecord.ThrowStatementInfo s : c.throwStatements) {
                    if (s.score < THROW_THRESHOLD) continue;
                    String txt = s.text == null ? "" : s.text.replaceAll("\\s+", " ").trim();
                    sb.append("    - ").append(txt).append(" [throws ").append(s.exceptionType)
                      .append(", score ").append(String.format("%.2f", s.score)).append("]\n");
                }
            } else if ("implicit".equals(c.kind)) {
                
                sb.append("Candidate ").append(ci)
                  .append(": implicit / third-party runtime exception identified in the target method body.\n");
                for (IntermediateRecord.ApiExceptionScore es : c.apiExceptionScores) {
                    if (es.score < API_THRESHOLD) continue;
                    sb.append("    - ").append(es.name).append(" [score ").append(String.format("%.2f", es.score)).append("]");
                    if (c.description != null && !c.description.trim().isEmpty())
                        sb.append(" — ").append(c.description.replaceAll("\\s+", " ").trim());
                    sb.append("\n");
                }
            }
            ci++;
        }
        sb.append("<end>\n");
        return sb.toString();
    }

    private static void parseAnalyzerResponse(AnalyzedRecord ar, String raw) {
        
        ar.need = "no"; ar.plan = ""; ar.analysis = "";
        try {
            String body = LLMResponse.stripJsonFence(raw);
            JsonElement el = JsonParser.parseString(body);
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("analysis") && !o.get("analysis").isJsonNull()) ar.analysis = o.get("analysis").getAsString();
                if (o.has("plan") && !o.get("plan").isJsonNull()) ar.plan = o.get("plan").getAsString();
                if (o.has("need") && !o.get("need").isJsonNull()) {
                    String n = o.get("need").getAsString().trim().toLowerCase();
                    ar.need = n.contains("yes") ? "yes" : "no";
                }
                return;
            }
        } catch (RuntimeException e) {
            LOGGER.warning("analyzer parse fallback (regex): " + e.getMessage());
        }
        
        String need = LLMResponse.extractField(raw, "need").trim().toLowerCase();
        ar.need = need.contains("yes") ? "yes" : (need.contains("no") ? "no" : "no");
        ar.plan = LLMResponse.extractField(raw, "plan");
        ar.analysis = LLMResponse.extractField(raw, "analysis");
    }

    private static String extractSimpleName(String mn) {
        if (mn == null) return "";
        int p = mn.indexOf('(');
        return p < 0 ? mn : mn.substring(0, p);
    }

    private static String sampleKey(ExampleHandler.ExampleData d) {
        return d.getRepo_id() + "|" + (d.getPatch() == null ? "" : d.getPatch().hashCode()) + "|" + d.getMethodName();
    }

    private static void writeAll(List<AnalyzedRecord> recs, String path) throws IOException {
        try (FileWriter w = new FileWriter(path)) { GSON.toJson(recs, w); }
    }

    private static void loggerInit() throws IOException {
        LOGGER.setLevel(Level.ALL);
        Handler fh = new FileHandler("RunAnalyzer_" + EXPERIMENT_MARK + ".log", true);
        fh.setLevel(Level.ALL); fh.setFormatter(new SimpleFormatter());
        LOGGER.addHandler(fh);
    }
}

package org.LLMAdvisers;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.ExperimentExecutor.ExampleHandler;
import org.callTreeGenerator.*;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

 




public class Advisers {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    private static final int SYNTAX_RETRY = 5;
    private static String FILE_PATH = "src/main/java/org/LLMAdvisers/PromptTemplate.json";
    private static String API_TRIAGE_SYSTEM = "api triage system";
    private static String THROW_TRIAGE_SYSTEM = "throw triage system";
    
    private static String API_GRAPH_SYSTEM = "api graph triage system";
    private static String API_GRAPH_QUESTION = "api graph triage question";
    private static String THROW_GRAPH_SYSTEM = "throw graph triage system";
    private static String THROW_GRAPH_QUESTION = "throw graph triage question";
    
    private static String METHOD_TRIAGE_SYSTEM = "method triage system";
    private static String METHOD_TRIAGE_QUESTION = "method triage question";
    
    private static String ANALYZER_SYSTEM = "analyzer system";
    private static String ANALYZER_QUESTION = "analyzer question";
    private static String REPAIRER_SYSTEM = "repairer system";
    private static String REPAIRER_QUESTION = "repairer question";
    private static String REPAIR_SYSTEM_PROMPT = "repair system output format description";
    private static String BACKGROUND = "background introduction";
    private static String QUESTION = "question";
    private static String INPUT_FORMAT = "input format description";
    private static String API_QUESTION = "api caught or not question";
    private static String API_FORMAT = "api input format description";
    private static String THROW_QUESTION = "throw catch or not question";
    private static String THROW_FORMAT = "throw input format description";
    private static String BASELINE_INPUT_FORMAT = "baseline input format description";
    private static Map<String, String> promptTemplates;
    
    
    
    private static double API_THRESHOLD = 0.3;
    private static double THROW_THRESHOLD = 0.3;
    
    
    private static boolean GRAPH_UNCAUGHT = false;

    static {
        Gson gson = new Gson();
        String promptFile = System.getenv("PROMPT_FILE") != null ? System.getenv("PROMPT_FILE") : FILE_PATH;
        try (FileReader reader = new FileReader(promptFile)) {
            
            Type mapType = new TypeToken<Map<String, String>>() {}.getType();
            
            promptTemplates = gson.fromJson(reader, mapType);
        } catch (IOException e) {
            e.printStackTrace();
        }
        try (java.io.FileInputStream fis = new java.io.FileInputStream(System.getenv("CONFIG_FILE") != null ? System.getenv("CONFIG_FILE") : "config.properties")) {
            java.util.Properties prop = new java.util.Properties();
            prop.load(fis);
            API_THRESHOLD = Double.parseDouble(
                    prop.getProperty("TRIAGE_API_THRESHOLD", "0.3").trim());
            String tt = prop.getProperty("TRIAGE_THROW_THRESHOLD", "").trim();
            THROW_THRESHOLD = tt.isEmpty() ? API_THRESHOLD : Double.parseDouble(tt);
            GRAPH_UNCAUGHT = "llm".equalsIgnoreCase(
                    prop.getProperty("UNCAUGHT_SOURCE", "static").trim());
        } catch (IOException | NumberFormatException e) {
            LOGGER.warning("triage threshold load failed, using default 0.3: " + e.getMessage());
            API_THRESHOLD = 0.3;
            THROW_THRESHOLD = 0.3;
        }
    }

    public static String constructApiTriageSystemPrompt() {
        return promptTemplates.get(API_TRIAGE_SYSTEM);
    }

    public static String constructThrowTriageSystemPrompt() {
        return promptTemplates.get(THROW_TRIAGE_SYSTEM);
    }

    public static String constructApiGraphSystemPrompt() {
        return promptTemplates.get(API_GRAPH_SYSTEM);
    }

    public static String constructThrowGraphSystemPrompt() {
        return promptTemplates.get(THROW_GRAPH_SYSTEM);
    }

    
    
    
    public static String serializeCallPath(List<MethodTreeNode> route, boolean excludeLast) {
        StringBuilder sb = new StringBuilder();
        int n = excludeLast ? route.size() - 1 : route.size();
        if (n < 1) n = route.size(); 
        for (int i = 0; i < n; i++) {
            MethodTreeNode m = route.get(i);
            sb.append("// ").append(i == 0 ? "target method" : "called: " + m.getQualifiedName()).append("\n");
            sb.append("```\n").append(m.getCode()).append("\n```\n");
        }
        return sb.toString();
    }

    
    
    
    public static String constructApiGraphQuestionPrompt(UncaughtExceptionInfoAPI uei) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptTemplates.get(API_GRAPH_QUESTION));
        sb.append("\n");
        
        if (!uei.getNodeRoute().isEmpty()) {
            String ctx = uei.getNodeRoute().get(0).getClassContextSummary();
            if (ctx != null && !ctx.isEmpty()) {
                sb.append("<class context>\n").append(ctx).append("<end>\n\n");
            }
        }
        sb.append("<call path>\n");
        sb.append(serializeCallPath(uei.getNodeRoute(), true)); 
        sb.append("The API method called at the bottom of this path is `")
          .append(uei.getMethodTreeNode().getQualifiedName()).append("`.\n");
        sb.append("<end>\n\n");
        
        String jd = uei.getJavaDoc();
        if (jd != null && jd.length() > 1200) jd = jd.substring(0, 1200) + " ...[truncated]";
        sb.append("<api javadoc>\n");
        sb.append(jd == null ? "" : jd);
        sb.append("<end>\n\n");
        
        java.util.Map<String, String> condMap = null;
        if (uei.getMethodTreeNode() instanceof org.callTreeGenerator.ArchivedMethodTreeNode) {
            condMap = ((org.callTreeGenerator.ArchivedMethodTreeNode) uei.getMethodTreeNode()).getExceptions();
        }
        sb.append("<candidate exceptions>\n");
        List<String> ordered = orderedExceptions(uei);
        for (int i = 0; i < ordered.size(); i++) {
            String exc = ordered.get(i);
            sb.append(i + 1).append(". ").append(exc).append(" (documented by the API above)\n");
            if (condMap != null) {
                
                String cond = condMap.get(exc);
                if (cond == null) {
                    String simple = exc.contains(".") ? exc.substring(exc.lastIndexOf('.') + 1) : exc;
                    cond = condMap.get(simple);
                }
                if (cond != null && !cond.trim().isEmpty()) {
                    if (cond.length() > 200) cond = cond.substring(0, 200) + "...";
                    sb.append("   throw condition: ").append(cond.trim()).append("\n");
                }
            }
        }
        sb.append("<end>\n\n");
        return sb.toString();
    }

    
    
    public static String constructThrowGraphQuestionPrompt(UncaughtExceptionInfoThrow uei) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptTemplates.get(THROW_GRAPH_QUESTION));
        sb.append("\n");
        sb.append("<call path>\n");
        sb.append(serializeCallPath(uei.getNodeRoute(), false)); 
        sb.append("<end>\n\n");
        sb.append("<candidate throws>\n");
        List<ThrowExceptionInfo> throwsList = uei.getUncaughtThrowExceptionInfo();
        for (int i = 0; i < throwsList.size(); i++) {
            String stmt = throwsList.get(i).getThrowStatementString();
            if (stmt != null) stmt = stmt.replaceAll("\\s+", " ").trim();
            sb.append(i + 1).append(". ").append(stmt)
              .append("  (raised in `").append(uei.getMethodTreeNode().getSimpleName()).append("`)\n");
        }
        sb.append("<end>\n\n");
        return sb.toString();
    }

    public static String constructRepairSystemPrompt() {
        return promptTemplates.get(REPAIR_SYSTEM_PROMPT);
    }

    public static Map<String, String> handleMainQuestion(MethodTreeNode root, List<UncaughtExceptionInfo> exceptionResults){
        LOGGER.info("handling main question");
        Map<String, String> result = new HashMap<>();
        
        int retryCount = 0;
        RuntimeException lastException = null;
        while (retryCount < SYNTAX_RETRY) {
            try {
                
                LLMApiCaller LLMApiCaller1 = new LLMApiCaller()
                        .tag("main_question", root.getSimpleName())
                        .responseFormat("json_object")
                        .addMessage(Advisers.constructRepairSystemPrompt(), "system")
                        .addMessage(Advisers.constructQuestionPrompt(root, exceptionResults), "user");
                
                try {
                    LLMApiCaller1.call();
                } catch (IOException e) {
                    
                    throw new RuntimeException(e);
                }
                
                result = LLMResponse.parseTaggedMessage(LLMApiCaller1.getLastAssistantMessage());
                
                String needVal = normalizeNeed(result.get("need"));
                result.put("need", needVal);
                if (needVal.equals("yes")) {
                    
                    validateMethodResult(result.get("result"));
                }
                
                break;
            } catch (RuntimeException e) {
                lastException = e;
                retryCount++;
                
                if (retryCount < SYNTAX_RETRY) {
                    LOGGER.fine("main question parse failed: " + e.getMessage() + " retry (" + retryCount + "/" + SYNTAX_RETRY + ")...");
                } else {
                    
                    LOGGER.warning("main question abort: " + e.getMessage());
                    throw lastException;
                }
            }
        }
        LOGGER.info("handle main question succeed");
        return result;
    }
    public static String constructQuestionPrompt(MethodTreeNode root, List<UncaughtExceptionInfo> exceptionResults) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptTemplates.get(BACKGROUND));
        sb.append(promptTemplates.get(QUESTION));
        
        
        sb.append(promptTemplates.get(exceptionResults.isEmpty() ? BASELINE_INPUT_FORMAT : INPUT_FORMAT));
        sb.append("\n");
        sb.append("<code snippet>\n");                                    
        sb.append("```\n").append(root.getCode()).append("\n```\n");
        sb.append("<end>\n\n");
        int i = 1;
        for (UncaughtExceptionInfo uei: exceptionResults) {
            sb.append("<call ").append(i).append(">\n");                  
            if (uei instanceof UncaughtExceptionInfoAPI) {
                
                sb.append("Function in snippet: ").append(uei.getNodeRoute().get(1).getSimpleName()).append("\n");
                
                sb.append("After ").append(uei.getNodeRoute().size() - 1).append(" levels of call, in function: ").append(uei.getMethodTreeNode().getSimpleName()).append("\n");
                
                sb.append("May throw runtime exceptions: ").append(uei.getCheckedUncaughtExceptionsString()).append("\n");
                
                
                if (uei.getNodeRoute().size() > 1) {
                    sb.append("<call path>\n");
                    
                    sb.append(serializeCallPath(uei.getNodeRoute(), true));
                    sb.append("<end>\n");
                }
            } else if (uei instanceof UncaughtExceptionInfoThrow) {
                UncaughtExceptionInfoThrow ueiThrow = (UncaughtExceptionInfoThrow) uei;
                List<ThrowExceptionInfo> shown = ueiThrow.getCheckedUncaughtThrowExceptionInfo();
                if (shown == null) {
                    shown = ueiThrow.getUncaughtThrowExceptionInfo();
                }
                if (shown == null || shown.isEmpty()) {
                    sb.append("<end ").append(i).append(">\n");
                    i++;
                    continue;
                }
                
                if (uei.getNodeRoute().size() > 1) {
                    sb.append("Function in snippet: ").append(uei.getNodeRoute().get(1).getSimpleName()).append("\n");
                    sb.append("After ").append(uei.getNodeRoute().size() - 1)
                      .append(" levels of call, the throw statement `").append(shown).append("` throws an exception\n");
                    
                    sb.append("<call path>\n");
                    sb.append(serializeCallPath(uei.getNodeRoute(), false));
                    sb.append("<end>\n");
                } else {
                    
                    
                    sb.append("The throw statement `").append(shown).append("` inside this method may throw an exception\n");
                }
            }
            if (uei.hasDescription()) {
                
                sb.append("Exception description: ").append(uei.getDescription()).append("\n");
            }
            sb.append("<end ").append(i).append(">\n");                   
            i++;
        }
        return sb.toString();
    }

    public static String handleUncaughtExceptionInfo(UncaughtExceptionInfo uncaughtExceptionInfo) {
        if (uncaughtExceptionInfo instanceof UncaughtExceptionInfoAPI) {
            return handleAPI((UncaughtExceptionInfoAPI) uncaughtExceptionInfo);
        } else if (uncaughtExceptionInfo instanceof UncaughtExceptionInfoThrow) {
            return handleThrow((UncaughtExceptionInfoThrow) uncaughtExceptionInfo);
        } else {
            LOGGER.severe("UncaughtExceptionInfo type error.");
            throw new IllegalStateException("UncaughtExceptionInfo type error");
        }
    }

    public static String handleAPI(UncaughtExceptionInfoAPI uncaughtExceptionInfo) {
        LOGGER.info("handling api by LLM: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
        
        LLMApiCaller LLMApiCaller1;
        try {
            LLMApiCaller1 = new LLMApiCaller()
                    .tag("api_triage", uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName())
                    .responseFormat("json_object")
                    .addMessage(GRAPH_UNCAUGHT ? constructApiGraphSystemPrompt() : constructApiTriageSystemPrompt(), "system")
                    .addMessage(GRAPH_UNCAUGHT ? constructApiGraphQuestionPrompt(uncaughtExceptionInfo) : constructApiQuestionPrompt(uncaughtExceptionInfo), "user");
        } catch (RuntimeException ignore) {
            LOGGER.warning("handling api by LLM failed: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName() + " because : " + ignore.getMessage());
            return null;
        }
        
        try {
            LLMApiCaller1.call();
        } catch (IOException ioException) {
            LOGGER.warning(ioException.getMessage() + ", abort handle api " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            uncaughtExceptionInfo.setCheckedUncaughtExceptions(uncaughtExceptionInfo.getUncaughtExceptions());
            return null;
        }
        
        try {
            String rawMessage = LLMApiCaller1.getLastAssistantMessage();
            String remark = LLMResponse.extractField(rawMessage, "remark");
            if (!remark.isEmpty()) {
                uncaughtExceptionInfo.setDescription(remark);
            }
            selectApiByScore(uncaughtExceptionInfo, rawMessage);
        } catch (RuntimeException throwable) {
            LOGGER.warning(throwable.getMessage() + ", abort handle api " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            uncaughtExceptionInfo.setCheckedUncaughtExceptions(uncaughtExceptionInfo.getUncaughtExceptions());
            return null;
        }
        LOGGER.info("api handle success: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
        return LLMApiCaller1.getLastAssistantMessage();
    }

    
    static List<String> orderedExceptions(UncaughtExceptionInfoAPI uei) {
        List<String> ordered = new ArrayList<>(uei.getUncaughtExceptions());
        Collections.sort(ordered);
        return ordered;
    }

    
    
    private static void selectApiByScore(UncaughtExceptionInfoAPI uei, String rawMessage) {
        List<String> ordered = orderedExceptions(uei);
        Map<Integer, Double> scores = LLMResponse.parseScores(rawMessage);
        if (scores.isEmpty()) {
            LOGGER.warning("api scores unparseable, keeping all candidates for "
                    + uei.getMethodTreeNode().getQualifiedName());
            uei.setCheckedUncaughtExceptions(new HashSet<>(ordered));
            uei.setCheckedExceptionScores(new HashMap<>());
            return;
        }
        Set<String> kept = new HashSet<>();
        Map<String, Double> keptScores = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            Double s = scores.get(i + 1); 
            if (s != null && s >= API_THRESHOLD) {
                kept.add(ordered.get(i));
                keptScores.put(ordered.get(i), s);
            }
        }
        uei.setCheckedUncaughtExceptions(kept);
        uei.setCheckedExceptionScores(keptScores);
    }

    public static String constructApiQuestionPrompt(UncaughtExceptionInfo uncaughtExceptionInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptTemplates.get(API_QUESTION).replaceAll("\\{str1\\}", Matcher.quoteReplacement(uncaughtExceptionInfo.getMethodTreeNode().getSimpleName())));
        sb.append(promptTemplates.get(API_FORMAT));
        sb.append("\n");
        sb.append("<code snippet>\n");                                    
        sb.append("```\n").append(uncaughtExceptionInfo.getSecondLastNode().getCode()).append("\n```\n");
        sb.append("<end>\n\n");
        sb.append("<api javadoc>\n");                                     
        sb.append(((ArchivedMethodTreeNode) uncaughtExceptionInfo.getMethodTreeNode()).getJavaDoc());
        sb.append("<end>\n\n");
        sb.append("<candidate exceptions>\n");
        List<String> ordered = orderedExceptions((UncaughtExceptionInfoAPI) uncaughtExceptionInfo);
        for (int i = 0; i < ordered.size(); i++) {
            sb.append(i + 1).append(". ").append(ordered.get(i)).append("\n");
        }
        sb.append("<end>\n\n");
        return sb.toString();
    }

    public static String handleThrow(UncaughtExceptionInfoThrow uncaughtExceptionInfo) {
        LOGGER.info("handling throw by LLM: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
        
        LLMApiCaller caller = new LLMApiCaller()
                .tag("throw_triage", uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName())
                .responseFormat("json_object")
                .addMessage(GRAPH_UNCAUGHT ? constructThrowGraphSystemPrompt() : constructThrowTriageSystemPrompt(), "system")
                .addMessage(GRAPH_UNCAUGHT ? constructThrowGraphQuestionPrompt(uncaughtExceptionInfo) : constructThrowQuestionPrompt(uncaughtExceptionInfo), "user");
        try {
            caller.call();
        } catch (IOException ioException) {
            LOGGER.warning(ioException.getMessage() + ", abort handle throw " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            uncaughtExceptionInfo.setCheckedUncaughtThrowExceptionInfo(new ArrayList<>(uncaughtExceptionInfo.getUncaughtThrowExceptionInfo()));
            return null;
        }
        try {
            String rawMessage = caller.getLastAssistantMessage();
            String remark = LLMResponse.extractField(rawMessage, "remark");
            if (!remark.isEmpty()) {
                uncaughtExceptionInfo.setDescription(remark);
            }
            uncaughtExceptionInfo.setCheckedUncaughtThrowExceptionInfo(
                    selectThrowByScore(uncaughtExceptionInfo, rawMessage));
            LOGGER.info("throw handle success: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            return rawMessage;
        } catch (RuntimeException throwable) {
            LOGGER.warning(throwable.getMessage() + ", abort handle throw " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            uncaughtExceptionInfo.setCheckedUncaughtThrowExceptionInfo(new ArrayList<>(uncaughtExceptionInfo.getUncaughtThrowExceptionInfo()));
            return null;
        }
    }

    
    
    private static List<ThrowExceptionInfo> selectThrowByScore(UncaughtExceptionInfoThrow uei, String rawMessage) {
        List<ThrowExceptionInfo> all = uei.getUncaughtThrowExceptionInfo();
        Map<Integer, Double> scores = LLMResponse.parseScores(rawMessage);
        if (scores.isEmpty()) {
            LOGGER.warning("throw scores unparseable, keeping all throws for "
                    + uei.getMethodTreeNode().getQualifiedName());
            uei.setThrowScores(new java.util.LinkedHashMap<>());
            return new ArrayList<>(all);
        }
        List<ThrowExceptionInfo> kept = new ArrayList<>();
        java.util.LinkedHashMap<ThrowExceptionInfo, Double> keptScores = new java.util.LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            Double s = scores.get(i + 1); 
            if (s != null && s >= THROW_THRESHOLD) {
                kept.add(all.get(i));
                keptScores.put(all.get(i), s);
            }
        }
        uei.setThrowScores(keptScores);
        return kept;
    }

    // ═══════════════════════════════════════════════════════════════════════
    
    
    
    
    // ═══════════════════════════════════════════════════════════════════════

     
    private static void keepAllCandidate(UncaughtExceptionInfo uei) {
        if (uei instanceof UncaughtExceptionInfoAPI) {
            UncaughtExceptionInfoAPI a = (UncaughtExceptionInfoAPI) uei;
            a.setCheckedUncaughtExceptions(new HashSet<>(a.getUncaughtExceptions()));
            a.setCheckedExceptionScores(new HashMap<>());
        } else if (uei instanceof UncaughtExceptionInfoThrow) {
            UncaughtExceptionInfoThrow t = (UncaughtExceptionInfoThrow) uei;
            t.setCheckedUncaughtThrowExceptionInfo(new ArrayList<>(t.getUncaughtThrowExceptionInfo()));
        }
    }

     
    public static String constructMethodTriageQuestionPrompt(MethodTreeNode root, List<UncaughtExceptionInfo> ueis) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptTemplates.get(METHOD_TRIAGE_QUESTION));
        sb.append("\n");

        
        LinkedHashMap<String, String> srcByQname = new LinkedHashMap<>();
        String rootQ = root.getQualifiedName();
        srcByQname.put(rootQ, root.getCode());
        for (UncaughtExceptionInfo uei : ueis) {
            List<MethodTreeNode> route = uei.getNodeRoute();
            
            int n = (uei instanceof UncaughtExceptionInfoAPI) ? route.size() - 1 : route.size();
            for (int i = 0; i < n; i++) {
                MethodTreeNode m = route.get(i);
                String q = m.getQualifiedName();
                if (!srcByQname.containsKey(q)) {
                    String code = m.getCode();
                    if (code != null && !code.isEmpty()) srcByQname.put(q, code);
                }
            }
        }
        
        LinkedHashMap<String, String> labelOf = new LinkedHashMap<>();
        int li = 0;
        sb.append("<source methods>\n");
        for (Map.Entry<String, String> e : srcByQname.entrySet()) {
            String lab = "M" + li++;
            labelOf.put(e.getKey(), lab);
            sb.append("// [").append(lab).append("] ").append(e.getKey()).append("\n");
            sb.append("```\n").append(e.getValue()).append("\n```\n");
        }
        sb.append("<end>\n\n");

        
        sb.append("<candidates>\n");
        int ci = 1;
        for (UncaughtExceptionInfo uei : ueis) {
            List<MethodTreeNode> route = uei.getNodeRoute();
            
            StringBuilder pathLab = new StringBuilder();
            int pn = (uei instanceof UncaughtExceptionInfoAPI) ? route.size() - 1 : route.size();
            for (int i = 0; i < pn; i++) {
                String q = route.get(i).getQualifiedName();
                pathLab.append(labelOf.getOrDefault(q, "?"));
                if (i < pn - 1) pathLab.append(" -> ");
            }
            if (uei instanceof UncaughtExceptionInfoAPI) {
                UncaughtExceptionInfoAPI a = (UncaughtExceptionInfoAPI) uei;
                sb.append("Candidate ").append(ci).append(": API call `")
                  .append(a.getMethodTreeNode().getQualifiedName())
                  .append("` reached via [").append(pathLab).append("].\n");
                
                String jd = a.getJavaDoc();
                if (jd != null && jd.length() > 800) jd = jd.substring(0, 800) + " ...[truncated]";
                if (jd != null && !jd.trim().isEmpty()) sb.append("  api javadoc: ").append(jd.replaceAll("\\s+", " ").trim()).append("\n");
                sb.append("  documented runtime exceptions (score each by type):\n");
                java.util.Map<String, String> condMap = null;
                if (a.getMethodTreeNode() instanceof org.callTreeGenerator.ArchivedMethodTreeNode) {
                    condMap = ((org.callTreeGenerator.ArchivedMethodTreeNode) a.getMethodTreeNode()).getExceptions();
                }
                for (String exc : orderedExceptions(a)) {
                    sb.append("   - ").append(exc);
                    if (condMap != null) {
                        String cond = condMap.get(exc);
                        if (cond == null) {
                            String simple = exc.contains(".") ? exc.substring(exc.lastIndexOf('.') + 1) : exc;
                            cond = condMap.get(simple);
                        }
                        if (cond != null && !cond.trim().isEmpty()) {
                            if (cond.length() > 200) cond = cond.substring(0, 200) + "...";
                            sb.append(" — throw condition: ").append(cond.trim());
                        }
                    }
                    sb.append("\n");
                }
            } else if (uei instanceof UncaughtExceptionInfoThrow) {
                UncaughtExceptionInfoThrow t = (UncaughtExceptionInfoThrow) uei;
                sb.append("Candidate ").append(ci).append(": throw statement(s) in `")
                  .append(t.getMethodTreeNode().getQualifiedName())
                  .append("` reached via [").append(pathLab).append("] (score each by type):\n");
                for (ThrowExceptionInfo ti : t.getUncaughtThrowExceptionInfo()) {
                    String stmt = ti.getThrowStatementString();
                    if (stmt != null) stmt = stmt.replaceAll("\\s+", " ").trim();
                    sb.append("   - ").append(ti.getExceptionQualifiedName())
                      .append("  (").append(stmt).append(")\n");
                }
            }
            ci++;
        }
        sb.append("<end>\n\n");
        return sb.toString();
    }

     
    public static class DiscoveredException {
        public String type; public double score; public String reason;
        public DiscoveredException(String type, double score, String reason) {
            this.type = type; this.score = score; this.reason = reason;
        }
    }

     

    public List<DiscoveredException> handleMethodTriage(MethodTreeNode root, List<UncaughtExceptionInfo> ueis, String experimentMark) {
        if (ueis == null) ueis = new ArrayList<>();
        
        StringBuilder keySrc = new StringBuilder(root.getQualifiedName());
        for (UncaughtExceptionInfo uei : ueis) keySrc.append("|").append(uei.getHash());
        String expectedName = experimentMark + "+mtri+" + ExampleHandler.md5Hash(keySrc.toString());
        String raw = ExampleHandler.getDataRecord(exampleData, expectedName);
        if (raw == null) {
            LOGGER.info("method triage by LLM: " + root.getSimpleName() + " (" + ueis.size() + " enumerated candidates + discovery)");
            try {
                LLMApiCaller caller = new LLMApiCaller()
                        .tag("method_triage", root.getSimpleName())
                        .responseFormat("json_object")
                        .addMessage(promptTemplates.get(METHOD_TRIAGE_SYSTEM), "system")
                        .addMessage(constructMethodTriageQuestionPrompt(root, ueis), "user");
                caller.call();
                raw = caller.getLastAssistantMessage();
            } catch (Exception e) {
                LOGGER.warning("method triage failed: " + root.getSimpleName() + " — " + e.getMessage() + "; keeping all");
                for (UncaughtExceptionInfo uei : ueis) keepAllCandidate(uei);
                return new ArrayList<>();
            }
            if (ExampleHandler.writeDataRecord(exampleData, raw, expectedName)) {
                LOGGER.info("experiment data wrote to: " + expectedName);
            }
        } else {
            LOGGER.info("method triage by recorded data: " + root.getSimpleName());
        }
        return distributeMethodTriageScores(ueis, raw);
    }

     

    private List<DiscoveredException> distributeMethodTriageScores(List<UncaughtExceptionInfo> ueis, String raw) {
        Map<Integer, Map<String, Double>> candScores = new HashMap<>();  
        Map<Integer, String> candRemark = new HashMap<>();
        List<DiscoveredException> discovered = new ArrayList<>();
        try {
            String body = LLMResponse.stripJsonFence(raw);
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject() && root.getAsJsonObject().has("candidates")) {
                for (JsonElement el : root.getAsJsonObject().getAsJsonArray("candidates")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    String remark = o.has("remark") && !o.get("remark").isJsonNull() ? o.get("remark").getAsString() : "";
                    boolean enumerated = o.has("candidate") && o.has("scores")
                            && o.get("candidate").isJsonPrimitive() && o.get("candidate").getAsJsonPrimitive().isNumber();
                    if (enumerated) {
                        int cand = o.get("candidate").getAsInt();
                        Map<String, Double> m = candScores.computeIfAbsent(cand, k -> new HashMap<>());
                        for (JsonElement se : o.getAsJsonArray("scores")) {
                            if (!se.isJsonObject()) continue;
                            JsonObject so = se.getAsJsonObject();
                            if (!so.has("type") || so.get("type").isJsonNull()) continue;
                            double sc = 0.0;
                            try { if (so.has("score")) sc = so.get("score").getAsDouble(); } catch (RuntimeException ig) {}
                            m.put(so.get("type").getAsString(), sc);
                        }
                        candRemark.put(cand, remark);
                    } else if (o.has("type") && !o.get("type").isJsonNull()) {
                        String type = o.get("type").getAsString();
                        if (type == null || type.trim().isEmpty()) continue;
                        double sc = 0.0;
                        try { if (o.has("score")) sc = o.get("score").getAsDouble(); } catch (RuntimeException ig) {}
                        discovered.add(new DiscoveredException(type.trim(), sc, remark));
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warning("method triage parse failed, keeping all enumerated: " + e.getMessage());
        }
        for (int i = 0; i < ueis.size(); i++) {
            UncaughtExceptionInfo uei = ueis.get(i);
            Map<String, Double> m = candScores.get(i + 1);
            if (m == null) { keepAllCandidate(uei); continue; }
            String remark = candRemark.getOrDefault(i + 1, "");
            if (!remark.isEmpty()) uei.setDescription(remark);
            applyScoresByType(uei, m);
        }
        return discovered;
    }

    private static boolean sameType(String a, String b) {
        if (a.equals(b)) return true;
        String sa = a.contains(".") ? a.substring(a.lastIndexOf('.') + 1) : a;
        String sb = b.contains(".") ? b.substring(b.lastIndexOf('.') + 1) : b;
        return sa.equals(sb);
    }

     
    private static void applyScoresByType(UncaughtExceptionInfo uei, Map<String, Double> typeScore) {
        if (uei instanceof UncaughtExceptionInfoAPI) {
            UncaughtExceptionInfoAPI a = (UncaughtExceptionInfoAPI) uei;
            Set<String> kept = new HashSet<>();
            Map<String, Double> keptScores = new HashMap<>();
            boolean any = false;
            for (String exc : a.getUncaughtExceptions()) {
                Double s = typeScore.get(exc);
                if (s == null) for (Map.Entry<String, Double> e : typeScore.entrySet())
                    if (sameType(e.getKey(), exc)) { s = e.getValue(); break; }
                if (s != null) { any = true; if (s >= API_THRESHOLD) { kept.add(exc); keptScores.put(exc, s); } }
            }
            if (!any) { a.setCheckedUncaughtExceptions(new HashSet<>(a.getUncaughtExceptions())); a.setCheckedExceptionScores(new HashMap<>()); }
            else { a.setCheckedUncaughtExceptions(kept); a.setCheckedExceptionScores(keptScores); }
        } else if (uei instanceof UncaughtExceptionInfoThrow) {
            UncaughtExceptionInfoThrow t = (UncaughtExceptionInfoThrow) uei;
            List<ThrowExceptionInfo> kept = new ArrayList<>();
            java.util.LinkedHashMap<ThrowExceptionInfo, Double> keptScores = new java.util.LinkedHashMap<>();
            boolean any = false;
            for (ThrowExceptionInfo info : t.getUncaughtThrowExceptionInfo()) {
                String exc = info.getExceptionQualifiedName();
                Double s = typeScore.get(exc);
                if (s == null) for (Map.Entry<String, Double> e : typeScore.entrySet())
                    if (sameType(e.getKey(), exc)) { s = e.getValue(); break; }
                if (s != null) { any = true; if (s >= THROW_THRESHOLD) { kept.add(info); keptScores.put(info, s); } }
            }
            if (!any) { t.setCheckedUncaughtThrowExceptionInfo(new ArrayList<>(t.getUncaughtThrowExceptionInfo())); }
            else { t.setThrowScores(keptScores); t.setCheckedUncaughtThrowExceptionInfo(kept); }
        }
    }

    
    public static String getAnalyzerSystem() { return promptTemplates.get(ANALYZER_SYSTEM); }
    public static String getAnalyzerQuestion() { return promptTemplates.get(ANALYZER_QUESTION); }
    public static String getRepairerSystem() { return promptTemplates.get(REPAIRER_SYSTEM); }
    public static String getRepairerQuestion() { return promptTemplates.get(REPAIRER_QUESTION); }

     




    public String cachedLLMCall(String stageTag, String cachePrefix, String systemPrompt,
                                String userPrompt, String simpleName, String experimentMark) {
        String expectedName = experimentMark + "+" + cachePrefix + "+" + ExampleHandler.md5Hash(userPrompt);
        String exist = ExampleHandler.getDataRecord(exampleData, expectedName);
        if (exist != null) {
            LOGGER.info(cachePrefix + " by recorded data: " + simpleName);
            return exist;
        }
        LOGGER.info(cachePrefix + " by LLM: " + simpleName);
        LLMApiCaller caller = new LLMApiCaller()
                .tag(stageTag, simpleName)
                .responseFormat("json_object")
                .addMessage(systemPrompt, "system")
                .addMessage(userPrompt, "user");
        try {
            caller.call();
        } catch (IOException e) {
            LOGGER.warning(cachePrefix + " call failed: " + simpleName + " — " + e.getMessage());
            return null;
        }
        String raw = caller.getLastAssistantMessage();
        if (ExampleHandler.writeDataRecord(exampleData, raw, expectedName)) {
            LOGGER.info("experiment data wrote to: " + expectedName);
        }
        return raw;
    }

    public static String constructThrowQuestionPrompt(UncaughtExceptionInfoThrow uncaughtExceptionInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptTemplates.get(THROW_QUESTION));
        sb.append(promptTemplates.get(THROW_FORMAT));
        sb.append("\n");
        sb.append("<code snippet>\n");                                    
        sb.append("```\n").append(uncaughtExceptionInfo.getMethodTreeNode().getCode()).append("\n```\n");
        sb.append("<end>\n\n");
        sb.append("<candidate throws>\n");
        List<ThrowExceptionInfo> throwsList = uncaughtExceptionInfo.getUncaughtThrowExceptionInfo();
        for (int i = 0; i < throwsList.size(); i++) {
            String stmt = throwsList.get(i).getThrowStatementString();
            if (stmt != null) stmt = stmt.replaceAll("\\s+", " ").trim();
            sb.append(i + 1).append(". ").append(stmt).append("\n");
        }
        sb.append("<end>\n\n");
        return sb.toString();
    }

    private ExampleHandler.ExampleData exampleData;

    public Advisers(ExampleHandler.ExampleData exampleData) {
        this.exampleData = exampleData;
    }

    public Map<String, String> handleMainQuestion(MethodTreeNode root, List<UncaughtExceptionInfo> exceptionResults, String experimentMark) {
        
        
        String prompt = constructQuestionPrompt(root, exceptionResults);
        String expectedName = experimentMark + "+" + root.getSimpleName() + "+" + ExampleHandler.md5Hash(prompt);
        String existData = ExampleHandler.getDataRecord(exampleData, expectedName);
        if (existData == null) {
            
            LOGGER.info("handling main question by LLM: " + root.getSimpleName());
            Map<String, String> result;
            try {
                result = handleMainQuestion(root, exceptionResults);
            } catch (RuntimeException e) {
                LOGGER.warning("handling main question by LLM failed: " + root.getSimpleName());
                return null;
            }
            String dataToRecord = serializeTaggedMessage(result);
            if (ExampleHandler.writeDataRecord(exampleData, dataToRecord, expectedName)) {
                LOGGER.info("experiment data wrote to: " + expectedName);
            } else {
                LOGGER.warning("experiment data failed to wrote to: " + expectedName);
            }
            LOGGER.info("main question handle success: " + root.getSimpleName());
            return result;
        } else {
            
            LOGGER.info("handling main question by recorded data: " + root.getSimpleName());
            Map<String, String> result = LLMResponse.parseTaggedMessage(existData);
            LOGGER.info("main question handle success: " + root.getSimpleName());
            return result;
        }
    }

    private static String serializeTaggedMessage(Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("<analysis>\n").append(data.getOrDefault("analysis", "")).append("\n</analysis>\n");
        sb.append("<answer>\n").append(data.getOrDefault("answer", "")).append("\n</answer>\n");
        
        sb.append("<need>\n").append(data.getOrDefault("need", "")).append("\n</need>\n");
        sb.append("<result>\n").append(data.getOrDefault("result", "")).append("\n</result>\n");
        sb.append("<remark>\n").append(data.getOrDefault("remark", "")).append("\n</remark>\n");
        return sb.toString();
    }

     






    public String handleUncaughtExceptionInfo(UncaughtExceptionInfo uncaughtExceptionInfo, String experimentMark) {
        
        String expectedName = experimentMark + "+" + (GRAPH_UNCAUGHT ? "gu+" : "") + uncaughtExceptionInfo.getHash();
        String existData = ExampleHandler.getDataRecord(exampleData, expectedName);
        if (existData == null) {
            
            String dataToRecord = handleUncaughtExceptionInfo(uncaughtExceptionInfo);
            if (dataToRecord != null) {
                if (ExampleHandler.writeDataRecord(exampleData, dataToRecord, expectedName)) {
                    LOGGER.info("experiment data wrote to: " + expectedName);
                } else {
                    LOGGER.warning("experiment data failed to wrote to: " + expectedName);
                }
            } else {
                LOGGER.warning("experiment data failed to wrote to: " + expectedName);
            }
            return dataToRecord;
        } else {
            
            if (uncaughtExceptionInfo instanceof UncaughtExceptionInfoAPI) {
                LOGGER.info("handling api by recorded data: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
                
                try {
                    String remark = LLMResponse.extractField(existData, "remark");
                    if (!remark.isEmpty()) {
                        uncaughtExceptionInfo.setDescription(remark);
                    }
                    selectApiByScore((UncaughtExceptionInfoAPI) uncaughtExceptionInfo, existData);
                } catch (RuntimeException throwable) {
                    LOGGER.warning(throwable.getMessage() + ", abort handle api " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
                    uncaughtExceptionInfo.setCheckedUncaughtExceptions(uncaughtExceptionInfo.getUncaughtExceptions());
                    return null;
                }
                LOGGER.info("api handle success: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            } else if (uncaughtExceptionInfo instanceof UncaughtExceptionInfoThrow) {
                LOGGER.info("handling throw by recorded data: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
                UncaughtExceptionInfoThrow throwInfo = (UncaughtExceptionInfoThrow) uncaughtExceptionInfo;
                try {
                    String remark = LLMResponse.extractField(existData, "remark");
                    if (!remark.isEmpty()) {
                        throwInfo.setDescription(remark);
                    }
                    
                    throwInfo.setCheckedUncaughtThrowExceptionInfo(selectThrowByScore(throwInfo, existData));
                } catch (RuntimeException throwable) {
                    LOGGER.warning(throwable.getMessage() + ", abort handle throw " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
                    throwInfo.setCheckedUncaughtThrowExceptionInfo(new ArrayList<>(throwInfo.getUncaughtThrowExceptionInfo()));
                    return null;
                }
                LOGGER.info("throw handle success: " + uncaughtExceptionInfo.getMethodTreeNode().getQualifiedName());
            }
            return existData;
        }
    }

    public static Map<String, String> handleLLMBaseline(String methodText){
        LOGGER.info("handling llm baseline question");
        Map<String, String> result = new HashMap<>();
        
        int retryCount = 0;
        RuntimeException lastException = null;
        while (retryCount < SYNTAX_RETRY) {
            try {
                
                LLMApiCaller LLMApiCaller1 = new LLMApiCaller()
                        .tag("llm_baseline", "")
                        .responseFormat("json_object")
                        .addMessage(Advisers.constructRepairSystemPrompt(), "system")
                        .addMessage(Advisers.constructBaselinePrompt(methodText), "user");
                
                try {
                    LLMApiCaller1.call();
                } catch (IOException e) {
                    
                    throw new RuntimeException(e);
                }
                
                result = LLMResponse.parseTaggedMessage(LLMApiCaller1.getLastAssistantMessage());
                validateMethodResult(result.get("result"));
                break;
            } catch (RuntimeException e) {
                lastException = e;
                retryCount++;
                
                if (retryCount < SYNTAX_RETRY) {
                    LOGGER.fine("llm baseline question parse failed: " + e.getMessage() + " retry (" + retryCount + "/" + SYNTAX_RETRY + ")...");
                } else {
                    
                    LOGGER.warning("llm baseline question abort");
                    throw lastException;
                }
            }
        }
        LOGGER.info("handle llm baseline question succeed");
        return result;
    }

    public static String constructBaselinePrompt(String methodText) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptTemplates.get(BACKGROUND));
        sb.append(promptTemplates.get(QUESTION));
        
        sb.append(promptTemplates.get(BASELINE_INPUT_FORMAT));
        sb.append("\n");
        sb.append("<code snippet>\n");                                    
        sb.append("```\n").append(methodText).append("\n```\n");
        sb.append("<end>\n\n");
        return sb.toString();
    }

     





    private static String normalizeNeed(String needRaw) {
        String need = needRaw == null ? "" : needRaw.trim().toLowerCase();
        if (need.equals("yes") || need.equals("no")) return need;
        Matcher m = Pattern.compile("\\b(yes|no)\\b").matcher(need);
        String last = null;
        while (m.find()) last = m.group(1);
        if (last != null) return last;
        throw new IllegalArgumentException("invalid <need> value (no yes/no token found): '"
                + (needRaw == null ? "<null>" : needRaw.length() > 80 ? needRaw.substring(0, 80) + "..." : needRaw) + "'");
    }

    private static void validateMethodResult(String methodResult) {
        String code = LLMResponse.stripCode(methodResult).trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("empty <result> method code");
        }
        
        int balance = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                balance++;
            } else if (c == '}') {
                balance--;
            }
            if (balance < 0) {
                throw new IllegalArgumentException("unbalanced braces in <result> method code");
            }
        }
        if (balance != 0) {
            throw new IllegalArgumentException("unbalanced braces in <result> method code");
        }
        
        
        try {
            ASTParser parser = ASTParser.newParser(AST.JLS21);
            parser.setKind(ASTParser.K_CLASS_BODY_DECLARATIONS);
            parser.setStatementsRecovery(false);
            parser.setSource(code.toCharArray());
            ASTNode root = parser.createAST(null);
            if (!(root instanceof TypeDeclaration)) {
                throw new IllegalArgumentException("<result> not parseable as a class body declaration");
            }
            TypeDeclaration td = (TypeDeclaration) root;
            boolean hasMethod = false;
            for (Object decl : td.bodyDeclarations()) {
                if (decl instanceof MethodDeclaration) {
                    hasMethod = true;
                    break;
                }
            }
            if (!hasMethod) {
                throw new IllegalArgumentException("<result> contains no method declaration");
            }
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (RuntimeException re) {
            throw new IllegalArgumentException("<result> JDT parse failure: " + re.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    
    
    
    //
    
    
    
    
    // ═══════════════════════════════════════════════════════════════════════

     
    public static String formatApiBlockBodyRaw(
            String parentSimpleName,
            int routeDepth,
            String simpleName,
            String exceptionsCommaSeparated,
            String callPathSerialized,
            String description) {
        StringBuilder sb = new StringBuilder();
        sb.append("Function in snippet: ").append(parentSimpleName).append("\n");
        sb.append("After ").append(routeDepth).append(" levels of call, in function: ").append(simpleName).append("\n");
        sb.append("May throw runtime exceptions: ").append(exceptionsCommaSeparated).append("\n");
        if (routeDepth > 0 && callPathSerialized != null && !callPathSerialized.isEmpty()) {
            sb.append("<call path>\n");
            sb.append(callPathSerialized);
            sb.append("<end>\n");
        }
        if (description != null && !description.isEmpty()) {
            sb.append("Exception description: ").append(description).append("\n");
        }
        return sb.toString();
    }

     
    public static String formatThrowBlockBodyRaw(
            String parentSimpleName,
            int routeDepth,
            String simpleName,
            String throwTextsListString,
            String callPathSerialized,
            String description) {
        StringBuilder sb = new StringBuilder();
        if (routeDepth > 0) {
            sb.append("Function in snippet: ").append(parentSimpleName).append("\n");
            sb.append("After ").append(routeDepth)
              .append(" levels of call, the throw statement `").append(throwTextsListString).append("` throws an exception\n");
            if (callPathSerialized != null && !callPathSerialized.isEmpty()) {
                sb.append("<call path>\n");
                sb.append(callPathSerialized);
                sb.append("<end>\n");
            }
        } else {
            sb.append("The throw statement `").append(throwTextsListString).append("` inside this method may throw an exception\n");
        }
        if (description != null && !description.isEmpty()) {
            sb.append("Exception description: ").append(description).append("\n");
        }
        return sb.toString();
    }

     


    public static String constructQuestionPromptFromBlocks(String methodCode, List<String> blockBodies) {
        StringBuilder sb = new StringBuilder();
        sb.append(promptTemplates.get(BACKGROUND));
        sb.append(promptTemplates.get(QUESTION));
        sb.append(promptTemplates.get(blockBodies.isEmpty() ? BASELINE_INPUT_FORMAT : INPUT_FORMAT));
        sb.append("\n");
        sb.append("<code snippet>\n");
        sb.append("```\n").append(methodCode).append("\n```\n");
        sb.append("<end>\n\n");
        for (int i = 0; i < blockBodies.size(); i++) {
            sb.append("<call ").append(i + 1).append(">\n");
            sb.append(blockBodies.get(i));
            sb.append("<end ").append(i + 1).append(">\n");
        }
        return sb.toString();
    }

     
    public static String listToStringStyle(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

     
    public Map<String, String> handleMainQuestionWithPrompt(
            String prompt, String simpleName, String experimentMark) {
        String expectedName = experimentMark + "+" + simpleName + "+" + ExampleHandler.md5Hash(prompt);
        String existData = ExampleHandler.getDataRecord(exampleData, expectedName);
        if (existData == null) {
            LOGGER.info("handling main question by LLM (intermediate path): " + simpleName);
            Map<String, String> result;
            try {
                result = handleMainQuestionRaw(prompt, simpleName);
            } catch (RuntimeException e) {
                LOGGER.warning("handling main question by LLM (intermediate path) failed: " + simpleName);
                return null;
            }
            String dataToRecord = serializeTaggedMessage(result);
            if (ExampleHandler.writeDataRecord(exampleData, dataToRecord, expectedName)) {
                LOGGER.info("experiment data wrote to: " + expectedName);
            } else {
                LOGGER.warning("experiment data failed to wrote to: " + expectedName);
            }
            return result;
        } else {
            LOGGER.info("handling main question by recorded data (intermediate path): " + simpleName);
            return LLMResponse.parseTaggedMessage(existData);
        }
    }

     
    private static Map<String, String> handleMainQuestionRaw(String userPrompt, String simpleName) {
        Map<String, String> result = new HashMap<>();
        int retryCount = 0;
        RuntimeException lastException = null;
        while (retryCount < SYNTAX_RETRY) {
            try {
                LLMApiCaller caller = new LLMApiCaller()
                        .tag("main_question", simpleName)
                        .responseFormat("json_object")
                        .addMessage(promptTemplates.get(REPAIR_SYSTEM_PROMPT), "system")
                        .addMessage(userPrompt, "user");
                try {
                    caller.call();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                result = LLMResponse.parseTaggedMessage(caller.getLastAssistantMessage());
                String needVal = normalizeNeed(result.get("need"));
                result.put("need", needVal);
                if (needVal.equals("yes")) {
                    validateMethodResult(result.get("result"));
                }
                break;
            } catch (RuntimeException e) {
                lastException = e;
                retryCount++;
                if (retryCount < SYNTAX_RETRY) {
                    LOGGER.fine("main question parse failed: " + e.getMessage() + " retry (" + retryCount + "/" + SYNTAX_RETRY + ")...");
                } else {
                    LOGGER.warning("main question abort: " + e.getMessage());
                    throw lastException;
                }
            }
        }
        return result;
    }

}

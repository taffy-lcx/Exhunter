package org.ASTAnalyzer;

import com.google.gson.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Offline postprocessor for generated experiment output JSON. It fills the
 * current evaluation fields directly from AST statement fingerprints and catch
 * type sets, without MethodDiffAnalyzer line matching and without any LLM call.
 *
 * Filled fields:
 *   afterTargetTryBodyFps     = raw human fix try-body fingerprints
 *   resultRawTryBodyFps       = raw model result try-body fingerprints
 *   exceptionTypesAll         = all catch types in methodAfter
 *   resultExceptionTypesAll   = all catch types in methodResult
 *   beforeExceptionTypes      = all catch types already present in methodBefore
 *
 * Legacy line-derived result fields are removed from the output to prevent
 * downstream scripts from accidentally mixing line-number metrics back in.
 *
 * Usage: java -cp ... org.ASTAnalyzer.ReanalyzeOutput <in.json> [out.json]
 *        If out.json is omitted, the input file is overwritten.
 */
public class ReanalyzeOutput {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: ReanalyzeOutput <output.json> [out.json]");
            return;
        }
        String inPath = args[0];
        String outPath = args.length >= 2 ? args[1] : inPath;

        String content = new String(Files.readAllBytes(Paths.get(inPath)));
        JsonArray arr = JsonParser.parseString(content).getAsJsonArray();

        int total = 0, typeFilled = 0;
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            total++;
            String before = getStr(o, "methodBefore");
            String result = getStr(o, "methodResult");
            String after  = getStr(o, "methodAfter");

            if (after != null) {
                java.util.List<String> truthCatchTypes = collectAllCatchTypes(after);
                if (truthCatchTypes != null) o.add("exceptionTypesAll", toStrArr(truthCatchTypes));
                
                org.eclipse.jdt.core.dom.MethodDeclaration mdA = parseMethod(after);
                if (mdA != null) {
                    java.util.List<String> rawFps = StatementFingerprinter.collectAllTryBodyFingerprintsList(mdA);
                    java.util.Collections.sort(rawFps);
                    o.add("afterTargetTryBodyFps", toStrArr(rawFps));
                }
            }
            
            
            // pred_new  = resultExceptionTypesAll - beforeExceptionTypes
            if (before != null) {
                java.util.List<String> beforeCatchTypes = collectAllCatchTypes(before);
                if (beforeCatchTypes != null) o.add("beforeExceptionTypes", toStrArr(beforeCatchTypes));
            }

            if (before != null && result != null && !result.trim().isEmpty()) {
                org.eclipse.jdt.core.dom.MethodDeclaration mdR0 = parseMethod(result);
                if (mdR0 != null) {
                    java.util.List<String> rawR = StatementFingerprinter.collectAllTryBodyFingerprintsList(mdR0);
                    java.util.Collections.sort(rawR);
                    o.add("resultRawTryBodyFps", toStrArr(rawR));
                }
                java.util.List<String> resTypes = collectAllCatchTypes(result);
                if (resTypes != null) { o.add("resultExceptionTypesAll", toStrArr(resTypes)); typeFilled++; }
            }

            clearLegacyLineFields(o);
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.write(Paths.get(outPath), gson.toJson(arr).getBytes());
        System.out.println(inPath + ": total=" + total
                + " resultTypes=" + typeFilled
                + " -> " + outPath);
    }

     




    public static void enrichExampleData(org.ExperimentExecutor.ExampleHandler.ExampleData data) {
        if (data == null) return;
        String before = data.getMethodBefore();
        String after  = data.getMethodAfter();
        String result = data.getMethodResult();

        if (after != null) {
            java.util.List<String> truthCatchTypes = collectAllCatchTypes(after);
            if (truthCatchTypes != null) data.setExceptionTypesAll(truthCatchTypes);
            org.eclipse.jdt.core.dom.MethodDeclaration mdA = parseMethod(after);
            if (mdA != null) {
                java.util.List<String> rawFps = StatementFingerprinter.collectAllTryBodyFingerprintsList(mdA);
                java.util.Collections.sort(rawFps);
                data.setAfterTargetTryBodyFps(rawFps);
            }
        }
        if (before != null) {
            java.util.List<String> beforeCatchTypes = collectAllCatchTypes(before);
            if (beforeCatchTypes != null) data.setBeforeExceptionTypes(beforeCatchTypes);
        }
        if (before != null && result != null && !result.trim().isEmpty()) {
            org.eclipse.jdt.core.dom.MethodDeclaration mdR = parseMethod(result);
            if (mdR != null) {
                java.util.List<String> rawR = StatementFingerprinter.collectAllTryBodyFingerprintsList(mdR);
                java.util.Collections.sort(rawR);
                data.setResultRawTryBodyFps(rawR);
            }
            java.util.List<String> resTypes = collectAllCatchTypes(result);
            if (resTypes != null) data.setResultExceptionTypesAll(resTypes);
        }
    }

     





    public static java.util.List<String> collectAllCatchTypes(String methodCode) {
        if (methodCode == null) return null;
        org.eclipse.jdt.core.dom.MethodDeclaration md = parseMethod(methodCode);
        if (md == null) return null;
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        md.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override
            public boolean visit(org.eclipse.jdt.core.dom.TryStatement node) {
                for (Object cc : node.catchClauses()) {
                    org.eclipse.jdt.core.dom.SingleVariableDeclaration param =
                        ((org.eclipse.jdt.core.dom.CatchClause) cc).getException();
                    if (param == null) continue;
                    org.eclipse.jdt.core.dom.Type t = param.getType();
                    if (t == null) continue;
                    if (t.isUnionType()) {
                        
                        for (Object u : ((org.eclipse.jdt.core.dom.UnionType) t).types()) {
                            String name = ((org.eclipse.jdt.core.dom.Type) u).toString().trim();
                            if (!name.isEmpty()) set.add(name);
                        }
                    } else {
                        String name = t.toString().trim();
                        if (!name.isEmpty()) set.add(name);
                    }
                }
                return true; 
            }
        });
        return new java.util.ArrayList<>(set);
    }

     





    private static void clearLegacyLineFields(JsonObject o) {
        String[] legacy = {
            "changed",
            "resultBeforeTargetNoNestingLines",
            "resultAfterTargetNoNestingLines",
            "resultCoveredLines",
            "resultBeforeTargetStartLine",
            "resultBeforeTargetEndLine",
            "resultAfterTargetStartLine",
            "resultAfterTargetEndLine",
            "resultCatchBlocks",
            "resultExceptionTypes"
        };
        for (String k : legacy) o.remove(k);
    }

    public static org.eclipse.jdt.core.dom.MethodDeclaration parseMethod(String methodCode) {
        try {
            org.eclipse.jdt.core.dom.CompilationUnit cu = Util.getMethodCompilationUnit(methodCode);
            MethodVisitor mv = new MethodVisitor();
            cu.accept(mv);
            return mv.getMethodDeclaration();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String getStr(JsonObject o, String k) {
        return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null;
    }


    private static JsonArray toStrArr(Collection<String> c) {
        JsonArray a = new JsonArray();
        for (String s : c) a.add(s);
        return a;
    }
}

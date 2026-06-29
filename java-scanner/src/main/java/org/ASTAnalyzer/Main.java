package org.ASTAnalyzer;

import org.eclipse.jdt.core.dom.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Main {
    public static void main(String[] args) {
        try {
            if (args.length != 3)
            {
//                throw new IllegalArgumentException("wrong input parameter number");
                System.err.println("wrong input parameter number: " + args.length);
                System.out.println("1");
                return;
            }
            String v1Input = args[0];
            String v2Input = args[1];

            List<TryPatternData> results = null;
            if (Objects.equals(args[2], "-f")) {
                results = analyzeFileDiff(v1Input, v2Input);
//        List<TryPatternData> results = analyzeFileDiff("src\\main\\java\\org\\ASTAnalyzer\\example1.java", "src\\main\\java\\org\\ASTAnalyzer\\example2.java");
            } else if (Objects.equals(args[2], "-m")) {
                results = MethodDiffAnalyzer.analyze(v1Input, v2Input);
            } else {
//                throw new IllegalArgumentException("unsupport parameter: " + args[2]);
                System.err.println("unsupport parameter: " + args[2]);
                System.out.println("1");
                return;
            }
            if (results == null) {
                System.out.println("1");
                return;
            }
            JSONArray jsonArray = new JSONArray();
            for (TryPatternData data : results) {
                jsonArray.put(data.getJson());
//                Util.fromJSONPrintTry(data.getJson());
            }
            System.out.println(jsonArray.toString());
        } catch (Exception ignore) {
            ignore.printStackTrace();
            System.err.println("unknown exception");
            System.out.println("1");
        }
    }

    public static List<TryPatternData> analyzeFileDiff(String javaFilePath1, String javaFilePath2) {
        CompilationUnit cu1 = Util.getCompilationUnit(javaFilePath1);
        MethodFinder mf1 = new MethodFinder();
        cu1.accept(mf1);
        CompilationUnit cu2 = Util.getCompilationUnit(javaFilePath2);
        MethodFinder mf2 = new MethodFinder();
        cu2.accept(mf2);
        List<TryPatternData> results = new ArrayList<>();
        for (MethodDeclaration md2 : mf2.getMethods()) {
            for (MethodDeclaration md1 : mf1.getMethods()) {
                if (Util.generateMethodName(md2).equals(Util.generateMethodName(md1))
                        && !md1.toString().equals(md2.toString())) {
                    try {
                        results.addAll(Objects.requireNonNull(MethodDiffAnalyzer.analyze(md1.toString(), md2.toString())));
                    } catch (NullPointerException e) {
                        System.err.println("method analyze error");
                    }

                }
            }
        }
        return results;
    }
}
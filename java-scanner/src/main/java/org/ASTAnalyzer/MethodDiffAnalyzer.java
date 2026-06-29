package org.ASTAnalyzer;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.TryStatement;

import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class MethodDiffAnalyzer {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    public static List<TryPatternData> analyze(String method1, String method2){
        
        if (!method2.contains("try") || !method2.contains("catch")){
            return new ArrayList<>();
        }
        
        CompilationUnit methodCU1 = Util.getMethodCompilationUnit(method1);
        CompilationUnit methodCU2 = Util.getMethodCompilationUnit(method2);
        MethodVisitor mv1 = new MethodVisitor();
        MethodVisitor mv2 = new MethodVisitor();
        
        try {
            methodCU1.accept(mv1);
            methodCU2.accept(mv2);
        } catch (IllegalStateException | NullPointerException e) {
            LOGGER.warning("method visit failed, method1: \n" +method1 + "\nmethod2: \n" + method2);
            return null;
        }
        
        Map<Integer, Integer> lineMatchSec2First = matchLines(mv1.getMethodBlock(), mv2.getMethodBlock());
        int[] method1lineTryNestings = mv1.getMethodLineTryNestings();
        int[] method2lineTryNestings = mv2.getMethodLineTryNestings();
        
        List<TryBlock> tryWithPatterns = new ArrayList<>();
        List<TryPatternData> results = new ArrayList<>();
        
        Map<String, Integer> m1Fps = null;

        for (TryBlock tb: mv2.getTryBlocks()){
            
            int offset = 0;
            if (tb.getNesting() > 0) {
                for (TryStatement parentTS : tb.getParentTries()) {
                    if (tryBlockListContainsTryStatement(tryWithPatterns, parentTS)) {
                        offset += 1;
                    }
                }
            }
            List<Integer> validIndexes = tb.getValidIndexesInMethod();
            List<Integer> matchedValidIndexes = new ArrayList<>();
            for (int i : validIndexes){
                if (lineMatchSec2First.containsKey(i)){
                    if (method2lineTryNestings[i] > method1lineTryNestings[lineMatchSec2First.get(i)] + offset){
                        matchedValidIndexes.add(i);
                    }
                }
            }
            boolean primaryPass = !matchedValidIndexes.isEmpty();
            if (primaryPass) {
                
                List<Integer> matchedLinesNotInNestedTry = new ArrayList<>(matchedValidIndexes);
                matchedLinesNotInNestedTry.retainAll(tb.getSelfNoNestingLines());
                if (matchedLinesNotInNestedTry.isEmpty()) {
                    primaryPass = false;
                }
            }

            if (!primaryPass) {
                
                
                if (m1Fps == null) {
                    m1Fps = StatementFingerprinter.collectMethodFingerprints(mv1.getMethodDeclaration());
                }
                Set<String> tbFps = StatementFingerprinter.collectTryBodyFingerprints(tb.getTryNode());
                int tbInnerDepth = tb.getNesting() + 1;
                boolean fallbackPass = false;
                for (String fp : tbFps) {
                    Integer m1d = m1Fps.get(fp);
                    if (m1d != null && tbInnerDepth > m1d + offset) {
                        fallbackPass = true;
                        break;
                    }
                }
                if (!fallbackPass) continue;
                
                
                for (int i : validIndexes) {
                    lineMatchSec2First.putIfAbsent(i, 0);
                }
            }

            
            tryWithPatterns.add(tb);
            results.add(new TryPatternData(tb, mv1.getMethodDeclaration(), mv2.getMethodDeclaration(), lineMatchSec2First));
        }
//        return tryWithPatterns;
        return results;
    }

    public static Map<Integer, Integer> matchLines(String version1, String version2) {
        String[] rawLines1 = version1.split("\n");
        String[] rawLines2 = version2.split("\n");
        
        
        MergedLines merged1 = mergeMultiLineControlFlow(rawLines1);
        MergedLines merged2 = mergeMultiLineControlFlow(rawLines2);
        String[] lines1 = merged1.lines;
        String[] lines2 = merged2.lines;

        int[][] d = new int[lines1.length + 1][lines2.length + 1];

        for (int i = 0; i <= lines1.length; i++) {
            d[i][0] = i; 
        }
        for (int j = 0; j <= lines2.length; j++) {
            d[0][j] = j; 
        }

        for (int i = 1; i <= lines1.length; i++) {
            for (int j = 1; j <= lines2.length; j++) {
                if (Util.isLineMatch(lines1[i - 1], lines2[j - 1])) {
                    d[i][j] = d[i - 1][j - 1]; 
                } else {
                    d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + 1); 
                }
            }
        }

        int i = lines1.length;
        int j = lines2.length;

        Map<Integer, Integer> sec2first = new HashMap<>();
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && Util.isLineMatch(lines1[i - 1], lines2[j - 1])) {
                
                int mergedI = i - 1;
                int mergedJ = j - 1;
                int origIRep = merged1.origStart[mergedI];
                for (int o = merged2.origStart[mergedJ]; o < merged2.origEnd[mergedJ]; o++) {
                    sec2first.put(o, origIRep);
                }
                i--;
                j--;
            } else if (i > 0 && (j == 0 || d[i][j] == d[i - 1][j] + 1)) {
                i--;
            } else if (j > 0 && (i == 0 || d[i][j] == d[i][j - 1] + 1)) {
                j--;
            } else {
                i--;
                j--;
            }
        }

        return sec2first;
    }

    private static final Pattern ELSE_START = Pattern.compile("^\\s*else(\\s+if\\b|\\s*\\{|\\s+).*");

    private static class MergedLines {
        String[] lines;
        int[] origStart;
        int[] origEnd;
    }

    private static MergedLines mergeMultiLineControlFlow(String[] raw) {
        List<String> outLines = new ArrayList<>();
        List<Integer> outStart = new ArrayList<>();
        int i = 0;
        while (i < raw.length) {
            int startIdx = i;
            StringBuilder sb = new StringBuilder(raw[i]);
            i++;
            
            while (i < raw.length
                    && sb.toString().replaceAll("\\s+$", "").endsWith("}")
                    && ELSE_START.matcher(raw[i]).matches()) {
                sb.append(' ').append(raw[i].replaceAll("^\\s+", ""));
                i++;
            }
            outLines.add(sb.toString());
            outStart.add(startIdx);
        }
        MergedLines r = new MergedLines();
        r.lines = outLines.toArray(new String[0]);
        r.origStart = new int[outStart.size()];
        r.origEnd = new int[outStart.size()];
        for (int k = 0; k < outStart.size(); k++) {
            r.origStart[k] = outStart.get(k);
            r.origEnd[k] = (k + 1 < outStart.size()) ? outStart.get(k + 1) : raw.length;
        }
        return r;
    }

    private static boolean tryBlockListContainsTryStatement(List<TryBlock> listTryBlock, TryStatement tryStatement) {
        boolean containFlag = false;
        for (TryBlock tb : listTryBlock) {
            if (tb.equals(tryStatement)) {
                containFlag = true;
                break;
            }
        }
        return containFlag;
    }

    public static int[] getIntersection(int[] arr1, int[] arr2) {
        Set<Integer> set = new HashSet<>();
        Set<Integer> intersect = new HashSet<>();

        for (int num : arr1) {
            set.add(num);
        }

        for (int num : arr2) {
            if (set.contains(num)) {
                intersect.add(num);
            }
        }

        int[] intersection = new int[intersect.size()];
        int index = 0;
        for (int num : intersect) {
            intersection[index++] = num;
        }

        return intersection;
    }
}

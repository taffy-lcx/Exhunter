package org.ASTAnalyzer;
import org.eclipse.jdt.core.dom.*;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Util {
    private static ASTParser astParser;

    static {
        astParser = ASTParser.newParser(AST.JLS21);
        astParser.setKind(ASTParser.K_COMPILATION_UNIT);
        
        
    }

    public static CompilationUnit getMethodCompilationUnit(String methodContent) throws IllegalStateException{
        astParser.setSource(methodContent.toCharArray());
        return (CompilationUnit) (astParser.createAST(null));
    }

    public static CompilationUnit getCompilationUnit(String javaFilePath){
        byte[] input = null;
        try {
            BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(javaFilePath));
            input = new byte[bufferedInputStream.available()];
            bufferedInputStream.read(input);
            bufferedInputStream.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        astParser.setSource(new String(input).toCharArray());

        return (CompilationUnit) (astParser.createAST(null));
    }

    public static String generateMethodName(MethodDeclaration node){
        StringBuilder sb = new StringBuilder();
        sb.append(node.getName().toString());
        sb.append("(");
        List<SingleVariableDeclaration> parameters = node.parameters();
        for (SingleVariableDeclaration parameter : parameters) {
            sb.append(parameter.getType().toString());
            sb.append(",");
        }
        sb.append(")");
        return sb.toString().replace(",)", ")");
    }

    private static final Pattern declarationAssignmentPattern = Pattern.compile("^[\\s\t]*(\\w+)\\s+(\\w+)\\s*=\\s*.*;\\s*$");
    private static final Pattern tryWithResourcePattern = Pattern.compile("^[\\s\t]*try\\s*\\((.*)\\)\\s*\\{\\s*$");
    public static boolean isLineMatch(String oldLine, String newLine) {
        if (isLineMatchPrimary(oldLine, newLine)) return true;
        
        
        return isLineMatchNoWhitespace(oldLine, newLine);
    }

    private static boolean isLineMatchPrimary(String oldLine, String newLine) {
        Matcher declarationAssignmentMatcher = declarationAssignmentPattern.matcher(oldLine);
        Matcher tryWithResourceMatcher = tryWithResourcePattern.matcher(newLine);
        if (declarationAssignmentMatcher.matches()) {
            String withoutType = oldLine.replaceAll((declarationAssignmentMatcher.group(1) + " "), "");
            return (isLineMatchWithoutIndentation(oldLine, newLine)
                    || isLineMatchWithoutIndentation(withoutType, newLine)
                    || isLineMatchPrimary(withoutType, newLine));
        } else if (tryWithResourceMatcher.matches()) {
            String resourceAssignment = tryWithResourceMatcher.group(1);
            return (isLineMatchWithoutIndentation(oldLine, newLine)
                    || isLineMatchWithoutIndentation(oldLine, resourceAssignment)
                    || isLineMatchPrimary(oldLine, resourceAssignment));
        }
        return isLineMatchWithoutIndentation(oldLine, newLine);
    }

    private static boolean isLineMatchNoWhitespace(String oldLine, String newLine) {
        String l1 = oldLine.replaceAll("\\s+", "");
        String l2 = newLine.replaceAll("\\s+", "");
        if (l1.length() <= 2 || l2.length() <= 2) return false;
        
        if (!l1.matches(".*[A-Za-z0-9].*") || !l2.matches(".*[A-Za-z0-9].*")) return false;
        return l1.equals(l2);
    }

    public static boolean isLineMatchWithoutIndentation(String l1, String l2){
        
        String trimmedStr1 = l1
                .replaceAll("\\(", " ( ")
                .replaceAll("\\)", " ) ")
                .replaceAll("\\[", " [ ")
                .replaceAll("]", " ] ")
                .replaceAll("\\{", " { ")
                .replaceAll("}", " } ")
                .replaceAll(";", " ; ")
                .replaceAll("^[\\s\t]+", "")
                .replaceAll("[\\s\t]+$", "")
                .replaceAll("\\s{2,}", " ");
        String trimmedStr2 = l2
                .replaceAll("\\(", " ( ")
                .replaceAll("\\)", " ) ")
                .replaceAll("\\[", " [ ")
                .replaceAll("]", " ] ")
                .replaceAll("\\{", " { ")
                .replaceAll("}", " } ")
                .replaceAll(";", " ; ")
                .replaceAll("^[\\s\t]+", "")
                .replaceAll("[\\s\t]+$", "")
                .replaceAll("\\s{2,}", " ");

        return trimmedStr1.equals(trimmedStr2);
    }

    public static List<String> splitStringByNewline(String input) {
        
        return Arrays.asList(input.split("\\n"));
    }

    public static List<String> diffLines(String[] lines1, String[] lines2) {

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

        List<String> result = new ArrayList<>();
        Map<Integer, Integer> sec2first = new HashMap<>();
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && Util.isLineMatch(lines1[i - 1], lines2[j - 1])) {
                result.add(" " + lines1[i - 1]);
                sec2first.put(j - 1, i - 1); 
                i--;
                j--;
            } else if (i > 0 && (j == 0 || d[i][j] == d[i - 1][j] + 1)) {
                result.add("- " + lines1[i - 1]);
                i--;
            } else if (j > 0 && (i == 0 || d[i][j] == d[i][j - 1] + 1)) {
                result.add("+ " + lines2[j - 1]);
                j--;
            } else {
                result.add("~ " + lines1[i - 1] + " -> " + lines2[j - 1]);
                i--;
                j--;
            }
        }
        
        List<String> reversedResult = new ArrayList<>();
        for (int k = result.size() - 1; k >= 0; k--) {
            reversedResult.add(result.get(k));
        }

        for (String s : reversedResult) {
            System.out.println(s);
        }
        return reversedResult;
    }

    public static void fromJSONPrintTry(JSONObject jsonObject) {
        try {
            String[] methodAfterLines = ((String) jsonObject.get("methodAfter")).split("\n");
            String[] methodBeforeLines = ((String) jsonObject.get("methodBefore")).split("\n");
            Integer afterStart = jsonObject.getInt("afterTargetStartLine");
            Integer afterEnd = jsonObject.getInt("afterTargetEndLine");
            Integer beforeStart = jsonObject.getInt("beforeTargetStartLine");
            Integer beforeEnd = jsonObject.getInt("beforeTargetEndLine");
            System.out.println("---------------------------------------------------");
            System.out.println("method name: " + jsonObject.get("methodName"));
            System.out.println("-------------------method before-------------------");
            for (int i = 0; i < methodBeforeLines.length; i++) {
                String mark = "";
                if (i >= beforeStart && i < beforeEnd){
                    mark = "O ";
                } else {
                    mark = "  ";
                }
                System.out.println(mark + methodBeforeLines[i]);
            }
            System.out.println("-------------------method  after-------------------");
            for (int i = 0; i < methodAfterLines.length; i++) {
                String mark = "";
                if (i >= afterStart && i < afterEnd){
                    mark = "O ";
                } else {
                    mark = "  ";
                }
                System.out.println(mark + methodAfterLines[i]);
            }

        } catch (Exception ignore) {
            System.err.println("json format error");
        }
    }
}

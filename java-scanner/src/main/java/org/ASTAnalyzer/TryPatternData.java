package org.ASTAnalyzer;

import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;

import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class TryPatternData {
    private final TryBlock tryBlockAfter;
    private final MethodDeclaration methodBefore;
    private final MethodDeclaration methodAfter;
    private final Map<Integer, Integer> lineMatchSec2First;
    private String projectName = "";
    private String classFilePath = "";
    private String methodName = "";
    private String commitHash = "";
    private String url = "";

    public TryPatternData(TryBlock tb2, MethodDeclaration md1, MethodDeclaration md2, Map<Integer, Integer> lineMatch) {
        this.tryBlockAfter = tb2;
        this.methodBefore = md1;
        this.methodAfter = md2;
        this.lineMatchSec2First = lineMatch;
        this.methodName = Util.generateMethodName(methodBefore);
    }

    public TryPatternData setProjectName(String projectName) {
        this.projectName = projectName;
        return this;
    }

    public TryPatternData setClassFilePath(String classFilePath) {
        this.classFilePath = classFilePath;
        return this;
    }

    public TryPatternData setMethodName(String methodName) {
        this.methodName = methodName;
        return this;
    }

    public TryPatternData setCommitHash(String commitHash) {
        this.commitHash = commitHash;
        return this;
    }

    public TryPatternData setUrl(String url) {
        this.url = url;
        return this;
    }

    public JSONObject getJson() {
        
        String oldVersionMethod = methodBefore.toString();
        String newVersionMethod = methodAfter.toString();
        Integer newVersionTargetStartPos = tryBlockAfter.getFormatStartLine();
        Integer newVersionTargetEndPos = tryBlockAfter.getFormatStartLine() + tryBlockAfter.getFormatLength();
        List<Integer> matchedOldVersionLines = new ArrayList<>();
        for (Integer i : tryBlockAfter.getValidIndexesInMethod()) {
            if (lineMatchSec2First.containsKey(i)) {
                matchedOldVersionLines.add(lineMatchSec2First.get(i));
            }
        }
        List<Integer> matchedOldVersionIndividualLines = new ArrayList<>();
        for (Integer i : tryBlockAfter.getSelfNoNestingLines()) {
            if (lineMatchSec2First.containsKey(i)) {
                matchedOldVersionIndividualLines.add(lineMatchSec2First.get(i));
            }
        }
        Integer oldVersionTargetStartPos = Collections.min(matchedOldVersionLines);
        Integer oldVersionTargetEndPos = Collections.max(matchedOldVersionLines) + 1;
        List<String> exceptionTypes = new ArrayList<>();
        List<String> catchStatements = new ArrayList<>();
        for (Object cc : tryBlockAfter.getTryNode().catchClauses()) {
            exceptionTypes.add(((CatchClause) cc).getException().toString().split(" ")[0]);
            catchStatements.add(((CatchClause) cc).getBody().toString());
        }

        
//        JSONObject jsonObject = getExternalJson();
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("methodName", methodName);
        jsonObject.put("methodBefore", oldVersionMethod);
        jsonObject.put("methodAfter", newVersionMethod);
        jsonObject.put("beforeTargetStartLine", oldVersionTargetStartPos);
        jsonObject.put("beforeTargetEndLine", oldVersionTargetEndPos);
        JSONArray jsonArrayIndividualLinesBefore = new JSONArray(matchedOldVersionIndividualLines);
        jsonObject.put("beforeTargetNoNestingLines", jsonArrayIndividualLinesBefore);

        jsonObject.put("afterTargetStartLine", newVersionTargetStartPos);
        jsonObject.put("afterTargetEndLine", newVersionTargetEndPos);
        JSONArray jsonArrayIndividualLinesAfter = new JSONArray(tryBlockAfter.getSelfNoNestingLines());
        jsonObject.put("afterTargetNoNestingLines", jsonArrayIndividualLinesAfter);

        JSONArray jsonArrayException = new JSONArray();
        for (String s : exceptionTypes) {
            jsonArrayException.put(s);
        }
        jsonObject.put("exceptionTypes", jsonArrayException);

        JSONArray jsonArrayCatch = new JSONArray();
        for (String s : catchStatements) {
            jsonArrayCatch.put(s);
        }
        jsonObject.put("catchBlocks", jsonArrayCatch);

        
        
        
        //   coveredStmtFps = tryBodyFps ∩ methodBeforeFps
        
        
        
        Set<String> tryBodyFps = StatementFingerprinter.collectTryBodyFingerprints(tryBlockAfter.getTryNode());
        Set<String> methodBeforeFps = StatementFingerprinter.collectMethodFingerprints(methodBefore).keySet();
        Set<String> covered = new TreeSet<>(tryBodyFps);
        covered.retainAll(methodBeforeFps);
        JSONArray jsonArrayFps = new JSONArray();
        for (String fp : covered) jsonArrayFps.put(fp);
        jsonObject.put("coveredStmtFps", jsonArrayFps);
        return jsonObject;
    }
}

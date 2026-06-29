package org.callTreeGenerator;

import java.util.*;

public class UncaughtExceptionInfoThrow extends UncaughtExceptionInfo {

    private List<ThrowExceptionInfo> uncaughtThrowExceptionInfo; 
    private List<ThrowExceptionInfo> checkedUncaughtThrowExceptionInfo = null; 
    // Per-throw LLM remarks. Keyed by the ThrowExceptionInfo identity so re-ordering or filtering
    // does not lose the mapping; LinkedHashMap preserves insertion order for stable prompt output.
    private final LinkedHashMap<ThrowExceptionInfo, String> throwDescriptions = new LinkedHashMap<>();

    public UncaughtExceptionInfoThrow(MethodTreeNode methodTreeNode, List<MethodTreeNode> nodeRoute, Set<String> uncaughtExceptions) {
        super(methodTreeNode, nodeRoute, uncaughtExceptions);
        initializeUncaughtThrowExceptionInfo();
    }
    private void initializeUncaughtThrowExceptionInfo() {
        uncaughtThrowExceptionInfo = new ArrayList<>();
        for (ThrowExceptionInfo throwExceptionInfo: methodTreeNode.getThrowExceptionInfo()) {
            if (uncaughtExceptions.contains(throwExceptionInfo.getExceptionQualifiedName())) {
                uncaughtThrowExceptionInfo.add(throwExceptionInfo);
            }
        }
    }

    public List<ThrowExceptionInfo> getUncaughtThrowExceptionInfo() {
        return uncaughtThrowExceptionInfo;
    }

    public List<ThrowExceptionInfo> getCheckedUncaughtThrowExceptionInfo() {
        return checkedUncaughtThrowExceptionInfo;
    }

    public void setCheckedUncaughtThrowExceptionInfo(List<ThrowExceptionInfo> checkedUncaughtThrowExceptionInfo) {
        this.checkedUncaughtThrowExceptionInfo = checkedUncaughtThrowExceptionInfo;
    }

    public void removeCheckedUncaughtThrowExceptionInfo(ThrowExceptionInfo checkedUncaughtThrowExceptionInfo) {
        if (this.checkedUncaughtThrowExceptionInfo == null) {
            this.checkedUncaughtThrowExceptionInfo = new ArrayList<>();
            return;
        }
        this.checkedUncaughtThrowExceptionInfo.remove(checkedUncaughtThrowExceptionInfo);
    }

    public void setThrowDescription(ThrowExceptionInfo throwInfo, String remark) {
        if (throwInfo == null || remark == null || remark.isEmpty()) return;
        throwDescriptions.put(throwInfo, remark);
    }

    public String getThrowDescription(ThrowExceptionInfo throwInfo) {
        if (throwInfo == null) return null;
        return throwDescriptions.get(throwInfo);
    }

    
    private final LinkedHashMap<ThrowExceptionInfo, Double> throwScores = new LinkedHashMap<>();

    public void setThrowScores(Map<ThrowExceptionInfo, Double> scores) {
        throwScores.clear();
        if (scores != null) throwScores.putAll(scores);
    }

    public Double getThrowScore(ThrowExceptionInfo throwInfo) {
        if (throwInfo == null) return null;
        return throwScores.get(throwInfo);
    }

    public Map<ThrowExceptionInfo, Double> getThrowScores() {
        return throwScores;
    }

    public String toString() {
        return "method: " + methodTreeNode.getQualifiedName() + "\nroute:\n    " + nodeRoute.toString() + "\nthrows:\n" + throwDisplayString(uncaughtThrowExceptionInfo, 4);
//        return "route: " + nodeRoute.toString() + ", exception: " + uncaughtExceptions.toString();
    }
    public String throwDisplayString(List<ThrowExceptionInfo> throwExceptionInfoes, int offset) {
        StringBuilder sb = new StringBuilder();
        for (ThrowExceptionInfo throwExceptionInfo: throwExceptionInfoes) {
            sb.append(" ".repeat(offset));
            sb.append(throwExceptionInfo.getExceptionQualifiedName());
            sb.append(":");
            sb.append(throwExceptionInfo.toString().replaceAll("\n", "\t"));
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override
    public Boolean isContainUncaughtExceptions() {
        if (checkedUncaughtThrowExceptionInfo == null) {
            return (!uncaughtThrowExceptionInfo.isEmpty());
        } else {
            return (!checkedUncaughtThrowExceptionInfo.isEmpty());
        }
    }
}

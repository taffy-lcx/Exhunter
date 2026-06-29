package org.callTreeGenerator;

import java.util.ArrayList;
import java.util.List;

public class MethodCallEdge {
    private List<TryCatchNestingInfo> tryCatchNestingInfos = new ArrayList<>();

    public void addMethodCallInfo(TryCatchNestingInfo tryCatchNestingInfo) {
        tryCatchNestingInfos.add(tryCatchNestingInfo);
    }

    public List<TryCatchNestingInfo> getMethodCallInfos() {
        return tryCatchNestingInfos;
    }

//    public List<Integer> isUncaught(String exceptionName) {
//        List<Integer> result = new ArrayList<>();
//        for (Integer i = 0; i < tryCatchNestingInfos.size(); i++) {
//            if (!tryCatchNestingInfos.get(i).isCaught(exceptionName)) {
//                result.add(i);
//            }
//        }
//        return result;
//    }
//
//    public boolean isCaught(String exceptionName) {
//        return (isUncaught(exceptionName).isEmpty());
//    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("calls:[");
        for (TryCatchNestingInfo tryCatchNestingInfo : tryCatchNestingInfos) {
            sb.append(tryCatchNestingInfo.toString());
            sb.append(",\n");
        }
        sb.append("end");
        return sb.toString().replaceAll(",\nend", "]");
    }
}

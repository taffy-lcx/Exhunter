package org.callTreeGenerator;

import org.jdkAnalyzer.SQLUtil;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

 







public class ExceptionCharacteristicManager {
    public static final String THROWABLE = "java.lang.Throwable";
    public static final String RUNTIME_EXCEPTION = "java.lang.RuntimeException";
    
    private static final Map<String, String> archivedExceptions = new HashMap<>();
    private static final Map<String, String> archivedQualifiedNameMap = new HashMap<>();

    static {
        try (ResultSet rs = SQLUtil.executeSelect("SELECT qualified_name, parent FROM exceptions");) {
            if (rs == null) {
                throw new RuntimeException("exception data initialize failed");
            }
            while (rs.next()) {
                archivedExceptions.put(rs.getString("qualified_name"), rs.getString("parent"));
            }
        } catch (SQLException ignore) {
            throw new RuntimeException("exception data initialize failed");
        }
        for (String qualifiedName: archivedExceptions.keySet()) {
            if (qualifiedName.contains(".")) {
                archivedQualifiedNameMap.put(getSubstringAfterLastDot(qualifiedName), qualifiedName);
            }
        }
    }

     


    public static boolean isArchived(String qualifiedName) {
        return (archivedExceptions.containsKey(qualifiedName));
    }

    public static String getSubstringAfterLastDot(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        int lastDotIndex = input.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return input.substring(lastDotIndex + 1);
    }

    public static boolean isArchivedRuntime(String simpleName) {
        String qualifiedName = getArchivedQualifiedName(simpleName);
        return isArchivedQualifiedRuntime(qualifiedName);
    }
    public static boolean isArchivedQualifiedRuntime(String qualifiedName) {
        if (qualifiedName == null) {
            return false;
        }
        if (qualifiedName.equals(RUNTIME_EXCEPTION)) {
            return true;
        }
        if (qualifiedName.equals(THROWABLE)) {
            return false;
        }
        return isArchivedQualifiedRuntime(archivedExceptions.get(qualifiedName));
    }

    public static String getArchivedQualifiedName(String simpleName) {
        String mappedName = archivedQualifiedNameMap.get(simpleName);
        if (mappedName == null) {
            return simpleName;
        } else {
            return mappedName;
        }
    }

    private Map<String, String> localExceptions = new HashMap<>(archivedExceptions);
    private Map<String, String> qualifiedNameMap = new HashMap<>();

    public ExceptionCharacteristicManager() {
        updateQualifiedNameMap(localExceptions.keySet());
    }

    public void updateLocalExceptions(Map<String, String> localExceptions) {
        this.localExceptions.putAll(localExceptions);
        updateQualifiedNameMap(localExceptions.keySet());
    }

    private void updateQualifiedNameMap(Set<String> qualifiedNames) {
        for (String qualifiedName: qualifiedNames) {
            if (qualifiedName.contains(".")) {
                this.qualifiedNameMap.put(getSubstringAfterLastDot(qualifiedName), qualifiedName);
            }
        }
    }

     


    public boolean isKnown(String qualifiedName) {
        return (localExceptions.containsKey(qualifiedName));
    }

     




    public boolean isChildOf(String qualifiedName1, String qualifiedName2) {
        if (!isKnown(qualifiedName1)) {
            return false;
        }
        String nowName = qualifiedName1;
        if (nowName == null) return false;
        while (!Objects.equals(nowName, THROWABLE)) {
            if (nowName.equals(qualifiedName2)) {
                return true;
            }
            nowName = localExceptions.get(nowName);
        }
        return nowName.equals(qualifiedName2);
    }

     



    public boolean isRuntime(String qualifiedName) {
        if (qualifiedName == null) {
            return false;
        }
        if (qualifiedName.equals(RUNTIME_EXCEPTION)) {
            return true;
        }
        if (qualifiedName.equals(THROWABLE)) {
            return false;
        }
        return isRuntime(localExceptions.get(qualifiedName));
    }

     



    public String getQualifiedName(String simpleName) {
        String mappedName = qualifiedNameMap.get(simpleName);
        if (mappedName == null) {
            for (String qualifiedName: localExceptions.keySet()) {
                String[] splits = qualifiedName.split("\\.");
                if (splits.length > 0 && splits[splits.length - 1].equals(simpleName)) {
                    qualifiedNameMap.put(simpleName, qualifiedName);
                    return qualifiedName;
                }
            }
            return simpleName;
        } else {
            return mappedName;
        }
    }

     



    public String getQualifiedNameElseNull(String simpleName) {
        String mappedName = qualifiedNameMap.get(simpleName);
        if (mappedName == null) {
            for (String qualifiedName: localExceptions.keySet()) {
                String[] splits = qualifiedName.split("\\.");
                if (splits.length > 0 && splits[splits.length - 1].equals(simpleName)) {
                    qualifiedNameMap.put(simpleName, qualifiedName);
                    return qualifiedName;
                }
            }
            return null;
        } else {
            return mappedName;
        }
    }

     



    public boolean isCaught(TryCatchNestingInfo tryCatchNestingInfo, String qualifiedName) {
        for (String exceptionType: tryCatchNestingInfo.getExceptionTypes(this)) {
            if (isChildOf(qualifiedName, exceptionType)) {
                return true;
            }
        }
        return false;
    }

     



    public boolean isCaught(MethodCallEdge methodCallEdge, String qualifiedName) {
        if (qualifiedName == null) return false;
        try {
            for (TryCatchNestingInfo tryCatchNestingInfo : methodCallEdge.getMethodCallInfos()) {
                if (!isCaught(tryCatchNestingInfo, qualifiedName)) {
                    return false;
                }
            }
        } catch (NullPointerException e) {
            // ignore
            return false;
        }
        return true;
    }

}

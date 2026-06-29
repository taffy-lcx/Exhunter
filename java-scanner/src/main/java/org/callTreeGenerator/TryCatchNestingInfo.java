package org.callTreeGenerator;

import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.jdkAnalyzer.SQLUtil;

import java.util.*;

public class TryCatchNestingInfo {
    private Set<CatchClause> caughtExceptions;
    private List<String> exceptionTypes = null;

    public TryCatchNestingInfo() {
        this(new HashSet<>());
    }

    public TryCatchNestingInfo(Set<CatchClause> caughtExceptions) {
        this.caughtExceptions = caughtExceptions;
    }

    public String toString() {
        if (caughtExceptions.isEmpty()) {
            return "[]";
        } else {
            return getExceptionTypes().toString();
        }
    }

     




    public List<String> getExceptionTypes(ExceptionCharacteristicManager exceptionCharacteristicManager) {
        if (exceptionTypes == null) {
            List<String> exceptionTypes = new ArrayList<>();
            for (CatchClause cc: caughtExceptions) {
                String typeString = cc.getException().getType().toString();
                if (typeString.contains("|")) {
                    for (String type: typeString.split("\\|")) {
                        if (exceptionCharacteristicManager == null) {
                            String fullExceptionName = SQLUtil.getFullExceptionName(type);
                            exceptionTypes.add(Objects.requireNonNullElse(fullExceptionName, type));
                        } else {
                            exceptionTypes.add(exceptionCharacteristicManager.getQualifiedName(type));
                        }
                    }
                } else {
                    ITypeBinding typeBinding = cc.getException().resolveBinding().getType();
                    if (typeBinding == null) {
                        if (exceptionCharacteristicManager == null) {
                            String fullExceptionName = SQLUtil.getFullExceptionName(typeString);
                            exceptionTypes.add(Objects.requireNonNullElse(fullExceptionName, typeString));
                        } else {
                            exceptionTypes.add(exceptionCharacteristicManager.getQualifiedName(typeString));
                        }
                    } else {
                        exceptionTypes.add(typeBinding.getQualifiedName());
                    }

                }
            }
            this.exceptionTypes = exceptionTypes;
            return exceptionTypes;
        } else {
            return exceptionTypes;
        }
    }

    public List<String> getExceptionTypes(){
        return getExceptionTypes(null);
    }
}

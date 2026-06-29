package org.ExperimentExecutor;

import java.util.ArrayList;
import java.util.List;

 











public class IntermediateRecord {
    public ExampleHandler.ExampleData example;   
    public String rootMethodCode;                
    public String staticError;                   
    public List<Candidate> candidates = new ArrayList<>();

    public IntermediateRecord() {}

    public static class Candidate {
        public String kind;                          // "api" | "throw"
        public String hash;                          
        public String description;                   
        public String simpleName;                    // uei.getMethodTreeNode().getSimpleName()
        public String parentSimpleName;              
        public int routeDepth;                       // uei.getNodeRoute().size() - 1
        public String callPathSerialized;            
        public boolean hasCallPath;                  
        public String javadoc;                       

        
        public List<ApiExceptionScore> apiExceptionScores;

        
        public List<ThrowStatementInfo> throwStatements;

        public Candidate() {}
    }

    public static class ApiExceptionScore {
        public String name;                          
        public double score;                         
        public String condition;                     

        public ApiExceptionScore() {}
        public ApiExceptionScore(String name, double score) { this.name = name; this.score = score; }
        public ApiExceptionScore(String name, double score, String condition) {
            this.name = name; this.score = score; this.condition = condition;
        }
    }

    public static class ThrowStatementInfo {
        public String text;                          
        public String exceptionType;                 // throwInfo.getExceptionQualifiedName()
        public double score;
        public String description;                   

        public ThrowStatementInfo() {}
    }
}

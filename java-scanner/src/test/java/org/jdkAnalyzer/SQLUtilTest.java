package org.jdkAnalyzer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SQLUtilTest {
    @Test
    void getMethodNameIfExist() {
        String trueName;
        trueName = SQLUtil.getMethodNameIfExist("build.tools.projectcreator.BuildConfig.init(Vector<String>,Vector<String>)");
        assertEquals("build.tools.projectcreator.BuildConfig.init(Vector<String>,Vector<String>)", trueName);
        trueName = SQLUtil.getMethodNameIfExist("java.util.stream.Stream.collect(Collector)");
        assertEquals("java.util.stream.Stream.collect(Collector<? super T,A,R>)", trueName);
        trueName = SQLUtil.getMethodNameIfExist("java.util.Collections.indexedBinarySearch(List,T)");
        assertEquals("java.util.Collections.indexedBinarySearch(List<? extends Comparable<? super T>>,T)", trueName);

    }

    @Test
    void generateMethodGenericsParameterNames() {
        List<String> names = SQLUtil.generateMethodGenericsParameterNames("java.something.abc(p1,p2,p3)");
        System.out.println(names);
    }

    @Test
    void findIndexes() {
    }


}
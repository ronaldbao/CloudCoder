// CloudCoder - a web-based pedagogical programming environment
// Copyright (C) 2011, Jaime Spacco <jspacco@knox.edu>
// Copyright (C) 2011, David H. Hovemeyer <dhovemey@ycp.edu>
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package org.cloudcoder.submitsvc.oop.builder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.cloudcoder.app.shared.model.Problem;
import org.cloudcoder.app.shared.model.TestCase;
import org.cloudcoder.app.shared.model.TestOutcome;
import org.cloudcoder.app.shared.model.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaTester implements ITester
{
    private static final Logger logger=LoggerFactory.getLogger(JavaTester.class);
    public static final long TIMEOUT_LIMIT=2000;
    
    static {
        System.setSecurityManager(new ThreadGroupSecurityManager(KillableTaskManager.WORKER_THREAD_GROUP));
    }
    
    public List<TestResult> testSubmission(Problem problem, 
            List<TestCase> testCaseList, 
            final String programText)
    {
        List<TestResult> testResultList = new ArrayList<TestResult>();

        // The Test class is the subject of the test
        String testCode = createTestClassSource(programText);
        
        // The Tester class contains the unit tests
        // FIXME: this could be cached
        String testerCode = createTesterClassSource(problem, testCaseList);
        
        logger.trace("Test code:");
        logger.trace(testCode);
        logger.trace("Tester code:");
        logger.trace(testerCode);
        
        // Compile
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<JavaFileObject> sources = new ArrayList<JavaFileObject>();
        sources.add(MemoryFileManager.makeSource("Test", testCode));
        sources.add(MemoryFileManager.makeSource("Tester", testerCode));
        
        DiagnosticCollector<JavaFileObject> collector=
                new DiagnosticCollector<JavaFileObject>();
        
        MemoryFileManager fm = new MemoryFileManager(compiler.getStandardFileManager(null, null, null));
        CompilationTask task = compiler.getTask(null, fm, collector, null, null, sources);
        if (!task.call()) {
            StringBuilder compilerErrors=new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
                compilerErrors.append(d.toString());
                compilerErrors.append("\n");
            }
            compilerErrors.replace(compilerErrors.length()-1, compilerErrors.length(), "");
            testResultList.add(new TestResult(TestOutcome.COMPILE_FAILED, 
                    "Compile error",
                    compilerErrors.toString(),
                    null));
            return testResultList;
        }
        ClassLoader cl = fm.getClassLoader(StandardLocation.CLASS_OUTPUT);
        
        try {
            final Class<?> testerCls = cl.loadClass("Tester");

            // create a list of tasks to be executed
            List<IsolatedTask<TestResult>> tasks=new ArrayList<IsolatedTask<TestResult>>();

            for (final TestCase t : testCaseList) {
                tasks.add(new IsolatedTask<TestResult>() {
                    @Override
                    public TestResult execute() {
                        try {
                            Method m = testerCls.getMethod(t.getTestCaseName());
                            Boolean result = (Boolean) m.invoke(null);
                            if (result) {
                                return new TestResult(TestOutcome.PASSED, "Passed! input=" + t.getInput() + ", output=" + t.getOutput());
                            } else {
                                return new TestResult(TestOutcome.FAILED_ASSERTION, "Failed for input=" + t.getInput() + ", expected=" + t.getOutput());
                            }
                        } catch (InvocationTargetException e) {
                            if (e.getCause() instanceof SecurityException) {
                                logger.warn("Security exception with code: "+programText);
                                return new TestResult(TestOutcome.FAILED_BY_SECURITY_MANAGER, "Security exception while testing submission");
                            } 
                            logger.warn("InvocationTargetException", e);
                            return new TestResult(TestOutcome.FAILED_WITH_EXCEPTION, "Failed with "+e.getTargetException().getMessage());
                        } catch (NoSuchMethodException e) {
                            return new TestResult(TestOutcome.INTERNAL_ERROR, "Method not found while testing submission");
                        } catch (IllegalAccessException e) {
                            return new TestResult(TestOutcome.INTERNAL_ERROR, "Illegal access while testing submission");
                        }
                        //TODO: Catch Throwable and report INTERNAL_ERROR for anything else
                    }
                });
            }

            KillableTaskManager<TestResult> pool=new KillableTaskManager<TestResult>(
                    tasks, 
                    TIMEOUT_LIMIT,
                    new KillableTaskManager.TimeoutHandler<TestResult>() {
                        @Override
                        public TestResult handleTimeout() {
                            return new TestResult(TestOutcome.FAILED_FROM_TIMEOUT, 
                                    "Took too long!  Check for infinite loops, or recursion without a proper base case");
                        }
                    });

            // run each task in a separate thread
            pool.run();

            //merge outcomes with their buffered inputs for stdout/stderr
            List<TestResult> outcomes=pool.getOutcomes();
            Map<Integer,String> stdout=pool.getBufferedStdout();
            Map<Integer,String> stderr=pool.getBufferedStderr();
            for (int i=0; i<outcomes.size(); i++) {
                TestResult t=outcomes.get(i);
                t.setStdout(stdout.get(i));
                t.setStderr(stderr.get(i));
            }
            return outcomes;
        } catch (ClassNotFoundException e) {
            testResultList.add(new TestResult(TestOutcome.INTERNAL_ERROR,"Class not found exception: "+e.getMessage()));
            return testResultList;
        }
    }

    /**
     * @param problem
     * @param testCaseList
     * @return
     */
    private String createTesterClassSource(Problem problem,
            List<TestCase> testCaseList) {
        StringBuilder tester = new StringBuilder();
        tester.append("public class Tester {\n");
        tester.append("\tpublic static boolean eq(Object o1, Object o2) { return o1.equals(o2); }\n");
        for (TestCase tc : testCaseList) {
            tester.append("\tpublic static boolean ");
            tester.append(tc.getTestCaseName());
            tester.append("() {\n");
            tester.append("\t\tTest t = new Test();\n");
            tester.append("\t\treturn eq(t." + problem.getTestName() + "(" + tc.getInput() + "), " + tc.getOutput() + ");\n");
            tester.append("\t\t}\n");
        }
        tester.append("}");
        String testerCode = tester.toString();
        return testerCode;
    }

    /**
     * @param programText
     * @return
     */
    private String createTestClassSource(String programText) {
        StringBuilder test = new StringBuilder();
        test.append("public class Test {\n");
        test.append(programText + "\n");
        test.append("}\n");
        String testCode = test.toString();
        return testCode;
    }
    
}

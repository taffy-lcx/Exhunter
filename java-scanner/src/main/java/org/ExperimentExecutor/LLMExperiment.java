package org.ExperimentExecutor;

import org.LLMAdvisers.Advisers;
import org.LLMAdvisers.LLMResponse;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.*;

public class LLMExperiment {

    private static final Logger LOGGER = Logger.getLogger("MAIN");
    private static final String EXPERIMENT_MARK;
    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            prop.load(fis);
            EXPERIMENT_MARK = prop.getProperty("EXPERIMENT_MARK");
        } catch (IOException e) {
            LOGGER.severe("config loading failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    public static void main(String[] args) throws IOException {
        loggerInit();
        LOGGER.info("experiment process start.");
        
        List<ExampleHandler.ExampleData> experimentData = ExampleHandler.getRawFinalResultData();
        for (ExampleHandler.ExampleData data : experimentData) {
            if (! ExampleHandler.dataInLLMResult(data)) {
                ExampleHandler.addRawLLMResultData(handleDataByLLM(data));
                ExampleHandler.writeData2File(ExampleHandler.getRawLLMResultData(), ExampleHandler.LLM_BASELINE_OUTPUT_PATH);
            }
        }
        LOGGER.info("finished");
    }

    public static void mainContent() throws IOException {
        loggerInit();
        
        List<ExampleHandler.ExampleData> experimentData = ExampleHandler.getRawFinalResultData();

        
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        
        
        int processingThreads = Math.min(Runtime.getRuntime().availableProcessors(), 4);
        var executor = Executors.newFixedThreadPool(processingThreads);

        
        ReadWriteLock rwLock = new ReentrantReadWriteLock();

        
        AtomicInteger processedCount = new AtomicInteger(0);
        int totalTasks = 0;

        
        for (ExampleHandler.ExampleData data : experimentData) {
            if (!ExampleHandler.dataInLLMResult(data)) {
                totalTasks++;
            }
        }

        final int finalTotalTasks = totalTasks;

        
        AtomicInteger taskIndex = new AtomicInteger(0);

        
        final long[] lastTaskSubmitTime = {System.currentTimeMillis()};

        
        Runnable taskProcessor = new Runnable() {
            @Override
            public void run() {
                int currentIndex = taskIndex.getAndIncrement();
                if (currentIndex < experimentData.size()) {
                    ExampleHandler.ExampleData data = experimentData.get(currentIndex);

                    
                    boolean needsProcessing = false;

                    
                    rwLock.readLock().lock();
                    try {
                        needsProcessing = !ExampleHandler.dataInLLMResult(data);
                    } finally {
                        rwLock.readLock().unlock();
                    }

                    long currentTime = System.currentTimeMillis();
                    long delay;

                    if (needsProcessing) {
                        
                        executor.submit(() -> {
                            try {
                                
                                ExampleHandler.ExampleData result = handleDataByLLM(data);

                                
                                rwLock.writeLock().lock();
                                try {
                                    ExampleHandler.addRawLLMResultData(result);
                                    ExampleHandler.writeData2File(
                                            ExampleHandler.getRawLLMResultData(),
                                            ExampleHandler.LLM_BASELINE_OUTPUT_PATH
                                    );
                                } finally {
                                    rwLock.writeLock().unlock();
                                }

                                
                                int completed = processedCount.incrementAndGet();
                                System.out.printf("Progress: %d/%d (%.2f%%)\n",
                                        completed, finalTotalTasks,
                                        (completed * 100.0) / finalTotalTasks);

                                
                                if (completed >= finalTotalTasks) {
                                    shutdown(scheduler, executor);
                                }
                            } catch (Exception e) {
                                System.err.println("Error processing record: " + e.getMessage());
                                e.printStackTrace();
                            }
                        });

                        
                        lastTaskSubmitTime[0] = currentTime;

                        
                        delay = 3000;
                        System.out.println("Task submitted; checking the next record in 3 seconds");
                    } else {
                        
                        delay = 10; 
                        System.out.println("Record #" + currentIndex + " already exists; checking the next record");
                    }

                    
                    scheduler.schedule(this, delay, TimeUnit.MILLISECONDS);
                } else {
                    
                    LOGGER.info("All records checked; shutting down scheduler");
                    shutdown(scheduler, executor);
                }
            }
        };

        
        scheduler.schedule(taskProcessor, 0, TimeUnit.MILLISECONDS);
    }

     


    private static void shutdown(ScheduledExecutorService scheduler,
                                 java.util.concurrent.ExecutorService executor) {
        
        scheduler.shutdown();

        
        executor.shutdown();
        try {
            
            if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("All records processed");
    }

    private static void loggerInit() throws IOException{
        
        LOGGER.setLevel(Level.ALL);
        
        Handler fileHandler = new FileHandler("LLMExperiment_Main" + EXPERIMENT_MARK + ".log", true);
        fileHandler.setLevel(Level.ALL); 
        
        fileHandler.setFormatter(new SimpleFormatter());
        
        LOGGER.addHandler(fileHandler);
        LOGGER.setLevel(Level.ALL); 
    }

    private static ExampleHandler.ExampleData handleDataByLLM(ExampleHandler.ExampleData data) {
        ExampleHandler.ExampleData result = new ExampleHandler.ExampleData(data);
        Map<String, String> mainQuestionResult;
        try {
            mainQuestionResult = Advisers.handleLLMBaseline(data.getMethodBefore());
        } catch (RuntimeException ignore) {
            mainQuestionResult = null;
        }
        if (mainQuestionResult == null) {
            data.setLabel(-1);
        } else {
            result.setMethodResult(LLMResponse.stripCode(mainQuestionResult.get("result")));
        }
        return Main.analyzeExperimentDiff(result);
    }
}

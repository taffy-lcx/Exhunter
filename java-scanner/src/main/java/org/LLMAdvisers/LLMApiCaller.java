package org.LLMAdvisers;

import com.google.gson.Gson;
import okhttp3.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

 



public class LLMApiCaller {
    private static final Logger LOGGER = Logger.getLogger("MAIN");
    private static String API_URL;
    private static String API_KEY;
    private static String MODEL_NAME;
    static {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream(System.getenv("CONFIG_FILE") != null ? System.getenv("CONFIG_FILE") : "config.properties")) {
            prop.load(fis);
            API_URL = prop.getProperty("LLM_API_URL");
            API_KEY = prop.getProperty("LLM_API_KEY");
            MODEL_NAME = prop.getProperty("LLM_MODEL_NAME");
        } catch (IOException e) {
            LOGGER.severe("config loading failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    
    
    private static final int DEFAULT_TRY_TIMES = 5;
    private static final long RETRY_DELAY_MS = 1000;
    private static final int DEFAULT_TIMEOUT = 300;
    private LLMRequest LLMRequest;
    private LLMResponse LLMResponse;
    private Response response;
    private String lastAssistantMessage;
    
    private String stageTag = "unknown";
    private String methodTag = "";
    
    private String ovrUrl = null;
    private String ovrKey = null;
    
    private static final String USAGE_LOG_PATH = "../data/llm_usage.jsonl";
    private static final Object USAGE_LOCK = new Object();

    public LLMApiCaller tag(String stage, String method) {
        this.stageTag = stage == null ? "unknown" : stage;
        this.methodTag = method == null ? "" : method;
        return this;
    }

     
    public LLMApiCaller endpoint(String model, String url, String key) {
        if (model != null && !model.isEmpty()) this.LLMRequest.setModel(model);
        if (url != null && !url.isEmpty())   this.ovrUrl = url;
        if (key != null && !key.isEmpty())   this.ovrKey = key;
        return this;
    }

    public LLMApiCaller() {
        LLMRequest = new LLMRequest();
        LLMRequest.setModel(MODEL_NAME);
    }

    public LLMApiCaller addMessage(String content, String role) {
        this.LLMRequest.addMessage(content, role);
        return this;
    }

     



    public LLMApiCaller responseFormat(String type) {
        this.LLMRequest.setResponseFormat(type);
        return this;
    }

    public LLMRequest getDeepSeekRequest() {
        return LLMRequest;
    }

    public LLMApiCaller call() throws IOException{
        return call(DEFAULT_TRY_TIMES);
    }
    public LLMApiCaller call(int maxRetries) throws IOException{
        LOGGER.fine("start call " + LLMRequest.hashCode());
        OkHttpClient client = new OkHttpClient().newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
                .build();
        MediaType mediaType = MediaType.parse("application/json");
        RequestBody body = RequestBody.create(
                LLMRequest.toJsonString(),
                mediaType);
        String useUrl = ovrUrl != null ? ovrUrl : API_URL;
        String useKey = ovrKey != null ? ovrKey : API_KEY;
        Request request = new Request.Builder()
                .url(useUrl)
                .method("POST", body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "Bearer " + useKey)
                .build();
        int retryCount = 0;
        IOException lastException = null;

        while (retryCount < maxRetries) {
            try {
                
                long t0 = System.currentTimeMillis();
                Response response = client.newCall(request).execute();

                
                if (response.isSuccessful()) {
                    this.response =  response;
                    String responseBody = response.body().string();
                    long durationMs = System.currentTimeMillis() - t0;
                    
                    LLMResponse = new Gson().fromJson(responseBody, LLMResponse.class);
                    
                    lastAssistantMessage = LLMResponse.getChoices().get(0).getMessage().getContent();
                    LLMRequest.addMessage(lastAssistantMessage, "assistant");
                    LOGGER.fine("call succeed: " + LLMRequest.hashCode());
                    LOGGER.finer(LLMRequest.hashCode() + " LLM chat as below:\n" + LLMRequest.messageToString());
                    
                    appendUsageLog(durationMs);
                    return this;
                } else {
                    
                    response.close();
                    throw new IOException("HTTP request failed with status: " + response.code());
                }
            } catch (IOException e) {
                lastException = e;
                retryCount++;

                
                if (shouldRetry(e) && retryCount < maxRetries) {
                    LOGGER.fine("call failed: " + e.getMessage() + " retry (" + retryCount + "/" + maxRetries + ")...");
                    try {
                        
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Retry delay interrupted", ie);
                    }
                } else {
                    
                    LOGGER.warning("call abort: " + LLMRequest.hashCode());
                    throw lastException;
                }
            }
        }
        
        LOGGER.warning("call abort: " + LLMRequest.hashCode());
        throw lastException;
    }
     


    private static boolean shouldRetry(IOException e) {
        
        return e instanceof ConnectException ||
                e instanceof SocketTimeoutException ||
                e instanceof UnknownHostException ||
                e instanceof SSLException ||
                e instanceof InterruptedIOException ||
                (e.getMessage() != null && e.getMessage().startsWith("HTTP request failed with status: "));
    }

    public LLMResponse getDeepSeekResponse() {
        return LLMResponse;
    }

    public Response getResponse() {
        return response;
    }

    public String getLastAssistantMessage() {
        return lastAssistantMessage;
    }

    public String getConversation() {
        return LLMRequest.messageToString();
    }

     



    private void appendUsageLog(long durationMs) {
        if (LLMResponse == null || LLMResponse.getUsage() == null) return;
        LLMResponse.Usage u = LLMResponse.getUsage();
        
        String line = "{\"ts\":\"" + java.time.Instant.now().toString()
                + "\",\"stage\":\"" + stageTag + "\""
                + ",\"method\":\"" + methodTag.replace("\"", "\\\"") + "\""
                + ",\"model\":\"" + MODEL_NAME + "\""
                + ",\"duration_ms\":" + durationMs
                + ",\"prompt_tokens\":" + u.getPromptTokens()
                + ",\"completion_tokens\":" + u.getCompletionTokens()
                + ",\"total_tokens\":" + u.getTotalTokens()
                + "}\n";
        synchronized (USAGE_LOCK) {
            try (java.io.FileWriter fw = new java.io.FileWriter(USAGE_LOG_PATH, true)) {
                fw.write(line);
            } catch (IOException e) {
                LOGGER.warning("usage log write failed: " + e.getMessage());
            }
        }
    }
}

package org.LLMAdvisers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.ArrayList;
import java.util.List;

public class LLMRequest {
    private List<Message> messages = new ArrayList<>();
    private String model = "deepseek-chat";
    private double frequency_penalty = 0;
    private int max_tokens = 8192;
    private double presence_penalty = 0;
    private ResponseFormat response_format;
    private Object stop = null;
    private boolean stream = false;
    private Object stream_options = null;
    private double temperature = 0;
    private double top_p = 1;
    private Object tools = null;
    
    
    
    private String tool_choice = null;
    private boolean logprobs = false;
    private Integer top_logprobs = null;

    
    public static class Message {
        private String content;
        private String role;

        public Message(String content, String role) {
            this.content = content;
            this.role = role;
        }

        // Getters
        public String getContent() { return content; }
        public String getRole() { return role; }

        public String toLine() {
            return "{\"role\": \"" + role + "\", \"content\": \"" + content + "\"}";
        }
    }

    
    public static class ResponseFormat {
        private String type;

        public ResponseFormat(String type) {
            this.type = type;
        }

        // Getters
        public String getType() { return type; }
    }

    
    public LLMRequest addMessage(String content, String role) {
        this.messages.add(new Message(content, role));
        return this;
    }

    
    public LLMRequest setModel(String model) {
        this.model = model;
        return this;
    }

    
    public LLMRequest setFrequencyPenalty(double frequency_penalty) {
        this.frequency_penalty = frequency_penalty;
        return this;
    }

    
    public LLMRequest setMaxTokens(int max_tokens) {
        this.max_tokens = max_tokens;
        return this;
    }

    
    public LLMRequest setPresencePenalty(double presence_penalty) {
        this.presence_penalty = presence_penalty;
        return this;
    }

    
    public LLMRequest setResponseFormat(String type) {
        this.response_format = new ResponseFormat(type);
        return this;
    }

    
    public LLMRequest setStop(Object stop) {
        this.stop = stop;
        return this;
    }

    
    public LLMRequest setStream(boolean stream) {
        this.stream = stream;
        return this;
    }

    
    public LLMRequest setStreamOptions(Object stream_options) {
        this.stream_options = stream_options;
        return this;
    }

    
    public LLMRequest setTemperature(double temperature) {
        this.temperature = temperature;
        return this;
    }

    
    public LLMRequest setTopP(double top_p) {
        this.top_p = top_p;
        return this;
    }

    
    public LLMRequest setTools(Object tools) {
        this.tools = tools;
        return this;
    }

    
    public LLMRequest setToolChoice(String tool_choice) {
        this.tool_choice = tool_choice;
        return this;
    }

    
    public LLMRequest setLogprobs(boolean logprobs) {
        this.logprobs = logprobs;
        return this;
    }

    
    public LLMRequest setTopLogprobs(Integer top_logprobs) {
        this.top_logprobs = top_logprobs;
        return this;
    }

    
    public String toJsonString() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(this);
    }

    public String messageToString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (Message message: messages) {
            sb.append(" ".repeat(4));
            sb.append(message.toLine());
            sb.append(",\n");
        }
        sb.append("]");
        return sb.toString().replaceAll(",\\n]", "\n]");
    }

    
//    public static void main(String[] args) {
//        LLMRequest request = new LLMRequest()
//                .addMessage("You are a helpful assistant", "system")
//                .addMessage("Hi", "user")
//                .setModel("deepseek-chat")
//                .setFrequencyPenalty(0)
//                .setMaxTokens(2048)
//                .setPresencePenalty(0)
//                .setResponseFormat("text")
//                .setStop(null)
//                .setStream(false)
//                .setStreamOptions(null)
//                .setTemperature(1)
//                .setTopP(1)
//                .setTools(null)
//                .setToolChoice("none")
//                .setLogprobs(false)
//                .setTopLogprobs(null);
//
//        String requestString = new LLMRequest()
//                .addMessage("You are a helpful assistant", "system")
//                .addMessage("Hi", "user")
//                .toJsonString();
//
//        System.out.println(requestString);
//    }
}

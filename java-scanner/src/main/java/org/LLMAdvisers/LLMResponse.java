package org.LLMAdvisers;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LLMResponse {
    private String id;
    private String object;
    private long created;
    private String model;
    private List<Choice> choices;
    private Usage usage;
    private String system_fingerprint;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getObject() { return object; }
    public void setObject(String object) { this.object = object; }

    public long getCreated() { return created; }
    public void setCreated(long created) { this.created = created; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public List<Choice> getChoices() { return choices; }
    public void setChoices(List<Choice> choices) { this.choices = choices; }

    public Usage getUsage() { return usage; }
    public void setUsage(Usage usage) { this.usage = usage; }

    public String getSystemFingerprint() { return system_fingerprint; }
    public void setSystemFingerprint(String system_fingerprint) { this.system_fingerprint = system_fingerprint; }

    // Inner classes
    public static class Choice {
        private int index;
        private Message message;
        private Object logprobs;
        private String finish_reason;

        // Getters and Setters
        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }

        public Message getMessage() { return message; }
        public void setMessage(Message message) { this.message = message; }

        public Object getLogprobs() { return logprobs; }
        public void setLogprobs(Object logprobs) { this.logprobs = logprobs; }

        public String getFinishReason() { return finish_reason; }
        public void setFinishReason(String finish_reason) { this.finish_reason = finish_reason; }
    }

    public static class Message {
        private String role;
        private String content;

        // Getters and Setters
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    public static class Usage {
        private int prompt_tokens;
        private int completion_tokens;
        private int total_tokens;
        private PromptTokensDetails prompt_tokens_details;
        private int prompt_cache_hit_tokens;
        private int prompt_cache_miss_tokens;

        // Getters and Setters
        public int getPromptTokens() { return prompt_tokens; }
        public void setPromptTokens(int prompt_tokens) { this.prompt_tokens = prompt_tokens; }

        public int getCompletionTokens() { return completion_tokens; }
        public void setCompletionTokens(int completion_tokens) { this.completion_tokens = completion_tokens; }

        public int getTotalTokens() { return total_tokens; }
        public void setTotalTokens(int total_tokens) { this.total_tokens = total_tokens; }

        public PromptTokensDetails getPromptTokensDetails() { return prompt_tokens_details; }
        public void setPromptTokensDetails(PromptTokensDetails prompt_tokens_details) { this.prompt_tokens_details = prompt_tokens_details; }

        public int getPromptCacheHitTokens() { return prompt_cache_hit_tokens; }
        public void setPromptCacheHitTokens(int prompt_cache_hit_tokens) { this.prompt_cache_hit_tokens = prompt_cache_hit_tokens; }

        public int getPromptCacheMissTokens() { return prompt_cache_miss_tokens; }
        public void setPromptCacheMissTokens(int prompt_cache_miss_tokens) { this.prompt_cache_miss_tokens = prompt_cache_miss_tokens; }
    }

    public static class PromptTokensDetails {
        private int cached_tokens;

        // Getters and Setters
        public int getCachedTokens() { return cached_tokens; }
        public void setCachedTokens(int cached_tokens) { this.cached_tokens = cached_tokens; }
    }

    public Map<String, Object> getJsonMessage() throws JsonSyntaxException {
        String respond = getChoices().get(0).getMessage().getContent();
        return parseJsonMessage(getJsonString(respond));
    }
    public static Map<String, Object> parseJsonMessage(String string) {
        return new Gson().fromJson(string, new TypeToken<Map<String, Object>>() {}.getType());
    }
    public static String getJsonString(String string) {
        if (string.startsWith("```json\n") && string.endsWith("\n```")) {
            return string.substring("```json\n".length(), string.length() - "\n```".length());
        } else if (string.startsWith("{") && string.endsWith("}")) {
            return string;
        } else {
            return string;
        }
    }

    public static Map<String, String> parseTaggedMessage(String string) {
        
        
        if (isJsonResponse(string)) {
            Map<String, Object> json = parseJsonObjectLenient(string);
            if (json != null && (json.containsKey("result") || json.containsKey("need") || json.containsKey("scores"))) {
                Map<String, String> out = new HashMap<>();
                out.put("analysis", asStringField(json.get("analysis")));
                out.put("answer",   asStringField(json.get("answer")));
                out.put("need",     asStringField(json.get("need")));
                
                out.put("result",   stripCode(asStringField(json.get("result"))));
                out.put("remark",   asStringField(json.get("remark")));
                return out;
            }
        }
        Map<String, String> result = new HashMap<>();
        result.put("analysis", extractTag(string, "analysis", false));
        result.put("answer", extractTag(string, "answer", false));
        
        result.put("need", extractTag(string, "need", false));
        result.put("result", extractTag(string, "result", true));
        result.put("remark", extractTag(string, "remark", false));
        return result;
    }

     



    public static String extractField(String raw, String name) {
        if (isJsonResponse(raw)) {
            Map<String, Object> json = parseJsonObjectLenient(raw);
            if (json != null) {
                Object v = json.get(name);
                if (v != null) return asStringField(v);
            }
        }
        return extractTag(raw, name, false);
    }

     
    public static boolean isJsonResponse(String raw) {
        if (raw == null) return false;
        String s = stripJsonFence(raw).trim();
        return s.length() >= 2 && s.charAt(0) == '{' && s.charAt(s.length() - 1) == '}';
    }

     
    public static String stripJsonFence(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("```json")) s = s.substring(7).trim();
        else if (s.startsWith("```")) s = s.substring(3).trim();
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3).trim();
        return s;
    }

     
    public static Map<String, Object> parseJsonObjectLenient(String raw) {
        if (raw == null) return null;
        try {
            return new Gson().fromJson(stripJsonFence(raw),
                    new TypeToken<Map<String, Object>>() {}.getType());
        } catch (RuntimeException e) {
            return null;
        }
    }

     
    private static String asStringField(Object v) {
        if (v == null) return "";
        if (v instanceof String) return (String) v;
        return v.toString();
    }

    public static String extractTag(String string, String tag, boolean required) {
        if (string == null) {
            if (required) throw new IllegalArgumentException("LLM response is null");
            return "";
        }
        Pattern pattern = Pattern.compile(
                "(?is)<\\s*" + Pattern.quote(tag) + "\\s*>\\s*(.*?)\\s*<\\s*/\\s*" + Pattern.quote(tag) + "\\s*>"
        );
        Matcher matcher = pattern.matcher(string);
        
        
        String last = null;
        int count = 0;
        while (matcher.find()) {
            last = matcher.group(1).trim();
            count++;
        }
        if (last != null) {
            if (count > 1) {
                LOGGER_FINE("multiple <" + tag + "> tags (n=" + count + "), using last");
            }
            return stripCode(last);
        }
        if (required) {
            throw new IllegalArgumentException("missing <" + tag + "> in LLM response");
        }
        return "";
    }

    private static final java.util.logging.Logger LLM_RESPONSE_LOGGER = java.util.logging.Logger.getLogger("MAIN");
    private static void LOGGER_FINE(String msg) { LLM_RESPONSE_LOGGER.fine(msg); }

    public static List<String> parseResultStringList(String string) {
        String result = extractTag(string, "result", true).trim();
        Type listType = new TypeToken<List<String>>() {}.getType();
        try {
            List<String> parsed = new Gson().fromJson(result, listType);
            return parsed == null ? new ArrayList<>() : parsed;
        } catch (JsonSyntaxException ignored) {
            return parseQuotedStringList(result);
        }
    }

     









    public static Map<Integer, Double> parseScores(String string) {
        Map<Integer, Double> out = new HashMap<>();
        
        
        String result;
        if (isJsonResponse(string)) {
            Map<String, Object> top = parseJsonObjectLenient(string);
            if (top != null && top.get("scores") instanceof List) {
                for (Object o : (List<?>) top.get("scores")) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) o;
                    Object idx = m.get("index");
                    Object sc = m.get("score");
                    if (sc == null) sc = m.get("likelihood");
                    if (idx == null || sc == null) continue;
                    try {
                        out.put((int) Math.round(Double.parseDouble(idx.toString())),
                                Double.parseDouble(sc.toString()));
                    } catch (NumberFormatException ignore) { /* skip */ }
                }
                if (!out.isEmpty()) return out;
            }
            
            result = stripJsonFence(string).trim();
        } else {
            result = extractTag(string, "result", true).trim();
        }
        
        try {
            Map<String, Object> root = new Gson().fromJson(result, new TypeToken<Map<String, Object>>(){}.getType());
            if (root != null && root.get("scores") instanceof List) {
                for (Object o : (List<?>) root.get("scores")) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) o;
                    Object idx = m.get("index");
                    Object sc = m.get("score");
                    if (sc == null) sc = m.get("likelihood"); 
                    if (idx == null || sc == null) continue;
                    try {
                        out.put((int) Math.round(Double.parseDouble(idx.toString())),
                                Double.parseDouble(sc.toString()));
                    } catch (NumberFormatException ignore) { /* skip */ }
                }
                if (!out.isEmpty()) return out;
            }
        } catch (JsonSyntaxException | NumberFormatException ignored) {
            // fall through to regex
        }
        
        Matcher m = Pattern.compile(
                "(?is)\"index\"\\s*:\\s*(\\d+)[^}]*?\"(?:score|likelihood)\"\\s*:\\s*([0-9]*\\.?[0-9]+)"
        ).matcher(result);
        while (m.find()) {
            try {
                out.put(Integer.parseInt(m.group(1)), Double.parseDouble(m.group(2)));
            } catch (NumberFormatException ignore) { /* skip */ }
        }
        
        if (out.isEmpty()) {
            Matcher m2 = Pattern.compile(
                    "(?is)\"(?:score|likelihood)\"\\s*:\\s*([0-9]*\\.?[0-9]+)[^}]*?\"index\"\\s*:\\s*(\\d+)"
            ).matcher(result);
            while (m2.find()) {
                try {
                    out.put(Integer.parseInt(m2.group(2)), Double.parseDouble(m2.group(1)));
                } catch (NumberFormatException ignore) { /* skip */ }
            }
        }
        return out;
    }

    private static List<String> parseQuotedStringList(String string) {
        List<String> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"|'([^']+)'").matcher(string);
        while (matcher.find()) {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
        }
        if (result.isEmpty() && string.replaceAll("[\\[\\]\\s,]", "").isEmpty()) {
            return result;
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("cannot parse <result> as string list: " + string);
        }
        return result;
    }

    public static String parseResultChoice(String string, String... allowedValues) {
        String result = extractTag(string, "result", true).trim();
        String normalized = result.toLowerCase();
        // Strip surrounding quotes / trailing punctuation that LLM may add.
        if (normalized.length() >= 2
                && ((normalized.startsWith("\"") && normalized.endsWith("\""))
                    || (normalized.startsWith("'") && normalized.endsWith("'")))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        normalized = normalized.replaceAll("[.\"'!?]+$", "").trim();

        // Pass 1: exact match — what the prompt explicitly asks for.
        for (String allowedValue : allowedValues) {
            if (normalized.equals(allowedValue)) {
                return allowedValue;
            }
        }
        // Pass 2: token-boundary startsWith — accepts "no catch — because ..." style answers.
        for (String allowedValue : allowedValues) {
            if (normalized.startsWith(allowedValue)) {
                int next = allowedValue.length();
                if (normalized.length() == next || !Character.isLetterOrDigit(normalized.charAt(next))) {
                    return allowedValue;
                }
            }
        }
        // Pass 3: whole-word substring count. Order-independent so the first option in the
        // arglist no longer wins by accident; ambiguous matches collapse to "unknown" when allowed.
        String matched = null;
        int matches = 0;
        for (String allowedValue : allowedValues) {
            Pattern pattern = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(allowedValue) + "(?![a-z0-9])");
            if (pattern.matcher(normalized).find()) {
                matches++;
                matched = allowedValue;
            }
        }
        if (matches == 1) return matched;
        if (matches > 1) {
            for (String allowedValue : allowedValues) {
                if (allowedValue.equals("unknown")) return "unknown";
            }
        }
        throw new IllegalArgumentException("unexpected <result>: " + result);
    }

    public static List<String> splitTaggedMessages(String string) {
        List<String> result = new ArrayList<>();
        if (string == null || string.isBlank()) {
            return result;
        }
        Matcher matcher = Pattern.compile("(?is)<\\s*analysis\\s*>.*?<\\s*/\\s*remark\\s*>").matcher(string);
        while (matcher.find()) {
            result.add(matcher.group().trim());
        }
        if (result.isEmpty()) {
            result.add(string.trim());
        }
        return result;
    }

    public static String stripCode(String string) {
        if (string == null) {
            return "";
        }
        
        
        Pattern open = Pattern.compile("(?s)\\A```[ \\t]*([A-Za-z0-9_+\\-]*)[ \\t]*\\r?\\n");
        Pattern close = Pattern.compile("(?s)\\r?\\n[ \\t]*```[ \\t]*\\z");
        Matcher openMatch = open.matcher(string);
        Matcher closeMatch = close.matcher(string);
        if (openMatch.find() && closeMatch.find() && openMatch.end() <= closeMatch.start()) {
            return string.substring(openMatch.end(), closeMatch.start());
        }
        return string;
    }
}

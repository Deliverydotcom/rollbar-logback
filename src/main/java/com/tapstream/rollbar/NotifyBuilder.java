package com.tapstream.rollbar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class NotifyBuilder {

    private static final String NOTIFIER_VERSION = "1.0";

    private static final String PERSON_EMAIL_KEY = "person.email";
    private static final String PERSON_USERNAME_KEY = "person.username";
    private static final String PERSON_ID_KEY = "person.id";
    private static final String UUID_KEY = "uuid";

    private final String accessToken;
    private final String environment;
    private final String rollbarContext;

    private final JSONObject notifierData;
    private final JSONObject serverData;

    public NotifyBuilder(String accessToken, String environment, String rollbarContext) throws JSONException {
        this.accessToken = accessToken;
        this.environment = environment;
        this.rollbarContext = rollbarContext;
        this.notifierData = getNotifierData();
        this.serverData = getServerData();
    }
    

    private String getValue(String key, Map<String, String> context, String defaultValue) {
        if (context == null) return defaultValue;
        Object value = context.get(key);
        if (value == null) return defaultValue;
        return value.toString();
    }

    public JSONObject build(String level, String message, Throwable throwable, Map<String, String> context) throws JSONException {

        JSONObject payload = new JSONObject();

        // access token
        payload.put("access_token", this.accessToken);

        // data
        JSONObject data = new JSONObject();

        // general values
        data.put("environment", this.environment);
        data.put("level", level);
        data.put("platform", getValue("platform", context, "java"));
        data.put("framework", getValue("framework", context, "java"));
        data.put("language", "java");
        if (rollbarContext != null && !rollbarContext.isEmpty())
            data.put("context", rollbarContext);
        data.put("timestamp", System.currentTimeMillis() / 1000);
        data.put("body", getBody(message, throwable));
        data.put("request", buildRequest(context));

        int length = 99;
        if(message.length() < length) {
            length = message.length();
        }
        data.put("title", message.subSequence(0, length));

        // Add person if available
        JSONObject person = buildPerson(context);
        if (person != null) {
            data.put("person", person);
        }

        // UUID if available
        if (context.containsKey(UUID_KEY)) {
            data.put("uuid", context.get(UUID_KEY));
        }

        String fingerprint = generateFingerPrint(message);
        if(fingerprint != null)
        {
            data.put("fingerprint", fingerprint);
        }


        // Custom data and log message if there's a throwable
        JSONObject customData = buildCustom(context);
        if (throwable != null && message != null) {
            customData.put("log", message);
        }
        
        data.put("custom", customData);
        data.put("client", buildClient(context));
        if (serverData != null) {
            data.put("server", serverData);
        }
        data.put("notifier", notifierData);
        payload.put("data", data);

        return payload;
    }

    private JSONObject buildClient(Map<String, String> ctx){
        JSONObject client = new JSONObject();
        JSONObject javaScript = new JSONObject();
        javaScript.put("browser", ctx.get(RollbarFilter.REQUEST_USER_AGENT));
        client.put("javascript", javaScript);
        return client;
    }
    
    private JSONObject buildCustom(Map<String, String> ctx){
        JSONObject custom = new JSONObject();
        for (Entry<String, String> ctxEntry : ctx.entrySet()){
            String key = ctxEntry.getKey();
            if (key.startsWith(RollbarFilter.REQUEST_PREFIX))
                continue;
            custom.put(key, ctxEntry.getValue());
        }
        return custom;
    }

    private JSONObject buildPerson(Map<String, String> ctx) {
        JSONObject request = new JSONObject();
        boolean populated = false;

        if (ctx.containsKey(PERSON_ID_KEY)) {
            request.put("id", ctx.get(PERSON_ID_KEY));
            populated = true;
        }
        if (ctx.containsKey(PERSON_USERNAME_KEY)) {
            request.put("username", ctx.get(PERSON_USERNAME_KEY));
            populated = true;
        }
        if (ctx.containsKey(PERSON_EMAIL_KEY)) {
            request.put("email", ctx.get(PERSON_EMAIL_KEY));
            populated = true;
        }

        return populated ? request : null;
    }

    private String stripPrefix(String value, String prefix){
        return value.substring(prefix.length(), value.length());
    }
    
    private JSONObject buildRequest(Map<String, String> ctx){
        JSONObject request = new JSONObject();
        request.put("url", ctx.get(RollbarFilter.REQUEST_URL));
        request.put("query_string", ctx.get(RollbarFilter.REQUEST_QS));
        
        JSONObject headers = new JSONObject();
        JSONObject params = new JSONObject();
        
        for (Entry<String, String> ctxEntry : ctx.entrySet()){
            String key = ctxEntry.getKey();
            if (key.startsWith(RollbarFilter.REQUEST_HEADER_PREFIX)){
                headers.put(stripPrefix(key, RollbarFilter.REQUEST_HEADER_PREFIX), ctxEntry.getValue());
            } else if (key.startsWith(RollbarFilter.REQUEST_PARAM_PREFIX)){
                params.put(stripPrefix(key, RollbarFilter.REQUEST_PARAM_PREFIX), ctxEntry.getValue());
            }
        }
        
        request.put("headers", headers);
        
        String method = ctx.get(RollbarFilter.REQUEST_METHOD);
        if (method != null){
            request.put("method", method);
            switch (method){
            case "GET":
                request.put("GET", params);
                break;
            case "POST":
                request.put("POST", params);
                break;
            }
        }
        
        
        request.put("user_ip", ctx.get(RollbarFilter.REQUEST_REMOTE_ADDR));
        return request;
    }

    private JSONObject getBody(String message, Throwable original) throws JSONException {
        JSONObject body = new JSONObject();

        Throwable throwable = original;

        if (throwable != null) {
            List<JSONObject> traces = new ArrayList<JSONObject>();
            do {
                traces.add(0, createTrace(throwable));
                throwable = throwable.getCause();
            } while (throwable != null);

            body.put("trace_chain", new JSONArray(traces));
        }

        if (original == null && message != null) {
            JSONObject messageBody = new JSONObject();
            messageBody.put("body", message);
            body.put("message", messageBody);
        }

        return body;
    }

    private JSONObject getNotifierData() throws JSONException {
        JSONObject notifier = new JSONObject();
        notifier.put("name", "rollbar-java");
        notifier.put("version", NOTIFIER_VERSION);
        return notifier;
    }

    private JSONObject getServerData() throws JSONException {
        try {
            InetAddress localhost = InetAddress.getLocalHost();

            String host = localhost.getHostName();
            String ip = localhost.getHostAddress();

            JSONObject notifier = new JSONObject();
            notifier.put("host", host);
            notifier.put("ip", ip);
            return notifier;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private JSONObject createTrace(Throwable throwable) throws JSONException {
        JSONObject trace = new JSONObject();

        JSONArray frames = new JSONArray();

        StackTraceElement[] elements = throwable.getStackTrace();
        for (int i = elements.length - 1; i >= 0; --i) {
            StackTraceElement element = elements[i];

            JSONObject frame = new JSONObject();

            frame.put("class_name", element.getClassName());
            frame.put("filename", element.getFileName());
            frame.put("method", element.getMethodName());

            if (element.getLineNumber() > 0) {
                frame.put("lineno", element.getLineNumber());
            }

            frames.put(frame);
        }

        JSONObject exceptionData = new JSONObject();
        exceptionData.put("class", throwable.getClass().getName());
        exceptionData.put("message", throwable.getMessage());

        trace.put("frames", frames);
        trace.put("exception", exceptionData);

        return trace;
    }

    /**
     * part of the code is courtesy of mkyong.com
     * @param message
     * @return
     */
    private String generateFingerPrint(String message) {
        try
        {
            MessageDigest md = MessageDigest.getInstance("MD5");
            int length = 99;
            if(message.length() < length) {
                length = message.length();
            }

            byte[] byteData = md.digest(message.substring(0, length).getBytes());

            //convert the byte to hex format method 1
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < byteData.length; i++) {
                sb.append(Integer.toString((byteData[i] & 0xff) + 0x100, 16).substring(1));
            }

            //convert the byte to hex format method 2
            StringBuffer hexString = new StringBuffer();
            for (int i=0;i<byteData.length;i++) {
                String hex=Integer.toHexString(0xff & byteData[i]);
                if(hex.length()==1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e)
        {

        }

         return null;
    }

}

package com.fongmi.rhino.utils;

import com.fongmi.rhino.bean.Req;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;
import com.google.common.net.HttpHeaders;

import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.Scriptable;
import org.htmlunit.corejs.javascript.ScriptableObject;
import org.htmlunit.corejs.javascript.VarScope;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Connect {

    public static Call to(String url, Req req) {
        OkHttpClient client = OkHttp.client(req.isRedirect(), req.getTimeout());
        return client.newCall(getRequest(url, req, Headers.of(req.getHeader())));
    }

    public static Scriptable success(Context cx, VarScope scope, Req req, Response res) {
        try (res) {
            Scriptable jsObject = cx.newObject(scope);
            Scriptable jsHeader = cx.newObject(scope);
            setHeader(cx, scope, res, jsHeader);
            ScriptableObject.putProperty(jsObject, "code", res.code());
            ScriptableObject.putProperty(jsObject, "headers", jsHeader);
            if (req.getBuffer() == 0) ScriptableObject.putProperty(jsObject, "content", new String(res.body().bytes(), req.getCharset()));
            if (req.getBuffer() == 1) ScriptableObject.putProperty(jsObject, "content", JSUtil.toArray(cx, scope, res.body().bytes()));
            if (req.getBuffer() == 2) ScriptableObject.putProperty(jsObject, "content", Util.base64(res.body().bytes()));
            if (req.getBuffer() == 3) ScriptableObject.putProperty(jsObject, "content", res.body().bytes());
            return jsObject;
        } catch (Exception e) {
            return error(cx, scope);
        }
    }

    public static Scriptable error(Context cx, VarScope scope) {
        Scriptable jsObject = cx.newObject(scope);
        Scriptable jsHeader = cx.newObject(scope);
        ScriptableObject.putProperty(jsObject, "headers", jsHeader);
        ScriptableObject.putProperty(jsObject, "content", "");
        ScriptableObject.putProperty(jsObject, "code", "");
        return jsObject;
    }

    private static Request getRequest(String url, Req req, Headers headers) {
        if (req.getMethod().equalsIgnoreCase("post")) {
            return new Request.Builder().url(url).headers(headers).post(getPostBody(req, headers.get(HttpHeaders.CONTENT_TYPE))).build();
        } else if (req.getMethod().equalsIgnoreCase("header")) {
            return new Request.Builder().url(url).headers(headers).head().build();
        } else {
            return new Request.Builder().url(url).headers(headers).get().build();
        }
    }

    private static RequestBody getPostBody(Req req, String contentType) {
        if (req.getData() != null && "json".equals(req.getPostType())) return getJsonBody(req);
        if (req.getData() != null && "form".equals(req.getPostType())) return getFormBody(req);
        if (req.getData() != null && "form-data".equals(req.getPostType())) return getFormDataBody(req);
        if (req.getBody() != null && contentType != null) return RequestBody.create(req.getBody(), MediaType.get(contentType));
        return RequestBody.create(new byte[0]);
    }

    private static RequestBody getJsonBody(Req req) {
        return RequestBody.create(req.getData().toString(), MediaType.get("application/json; charset=utf-8"));
    }

    private static RequestBody getFormBody(Req req) {
        FormBody.Builder builder = new FormBody.Builder();
        Map<String, String> params = Json.toMap(req.getData());
        for (String key : params.keySet()) builder.add(key, params.get(key));
        return builder.build();
    }

    private static RequestBody getFormDataBody(Req req) {
        String boundary = "--dio-boundary-" + new SecureRandom().nextInt(42949) + new SecureRandom().nextInt(67296);
        MultipartBody.Builder builder = new MultipartBody.Builder(boundary).setType(MultipartBody.FORM);
        Map<String, String> params = Json.toMap(req.getData());
        for (String key : params.keySet()) builder.addFormDataPart(key, params.get(key));
        return builder.build();
    }

    private static void setHeader(Context cx, VarScope scope, Response res, Scriptable object) {
        for (Map.Entry<String, List<String>> entry : res.headers().toMultimap().entrySet()) {
            if (entry.getValue().size() == 1) ScriptableObject.putProperty(object, entry.getKey(), entry.getValue().get(0));
            if (entry.getValue().size() >= 2) ScriptableObject.putProperty(object, entry.getKey(), JSUtil.toArray(cx, scope, entry.getValue()));
        }
    }
}
package com.lyw.common.core.utils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/** 网关和服务端对内部对账路径使用相同的保守识别规则。 */
public final class InternalReconcilePath {
    public static final String PATH = "/internal/question-index/reconcile";
    public static final String TOKEN_HEADER = "X-Question-Reconcile-Token";

    private InternalReconcilePath() { }

    public static boolean matches(String rawPath) {
        if (rawPath == null) return false;
        // Tomcat 会先移除原始矩阵参数；代理或 WebFlux 也可能先解码，两个结果都要拦截。
        return normalize(decode(rawPath)).endsWith(PATH)
                || normalize(decode(stripParameters(rawPath))).endsWith(PATH);
    }

    private static String decode(String path) {
        // HTTP 路径不是表单，+ 必须保留；兼顾代理再次解码的情况。
        for (int i = 0; i < 4 && path.indexOf('%') >= 0; i++) {
            try {
                String decoded = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
                if (decoded.equals(path)) break;
                path = decoded;
            } catch (IllegalArgumentException invalidEncoding) { break; }
        }
        return path;
    }

    private static String stripParameters(String path) {
        return path.replaceAll(";[^/]*", "");
    }

    private static String normalize(String path) {
        var segments = new ArrayDeque<String>();
        for (String segment : path.replace('\\', '/').split("/+")) {
            int parameters = segment.indexOf(';');
            if (parameters >= 0) segment = segment.substring(0, parameters);
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) { if (!segments.isEmpty()) segments.removeLast(); }
            else segments.addLast(segment);
        }
        // 同时覆盖 /system、/oj-system 等服务前缀；无额外子路径映射。
        return "/" + String.join("/", segments);
    }
}

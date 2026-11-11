package com.lyw.system.questionsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.api.reconcile.QuestionReconcileProperties;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.core.utils.InternalReconcilePath;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

public final class QuestionReconcileAuthFilter extends OncePerRequestFilter {
    private final QuestionReconcileProperties properties;
    private final ObjectMapper mapper;
    public QuestionReconcileAuthFilter(QuestionReconcileProperties properties, ObjectMapper mapper) {
        this.properties = properties; this.mapper = mapper;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !InternalReconcilePath.matches(request.getRequestURI())
                && !InternalReconcilePath.matches(request.getServletPath());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!properties.isEnabled()) { reject(response, 503, "题目对账入口未启用"); return; }
        var headers = Collections.list(request.getHeaders(InternalReconcilePath.TOKEN_HEADER));
        String token = headers.size() == 1 ? headers.get(0) : null;
        if (token == null || token.length() > 256 || properties.getInternalToken() == null
                || !MessageDigest.isEqual(properties.getInternalToken().getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8))) {
            reject(response, 403, "内部请求未授权"); return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), Result.fail(ResultCode.FAILED.getCode(), message));
    }
}

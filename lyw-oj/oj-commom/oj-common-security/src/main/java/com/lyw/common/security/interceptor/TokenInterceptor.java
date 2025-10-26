package com.lyw.common.security.interceptor;


import cn.hutool.core.util.StrUtil;
import com.lyw.common.core.constants.HttpConstants;
import com.lyw.common.security.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TokenInterceptor implements HandlerInterceptor {

    @Autowired
    private TokenService tokenService;

    @Value("${jwt.secret}")
    private String secret;  //从哪个服务的配置文件中读取，取决于这个bean对象交给了哪个服务的spring容器进行管理。

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = getToken(request);  //请求头中获取token
        if (StrUtil.isEmpty(token)) {
            return true;
        }
        tokenService.extendToken(token, secret);
        return true;
    }

    private String getToken(HttpServletRequest request) {
        String token = request.getHeader(HttpConstants.AUTHENTICATION);
        if (StrUtil.isNotEmpty(token) && token.startsWith(HttpConstants.PREFIX)) {
            token = token.replaceFirst(HttpConstants.PREFIX, "");
        }
        return token;
    }
}

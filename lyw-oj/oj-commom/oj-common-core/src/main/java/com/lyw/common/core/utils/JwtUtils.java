package com.lyw.common.core.utils;

import com.lyw.common.core.constants.JwtConstants;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import java.util.Map;

public class JwtUtils {

    /**
     * 生成令牌
     *
     * @param claims 数据
     * @param secret 密钥
     * @return 令牌
     */
    public static String createToken(Map<String, Object> claims, String secret) {
        String token = Jwts.builder().setClaims(claims).signWith(SignatureAlgorithm.HS512, secret).compact();
        return token;
    }

    /**
     * 从令牌中获取数据
     *
     * @param token  令牌
     * @param secret 密钥
     * @return 数据
     */
    public static Claims parseToken(String token, String secret) {
        return Jwts.parser().setSigningKey(secret).parseClaimsJws(token).getBody();
    }


    public static String getUserKey(Claims claims) {
        return toStr(claims.get(JwtConstants.LOGIN_USER_KEY));
    }

    public static String getUserId(Claims claims) {
        return toStr(claims.get(JwtConstants.LOGIN_USER_ID));
    }

    private static String toStr(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString();
    }

    public static void main(String[] args) {
//        Map<String, Object> claims = new HashMap<>();
//        claims.put("userId", 123456789L);
//        //secret  保密 随机 不能硬编码  定期更换
//        System.out.println(createToken(claims, "zxcvbnmasdfghjuiyreqtuiwq"));
        String token = "eyJhbGciOiJIUzUxMiJ9.eyJ1c2VySWQiOjEyMzQ1Njc4OX0.rgVT9HXxyzwEPq4c_2gznBlrJ0NIC1wkJZzYml6dddZxVFP0ELAMQRT-o8LcrEIFESHKEuMIfUjhBBvq12ucqw";

        Claims claim = parseToken(token, "zxcvbnmasdfghjuiyreqtuiwq");
        System.out.println(claim);


        //1.用户登录成功之后，调用createToken 生成令牌  并发送给客户端    2. 后续的所有请求，再调用具体的接口之前，都要先通过token进行身份认证  3、用户使用系统的过程中我们需要进行适时的延长jwt的过期时间.'

    }
}

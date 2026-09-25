package me.luucka.finance.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot copies {@code server.servlet.session.cookie.*} onto the Spring Session cookie only
 * when it starts an embedded server. Apply the same properties unconditionally, so MockMvc tests
 * get the production cookie (name, Secure, HttpOnly, SameSite) instead of the default "SESSION".
 */
@Configuration(proxyBeanMethods = false)
class SessionCookieConfig {

    @Bean
    DefaultCookieSerializerCustomizer sessionCookie(
            @Value("${server.servlet.session.cookie.name}") String name,
            @Value("${server.servlet.session.cookie.path:/}") String path,
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure,
            @Value("${server.servlet.session.cookie.http-only:true}") boolean httpOnly,
            @Value("${server.servlet.session.cookie.same-site:strict}") String sameSite) {
        return serializer -> {
            serializer.setCookieName(name);
            serializer.setCookiePath(path);
            serializer.setUseSecureCookie(secure);
            serializer.setUseHttpOnlyCookie(httpOnly);
            serializer.setSameSite(Character.toUpperCase(sameSite.charAt(0)) + sameSite.substring(1).toLowerCase());
        };
    }
}

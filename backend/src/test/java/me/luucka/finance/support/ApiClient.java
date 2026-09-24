package me.luucka.finance.support;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Minimal browser-like client for MockMvc: keeps the session cookie across requests
 * (following id rotation) and sends the CSRF token like the SPA does.
 */
public class ApiClient {

    public static final String SESSION_COOKIE = "FT_SESSION";

    private final MockMvc mvc;
    private Cookie session;
    private String csrfToken;

    public ApiClient(MockMvc mvc) {
        this.mvc = mvc;
    }

    public MvcResult get(String url) throws Exception {
        return perform(MockMvcRequestBuilders.get(url), false);
    }

    public MvcResult post(String url, String json) throws Exception {
        return perform(MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON).content(json == null ? "{}" : json), true);
    }

    public MvcResult put(String url, String json) throws Exception {
        return perform(MockMvcRequestBuilders.put(url).contentType(MediaType.APPLICATION_JSON).content(json), true);
    }

    public MvcResult patch(String url, String json) throws Exception {
        return perform(MockMvcRequestBuilders.patch(url).contentType(MediaType.APPLICATION_JSON).content(json), true);
    }

    public MvcResult delete(String url) throws Exception {
        return perform(MockMvcRequestBuilders.delete(url), true);
    }

    /** Sends a mutating request without the CSRF header. */
    public MvcResult postWithoutCsrf(String url, String json) throws Exception {
        return perform(MockMvcRequestBuilders.post(url).contentType(MediaType.APPLICATION_JSON).content(json), false);
    }

    /** Fetches a fresh CSRF token for the current session. */
    public void refreshCsrf() throws Exception {
        MvcResult result = perform(MockMvcRequestBuilders.get("/api/auth/csrf"), false);
        csrfToken = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    /** Password step; returns the HTTP status. */
    public int login(String username, String password) throws Exception {
        refreshCsrf();
        MvcResult result = post("/api/auth/login",
                "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password));
        refreshCsrf();
        return result.getResponse().getStatus();
    }

    /** Second-factor step; returns the HTTP status. */
    public int loginMfa(String code) throws Exception {
        MvcResult result = post("/api/auth/login/mfa", "{\"code\":\"%s\"}".formatted(code));
        refreshCsrf();
        return result.getResponse().getStatus();
    }

    /** Current session cookie value, or null when there is no session. */
    public String sessionId() {
        return session == null ? null : session.getValue();
    }

    public static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    public static <T> T json(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder, boolean withCsrf) throws Exception {
        if (session != null) {
            builder.cookie(session);
        }
        if (withCsrf && csrfToken != null) {
            builder.header("X-CSRF-TOKEN", csrfToken);
        }
        MvcResult result = mvc.perform(builder).andReturn();
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        if (cookie != null) {
            session = cookie.getMaxAge() == 0 || cookie.getValue().isEmpty() ? null : cookie;
        }
        return result;
    }
}

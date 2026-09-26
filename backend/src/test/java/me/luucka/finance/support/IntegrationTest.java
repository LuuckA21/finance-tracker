package me.luucka.finance.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Full application against a real PostgreSQL (Testcontainers), driven through MockMvc
 * with the complete security filter chain and Spring Session.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestUsers.class})
@TestPropertySource(properties = {
        "app.encryption-key=dGVzdC1rZXktdGVzdC1rZXktdGVzdC1rZXktdGVzdCE=",
        "app.bootstrap-admin.username=admin",
        "app.bootstrap-admin.password=" + IntegrationTest.ADMIN_PASSWORD,
        "app.login.ip-max-attempts=1000",
        // No downloads from the real ECB; EcbRatesIT serves the feeds itself on this address
        "app.fx.ecb.enabled=false",
        "app.fx.ecb.base-url=http://127.0.0.1:" + IntegrationTest.ECB_PORT + "/ecb/"
})
public @interface IntegrationTest {

    String ADMIN_PASSWORD = "Initial-Bootstrap-Secret-42";
    int ECB_PORT = 18089;
}

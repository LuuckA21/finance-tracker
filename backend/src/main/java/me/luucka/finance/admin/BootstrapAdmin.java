package me.luucka.finance.admin;

import me.luucka.finance.config.AppProperties;
import me.luucka.finance.user.AppUserRepository;
import me.luucka.finance.user.Language;
import me.luucka.finance.user.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator when the database has no users.
 * If no password is configured, a random one is generated and logged once; it must be
 * changed at first login.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final AppUserRepository users;
    private final UserAdminService userAdminService;
    private final AppProperties properties;

    public BootstrapAdmin(AppUserRepository users, UserAdminService userAdminService, AppProperties properties) {
        this.users = users;
        this.userAdminService = userAdminService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        AppProperties.BootstrapAdmin config = properties.bootstrapAdmin();
        String password = config.password() == null || config.password().isBlank() ? null : config.password();
        Language language = config.language() == null ? Language.IT : config.language();
        var created = userAdminService.create(config.username(), Role.ADMIN, password, language);
        if (created.temporaryPassword() != null) {
            log.warn("""

                    ============================================================
                     Created administrator '{}' with temporary password:
                         {}
                     You will be asked to change it at first login.
                    ============================================================""",
                    created.user().username(), created.temporaryPassword());
        } else {
            log.warn("Created administrator '{}' with the configured password (change required at first login)",
                    created.user().username());
        }
    }
}

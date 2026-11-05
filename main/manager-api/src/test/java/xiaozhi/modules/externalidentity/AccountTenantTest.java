package xiaozhi.modules.externalidentity;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import xiaozhi.modules.externalidentity.ExternalModels.*;
class AccountTenantTest {
    @Test void acceptsOnlyOwnAccountWithEmptyResourceScope() {
        var account = new Tenant("account", "42");
        var empty = new Scope(List.of(), List.of());
        assertDoesNotThrow(() -> ExternalIdentityService.validateAccount("42", account, empty));
        assertThrows(RuntimeException.class, () -> ExternalIdentityService.validateAccount("43", account, empty));
        assertThrows(RuntimeException.class, () -> ExternalIdentityService.validateAccount("42", account, new Scope(List.of("17"), List.of())));
        assertThrows(RuntimeException.class, () -> ExternalIdentityService.validateAccount("42", account, new Scope(List.of(), List.of("17"))));
        assertDoesNotThrow(() -> ExternalIdentityService.validateAccount("43", new Tenant("customer", "42"), new Scope(List.of("17"), List.of("18"))));
    }
}

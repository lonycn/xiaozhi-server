package xiaozhi.modules.esp32conversation;

import org.springframework.stereotype.Component;
import xiaozhi.modules.esp32gateway.Esp32GatewayService;
import xiaozhi.modules.esp32gateway.Esp32GatewayService.DeviceContext;

@Component
public class Esp32ContextGuard implements Esp32ConversationService.ContextGuard {
    private final Esp32GatewayService gateway;
    public Esp32ContextGuard(Esp32GatewayService gateway){this.gateway=gateway;}
    @Override public void requireCurrent(Esp32ConversationService.TrustedContext c) {
        gateway.assertCurrent(new DeviceContext(c.deviceId(),c.ownerId(),c.agentId(),c.bindingVersion(),c.connectionId(),c.generation(),c.gatewayId(),0));
    }
    @Override public void requireBinding(Esp32ConversationService.TrustedContext c) {
        gateway.assertBinding(new DeviceContext(c.deviceId(),c.ownerId(),c.agentId(),c.bindingVersion(),c.connectionId(),c.generation(),c.gatewayId(),0));
    }
    public static Esp32ConversationService.TrustedContext trusted(DeviceContext c){
        return new Esp32ConversationService.TrustedContext(c.deviceId(),c.bindingVersion(),c.ownerId(),c.agentId(),c.connectionId(),c.generation(),c.gatewayId());
    }
}

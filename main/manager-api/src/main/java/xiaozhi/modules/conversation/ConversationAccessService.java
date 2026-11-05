package xiaozhi.modules.conversation;

import org.springframework.stereotype.Service;

import xiaozhi.modules.conversation.ConversationModels.Conversation;

/** Resource authorization shared by conversation consumers; menu permissions are not grants. */
@Service
public class ConversationAccessService {
    private final ConversationDao dao;

    public ConversationAccessService(ConversationDao dao) {
        this.dao = dao;
    }

    public record ReadAccess(Conversation conversation, boolean owner, boolean current,
            long messageCutoff, long eventCutoff) { }

    /** Call inside a transaction with lock=true to freeze release/new-write boundaries while reading. */
    public ReadAccess read(long actor, String id, boolean lock) {
        try {
            Conversation owned = dao.owned(id, actor, lock);
            return new ReadAccess(owned, true, false, Long.MAX_VALUE, Long.MAX_VALUE);
        } catch (ConversationProblem problem) {
            if (problem.status() != 404) throw problem;
        }
        internalStaff();
        Conversation conversation = dao.byId(id, lock);
        var grant = dao.staffGrant(id, actor);
        if (grant == null) throw missing();
        return new ReadAccess(conversation, false, grant.current(),
                grant.current() ? Long.MAX_VALUE : grant.messageCutoff(),
                grant.current() ? Long.MAX_VALUE : grant.eventCutoff());
    }

    public Conversation detail(long actor, String id, boolean lock) {
        var access = read(actor, id, lock);
        // Historical authorization covers immutable ledger slices, never present-day metadata.
        if (!access.owner() && !access.current()) throw missing();
        return access.conversation();
    }

    public String lockSenderRole(long actor) {
        internalStaff();
        String role = dao.lockStaffRole(actor);
        if (role == null) throw missing();
        return role;
    }

    public String currentSenderRole(long actor,String id) {
        String role=dao.currentServiceType(id,actor);
        if(role==null)throw missing();
        return role;
    }

    public Conversation requireCurrentStaff(long actor, String id) {
        internalStaff();
        Conversation conversation = dao.byId(id, true);
        if (!dao.currentAssignment(id, actor)) throw missing();
        return conversation;
    }

    private static void internalStaff() {
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
                && servlet.getRequest().getAttribute(xiaozhi.modules.externalidentity.ExternalIdentityService.ATTRIBUTE)
                instanceof xiaozhi.modules.externalidentity.ExternalModels.Principal)throw missing();
    }

    private static ConversationProblem missing() {
        return new ConversationProblem(404, "CONVERSATION_NOT_FOUND");
    }
}

package com.ramiart.admin.adminuser.application;

import static com.ramiart.admin.adminuser.application.AdminUserModels.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Clock;
import java.util.Map;
import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.application.AuditRecorder.Event;
import com.ramiart.admin.auth.application.AuthSessionRepository.RequestMetadata;
import com.ramiart.admin.inquiry.application.InquiryDataProtector;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.auth.application.PasswordVerifier;
import com.ramiart.admin.auth.domain.EmailAddress;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserService {
    private static final Set<String> ROLES = Set.of("OWNER", "OPERATOR", "CONTENT", "FINANCE");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE", "LOCKED");
    private final AdminUserRepository repository;
    private final JdbcClient jdbc;
    private final InquiryDataProtector protector;
    private final AuditRecorder audit;
    private final Clock clock;
    private final PasswordVerifier passwords;
    private final ObjectMapper mapper;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%".toCharArray();

    public AdminUserService(AdminUserRepository repository, JdbcClient jdbc, InquiryDataProtector protector,
            AuditRecorder audit, Clock clock, PasswordVerifier passwords, ObjectMapper mapper) {
        this.repository = repository; this.jdbc = jdbc; this.protector = protector; this.audit = audit; this.clock = clock;
        this.passwords = passwords; this.mapper = mapper;
    }

    @Transactional
    public CreatedResponse create(CreateRequest input, UUID key, Authentication auth, RequestMetadata metadata) {
        UUID actor = require(auth, "ADMIN_ACCOUNT_WRITE");
        if (input == null || key == null || input.displayName() == null || input.email() == null)
            throw new AdminUserException("ADMIN_USER_REQUEST_INVALID");
        String displayName = input.displayName().trim();
        String email;
        try { email = EmailAddress.of(input.email()).value(); }
        catch (IllegalArgumentException exception) { throw new AdminUserException("ADMIN_USER_REQUEST_INVALID"); }
        String roleCode = input.roleCode() == null ? "" : input.roleCode().trim().toUpperCase(java.util.Locale.ROOT);
        if (displayName.isEmpty() || displayName.length() > 100 || !ROLES.contains(roleCode))
            throw new AdminUserException(ROLES.contains(roleCode) ? "ADMIN_USER_REQUEST_INVALID" : "ADMIN_ROLE_INVALID");
        String scope = "ADMIN_USER_CREATE:" + actor;
        String hash = protector.hash(displayName + "|" + email + "|" + roleCode);
        int inserted = jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at) values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours') on conflict do nothing")
                .param("scope",scope).param("key",key).param("hash",hash).update();
        if (inserted == 0) return replayCreation(scope, key, hash);
        UUID userId = UUID.randomUUID();
        String temporaryPassword = generateTemporaryPassword();
        Instant now = clock.instant();
        Instant expires = now.plusSeconds(24 * 60 * 60);
        try {
            jdbc.sql("insert into admin_user(id,email,password_hash,display_name,status,password_must_change,temporary_password_expires_at,password_changed_at,created_by) values(:id,:email,:password,:name,'ACTIVE',true,:expires,:now,:actor)")
                    .param("id",userId).param("email",email).param("password",passwords.encode(temporaryPassword))
                    .param("name",displayName).param("expires",expires.atOffset(ZoneOffset.UTC))
                    .param("now",now.atOffset(ZoneOffset.UTC)).param("actor",actor).update();
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new AdminUserException("ADMIN_EMAIL_DUPLICATED");
        }
        UUID roleId = jdbc.sql("select id from admin_role where code=:role and active").param("role",roleCode)
                .query(UUID.class).optional().orElseThrow(() -> new AdminUserException("ADMIN_ROLE_INVALID"));
        jdbc.sql("insert into admin_user_role(admin_user_id,admin_role_id,assigned_by) values(:user,:role,:actor)")
                .param("user",userId).param("role",roleId).param("actor",actor).update();
        audit.record(new Event(now,metadata.requestId(),"MGT-ADMIN-ACCOUNT","PRIVILEGE","ADMIN",actor,null,
                "ADMIN_USER_CREATED","ADMIN_USER",userId,"SUCCESS",null,metadata.ipAddress(),metadata.userAgent(),
                Map.of("roleCode",roleCode)));
        Summary user = decorate(repository.findById(userId).orElseThrow(() -> new AdminUserException("ADMIN_USER_NOT_FOUND")), actor);
        CreatedResponse response = new CreatedResponse(user,temporaryPassword,OffsetDateTime.ofInstant(expires,ZoneOffset.ofHours(9)));
        completeSensitive(scope,key,userId,201,response);
        return response;
    }

    private CreatedResponse replayCreation(String scope, UUID key, String hash) {
        var prior = jdbc.sql("select request_hash,state,encrypted_response,sensitive_response_revealed_at from idempotency_record where scope=:scope and idempotency_key=:key for update")
                .param("scope",scope).param("key",key).query((rs,row)->new Object[]{rs.getString(1),rs.getString(2),rs.getBytes(3),rs.getObject(4)}).single();
        if (!hash.equals(prior[0])) throw new AdminUserException("IDEMPOTENCY_KEY_REUSED");
        if (!"COMPLETED".equals(prior[1]) || prior[2] == null) throw new AdminUserException("IDEMPOTENCY_IN_PROGRESS");
        if (prior[3] != null) throw new AdminUserException("SENSITIVE_IDEMPOTENCY_RESPONSE_CONSUMED");
        int consumed = jdbc.sql("update idempotency_record set sensitive_response_revealed_at=statement_timestamp() where scope=:scope and idempotency_key=:key and sensitive_response_revealed_at is null")
                .param("scope",scope).param("key",key).update();
        if (consumed != 1) throw new AdminUserException("SENSITIVE_IDEMPOTENCY_RESPONSE_CONSUMED");
        try { return mapper.readValue(protector.reveal((byte[])prior[2]), CreatedResponse.class); }
        catch (Exception exception) { throw new AdminUserException("ADMIN_USER_CREATE_FAILED"); }
    }

    private void completeSensitive(String scope, UUID key, UUID id, int status, Object response) {
        try {
            byte[] encrypted = protector.protect(mapper.writeValueAsString(response));
            jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:id,response_status=:status,encrypted_response=:response where scope=:scope and idempotency_key=:key")
                    .param("id",id).param("status",status).param("response",encrypted).param("scope",scope).param("key",key).update();
        } catch (Exception exception) { throw new AdminUserException("ADMIN_USER_CREATE_FAILED"); }
    }

    private static String generateTemporaryPassword() {
        char[] password = new char[20];
        for (int i=0;i<password.length;i++) password[i]=PASSWORD_CHARS[RANDOM.nextInt(PASSWORD_CHARS.length)];
        return new String(password);
    }

    @Transactional(readOnly = true)
    public ListResponse list(Query input, Authentication auth) {
        UUID current = require(auth, "ADMIN_ACCOUNT_READ");
        String keyword = input.keyword() == null ? null : input.keyword().trim();
        String role = blank(input.role());
        String status = blank(input.status());
        String sort = input.sort() == null ? "displayName,asc" : input.sort();
        if (keyword != null && (keyword.length() < 2 || keyword.length() > 100)
                || role != null && !ROLES.contains(role) || status != null && !STATUSES.contains(status)
                || input.page() < 0 || input.size() != 20
                || !Set.of("displayName,asc", "displayName,desc", "lastLoginAt,asc", "lastLoginAt,desc", "updatedAt,asc", "updatedAt,desc").contains(sort))
            throw new AdminUserException("ADMIN_USER_QUERY_INVALID");
        Query query = new Query(keyword, role, status, input.page(), input.size(), sort);
        long total = repository.count(query);
        int offset = Math.multiplyExact(input.page(), input.size());
        List<Summary> items = repository.find(query, input.size(), offset).stream().map(item -> decorate(item, current)).toList();
        int pages = (int) Math.ceil(total / (double) input.size());
        return new ListResponse(items, input.page(), input.size(), total, pages, sort);
    }

    @Transactional
    public RoleChangeResponse changeRole(UUID targetId, ChangeRoleRequest body, UUID key,
            Authentication auth, RequestMetadata metadata) {
        UUID actor = require(auth, "ADMIN_ACCOUNT_WRITE");
        if (targetId == null || body == null || body.version() < 0 || key == null)
            throw new AdminUserException("ADMIN_USER_REQUEST_INVALID");
        String roleCode = body.roleCode() == null ? "" : body.roleCode().trim().toUpperCase(java.util.Locale.ROOT);
        if (!ROLES.contains(roleCode)) throw new AdminUserException("ADMIN_ROLE_INVALID");
        String scope = "ADMIN_USER_ROLE:" + actor;
        String hash = protector.hash(targetId + "|" + roleCode + "|" + body.version());
        int claimed = jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at) values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours') on conflict do nothing")
                .param("scope", scope).param("key", key).param("hash", hash).update();
        if (claimed == 0) {
            var prior = jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key")
                    .param("scope", scope).param("key", key).query((rs, row) -> new Object[]{rs.getString(1),rs.getString(2),rs.getObject(3,UUID.class)}).single();
            if (!hash.equals(prior[0])) throw new AdminUserException("IDEMPOTENCY_KEY_REUSED");
            if (!"COMPLETED".equals(prior[1])) throw new AdminUserException("IDEMPOTENCY_IN_PROGRESS");
            return snapshot(scope,key,RoleChangeResponse.class);
        }
        jdbc.sql("select id from admin_role where code='OWNER' for update").query(UUID.class).single();
        var target = jdbc.sql("select u.status,r.code from admin_user u join admin_user_role ur on ur.admin_user_id=u.id join admin_role r on r.id=ur.admin_role_id where u.id=:id for update of u")
                .param("id", targetId).query((rs,row)->new String[]{rs.getString(1),rs.getString(2)}).optional()
                .orElseThrow(() -> new AdminUserException("ADMIN_USER_NOT_FOUND"));
        if (target[1].equals(roleCode)) throw new AdminUserException("ADMIN_ROLE_UNCHANGED");
        if ("ACTIVE".equals(target[0]) && "OWNER".equals(target[1]) && !"OWNER".equals(roleCode)
                && ownerCount() <= 1) throw new AdminUserException("LAST_OWNER_REQUIRED");
        int changed = jdbc.sql("update admin_user set version=version+1 where id=:id and version=:version")
                .param("id", targetId).param("version", body.version()).update();
        if (changed != 1) throw new AdminUserException("ADMIN_USER_VERSION_CONFLICT");
        UUID roleId = jdbc.sql("select id from admin_role where code=:code and active").param("code", roleCode)
                .query(UUID.class).optional().orElseThrow(() -> new AdminUserException("ADMIN_ROLE_INVALID"));
        jdbc.sql("update admin_user_role set admin_role_id=:role,assigned_by=:actor,assigned_at=statement_timestamp() where admin_user_id=:id")
                .param("role", roleId).param("actor", actor).param("id", targetId).update();
        audit.record(new Event(clock.instant(), metadata.requestId(), "MGT-ADMIN-ACCOUNT", "PRIVILEGE", "ADMIN", actor,
                null, "ADMIN_ROLE_CHANGED", "ADMIN_USER", targetId, "SUCCESS", null, metadata.ipAddress(),
                metadata.userAgent(), Map.of("beforeRole", target[1], "afterRole", roleCode)));
        Summary changedUser = repository.findById(targetId).orElseThrow(() -> new AdminUserException("ADMIN_USER_NOT_FOUND"));
        boolean self = actor.equals(targetId);
        RoleChangeResponse response = new RoleChangeResponse(decorate(changedUser, actor), self
                ? new CurrentSessionImpact(true, true, false, "/admin") : null);
        completeSnapshot(scope,key,targetId,200,response);
        return response;
    }

    @Transactional
    public StatusResponse changeStatus(UUID targetId, StatusRequest body, UUID key, Authentication auth, RequestMetadata metadata) {
        UUID actor = require(auth, "ADMIN_ACCOUNT_WRITE");
        if (targetId == null || body == null || key == null || body.version() < 0 || body.reason() == null)
            throw new AdminUserException("ADMIN_USER_REQUEST_INVALID");
        String to = body.toStatus() == null ? "" : body.toStatus().trim().toUpperCase(java.util.Locale.ROOT);
        String reason = body.reason().trim();
        if (!Set.of("ACTIVE", "INACTIVE").contains(to) || reason.isEmpty() || reason.length() > 200)
            throw new AdminUserException("ADMIN_USER_REQUEST_INVALID");
        String scope = "ADMIN_USER_STATUS:" + actor;
        String hash = protector.hash(targetId + "|" + to + "|" + reason + "|" + body.version());
        int claimed = claim(scope,key,hash);
        if (claimed == 0) {
            idempotency(scope,key,hash);
            return snapshot(scope,key,StatusResponse.class);
        }
        jdbc.sql("select id from admin_role where code='OWNER' for update").query(UUID.class).single();
        var target = jdbc.sql("select u.status,r.code from admin_user u join admin_user_role ur on ur.admin_user_id=u.id join admin_role r on r.id=ur.admin_role_id where u.id=:id for update of u")
                .param("id",targetId).query((rs,row)->new String[]{rs.getString(1),rs.getString(2)}).optional()
                .orElseThrow(()->new AdminUserException("ADMIN_USER_NOT_FOUND"));
        if (body.version()!=jdbc.sql("select version from admin_user where id=:id").param("id",targetId).query(Long.class).single())
            throw new AdminUserException("ADMIN_USER_VERSION_CONFLICT");
        if (to.equals(target[0]) || "LOCKED".equals(target[0]) && "ACTIVE".equals(to))
            throw new AdminUserException("ADMIN_STATUS_TRANSITION_DENIED");
        if (targetId.equals(actor) && "INACTIVE".equals(to)) throw new AdminUserException("SELF_DEACTIVATION_DENIED");
        if ("ACTIVE".equals(target[0]) && "OWNER".equals(target[1]) && "INACTIVE".equals(to) && ownerCount()<=1)
            throw new AdminUserException("LAST_OWNER_REQUIRED");
        Instant now=clock.instant();
        int changed=jdbc.sql("update admin_user set status=:status,status_reason=:reason,locked_until=null,failed_login_count=0,version=version+1 where id=:id and version=:version")
                .param("status",to).param("reason","INACTIVE".equals(to)?reason:null).param("id",targetId).param("version",body.version()).update();
        if(changed!=1) throw new AdminUserException("ADMIN_USER_VERSION_CONFLICT");
        int revoked="INACTIVE".equals(to)?jdbc.sql("update admin_session set revoked_at=:now,revoke_reason='USER_DISABLED' where admin_user_id=:id and revoked_at is null")
                .param("now",now.atOffset(ZoneOffset.UTC)).param("id",targetId).update():0;
        audit.record(new Event(now,metadata.requestId(),"MGT-ADMIN-ACCOUNT","PRIVILEGE","ADMIN",actor,null,
                "ADMIN_STATUS_CHANGED","ADMIN_USER",targetId,"SUCCESS",null,metadata.ipAddress(),metadata.userAgent(),
                Map.of("fromStatus",target[0],"toStatus",to,"reasonLength",reason.length())));
        Summary user=decorate(repository.findById(targetId).orElseThrow(()->new AdminUserException("ADMIN_USER_NOT_FOUND")),actor);
        StatusResponse response=new StatusResponse(user,revoked,OffsetDateTime.ofInstant(now,ZoneOffset.ofHours(9)));
        completeSnapshot(scope,key,targetId,201,response);
        return response;
    }

    @Transactional
    public UnlockResponse unlock(UUID targetId, VersionRequest body, UUID key, Authentication auth, RequestMetadata metadata) {
        UUID actor=require(auth,"ADMIN_ACCOUNT_WRITE");
        if(targetId==null||body==null||key==null||body.version()<0)throw new AdminUserException("ADMIN_USER_REQUEST_INVALID");
        String scope="ADMIN_USER_UNLOCK:"+actor, hash=protector.hash(targetId+"|"+body.version());
        if(claim(scope,key,hash)==0){idempotency(scope,key,hash);return snapshot(scope,key,UnlockResponse.class);}
        var state=jdbc.sql("select status,version from admin_user where id=:id for update").param("id",targetId)
                .query((rs,row)->new Object[]{rs.getString(1),rs.getLong(2)}).optional().orElseThrow(()->new AdminUserException("ADMIN_USER_NOT_FOUND"));
        if(((Number)state[1]).longValue()!=body.version())throw new AdminUserException("ADMIN_USER_VERSION_CONFLICT");
        if("INACTIVE".equals(state[0]))throw new AdminUserException("INACTIVE_USER_UNLOCK_DENIED");
        if(!"LOCKED".equals(state[0]))throw new AdminUserException("ADMIN_USER_NOT_LOCKED");
        Instant now=clock.instant();
        jdbc.sql("update admin_user set status='ACTIVE',locked_until=null,failed_login_count=0,version=version+1 where id=:id and version=:version")
                .param("id",targetId).param("version",body.version()).update();
        audit.record(new Event(now,metadata.requestId(),"MGT-ADMIN-ACCOUNT","SECURITY","ADMIN",actor,null,
                "ADMIN_USER_UNLOCKED","ADMIN_USER",targetId,"SUCCESS",null,metadata.ipAddress(),metadata.userAgent(),Map.of()));
        UnlockResponse response=new UnlockResponse(decorate(repository.findById(targetId).orElseThrow(()->new AdminUserException("ADMIN_USER_NOT_FOUND")),actor),OffsetDateTime.ofInstant(now,ZoneOffset.ofHours(9)));
        completeSnapshot(scope,key,targetId,201,response);
        return response;
    }

    @Transactional
    public TemporaryPasswordResponse issueTemporaryPassword(UUID targetId, VersionRequest body, UUID key,
            Authentication auth, RequestMetadata metadata) {
        UUID actor=require(auth,"ADMIN_ACCOUNT_WRITE");
        if(targetId==null||body==null||key==null||body.version()<0)throw new AdminUserException("ADMIN_USER_REQUEST_INVALID");
        if(targetId.equals(actor))throw new AdminUserException("SELF_TEMP_PASSWORD_ISSUE_DENIED");
        String scope="ADMIN_USER_TEMP_PASSWORD:"+actor, hash=protector.hash(targetId+"|"+body.version());
        if(claim(scope,key,hash)==0)return replayTemporaryPassword(scope,key,hash);
        var state=jdbc.sql("select status,version from admin_user where id=:id for update").param("id",targetId)
                .query((rs,row)->new Object[]{rs.getString(1),rs.getLong(2)}).optional().orElseThrow(()->new AdminUserException("ADMIN_USER_NOT_FOUND"));
        if(((Number)state[1]).longValue()!=body.version())throw new AdminUserException("ADMIN_USER_VERSION_CONFLICT");
        if("INACTIVE".equals(state[0]))throw new AdminUserException("INACTIVE_USER_TEMP_PASSWORD_DENIED");
        String password=generateTemporaryPassword();
        String encoded=passwords.encode(password);
        Instant now=clock.instant(), expires=now.plusSeconds(24*60*60);
        int updated=jdbc.sql("update admin_user set password_hash=:hash,password_must_change=true,temporary_password_expires_at=:expires,password_changed_at=:now,status='ACTIVE',status_reason=null,locked_until=null,failed_login_count=0,version=version+1 where id=:id and version=:version")
                .param("hash",encoded).param("expires",expires.atOffset(ZoneOffset.UTC)).param("now",now.atOffset(ZoneOffset.UTC))
                .param("id",targetId).param("version",body.version()).update();
        if(updated!=1)throw new AdminUserException("ADMIN_USER_VERSION_CONFLICT");
        jdbc.sql("insert into admin_password_history(admin_user_id,password_hash,changed_at) values(:id,:hash,:now)")
                .param("id",targetId).param("hash",encoded).param("now",now.atOffset(ZoneOffset.UTC)).update();
        int revoked=jdbc.sql("update admin_session set revoked_at=:now,revoke_reason='PASSWORD_REISSUED' where admin_user_id=:id and revoked_at is null")
                .param("now",now.atOffset(ZoneOffset.UTC)).param("id",targetId).update();
        audit.record(new Event(now,metadata.requestId(),"MGT-ADMIN-ACCOUNT","SECURITY","ADMIN",actor,null,
                "ADMIN_TEMP_PASSWORD_ISSUED","ADMIN_USER",targetId,"SUCCESS",null,metadata.ipAddress(),metadata.userAgent(),Map.of()));
        Summary user=decorate(repository.findById(targetId).orElseThrow(()->new AdminUserException("ADMIN_USER_NOT_FOUND")),actor);
        TemporaryPasswordResponse response=new TemporaryPasswordResponse(user,password,OffsetDateTime.ofInstant(expires,ZoneOffset.ofHours(9)),revoked);
        completeSensitive(scope,key,targetId,201,response);
        return response;
    }

    private TemporaryPasswordResponse replayTemporaryPassword(String scope,UUID key,String hash) {
        var prior=jdbc.sql("select request_hash,state,encrypted_response,sensitive_response_revealed_at from idempotency_record where scope=:scope and idempotency_key=:key for update")
                .param("scope",scope).param("key",key).query((rs,row)->new Object[]{rs.getString(1),rs.getString(2),rs.getBytes(3),rs.getObject(4)}).single();
        if(!hash.equals(prior[0]))throw new AdminUserException("IDEMPOTENCY_KEY_REUSED");
        if(!"COMPLETED".equals(prior[1])||prior[2]==null)throw new AdminUserException("IDEMPOTENCY_IN_PROGRESS");
        if(prior[3]!=null)throw new AdminUserException("SENSITIVE_IDEMPOTENCY_RESPONSE_CONSUMED");
        int consumed=jdbc.sql("update idempotency_record set sensitive_response_revealed_at=statement_timestamp() where scope=:scope and idempotency_key=:key and sensitive_response_revealed_at is null")
                .param("scope",scope).param("key",key).update();
        if(consumed!=1)throw new AdminUserException("SENSITIVE_IDEMPOTENCY_RESPONSE_CONSUMED");
        try{return mapper.readValue(protector.reveal((byte[])prior[2]),TemporaryPasswordResponse.class);}
        catch(Exception exception){throw new AdminUserException("ADMIN_USER_CREATE_FAILED");}
    }

    private int claim(String scope, UUID key, String hash) {
        return jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at) values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours') on conflict do nothing")
                .param("scope",scope).param("key",key).param("hash",hash).update();
    }
    private UUID idempotency(String scope,UUID key,String hash) {
        var prior=jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key for update")
                .param("scope",scope).param("key",key).query((rs,row)->new Object[]{rs.getString(1),rs.getString(2),rs.getObject(3,UUID.class)}).single();
        if(!hash.equals(prior[0]))throw new AdminUserException("IDEMPOTENCY_KEY_REUSED");
        if(!"COMPLETED".equals(prior[1])||prior[2]==null)throw new AdminUserException("IDEMPOTENCY_IN_PROGRESS");
        return (UUID)prior[2];
    }
    private void completeSnapshot(String scope,UUID key,UUID id,int status,Object response) {
        try {
            jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:id,response_status=:status,encrypted_response=:response where scope=:scope and idempotency_key=:key")
                    .param("id",id).param("status",status).param("response",protector.protect(mapper.writeValueAsString(response)))
                    .param("scope",scope).param("key",key).update();
        } catch(Exception exception) { throw new AdminUserException("ADMIN_USER_UPDATE_FAILED"); }
    }
    private <T> T snapshot(String scope,UUID key,Class<T> type) {
        byte[] encrypted=jdbc.sql("select encrypted_response from idempotency_record where scope=:scope and idempotency_key=:key")
                .param("scope",scope).param("key",key).query(byte[].class).optional().orElseThrow(()->new AdminUserException("IDEMPOTENCY_IN_PROGRESS"));
        try{return mapper.readValue(protector.reveal(encrypted),type);}
        catch(Exception exception){throw new AdminUserException("ADMIN_USER_UPDATE_FAILED");}
    }

    private long ownerCount() {
        return jdbc.sql("select count(*) from admin_user u join admin_user_role ur on ur.admin_user_id=u.id join admin_role r on r.id=ur.admin_role_id where u.status='ACTIVE' and r.code='OWNER'")
                .query(Long.class).single();
    }

    private Summary decorate(Summary item, UUID current) {
        List<String> actions = new ArrayList<>();
        boolean self = item.id().equals(current);
        if ("ACTIVE".equals(item.status())) {
            actions.add("CHANGE_ROLE");
            if (!self) actions.add("DEACTIVATE");
        } else if ("INACTIVE".equals(item.status())) {
            actions.add("REACTIVATE");
        } else {
            actions.add("UNLOCK");
        }
        if (!self && ("ACTIVE".equals(item.status()) || "LOCKED".equals(item.status())))
            actions.add("ISSUE_TEMPORARY_PASSWORD");
        return new Summary(item.id(), item.displayName(), item.email(), item.role(), item.status(), item.lastLoginAt(),
                item.lockedUntil(), item.passwordMustChange(), item.createdAt(), item.updatedAt(), item.version(), self, List.copyOf(actions));
    }

    private UUID require(Authentication auth, String permission) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UUID id)
                || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(permission)))
            throw new AdminUserException("ADMIN_ACCOUNT_READ_DENIED");
        return id;
    }
    private static String blank(String value) { if (value == null || value.isBlank()) return null; return value.trim(); }
    public static final class AdminUserException extends RuntimeException {
        private final String code;
        public AdminUserException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}

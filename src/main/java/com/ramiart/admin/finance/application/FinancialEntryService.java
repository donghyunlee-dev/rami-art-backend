package com.ramiart.admin.finance.application;

import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.finance.application.FinancialEntryModels.*;
import com.ramiart.admin.finance.application.FinancialEntryRepository.Claim;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinancialEntryService {
    private static final ZoneId STUDIO_ZONE = ZoneId.of("Asia/Seoul");
    private final FinancialEntryRepository repository;
    private final AuditRecorder audit;
    private final Clock clock;

    public FinancialEntryService(FinancialEntryRepository repository, AuditRecorder audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EntryPage list(LocalDate from, LocalDate to, List<String> types, List<UUID> accounts,
            List<String> categories, List<String> statuses, String keyword, int page, int size,
            Authentication authentication) {
        require(authentication, "FINANCE_READ");
        LocalDate today = LocalDate.now(clock.withZone(STUDIO_ZONE));
        LocalDate end = to == null ? today.withDayOfMonth(today.lengthOfMonth()) : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start,end) > 365 || page < 0 || !List.of(10, 20, 50).contains(size)
                || !validEnums(types, List.of("INCOME", "EXPENSE"))
                || !validEnums(statuses, List.of("CONFIRMED", "CANCELLED"))
                || keyword != null && !keyword.isBlank() && (keyword.trim().length() < 2 || keyword.trim().length() > 50)
                || categories != null && categories.stream().anyMatch(x -> x == null || !x.matches("[A-Z][A-Z0-9_]{2,49}"))
                || accounts != null && accounts.stream().anyMatch(x -> x == null)) {
            throw new FinancialEntryException("FINANCIAL_ENTRY_QUERY_INVALID");
        }
        List<String> selectedStatuses = statuses == null || statuses.isEmpty() ? List.of("CONFIRMED") : statuses.stream().distinct().sorted().toList();
        return repository.list(start, end, types == null ? List.of() : types.stream().distinct().sorted().toList(),
                accounts == null ? List.of() : accounts.stream().distinct().sorted().toList(),
                categories == null ? List.of() : categories.stream().distinct().sorted().toList(), selectedStatuses,
                keyword == null || keyword.isBlank() ? null : keyword.trim(), page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Options options(Authentication authentication) {
        require(authentication, "FINANCE_READ");
        return repository.options();
    }

    @Transactional
    public WriteResult create(CreateRequest request, UUID key, Metadata metadata, Authentication authentication) {
        UUID actor = require(authentication, "FINANCE_WRITE");
        validate(request, key);
        if (request.transactionDate().isAfter(LocalDate.now(clock.withZone(STUDIO_ZONE)))) throw new FinancialEntryException("VALIDATION_ERROR");
        if (request.amount() < 1 || request.amount() > 99999999999999L) throw new FinancialEntryException("FINANCIAL_ENTRY_INVALID_AMOUNT");
        String description = request.description().trim();
        if (!repository.validAccount(request.accountId())) throw new FinancialEntryException("FINANCE_ACCOUNT_INACTIVE");
        if (!repository.validCategory(request.categoryCode(), request.type())) throw new FinancialEntryException("FINANCE_CATEGORY_TYPE_MISMATCH");
        CreateRequest normalized = new CreateRequest(request.transactionDate(), request.type(), request.accountId(), request.categoryCode(), description, request.amount());
        String scope = "financial-entry:create:" + actor;
        Claim claim = repository.claim(scope, key, hash(normalized.toString()));
        if (claim.claimed()) {
            UUID id = repository.insert(normalized, actor);
            repository.complete(scope, key, id, 201);
            audit.record(event(actor, metadata, "FINANCIAL_ENTRY_CREATED", id, key,
                    Map.of("type", request.type(), "amount", request.amount(), "sourceType", "MANUAL")));
            return new WriteResult(repository.find(id).orElseThrow(() -> new FinancialEntryException("FINANCIAL_ENTRY_SAVE_FAILED")), true);
        }
        return new WriteResult(repository.find(claim.resourceId()).orElseThrow(() -> new FinancialEntryException("FINANCIAL_ENTRY_SAVE_FAILED")), false);
    }

    @Transactional
    public WriteResult cancel(UUID id, CancelRequest request, UUID key, Metadata metadata, Authentication authentication) {
        UUID actor = require(authentication, "FINANCE_WRITE");
        if (id == null || key == null || request == null || request.reason() == null || request.reason().trim().length() < 5
                || request.reason().trim().length() > 200 || request.version() < 0) throw new FinancialEntryException("VALIDATION_ERROR");
        Entry old = repository.find(id).orElseThrow(() -> new FinancialEntryException("FINANCIAL_ENTRY_NOT_FOUND"));
        if (!List.of("MANUAL", "IMPORT").contains(old.sourceType())) throw new FinancialEntryException("FINANCIAL_ENTRY_LINKED_SOURCE");
        String reason = request.reason().trim();
        String scope = "financial-entry:cancel:" + id + ":" + actor;
        Claim claim = repository.claim(scope, key, hash(id + "|" + reason + "|" + request.version()));
        if (!claim.claimed()) return new WriteResult(repository.find(id).orElseThrow(() -> new FinancialEntryException("FINANCIAL_ENTRY_NOT_FOUND")), false);
        if ("CANCELLED".equals(old.status())) throw new FinancialEntryException("FINANCIAL_ENTRY_ALREADY_CANCELLED");
        if (old.version() != request.version() || repository.cancel(id, request.version(), actor, reason) != 1)
            throw new FinancialEntryException("FINANCIAL_ENTRY_VERSION_CONFLICT");
        repository.complete(scope, key, id, 200);
        audit.record(event(actor, metadata, "FINANCIAL_ENTRY_CANCELLED", id, key, Map.of("sourceType", old.sourceType())));
        return new WriteResult(repository.find(id).orElseThrow(() -> new FinancialEntryException("FINANCIAL_ENTRY_NOT_FOUND")), true);
    }

    private AuditRecorder.Event event(UUID actor, Metadata m, String action, UUID id, UUID key, Map<String,Object> details) {
        return new AuditRecorder.Event(clock.instant(), m.requestId(), "MGT-FINANCE-LEDGER", "FINANCE", "ADMIN", actor,
                null, action, "FINANCIAL_ENTRY", id, "SUCCESS", key.toString(), m.ipAddress(), m.userAgent(), details);
    }
    private static boolean validEnums(List<String> values, List<String> allowed) { return values == null || values.stream().allMatch(x -> x != null && allowed.contains(x)); }
    private static UUID require(Authentication a, String p) { if(a==null||a.getAuthorities().stream().noneMatch(x->p.equals(x.getAuthority())))throw new FinancialEntryException(p+"_DENIED");try{return UUID.fromString(a.getName());}catch(Exception e){throw new FinancialEntryException(p+"_DENIED");} }
    private static void validate(CreateRequest r, UUID key) {
        if (r == null || key == null || r.transactionDate() == null || !List.of("INCOME", "EXPENSE").contains(r.type())
                || r.accountId() == null || r.categoryCode() == null || !r.categoryCode().matches("[A-Z][A-Z0-9_]{2,49}")
                || r.description() == null || r.description().trim().isEmpty() || r.description().trim().length() > 200)
            throw new FinancialEntryException("VALIDATION_ERROR");
    }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }

    public record Metadata(String requestId, String ipAddress, String userAgent) {}
    public static final class FinancialEntryException extends RuntimeException {
        private final String code;
        public FinancialEntryException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}

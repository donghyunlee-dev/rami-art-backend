package com.ramiart.admin.finance.application;

import com.ramiart.admin.finance.application.FinanceSettlementModels.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceSettlementService {
    private static final ZoneId STUDIO_ZONE=ZoneId.of("Asia/Seoul");
    private final FinanceSettlementRepository repository;
    private final Clock clock;
    public FinanceSettlementService(FinanceSettlementRepository repository,Clock clock){this.repository=repository;this.clock=clock;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Settlement get(LocalDate from,LocalDate to,List<UUID> accountIds,Authentication authentication){
        require(authentication);
        LocalDate today=LocalDate.now(clock.withZone(STUDIO_ZONE));
        LocalDate start=from==null?today.withDayOfMonth(1):from;
        LocalDate end=to==null?today.withDayOfMonth(today.lengthOfMonth()):to;
        List<UUID> ids=accountIds==null?List.of():accountIds.stream().distinct().sorted().toList();
        if(start.isAfter(end)||ChronoUnit.DAYS.between(start,end)>365)throw new FinanceSettlementException("SETTLEMENT_PERIOD_INVALID");
        if(!repository.accountsExist(ids))throw new FinanceSettlementException("SETTLEMENT_ACCOUNT_INVALID");
        Settlement result=repository.get(start,end,ids,OffsetDateTime.now(clock.withZone(STUDIO_ZONE)));
        long categoryIncome=result.categoryTotals().stream().filter(x->"INCOME".equals(x.type())).mapToLong(CategoryTotal::amount).sum();
        long categoryExpense=result.categoryTotals().stream().filter(x->"EXPENSE".equals(x.type())).mapToLong(CategoryTotal::amount).sum();
        long dailyIncome=result.dailyTotals().stream().mapToLong(DailyTotal::income).sum();
        long dailyExpense=result.dailyTotals().stream().mapToLong(DailyTotal::expense).sum();
        long categoryCount=result.categoryTotals().stream().mapToLong(CategoryTotal::count).sum();
        long dailyCount=result.dailyTotals().stream().mapToLong(DailyTotal::count).sum();
        if(result.totals().net()!=result.totals().income()-result.totals().expense()
                ||categoryIncome!=result.totals().income()||categoryExpense!=result.totals().expense()
                ||dailyIncome!=result.totals().income()||dailyExpense!=result.totals().expense()
                ||categoryCount!=result.totals().entryCount()||dailyCount!=result.totals().entryCount())
            throw new FinanceSettlementException("SETTLEMENT_QUERY_FAILED");
        return result;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Options options(Authentication authentication){
        require(authentication); LocalDate today=LocalDate.now(clock.withZone(STUDIO_ZONE));
        LocalDate currentStart=today.withDayOfMonth(1),previousStart=currentStart.minusMonths(1);
        return new Options("Asia/Seoul",repository.accounts(),new Presets(new Preset(currentStart,currentStart.plusMonths(1).minusDays(1)),
                new Preset(previousStart,currentStart.minusDays(1))));
    }
    private static void require(Authentication a){if(a==null||a.getAuthorities().stream().noneMatch(x->"FINANCE_READ".equals(x.getAuthority())))throw new FinanceSettlementException("FINANCE_READ_DENIED");}
    public static final class FinanceSettlementException extends RuntimeException {private final String code;public FinanceSettlementException(String code){super(code);this.code=code;}public String code(){return code;}}
}

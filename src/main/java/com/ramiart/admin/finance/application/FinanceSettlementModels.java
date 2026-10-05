package com.ramiart.admin.finance.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class FinanceSettlementModels {
    private FinanceSettlementModels() {}
    public record Account(UUID accountId,String name,boolean active,int displayOrder) {}
    public record Period(LocalDate from,LocalDate to) {}
    public record Totals(long income,long expense,long net,long entryCount) {}
    public record CategoryTotal(String type,String categoryCode,String categoryName,long amount,long count,String targetUrl) {}
    public record DailyTotal(LocalDate date,long income,long expense,long net,long count,String targetUrl) {}
    public record Filters(List<UUID> accountIds,List<Account> accounts) {}
    public record Links(String incomeLedgerUrl,String expenseLedgerUrl) {}
    public record Settlement(OffsetDateTime asOf,String timezone,String currency,Period period,Filters filters,
            Totals totals,List<CategoryTotal> categoryTotals,List<DailyTotal> dailyTotals,Links links) {}
    public record Preset( LocalDate from,LocalDate to) {}
    public record Options(String timezone,List<Account> accounts,Presets presets) {}
    public record Presets(Preset thisMonth,Preset previousMonth) {}
}

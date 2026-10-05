package com.ramiart.admin.finance.application;

import com.ramiart.admin.finance.application.FinanceSettlementModels.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface FinanceSettlementRepository {
    Settlement get(LocalDate from,LocalDate to,List<UUID> accountIds,OffsetDateTime asOf);
    List<Account> accounts();
    boolean accountsExist(List<UUID> ids);
}

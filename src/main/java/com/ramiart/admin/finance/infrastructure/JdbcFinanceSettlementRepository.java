package com.ramiart.admin.finance.infrastructure;

import com.ramiart.admin.finance.application.FinanceSettlementModels.*;
import com.ramiart.admin.finance.application.FinanceSettlementRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFinanceSettlementRepository implements FinanceSettlementRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcFinanceSettlementRepository(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}

    @Override public Settlement get(LocalDate from,LocalDate to,List<UUID> accountIds,OffsetDateTime asOf){
        MapSqlParameterSource p=new MapSqlParameterSource().addValue("from",from).addValue("to",to).addValue("ids",accountIds);
        String where=" where e.status='CONFIRMED' and e.transaction_date between :from and :to "+(accountIds.isEmpty()?"":"and e.account_id in (:ids) ");
        String joins=" from financial_entry e join finance_account a on a.id=e.account_id join finance_category c on c.code=e.category_code ";
        Map<String,Object> raw=jdbc.queryForMap("select coalesce(sum(e.amount) filter(where e.type='INCOME'),0)::bigint income,coalesce(sum(e.amount) filter(where e.type='EXPENSE'),0)::bigint expense,count(*) entry_count"+joins+where,p);
        long income=((Number)raw.get("income")).longValue(),expense=((Number)raw.get("expense")).longValue(),count=((Number)raw.get("entry_count")).longValue();
        List<Account> accounts=accountsByIds(accountIds);
        List<CategoryTotal> categoryTotals=jdbc.query("select e.type,e.category_code,c.name category_name,sum(e.amount)::bigint amount,count(*)::bigint count"+joins+where+
                        " group by e.type,e.category_code,c.name order by case e.type when 'INCOME' then 0 else 1 end,amount desc,e.category_code asc",p,
                (r,n)->new CategoryTotal(r.getString("type"),r.getString("category_code"),r.getString("category_name"),r.getLong("amount"),r.getLong("count"),
                        ledger(from,to,r.getString("type"),r.getString("category_code"),accountIds)));
        List<DailyTotal> dailyTotals=jdbc.query("select e.transaction_date,coalesce(sum(e.amount) filter(where e.type='INCOME'),0)::bigint income,coalesce(sum(e.amount) filter(where e.type='EXPENSE'),0)::bigint expense,count(*)::bigint count"+joins+where+
                        " group by e.transaction_date order by e.transaction_date asc",p,
                (r,n)->{LocalDate date=r.getObject("transaction_date",LocalDate.class);return new DailyTotal(date,r.getLong("income"),r.getLong("expense"),r.getLong("income")-r.getLong("expense"),r.getLong("count"),ledger(date,date,null,null,accountIds));});
        return new Settlement(asOf,"Asia/Seoul","KRW",new Period(from,to),new Filters(accountIds,accounts),new Totals(income,expense,income-expense,count),categoryTotals,dailyTotals,
                new Links(ledger(from,to,"INCOME",null,accountIds),ledger(from,to,"EXPENSE",null,accountIds)));
    }
    @Override public List<Account> accounts(){return jdbc.query("select id,name,active,display_order from finance_account order by display_order,name,id",Map.of(),this::account);}
    @Override public boolean accountsExist(List<UUID> ids){if(ids.isEmpty())return true;return jdbc.queryForObject("select count(*)=:size from finance_account where id in (:ids)",Map.of("size",ids.size(),"ids",ids),Boolean.class);}
    private List<Account> accountsByIds(List<UUID> ids){
        if(ids.isEmpty())return List.of();
        return jdbc.query("select id,name,active,display_order from finance_account where id in (:ids) order by display_order,name,id",Map.of("ids",ids),this::account);
    }
    private Account account(ResultSet r,int row)throws SQLException{return new Account(r.getObject("id",UUID.class),r.getString("name"),r.getBoolean("active"),r.getInt("display_order"));}
    private static String ledger(LocalDate from,LocalDate to,String type,String category,List<UUID> accountIds){
        StringBuilder url=new StringBuilder("/admin/finance/ledger?from=").append(from).append("&to=").append(to);
        if(type!=null)url.append("&types=").append(type);
        if(category!=null)url.append("&categoryCodes=").append(category);
        for(UUID id:accountIds)url.append("&accountIds=").append(id);
        return url.append("&statuses=CONFIRMED").toString();
    }
}

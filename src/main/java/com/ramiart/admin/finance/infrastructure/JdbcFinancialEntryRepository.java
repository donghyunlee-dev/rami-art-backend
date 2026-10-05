package com.ramiart.admin.finance.infrastructure;

import com.ramiart.admin.finance.application.FinancialEntryModels.*;
import com.ramiart.admin.finance.application.FinancialEntryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFinancialEntryRepository implements FinancialEntryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcFinancialEntryRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public EntryPage list(LocalDate from, LocalDate to, List<String> types, List<UUID> accounts,
            List<String> categories, List<String> statuses, String keyword, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", from).addValue("to", to)
                .addValue("types", types).addValue("accounts", accounts).addValue("categories", categories)
                .addValue("statuses", statuses).addValue("keyword", keyword).addValue("offset", (long)page * size).addValue("size", size);
        StringBuilder where = new StringBuilder(" where e.transaction_date between :from and :to ");
        if (!types.isEmpty()) where.append("and e.type in (:types) ");
        if (!accounts.isEmpty()) where.append("and e.account_id in (:accounts) ");
        if (!categories.isEmpty()) where.append("and e.category_code in (:categories) ");
        where.append("and e.status in (:statuses) ");
        if (keyword != null) where.append("and e.description ilike :keyword ");
        if (keyword != null) p.addValue("keyword", "%" + keyword + "%");
        String fromSql = " from financial_entry e join finance_account a on a.id=e.account_id join finance_category c on c.code=e.category_code join admin_user u on u.id=e.created_by ";
        long count = jdbc.queryForObject("select count(*)" + fromSql + where, p, Long.class);
        long income = total("INCOME", fromSql, where, p), expense = total("EXPENSE", fromSql, where, p);
        List<Entry> items = jdbc.query("select e.*,a.name account_name,a.active account_active,a.type account_type,a.display_order account_order,c.name category_name,c.type category_type,c.active category_active,c.display_order category_order,u.display_name created_by_name"
                + fromSql + where + " order by e.transaction_date desc,e.id desc limit :size offset :offset", p, this::entry);
        return new EntryPage(items, new Page(page, size, count, (count + size - 1) / size),
                new Totals(income, expense, income - expense, countConfirmed(fromSql, from, to, types, accounts, categories, keyword)), new Applied(from, to, statuses));
    }
    private long total(String type, String fromSql, StringBuilder where, MapSqlParameterSource base) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        base.getValues().forEach(p::addValue);
        p.addValue("sumType", type);
        String criteria = where.toString().replace("and e.status in (:statuses)", "and e.status='CONFIRMED'");
        return jdbc.queryForObject("select coalesce(sum(e.amount),0)::bigint" + fromSql + criteria + " and e.type=:sumType", p, Long.class);
    }
    private long countConfirmed(String fromSql, LocalDate from, LocalDate to, List<String> types, List<UUID> accounts,
            List<String> categories, String keyword) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", from).addValue("to", to)
                .addValue("types", types).addValue("accounts", accounts).addValue("categories", categories).addValue("keyword", keyword);
        StringBuilder sql = new StringBuilder("select count(*)" + fromSql + " where e.transaction_date between :from and :to and e.status='CONFIRMED' ");
        if (!types.isEmpty()) sql.append("and e.type in (:types) ");
        if (!accounts.isEmpty()) sql.append("and e.account_id in (:accounts) ");
        if (!categories.isEmpty()) sql.append("and e.category_code in (:categories) ");
        if (keyword != null) { sql.append("and e.description ilike :keyword "); p.addValue("keyword", "%" + keyword + "%"); }
        return jdbc.queryForObject(sql.toString(), p, Long.class);
    }
    @Override public Options options() {
        List<Account> filterAccounts = jdbc.query("select id,name,active,type,display_order from finance_account order by display_order,name,id", Map.of(),
                (r,n) -> new Account(r.getObject("id",UUID.class),r.getString("name"),r.getBoolean("active"),r.getString("type"),r.getInt("display_order")));
        List<Category> filterCategories = jdbc.query("select code,name,type,active,display_order from finance_category order by case type when 'INCOME' then 0 else 1 end,display_order,code", Map.of(),
                (r,n) -> new Category(r.getString("code"),r.getString("name"),r.getString("type"),r.getBoolean("active"),r.getInt("display_order")));
        return new Options(filterAccounts.stream().filter(Account::active).toList(), filterCategories.stream().filter(Category::active).toList(), filterAccounts, filterCategories);
    }
    @Override public boolean validAccount(UUID id) { return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from finance_account where id=:id and active and currency='KRW')",Map.of("id",id),Boolean.class)); }
    @Override public boolean validCategory(String code,String type) { return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from finance_category where code=:code and type=:type and active)",Map.of("code",code,"type",type),Boolean.class)); }
    @Override public Claim claim(String scope,UUID key,String hash) {
        int inserted=jdbc.update("insert into idempotency_record(scope,idempotency_key,request_hash,state,expires_at) values(:scope,:key,:hash,'PROCESSING',statement_timestamp()+interval '24 hours') on conflict(scope,idempotency_key) do nothing",Map.of("scope",scope,"key",key,"hash",hash));
        if(inserted==1)return new Claim(true,null);
        Map<String,Object> old=jdbc.queryForMap("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key for update",Map.of("scope",scope,"key",key));
        if(!hash.equals(old.get("request_hash")))throw new com.ramiart.admin.finance.application.FinancialEntryService.FinancialEntryException("IDEMPOTENCY_KEY_REUSED");
        if(!"COMPLETED".equals(old.get("state")))throw new com.ramiart.admin.finance.application.FinancialEntryService.FinancialEntryException("IDEMPOTENCY_IN_PROGRESS");
        return new Claim(false,(UUID)old.get("resource_id"));
    }
    @Override public UUID insert(CreateRequest r,UUID actor) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into financial_entry(id,transaction_date,type,account_id,category_code,description,amount,status,source_type,created_by) values(:id,:date,:type,:account,:category,:description,:amount,'CONFIRMED','MANUAL',:actor)",
                Map.of("id",id,"date",r.transactionDate(),"type",r.type(),"account",r.accountId(),"category",r.categoryCode(),"description",r.description(),"amount",r.amount(),"actor",actor));
        return id;
    }
    @Override public Optional<Entry> find(UUID id) {
        List<Entry> rows=jdbc.query("select e.*,a.name account_name,a.active account_active,a.type account_type,a.display_order account_order,c.name category_name,c.type category_type,c.active category_active,c.display_order category_order,u.display_name created_by_name from financial_entry e join finance_account a on a.id=e.account_id join finance_category c on c.code=e.category_code join admin_user u on u.id=e.created_by where e.id=:id",Map.of("id",id),this::entry);
        return rows.stream().findFirst();
    }
    @Override public int cancel(UUID id,long version,UUID actor,String reason) {
        return jdbc.update("update financial_entry set status='CANCELLED',cancelled_by=:actor,cancelled_at=statement_timestamp(),cancel_reason=:reason,version=version+1 where id=:id and version=:version and status='CONFIRMED' and source_type in ('MANUAL','IMPORT')",
                Map.of("id",id,"version",version,"actor",actor,"reason",reason));
    }
    @Override public void complete(String scope,UUID key,UUID id,int status) {
        jdbc.update("update idempotency_record set state='COMPLETED',resource_id=:id,response_status=:status where scope=:scope and idempotency_key=:key",Map.of("id",id,"status",status,"scope",scope,"key",key));
    }
    private Entry entry(ResultSet r,int row) throws SQLException {
        Account account=new Account(r.getObject("account_id",UUID.class),r.getString("account_name"),r.getBoolean("account_active"),r.getString("account_type"),r.getInt("account_order"));
        Category category=new Category(r.getString("category_code"),r.getString("category_name"),r.getString("category_type"),r.getBoolean("category_active"),r.getInt("category_order"));
        UUID creatorId=r.getObject("created_by",UUID.class); String source=r.getString("source_type"),status=r.getString("status");
        return new Entry(r.getObject("id",UUID.class),r.getObject("transaction_date",LocalDate.class),r.getString("type"),account,category,
                r.getString("description"),r.getBigDecimal("amount").longValueExact(),status,source,r.getObject("source_id",UUID.class),r.getString("external_id"),
                new Creator(creatorId,r.getString("created_by_name")),r.getObject("created_at",OffsetDateTime.class),r.getObject("cancelled_at",OffsetDateTime.class),
                r.getString("cancel_reason"),r.getLong("version"),new Actions("CONFIRMED".equals(status)&&List.of("MANUAL","IMPORT").contains(source)));
    }
}

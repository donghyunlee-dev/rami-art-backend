package com.ramiart.admin.financeimport.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.financeimport.application.FinancialImportModels.*;
import com.ramiart.admin.financeimport.application.FinancialImportRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFinancialImportRepository implements FinancialImportRepository {
    private final NamedParameterJdbcTemplate jdbc;private final ObjectMapper mapper;
    public JdbcFinancialImportRepository(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    @Override public boolean activeAccount(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from finance_account where id=:id and active and currency='KRW')",Map.of("id",id),Boolean.class));}
    @Override public UUID create(String fileName,String sha,UUID account,UUID actor){
        UUID id=UUID.randomUUID();jdbc.update("insert into financial_import_batch(id,file_name,file_sha256,account_id,status,created_by,expires_at) values(:id,:name,:sha,:account,'PARSING',:actor,statement_timestamp()+interval '30 days')",
                Map.of("id",id,"name",fileName,"sha",sha,"account",account,"actor",actor));return id;
    }
    @Override public void setStorageKey(UUID id,String key){jdbc.update("update financial_import_batch set storage_key=:key where id=:id",Map.of("key",key,"id",id));}
    @Override public DuplicateFileWarning duplicateWarning(UUID account,String sha,UUID exclude){
        return jdbc.query("select id,created_at from financial_import_batch where account_id=:account and file_sha256=:sha and id<>:exclude and created_at>=statement_timestamp()-interval '30 days' order by created_at desc limit 1",
                Map.of("account",account,"sha",sha,"exclude",exclude),(r,n)->new DuplicateFileWarning(true,r.getObject("id",UUID.class),r.getObject("created_at",OffsetDateTime.class)))
                .stream().findFirst().orElse(new DuplicateFileWarning(false,null,null));
    }
    @Override public void parseRows(UUID batch,List<RowInput> rows){
        for(RowInput x:rows)jdbc.update("insert into financial_import_row(id,batch_id,row_number,transaction_date,type,amount,description,category_code,external_id,dedup_hash,status,issues,duplicate_entry_id,duplicate_row_id) values(:id,:batch,:number,:date,:type,:amount,:description,:category,:external,:hash,:status,cast(:issues as jsonb),:duplicateEntry,:duplicateRow)",
                new MapSqlParameterSource().addValue("id",x.id()).addValue("batch",batch).addValue("number",x.rowNumber()).addValue("date",x.transactionDate()).addValue("type",x.type()).addValue("amount",x.amount()).addValue("description",x.description()).addValue("category",x.categoryCode()).addValue("external",x.externalId()).addValue("hash",x.dedupHash()).addValue("status",x.status()).addValue("issues",issuesJson(x.issues())).addValue("duplicateEntry",x.duplicateEntryId()).addValue("duplicateRow",x.duplicateRowId()));
        int valid=(int)rows.stream().filter(x->"VALID".equals(x.status())).count(),errors=(int)rows.stream().filter(x->"ERROR".equals(x.status())).count(),duplicates=(int)rows.stream().filter(x->"DUPLICATE".equals(x.status())).count();
        jdbc.update("update financial_import_batch set status='READY',total_count=:total,valid_count=:valid,error_count=:errors,duplicate_count=:duplicates,version=version+1 where id=:id and status='PARSING'",
                Map.of("total",rows.size(),"valid",valid,"errors",errors,"duplicates",duplicates,"id",batch));
    }
    @Override public Optional<Batch> batch(UUID id){return queryBatch(id,false);}
    @Override public Optional<Batch> lockBatch(UUID id){return queryBatch(id,true);}
    private Optional<Batch> queryBatch(UUID id,boolean lock){
        List<Batch> found=jdbc.query("select b.*,a.name account_name,coalesce(t.income,0)::bigint result_income,coalesce(t.expense,0)::bigint result_expense,coalesce(t.entry_count,0)::bigint result_count from financial_import_batch b join finance_account a on a.id=b.account_id left join lateral(select sum(e.amount) filter(where e.type='INCOME') income,sum(e.amount) filter(where e.type='EXPENSE') expense,count(*) entry_count from financial_import_row r join financial_entry e on e.id=r.financial_entry_id where r.batch_id=b.id and e.status='CONFIRMED') t on true where b.id=:id"+(lock?" for update of b":""),Map.of("id",id),this::mapBatch);
        return found.stream().findFirst();
    }
    @Override public Optional<String> storageKey(UUID id){return jdbc.query("select storage_key from financial_import_batch where id=:id",Map.of("id",id),(r,n)->r.getString(1)).stream().filter(java.util.Objects::nonNull).findFirst();}
    @Override public void failParsing(UUID id){jdbc.update("update financial_import_batch set status='FAILED',version=version+1 where id=:id and status='PARSING'",Map.of("id",id));}
    @Override public List<UUID> confirmingBatches(int limit){return jdbc.query("select id from financial_import_batch where status='CONFIRMING' order by created_at,id limit :limit",Map.of("limit",limit),(r,n)->r.getObject(1,UUID.class));}
    @Override public List<ExpiringFile> expiringFiles(int limit){return jdbc.query("select id,storage_key from financial_import_batch where storage_key is not null and ((status in ('PARSING','READY','FAILED') and expires_at<=statement_timestamp()) or (status in ('CONFIRMED','PARTIAL') and confirmed_at<=statement_timestamp()-interval '30 days')) order by expires_at,id limit :limit",Map.of("limit",limit),(r,n)->new ExpiringFile(r.getObject("id",UUID.class),r.getString("storage_key")));}
    @Override public void completeFileExpiry(UUID id,String key){jdbc.update("update financial_import_batch set status=case when status in ('PARSING','READY','FAILED') and expires_at<=statement_timestamp() then 'EXPIRED' else status end,storage_key=null,version=version+case when status in ('PARSING','READY','FAILED') and expires_at<=statement_timestamp() then 1 else 0 end where id=:id and storage_key=:key and ((status in ('PARSING','READY','FAILED') and expires_at<=statement_timestamp()) or (status in ('CONFIRMED','PARTIAL') and confirmed_at<=statement_timestamp()-interval '30 days'))",Map.of("id",id,"key",key));}
    @Override public List<UUID> validRowsForConfirmation(UUID batch,int limit){return jdbc.query("select id from financial_import_row where batch_id=:batch and status='VALID' order by row_number,id limit :limit",Map.of("batch",batch,"limit",limit),(r,n)->r.getObject(1,UUID.class));}
    @Override public Optional<UUID> confirmationActor(UUID batch){return jdbc.query("select split_part(confirmation_idempotency_scope,':',2)::uuid from financial_import_batch where id=:batch and status='CONFIRMING'",Map.of("batch",batch),(r,n)->r.getObject(1,UUID.class)).stream().findFirst();}
    @Override public RowPage rows(UUID batch,List<String> statuses,int size,String cursorRow,String cursorId){
        MapSqlParameterSource p=new MapSqlParameterSource().addValue("batch",batch).addValue("statuses",statuses).addValue("size",size+1);
        StringBuilder where=new StringBuilder(" where r.batch_id=:batch ");if(!statuses.isEmpty())where.append("and r.status in (:statuses) ");
        if(cursorRow!=null){where.append("and (r.row_number,r.id)>(:cursorRow,:cursorId) ");p.addValue("cursorRow",Integer.parseInt(cursorRow)).addValue("cursorId",UUID.fromString(cursorId));}
        List<Row> values=jdbc.query("select r.*,e.status financial_entry_status,d.transaction_date duplicate_date,d.amount duplicate_amount,d.description duplicate_description from financial_import_row r left join financial_entry e on e.id=r.financial_entry_id left join financial_entry d on d.id=r.duplicate_entry_id"+where+" order by r.row_number,r.id limit :size",p,this::row);
        boolean more=values.size()>size;List<Row> items=more?values.subList(0,size):values;
        String next=more&&!items.isEmpty()?items.getLast().rowNumber()+"|"+items.getLast().rowId():null;
        return new RowPage(items,new RowPageInfo(size,next,more));
    }
    @Override public List<Row> selectedRows(UUID batch,List<UUID> ids){if(ids.isEmpty())return List.of();return jdbc.query("select r.*,e.status financial_entry_status,null::date duplicate_date,null::numeric duplicate_amount,null::varchar duplicate_description from financial_import_row r left join financial_entry e on e.id=r.financial_entry_id where r.batch_id=:batch and r.id in (:ids) and r.status='VALID' order by r.row_number,r.id",Map.of("batch",batch,"ids",ids),this::row);}
    @Override public void prepareConfirmation(UUID batch,int selected,long version,String scope,UUID key,String hash){
        int count=jdbc.update("update financial_import_batch set status='CONFIRMING',selected_count=:selected,version=version+1,confirmation_idempotency_scope=:scope,confirmation_idempotency_key=:key,confirmation_request_hash=:hash where id=:id and status='READY' and version=:version",
                Map.of("selected",selected,"scope",scope,"key",key,"hash",hash,"id",batch,"version",version));if(count!=1)throw new IllegalStateException("IMPORT_BATCH_VERSION_CONFLICT");
    }
    @Override public List<UUID> validRowsExcept(UUID batch,List<UUID> ids){
        String sql="select id from financial_import_row where batch_id=:batch and status='VALID'"+(ids.isEmpty()?"":" and id not in (:ids)")+" order by row_number,id";
        return jdbc.query(sql,ids.isEmpty()?Map.of("batch",batch):Map.of("batch",batch,"ids",ids),(r,n)->r.getObject(1,UUID.class));
    }
    @Override public void excludeRows(List<UUID> ids){if(!ids.isEmpty())jdbc.update("update financial_import_row set status='EXCLUDED' where id in (:ids) and status='VALID'",Map.of("ids",ids));}
    @Override public void importRow(UUID batch,UUID row,UUID actor){
        var values=jdbc.queryForMap("select r.id,r.transaction_date,r.type,r.amount,r.description,r.category_code,r.external_id,r.dedup_hash,b.account_id from financial_import_row r join financial_import_batch b on b.id=r.batch_id where r.id=:row and r.batch_id=:batch and r.status='VALID' for update of r",Map.of("row",row,"batch",batch));
        UUID entry=UUID.randomUUID();jdbc.update("insert into financial_entry(id,transaction_date,type,account_id,category_code,description,amount,status,source_type,source_id,external_id,dedup_hash,created_by) values(:id,:date,:type,:account,:category,:description,:amount,'CONFIRMED','IMPORT',:source,:external,:hash,:actor)",
                new MapSqlParameterSource().addValue("id",entry).addValue("date",values.get("transaction_date")).addValue("type",values.get("type")).addValue("account",values.get("account_id")).addValue("category",values.get("category_code")).addValue("description",values.get("description")).addValue("amount",values.get("amount")).addValue("source",row).addValue("external",values.get("external_id")).addValue("hash",values.get("dedup_hash")).addValue("actor",actor));
        jdbc.update("update financial_import_row set status='IMPORTED',financial_entry_id=:entry where id=:row",Map.of("entry",entry,"row",row));
    }
    @Override public void failRow(UUID row,String code){jdbc.update("update financial_import_row set status='FAILED',issues=issues||cast(:issue as jsonb) where id=:id and status='VALID'",Map.of("issue","[{\"field\":\"row\",\"code\":\""+code+"\"}]","id",row));}
    @Override public Confirmation finishConfirmation(UUID batch){
        Map<String,Object> counts=jdbc.queryForMap("select count(*) filter(where status='IMPORTED') imported,count(*) filter(where status='FAILED') failed,count(*) filter(where status in ('IMPORTED','FAILED')) selected from financial_import_row where batch_id=:batch",Map.of("batch",batch));
        int imported=((Number)counts.get("imported")).intValue(),failed=((Number)counts.get("failed")).intValue(),selected=((Number)counts.get("selected")).intValue();String status=failed==0?"CONFIRMED":"PARTIAL";
        jdbc.update("update financial_import_batch set status=:status,imported_count=:imported,failed_count=:failed,selected_count=:selected,confirmed_at=statement_timestamp(),version=version+1 where id=:id and status='CONFIRMING'",Map.of("status",status,"imported",imported,"failed",failed,"selected",selected,"id",batch));
        return confirmation(batch,status,selected);
    }
    @Override public Confirmation confirmation(UUID batch){
        Batch detail=batch(batch).orElseThrow(()->new IllegalArgumentException("IMPORT_BATCH_NOT_FOUND"));return confirmation(batch,detail.status(),detail.counts().selected());
    }
    private Confirmation confirmation(UUID batch,String status,int selected){
        List<Imported> imported=jdbc.query("select id,financial_entry_id from financial_import_row where batch_id=:batch and status='IMPORTED' order by row_number,id",Map.of("batch",batch),(r,n)->new Imported(r.getObject("id",UUID.class),r.getObject("financial_entry_id",UUID.class)));
        List<Failed> failed=jdbc.query("select id,coalesce(issues->0->>'code','IMPORT_ROW_FAILED') from financial_import_row where batch_id=:batch and status='FAILED' order by row_number,id",Map.of("batch",batch),(r,n)->new Failed(r.getObject(1,UUID.class),r.getString(2)));
        Map<String,Object> totals=jdbc.queryForMap("select coalesce(sum(e.amount) filter(where e.type='INCOME'),0)::bigint income,coalesce(sum(e.amount) filter(where e.type='EXPENSE'),0)::bigint expense from financial_import_row r join financial_entry e on e.id=r.financial_entry_id where r.batch_id=:batch and e.status='CONFIRMED'",Map.of("batch",batch));
        long income=((Number)totals.get("income")).longValue(),expense=((Number)totals.get("expense")).longValue();Batch summary=batch(batch).orElseThrow();
        return new Confirmation(batch,status,selected,imported,failed,new Totals(income,expense,income-expense),summary.version());
    }
    @Override public String resultCsv(UUID batch){return String.join("\r\n",jdbc.query("select row_number,status,financial_entry_id,coalesce(issues->0->>'code','') code from financial_import_row where batch_id=:batch order by row_number,id",Map.of("batch",batch),(r,n)->r.getInt(1)+","+r.getString(2)+","+java.util.Objects.toString(r.getObject(3),"")+","+r.getString(4)).stream().collect(java.util.stream.Collectors.toCollection(()->new ArrayList<>(List.of("rowNumber,status,entryId,errorCode")))));}
    @Override public boolean activeCategory(String code,String type){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from finance_category where code=:code and type=:type and active)",Map.of("code",code,"type",type),Boolean.class));}
    @Override public boolean duplicateExternalId(UUID account,String external){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from financial_entry where account_id=:account and external_id=:external)",Map.of("account",account,"external",external),Boolean.class));}
    @Override public boolean duplicateHash(UUID account,String hash){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from financial_entry where account_id=:account and dedup_hash=:hash)",Map.of("account",account,"hash",hash),Boolean.class));}
    @Override public Optional<DuplicateEntry> duplicateEntry(UUID account,String external,String hash){
        if(external==null&&hash==null)return Optional.empty();String predicate=external!=null?"external_id=:value":"dedup_hash=:value";
        return jdbc.query("select id,transaction_date,amount,description from financial_entry where account_id=:account and "+predicate+" order by created_at desc limit 1",Map.of("account",account,"value",external==null?hash:external),
                (r,n)->new DuplicateEntry(r.getObject("id",UUID.class),r.getObject("transaction_date",LocalDate.class),r.getBigDecimal("amount").longValue(),mask(r.getString("description")))).stream().findFirst();
    }
    @Override public Optional<UUID> duplicateRow(UUID batch,String external,String hash){if(external==null&&hash==null)return Optional.empty();String predicate=external!=null?"external_id=:value":"dedup_hash=:value";return jdbc.query("select id from financial_import_row where batch_id=:batch and "+predicate+" and status in ('VALID','DUPLICATE') order by row_number limit 1",Map.of("batch",batch,"value",external==null?hash:external),(r,n)->r.getObject(1,UUID.class)).stream().findFirst();}
    @Override public Claim claim(UUID id,long version,String scope,UUID key,String hash){
        Batch b=lockBatch(id).orElseThrow(()->new IllegalArgumentException("IMPORT_BATCH_NOT_FOUND"));
        Map<String,Object> v=jdbc.queryForMap("select confirmation_idempotency_scope,confirmation_idempotency_key,confirmation_request_hash from financial_import_batch where id=:id",Map.of("id",id));
        if(v.get("confirmation_idempotency_key") instanceof UUID oldKey&&oldKey.equals(key)){
            if(!scope.equals(v.get("confirmation_idempotency_scope"))||!hash.equals(v.get("confirmation_request_hash")))throw new IllegalStateException("IDEMPOTENCY_KEY_REUSED");
            return new Claim(false,true,b);
        }
        if(!"READY".equals(b.status()))throw new IllegalStateException("IMPORT_BATCH_NOT_CONFIRMABLE");
        if(b.version()!=version)throw new IllegalStateException("IMPORT_BATCH_VERSION_CONFLICT");
        return new Claim(true,false,b);
    }
    private Batch mapBatch(ResultSet r,int n)throws SQLException{
        UUID id=r.getObject("id",UUID.class);String status=r.getString("status");UUID accountId=r.getObject("account_id",UUID.class);
        DuplicateFileWarning warning=duplicateWarning(accountId,r.getString("file_sha256"),id);
        long income=r.getLong("result_income"),expense=r.getLong("result_expense");
        boolean canConfirm="READY".equals(status)&&r.getInt("valid_count")>0,canDownload="CONFIRMED".equals(status)||"PARTIAL".equals(status);
        return new Batch(id,status,r.getString("file_name"),new Account(accountId,r.getString("account_name")),new Counts(r.getInt("total_count"),r.getInt("valid_count"),r.getInt("error_count"),r.getInt("duplicate_count"),r.getInt("selected_count"),r.getInt("imported_count"),r.getInt("failed_count")),new Totals(income,expense,income-expense),warning,r.getObject("created_at",OffsetDateTime.class),r.getObject("confirmed_at",OffsetDateTime.class),r.getObject("expires_at",OffsetDateTime.class),r.getLong("version"),new Actions(canConfirm,canDownload));
    }
    private Row row(ResultSet r,int n)throws SQLException{
        List<Issue> issues;try{issues=mapper.readValue(r.getString("issues"),new TypeReference<>(){});}catch(Exception ex){issues=List.of(new Issue("row","IMPORT_ROW_FAILED"));}
        String desc=r.getString("duplicate_description");DuplicateEntry dupe=r.getObject("duplicate_entry_id")==null?null:new DuplicateEntry(r.getObject("duplicate_entry_id",UUID.class),r.getObject("duplicate_date",LocalDate.class),r.getBigDecimal("duplicate_amount").longValue(),mask(desc));
        return new Row(r.getObject("id",UUID.class),r.getInt("row_number"),r.getObject("transaction_date",LocalDate.class),r.getString("type"),r.getObject("amount")==null?null:r.getBigDecimal("amount").longValue(),r.getString("description"),r.getString("category_code"),r.getString("external_id"),r.getString("status"),issues,dupe,"VALID".equals(r.getString("status")),r.getObject("financial_entry_id",UUID.class),r.getString("financial_entry_status"));
    }
    private String issuesJson(List<Issue> values){try{return mapper.writeValueAsString(values);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String mask(String text){if(text==null)return "";String t=text.trim();return t.length()<4?"•••":t.substring(0,3)+"…";}
}

package com.ramiart.admin.tuition.infrastructure;

import com.ramiart.admin.tuition.application.*;
import com.ramiart.admin.tuition.application.TuitionPolicyModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTuitionPolicyRepository implements TuitionPolicyRepository {
    private final JdbcClient jdbc;
    public JdbcTuitionPolicyRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public Optional<Policy> byYearStatus(int year, String status) {
        return jdbc.sql("select * from tuition_policy where year=:year and status=:status")
                .param("year", year).param("status", status).query(this::map).optional();
    }
    @Override public Optional<Policy> byId(UUID id) {
        return jdbc.sql("select * from tuition_policy where id=:id").param("id", id).query(this::map).optional();
    }
    private Policy map(ResultSet rs, int row) throws SQLException {
        UUID id=rs.getObject("id",UUID.class); int year=rs.getInt("year"); String status=rs.getString("status");
        List<Item> items=jdbc.sql("select id,lesson_count_per_week,monthly_amount from tuition_policy_item where tuition_policy_id=:id order by lesson_count_per_week")
                .param("id",id).query((r,n)->new Item(r.getObject("id",UUID.class),r.getInt("lesson_count_per_week"),r.getLong("monthly_amount"))).list();
        List<Integer> missing=java.util.stream.IntStream.rangeClosed(1,7).filter(c->items.stream().noneMatch(i->i.lessonCountPerWeek()==c)).boxed().toList();
        return new Policy(id,id,year,rs.getInt("revision"),status,status,"DRAFT".equals(status),rs.getLong("version"),rs.getInt("default_due_day"),
                rs.getObject("based_on_policy_id",UUID.class),rs.getTimestamp("published_at")==null?null:rs.getTimestamp("published_at").toInstant(),
                rs.getObject("published_by",UUID.class),items,new Validation(missing.isEmpty(),missing,List.of()),new Actions(false,"DRAFT".equals(status),"DRAFT".equals(status),"DRAFT".equals(status)&&missing.isEmpty()));
    }
    @Override public UUID create(int year,int revision,UUID basedOn,int dueDay,UUID actor) {
        UUID id=UUID.randomUUID(); jdbc.sql("insert into tuition_policy(id,year,revision,status,default_due_day,based_on_policy_id,created_by) values (:id,:year,:revision,'DRAFT',:day,:base,:actor)")
                .param("id",id).param("year",year).param("revision",revision).param("day",dueDay).param("base",basedOn).param("actor",actor).update();
        if(basedOn!=null) jdbc.sql("insert into tuition_policy_item(id,tuition_policy_id,lesson_count_per_week,monthly_amount) select gen_random_uuid(),:id,lesson_count_per_week,monthly_amount from tuition_policy_item where tuition_policy_id=:base")
                .param("id",id).param("base",basedOn).update();
        return id;
    }
    @Override public int update(UUID id,long version,int dueDay,List<Item> items) {
        int changed=jdbc.sql("update tuition_policy set default_due_day=:day,version=version+1 where id=:id and status='DRAFT' and version=:version")
                .param("day",dueDay).param("id",id).param("version",version).update(); if(changed!=1)return 0;
        jdbc.sql("delete from tuition_policy_item where tuition_policy_id=:id").param("id",id).update();
        for(Item item:items) jdbc.sql("insert into tuition_policy_item(id,tuition_policy_id,lesson_count_per_week,monthly_amount) values (:item,:id,:count,:amount)")
                .param("item",item.id()).param("id",id).param("count",item.lessonCountPerWeek()).param("amount",item.monthlyAmount()).update();
        return 1;
    }
    @Override public void publish(UUID id,long version,UUID actor) {
        jdbc.sql("update tuition_policy set status='ARCHIVED' where year=(select year from tuition_policy where id=:id) and status='PUBLISHED'").param("id",id).update();
        int n=jdbc.sql("update tuition_policy set status='PUBLISHED',published_at=statement_timestamp(),published_by=:actor,version=version+1 where id=:id and status='DRAFT' and version=:version")
                .param("actor",actor).param("id",id).param("version",version).update();
        if(n!=1) throw new TuitionPolicyException("TUITION_POLICY_VERSION_CONFLICT");
    }
    @Override public Claim claim(String scope,UUID key,String hash) {
        int inserted=jdbc.sql("insert into idempotency_record(scope,idempotency_key,request_hash,expires_at) values (:scope,:key,:hash,statement_timestamp()+interval '24 hours') on conflict do nothing")
                .param("scope",scope).param("key",key).param("hash",hash).update();
        if(inserted==1)return new Claim(true,null);
        var row=jdbc.sql("select request_hash,state,resource_id from idempotency_record where scope=:scope and idempotency_key=:key")
                .param("scope",scope).param("key",key).query((r,n)->new Object[]{r.getString(1),r.getString(2),r.getObject(3,UUID.class)}).single();
        if(!hash.equals(row[0]))throw new TuitionPolicyException("IDEMPOTENCY_KEY_REUSED");
        if(!"COMPLETED".equals(row[1])||row[2]==null)throw new TuitionPolicyException("IDEMPOTENCY_IN_PROGRESS");
        return new Claim(false,(UUID)row[2]);
    }
    @Override public void complete(String scope,UUID key,UUID id,int status){jdbc.sql("update idempotency_record set state='COMPLETED',resource_id=:id,response_status=:status where scope=:scope and idempotency_key=:key")
            .param("id",id).param("status",status).param("scope",scope).param("key",key).update();}
}

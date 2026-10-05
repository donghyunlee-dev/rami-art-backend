package com.ramiart.admin.datatransfer.infrastructure;

import com.ramiart.admin.datatransfer.application.DataTransferRepository;
import static com.ramiart.admin.datatransfer.application.DataTransferModels.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDataTransferRepository implements DataTransferRepository {
    private final JdbcClient jdbc;
    public JdbcDataTransferRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public Optional<JobRecord> findJob(UUID id, UUID createdBy) {
        return jdbc.sql("""
                select id,direction,domain,status,template_version,total_count,valid_count,invalid_count,duplicate_count,
                       confirmed_count,failed_count,version,expires_at
                  from data_transfer_job where id=:id and created_by=:actor and expires_at>statement_timestamp()
                """).param("id", id).param("actor", createdBy).query((row, index) -> new JobRecord(
                row.getObject("id", UUID.class), row.getString("direction"), row.getString("domain"), row.getString("status"),
                row.getString("template_version"), row.getInt("total_count"), row.getInt("valid_count"),
                row.getInt("invalid_count"), row.getInt("duplicate_count"), row.getInt("confirmed_count"),
                row.getInt("failed_count"), row.getLong("version"), row.getObject("expires_at", OffsetDateTime.class)))
                .optional();
    }

    @Override public List<RowRecord> findRows(UUID jobId, List<String> statuses, int afterRowNumber, int limit) {
        return jdbc.sql("""
                select row_number,status,masked_summary,field_errors::text field_errors,duplicate_target_id,result_target_id,error_code
                  from data_transfer_row where job_id=:job and status in (:statuses) and row_number>:after
                 order by row_number,id limit :limit
                """).param("job", jobId).param("statuses", statuses).param("after", afterRowNumber).param("limit", limit)
                .query((row, index) -> new RowRecord(row.getInt("row_number"), row.getString("status"),
                        row.getString("masked_summary"), row.getString("field_errors"),
                        row.getObject("duplicate_target_id", UUID.class), row.getObject("result_target_id", UUID.class),
                        row.getString("error_code"))).list();
    }
}

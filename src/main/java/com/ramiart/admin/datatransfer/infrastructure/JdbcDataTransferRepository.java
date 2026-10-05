package com.ramiart.admin.datatransfer.infrastructure;

import com.ramiart.admin.datatransfer.application.DataTransferRepository;
import static com.ramiart.admin.datatransfer.application.DataTransferModels.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static com.ramiart.admin.datatransfer.application.DataTransferModels.ImportedRow;
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

    @Override public boolean hasActiveImport(String domain, String sha256) {
        return jdbc.sql("select exists(select 1 from data_transfer_job where direction='IMPORT' and domain=:domain and sha256=:hash and status not in ('FAILED','EXPIRED'))")
                .param("domain", domain).param("hash", sha256).query(Boolean.class).single();
    }

    @Override public void createImport(UUID id, String domain, String version, String fileName, String storageKey,
            String sha256, long fileSize, UUID actor) {
        jdbc.sql("""
                insert into data_transfer_job(id,direction,domain,status,template_version,source_file_name,
                    storage_key,sha256,file_size,expires_at,created_by)
                values(:id,'IMPORT',:domain,'PARSING',:version,:name,:key,:hash,:size,
                    statement_timestamp()+interval '24 hours',:actor)
                """).param("id", id).param("domain", domain).param("version", version).param("name", fileName)
                .param("key", storageKey).param("hash", sha256).param("size", fileSize).param("actor", actor).update();
    }

    @Override public void insertRows(UUID jobId, java.util.Collection<ImportedRow> rows) {
        for (ImportedRow row : rows) {
            jdbc.sql("""
                    insert into data_transfer_row(id,job_id,row_number,status,payload_ciphertext,dedup_hash,
                        masked_summary,field_errors,duplicate_target_id,error_code)
                    values(:id,:job,:number,:status,:payload,:hash,:summary,cast(:errors as jsonb),:duplicate,:error)
                    """).param("id", row.id()).param("job", jobId).param("number", row.rowNumber())
                    .param("status", row.status()).param("payload", row.payloadCiphertext()).param("hash", row.dedupHash())
                    .param("summary", row.maskedSummary()).param("errors", row.fieldErrorsJson())
                    .param("duplicate", row.duplicateTargetId()).param("error", row.errorCode()).update();
        }
    }

    @Override public void markImportReady(UUID jobId, int total, int valid, int invalid, int duplicates) {
        jdbc.sql("""
                update data_transfer_job set status='READY',total_count=:total,valid_count=:valid,
                    invalid_count=:invalid,duplicate_count=:duplicates,version=version+1
                 where id=:id and status='PARSING'
                """).param("total", total).param("valid", valid).param("invalid", invalid)
                .param("duplicates", duplicates).param("id", jobId).update();
    }
}

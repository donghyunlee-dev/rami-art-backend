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
                select id,row_number,status,masked_summary,field_errors::text field_errors,duplicate_target_id,result_target_id,error_code
                  from data_transfer_row where job_id=:job and status in (:statuses) and row_number>:after
                 order by row_number,id limit :limit
                """).param("job", jobId).param("statuses", statuses).param("after", afterRowNumber).param("limit", limit)
                .query((row, index) -> new RowRecord(row.getObject("id", UUID.class), row.getInt("row_number"), row.getString("status"),
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

    @Override public Optional<ConfirmJob> lockConfirmJob(UUID jobId, UUID actor) {
        return jdbc.sql("""
                select id,domain,version,valid_count,confirmed_count,failed_count,invalid_count,duplicate_count,status
                  from data_transfer_job where id=:id and created_by=:actor and direction='IMPORT'
                    and expires_at>statement_timestamp() for update
                """).param("id", jobId).param("actor", actor).query((row, index) -> new ConfirmJob(
                row.getObject("id", UUID.class), row.getString("domain"), row.getInt("version"), row.getInt("valid_count"),
                row.getInt("confirmed_count"), row.getInt("failed_count"), row.getInt("invalid_count"),
                row.getInt("duplicate_count"), row.getString("status"))).optional();
    }

    @Override public List<ConfirmRow> lockConfirmRows(UUID jobId, List<UUID> rowIds) {
        return jdbc.sql("""
                select id,row_number,status,payload_ciphertext from data_transfer_row
                 where job_id=:job and id in (:ids) and status in ('VALID','FAILED','DUPLICATE')
                 order by id for update
                """).param("job", jobId).param("ids", rowIds).query((row, index) -> new ConfirmRow(
                row.getObject("id", UUID.class), row.getInt("row_number"), row.getString("status"),
                row.getBytes("payload_ciphertext"))).list();
    }

    @Override public void markRowConfirmed(UUID rowId, UUID resultTargetId) {
        jdbc.sql("""
                update data_transfer_row set status='CONFIRMED',payload_ciphertext=null,duplicate_target_id=null,
                    result_target_id=:target,error_code=null,confirmed_at=statement_timestamp()
                 where id=:id and status in ('VALID','FAILED','DUPLICATE')
                """).param("target", resultTargetId).param("id", rowId).update();
    }

    @Override public void markRowDuplicate(UUID rowId, UUID duplicateTargetId) {
        jdbc.sql("""
                update data_transfer_row set status='DUPLICATE',duplicate_target_id=:target,
                    result_target_id=null,error_code=null,confirmed_at=null
                 where id=:id and status in ('VALID','FAILED','DUPLICATE')
                """).param("target", duplicateTargetId).param("id", rowId).update();
    }

    @Override public void markRowFailed(UUID rowId, String errorCode) {
        jdbc.sql("""
                update data_transfer_row set status='FAILED',duplicate_target_id=null,
                    result_target_id=null,error_code=:error,confirmed_at=null
                 where id=:id and status in ('VALID','FAILED','DUPLICATE')
                """).param("error", errorCode).param("id", rowId).update();
    }

    @Override public boolean finishConfirmation(UUID jobId, int expectedVersion, int confirmedDelta,
            int failedDelta, int duplicateDelta, int validDelta) {
        return jdbc.sql("""
                update data_transfer_job j set confirmed_count=j.confirmed_count+:confirmed,
                    failed_count=j.failed_count+:failed,duplicate_count=j.duplicate_count+:duplicates,
                    valid_count=j.valid_count+:valid,version=j.version+1,
                    status=case when exists(select 1 from data_transfer_row r where r.job_id=j.id and r.status='VALID')
                                then 'PROCESSING'
                                when j.invalid_count+j.duplicate_count+:duplicates+j.failed_count+:failed>0 then 'PARTIAL'
                                else 'COMPLETED' end
                 where j.id=:id and j.version=:version
                   and j.confirmed_count+:confirmed>=0 and j.failed_count+:failed>=0
                   and j.valid_count+:valid>=0
                """).param("confirmed", confirmedDelta).param("failed", failedDelta)
                .param("duplicates", duplicateDelta).param("valid", validDelta)
                .param("id", jobId).param("version", expectedVersion).update() == 1;
    }
}

package com.ramiart.admin.financeimport.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ramiart.admin.auth.application.AuditRecorder;
import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.dev.PostgresScriptRunner;
import com.ramiart.admin.financeimport.application.FinancialImportModels.*;
import com.ramiart.admin.financeimport.application.FinancialImportRepository;
import com.ramiart.admin.financeimport.application.FinancialImportRowImporter;
import com.ramiart.admin.financeimport.application.FinancialImportService;
import com.ramiart.admin.financeimport.application.FinancialImportStorage;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

class FinancialImportConfirmationIntegrationTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final DataSource DATA_SOURCE = POSTGRES.getPostgresDatabase();
    private static final JdbcTemplate JDBC = new JdbcTemplate(DATA_SOURCE);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static FinancialImportRepository repository;
    private static FinancialImportService service;
    private static UUID actorId;
    private static UUID accountId;
    private static String categoryCode;

    @BeforeAll
    static void prepareDatabase() throws Exception {
        try (Connection connection=DATA_SOURCE.getConnection(); Statement statement=connection.createStatement()) {
            createRoleIfMissing(statement,"anon");createRoleIfMissing(statement,"authenticated");
            statement.execute("drop schema if exists rami_art_studio cascade");
            statement.execute("drop schema if exists public cascade");statement.execute("drop schema if exists extensions cascade");
            statement.execute("create schema public");statement.execute("create schema extensions");
        }
        Path root=projectRoot();
        try(var migrations=Files.list(root.resolve("supabase/migrations"))){
            for(Path migration:migrations.filter(path->path.getFileName().toString().matches("[0-9]{12,}_.+\\.sql")).sorted().toList())PostgresScriptRunner.execute(DATA_SOURCE,migration);
        }
        PostgresScriptRunner.execute(DATA_SOURCE,root.resolve("supabase/seed.sql"));
        actorId=JDBC.queryForObject("select id from admin_user order by email limit 1",UUID.class);
        accountId=JDBC.queryForObject("select id from finance_account where active and currency='KRW' order by display_order,id limit 1",UUID.class);
        categoryCode=JDBC.queryForObject("select code from finance_category where active and type='INCOME' order by display_order,code limit 1",String.class);
        repository=new JdbcFinancialImportRepository(new NamedParameterJdbcTemplate(DATA_SOURCE),MAPPER);
        var audit=mock(AuditRecorder.class);
        var storage=mock(FinancialImportStorage.class);
        var parser=mock(FinancialImportParserWorker.class);
        var rowTransactions=new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE));
        FinancialImportRowImporter importer=new FinancialImportRowImporter(repository,audit,Clock.systemUTC()){
            @Override public void importRow(UUID batchId,UUID rowId,UUID actor,FinancialImportService.Metadata metadata){
                rowTransactions.executeWithoutResult(status->super.importRow(batchId,rowId,actor,metadata));
            }
        };
        service=new FinancialImportService(repository,storage,audit,parser,importer,Clock.systemUTC(),
                new DataSourceTransactionManager(DATA_SOURCE),"integration-test-dedup-key");
    }

    @AfterAll
    static void closeDatabase() throws IOException {POSTGRES.close();}

    @Test
    void MGT_FINANCE_IMPORT_concurrent_external_id_conflict_creates_one_ledger_entry_and_marks_other_row_duplicate() throws Exception {
        String externalId="concurrent-bank-"+UUID.randomUUID();
        UUID firstBatch=createReadyBatch(externalId,11);
        UUID secondBatch=createReadyBatch(externalId,12);
        UUID firstRow=rowId(firstBatch);
        UUID secondRow=rowId(secondBatch);
        CountDownLatch start=new CountDownLatch(1);
        var authentication=new TestingAuthenticationToken(actorId.toString(),"","FINANCE_IMPORT");
        try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->{await(start);return service.confirm(firstBatch,new ConfirmationRequest(List.of(firstRow),1),UUID.randomUUID(),authentication,metadata("request-import-race-a"));});
            var second=pool.submit(()->{await(start);return service.confirm(secondBatch,new ConfirmationRequest(List.of(secondRow),1),UUID.randomUUID(),authentication,metadata("request-import-race-b"));});
            start.countDown();
            var results=List.of(first.get(),second.get());
            assertThat(results.stream().map(Confirmation::status)).containsExactlyInAnyOrder("CONFIRMED","PARTIAL");
            assertThat(results.stream().flatMap(result->result.failed().stream()).map(Failed::code)).containsExactly("IMPORT_DUPLICATE_DETECTED");
        }
        assertThat(JDBC.queryForObject("select count(*) from financial_entry where account_id=? and external_id=?",Integer.class,accountId,externalId)).isOne();
        assertThat(JDBC.queryForObject("select count(*) from financial_import_row where id in (?,?) and status='IMPORTED'",Integer.class,firstRow,secondRow)).isOne();
        assertThat(JDBC.queryForObject("select count(*) from financial_import_row where id in (?,?) and status='FAILED' and issues @> '[{\"code\":\"IMPORT_DUPLICATE_DETECTED\"}]'",Integer.class,firstRow,secondRow)).isOne();
    }

    private static UUID createReadyBatch(String externalId,int suffix){
        UUID batch=repository.create("race-"+suffix+".csv","%064x".formatted(suffix),accountId,actorId);
        repository.parseRows(batch,List.of(new RowInput(UUID.randomUUID(),2,LocalDate.parse("2026-10-10"),"INCOME",12000L,
                "경합 입금 "+suffix,categoryCode,externalId,null,"VALID",List.of(),null,null)));
        return batch;
    }
    private static UUID rowId(UUID batch){return JDBC.queryForObject("select id from financial_import_row where batch_id=?",UUID.class,batch);}
    private static FinancialImportService.Metadata metadata(String requestId){return new FinancialImportService.Metadata(requestId,"127.0.0.1","integration-test");}
    private static void await(CountDownLatch latch){try{latch.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private static EmbeddedPostgres startPostgres(){try{return EmbeddedPostgres.builder().setPort(0).start();}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    private static Path projectRoot(){Path root=Path.of("").toAbsolutePath().normalize();if(!Files.isDirectory(root.resolve("supabase")))root=root.getParent();return root;}
    private static void createRoleIfMissing(Statement statement,String role)throws Exception{statement.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='"+role+"') then create role "+role+"; end if; end $$");}
}

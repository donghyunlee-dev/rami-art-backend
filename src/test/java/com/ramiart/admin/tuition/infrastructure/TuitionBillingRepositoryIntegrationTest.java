package com.ramiart.admin.tuition.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ramiart.admin.dev.PostgresScriptRunner;
import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.tuition.application.TuitionBillingRepository;
import com.ramiart.admin.tuition.application.TuitionBillingService;
import com.ramiart.admin.tuition.application.TuitionBillingBatchRepository;
import com.ramiart.admin.tuition.application.TuitionBillingBatchService;
import com.ramiart.admin.tuition.application.TuitionPaymentService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.authentication.TestingAuthenticationToken;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.DisplayName;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TuitionBillingRepositoryIntegrationTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private final DataSource dataSource = POSTGRES.getPostgresDatabase();
    private JdbcTemplate jdbc;
    private TuitionBillingRepository repository;
    private UUID actorId;
    private UUID studentId;
    private UUID billingId;
    private UUID assignmentId;

    @BeforeAll
    void applyCanonicalSchemaAndInsertDisposableBilling() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection=dataSource.getConnection(); Statement statement=connection.createStatement()) {
            createRoleIfMissing(statement,"anon"); createRoleIfMissing(statement,"authenticated");
            statement.execute("drop schema if exists public cascade"); statement.execute("drop schema if exists extensions cascade");
            statement.execute("create schema public"); statement.execute("create schema extensions");
        }
        Path root=projectRoot();
        try (var migrations=Files.list(root.resolve("supabase/migrations"))) {
            for (Path migration:migrations.filter(path -> path.toString().endsWith(".sql")).sorted().toList())
                PostgresScriptRunner.execute(dataSource,migration);
        }
        PostgresScriptRunner.execute(dataSource,root.resolve("supabase/seed.sql"));
        actorId=jdbc.queryForObject("select id from admin_user order by email limit 1",UUID.class);
        studentId=UUID.randomUUID(); billingId=UUID.randomUUID();
        UUID policyId=UUID.randomUUID(),itemId=UUID.randomUUID(),batchId=UUID.randomUUID();
        assignmentId=UUID.randomUUID();
        LocalDate today=LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        jdbc.update("insert into student(id,student_name,student_name_search,joined_at,status,created_by,updated_by) values(?,?,?,?,'ACTIVE',?,?)",
                studentId,"통합 청구 원생","통합청구원생",today.minusYears(8),actorId,actorId);
        jdbc.update("insert into tuition_policy(id,year,revision,status,default_due_day,created_by) values(?, ?,1,'DRAFT',25,?)",
                policyId,today.getYear(),actorId);
        jdbc.update("insert into tuition_policy_item(id,tuition_policy_id,lesson_count_per_week,monthly_amount) values(?,?,2,180000)",itemId,policyId);
        jdbc.update("update tuition_policy set status='PUBLISHED',published_by=?,published_at=statement_timestamp() where id=?",actorId,policyId);
        jdbc.update("insert into student_tuition_assignment(id,student_id,policy_item_id,effective_from,created_by,updated_by) values(?,?,?,?,?,?)",
                assignmentId,studentId,itemId,today.minusMonths(1).withDayOfMonth(1),actorId,actorId);
        jdbc.update("insert into tuition_billing_batch(id,year_month,requested_count,created_count,existing_count,failed_count,created_amount,status,requested_by,completed_at,idempotency_scope,idempotency_key,request_hash) values(?,?,1,1,0,0,180000,'COMPLETED',?,statement_timestamp(),?,?,repeat('a',64))",
                batchId,today.minusMonths(1).withDayOfMonth(1).toString().substring(0,7),actorId,"integration-test:"+actorId,UUID.randomUUID());
        String billingMonth=today.minusMonths(1).toString().substring(0,7);
        LocalDate dueDate=today.minusMonths(1).withDayOfMonth(25);
        jdbc.update("""
                insert into tuition_billing(id,billing_batch_id,student_id,year_month,tuition_assignment_id,policy_item_id,
                    billed_amount,adjustment_amount,paid_amount,refunded_amount,due_date,payment_status,issued_by,
                    assignment_version,override_amount_snapshot,override_reason_snapshot,version)
                values(?,?,?,?,?,?,180000,-10000,50000,0,?,'PARTIALLY_PAID',?,0,null,null,3)
                """,billingId,batchId,studentId,billingMonth,assignmentId,itemId,dueDate,actorId);
        jdbc.update("update student_tuition_assignment set version=7 where id=?",assignmentId);
        repository=new JdbcTuitionBillingRepository(JdbcClient.create(dataSource));
    }

    @AfterAll void stopPostgres() throws IOException { POSTGRES.close(); }

    @Test @DisplayName("MGT-TUITION-BILLING-GENERATE-T015/T017 overdue filter and month summary")
    void overdueFilterAndSummaryUseAdjustmentAndRefundAwareBalance() {
        LocalDate today=LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        var page=repository.list(null,true,List.of("OVERDUE"),today.minusMonths(24).withDayOfMonth(1),today,today,20,0);
        var summary=repository.summarize(null,true,List.of("OVERDUE"),today.minusMonths(24).withDayOfMonth(1),today,today);
        var count=repository.count(null,true,List.of("OVERDUE"),today.minusMonths(24).withDayOfMonth(1),today,today);
        assertThat(page).hasSize(1);
        assertThat(page.getFirst().billingId()).isEqualTo(billingId);
        assertThat(page.getFirst().balance()).isEqualTo(120000);
        assertThat(summary).containsEntry("count",1L).containsEntry("chargeAmount",170000L)
                .containsEntry("netPaidAmount",50000L).containsEntry("balance",120000L);
        assertThat(count).isEqualTo(1);
    }

    @Test @DisplayName("MGT-TUITION-BILLING-GENERATE-T018 billing detail preserves issue snapshots")
    void billingDetailReturnsIssuedPolicyAndAssignmentSnapshots() {
        var detail=repository.detail(billingId).orElseThrow();
        assertThat(detail.row().studentId()).isEqualTo(studentId);
        assertThat(detail.row().baseAmount()).isEqualTo(180000);
        assertThat(detail.row().adjustmentAmount()).isEqualTo(-10000);
        assertThat(detail.assignmentId()).isNotNull();
        assertThat(detail.assignmentVersion()).isZero();
        assertThat(detail.issuerId()).isEqualTo(actorId);
        assertThat(detail.batchId()).isNotNull();
    }

    @Test @DisplayName("MGT-TUITION-BILLING-GENERATE-T001 preview uses assignment price and expires in five minutes")
    void previewReturnsRealAssignmentPricingAndAnOpaqueShortLivedVersion() {
        LocalDate today=LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        var protector=new com.ramiart.admin.tuition.infrastructure.AesGcmTuitionPreviewSnapshotProtector("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var service=new TuitionBillingService(repository,protector,java.time.Clock.systemUTC(),"Asia/Seoul");
        var authentication=new TestingAuthenticationToken(actorId.toString(),"","TUITION_BILLING_READ","TUITION_BILLING_WRITE");
        var preview=service.preview(today.toString().substring(0,7),authentication);
        assertThat(preview.candidates()).hasSize(1);
        assertThat(preview.candidates().getFirst()).containsEntry("studentId",studentId)
                .containsEntry("amount",180000L).containsEntry("selectedByDefault",true);
        assertThat(preview.excluded()).isEmpty();
        assertThat(preview.totals()).containsEntry("selectedCount",1).containsEntry("amount",180000L);
        UUID token=UUID.fromString(preview.previewVersion());
        var saved=repository.findPreview(TuitionBillingService.previewScope(actorId.toString(),java.time.YearMonth.from(today)),
                token,preview.expiresAt().minusSeconds(1)).orElseThrow();
        assertThat(saved.requestHash()).hasSize(64);
        assertThat(protector.decrypt(saved.encryptedResponse())).contains(studentId.toString()).doesNotContain("통합 청구 원생");
        assertThat(java.time.Duration.between(saved.expiresAt(),preview.expiresAt()).abs().toMillis()).isLessThan(1L);
        assertThat(repository.findPreview(TuitionBillingService.previewScope(actorId.toString(),java.time.YearMonth.from(today)),
                token,preview.expiresAt())).isEmpty();
        var verified=service.verifyPreviewToken(preview.yearMonth(),token,List.of(studentId),authentication);
        assertThat(verified.get(studentId).amount()).isEqualTo(180000);
        var otherAdmin=new TestingAuthenticationToken(UUID.randomUUID().toString(),"","TUITION_BILLING_WRITE");
        assertThatThrownBy(()->service.verifyPreviewToken(preview.yearMonth(),token,List.of(studentId),otherAdmin))
                .isInstanceOf(TuitionBillingService.BillingException.class).extracting("code").isEqualTo("BILLING_PREVIEW_CHANGED");
        jdbc.update("update student_tuition_assignment set version=8 where id=?",assignmentId);
        assertThatThrownBy(()->service.verifyPreviewToken(preview.yearMonth(),token,List.of(studentId),authentication))
                .isInstanceOf(TuitionBillingService.BillingException.class).extracting("code").isEqualTo("BILLING_PREVIEW_CHANGED");
    }

    @Test @DisplayName("MGT-TUITION-PAYMENT-RECORD-T004/T005 payment create and cancel reconcile billing and ledger")
    void paymentCreateReplayAndCancellationReconcileBillingAndLedger() {
        var paymentRepository=new com.ramiart.admin.tuition.infrastructure.JdbcTuitionPaymentRepository(JdbcClient.create(dataSource));
        var service=new TuitionPaymentService(paymentRepository,
                new JdbcAuditRecorder(JdbcClient.create(dataSource),new com.fasterxml.jackson.databind.ObjectMapper()),
                java.time.Clock.systemUTC());
        var authentication=new TestingAuthenticationToken(actorId.toString(),"","TUITION_PAYMENT_READ","TUITION_PAYMENT_WRITE");
        var metadata=new TuitionPaymentService.Metadata("payment-it","127.0.0.1","test");
        var paymentTransactions=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        var initial=service.list(billingId,null,20,authentication);
        assertThat((List<?>)initial.get("payments")).isEmpty();
        assertThat(((Map<?,?>)initial.get("billing")).get("balance")).isEqualTo(120000L);
        var key=UUID.randomUUID();
        var request=new TuitionPaymentService.CreateRequest(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")),1000,"CASH",null,3);
        var created=paymentTransactions.execute(status->service.create(billingId,request,key,metadata,authentication));
        var payment=(Map<?,?>)created.get("payment");
        UUID paymentId=(UUID)payment.get("paymentId");
        assertThat(payment.get("status")).isEqualTo("CONFIRMED");
        assertThat(((Map<?,?>)created.get("billing")).get("version")).isEqualTo(4L);
        assertThat(jdbc.queryForObject("select paid_amount from tuition_billing where id=?",Long.class,billingId)).isEqualTo(51000L);
        assertThat(jdbc.queryForObject("select count(*) from financial_entry where source_type='TUITION_PAYMENT' and source_id=? and status='CONFIRMED'",Integer.class,paymentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select a.type from financial_entry e join finance_account a on a.id=e.account_id where e.source_id=?",String.class,paymentId)).isEqualTo("CASH");
        assertThat(((List<?>)service.list(billingId,null,20,authentication).get("payments"))).hasSize(1);
        var replay=paymentTransactions.execute(status->service.create(billingId,request,key,metadata,authentication));
        assertThat(((Map<?,?>)replay.get("payment")).get("paymentId")).isEqualTo(paymentId);
        assertThat(jdbc.queryForObject("select count(*) from tuition_payment where id=?",Integer.class,paymentId)).isEqualTo(1);
        var cancelled=paymentTransactions.execute(status->service.cancel(paymentId,new TuitionPaymentService.CancelRequest("중복 등록 정정",0,4),UUID.randomUUID(),metadata,authentication));
        assertThat(((Map<?,?>)cancelled.get("payment")).get("status")).isEqualTo("CANCELLED");
        assertThat(((Map<?,?>)cancelled.get("billing")).get("version")).isEqualTo(5L);
        assertThat(jdbc.queryForObject("select paid_amount from tuition_billing where id=?",Long.class,billingId)).isEqualTo(50000L);
        assertThat(jdbc.queryForObject("select status from financial_entry where source_id=?",String.class,paymentId)).isEqualTo("CANCELLED");
    }

    @Test @DisplayName("MGT-TUITION-POLICY creates, saves, publishes, and replays an isolated policy revision")
    void tuitionPolicyDraftAndPublicationAreAtomicAndIdempotent() {
        var policies=new JdbcTuitionPolicyRepository(JdbcClient.create(dataSource));
        var service=new com.ramiart.admin.tuition.application.TuitionPolicyService(policies,
                new JdbcAuditRecorder(JdbcClient.create(dataSource),new com.fasterxml.jackson.databind.ObjectMapper()),java.time.Clock.systemUTC());
        var metadata=new com.ramiart.admin.tuition.application.TuitionPolicyService.Metadata("policy-it","127.0.0.1","test");
        int year=java.time.Year.now().getValue()+1;
        UUID createKey=UUID.randomUUID();
        var draft=service.create(year,actorId,createKey,metadata);
        assertThat(service.create(year,actorId,createKey,metadata).id()).isEqualTo(draft.id());
        var items=java.util.stream.IntStream.rangeClosed(1,7).mapToObj(count->new com.ramiart.admin.tuition.application.TuitionPolicyModels.Item(UUID.randomUUID(),count,count*10000L)).toList();
        var saved=service.save(year,draft.id(),new com.ramiart.admin.tuition.application.TuitionPolicyModels.Write(draft.version(),25,items),actorId,UUID.randomUUID(),metadata);
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.validation().publishable()).isTrue();
        UUID publishKey=UUID.randomUUID();
        var published=service.publish(year,draft.id(),saved.version(),actorId,publishKey,metadata);
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(service.publish(year,draft.id(),saved.version(),actorId,publishKey,metadata).id()).isEqualTo(draft.id());
        assertThat(jdbc.queryForObject("select count(*) from tuition_policy_item where tuition_policy_id=?",Integer.class,draft.id())).isEqualTo(7);
    }

    @Test @DisplayName("MGT-STUDENT-TUITION-ASSIGN exposes published candidates and creates an idempotent assignment")
    void studentTuitionAssignmentUsesActiveSchedulesAndPublishedPolicies() {
        var assignments=new JdbcStudentTuitionAssignmentRepository(JdbcClient.create(dataSource));
        var service=new com.ramiart.admin.tuition.application.StudentTuitionAssignmentService(assignments,
                new JdbcAuditRecorder(JdbcClient.create(dataSource),new com.fasterxml.jackson.databind.ObjectMapper()),java.time.Clock.systemUTC());
        var auth=new org.springframework.security.authentication.TestingAuthenticationToken(actorId.toString(),"","STUDENT_READ","TUITION_POLICY_READ","STUDENT_TUITION_WRITE");
        var metadata=new com.ramiart.admin.tuition.application.StudentTuitionAssignmentModels.Metadata("student-tuition-it","127.0.0.1","test");
        int year=java.time.Year.now().getValue()+2;LocalDate start=LocalDate.of(year,1,1);UUID target=UUID.randomUUID();
        jdbc.update("insert into student(id,student_name,student_name_search,joined_at,status,created_by,updated_by) values(?,?,?,?,'ACTIVE',?,?)",target,"배정 통합 원생","배정통합원생",LocalDate.now().minusYears(8),actorId,actorId);
        UUID course=UUID.randomUUID(),group=UUID.randomUUID();String suffix=UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase();
        int order=jdbc.queryForObject("select coalesce(max(display_order),-1)+1 from course",Integer.class);
        jdbc.update("insert into course(id,code,name,display_order,created_by,updated_by) values(?,?,?, ?,?,?)",course,"TU"+suffix,"배정 통합 과정",order,actorId,actorId);
        jdbc.update("insert into class_group(id,course_id,code,name,room_code,capacity,makeup_valid_days,starts_on,status,created_by,updated_by) values(?,?,?,?,?,10,0,?,'ACTIVE',?,?)",group,course,"GR"+suffix,"배정 통합 반","R1",LocalDate.now().minusYears(3),actorId,actorId);
        for(int n=0;n<2;n++){UUID slot=UUID.randomUUID();jdbc.update("insert into schedule_slot(id,class_group_id,status,created_by) values(?,?,'ACTIVE',?)",slot,group,actorId);jdbc.update("insert into student_schedule_assignment(id,student_id,schedule_slot_id,effective_from,created_by,updated_by) values(?,?,?,?,?,?)",UUID.randomUUID(),target,slot,start,actorId,actorId);}
        UUID policy=UUID.randomUUID(),item=UUID.randomUUID(),wrongCountItem=UUID.randomUUID();jdbc.update("insert into tuition_policy(id,year,revision,status,default_due_day,created_by) values(?,?,1,'DRAFT',25,?)",policy,year,actorId);jdbc.update("insert into tuition_policy_item(id,tuition_policy_id,lesson_count_per_week,monthly_amount) values(?,?,2,220000)",item,policy);jdbc.update("insert into tuition_policy_item(id,tuition_policy_id,lesson_count_per_week,monthly_amount) values(?,?,3,300000)",wrongCountItem,policy);jdbc.update("update tuition_policy set status='PUBLISHED',published_by=?,published_at=statement_timestamp() where id=?",actorId,policy);
        var candidate=service.candidates(target,start,auth);assertThat(candidate.lessonCountPerWeek()).isEqualTo(2);assertThat(candidate.candidates()).hasSize(1);
        UUID key=UUID.randomUUID();var write=new com.ramiart.admin.tuition.application.StudentTuitionAssignmentModels.Write(item,start,null,null,null);var created=service.create(target,write,actorId,key,metadata);
        assertThat(created.status()).isEqualTo(201);assertThat(created.data()).containsEntry("effectiveAmount",220000L).containsEntry("status","SCHEDULED");
        var replay=service.create(target,write,actorId,key,metadata);assertThat(replay.status()).isEqualTo(200);assertThat(replay.data().get("assignmentId")).isEqualTo(created.data().get("assignmentId"));
        assertThatThrownBy(()->service.create(target,write,actorId,UUID.randomUUID(),metadata))
                .isInstanceOf(com.ramiart.admin.tuition.application.StudentTuitionAssignmentException.class).extracting("code").isEqualTo("STUDENT_TUITION_PERIOD_CONFLICT");
        assertThatThrownBy(()->service.create(target,new com.ramiart.admin.tuition.application.StudentTuitionAssignmentModels.Write(wrongCountItem,start,null,null,null),actorId,UUID.randomUUID(),metadata))
                .isInstanceOf(com.ramiart.admin.tuition.application.StudentTuitionAssignmentException.class).extracting("code").isEqualTo("TUITION_LESSON_COUNT_MISMATCH");
        UUID assignment=(UUID)created.data().get("assignmentId");assertThatThrownBy(()->service.update(assignment,new com.ramiart.admin.tuition.application.StudentTuitionAssignmentModels.Update(start.minusDays(1),null,null,null,0),actorId,UUID.randomUUID(),metadata)).isInstanceOf(com.ramiart.admin.tuition.application.StudentTuitionAssignmentException.class).extracting("code").isEqualTo("TUITION_LESSON_COUNT_MISMATCH");jdbc.update("update student set status='PAUSED' where id=?",target);
        var ended=service.update(assignment,new com.ramiart.admin.tuition.application.StudentTuitionAssignmentModels.Update(start,start.plusDays(20),null,null,0),actorId,UUID.randomUUID(),metadata);
        assertThat(ended).containsEntry("effectiveTo",start.plusDays(20));
        assertThatThrownBy(()->service.update(assignment,new com.ramiart.admin.tuition.application.StudentTuitionAssignmentModels.Update(start,start.plusDays(20),0L,"무상 조정 사유",1),actorId,UUID.randomUUID(),metadata))
                .isInstanceOf(com.ramiart.admin.tuition.application.StudentTuitionAssignmentException.class).extracting("code").isEqualTo("STUDENT_STATUS_NOT_ASSIGNABLE");
    }

    @Test @DisplayName("MGT-TUITION-ADJUSTMENT adjustment, refund, and cancellations reconcile immutable history")
    void adjustmentAndRefundReconcileBillingAndFinancialLedger() {
        var adjustmentRepository=new com.ramiart.admin.tuition.infrastructure.JdbcTuitionAdjustmentRepository(JdbcClient.create(dataSource));
        var service=new com.ramiart.admin.tuition.application.TuitionAdjustmentService(adjustmentRepository,
                new JdbcAuditRecorder(JdbcClient.create(dataSource),new com.fasterxml.jackson.databind.ObjectMapper()),java.time.Clock.systemUTC());
        var authentication=new TestingAuthenticationToken(actorId.toString(),"","TUITION_ADJUSTMENT_READ","TUITION_ADJUSTMENT_WRITE","TUITION_REFUND_WRITE");
        var metadata=new com.ramiart.admin.tuition.application.TuitionAdjustmentService.Metadata("adjustment-it","127.0.0.1","integration-test");
        var transactions=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        long version=jdbc.queryForObject("select version from tuition_billing where id=?",Long.class,billingId);
        var adjustment=transactions.execute(status->service.adjust(billingId,
                new com.ramiart.admin.tuition.application.TuitionAdjustmentService.AdjustmentRequest("CORRECTION",-130000,"통합시험 조정",version),
                UUID.randomUUID(),metadata,authentication));
        UUID adjustmentId=(UUID)((Map<?,?>)((List<?>)adjustment.get("adjustments")).getFirst()).get("adjustmentId");
        assertThat(((Map<?,?>)adjustment.get("billing")).get("refundableAmount")).isEqualTo(10000L);
        long refundBillingVersion=jdbc.queryForObject("select version from tuition_billing where id=?",Long.class,billingId);
        var refunded=transactions.execute(status->service.refund(billingId,
                new com.ramiart.admin.tuition.application.TuitionAdjustmentService.RefundRequest(1000,LocalDate.now(),"CASH",null,null,"통합시험 환불",refundBillingVersion),
                UUID.randomUUID(),metadata,authentication));
        UUID refundId=(UUID)((Map<?,?>)((List<?>)refunded.get("refunds")).getFirst()).get("refundId");
        UUID entryId=jdbc.queryForObject("select financial_entry_id from tuition_refund where id=?",UUID.class,refundId);
        assertThat(jdbc.queryForObject("select source_type from financial_entry where id=?",String.class,entryId)).isEqualTo("TUITION_REFUND");
        long versionAfterRefund=jdbc.queryForObject("select version from tuition_billing where id=?",Long.class,billingId);
        transactions.executeWithoutResult(status->service.cancelRefund(refundId,
                new com.ramiart.admin.tuition.application.TuitionAdjustmentService.CancelRequest("통합시험 환불 취소",0,versionAfterRefund),
                UUID.randomUUID(),metadata,authentication));
        long versionBeforeAdjustmentCancel=jdbc.queryForObject("select version from tuition_billing where id=?",Long.class,billingId);
        transactions.executeWithoutResult(status->service.cancelAdjustment(adjustmentId,
                new com.ramiart.admin.tuition.application.TuitionAdjustmentService.CancelRequest("통합시험 조정 취소",0,versionBeforeAdjustmentCancel),
                UUID.randomUUID(),metadata,authentication));
        assertThat(jdbc.queryForObject("select adjustment_amount from tuition_billing where id=?",Long.class,billingId)).isEqualTo(-10000L);
        assertThat(jdbc.queryForObject("select refunded_amount from tuition_billing where id=?",Long.class,billingId)).isZero();
        assertThat(jdbc.queryForObject("select status from financial_entry where id=?",String.class,entryId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from billing_adjustment where id=?",String.class,adjustmentId)).isEqualTo("CANCELLED");
    }

    @Test @DisplayName("MGT-TUITION-RECEIPT issue, reissue, hash validation, and payment cancellation preserve versions")
    void receiptVersionsUsePaymentSnapshotsAndVoidOnPaymentCancellation() {
        var objectStorage=new java.util.concurrent.ConcurrentHashMap<String,byte[]>();
        var storage=new com.ramiart.admin.tuition.application.TuitionReceiptStorage(){
            public void upload(String key,byte[] pdf){objectStorage.put(key,pdf.clone());}
            public byte[] download(String key){return objectStorage.get(key).clone();}
            public String signedUrl(String key,int seconds){return "https://storage.test/"+key+"?expiresIn="+seconds;}
            public void delete(String key){objectStorage.remove(key);}
        };
        var receiptMapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var receipts=new JdbcTuitionReceiptRepository(JdbcClient.create(dataSource),receiptMapper);
        var audit=new JdbcAuditRecorder(JdbcClient.create(dataSource),new com.fasterxml.jackson.databind.ObjectMapper());
        var service=new com.ramiart.admin.tuition.application.TuitionReceiptService(receipts,storage,
                new com.ramiart.admin.tuition.infrastructure.TuitionReceiptPdfGenerator(),audit,java.time.Clock.systemUTC(),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        var paymentRepository=new com.ramiart.admin.tuition.infrastructure.JdbcTuitionPaymentRepository(JdbcClient.create(dataSource));
        var paymentService=new TuitionPaymentService(paymentRepository,audit,java.time.Clock.systemUTC());
        var auth=new TestingAuthenticationToken(actorId.toString(),"","TUITION_PAYMENT_WRITE","TUITION_RECEIPT_READ","TUITION_RECEIPT_ISSUE");
        var paymentTx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        var metadata=new com.ramiart.admin.tuition.application.TuitionReceiptService.Metadata("receipt-it","127.0.0.1","integration-test");
        long billingVersion=jdbc.queryForObject("select version from tuition_billing where id=?",Long.class,billingId);
        var created=paymentTx.execute(status->paymentService.create(billingId,new TuitionPaymentService.CreateRequest(LocalDate.now(),1000,"CASH",null,billingVersion),
                UUID.randomUUID(),new TuitionPaymentService.Metadata("receipt-payment-it","127.0.0.1","integration-test"),auth));
        UUID paymentId=(UUID)((Map<?,?>)created.get("payment")).get("paymentId");
        var issueKey=UUID.randomUUID();
        var issueRequest=new com.ramiart.admin.tuition.application.TuitionReceiptService.IssueRequest(0L,billingVersion+1);
        var first=service.issue(paymentId,issueRequest,issueKey,metadata,
                new TestingAuthenticationToken(actorId.toString(),"","TUITION_RECEIPT_READ","TUITION_RECEIPT_ISSUE"));
        assertThat(first).containsEntry("receiptNumber",first.get("receiptNumber")).containsEntry("currentVersion",1);
        var replay=service.issue(paymentId,issueRequest,issueKey,metadata,new TestingAuthenticationToken(actorId.toString(),"","TUITION_RECEIPT_READ","TUITION_RECEIPT_ISSUE"));
        assertThat(replay.get("receiptId")).isEqualTo(first.get("receiptId"));
        assertThat(jdbc.queryForObject("select count(*) from tuition_receipt where payment_id=?",Integer.class,paymentId)).isOne();
        UUID receiptId=(UUID)first.get("receiptId");
        Map<?,?> firstVersion=(Map<?,?>)((List<?>)first.get("versions")).getFirst();
        assertThat(firstVersion.get("status")).isEqualTo("READY");
        assertThat(firstVersion.get("downloadable")).isEqualTo(true);
        String firstKey=jdbc.queryForObject("select storage_key from tuition_receipt_version where receipt_id=? and version=1",String.class,receiptId);
        assertThat(objectStorage.get(firstKey)).startsWith("%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] originalPdf=objectStorage.get(firstKey).clone();objectStorage.put(firstKey,new byte[]{1,2,3});
        assertThatThrownBy(()->service.downloadUrl(receiptId,1,metadata,new TestingAuthenticationToken(actorId.toString(),"","TUITION_RECEIPT_READ")))
                .isInstanceOf(JdbcTuitionReceiptRepository.TuitionReceiptException.class).extracting("code").isEqualTo("RECEIPT_FILE_INTEGRITY_FAILED");
        objectStorage.put(firstKey,originalPdf);
        var url=service.downloadUrl(receiptId,1,metadata,new TestingAuthenticationToken(actorId.toString(),"","TUITION_RECEIPT_READ"));
        assertThat(url.get("url")).isEqualTo("https://storage.test/"+firstKey+"?expiresIn=60");
        long reissueVersion=((Number)first.get("currentVersion")).longValue();
        var second=service.reissue(receiptId,new com.ramiart.admin.tuition.application.TuitionReceiptService.ReissueRequest((int)reissueVersion,"통합시험 재발행"),
                UUID.randomUUID(),metadata,new TestingAuthenticationToken(actorId.toString(),"","TUITION_RECEIPT_READ","TUITION_RECEIPT_ISSUE"));
        assertThat(second.get("currentVersion")).isEqualTo(2);
        assertThat((List<?>)second.get("versions")).hasSize(2);
        assertThat(jdbc.queryForObject("select snapshot->>'maskedStudentName' from tuition_receipt_version where receipt_id=? and version=1",String.class,receiptId))
                .isEqualTo("통*******");
        long currentBillingVersion=jdbc.queryForObject("select version from tuition_billing where id=?",Long.class,billingId);
        paymentTx.executeWithoutResult(status->paymentService.cancel(paymentId,new TuitionPaymentService.CancelRequest("통합시험 납입 취소",0,currentBillingVersion),
                UUID.randomUUID(),new TuitionPaymentService.Metadata("receipt-payment-cancel-it","127.0.0.1","integration-test"),auth));
        assertThat(jdbc.queryForObject("select status from tuition_receipt where id=?",String.class,receiptId)).isEqualTo("VOID");
    }

    @Test @DisplayName("MGT-TUITION-BILLING-GENERATE-T005/T009/T010 batch issuance and idempotent replay")
    void selectedBatchIssuesOnceAndPersistsAuditAndReplayResult() {
        var protector=new com.ramiart.admin.tuition.infrastructure.AesGcmTuitionPreviewSnapshotProtector("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        var billingService=new TuitionBillingService(repository,protector,java.time.Clock.systemUTC(),"Asia/Seoul");
        TuitionBillingBatchRepository batchRepository=new JdbcTuitionBillingBatchRepository(JdbcClient.create(dataSource),
                new JdbcAuditRecorder(JdbcClient.create(dataSource),new com.fasterxml.jackson.databind.ObjectMapper()));
        var transactions=new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        var batchService=new TuitionBillingBatchService(batchRepository,billingService,transactions,java.time.Clock.systemUTC(),"Asia/Seoul");
        var authentication=new TestingAuthenticationToken(actorId.toString(),"","TUITION_BILLING_READ","TUITION_BILLING_WRITE");
        LocalDate today=LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        String yearMonth=java.time.YearMonth.from(today).plusMonths(1).toString();
        var preview=billingService.preview(yearMonth,authentication);
        UUID key=UUID.randomUUID();
        var request=new TuitionBillingBatchService.Request(yearMonth,List.of(studentId),preview.previewVersion());
        var metadata=new TuitionBillingBatchService.RequestMetadata("req_billing_batch_test","127.0.0.1","integration-test");
        var issued=batchService.issue(request,key,metadata,authentication);
        assertThat(issued).containsEntry("status","COMPLETED");
        var totals=(Map<?,?>)issued.get("totals");
        assertThat(totals.get("created")).isEqualTo(1);
        assertThat(totals.get("failed")).isEqualTo(0);
        assertThat(totals.get("createdAmount")).isEqualTo(180000L);
        assertThat(jdbc.queryForObject("select count(*) from tuition_billing where student_id=? and year_month=?",Integer.class,studentId,yearMonth)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='TUITION_BILLING_ISSUED'",Integer.class)).isEqualTo(1);
        var replay=batchService.issue(request,key,metadata,authentication);
        assertThat(replay).isEqualTo(issued);
        assertThatThrownBy(()->batchService.issue(new TuitionBillingBatchService.Request(yearMonth,List.of(UUID.randomUUID()),preview.previewVersion()),
                key,metadata,authentication)).isInstanceOf(TuitionBillingService.BillingException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    private static Path projectRoot() {
        Path current=Path.of("").toAbsolutePath().normalize();
        while(current!=null && !Files.isDirectory(current.resolve("supabase/migrations"))) current=current.getParent();
        if(current==null) throw new IllegalStateException("supabase migrations directory not found");
        return current;
    }
    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().start(); }
        catch(IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    private static void createRoleIfMissing(Statement statement,String role) throws SQLException {
        try(var rows=statement.executeQuery("select exists(select 1 from pg_roles where rolname='"+role+"')")) {
            rows.next(); if(!rows.getBoolean(1)) statement.execute("create role "+role);
        }
    }
}

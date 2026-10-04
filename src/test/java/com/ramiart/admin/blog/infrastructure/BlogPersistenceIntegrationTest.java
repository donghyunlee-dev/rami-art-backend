package com.ramiart.admin.blog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ramiart.admin.auth.infrastructure.JdbcAuditRecorder;
import com.ramiart.admin.blog.application.BlogContentSanitizer;
import com.ramiart.admin.blog.application.BlogException;
import com.ramiart.admin.blog.application.BlogModels.Publish;
import com.ramiart.admin.blog.application.BlogModels.Write;
import com.ramiart.admin.blog.application.BlogService;
import com.ramiart.admin.dev.PostgresScriptRunner;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes=BlogPersistenceIntegrationTest.TestConfiguration.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlogPersistenceIntegrationTest {
    private static final EmbeddedPostgres POSTGRES=start();
    @Autowired DataSource dataSource;
    @Autowired BlogService service;
    JdbcTemplate jdbc;

    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @Import({BlogService.class,BlogContentSanitizer.class,JdbcBlogRepository.class,JdbcAuditRecorder.class})
    static class TestConfiguration { @Bean Clock clock() { return Clock.systemUTC(); } }

    @DynamicPropertySource static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",BlogPersistenceIntegrationTest::jdbcUrl);
        registry.add("spring.datasource.username",()->"postgres"); registry.add("spring.datasource.password",()->"postgres");
    }

    @BeforeEach void reset() throws Exception {
        jdbc=new JdbcTemplate(dataSource);
        try(Connection c=dataSource.getConnection();Statement s=c.createStatement()) {
            role(s,"anon"); role(s,"authenticated"); s.execute("drop schema if exists public cascade");
            s.execute("drop schema if exists extensions cascade"); s.execute("create schema public"); s.execute("create schema extensions");
        }
        try(var migrations=Files.list(projectRoot().resolve("supabase/migrations"))) {
            for(Path p:migrations.filter(p->p.toString().endsWith(".sql")).sorted().toList()) PostgresScriptRunner.execute(dataSource,p);
        }
        PostgresScriptRunner.execute(dataSource,projectRoot().resolve("supabase/seed.sql"));
    }
    @AfterAll void close() throws IOException { POSTGRES.close(); }

    @Test void createSavePublishAndPublicReadAreAtomicIdempotentAndCanonical() {
        UUID actor=jdbc.queryForObject("select id from admin_user order by created_at limit 1",UUID.class);
        UUID media=jdbc.queryForObject("select id from media_asset where storage_key='seed/generic-brand-logo.png'",UUID.class);
        UUID key=UUID.randomUUID();
        var created=service.create(new Write(" 여름 이야기 ","요약","CLASS_STORY","<p><strong>본문</strong></p>",media,"작품",true),actor,key,metadata());
        assertThat(service.create(new Write(" 여름 이야기 ","요약","CLASS_STORY","<p><strong>본문</strong></p>",media,"작품",true),actor,key,metadata()))
                .isEqualTo(created);
        assertThat(jdbc.queryForObject("select content from blog_post where id=?",String.class,created.draftId()))
                .isEqualTo("<p><strong>본문</strong></p>");
        assertThat(jdbc.queryForObject("select reference_state from media_asset_reference where owner_id=?",String.class,created.draftId()))
                .isEqualTo("DRAFT");
        service.publish(created.postId(),new Publish(created.draftId(),created.version()),actor,UUID.randomUUID(),metadata());
        assertThat(jdbc.queryForObject("select status from blog_post where id=?",String.class,created.draftId())).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("select reference_state from media_asset_reference where owner_id=?",String.class,created.draftId()))
                .isEqualTo("PUBLISHED");
        var publicPosts=service.publicList(null,null,1,12);
        assertThat(publicPosts.items()).singleElement().satisfies(item -> {
            assertThat(item.content()).isEqualTo("<p><strong>본문</strong></p>");
            assertThat(item.title()).isEqualTo("여름 이야기");
        });
        assertThat(jdbc.queryForObject("select count(*) from audit_log where target_type='BLOG_POST'",Integer.class)).isEqualTo(2);
    }

    @Test void rejectsUnsafeRichTextAndWrongVersionWithoutChangingDraft() {
        UUID actor=jdbc.queryForObject("select id from admin_user order by created_at limit 1",UUID.class);
        var created=service.create(new Write("제목",null,"CLASS_STORY","<p>ok</p>",null,null,false),actor,UUID.randomUUID(),metadata());
        assertThatThrownBy(()->service.save(created.postId(),created.draftId(),
                new com.ramiart.admin.blog.application.BlogModels.Save(0,"제목",null,"CLASS_STORY","<img src=\"data:x\">",null,null,false),
                actor,UUID.randomUUID(),metadata())).isInstanceOf(BlogException.class).hasMessage("BLOG_CONTENT_INVALID");
        assertThatThrownBy(()->service.publish(created.postId(),new Publish(created.draftId(),1),actor,UUID.randomUUID(),metadata()))
                .isInstanceOf(BlogException.class).hasMessage("BLOG_DRAFT_VERSION_CONFLICT");
        assertThat(jdbc.queryForObject("select status from blog_post where id=?",String.class,created.draftId())).isEqualTo("DRAFT");
    }

    private static BlogService.RequestMetadata metadata() { return new BlogService.RequestMetadata("req_blog_test","127.0.0.1","integration"); }
    private static void role(Statement statement,String name) throws Exception { statement.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='"+name+"') then create role "+name+"; end if; end $$"); }
    private static EmbeddedPostgres start() { try{return EmbeddedPostgres.builder().start();}catch(IOException e){throw new ExceptionInInitializerError(e);} }
    private static String jdbcUrl() { return POSTGRES.getJdbcUrl("postgres","postgres"); }
    private static Path projectRoot() { Path p=Path.of("").toAbsolutePath(); while(p!=null&&!Files.isDirectory(p.resolve("supabase/migrations")))p=p.getParent(); if(p==null)throw new IllegalStateException("project root not found"); return p; }
}

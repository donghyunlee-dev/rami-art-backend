set search_path = public, extensions;

create table public.public_privacy_policy (
  id uuid primary key,
  revision integer not null,
  status varchar(20) not null default 'DRAFT',
  based_on_policy_id uuid references public.public_privacy_policy(id) on update restrict on delete restrict,
  version_code varchar(30) not null,
  title varchar(200) not null,
  collection_items text not null,
  purpose text not null,
  retention_months integer not null,
  retention_anchor varchar(30) not null,
  contact_email varchar(254) not null,
  effective_on date not null,
  created_by uuid references public.admin_user(id) on update restrict on delete restrict,
  published_by uuid references public.admin_user(id) on update restrict on delete restrict,
  published_at timestamptz,
  version bigint not null default 0,
  created_at timestamptz not null default statement_timestamp(),
  updated_at timestamptz not null default statement_timestamp(),
  constraint uq_public_privacy_policy_revision unique(revision),
  constraint uq_public_privacy_policy_version unique(version_code),
  constraint ck_public_privacy_policy_revision check(revision >= 1),
  constraint ck_public_privacy_policy_status check(status in ('DRAFT','PUBLISHED','ARCHIVED')),
  constraint ck_public_privacy_policy_lengths check(
    char_length(btrim(version_code)) between 1 and 30
    and char_length(btrim(title)) between 1 and 200
    and char_length(btrim(collection_items)) between 1 and 4000
    and char_length(btrim(purpose)) between 1 and 4000
    and retention_months between 1 and 120
    and char_length(btrim(contact_email)) between 3 and 254
  ),
  constraint ck_public_privacy_policy_anchor check(retention_anchor in ('RECEIVED','CONSULTATION_COMPLETED')),
  constraint ck_public_privacy_policy_version check(version >= 0),
  constraint ck_public_privacy_policy_publication check(
    (status='DRAFT' and published_by is null and published_at is null)
    or (status in ('PUBLISHED','ARCHIVED') and published_at is not null)
  )
);

create unique index uq_public_privacy_policy_one_draft
  on public.public_privacy_policy(status) where status='DRAFT';
create unique index uq_public_privacy_policy_one_published
  on public.public_privacy_policy(status) where status='PUBLISHED';
create index ix_public_privacy_policy_status_revision
  on public.public_privacy_policy(status,revision desc);
create trigger tr_public_privacy_policy_updated_at
  before update on public.public_privacy_policy
  for each row execute function public.set_updated_at();

insert into public.public_privacy_policy(
  id,revision,status,version_code,title,collection_items,purpose,retention_months,
  retention_anchor,contact_email,effective_on,published_at
) values (
  '00000000-0000-4000-8000-000000000027',1,'PUBLISHED','privacy-2026-07','개인정보처리 안내',
  '이름, 연락처, 관심 수업, 문의 내용',
  '수업 상담, 문의 답변 및 방문 안내',
  6,'CONSULTATION_COMPLETED','school579@naver.com','2026-07-01',statement_timestamp()
);

alter table public.inquiry
  add column privacy_policy_revision_id uuid references public.public_privacy_policy(id) on update restrict on delete restrict;

update public.inquiry i
   set privacy_policy_revision_id = p.id
  from public.public_privacy_policy p
 where p.version_code = i.consent_policy_version;

revoke all on table public.public_privacy_policy from public,anon,authenticated;
grant select,insert,update on public.public_privacy_policy to rami_backend;
alter table public.public_privacy_policy enable row level security;
create policy public_privacy_policy_backend_all on public.public_privacy_policy
  for all to rami_backend using (true) with check (true);

reset search_path;

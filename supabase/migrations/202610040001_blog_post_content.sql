alter table public.blog_post add column content text;

alter table public.blog_post
  add constraint ck_blog_post_content_length
  check (content is null or char_length(content) <= 100000);

comment on column public.blog_post.content is
  'Canonical allowlisted HTML rich-text content, limited to 100000 Unicode code points by the application.';

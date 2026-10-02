create extension if not exists pgcrypto with schema extensions;
create or replace function public.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at = statement_timestamp();
  return new;
end;
$$;
comment on function public.set_updated_at() is
  'Keeps mutable aggregate updated_at values on the database clock.';

drop policy if exists admin_auth_rate_limit_backend_all
    on rami_art_studio.admin_auth_rate_limit;
create policy admin_auth_rate_limit_backend_all
    on rami_art_studio.admin_auth_rate_limit
    for all to rami_backend
    using (true)
    with check (true);

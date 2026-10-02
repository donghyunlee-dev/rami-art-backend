set search_path = public, extensions;
alter table public.data_transfer_row
  drop constraint ck_data_transfer_row_shape;
alter table public.data_transfer_row
  add constraint ck_data_transfer_row_shape check (
    (status='VALID'
      and jsonb_array_length(field_errors)=0
      and duplicate_target_id is null
      and result_target_id is null
      and error_code is null
      and confirmed_at is null)
    or
    (status='INVALID'
      and jsonb_array_length(field_errors)>0
      and duplicate_target_id is null
      and result_target_id is null
      and error_code is null
      and confirmed_at is null)
    or
    (status='DUPLICATE'
      and duplicate_target_id is not null
      and result_target_id is null
      and error_code is null
      and confirmed_at is null)
    or
    (status='CONFIRMED'
      and result_target_id is not null
      and error_code is null
      and confirmed_at is not null)
    or
    (status='FAILED'
      and result_target_id is null
      and error_code is not null
      and confirmed_at is null)
  );
reset search_path;
